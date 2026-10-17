package com.suixing.agent.data

/**
 * 画像的**变更协议**：模型不再交回整篇画像，只交回「加一条 / 删一条 / 改一条」。
 *
 * 为什么改：整篇替换下，模型抄漏旧条目＝旧信息消失，代码事后无从分辨（用户报的
 * 「每几轮就覆盖新的」）。改成操作行之后，旧条目**根本不在模型手里**，想丢也丢不掉；
 * 对不上的删除/修正**整批拒绝**，最坏结果是「这次没更新」，而不是「画像被写坏」。
 *
 * 输出格式（一行一条，别的行一律忽略）：
 * ```
 * + 新增的一条
 * + [工作与生活背景] 归到指定小节的新增
 * - 要删掉的那一条（必须与现有条目逐字一致）
 * ~ 旧的一条 => 改成的新一条
 * ```
 */
internal sealed interface ProfileOp {
    /** 新增；[section] 为 null 时归到「其它」。 */
    data class Add(val text: String, val section: String? = null) : ProfileOp

    /** 删除：必须唯一匹配现有条目。 */
    data class Remove(val text: String) : ProfileOp

    /** 修正：唯一匹配现有条目后换成新文本。 */
    data class Replace(val from: String, val to: String) : ProfileOp
}

internal sealed interface ProfileApply {
    data class Ok(val text: String) : ProfileApply

    /** 有对不上的操作：一个字都不写，把原因交回给模型重试。 */
    data class Rejected(val reason: String) : ProfileApply
}

/** 解析模型输出的变更行；看不懂的行直接忽略（模型偶尔会加解释）。 */
internal fun parseProfileOps(raw: String): List<ProfileOp> {
    val ops = mutableListOf<ProfileOp>()
    for (line in raw.lines()) {
        val t = line.trim()
        when {
            t.startsWith("+ ") -> {
                var body = t.removePrefix("+ ").trim()
                var section: String? = null
                if (body.startsWith("[")) {
                    val close = body.indexOf(']')
                    if (close > 1) {
                        section = body.substring(1, close).trim().ifBlank { null }
                        body = body.substring(close + 1).trim()
                    }
                }
                if (body.isNotEmpty()) ops += ProfileOp.Add(body, section)
            }

            t.startsWith("~ ") -> {
                val body = t.removePrefix("~ ").trim()
                val arrow = body.indexOf("=>")
                if (arrow > 0) {
                    val from = body.substring(0, arrow).trim()
                    val to = body.substring(arrow + 2).trim()
                    if (from.isNotEmpty() && to.isNotEmpty()) ops += ProfileOp.Replace(from, to)
                }
            }

            t.startsWith("- ") -> {
                val body = t.removePrefix("- ").trim()
                if (body.isNotEmpty()) ops += ProfileOp.Remove(body)
            }
        }
    }
    return ops
}

/**
 * 把变更应用到现有画像上。**要么全成、要么全不动**：
 * 任何一条 `-` / `~` 找不到唯一对应的现有条目，就整批拒绝并说清是哪一条。
 */
internal fun applyProfileOps(old: String, ops: List<ProfileOp>, maxLength: Int): ProfileApply {
    val sections = parseSections(old)
    val rejected = mutableListOf<String>()
    for (op in ops) {
        when (op) {
            is ProfileOp.Add -> addBullet(sections, op.text, op.section)
            is ProfileOp.Remove -> {
                if (!removeBullet(sections, op.text)) rejected += "找不到要删的条目「${op.text}」"
            }

            is ProfileOp.Replace -> {
                if (!replaceBullet(sections, op.from, op.to)) rejected += "找不到要改的条目「${op.from}」"
            }
        }
    }
    if (rejected.isNotEmpty()) {
        return ProfileApply.Rejected(
            rejected.joinToString("；") + "。请让「-」与「~」里的旧条目与现有画像逐字一致（先 profile_read 看一眼）。",
        )
    }
    val text = renderSections(sections)
    if (text.length > maxLength) {
        return ProfileApply.Rejected("应用后超过 $maxLength 字，未保存")
    }
    return ProfileApply.Ok(text)
}

/** 小节名 → 条目（按文件顺序）。标题行之外的散行也保留在「其它」里。 */
private fun parseSections(old: String): LinkedHashMap<String, MutableList<String>> {
    val out = LinkedHashMap<String, MutableList<String>>()
    var current = OTHER_SECTION
    out[current] = mutableListOf()
    for (line in old.lines()) {
        val t = line.trim()
        when {
            t.startsWith("### ") -> {
                current = t.removePrefix("### ").trim().ifBlank { OTHER_SECTION }
                out.getOrPut(current) { mutableListOf() }
            }

            t.startsWith("- ") -> {
                val body = t.removePrefix("- ").trim()
                if (body.isNotEmpty()) out.getOrPut(current) { mutableListOf() } += body
            }
        }
    }
    out.entries.removeAll { it.value.isEmpty() && it.key == OTHER_SECTION }
    return out
}

private fun renderSections(sections: Map<String, List<String>>): String = buildString {
    for ((name, bullets) in sections) {
        if (bullets.isEmpty()) continue
        if (isNotEmpty()) append('\n')
        if (name != OTHER_SECTION) appendLine("### $name")
        bullets.forEach { appendLine("- $it") }
    }
}.trim()

private fun addBullet(sections: LinkedHashMap<String, MutableList<String>>, text: String, section: String?) {
    val key = section?.takeIf { it.isNotBlank() } ?: sections.keys.firstOrNull { it != OTHER_SECTION } ?: OTHER_SECTION
    val list = sections.getOrPut(key) { mutableListOf() }
    if (list.none { it == text }) list += text
}

private fun removeBullet(sections: LinkedHashMap<String, MutableList<String>>, text: String): Boolean {
    val hits = sections.entries.filter { entry -> entry.value.any { it == text } }
    if (hits.size != 1) return false
    hits.first().value.remove(text)
    return true
}

private fun replaceBullet(sections: LinkedHashMap<String, MutableList<String>>, from: String, to: String): Boolean {
    for (entry in sections) {
        val index = entry.value.indexOf(from)
        if (index >= 0) {
            entry.value[index] = to
            return true
        }
    }
    return false
}

internal const val OTHER_SECTION = "其它"
