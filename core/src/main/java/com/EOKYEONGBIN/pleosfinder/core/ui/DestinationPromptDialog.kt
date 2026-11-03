package com.EOKYEONGBIN.pleosfinder.core.ui

import android.content.Context
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import com.EOKYEONGBIN.pleosfinder.core.DestinationShare
import com.EOKYEONGBIN.pleosfinder.core.R
import com.EOKYEONGBIN.pleosfinder.core.iGa

/** 같은 그룹 구성원이 공유한 목적지로 갈지 묻는 Pleos 대화상자(차량: 안내 시작, 폰: 길 안내). */
object DestinationPromptDialog {

    fun show(
        context: Context,
        fromName: String,
        groupName: String,
        share: DestinationShare,
        acceptLabel: String,
        question: String,
        onAccept: () -> Unit,
        onDecline: () -> Unit,
    ) {
        val view = LayoutInflater.from(context).inflate(R.layout.pleos_dialog_destination, null)
        view.findViewById<TextView>(R.id.destinationFrom).text = "${fromName.iGa} '$groupName' 그룹에 목적지를 공유했어요."
        view.findViewById<TextView>(R.id.destinationName).text = share.name
        view.findViewById<TextView>(R.id.destinationAddress).apply {
            text = share.address
            visibility = if (share.address.isBlank()) View.GONE else View.VISIBLE
        }
        view.findViewById<TextView>(R.id.destinationQuestion).text = question
        AlertDialog.Builder(context)
            .setTitle("목적지 공유")
            .setView(view)
            .setPositiveButton(acceptLabel) { _, _ -> onAccept() }
            .setNegativeButton("거절") { _, _ -> onDecline() }
            .show()
    }
}
