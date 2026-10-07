package com.ling.iwaraflow

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import java.util.concurrent.Executors
import org.json.JSONObject

data class UploadUiState(val phase: String = "idle", val message: String = "选择视频并填写投稿信息", val percent: Int = 0,
    val busy: Boolean = false, val videoId: String? = null, val uncertain: Boolean = false)

class UploadViewModel(app: Application) : AndroidViewModel(app) {
    private val api = IwaraApi(app)
    private val executor = Executors.newSingleThreadExecutor()
    private var client = newClient()
    private fun newClient() = IwaraUploadClient({ api.uploadAccessToken() }, authExpired = { api.uploadAuthExpired() })
    val state = MutableLiveData(UploadUiState())
    var uri: Uri? = null
        private set
    var filename = ""
        private set
    var size = -1L
        private set
    @Volatile var busy = false
        private set
    @Volatile private var cancelled = false
    private var ticket: UploadTicket? = null
    private var processedFile: JSONObject? = null
    var publicationUncertain = false
    private val rulesPrefs = app.getSharedPreferences("upload_rules_cache", 0)
    val rules = MutableLiveData(runCatching {
        IwaraRule.parse(JSONObject(rulesPrefs.getString("rules", "{}") ?: "{}"))
    }.getOrDefault(emptyList()))
    val rulesStatus = MutableLiveData(if (rules.value.isNullOrEmpty()) "正在读取官网规则…" else "已保存的官网规则 · 正在更新…")
    init { loadRules() }
    fun loadRules() {
        rulesStatus.postValue(if (rules.value.isNullOrEmpty()) "正在读取官网规则…" else "已保存的官网规则 · 正在更新…")
        api.getUploadRules { result ->
            result.onSuccess { response ->
                val items = IwaraRule.parse(response)
                if (items.isEmpty()) {
                    rulesStatus.postValue("官网规则暂不可用 · 点击重试")
                } else {
                    rulesPrefs.edit().putString("rules", response.toString()).apply()
                    rules.postValue(items)
                    rulesStatus.postValue("官网规则 · 共 ${items.size} 条，点击标题展开")
                }
            }.onFailure {
                rulesStatus.postValue(if (rules.value.isNullOrEmpty()) "官网规则读取失败 · 点击重试，或查看下方官网原文"
                    else "显示上次读取的规则 · 更新失败，点击重试")
            }
        }
    }
    fun loggedIn() = api.isLoggedIn()

    fun select(value: Uri, name: String, bytes: Long) {
        check(!busy)
        uri = value; filename = name; size = bytes
        ticket = null; processedFile = null
        state.value = UploadUiState(message = "已选择 $name")
    }

    fun submit(draft: VideoUploadDraft) {
        if (busy || state.value?.videoId != null || publicationUncertain) return
        val selected = uri ?: return
        draft.validate()
        busy = true; cancelled = false
        state.value = UploadUiState("uploading", "正在准备上传…", busy = true)
        executor.execute {
            try {
                val uploadClient = client
                if (ticket == null && processedFile == null) {
                    ticket = uploadClient.upload(filename, size, {
                        getApplication<Application>().contentResolver.openInputStream(selected)
                            ?: throw java.io.IOException("无法读取视频，请重新选择文件")
                    }) { percent -> state.postValue(UploadUiState("uploading", "正在上传文件…", percent, true)) }
                }
                val deadline = System.currentTimeMillis() + 30 * 60_000L
                while (processedFile == null) {
                    if (cancelled || Thread.currentThread().isInterrupted) return@execute
                    val progress = uploadClient.processing(ticket!!)
                    if (progress.state == "completed") { processedFile = progress.file; break }
                    state.postValue(UploadUiState("processing", "官网正在处理视频…", progress.progress, true))
                    if (System.currentTimeMillis() >= deadline) throw java.io.IOException("处理时间较长，稍后点击重试可继续查询，无需重新上传")
                    Thread.sleep(2000)
                }
                if (cancelled) return@execute
                state.postValue(UploadUiState("publishing", "正在发布…", 100, true))
                val id = uploadClient.publish(draft, processedFile!!)
                state.postValue(UploadUiState("complete", "投稿已提交，官网可能仍需转码或审核", 100, videoId = id))
            } catch (e: Exception) {
                if (!cancelled) {
                    publicationUncertain = e is PublicationUncertainException
                    state.postValue(UploadUiState("error", e.message ?: "上传失败，请重试", uncertain = publicationUncertain))
                }
            } finally { busy = false }
        }
    }

    override fun onCleared() {
        cancelled = true
        client.close()
        api.close()
        executor.shutdownNow()
    }
}
