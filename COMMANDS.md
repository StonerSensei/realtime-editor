# CollabIDE - Quick Reference Commands

## Docker & MongoDB

```bash
# Start MongoDB container (first time)
docker run -d --name mongodb -p 27017:27017 mongo:7

# Start/Stop MongoDB container
docker start mongodb
docker stop mongodb

# Check if MongoDB is running
docker ps --filter name=mongodb

# View MongoDB logs
docker logs mongodb

# Remove MongoDB container (data lost)
docker rm -f mongodb
```

## Redis (or Valkey)

Redis is used for distributed rate limiting, access-token blacklisting, and
cross-instance Pub/Sub fanout. It is **optional** — every feature falls back to
in-memory when Redis is unavailable — but required for multi-instance setups.

```bash
# Start a Redis container (first time)
docker run -d --name redis -p 6379:6379 redis:7-alpine

# Start/Stop
docker start redis
docker stop redis

# If you have Valkey installed as a system service (e.g. Arch Linux), it works too:
sudo systemctl start valkey
sudo systemctl stop valkey

# Check connectivity
redis-cli ping               # or: valkey-cli ping  → PONG

# Inspect CollabIDE keys
redis-cli KEYS 'ratelimit:*'          # rate-limit counters
redis-cli KEYS 'blacklist:*'          # revoked access-token JTIs
redis-cli KEYS 'collab:presence:*'    # rooms with active peers
redis-cli PSUBSCRIBE 'collab:*'       # watch Pub/Sub traffic live (Ctrl+C to stop)

# Flush all CollabIDE keys (without dropping other data)
redis-cli --scan --pattern 'ratelimit:*' | xargs -r redis-cli DEL
redis-cli --scan --pattern 'blacklist:*' | xargs -r redis-cli DEL
redis-cli --scan --pattern 'collab:*'    | xargs -r redis-cli DEL
```

Environment variables (safe defaults for local dev):

```bash
REDIS_HOST=localhost    # default
REDIS_PORT=6379         # default
```

## Code Execution (ephemeral Docker containers)

Code runs in throwaway Docker containers. Pre-pull the language images once so the
first execution isn't slow (each image is downloaded on first use otherwise):

```bash
docker pull python:3.11-alpine     # Python
docker pull node:20-alpine         # JavaScript
docker pull gcc:13                 # C and C++
docker pull eclipse-temurin:21-jdk # Java

# Verify Docker can run a throwaway container
docker run --rm python:3.11-alpine python3 -c "print('hello')"
```

Notes:
- The Spring Boot app shells out to `docker run` for each execution, so the app
  must run where the Docker CLI is available (your host user is in the `docker` group).
- Each container runs with `--network none`, a memory cap, CPU cap, PID limit, and a
  wall-clock timeout. Containers are auto-removed (`--rm`) after each run.
- Tunable via env vars: `EXEC_TIMEOUT`, `EXEC_MEMORY`, `EXEC_CPUS`, `EXEC_MAX_OUTPUT`.

## MongoDB Shell (mongosh)

```bash
# Connect to MongoDB
mongosh

# Switch to app database
use editor

# ── View Data ──
show collections
db.rooms.find()
db.rooms.find().pretty()
db.users.find()
db.snapshots.find()
db.snapshots.find({ roomId: "my-room" })
db.snapshots.find().sort({ timestamp: -1 }).limit(5)

# ── Other collections ──
db.chat_messages.find({ roomId: "my-room" })
db.refresh_tokens.find({ username: "testuser" })

# ── Count ──
db.rooms.countDocuments()
db.users.countDocuments()
db.snapshots.countDocuments()

# ── Insert (for testing) ──
db.rooms.insertOne({ roomId: "test-room", language: "javascript", owner: "testuser", createdAt: new Date() })

# ── Delete ──
db.rooms.deleteOne({ roomId: "test-room" })
db.rooms.deleteMany({})
db.users.deleteMany({})
db.snapshots.deleteMany({})
db.chat_messages.deleteMany({})
db.refresh_tokens.deleteMany({})   # forces everyone to re-login
db.dropDatabase()

# ── Test Database (used by integration tests) ──
use editor-test
db.dropDatabase()
```

## Build → Run (full sequence)

