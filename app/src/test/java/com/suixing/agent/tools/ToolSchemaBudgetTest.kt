package com.suixing.agent.tools

import android.content.Context
import com.suixing.agent.capability.CapabilityHub
import com.suixing.agent.data.MemoryStore
import com.suixing.agent.voice.Speaker
import com.suixing.agent.data.UserProfileStore
import com.suixing.agent.data.ScheduleStore
import com.suixing.agent.data.SettingsRepository
import com.suixing.agent.data.TodoStore
import com.suixing.agent.skills.SkillStore
import io.mockk.mockk
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 工具定义占用盘点：把每个工具族真实发出的 schema 字符数量出来，写成报告文件。
 *
 * 为什么要有它（用户：「工具定义占用盘点 可以」）：引擎里那一项 `breakTools` 只有**总数**，
 * 而"该不该学 pi 砍工具"要的是**分族**的数字 —— 谁是常驻的大头。这里直接用真实 ToolGroup 构造
 * specs（不是静态分析源码），所以量到的就是发给网关的字节。
 *
 * 估算口径与引擎一致：`(name + description + parameters.toString())` 的字符数 / 4 ≈ tokens。
 */
class ToolSchemaBudgetTest {

    @get:Rule
    val folder = TemporaryFolder()

    private fun context(): Context {
        val context = mockk<Context>(relaxed = true)
        io.mockk.every { context.filesDir } returns folder.root
        io.mockk.every { context.getExternalFilesDir(null) } returns folder.root
        return context
    }

    private fun groups(context: Context): List<Pair<String, ToolGroup>> = listOf(
        "SystemTools" to SystemTools(context, mockk<MemoryStore>(relaxed = true), mockk<UserProfileStore>(relaxed = true)),
        "FileTools" to FileTools(context),
        "ShellTools" to ShellTools(context, mockk<CapabilityHub>(relaxed = true)),
        "UiTools" to UiTools(context, mockk<CapabilityHub>(relaxed = true)),
        "JobTools" to JobTools(mockk(relaxed = true)),
        "TodoTools" to TodoTools(mockk<TodoStore>(relaxed = true)),
        "ScheduleTools" to ScheduleTools(context, mockk<ScheduleStore>(relaxed = true)),
        "SkillTools" to SkillTools(mockk<SkillStore>(relaxed = true)),
        "WebTools" to WebTools(context, mockk<SettingsRepository>(relaxed = true)),
        "VoiceTools" to VoiceTools(mockk<Speaker>(relaxed = true)),
        "AssistantTools" to AssistantTools(),
    )

    private fun chars(spec: ToolSpec): Int =
        spec.name.length + spec.description.length + spec.parameters.toString().length

    @Test
    fun `report per-group and per-tool schema size`() {
        val context = context()
        val rows = mutableListOf<ToolRow>()
        for ((group, tools) in groups(context)) {
            for (spec in tools.specs) {
                rows += ToolRow(
                    group = group,
                    name = spec.name,
                    chars = chars(spec),
                    tokens = estTokens(spec),
                    descChars = spec.description.length,
                    paramChars = spec.parameters.toString().length,
                )
            }
        }
        // 全部工具定义（这就是每次请求发出去的那一份）落盘：真正的 token 数用真 tokenizer 量
        // （见 D:\dsh\fixbug\_toolchain\tok\full.js，gpt-tokenizer）。估算器只用来排序。
        File("build/tool-schema-full.txt").writeText(
            groups(context).joinToString("\n\n") { (_, tools) ->
                tools.specs.joinToString("\n") { it.name + "\n" + it.description + "\n" + it.parameters }
            },
        )
        val totalChars = rows.sumOf { it.chars }
        val totalEst = rows.sumOf { it.tokens }
        val report = buildString {
            appendLine("工具定义占用盘点")
            appendLine("全部：$totalChars 字符；est ≈ $totalEst tokens；${rows.size} 个工具")
            appendLine()
            appendLine("== 按族汇总（est 降序；[按需] = 默认不发）==")
            rows.groupBy { it.group }
                .map { (group, list) -> Triple(group, list.sumOf { it.chars }, list.sumOf { it.tokens }) }
                .sortedByDescending { it.third }
                .forEach { (group, size, est) ->
                    val count = rows.count { it.group == group }
                    appendLine(
                        "%-16s %6d 字符  est %5d tok  %2d 个  %4.1f%%".format(
                            group, size, est, count, est * 100.0 / totalEst,
                        ),
                    )
                }
            appendLine()
            appendLine("== 按工具（est 降序，前 25）==")
            rows.sortedByDescending { it.tokens }.take(25).forEach { row ->
                appendLine(
                    "%-16s %-22s %6d 字符  est %5d tok  (desc %d / params %d)".format(
                        row.group, row.name, row.chars, row.tokens, row.descChars, row.paramChars,
                    ),
                )
            }
            appendLine()
            appendLine("== 描述 vs 参数 schema ==")
            appendLine("description 合计 ${rows.sumOf { it.descChars }} 字符")
            appendLine("parameters 合计 ${rows.sumOf { it.paramChars }} 字符（property 描述是主要来源）")        }
        // 写到模块的 build 目录（测试的工作目录就是 app/）：TemporaryFolder 会在测试结束时被删，
        // 而这份报告是要被人读的。
        val out = File("build/tool-schema-report.txt")
        out.parentFile?.mkdirs()
        out.writeText(report)
        println(report)
        assertTrue("报告应已写出", out.length() > 0)
    }

    private data class ToolRow(
        val group: String,
        val name: String,
        val chars: Int,
        val tokens: Int,
        val descChars: Int = 0,
        val paramChars: Int = 0,
    )

    /**
     * 脚本感知的 token 估算：**不能只按字符数除以 4** —— 中文一个字大约一个 token，英文一个 token
     * 大约 3.6 个字符，两者差 3 倍以上。所以按字符集分开算：CJK ≈ 0.8 tok/字，其余 ≈ 1/3.6。
     */
    private fun estTokens(spec: ToolSpec): Int {
        val text = spec.name + spec.description + spec.parameters.toString()
        var cjk = 0
        var other = 0
        for (ch in text) {
            // CJK 统一表意文字 + 扩展 A + 中日韩标点 + 全角形式：都按「一个字≈一个 token」算。
            val code = ch.code
            val isCjk = code in 0x4E00..0x9FFF || code in 0x3400..0x4DBF ||
                code in 0x3000..0x303F || code in 0xFF00..0xFFEF
            if (isCjk) cjk++ else other++
        }
        return (cjk * 0.8 + other / 3.6).toInt()
    }
}

