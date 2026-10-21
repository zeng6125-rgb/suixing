package com.suixing.agent.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.suixing.agent.core.Core
import com.suixing.agent.data.AppSettings
import com.suixing.agent.data.PROTOCOL_OPENAI
import com.suixing.agent.data.ProviderConfig
import com.suixing.agent.ui.components.LocalHeldImePx
import com.suixing.agent.ui.components.SuixingOrb
import kotlinx.coroutines.launch

/** 引导页的五页，顺序即推进顺序。 */
enum class OnboardingStep { WELCOME, PROVIDER, MODEL, PERMISSION, READY }

/**
 * 引导页这一趟里用户填过的东西。
 *
 * 它是**引导页自己的草稿**，不是 [AppSettings]：AppSettings 有上百个字段，而引导页只碰
 * 接口、Key、模型、思考强度这四样。离开这一页时由 [mergeOnboarding] 一次性并回去。
 */
data class OnboardingDraft(
    /** 选中的预设 id；空＝自定义（自己填的地址）。 */
    val presetId: String = "",
    /** 预设的名字（自定义时是用户自己起的名字或空）。 */
    val presetName: String = "",
    val baseUrl: String = "",
    val apiKey: String = "",
    val model: String = "",
    val protocol: String = PROTOCOL_OPENAI,
    /** 思考强度（全局档位；空＝交给模型自己决定）。 */
    val reasoningEffort: String = "",
    /** 上游 /models 报出来的上下文窗口（按模型名）。没报就不写。 */
    val modelContexts: Map<String, Int> = emptyMap(),
    /** 用户动过没有。没动过＝这一页看到的东西就是存着的那些，退出时一个字也不写。 */
    val touched: Boolean = false,
)

/**
 * 首次启动引导：五页，从「这是什么」到「开始用」。
 *
 * 插在**导航宿主之外、盖在它上面**（见 SuixingApp 里的 [com.suixing.agent.ui.components.FullScreenOverlay]
 * 用法），所以它不占一条路由、也不进返回栈——它是盖在应用上面的一层，不是应用里的一页。
 *
 * 三条规矩：
 *  - **可跳过**：任何一页都能跳过。没配 Key 就不能聊天，这是正常状态，不是错误。
 *  - **不拦人**：Key 连不上只提示，不挡住「开始使用」。
 *  - **不覆盖**：用户什么都没改就退出 ⇒ 一个字也不写盘（[OnboardingDraft.touched]）。
 */
@Composable
fun OnboardingHost(onClose: () -> Unit) {
    var step by remember { mutableStateOf(OnboardingStep.WELCOME) }
    var draft by remember { mutableStateOf<OnboardingDraft?>(null) }
    // 落盘的那一份（含迁移：老文件会被包成一条 provider），不是 AppSettings() 的原始默认值。
    // 拿它当草稿的种子，也拿它当「拉模型清单 / 测试连接」时那一次请求的底子——用户自己设的
    // 自定义请求头、允许自签证书那些开关都在里面，重建一份空的会把它们丢掉。
    var stored by remember { mutableStateOf<AppSettings?>(null) }

    // 种子是**落盘后的那一份**，不是 AppSettings() 的原始默认值。读出来之前不画表单——
    // 否则会先闪一下空地址，再跳成真地址。
    LaunchedEffect(Unit) {
        val s = Core.settings.current()
        val provider = s.activeProvider()
        stored = s
        draft = OnboardingDraft(
            presetId = presetFor(s.baseUrl)?.id ?: "",
            presetName = provider?.name.orEmpty(),
            baseUrl = s.baseUrl,
            apiKey = s.apiKey,
            model = s.model,
            protocol = s.protocol.ifBlank { provider?.protocol ?: PROTOCOL_OPENAI },
            reasoningEffort = s.reasoningEffort,
        )
    }

    val current = draft
    val base = stored
    if (current == null || base == null) {
        // 只有几毫秒的读盘时间。一枚居中的转圈，别闪一帧表单。
        Box(
            Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background),
            contentAlignment = Alignment.Center,
        ) { CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp) }
        return
    }

    // 收尾：把草稿并进设置（动过才写），并把「看过引导」记上——下次启动不再弹。
    //
    // 写盘放在**应用级**的 scope 里（Core.appScope），不放在这一层的 scope 里：覆盖层退场
    // 那 200ms 会把这一层的 scope 取消掉，而「看过引导」这个标记必须落盘——它没了，下次
    // 启动又弹一遍，用户会以为这个 App 记不住事。
    val close: () -> Unit = {
        val snapshot = current
        Core.appScope.launch {
            Core.settings.update { latest ->
                val merged = if (snapshot.touched) latest.mergeOnboarding(snapshot) else latest
                merged.copy(onboardingSeen = true)
            }
        }
        onClose()
    }
    // 跳过与走完是同一件事：同样记上「看过」，但**已填的 Key 不丢**——填到一半被打断的人
    // 回来还能用。
    val skip: () -> Unit = close
    val finish: () -> Unit = close

    // 返回键：先退一页，退到第一页再按＝跟「跳过」一样（收工，不当成要退出应用）。
    // 这个 BackHandler 压在导航宿主之上——它后注册，所以先拿到返回键。
    BackHandler {
        if (step != OnboardingStep.WELCOME) step = step.previous() else skip()
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            // 键盘让位：填 Key 的时候键盘会盖住页脚（下一步/开始使用那两个按钮）。
            // 用外壳给的那个**夹过的**键盘高度（LocalHeldImePx），与聊天页同一套算法——
            // 自己读 WindowInsets.ime 会遇到那个夹紧逻辑（短促的回落）带来的跳动。
            .padding(bottom = with(LocalDensity.current) { LocalHeldImePx.current.toDp() })
            // **吃掉落在空白处的点击**：引导层是盖在导航宿主之上的，而 chat 常驻在宿主之下
            // 且还在组合里——不挡这一下，点在引导页的空白处就会打到下面的输入框、消息行上，
            // 左缘拖动还会把抽屉拉出来。（与 SuixingApp 里 NavHost 那一层同一招。）
            .pointerInput(Unit) { detectTapGestures { } },
    ) {
        OnboardingHeader(
            step = step,
            // 最后一页不用再「跳过」了——它和「开始使用」是同一件事，两个一样的按钮摆在一起
            // 只会让人犹豫该按哪个。
            onSkip = if (step == OnboardingStep.READY) null else skip,
        )
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth(),
        ) {
            when (step) {
                OnboardingStep.WELCOME -> WelcomePage()
                OnboardingStep.PROVIDER -> ProviderPage(
                    draft = current,
                    stored = base,
                    onChange = { draft = it },
                )

                OnboardingStep.MODEL -> ModelPage(
                    draft = current,
                    stored = base,
                    onChange = { draft = it },
                )

                OnboardingStep.PERMISSION -> PermissionPage()
                OnboardingStep.READY -> ReadyPage(draft = current)
            }
        }
        OnboardingFooter(
            step = step,
            canGoBack = step != OnboardingStep.WELCOME,
            onBack = { step = step.previous() },
            onNext = { step = step.next() },
            onFinish = finish,
        )
    }
}

