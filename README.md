# Mediary · 用药手记

个人自用的安卓用药提醒与记录工具。把已确认的医嘱变成计划，留下每一次实际服药记录。

默认在 ChatGPT 中整理医嘱，通过 MCP 保存待核对草稿，手机同步后核对并启用本地提醒，无需模型 API Key。已确认计划与服药历史仍在手机，本地提醒不依赖 GPT 或网络。原模型 API 直连保留为可选方式，PC 端留待后续。

## 已实现

- **今日**：七日日期切换、按时间显示计划、服药登记、实际时间补录、跳过记录、稍后提醒。
- **药箱**：手动添加、查看原文、暂停与恢复，支持每日固定钟点、固定小时间隔、按需记录；每天 2 次可选 08:00、18:00，每天 3 次可选 08:00、12:00、18:00，均为可调整的个人提醒预设，保存前需人工核对。支持钟点选择器和字段错误提示。
- **记录**：保存计划时间、实际时间、药名和剂量快照；撤销通过追加事件实现；导出完整 JSON。
- **本地提醒**：系统 AlarmManager、通知操作按钮、权限检查、15 秒测试提醒；重启与时区变化后重排。
- **ChatGPT 草稿**：OAuth 授权的 MCP 工具创建、读取草稿 → 手机同步收件箱 → 本机原文证据校验 → 人工设置时间并保存计划。持久化草稿、重复创建去重、离线回执重试。
- **可选 API 导入**：保留智谱固定型号、其他 Responses API 与自有整理中转；默认界面不要求模型密钥。
- **隐私**：计划和服药历史保存在本机，不上传至 MCP；草稿原文由你主动提供给 ChatGPT 并保存在自有服务。服务口令与可选 API 密钥在手机加密保存，不随 APK 分发、不包含在导出中。

应用不附带真实药物示例，不自动创建任何服药计划。当前支持草稿同步，暂不支持服药历史云同步、照片识别、PC 客户端和备份文件一键恢复。

## 下载到手机

