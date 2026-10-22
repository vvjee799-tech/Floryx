package com.flowchat.app.data.terminal

import android.content.Context
import android.net.ConnectivityManager
import android.system.Os
import com.flowchat.app.domain.terminal.LinuxEnvironmentState
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject

@Singleton
class LinuxRuntime @Inject constructor(@ApplicationContext private val context: Context) {
    private val base = File(context.filesDir, "terminal")
    private val rootfs = File(base, "linux")
    private val nativeDir = File(context.applicationInfo.nativeLibraryDir)
    private val mutex = Mutex()
    private val mutableState = MutableStateFlow(LinuxEnvironmentState())
    val state = mutableState.asStateFlow()
    private val abi: String by lazy {
        val executable = File(nativeDir, "libflowchat_proot.so")
        RandomAccessFile(executable, "r").use { file ->
            file.seek(18)
            when (file.read() or (file.read() shl 8)) { 183 -> "arm64-v8a"; 62 -> "x86_64"; else -> "unsupported" }
        }
    }

    suspend fun prepare() = mutex.withLock {
        withContext(Dispatchers.IO) {
            mutableState.value = state.value.copy(preparing = true, error = null)
            try {
                val manifest = JSONObject(context.assets.open("terminal/runtime-manifest.json").bufferedReader().use { it.readText() })
                val distribution = manifest.getJSONObject("linux").optJSONObject(abi)
                if (distribution == null) {
                    mutableState.value = LinuxEnvironmentState(supported = false, architecture = abi)
                    return@withContext
                }
                if (!File(rootfs, ".flowchat-ready").isFile) {
                    val staging = File(base, "linux-installing")
                    removeStaging(staging)
                    staging.mkdirs()
                    val asset = distribution.getString("asset")
                    val checksum = context.assets.open("terminal/$asset").use { input ->
                        val digest = MessageDigest.getInstance("SHA-256")
                        val buffer = ByteArray(16384)
                        while (true) { val size = input.read(buffer); if (size < 0) break; digest.update(buffer, 0, size) }
                        digest.digest().joinToString("") { "%02x".format(it) }
                    }
                    check(checksum == distribution.getString("sha256")) { "Runtime checksum mismatch." }
                    context.assets.open("terminal/$asset").use { input ->
                        SafeTarExtractor.extractGzip(input, staging.toPath()) { path, mode -> Os.chmod(path.toString(), mode) }
                    }
                    check(File(staging, "bin/busybox").isFile) { "Runtime shell is missing." }
                    File(staging, "workspace").mkdirs()
                    File(staging, "root").mkdirs()
                    File(staging, "tmp").mkdirs()
                    File(staging, ".flowchat-ready").writeText(distribution.getString("version"))
                    check(!rootfs.exists()) { "An incomplete Linux environment already exists; its files were preserved." }
                    check(staging.renameTo(rootfs)) { "Could not activate the Linux environment." }
                }
                val libraries = File(base, "lib").apply { mkdirs() }
                val talloc = File(libraries, "libtalloc.so.2").toPath()
                Files.deleteIfExists(talloc)
                Files.createSymbolicLink(talloc, File(nativeDir, "libflowchat_talloc.so").toPath())
                File(base, "tmp").mkdirs()
                val toolsInstaller = File(rootfs, "usr/local/bin/flowchat-install-tools")
                toolsInstaller.parentFile?.mkdirs()
                toolsInstaller.writeBytes(context.assets.open("terminal/install-development-tools.sh").use { it.readBytes() })
                Os.chmod(toolsInstaller.path, 0x1ed)
                refreshDns()
                refreshState()
            } catch (error: Exception) {
                mutableState.value = state.value.copy(preparing = false, ready = false, error = error.message ?: "Linux initialization failed.")
            }
        }
    }

    fun refreshState() {
        mutableState.value = LinuxEnvironmentState(
            ready = File(rootfs, ".flowchat-ready").isFile,
            version = runCatching { File(rootfs, "etc/alpine-release").readText().trim() }.getOrDefault(""),
            architecture = runCatching { abi }.getOrDefault("unsupported"),
            developerToolsReady = listOf("usr/bin/python3", "usr/bin/pip3", "usr/bin/node", "usr/bin/npm", "usr/bin/git").all { File(rootfs, it).exists() }
        )
    }

    internal fun processSpec(owner: String, command: String, cwd: String): CommandProcessSpec {
        check(state.value.ready) { state.value.error ?: "Linux environment is not ready." }
        require(owner.matches(Regex("[A-Za-z0-9_-]{1,100}"))) { "Invalid workspace identifier." }
        val workspace = File(base, "workspaces/$owner").apply { mkdirs() }
        val executable = File(nativeDir, "libflowchat_proot.so").path
        val argv = listOf(executable, "--link2symlink", "--kill-on-exit", "-0", "-r", rootfs.path,
            "-b", "/dev", "-b", "/proc", "-b", "/sys", "-b", "${workspace.path}:/workspace", "-w", "/workspace",
            "/usr/bin/env", "-i", "HOME=/root", "USER=root", "LOGNAME=root", "LANG=C.UTF-8", "TERM=dumb", "TMPDIR=/tmp",
            "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin", "/bin/sh", "-lc", "cd ${ManagedCommandRegistry.shellQuote(cwd)} || exit; $command")
        return CommandProcessSpec(argv, base, mapOf(
            "HOME" to base.path, "PATH" to "/system/bin", "TMPDIR" to File(base, "tmp").path,
            "LD_LIBRARY_PATH" to "${nativeDir.path}:${File(base, "lib").path}",
            "PROOT_LOADER" to File(nativeDir, "libflowchat_loader.so").path,
            "PROOT_LOADER_32" to File(nativeDir, "libflowchat_loader32.so").path,
            "PROOT_TMP_DIR" to File(base, "tmp").path, "PROOT_NO_SECCOMP" to "1"
        ))
    }

    private fun refreshDns() {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val servers = manager.activeNetwork?.let(manager::getLinkProperties)?.dnsServers.orEmpty()
            .mapNotNull { it.hostAddress }.filter { ':' !in it }.take(3).ifEmpty { listOf("1.1.1.1", "8.8.8.8") }
        File(rootfs, "etc/resolv.conf").writeText(servers.joinToString("\n", postfix = "\n") { "nameserver $it" })
    }

    private fun removeStaging(staging: File) {
        check(staging.canonicalFile.parentFile == base.canonicalFile)
        if (!Files.exists(staging.toPath())) return
        Files.walkFileTree(staging.toPath(), object : SimpleFileVisitor<java.nio.file.Path>() {
            override fun visitFile(file: java.nio.file.Path, attrs: BasicFileAttributes): FileVisitResult { Files.delete(file); return FileVisitResult.CONTINUE }
            override fun postVisitDirectory(dir: java.nio.file.Path, error: java.io.IOException?): FileVisitResult { if (error != null) throw error; Files.delete(dir); return FileVisitResult.CONTINUE }
        })
    }
}
