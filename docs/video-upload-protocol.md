# Iwara 原生视频投稿协议

核实时间：2026-10-07。参考官网公开客户端 3.3.7（构建时间 2026-03-29），入口 [创建视频](https://www.iwara.tv/create/video)。

公开源文件：
- [主客户端](https://www.iwara.tv/main.838884031a28ff0e346d.js)：uploadFile、processFileProgress、createVideo。
- [文件输入组件](https://www.iwara.tv/chunk-9614.68a0fa4b369519bab0c8.js)：VideoUploadField 的文件传输和任务轮询。
- [视频表单](https://www.iwara.tv/chunk-5749.bc21aec4e57909f98c0d.js)：UploadForm 字段。
- [发布流程](https://www.iwara.tv/chunk-5790.43d86b1b461ef74bf5e8.js)：CreateVideo 调用 createVideo 并读取返回的 id。

## 流程

1. 使用应用现有会话获取 access token；到期沿用现有 refresh token 刷新，无需网页再次登录。
2. POST https://files.iwara.tv/upload/video，Authorization: Bearer access token，multipart/form-data，文件字段 file。官网以 HTTP 201 返回任务 id 和 key。
3. GET https://files.iwara.tv/upload/video/{id}/{key}，不附带账户 token。响应包括 state、progress；completed 时的 data 是文件对象；failed 是处理失败。
4. POST https://api.iwara.tv/videos，JSON 包括 title、body、file（上一步完整对象）、tags（带 id 的对象数组）、rating（general/ecchi）、private、unlisted、thumbnail、rulesAgreement。使用相同账户登录，携带 X-Site: www.iwara.tv。成功响应里的 id 为新视频编号。提交成功与官网转码/审核完成是两个状态。

官网通用发布 API 支持 X-Captcha，但当前视频表单没有验证码字段。本客户端遇到验证/权限错误会显示官网错误，不绕过验证。

## 实现与验证边界

- 文件通过 ContentResolver 流式读取，不整段载入内存；旋转保留 ViewModel 中的上传任务。
- 上传或发布不自动重试，不跟随重定向转发令牌与文件。明确的表单错误可修改后重试，复用已处理文件。
- 发布超时、HTTP 5xx 或缺少视频编号时标记结果未知，需先确认我的作品，避免重复投稿。
- 返回退出会取消请求；进程被系统终止后不自动投稿，表单及 URI 在可恢复时保留。
- 本地 MockWebServer 覆盖协议顺序、认证、错误、重定向和结果未知；CI 使用中性数据验证原生界面。
- 没有使用用户账号发布测试视频；真实账号额度、后台转码、审核和官网后续协议变化仍需投稿时验证。
