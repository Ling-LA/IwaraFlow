# IwaraFlow

Android 原生的 Iwara 竖屏短视频流播放器。

## v0.2.0

- 修复滑到下一条后旧视频继续播放声音的问题
- 推荐 / 趋势 / 热门 / 最新流
- 本地推荐算法：综合 trending、popularity、likes、views、新鲜度和本地偏好权重
- 上滑下一条、下滑上一条
- 下一条播放器预准备；下下条提前解析 CDN 视频源
- 播放结束自动进入下一条
- 单击播放/暂停
- 双击点赞 + 中央爱心动画
- Iwara 登录：`/user/login` → `/user/token`
- Token 使用 AndroidX Security 加密存储，密码不落盘
- Iwara 点赞真实同步：`POST/DELETE /video/{id}/like`
- Iwara 收藏/点赞列表：`/favorites/videos`
- 浏览历史（SQLite）
- 已看视频自动跳过开关
- 播放清晰度切换
- 默认最高可用/原画，可设置 1080p / 720p / 540p / 360p
- 搜索：`/search?type=videos&query=...`
- 系统 DownloadManager 下载，可选择清晰度
- Android 画中画，支持切后台自动进入 PiP
- 热门个性化偏好：点赞、看完、下载等行为会影响后续推荐排序

## 关于 Iwara 的“收藏”

Iwara 当前 API 的 Favorites 视频列表来自点赞关系：`/favorites/videos`，写入/取消使用 `/video/{id}/like`。因此 IwaraFlow 的心形点赞与星形收藏状态会同步同一个 Iwara 服务端状态，而不是伪造一套并不存在的独立远端收藏接口。

## 技术栈

- Kotlin
- ViewPager2
- AndroidX Media3 / ExoPlayer 1.11.0
- OkHttp
- SQLiteOpenHelper
- AndroidX Security Crypto
- Android DownloadManager

## 构建

要求：

- JDK 17+
- Android SDK 36
- Android Studio 或 Gradle 8.13

Windows 可直接打开项目后 Build APK，或在 Gradle Wrapper 可用时运行：

```bat
gradlew.bat assembleDebug
```

APK 默认位于：

```text
app\build\outputs\apk\debug\app-debug.apk
```

## 说明

这是第三方客户端，不隶属于 Iwara。Iwara API 属于站点内部接口，后续若接口、Cloudflare 策略或视频源签名变化，相关网络层可能需要适配。
