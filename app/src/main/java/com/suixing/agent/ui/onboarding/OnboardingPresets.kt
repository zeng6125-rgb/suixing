package com.suixing.agent.ui.onboarding

import com.suixing.agent.data.PROTOCOL_ANTHROPIC
import com.suixing.agent.data.PROTOCOL_OPENAI

/**
 * 引导页第二页的供应商预设：挑一家、填上 Key 就能用。
 *
 * **baseUrl 与模型名都是查过的**，不是凭记忆写的。每条的出处写在 [ProviderPreset.source] 里，
 * 改之前先看一眼那行——这些地址变了就是「连不上」而不是「填错」，用户分不清这两件事。
 *
 * 挑选依据（为什么是这八家）：
 *  - 覆盖面：国内四家（DeepSeek / 智谱 / 月之暗面 / 阿里百炼）都是 OpenAI 兼容，一份 Key 直接用；
 *  - 聚合站：硅基流动一个 Key 就能开 DeepSeek、Qwen、GLM，省掉多注册；
 *  - 海外两家：OpenAI 官方与 Anthropic（协议不同，Claude 走 /v1/messages）；
 *  - 本机：Ollama 让「不花钱、不外传」这条路存在；
 *  - 外加一条「OpenAI 兼容（自填）」：自建网关、OneAPI、新中转、公司的内部代理都走它，
 *    没有这一条，兼容 OpenAI 的服务就得一个个加进来。
 *
 * 地址怎么用：填到 `/v1` 即可（结尾带不带斜杠都行，客户端会 trim 掉）。
 * 见 [com.suixing.agent.llm.LlmClient] 的 endpoint 规则：OpenAI 兼容补 `/chat/completions`，
 * Anthropic 补 `/v1/messages`。
 */
data class ProviderPreset(
    val id: String,
    val name: String,
    /** 接口地址；空串＝让用户自己填（OpenAI 兼容那条）。 */
    val baseUrl: String,
    /** 默认模型名。 */
    val model: String,
    /** Key 输入框下面那行提示：去哪儿申请、长什么样。 */
    val keyHint: String,
    /** 这个端点说的话：OpenAI 兼容（空串）还是 Anthropic。 */
    val protocol: String = PROTOCOL_OPENAI,
    /** 本机端点：Key 不校验，随便填一个就行。 */
    val local: Boolean = false,
    /** 一句话说明：为什么选它、有什么坑。 */
    val note: String = "",
    /** 依据出处（官方文档地址）。改地址前先看它。 */
    val source: String = "",
)

/**
 * 引导页的供应商清单。
 *
 * 顺序是给用户看的：先是「点两下就能用」的四条（DeepSeek / 智谱 / OpenAI 兼容 / 本机 Ollama），
 * 再是另外四条常用接口。
 */
val PROVIDER_PRESETS: List<ProviderPreset> = listOf(
    ProviderPreset(
        id = "deepseek",
        name = "DeepSeek",
        baseUrl = "https://api.deepseek.com/v1",
        model = "deepseek-flash",
        keyHint = "在 platform.deepseek.com 的「API keys」里创建，形如 sk-…",
        note = "便宜、国内直连、默认就是这个 App 的出厂端点。",
        source = "https://api-docs.deepseek.com/zh-cn/（base_url 与模型名取自「首次调用 API」）",
    ),
    ProviderPreset(
        id = "zhipu",
        name = "智谱 GLM",
        baseUrl = "https://open.bigmodel.cn/api/paas/v4",
        model = "glm-5.3",
        keyHint = "在 bigmodel.cn 的「API Keys」页创建，形如 …",
        note = "OpenAI 兼容，地址要带 /api/paas/v4 这一段。",
        source = "https://docs.bigmodel.cn/cn/guide/develop/openai/introduction（base_url 与 glm-5.3 取自官方示例）",
    ),
    ProviderPreset(
        id = "openai-compatible",
        name = "OpenAI 兼容（自填）",
        baseUrl = "",
        model = "",
        keyHint = "填你这个兼容端点的 Key",
        note = "自建网关、OneAPI、公司内部代理——凡是「OpenAI 兼容」的都走这条。",
        source = "地址与模型都由你自己填，填到 /v1 即可",
    ),
    ProviderPreset(
        id = "ollama",
        name = "本机 Ollama",
        baseUrl = "http://localhost:11434/v1",
        model = "qwen3",
        keyHint = "本机不用 Key，随便填一个（例如 ollama）就行",
        local = true,
        note = "模型名要和你本机 ollama list 里那个一致；手机上的 Ollama 多数是跑在电脑上的局域网地址。",
        source = "https://ollama.com/blog/openai-compatibility（base_url = http://localhost:11434/v1）",
    ),
    ProviderPreset(
        id = "moonshot",
        name = "月之暗面 Kimi",
        baseUrl = "https://api.moonshot.cn/v1",
        model = "kimi-k3",
        keyHint = "在 platform.moonshot.cn 创建，形如 sk-…",
        note = "长文档与多轮会话比较稳。",
        source = "https://platform.kimi.com/docs/api/chat（curl 示例：POST https://api.moonshot.cn/v1/chat/completions，model kimi-k3）",
    ),
    ProviderPreset(
        id = "dashscope",
        name = "阿里百炼 · 千问",
        baseUrl = "https://dashscope.aliyuncs.com/compatible-mode/v1",
        model = "qwen-plus",
        keyHint = "在百炼控制台的「API Key 管理」页创建，形如 sk-…",
        note = "旧域名一直可用；新域名要带工作空间 ID，自己填地址时按控制台给的那条。",
        source = "https://help.aliyun.com/zh/model-studio/compatibility-of-openai-with-dashscope（原文：「原 https://dashscope.aliyuncs.com 域名仍可正常使用」）",
    ),
    ProviderPreset(
        id = "siliconflow",
        name = "硅基流动",
        baseUrl = "https://api.siliconflow.cn/v1",
        model = "Pro/deepseek-ai/DeepSeek-R1",
        keyHint = "在 cloud.siliconflow.cn 的「API Keys」页创建，形如 sk-…",
        note = "一个 Key 开一大把开源模型；模型名带斜杠是正常的，按提供方分组显示。",
        source = "https://docs.siliconflow.cn/docs/userguide/quickstart（示例 base_url 与 model 取自官方 quickstart）",
    ),
    ProviderPreset(
        id = "anthropic",
        name = "Claude",
        baseUrl = "https://api.anthropic.com/v1",
        model = "claude-sonnet-5-5",
        keyHint = "在 platform.claude.com 的 API Keys 页创建，形如 sk-ant-…",
        protocol = PROTOCOL_ANTHROPIC,
        note = "走的是 Anthropic 协议（/v1/messages），不是 OpenAI 兼容——协议已经替你选好了。",
        source = "https://platform.claude.com/docs/en/api/http/messages（Messages = POST /v1/messages）；模型名按 anthropic.com 2026-09-28 的 Claude Sonnet 5.5 公告与官方 SDK 的 claude-opus-5-5 命名推定，可用「获取模型列表」核对",
    ),
)

/** 地址相同就是同一个端点（结尾斜杠不算）。 */
internal fun sameEndpoint(a: String, b: String): Boolean =
    a.trim().trimEnd('/').equals(b.trim().trimEnd('/'), ignoreCase = true)

/** 清单里有没有这一条地址的预设。 */
internal fun presetFor(baseUrl: String): ProviderPreset? =
    PROVIDER_PRESETS.firstOrNull { it.baseUrl.isNotBlank() && sameEndpoint(it.baseUrl, baseUrl) }
