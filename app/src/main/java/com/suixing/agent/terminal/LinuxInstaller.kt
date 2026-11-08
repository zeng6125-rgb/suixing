package com.suixing.agent.terminal

import android.content.Context
import android.os.Build
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import okhttp3.OkHttpClient
import okhttp3.Request
import org.tukaani.xz.XZInputStream
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

internal data class LinuxDistro(
    val id: String, val title: String, val version: String, val url: String,
    val sha256: String, val downloadBytes: Long, val installedBytes: Long,
    val prefix: String = "", val xz: Boolean = false,
) {
    val toolsCommand: String get() = if (id == "alpine")
        "apk update && apk add python3 py3-pip nodejs npm git ca-certificates"
    else "apt-get update && apt-get install -y python3 python3-pip nodejs npm git ca-certificates"
}

internal val LINUX_DISTRIBUTIONS = listOf(
    LinuxDistro("alpine", "Alpine", "3.22.2",
        "https://dl-cdn.alpinelinux.org/alpine/v3.22/releases/aarch64/alpine-minirootfs-3.22.2-aarch64.tar.gz",
        "6bf491907b705caa5fc65773fbbc1d0954530b6c455d4446f670a4c1fdcea489", 3_981_043, 64L * 1024 * 1024),
    LinuxDistro("debian", "Debian", "12 Bookworm",
        "https://github.com/termux/proot-distro/releases/download/v4.17.3/debian-bookworm-aarch64-pd-v4.17.3.tar.xz",
        "3a841a794ae5999b33e33b329582ed0379d4f54ca62c6ce5a8eb9cff5ef8900b", 42_912_980, 384L * 1024 * 1024,
        prefix = "debian-bookworm-aarch64", xz = true),
)

internal enum class LinuxPhase { NOT_INSTALLED, DOWNLOADING, VERIFYING, EXTRACTING, CHECKING, READY, FAILED, REMOVING }
internal data class LinuxState(
    val phase: LinuxPhase = LinuxPhase.NOT_INSTALLED,
    val bytes: Long = 0, val message: String? = null, val available: Boolean = false,
)

/** Optional, transactional rootfs data installation. Never downloads an Android executable. */
internal class LinuxInstaller(private val context: Context, private val scope: CoroutineScope) {
    private val directory = File(context.filesDir, "linux")
    private val nativeDir get() = context.applicationInfo.nativeLibraryDir
    private val _states = MutableStateFlow(LINUX_DISTRIBUTIONS.associate { it.id to LinuxState() })
    val states = _states.asStateFlow()
    private val operation = Mutex()
    private var installation: Job? = null
    @Volatile private var activeCall: okhttp3.Call? = null
    private val client by lazy {
        OkHttpClient.Builder().followSslRedirects(false).callTimeout(15, TimeUnit.MINUTES).build()
    }
    val supported: Boolean get() =
        Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a" && File(nativeDir, "libproot.so").canExecute()

    suspend fun load() = withContext(Dispatchers.IO) {
        directory.mkdirs()
        for (distro in LINUX_DISTRIBUTIONS) {
            val target = File(directory, distro.id)
            val backup = File(directory, "." + distro.id + "-old")
            if (!target.exists() && backup.isDirectory) check(backup.renameTo(target)) { "Linux 环境恢复失败" }
            val ready = File(target, "ready").takeIf { it.isFile }?.readText() == distro.sha256
            _states.update { current ->
                if (current[distro.id]?.phase in busyPhases) current else current +
                    (distro.id to LinuxState(if (ready) LinuxPhase.READY else LinuxPhase.NOT_INSTALLED, available = ready))
            }
        }
    }

