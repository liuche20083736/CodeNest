# CodeNest — 每人一个专属 OpenCode 沙箱容器

多租户 Web 平台：用户注册/登录后，平台自动为该用户创建一个**专属 Docker 容器**，容器内运行
OpenCode 官方镜像的 `opencode serve` 服务，Web 界面通过后端代理调用容器内的 OpenCode API 进行 AI 编程对话。

> 详细部署/接口文档见 [`opencode-web/README.md`](opencode-web/README.md)

## ✨ 功能

- 🔐 **账号体系**：注册 / 登录（BCrypt + JWT），每个用户一个独立沙箱
- 📦 **自动容器编排**：登录后自动拉取官方镜像并创建容器（随机端口 + 独立 auth），即时可见状态
- 💬 **AI 对话**：多会话管理、流式任务轮询、工具调用展示、任务中断
- 🔑 **每用户自带 Key**：在设置页填入自己的模型 API Key（anthropic / openai / …），自动写入该用户容器内的 opencode 配置并热加载，Key 不回传前端
- 🗄 **数据隔离**：每用户独立命名卷（`/workspace` 工作区 + `/data` 配置/会话数据），删容器不丢数据

## 🏗 架构

```
浏览器 (React + Vite)
   │  Bearer JWT
   ▼
Java 后端 (Spring Boot :8080)
   ├── auth/        注册/登录/JWT 过滤器          /api/auth/*
   ├── docker/      docker-java 容器编排          /api/oc/start | /stop | /container
   ├── opencode/    按用户代理 OpenCode V2 API     /api/oc/session/*
   ▼
每用户容器 (ghcr.io/anomalyco/opencode:2.x)
   └── opencode serve --hostname 0.0.0.0 --port 4096
       （随机宿主端口 + basic auth，用户名固定 "opencode"）
```

## 📁 目录结构

```
.
├── README.en.md / README.md      # 本文件
└── opencode-web/
    ├── server/                   # Spring Boot 后端 (Java 17)
    │   └── src/main/java/com/opencodeweb/{auth,docker,opencode,config,common}
    ├── web/                      # React + Vite 前端
    │   └── src/pages/{Login,Chat,Settings}.jsx
    └── README.md                 # 详细文档
```

## 🚀 快速开始

前置条件：Docker Desktop / Docker Engine、Java 17+、Node 18+

```bash
# 终端 1 —— 后端
cd opencode-web/server
mvn spring-boot:run

# 终端 2 —— 前端
cd opencode-web/web
npm install
npm run dev     # → http://localhost:5173
```

使用流程：注册登录 → 等待专属容器创建并就绪 → 「模型设置」填 Provider/模型/API Key → 对话。

## ⚙️ 关键配置（`opencode-web/server/src/main/resources/application.yml`）

| 配置 | 默认 | 说明 |
|---|---|---|
| `ocw.image` | `ghcr.io/anomalyco/opencode:2.1.4` | 沙箱镜像（须 V2 版本） |
| `ocw.docker-host` | 自动探测 | `npipe://`（Docker Desktop）/ `tcp://host:2375` / unix socket |
| `ocw.connect-mode` | `host-port` | `host-port` 或 `container-dns`（后端也在 Docker 网络内时） |
| `ocw.jwt-secret` | 随机 | 固定后重启登录态不失效 |

## 🧠 实现要点（踩坑记录）

1. **镜像无 `EXPOSE`**：`--publish-all` 不会发布任何端口，必须显式 `withExposedPorts(4096)` + 空绑定由 Docker 分配随机宿主端口
2. **V2 认证**：`opencode serve` 忽略 `OPENCODE_SERVER_USERNAME`，basic auth 用户名硬编码为 `opencode`（issue #45856），密码来自 `OPENCODE_SERVER_PASSWORD`
3. **V2 API 响应包装**：`{"data": ..., "cursor": ...}` 包裹层由后端统一解包
4. **容器创建即落库**：避免就绪探测超时重试时产生重复容器
5. **消息 schema**（V2）：用户消息 `{type:"user", text}`，助手 `{type:"assistant", content:[...]}`，以 `type:"idle"` 结尾

## 🔒 安全说明

- 容器内 opencode 服务启用 basic auth（随机密码，仅存于服务端数据库）
- 模型 API Key 由服务端写入用户容器配置，永不回传前端
- 所有 OpenCode API 请求经由后端按用户代理，浏览器不直连容器
