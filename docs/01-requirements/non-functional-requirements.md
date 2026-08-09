# Non-Functional Requirements

**Status:** Accepted · v0.1

This is where we think like system engineers, not just application developers.

> **A rule about numbers.** We do **not** invent "99.99%" or "<100ms" because they sound enterprise-grade. Targets are set from real business need and confirmed by measurement. Until we've load-tested, these are directional, and each carries a `[TBD: measure]` where a hard number belongs.

---

## Performance

| ID | Requirement |
|---|---|
| NFR-PERF-001 | Normal API requests should return with low latency. `[TBD: measure]` |
| NFR-PERF-002 | Webhook endpoints should acknowledge (`200`) promptly and process asynchronously. |
| NFR-PERF-003 | Realtime events should reach the dashboard with minimal latency. `[TBD: measure]` |

The webhook contract is the important one: **acknowledge fast, process later.** A slow webhook makes Meta retry, which multiplies load and duplicates events.

## Reliability

The system shall:

- Handle webhook retries without creating duplicate messages.
- Tolerate temporary WhatsApp/API failures and retry recoverable operations.
- Record failures rather than swallow them.
- Never lose an accepted message once it has returned `200` to Meta.

Reliability posture: **accept-then-process.** Once we ACK a webhook we own that message — durability starts at acceptance, not at the end of processing. See the transactional-outbox and idempotency notes in [ADR-012](../08-decisions/decision-log.md#adr-012--idempotent-ingestion--transactional-outbox).

## Security

The system shall:

- Use HTTPS everywhere.
- Authenticate users and authorize actions by role.
- Enforce tenant boundaries on every tenant-scoped query.
- Store WhatsApp credentials encrypted; never expose secrets to the frontend.
- Verify every inbound webhook signature.
- Keep audit records for sensitive actions.

Full detail in the security architecture (to be written); the invariant lives in [architecture principles](../03-architecture/architecture-principles.md).

## Scalability

The architecture should absorb growth in tenants, agents, customers, conversations, messages, and webhook traffic **without a rewrite**. The modular monolith keeps module boundaries clean so a hot module can later be extracted if — and only if — real load justifies it.

## Maintainability

- The backend uses clearly bounded modules; no omniscient `UserService` that slowly learns everything.
- Business capabilities have explicit boundaries and speak the shared domain language.
- Every significant decision is captured as an ADR.

## Observability `[added]`

Not in the original draft, but non-negotiable for a platform that ingests third-party webhooks:

| ID | Requirement |
|---|---|
| NFR-OBS-001 | The system shall emit structured logs correlating a message across ingest → persist → notify → send. |
| NFR-OBS-002 | The system shall expose health and readiness endpoints. |
| NFR-OBS-003 | The system shall track webhook failure and outbound-send failure rates as first-class metrics. |

You cannot operate a webhook-driven system you cannot see. Wire this in early, not after the first outage.
