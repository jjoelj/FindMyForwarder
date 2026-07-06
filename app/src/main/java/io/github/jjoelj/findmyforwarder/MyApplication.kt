package io.github.jjoelj.findmyforwarder

import android.app.Application

class MyApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        FileLogger.init(this)
    }
}