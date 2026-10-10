package com.suixing.agent.ui.schedules

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.suixing.agent.R
import com.suixing.agent.core.Core
import com.suixing.agent.data.Schedule
import com.suixing.agent.service.SchedulerService
import com.suixing.agent.tools.describeSchedule
import com.suixing.agent.tools.formatScheduleMoment
import com.suixing.agent.ui.components.EmptyState
import com.suixing.agent.ui.components.PageTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 定时任务：到点自己跑的那些提醒。
 *
 * 这是 [com.suixing.agent.data.ScheduleStore] 的第一张脸。在此之前提醒只能由随行用
 * `schedule_create` 建，用户既看不见也管不了（设置页里只有一行工具名）。这里只做三件事：
 * 看、暂停/恢复、删；**新建仍然交给随行**，因为一条提醒的核心是「到点做什么」那句 prompt，
 * 在对话框里说一句比在表单里填三段自然（空态里就写着怎么开口）。
 *
 * 每次改动都整表写回并重新上闹钟：系统里始终只留最近的那一个 alarm（见
 * [SchedulerService.arm]），少上一次就等于这条提醒不存在——包括「删掉最近的那条」这种改动，
 * 也必须让下一个顶上。
 */
@Composable
fun SchedulesScreen(onBack: () -> Unit) {
    val context = LocalContext.current.applicationContext
    val scope = rememberCoroutineScope()
    var schedules by remember { mutableStateOf<List<Schedule>>(emptyList()) }
    // 空态要等读完再画，否则冷启动那一帧会先说「还没有定时任务」再冒出列表。
    var loaded by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        schedules = withContext(Dispatchers.IO) { Core.schedules.read() }
        loaded = true
    }
    val apply: (List<Schedule>) -> Unit = { next ->
        schedules = next
        scope.launch {
            withContext(Dispatchers.IO) {
                Core.schedules.write(next)
                SchedulerService.arm(context)
            }
        }
    }
    val scheme = MaterialTheme.colorScheme
    Scaffold(
        containerColor = scheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { PageTopBar(title = "定时任务", onBack = onBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 72.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (loaded && schedules.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        title = "还没有定时任务",
                        subtitle = "让随行帮你建就行，例如「每天早上八点提醒我把今天的待办念一遍」——" +
                            "到点它会在一个新会话里执行，并发一条通知。",
                        modifier = Modifier.padding(top = 56.dp),
                    )
                }
            }
            items(schedules, key = { it.id }) { schedule ->
                ScheduleRow(
                    schedule = schedule,
                    onToggle = { on ->
                        apply(schedules.map { if (it.id == schedule.id) it.copy(enabled = on) else it })
                    },
                    onDelete = { apply(schedules.filterNot { it.id == schedule.id }) },
                )
            }
        }
    }
}

/**
 * One reminder: what it is called, when it fires next, and the two things the user can do
 * about it. The prompt itself is deliberately not shown — it is a sentence meant for the
 * model, and two lines of it per row would push the timing off the row it belongs to.
 */
@Composable
private fun ScheduleRow(
    schedule: Schedule,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = scheme.surfaceContainer,
        border = BorderStroke(1.dp, scheme.outlineVariant),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 14.dp, end = 6.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = schedule.title,
                    style = MaterialTheme.typography.bodyMedium,
                    // A paused reminder is still listed, but it stops competing with the live
                    // ones for the eye: the title is the loudest thing on the row.
                    color = if (schedule.enabled) scheme.onSurface else scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = scheduleLine(schedule),
                    style = MaterialTheme.typography.labelSmall,
                    color = scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(8.dp))
            Switch(checked = schedule.enabled, onCheckedChange = onToggle)
            RowAction(icon = R.drawable.ic_trash, label = "删除", onClick = onDelete)
        }
    }
}

/**
 * 时机 · 下一次。
 *
 * 暂停的那条不写「下一次」：`nextAt` 是上次上闹钟时算的，暂停之后它就不再有意义，照抄会
 * 让用户以为它还排着队。
 */
private fun scheduleLine(schedule: Schedule): String {
    val timing = describeSchedule(schedule)
    if (!schedule.enabled) return "$timing · 已暂停"
    val next = schedule.nextAt.takeIf { it > 0L }?.let { formatScheduleMoment(it) }
    return if (next == null) "$timing · 不会触发" else "$timing · 下一次 $next"
}

/**
 * One icon action of a row.
 *
 * 40dp 的触区：这是行尾的动作，和聊天页插话条那三个按钮同一尺寸（统一规格里 48dp 那条是给
 * 独立的纯图标盒的，行内动作跟着行高走）。[label] 是读屏名字，也是搜索用的锚点。
 */
@Composable
private fun RowAction(icon: Int, label: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = ripple(),
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = label,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(16.dp),
        )
    }
}
