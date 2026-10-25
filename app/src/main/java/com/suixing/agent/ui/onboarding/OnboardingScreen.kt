package com.suixing.agent.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.suixing.agent.core.Core
import com.suixing.agent.data.AppSettings
import com.suixing.agent.data.PROTOCOL_OPENAI
import com.suixing.agent.data.ProviderConfig
import com.suixing.agent.data.SecretBox
import com.suixing.agent.ui.chat.REVEAL_EASING
import com.suixing.agent.ui.components.LocalHeldImePx
import com.suixing.agent.ui.components.SuixingOrb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 引导页的五页，顺序即推进顺序。 */
enum class OnboardingStep { WELCOME, PROVIDER, MODEL, PERMISSION, READY }

/**
 * 页内切换的时长：与导航页推入（SuixingApp 的 280ms）同一档，两处手感不打架。
 */
private const val PAGE_MS = 280

/**
 * 淡入淡出比滑动短一半：滑动是主角，淡入淡出只是把它接住。两段一样长会显得"糊"。
 */
private const val PAGE_FADE_MS = 140

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
 * 读回来的一份设置，加上**解开的** Key。
 *
 * **Key 为什么在这儿解**：落盘层已经不再解密了（见 `data/Settings.kt` 的 `decode`：AndroidKeyStore
 * 每次冷启动要 ~150ms，放在读路径上会连累首屏——Lead 的真机 A/B 是冷启动 165ms → 340ms）。
 * 所以设置流里的 `apiKey` 现在是 `enc1:` 开头的**密文**，显示与回填的地方要自己调
 * [SecretBox.decrypt]；解不开（换机、密钥被系统作废）它返回空串 ⇒ 一律按「没填」处理。
 */
private data class OnboardingSeed(val settings: AppSettings, val apiKey: String)

/**
 * 上一次读到的结果：让「重新看一遍引导」进来时**不用再等一次读盘与 Keystore**。
 *
 * 只当"立刻能显示"用——进来后还会在后台再读一遍对齐（用户没动过就换成最新的那一份）。
 */
