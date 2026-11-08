package com.suixing.agent.service

import android.content.res.Configuration
import android.graphics.drawable.GradientDrawable
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.compose.ui.graphics.toArgb
import com.suixing.agent.R
import com.suixing.agent.core.Core
import com.suixing.agent.ui.theme.AppearancePalette
import com.suixing.agent.service.FloatConsoleService.Companion.THEME_SYSTEM
import com.suixing.agent.service.FloatConsoleService.Companion.THEME_DARK
import com.suixing.agent.service.FloatConsoleService.Companion.BALL_SIZE_DP
import com.suixing.agent.service.FloatConsoleService.Companion.TOUCH_TARGET_DP
import com.suixing.agent.service.FloatConsoleService.Palette

/** A 42dp eye-animated ball inside a 48dp touch target. Run state chooses the loop. */
internal fun FloatConsoleService.buildBall(): FrameLayout {
    resetBallTouchFeedback()
    val size = dp(BALL_SIZE_DP)
    val ball = FrameLayout(this).apply {
        layoutParams = LinearLayout.LayoutParams(dp(TOUCH_TARGET_DP), dp(TOUCH_TARGET_DP))
        setOnTouchListener { view, event -> onBallTouch(view, event) }
    }
    val visual = FrameLayout(this).apply {
        layoutParams = FrameLayout.LayoutParams(size, size, Gravity.CENTER)
        // 静态与四组动效使用同一个圆盘轮廓，透明区域直接露出壁纸。
    }
    ballMotionView = ImageView(this).apply {
        layoutParams = FrameLayout.LayoutParams(size, size)
        scaleType = ImageView.ScaleType.FIT_XY
        setImageResource(com.suixing.agent.R.drawable.brand_mark_round)
    }
    visual.addView(ballMotionView)
    ballMotion = BallMotion(this, ballMotionView, mainHandler, scope)
    ball.addView(visual)
    ballTouchFeedback = FloatBallTouchFeedback(visual).also { feedback ->
        ball.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = Unit
            override fun onViewDetachedFromWindow(view: View) = feedback.reset()
        })
    }
    return ball
}

internal fun FloatConsoleService.buildPanel(): LinearLayout = LinearLayout(this).apply {
    orientation = LinearLayout.VERTICAL
    visibility = View.GONE
    background = GradientDrawable().apply {
        cornerRadius = dp(20).toFloat()
        setColor(palette.surface)
        setStroke(dp(1), palette.stroke)
    }
    setPadding(dp(16), dp(14), dp(16), dp(14))
    layoutParams = LinearLayout.LayoutParams(panelWidth(), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(8)
    }

    addView(
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(
                TextView(context).apply {
                    text = "JAgent控制台"
                    setTextColor(palette.accent)
                    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                },
            )
            addView(collapseButton())
        },
    )

    stateText = TextView(context).apply {
        setTextColor(palette.ink)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        text = "空闲"
    }
    addView(stateText)

    // One line, always: it changes many times a second, and a box that grows and
    // shrinks with every delta is unreadable.
    liveText = TextView(context).apply {
        setTextColor(palette.muted)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        setPadding(0, dp(2), 0, 0)
    }
    addView(liveText)

    statsText = TextView(context).apply {
        setTextColor(palette.muted)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setPadding(0, dp(4), 0, dp(10))
    }
    addView(statsText)

    // 能看：which conversation this panel is talking about, and its last line or two. Without
    // the name the box below would write into a chat the user cannot see; without the lines
    // the panel could only ever say what the run is doing, never what was asked.
    conversationText = TextView(context).apply {
        setTextColor(palette.accent)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setPadding(0, dp(2), 0, 0)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    addView(conversationText)

    recentText = TextView(context).apply {
        setTextColor(palette.muted)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        // 顶部留 6dp：截图里内容首行紧贴会话名、像被裁掉半行，就是这里没有呼吸空间。
        setPadding(0, dp(6), 0, dp(8))
        // 不再 maxLines=2：内容现在包在可滚容器里，用户拖高面板就能多看 —— 这正是「悬浮窗
        // 最主要的是显示内容给用户」要的效果。行数交给高度，而不是写死两行截断。
    }
    // 会话内容包进 ScrollView：默认随内容自适应（WRAP_CONTENT），用户拖手柄纵向把它固定成
    // 某个高度，内容超出就滚 —— 拖矮也不会把下面的输入框/停止按钮顶出去（它们在这个滚动容器
    // 之外）。这就是「高度可调」的落点，也是原注释里「固定高度会裁掉 stop 行」那个顾虑的正解。
    contentScroll = ScrollView(context).apply {
        isFillViewport = true
        addView(
            recentText,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            contentHeightPx(),
        )
    }
    addView(contentScroll)

    steerInput = EditText(context).apply {
        // Neutral on purpose: this one box both steers a run and opens the next turn.
        hint = "说一句…"
        setHintTextColor(palette.muted)
        setTextColor(palette.ink)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        maxLines = 3
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(palette.field)
        }
        setPadding(dp(10), dp(8), dp(10), dp(8))
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }

    addView(
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(steerInput)
            // One button for both cases, labelled by the run state: 「插话」 into the run in
            // flight, 「发送」 when nothing is running. See [sendFromPanel].
            sendButton = actionButton("发送", accent = true) { sendFromPanel() }
            addView(sendButton)
        },
    )

    // 底部动作行：「回到对话」居左、「停止」居右，**同一行对齐**（截图反馈：原来「回到对话」
    // 独占一行、停止孤悬在下一行右下角，中间一大块死空间）。单 run 时停止按钮就落在这行右侧；
    // 多 run 时这行只留「回到对话」，带标题的停止行进上面的 [stopArea]。
    stopArea = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
    }
    addView(stopArea)
    addView(
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(actionButton("回到对话") { openChat() })
            addView(
                View(context).apply {
                    layoutParams = LinearLayout.LayoutParams(0, 1, 1f)
                },
            )
            stopButton = actionButton("停止", accent = true) {
                // 单 run 时这枚就是那个停止键：停最新的那个 run（与面板跟随的是同一个）。
                Core.engine.newestRun()?.let { stopRun(it) }
            }
            stopButton.visibility = View.GONE
            addView(stopButton)
        },
    )
    // The panel's other lines are rewritten from the run's state on every change, so the
    // confirmation of a stop needs a line of its own to still be there when the run it
    // named has already left the list.
    feedbackText = TextView(context).apply {
        setTextColor(palette.muted)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setPadding(0, dp(6), 0, 0)
        visibility = View.GONE
    }
    addView(feedbackText)
    // Last, so it reads as the panel's own corner: the handle that says 「this card can be
    // dragged wider」 without a label.
    addView(resizeHandle())
}

