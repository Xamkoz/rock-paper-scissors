package com.rpsonline.app.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.TypedValue
import android.widget.RemoteViews
import androidx.core.app.NotificationCompat
import com.rpsonline.app.R
import com.rpsonline.app.ui.segment.AndroidCanvasDrawTarget
import com.rpsonline.app.ui.segment.NotificationSegmentDimensions
import com.rpsonline.app.ui.segment.SevenSegmentPainter
import com.rpsonline.app.ui.segment.SevenSegmentStatusRowLayout
import com.rpsonline.app.ui.segment.SevenSegmentThemeColors
import com.rpsonline.app.ui.segment.SegmentedNotificationStatus
import com.rpsonline.app.ui.segment.TopBarStatusRowSpec
import kotlin.math.max
import kotlin.math.roundToInt

/** Segmented notification visuals backed by [SevenSegmentPainter]. */
object SevenSegmentNotificationRenderer {
    fun buildRemoteViews(context: Context, state: TopBarStatusRowSpec): RemoteViews {
        val bitmap = renderComposite(context, state)
        val remoteViews = RemoteViews(context.packageName, R.layout.notification_segmented_status)
        remoteViews.setImageViewBitmap(R.id.segmented_status_image, bitmap)
        return remoteViews
    }

    /**
     * Collapsed, expanded, and heads-up all use the segmented composite bitmap.
     * [DecoratedCustomViewStyle] is required for targetSdk 31+; undecorated custom views are rejected.
     */
    fun applySegmentedStatusViews(
        builder: NotificationCompat.Builder,
        context: Context,
        state: TopBarStatusRowSpec,
        accessibilitySummary: String,
    ) {
        val remoteViews = buildRemoteViews(context, state)
        builder
            .setContentTitle(statusHeader(context, state.status))
            .setContentText(accessibilitySummary)
            .setStyle(NotificationCompat.DecoratedCustomViewStyle())
            .setCustomContentView(remoteViews)
            .setCustomBigContentView(remoteViews)
            .setCustomHeadsUpContentView(remoteViews)
    }

    fun statusHeader(context: Context, status: SegmentedNotificationStatus): String {
        val resId = when (status) {
            SegmentedNotificationStatus.IN_QUEUE -> R.string.in_queue
            SegmentedNotificationStatus.MATCH_FOUND -> R.string.match_found
            SegmentedNotificationStatus.IN_MATCH -> R.string.in_match
        }
        return context.getString(resId)
    }

    private fun renderComposite(context: Context, state: TopBarStatusRowSpec): Bitmap {
        val header = statusHeader(context, state.status)
        val digitWidthPx = dpToPx(context, NotificationSegmentDimensions.DIGIT_WIDTH_DP)
        val digitHeightPx = dpToPx(context, NotificationSegmentDimensions.DIGIT_HEIGHT_DP)
        val spacingPx = dpToPx(context, NotificationSegmentDimensions.SPACING_DP)
        val horizontalBleedPx = dpToPx(context, NotificationSegmentDimensions.HORIZONTAL_BLEED_DP)
        val verticalPadPx = dpToPx(context, NotificationSegmentDimensions.VERTICAL_PAD_DP)
        val headerLinePx = dpToPx(context, NotificationSegmentDimensions.HEADER_LINE_DP)
        val headerGapPx = dpToPx(context, NotificationSegmentDimensions.HEADER_ROW_GAP_DP)
        val rowWidthPx = SevenSegmentStatusRowLayout.widthPx(digitWidthPx, spacingPx)
        val totalWidthPx = rowWidthPx + horizontalBleedPx * 2
        val totalHeightPx = dpToPx(context, NotificationSegmentDimensions.compositeHeightDp())
        val rowTopPx = verticalPadPx + headerLinePx + headerGapPx
        val rowLeftPx = horizontalBleedPx.toFloat()

        val palette = SevenSegmentThemeColors.paletteFor(context)
        val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.litArgb
            textSize = spToPx(context, NotificationSegmentDimensions.HEADER_TEXT_SP).toFloat()
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val textHeightPx = headerPaint.fontMetrics.descent - headerPaint.fontMetrics.ascent
        val textBaselineY = verticalPadPx + (headerLinePx - textHeightPx) / 2f - headerPaint.fontMetrics.ascent

        val bitmap = Bitmap.createBitmap(
            max(1, totalWidthPx),
            max(1, totalHeightPx),
            Bitmap.Config.ARGB_8888,
        )
        bitmap.density = context.resources.displayMetrics.densityDpi
        val canvas = Canvas(bitmap)
        canvas.drawText(header, rowLeftPx, textBaselineY, headerPaint)
        SevenSegmentPainter.drawTopBarStatusRow(
            target = AndroidCanvasDrawTarget(canvas),
            left = rowLeftPx,
            top = rowTopPx.toFloat(),
            digitWidthPx = digitWidthPx.toFloat(),
            digitHeightPx = digitHeightPx.toFloat(),
            spacingPx = spacingPx.toFloat(),
            spec = state,
            palette = palette,
        )
        return bitmap
    }

    private fun dpToPx(context: Context, dp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_DIP,
            dp,
            context.resources.displayMetrics,
        ).roundToInt()
    }

    private fun spToPx(context: Context, sp: Float): Int {
        return TypedValue.applyDimension(
            TypedValue.COMPLEX_UNIT_SP,
            sp,
            context.resources.displayMetrics,
        ).roundToInt()
    }
}
