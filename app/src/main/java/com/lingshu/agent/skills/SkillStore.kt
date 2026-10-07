package com.lingshu.agent.skills

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * A skill is a folder with a `SKILL.md` inside (or a loose `.md` file): optional
 * YAML frontmatter with `name` and `description`, then the instructions.
 *
 * Only the catalogue — names and one-line descriptions — is always in the
 * prompt; the body is fetched with the `skill_read` tool on demand. That is what
 * keeps a large skill library from eating the context window.
 */
data class Skill(
    val id: String,
    val name: String,
    val description: String,
    val body: String,
    val path: String,
)

class SkillStore(private val context: Context) {
    /** Where users drop skill folders; reachable through any file manager. */
    val root: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, SKILLS_DIR)

    suspend fun list(): List<Skill> = withContext(Dispatchers.IO) { scan(root) }

    suspend fun find(query: String): Skill? = withContext(Dispatchers.IO) {
        val wanted = query.trim()
        if (wanted.isEmpty()) return@withContext null
        scan(root).firstOrNull { it.id.equals(wanted, true) || it.name.equals(wanted, true) }
    }

    suspend fun save(id: String, content: String): Skill = withContext(Dispatchers.IO) {
        val folder = File(root, sanitizeId(id))
        if (!folder.exists() && !folder.mkdirs()) {
            throw IllegalStateException("无法创建技能目录：${folder.absolutePath}")
        }
        val file = File(folder, SKILL_FILE)
        file.writeText(content)
        parse(folder, file)
    }

    suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) {
        val folder = File(root, sanitizeId(id))
        val loose = File(root, "$id.md")
        when {
            folder.isDirectory -> folder.deleteRecursively()
            loose.isFile -> loose.delete()
            else -> false
        }
    }

    /**
     * Copies a user-picked directory into the skills folder: every sub-directory
     * becomes one skill (its `SKILL.md` is the entry point) and loose `.md` files
     * at the top level become single-file skills. Returns how many skills landed.
     */
    suspend fun importTree(uri: Uri): Int = withContext(Dispatchers.IO) {
        val tree = DocumentFile.fromTreeUri(context, uri) ?: return@withContext 0
        var count = 0
        for (child in tree.listFiles()) {
            val name = child.name ?: continue
            if (child.isDirectory) {
                if (copyDirectory(child, File(root, sanitizeId(name)), 0) > 0) count++
            } else if (name.endsWith(".md", ignoreCase = true)) {
                if (copyFile(child, File(root, name))) count++
            }
        }
        count
    }

    private fun copyDirectory(source: DocumentFile, target: File, depth: Int): Int {
        if (depth > MAX_IMPORT_DEPTH) return 0
        if (!target.exists() && !target.mkdirs()) return 0
        var copied = 0
        for (child in source.listFiles()) {
            if (copied >= MAX_IMPORT_FILES) break
            val name = child.name ?: continue
            copied += if (child.isDirectory) {
                copyDirectory(child, File(target, name), depth + 1)
            } else if (copyFile(child, File(target, name))) {
                1
            } else {
                0
            }
        }
        return copied
    }

    private fun copyFile(source: DocumentFile, target: File): Boolean {
        val length = source.length()
        if (length > MAX_IMPORT_BYTES) return false
        return runCatching {
            context.contentResolver.openInputStream(source.uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: return false
            true
        }.getOrDefault(false)
    }

    /** Wraps a body in the frontmatter a skill file is expected to carry. */
    fun compose(id: String, description: String, body: String): String = buildString {
        appendLine("---")
        appendLine("name: ${id.lineSequence().first().trim()}")
        appendLine("description: ${description.lineSequence().first().trim()}")
        appendLine("---")
        appendLine()
        append(body.trim())
        appendLine()
    }

    /** The always-in-context catalogue. */
    fun catalog(skills: List<Skill>): String {
        if (skills.isEmpty()) return ""
        return buildString {
            appendLine("## 可用技能")
            appendLine("技能是别人写好的操作手册。下面只有名字和用途；真正要用某个技能时，先调用 skill_read 读它的完整说明，再照着做。")
            for (skill in skills) {
                appendLine("- ${skill.name}（id: ${skill.id}）：${skill.description}")
            }
        }.trim()
    }

    private fun scan(dir: File): List<Skill> {
        if (!dir.isDirectory) return emptyList()
        val out = mutableListOf<Skill>()
        for (child in dir.listFiles().orEmpty().sortedBy { it.name.lowercase() }) {
            when {
                child.isDirectory -> {
                    val file = SKILL_FILE_NAMES
                        .map { File(child, it) }
                        .firstOrNull { it.isFile }
                    if (file != null) out += parse(child, file)
                }

                child.isFile && child.name.endsWith(".md", ignoreCase = true) -> out += parse(dir, child)
            }
        }
        return out
    }

    private fun parse(folder: File, file: File): Skill {
        val raw = file.readText()
        val (meta, body) = splitFrontmatter(raw)
        val id = if (file.name.equals(SKILL_FILE, ignoreCase = true)) {
            folder.name
        } else {
            file.nameWithoutExtension
        }
        val name = meta["name"]?.takeIf { it.isNotBlank() } ?: id
        val description = meta["description"]?.takeIf { it.isNotBlank() }
            ?: body.lineSequence().firstOrNull { it.isNotBlank() }?.take(120).orEmpty()
        return Skill(
            id = id,
            name = name,
            description = description,
            body = body,
            path = file.absolutePath,
        )
    }

    private fun splitFrontmatter(text: String): Pair<Map<String, String>, String> {
        val trimmed = text.trimStart()
        if (!trimmed.startsWith("---")) return emptyMap<String, String>() to text.trim()
        val end = trimmed.indexOf("\n---", startIndex = 3)
        if (end < 0) return emptyMap<String, String>() to text.trim()
        val header = trimmed.substring(3, end).trim()
        val body = trimmed.substring(end + 4).trimStart('\n', '\r', ' ', '\t')
        val meta = header.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && it.contains(':') }
            .associate { line ->
                line.substringBefore(':').trim() to
                    line.substringAfter(':').trim().trim('"', '\'', ' ')
            }
        return meta to body
    }

    /** Skill ids become directory names, so separators and dot-only names are refused. */
    private fun sanitizeId(id: String): String {
        val cleaned = buildString {
            for (ch in id.trim()) {
                append(if (ch.isLetterOrDigit() || ch == '-' || ch == '_' || ch == '.') ch else '-')
            }
        }.trim('.', '-', ' ')
        return cleaned.ifEmpty { "skill" }
    }

    private companion object {
        const val SKILLS_DIR = "skills"
        const val SKILL_FILE = "SKILL.md"
        const val MAX_IMPORT_BYTES = 2L * 1024L * 1024L
        const val MAX_IMPORT_FILES = 200
        const val MAX_IMPORT_DEPTH = 6
        val SKILL_FILE_NAMES = listOf("SKILL.md", "skill.md", "Skill.md")
    }
}