    fun install(distro: LinuxDistro) {
        if (!operation.tryLock()) return
        // Publish the job and its completion owner before dispatch. A cancelled lazy job
        // never enters the body, so the body's finally cannot be the mutex owner.
        val pending = scope.launch(Dispatchers.IO, start = CoroutineStart.LAZY) {
            val target = File(directory, distro.id)
            val staging = File(directory, "." + distro.id + "-install")
            val backup = File(directory, "." + distro.id + "-old")
            val oldAvailable = _states.value[distro.id]?.available == true
            fun update(phase: LinuxPhase, bytes: Long = 0, message: String? = null) {
                _states.update { it + (distro.id to LinuxState(phase, bytes, message, oldAvailable)) }
            }
            try {
                check(supported) { "当前 CPU 或安装包不支持内置 Linux（仅 ARM64）" }
                directory.mkdirs()
                check(directory.usableSpace > distro.installedBytes + distro.downloadBytes + 64L * 1024 * 1024) { "可用存储空间不足" }
                deleteLinuxTree(staging.toPath())
                staging.mkdirs()
                val archive = File(staging, "rootfs.archive")
                update(LinuxPhase.DOWNLOADING)
                val call = client.newCall(Request.Builder().url(distro.url).build())
                activeCall = call
                val cancellation = currentCoroutineContext()[Job]!!.invokeOnCompletion { if (it != null) call.cancel() }
                try {
                    call.execute().use { response ->
                        check(response.isSuccessful) { "下载失败：HTTP " + response.code }
                        check(response.request.url.isHttps) { "Linux 来源必须为 HTTPS" }
                        val body = response.body
                        val length = body.contentLength()
                        check(length < 0 || length == distro.downloadBytes) { "Linux 归档大小不匹配" }
                        var count = 0L
                        val digest = MessageDigest.getInstance("SHA-256")
                        archive.outputStream().use { out ->
                            body.byteStream().use { input ->
                                val block = ByteArray(64 * 1024)
                                while (true) {
                                    currentCoroutineContext().ensureActive()
                                    val read = input.read(block)
                                    if (read < 0) break
                                    count += read
                                    check(count <= distro.downloadBytes) { "Linux 归档超出预期大小" }
                                    digest.update(block, 0, read); out.write(block, 0, read)
                                    update(LinuxPhase.DOWNLOADING, count)
                                }
                            }
                        }
                        update(LinuxPhase.VERIFYING, count)
                        check(count == distro.downloadBytes && digest.digest().joinToString("") { "%02x".format(it) } == distro.sha256) {
                            "Linux 归档 SHA-256 校验失败"
                        }
                    }
                } finally { cancellation.dispose(); activeCall = null }
                currentCoroutineContext().ensureActive()
                update(LinuxPhase.EXTRACTING)
                val root = File(staging, "rootfs")
                val job = currentCoroutineContext()[Job]!!
                archive.inputStream().buffered().use { source ->
                    val input = if (distro.xz) XZInputStream(source, 64 * 1024) else GZIPInputStream(source)
                    input.use {
                        LinuxArchive(root.toPath(), distro.prefix).extract(it) { job.ensureActive() }
                    }
                }
                configure(root)
                update(LinuxPhase.CHECKING)
                probe(plan(distro, root, probe = true), job)
                File(staging, "ready").writeText(distro.sha256)
                archive.delete()
                currentCoroutineContext().ensureActive()
                withContext(NonCancellable) {
                    deleteLinuxTree(backup.toPath())
                    if (target.exists()) check(target.renameTo(backup)) { "无法保存旧 Linux 环境" }
                    if (!staging.renameTo(target)) {
                        if (backup.exists()) backup.renameTo(target)
                        error("Linux 环境替换失败，已保留旧环境")
                    }
                    deleteLinuxTree(backup.toPath())
                }
                _states.update { it + (distro.id to LinuxState(LinuxPhase.READY, available = true,
                    message = "已通过本机 shell 和包管理器启动检查")) }
            } catch (error: CancellationException) {
                update(if (oldAvailable) LinuxPhase.READY else LinuxPhase.NOT_INSTALLED, message = "安装已取消")
                throw error
            } catch (error: Exception) {
                update(LinuxPhase.FAILED, message = error.message ?: error.javaClass.simpleName)
            } finally {
                withContext(NonCancellable) { runCatching { deleteLinuxTree(staging.toPath()) } }
            }
        }
        installation = pending
        pending.invokeOnCompletion { operation.unlock() }
        pending.start()
    }

    fun cancel() { installation?.cancel(); activeCall?.cancel() }

    suspend fun remove(distro: LinuxDistro) = withContext(Dispatchers.IO) {
        check(operation.tryLock()) { "请先完成或取消当前安装" }
        try {
            _states.update { it + (distro.id to LinuxState(LinuxPhase.REMOVING)) }
            deleteLinuxTree(File(directory, distro.id).toPath())
            deleteLinuxTree(File(directory, "." + distro.id + "-old").toPath())
            _states.update { it + (distro.id to LinuxState()) }
        } finally { operation.unlock() }
    }