/**
 * 页脚：上一步 / 下一步，最后一页换成「开始使用」。
 *
 * 「开始使用」永远可点：Key 没填、连通性没测过，都不拦——用户自己决定要不要先配。
 */
@Composable
private fun OnboardingFooter(
    step: OnboardingStep,
    canGoBack: Boolean,
    onBack: () -> Unit,
    onNext: () -> Unit,
    onFinish: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
            .navigationBarsPadding()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        HorizontalRule()
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (canGoBack) {
                OutlinedButton(onClick = onBack, modifier = Modifier.weight(1f)) { Text("上一步") }
            }
            if (step == OnboardingStep.READY) {
                Button(onClick = onFinish, modifier = Modifier.weight(1f)) { Text("开始使用") }
            } else {
                Button(onClick = onNext, modifier = Modifier.weight(1f)) { Text("下一步") }
            }
        }
    }
}

/** 页脚上那条发丝分隔线：页面与页面之间靠它断开，不靠阴影。 */
@Composable
private fun HorizontalRule() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(MaterialTheme.colorScheme.outlineVariant),
    )
}

/**
 * 页头：左边是第几页，右边是「跳过」。
 *
 * 「跳过」一直都在（含最后一页之前），因为用户随时可以反悔——这一页不该成为他必须走完的关卡。
 */
@Composable
private fun OnboardingHeader(step: OnboardingStep, onSkip: (() -> Unit)?) {
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = "${step.ordinal + 1} / ${OnboardingStep.entries.size}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        if (onSkip != null) {
            TextButton(onClick = onSkip) { Text("跳过") }
        }
    }
}

/**
 * 第一页：这是什么。
 *
 * 这一页不推销功能，只说清三件事：它是什么、它要什么（一个能对话的模型接口）、它不做什么
 * （不上传、不注册、不替你决定用什么模型）。最后一条是用户最关心的——「装了这个 App，我的东西
 * 会去哪」。
 */
@Composable
private fun WelcomePage() {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(40.dp))
        SuixingOrb(size = 148.dp)
        Spacer(Modifier.height(28.dp))
        Text(
            text = "随行",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(10.dp))
        Text(
            text = "你自己的 AI 助手，住在你自己的手机里。",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        WelcomePoint(
            title = "它需要一个能对话的模型接口",
            body = "你给它一个 OpenAI 兼容的地址和一把 Key，它就能替你回消息、查资料、管文件。没有 Key 它就只能说「我还没接上模型」——这是正常状态。",
        )
        WelcomePoint(
            title = "你的东西留在你这儿",
            body = "聊天记录、记忆、文件都写在应用自己的目录里；不注册账号，也没有后台替你传话。Key 用系统 Keystore 加密后存在这台手机上。",
        )
        WelcomePoint(
            title = "能做什么由你决定",
            body = "聊天、工具、技能、定时任务都在，但要不要开、开到哪一档，权限页和设置页都写得很清楚。",
        )
        Spacer(Modifier.height(32.dp))
    }
}

