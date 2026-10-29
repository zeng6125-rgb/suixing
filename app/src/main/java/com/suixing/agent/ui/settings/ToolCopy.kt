package com.suixing.agent.ui.settings

/**
 * 工具页上给人看的中文一句话说明。
 *
 * 模型看到的是 `ToolSpec.description`（**英文**，那是工具提示词）；这一页是给人读的，所以中文。
 * 表里没有的名字（新加的内置工具、插件/MCP 工具）回落到英文描述，不会空着。
 */
private val TOOL_COPY_ZH: Map<String, String> = mapOf(
    "spawn_agent" to "创建独立上下文的并行子任务",
    "wait_agents" to "等待并汇总指定子任务结果",
    "list_agents" to "查看本轮子任务状态和结果",
    "cancel_agent" to "取消指定子任务",
    // 文件
    "read_file" to "读取文件内容（可分页）",
    "write_file" to "写文件，大文件分段追加",
    "edit_file" to "按原文替换文件里的一段，多处命中会拒绝",
    "list_dir" to "列出目录内容（应用内，不起进程）",
    "find_files" to "按名字找文件（应用内，不起进程）",
    "grep_files" to "在文件内容里搜索（应用内，不起进程）",
    // Shell
    "run_shell" to "执行 shell 命令（自带 GNU bash）",
    "app_action" to "对已装应用做特权操作",
    // 界面操作
    "ui_dump" to "读取当前界面的控件树",
    "ui_screenshot" to "截屏",
    "ui_tap" to "点击坐标",
    "ui_long_press" to "长按坐标",
    "ui_swipe" to "滑动",
    "ui_click_text" to "按文字点击控件",
    "ui_type" to "向当前焦点输入文字",
    "ui_type_into" to "向指定控件输入文字",
    "ui_key" to "按键（返回、主页等）",
    "ui_current_app" to "当前前台应用",
    "ui_wait" to "等界面出现某个控件",
    // 系统
    "device_info" to "设备信息",
    "list_apps" to "列出已安装应用",
    "open_app" to "打开应用",
    "open_url" to "打开链接",
    "clipboard_read" to "读剪贴板",
    "clipboard_write" to "写剪贴板",
    "memory_write" to "记一条长期记忆",
    "memory_read" to "读长期记忆",
    "profile_read" to "读用户画像",
    "profile_write" to "写用户画像",
    "create_assistant" to "创建或修改助手人设",
    // 引擎虚拟工具（不在注册表里，但模型看得到，见 AgentEngine 的 COMPRESS_CONTEXT_SPEC）
    "compress_context" to "把最旧的对话折成摘要，腾出上下文空间",
    // 后台任务
    "job_start" to "后台起一个长任务",
    "job_list" to "列出后台任务",
    "job_output" to "读后台任务输出",
    "job_kill" to "结束后台任务",
    // 任务清单
    "todo_write" to "写任务清单",
    "todo_read" to "读任务清单",
    // 定时提醒
    "schedule_create" to "建定时提醒",
    "schedule_list" to "列出提醒",
    "schedule_update" to "改提醒",
    "schedule_delete" to "删提醒",
    // 技能
    "skill_list" to "列出技能",
    "skill_read" to "读技能内容",
    "skill_save" to "保存技能",
    "skill_download" to "下载技能",
    // 网络
    "web_fetch" to "抓取网页正文",
    "web_download" to "下载文件到本地",
    "web_search" to "联网搜索",
    // 语音
    "speak" to "朗读一段话",
    "stop_speaking" to "停止朗读",
)

/** 工具页这一行显示什么：有中文就用中文，没有就用英文描述。 */
internal fun toolCopyZh(name: String, fallback: String): String = TOOL_COPY_ZH[name] ?: fallback
