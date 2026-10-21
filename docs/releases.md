# APK 下载与发行

日常安装使用 [GitHub Releases](https://github.com/yunko1993/mediary/releases/latest) 的 `mediary.apk`，也可通过 [固定下载地址](https://github.com/yunko1993/mediary/releases/latest/download/mediary.apk) 获取最新安装包。不要下载页面自动生成的 Source code 压缩包来安装。手机按提示允许当前浏览器或文件管理器安装应用即可；安装后不需要 USB 或电脑保持运行。当前没有应用内自动更新。

更新请选择覆盖安装，不要先卸载。正式版包名保持为 `app.mediary`，版本号递增，关闭调试功能。卸载会删除本机计划、历史和连接设置，导出文件目前不能一键恢复。

## 签名与兼容升级

正式版采用独立的固定 RSA 发行密钥。`android/signing/lineage.bin` 是由原调试密钥签署的公开迁移证明，`certificates.json` 只含公开证书指纹，不包含私钥。发布脚本逐个验证签名区间：Android 8 使用原签名，Android 9 起使用发行签名和迁移链。OPPO 真机已验证从 0.2.2 调试版覆盖升级到 0.2.3 正式版，应用 UID、数据库、设置与解密连接配置均保留；Android 8 的兼容签名只做了工具校验，未做旧机实测。

从 0.2.3 起，开发构建改为 `app.mediary.debug` / **Mediary Dev**，使用独立数据，避免开发测试覆盖日常安装。正式版和开发版的连接配置互不共享。

私有签名配置和两份受口令保护的密钥保存在本机用户私有的 `.mediary-signing` 目录，同时以加密 Secret `ANDROID_SIGNING_BUNDLE` 提供给仓库 Actions。Secrets 不出现在 Git 文件、日志或 APK 中。应将整个私有目录另存到加密离线备份；原兼容密钥仍用于 Android 8 签名，不能只备份新密钥。不要重新生成证书来替代已有发行身份。

## 本地构建与校验

需要 JDK 17、Python 3 和 Android Build Tools 35.0.0。私有配置通过环境变量或参数传入，不写进 Gradle：

```powershell
$env:MEDIARY_SIGNING_CONFIG = Join-Path $env:USERPROFILE '.mediary-signing/signing.local.json'
.\scripts\build.ps1 -Variant Release
```

产物位于 `outputs/release/`：

- `mediary.apk`：已签名正式安装包。
- `SHA256SUMS`：APK 的 SHA-256 校验值。
- `release-info.json`：版本、源码提交、签名证书公开指纹和 APK 校验值。

可通过 `python scripts/android_release.py verify --apk outputs/release/mediary.apk` 重新校验。验证器拒绝调试包、错误包名、版本不匹配或不受信任的签名。GitHub Actions 使用相同脚本。

需要 USB 安装时执行 `./scripts/install.ps1`，默认安装正式包；`-Variant Debug` 才会安装独立开发版。安装和真机测试会短暂重启应用，开始前应告知使用者，避免正在编辑时执行。

## 发布下一版

1. 增加 `android/app/build.gradle.kts` 的 `versionCode` 和 `versionName`，新增对应的 `releases/vX.Y.Z.md`。
2. 提交到 `main` 并确认常规 CI 通过，再创建、推送匹配版本的 `vX.Y.Z` 标签。
3. `Android release` 工作流验证标签与源码版本、主分支来源，运行服务测试、安卓测试、编译和 Lint，再签名、校验、上传产物，最后发布 GitHub Release。

已发布版本不覆盖附件、不移动标签；需要修正时增加新版本。签名迁移链和指纹保持不变，除非专门执行下一次密钥迁移。

实现依据：[Android 签名与迁移工具](https://developer.android.com/tools/apksigner)、[GitHub CLI Release](https://cli.github.com/manual/gh_release_create)。
