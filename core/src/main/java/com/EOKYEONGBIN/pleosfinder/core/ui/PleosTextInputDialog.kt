package com.EOKYEONGBIN.pleosfinder.core.ui

import android.content.Context
import android.text.Editable
import android.text.InputFilter
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.EOKYEONGBIN.pleosfinder.core.R

/**
 * 이름 한 줄을 입력받는 Pleos 대화상자(그룹 만들기, 그룹 이름 변경).
 * 비어 있으면 확인 버튼을 끄고, 키보드의 완료로도 확인할 수 있다.
 */
object PleosTextInputDialog {

    const val MAX_LENGTH = 20

    fun show(
        context: Context,
        title: String,
        label: String,
        hint: String,
        initial: String,
        confirmLabel: String,
        onConfirm: (String) -> Unit,
    ) {
        val view = LayoutInflater.from(context).inflate(R.layout.pleos_dialog_text_input, null)
        val input = view.findViewById<EditText>(R.id.inputText)
        val count = view.findViewById<TextView>(R.id.inputCount)
        view.findViewById<TextView>(R.id.inputLabel).text = label
        input.hint = hint
        input.filters = arrayOf(InputFilter.LengthFilter(MAX_LENGTH))
        input.setText(initial)
        input.setSelection(input.text.length)

        val dialog = AlertDialog.Builder(context)
            .setTitle(title)
            .setView(view)
            .setPositiveButton(confirmLabel) { _, _ -> onConfirm(input.text.toString().trim()) }
            .setNegativeButton("취소", null)
            .create()

        fun update() {
            count.text = "${input.text.length}/$MAX_LENGTH"
            dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.isEnabled = input.text.isNotBlank()
        }
        input.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = update()
        })
        input.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE && input.text.isNotBlank()) {
                dialog.dismiss()
                onConfirm(input.text.toString().trim())
                true
            } else false
        }
        dialog.setOnShowListener {
            update()
            input.requestFocus()
        }
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
    }
}