private object OnboardingCache {
    var seed: OnboardingSeed? = null
}

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

    // 滚动位置由外壳拿着：换页再回来时别跳回顶上。页内的 rememberScrollState 活不过换页
    // （AnimatedContent 退场后旧页会被销毁，那个 remember 一起没了）。
    val welcomeScroll = rememberScrollState()
    val providerScroll = rememberScrollState()
    val modelScroll = rememberScrollState()
    val permissionScroll = rememberScrollState()
    val readyScroll = rememberScrollState()

    // **首帧就画**：欢迎页不依赖任何设置，所以不等读盘。以前这里是"先转圈、再把整页跳出来"，
    // 两段式的一次亮相——用户看到的就是"卡了一下再出现"。
    val cached = OnboardingCache.seed
    var stored by remember { mutableStateOf(cached?.settings) }
    var draft by remember { mutableStateOf(cached?.let { seedOf(it) }) }

    // 读盘与解密都在 IO 上：Keystore 冷启动要 ~150ms（Lead 的真机 A/B：165ms → 340ms），
    // 放在主线程上就是一次能看见的卡顿。
    LaunchedEffect(Unit) {
        val seed = withContext(Dispatchers.IO) {
            val s = Core.settings.current()
            OnboardingSeed(s, SecretBox.decrypt(s.apiKey))
        }
        OnboardingCache.seed = seed
        stored = seed.settings
        val fresh = seedOf(seed)
        val mine = draft
        // 用户已经动过草稿就别覆盖：他在填的东西不能被读回来的旧值顶掉。
        if (mine == null || !mine.touched) draft = fresh
    }

    // **离开这一页就把缓存里的明文密钥丢掉**：`OnboardingCache` 是进程级的，用来让「重新看
    // 一遍引导」进来时不必再等一次 Keystore（~150ms）。但那份 seed 里带着 `SecretBox.decrypt`
    // 出来的 apiKey —— 一个不做任何事也会在内存里躺到进程结束的密钥副本。进页面照旧用它
    // 立刻显示，离开时清空；下次进来重新读盘解密（本来后台也会再读一遍对齐）。
    DisposableEffect(Unit) {
        onDispose { OnboardingCache.seed = null }
    }

    val current = draft
    val base = stored

    // 收尾：把草稿并进设置（动过才写），并把「看过引导」记上——下次启动不再弹。
    //
    // 写盘放在**应用级**的 scope 里（Core.appScope），不放在这一层的 scope 里：覆盖层退场
    // 那 200ms 会把这一层的 scope 取消掉，而「看过引导」这个标记必须落盘——它没了，下次
    // 启动又弹一遍，用户会以为这个 App 记不住事。
    val close: () -> Unit = {
        val snapshot = current
        Core.appScope.launch {
            Core.settings.update { latest ->
                val merged = if (snapshot != null && snapshot.touched) {
                    latest.mergeOnboarding(snapshot)
                } else {
                    latest
                }
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
            // 挂在根上 ⇒ 五页全都让位，不用每页各写一遍。
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
            // 页面切换：横向推入 + 轻微淡入淡出。
            //  - 前进：新页从右边滑进来，旧页往左退 1/3（M3 的"共享轴"做法：旧页退得少，
            //    两页错开一点才看得出是"推"而不是"换"）；后退方向反过来。
            //  - 曲线用 REVEAL_EASING（= M3 standard，CubicBezierEasing(0.2, 0, 0, 1)），
            //    与聊天页的消息揭示同一条曲线，全站只有这一条。
            //  - **只组合当前页**（外加退场中的那一页）：五个页面一次全组合的话，光是
            //    供应商页那八张卡就足以让首帧变重。
            AnimatedContent(
                targetState = step,
                // **必须裁掉**：滑进来的那一页要从整屏宽之外进来，不裁的话它会盖到页头与
                // 页脚上（内容区只有中间那一块）。
                modifier = Modifier.clipToBounds(),
                transitionSpec = {
                    val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                    val enter = slideInHorizontally(
                        animationSpec = tween(PAGE_MS, easing = REVEAL_EASING),
                    ) { full -> dir * full }
                    val exit = slideOutHorizontally(
                        animationSpec = tween(PAGE_MS, easing = REVEAL_EASING),
                    ) { full -> -dir * full / 3 }
                    (enter + fadeIn(tween(PAGE_FADE_MS, easing = REVEAL_EASING)))
                        .togetherWith(
                            exit + fadeOut(tween(PAGE_FADE_MS, easing = REVEAL_EASING)),
                        )
                },
                label = "onboarding-page",
            ) { target ->
                val d = draft
                val b = stored
                when (target) {
                    OnboardingStep.WELCOME -> WelcomePage(scroll = welcomeScroll)
                    OnboardingStep.PROVIDER -> if (d != null && b != null) {
                        ProviderPage(
                            draft = d,
                            stored = b,
                            scroll = providerScroll,
                            onChange = { draft = it },
                        )
                    } else {
                        PendingPage()
                    }

                    OnboardingStep.MODEL -> if (d != null && b != null) {
                        ModelPage(
                            draft = d,
                            stored = b,
                            scroll = modelScroll,
                            onChange = { draft = it },
                        )
                    } else {
                        PendingPage()
                    }

                    OnboardingStep.PERMISSION -> PermissionPage(scroll = permissionScroll)
                    OnboardingStep.READY -> if (d != null) {
                        ReadyPage(draft = d, scroll = readyScroll)
                    } else {
                        PendingPage()
                    }
                }
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
 * 设置还没读到时那一页的样子。
 *
 * 只在用户一进来就猛点「下一步」时才会露脸（读盘是毫秒级的事）。这里**不转圈**：转圈在
 * 半路出现再消失，本身就是一次"闪"；一句话更安静。
 */
@Composable
private fun PendingPage() {
    Box(
        Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "正在读你的设置…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * 由设置生成一份草稿（种子）。
 *
 * Key 在 [OnboardingSeed] 里已经解开了：设置流给的是密文，显示与回填都要用明文。
 */
private fun seedOf(seed: OnboardingSeed): OnboardingDraft {
    val s = seed.settings
    val provider = s.activeProvider()
    return OnboardingDraft(
        presetId = presetFor(s.baseUrl)?.id ?: "",
        presetName = provider?.name.orEmpty(),
        baseUrl = s.baseUrl,
        apiKey = seed.apiKey,
        model = s.model,
        protocol = s.protocol.ifBlank { provider?.protocol ?: PROTOCOL_OPENAI },
        reasoningEffort = s.reasoningEffort,
    )
}

/**
 * 页脚：上一步 / 下一步，最后一页换成「开始使用」。
 *
 * 「开始使用」永远可点：Key 没填、连通性没测过，都不拦——用户自己决定要不要先配。
 * 按压反馈与禁用态用 M3 按钮自带的（涟漪 + disabled 配色），不另写一套。
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
 * 页头：左边是第几步，右边是「跳过」，下面一条进度条。
 *
 * 「跳过」一直都在（含最后一页之前），因为用户随时可以反悔——这一页不该成为他必须走完的关卡。
 *
 * 进度条跟页面切换**同一套时长与曲线**（[PAGE_MS] + REVEAL_EASING），所以它是跟页面一起
 * 动起来的，而不是页面已经换了、条子还在慢慢爬。起手值就是当前进度（animateFloatAsState
 * 的初值＝目标值），所以不会"先跳一下再归位"。
 */
@Composable
private fun OnboardingHeader(step: OnboardingStep, onSkip: (() -> Unit)?) {
    val progress by animateFloatAsState(
        targetValue = (step.ordinal + 1f) / OnboardingStep.entries.size,
        animationSpec = tween(PAGE_MS, easing = REVEAL_EASING),
        label = "onboarding-progress",
    )
    Column(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "第 ${step.ordinal + 1} 步 / 共 ${OnboardingStep.entries.size} 步",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.weight(1f))
            if (onSkip != null) {
                TextButton(onClick = onSkip) { Text("跳过") }
            }
        }
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(progress)
                    .background(MaterialTheme.colorScheme.primary, CircleShape),
            )
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
private fun WelcomePage(scroll: ScrollState) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
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
        Spacer(Modifier.height(16.dp))
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
private fun ReadyPage(draft: OnboardingDraft, scroll: ScrollState) {
    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(scroll)
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
        Spacer(Modifier.height(16.dp))
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
        // 这里是**明文**：落盘那一步由 SettingsRepository.encrypted() 统一加密。
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