```bash
# 1. Start MongoDB + Redis (must be running first; Redis is optional but recommended)
docker start mongodb redis        # or: docker start mongodb && sudo systemctl start valkey

# 2. Build (from the project root)
cd /home/parth/Documents/Projects/realtime-editor
./mvnw clean package -DskipTests        # fast build, skips tests
# ./mvnw clean package                  # build + run full test suite (needs MongoDB + Redis)

# 3. Run - choose one:
java -jar target/realtime-editor-0.0.1-SNAPSHOT.jar   # recommended (avoids IDE stale-class issues)
# ./mvnw spring-boot:run                              # alternative, good for live reload
```

Day-to-day quick start (everything already set up):

```bash
docker start mongodb redis
cd /home/parth/Documents/Projects/realtime-editor
./mvnw clean package -DskipTests
java -jar target/realtime-editor-0.0.1-SNAPSHOT.jar
```

The app starts normally without Redis — you'll see a warning in the log and all
Redis features fall back to in-memory.

Tip: if you ever see `java.lang.Error: Unresolved compilation problem`, it's stale
IDE-compiled bytecode - run `./mvnw clean package` and launch the jar (not the IDE Run button).

## Docker Compose (app + MongoDB in one command)

Runs the whole stack: MongoDB + Redis + the app (which itself shells out to the host
Docker daemon to run code sandboxes).

```bash
# 0. One-time: create your local secrets file
cp .env.example .env
# then edit .env and set a strong JWT_SECRET (>= 32 chars):
#   openssl rand -base64 48

# 1. Pre-pull the language images ONTO THE HOST (code sandboxes run on the host daemon)
docker pull python:3.11-alpine
docker pull node:20-alpine
docker pull gcc:13
docker pull eclipse-temurin:21-jdk

# 2. Build + start everything (add -d to run in background)
docker compose up --build

# App:      http://localhost:8080
# MongoDB:  internal only (service name "mongo"); not exposed to host by default
# Redis:    internal only (service name "redis"); not exposed to host by default

# ── Managing the stack ──
docker compose ps                 # status
docker compose logs -f app        # tail app logs
docker compose logs -f mongo      # tail mongo logs
docker compose restart app        # restart just the app
docker compose down               # stop + remove containers (keeps mongo-data volume)
docker compose down -v            # also delete the MongoDB data volume (fresh DB)
```

Why the two special volume mounts in `docker-compose.yml`:
- `/var/run/docker.sock` - lets the app run code-execution sandboxes on the host daemon
  (Docker-out-of-Docker). The app image ships the `docker` CLI for this.
- `/tmp/collabide-exec` (same path on host + container) - the sandbox source is written
  here so the host daemon's `-v .../code` bind mount resolves to a real host path.

Note: the app container runs as root so it can access the mounted Docker socket. That is
a deliberate trade-off for local/self-hosted use; a hardened prod setup would use a
rootless/socket-proxy approach.

## Docker image (build/run without Compose)

```bash
# Build the image
docker build -t collabide:latest .

# Run it against a separately-running MongoDB + host Docker socket
docker run --rm -p 8080:8080 \
  -e MONGODB_URI="mongodb://host.docker.internal:27017/editor" \
  -e JWT_SECRET="a-strong-secret-at-least-32-characters" \
  -e EXEC_WORK_DIR=/tmp/collabide-exec \
  -v /var/run/docker.sock:/var/run/docker.sock \
  -v /tmp/collabide-exec:/tmp/collabide-exec \
  collabide:latest
```

## Continuous Integration (GitHub Actions)

`.github/workflows/ci.yml` runs on every push/PR:
- **build-test** job: JDK 17 + Maven cache, spins up a `mongo:7` service container, runs
  `./mvnw clean verify` (unit + integration tests), uploads surefire reports.
- **docker-image** job: builds the Docker image; on pushes to `main`/`master` it logs in
  to GHCR and pushes `ghcr.io/<owner>/<repo>:latest` and `:<sha>`.

No extra secrets needed - it uses the built-in `GITHUB_TOKEN`. Just push the repo to
GitHub and the pipeline runs automatically.

## URLs

