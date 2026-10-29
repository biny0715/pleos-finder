package com.EOKYEONGBIN.pleosfinder

import android.app.Application

class PleosFinderApp : Application() {

    lateinit var session: VehicleSession
        private set

    override fun onCreate() {
        super.onCreate()
        session = VehicleSession(this)
    }
}
