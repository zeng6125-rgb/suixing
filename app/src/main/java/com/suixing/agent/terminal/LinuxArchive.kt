package com.suixing.agent.terminal

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes

/** Rootfs is untrusted data: never extract through links, device nodes or a host absolute path. */
internal class LinuxArchive(
    private val root: Path,
    private val stripPrefix: String = "",
    private val maxBytes: Long = 2L * 1024 * 1024 * 1024,
    private val maxFiles: Int = 150_000,
) {
    private data class Link(val path: Path, val target: String, val hard: Boolean)
    fun extract(input: InputStream, checkpoint: (Long) -> Unit = {}) {
        Files.createDirectories(root)
        val links = mutableListOf<Link>()
        val paths = mutableSetOf<Path>()
        var bytes = 0L
        var files = 0
        var longName: String? = null
        var longLink: String? = null
        var pax = emptyMap<String, String>()
        val header = ByteArray(512)
        while (true) {
            readFully(input, header)
            if (header.all { it == 0.toByte() }) break
            val checksum = octal(header, 148, 8)
            val actual = header.indices.sumOf { if (it in 148..155) 32L else header[it].toLong() and 255 }
            require(checksum == actual) { "rootfs tar 校验失败" }
            val size = octal(header, 124, 12)
            require(size in 0..maxBytes && bytes <= maxBytes - size) { "rootfs 解压大小超限" }
            bytes += size
            require(++files <= maxFiles) { "rootfs 文件数超限" }
            val type = header[156].toInt().toChar()
            if (type in listOf('x', 'g', 'L', 'K')) {
                require(size <= 64 * 1024) { "rootfs 扩展头过长" }
                val data = ByteArray(size.toInt()).also { readFully(input, it) }
                val value = data.toString(Charsets.UTF_8).trimEnd('\u0000')
                when (type) {
                    'L' -> longName = value
                    'K' -> longLink = value
                    'x' -> pax = parsePax(data)
                    'g' -> require(parsePax(data).keys.none { it in listOf("path", "linkpath", "size") }) { "不支持全局 tar 路径覆盖" }
                }
            } else {
                require("size" !in pax) { "不支持覆盖 tar 大小" }
                val headerName = field(header, 0, 100)
                // GNU tar uses this area for timestamps/sparse metadata, not a path prefix.
                val prefix = if (field(header, 257, 6) == "ustar") field(header, 345, 155) else ""
                val rawName = pax["path"] ?: longName ?: (if (prefix.isEmpty()) headerName else "$prefix/$headerName")
                val rawLink = pax["linkpath"] ?: longLink ?: field(header, 157, 100)
                longName = null; longLink = null
                pax = emptyMap()
                val path = entryPath(rawName)
                when (type) {
                    '0', '\u0000', '7' -> {
                        require(path != root && paths.add(path)) { "rootfs 重复文件路径" }
                        Files.createDirectories(path.parent)
                        Files.newOutputStream(path).use { out ->
                            var remaining = size
                            val block = ByteArray(64 * 1024)
                            while (remaining > 0) {
                                val count = input.read(block, 0, minOf(remaining, block.size.toLong()).toInt())
                                check(count > 0) { "rootfs 归档不完整" }
                                out.write(block, 0, count)
                                remaining -= count
                                checkpoint(bytes - remaining)
                            }
                        }
                        path.toFile().setExecutable(octal(header, 100, 8) and 73L != 0L, true)
                    }
                    '5' -> {
                        Files.createDirectories(path)
                        skipFully(input, size)
                    }
                    '1', '2' -> {
                        require(path != root && paths.add(path) && !Files.exists(path)) { "rootfs 链接与文件冲突" }
                        links += Link(path, rawLink, type == '1')
                        skipFully(input, size)
                    }
                    // Devices/FIFOs are supplied explicitly by the runtime, never created on the host.
                    '3', '4', '6' -> skipFully(input, size)
                    else -> error("不支持的 rootfs tar 类型：" + type.code)
                }
            }
            skipFully(input, (512 - size % 512) % 512)
            checkpoint(bytes)
        }
        // All regular writes finish before any symbolic link exists.
        for (link in links.filter { it.hard }) {
            val target = entryPath(link.target)
            require(Files.isRegularFile(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) { "rootfs 硬链接目标无效" }
            Files.createDirectories(link.path.parent)
            Files.createLink(link.path, target)
        }
        for (link in links.filterNot { it.hard }) {
            val target = if (link.target.startsWith("/")) root.resolve(link.target.trimStart('/')).normalize()
                else link.path.parent.resolve(link.target).normalize()
            require(target.startsWith(root)) { "rootfs 符号链接越界" }
            Files.createDirectories(link.path.parent)
            // Rewrite guest absolute links into relative links confined to this rootfs on the host.
            val relative = link.path.parent.relativize(target)
            Files.createSymbolicLink(link.path, if (relative.toString().isEmpty()) root.fileSystem.getPath(".") else relative)
        }
    }

    private fun entryPath(raw: String): Path {
        require(!raw.startsWith("/") && '\\' !in raw && '\u0000' !in raw) { "rootfs 路径无效" }
        val clean = raw.removePrefix("./").trimEnd('/')
        val name = if (stripPrefix.isEmpty()) clean else {
            require(clean == stripPrefix || clean.startsWith("$stripPrefix/")) { "rootfs 顶级目录不匹配" }
            clean.removePrefix(stripPrefix).trimStart('/')
        }
        val path = root.resolve(name).normalize()
        require(path.startsWith(root)) { "rootfs 文件路径越界" }
        return path
    }
    private fun field(data: ByteArray, offset: Int, size: Int) =
        data.copyOfRange(offset, offset + size).takeWhile { it != 0.toByte() }.toByteArray().toString(Charsets.UTF_8)
    private fun octal(data: ByteArray, offset: Int, size: Int): Long =
        field(data, offset, size).trim().ifEmpty { "0" }.toLong(8)
    private fun readFully(input: InputStream, buffer: ByteArray) {
        var at = 0
        while (at < buffer.size) {
            val count = input.read(buffer, at, buffer.size - at)
            check(count > 0) { "rootfs 归档不完整" }
            at += count
        }
    }
    private fun skipFully(input: InputStream, size: Long) {
        var left = size
        val buffer = ByteArray(8192)
        while (left > 0) {
            val count = input.read(buffer, 0, minOf(left, buffer.size.toLong()).toInt())
            check(count > 0) { "rootfs 归档不完整" }
            left -= count
        }
    }
    private fun parsePax(data: ByteArray): Map<String, String> {
        val values = mutableMapOf<String, String>()
        var position = 0
        while (position < data.size) {
            val space = (position until data.size).firstOrNull { data[it] == 32.toByte() } ?: error("tar PAX 长度无效")
            val length = data.copyOfRange(position, space).toString(Charsets.US_ASCII).toInt()
            require(length > space - position + 1 && position + length <= data.size) { "tar PAX 记录无效" }
            val value = data.copyOfRange(space + 1, position + length - 1).toString(Charsets.UTF_8)
            values[value.substringBefore('=')] = value.substringAfter('=')
            position += length
        }
        require("size" !in values) { "不支持 tar 大小覆盖" }
        return values
    }
}

/** Never follow guest-created symlinks when removing/replacing a distribution. */
internal fun deleteLinuxTree(root: Path) {
    if (!Files.exists(root, java.nio.file.LinkOption.NOFOLLOW_LINKS)) return
    Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
        override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
            Files.delete(file)
            return FileVisitResult.CONTINUE
        }
        override fun postVisitDirectory(dir: Path, error: java.io.IOException?): FileVisitResult {
            if (error != null) throw error
            Files.delete(dir)
            return FileVisitResult.CONTINUE
        }
    })
}
