package com.ling.iwaraflow

import android.app.Application

class IwaraFlowApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        MediaCodecWarmup.start()
        NavigationDiagnostics.install(this)
    }
}
