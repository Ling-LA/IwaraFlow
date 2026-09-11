package com.ling.iwaraflow

import android.app.Application

class IwaraFlowApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        NavigationDiagnostics.install(this)
        // 代理要在第一个网络请求之前就位，所以放在这里而不是等到首页。
        NetworkProxy.apply(AppPrefs(this))
    }
}
