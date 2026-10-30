package com.ai.assistance.operit.api.speech

import android.content.Context
import com.ai.assistance.operit.util.AppLogger
import java.io.BufferedInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

/**
 * 下载、校验并管理本地语音模型（STT 模型与 TTS 音色）。
 *
 * 文件存放在应用内部存储中，应用更新后保留；中断的下载以 `.part` 文件保存，下次从断点继续。
 */
object LocalModelDownloadManager {
    private const val TAG = "LocalModelDownload"
    private const val COMPLETE_MARKER = ".complete"
    private const val PART_SUFFIX = ".part"

    sealed class State {
        data object NotDownloaded : State()
        data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : State()
        data object Ready : State()
        data class Failed(val message: String) : State()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableMapOf<String, Job>()
    private val _states = MutableStateFlow<Map<String, State>>(emptyMap())
    /** 按 [DownloadableModel.key] 索引的下载状态 */
    val states: StateFlow<Map<String, State>> = _states.asStateFlow()

    private val client =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()

    fun isReady(context: Context, model: DownloadableModel): Boolean {
        val dir = model.dir(context)
        return File(dir, COMPLETE_MARKER).isFile && model.requiredFiles.all { File(dir, it).isFile }
    }

    /** 根据磁盘内容刷新模型状态（下载中的模型保持不变） */
    fun refresh(context: Context, models: List<DownloadableModel>) {
        _states.update { current ->
            current + models.associate { model ->
                val existing = current[model.key]
                val active = synchronized(jobs) { jobs[model.key]?.isActive == true }
                model.key to
                    when {
                        existing is State.Downloading && active -> existing
                        isReady(context, model) -> State.Ready
                        existing is State.Failed -> existing
                        else -> State.NotDownloaded
                    }
            }
        }
    }

    fun download(context: Context, model: DownloadableModel) {
        val appContext = context.applicationContext
        synchronized(jobs) {
            if (jobs[model.key]?.isActive == true) return
            setState(model, State.Downloading(0, model.downloadBytes))
            jobs[model.key] =
                scope.launch {
                    try {
                        downloadModel(appContext, model)
                        setState(model, State.Ready)
                        AppLogger.d(TAG, "Model ${model.key} ready")
                    } catch (e: CancellationException) {
                        setState(model, if (isReady(appContext, model)) State.Ready else State.NotDownloaded)
                        throw e
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "Failed to download model ${model.key}", e)
                        setState(model, State.Failed(e.message ?: e.javaClass.simpleName))
                    }
                }
        }
    }

    fun cancel(model: DownloadableModel) {
        synchronized(jobs) { jobs.remove(model.key) }?.cancel()
    }

    fun delete(context: Context, model: DownloadableModel) {
        cancel(model)
        model.dir(context).deleteRecursively()
        setState(model, State.NotDownloaded)
    }

    private fun setState(model: DownloadableModel, state: State) {
        _states.update { it + (model.key to state) }
    }

    private suspend fun downloadModel(context: Context, model: DownloadableModel) {
        val dir = model.dir(context)
        if (!dir.isDirectory && !dir.mkdirs()) {
            throw IOException("Unable to create model directory: ${dir.absolutePath}")
        }
        File(dir, COMPLETE_MARKER).delete()

        when (val source = model.source) {
            is ModelSource.Files -> {
                var completedBytes = 0L
                for (remote in source.files) {
                    val target = File(dir, remote.name)
                    if (!verifies(target, remote)) {
                        downloadFile(remote, target) { fileBytes ->
                            setState(model, State.Downloading(completedBytes + fileBytes, model.downloadBytes))
                        }
                    }
                    completedBytes += remote.bytes
                    setState(model, State.Downloading(completedBytes, model.downloadBytes))
                }
            }
            is ModelSource.TarBz2 -> {
                val archive = File(dir, source.archive.name)
                if (!verifies(archive, source.archive)) {
                    downloadFile(source.archive, archive) { fileBytes ->
                        setState(model, State.Downloading(fileBytes, model.downloadBytes))
                    }
                }
                extractArchive(archive, dir, source.members)
                archive.delete()
            }
        }

        val missing = model.requiredFiles.filterNot { File(dir, it).isFile }
        if (missing.isNotEmpty()) {
            throw IOException("Model files missing after download: ${missing.joinToString()}")
        }
        File(dir, COMPLETE_MARKER).writeText(model.key)
    }

    private fun verifies(file: File, remote: ModelRemoteFile): Boolean =
        file.isFile && file.length() == remote.bytes && sha256(file) == remote.sha256

    private suspend fun downloadFile(remote: ModelRemoteFile, target: File, onProgress: (Long) -> Unit) {
        val part = File(target.parentFile, target.name + PART_SUFFIX)
        if (part.length() > remote.bytes) part.delete()
        val resumeFrom = part.length()

        val requestBuilder = Request.Builder().url(remote.url).header("User-Agent", "Operit local model download")
        if (resumeFrom > 0) requestBuilder.header("Range", "bytes=$resumeFrom-")

        client.newCall(requestBuilder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} for ${remote.name}")
            }
            // 服务器不支持断点续传时返回 200 和完整内容，需要从头写
            val append = resumeFrom > 0 && response.code == 206
            var written = if (append) resumeFrom else 0L
            val body = response.body ?: throw IOException("Empty response for ${remote.name}")
            body.byteStream().use { input ->
                FileOutputStream(part, append).use { output ->
                    val buffer = ByteArray(256 * 1024)
                    var lastReported = 0L
                    while (true) {
                        kotlin.coroutines.coroutineContext.ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        if (written - lastReported >= 1024 * 1024) {
                            lastReported = written
                            onProgress(written)
                        }
                    }
                }
            }
            onProgress(written)
        }

        if (!verifies(part, remote)) {
            part.delete()
            throw IOException("Checksum mismatch for ${remote.name}")
        }
        target.delete()
        if (!part.renameTo(target)) {
            throw IOException("Unable to move ${part.name} to ${target.name}")
        }
    }

    /** 解压 tar.bz2，去掉顶层目录；[members] 不为 null 时只解压这些文件名 */
    private fun extractArchive(archive: File, dir: File, members: List<String>?) {
        val wanted = members?.toSet()
        val extracted = mutableSetOf<String>()
        val root = dir.canonicalFile
        TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(archive.inputStream()))).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                val relative = entry.name.removePrefix("./").substringAfter('/', "")
                if (relative.isEmpty() || entry.isDirectory) continue
                if (wanted != null && relative.substringAfterLast('/') !in wanted) continue
                val outputName = if (wanted != null) relative.substringAfterLast('/') else relative
                val output = File(root, outputName).canonicalFile
                if (!output.path.startsWith(root.path + File.separator)) {
                    throw IOException("Archive entry escapes model directory: ${entry.name}")
                }
                output.parentFile?.mkdirs()
                output.outputStream().use { stream -> tar.copyTo(stream) }
                extracted.add(outputName)
            }
        }
        if (wanted != null) {
            val missing = wanted - extracted
            if (missing.isNotEmpty()) {
                throw IOException("Archive ${archive.name} is missing ${missing.joinToString()}")
            }
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(1024 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
