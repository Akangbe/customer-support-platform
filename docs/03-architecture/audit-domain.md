# Audit Design (Phase 8)

**Status:** Proposed · v0.1

FR-AUD-001–003 want administrative actions, assignment changes, and
configuration changes recorded. Three prior phases already flagged
concrete gaps waiting on this module (`whatsapp-domain.md` §1, §4) —
this is where they get closed. Per the engineering process, this is
paired with ADR-019, since the persistence-timing decision below is
exactly the reversible-but-important kind Rule 6 asks for.

---

## 1. Scope

**In scope:**
- `AuditLogEntry`: tenant-scoped, who did what to what, when.
- Publish sites, matching the three FRs precisely (not "everything,"
  which would be scope creep — Rule 5):
  - FR-AUD-001 (administrative actions): `UserService.invite`,
    `changeRole`, `disable`, `enable`.
  - FR-AUD-002 (assignment changes): `ConversationService.assign`,
    `unassign`.
  - FR-AUD-003 (configuration changes): `WhatsAppConnectionService.connect`
    — the exact gap `whatsapp-domain.md` §1 named.
- `GET /api/v1/audit-log`: Owner/Admin only, paginated, newest first. A
  record nobody can read doesn't serve FR-AUD's purpose — write-only
  audit logging is half a feature.
- ADR-019: audit persistence is synchronous and same-transaction, the
  opposite trade-off from Phase 7's realtime notifications, deliberately.

**Out of scope (deferred, not forgotten):**
- Conversation close/reopen/priority changes. FR-AUD-002 says
  "assignment changes," not "every conversation change" — those are
  already realtime-broadcast (Phase 7) and not named here. Extending
  audit to them without a requirement is exactly Rule 5's target.
- Filtering/search on the read endpoint beyond pagination. Nobody has
  asked for it yet; add it when a real need shows up, not speculatively.
- Message-level audit (who sent what). Messages already have their own
  durable history (`message-domain.md`) with `sender_user_id` — a
  second audit trail of the same facts would be redundant, not
  additive.
- Tamper-evidence (hash chaining, append-only enforcement beyond normal
  DB permissions). No requirement asks for it at this stage; revisit if
  a real compliance need does.

## 2. AuditLogEntry model

| Column | Type | Notes |
|---|---|---|
| `id` | uuid, PK | |
| `tenant_id` | uuid, FK → tenant.id, not null | |
| `actor_user_id` | uuid, FK → app_user.id, not null | Every publish site in scope is an authenticated user action — no system/webhook-triggered audit events exist yet, so this is never null today |
| `action` | enum: `USER_INVITED`, `USER_ROLE_CHANGED`, `USER_DISABLED`, `USER_ENABLED`, `CONVERSATION_ASSIGNED`, `CONVERSATION_UNASSIGNED`, `WHATSAPP_CONNECTED` | |
| `target_type` | varchar | `"USER"`, `"CONVERSATION"`, `"WHATSAPP_CONNECTION"` |
| `target_id` | uuid | |
| `detail` | text | Short, human-readable — e.g. "Changed role from AGENT to MANAGER". Never a credential (WHATSAPP_CONNECTED's detail names the phone_number_id, never the access token) |
| `occurred_at` | timestamptz, not null | |

## 3. The event contract, and why it's not Phase 7's

Producers publish a single generic `AuditEvent(tenantId, actorUserId,
action, targetType, targetId, detail)` via Spring's
`ApplicationEventPublisher` — same mechanism Phase 7 established, so
`UserService`/`ConversationService`/`WhatsAppConnectionService` stay
just as unaware of *how* an audit trail gets written as they are of
WebSocket. The event type itself lives in the `audit` module (not each
producer's own package, unlike `ConversationChangedEvent`/`MessageEvent`)
because it's genuinely generic — one shape covers seven different
actions across three modules, and defining seven near-identical
one-off records would be repetition without benefit. Producers depend
on this one small, logic-free record type the same way every module
already depends on `common.error.ErrorResponse` — a shared contract,
not a peripheral integration module reaching back into core domain.

**This is a different event, not a reuse of Phase 7's**, because it
needs different information: `ConversationChangedEvent` is
identifiers-only by design (realtime-domain.md §4.1 — the listener
re-fetches current state, past state doesn't matter for a UI refresh).
Audit needs the opposite: *who* acted and *what specifically happened*,
captured at the moment of mutation — information that's gone by the
time a listener could re-fetch "current state" later. A single
mutation (e.g. `assign`) raises both events, for two different readers
with two different needs.

## 4. Same-transaction, not best-effort (ADR-019)

Phase 7's `RealtimeEventListener` deliberately fires
`AFTER_COMMIT` and swallows its own failures — a lost broadcast is a
UI staleness problem, never worth failing the triggering request over.

Audit logging makes the **opposite** call: `AuditLogListener` uses a
plain `@EventListener` (no transaction-phase annotation), which Spring
invokes synchronously, inside the same transaction as the action being
audited. If the audit write fails, the whole transaction — including
the business mutation — rolls back. An admin action that silently left
no audit trail is a worse outcome than a failed request the caller can
retry; "the change happened but nobody can prove who made it" is
precisely the failure mode FR-AUD-001–003 exist to prevent. Full
reasoning in ADR-019.

## 5. Threading the actor through

`ConversationService.assign`/`unassign` already take `actingUserId` —
no signature change needed. `UserService.invite`/`changeRole`/`disable`/
`enable` and `WhatsAppConnectionService.connect` currently take
`actingRole` but not the caller's own id; each gains an `actorUserId`
parameter, sourced from `principal.getUserId()` at the controller layer
exactly like `actingRole` already is from `principal.getRole()`. This
is a small, mechanical signature change, not a redesign.

## 6. Authorization

| Action | Who |
|---|---|
| Trigger an audited action | Whatever that action already requires (ADR-016 for user management, ADR-017 for assignment, Owner/Admin for WhatsApp config) — auditing adds no new gate |
| View the audit log | Owner, Admin |

Matches the existing pattern: auditing observes authorization decisions
made elsewhere, it doesn't add its own new privilege tier for
*performing* an action — only for *reading the record of* one.

## 7. Tenant isolation

Same shape as every prior phase: `tenant_id` on the table, every read
scoped to the caller's own tenant, sourced only from the authenticated
principal.
