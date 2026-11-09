package com.suixing.agent.skills

import com.suixing.agent.core.NativeCore
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import okio.Buffer

internal object NativeSkillCatalogJni {
    external fun nativeDocument(text: String): ByteArray?
    external fun nativeOneLine(text: String, limit: Int): String?
    external fun nativeCatalog(metadata: ByteArray): String?
}

internal data class NativeSkillDocument(
    val metadata: Map<String, String>,
    val body: String,
    val name: String?,
    val description: String,
)

/** Pure document ranges and catalogue metadata; filesystem/state/installation stay in SkillStore. */
internal object NativeSkillCatalog {
    private const val MAGIC = 0x314B5853
    private const val MAX_UNITS = 2 * 1024 * 1024
    private const val MAX_METADATA_BYTES = 8 * 1024 * 1024
    private const val MAX_ENTRIES = 4096
    private const val MAX_SKILLS = 16_384
    private const val MAX_KEYS = 65_536

    fun document(text: String): NativeSkillDocument? {
        if (!NativeCore.available || text.length > MAX_UNITS) return null
        val bytes = try { NativeSkillCatalogJni.nativeDocument(text) }
            catch (_: UnsatisfiedLinkError) { return null } ?: return null
        fun valid(condition: Boolean) {
            if (!condition) throw IOException("原生技能文档响应无效")
        }
        valid(bytes.size in 32..(32 + MAX_ENTRIES * 16))
        val input = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        fun integer(): Int { valid(input.remaining() >= 4); return input.int }
        fun range(start: Int, length: Int): String {
            valid(start >= 0 && length >= 0 && start.toLong() + length <= text.length)
            return text.substring(start, start + length)
        }
        valid(integer() == MAGIC)
        val body = range(integer(), integer())
        val nameStart = integer()
        val nameLength = integer()
        val name = if (nameStart == -1) {
            valid(nameLength == 0)
            null
        } else range(nameStart, nameLength)
        val description = range(integer(), integer())
        val count = integer()
        valid(count in 0..MAX_ENTRIES && input.remaining().toLong() == count * 16L)
        val metadata = LinkedHashMap<String, String>()
        repeat(count) {
            val key = range(integer(), integer())
            val value = range(integer(), integer())
            valid(!metadata.containsKey(key))
            metadata[key] = value
        }
        valid(!input.hasRemaining())
        return NativeSkillDocument(metadata, body, name, description)
    }

    /** Only the unchanged default Java regex contract is eligible for the native fixed scanner. */
    fun oneLine(raw: String, limit: Int, regexCompatible: Boolean): String? {
        if (!regexCompatible || limit < 0 || raw.length > MAX_UNITS || !NativeCore.available) return null
        return try { NativeSkillCatalogJni.nativeOneLine(raw, limit) }
            catch (_: UnsatisfiedLinkError) { null }
    }

    fun catalog(skills: List<Skill>, preamble: String, regexCompatible: Boolean): String? {
        if (!regexCompatible || skills.size > MAX_SKILLS || !NativeCore.available) return null
        val keys = ArrayList<String>()
        val indices = LinkedHashMap<String, Int>()
        var poolBytes = 0L
        fun key(value: String): Int {
            indices[value]?.let { return it }
            val added = 4L + value.length * 2L
            if (keys.size >= MAX_KEYS || poolBytes + added > MAX_METADATA_BYTES) throw MetadataLimit()
            poolBytes += added
            val index = keys.size
            keys += value
            indices[value] = index
            return index
        }
        val metadata = try {
            val preambleIndex = key(preamble)
            val rows = IntArray(skills.size * 3)
            skills.forEachIndexed { index, skill ->
                rows[index * 3] = key(skill.id)
                rows[index * 3 + 1] = key(skill.name)
                rows[index * 3 + 2] = key(skill.description)
            }
            if (20L + poolBytes + rows.size * 4L > MAX_METADATA_BYTES) throw MetadataLimit()
            val output = Buffer().writeIntLe(MAGIC).writeIntLe(SKILL_LINE_CHARS)
                .writeIntLe(keys.size).writeIntLe(skills.size).writeIntLe(preambleIndex)
            for (value in keys) {
                output.writeIntLe(value.length)
                for (unit in value) output.writeShortLe(unit.code)
            }
            rows.forEach { output.writeIntLe(it) }
            output.readByteArray()
        } catch (_: MetadataLimit) {
            return null
        }
        return try { NativeSkillCatalogJni.nativeCatalog(metadata) }
            catch (_: UnsatisfiedLinkError) { null }
    }

    private class MetadataLimit : RuntimeException()
}
