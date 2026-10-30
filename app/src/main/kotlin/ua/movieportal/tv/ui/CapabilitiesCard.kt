package ua.movieportal.tv.ui

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.View
import android.widget.TableLayout
import android.widget.TableRow
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.text.inSpans
import androidx.core.view.isVisible
import ua.movieportal.tv.R
import ua.movieportal.tv.profile.CapabilityGroup

/**
 * The "what this device can play" card on the status screen: one table row per group, a supported format in white
 * with a green check, an unsupported one dimmed with a cross.
 */
class CapabilitiesCard(private val card: View, private val table: TableLayout) {
	private val context = card.context
	private val ok = ContextCompat.getColor(context, R.color.ok)
	private val primary = ContextCompat.getColor(context, R.color.text_primary)
	private val secondary = ContextCompat.getColor(context, R.color.text_secondary)

	fun show(groups: List<CapabilityGroup>) {
		card.isVisible = context.resources.getBoolean(R.bool.show_capabilities) && groups.isNotEmpty()
		table.removeAllViews()
		// The formats column wraps within the card instead of widening it
		table.setColumnShrinkable(1, true)
		groups.forEach { group ->
			table.addView(TableRow(context).apply {
				addView(cell(group.label).apply {
					setTextColor(secondary)
					setPadding(0, rowGap, dp(LABEL_GAP_DP), rowGap)
				})
				addView(cell(formats(group)).apply { setPadding(0, rowGap, 0, rowGap) })
			})
		}
	}

	private fun formats(group: CapabilityGroup) = SpannableStringBuilder().apply {
		// A lone value (screen size) needs no mark
		val marked = group.items.size > 1
		group.items.forEachIndexed { i, (name, supported) ->
			if (i > 0) append("  ")
			// Non-breaking spaces keep the mark and the name on one line
			if (marked) inSpans(ForegroundColorSpan(if (supported) ok else secondary)) { append(if (supported) "✓\u00A0" else "✗\u00A0") }
			inSpans(ForegroundColorSpan(if (supported) primary else secondary)) { append(name.replace(' ', '\u00A0')) }
		}
	}

	private fun cell(text: CharSequence) = TextView(context).apply {
		this.text = text
		setTextSize(TypedValue.COMPLEX_UNIT_SP, TEXT_SP)
		typeface = Typeface.DEFAULT
	}

	private val rowGap = dp(2)

	private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

	private companion object {
		const val TEXT_SP = 12f
		const val LABEL_GAP_DP = 12
	}
}
