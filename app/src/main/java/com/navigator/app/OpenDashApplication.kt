package com.navigator.app

import android.app.Application
import com.navigator.app.logging.AppLogger

class OpenDashApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLogger.init(this)
    }
}
