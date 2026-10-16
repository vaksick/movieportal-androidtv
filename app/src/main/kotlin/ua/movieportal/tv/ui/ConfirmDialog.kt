package ua.movieportal.tv.ui

import android.app.AlertDialog
import android.content.Context
import android.view.LayoutInflater
import android.widget.Button
import android.widget.TextView
import ua.movieportal.tv.R

/**
 * "Are you sure?" dialog in the app's look. The safe choice ([cancel]) is on the right and focused, so an accidental
 * OK never confirms.
 */
object ConfirmDialog {
	fun create(context: Context, message: Int, confirm: Int, cancel: Int, onConfirm: () -> Unit): AlertDialog {
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
		val cancelButton = view.findViewById<Button>(R.id.confirm_negative).apply {
			setText(cancel)
			setOnClickListener { dialog.cancel() }
		}
		dialog.setOnShowListener { cancelButton.requestFocus() }
		return dialog
	}
}
