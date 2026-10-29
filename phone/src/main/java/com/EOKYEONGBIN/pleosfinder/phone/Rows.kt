package com.EOKYEONGBIN.pleosfinder.phone

import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import androidx.annotation.ColorRes
import androidx.core.content.ContextCompat

/** item_row 한 줄을 만들고 채운다. 그룹 목록과 그룹 구성원 목록이 함께 쓴다. */
class Row(val view: View) {
    private val avatar: TextView = view.findViewById(R.id.avatar)
    private val name: TextView = view.findViewById(R.id.name)
    private val status: TextView = view.findViewById(R.id.status)
    private val dot: View = view.findViewById(R.id.dot)
    val actions: LinearLayout = view.findViewById(R.id.actions)

    fun bind(title: String, avatarText: String, showPerson: Boolean, showCar: Boolean, statusText: String, @ColorRes dotColor: Int, chevron: Boolean = false): Row {
        name.text = title
        avatar.text = avatarText
        view.findViewById<View>(R.id.personIcon).visibility = if (showPerson) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.carIcon).visibility = if (showCar) View.VISIBLE else View.GONE
        view.findViewById<View>(R.id.chevron).visibility = if (chevron) View.VISIBLE else View.GONE
        status.text = statusText
        dot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(view.context, dotColor))
        return this
    }

    /** 아래에 버튼을 붙인다. filled = 주요 동작 */
    fun addAction(label: String, filled: Boolean, onClick: () -> Unit) {
        val style = if (filled) R.layout.item_action_filled else R.layout.item_action_basic
        val button = LayoutInflater.from(view.context).inflate(style, actions, false) as Button
        button.text = label
        button.setOnClickListener { onClick() }
        if (actions.childCount > 0) (button.layoutParams as ViewGroup.MarginLayoutParams).marginStart = (8 * view.resources.displayMetrics.density).toInt()
        actions.addView(button)
        actions.visibility = View.VISIBLE
    }

    companion object {
        fun add(parent: LinearLayout): Row {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_row, parent, false)
            parent.addView(view)
            return Row(view)
        }
    }
}
