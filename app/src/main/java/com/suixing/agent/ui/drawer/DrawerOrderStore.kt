package com.suixing.agent.ui.drawer

import android.content.Context
import androidx.compose.runtime.Immutable
import com.suixing.agent.data.Conversation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 抽屉里两张手动顺序表。
 *
 * 为什么存在一个单独文件里、而不是 Conversation 上的一个字段：这是**查看偏好**，不是会话数据。
 * 放进会话记录里会让每份聊天都背上一个空字段，也会跟着导出一起走；放在 `filesDir/drawer-order`
 * （**无扩展名**）还有一个实际好处 —— `ChatRepository.load()` 只读 `chats/` 下 `ext == "json"`
 * 的文件，这个文件天然不会被它当成会话去解析。
 *
 * 置顶区与普通区各一张表，都只存 id：
 *  · `pinned` —— 置顶会话之间的先后（置顶区只看它；表外的置顶会话按 updatedAt 兜底追加）；
 *  · `normal` —— 普通区的手动基线（表外的会话按 updatedAt 排在最前，于是新消息自然冒回顶部）。
 *
 * 两张表都可能残留已删除的 id：读出来先剔一次，写盘时再剔一次。不剔的话列表里会空一格，
 * 表现就是「拖了但顺序看着没生效」。
 */
@Immutable
internal data class DrawerOrder(
    val pinned: List<String> = emptyList(),
    val normal: List<String> = emptyList(),
) {
    /** 去掉已经不存在的会话 id。 */
    fun prunedTo(known: Set<String>): DrawerOrder = DrawerOrder(
        pinned = pinned.filter { it in known },
        normal = normal.filter { it in known },
    )
}

/** 抽屉里分出来的两段（都已剔除归档会话）。 */
internal data class DrawerSections(
    val pinned: List<Conversation>,
    val normal: List<Conversation>,
)

/**
 * 按 [DrawerOrder] 把会话分成置顶区与普通区。
 *
 * 规则（用户原话：「置顶的就是最上面的呀……普通会话也能拖动啊，但是怎么拖都不能越过置顶，
 * 新会话照常出现在普通会话上面」）：
 *  · 置顶区永远在最前：顺序 = `pinned` 表序，表外的按 updatedAt 追加；
 *  · 普通区 = 表外的（新会话、刚收到新消息的）按 updatedAt 在前，其后按 `normal` 表序；
 *  · 只看展示顺序，不改任何会话数据（仓库那份列表仍是时间序）。
 */
internal fun drawerSections(
    conversations: List<Conversation>,
    order: DrawerOrder,
): DrawerSections {
    val visible = conversations.filterNot { it.archived }
    val byId = visible.associateBy { it.id }
    val pinnedIds = order.pinned.toHashSet()
    val normalIds = order.normal.toHashSet()
    val pinned = order.pinned.mapNotNull { byId[it] }.filter { it.pinned } +
        visible.filter { it.pinned && it.id !in pinnedIds }.sortedByDescending { it.updatedAt }
    val outside = visible
        .filter { !it.pinned && it.id !in normalIds }
        .sortedByDescending { it.updatedAt }
    val inside = order.normal.mapNotNull { byId[it] }.filter { !it.pinned }
    return DrawerSections(pinned = pinned, normal = outside + inside)
}

/**
 * `filesDir/drawer-order` 的读写。
 *
 * 读失败就当没有（退回时间序），写失败也就当没写成：一个排序偏好不值得让用户看见一条报错，
 * 更不能因为它写不进去就不显示会话列表。
 */
internal class DrawerOrderStore(context: Context) {

    private val file = File(context.filesDir, "drawer-order")

    /**
     * 单写者（issue #66 P0-1）：并发保存时**最新顺序说了算**，旧快照不会后到覆盖。
     * 队列本身在 `OrderWriteQueue`，这里只把"真正写盘"这一步交给它。
     */
    private val writer = OrderWriteQueue { order -> persist(order) }

    suspend fun load(): DrawerOrder = withContext(Dispatchers.IO) {
        runCatching {
            // 主文件缺失或损坏时退回 `.tmp`（issue #66 P0-2）：上次写到一半被杀，
            // 临时文件里往往就是完整的最后一份顺序，比直接丢掉偏好更好。
            val source = when {
                file.exists() -> file
                tempFile.exists() -> tempFile
                else -> return@runCatching DrawerOrder()
            }
            // **不用 org.json**：它在 JVM 单测里是 Android 桩（方法一律 not mocked），
            // 于是这条路在 CI 上永远走 catch 分支、偏好静默丢失——换成项目已在用的
            // kotlinx.serialization 之后，这套读写才真正能被单测覆盖（issue #66 P0-2）。
            val dto = json.decodeFromString(DrawerOrderDto.serializer(), source.readText())
            DrawerOrder(pinned = dto.pinned, normal = dto.normal)
        }.getOrDefault(DrawerOrder())
    }

    /** 提交一次保存：走单写者队列，过期内容会被合并掉。 */
    suspend fun save(order: DrawerOrder): Boolean = writer.enqueue(order)

    /** 真正写盘（在队列的锁内调用）。 */
    private suspend fun persist(order: DrawerOrder) = withContext(Dispatchers.IO) {
        runCatching {
            val payload = json.encodeToString(
                DrawerOrderDto.serializer(),
                DrawerOrderDto(pinned = order.pinned, normal = order.normal),
            )
            // 公共原子写（issue #69 P1-B）：中途被杀不会留下半行 JSON。
            com.suixing.agent.core.AtomicFiles.writeText(file, payload)
        }
        Unit
    }

    private val tempFile: File get() = File(file.parentFile, "${file.name}.tmp")

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class DrawerOrderDto(
        val pinned: List<String> = emptyList(),
        val normal: List<String> = emptyList(),
    )
}
