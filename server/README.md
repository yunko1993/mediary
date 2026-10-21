# Mediary MCP 与草稿服务

Node.js 22 单用户服务，无第三方 npm 运行依赖。默认由 ChatGPT 的模型调用 MCP 工具保存医嘱草稿，安卓主动领取、核对并创建本地计划；这条路径无需模型 API Key。服务不负责手机提醒，不同步服药事件。

## Docker Compose 部署

在服务器独立目录中放置整个 `server/`，在该目录运行：

```sh
cp -n .env.example .env
chmod 600 .env
openssl rand -hex 32
```

在服务器本地编辑 `.env`，使用自己的实际 HTTPS 地址和刚生成的随机口令：

```dotenv
PUBLIC_BASE_URL=https://your-mcp.example.com/mediary
MEDIARY_ACCESS_TOKEN=填入生成的64位十六进制随机口令
MEDIARY_PORT=8787
```

占位值不能原样使用。口令至少 24 位，仅在手机的草稿连接设置及自己的 OAuth 授权页面输入，不放入聊天或 URL。不需要配置任何模型 API Key。

```sh
docker compose config --quiet
docker compose up -d --build
docker compose ps
curl --fail http://127.0.0.1:8787/health
```

构建先执行服务测试，再以非 root 用户运行。容器根文件系统只读，仅 `/data` 挂载可写的 `mediary_mediary-data` 命名数据卷（项目名为 mediary 时）；该卷保存草稿、客户端和令牌摘要，更新源码不会删除它。不要执行 `docker compose down -v`，这会删除数据。医嘱在该私有卷中以明文保存，文件权限 0600，不是端到端加密。应独立备份数据卷和 `.env`，不要上传到 Git。

服务启动时校验配置和数据文件；文件损坏时失败，不自动覆盖为空库。当前仅支持单用户、单进程，不可多实例共享同一 JSON 文件。

端口只发布在 `127.0.0.1:8787`，通过已有 HTTPS 反向代理访问，不向公网开放裸端口。已有服务占用该端口时修改 `MEDIARY_PORT` 并同步修改代理。

## 接入 HTTPS 与 OAuth 发现

如果以 `/mediary` 前缀接入现有宿主机 Nginx，将 [nginx-prefix.conf.example](deploy/nginx-prefix.conf.example) 放进已有 HTTPS server 块，按实际端口调整，并确保 `PUBLIC_BASE_URL` 精确对应外部地址。模板包含带路径的 RFC well-known 路由，以及站点根路径发现的兼容别名；已有其他 OAuth issuer 占用根发现路径时，应保留原配置，使用独立域名隔离。

修改代理后先 `sudo nginx -t`，通过才重载。保持 TLS 校验，不记录访问路径中的授权码、请求体或 Authorization。模板关闭本应用访问日志；不要开启请求调试日志。

如果使用独立域名根路径，可设置 `PUBLIC_BASE_URL=https://mediary.example.com`，使用 [nginx-domain.conf.example](deploy/nginx-domain.conf.example)。先只启用 HTTP 块完成域名验证，证书签发后再启用 HTTPS 块，并配置证书自动续期。该配置保留请求路径，关闭代理缓冲、请求日志和可能包含 OAuth URL 的代理错误日志。代理运行在容器内时，应通过专用共享网络访问 `mediary-api:8787`，不能使用代理容器自身的 127.0.0.1。

