package com.ai.assistance.operit.api.speech

import android.content.Context
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.data.preferences.SpeechServiceProfilesPreferences
import com.ai.assistance.operit.data.preferences.SpeechServicesPreferences.SttHttpConfig
import kotlinx.coroutines.runBlocking
import java.util.concurrent.atomic.AtomicBoolean

/** 语音识别服务工厂类 用于创建和管理不同类型的语音识别服务 */
object SpeechServiceFactory {
    private const val TAG = "SpeechServiceFactory"

    /** 语音识别服务类型 */
    enum class SpeechServiceType(val isLocal: Boolean) {
        /** 基于Sherpa-ncnn的本地识别实现 */
        SHERPA_NCNN(true),
        OPENAI_STT(false),
        DEEPGRAM_STT(false),
        /** sherpa-onnx NVIDIA Parakeet TDT 0.6B v3，本地多语言（含俄语、英语） */
        SHERPA_ONNX_PARAKEET_V3(true),
        /** sherpa-onnx NVIDIA FastConformer，本地 10 种语言（含俄语、英语） */
        SHERPA_ONNX_FASTCONFORMER(true),
        /** sherpa-onnx Sber GigaAM v3，本地俄语 */
        SHERPA_ONNX_GIGAAM_V3(true),
    }

    /**
     * 创建语音识别服务实例
     *
     * @param context 应用上下文
     * @return 对应类型的语音识别服务实例
     */
    fun createSpeechService(
        context: Context
    ): SpeechService {
        val profiles = SpeechServiceProfilesPreferences(context)
        val profile = runBlocking { profiles.getCurrentSttProfile() }

        return createSpeechService(context, profile.serviceType, profile.httpConfig)
    }

    fun createWakeSpeechService(
        context: Context,
    ): SpeechService {
        val profiles = SpeechServiceProfilesPreferences(context)
        val profile = runBlocking { profiles.getCurrentSttProfile() }
        val selectedType = profile.serviceType
        val effectiveType = when (selectedType) {
            SpeechServiceType.OPENAI_STT,
            SpeechServiceType.DEEPGRAM_STT,
            -> SpeechServiceType.SHERPA_NCNN
            else -> selectedType
        }
        return createSpeechService(context, effectiveType, profile.httpConfig)
    }

    fun createSpeechService(
        context: Context,
        type: SpeechServiceType,
    ): SpeechService {
        val profiles = SpeechServiceProfilesPreferences(context)
        val config = runBlocking { profiles.getCurrentSttProfile().httpConfig }
        return createSpeechService(context, type, config)
    }

    private fun createSpeechService(
        context: Context,
        type: SpeechServiceType,
        httpConfig: SttHttpConfig,
    ): SpeechService {
        return when (type) {
            SpeechServiceType.SHERPA_NCNN,
            SpeechServiceType.SHERPA_ONNX_PARAKEET_V3,
            SpeechServiceType.SHERPA_ONNX_FASTCONFORMER,
            SpeechServiceType.SHERPA_ONNX_GIGAAM_V3,
            -> acquireLocalSpeechService(context, type)
            SpeechServiceType.OPENAI_STT -> {
                runBlocking {
                    OpenAISttProvider(
                        context = context,
                        endpointUrl = httpConfig.endpointUrl,
                        apiKey = httpConfig.apiKey,
                        model = httpConfig.modelName,
                    )
                }
            }
            SpeechServiceType.DEEPGRAM_STT -> {
                runBlocking {
                    DeepgramSttProvider(
                        context = context,
                        endpointUrl = httpConfig.endpointUrl,
                        apiKey = httpConfig.apiKey,
                        model = httpConfig.modelName,
                    )
                }
            }
        }
    }

    private class SpeechServiceLease(
        private val delegate: SpeechService,
        private val onRelease: () -> Unit,
    ) : SpeechService by delegate {
        private val released = AtomicBoolean(false)

        override fun shutdown() {
            if (released.compareAndSet(false, true)) {
                onRelease()
            }
        }
    }

