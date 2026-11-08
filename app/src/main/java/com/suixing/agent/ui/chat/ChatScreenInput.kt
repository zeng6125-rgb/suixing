package com.suixing.agent.ui.chat

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Input I/O and queued attachment restoration stay outside rendering. */
internal class ChatInputController(
    private val state: ChatUiState,
    private val actions: ChatScreenActions,
    private val scope: CoroutineScope,
    private val context: Context,
    private val snackbarHostState: SnackbarHostState,
    val attachments: List<Attachment>,
) {
    private val draft = state.draft
    fun currentInput(): String = actions.draftFor(state.conversationId).text
    fun setDraft(
        text: String = currentInput(),
        uri: String? = draft.attachmentUri,
        name: String? = draft.attachmentName,
        imageNames: List<String> = draft.imageNames,
    ) {
        actions.onDraftChange(state.conversationId, Draft(text, uri, name, imageNames))
    }

    /**
     * 「修改」 on a queued line: the line comes back to the composer, text **and picture**.
     *
     * The text is appended rather than substituted — the field may hold something the user is in
     * the middle of typing, and taking a queued line back must not throw that away without a word.
     *
     * The picture needs three things, which is why this is not a one-liner: the bytes have to be
     * decoded off the main thread, they have to go into the view model's attachment cache (that is
     * what the chip and the send path read), and the draft's `attachmentUri`/`attachmentName` have
     * to change so the composer's `remember` re-reads that cache. [QUEUED_ATTACHMENT_URI] is a
     * marker, not a real uri: this picture exists only in memory, so after a restart the chip is
     * simply absent (the cache is gone) while the text survives. That is the honest outcome — the
     * alternative would be a draft pointing at a uri that cannot be read.
     *
     * A decode that fails still returns the text: losing the picture is a smaller loss than losing
     * the sentence the user typed.
     *
     * Returns the number of pictures that could not be decoded, or null when the user left the
     * conversation while the decode was running (the queued row is deliberately left alone in
     * both cases). The caller retracts the queue entry only when this returns a number.
     */
    suspend fun restoreQueuedSteer(steer: PendingSteer): Int? {
        // Captured before decoding: the write below must land in the conversation the user
        // tapped in, never in whatever happens to be on screen when the decode finishes.
        val chatId = state.conversationId
        // 多附件：排队行的图逐张解回缓存列；一张解不开就跳过它（丢一张图比丢整句话小）。
        // 解码并行（顺序 decodeAttachment 会在多图时串行等待），且 **bitmapAttachment 必须
        // 在 Default 上跑**：JPEG 压缩 + Base64 编码是主线程杀手（cameraLauncher 路径早就
        // 包了 withContext(Default)，这里原来漏了 —— 点「修改」排队行会卡一拍）。
        val decoded = coroutineScope {
            steer.imagesBase64.map { base64 ->
                async {
                    val bitmap = decodeAttachment(base64) ?: return@async null
                    withContext(Dispatchers.Default) { bitmapAttachment(bitmap, QUEUED_IMAGE_LABEL) }
                }
            }.awaitAll()
        }
        val restored = decoded.filterNotNull()
        // A switch during the decode: keep the queue row and touch nothing here. The caller
        // reports that instead of silently dropping the line.
        if (actions.screenState.value.conversationId != chatId) return null
        restored.forEach { actions.addAttachment(chatId, it) }
        // **读最新草稿**（issue #76 F-01）：解码期间用户可能又打了字；用点击时那份会把它
        // 覆盖掉。附件字段取最新那份，恢复排队行不能顺带换掉用户刚选的附件。
        val latest = actions.draftFor(chatId)
        actions.onDraftChange(chatId, mergeRestoredDraft(latest, steer, restored.size))
        return steer.imagesBase64.size - restored.size
    }
    fun keepAttachment(chatId: String, attachment: Attachment) {
        actions.addAttachment(chatId, attachment)
        val current = actions.draftFor(chatId)
        actions.onDraftChange(chatId, current.copy(imageNames = current.imageNames + attachment.label))
    }

    fun attach(uri: Uri?, kind: AttachKind) {
        if (uri == null) return
        val chatId = state.conversationId
        scope.launch {
            val picked = withContext(Dispatchers.IO) { readAttachment(context, uri, kind) }
            if (picked == null) {
                snackbarHostState.showSnackbar(if (kind == AttachKind.FILE) "读不到这个文件" else "读不到这张图片")
            } else {
                // 多附件：追加而不是覆盖（用户：「图片为什么只能添加一张」）。draft 的 imageNames
                // 是 remember 的重读触发器，标签列与缓存列同序。
                keepAttachment(chatId, picked)
            }
        }
    }


}

internal class ChatInputBindings(
    val controller: ChatInputController,
    val pickImage: () -> Unit,
    val takePhoto: () -> Unit,
    val pickFile: () -> Unit,
    val openMic: () -> Unit,
)

@Composable
internal fun rememberChatInputBindings(
    state: ChatUiState,
    actions: ChatScreenActions,
    scope: CoroutineScope,
    snackbarHostState: SnackbarHostState,
    dialogs: ChatDialogState,
): ChatInputBindings {
    val draft = state.draft
    val context = LocalContext.current
    // The conversation is a key as well: the cache is per conversation, and two chats that both
    // hold a 「修改」-restored picture carry the *same* marker uri and label — without this, coming
    // back from one to the other kept the previous chat's decoded bitmap (the chip would show, and
    // send, the wrong picture).
    val attachments = remember(
        state.conversationId,
        draft.attachmentUri,
        draft.attachmentName,
        draft.imageNames,
    ) {
        actions.attachmentsFor(state.conversationId)
    }

    val controller = remember(state.conversationId, IdentityKey(draft), actions, scope, context, snackbarHostState, IdentityKey(attachments)) {
        ChatInputController(state, actions, scope, context, snackbarHostState, attachments)
    }
    val micPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            dialogs.voiceOpen = true
        } else {
            scope.launch { snackbarHostState.showSnackbar("没有麦克风权限，语音输入用不了") }
        }
    }

    val imagePicker = rememberLauncherForActivityResult(
        // 多附件：PickMultipleVisualMedia 让用户一次连选多张（用户选了「不限张数」）。
        ActivityResultContracts.PickMultipleVisualMedia(),
    ) { uris -> uris.forEach { controller.attach(it, AttachKind.IMAGE) } }

    val cameraLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicturePreview(),
    ) { bitmap ->
        if (bitmap != null) {
            val chatId = state.conversationId
            scope.launch {
                val picked = withContext(Dispatchers.Default) { bitmapAttachment(bitmap) }
                controller.keepAttachment(chatId, picked)
            }
        }
    }

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri -> controller.attach(uri, AttachKind.FILE) }


    return remember(controller, imagePicker, cameraLauncher, filePicker, micPermission, context, dialogs) {
        ChatInputBindings(
            controller = controller,
            pickImage = { imagePicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
            takePhoto = { cameraLauncher.launch(null) },
            pickFile = { filePicker.launch("*/*") },
            openMic = {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
                    dialogs.voiceOpen = true
                } else micPermission.launch(Manifest.permission.RECORD_AUDIO)
            },
        )
    }
}