| What | URL |
|------|-----|
| App (login/lobby) | http://localhost:8080 |
| Swagger UI (interactive API docs) | http://localhost:8080/swagger-ui.html |
| OpenAPI spec (JSON) | http://localhost:8080/v3/api-docs |
| Health check | http://localhost:8080/actuator/health |
| Metrics | http://localhost:8080/actuator/metrics |
| Prometheus metrics | http://localhost:8080/actuator/prometheus |
| App info | http://localhost:8080/actuator/info |

## Tests

```bash
# Run ALL tests (needs MongoDB running)
./mvnw test

# Run only unit tests (no external deps needed)
./mvnw test -Dtest="JwtServiceTest,AuthServiceTest,RoomServiceTest,SnapshotServiceTest,TokenBlacklistServiceTest,RateLimiterServiceTest,RedisRoomBrokerTest,RoomPresenceTrackerTest,YjsRelayWebSocketHandlerTest,ChatWebSocketHandlerTest,JwtAuthenticationFilterTest,ResilientRedisMessageListenerContainerTest"

# Run only integration tests (needs MongoDB; CrossInstanceFanoutIntegrationTest also needs Redis)
./mvnw test -Dtest="AuthControllerIntegrationTest,RoomControllerIntegrationTest,RateLimitIntegrationTest,LogoutBlacklistIntegrationTest,CrossInstanceFanoutIntegrationTest"

# Run a single test class
./mvnw test -Dtest="JwtServiceTest"

# Run a single test method
./mvnw test -Dtest="JwtServiceTest#generateToken_shouldReturnNonEmptyToken"

# Skip tests during build
./mvnw clean package -DskipTests
```

## API Endpoints (for manual testing with curl)

```bash
# ── Auth ──
# Register
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"testuser","email":"test@example.com","password":"password123"}'

# Login (returns both an access "token" and a "refreshToken")
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"testuser","password":"password123"}'

# Save the access token from the login response
TOKEN="paste-your-access-token-here"
REFRESH="paste-your-refresh-token-here"

# Refresh: exchange a refresh token for a new access token (refresh token is rotated)
curl -X POST http://localhost:8080/api/auth/refresh \
  -H "Content-Type: application/json" \
  -d "{\"refreshToken\":\"$REFRESH\"}"

# Logout: revoke the refresh token AND blacklist the access token immediately
curl -X POST http://localhost:8080/api/auth/logout \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d "{\"refreshToken\":\"$REFRESH\"}"
# Omitting the Authorization header still revokes the refresh token,
# but the access token stays valid until it expires (~30 min).

# ── Rooms ──
# Create room (creator becomes OWNER)
curl -X POST http://localhost:8080/api/rooms/host \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"roomId":"my-room","language":"javascript"}'

# Join room (adds you as a member; returns your role)
curl -X POST http://localhost:8080/api/rooms/my-room/join \
  -H "Authorization: Bearer $TOKEN"

# Get room details (owner, members, your role)
curl http://localhost:8080/api/rooms/my-room \
  -H "Authorization: Bearer $TOKEN"

# ── Roles (owner only) ──
# Change a member's role (EDITOR or VIEWER)
curl -X PUT http://localhost:8080/api/rooms/my-room/members/bob/role \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"role":"VIEWER"}'

# Kick a member
curl -X DELETE http://localhost:8080/api/rooms/my-room/members/bob \
  -H "Authorization: Bearer $TOKEN"

# ── Chat ──
# Get recent chat messages for a room
curl http://localhost:8080/api/chat/my-room \
  -H "Authorization: Bearer $TOKEN"

# ── Snapshots (version history) ──
# Save a multi-file project snapshot (preferred). "files" is a map of name -> content.
curl -X POST http://localhost:8080/api/snapshots/save \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"roomId":"my-room","language":"javascript","files":{"main.js":"console.log(\"hi\")","utils.js":"export const x = 1;"}}'

# Legacy single-file save (still accepted for backward compatibility)
curl -X POST http://localhost:8080/api/snapshots/save \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"roomId":"my-room","code":"console.log(\"hello\")","language":"javascript"}'

# Get latest snapshot
curl http://localhost:8080/api/snapshots/latest/my-room \
  -H "Authorization: Bearer $TOKEN"

# Get all snapshots for a room (newest first)
curl http://localhost:8080/api/snapshots/my-room \
  -H "Authorization: Bearer $TOKEN"
```

