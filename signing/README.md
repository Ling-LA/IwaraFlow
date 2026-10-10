# 发布签名

0.16.10 起正式发布使用 RSA-4096 私密密钥。仓库只包含公开的 `release-cert.pem`、SHA-256 指纹和 `release.lineage`，均不包含私钥。

当前证书 SHA-256：`8cb7891ffedc59346b6320fa5f517ad187b1515973471b031d8da552f7bf86e1`。

维护者需要在 GitHub Actions repository Secrets 中保存 `IWARAFLOW_RELEASE_KEYSTORE_B64`（PKCS12 keystore 的 base64）与 `IWARAFLOW_RELEASE_STORE_PASSWORD`；别名为 `iwaraflow-release`。私钥只能由维护者从安全备份恢复，GitHub Secrets 不能导出。不要在 Issue、日志、提交或发布附件中粘贴私钥及密码。

发布只在本仓库 main 上运行；PR 运行测试，不接触发布密钥。签名临时文件限制权限并在任务末尾删除。缺少 Secrets 会失败，绝不回退到公开开发签名。fork 可自行生成自己的私密密钥用于独立分发，但不能用自己的密钥覆盖官方安装。

## 升级衔接

应用最低支持 Android 9，使用 APK v3 和 `--rotation-min-sdk-version 28`，所有支持的系统均采用新密钥。轮换证明允许旧版数据迁入新版，明确关闭旧签名的 rollback、shared UID、signature permission 和 authenticator 信任。旧开发密钥曾公开，删除当前文件不能消除 Git 历史中的副本。

发布必须通过 Android 9、13、16 的覆盖升级测试：安装 0.16.9、写入数据标记、覆盖安装新版并检查标记、拒绝旧密钥重新签署的同版本 APK、验证新签名可重装。测试仅从固定历史提交读取公开旧密钥，用于拒绝测试，不用于正式签名。

Android 官方对较早系统的轮换兼容性有注意事项，因此这里保留跨系统安装测试。参考：[apksigner](https://developer.android.com/tools/apksigner) 与 [APK v3 签名轮换](https://source.android.com/docs/security/features/apksigning/v3)。
