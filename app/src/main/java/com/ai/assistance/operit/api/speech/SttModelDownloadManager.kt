package com.ai.assistance.operit.api.speech

import android.content.Context
import com.ai.assistance.operit.util.AppLogger
import com.ai.assistance.operit.util.OperitPaths
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
 * 下载、校验并管理本地 STT 模型文件。
 *
 * 文件存放在应用内部存储中，应用更新后保留；中断的下载以 `.part` 文件保存，下次从断点继续。
 */
object SttModelDownloadManager {
    private const val TAG = "SttModelDownload"
    private const val COMPLETE_MARKER = ".complete"
    private const val PART_SUFFIX = ".part"

    sealed class State {
        data object NotDownloaded : State()
        data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : State()
        data object Ready : State()
        data class Failed(val message: String) : State()
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val jobs = mutableMapOf<SherpaOnnxSttModel, Job>()
    private val _states = MutableStateFlow<Map<SherpaOnnxSttModel, State>>(emptyMap())
    val states: StateFlow<Map<SherpaOnnxSttModel, State>> = _states.asStateFlow()

    private val client =
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build()

    fun modelDir(context: Context, model: SherpaOnnxSttModel): File =
        File(OperitPaths.sttOnnxModelsDir(context), model.dirName)

    fun isReady(context: Context, model: SherpaOnnxSttModel): Boolean {
        val dir = modelDir(context, model)
        return File(dir, COMPLETE_MARKER).isFile && model.requiredFiles.all { File(dir, it).isFile }
    }

    /** 根据磁盘内容刷新各模型状态（下载中的模型保持不变） */
    fun refresh(context: Context) {
        _states.update { current ->
            SherpaOnnxSttModel.values().associateWith { model ->
                val existing = current[model]
                when {
                    existing is State.Downloading && jobs[model]?.isActive == true -> existing
                    isReady(context, model) -> State.Ready
                    existing is State.Failed -> existing
                    else -> State.NotDownloaded
                }
            }
        }
    }

    fun download(context: Context, model: SherpaOnnxSttModel) {
        val appContext = context.applicationContext
        synchronized(jobs) {
            if (jobs[model]?.isActive == true) return
            setState(model, State.Downloading(0, model.downloadBytes))
            jobs[model] =
                scope.launch {
                    try {
                        downloadModel(appContext, model)
                        setState(model, State.Ready)
                        AppLogger.d(TAG, "Model $model ready")
                    } catch (e: CancellationException) {
                        setState(model, if (isReady(appContext, model)) State.Ready else State.NotDownloaded)
                        throw e
                    } catch (e: Exception) {
                        AppLogger.e(TAG, "Failed to download model $model", e)
                        setState(model, State.Failed(e.message ?: e.javaClass.simpleName))
                    }
                }
        }
    }

    fun cancel(model: SherpaOnnxSttModel) {
        synchronized(jobs) { jobs.remove(model) }?.cancel()
    }

    fun delete(context: Context, model: SherpaOnnxSttModel) {
        cancel(model)
        modelDir(context, model).deleteRecursively()
        setState(model, State.NotDownloaded)
    }

    private fun setState(model: SherpaOnnxSttModel, state: State) {
        _states.update { it + (model to state) }
    }

    private suspend fun downloadModel(context: Context, model: SherpaOnnxSttModel) {
        val dir = modelDir(context, model)
        if (!dir.isDirectory && !dir.mkdirs()) {
            throw IOException("Unable to create model directory: ${dir.absolutePath}")
        }
        File(dir, COMPLETE_MARKER).delete()

        when (val source = model.source) {
            is SttModelSource.Files -> {
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
            is SttModelSource.TarBz2 -> {
                val archive = File(dir, source.archive.name)
                if (!verifies(archive, source.archive)) {
                    downloadFile(source.archive, archive) { fileBytes ->
                        setState(model, State.Downloading(fileBytes, model.downloadBytes))
                    }
                }
                extractMembers(archive, dir, source.members)
                archive.delete()
            }
        }

        val missing = model.requiredFiles.filterNot { File(dir, it).isFile }
        if (missing.isNotEmpty()) {
            throw IOException("Model files missing after download: ${missing.joinToString()}")
        }
        File(dir, COMPLETE_MARKER).writeText(model.name)
    }

    private fun verifies(file: File, remote: SttRemoteFile): Boolean =
        file.isFile && file.length() == remote.bytes && sha256(file) == remote.sha256

    private suspend fun downloadFile(remote: SttRemoteFile, target: File, onProgress: (Long) -> Unit) {
        val part = File(target.parentFile, target.name + PART_SUFFIX)
        if (part.length() > remote.bytes) part.delete()
        val resumeFrom = part.length()

        val requestBuilder = Request.Builder().url(remote.url).header("User-Agent", "Operit STT model download")
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

    private fun extractMembers(archive: File, dir: File, members: List<String>) {
        val wanted = members.toSet()
        val extracted = mutableSetOf<String>()
        TarArchiveInputStream(BZip2CompressorInputStream(BufferedInputStream(archive.inputStream()))).use { tar ->
            while (true) {
                val entry = tar.nextEntry ?: break
                val name = entry.name.substringAfterLast('/')
                if (entry.isDirectory || name !in wanted) continue
                File(dir, name).outputStream().use { output -> tar.copyTo(output) }
                extracted.add(name)
            }
        }
        val missing = wanted - extracted
        if (missing.isNotEmpty()) {
            throw IOException("Archive ${archive.name} is missing ${missing.joinToString()}")
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
