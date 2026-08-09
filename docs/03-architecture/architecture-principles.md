# Architecture Principles

**Status:** Accepted · v0.1

These are the rules the codebase must obey. A pull request that violates one needs an ADR that changes the rule — not a quiet exception.

---

### Rule 1 — WhatsApp is an integration, not the domain
The domain is customer-conversation management. WhatsApp is one way conversations arrive. Model conversations, customers, and assignments as first-class; keep Meta identifiers at the edge.

### Rule 2 — PostgreSQL is the single source of truth
Redis is not. WebSocket is not. The frontend is not. Ephemeral stores and transports may cache or deliver state, but the authoritative copy lives in Postgres.

### Rule 3 — No tenant can touch another tenant's data
Tenant isolation is a security invariant, not a feature. Every tenant-scoped query is scoped by a `tenant_id` derived from the trusted security context — never from client input.

### Rule 4 — Don't couple the core domain to Meta's APIs
All WhatsApp access goes through the WhatsApp integration boundary. If the Conversation module imports a Meta SDK type, the design is wrong.

### Rule 5 — Don't build future features prematurely
If a feature isn't needed to validate the core product, it doesn't enter the MVP just because we *can* build it. The non-goals list is a decision.

### Rule 6 — Every significant decision gets an ADR
We never justify a choice with "everyone uses X." We record **Problem → Options → Decision → Trade-offs**. Reversible-but-important decisions especially.

---

## Two engineering invariants `[added]`

The original six are about boundaries. These two are about correctness under a third party we don't control:

### Rule 7 — Webhooks are at-least-once; ingestion must be idempotent
Meta can deliver the same event more than once, out of order, or retry after a slow response. Every inbound event is deduplicated on its provider message id before it becomes a domain fact. Idempotency is a correctness property, not an optimization.

### Rule 8 — Accept-then-process; never lose an accepted message
Once we return `200` to a webhook, that message is our responsibility. Acknowledge fast, persist durably, process asynchronously. Outbound sends go through an outbox so a transient failure is a retry, not a dropped reply.

---

## How these show up in review

- A tenant-scoped repository method with no `tenant_id` filter → **blocked (Rule 3).**
- A domain service importing a Meta type → **blocked (Rule 4).**
- New inbound handling with no dedupe key → **blocked (Rule 7).**
- A "we'll need it later" module with no MVP requirement → **blocked (Rule 5).**
