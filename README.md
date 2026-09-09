# IwaraFlow

Android 原生的 Iwara 竖屏短视频流播放器，目标是提供类似短视频 App 的连续浏览体验。

## 当前功能

### 视频流

- 推荐 / 趋势 / 人气 / 最新
- **趋势**：直接使用 Iwara 官方 `sort=trending`
- **人气**：直接使用 Iwara 官方 `sort=popularity`
- **最新**：直接使用 Iwara 官方 `sort=date`
- **推荐**：IwaraFlow 本地推荐算法，对多个官方榜单候选重新打分排序
- 上滑下一条、下滑上一条
- 自动加载更多视频
- 下一条 / 下下条提前解析视频源
- 播放结束自动进入下一条
- 已看视频自动跳过开关

### 播放器

- AndroidX Media3 / ExoPlayer
- 默认最高可用画质 / 原画
- 手动切换清晰度
- 单击播放 / 暂停
- 双击点赞 + 爱心动画
- **按住视频约 0.45 秒临时 2× 加速，松手恢复 1×**
- 加速时显示 `2× 加速中`
- Android 画中画（PiP）
- 可设置切到后台自动进入画中画
- 播放器 decoder fallback
- 播放卡死 watchdog：检测到时间轴长时间不前进时自动重建播放源
- 切换视频后旧页面直接停止并释放 ExoPlayer，避免后台继续播放声音

### Iwara 账号

- Iwara 登录：`/user/login` → `/user/token`
- Token 使用 AndroidX Security 加密存储
- 密码不保存到本地
- Iwara 点赞真实同步：`POST / DELETE /video/{id}/like`
- Iwara 点赞记录：`/favorites/videos`

### 本地收藏

IwaraFlow 的星形收藏是**纯本地收藏**，与 Iwara 官方点赞完全分离：

- `♥` 心形：Iwara 官方点赞，会同步服务端
- `★` 星形：IwaraFlow 本地收藏，仅保存在当前设备 SQLite 数据库

点击点赞不会再同时点亮本地收藏。

### 浏览历史

- SQLite 本地浏览历史
- 保存最近观看的视频
- 保存最后播放位置、视频时长和是否基本看完
- 浏览历史使用独立卡片列表展示
- 本地收藏也有独立列表

### 搜索与下载

- Iwara 视频搜索：`/search?type=videos&query=...`
- 支持搜索标题、标签、作者关键词
- 使用 Android DownloadManager 下载
- 下载前可选择视频清晰度

## 推荐算法

推荐页不会直接照搬某一个 Iwara 官方榜单。

候选视频来自：

- `trending`，基础权重 3.3
- `popularity`，基础权重 2.8
- `likes`，基础权重 2.1
- `views`，基础权重 1.6

每组抓取前 36 条，同一个视频同时进入多个榜单时会得到额外加分。

随后综合：

- 官方榜单来源权重
- 榜单排名
- 点赞数
- 播放量
- 视频新鲜度
- 本地作者偏好
- 本地标签偏好
- 点赞、收藏、完整观看、下载等本地行为
- 少量稳定扰动，避免推荐结果完全僵化

设置中开启“自动跳过已看视频”后，浏览历史中的视频会在推荐阶段被过滤。

## UI

- 播放主界面保持深色沉浸式风格
- 登录 / 搜索 / 设置弹窗使用浅蓝白色主题
- 登录、保存、搜索等主按钮使用明确的实心强调色按钮
- 浏览历史改为浅蓝背景 + 浅色卡片 + 深色文字
- 调整卡片圆角、内边距和条目间距
- 画质 / 下载 / 小窗按钮带文字说明，避免只有图标难以理解

## 技术栈

- Kotlin
- ViewPager2
- AndroidX Media3 / ExoPlayer 1.11.0
- OkHttp
- SQLiteOpenHelper
- AndroidX Security Crypto
- Android DownloadManager
- Android Picture-in-Picture

## 构建

要求：

- JDK 17+
- Android SDK 36
- Gradle 8.13

本地可使用：

```bat
gradlew.bat assembleDebug
```

## GitHub Actions APK

仓库包含自动 Android 构建工作流。

每次推送到 `main` 后会自动：

1. 配置 JDK / Android SDK / Gradle
2. 构建可安装 APK
3. 使用 `apksigner verify` 检查 APK 签名
4. 上传 `IwaraFlow-signed-APK` Artifact

> 当前 Actions 产物可以直接安装。后续若需要不同版本长期无缝覆盖安装，建议配置固定 release keystore 与 GitHub Secrets，避免构建环境签名变化。

## 版本说明

### v0.3.x

- 重构播放器生命周期，减少切页后旧视频继续播放
- 增加 decoder fallback 与自动卡死恢复
- 点赞与本地收藏完全分离
- 优化登录、设置、历史等 UI
- 浏览历史切换为淡蓝色卡片布局
- 修复部分弹窗主按钮不明显的问题
- 优化画质 / 下载 / 小窗按钮说明
- 新增按住 2× 加速，松手恢复正常速度
- GitHub Actions 改为产出经过签名验证的可安装 APK

### v0.2.0

- 加入 Iwara 登录与 Token 管理
- 加入官方点赞同步
- 加入浏览历史、搜索、下载和画中画
- 加入本地推荐算法
- 加入自动下一条与视频源预解析

## 说明

这是第三方客户端，不隶属于 Iwara。

Iwara API 属于站点内部接口，后续若接口、Cloudflare 策略或视频源签名方式发生变化，相关网络层可能需要继续适配。
