# ChatGPT → Mediary 草稿收件箱

在 ChatGPT 整理医嘱，通过 MCP 保存草稿，再在安卓端领取、核对并保存本地计划。这条路径不调用模型 API，不需要 OpenAI 或智谱 Key；ChatGPT 的模型和额度由你的订阅及账号决定。

## 接入

1. 按 [服务部署说明](../server/README.md) 启动服务，配置 HTTPS `PUBLIC_BASE_URL`、强随机 `MEDIARY_ACCESS_TOKEN` 和持久化数据卷。
2. 在安卓 **设置 → ChatGPT 草稿连接** 填入服务基础地址（例如 `https://你的主机/mediary`）和服务访问口令。口令由 Android Keystore 加密，不包含在 APK 或导出文件中。
3. 在 ChatGPT 网页的 **Plugins → ＋ → Add custom MCP server** 添加服务，地址是基础地址后加 `/mcp`，认证选 **OAuth**。服务支持动态客户端注册（DCR），无需事先填写客户端 ID 或 Secret。
4. 在你自己服务的授权页面输入服务访问口令，允许连接读取或创建草稿。不要把口令放进聊天、URL、截图或 Git。
5. 安装该连接后，在对话中用 `@Mediary` 选择它，发送已经去除无关身份信息的医嘱原文，例如“请按原文保存成 Mediary 待核对草稿；未知字段留空”。
6. 打开安卓右上角的 **ChatGPT 草稿收件箱**，同步、逐项核对药名和剂量，自己设置日期与提醒时间，再保存计划。

ChatGPT 的自定义服务入口受账号、工作区权限、客户端版本及开放情况影响。这里实现的是标准 MCP 服务；部署通过不等于某个 Plus 账号已获得创建入口。如果当前账号没有自定义入口，不要关闭鉴权或把服务口令交给聊天模型来绕过；保留本地手工添加及可选 API 导入。支持安装自定义 MCP 的其他客户端也可接入，但本版本 OAuth 仅允许 ChatGPT 的 HTTPS 回调。

