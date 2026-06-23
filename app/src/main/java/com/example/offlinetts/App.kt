package com.example.offlinetts

import android.app.Application

/**
 * Custom Application so the crash handler is installed before ANY Activity or
 * Service is created. This guarantees even the earliest startup crash is logged.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashReporter.install(this)
    }
}
