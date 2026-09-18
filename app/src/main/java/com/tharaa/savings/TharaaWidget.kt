package com.tharaa.savings

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews

/**
 * Home-screen widget showing the current combined value at a glance, without opening (or
 * unlocking) the app. Opt-in: it only exists once the user places it. Note it deliberately shows
 * the balance outside the passcode wall, which is the whole point of a glanceable widget.
 */
class TharaaWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val views = render(context)
        ids.forEach { manager.updateAppWidget(it, views) }
    }

    companion object {
        /** Re-render every placed widget. Safe to call with a null context (no-op). */
        fun refresh(context: Context?) {
            context ?: return
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, TharaaWidget::class.java))
            if (ids.isEmpty()) return
            val views = render(context)
            ids.forEach { manager.updateAppWidget(it, views) }
        }

        private fun render(context: Context): RemoteViews {
            SavingsRepository.init(context) // idempotent; loads data if this is a fresh process
            val d = SavingsRepository.data.value
            val now = System.currentTimeMillis()
            val total = d.labels.sumOf { SavingsRepository.valueMinor(it.id, now, d) }
            val breakdown = d.labels.joinToString("  •  ") { label ->
                "${label.name} ${Money.formatMinor(SavingsRepository.valueMinor(label.id, now, d))}"
            }

            return RemoteViews(context.packageName, R.layout.widget_tharaa).apply {
                setTextViewText(R.id.widget_value, "${Money.formatMinor(total)} EGP")
                setTextViewText(R.id.widget_breakdown, breakdown)
                val open = PendingIntent.getActivity(
                    context, 0,
                    Intent(context, MainActivity::class.java),
                    PendingIntent.FLAG_IMMUTABLE
                )
                setOnClickPendingIntent(R.id.widget_root, open)
            }
        }
    }
}
