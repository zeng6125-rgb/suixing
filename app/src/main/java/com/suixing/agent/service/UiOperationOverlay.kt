package com.suixing.agent.service

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.provider.Settings
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import com.suixing.agent.MainActivity
import com.suixing.agent.R
import com.suixing.agent.core.Core
import com.suixing.agent.ui.components.ChatTitleShadowColor
import com.suixing.agent.ui.components.ChatTitleShadowElevationDp
import com.suixing.agent.ui.components.PopupShadowMarginDp
import com.suixing.agent.ui.theme.AppearancePalette
import com.suixing.agent.ui.theme.resolvedScheme
import kotlin.math.roundToInt

/** A touchable window limited to the controls; the full-screen effects remain untouchable. */
internal object UiOperationOverlay {
    private var root: LinearLayout? = null
    private var capsule: LinearLayout? = null
    private var manager: WindowManager? = null
    private var params: WindowManager.LayoutParams? = null
    private var title: TextView? = null
    private var icon: ImageView? = null
    private var open: ImageButton? = null
    private var stop: ImageButton? = null
    private var current: UiOperationSnapshot? = null
    private var stoppingOwner: UiOperationOwner? = null
    private var captureHidden = false
    private var inputDepth = 0
    private var colorScheme = AppearancePalette.CLAUDE.scheme(dark = false)

    val isAttached: Boolean get() = root?.isAttachedToWindow == true

    fun setAppearance(context: Context, uiTheme: Int, themeMode: Int, paletteMode: Int) {
        val night = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
        val next = resolvedScheme(uiTheme, paletteMode, themeMode == 2 || (themeMode == 0 && night))
        if (colorScheme == next) return
        colorScheme = next
        capsule?.let { container ->
            (container.background as GradientDrawable).setColor(colorScheme.surfaceContainerHigh.toArgb())
            open?.imageTintList = ColorStateList.valueOf(colorScheme.primary.toArgb())
            stop?.imageTintList = ColorStateList.valueOf(colorScheme.error.toArgb())
            current?.let { show(context, null, it) }
        }
    }

    fun show(context: Context, service: Context?, snapshot: UiOperationSnapshot) {
        val wasHidden = current == null
        current = snapshot
        if (snapshot.owner != stoppingOwner || snapshot.result == UiOperationResult.Cancelled) stoppingOwner = null
        val existing = root
        val container = existing ?: create(service ?: context.applicationContext, service != null) ?: return
        val hidden = captureHidden
        container.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
        if (!hidden && existing != null && wasHidden) {
            container.animate().cancel()
            container.animate().alpha(1f).translationY(0f).setDuration(160).start()
        }
        val label = if (stoppingOwner != null) "正在停止" else snapshot.label
        if (title?.text?.toString() != label) title?.text = label
        val controlsAvailable = !captureHidden && inputDepth == 0
        icon?.contentDescription = "JAgent，$label"
        val color = colorScheme.onSurfaceVariant.toArgb()
        if (title?.currentTextColor != color) title?.setTextColor(color)
        open?.visibility = if (existingChatId(snapshot.owner) != null) View.VISIBLE else View.GONE
        stop?.visibility = if (snapshot.cancelRun != null) View.VISIBLE else View.GONE
        open?.isEnabled = controlsAvailable
        open?.alpha = if (controlsAvailable) 1f else 0.45f
        stop?.isEnabled = controlsAvailable && stoppingOwner == null
        stop?.alpha = if (controlsAvailable && stoppingOwner == null) 1f else 0.45f
    }

    fun hide() {
        if (current == null) return
        current = null
        stoppingOwner = null
        val container = root ?: return
        if (captureHidden) {
            release()
            return
        }
        container.animate().cancel()
        container.animate().alpha(0f).translationY(-dp(container.context, 4).toFloat())
            .setDuration(120).withEndAction {
                if (root === container && current == null) release()
            }.start()
    }

    fun setCaptureHidden(hidden: Boolean) {
        if (captureHidden == hidden) return
        captureHidden = hidden
        updateInteraction()
    }

    fun beginInput() {
        inputDepth++
        updateInteraction()
    }

    fun endInput() {
        inputDepth--
        updateInteraction()
    }

    private fun updateInteraction() {
        val container = root ?: return
        val layout = params ?: return
        val hidden = captureHidden
        val wasVisible = container.visibility == View.VISIBLE
        val nextFlags = uiOperationWindowFlags(captureHidden, inputDepth)
        val nextAlpha = feedbackOverlayAlpha(
            container.context,
            layout.type == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            touchable = nextFlags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE == 0,
        )
        if (hidden && wasVisible) container.animate().cancel()
        container.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
        if (layout.flags != nextFlags || layout.alpha != nextAlpha) {
            layout.flags = nextFlags
            layout.alpha = nextAlpha
            runCatching { manager?.updateViewLayout(container, layout) }
        }
        if (!hidden && !wasVisible && current != null) {
            container.alpha = 1f
            container.translationY = 0f
        }
        current?.let { show(container.context, null, it) }
    }

