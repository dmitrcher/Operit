package com.ai.assistance.operit.api.voice

import android.content.Context
import com.ai.assistance.operit.api.speech.DownloadableModel
import com.ai.assistance.operit.api.speech.ModelRemoteFile
import com.ai.assistance.operit.api.speech.ModelSource
import com.ai.assistance.operit.util.OperitPaths
import java.io.File

/**
 * sherpa-onnx 打包的 Piper 音色（VITS + espeak-ng 音素化），运行时下载。
 *
 * 每个压缩包包含模型、tokens.txt 和 espeak-ng-data，解压到 [OperitPaths.piperVoicesDir]。
 */
enum class PiperVoice(
    val id: String,
    override val displayName: String,
    /** espeak-ng / Piper 语言：ru 或 en */
    val language: String,
    val gender: String,
    archiveBytes: Long,
    archiveSha256: String,
) : DownloadableModel {
    RU_IRINA("ru_RU-irina-medium", "Ирина", "ru", "FEMALE", 67153308L, "1fc0f54e5e084fe287c07909f2f6e0ba6d857864cf800e3ab80286a4e8233008"),
    RU_DENIS("ru_RU-denis-medium", "Денис", "ru", "MALE", 67190991L, "efa4c18e0b5e32b81d1b6df36b9d312831e5d545200e27848ef926a4cd930300"),
    RU_DMITRI("ru_RU-dmitri-medium", "Дмитрий", "ru", "MALE", 67188551L, "c86d0803737de13d441923ff3b3f309482fab8d7af3ec85949942809eb9a3660"),
    RU_RUSLAN("ru_RU-ruslan-medium", "Руслан", "ru", "MALE", 67210684L, "0690b1cad01f86e8db9ba988af24898bdc1af774e23cb2e46b9c730269b6fd83"),
    EN_AMY("en_US-amy-medium", "Amy", "en", "FEMALE", 67223746L, "9a5d1fc497f85e8022b785bff5f8105203b1e33099ee6265203efc70b0cb0264"),
    EN_LESSAC("en_US-lessac-medium", "Lessac", "en", "FEMALE", 67230653L, "9e3febfacf0abf4270172d2958bcec246032b7e88efc2720840cc80c93de334e"),
    EN_RYAN("en_US-ryan-medium", "Ryan", "en", "MALE", 67213100L, "c546af78b6395b4e7c4ce1ed899438b64426a362f5d4ec5fecd090ded9ad7505"),
    EN_JOE("en_US-joe-medium", "Joe", "en", "MALE", 67169394L, "261b903a1fb2ce621e2b5b14233cd41223c3b9f9590f896c11daee4df22d9bd3");

    override val source: ModelSource =
        ModelSource.TarBz2(
            archive = ModelRemoteFile(
                name = "vits-piper-$id.tar.bz2",
                url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-piper-$id.tar.bz2",
                bytes = archiveBytes,
                sha256 = archiveSha256,
            ),
            members = null,
        )

    val modelFile: String
        get() = "$id.onnx"

    override val key: String
        get() = "tts:piper:$id"

    override val requiredFiles: List<String>
        get() = listOf(modelFile, "tokens.txt", "espeak-ng-data/phontab")

    override fun dir(context: Context): File = File(OperitPaths.piperVoicesDir(context), id)

    companion object {
        fun byId(id: String): PiperVoice? = values().firstOrNull { it.id == id }
        fun forLanguage(language: String): List<PiperVoice> = values().filter { it.language == language }
        val DEFAULT_RU = RU_IRINA
    }
}
