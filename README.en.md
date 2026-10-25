# CodeNest — A dedicated OpenCode sandbox container for every user

A multi-tenant web platform: after a user signs up / signs in, the backend automatically creates a dedicated Docker container for that user running `opencode serve` (official image). The web frontend talks to the OpenCode HTTP API inside the container through a backend proxy.

## Architecture

```
Browser (React + Vite)
   │  Bearer JWT
   ▼
Java backend (Spring Boot :8080)
   ├── Accounts: H2 file DB + JWT + BCrypt            /api/auth/*
   ├── Container orchestration: docker-java           /api/oc/start | /stop | /container
   │      1 container + 2 named volumes per user (workspace / data)
   └── API proxy: routes to each user's container     /api/oc/session/* (OpenCode V2 API passthrough)
   ▼
Per-user container (ghcr.io/anomalyco/opencode:2.x)
   └── opencode serve --hostname 0.0.0.0 --port 4096
       (random host port + per-container basic auth password)
```

Isolation design:

- One container per user (container name `cn-<username>`, label `dev.codenest.owner`)
- Named volumes `cn-ws-<id>` (/workspace) and `cn-data-<id>` (/data: XDG config, session data);
  deleting the container keeps the workspace
- The in-container opencode service uses basic auth (username fixed to `opencode`,
  random password stored only server-side)
- The model API key is entered by the user on the settings page; the backend writes it to the
  user's `/data/config/opencode/opencode.json` inside the container and hot-reloads

## Project layout

```
CodeNest/
├── server/            # Spring Boot backend (codenest-server)
│   └── src/main/java/com/opencodeweb/
│       ├── auth/      # register / login / JWT
│       ├── docker/    # container lifecycle (ContainerService)
│       ├── opencode/  # OpenCode API proxy + in-container config writer
│       ├── config/    # DockerClient wiring + configuration properties
│       └── common/    # global exception handling
└── web/               # React + Vite frontend (login / chat / model settings)
```

## Prerequisites

1. **Docker Desktop / Docker Engine available**
   - Windows: Docker Desktop running is enough (the backend uses the named pipe by default);
     or set the `DOCKER_HOST` environment variable
2. **Java 17+** (backend)
3. **Node 18+** (frontend development)

## Getting started

```bash
# Terminal 1 — backend
cd server
mvn spring-boot:run

# Terminal 2 — frontend
cd web
npm install
npm run dev      # http://localhost:5173 (proxy to :8080 configured)
```

Usage flow:

1. Open http://localhost:5173 , register and sign in
2. Entering the chat page automatically runs `POST /api/oc/start`: pull image → create container → wait for opencode readiness
3. Go to "Model settings", fill in a Provider (e.g. `anthropic`), a model
   (e.g. `anthropic/claude-sonnet-4-5`), and the API key → saved config is written into the container
4. Return to the chat page and start chatting

## Main API (backend, all require Bearer JWT)

| Method | Path | Description |
|---|---|---|
| POST | `/api/auth/register` `/login` | Register / sign in |
| GET | `/api/auth/me` | Current signed-in user |
| GET | `/api/oc/status` | Container status (dockerAvailable/exists/running/ready/hostPort + model credential info) |
| POST | `/api/oc/start` | Create/start the container and wait for OpenCode readiness (image pull on first run) |
| POST | `/api/oc/stop` / DELETE `/api/oc/container` | Stop / delete the container (volumes kept) |
| PUT/GET | `/api/oc/settings` | Read/write model credentials (key never echoed back; supports custom Base URL and npm provider packages) |
| GET | `/api/oc/models` | Models available inside the container |
| POST | `/api/oc/session` | Create a session inside the container (fixed `/workspace`) |
| GET/DELETE | `/api/oc/session[/{id}]` | Session list / detail / delete |
| GET | `/api/oc/session/{id}/message` | Message list (rendered via frontend polling) |
| POST | `/api/oc/session/{id}/prompt` `/interrupt` | Send prompt / interrupt |
| GET | `/api/oc/session/active` | Busy-session detection |

## Configuration (application.yml / environment variables)

| Setting | Default | Description |
|---|---|---|
| `ocw.image` (`OCW_OPENCODE_IMAGE`) | `ghcr.io/anomalyco/opencode:2.1.4` | Sandbox image, must be ≥ V2 |
| `ocw.docker-host` (`OCW_DOCKER_HOST` / `DOCKER_HOST`) | auto-detect | Docker daemon address: `npipe:////./pipe/docker_engine`, `tcp://localhost:2375`, `unix:///var/run/docker.sock` |
| `ocw.connect-mode` (`OCW_CONNECT_MODE`) | `host-port` | Backend uses `127.0.0.1:<random port>` on the host; set to `container-dns` if the backend itself runs in a container on the same Docker network as the sandbox |
| `ocw.ready-timeout-seconds` (`OCW_READY_TIMEOUT`) | `120` | Timeout for waiting until opencode is ready |
| `ocw.jwt-secret` (`OCW_JWT_SECRET`) | random | Set it so sign-ins survive backend restarts |

## Troubleshooting

**"Cannot connect to the Docker daemon"**: the application could not find Docker Engine. Check in order:

1. Windows: is Docker Desktop installed and **running**? (green/steady tray icon)
2. Once Docker Desktop is up, the named pipe is detected automatically
   (`dockerDesktopLinuxEngine` / `docker_engine`)
3. If the daemon has TCP enabled (Docker Desktop → Settings → General → "Expose daemon on tcp://localhost:2375"),
   or the daemon is on WSL / a remote machine: `OCW_DOCKER_HOST=tcp://localhost:2375 mvn spring-boot:run`
4. `GET /api/oc/status` returning `dockerAvailable:false` means the daemon is unreachable; fix the environment before clicking "Start"

## Notes

- **API key security**: keys are stored in the platform database (H2 file DB) and written to the
  user's container config; the browser never keeps them, and `/api/oc/settings` never returns the plaintext key
- **Data persistence**: user workspaces/sessions live in named volumes; deleting the container only
  removes the container itself — the data is only truly deleted by `docker volume rm`
- **Slow first start**: pulling the ~160MB official image the first time is normal
- **Events stream**: realtime updates are currently implemented with message polling (1.5s), fine for
  single users / small scale; for many users switch to SSE passthrough of `/api/event`
- **Container affinity**: signing in from another browser/device reuses the same container
