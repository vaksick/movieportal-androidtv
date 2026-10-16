package ua.movieportal.tv.ui

import android.app.AlertDialog
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.widget.Button
import android.widget.TextView
import ua.movieportal.tv.R

/**
 * "Are you sure?" dialog in the app's look. The safe choice ([cancel]) is on the right and focused, so an accidental
 * OK never confirms. With [autoCancelSeconds] the safe choice counts down on its button and is taken by itself when
 * nobody touches the remote (moving between the buttons restarts the countdown).
 */
object ConfirmDialog {
	fun create(
		context: Context,
		message: Int,
		confirm: Int,
		cancel: Int,
		autoCancelSeconds: Int? = null,
		onConfirm: () -> Unit,
	): AlertDialog {
		val view = LayoutInflater.from(context).inflate(R.layout.dialog_confirm, null)
		val dialog = AlertDialog.Builder(context, R.style.Theme_MoviePortal_Dialog).setView(view).create()
		view.findViewById<TextView>(R.id.confirm_message).setText(message)
		view.findViewById<Button>(R.id.confirm_positive).apply {
			setText(confirm)
			setOnClickListener {
				dialog.dismiss()
				onConfirm()
			}
		}
		val confirmButton = view.findViewById<Button>(R.id.confirm_positive)
		val cancelButton = view.findViewById<Button>(R.id.confirm_negative).apply {
			setText(cancel)
			setOnClickListener { dialog.cancel() }
		}
		dialog.setOnShowListener { cancelButton.requestFocusFromTouch() }

		if (autoCancelSeconds != null) {
			val cancelText = context.getString(cancel)
			val handler = Handler(Looper.getMainLooper())
			var left: Int = autoCancelSeconds
			val tick = object : Runnable {
				override fun run() {
					if (left <= 0) {
						dialog.cancel()
						return
					}
					cancelButton.text = context.getString(R.string.confirm_countdown, cancelText, left)
					left--
					handler.postDelayed(this, TICK_MS)
				}
			}
			val restart = {
				handler.removeCallbacks(tick)
				left = autoCancelSeconds
				tick.run()
			}
			listOf(confirmButton, cancelButton).forEach { button ->
				button.setOnFocusChangeListener { _, hasFocus -> if (hasFocus && dialog.isShowing) restart() }
			}
			// The dialog sizes itself to the first layout: measure it with the longest label, then keep that width so
			// the button does not shrink when the count gets shorter
			cancelButton.text = context.getString(R.string.confirm_countdown, cancelText, autoCancelSeconds)
			cancelButton.post { cancelButton.minWidth = cancelButton.width }
			dialog.setOnShowListener {
				cancelButton.requestFocusFromTouch()
				restart()
			}
			dialog.setOnDismissListener { handler.removeCallbacks(tick) }
		}
		return dialog
	}

	private const val TICK_MS = 1_000L
}