    fun release() {
        val container = root
        val windowManager = manager
        root = null
        capsule = null
        manager = null
        params = null
        title = null
        icon = null
        open = null
        stop = null
        container?.animate()?.cancel()
        container?.visibility = View.GONE
        if (container != null) runCatching { windowManager?.removeView(container) }
    }

    private fun create(host: Context, accessibility: Boolean): LinearLayout? {
        if (!accessibility && !Settings.canDrawOverlays(host)) return null
        val windowManager = host.getSystemService(WindowManager::class.java) ?: return null
        val card = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(host, 48)
            setPadding(dp(host, 10), dp(host, 2), dp(host, 4), dp(host, 2))
            background = GradientDrawable().apply {
                setColor(colorScheme.surfaceContainerHigh.toArgb())
                cornerRadius = dp(host, 1000).toFloat()
            }
            clipToOutline = true
            elevation = ChatTitleShadowElevationDp * host.resources.displayMetrics.density
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                val shadowColor = ChatTitleShadowColor.toArgb()
                outlineAmbientShadowColor = shadowColor
                outlineSpotShadowColor = shadowColor
            }
        }
        val badge = ImageView(host).apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setImageResource(R.drawable.brand_mark_round)
        }
        val label = TextView(host).apply {
            textSize = 14f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(colorScheme.onSurfaceVariant.toArgb())
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val openButton = action(host, R.drawable.ic_arrow_left, "返回当前对话", colorScheme.primary.toArgb()).apply {
            setOnClickListener {
                val chatId = existingChatId(current?.owner) ?: return@setOnClickListener
                host.applicationContext.startActivity(Intent(host, MainActivity::class.java)
                    .putExtra(ChatCompletionNotifications.OPEN_CONVERSATION, chatId)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            }
        }
        val stopButton = action(host, R.drawable.ic_stop_filled, "停止当前屏幕操作任务", colorScheme.error.toArgb()).apply {
            setOnClickListener {
                val operation = current ?: return@setOnClickListener
                if (operation.cancelRun?.invoke() == true && current?.owner == operation.owner) {
                    stoppingOwner = operation.owner
                    current?.let { show(host, null, it) }
                }
            }
        }
        card.addView(badge, LinearLayout.LayoutParams(dp(host, 28), dp(host, 28)).apply {
            rightMargin = dp(host, 8)
        })
        card.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        card.addView(openButton, LinearLayout.LayoutParams(dp(host, 44), dp(host, 44)))
        card.addView(stopButton, LinearLayout.LayoutParams(dp(host, 44), dp(host, 44)))
        val container = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            clipChildren = false
            clipToPadding = false
            val shadowMargin = dp(host, PopupShadowMarginDp)
            setPadding(shadowMargin, dp(host, 12), shadowMargin, shadowMargin)
            addView(card, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
            ))
        }
        val layout = WindowManager.LayoutParams(
            minOf(dp(host, 312), host.resources.displayMetrics.widthPixels),
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (accessibility) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            uiOperationWindowFlags(captureHidden, inputDepth),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = 0
            alpha = feedbackOverlayAlpha(host, accessibility,
                touchable = flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE == 0)
        }
        val hidden = captureHidden
        container.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
        if (runCatching { windowManager.addView(container, layout) }.isFailure) return null
        root = container
        capsule = card
        manager = windowManager
        params = layout
        title = label
        icon = badge
        open = openButton
        stop = stopButton
        container.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) { if (root === container) release() }
        })
        if (!hidden) {
            container.alpha = 0f
            container.translationY = -dp(host, 6).toFloat()
            container.animate().alpha(1f).translationY(0f).setDuration(160)
                .setInterpolator(DecelerateInterpolator()).start()
        }
        return container
    }

    private fun action(context: Context, resource: Int, description: String, color: Int): ImageButton =
        ImageButton(context).apply {
            setImageResource(resource)
            imageTintList = ColorStateList.valueOf(color)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val inset = dp(context, if (resource == R.drawable.ic_stop_filled) 13 else 11)
            setPadding(inset, inset, inset, inset)
            contentDescription = description
            tooltipText = description
            background = null
            val ripple = TypedValue()
            if (context.theme.resolveAttribute(android.R.attr.selectableItemBackgroundBorderless, ripple, true)) {
                setBackgroundResource(ripple.resourceId)
            }
        }

    private fun existingChatId(owner: UiOperationOwner?): String? =
        owner?.chatId?.takeIf { chatId -> Core.chats.conversations.value.any { it.id == chatId } }

    private fun dp(context: Context, value: Int): Int = (value * context.resources.displayMetrics.density).roundToInt()
}

internal fun uiOperationWindowFlags(captureHidden: Boolean, inputDepth: Int): Int {
    val base = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
    return if (captureHidden || inputDepth > 0) base or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE else base
}
