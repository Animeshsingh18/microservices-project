# Microservices Project — User & Notification System with API Gateway

An event-driven microservices system built with **Java, Spring Boot, NATS JetStream, PostgreSQL, and Spring Cloud Gateway**.

Two independent services — **User Service** and **Notification Service** — communicate **asynchronously via NATS JetStream only**. There is no REST or WebSocket call between them; the only integration surface is the event contract published over the message broker. An **API Gateway** sits in front of both, centralizing JWT validation, routing, and resilience.

```
microservices-project/
├── docs/                     # Full architecture + API reference
├── api-gateway/               # Spring Cloud Gateway — JWT validation + routing
├── user-service/               # Publishes events via outbox -> NATS
├── notification-service/       # Durable NATS consumer, sends notifications
└── docker-compose.yml          # Shared infra: NATS + both Postgres DBs
```

## Architecture

```
                        ┌──────────────────────────┐
                        │   Client (Postman, etc.)  │
                        └─────────────┬─────────────┘
                                      │ HTTPS
                                      ▼
                        ┌──────────────────────────┐
                        │       API Gateway :8080    │
                        │  - JWT validation           │
                        │  - Routing                  │
                        │  - Circuit breaker/fallback  │
                        └──────┬────────────┬────────┘
                    /api/auth/**│            │/api/notifications/**
                    /api/users/**│            │
                               ▼            ▼
              ┌─────────────────────┐  ┌──────────────────────────┐
              │    User Service       │  │   Notification Service    │
              │  Spring Boot :8081    │  │  Spring Boot :8082         │
              │                        │  │                             │
              │  Outbox pattern         │  │  Durable pull consumer      │
              │  (transactional          │  │  Idempotent processing      │
              │   write + async relay)   │  │  DLQ on repeated failure     │
              └──────────┬─────────────┘  └─────────────┬───────────────┘
                         │ publish                        │ subscribe
                         ▼                                 │
              ┌─────────────────────────────────────────────┐
              │         NATS JetStream (message broker)      │
              │  Stream: USER_EVENTS | Subjects: user.events.>│
              │  Durable consumer: notification-service-durable│
              │  DLQ subject: user.events.dlq                  │
              └─────────────────────────────────────────────┘

     ┌──────────────┐                              ┌──────────────────────┐
     │   user_db     │  owned only by User Service   │   notification_db     │  owned only by
     │   Postgres     │                               │   Postgres             │  Notification Service
     └──────────────┘                              └──────────────────────┘
```

**Key property:** User Service and Notification Service never call each other directly — no shared database, no synchronous HTTP/WebSocket link between them. The gateway is a separate concern layered on top for client-facing traffic only.

## Services at a glance

| Service | Port | Responsibility | Docs |
|---|---|---|---|
| **API Gateway** | `8080` | Single entry point. Validates JWTs at the edge, routes to both services, circuit-breaks failing backends. | [`api-gateway/README.md`](api-gateway/README.md) |
| **User Service** | `8081` | Registration/login, issues JWTs, owns `user_db`, publishes `USER_REGISTERED` events via the outbox pattern. | [`user-service/README.md`](user-service/README.md) |
| **Notification Service** | `8082` | Durable NATS consumer, owns `notification_db`, sends notifications, idempotent + DLQ on failure. | [`notification-service/README.md`](notification-service/README.md) |

Full design rationale and the request-flow sequence live in [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md). Endpoint reference and the internal event contract live in [`docs/API.md`](docs/API.md).

## Why these design choices

| Choice | Reasoning |
|---|---|
| **NATS JetStream**, not core NATS | Core NATS is fire-and-forget. JetStream adds durable streams and at-least-once delivery — required for "reliable, production-ready" async messaging. |
| **Outbox pattern** in User Service | Publishing directly inside the transactional method risks a DB/broker mismatch. Writing to an outbox table in the same transaction, then relaying separately, makes the DB write the single source of truth. |
| **Durable pull consumer** in Notification Service | "Durable" means JetStream remembers the consumer's position across restarts. "Pull" gives the consumer control over its own backpressure. |
| **Idempotency via unique `eventId`** | At-least-once delivery means duplicates *will* happen. A unique DB constraint on the event ID turns a duplicate delivery into a no-op. |
| **Dead-letter subject** | Without one, a poison message would retry forever or vanish silently after max-deliver. Republishing to `user.events.dlq` keeps it inspectable. |
| **Database-per-service** | `user_db` and `notification_db` are separate Postgres instances — each service owns its data; the only integration surface is the event contract. |
| **JWT validated at Gateway AND each service** | Gateway is the primary checkpoint for client traffic. Each service still re-validates independently, so it's safe to call directly during local dev without the gateway running. |
| **Circuit breaker at the Gateway** | Prevents a downstream outage from hanging client requests indefinitely — fails fast with a clean `503` instead. |

