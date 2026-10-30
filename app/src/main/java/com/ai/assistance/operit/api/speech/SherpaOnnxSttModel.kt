package com.ai.assistance.operit.api.speech

/** 可下载的远程文件，按大小和 SHA-256 校验 */
data class SttRemoteFile(
    val name: String,
    val url: String,
    val bytes: Long,
    val sha256: String,
)

/** 模型文件的发布形式 */
sealed class SttModelSource {
    /** 各文件单独下载 */
    data class Files(val files: List<SttRemoteFile>) : SttModelSource()

    /** 仅以 tar.bz2 发布：下载校验后解压 [members] 到模型目录 */
    data class TarBz2(val archive: SttRemoteFile, val members: List<String>) : SttModelSource()
}

/**
 * 运行时下载的 sherpa-onnx NeMo transducer 模型。
 *
 * 模型不打包进 APK，下载到 [com.ai.assistance.operit.util.OperitPaths.sttOnnxModelsDir]，
 * 应用更新后保留，卸载应用时才会被删除。
 */
enum class SherpaOnnxSttModel(
    val dirName: String,
    val displayName: String,
    val encoder: String,
    val decoder: String,
    val joiner: String,
    val languages: List<String>,
    val source: SttModelSource,
) {
    /** NVIDIA Parakeet TDT 0.6B v3：25 种欧洲语言，自动识别语种，带标点和大小写（CC-BY-4.0） */
    PARAKEET_V3(
        dirName = "sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8",
        displayName = "Parakeet v3",
        encoder = "encoder.int8.onnx",
        decoder = "decoder.int8.onnx",
        joiner = "joiner.int8.onnx",
        languages = listOf(
            "ru", "en", "bg", "cs", "da", "de", "el", "es", "et", "fi", "fr", "hr", "hu",
            "it", "lt", "lv", "mt", "nl", "pl", "pt", "ro", "sk", "sl", "sv", "uk",
        ),
        source = SttModelSource.Files(
            huggingFaceFiles(
                repo = "csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8",
                commit = "2bda32ec70b097a55adaa07d9a7173915b43cc78",
                files = listOf(
                    Triple("encoder.int8.onnx", 652184281L, "acfc2b4456377e15d04f0243af540b7fe7c992f8d898d751cf134c3a55fd2247"),
                    Triple("decoder.int8.onnx", 11845275L, "179e50c43d1a9de79c8a24149a2f9bac6eb5981823f2a2ed88d655b24248db4e"),
                    Triple("joiner.int8.onnx", 6355277L, "3164c13fc2821009440d20fcb5fdc78bff28b4db2f8d0f0b329101719c0948b3"),
                    Triple("tokens.txt", 93939L, "d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d"),
                ),
            ),
        ),
    ),
    /** NVIDIA FastConformer 多语言 transducer：10 种语言，带标点（CC-BY-4.0） */
    FASTCONFORMER(
        dirName = "sherpa-onnx-nemo-fast-conformer-transducer-be-de-en-es-fr-hr-it-pl-ru-uk-20k-int8",
        displayName = "FastConformer",
        encoder = "encoder.int8.onnx",
        decoder = "decoder.int8.onnx",
        joiner = "joiner.int8.onnx",
        languages = listOf("ru", "en", "be", "de", "es", "fr", "hr", "it", "pl", "uk"),
        source = SttModelSource.TarBz2(
            archive = SttRemoteFile(
                name = "sherpa-onnx-nemo-fast-conformer-transducer-be-de-en-es-fr-hr-it-pl-ru-uk-20k-int8.tar.bz2",
                url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-fast-conformer-transducer-be-de-en-es-fr-hr-it-pl-ru-uk-20k-int8.tar.bz2",
                bytes = 106546673L,
                sha256 = "06072bad277f0f4c29cc866d7c62b0e47936da39afafeae453faa925025ccad6",
            ),
            members = listOf("encoder.int8.onnx", "decoder.int8.onnx", "joiner.int8.onnx", "tokens.txt"),
        ),
    ),
    /** Sber GigaAM v3 带标点 transducer：俄语（MIT） */
    GIGAAM_V3(
        dirName = "sherpa-onnx-nemo-transducer-punct-giga-am-v3-russian-2025-12-16",
        displayName = "GigaAM v3",
        encoder = "encoder.int8.onnx",
        decoder = "decoder.onnx",
        joiner = "joiner.onnx",
        languages = listOf("ru"),
        source = SttModelSource.Files(
            huggingFaceFiles(
                repo = "csukuangfj/sherpa-onnx-nemo-transducer-punct-giga-am-v3-russian-2025-12-16",
                commit = "a6039be7cee829a9044a69ac0ebaf1c191217c97",
                files = listOf(
                    Triple("encoder.int8.onnx", 224570820L, "369f35a71bf288d3b8e0391fabd8dba5f2314088d440bca474056b7b4b6e66bf"),
                    Triple("decoder.onnx", 4600132L, "38fc7475443ea2a26f63211ca350f73ac50fff824ab7a3876ee2bd610c53bbc4"),
                    Triple("joiner.onnx", 2712896L, "602ff7017a93311aad34df1437c8d7f49911353c13d6eae7a6ee7b041339465c"),
                    Triple("tokens.txt", 13354L, "39abae20e692998290c574e606f11a9edef2902a1995463fcff63d1490cf22b7"),
                ),
            ),
        ),
    );

    /** 需要下载的字节数 */
    val downloadBytes: Long
        get() = when (val s = source) {
            is SttModelSource.Files -> s.files.sumOf { it.bytes }
            is SttModelSource.TarBz2 -> s.archive.bytes
        }

    /** 识别器加载所需的文件 */
    val requiredFiles: List<String>
        get() = listOf(encoder, decoder, joiner, "tokens.txt")
}

private fun huggingFaceFiles(
    repo: String,
    commit: String,
    files: List<Triple<String, Long, String>>,
): List<SttRemoteFile> =
    files.map { (name, bytes, sha256) ->
        SttRemoteFile(name, "https://huggingface.co/$repo/resolve/$commit/$name", bytes, sha256)
    }
