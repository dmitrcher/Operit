package com.ai.assistance.operit.api.voice

import android.content.Context
import com.ai.assistance.operit.R
import com.ai.assistance.operit.api.speech.LocalModelDownloadManager
import com.ai.assistance.operit.data.preferences.SpeechServicesPreferences
import com.ai.assistance.operit.util.AppLogger
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.OfflineTtsConfig
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 基于 sherpa-onnx Piper 音色的本地 TTS。
 *
 * 可同时配置俄语和英语音色：文本按文字系统切分，西里尔字母片段用俄语音色，
 * 拉丁字母片段用英语音色，再拼接为一段音频播放。未配置英语音色时全部文本使用俄语音色。
 */
class PiperVoiceProvider(
    private val context: Context,
    ruVoiceId: String,
    enVoiceId: String,
) : VoiceService {
    companion object {
        private const val TAG = "PiperVoiceProvider"
    }

    private enum class Script { CYRILLIC, LATIN }

    private var ruVoice: PiperVoice? = PiperVoice.byId(ruVoiceId.ifBlank { PiperVoice.DEFAULT_RU.id })
    private var enVoice: PiperVoice? = enVoiceId.takeIf { it.isNotBlank() }?.let { PiperVoice.byId(it) }

    private val engines = mutableMapOf<PiperVoice, OfflineTts>()
    private val initializeMutex = Mutex()
    private val synthesisMutex = Mutex()
    @Volatile private var initialized = false

    private val playback = QueuedTtsPlayback(TAG) { request -> synthesizeToFile(request) }

    override val isInitialized: Boolean
        get() = initialized

    override val isSpeaking: Boolean
        get() = playback.isSpeaking

    override val speakingStateFlow: Flow<Boolean> = playback.speakingStateFlow

    override suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        initializeMutex.withLock {
            if (initialized) return@withLock true
            val voices = listOfNotNull(
                ruVoice ?: throw TtsException(context.getString(R.string.piper_tts_error_voice_not_set)),
                enVoice,
            )
            try {
                voices.forEach { voice ->
                    if (!LocalModelDownloadManager.isReady(context, voice)) {
                        throw TtsException(context.getString(R.string.piper_tts_error_voice_not_downloaded, voice.displayName))
                    }
                    if (voice !in engines) {
                        engines[voice] = createEngine(voice)
                    }
                }
                initialized = true
                AppLogger.d(TAG, "Initialized voices=${voices.joinToString { it.id }}")
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: TtsException) {
                throw e
            } catch (e: Exception) {
                AppLogger.e(TAG, "Piper TTS initialize failed", e)
                throw TtsException(context.getString(R.string.piper_tts_error_init_failed), cause = e)
            }
        }
    }

    private fun createEngine(voice: PiperVoice): OfflineTts {
        val dir = voice.dir(context)
        val config =
            OfflineTtsConfig(
                model =
                    OfflineTtsModelConfig(
                        vits =
                            OfflineTtsVitsModelConfig(
                                model = File(dir, voice.modelFile).absolutePath,
                                tokens = File(dir, "tokens.txt").absolutePath,
                                dataDir = File(dir, "espeak-ng-data").absolutePath,
                            ),
                        numThreads = 2,
                    ),
            )
        return OfflineTts(assetManager = null, config = config)
    }

    override suspend fun speak(
        text: String,
        interrupt: Boolean,
        rate: Float?,
        pitch: Float?,
        extraParams: Map<String, String>,
    ): Boolean = playback.speak(text, interrupt, rate, pitch, extraParams)

    private suspend fun synthesizeToFile(request: QueuedTtsPlayback.Request): File? {
        if (!initialized && !initialize()) return null
        if (!playback.isCurrent(request)) return null

        val speed = request.rate ?: SpeechServicesPreferences(context.applicationContext).ttsSpeechRateFlow.first()
        val segments = splitByScript(request.text)
        if (segments.isEmpty()) return null

        return synthesisMutex.withLock {
            var sampleRate = 0
            val chunks = ArrayList<FloatArray>()
            for ((voice, segmentText) in segments) {
                if (!playback.isCurrent(request)) return@withLock null
                val engine = engines[voice] ?: throw TtsException(context.getString(R.string.piper_tts_error_init_failed))
                val audio = engine.generate(segmentText, 0, speed)
                if (sampleRate == 0) sampleRate = audio.sampleRate
                if (audio.sampleRate != sampleRate) {
                    throw TtsException(context.getString(R.string.piper_tts_error_sample_rate_mismatch))
                }
                chunks.add(audio.samples)
            }
            if (chunks.sumOf { it.size } == 0) {
                throw TtsException(context.getString(R.string.vits_tts_error_output_empty))
            }
            // 已播放完的旧文件可以直接删除：正在播放的文件已被 MediaPlayer 打开，删除不影响播放
            context.cacheDir.listFiles { f -> f.name.startsWith("piper_tts_") && f.name.endsWith(".wav") }
                ?.forEach { it.delete() }
            val file = File(context.cacheDir, "piper_tts_${request.generation}_${System.nanoTime()}.wav")
            writeWav(file, chunks, sampleRate)
            file
        }
    }

    /** 按文字系统切分文本；标点、数字和空白归入当前片段 */
    private fun splitByScript(text: String): List<Pair<PiperVoice, String>> {
        val ru = ruVoice ?: throw TtsException(context.getString(R.string.piper_tts_error_voice_not_set))
        val en = enVoice
        if (en == null) {
            return if (text.isBlank()) emptyList() else listOf(ru to text)
        }

        val result = ArrayList<Pair<PiperVoice, String>>()
        val current = StringBuilder()
        var currentScript: Script? = null
        for (ch in text) {
            val script =
                when {
                    !ch.isLetter() -> null
                    Character.UnicodeScript.of(ch.code) == Character.UnicodeScript.CYRILLIC -> Script.CYRILLIC
                    Character.UnicodeScript.of(ch.code) == Character.UnicodeScript.LATIN -> Script.LATIN
                    else -> null
                }
            if (script != null && currentScript != null && script != currentScript) {
                result.add((if (currentScript == Script.LATIN) en else ru) to current.toString())
                current.clear()
            }
            if (script != null) currentScript = script
            current.append(ch)
        }
        if (current.isNotBlank()) {
            result.add((if (currentScript == Script.LATIN) en else ru) to current.toString())
        }
        return result.filter { it.second.any(Char::isLetterOrDigit) }
    }

    private fun writeWav(file: File, chunks: List<FloatArray>, sampleRate: Int) {
        val totalSamples = chunks.sumOf { it.size }
        val dataBytes = totalSamples * 2
        DataOutputStream(FileOutputStream(file).buffered()).use { out ->
            fun intLE(v: Int) { out.writeByte(v and 0xff); out.writeByte(v shr 8 and 0xff); out.writeByte(v shr 16 and 0xff); out.writeByte(v shr 24 and 0xff) }
            fun shortLE(v: Int) { out.writeByte(v and 0xff); out.writeByte(v shr 8 and 0xff) }
            out.writeBytes("RIFF"); intLE(36 + dataBytes); out.writeBytes("WAVE")
            out.writeBytes("fmt "); intLE(16); shortLE(1); shortLE(1); intLE(sampleRate); intLE(sampleRate * 2); shortLE(2); shortLE(16)
            out.writeBytes("data"); intLE(dataBytes)
            for (chunk in chunks) {
                for (sample in chunk) {
                    shortLE((sample.coerceIn(-1f, 1f) * 32767f).toInt())
                }
            }
        }
    }

    override suspend fun stop(): Boolean = playback.stop()

    override suspend fun pause(): Boolean = playback.pause()

    override suspend fun resume(): Boolean = playback.resume()

    override fun shutdown() {
        playback.shutdown()
        engines.values.forEach { engine ->
            try {
                engine.release()
            } catch (e: Exception) {
                AppLogger.w(TAG, "Failed to release Piper engine", e)
            }
        }
        engines.clear()
        initialized = false
    }

    override suspend fun getAvailableVoices(): List<VoiceService.Voice> =
        PiperVoice.values().map { voice ->
            VoiceService.Voice(voice.id, voice.displayName, if (voice.language == "ru") "ru-RU" else "en-US", voice.gender)
        }

    override suspend fun setVoice(voiceId: String): Boolean {
        val voice = PiperVoice.byId(voiceId) ?: return false
        if (voice.language == "ru") ruVoice = voice else enVoice = voice
        initialized = false
        return initialize()
    }
}
