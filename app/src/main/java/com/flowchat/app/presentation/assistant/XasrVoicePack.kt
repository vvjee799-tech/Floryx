package com.flowchat.app.presentation.assistant

import android.content.Context
import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.readAvailable
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream

internal object XasrModelPack {
    const val VERSION = "sherpa-onnx-x-asr-480ms-streaming-zipformer-transducer-zh-en-punct-int8-2026-06-05"
    const val ARCHIVE_BYTES = 133_895_136L
    const val ARCHIVE_SHA256 = "fa5f63d618e5a01526e275a358bb7772e403f84808a4769fba52cffd8160bf74"
    const val ENCODER = "encoder.int8.onnx"
    const val DECODER = "decoder.onnx"
    const val JOINER = "joiner.int8.onnx"
    const val TOKENS = "tokens.txt"
    val files = mapOf(ENCODER to 155_278_641L, DECODER to 11_309_084L, JOINER to 2_581_422L, TOKENS to 58_806L)
    const val DOWNLOAD_URL = "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/$VERSION.tar.bz2"
}

/** Stages in private storage and publishes the entire verified pack with one atomic rename. */
internal class XasrVoicePackStore(
    private val root: File,
    private val fetch: suspend (target: File, progress: suspend (Int) -> Unit) -> Unit = ::downloadXasrArchive
) {
    private val installed = root.resolve(XasrModelPack.VERSION)

    fun modelDirectory(): File? = installed.takeIf { dir ->
        XasrModelPack.files.all { (name, size) -> dir.resolve(name).let { it.isFile && it.length() == size } }
    }

    suspend fun install(progress: suspend (Int) -> Unit): File = withContext(Dispatchers.IO) {
        installLock.withLock {
            modelDirectory()?.let { return@withLock it }
            if (!root.mkdirs() && !root.isDirectory) throw IOException("Cannot create voice pack storage")
            val staging = root.resolve(".installing")
            if (!staging.deleteRecursively() || !staging.mkdir()) throw IOException("Cannot prepare voice pack storage")
            try {
                if (root.usableSpace < XasrModelPack.ARCHIVE_BYTES + XasrModelPack.files.values.sum()) {
                    throw InsufficientSpace()
                }
                val archive = staging.resolve("model.tar.bz2")
                fetch(archive, progress)
                verifyArchive(archive)
                val extracted = staging.resolve("model")
                if (!extracted.mkdir()) throw IOException("Cannot create voice model directory")
                extractModels(archive, extracted)
                currentCoroutineContext().ensureActive()
                if (!installed.deleteRecursively()) throw IOException("Cannot replace incomplete voice pack")
                Files.move(extracted.toPath(), installed.toPath(), StandardCopyOption.ATOMIC_MOVE)
                installed
            } finally {
                if (!staging.deleteRecursively()) Log.w("XasrVoicePack", "Could not remove incomplete staging")
            }
        }
    }

    private suspend fun verifyArchive(archive: File) {
        if (archive.length() != XasrModelPack.ARCHIVE_BYTES) throw InvalidPack()
        val digest = MessageDigest.getInstance("SHA-256")
        archive.inputStream().buffered().use { input ->
            val buffer = ByteArray(BUFFER_SIZE)
            while (true) {
                currentCoroutineContext().ensureActive()
                val count = input.read(buffer)
                if (count == -1) break
                digest.update(buffer, 0, count)
            }
        }
        if (!MessageDigest.isEqual(digest.digest(), archiveHash)) throw InvalidPack()
    }

    private suspend fun extractModels(archive: File, directory: File) {
        val remaining = XasrModelPack.files.keys.toMutableSet()
        val buffer = ByteArray(BUFFER_SIZE)
        archive.inputStream().buffered().use { raw ->
            TarArchiveInputStream(BZip2CompressorInputStream(raw)).use { tar ->
                while (remaining.isNotEmpty()) {
                    currentCoroutineContext().ensureActive()
                    val entry = tar.nextEntry ?: throw InvalidPack()
                    val name = entry.name.removePrefix("${XasrModelPack.VERSION}/")
                    val expected = XasrModelPack.files[name] ?: continue
                    if (entry.name != "${XasrModelPack.VERSION}/$name" || !remaining.remove(name) ||
                        !entry.isFile || entry.isLink || entry.isSymbolicLink || entry.size != expected
                    ) throw InvalidPack()
                    directory.resolve(name).outputStream().buffered().use { output ->
                        var copied = 0L
                        while (copied < expected) {
                            currentCoroutineContext().ensureActive()
                            val read = tar.read(buffer, 0, minOf(buffer.size.toLong(), expected - copied).toInt())
                            if (read <= 0) throw InvalidPack()
                            output.write(buffer, 0, read)
                            copied += read
                        }
                    }
                }
            }
        }
    }

    class InvalidPack : IOException("X-ASR archive failed verification")
    class InsufficientSpace : IOException("Insufficient storage for the X-ASR voice pack")

    private companion object {
        val installLock = Mutex()
        val archiveHash = XasrModelPack.ARCHIVE_SHA256.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
    }
}

private const val BUFFER_SIZE = 64 * 1024

private suspend fun downloadXasrArchive(target: File, progress: suspend (Int) -> Unit) {
    HttpClient(OkHttp) {
        engine {
            config {
                connectTimeout(15, TimeUnit.SECONDS)
                readTimeout(20, TimeUnit.SECONDS)
                followSslRedirects(false)
                retryOnConnectionFailure(false)
            }
        }
    }.use { client ->
        client.prepareGet(XasrModelPack.DOWNLOAD_URL).execute { response ->
            if (response.status != HttpStatusCode.OK) throw IOException("Voice pack HTTP ${response.status.value}")
            val body = response.bodyAsChannel()
            target.outputStream().buffered().use { output ->
                val buffer = ByteArray(BUFFER_SIZE)
                var received = 0L
                var lastProgress = -1
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val read = body.readAvailable(buffer)
                    if (read == -1) break
                    received += read
                    if (received > XasrModelPack.ARCHIVE_BYTES) throw XasrVoicePackStore.InvalidPack()
                    output.write(buffer, 0, read)
                    val percent = (received * 100 / XasrModelPack.ARCHIVE_BYTES).toInt()
                    if (percent != lastProgress) {
                        progress(percent)
                        lastProgress = percent
                    }
                }
            }
        }
    }
}

internal class XasrVoicePack(private val store: XasrVoicePackStore) {
    fun isReady() = store.modelDirectory() != null
    suspend fun download(progress: suspend (Int) -> Unit) = store.install(progress)
    fun newInputIfReady() = store.modelDirectory()?.let(XasrSpeechInputFactory::create)

    internal fun modelDirectory(): File? = store.modelDirectory()

    companion object {
        fun forDevice(context: Context): XasrVoicePack? = if (XasrSpeechInputFactory.supported) {
            XasrVoicePack(XasrVoicePackStore(context.noBackupFilesDir.resolve("assistant-voice")))
        } else null
    }
}
