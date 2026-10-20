# Mediary 医嘱整理服务

个人自用的无状态 AI 中转。默认由服务端调用智谱官方 `glm-4.7-flash`；手机只持有服务地址和访问口令。服务不保存医嘱、计划和服药历史，不负责发送用药提醒，不需要数据库。安卓仍可离线提醒和记录。

## Docker Compose 部署

将仓库的整个 `server/` 目录放到服务器的独立目录，例如 `/opt/mediary/server`。以下命令均在该目录执行，需要 Docker Engine 和 Compose 插件。

首次部署准备配置（已有 `.env` 时不要覆盖）：

```sh
cp -n .env.example .env
chmod 600 .env
openssl rand -hex 32
```

在服务器本地编辑 `.env`，填写自己的智谱 Key 和刚生成的随机服务口令：

```dotenv
AI_PROVIDER=zhipu
ZHIPU_API_KEY=填入自己的智谱密钥
MEDIARY_ACCESS_TOKEN=填入生成的64位十六进制随机口令
MEDIARY_PORT=8787
```

上面是占位说明，不能原样运行。Key 只填在服务器，手机填写 `MEDIARY_ACCESS_TOKEN`。服务口令至少 24 字符；建议保留命令生成的随机值，不使用日常密码。不要将 `.env`、展开后的 Compose 配置或容器完整环境输出发到聊天和公开日志。

`zhipu` 模式固定官方地址和 `glm-4.7-flash`，不提供切换型号的环境变量、不自动回退。响应的 `model` 也必须一致。免费与限流政策以智谱平台为准，部署本身不能保证平台未来不调整价格。

```sh
docker compose config --quiet
docker compose up -d --build
docker compose ps
curl --fail http://127.0.0.1:8787/health
```

镜像构建先执行全部服务端测试，再以非 root 用户运行。运行容器只读、限制资源、保留少量滚动启动日志。密钥通过运行时 `.env` 注入，Docker 构建上下文采用白名单，不包含 `.env`。不需要挂载数据库或文件卷。

默认只发布服务器本机的 `127.0.0.1:8787`，不会占用现有应用的 80/443 端口。若 8787 已被占用，改 `.env` 的 `MEDIARY_PORT`，并同步调整反向代理和上述 curl 命令。容器内部固定监听 8787。

`/health` 只检查本地接口及配置：配置完整返回 200，缺少密钥/口令等返回 503，不调用智谱、不验证密钥在平台上是否有效。Docker 重启策略负责进程退出后的恢复，`unhealthy` 本身不触发自动重启。

## 接入现有 HTTPS 入口

推荐给 Mediary 分配一个独立子域名，例如 `mediary.example.com`，在现有反向代理中配置证书后转发给该服务。无需改动其他应用的域名或路由。

如果 Nginx 直接运行在宿主机，将下面的 location 放入该域名已有的 HTTPS server 块。端口与 `MEDIARY_PORT` 对应：

```nginx
location / {
    proxy_pass http://127.0.0.1:8787;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-Proto $scheme;
    proxy_connect_timeout 5s;
    proxy_send_timeout 70s;
    proxy_read_timeout 70s;
    client_max_body_size 64k;
    access_log off;
}
```

不要开启请求体或 Authorization 调试日志。服务自身不记录医嘱正文、访问口令或上游密钥；反向代理同样需要保持这个边界。

如果反向代理也运行在 Docker 容器内，代理里的 `127.0.0.1` 指向代理自身，无法访问本服务。可以把两个容器加入你已有的代理网络，在 `server/` 新建 `compose.proxy.yaml`：

```yaml
services:
  mediary-api:
    networks:
      - default
      - proxy
networks:
  proxy:
    external: true
    name: 替换成已有代理网络名
```

使用 `docker compose -f compose.yaml -f compose.proxy.yaml config --quiet` 检查，再以相同的两个 `-f` 参数执行 `up -d --build`。之后更新、查看状态、停止时也使用相同的两个 `-f` 参数。代理的上游改为 `http://mediary-api:8787`；端口保持容器内部的 8787，无需向公网额外开放。

这是已有代理网络的接入示例，不会替你创建证书或修改现有代理。Caddy、Traefik、面板的具体域名规则沿用你服务器现有方式，超时要覆盖最多约 60 秒的 AI 整理请求。

## 手机连接与验收

1. 打开 **设置 → AI 医嘱整理 → 接口地址、模型与连接方式 → 自有服务（可选）**。
2. 服务地址填 `https://mediary.example.com`（换成实际地址，不加 `/v1/instructions/parse`）；服务访问口令填 `.env` 中的 `MEDIARY_ACCESS_TOKEN`，点击 **保存 AI 设置**。
3. 用明确标记为测试的虚构医嘱，核对发送目的地并同意整理。检查返回草稿与原文一致，不保存成实际用药计划；再到智谱控制台核对调用型号和费用。
4. 断开电脑后复测整理。关闭手机网络后，已有计划和记录仍应可用；锁屏和重启提醒需独立真机验证。

