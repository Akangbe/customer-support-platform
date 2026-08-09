# System Architecture

**Status:** Accepted · v0.1

---

## 1. Architectural style — Modular Monolith

One Spring Boot application, internally divided into well-bounded business modules. **Not** microservices — we don't have the operational scale or team size that would justify the distributed-systems tax.

```
┌──────────────────────── Spring Boot application ────────────────────────┐
│                                                                         │
│  Auth    Tenant    Customer    Conversation    Messaging    Assignment  │
│                                                                         │
│  WhatsApp (integration)    Notification (realtime)    Storage    Audit  │
│                                                                         │
└─────────────────────────────────────────────────────────────────────────┘
```

Modules are separated **logically** and communicate through clear interfaces, so a genuinely hot one can be extracted later without a rewrite. Discipline now buys optionality later.

## 2. High-level topology

```mermaid
flowchart TD
    C[Customer on WhatsApp] --> WA[WhatsApp Business Platform]
    WA -- webhook --> BE[Spring Boot backend]
    BE -- send --> WA

    BE --> PG[(PostgreSQL · Neon)]
    BE --> RD[(Redis)]
    BE --> R2[(Object storage · Cloudflare R2)]

    BE -- WebSocket / REST --> FE[Agent dashboard · Next.js]

    subgraph Stores
      PG
      RD
      R2
    end
```

**Roles of each store:**

- **PostgreSQL (Neon)** — source of truth for all business state.
- **Redis** — ephemeral only: agent presence, WebSocket session data, conversation claim-locks, rate-limit counters. Never authoritative.
- **Cloudflare R2** — media/file bytes (S3-compatible); Postgres holds the metadata and object key.

## 3. Modules and responsibilities

| Module | Owns |
|---|---|
| **Auth** | Authentication, sessions/tokens, role checks |
| **Tenant** | Organizations, workspace config, tenant isolation plumbing |
| **Customer** | Customer identity and profile, per-tenant |
| **Conversation** | The central aggregate: status, priority, history |
| **Messaging** | Message persistence, inbound/outbound, 24h-window rules |
| **Assignment** | Claiming, assigning, reassignment, collision control |
| **WhatsApp** | The integration boundary: webhooks, tenant resolution, send, status, idempotency, credentials |
| **Notification** | Realtime fan-out over WebSocket |
| **Storage** | Object-storage read/write and signed URLs |
| **Audit** | Recording sensitive actions |

The **WhatsApp module is a boundary, not a leak.** No other module talks to Meta directly — they speak to the WhatsApp module's interface. See [whatsapp-integration.md](whatsapp-integration.md).

## 4. Inbound flow (customer → agent)

```mermaid
sequenceDiagram
    participant WA as WhatsApp Platform
    participant WH as Webhook (WhatsApp module)
    participant Q as Async queue
    participant P as Processing
    participant DB as PostgreSQL
    participant N as Notification
    participant FE as Dashboard

    WA->>WH: POST event (message)
    WH->>WH: verify signature
    WH-->>WA: 200 OK (fast)
    WH->>Q: enqueue raw event
    Q->>P: consume
    P->>P: resolve tenant (phone_number_id)
    P->>P: dedupe on wa_message_id
    P->>DB: upsert customer, conversation, message
    P->>N: publish "new message"
    N->>FE: WebSocket push
```

The **fast-ACK + async processing** split is deliberate: it satisfies NFR-PERF-002 and prevents Meta's retries from becoming duplicate storms.

## 5. Outbound flow (agent → customer)

```mermaid
sequenceDiagram
    participant FE as Dashboard
    participant API as Messaging API
    participant DB as PostgreSQL
    participant OB as Outbox / sender
    participant WA as WhatsApp Platform

    FE->>API: send reply
    API->>API: authz + tenant scope + 24h window check
    API->>DB: persist message (PENDING)
    API->>OB: enqueue send
    OB->>WA: POST message (per-tenant creds)
    WA-->>OB: message id
    OB->>DB: update status (SENT)
    WA-->>WH: status webhook (DELIVERED/READ/FAILED)
```

Outbound sends go through an **outbox** so a transient Meta failure is a retry, not a lost reply.

## 6. Multi-tenancy at the architecture level

One shared database, tenant-scoped by `tenant_id`. The tenant is **derived from the authenticated security context or the trusted webhook→tenant mapping — never from a client-supplied value.** This is the platform's central security boundary; it is enforced in code and, as defense-in-depth, may be reinforced with Postgres Row-Level Security.

## 7. What is intentionally absent

No message broker cluster, no service mesh, no per-tenant databases, no Kubernetes on day one. Each of those is a real cost with no v1 payoff. They enter only when a measured need — not a resume — demands them.