internal fun FloatConsoleService.collapseButton(): TextView = TextView(this).apply {
    text = "收起"
    setTextColor(palette.muted)
    setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
    setPadding(dp(8), 0, 0, 0)
    setOnClickListener { setExpanded(false) }
}

internal fun FloatConsoleService.actionButton(label: String, accent: Boolean = false, onClick: () -> Unit): TextView =
    TextView(this).apply {
        text = label
        setTextColor(if (accent) palette.onAccent else palette.ink)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        gravity = Gravity.CENTER
        background = GradientDrawable().apply {
            cornerRadius = dp(12).toFloat()
            setColor(if (accent) palette.accent else palette.field)
        }
        setPadding(dp(14), dp(9), dp(14), dp(9))
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.WRAP_CONTENT,
            LinearLayout.LayoutParams.WRAP_CONTENT,
        ).apply { leftMargin = dp(8) }
        setOnClickListener { onClick() }
    }

/**
 * 悬浮面板的颜色整份取自当前配色，不再写死一套墨绿：会话行、完成/运行标记和悬浮球
 * 跟的是同一个 primary，切到 JAgent 配色（陶土橙）或 Codex 时一起变。
 */
internal fun FloatConsoleService.paletteFor(themeMode: Int, paletteMode: Int): Palette {
    val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
        Configuration.UI_MODE_NIGHT_YES
    val dark = themeMode == THEME_DARK || (themeMode == THEME_SYSTEM && night)
    val scheme = AppearancePalette.fromId(paletteMode).scheme(dark)
    fun Int.withAlpha(alpha: Float): Int =
        (this and 0x00FFFFFF) or ((alpha * 255).toInt() shl 24)
    return Palette(
        surface = scheme.background.toArgb().withAlpha(0.95f),
        field = scheme.surfaceContainer.toArgb(),
        ink = scheme.onSurface.toArgb(),
        muted = scheme.onSurface.toArgb().withAlpha(0.55f),
        accent = scheme.primary.toArgb(),
        onAccent = scheme.onPrimary.toArgb(),
        stroke = scheme.outline.toArgb().withAlpha(0.2f),
    )
}

internal fun FloatConsoleService.dp(value: Int): Int = TypedValue.applyDimension(
    TypedValue.COMPLEX_UNIT_DIP,
    value.toFloat(),
    resources.displayMetrics,
).toInt()