## Useful Docker Commands

```bash
# Check Docker is running
systemctl status docker

# Start Docker service
sudo systemctl start docker
sudo systemctl enable docker

# List all containers
docker ps -a

# Kill all containers
docker stop $(docker ps -q)
```

## Rate limiting

Auth endpoints (`/api/auth/**`) are rate-limited per client IP. The primary limiter
uses a Redis fixed-window counter (`INCR` + `PEXPIRE` via a Lua script, key
`ratelimit:{ip}`); if Redis is unavailable it falls back to an in-memory Bucket4j
token bucket per IP. After a Redis error, Redis is skipped for 5 seconds so a
Redis outage does not add a connection timeout to every auth request.

Exceeding the limit returns HTTP 429. Tunable via env vars:

```bash
AUTH_RATE_CAPACITY=10        # requests allowed per window (default 10)
AUTH_RATE_REFILL_MINUTES=1   # window length in minutes (default 1)
```

Quick test - fire 15 logins and watch for 429:

```bash
for i in $(seq 1 15); do
  curl -s -o /dev/null -w "%{http_code}\n" -X POST http://localhost:8080/api/auth/login \
    -H "Content-Type: application/json" \
    -d '{"username":"nobody","password":"wrong"}'
done
```

## Auth tokens (access + refresh)

Login/register return a short-lived **access token** (JWT) and a long-lived,
revocable **refresh token**. The frontend silently refreshes on a 401 and via a
background timer, so long-lived WebSocket reconnects keep working.

```bash
JWT_ACCESS_EXPIRATION=1800000   # access token lifetime ms (default 30 min)
JWT_REFRESH_EXPIRATION=604800000 # refresh token lifetime ms (default 7 days)
JWT_SECRET=change-me-in-prod-min-32-chars
```

To watch silent renewal happen quickly, start with a 1-minute access token and
use the app for a couple minutes (watch the Network tab for `/api/auth/refresh`):

```bash
JWT_ACCESS_EXPIRATION=60000 java -jar target/realtime-editor-0.0.1-SNAPSHOT.jar
```

## Multiple files per room

Each room is a mini project: the file tree (Files icon in the editor sidebar) lets
you create / rename / delete / switch files, all synced live via Yjs. The editor
binds to the active file; Run/Save act on the active file. Version-history snapshots
capture the whole project (all files) and restore replaces every file.

## Project Structure

```
src/main/java/com/collabeditor/realtime_editor/
├── RealtimeEditorApplication.java
├── config/          (SecurityConfig, WebSocketConfig, JwtAuthenticationFilter,
│                     RateLimitFilter, RedisConfig, ResilientRedisMessageListenerContainer,
│                     OpenApiConfig)
├── controller/      (AuthController, RoomController, SnapshotController,
│                     ChatController, CodeExecutionController)
├── service/         (AuthService, JwtService, RefreshTokenService, TokenBlacklistService,
│                     RateLimiterService, RoomService, SnapshotService, ChatService,
│                     CodeExecutionService)
├── messaging/       (RedisRoomBroker, RoomPresenceTracker)
├── repository/      (UserRepository, RefreshTokenRepository, RoomRepository,
│                     CodeSnapshotRepository, ChatMessageRepository)
├── model/           (User, RefreshToken, Room, Role, CodeSnapshot, ChatMessage)
├── dto/request/     (LoginRequest, RegisterRequest, RefreshRequest, CreateRoomRequest,
│                     ChangeRoleRequest, CodeExecutionRequest, SaveSnapshotRequest)
├── dto/response/    (AuthResponse, RoomResponse, MemberDto, SnapshotResponse,
│                     ChatMessageResponse, CodeExecutionResponse, ErrorResponse)
├── exception/       (GlobalExceptionHandler, RoomNotFoundException,
│                     RoomAlreadyExistsException, AuthenticationException,
│                     ForbiddenActionException, CodeExecutionException)
└── websocket/       (YjsRelayWebSocketHandler, ChatWebSocketHandler,
                      CodeExecutionWebSocketHandler)

src/main/resources/static/
├── css/             (styles.css, editor.css)
├── js/              (auth.js, app.js, editor.js, collab.js, rooms.js, files.js)
├── login.html
├── index.html
└── editor.html

MongoDB collections:
  users, refresh_tokens, rooms, snapshots, chat_messages

WebSocket endpoints:
  /yjs/{roomId}       - Yjs CRDT collaboration relay (binary, JWT via ?token=)
  /ws/chat/{roomId}   - real-time chat (JWT via ?token=)
  /ws/exec            - code execution streaming

REST endpoints:
  POST   /api/auth/register | /login | /refresh | /logout
  POST   /api/rooms/host
  POST   /api/rooms/{roomId}/join      GET /api/rooms/{roomId}
  PUT    /api/rooms/{roomId}/members/{username}/role
  DELETE /api/rooms/{roomId}/members/{username}
  POST   /api/snapshots/save           GET /api/snapshots/{roomId}
  GET    /api/snapshots/latest/{roomId}   GET /api/snapshots/get/{id}
  GET    /api/chat/{roomId}
  POST   /api/execute
```

