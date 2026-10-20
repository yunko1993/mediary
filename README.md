# Mediary · 用药手记

个人自用的安卓用药提醒与记录工具。把已确认的医嘱变成计划，留下每一次实际服药记录。

第一版是可独立使用的原生安卓 App：手机填写自己的 API 密钥后直接连接 AI，无需电脑在线。内置智谱 GLM-4.7-Flash 固定型号入口；其他 Responses API 和 Node 医嘱整理服务作为可选方式保留。PC 端与 Notion 同步留待后续。

## 已实现

- **今日**：七日日期切换、按时间显示计划、服药登记、实际时间补录、跳过记录、稍后提醒。
- **药箱**：手动添加、查看原文、暂停与恢复，支持每日固定钟点、固定小时间隔、按需记录。
- **记录**：保存计划时间、实际时间、药名和剂量快照；撤销通过追加事件实现；导出完整 JSON。
- **本地提醒**：系统 AlarmManager、通知操作按钮、权限检查、15 秒测试提醒；重启与时区变化后重排。
- **AI 导入**：文字医嘱 → 手机调用智谱 Chat Completions 或其他 Responses API → 本机原文证据校验 → 逐项确认 → 保存计划。也可选用自有整理服务。多条药物可以连续核对，已保存条目会标记。
- **隐私**：计划和记录默认保存在本机；只有主动同意导入时发送输入的文字；用户填写的 API 密钥与可选服务口令由 Android Keystore 加密保存，不随 APK 分发、不包含在数据导出中。

应用不附带真实药物示例，不自动创建任何服药计划。第一版暂不支持照片识别、云同步、PC 客户端和备份文件一键恢复。

## 工程结构

```text
android/
  app/                 Compose 页面、SQLite 本地存储、系统通知、GPT 导入客户端
  core/                纯 Kotlin 排程规则、医嘱原文校验与单元测试
server/
  src/                 可选的个人 GPT 医嘱整理服务
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

`scripts/build.ps1` 可在仓库根目录执行相同检查，并将 APK 放入 `outputs/mediary-0.1.0-debug.apk`。如果本机使用自动准备的工具，SDK 位于 `work/tools/android-sdk`，Gradle 缓存在 `work/gradle-home`。这些本机依赖不提交 Git。

APK 为开发调试包，适合自用验证。最低 Android 8.0（API 26），目标 API 35。

连接一部已授权 USB 调试的手机后，可以在仓库根目录运行 `./scripts/install.ps1` 安装并打开应用。多部手机时使用 `-Serial <设备ID>` 指定。手机直连 GPT 不需要 USB；仅在调试可选本机整理服务时使用 `-ConnectGptService` 设置 USB 反向连接。

如果开发者选项的厂商菜单入口不可见，可以先通过文件传输将 APK 复制到手机安装，再在 Mediary 的 **设置 → 打开开发者选项** 中跳转系统页面。这个快捷入口仅在开发调试包显示，不会自动改变系统开关。

首次使用先到 **设置 → 提醒状态** 开启通知、闹钟与提醒权限，再执行 **15 秒后测试提醒**。最近测试会显示计划时间、系统触发时间与通知提交结果；提交成功仍需在通知栏核对，声音和横幅会受勿扰模式及系统设置影响。未开启精确闹钟权限时会降级为可能延迟的系统提醒，页面会标示未就绪。

OPPO Find N5 / ColorOS V16.1.0 真机曾在默认耗电策略下推迟后台测试闹钟。可在 **设置 → 打开系统应用设置 → 耗电管理 → 完全允许后台行为** 中调整 Mediary 的后台设置，再复测锁屏和长时间待机；单次成功不能代表所有状态下必达。实测进度见 `docs/device-checklist.md`。

## 在手机配置 AI（默认智谱 GLM-4.7-Flash）

1. 打开 **设置 → AI 医嘱整理**，确认 **智谱 · GLM-4.7-Flash**，填写自己的 **智谱 API Key**，点击 **保存 AI 设置**。升级前已保存的连接仍保留原选择，可在高级设置中切换到智谱。
2. 内置入口固定使用 `https://open.bigmodel.cn/api/paas/v4/chat/completions`，请求明确指定 `model: glm-4.7-flash`，并核对响应的模型标识。返回其他型号、解析失败或限流时直接提示，不自动切换型号或提供方。
3. 从首页右上角 **从医嘱导入** 粘贴文字，核对发送目的地，勾选同意后开始整理。
4. 核对原文、剂量、频次、日期和具体时间，再启用提醒。缺失字段不会自动补全。