/** 欢迎页上的一段话：标题 + 正文，卡片里读起来是一段一件事。 */
@Composable
private fun WelcomePoint(title: String, body: String) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.primary,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 最后一页：把这一趟配的东西摊开给用户看，然后放他走。
 *
 * 摊开是为了让人知道自己现在处于什么状态：没配 Key 就明说「还没接上模型」，而不是给一个
 * 看起来一切就绪的假象。
 */
@Composable
private fun ReadyPage(draft: OnboardingDraft) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(16.dp))
        Text(
            text = "可以开始了",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "下面是你现在的配置。随时可以在设置里改。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(20.dp))
        ReadyRow("模型接口", draft.baseUrl.ifBlank { "还没填" })
        ReadyRow("模型", draft.model.ifBlank { "还没填" })
        ReadyRow("思考强度", draft.reasoningEffort.ifBlank { "默认（交给模型自己决定）" })
        ReadyRow(
            "API Key",
            if (draft.apiKey.isBlank()) "还没填 —— 现在还不能聊天" else "已填写",
        )
        Spacer(Modifier.height(20.dp))
        Text(
            text = "没配 Key 也能用这个 App：设置、记忆、技能、定时任务都在那里等着。填上 Key 之后随时接着聊。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(32.dp))
    }
}

/** 最后一页的一行「标题 / 值」。 */
@Composable
private fun ReadyRow(label: String, value: String) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(88.dp),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 把引导页的草稿并进设置。
 *
 * 两条规矩：
 *  - **没动过就不写**（[OnboardingDraft.touched]）：从设置里「重新看一遍引导」的人只是走个过场，
 *    他存着的地址与 Key 不能被预设的默认值盖掉。
 *  - **同一个端点只留一条**：地址相同的旧 provider 就地改，不新建——否则每走一遍引导就多一条
 *    重复的 provider，模型切换器会越列越长。
 */
internal fun AppSettings.mergeOnboarding(draft: OnboardingDraft): AppSettings {
    // 地址空了 ⇒ 线上那一套三元组一个字也不动（照设置页 forSaving 的规矩），
    // 否则会把正在用的接口清成「没地址」，下一次发消息直接失败。
    if (draft.baseUrl.isBlank()) {
        return copy(
            reasoningEffort = draft.reasoningEffort,
            modelContexts = modelContexts + draft.modelContexts,
        )
    }
    // **按地址找那一条**，不按 id：换了厂商就是换了端点，老的那一条要原样留在列表里
    // （它的 Key 也留着），不能让新配置把旧 provider 覆盖掉。
    val existing = providers.firstOrNull { sameEndpoint(it.baseUrl, draft.baseUrl) }
    val id = existing?.id ?: freshProviderId(draft, providers.map { it.id }.toSet())
    val picked = listOfNotNull(draft.model.takeIf { it.isNotBlank() })
    // 清单只增不减：这一条端点原来勾着的模型不能因为引导页走过一遍就被砍到只剩一个。
    val models = (existing?.models.orEmpty() + picked).distinct()
    val enabled = (existing?.enabled.orEmpty() + picked).distinct()
    val provider = (existing ?: ProviderConfig(id = id)).copy(
        name = draft.presetName.ifBlank { existing?.name.orEmpty() },
        baseUrl = draft.baseUrl,
        // Key 原样搬过去，**不拿老的顶上**：换厂商时老 Key 是另一个厂商的，
        // 拿过去只会被拒，还要多一次「为什么一直 401」的排查。
        apiKey = draft.apiKey,
        models = models,
        enabled = enabled,
        protocol = draft.protocol,
    )
    return copy(
        baseUrl = draft.baseUrl,
        apiKey = draft.apiKey,
        model = draft.model,
        protocol = draft.protocol,
        reasoningEffort = draft.reasoningEffort,
        // 上游报过的窗口写进去；设过的不被覆盖（自定义赢过上游，与设置页同一套规则）。
        modelContexts = modelContexts + draft.modelContexts,
        providers = providers.filterNot { it.id == id } + provider,
    )
}

/** 一个不与现有 provider 撞号的 id：同一份引导重复走也不会堆出两条一样的。 */
private fun freshProviderId(draft: OnboardingDraft, taken: Set<String>): String {
    val base = "onb-" + draft.presetId.ifBlank { "custom" }
    if (base !in taken) return base
    var n = 2
    while ("$base-$n" in taken) n++
    return "$base-$n"
}

/** 推进与退回。 */
private fun OnboardingStep.next(): OnboardingStep =
    OnboardingStep.entries.getOrElse(ordinal + 1) { OnboardingStep.READY }

private fun OnboardingStep.previous(): OnboardingStep =
    OnboardingStep.entries.getOrElse(ordinal - 1) { OnboardingStep.WELCOME }
