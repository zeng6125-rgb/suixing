package com.suixing.agent.service

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
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
import com.suixing.agent.MainActivity
import com.suixing.agent.R
import com.suixing.agent.core.Core
import kotlin.math.roundToInt

/** A touchable window limited to the controls; the full-screen effects remain untouchable. */
internal object UiOperationOverlay {
    private var root: LinearLayout? = null
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

    val isAttached: Boolean get() = root?.isAttachedToWindow == true

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
        val color = when (snapshot.result) {
            UiOperationResult.Failure -> 0xFFFFB4AB.toInt()
            UiOperationResult.Cancelled -> 0xFFBDC4CF.toInt()
            else -> 0xFFF3F5F8.toInt()
        }
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
        val nextFlags = if (captureHidden || inputDepth > 0) layout.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        else layout.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        if (hidden && wasVisible) container.animate().cancel()
        container.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
        if (layout.flags != nextFlags) {
            layout.flags = nextFlags
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
        val container = LinearLayout(host).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(host, 52)
            setPadding(dp(host, 8), dp(host, 4), dp(host, 8), dp(host, 4))
            background = GradientDrawable().apply {
                setColor(0xF2282A2E.toInt())
                cornerRadius = dp(host, 8).toFloat()
                setStroke(dp(host, 1), 0x24FFFFFF)
            }
            elevation = dp(host, 6).toFloat()
        }
        val badge = ImageView(host).apply {
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setImageResource(R.drawable.brand_mark_round)
        }
        val label = TextView(host).apply {
            textSize = 13f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(0xFFF3F5F8.toInt())
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        val labels = LinearLayout(host).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(host, 2), dp(host, 4), dp(host, 2))
            addView(TextView(host).apply {
                text = "JAgent"
                textSize = 10f
                setTextColor(0xFFB1B8C3.toInt())
                includeFontPadding = false
                maxLines = 1
            })
            addView(label)
        }
        val openButton = action(host, R.drawable.ic_arrow_left, "返回当前对话", 0xFFD9DFE9.toInt()).apply {
            setOnClickListener {
                val chatId = existingChatId(current?.owner) ?: return@setOnClickListener
                host.applicationContext.startActivity(Intent(host, MainActivity::class.java)
                    .putExtra(ChatCompletionNotifications.OPEN_CONVERSATION, chatId)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            }
        }
        val stopButton = action(host, R.drawable.ic_stop_filled, "停止当前屏幕操作任务", 0xFFFFB4AB.toInt()).apply {
            setOnClickListener {
                val operation = current ?: return@setOnClickListener
                if (operation.cancelRun?.invoke() == true && current?.owner == operation.owner) {
                    stoppingOwner = operation.owner
                    current?.let { show(host, null, it) }
                }
            }
        }
        container.addView(badge, LinearLayout.LayoutParams(dp(host, 32), dp(host, 32)).apply {
            rightMargin = dp(host, 8)
        })
        container.addView(labels, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        container.addView(openButton, LinearLayout.LayoutParams(dp(host, 44), dp(host, 44)))
        container.addView(stopButton, LinearLayout.LayoutParams(dp(host, 44), dp(host, 44)))
        val layout = WindowManager.LayoutParams(
            minOf(dp(host, 264), host.resources.displayMetrics.widthPixels - dp(host, 24)),
            WindowManager.LayoutParams.WRAP_CONTENT,
            if (accessibility) WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            else WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            x = 0
            y = dp(host, 12)
            alpha = feedbackOverlayAlpha(host, accessibility)
            if (captureHidden || inputDepth > 0) flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        }
        val hidden = captureHidden
        container.visibility = if (hidden) View.INVISIBLE else View.VISIBLE
        if (runCatching { windowManager.addView(container, layout) }.isFailure) return null
        root = container
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
            setPadding(dp(context, 13), dp(context, 13), dp(context, 13), dp(context, 13))
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
