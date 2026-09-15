package com.ling.iwaraflow

import android.app.Activity
import android.app.AlertDialog
import android.text.InputType
import android.view.View
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat

/**
 * 设置弹窗：推荐 / 播放 / 默认清晰度 / 网络 / 翻译 / 维护六段，加上自己摆的底部按钮栏。
 *
 * 从 [MainActivityV3] 里独立出来的一块：这三百行全是搭界面和存偏好，
 * 和页面的播放、导航、下载没有关系，混在同一个类里只会让那个类更难读。
 * 它只认几个回调：改了老片间隔要通知谁、保存之后要不要重拉列表、维护里那三个动作。
 */
class SettingsDialogController(
    private val activity: Activity,
    private val prefs: AppPrefs,
    /** 保存时读一次当前在哪个流：只有推荐流受“排除已看 / 老片穿插”影响。 */
    private val currentMode: () -> String,
    private val onClassicsChanged: (Int) -> Unit,
    /** 保存完了：[reload] 为 true 表示候选真的变了，要重拉列表，否则只刷新当前卡片。 */
    private val onSaved: (reload: Boolean) -> Unit,
    private val onSyncLikes: () -> Unit,
    private val onInterestManager: () -> Unit,
    private val onDiagnostics: () -> Unit
) {
    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    fun show() {
        val panel = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(24), dp(8), dp(24), dp(8)) }
        panel.addView(sectionTitle("推荐"))
        val skipSeen = CheckBox(activity).apply { text = "刷新推荐时排除已看视频"; isChecked = prefs.skipSeen }
        val skipSeenEverywhere = CheckBox(activity).apply {
            text = "「最新 / 流行 / 人气」也排除已看视频"; isChecked = prefs.skipSeenEverywhere
        }
        panel.addView(skipSeen)
        panel.addView(skipSeenEverywhere)
        panel.addView(TextView(activity).apply {
            text = "三个榜单页默认照着官方顺序看，刷过的还会出现。打开后它们和推荐流一个口径：看过的不再重复露面（整页都看过时仍然照常显示，不给空页）。"
            textSize = 12f; setTextColor(0xFF607D93.toInt()); setPadding(dp(4), dp(4), 0, dp(8))
        })
        panel.addView(TextView(activity).apply {
            text = "开启后只在生成新的推荐列表时过滤历史记录，不会在滑动过程中连续自动跳过。"; textSize = 12f
            setTextColor(0xFF607D93.toInt()); setPadding(dp(4), 0, 0, dp(8))
        })
        val classicsValues = intArrayOf(0, 8, 12, 16, 24)
        val classicsNames = arrayOf("不穿插老片", "每 8 条穿插一条老片", "每 12 条穿插一条老片", "每 16 条穿插一条老片", "每 24 条穿插一条老片")
        val classics = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, classicsNames)
            setSelection(classicsValues.indexOf(prefs.classicsEvery).takeIf { it >= 0 } ?: 2)
        }
        panel.addView(classics)
        panel.addView(TextView(activity).apply {
            text = "老片指点赞很高、发布超过一年的作品，随机插在每一组里的任意位置。避免短时间刷太多把新片刷没、后面越刷越旧，也避免一直碰不到历史上的高质量作品。"
            textSize = 12f; setTextColor(0xFF607D93.toInt()); setPadding(dp(4), dp(4), 0, dp(8))
        })
        val recommendDebug = CheckBox(activity).apply {
            text = "显示推荐调试信息"; isChecked = prefs.recommendDebug
        }
        panel.addView(recommendDebug)
        panel.addView(TextView(activity).apply {
            text = "打开后简介面板里会在「推荐理由」下面写清这条视频的分是怎么来的：来源 / 质量 / 新鲜度 / 画像 / 扰动各占多少、命中了哪些标签、来自哪一路召回。只用于调推荐算法，平时可以关着。"
            textSize = 12f; setTextColor(0xFF607D93.toInt()); setPadding(dp(4), dp(4), 0, dp(8))
        })
        panel.addView(sectionTitle("播放"))
        val autoNext = CheckBox(activity).apply { text = "播放完毕自动进入下一条"; isChecked = prefs.autoNext }
        val autoPip = CheckBox(activity).apply { text = "切到后台时自动进入画中画"; isChecked = prefs.autoPip }
        val pauseIcon = CheckBox(activity).apply {
            text = "暂停时显示播放三角"; isChecked = prefs.showPauseIndicator
        }
        val tapPause = CheckBox(activity).apply {
            text = "点一下画面暂停播放"; isChecked = prefs.tapToPause
        }
        panel.addView(autoNext); panel.addView(autoPip); panel.addView(pauseIcon); panel.addView(tapPause)
        panel.addView(TextView(activity).apply {
            text = "关掉后点一下画面不再暂停：只在「标题 / 标签 / 操作栏」和「进度条 / 快进后退 / 剩余时长」之间切换，视频照常播，要暂停就点控件里的暂停按钮。"
            textSize = 12f; setTextColor(0xFF607D93.toInt()); setPadding(dp(4), dp(4), 0, dp(8))
        })
        val skipValues = intArrayOf(5, 10, 15, 30, 60)
        val skipNames = skipValues.map { "前进 / 后退 $it 秒" }.toTypedArray()
        val skip = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, skipNames)
            setSelection(skipValues.indexOf(prefs.skipSeconds).takeIf { it >= 0 } ?: 2)
        }
        panel.addView(skip)
        panel.addView(TextView(activity).apply {
            text = "暂停时左下角的两个按钮一次跳多少秒。"
            textSize = 12f; setTextColor(0xFF607D93.toInt()); setPadding(dp(4), dp(4), 0, dp(8))
        })
        panel.addView(sectionTitle("默认清晰度"))
        val qualityValues = arrayOf("highest", "Source", "1080", "720", "540", "360")
        val qualityNames = arrayOf("最高可用 / 原画", "Source", "1080p", "720p", "540p", "360p")
        val spinner = Spinner(activity); spinner.adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, qualityNames)
        spinner.setSelection(qualityValues.indexOf(prefs.defaultQuality).let { if (it >= 0) it else 0 }); panel.addView(spinner)

        panel.addView(sectionTitle("网络"))
        panel.addView(TextView(activity).apply {
            text = "Iwara 在部分地区无法直连。代理工具只开了本地端口、没开 VPN 模式时，可以让本应用自己走那个端口。"
            textSize = 12f; setTextColor(0xFF607D93.toInt()); setPadding(dp(4), 0, 0, dp(6))
        })
        val proxyTypes = arrayOf(NetworkProxy.TYPE_NONE, NetworkProxy.TYPE_HTTP, NetworkProxy.TYPE_SOCKS)
        val proxyNames = arrayOf("不使用代理", "HTTP 代理", "SOCKS5 代理")
        val proxyType = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, proxyNames)
            setSelection(proxyTypes.indexOf(prefs.proxyType).coerceAtLeast(0))
        }
        val proxyHost = settingsInput("主机，例如 127.0.0.1", prefs.proxyHost, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val proxyPort = settingsInput("端口，例如 7890", prefs.proxyPort.takeIf { it > 0 }?.toString().orEmpty(), InputType.TYPE_CLASS_NUMBER)
        panel.addView(proxyType); panel.addView(proxyHost); panel.addView(proxyPort)

        panel.addView(sectionTitle("翻译"))
        panel.addView(TextView(activity).apply {
            text = "简介和评论自动翻成中文用哪家服务。默认的谷歌免费接口不用密钥；其它几家填上自己的密钥，也可以接 AI 中转站。"
            textSize = 12f; setTextColor(0xFF607D93.toInt()); setPadding(dp(4), 0, 0, dp(6))
        })
        val translation = prefs.translation
        val providerIds = Translator.PROVIDERS.map { it.first }
        val providerSpinner = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, Translator.PROVIDERS.map { it.second }.toTypedArray())
            setSelection(providerIds.indexOf(translation.provider).coerceAtLeast(0))
        }
        panel.addView(providerSpinner)
        val trKey = settingsInput("API Key / 密钥", translation.key, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD)
        val trRegion = settingsInput("区域（Azure，例如 eastasia，可留空）", translation.region, InputType.TYPE_CLASS_TEXT)
        val trAppId = settingsInput("APP ID", translation.appId, InputType.TYPE_CLASS_TEXT)
        val trEndpoint = settingsInput("服务器地址，例如 https://libretranslate.com", translation.endpoint, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val trCustomUrl = settingsInput("请求地址模板，{text} 是原文、{target} 是目标语言", translation.endpoint, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val methodValues = arrayOf("GET", "POST")
        val trMethod = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, arrayOf("GET 请求", "POST 请求"))
            setSelection(methodValues.indexOf(translation.customMethod.uppercase()).coerceAtLeast(0))
        }
        val trBody = settingsInput("POST 请求体模板，例如 {\"q\":\"{text}\",\"target\":\"{target}\"}", translation.customBody, InputType.TYPE_CLASS_TEXT, multiline = true)
        val trHeaders = settingsInput("请求头，一行一个：Authorization: Bearer xxx", translation.customHeaders, InputType.TYPE_CLASS_TEXT, multiline = true)
        val trResultPath = settingsInput("译文字段路径，例如 data.translatedText（留空自动猜）", translation.customResultPath, InputType.TYPE_CLASS_TEXT)
        val trLangPath = settingsInput("源语言字段路径（可留空）", translation.customLangPath, InputType.TYPE_CLASS_TEXT)
        // AI 翻译：内置服务商只填 Key；选“自定义”才出现地址和模型名。
        val vendorIds = Translator.AI_VENDORS.map { it.id }
        val trAiVendor = Spinner(activity).apply {
            adapter = ArrayAdapter(activity, android.R.layout.simple_spinner_dropdown_item, Translator.AI_VENDORS.map { it.name }.toTypedArray())
            setSelection(vendorIds.indexOf(translation.aiVendor).coerceAtLeast(0))
        }
        val trAiBase = settingsInput("接口地址，例如 https://api.openai.com/v1（中转站填它给的地址）", translation.endpoint, InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI)
        val trModel = settingsInput("模型名，例如 gpt-4o-mini、deepseek-chat、qwen-plus", translation.model, InputType.TYPE_CLASS_TEXT)
        val trHint = TextView(activity).apply {
            textSize = 12f; setTextColor(0xFF607D93.toInt()); setPadding(dp(4), dp(4), 0, dp(8))
        }
        val translationFields = listOf(trAiVendor, trAiBase, trKey, trModel, trRegion, trAppId, trEndpoint, trCustomUrl, trMethod, trBody, trHeaders, trResultPath, trLangPath)
        translationFields.forEach { panel.addView(it) }
        panel.addView(trHint)
        fun showTranslationFields(provider: String) {
            val vendor = Translator.vendor(vendorIds[trAiVendor.selectedItemPosition.coerceAtLeast(0)])
            val customVendor = vendor.id == Translator.AI_VENDOR_CUSTOM
            val visible: List<View> = when (provider) {
                Translator.PROVIDER_DEEPL -> listOf(trKey)
                Translator.PROVIDER_MICROSOFT -> listOf(trKey, trRegion)
                Translator.PROVIDER_BAIDU -> listOf(trAppId, trKey)
                Translator.PROVIDER_LIBRE -> listOf(trEndpoint, trKey)
                Translator.PROVIDER_OPENAI -> if (customVendor) listOf(trAiVendor, trAiBase, trKey, trModel) else listOf(trAiVendor, trKey)
                Translator.PROVIDER_CUSTOM -> listOf(trCustomUrl, trMethod, trBody, trHeaders, trResultPath, trLangPath)
                else -> emptyList()
            }
            translationFields.forEach { it.visibility = if (it in visible) View.VISIBLE else View.GONE }
            trHint.text = when (provider) {
                Translator.PROVIDER_GOOGLE -> "走 translate.googleapis.com，在国内需要代理。"
                Translator.PROVIDER_DEEPL -> "免费版 Key 以 :fx 结尾，会自动走 api-free.deepl.com。"
                Translator.PROVIDER_MICROSOFT -> "Azure 门户里的 Translator 资源 Key；多区域资源可留空区域。"
                Translator.PROVIDER_BAIDU -> "fanyi-api.baidu.com 的通用翻译，APP ID 和密钥在开放平台的开发者信息里。"
                Translator.PROVIDER_LIBRE -> "POST 到 <地址>/translate；公共服务器可能要 API Key。"
                Translator.PROVIDER_OPENAI ->
                    if (customVendor) "任何兼容 OpenAI 聊天接口的中转站都能用：地址写到 /v1 为止即可（会自动补成 /chat/completions），模型名留空用 ${Translator.DEFAULT_AI_MODEL}。"
                    else "只需填 ${vendor.name} 的 API Key。接口 ${vendor.endpoint}，模型 ${vendor.model}。"
                else -> "返回 JSON 里译文在哪个字段用点分路径写，数字是数组下标；留空会按常见字段名猜。"
            }
        }
        trAiVendor.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                showTranslationFields(providerIds[providerSpinner.selectedItemPosition])
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }
        showTranslationFields(translation.provider)
        providerSpinner.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                showTranslationFields(providerIds[position])
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        panel.addView(sectionTitle("维护"))
        panel.addView(actionRow("同步点赞记录", "把在网页端点过的赞补进“已看”，刷新推荐后生效。") { onSyncLikes() })
        panel.addView(actionRow("兴趣管理", "点过「不感兴趣」的作者和标签列在这里，它们不再进入推荐，可以随时恢复。") { onInterestManager() })
        panel.addView(actionRow("诊断信息", "最近的异常、退出原因和加载线索、推荐质量指标，只存在本机，不会上传。") {
            onDiagnostics()
        })

        // 多了“维护”这一段，矮屏幕上放不下，内容区要能滚动。
        // 限高：和主菜单差不多大，内容多出来的部分滚动看，弹窗不顶到屏幕上下沿。
        val scroll = ScrollView(activity).apply {
            addView(panel)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (activity.resources.displayMetrics.heightPixels * SCROLL_FRACTION).toInt()
            )
        }
        // 取消 / 保存放在滚动区下面、弹窗自己的一栏里，常驻底部，不随内容滚走，
        // 也不依赖 AlertDialog 的按钮栏（内容一高它就被挤出屏幕）。
        val cancel = dialogTextButton("取消")
        val save = dialogTextButton("保存")
        val buttonBar = LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = android.view.Gravity.END or android.view.Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(4), dp(16), dp(6))
            addView(cancel); addView(save)
        }
        val divider = View(activity).apply {
            setBackgroundColor(0xFFD9E6F2.toInt())
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
        }
        val content = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            addView(scroll); addView(divider); addView(buttonBar)
        }
        val dialog = AlertDialog.Builder(activity).setTitle("设置").setMessage("播放行为、画质、推荐过滤和维护工具").setView(content).create()
        cancel.setOnClickListener { dialog.dismiss() }
        save.setOnClickListener {
            // 只有“排除已看视频”和“老片穿插”会改变推荐候选，也只有推荐流受它们影响；
            // 其它几项重拉一遍列表只会把用户刷到一半的位置冲掉。
            val newClassics = classicsValues[classics.selectedItemPosition]
            val feedMode = currentMode()
            val reloadFeed = (feedMode == "recommend" &&
                (prefs.skipSeen != skipSeen.isChecked || prefs.classicsEvery != newClassics)) ||
                (feedMode != "recommend" && prefs.skipSeenEverywhere != skipSeenEverywhere.isChecked)
            prefs.classicsEvery = newClassics; onClassicsChanged(newClassics)
            prefs.skipSeen = skipSeen.isChecked; prefs.autoNext = autoNext.isChecked; prefs.autoPip = autoPip.isChecked
            prefs.skipSeenEverywhere = skipSeenEverywhere.isChecked
            prefs.recommendDebug = recommendDebug.isChecked
            prefs.showPauseIndicator = pauseIcon.isChecked
            prefs.tapToPause = tapPause.isChecked
            prefs.skipSeconds = skipValues[skip.selectedItemPosition]
            prefs.defaultQuality = qualityValues[spinner.selectedItemPosition]
            val newType = proxyTypes[proxyType.selectedItemPosition]
            val newHost = proxyHost.text.toString().trim()
            val newPort = proxyPort.text.toString().trim().toIntOrNull() ?: 0
            val proxyChanged = newType != prefs.proxyType || newHost != prefs.proxyHost || newPort != prefs.proxyPort
            if (newType != NetworkProxy.TYPE_NONE && NetworkProxy.proxyFor(newType, newHost, newPort) == null) {
                Toast.makeText(activity, "代理主机或端口不完整，代理设置未保存", Toast.LENGTH_LONG).show()
            } else {
                prefs.proxyType = newType; prefs.proxyHost = newHost; prefs.proxyPort = newPort
                if (proxyChanged) NetworkProxy.apply(prefs)
            }
            val provider = providerIds[providerSpinner.selectedItemPosition]
            val newTranslation = TranslationConfig(
                provider = provider,
                key = trKey.text.toString().trim(),
                region = trRegion.text.toString().trim(),
                appId = trAppId.text.toString().trim(),
                endpoint = when (provider) {
                    Translator.PROVIDER_CUSTOM -> trCustomUrl
                    Translator.PROVIDER_OPENAI -> trAiBase
                    else -> trEndpoint
                }.text.toString().trim(),
                customMethod = methodValues[trMethod.selectedItemPosition],
                customBody = trBody.text.toString(),
                customHeaders = trHeaders.text.toString(),
                customResultPath = trResultPath.text.toString().trim(),
                customLangPath = trLangPath.text.toString().trim(),
                model = trModel.text.toString().trim(),
                aiVendor = vendorIds[trAiVendor.selectedItemPosition.coerceAtLeast(0)]
            )
            prefs.translation = newTranslation
            Translator.configure(newTranslation)
            Toast.makeText(activity, "设置已保存", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
            // 网络路径变了，之前失败的请求要重新来。
            onSaved(reloadFeed || proxyChanged)
        }
        dialog.show()
    }

    /** 和 AlertDialog 按钮栏同款的文字按钮，给自己摆的底栏用。 */
    private fun dialogTextButton(label: String) = TextView(activity).apply {
        text = label; textSize = 14f; setTypeface(null, android.graphics.Typeface.BOLD)
        setTextColor(0xFFD84B73.toInt()); gravity = android.view.Gravity.CENTER
        minHeight = dp(42); setPadding(dp(16), 0, dp(16), 0)
        isClickable = true; isFocusable = true
        background = with(android.util.TypedValue()) {
            activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, this, true)
            ContextCompat.getDrawable(activity, resourceId)
        }
    }

    private fun settingsInput(hint: String, value: String, inputType: Int, multiline: Boolean = false) = EditText(activity).apply {
        this.hint = hint; setText(value)
        this.inputType = if (multiline) inputType or InputType.TYPE_TEXT_FLAG_MULTI_LINE else inputType
        setSingleLine(!multiline)
        // setSingleLine 会覆盖掉密码遮罩：密钥类的输入框要再装回圆点显示。
        if (inputType and InputType.TYPE_MASK_VARIATION == InputType.TYPE_TEXT_VARIATION_PASSWORD) {
            transformationMethod = android.text.method.PasswordTransformationMethod.getInstance()
        }
        if (multiline) { minLines = 2; maxLines = 5 }
        setTextColor(0xFF17324A.toInt()); setHintTextColor(0x99607D93.toInt())
        background = ContextCompat.getDrawable(activity, R.drawable.bg_input)
        setPadding(dp(16), dp(10), dp(16), dp(10))
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(6) }
    }

    /**
     * 设置页里的一行“动作”。点了立刻执行，不等“保存”——它们本来就不是开关。
     * 也不关闭设置页：关掉的话，用户刚勾上还没保存的选项就白勾了。
     */
    private fun actionRow(title: String, subtitle: String, onClick: () -> Unit): View =
        LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            isClickable = true
            isFocusable = true
            setPadding(dp(4), dp(10), dp(4), dp(10))
            addView(TextView(context).apply {
                text = title; textSize = 15f; setTextColor(0xFFD84B73.toInt())
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
            addView(TextView(context).apply {
                text = subtitle; textSize = 12f; setTextColor(0xFF607D93.toInt()); setPadding(0, dp(2), 0, 0)
            })
            setOnClickListener { onClick() }
        }

    private fun sectionTitle(text: String) = TextView(activity).apply {
        this.text = text; setTextColor(0xFFFF6F91.toInt()); textSize = 13f; setPadding(0, dp(12), 0, dp(4)); setTypeface(null, android.graphics.Typeface.BOLD)
    }

    companion object {
        /** 设置弹窗内容区最高占屏幕的比例，超出的部分滚动看。 */
        const val SCROLL_FRACTION = 0.42f
    }
}