Deployment / DevOps files (project root):
```
Dockerfile               multi-stage build (Maven build -> JRE runtime + docker CLI)
docker-compose.yml       app + mongo, docker.sock + shared exec dir mounts
.dockerignore            keeps build context small
.env.example             template for local secrets (copy to .env, git-ignored)
.github/workflows/ci.yml build + test (mongo service) + build/push Docker image to GHCR
```

## Two-instance fanout demo

Redis Pub/Sub lets multiple app instances relay edits and chat to each other.
To test this locally:

```bash
# 1. Databases must be running and accessible from the host
docker start mongodb
# Redis/Valkey must be on localhost:6379 (system service or Docker with -p 6379:6379)

# 2. Build once
./mvnw clean package -DskipTests

# 3. Start two instances (they MUST share the same JWT_SECRET)
java -jar target/realtime-editor-0.0.1-SNAPSHOT.jar                   # instance 1 on :8080
PORT=8081 java -jar target/realtime-editor-0.0.1-SNAPSHOT.jar         # instance 2 on :8081
```

Both logs should show `Subscribed to collab:yjs:*`.

Open the same room on `localhost:8080` (browser 1) and `localhost:8081` (browser 2).
Edits and chat typed on one side appear on the other.

Watch the traffic:

```bash
redis-cli PSUBSCRIBE 'collab:*'                       # live Pub/Sub stream
redis-cli ZRANGE collab:presence:<roomId> 0 -1         # instance IDs with peers
```

## Degraded mode (Redis down)

Every Redis-backed feature has an in-memory fallback. The app boots and runs
normally without Redis; cross-instance features simply don't activate.

| Feature | With Redis | Without Redis |
|---------|-----------|---------------|
| Rate limiting | Redis fixed-window (shared across instances) | In-memory Bucket4j (per-instance) |
| Token blacklist | Shared across instances via `blacklist:{jti}` | Local-only; fail-open on read |
| Yjs/Chat fanout | Pub/Sub across instances | Local delivery only |
| Room presence | Cluster-wide "am I first?" check | Per-instance only |
| Pub/Sub container | Subscribes; auto-recovers on disconnect | Retries every 2s until Redis appears |

After a Redis error, each feature skips Redis for 5 seconds (cooldown) so a
Redis outage does not add a connection timeout to every request.

**Recovery is automatic.** When Redis comes back:
1. The Pub/Sub container re-subscribes.
2. Every client is asked to re-broadcast its full document (Yjs resync), so
   edits made during the outage on different instances merge automatically.
3. The room presence tracker re-registers every active room immediately.

To test:

```bash
# Stop Redis while two instances are running
sudo systemctl stop valkey        # or: redis-cli shutdown nosave

# Both instances keep working locally; edits don't cross between them.
# Make an edit on each side.

# Bring Redis back
sudo systemctl start valkey       # or: docker start redis

# Within seconds, both sides show the same merged document.
# Logs show: "Subscribed to collab:yjs:*" and "Requesting full Yjs resync"
```

## Per-instance roster limitation

The online count and participant list in the editor show users connected to
**your own app instance**, not the cluster-wide total. With one instance this
is invisible; with two, each side's count reflects only its own connections.
A cluster-wide roster would need a shared presence data structure beyond the
current room-level tracker (a possible future improvement).