package com.suixing.agent.ui.assistant

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.suixing.agent.data.Assistant
import com.suixing.agent.data.AssistantAvatarStore
import com.suixing.agent.ui.components.FeatherIcon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * An assistant's face, drawn in one place: the picture the user uploaded, else the persona's
 * emoji, else the app's own feather.
 *
 * Every surface that shows an assistant — the chat card's header, the live bubble, the
 * assistant sheet's cards, the editor's own preview, the empty transcript's hero — calls this
 * instead of drawing an emoji or a feather itself. Three fallbacks in one place is what keeps
 * a persona that has a picture from showing its emoji on one screen and its photo on another.
 *
 * The picture is decoded off the main thread and remembered per path. The path is unique per
 * save ([AssistantAvatarStore.save] stamps it), which is what makes that remembering safe: a
 * picture the user replaces arrives as a new path, so every surface drawing it reloads instead
 * of holding a decode of the file that used to be there.
 */
@Composable
fun AssistantAvatar(
    face: String,
    avatarPath: String,
    size: Dp,
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
) {
    val picture = rememberAssistantPicture(avatarPath)
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(containerColor),
        contentAlignment = Alignment.Center,
    ) {
        when {
            // The picture fills the disc, cropped: a face photographed off-centre still reads
            // at 24dp, where letterboxing it would leave the actual subject a few pixels tall.
            picture != null -> Image(
                bitmap = picture,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
            // An emoji is a glyph drawn at the disc's scale rather than a line of text, so its
            // size is a ratio of the disc and not a typography token.
            face.isNotBlank() -> Text(
                text = face,
                fontSize = (size.value * 0.55f).sp,
                maxLines = 1,
            )
            else -> Icon(
                imageVector = FeatherIcon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(size * 0.62f),
            )
        }
    }
}

/** The same face, read off the persona itself; null draws the brand feather. */
@Composable
fun AssistantAvatar(
    assistant: Assistant?,
    size: Dp,
    modifier: Modifier = Modifier,
    shape: Shape = CircleShape,
    containerColor: Color = MaterialTheme.colorScheme.surfaceContainerHigh,
) = AssistantAvatar(
    face = assistant?.face.orEmpty(),
    avatarPath = assistant?.avatarPath.orEmpty(),
    size = size,
    modifier = modifier,
    shape = shape,
    containerColor = containerColor,
)

/** The stored picture, or null while there is none to draw. */
@Composable
private fun rememberAssistantPicture(path: String): ImageBitmap? {
    val context = LocalContext.current
    var picture by remember(path) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(path) {
        picture = if (path.isBlank()) {
            null
        } else {
            withContext(Dispatchers.IO) { AssistantAvatarStore.load(context, path) }?.asImageBitmap()
        }
    }
    return picture
}