当前服务端只做 AI 中转，尚未实现手机和 PC 的计划/记录同步。开启此模式不会自动上传本机数据库。导入文字会经过自己的服务器并发送到智谱，由你控制的服务端只在本次请求内处理，不写入持久化存储。

## 更新与维护

保存 `.env`，更新源代码后执行 `docker compose up -d --build`。Dockerfile 会先测试，再构建运行镜像。查看启动状态可用 `docker compose ps` 和 `docker compose logs --tail=30 mediary-api`；不要附带导出容器环境。

修改 `.env` 后执行 `docker compose up -d --force-recreate`；仅 `restart` 不会重新注入环境变量。轮换服务口令后，手机也需要更新口令。停止用 `docker compose down`；服务没有云端服药数据，手机本地历史不受此操作影响。

常见检查：

- 健康检查 503：检查所选提供方的 Key、服务口令长度和服务端配置。
- 代理 502/504：检查服务状态、代理所在容器/宿主机网络和超时。
- 整理请求 401：检查手机的服务访问口令；上游 Key 只在服务器填写。
- 整理请求 429：达到本服务每分钟 10 次、同时 2 次的限制，或智谱平台限流。
- 返回型号不一致、拒绝、输出截断或原文校验失败：直接报错，手动核对或稍后重试，不会自动改用其他型号。

## 本机运行与兼容模式

不使用 Docker 时需要 Node.js 22。在本目录配置 `.env` 后执行 `npm start`，默认监听 `127.0.0.1:8787`。`node --test` 使用模拟响应，不会消耗真实 API 额度。

保留 Responses API 兼容方式：显式设置 `AI_PROVIDER=openai`，填写 `OPENAI_API_KEY`、`OPENAI_MODEL` 和可选的 `OPENAI_BASE_URL`（必须是 HTTPS Responses 接口）。升级旧配置时，未设置 `AI_PROVIDER` 但已有 `OPENAI_*` 非空配置，会继续使用旧接口；不同提供方不会共享或自动复用密钥。

接口仍是 `POST /v1/instructions/parse`，Bearer 服务口令鉴权，仅接受 `{ "text": "医嘱原文" }`。模型、地址、上游密钥不接受客户端覆盖。两种提供方都经过同一原文证据校验，再返回安卓已有的草稿格式。所有草稿必须经过人工核对才能启用提醒。

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

私钥不写进工作流或仓库，工作流不会关闭 SSH 主机校验。提供方 Key 不需要存到 GitHub；服务器初次准备时，单独放在 `/opt/mediary/shared/.env`，权限 600。

初次在服务器创建部署目录（按实际部署用户调整）：

```sh
sudo install -d -m 750 -o "$USER" -g "$(id -gn)" /opt/mediary
install -d -m 700 /opt/mediary/shared
```

将按上文填写完成的 `.env` 安全传到 `/opt/mediary/shared/.env`，设置权限 600。不要覆盖已有凭据。宿主机 Nginx 可使用 `deploy/nginx-prefix.conf.example`，在已有 HTTPS 站点内增加 `/mediary/` 路由，手机服务地址对应填 `https://实际主机/mediary`。修改后先 `sudo nginx -t`，通过才重载。已有应用的路由和证书继续沿用。

设置 `DEPLOY_ENABLED=true` 后推送或手动运行工作流。Actions 仅打包服务源码，上传并在 `/opt/mediary/releases/<commit>` 构建、启动 Compose 项目 `mediary`。共享 `.env` 通过软链接读取，不包含在发布包或镜像里。健康检查通过才切换 `/opt/mediary/current`，并记录 `DEPLOYED_SHA`；失败时保留指针，已有版本存在时尝试恢复原版本。失败工作流仍会标红，需要查看恢复结果。

后续版本更新通过 `main` 推送进行。旧发布源码暂不自动删除，也不执行全局 `docker image prune`；按需要人工整理本应用的旧版本。持久化配置始终在 `shared/`，不会随源码发布覆盖。临时停止自动部署可把仓库变量 `DEPLOY_ENABLED` 改为 `false`。

参考：[智谱模型文档](https://docs.bigmodel.cn/cn/guide/models/free/glm-4.7-flash)、[对话补全接口](https://docs.bigmodel.cn/api-reference/模型-api/对话补全)、[Compose 环境变量](https://docs.docker.com/compose/how-tos/environment-variables/set-environment-variables/)、[Compose 服务配置](https://docs.docker.com/reference/compose-file/services/)。
