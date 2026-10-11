package com.ling.iwaraflow

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {
    private lateinit var updates: UpdateManager

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        updates = UpdateManager(this)
        SettingsDialogController(this, AppPrefs(this), { intent.getStringExtra("mode") ?: "recommend" }, {},
            { reload -> setResult(RESULT_OK, Intent().putExtra("reload", reload)); finish() },
            { startActivity(Intent(this, MainActivityV3::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("sync_likes", true)) },
            { startActivity(Intent(this, InterestActivity::class.java)) },
            { NavigationDiagnostics.show(this) },
            { updates.check(manual = true) }).show()
    }

    override fun onStart() {
        super.onStart()
        updates.tryContinueInstall()
    }

    override fun onDestroy() {
        if (::updates.isInitialized) updates.close()
        super.onDestroy()
    }
}

class InterestActivity : AppCompatActivity() {
    private lateinit var history: HistoryStore
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        history = HistoryStore(this)
        InterestManager.show(this, history) { setResult(RESULT_OK) }
    }
    override fun onDestroy() { if (::history.isInitialized) history.close(); super.onDestroy() }
}
