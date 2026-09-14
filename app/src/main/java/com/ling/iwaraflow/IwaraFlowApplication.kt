package com.ling.iwaraflow

import android.app.Application

class IwaraFlowApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        NavigationDiagnostics.install(this)
        // 代理要在第一个网络请求之前就位，所以放在这里而不是等到首页。
        val prefs = AppPrefs(this)
        NetworkProxy.apply(prefs)
        Translator.onFailure = { NavigationDiagnostics.note(this, it) }
        // 翻译配置里有密钥，读它要走加密存储（Keystore 建主密钥有几十毫秒），
        // 放后台线程，别卡在启动路径上。翻译只有打开面板时才会用到，来得及。
        Thread({ runCatching { Translator.configure(prefs.translation) } }, "IwaraFlow-translation-config")
            .apply { isDaemon = true }.start()
    }
}
