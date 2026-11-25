package com.suixing.agent.service

import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ImageButton
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
        cornerRadius = dp(8).toFloat()
        setColor(palette.surface)
        setStroke(dp(1), palette.stroke)
    }
    setPadding(dp(12), dp(10), dp(12), dp(6))
    layoutParams = LinearLayout.LayoutParams(panelWidth(), LinearLayout.LayoutParams.WRAP_CONTENT).apply {
        topMargin = dp(8)
    }

    conversationText = TextView(context).apply {
        setTextColor(palette.ink)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        setTypeface(typeface, Typeface.BOLD)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
    }
    stateText = TextView(context).apply {
        setTextColor(palette.accent)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
        setPadding(0, dp(3), 0, 0)
        maxLines = 1
        ellipsize = TextUtils.TruncateAt.END
        text = "空闲"
    }
    addView(
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    addView(conversationText)
                    addView(stateText)
                },
            )
            addView(actionIcon(R.drawable.ic_arrow_left, "回到对话") { openChat() })
            stopButton = actionIcon(R.drawable.ic_stop_filled, "停止", accent = true) {
                Core.engine.newestRun()?.let { stopRun(it) }
            }
            stopButton.visibility = View.GONE
            addView(stopButton)
            addView(collapseButton())
        },
    )
    addView(
        View(context).apply {
            setBackgroundColor(palette.stroke)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)).apply {
                topMargin = dp(8)
            }
        },
    )

    recentText = TextView(context).apply {
        setTextColor(palette.ink)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setLineSpacing(dp(2).toFloat(), 1f)
    }
    liveAnswerText = TextView(context).apply {
        setTextColor(palette.ink)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        setLineSpacing(dp(2).toFloat(), 1f)
        setPadding(0, dp(10), 0, 0)
        maxLines = 4
        ellipsize = TextUtils.TruncateAt.END
        visibility = View.GONE
    }
    stopArea = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, 0, 0, dp(8))
        visibility = View.GONE
    }
    // The resizable body scrolls as a whole; the conversation and composer stay reachable.
    contentScroll = ScrollView(context).apply {
        isFillViewport = true
        isHorizontalScrollBarEnabled = false
        clipToPadding = false
        addView(
            LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(10), 0, dp(10))
                addView(stopArea)
                addView(recentText)
                addView(liveAnswerText)
            },
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            contentHeightPx(),
            1f,
        )
    }
    addView(contentScroll)
    addView(
        View(context).apply {
            setBackgroundColor(palette.stroke)
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
        },
    )

    steerInput = EditText(context).apply {
        // Neutral on purpose: this one box both steers a run and opens the next turn.
        hint = "说一句…"
        setHintTextColor(palette.muted)
        setTextColor(palette.ink)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        maxLines = 3
        minHeight = dp(40)
        background = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(palette.field)
        }
        setPadding(dp(10), dp(8), dp(10), dp(8))
        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
    }

    addView(
        LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(10) }
            addView(steerInput)
            // One button for both cases, labelled by the run state: 「插话」 into the run in
            // flight, 「发送」 when nothing is running. See [sendFromPanel].
            sendButton = actionButton("发送", accent = true) { sendFromPanel() }
            addView(sendButton)
        },
    )
    addView(resizeHandle())
}

internal fun FloatConsoleService.collapseButton(): View =
    actionIcon(R.drawable.ic_chevron_up, "收起") { setExpanded(false) }

private fun FloatConsoleService.actionIcon(
    drawable: Int,
    description: String,
    accent: Boolean = false,
    onClick: () -> Unit,
): ImageButton = ImageButton(this).apply {
    setImageResource(drawable)
    imageTintList = ColorStateList.valueOf(if (accent) palette.accent else palette.muted)
    scaleType = ImageView.ScaleType.FIT_CENTER
    val inset = dp(if (drawable == R.drawable.ic_stop_filled) 11 else 9)
    setPadding(inset, inset, inset, inset)
    contentDescription = description
    tooltipText = description
    background = RippleDrawable(
        ColorStateList.valueOf(palette.stroke),
        null,
        GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(palette.ink)
        },
    )
    layoutParams = LinearLayout.LayoutParams(dp(40), dp(40))
    setOnClickListener { onClick() }
}

internal fun FloatConsoleService.actionButton(label: String, accent: Boolean = false, onClick: () -> Unit): TextView =
    TextView(this).apply {
        text = label
        setTextColor(if (accent) palette.onAccent else palette.ink)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
        gravity = Gravity.CENTER
        minHeight = dp(40)
        background = GradientDrawable().apply {
            cornerRadius = dp(8).toFloat()
            setColor(if (accent) palette.accent else palette.field)
        }
        setPadding(dp(12), dp(8), dp(12), dp(8))
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
