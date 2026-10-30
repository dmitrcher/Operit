package com.ai.assistance.operit.api.speech

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import com.ai.assistance.operit.R
import com.ai.assistance.operit.util.AppLogger
import java.io.File
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineTransducerModelConfig
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.log10
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 基于 sherpa-onnx 非流式（offline）NeMo transducer 模型的本地语音识别。
 * 模型文件由 [SttModelDownloadManager] 下载到应用内部存储。
 *
 * 用 Silero VAD 把麦克风音频切成短语：短语结束后整段解码得到最终结果；说话过程中每隔
 * [PARTIAL_INTERVAL_SAMPLES] 对已收到的音频重新解码一次作为中间结果。
 */
@SuppressLint("MissingPermission")
class SherpaOnnxOfflineSpeechProvider(
    private val context: Context,
    private val model: SherpaOnnxSttModel,
) : SpeechService {

    companion object {
        private const val TAG = "SherpaOnnxOfflineStt"
        private const val SAMPLE_RATE = 16000
        private const val VAD_FRAME_SIZE = 512
        // 句中停顿短于该时长不切分，否则会在半句话处截断
        private const val VAD_SILENCE_MS = 800
        // 语音起点前保留的帧数（每帧 32ms），避免 VAD 确认前的词头被丢掉
        private const val PREROLL_FRAMES = 10
        // 中间结果的解码间隔
        private const val PARTIAL_INTERVAL_SAMPLES = SAMPLE_RATE
        // 单个短语的最长时长，超过后直接结束该短语，控制解码耗时和内存
        private const val MAX_SEGMENT_SAMPLES = SAMPLE_RATE * 25
    }

    private class DecodeRequest(val samples: FloatArray, val isFinal: Boolean, val segmentId: Long)

    private var recognizer: OfflineRecognizer? = null
    private var vad: OnnxSileroVad? = null
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var decodeJob: Job? = null
    private var decodeChannel: Channel<DecodeRequest>? = null
    private val scope = CoroutineScope(Dispatchers.Default)

    private val segmentFrames = ArrayList<ShortArray>()
    private val segmentLock = Any()
    private val currentSegmentId = AtomicLong(0)
    private val partialPending = AtomicBoolean(false)

    private val _recognitionState = MutableStateFlow(SpeechService.RecognitionState.UNINITIALIZED)
    override val currentState: SpeechService.RecognitionState
        get() = _recognitionState.value
    override val recognitionStateFlow: StateFlow<SpeechService.RecognitionState> =
        _recognitionState.asStateFlow()

    private val _recognitionResult = MutableStateFlow(SpeechService.RecognitionResult(""))
    override val recognitionResultFlow: StateFlow<SpeechService.RecognitionResult> =
        _recognitionResult.asStateFlow()

    private val _recognitionError = MutableStateFlow(SpeechService.RecognitionError(0, ""))
    override val recognitionErrorFlow: StateFlow<SpeechService.RecognitionError> =
        _recognitionError.asStateFlow()

    private val _isInitialized = MutableStateFlow(false)
    override val isInitialized: StateFlow<Boolean> = _isInitialized.asStateFlow()

    private val _volumeLevelFlow = MutableStateFlow(0f)
    override val volumeLevelFlow: StateFlow<Float> = _volumeLevelFlow.asStateFlow()

    override val isRecognizing: Boolean
        get() = currentState == SpeechService.RecognitionState.RECOGNIZING

    private val VOLUME_SMOOTHING_FACTOR = 0.1f
    private var currentVolume = 0f

    private val initializeMutex = Mutex()
    private val recognitionMutex = Mutex()

    override suspend fun initialize(): Boolean {
        if (isInitialized.value) return true
        return initializeMutex.withLock {
            if (isInitialized.value) return@withLock true
            AppLogger.d(TAG, "Initializing sherpa-onnx offline model $model...")
            try {
                withContext(Dispatchers.IO) {
                    // 加载中途被取消时保留已创建的识别器，下次初始化直接复用
                    if (recognizer == null) recognizer = createRecognizer()
                }
                AppLogger.d(TAG, "sherpa-onnx offline model $model initialized")
                _isInitialized.value = true
                _recognitionState.value = SpeechService.RecognitionState.IDLE
                true
            } catch (e: CancellationException) {
                // 调用方（如离开组合的界面）取消了初始化，这不是识别引擎错误
                throw e
            } catch (e: Exception) {
                AppLogger.e(TAG, "Failed to initialize sherpa-onnx offline model $model", e)
                _recognitionState.value = SpeechService.RecognitionState.ERROR
                _recognitionError.value =
                    SpeechService.RecognitionError(-1, e.message ?: "Failed to initialize recognizer")
                false
            }
        }
    }

    private fun createRecognizer(): OfflineRecognizer {
        if (!SttModelDownloadManager.isReady(context, model)) {
            throw IllegalStateException(context.getString(R.string.stt_model_not_downloaded_error, model.displayName))
        }
        val dir = SttModelDownloadManager.modelDir(context, model)
        val config =
            OfflineRecognizerConfig(
                modelConfig =
                    OfflineModelConfig(
                        transducer =
                            OfflineTransducerModelConfig(
                                encoder = File(dir, model.encoder).absolutePath,
                                decoder = File(dir, model.decoder).absolutePath,
                                joiner = File(dir, model.joiner).absolutePath,
                            ),
                        tokens = File(dir, "tokens.txt").absolutePath,
                        modelType = "nemo_transducer",
                        numThreads = 4,
                    ),
                decodingMethod = "greedy_search",
            )
        return OfflineRecognizer(assetManager = null, config = config)
    }

    private fun decode(samples: FloatArray): String {
        val recognizerInstance = recognizer ?: return ""
        val stream = recognizerInstance.createStream()
        try {
            stream.acceptWaveform(samples, SAMPLE_RATE)
            recognizerInstance.decode(stream)
            return recognizerInstance.getResult(stream).text.trim()
        } finally {
            stream.release()
        }
    }

    private fun calculateVolumeLevel(buffer: ShortArray, size: Int): Float {
        if (size <= 0) return 0f
        var sum = 0.0
        for (i in 0 until size) {
            sum += abs(buffer[i].toDouble())
        }
        val average = sum / size
        val db = if (average > 0) 20 * log10(average / 32768.0) else -160.0
        val volume = ((db + 60.0) / 60.0).coerceIn(0.0, 1.0).toFloat()
        currentVolume = currentVolume * (1 - VOLUME_SMOOTHING_FACTOR) + volume * VOLUME_SMOOTHING_FACTOR
        return currentVolume
    }

    private fun snapshotSegment(): FloatArray {
        synchronized(segmentLock) {
            val total = segmentFrames.sumOf { it.size }
            val samples = FloatArray(total)
            var pos = 0
            segmentFrames.forEach { frame ->
                for (sample in frame) {
                    samples[pos++] = sample / 32768.0f
                }
            }
            return samples
        }
    }

    private fun segmentSampleCount(): Int =
        synchronized(segmentLock) { segmentFrames.sumOf { it.size } }

    /** 结束当前短语并排队做最终解码；返回是否有音频被提交 */
    private fun finishSegment(): Boolean {
        val samples = snapshotSegment()
        synchronized(segmentLock) { segmentFrames.clear() }
        val segmentId = currentSegmentId.getAndIncrement()
        if (samples.isEmpty()) return false
        decodeChannel?.trySend(DecodeRequest(samples, isFinal = true, segmentId = segmentId))
        return true
    }

    private fun startDecodeLoop(): Channel<DecodeRequest> {
        val channel = Channel<DecodeRequest>(Channel.UNLIMITED)
        decodeChannel = channel
        decodeJob =
            scope.launch(Dispatchers.IO) {
                for (request in channel) {
                    try {
                        if (!request.isFinal && request.segmentId != currentSegmentId.get()) {
                            continue
                        }
                        val text = decode(request.samples)
                        if (request.isFinal) {
                            if (text.isNotBlank()) {
                                _recognitionResult.value =
                                    SpeechService.RecognitionResult(text = text, isFinal = true)
                            }
                        } else if (text.isNotBlank() && request.segmentId == currentSegmentId.get()) {
                            _recognitionResult.value =
                                SpeechService.RecognitionResult(text = text, isFinal = false)
                        }
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "Decoding failed", e)
                    } finally {
                        if (!request.isFinal) partialPending.set(false)
                    }
                }
            }
        return channel
    }

    override suspend fun startRecognition(
        languageCode: String,
        continuousMode: Boolean,
        partialResults: Boolean,
        audioSource: Int,
    ): Boolean {
        return recognitionMutex.withLock {
            if (!isInitialized.value) {
                if (!initialize()) return@withLock false
            }
            if (
                currentState == SpeechService.RecognitionState.PREPARING ||
                    currentState == SpeechService.RecognitionState.PROCESSING ||
                    currentState == SpeechService.RecognitionState.RECOGNIZING ||
                    recordingJob?.isActive == true
            ) {
                return@withLock false
            }
            if (Locale.forLanguageTag(languageCode).language !in model.languages) {
                _recognitionState.value = SpeechService.RecognitionState.ERROR
                _recognitionError.value =
                    SpeechService.RecognitionError(-5, "Unsupported local recognition language for $model: $languageCode")
                return@withLock false
            }

            clearAndReleaseAudioRecord()
            _recognitionState.value = SpeechService.RecognitionState.PREPARING
            _recognitionResult.value = SpeechService.RecognitionResult(text = "", isFinal = false, confidence = 0f)
            synchronized(segmentLock) { segmentFrames.clear() }
            currentSegmentId.incrementAndGet()
            partialPending.set(false)

            // 唤醒后的预录音频直接作为第一个短语的开头
            val pendingPcm = SpeechPrerollStore.consumePending()
            val startWithSpeech = pendingPcm != null && pendingPcm.isNotEmpty()
            if (startWithSpeech) {
                synchronized(segmentLock) { segmentFrames.add(pendingPcm!!.copyOf()) }
                AppLogger.d(TAG, "Applied preroll: samples=${pendingPcm!!.size}")
            }

            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            val minBufferSize = AudioRecord.getMinBufferSize(SAMPLE_RATE, channelConfig, audioFormat)
            if (minBufferSize <= 0) {
                _recognitionState.value = SpeechService.RecognitionState.ERROR
                _recognitionError.value = SpeechService.RecognitionError(-2, "Invalid AudioRecord buffer size")
                return@withLock false
            }
            // 解码在单独的协程中进行，录音缓冲留足余量
            val record = AudioRecord(audioSource, SAMPLE_RATE, channelConfig, audioFormat, maxOf(minBufferSize * 4, SAMPLE_RATE))
            audioRecord = record
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                clearAndReleaseAudioRecord()
                _recognitionState.value = SpeechService.RecognitionState.ERROR
                _recognitionError.value = SpeechService.RecognitionError(-3, "AudioRecord not initialized")
                return@withLock false
            }
            try {
                record.startRecording()
            } catch (e: Exception) {
                AppLogger.e(TAG, "AudioRecord.startRecording failed", e)
                clearAndReleaseAudioRecord()
                _recognitionState.value = SpeechService.RecognitionState.ERROR
                _recognitionError.value = SpeechService.RecognitionError(-4, e.message ?: "AudioRecord start failed")
                return@withLock false
            }

            val channel = startDecodeLoop()
            val decoder = decodeJob
            _recognitionState.value = SpeechService.RecognitionState.RECOGNIZING
            currentVolume = 0f
            _volumeLevelFlow.value = 0f
            AppLogger.d(TAG, "Started recording language=$languageCode model=$model")

            recordingJob =
                scope.launch {
                    try {
                        val audioBuffer = ShortArray(minBufferSize)
                        val vadInstance =
                            (vad ?: OnnxSileroVad(context = context, silenceDurationMs = VAD_SILENCE_MS)).also { created ->
                                vad = created
                                created.reset()
                            }
                        val vadFrame = ShortArray(VAD_FRAME_SIZE)
                        var vadFramePos = 0
                        var speechActive = startWithSpeech
                        var samplesSincePartial = 0
                        val preroll = ArrayDeque<ShortArray>()

                        while (isActive && _recognitionState.value == SpeechService.RecognitionState.RECOGNIZING) {
                            val read = audioRecord?.read(audioBuffer, 0, audioBuffer.size) ?: 0
                            if (read <= 0) break

                            SpeechPrerollStore.appendPcm(audioBuffer, read)
                            _volumeLevelFlow.value = calculateVolumeLevel(audioBuffer, read)

                            var idx = 0
                            while (idx < read) {
                                val toCopy = minOf(VAD_FRAME_SIZE - vadFramePos, read - idx)
                                java.lang.System.arraycopy(audioBuffer, idx, vadFrame, vadFramePos, toCopy)
                                vadFramePos += toCopy
                                idx += toCopy
                                if (vadFramePos < VAD_FRAME_SIZE) continue
                                vadFramePos = 0

                                val frame = vadFrame.copyOf()
                                if (vadInstance.isSpeech(frame)) {
                                    synchronized(segmentLock) {
                                        if (!speechActive) {
                                            segmentFrames.addAll(preroll)
                                        }
                                        segmentFrames.add(frame)
                                    }
                                    preroll.clear()
                                    speechActive = true
                                    samplesSincePartial += VAD_FRAME_SIZE

                                    if (segmentSampleCount() >= MAX_SEGMENT_SAMPLES) {
                                        finishSegment()
                                        samplesSincePartial = 0
                                    } else if (
                                        partialResults &&
                                            samplesSincePartial >= PARTIAL_INTERVAL_SAMPLES &&
                                            partialPending.compareAndSet(false, true)
                                    ) {
                                        samplesSincePartial = 0
                                        channel.trySend(
                                            DecodeRequest(snapshotSegment(), isFinal = false, segmentId = currentSegmentId.get())
                                        )
                                    }
                                } else if (speechActive) {
                                    speechActive = false
                                    samplesSincePartial = 0
                                    finishSegment()
                                    vadInstance.reset()
                                    if (!continuousMode) {
                                        return@launch
                                    }
                                } else {
                                    preroll.addLast(frame)
                                    if (preroll.size > PREROLL_FRAMES) preroll.removeFirst()
                                }
                            }
                        }
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "Recording loop failed", e)
                    } finally {
                        // 录音协程被 stop 取消后仍需等待排队中的最终解码完成
                        withContext(NonCancellable) {
                            clearAndReleaseAudioRecord()
                            channel.close()
                            decoder?.join()
                            if (_recognitionState.value == SpeechService.RecognitionState.RECOGNIZING) {
                                _recognitionState.value = SpeechService.RecognitionState.IDLE
                            }
                            _volumeLevelFlow.value = 0f
                        }
                        AppLogger.d(TAG, "Stopped recording.")
                    }
                }
            return@withLock true
        }
    }

    private fun clearAndReleaseAudioRecord() {
        val record = audioRecord
        audioRecord = null
        if (record != null) {
            try {
                if (record.recordingState == AudioRecord.RECORDSTATE_RECORDING) record.stop()
            } catch (e: Exception) {
                AppLogger.w(TAG, "Error stopping AudioRecord", e)
            }
            try {
                record.release()
            } catch (e: Exception) {
                AppLogger.w(TAG, "Error releasing AudioRecord", e)
            }
        }
    }

    override suspend fun stopRecognition(): Boolean {
        return recognitionMutex.withLock {
            val job = recordingJob
            if (job?.isActive != true || _recognitionState.value != SpeechService.RecognitionState.RECOGNIZING) {
                return@withLock false
            }
            AppLogger.d(TAG, "Stopping recognition...")
            _recognitionState.value = SpeechService.RecognitionState.PROCESSING
            // 停止时把尚未结束的短语也交给解码，然后等待解码队列清空
            val submitted = finishSegment()
            job.cancel()
            try {
                job.join()
            } catch (_: Exception) {
            }
            if (!submitted) {
                // 最后一段已在录音中结束：仍发出最终结果，调用方据此结束处理而不必等待超时
                _recognitionResult.value = SpeechService.RecognitionResult(text = "", isFinal = true)
            }
            recordingJob = null
            decodeJob = null
            decodeChannel = null
            _recognitionState.value = SpeechService.RecognitionState.IDLE
            _volumeLevelFlow.value = 0f
            true
        }
    }

    override suspend fun cancelRecognition() {
        recognitionMutex.withLock {
            currentSegmentId.incrementAndGet()
            synchronized(segmentLock) { segmentFrames.clear() }
            decodeChannel?.close()
            decodeJob?.cancel()
            recordingJob?.cancel()
            try {
                recordingJob?.join()
            } catch (_: Exception) {
            }
            recordingJob = null
            decodeJob = null
            decodeChannel = null
            clearAndReleaseAudioRecord()
            _recognitionState.value = SpeechService.RecognitionState.IDLE
            _volumeLevelFlow.value = 0f
            _recognitionResult.value = SpeechService.RecognitionResult(text = "", isFinal = false, confidence = 0f)
            try {
                vad?.reset()
            } catch (_: Exception) {
            }
        }
    }

    override fun shutdown() {
        runBlocking {
            try {
                cancelRecognition()
            } catch (_: Exception) {
            }
            withContext(Dispatchers.IO) {
                try {
                    recognizer?.release()
                } catch (_: Exception) {
                }
                recognizer = null
                try {
                    vad?.close()
                } catch (_: Exception) {
                }
                vad = null
            }
            _isInitialized.value = false
            _recognitionState.value = SpeechService.RecognitionState.UNINITIALIZED
            _volumeLevelFlow.value = 0f
            _recognitionResult.value = SpeechService.RecognitionResult(text = "", isFinal = false, confidence = 0f)
        }
    }

    override suspend fun getSupportedLanguages(): List<String> = model.languages

    override suspend fun recognize(audioData: FloatArray) {
        if (!isInitialized.value && !initialize()) return
        val text = withContext(Dispatchers.IO) { decode(audioData) }
        _recognitionResult.value = SpeechService.RecognitionResult(text = text, isFinal = true)
    }
}
