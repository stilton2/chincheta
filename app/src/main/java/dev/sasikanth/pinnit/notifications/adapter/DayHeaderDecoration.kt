package dev.sasikanth.pinnit.notifications.adapter

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.text.format.DateUtils
import android.util.TypedValue
import android.view.View
import androidx.recyclerview.widget.RecyclerView
import dev.sasikanth.pinnit.R
import dev.sasikanth.pinnit.data.PinnitNotification
import dev.sasikanth.pinnit.utils.resolveColor
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/**
 * Draws section headers above the list rows: "Pinned" for pinned notes, then one per day
 * ("Today", "Yesterday", "Monday 10 November"...). Done as a decoration so the adapter,
 * swipe-to-delete and pin animations keep working on plain positions.
 */
class DayHeaderDecoration(
  context: Context,
  private val itemAt: (Int) -> PinnitNotification?
) : RecyclerView.ItemDecoration() {

  private val appContext = context.applicationContext
  private val headerHeight = dp(context, 40f).toInt()
  private val textStart = dp(context, 24f)

  private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
    color = context.resolveColor(attrRes = R.attr.colorSecondary)
    textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, context.resources.displayMetrics)
    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
    letterSpacing = 0.08f
  }
  private val backgroundPaint = Paint().apply {
    color = context.resolveColor(attrRes = android.R.attr.colorBackground)
  }

  fun labelAt(position: Int): String? {
    val item = itemAt(position) ?: return null
    if (item.isPinned) return appContext.getString(R.string.section_pinned)

    val zone = ZoneId.systemDefault()
    val day = item.updatedAt.atZone(zone).toLocalDate()
    val today = LocalDate.now(zone)
    return when (day) {
      today -> appContext.getString(R.string.section_today)
      today.minusDays(1) -> appContext.getString(R.string.section_yesterday)
      else -> {
        val flags = DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_DATE or
          (if (day.year == today.year) DateUtils.FORMAT_NO_YEAR else DateUtils.FORMAT_SHOW_YEAR)
        DateUtils.formatDateTime(appContext, day.atStartOfDay(zone).toInstant().toEpochMilli(), flags)
      }
    }.uppercase(Locale.getDefault())
  }

  private fun hasHeader(position: Int): Boolean {
    if (position == RecyclerView.NO_POSITION) return false
    val label = labelAt(position) ?: return false
    return position == 0 || labelAt(position - 1) != label
  }

  override fun getItemOffsets(outRect: Rect, view: View, parent: RecyclerView, state: RecyclerView.State) {
    val position = parent.getChildAdapterPosition(view)
    if (hasHeader(position)) outRect.top = headerHeight
  }

  override fun onDraw(canvas: Canvas, parent: RecyclerView, state: RecyclerView.State) {
    for (i in 0 until parent.childCount) {
      val child = parent.getChildAt(i)
      val position = parent.getChildAdapterPosition(child)
      if (!hasHeader(position)) continue
      val label = labelAt(position) ?: continue

      val bottom = child.top + child.translationY
      val top = bottom - headerHeight
      canvas.drawRect(parent.paddingLeft.toFloat(), top, (parent.width - parent.paddingRight).toFloat(), bottom, backgroundPaint)
      val baseline = top + headerHeight / 2f - (textPaint.descent() + textPaint.ascent()) / 2f
      canvas.drawText(label, parent.paddingLeft + textStart, baseline, textPaint)
    }
  }

  private fun dp(context: Context, value: Float) =
    TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value, context.resources.displayMetrics)
}
