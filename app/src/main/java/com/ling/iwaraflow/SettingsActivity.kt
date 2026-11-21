package com.ling.iwaraflow

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

class SettingsActivity : AppCompatActivity() {
    private lateinit var updates: UpdateManager
    private var interestsChanged = false
    private val interests = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) {
            interestsChanged = true
            setResult(RESULT_OK, Intent().putExtra("interests_changed", true))
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        interestsChanged = state?.getBoolean("interests_changed") == true
        if (interestsChanged) setResult(RESULT_OK, Intent().putExtra("interests_changed", true))
        updates = UpdateManager(this)
        SettingsDialogController(this, AppPrefs(this), { intent.getStringExtra("mode") ?: "recommend" }, {},
            { reload -> setResult(RESULT_OK, Intent().putExtra("reload", reload).putExtra("interests_changed", interestsChanged)); finish() },
            { startActivity(Intent(this, MainActivityV3::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP).putExtra("sync_likes", true)) },
            { interests.launch(Intent(this, InterestActivity::class.java)) },
            { NavigationDiagnostics.show(this) },
            { updates.check(manual = true) }).show()
    }

    override fun onStart() {
        super.onStart()
        updates.tryContinueInstall()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("interests_changed", interestsChanged)
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