    private data class LocalEntry(
        val type: SpeechServiceType,
        val service: SpeechService,
        var refCount: Int,
    )

    private val localLock = Any()
    // 每种本地引擎各自一个共享实例：唤醒监听与语音识别工具可以同时使用不同的本地模型
    private val localEntries = mutableMapOf<SpeechServiceType, LocalEntry>()

    private fun acquireLocalSpeechService(
        context: Context,
        type: SpeechServiceType,
    ): SpeechService {
        val appContext = context.applicationContext
        synchronized(localLock) {
            val existing = localEntries[type]
            val entry =
                if (existing != null) {
                    existing.refCount += 1
                    existing
                } else {
                    val service =
                        when (type) {
                            SpeechServiceType.SHERPA_NCNN -> SherpaSpeechProvider(appContext)
                            SpeechServiceType.SHERPA_ONNX_PARAKEET_V3,
                            SpeechServiceType.SHERPA_ONNX_FASTCONFORMER,
                            SpeechServiceType.SHERPA_ONNX_GIGAAM_V3,
                            -> SherpaOnnxOfflineSpeechProvider(appContext, onnxModelFor(type)!!)
                            SpeechServiceType.OPENAI_STT,
                            SpeechServiceType.DEEPGRAM_STT,
                            -> throw IllegalArgumentException("Not a local SpeechService type: $type")
                        }
                    LocalEntry(type = type, service = service, refCount = 1).also { localEntries[type] = it }
                }

            return SpeechServiceLease(
                delegate = entry.service,
                onRelease = { releaseLocalSpeechService(entry.type) },
            )
        }
    }

    /** 需要下载模型文件的本地引擎对应的模型；其他引擎返回 null */
    fun onnxModelFor(type: SpeechServiceType): SherpaOnnxSttModel? =
        when (type) {
            SpeechServiceType.SHERPA_ONNX_PARAKEET_V3 -> SherpaOnnxSttModel.PARAKEET_V3
            SpeechServiceType.SHERPA_ONNX_FASTCONFORMER -> SherpaOnnxSttModel.FASTCONFORMER
            SpeechServiceType.SHERPA_ONNX_GIGAAM_V3 -> SherpaOnnxSttModel.GIGAAM_V3
            SpeechServiceType.SHERPA_NCNN,
            SpeechServiceType.OPENAI_STT,
            SpeechServiceType.DEEPGRAM_STT,
            -> null
        }

    private fun releaseLocalSpeechService(type: SpeechServiceType) {
        val toShutdown: SpeechService?
        synchronized(localLock) {
            val entry = localEntries[type] ?: return

            entry.refCount -= 1
            if (entry.refCount > 0) {
                return
            }

            localEntries.remove(type)
            toShutdown = entry.service
        }

        try {
            toShutdown?.shutdown()
        } catch (e: Exception) {
            AppLogger.w(TAG, "Failed to shutdown local SpeechService", e)
        }
    }

    // 单例实例缓存
    private var instance: SpeechService? = null
    private var currentProfileId: String? = null

    /**
     * 获取语音识别服务单例实例
     *
     * @param context 应用上下文
     * @return 语音识别服务实例
     */
    fun getInstance(
        context: Context,
    ): SpeechService {
        val profiles = SpeechServiceProfilesPreferences(context)
        val profile = runBlocking { profiles.getCurrentSttProfile() }
        val selectedProfileId = profile.id

        val needNewInstance = instance == null || selectedProfileId != currentProfileId
        
        if (needNewInstance) {
            try {
                instance?.shutdown()
            } catch (_: Exception) {
            }

            instance = createSpeechService(context)
            currentProfileId = selectedProfileId
        }

        return instance!!
    }

    /** 重置单例实例 在需要更改语音识别服务类型或释放资源时调用 */
    fun resetInstance() {
        try {
            instance?.shutdown()
        } catch (_: Exception) {
        }
        instance = null
        currentProfileId = null
    }
}
