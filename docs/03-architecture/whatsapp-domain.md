# WhatsApp Integration Design (Phase 6)

**Status:** Proposed · v0.1

`whatsapp-integration.md` already fixed the *contract* — this document
fixes the *implementation*: concrete entities, endpoints, retry
mechanics, and authorization the contract left abstract. Read that doc
first; this one assumes it. Per the engineering process, this is also
where [ADR-011](../08-decisions/decision-log.md#adr-011--phased-meta-onboarding)
finally gets its full body, closing the action item open since Phase 0.

---

## 1. Scope

**In scope:**
- `WhatsAppConnection` — the tenant's WABA credentials, connect/view
  endpoints (FR-WA-001), encrypted at rest (NFR-SEC).
- The webhook: verification handshake, signature verification
  (FR-WA-003), tenant resolution by `phone_number_id` (FR-WA-004),
  fast-ACK-then-async-process (FR-WA-002, NFR-PERF-002, Rule 8).
- Wiring inbound events to the idempotent methods Phases 3–5 already
  built ahead of a caller: `CustomerService.findOrCreateFromInbound`,
  `ConversationService.findOrOpenForCustomer`,
  `MessageService.recordInbound` (FR-WA-005, FR-CUS-001, FR-CON-001).
- `WhatsAppGateway` — the boundary interface `whatsapp-integration.md`
  §10 already sketched — plus a concrete Meta Graph API implementation,
  text messages only (FR-WA-006).
- An outbound sender that drains `PENDING` messages through the gateway
  with retry/backoff, and a status webhook path that drives
  `SENT → DELIVERED → READ` / `FAILED` (FR-WA-007, ADR-012's outbox half,
  finally given a consumer).
- A minimal template-send path so a reply outside the 24h window has a
  legal way to happen at all, per the promise `message-domain.md` made
  ("Phase 6 adds the template path as an alternative to rejection").
- ADR-011's full body.

**Out of scope (deferred, not forgotten):**
- Tech Provider / Embedded Signup onboarding (ADR-011 Phase B/C) — no
  App Review has been filed; every tenant in Phase A connects the one
  way direct-developer mode allows, pasting credentials generated in
  Meta Business Manager for their own owned WABA. The `WhatsAppConnection`
  model is already tenant-scoped so nothing here needs to change shape
  when Phase C flips the onboarding path on — only *how a row gets
  created* changes, not the table.
- Media/attachments — still blocked on the Storage module, per
  `message-domain.md` §1. Text only.
- Template *management* (creating/tracking approval status of templates
  in our system). Template names/languages are configured in Meta
  Business Manager, out of our control either way; we only need to be
  able to *reference* one by name when sending. Building a template
  catalog with approval-state tracking is real speculative scope with
  no current requirement — Rule 5.
- A resend/retry API for a message that hit terminal `FAILED`. An agent
  can just send a new message; a dedicated "retry" action is UI sugar
  with no new domain capability behind it.
- Per-tenant Meta app secrets. In direct-developer mode there is exactly
  one Meta app (ours), so exactly one app secret and one webhook
  verify-token, both global config — not per-tenant data.
- Audit-log entries for connect/reconfigure (FR-AUD-003). The Audit
  module doesn't exist yet; recorded here as a known gap for whenever
  that module is built, not solved by improvising a one-off log table
  now.

## 2. WhatsAppConnection

| Column | Type | Notes |
|---|---|---|
| `id` | uuid, PK | |
| `tenant_id` | uuid, FK → tenant.id, **unique** | One WABA per tenant (domain-model.md's tenant-level-singleton note) |
| `phone_number_id` | varchar, **unique**, not tenant-scoped | Meta's id for the WABA phone number — this is deliberately the one lookup in the whole system that *isn't* filtered by tenant, because resolving tenant is exactly what it's for (`whatsapp-integration.md` §3) |
| `waba_id` | varchar, not null | |
| `access_token` | varchar, not null, **encrypted at rest** | AES-256-GCM via a JPA `AttributeConverter`; key from `app.whatsapp.credential-encryption-key`, sourced from env/secrets manager, never committed |
| `created_at`, `updated_at` | timestamptz | |

**Connect is an upsert**, not a one-shot create: `POST
/api/v1/whatsapp/connection` replaces the tenant's existing row if one
exists. Token rotation is a real operational need even for a single
owned WABA — not speculative.

**Authorization:** Owner or Admin only, per Product Vision's role table
("workspace and WhatsApp configuration" is explicitly an
Owner/Admin duty, never Manager/Agent). `GET
/api/v1/whatsapp/connection` (view, credentials never echoed back — the
response shows `phoneNumberId`/`wabaId`/`connectedAt`, never
`access_token`) is available to the same two roles.

## 3. Webhook: verification and signature

```
GET  /api/v1/whatsapp/webhook   — Meta's registration handshake
POST /api/v1/whatsapp/webhook   — event delivery
```

**GET** (`whatsapp-integration.md` §4.2): if `hub.mode=subscribe` and
`hub.verify_token` matches `app.whatsapp.verify-token` (global config,
one Meta app), respond `200` with `hub.challenge` as a plain-text body.
Otherwise `403`.

**POST**: the body is read as raw bytes, not bound to a DTO — HMAC
verification needs the exact bytes Meta signed, and Jackson's parsed
form is not guaranteed byte-identical. `X-Hub-Signature-256` is
`sha256=<hex HMAC-SHA256 of the raw body, keyed by app.whatsapp.app-secret>`;
compared with `MessageDigest.isEqual` (constant-time — a naive `String
.equals` on a MAC comparison is a timing side-channel). Mismatch → `403`,
nothing persisted. This is the *only* checkpoint before persistence —
matches Rule 7/8's ordering: verify, then accept, then everything else
can be async.

## 4. Accept-then-process, without a message broker

`system-architecture.md`'s inbound sequence diagram draws a queue (`Q`)
between the webhook and processing (`P`). Building an actual broker for
this is explicitly listed as absent-on-day-one
(`system-architecture.md` §7) — and unnecessary. Postgres already plays
this role once ADR-012 is taken seriously: a durable table *is* a queue
when something polls it.

**`webhook_event`** (the inbound counterpart to the outbound "outbox"
ADR-012 already named):

| Column | Type | Notes |
|---|---|---|
| `id` | uuid, PK | |
| `payload` | jsonb, not null | The raw, signature-verified body |
| `status` | enum: `PENDING`, `PROCESSED`, `DROPPED`, `FAILED` | |
| `attempt_count` | int, default 0 | |
| `next_attempt_at` | timestamptz, nullable | Backoff scheduling |
| `error` | text, nullable | Last processing error, for `FAILED` |
| `received_at`, `processed_at` | timestamptz | |

Request flow: verify signature → **persist the row → return `200`**, in
that order, in one transaction. Nothing about Meta or the domain
happens before the row is durable — a crash right after the `200` loses
nothing, because the fact of having accepted the event already survived
to disk.

A `@Scheduled` poller (its own `WebhookProcessingConfig` +
`@EnableScheduling`, kept off the main application class the same
reason `JpaAuditingConfig` is separate — so slice tests never
accidentally pull in a live scheduler) picks up `PENDING` rows on a
short fixed delay (2s) and processes them:

```
for each entry in payload.entries:
  resolve tenant via phone_number_id → whatsapp_connection            (§5)
  if no mapping found: mark row DROPPED, log, stop — never guess       (whatsapp-integration.md §3)
  for each message in entry:
    customerService.findOrCreateFromInbound(tenant, from, profileName)
    conversationService.findOrOpenForCustomer(tenant, customer.id)     (ADR-013)
    messageService.recordInbound(tenant, conversation.id, wa_message_id, body)   (ADR-012 — this call is the real dedupe boundary, not this table)
  for each status update in entry:
    look up Message by (tenant_id, wa_message_id); apply the matching transition   (§7)
mark row PROCESSED
```

A single delivery can carry several messages and status updates in one
payload (Meta batches) — the loop reflects that; nothing here assumes
one event, one message.

**On failure** (exception partway through): increment `attempt_count`,
set `next_attempt_at = now + 4^attempt_count` seconds (same formula
`whatsapp-integration.md` §6 already specifies for outbound), leave
`status = PENDING`. After 5 attempts, `status = FAILED` — terminal,
surfaced only in logs/future-Audit-module territory, not retried
further; a real, persistent failure at this point needs a human, not
another timer.

**Note on redelivery safety:** if the *same* Meta delivery somehow gets
persisted as two `webhook_event` rows (Meta itself retrying because our
`200` was lost in transit), both rows process fine independently —
`recordInbound`'s dedupe on `(tenant_id, wa_message_id)` collapses them
to one `Message` regardless of how many times this table's poller sees
the same underlying WhatsApp message id. This table is a durability and
retry mechanism, not the idempotency boundary; ADR-012 already put that
where it belongs.

## 5. Tenant resolution

Exactly `whatsapp-integration.md` §3, now with a concrete lookup:
`WhatsAppConnectionRepository.findByPhoneNumberId(phoneNumberId)`. Its
`tenant_id` is trusted without further checks — it is, alongside the
authenticated session, one of only two legitimate sources of tenant
identity in the whole system (Rule 3).

## 6. Outbound: the gateway boundary

```java
public interface WhatsAppGateway {
    SendResult sendText(WhatsAppConnection connection, String toPhone, String body);
    SendResult sendTemplate(WhatsAppConnection connection, String toPhone, String templateName,
                             String languageCode, List<String> params);
}
```

`MetaWhatsAppGateway` is the only class in the codebase allowed to
import a Meta HTTP shape (Rule 4) — it calls
`POST https://graph.facebook.com/{version}/{phone_number_id}/messages`
using `RestClient` (already a dependency, moved from test to main
scope) with `Authorization: Bearer <decrypted access_token>`, and maps
Meta's JSON response into `SendResult` (`waMessageId` on success, an
error detail on failure). No other module ever sees that JSON shape.

**Outbound sender** — the actual outbox consumer ADR-012 promised in
Phase 5, now built: a second `@Scheduled` poller selects `Message` rows
where `direction = OUTBOUND AND status = PENDING AND (next_attempt_at
IS NULL OR next_attempt_at <= now)`, resolves the conversation → customer
→ phone and the tenant's `WhatsAppConnection`, and calls `sendText`
(or `sendTemplate` — see §8). On success: `wa_message_id` set,
`status = SENT`. On failure: same `4^attempt_count`-second backoff and
5-attempt cap as §4, landing on `status = FAILED` — terminal, matching
message-domain.md §6's point that the dashboard must render `PENDING`/
`FAILED` honestly rather than assuming synchronous success.

This requires adding `attempt_count`, `next_attempt_at`, and
`failure_reason` columns to `message` — the retry bookkeeping
Phase 5 deliberately didn't add yet, because nothing consumed
`PENDING` rows until now.

## 7. Status webhook → message status

An inbound status-update entry (`sent`/`delivered`/`read`/`failed`,
keyed by the outbound message's own `wa_message_id`) looks up the
`Message` via `MessageRepository.findByTenantIdAndWaMessageId` (already
built in Phase 5 for exactly this) and calls a matching transition
method on the entity — `markDelivered()`, `markRead()`, `markFailed(reason)`
— the methods `message-domain.md` §2 named but deliberately left
unwritten ("nothing advances it past `PENDING` until Phase 6"). A
status update for a `wa_message_id` we don't recognize is logged and
dropped, same as an unmapped `phone_number_id` — never guessed at.

## 8. The 24-hour window's other half: template sends

`message-domain.md` §3 made sending outside the window a flat reject.
Now that `sendTemplate` exists, `MessageService.sendOutbound` gains an
optional `templateName`/`languageCode`/`params` argument: if the window
is closed *and* a template is provided, the send proceeds via
`sendTemplate` instead of being rejected; if the window is closed and no
template is provided, the rejection from Phase 5 stands unchanged. A
template is not required inside the window — Meta allows sending one
any time — so supplying one when the window is open just sends via
template instead of free text, which is a legitimate choice, not an
error.

## 9. Authorization summary

| Action | Who |
|---|---|
| Connect / reconfigure WhatsApp | Owner, Admin |
| View connection status | Owner, Admin |
| Receive webhook events | Meta only, via signature verification — no session, this is machine-to-machine |
| Send a message (free-form or template) | Any authenticated tenant member — unchanged from Phase 5 §5 |

## 10. Tenant isolation

Same shape as every prior phase, with one deliberate, documented
exception: `phone_number_id → tenant_id` resolution is the join between
Meta's world and ours and is *not* filtered by a tenant the caller
doesn't have yet — that lookup **is** how tenant gets established for
inbound traffic. Every lookup downstream of that point (`customer`,
`conversation`, `message`) goes through the same tenant-scoped
repository methods as every other phase, with `tenantId` now sourced
from this webhook-trusted lookup instead of a session, exactly as
`system-architecture.md` §6 already specifies as the second legitimate
source.

## 11. Observability

Per NFR-OBS-001, every log line in the inbound/outbound processing path
includes `tenantId`, `conversationId` (once known), and `waMessageId`
(once known) so one message's journey (ingest → persist → send) is
greppable across the whole pipeline. This is structured logging, not a
metrics/tracing platform — Prometheus-style counters for
NFR-OBS-003 stay `[TBD]`, matching non-functional-requirements.md's own
stated policy of not inventing numbers before there's load to measure.

## 12. Configuration

New `app.whatsapp.*` properties, following the existing
`app.cors.allowed-origins` pattern (`@Value`-injected, `${ENV_VAR}` in
`application.yml`, required — no default — in `-dev.yml`/`-prod.yml`):

```yaml
app:
  whatsapp:
    verify-token: ${WHATSAPP_VERIFY_TOKEN}
    app-secret: ${WHATSAPP_APP_SECRET}
    credential-encryption-key: ${WHATSAPP_CREDENTIAL_ENCRYPTION_KEY}
    graph-api-base-url: ${WHATSAPP_GRAPH_API_BASE_URL:https://graph.facebook.com/v21.0}
```

None of these are ever tenant data; they belong to *our* Meta app, one
per environment.
