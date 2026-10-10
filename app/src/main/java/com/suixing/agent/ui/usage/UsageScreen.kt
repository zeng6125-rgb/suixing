package com.suixing.agent.ui.usage

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.suixing.agent.core.Core
import com.suixing.agent.data.DayUsage
import com.suixing.agent.data.ModelUsage
import com.suixing.agent.data.Roles
import com.suixing.agent.data.TokenTally
import com.suixing.agent.data.Transcript
import com.suixing.agent.ui.components.PageTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Where the tokens went: three headline cards, a 30-day bar chart, per-model subtotals.
 *
 * [Core.usage] is the process-wide store; [com.suixing.agent.data.UsageStore.refresh] is
 * watermark-incremental, so opening the page is a safe place to fold in whatever the
 * chats have grown since the last look.
 */
@Composable
fun UsageScreen(onBack: () -> Unit) {
    val store = Core.usage
    var days by remember { mutableStateOf<List<DayUsage>>(emptyList()) }
    var models by remember { mutableStateOf<List<ModelUsage>>(emptyList()) }
    var totals by remember { mutableStateOf(TokenTally()) }
    var longestTurn by remember { mutableStateOf(0L) }
    var conversations by remember { mutableStateOf(0) }
    LaunchedEffect(Unit) {
        val snapshot = withContext(Dispatchers.IO) { store.refresh() }
        days = store.byDay(30)
        models = store.byModel()
        totals = snapshot.totals
        val chats = Core.chats.conversations.value
        conversations = chats.size
        // One run — one engine pass — is what one bubble on the chat screen reports, and it
        // is the unit this card is named after. Context is cumulative, so only the run's
        // newest round knows the real prompt size: the prompt is not summed, the output is
        // (the same reading as ChatScreen's Usage.of). Rounds written before branching carry
        // no run id, and each of those is its own group — matching the chat bubble's merge
        // guard (ChatScreen.kt:1148, `run.isNotEmpty()`). Folding them into one BASE_RUN
        // group instead would add every old reply in a chat up as though it were one run.
        // Every chat's every message is walked here — the heaviest step on this page, so it
        // stays off the main thread like the store refresh above.
        longestTurn = withContext(Dispatchers.IO) {
            chats.maxOfOrNull { chat ->
                chat.messages
                    .filter { it.role == Roles.ASSISTANT }
                    .groupBy { message -> Transcript.runOf(message).ifEmpty { message.id } }
                    .values
                    .maxOfOrNull { rounds ->
                        rounds.last().promptTokens.toLong() + rounds.sumOf { it.completionTokens.toLong() }
                    } ?: 0L
            } ?: 0L
        }
    }
    val scheme = MaterialTheme.colorScheme
    Scaffold(
        containerColor = scheme.background,
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { PageTopBar(title = "用量", onBack = onBack) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .navigationBarsPadding(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 0.dp, bottom = 72.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatCard("累计 tokens", compact(totals.total), Modifier.weight(1f))
                    StatCard("会话数", "$conversations", Modifier.weight(1f))
                    StatCard("最长单轮", compact(longestTurn), Modifier.weight(1f))
                }
            }
            item {
                SectionTitle("近 30 天")
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = scheme.surfaceContainer,
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (days.isEmpty()) {
                            Text(
                                text = "还没有用量记录",
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                        val peak = days.maxOfOrNull { it.tokens.total } ?: 0L
                        days.forEach { day ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = day.date.substring(5),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = scheme.onSurfaceVariant,
                                    modifier = Modifier.width(44.dp),
                                )
                                Box(
                                    Modifier
                                        .weight(1f)
                                        .heightIn(min = 10.dp),
                                    contentAlignment = Alignment.CenterStart,
                                ) {
                                    Box(
                                        Modifier
                                            .fillMaxWidth(fraction = if (peak > 0) (day.tokens.total.toFloat() / peak).coerceIn(0.02f, 1f) else 0.02f)
                                            .height(10.dp)
                                            .background(scheme.primary, RoundedCornerShape(5.dp)),
                                    )
                                }
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    text = compact(day.tokens.total),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = scheme.onSurfaceVariant,
                                    modifier = Modifier.width(52.dp),
                                )
                            }
                        }
                    }
                }
            }
            item {
                SectionTitle("按模型")
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = MaterialTheme.shapes.large,
                    color = scheme.surfaceContainer,
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (models.isEmpty()) {
                            Text(
                                text = "还没有用量记录",
                                style = MaterialTheme.typography.bodySmall,
                                color = scheme.onSurfaceVariant,
                            )
                        }
                        models.forEach { row ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = row.model,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                )
                                Text(
                                    text = compact(row.tokens.total),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = scheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(top = 6.dp),
    )
}

@Composable
private fun StatCard(title: String, value: String, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(4.dp))
            Text(text = value, style = MaterialTheme.typography.headlineSmall)
        }
    }
}

/** `1234567` → `1.2M`; `12345` → `12.3k`; small numbers stay as they are. */
private fun compact(value: Long): String = when {
    value >= 1_000_000 -> String.format(java.util.Locale.US, "%.1fM", value / 1_000_000.0)
    value >= 1_000 -> String.format(java.util.Locale.US, "%.1fk", value / 1_000.0)
    else -> "$value"
}
