package ua.movieportal.tv.player

import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.view.updateLayoutParams
import ua.movieportal.tv.R

/**
 * Audio and subtitle choice in two columns (like the portal's web player).
 *
 * ↑/↓ move within a column, ←/→ between the columns, OK selects; the panel stays open so both can be changed,
 * Back closes it.
 */
class TrackPanel(root: View) {
	/** A selectable track: Jellyfin stream index (-1: subtitles off) and its label. */
	data class Row(val index: Int, val label: String)

	private val container = root.findViewById<View>(R.id.player_tracks)
	private val audioScroll = root.findViewById<ScrollView>(R.id.player_tracks_audio_scroll)
	private val subtitleScroll = root.findViewById<ScrollView>(R.id.player_tracks_subtitles_scroll)
	private val audioColumn = root.findViewById<LinearLayout>(R.id.player_tracks_audio)
	private val subtitleColumn = root.findViewById<LinearLayout>(R.id.player_tracks_subtitles)
	private val context = root.context

	val isVisible get() = container.isVisible

	/**
	 * @param bottomPx distance from the bottom of the screen (the panel sits above the control panel's content)
	 * @param maxHeightPx height available for the panel; long lists scroll
	 */
	fun show(
		audio: List<Row>,
		selectedAudio: Int?,
		subtitles: List<Row>,
		selectedSubtitle: Int?,
		bottomPx: Int,
		maxHeightPx: Int,
		onAudio: (Int) -> Unit,
		onSubtitle: (Int) -> Unit,
	) {
		fill(audioColumn, audio, selectedAudio, onAudio)
		fill(subtitleColumn, subtitles, selectedSubtitle, onSubtitle)
		// A column without tracks is not shown; a lone column needs no gap before it
		audioScroll.column.isVisible = audio.isNotEmpty()
		subtitleScroll.column.isVisible = subtitles.isNotEmpty()
		subtitleScroll.column.updateLayoutParams<ViewGroup.MarginLayoutParams> {
			marginStart = if (audio.isNotEmpty()) dp(COLUMN_GAP_DP) else 0
		}
		container.updateLayoutParams<ViewGroup.MarginLayoutParams> { bottomMargin = bottomPx }
		limitHeight(maxHeightPx)
		container.isVisible = true

		// Start on the current audio track (or the first column that has anything)
		val column = if (audioColumn.childCount > 0) audioColumn else subtitleColumn
		column.selectedRow()?.requestFocus() ?: column.getChildAt(0)?.requestFocus()
	}

	/** Moves the check mark after a selection without rebuilding the rows (focus stays where it is). */
	fun updateSelection(selectedAudio: Int?, selectedSubtitle: Int?) {
		mark(audioColumn, selectedAudio)
		mark(subtitleColumn, selectedSubtitle)
	}

	fun hide() {
		container.clearFocus()
		container.isVisible = false
	}

	private fun fill(column: LinearLayout, rows: List<Row>, selected: Int?, onSelect: (Int) -> Unit) {
		column.removeAllViews()
		rows.forEach { row ->
			column.addView(createRow(row).apply {
				id = View.generateViewId()
				tag = row.index
				setOnClickListener { onSelect(row.index) }
			})
		}
		// Keep the focus inside the panel at the ends of a column
		column.getChildAt(0)?.let { it.nextFocusUpId = it.id }
		column.getChildAt(column.childCount - 1)?.let { it.nextFocusDownId = it.id }
		mark(column, selected)
	}

	private fun createRow(row: Row) = TextView(context).apply {
		text = row.label
		setTextColor(ContextCompat.getColor(context, R.color.text_primary))
		setTextSize(TypedValue.COMPLEX_UNIT_SP, ROW_TEXT_SP)
		maxLines = 2
		isFocusable = true
		isClickable = true
		setBackgroundResource(R.drawable.bg_track_row)
		val horizontal = dp(12)
		val vertical = dp(10)
		setPadding(horizontal, vertical, horizontal, vertical)
		compoundDrawablePadding = dp(12)
		layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
			.apply { bottomMargin = dp(4) }
	}

	private fun mark(column: LinearLayout, selected: Int?) {
		column.forEachRow { row ->
			val isSelected = row.tag == selected
			row.isSelected = isSelected
			// The check keeps its space on every row so the labels line up
			val check = ContextCompat.getDrawable(context, R.drawable.ic_check)?.mutate()?.apply {
				alpha = if (isSelected) OPAQUE else 0
			}
			(row as TextView).setCompoundDrawablesRelativeWithIntrinsicBounds(check, null, null, null)
		}
	}

	private fun limitHeight(maxHeightPx: Int) {
		listOf(audioScroll, subtitleScroll).forEach { it.updateLayoutParams { height = ViewGroup.LayoutParams.WRAP_CONTENT } }
		container.measure(
			View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
			View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
		)
		val overflow = container.measuredHeight - maxHeightPx
		if (overflow <= 0) return
		listOf(audioScroll, subtitleScroll).forEach { scroll ->
			val limit = (scroll.measuredHeight - overflow).coerceAtLeast(dp(MIN_LIST_DP))
			if (scroll.measuredHeight > limit) scroll.updateLayoutParams { height = limit }
		}
	}

	/** The column (title + list) that holds this list. */
	private val ScrollView.column get() = parent as View

	private fun LinearLayout.selectedRow(): View? = (0 until childCount).map(::getChildAt).firstOrNull { it.isSelected }

	private inline fun LinearLayout.forEachRow(action: (View) -> Unit) {
		for (i in 0 until childCount) action(getChildAt(i))
	}

	private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

	private companion object {
		const val ROW_TEXT_SP = 17f
		const val OPAQUE = 255
		const val MIN_LIST_DP = 96
		const val COLUMN_GAP_DP = 24
	}
}