当前官方入口说明：[添加自定义 MCP](https://developers.openai.com/api/docs/guides/custom-mcp-server)、[OAuth 要求](https://developers.openai.com/plugins/build/auth)、[连接与测试](https://developers.openai.com/plugins/deploy/connect-chatgpt)。账号与手机客户端可用性需实际验证，不在应用中承诺所有套餐都可用。

## OAuth 发现故障排查

`Couldn't discover OAuth settings` 是发现阶段的通用错误，不足以判断是套餐、地址、证书还是元数据问题。先在公网验证 MCP 未授权请求返回 `401` 和 `WWW-Authenticate`，资源元数据中的 `authorization_servers` 指向授权服务器，再验证授权服务器元数据返回 JSON、issuer 与公开基础地址完全一致。路径前缀部署还需检查站点根路径及带路径的 well-known 入口，参见 Nginx 模板。

标准 SDK 自动发现通过，只能证明该测试环境可以访问和解析。应结合实际 ChatGPT 尝试检查请求是否到达；诊断只记录时间、方法、无查询参数的固定公开路径和状态码，不能保存请求头、请求体、完整 OAuth URL 或客户端地址。局部日志没有请求不能单独证明平台不支持 IP 地址。TLS 必须验证完整信任链和主机名，不能使用自签名证书或跳过校验。

没有自有域名时，可以用免费 DNS 名称进行兼容性验证，但证书仍需验证该名称的控制权。公网验证若被云服务商重定向到拦截页，本机访问正常也无法完成 HTTP-01；应先处理域名入口限制，不能把失败的名称配置进手机。

必要时可使用 [临时隧道 Compose 示例](../server/deploy/compose.quick-tunnel.yaml.example) 对照测试。它不需要 Cloudflare 账号或域名，但会由 Cloudflare 终止公网 TLS：启用真实 OAuth 和草稿流量后，访问口令、OAuth 凭据和医嘱会经过该第三方中转，不能当成仅提供 DNS。应先确认接受这条数据路径，保留原服务与数据卷，并停用原始隧道日志。

Quick Tunnel 的域名随进程重新创建而变化，没有可用性保证，也不支持 SSE；Mediary 的无会话 JSON 传输不使用 SSE。它适合排查，不能替代稳定入口。更换地址时应更新 `PUBLIC_BASE_URL` 并重建应用容器、更新手机连接、重新授权 ChatGPT；仅重启容器不能重新注入环境变量。参见 [Cloudflare 官方说明](https://developers.cloudflare.com/tunnel/get-started/quick-tunnels/)。

## 工具与边界

| 工具 | 用途 |
| --- | --- |
| `create_medication_draft` | 保存一种药物的待核对草稿，必须带原文、逐字段引文和幂等 requestId |
| `list_medication_drafts` | 按序号分页读取草稿和手机回执 |
| `get_medication_draft` | 按 ID 读取单条草稿 |
| `get_sync_status` | 查看最近一次手机主动同步时间和待核对数量 |

- 每个非空用药字段必须出现在对应的连续原文引文中；未知项留空。每日三次不会自动变成每八小时。
- 模型只能创建草稿，不能确认草稿、启用提醒、修改剂量或处理漏服；当前也不读取或登记手机服药历史。
- 手机保存计划与本地去重标记在同一 SQLite 事务提交，回执可在断网后补发。重试使用相同 requestId 不会新建第二条草稿；相同 ID 携带不同内容会被拒绝。
- 手机同步按服务地址隔离；切换服务后，不向新服务发送旧服务的回执。旧草稿保留本地缓存。
- 服务器的 `reviewed_on_device` 只表示手机回报已核对保存，不能证明通知权限、闹钟实际启用或通知送达。`reminderStatus` 始终标明服务器不知道手机当前提醒状态。
- 已确认的本地计划在断网时继续排程。新增草稿必须主动同步、核对保存，不依赖网络推送唤醒。

## 授权与存储

MCP 使用无会话 Streamable HTTP，可协商 `2025-03-26` / `2025-06-18`，POST 返回 JSON，GET 返回 405。支持 `initialize`、`ping`、`tools/list`、`tools/call` 与通知，不提供旧版 SSE 长连接。所有 MCP 数据请求必须携带 OAuth 令牌；手机 REST 只接受服务访问口令，OAuth 令牌不能伪造手机确认。

OAuth 支持 DCR、授权码 + S256 PKCE、resource audience、scope、一次性授权码、刷新令牌轮换与撤销。access token 默认 1 小时，申请 `offline_access` 后 refresh token 最长 30 天。授权页带 CSRF、短期一次性表单及防嵌套策略。当前只允许 `https://chatgpt.com/connector_platform_oauth_redirect` 和 `https://chatgpt.com/connector/oauth/<callback_id>` 两类精确格式回调。客户端注册只接受 public client (`none`)，不支持 CIMD 或第三方登录账号。

持久化文件只保存 OAuth 令牌摘要，草稿医嘱以明文保存在私有 Docker 数据卷中（文件权限 0600），并非端到端加密。不要把数据卷、备份或手机数据库上传到仓库。服务启动时如果数据损坏会失败，不会悄悄建立空库覆盖历史。当前是单进程、单用户实现，最多 1000 条草稿、500 个客户端和 500 个有效授权，数据文件上限 32 MiB；尚无自动清理或多实例共享数据库。

停用连接可在 ChatGPT 断开，并调用标准 OAuth 撤销接口；更换服务访问口令会使旧 OAuth 授权失效，手机也需要更新口令。更换公开基础地址后需要重新连接授权。

## 验证

服务：在 `server/` 运行 `node --test`。覆盖原文证据、重复请求、持久化、OAuth scopes、PKCE 参数、刷新、撤销、授权码重放、来源校验、手机回执与权限隔离。

安卓：运行 `:core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`。真机运行 `ConnectionSettingsTest` 与 `InboxRepositoryTest`，验证凭据加密及地址绑定、旧数据库迁移、不自动建计划、离线保存与待补发回执。测试使用独立数据库，不添加真实提醒。

最后仍需在实际 ChatGPT 账号中完成一次授权和草稿调用，并在手机核对；只有这一步通过，才能声称该账号已接入。锁屏、断网、重启等提醒验收继续按 [真机清单](device-checklist.md) 操作。
