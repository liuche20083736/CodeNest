# CodeNest — 每个用户一个专属 OpenCode 沙箱容器

一个多租户 Web 平台：用户注册/登录后，后端自动为该用户创建一个专属 Docker 容器，容器内运行
`opencode serve`（官方镜像），Web 端通过后端代理调用容器内的 OpenCode HTTP API 进行对话。

## 架构

```
浏览器 (React + Vite)
   │  Bearer JWT
   ▼
Java 后端 (Spring Boot :8080)
   ├── 账号体系：H2 文件库 + JWT + BCrypt             /api/auth/*
   ├── 容器编排：docker-java                          /api/oc/start | /stop | /container
   │      每用户 1 个容器 + 2 个命名卷 (workspace / data)
   └── API 代理：按用户路由到其容器                     /api/oc/session/* (透传 OpenCode V2 API)
   ▼
每个用户的容器 (ghcr.io/anomalyco/opencode:2.x)
   └── opencode serve --hostname 0.0.0.0 --port 4096
       （随机宿主机端口 + 容器独立 basic auth 密码）
```

隔离设计：

- 每用户独立容器（容器名 `cn-<username>`，label `dev.codenest.owner`）
- 命名卷 `cn-ws-<id>`（/workspace）与 `cn-data-<id>`（/data：XDG 配置、会话数据），
  删容器不丢工作区
- 容器内 opencode 服务启用 basic auth（随机密码，仅存于服务端数据库）
- 模型 API Key 由用户在设置页填写，后端写入该用户容器内的
  `/data/config/opencode/opencode.json` 并热加载

## 目录结构

```
opencode-web/
├── server/            # Spring Boot 后端 (codenest-server)
│   └── src/main/java/com/opencodeweb/
│       ├── auth/      # 注册/登录/JWT
│       ├── docker/    # 容器生命周期 (ContainerService)
│       ├── opencode/  # OpenCode API 代理 + 容器内配置写入
│       └── config/    # DockerClient 装配
└── web/               # React + Vite 前端（登录页 / 对话页 / 模型设置页）
```

## 运行前提

1. **Docker Desktop / Docker Engine 可用**
   - Windows：Docker Desktop 运行中即可（后端默认走 named pipe）；
     或设置环境变量 `DOCKER_HOST`
2. **Java 17+**（后端）
3. **Node 18+**（前端开发）

## 启动步骤

```bash
# 终端 1 —— 后端
cd server
mvn spring-boot:run

# 终端 2 —— 前端
cd web
npm install
npm run dev      # http://localhost:5173 （已配置代理到 :8080）
```

使用流程：

1. 打开 http://localhost:5173 ，注册并登录
2. 首次进入对话页自动执行 `POST /api/oc/start`：拉取镜像 → 创建容器 → 等 opencode 就绪
3. 进入「模型设置」填入 Provider（如 `anthropic`）、模型（如 `anthropic/claude-sonnet-4-5`）、
   API Key → 保存后自动写入容器配置
4. 回到对话页直接对话

## 主要 API（后端，均需 Bearer JWT）

| 方法 | 路径 | 说明 |
|---|---|---|
| POST | `/api/auth/register` `/login` | 注册 / 登录 |
| GET | `/api/oc/status` | 容器状态（exists/running/ready/port） |
| POST | `/api/oc/start` | 创建/启动容器并等待 OpenCode 就绪（首次含镜像拉取） |
| POST | `/api/oc/stop` / DELETE `/api/oc/container` | 停止 / 删除容器（卷保留） |
| PUT/GET | `/api/oc/settings` | 读写模型凭据（Key 不回传） |
| GET | `/api/oc/models` | 容器内可用模型列表 |
| POST | `/api/oc/session` | 在容器内创建会话（固定 `/workspace`） |
| GET/DELETE | `/api/oc/session[/{id}]` | 会话列表 / 详情 / 删除 |
| GET | `/api/oc/session/{id}/message` | 消息列表（前端轮询渲染） |
| POST | `/api/oc/session/{id}/prompt` `/interrupt` | 发送提示 / 中断 |
| GET | `/api/oc/session/active` | 忙碌会话检测 |

## 配置项（application.yml / 环境变量）

| 配置 | 默认 | 说明 |
|---|---|---|
| `ocw.image` (`OCW_OPENCODE_IMAGE`) | `ghcr.io/anomalyco/opencode:2.1.4` | 沙箱镜像，须 ≥ V2 版本 |
| `ocw.docker-host` (`OCW_DOCKER_HOST` / `DOCKER_HOST`) | 自动探测 | Docker 守护进程地址：`npipe:////./pipe/docker_engine`、`tcp://localhost:2375`、`unix:///var/run/docker.sock` |
| `ocw.connect-mode` (`OCW_CONNECT_MODE`) | `host-port` | 后端在宿主机用 `127.0.0.1:随机端口`；若后端本身跑在容器里与沙箱同一 Docker 网络，可改 `container-dns` |
| `ocw.ready-timeout-seconds` | `120` | 等待 opencode 就绪的超时 |
| `ocw.jwt-secret` (`OCW_JWT_SECRET`) | 随机 | 固定后重启后端登录态不失效 |

## 故障排查

**报错「无法连接 Docker 守护进程」**：说明应用没找到 Docker 引擎，按顺序检查：

1. Windows：是否安装并**启动**了 Docker Desktop？（托盘图标为绿色/稳定状态）
2. Docker Desktop 装好后，named pipe 会自动被识别（`dockerDesktopLinuxEngine` / `docker_engine`）
3. 若守护进程开了 TCP（Docker Desktop 设置 → General → "Expose daemon on tcp://localhost:2375"），
   或在 WSL/远程机器上：`OCW_DOCKER_HOST=tcp://localhost:2375 mvn spring-boot:run`
4. `GET /api/oc/status` 返回 `dockerAvailable:false` 即守护进程不可达，先解决环境再点「开始」

## 说明与注意事项

- **API Key 安全**：Key 存于平台数据库（H2 文件库）并写入用户容器配置，浏览器端不保存；
  `/api/oc/settings` 永不回传 Key 明文
- **数据持久化**：用户工作区/会话保存在命名卷；`删除容器` 只删容器本身；
  直接 `docker volume rm` 才会真正删除数据
- **首次启动较慢**：需拉取约 160MB 的官方镜像，属正常现象
- **events 流**：当前用消息轮询（1.5s）实现实时性，足够单人/小规模使用；
  如接多人可改为 SSE 透传 `/api/event`
- **容器迁移**：同一账号换浏览器/设备登录后复用同一容器
