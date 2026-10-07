package com.ling.iwaraflow

import android.app.Application
import android.net.Uri
import androidx.lifecycle.MutableLiveData
import java.util.concurrent.Executors
import org.json.JSONObject

data class UploadUiState(val phase: String = "idle", val message: String = "选择视频并填写投稿信息", val percent: Int = 0,
    val busy: Boolean = false, val videoId: String? = null, val uncertain: Boolean = false)

class UploadCoordinator private constructor(private val app: Application) {
    private val secrets = SecureSessionStore(app)
    private var checkpoint = runCatching { JSONObject(secrets.secret(TASK_KEY) ?: "{}") }.getOrDefault(JSONObject())
    var draft: VideoUploadDraft? = readDraft(checkpoint.optJSONObject("draft"))
        private set
    private var owner = checkpoint.optString("owner")
    @Volatile private var completedVideoId = checkpoint.optString("videoId").takeUnless { it.isBlank() || it == "null" }
    private fun account(): String = secrets.refreshToken?.let { token ->
        java.security.MessageDigest.getInstance("SHA-256").digest(token.toByteArray()).joinToString("") { "%02x".format(it) }
    }.orEmpty()
    private fun validAccount() = owner.isNotBlank() && account() == owner
    private fun persist(phase: String) {
        checkpoint = JSONObject().put("owner", owner).put("uri", uri?.toString()).put("filename", filename)
            .put("size", size).put("phase", phase).put("uncertain", publicationUncertain)
            .put("videoId", completedVideoId)
        ticket?.let { checkpoint.put("ticket", JSONObject().put("id", it.id).put("key", it.key)) }
        processedFile?.let { checkpoint.put("file", it) }
        draft?.let { checkpoint.put("draft", writeDraft(it)) }
        check(secrets.putSecretDurable(TASK_KEY, checkpoint.toString())) { "无法安全保存上传进度，请检查设备存储后重试" }
    }
    fun saveDraft(value: VideoUploadDraft) {
        if (busy) return
        draft = value
        if (owner.isBlank()) owner = account()
        runCatching { persist(state.value?.phase ?: "idle") }
    }

