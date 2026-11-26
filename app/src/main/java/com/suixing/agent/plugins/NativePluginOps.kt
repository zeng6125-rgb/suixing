package com.suixing.agent.plugins

import com.suixing.agent.core.NativeCore
import java.io.ByteArrayOutputStream

internal object NativePluginOpsJni {
    @JvmStatic
    external fun nativeScanCommand(command: String, outStatus: IntArray): ByteArray?

    @JvmStatic
    external fun nativeExtractTemplateVars(text: String, outStatus: IntArray): ByteArray?

    @JvmStatic
    external fun nativeRenderTemplate(
        template: String,
        packedVars: ByteArray,
        mode: Int,
        outStatus: IntArray,
    ): ByteArray?
}

internal object NativePluginOps {
    const val MODE_RAW = 0
    const val MODE_SHELL_QUOTE = 1
    const val MODE_HEADER_SAFE = 2
    const val MODE_JSON_AWARE = 3
    const val MODE_URL_ENCODE = 4

    private const val MAX_INPUT_UNITS = 4 * 1024 * 1024
    private const val MAX_PACKED_BYTES = 8 * 1024 * 1024

    sealed interface DangerHit {
        data class RmForce(val target: String) : DangerHit
        data class Blacklist(val index: Int) : DangerHit
    }

    fun scanCommandDanger(command: String): List<DangerHit>? {
        if (!NativeCore.available || command.length > MAX_INPUT_UNITS) return null
        val status = IntArray(1)
        val raw = runCatching { NativePluginOpsJni.nativeScanCommand(command, status) }.getOrNull()
        if (status[0] != 0 || raw == null) return null
        if (raw.isEmpty()) return emptyList()
        return decodeDangerHits(raw)
    }

    fun extractTemplateVars(text: String): List<String>? {
        if (!NativeCore.available || text.length > MAX_INPUT_UNITS) return null
        if (!text.contains("{{")) return emptyList()
        val status = IntArray(1)
        val raw = runCatching { NativePluginOpsJni.nativeExtractTemplateVars(text, status) }.getOrNull()
        if (status[0] != 0 || raw == null) return null
        if (raw.isEmpty()) return emptyList()
        return decodeStringList(raw)
    }

    fun renderTemplate(
        template: String,
        vars: Map<String, String>,
        missing: MutableList<String>,
        mode: Int,
    ): String? {
        if (!NativeCore.available || template.length > MAX_INPUT_UNITS) return null
        if (!template.contains("{{")) return template
        val packed = packVars(vars) ?: return null
        val status = IntArray(1)
        val raw = runCatching {
            NativePluginOpsJni.nativeRenderTemplate(template, packed, mode, status)
        }.getOrNull()
        if (status[0] != 0 || raw == null) return null
        return decodeRenderOutput(raw, missing)
    }

    private fun packVars(vars: Map<String, String>): ByteArray? {
        if (vars.isEmpty()) return EMPTY_BYTES
        val out = ByteArrayOutputStream(64 + vars.size * 32)
        writeU32(out, vars.size)
        for ((k, v) in vars) {
            val kb = k.toByteArray(Charsets.UTF_8)
            val vb = v.toByteArray(Charsets.UTF_8)
            writeU32(out, kb.size)
            out.write(kb)
            writeU32(out, vb.size)
            out.write(vb)
            if (out.size() > MAX_PACKED_BYTES) return null
        }
        return out.toByteArray()
    }

    private fun decodeDangerHits(raw: ByteArray): List<DangerHit>? {
        if (raw.size < 4) return null
        val count = readU32(raw, 0)
        if (count < 0) return null
        var off = 4
        val hits = ArrayList<DangerHit>(count.coerceAtMost(64))
        repeat(count) {
            if (off >= raw.size) return null
            val kind = raw[off++].toInt() and 0xff
            if (kind == 0) {
                if (off + 4 > raw.size) return null
                val len = readU32(raw, off)
                off += 4
                if (len < 0 || off + len > raw.size) return null
                val target = raw.decodeToString(off, off + len)
                off += len
                hits.add(DangerHit.RmForce(target))
            } else {
                hits.add(DangerHit.Blacklist(kind - 1))
            }
        }
        return if (off == raw.size) hits else null
    }

    private fun decodeStringList(raw: ByteArray): List<String>? {
        if (raw.size < 4) return null
        val count = readU32(raw, 0)
        if (count < 0) return null
        var off = 4
        val list = ArrayList<String>(count.coerceAtMost(64))
        repeat(count) {
            if (off + 4 > raw.size) return null
            val len = readU32(raw, off)
            off += 4
            if (len < 0 || off + len > raw.size) return null
            list.add(raw.decodeToString(off, off + len))
            off += len
        }
        return if (off == raw.size) list else null
    }

    private fun decodeRenderOutput(raw: ByteArray, missing: MutableList<String>): String? {
        if (raw.size < 8) return null
        val missCount = readU32(raw, 0)
        if (missCount < 0) return null
        var off = 4
        val newlyMissing = if (missCount > 0) ArrayList<String>(missCount.coerceAtMost(32)) else null
        repeat(missCount) {
            if (off + 4 > raw.size) return null
            val len = readU32(raw, off)
            off += 4
            if (len < 0 || off + len > raw.size) return null
            newlyMissing?.add(raw.decodeToString(off, off + len))
            off += len
        }
        if (off + 4 > raw.size) return null
        val outLen = readU32(raw, off)
        off += 4
        if (outLen < 0 || off + outLen != raw.size) return null
        val rendered = raw.decodeToString(off, off + outLen)
        newlyMissing?.forEach { key ->
            if (key !in missing) missing += key
        }
        return rendered
    }

    private fun writeU32(out: ByteArrayOutputStream, v: Int) {
        out.write(v and 0xff)
        out.write((v ushr 8) and 0xff)
        out.write((v ushr 16) and 0xff)
        out.write((v ushr 24) and 0xff)
    }

    private fun readU32(raw: ByteArray, off: Int): Int =
        (raw[off].toInt() and 0xff) or
            ((raw[off + 1].toInt() and 0xff) shl 8) or
            ((raw[off + 2].toInt() and 0xff) shl 16) or
            ((raw[off + 3].toInt() and 0xff) shl 24)

    private val EMPTY_BYTES = ByteArray(0)
}
