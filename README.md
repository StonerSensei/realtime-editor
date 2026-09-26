# CollabIDE

A real-time collaborative code editor — think a lightweight, self-hostable Google Docs for code. Multiple people edit the same project simultaneously with conflict-free merging and live cursors, chat in the room, and run their code in sandboxed containers, all in the browser.

Built with **Spring Boot 3.5 (Java 17)**, **MongoDB**, **Redis**, **Yjs (CRDT)**, and **Docker**.

---

## Table of Contents

- [Features](#features)
- [Architecture](#architecture)
- [How real-time collaboration works](#how-real-time-collaboration-works-crdt)
- [How code execution works](#how-code-execution-works-sandboxed)
- [Authentication](#authentication)
- [Tech stack](#tech-stack)
- [Getting started](#getting-started)
- [Configuration](#configuration)
- [API & docs](#api--docs)
- [Testing & CI](#testing--ci)
- [Project structure](#project-structure)
- [Notable engineering decisions](#notable-engineering-decisions)
- [Known limitations & future work](#known-limitations--future-work)

---

## Features

- **Conflict-free real-time editing** — multiple users edit simultaneously; changes merge without conflicts (CRDT), with live remote cursors and presence.
- **Multi-file projects** — a file tree per room; create / rename / delete / switch files, all synced live.
- **Sandboxed code execution** — run JavaScript, Python, C, C++, and Java in ephemeral, resource-limited Docker containers with stdin support.
- **Rooms & roles** — create/join rooms; owners manage members with `OWNER` / `EDITOR` / `VIEWER` roles, live promotion/demotion, and kick.
- **In-room chat** and a **live participant list**.
- **Version history** — auto-saved project snapshots you can browse and restore.
- **Authentication** — JWT access tokens + revocable refresh tokens with silent renewal; logout immediately invalidates the access token via a Redis-backed blacklist.
- **Multi-instance ready** — Redis Pub/Sub fans out edits and chat across app instances; the app boots and runs normally without Redis (single-instance, in-memory fallback) and re-syncs automatically when Redis returns.
- **Production concerns** — distributed rate limiting (Redis primary, in-memory fallback), health/metrics endpoints, interactive API docs, and a containerized deployment with CI/CD.

---

## Architecture

```mermaid
graph TB
    subgraph Browser["Browser (SPA)"]
        UI["CodeMirror editor + file tree + chat"]
        YC["Yjs doc + y-codemirror binding"]
        AUTH["auth.js (token mgmt + silent refresh)"]
    end

    subgraph Server["Spring Boot application"]
        SEC["Security filter chain<br/>(JWT filter + rate limiter)"]
        REST["REST controllers<br/>Auth / Rooms / Snapshots / Chat / Exec"]
        WS["WebSocket handlers<br/>Yjs relay / Chat / Exec"]
        SVC["Service layer"]
        REPO["Repositories (Spring Data)"]
    end

    DB[("MongoDB<br/>users, rooms, snapshots,<br/>chat_messages, refresh_tokens")]
    REDIS[("Redis<br/>rate limits, token blacklist,<br/>Pub/Sub fanout, room presence")]
    DOCKER["Host Docker daemon<br/>(ephemeral run-code containers)"]

    UI --> YC
    YC -- "binary CRDT frames (WSS)" --> WS
    UI -- "REST + JWT" --> SEC
    AUTH -- "REST + JWT" --> SEC
    SEC --> REST --> SVC --> REPO --> DB
    WS --> SVC
    WS -- "run code" --> SVC
    SVC -- "docker run (sandbox)" --> DOCKER
    SVC --> REDIS
    WS -- "Pub/Sub fanout" --> REDIS
```

**Layered backend** — controllers stay thin, business logic lives in services, and Spring Data repositories handle persistence. DTOs isolate the API contract from domain models, and a global `@RestControllerAdvice` produces consistent error responses. Redis is used for cross-cutting concerns (rate limiting, token revocation, cross-instance messaging) and is optional: every Redis-backed feature falls back to an in-memory implementation when Redis is unavailable.

---

## How real-time collaboration works (CRDT)

Naive collaborative editors broadcast the whole document on every keystroke, so simultaneous edits overwrite each other. CollabIDE instead uses **Yjs**, a CRDT (Conflict-free Replicated Data Type): each edit is a small, commutative operation that merges deterministically on every client.

The project is one Yjs document — a `Y.Map` of files, where each file holds a `Y.Text` of its content. The Spring Boot backend is a **dumb binary relay**: it forwards CRDT update frames between peers in a room and never has to understand the document itself. This keeps the server simple and the merge logic correct.

```mermaid
sequenceDiagram
    participant A as User A
    participant S as Yjs Relay (Spring)
    participant B as User B

    A->>S: connect /yjs/{room}?token=JWT
    S-->>A: PRESENCE (are you first?) + ROSTER
    A->>S: sync request (state vector)
    B->>S: local edit -> CRDT update
    S->>A: relay update
    A->>A: merge (conflict-free) + render remote cursor
    Note over S: VIEWER role edits are dropped server-side
```

- **Presence & cursors** use the Yjs *awareness* protocol; the server also broadcasts an authoritative **roster** so the online count is exact.
- **Access control is enforced at the relay**: the handshake is JWT-authenticated, and document-mutating frames from `VIEWER`s are dropped — read-only isn't just a client-side illusion.
- **Seeding**: the first peer to join a room *across all instances* loads the latest saved snapshot into the CRDT; everyone else syncs from peers. A Redis-backed presence tracker prevents two instances from both seeding the same room.

---

## How code execution works (sandboxed)

Running untrusted code safely is the other hard problem. Each run happens in a throwaway Docker container with tight limits:

```mermaid
graph LR
    REQ["Run request<br/>(language, code, stdin)"] --> SVC["CodeExecutionService"]
    SVC --> TMP["write source + stdin<br/>to work dir"]
    TMP --> RUN["docker run --rm --network none<br/>--memory 256m --cpus 0.5 --pids-limit 128<br/>-v workdir:/code:ro"]
    RUN --> OUT["capture stdout/stderr<br/>+ wall-clock timeout"]
    OUT --> CLEAN["force-kill + delete temp dir"]
```

- **No network**, capped memory/CPU/PIDs, wall-clock timeout, output truncation, auto-removed container.
- When the app itself runs in a container, it drives the **host** Docker daemon via the mounted socket (Docker-out-of-Docker); the sandbox work directory is bind-mounted at an identical path on host and container so the `-v` mount resolves correctly.

---

## Authentication

```mermaid
sequenceDiagram
    participant C as Client
    participant S as Server
    C->>S: POST /api/auth/login
    S-->>C: access token (30m JWT, carries jti) + refresh token (7d, in DB)
    C->>S: API call with expired access token
    S-->>C: 401
    C->>S: POST /api/auth/refresh (refresh token)
    S-->>C: new access + rotated refresh token
    C->>S: retry original call -> 200
    C->>S: POST /api/auth/logout (refresh + access token)
    S->>S: revoke refresh token + blacklist jti in Redis
    C->>S: API call with the logged-out access token
    S-->>C: 401 (jti is blacklisted)
```

- **Access tokens** are short-lived stateless JWTs, each carrying a unique `jti` (JWT ID); **refresh tokens** are long-lived, stored in MongoDB (so they're revocable) with a TTL index for automatic cleanup and rotation on every refresh.
- **Logout invalidates both tokens immediately**: the refresh token is revoked in MongoDB, and the access token's `jti` is written to a Redis blacklist with a TTL equal to its remaining lifetime. The blacklist is checked on every authenticated request, so a logged-out token is rejected within milliseconds, not after 30 minutes. A bounded in-memory fallback keeps revocation working when Redis is down.
- The client refreshes **silently** on a 401 and via a background timer, so long-lived WebSocket sessions keep working.
- Auth endpoints are **rate-limited** per IP via a Redis-backed fixed-window counter (with an in-memory Bucket4j fallback) to throttle brute-force attempts.

---

## Tech stack

| Layer | Technology |
|-------|-----------|
| Backend | Spring Boot 3.5, Java 17, Spring Security, Spring WebSocket, Spring Data MongoDB, Spring Data Redis |
| Database | MongoDB 7, Redis 7 (or Valkey) |
| Real-time | Yjs (CRDT), y-codemirror, y-protocols (awareness) |
| Editor | CodeMirror 5 |
| Auth | JWT (jjwt) + refresh tokens, BCrypt |
| Code execution | Docker (ephemeral sandboxes) |
| Rate limiting | Redis fixed-window + Bucket4j (fallback) |
| Observability | Spring Actuator + Micrometer/Prometheus |
| API docs | springdoc-openapi (Swagger UI) |
| Testing | JUnit 5, Mockito, Spring Boot Test, Testcontainers |
| Delivery | Docker, Docker Compose, GitHub Actions |

---

## Getting started

### Prerequisites

- **Docker** and the **Docker Compose** plugin (for Option A) — that's all you need.
- For local (non-Docker) runs instead: **JDK 17**, a running **MongoDB**, **Redis** (or Valkey), and **Docker** (used only to run code sandboxes). Redis is optional: the app falls back to in-memory for all Redis features, but cross-instance fanout requires it.

> First time you run someone's code, the language image is pulled automatically if missing (slow on the very first run). Pre-pulling them (step 2 below) avoids that wait.

### Option A — Docker Compose (recommended)

```bash
# 1. Configure secrets
cp .env.example .env      # then set a strong JWT_SECRET (openssl rand -base64 48)

# 2. Pre-pull the language images onto the host (sandboxes run on the host daemon)
docker pull python:3.11-alpine node:20-alpine gcc:13 eclipse-temurin:21-jdk

# 3. Build and run the whole stack
docker compose up --build -d

# App: http://localhost:8080
```

### Option B — Local (run the jar)

```bash
# MongoDB must be running (e.g. docker run -d --name mongodb -p 27017:27017 mongo:7)
# Redis is optional  (e.g. docker run -d --name redis -p 6379:6379 redis:7-alpine)
./mvnw clean package -DskipTests
java -jar target/realtime-editor-0.0.1-SNAPSHOT.jar
```

> Full command reference lives in [`COMMANDS.md`](COMMANDS.md).

---

## Configuration

All configuration is environment-driven (safe defaults for local dev):

| Variable | Default | Description |
|----------|---------|-------------|
| `MONGODB_URI` | `mongodb://localhost:27017/editor` | MongoDB connection string |
| `REDIS_HOST` | `localhost` | Redis/Valkey hostname |
| `REDIS_PORT` | `6379` | Redis/Valkey port |
| `JWT_SECRET` | dev-only fallback | JWT signing secret (**set in prod**, ≥ 32 chars) |
| `JWT_ACCESS_EXPIRATION` | `1800000` | Access token lifetime (ms) |
| `JWT_REFRESH_EXPIRATION` | `604800000` | Refresh token lifetime (ms) |
| `EXEC_TIMEOUT` / `EXEC_MEMORY` / `EXEC_CPUS` | `15` / `256m` / `0.5` | Code-execution sandbox limits |
| `EXEC_WORK_DIR` | JVM temp dir | Sandbox work dir (host-shared path in Docker) |
| `AUTH_RATE_CAPACITY` / `AUTH_RATE_REFILL_MINUTES` | `10` / `1` | Auth rate limit |

---

## API & docs

Interactive API documentation (Swagger UI) is available at runtime:

- **Swagger UI:** http://localhost:8080/swagger-ui.html
- **OpenAPI spec:** http://localhost:8080/v3/api-docs
- **Health:** http://localhost:8080/actuator/health
- **Metrics (Prometheus):** http://localhost:8080/actuator/prometheus *(auth required)*

| Area | Endpoints |
|------|-----------|
| Auth | `POST /api/auth/register \| /login \| /refresh \| /logout` |
| Rooms | `POST /api/rooms/host`, `POST /api/rooms/{id}/join`, `GET /api/rooms/{id}` |
| Roles | `PUT /api/rooms/{id}/members/{user}/role`, `DELETE /api/rooms/{id}/members/{user}` |
| Snapshots | `POST /api/snapshots/save`, `GET /api/snapshots/{room}`, `/latest/{room}`, `/get/{id}` |
| Chat | `GET /api/chat/{room}` |
| WebSocket | `/yjs/{room}` (CRDT), `/ws/chat/{room}`, `/ws/exec` |

---

## Testing & CI

```bash
./mvnw test    # unit tests (no external deps) + integration tests (need MongoDB)
```

- **Unit tests** cover the service layer, the Redis broker, the presence tracker, and both WebSocket handlers with Mockito.
- **Integration tests** exercise controllers end-to-end (HTTP → controller → service → MongoDB) and, when Redis is available, cross-instance fanout with two full relay stacks talking through a real Redis.
- **GitHub Actions** (`.github/workflows/ci.yml`) runs the full suite against MongoDB and Redis service containers on every push/PR, then builds the Docker image and pushes it to GHCR on `main`.

---

## Project structure

```
src/main/java/com/collabeditor/realtime_editor/
├── config/        Security, JWT filter, rate limiter, Redis, WebSocket, OpenAPI
├── controller/    Auth, Rooms, Snapshots, Chat, CodeExecution
├── service/       Auth, JWT, RefreshToken, TokenBlacklist, RateLimiter, Room, Snapshot, Chat, CodeExecution
├── messaging/     RedisRoomBroker (Pub/Sub fanout), RoomPresenceTracker
├── repository/    Spring Data MongoDB repositories
├── model/         User, RefreshToken, Room, Role, CodeSnapshot, ChatMessage
├── dto/           request/ + response/ (API contract)
├── exception/     Custom exceptions + global handler
└── websocket/     Yjs relay, chat, code-execution handlers

src/main/resources/static/
├── css/   styles.css, editor.css
├── js/    auth.js, app.js, editor.js, collab.js (Yjs), rooms.js, files.js
└── *.html login, lobby, editor

Dockerfile · docker-compose.yml · .github/workflows/ci.yml
```

---

## Notable engineering decisions

- **CRDT over operational transform** — Yjs gives conflict-free merging without a central authority, letting the server stay a thin relay. This is the core of correct multi-user editing.
- **Server as a dumb relay** — the backend never parses CRDT payloads, which keeps it simple and avoids a fragile Java CRDT port. Authorization (viewer read-only) is still enforced by inspecting only the message *type* byte.
- **Ephemeral Docker sandboxes** — no dependency on external code-execution APIs; every run is isolated, resource-capped, and network-disabled.
- **Refresh-token rotation + access-token blacklist** — short access tokens limit blast radius; revocable, rotating refresh tokens in the DB allow real logout/invalidation that stateless JWTs can't. On logout, the access token's `jti` is blacklisted in Redis so it is rejected immediately, not after 30 minutes.
- **Redis as an optional accelerator, not a dependency** — every Redis-backed feature (rate limiting, token blacklist, Pub/Sub fanout, room presence) falls back to an in-memory implementation with a 5-second cooldown after a Redis error. The app boots and runs normally without Redis; cross-instance features simply don't activate until Redis appears.
- **Config externalized to env vars** — the same artifact runs locally and in containers; no secrets in the repo.

---

## Known limitations & future work

- **Remote cursors are per-file** — you see a collaborator's cursor only when you're viewing the same file.
- **Roster and online count are per-instance** — the participant list shows users connected to your own app instance, not the cluster-wide total. A cross-instance roster would need a shared presence data structure beyond the current room-level tracker.
- **Kick is per-instance** — kicking a user disconnects them only on the instance that handled the kick request. A kicked user on another instance stays connected until they reconnect.
- **Docker-out-of-Docker runs the app as root** to reach the socket; a hardened deployment would use a rootless setup or a socket proxy / dedicated executor service.
- **Roadmap ideas:** cluster-wide roster via Redis, WebSocket blacklist check (logged-out tokens can still hold an open socket until expiry), cloud deployment, and a language-server-backed autocomplete.

---

*Built as a deep-dive into real-time distributed systems, secure code execution, and production Spring Boot.*