## Prerequisites

- JDK 17
- Maven (or IntelliJ's bundled Maven)
- Docker Desktop

## Quick start

### 1. Start shared infrastructure (one command)

```bash
docker compose up -d
```

This brings up:

| Container | Port | Purpose |
|---|---|---|
| NATS (JetStream) | `4222` (client), `8222` (monitoring UI) | Message broker |
| user-db (Postgres) | `5432` | User Service's database |
| notification-db (Postgres) | `5433` | Notification Service's database |

Verify: `curl http://localhost:8222/jsz` should return JetStream stats.

### 2. Configure environment variables

Each service has its own `.env.example` — copy to `.env` in each folder:

```bash
cp user-service/.env.example user-service/.env
cp notification-service/.env.example notification-service/.env
cp api-gateway/.env.example api-gateway/.env
```

Generate a JWT secret **once**:

```bash
openssl rand -base64 32
```

**Use the exact same `JWT_SECRET` value in all three `.env` files** — User Service issues tokens, Notification Service and the Gateway both validate against the same signing key. This is the single most common source of `401` errors if it's mismatched.

In IntelliJ: install the **EnvFile** plugin (Marketplace), then in each service's Run Configuration → EnvFile tab → point it at that service's `.env`.

### 3. Run all three services, in this order

Order matters — each step depends on the previous one being ready.

1. **User Service** first — creates the `USER_EVENTS` JetStream stream on startup if it doesn't exist.
2. **Notification Service** second — attaches its durable consumer to that stream.
3. **API Gateway** last — needs both backend ports listening for its circuit breakers to see them healthy.

```bash
cd user-service && mvn spring-boot:run
cd notification-service && mvn spring-boot:run
cd api-gateway && mvn spring-boot:run
```

(Or run each `*Application.java` main class directly from IntelliJ with its EnvFile-attached run configuration.)

| Service | Port | Swagger UI |
|---|---|---|
| API Gateway | 8080 | — (routes to the two below) |
| User Service | 8081 | http://localhost:8081/swagger-ui.html |
| Notification Service | 8082 | http://localhost:8082/swagger-ui.html |

### 4. Try it end-to-end — through the gateway

```bash
# 1. Register
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"name":"Animesh Singh","email":"animesh@example.com","password":"SecurePass123"}'
```

Watch Notification Service's console — within ~2 seconds (the outbox relay's poll interval) you'll see a simulated notification logged.

```bash
# 2. Use the returned token to call both services through the gateway
curl http://localhost:8080/api/users/1 -H "Authorization: Bearer <token>"
curl http://localhost:8080/api/notifications/users/1 -H "Authorization: Bearer <token>"

# 3. Confirm the gateway rejects unauthenticated requests
curl -i http://localhost:8080/api/users/1
# expect: 401 Unauthorized
```

Full request/response shapes: [`docs/API.md`](docs/API.md).

### 5. Confirm the messaging layer directly (optional)

```bash
docker exec -it nats nats stream info USER_EVENTS
docker exec -it nats nats consumer info USER_EVENTS notification-service-durable
```

### 6. Shut down

```bash
docker compose down          # stop containers, keep data
docker compose down -v       # stop containers and wipe volumes
```

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| User Service fails to start with a NATS connection error | `docker compose up -d` wasn't run, or `NATS_URL` doesn't match |
| Notification Service never receives events | Check `NATS_STREAM_NAME` / `NATS_SUBJECT_PREFIX` match across all `.env` files |
| `401 Unauthorized` at the Gateway or either service | `JWT_SECRET` differs between the three `.env` files |
| Gateway returns `503` on a route | That backend isn't running, or the circuit breaker tripped — check its console |
| Registration succeeds but no notification appears | Check User Service logs for outbox relay errors; confirm the event reached NATS with `nats stream info USER_EVENTS` |

## Technology stack

| Technology | Purpose |
|---|---|
| Java 17 / Spring Boot | Application framework across all three services |
| Spring Cloud Gateway | Reactive API Gateway — routing, JWT enforcement, resilience |
| NATS JetStream | Durable, at-least-once event broker between User and Notification Service |
| PostgreSQL | Database-per-service persistence |
| Spring Data JPA | Persistence layer |
| Spring Security + JJWT | Stateless JWT authentication, validated at the Gateway and each service |
| Resilience4j | Circuit breaker + timeout on Gateway routes |
| Docker / Docker Compose | Local infrastructure (NATS + Postgres) |
| Swagger / OpenAPI | Interactive API docs per service |
| Maven | Build and dependency management |

