package com.example.forgegen

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.TypedValue
import android.view.View
import android.widget.RemoteViews

/* ============================================================================
 * HOME SCREEN WIDGETS: THE VIEWS (3.6.0)
 * The two widgets as RemoteViews, drawn from ForgeWidgets' state. A tap opens the queue (through the app lock, like
 * the launcher shortcuts); Pause/Resume works like the Quick Settings tile without opening the app; Generate Again
 * opens the app and queues the last settings.
 * ============================================================================ */

/** "Queue" (2×1). */
class QueueWidget : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        manager: AppWidgetManager,
        ids: IntArray,
    ) = ForgeWidgets.widgetsChanged(context)

    override fun onDisabled(context: Context) = ForgeWidgets.widgetsChanged(context)
}

/** "ForgeGen" (4×2). */
class ForgeGenWidget : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        manager: AppWidgetManager,
        ids: IntArray,
    ) = ForgeWidgets.widgetsChanged(context)

    override fun onDisabled(context: Context) = ForgeWidgets.widgetsChanged(context)
}

object WidgetViews : WidgetRenderer {
    private const val OPEN_QUEUE_CODE = 10
    private const val GENERATE_CODE = 11
    private const val PAUSE_CODE = 12

    override fun present(context: Context): Boolean {
        val manager = AppWidgetManager.getInstance(context) ?: return false
        return manager.getAppWidgetIds(ComponentName(context, QueueWidget::class.java)).isNotEmpty() ||
            manager.getAppWidgetIds(ComponentName(context, ForgeGenWidget::class.java)).isNotEmpty()
    }

    override fun render(
        context: Context,
        state: WidgetState,
    ) {
        val manager = AppWidgetManager.getInstance(context) ?: return
        manager.updateAppWidget(ComponentName(context, QueueWidget::class.java), queueViews(context, state))
        manager.updateAppWidget(ComponentName(context, ForgeGenWidget::class.java), forgeGenViews(context, state))
    }

    private fun time(millis: Long) = QueueSchedule.formatTime(millis)

    private fun activity(
        context: Context,
        code: Int,
        action: String,
    ): PendingIntent =
        PendingIntent.getActivity(
            context,
            code,
            Intent(context, MainActivity::class.java).setAction(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    fun queueViews(
        context: Context,
        state: WidgetState,
    ): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_queue).apply {
            val idle = state.waiting == 0
            setTextViewText(R.id.queue_title, WidgetText.queueTitle(state))
            setTextColor(R.id.queue_title, if (idle) 0xFFB3B3B3.toInt() else 0xFFECECEC.toInt())
            setViewVisibility(R.id.queue_progress, if (idle) View.GONE else View.VISIBLE)
            setProgressBar(R.id.queue_progress, 100, state.percent ?: 0, false)
            setTextViewText(R.id.queue_line, WidgetText.queueLine(state, ::time))
            setViewVisibility(R.id.queue_line2, if (idle && state.imagesToday != null) View.VISIBLE else View.GONE)
            setTextViewText(R.id.queue_line2, "${state.imagesToday ?: 0} images today")
            setOnClickPendingIntent(R.id.widget_root, activity(context, OPEN_QUEUE_CODE, MainActivity.ACTION_OPEN_QUEUE))
        }

    fun forgeGenViews(
        context: Context,
        state: WidgetState,
    ): RemoteViews =
        RemoteViews(context.packageName, R.layout.widget_forgegen).apply {
            setImageViewResource(R.id.connection_dot, if (state.connected) R.drawable.widget_dot_on else R.drawable.widget_dot_off)
            setTextViewText(R.id.connection_text, if (state.connected) "Connected" else "Offline")
            setTextViewText(R.id.job_line, WidgetText.jobLine(state))
            setTextViewText(R.id.end_line, WidgetText.endLine(state, ::time))
            setProgressBar(R.id.job_progress, 100, if (state.waiting > 0) state.percent ?: 0 else 0, false)
            setTextViewText(R.id.images_value, WidgetText.images(state))
            setTextViewText(R.id.gpu_value, WidgetText.gpu(state))
            val (vram, vramLabel) = WidgetText.vram(state)
            setTextViewText(R.id.vram_value, vram)
            setTextViewText(R.id.vram_label, vramLabel)
            val pause = WidgetText.pauseLabel(state)
            setViewVisibility(R.id.pause_button, if (pause == null) View.GONE else View.VISIBLE)
            // Generate Again fills the row alone without its gap.
            setViewLayoutMargin(R.id.again_button, RemoteViews.MARGIN_START, if (pause == null) 0f else 8f, TypedValue.COMPLEX_UNIT_DIP)
            setTextViewText(R.id.pause_button, pause.orEmpty())
            setOnClickPendingIntent(
                R.id.pause_button,
                PendingIntent.getBroadcast(
                    context,
                    PAUSE_CODE,
                    Intent(context, WidgetActionReceiver::class.java).setAction(WidgetActionReceiver.ACTION_PAUSE_RESUME),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            )
            setOnClickPendingIntent(R.id.again_button, activity(context, GENERATE_CODE, MainActivity.ACTION_GENERATE_AGAIN))
            setOnClickPendingIntent(R.id.widget_root, activity(context, OPEN_QUEUE_CODE, MainActivity.ACTION_OPEN_QUEUE))
        }
}
