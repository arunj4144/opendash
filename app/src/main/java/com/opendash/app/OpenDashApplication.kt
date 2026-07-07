package com.opendash.app

import android.app.Application
import com.opendash.app.logging.AppLogger

class OpenDashApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppLogger.init(this)
    }
}