    suspend fun launch(id: String): TerminalLaunch = withContext(Dispatchers.IO) {
        val distro = LINUX_DISTRIBUTIONS.firstOrNull { it.id == id } ?: error("未知 Linux 环境")
        check(supported && _states.value[id]?.available == true) { "请先在终端页安装并检查 Linux 环境" }
        plan(distro, File(File(directory, id), "rootfs"))
    }

    private fun plan(distro: LinuxDistro, root: File, probe: Boolean = false): TerminalLaunch {
        val workspace = TerminalNative.appShellCwd(context)
        val temp = File(context.cacheDir, "linux-tmp").apply { mkdirs() }
        val environment = mapOf(
            "PATH" to "$nativeDir:/system/bin", "HOME" to workspace, "TMPDIR" to temp.absolutePath,
            "PROOT_TMP_DIR" to temp.absolutePath, "PROOT_LOADER" to "$nativeDir/libproot-loader.so",
            "PROOT_LOADER_32" to "$nativeDir/libproot-loader32.so", "LD_LIBRARY_PATH" to nativeDir,
            "TERM" to "xterm-256color", "LANG" to "C.UTF-8",
        )
        val command = mutableListOf("$nativeDir/libproot.so", "--link2symlink", "--kill-on-exit", "--sysvipc",
            "-0", "-r", root.absolutePath, "-b", "/dev", "-b", "/proc", "-b", "/sys",
            "-b", "$workspace:/workspace", "-b", temp.absolutePath + ":/tmp", "-w", "/workspace",
            "/usr/bin/env", "-i", "HOME=/root", "USER=root", "LOGNAME=root", "TERM=xterm-256color",
            "LANG=C.UTF-8", "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
            "TMPDIR=/tmp", "PS1=$ ", "/bin/sh")
        if (probe) command += listOf("-c", "printf 'suixing-linux-ok\\n'; uname -m; " +
            if (distro.id == "alpine") "apk --version" else "apt-get --version")
        else command += "-i"
        return TerminalLaunch(command, environment, workspace, distro.title + " · sh", distro.id)
    }

    private fun configure(root: File) {
        File(root, "workspace").mkdirs()
        File(root, "tmp").mkdirs()
        File(root, "root").mkdirs()
        val resolver = File(root, "etc/resolv.conf")
        java.nio.file.Files.deleteIfExists(resolver.toPath())
        resolver.writeText("nameserver 1.1.1.1\nnameserver 8.8.8.8\n")
        val apt = File(root, "etc/apt")
        apt.walkTopDown().filter { it.isFile && (it.extension == "list" || it.extension == "sources") }.forEach { file ->
            val content = file.readText()
            file.writeText(content.replace("http://deb.debian.org", "https://deb.debian.org")
                .replace("http://security.debian.org", "https://security.debian.org"))
        }
    }

    private suspend fun probe(plan: TerminalLaunch, job: Job) {
        val native = TerminalNative.nativeOpenProcess(plan.argv.toTypedArray(),
            plan.environment.map { (k, v) -> "$k=$v" }.toTypedArray(), plan.cwd, 80, 24)
        val fd = native[0].toInt(); val pid = native[1].toInt()
        val output = StringBuilder()
        var eof = false
        try {
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
            while (System.nanoTime() < deadline) {
                job.ensureActive()
                val bytes = TerminalNative.nativeRead(fd, 16 * 1024, 200) ?: continue
                if (bytes.isEmpty()) { eof = true; break }
                if (output.length < 16 * 1024) output.append(bytes.toString(Charsets.UTF_8))
            }
            check(eof && output.contains("suixing-linux-ok") && output.contains("aarch64")) {
                "当前系统未通过 Linux 动态程序启动检查：" + output.toString().takeLast(1500)
            }
        } finally {
            withContext(NonCancellable) {
                runCatching { TerminalNative.nativeSignalGroup(pid, SIGKILL) }
                val exit = runCatching { TerminalNative.nativeWait(pid) }.getOrDefault(-1)
                TerminalNative.nativeClose(fd)
                check(!eof || exit == 0) { "Linux 包管理器启动失败（退出码 $exit）：" + output.toString().takeLast(1500) }
            }
        }
    }

    companion object {
        val busyPhases = setOf(LinuxPhase.DOWNLOADING, LinuxPhase.VERIFYING, LinuxPhase.EXTRACTING, LinuxPhase.CHECKING, LinuxPhase.REMOVING)
    }
}