打开 [最新 GitHub Release](https://github.com/yunko1993/mediary/releases/latest)，下载 Assets 中的 **mediary.apk**，在手机点击安装即可。也可使用 [最新 APK 固定下载地址](https://github.com/yunko1993/mediary/releases/latest/download/mediary.apk)。不需要一直连接 USB；更新时覆盖安装，不要先卸载。安装包不包含个人服务口令，首次使用仍须配置自己的草稿连接。

正式版关闭调试功能，固定签名并包含旧版签名迁移证明；开发版单独显示为 **Mediary Dev**，使用独立数据。签名备份、构建和后续自动发布见 [发行说明](docs/releases.md)。

## 工程结构

```text
android/
  app/                 Compose 页面、SQLite 本地存储、系统通知、GPT 导入客户端
  core/                纯 Kotlin 排程规则、医嘱原文校验与单元测试
server/
  src/                 个人 MCP/OAuth 草稿服务与可选 AI 整理中转
  test/                原文证据校验与 HTTP 集成测试
docs/
  architecture.md      时间、记录与 AI 边界说明
  device-checklist.md  OPPO / ColorOS 真机验收
scripts/
  build.ps1            Windows 编译、测试与 APK 归档
outputs/               本机生成的 APK 与验证记录（Git 忽略）
```

## 运行安卓端

需要 JDK 17、Android SDK Platform 35 和 Build Tools 35.0.0。推荐用 Android Studio 打开 `android/`，让 IDE 选择本机 SDK。

也可以在 `android/local.properties` 配置 `sdk.dir=C:/path/to/Android/Sdk`，随后：

```powershell
$env:JAVA_HOME = '<本机 JDK 17 安装目录>'
Set-Location android
.\gradlew.bat :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

`scripts/build.ps1` 可在仓库根目录执行相同检查，并将开发 APK 按实际版本放入 `outputs/`。使用 `-Variant Release` 和私有签名配置可构建正式包 `outputs/release/mediary.apk`。如果本机使用自动准备的工具，SDK 位于 `work/tools/android-sdk`，Gradle 缓存在 `work/gradle-home`。这些本机依赖不提交 Git。

开发调试包只用于开发验证；日常使用请下载 Release 正式包。最低 Android 8.0（API 26），目标 API 35。

连接一部已授权 USB 调试的手机后，可以在仓库根目录运行 `./scripts/install.ps1` 安装并打开正式包；独立开发版使用 `-Variant Debug`。多部手机时使用 `-Serial <设备ID>` 指定。手机直连 GPT 不需要 USB；仅在调试可选本机整理服务时使用 `-Variant Debug -ConnectGptService` 设置 USB 反向连接。

如果开发者选项的厂商菜单入口不可见，可以先通过文件传输将 APK 复制到手机安装，再在 Mediary 的 **设置 → 打开开发者选项** 中跳转系统页面。这个快捷入口仅在开发调试包显示，不会自动改变系统开关。

首次使用先到 **设置 → 提醒状态** 开启通知、闹钟与提醒权限，再执行 **15 秒后测试提醒**。最近测试会显示计划时间、系统触发时间与通知提交结果；提交成功仍需在通知栏核对，声音和横幅会受勿扰模式及系统设置影响。未开启精确闹钟权限时会降级为可能延迟的系统提醒，页面会标示未就绪。

OPPO Find N5 / ColorOS V16.1.0 真机曾在默认耗电策略下推迟后台测试闹钟。可在 **设置 → 打开系统应用设置 → 耗电管理 → 完全允许后台行为** 中调整 Mediary 的后台设置，再复测锁屏和长时间待机；单次成功不能代表所有状态下必达。实测进度见 `docs/device-checklist.md`。

## 连接 ChatGPT 与手机

1. 按 [服务部署说明](server/README.md) 配置 HTTPS 服务、强随机服务口令与私有持久化数据卷。
2. 手机 **设置 → ChatGPT 草稿连接** 填入服务基础地址和服务访问口令，无需模型 Key。
3. 在 ChatGPT 网页 **Plugins → ＋ → Add custom MCP server** 添加 `<服务基础地址>/mcp`，认证选 OAuth，在自己的服务页面授权。不要把服务口令发进聊天。
4. 对话中选择 Mediary，发送医嘱原文并要求保存草稿；回到手机右上角收件箱同步，逐项核对、设置日期和钟点后保存。

入口是否出现取决于 ChatGPT 账号、工作区和客户端。部署服务不等于某个 Plus 账号已经接入；没有创建入口时请先保留手动计划，勿关闭鉴权。当前 MCP 工具只处理草稿及手机回执，不能直接启用提醒或读写手机服药记录。

完整接入方式、工具范围及验收见 [MCP 接入说明](docs/mcp.md)。原智谱、Responses API 和整理中转保留在 **设置 → 可选：模型 API 直连与整理服务**，只在使用这些可选方式时才需要对应 API Key。

## 用药计划与记录规则

- 提醒的内容必须先与医生或药师的要求核对。模型只抽取原文，不生成额外剂量、疗程或补服方案。
- **每天定时**跟随手机当前时区；**按间隔**按真实经过小时计算，并保持首次时间锚点。
- 开始和结束日期都包含当天；结束日期留空表示没有自动结束日期。
- 按需计划不会发定时服药通知，只能记录实际服用。
- “稍后提醒”延后通知 10 分钟，不改变原始计划时间。等待中的稍后提醒会持久化，重启后只恢复尚未到期的提醒。
- 重启、恢复计划或更改时间后只安排未来剂次，不集中补发过去的提醒。
- “未记录”不是“漏服”。通知送达也不等于实际服用。
- 改变医嘱时先暂停原计划，再新增核对后的计划；历史药名和剂量快照不会随新计划改变。
- 卸载会清除本机数据。导出的 JSON 含完整计划与操作历史，当前尚未实现一键恢复，请妥善保存导出文件。

## 验证

```powershell
cd server
node --test
cd ../android
.\gradlew.bat :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

安卓与可选服务测试使用可控的上游响应，验证请求格式、凭据边界、原文证据、缺失字段、拒绝与异常；不消耗真实 API 调用。安卓真机运行 `ConnectionSettingsTest` 与 `InboxRepositoryTest`，使用独立临时偏好文件和数据库，验证加密、迁移与离线去重。真实 AI 联调需用户在手机或自有服务器填写对应密钥，通知送达需连接真机验证。

更多验收步骤见 [真机检查](docs/device-checklist.md)。

## GitHub Actions

推送 `main`、提交 PR 或手动运行时，会检查服务端测试、Docker 镜像构建，以及安卓测试、APK 编译和 Lint。`main` 的检查全部通过且仓库变量 `DEPLOY_ENABLED=true` 时，自动通过 SSH 更新自有服务器；PR 不部署。连接凭据使用 GitHub Actions Secrets，提供方 API Key 只保留在服务器的共享 `.env`，不随代码上传。

初次准备、Secrets 配置、发布目录及失败恢复见 [Actions 部署说明](server/README.md#github-actions-自动部署)。

## 实现参考

- [Android 系统闹钟](https://developer.android.com/develop/background-work/services/alarms)
- [OpenAI Responses API](https://developers.openai.com/api/reference/responses/overview)
- [OpenAI Structured Outputs](https://developers.openai.com/api/docs/guides/structured-outputs?api-mode=responses)
