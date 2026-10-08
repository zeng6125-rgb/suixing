package com.suixing.agent.skills

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.SetSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * A skill is a folder with a `SKILL.md` inside (or a loose `.md` file): optional
 * YAML frontmatter with `name` and `description`, then the instructions.
 *
 * Only the catalogue — names and one-line descriptions — is always in the
 * prompt; the body is fetched with the `skill_read` tool on demand. That is what
 * keeps a large skill library from eating the context window.
 *
 * [enabled] is not read from the file: a skill the user switched off must stay on
 * disk untouched, so the flag lives in a side table (see [SkillStore]).
 */
data class Skill(
    val id: String,
    val name: String,
    val description: String,
    val body: String,
    val path: String,
    val enabled: Boolean = true,
)

class SkillStore(private val context: Context) {
    /** Where users drop skill folders; reachable through any file manager. */
    val root: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, SKILLS_DIR)

    /**
     * Internal rather than external on purpose: this is app state, not user
     * documents, and it must not be reachable from a file manager where a stray
     * edit could silently disable every skill.
     */
    private val stateFile: File
        get() = File(context.filesDir, STATE_FILE)

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
    }

    suspend fun list(): List<Skill> = withContext(Dispatchers.IO) {
        val found = scanSkills(root)
        val disabled = readDisabled()
        // Ids whose folder is gone: dropping them here is what keeps a re-imported
        // skill from coming back switched off because of an old entry.
        val stale = disabled.filterNot { id -> found.any { it.id == id } }
        if (stale.isNotEmpty()) writeDisabled(disabled - stale)
        found.map { it.copy(enabled = it.id !in disabled) }
    }

    /** The skill the model may actually use: a disabled one is not findable. */
    suspend fun find(query: String): Skill? = withContext(Dispatchers.IO) {
        val wanted = query.trim()
        if (wanted.isEmpty()) return@withContext null
        val disabled = readDisabled()
        scanSkills(root)
            .firstOrNull { it.id.equals(wanted, true) || it.name.equals(wanted, true) }
            ?.takeIf { it.id !in disabled }
    }

    suspend fun setEnabled(id: String, enabled: Boolean): Unit = withContext(Dispatchers.IO) {
        val disabled = readDisabled()
        val next = if (enabled) disabled - id else disabled + id
        if (next != disabled) writeDisabled(next)
    }

    suspend fun save(id: String, content: String): Skill = withContext(Dispatchers.IO) {
        val folder = File(root, sanitizeSkillId(id))
        if (!folder.exists() && !folder.mkdirs()) {
            throw IllegalStateException("无法创建技能目录：${folder.absolutePath}")
        }
        val file = File(folder, SKILL_FILE_NAME)
        file.writeText(content)
        parseSkill(folder, file).withState()
    }

    suspend fun delete(id: String): Boolean = withContext(Dispatchers.IO) {
        val folder = File(root, sanitizeSkillId(id))
        val loose = File(root, "$id.md")
        val removed = when {
            folder.isDirectory -> folder.deleteRecursively()
            loose.isFile -> loose.delete()
            else -> false
        }
        // A skill the model saves again under a deleted id must come back enabled;
        // `list()` would prune the entry eventually, but `save()` reads it first.
        if (removed) setEnabled(id, enabled = true)
        removed
    }

    /**
     * Copies a user-picked directory into the skills folder: every sub-directory
     * becomes one skill (its `SKILL.md` is the entry point) and loose `.md` files
     * at the top level become single-file skills. Returns how many skills landed.
     */
    suspend fun importTree(uri: Uri): Int = withContext(Dispatchers.IO) {
        val tree = DocumentFile.fromTreeUri(context, uri) ?: return@withContext 0
        ensureRoot()
        var count = 0
        for (child in tree.listFiles()) {
            val name = child.name ?: continue
            if (child.isDirectory) {
                if (copyDirectory(child, File(root, sanitizeSkillId(name)), 0) > 0) count++
            } else if (name.endsWith(".md", ignoreCase = true)) {
                if (copyFile(child, File(root, name))) count++
            }
        }
        count
    }

    /**
     * Copies one picked `.md` file in as a single-file skill — the shape a SKILL.md
     * arrives in when it is shared straight out of a chat or a browser download.
     * Returns the skill it became, or null when the pick was not a `.md` file.
     */
    suspend fun importFile(uri: Uri): Skill? = withContext(Dispatchers.IO) {
        val source = DocumentFile.fromSingleUri(context, uri) ?: return@withContext null
        val name = source.name.orEmpty()
        if (!name.endsWith(".md", ignoreCase = true)) return@withContext null
        ensureRoot()
        val target = File(root, name)
        if (!copyFile(source, target)) return@withContext null
        parseSkill(root, target).withState()
    }

    private fun ensureRoot() {
        if (!root.isDirectory && !root.mkdirs()) {
            throw IllegalStateException("无法创建技能目录：${root.absolutePath}")
        }
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

    /**
     * A state file that cannot be read must not hide the user's skills, so a
     * corrupt one degrades to "everything enabled" and the next write replaces it.
     */
    private fun readDisabled(): Set<String> {
        if (!stateFile.isFile) return emptySet()
        return try {
            json.decodeFromString(DISABLED_SERIALIZER, stateFile.readText())
        } catch (error: Exception) {
            emptySet()
        }
    }

    private fun writeDisabled(ids: Set<String>) {
        stateFile.writeText(json.encodeToString(DISABLED_SERIALIZER, ids))
    }

    private fun Skill.withState(): Skill = copy(enabled = id !in readDisabled())

    /** Wraps a body in the frontmatter a skill file is expected to carry. */
    fun compose(id: String, description: String, body: String): String = composeSkill(id, description, body)

    /**
     * The always-in-context catalogue, and the one place where "disabled" takes
     * effect for the model: [AgentEngine] passes the whole list in and gets only
     * the enabled ones back, so no prompt-building code has to know about the flag.
     */
    fun catalog(skills: List<Skill>): String = catalogOf(skills.filter { it.enabled })

    private companion object {
        const val SKILLS_DIR = "skills"
        const val STATE_FILE = "skill-state.json"
        const val MAX_IMPORT_BYTES = 2L * 1024L * 1024L
        const val MAX_IMPORT_FILES = 200
        const val MAX_IMPORT_DEPTH = 6
        val DISABLED_SERIALIZER = SetSerializer(String.serializer())
    }
}