    fun recentTasks(): List<JSONObject> = runCatching {
        val rows = org.json.JSONArray(secrets.secret("upload_completed_tasks") ?: "[]")
        (0 until rows.length()).map(rows::getJSONObject).filter { it.optString("owner") == account() }
    }.getOrDefault(emptyList())
    private fun rememberCompleted(id: String) {
        val old = runCatching { org.json.JSONArray(secrets.secret("upload_completed_tasks") ?: "[]") }.getOrDefault(org.json.JSONArray())
        val rows = org.json.JSONArray().put(JSONObject().put("owner", owner).put("id", id)
            .put("title", draft?.title.orEmpty()).put("time", System.currentTimeMillis()))
        for (i in 0 until minOf(old.length(), 19)) rows.put(old.getJSONObject(i))
        secrets.putSecretDurable("upload_completed_tasks", rows.toString())
    }
    private val api = IwaraApi(app)
    private val executor = Executors.newSingleThreadExecutor()
    private var client = newClient()
    private fun newClient(): IwaraUploadClient {
        val revision = SecureSessionStore.accountRevision
        val expectedOwner = owner
        val allowed = validAccount()
        fun valid() = allowed && revision == SecureSessionStore.accountRevision && owner == expectedOwner
        return IwaraUploadClient({ check(valid()) { "上传账号已改变，请切回原账号" }; api.uploadAccessToken() },
            authExpired = { if (valid()) api.uploadAuthExpired() }, sessionValid = ::valid)
    }
    val state = MutableLiveData(UploadUiState(
        phase = if (checkpoint.length() > 0) "paused" else "idle",
        message = if (checkpoint.length() > 0) "已恢复上传草稿，点击继续" else "选择视频并填写投稿信息",
        videoId = checkpoint.optString("videoId").takeUnless { it.isBlank() || it == "null" },
        uncertain = checkpoint.optBoolean("uncertain") || checkpoint.optString("phase") == "publishing"))
    var uri: Uri? = checkpoint.optString("uri").takeUnless { it.isBlank() || it == "null" }?.let(Uri::parse)
        private set
    var filename = checkpoint.optString("filename")
        private set
    var size = checkpoint.optLong("size", -1L)
        private set
    @Volatile var busy = false
        private set
    @Volatile private var cancelled = false
    private var ticket: UploadTicket? = checkpoint.optJSONObject("ticket")?.let { UploadTicket(it.getString("id"), it.getString("key")) }
    private var processedFile: JSONObject? = checkpoint.optJSONObject("file")
    var publicationUncertain = state.value?.uncertain == true
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
        ticket = null; processedFile = null; completedVideoId = null; publicationUncertain = false; owner = account()
        state.value = UploadUiState(message = "已选择 $name")
        runCatching { persist("idle") }
    }

    fun submit(draft: VideoUploadDraft) {
        if (busy || running.get() || completedVideoId != null || publicationUncertain) return
        if (uri == null) return
        draft.validate()
        check(validAccount()) { "上传账号已改变，请切回原账号，或重新选择文件创建新任务" }
        this.draft = draft
        persist(if (processedFile != null) "ready" else if (ticket != null) "processing" else "idle")
        busy = true; cancelled = false
        state.value = UploadUiState("queued", "正在启动后台上传…", busy = true)
        try { androidx.core.content.ContextCompat.startForegroundService(app, android.content.Intent(app, UploadService::class.java)) }
        catch (e: Exception) { busy = false; state.value = UploadUiState("error", "无法启动后台上传，请保持应用前台并重试"); throw e }
    }

    private val running = java.util.concurrent.atomic.AtomicBoolean(false)
    internal fun runTask() {
        if (!busy || !running.compareAndSet(false, true)) return
        val selected = uri ?: run { running.set(false); busy = false; return }
        val draft = draft ?: run { running.set(false); busy = false; return }
        client = newClient()
        executor.execute {
            try {
                val uploadClient = client
                if (ticket == null && processedFile == null) {
                    ticket = uploadClient.upload(filename, size, {
                        app.contentResolver.openInputStream(selected)
                            ?: throw java.io.IOException("无法读取视频，请重新选择文件")
                    }) { percent -> if (!cancelled) state.postValue(UploadUiState("uploading", "正在上传文件…", percent, true)) }
                }
                persist("processing")
                val deadline = System.currentTimeMillis() + 30 * 60_000L
                while (processedFile == null) {
                    if (cancelled || Thread.currentThread().isInterrupted) return@execute
                    val progress = uploadClient.processing(ticket!!)
                    if (progress.state == "completed") { processedFile = progress.file; break }
                    if (!cancelled) state.postValue(UploadUiState("processing", "官网正在处理视频…", progress.progress, true))
                    if (System.currentTimeMillis() >= deadline) throw java.io.IOException("处理时间较长，稍后点击重试可继续查询，无需重新上传")
                    Thread.sleep(2000)
                }
                if (cancelled) return@execute
                check(validAccount()) { "上传账号已改变，任务已暂停" }
                persist("publishing")
                state.postValue(UploadUiState("publishing", "正在发布…", 100, true))
                val id = uploadClient.publish(draft, processedFile!!)
                completedVideoId = id
                publicationUncertain = false
                checkpoint.put("videoId", id).put("phase", "complete").put("uncertain", false)
                secrets.putSecretDurable(TASK_KEY, checkpoint.toString())
                runCatching { rememberCompleted(id) }
                busy = false
                state.postValue(UploadUiState("complete", "投稿已提交，官网可能仍需转码或审核", 100, videoId = id))
            } catch (e: Exception) {
                if (!cancelled) {
                    publicationUncertain = e is PublicationUncertainException
                    runCatching { persist(if (publicationUncertain) "publishing" else "paused") }
                    busy = false
                    state.postValue(UploadUiState("error", e.message ?: "上传失败，请重试", uncertain = publicationUncertain))
                }
            } finally {
                client.close(); running.set(false); busy = false
                if (cancelled && completedVideoId == null) state.postValue(UploadUiState("paused", "任务已暂停，可返回上传页继续", uncertain = publicationUncertain))
            }
        }
    }

    fun pause() {
        if (!busy || cancelled) return
        cancelled = true
        if (checkpoint.optString("phase") == "publishing") publicationUncertain = true
        client.close()
        runCatching { persist(if (publicationUncertain) "publishing" else "paused") }
        state.postValue(UploadUiState("paused", "任务已暂停，可返回上传页继续", uncertain = publicationUncertain))
    }

    companion object {
        private const val TASK_KEY = "native_upload_task_v2"
        @Volatile private var instance: UploadCoordinator? = null
        fun get(app: Application): UploadCoordinator = synchronized(this) {
            instance ?: UploadCoordinator(app).also { instance = it }
        }
        internal fun writeDraft(d: VideoUploadDraft) = JSONObject().put("title", d.title).put("body", d.body)
            .put("tags", org.json.JSONArray(d.tags)).put("rating", d.rating).put("private", d.privateVideo)
            .put("unlisted", d.unlisted).put("rules", d.rulesAgreement)
        internal fun readDraft(o: JSONObject?): VideoUploadDraft? = o?.let {
            val tags = it.optJSONArray("tags") ?: org.json.JSONArray()
            VideoUploadDraft(it.optString("title"), it.optString("body"), (0 until tags.length()).map(tags::getString),
                it.optString("rating", "general"), it.optBoolean("private"), it.optBoolean("unlisted"), it.optBoolean("rules"))
        }
    }
}
