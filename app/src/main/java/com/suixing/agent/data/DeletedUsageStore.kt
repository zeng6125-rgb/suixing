package com.suixing.agent.data

import com.suixing.agent.core.decodeCoreObject
import com.suixing.agent.core.encodeCoreBytes
import com.suixing.agent.core.AtomicFiles
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Durable token summaries only; deleting a conversation does not retain its text or files. */
internal class DeletedUsageStore(private val directory: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun read(issues: MutableList<String>): MutableMap<String, ChatUsage> {
        val files = directory.listFiles { file -> file.extension == "json" }
            ?: if (!directory.exists()) emptyArray() else throw IOException("无法读取已删除会话的用量记录。")
        val entries = linkedMapOf<String, ChatUsage>()
        for (file in files) {
            try {
                val entry = json.decodeCoreObject(DeletedUsageEntry.serializer(), file.readText())
                require(entry.id.isNotBlank() && entry.usage.deletedAt > 0L)
                entries[entry.id] = entry.usage
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                issues += "部分已删除会话的用量记录无法读取，已保留原文件。"
            }
        }
        return entries
    }

    fun save(id: String, usage: ChatUsage, zoneId: String): ChatUsage {
        if (!directory.isDirectory && !directory.mkdirs()) throw IOException("无法保存删除前的用量记录。")
        val saved = usage.copy(deletedAt = usage.deletedAt.takeIf { it > 0L }
            ?: System.currentTimeMillis().coerceAtLeast(1L))
        val name = MessageDigest.getInstance("SHA-256").digest(id.toByteArray(Charsets.UTF_8))
            .joinToString("") { (it.toInt() and 0xff).toString(16).padStart(2, '0') }
        AtomicFiles.writeBytes(File(directory, "$name.json"),
            json.encodeCoreBytes(DeletedUsageEntry.serializer(), DeletedUsageEntry(id, saved, zoneId)))
        return saved
    }
}

@Serializable
private data class DeletedUsageEntry(val id: String, val usage: ChatUsage, val zoneId: String)