2026-10-20 核对的[智谱官方说明](https://www.zhipuai.cn/zh/news/148)将 **GLM-4.7-Flash** 列为免费调用，不能据此把整个 GLM-4.7 系列都视为免费。API Key 代表账号权限，具体型号由请求的 `model` 决定；平台定价可能变化，实际费用和调用记录以控制台为准。[模型接口文档](https://docs.bigmodel.cn/cn/guide/models/free/glm-4.7-flash)。

在 **接口地址、模型与连接方式** 中选择 **其他 API 直连**，可使用支持 Responses API 及结构化输出的接口。此模式默认 `https://api.openai.com/v1` 和 `gpt-4.1-mini`，更换地址需重新填写对应密钥。不同提供方的密钥分开加密保存。

安装完成后，手机可独立运行；只有主动整理医嘱时需要联网。断开电脑不会影响 AI 直连、本地提醒或记录。需要可调用所选模型的 API 密钥，不能用 ChatGPT 登录密码代替；密钥请只在自己的手机填写。ChatGPT 订阅授权登录尚未接入。

API 密钥保存在手机私有偏好文件的密文中，AES/GCM 密钥由 Android Keystore 管理，并绑定你保存的 API 地址。可在设置中清除已保存的密钥。设备上的凭据仍需要妥善管理，不应向其他人分发带有个人凭据的应用数据。

## 自有 AI 整理服务（可选，支持 Docker Compose）

仅在你希望自己部署中转服务时使用本节。手机默认直连无需创建 `.env`、启动电脑服务或配置 USB 反向连接。在 App 的高级连接设置中选择 **自有服务（可选）** 后，填写服务地址与服务访问口令。

服务端现已支持固定智谱 **GLM-4.7-Flash**，密钥只需配置在服务器。仓库提供 `server/Dockerfile` 和 `server/compose.yaml`，可部署到已有 Docker 服务器，通过现有 HTTPS 反向代理接入。镜像构建会执行服务端测试，默认端口只向服务器本机开放；计划和服药历史仍留在手机。完整步骤见 [服务端部署说明](server/README.md)。

也可用 Node.js 22 在本机联调：

```powershell
Set-Location server
Copy-Item .env.example .env
node -e "console.log(require('node:crypto').randomBytes(32).toString('hex'))"
```

在本机编辑 `server/.env`：保留 `AI_PROVIDER=zhipu`，填写 `ZHIPU_API_KEY`，把刚生成的随机字符串填入 `MEDIARY_ACCESS_TOKEN`（至少 24 字符）。智谱模式固定官方地址和 `glm-4.7-flash`，失败不回退。可选 OpenAI Responses 模式及旧配置兼容规则见部署说明。

```powershell
npm start
```

服务默认只监听 `127.0.0.1:8787`。健康检查：`http://127.0.0.1:8787/health`。

**真机 USB 联调**：开启手机开发者选项和 USB 调试，连接电脑并在手机上授权，然后执行：

```powershell
adb reverse tcp:8787 tcp:8787
```

安卓 App 设置中填写：

- 服务地址：`http://127.0.0.1:8787`
- 服务访问口令：与 `MEDIARY_ACCESS_TOKEN` 相同。这里填写服务口令，不是智谱或其他提供方的 API 密钥。

开发包仅对本机回环地址放行 HTTP。独立部署时应使用 HTTPS 服务地址；不要把未经保护的服务端口直接暴露到公网。断开 USB 后本机反向连接不可用，但已经保存的计划、提醒和记录仍可离线使用。

可选服务不自动启动、不伪造解析结果；手机直连未填写密钥或自有服务未就绪时会清楚提示，手动添加和本地提醒不受影响。

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

安卓与可选服务测试使用可控的上游响应，验证请求格式、凭据边界、原文证据、缺失字段、拒绝与异常；不消耗真实 API 调用。安卓加密存储可在真机运行 `ConnectionSettingsTest` 验证，使用独立临时偏好文件。真实 AI 联调需用户在手机或自有服务器填写对应密钥，通知送达需连接真机验证。

更多验收步骤见 [真机检查](docs/device-checklist.md)。

## GitHub Actions

推送 `main`、提交 PR 或手动运行时，会检查服务端测试、Docker 镜像构建，以及安卓测试、APK 编译和 Lint。`main` 的检查全部通过且仓库变量 `DEPLOY_ENABLED=true` 时，自动通过 SSH 更新自有服务器；PR 不部署。连接凭据使用 GitHub Actions Secrets，提供方 API Key 只保留在服务器的共享 `.env`，不随代码上传。

初次准备、Secrets 配置、发布目录及失败恢复见 [Actions 部署说明](server/README.md#github-actions-自动部署)。

## 实现参考

- [Android 系统闹钟](https://developer.android.com/develop/background-work/services/alarms)
- [OpenAI Responses API](https://developers.openai.com/api/reference/responses/overview)
- [OpenAI Structured Outputs](https://developers.openai.com/api/docs/guides/structured-outputs?api-mode=responses)