/** The entry point inside a skill folder; the other spellings are tolerated. */
internal const val SKILL_FILE_NAME = "SKILL.md"
internal val SKILL_FILE_NAMES = listOf("SKILL.md", "skill.md", "Skill.md")

/**
 * Reads one skill file. Frontmatter is optional and every field degrades, because
 * a SKILL.md from the wild is only guaranteed to be a markdown document: the name
 * falls back to the folder (or file) name and the description to the first
 * non-blank line of the body.
 */
internal fun parseSkill(folder: File, file: File): Skill {
    val raw = file.readText()
    val (meta, body) = splitFrontmatter(raw)
    val id = if (file.name.equals(SKILL_FILE_NAME, ignoreCase = true)) {
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

/**
 * Walks the skills root: one skill per sub-directory that has a `SKILL.md`, plus
 * one per loose `.md` file. Sorted so the catalogue order is stable across runs —
 * a prompt that reshuffles every request would miss the prefix cache.
 */
internal fun scanSkills(dir: File): List<Skill> {
    if (!dir.isDirectory) return emptyList()
    val out = mutableListOf<Skill>()
    for (child in dir.listFiles().orEmpty().sortedBy { it.name.lowercase() }) {
        when {
            child.isDirectory -> {
                val file = SKILL_FILE_NAMES
                    .map { File(child, it) }
                    .firstOrNull { it.isFile }
                if (file != null) out += parseSkill(child, file)
            }

            child.isFile && child.name.endsWith(".md", ignoreCase = true) -> out += parseSkill(dir, child)
        }
    }
    return out
}

/**
 * Splits optional YAML frontmatter from the body. Deliberately not a YAML parser:
 * skill files in the wild carry a flat `key: value` header, and anything this does
 * not understand stays in the body where the model can still read it.
 */
internal fun splitFrontmatter(text: String): Pair<Map<String, String>, String> {
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

internal fun composeSkill(id: String, description: String, body: String): String = buildString {
    appendLine("---")
    appendLine("name: ${id.lineSequence().first().trim()}")
    appendLine("description: ${description.lineSequence().first().trim()}")
    appendLine("---")
    appendLine()
    append(body.trim())
    appendLine()
}

internal fun catalogOf(skills: List<Skill>): String {
    if (skills.isEmpty()) return ""
    return buildString {
        appendLine("## 可用技能")
        appendLine("技能是别人写好的操作手册。下面只有名字和用途；真正要用某个技能时，先调用 skill_read 读它的完整说明，再照着做。")
        for (skill in skills) {
            appendLine("- ${skill.name}（id: ${skill.id}）：${skill.description}")
        }
    }.trim()
}

/** Skill ids become directory names, so separators and dot-only names are refused. */
internal fun sanitizeSkillId(id: String): String {
    val cleaned = buildString {
        for (ch in id.trim()) {
            append(if (ch.isLetterOrDigit() || ch == '-' || ch == '_' || ch == '.') ch else '-')
        }
    }.trim('.', '-', ' ')
    return cleaned.ifEmpty { "skill" }
}