出现 `Couldn't discover OAuth settings` 时，先按 [发现故障排查](../docs/mcp.md#oauth-发现故障排查) 验证公网请求和证书；不要通过关闭 OAuth 或 TLS 校验解决。临时隧道示例只用于经确认的诊断，不随主服务启动。

发现或授权卡住时，可临时设置 `MCP_DIAGNOSTICS=1` 并重建 API 容器。默认关闭；启用后，标准输出中的 `mediary_mcp_diagnostic` 事件只包含服务器时间、固定路由标签、固定 HTTP/RPC 阶段、结果类型和状态码，不记录 URL、查询参数、请求头、正文、客户端地址、错误正文或凭据。`tools_list / success` 表示服务器已返回工具目录，`auth_required` 表示工具正在要求登录，`authorize / POST / 303` 与 `token / 200` 可区分表单完成和令牌兑换；这些仍不等于 ChatGPT 已把工具加载进聊天。排查后设回 `0` 并重建 API 容器。不要因此开启代理或隧道的原始访问日志。

## 手机和 ChatGPT 接入

见 [MCP 接入说明](../docs/mcp.md)。主要接口：

| 路径 | 权限与用途 |
| --- | --- |
| `POST /mcp` | 初始化和工具目录可匿名读取；所有工具调用验证 OAuth，未授权返回聊天登录挑战，无匿名医疗数据访问 |
| `GET /.well-known/oauth-protected-resource` | 公开资源元数据 |
| `GET /.well-known/oauth-authorization-server` | 公开授权服务器元数据 |
| `/oauth/register`, `/oauth/authorize`, `/oauth/token`, `/oauth/revoke` | DCR、授权码 + PKCE、刷新及撤销 |
| `GET /v1/drafts` | 手机服务口令，分页领取草稿 |
| `POST /v1/drafts/receipts` | 手机服务口令，领取及核对保存回执 |
| `GET /health` | 本地配置状态，不调用模型、不返回医嘱或凭据 |

手机服务地址是基础地址，不加 `/mcp`；ChatGPT 的 MCP 地址则要加 `/mcp`。创建自定义服务的入口取决于账号和客户端，必须用实际 ChatGPT 账号完成授权和工具调用后才算接入成功。

未完成授权的 OAuth 注册保留 1 小时，容量不足时优先回收未授权申请；已经通过口令授权的连接和旧版已有令牌的连接会保留。刷新请求缩小 scope 时，新访问权限及离线续期能力同步缩小。

## 本机运行

本机 Node.js 22 可运行 `npm start`，测试为 `node --test`。启用 MCP 时，除了 HTTPS `PUBLIC_BASE_URL` 和服务口令，还需设置本机私有的绝对 `DATA_DIR`，不能使用仓库受跟踪目录。默认只监听 127.0.0.1；本机代理仍需提供 HTTPS。

服务只提供 MCP/OAuth 与手机草稿同步，不调用模型 API。旧整理中转接口已移除。健康检查要求 MCP 配置完整。

## 更新与备份

更新源码执行 `docker compose up -d --build`。更改 `.env` 后需 `docker compose up -d --force-recreate`，单独 restart 不重新注入环境。更换访问口令会使旧 OAuth 授权失效，手机要更新口令。变更公开基础地址后重新连接 ChatGPT。

数据卷需要单独备份：先停止本服务，使用受控的服务器本地工具或临时容器归档命名卷，再启动服务，备份限制本机访问。还需保管手机导出的本地计划和服药记录；服务端草稿不能替代手机备份。不要把完整容器环境、Compose 展开配置、数据文件或备份附在公开日志里。

## GitHub Actions 自动部署

`.github/workflows/ci.yml` 对 `main` 推送、PR 和手动运行执行服务端测试、Docker 构建、安卓 JVM 测试、APK 编译及 Lint。只有 `main` 检查全部成功且仓库变量 `DEPLOY_ENABLED=true` 才部署。部署中的运行不会因下一次推送被取消。CI 的调试 APK 仅用于编译检查；开发机安装包与 CI 的默认调试签名可能不同。

在仓库 Actions Secrets 配置：

| 名称 | 内容 |
| --- | --- |
| `DEPLOY_HOST` | 服务器 IP 或域名，不含协议 |
| `DEPLOY_USER` | SSH 登录用户名 |
| `DEPLOY_PORT` | SSH 端口，省略默认 22 |
| `DEPLOY_SSH_KEY` | 对应服务器的部署私钥 |
| `DEPLOY_KNOWN_HOSTS` | 已核对的服务器 SSH 主机公钥记录 |

私钥不写进工作流或仓库，工作流不会关闭 SSH 主机校验。服务访问口令在服务器初次准备时单独放在 `/opt/mediary/shared/.env`，权限 600，不写入 GitHub 或镜像。

初次在服务器创建部署目录（按实际部署用户调整）：

```sh
sudo install -d -m 750 -o "$USER" -g "$(id -gn)" /opt/mediary
install -d -m 700 /opt/mediary/shared
```

将按上文填写完成的 `.env` 安全传到 `/opt/mediary/shared/.env`，设置权限 600。不要覆盖已有凭据。宿主机 Nginx 可使用 `deploy/nginx-prefix.conf.example`，在已有 HTTPS 站点内增加 `/mediary/` 路由，手机服务地址对应填 `https://实际主机/mediary`。修改后先 `sudo nginx -t`，通过才重载。已有应用的路由和证书继续沿用。

设置 `DEPLOY_ENABLED=true` 后推送或手动运行工作流。Actions 仅打包服务源码，上传并在 `/opt/mediary/releases/<commit>` 构建、启动 Compose 项目 `mediary`。共享 `.env` 通过软链接读取，不包含在发布包或镜像里。健康检查通过才切换 `/opt/mediary/current`，并记录 `DEPLOYED_SHA`；失败时保留指针，已有版本存在时尝试恢复原版本。失败工作流仍会标红，需要查看恢复结果。

后续版本更新通过 `main` 推送进行。旧发布源码暂不自动删除，也不执行全局 `docker image prune`；按需要人工整理本应用的旧版本。持久化配置始终在 `shared/`，不会随源码发布覆盖。临时停止自动部署可把仓库变量 `DEPLOY_ENABLED` 改为 `false`。

参考：[Compose 环境变量](https://docs.docker.com/compose/how-tos/environment-variables/set-environment-variables/)、[Compose 服务配置](https://docs.docker.com/reference/compose-file/services/)。
