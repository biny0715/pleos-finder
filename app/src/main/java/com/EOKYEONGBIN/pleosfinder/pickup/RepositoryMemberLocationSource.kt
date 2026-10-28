package com.EOKYEONGBIN.pleosfinder.pickup

import com.EOKYEONGBIN.pleosfinder.core.FinderRepository
import com.EOKYEONGBIN.pleosfinder.core.Member
import com.EOKYEONGBIN.pleosfinder.core.MemberLocation
import com.EOKYEONGBIN.pleosfinder.core.Subscription

/** 서버에 올라오는 구성원 폰 위치를 픽업 모드에 전달한다. */
class RepositoryMemberLocationSource(private val repository: FinderRepository) : MemberLocationSource {

    private var subscription: Subscription? = null

    override fun start(member: Member, onUpdate: (MemberLocation) -> Unit) {
        stop()
        subscription = repository.observeMemberLocation(member.id, onUpdate)
    }

    override fun stop() {
        subscription?.cancel()
        subscription = null
    }
}
