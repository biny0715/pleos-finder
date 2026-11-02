package com.EOKYEONGBIN.pleosfinder.phone

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.EOKYEONGBIN.pleosfinder.core.DestinationShare

/** 폰에서 받은 그룹 목적지로 길 안내: 폰의 지도 앱(없으면 웹 지도)으로 연다. */
object Directions {

    fun open(context: Context, share: DestinationShare) {
        val label = Uri.encode(share.name)
        val candidates = listOf(
            Uri.parse("google.navigation:q=${share.latitude},${share.longitude}"),
            Uri.parse("geo:${share.latitude},${share.longitude}?q=${share.latitude},${share.longitude}($label)"),
            Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${share.latitude},${share.longitude}"),
        )
        for (uri in candidates) {
            try {
                context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return
            } catch (_: ActivityNotFoundException) {
                // 다음 방식으로 연다.
            }
        }
        Toast.makeText(context, "길 안내를 열 지도 앱이 없어요.", Toast.LENGTH_LONG).show()
    }

    /** 같은 공유의 알림을 수락·거절하면 지운다. */
    fun notificationId(share: DestinationShare) = share.id.hashCode()
}
