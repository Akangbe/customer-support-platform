# Message Domain Design (Phase 5)

**Status:** Proposed · v0.1

Messaging is the last piece of the core conversation workflow before the
WhatsApp integration (Phase 6) has anything real to plug into. This
document settles the Message entity, the send/receive contract, and the
24-hour customer-service window rule ahead of coding it, per the
engineering process.

---

## 1. Scope

**In scope:**
- `Message` entity: inbound/outbound, persisted durably, associated with a
  conversation (FR-MSG-002, FR-MSG-003, FR-MSG-004).
- `POST /conversations/{id}/messages` — an agent sending a reply
  (FR-MSG-001), text only.
- `GET /conversations/{id}/messages` — conversation history, paginated
  (FR-CON-005, FR-CUS-002).
- The 24-hour service-window check on outbound sends (FR-WA-009), enforced
  here because `whatsapp-integration.md` §7 assigns it to the Messaging
  module, not the WhatsApp module — it's a domain rule about
  `conversation.last_inbound_at`, not a Meta API detail.
- `recordInbound(...)` — an idempotent domain method Phase 6's webhook
  will call, built now the same way `findOrOpenForCustomer` was built in
  Phase 4 before anything called it over HTTP.
- `Conversation.recordInboundAt` / `recordOutboundAt` — finally populating
  the timestamp columns that have existed since Phase 4 (FR-CON-006).
- Authoring the ADR-012 body properly (idempotent ingestion), which the
  decision log has flagged as due before this phase since Phase 0.

**Out of scope (deferred, not forgotten):**
- Actually talking to WhatsApp. Outbound messages persist as `PENDING`
  and stop there — matching `system-architecture.md` §5's outbound
  sequence diagram exactly up to "persist message (PENDING)". The outbox
  consumer that calls the WhatsApp gateway and drives
  `SENT → DELIVERED → READ / FAILED` is Phase 6.
- The inbound HTTP path (webhook) — Phase 6. `recordInbound` exists as a
  service method only until then.
- Template messages. FR-WA-009 says the system "requires an approved
  template outside" the 24h window; templates are a Meta-specific concept
  (approved content, WhatsApp module territory) that doesn't exist yet.
  Phase 5's window check has exactly one outcome when the window is
  closed: reject with a clear error. Phase 6 adds the template path as an
  alternative to rejection, not a replacement of the check.
- Media/attachments (FR-MSG-005's "media/file" half). Needs the Storage
  module (Cloudflare R2) and the `Attachment` entity from
  `domain-model.md`, neither of which exist yet. Text-only for now.
- Any restriction on *who* may send on a conversation beyond tenant
  membership. No requirement asks for "only the assignee may reply" —
  see §5.

## 2. Message model

| Column | Type | Notes |
|---|---|---|
| `id` | uuid, PK | |
| `tenant_id` | uuid, FK → tenant.id, not null | |
| `conversation_id` | uuid, FK → conversation.id, not null | |
| `direction` | enum: `INBOUND`, `OUTBOUND` | |
| `status` | enum: `PENDING`, `SENT`, `DELIVERED`, `READ`, `FAILED`, nullable | Outbound-only, per `domain-model.md`; null for inbound. Starts `PENDING`; nothing advances it past that until Phase 6 wires the outbox/webhook — no dead transition methods added early. |
| `body` | text, not null | Text content. Bounded to WhatsApp's own 4096-char text limit — no reason to allow what the channel can't send. |
| `sender_user_id` | uuid, FK → app_user.id, nullable | Set for outbound only: the agent who sent it. Null for inbound. |
| `wa_message_id` | varchar, nullable | Meta's message id. Null until Phase 6 populates it on inbound receipt / outbound confirmation. |
| `created_at` | timestamptz, not null | |

**Idempotency (ADR-012):** a unique index on `(tenant_id, wa_message_id)`
where `wa_message_id IS NOT NULL`. Two webhook deliveries of the same
Meta message can never create two rows — the second insert fails the
constraint, and `recordInbound` treats that as "already processed," not
an error. This is dormant in Phase 5 (nothing sets `wa_message_id` yet)
but the constraint has to exist on the table from day one, because
retrofitting a uniqueness constraint onto a live table with the
possibility of pre-existing duplicates is a much worse day than adding it
up front.

## 3. Sending (outbound)

```
POST /api/v1/conversations/{conversationId}/messages
{ "body": "..." }
```

1. Resolve the conversation within the caller's tenant (404 otherwise —
   same guessable-id protection as every other resource).
2. Reject if the conversation is `CLOSED` (409) — an agent must reopen it
   first; sending shouldn't silently resurrect a resolved conversation
   the way an inbound message does under ADR-013. Those are different
   actors with different intent: a customer messaging back is "the
   conversation continues," an agent's own action should be deliberate.
3. Check the 24-hour window: `last_inbound_at` must be non-null and
   within 24 hours of now. If the window is closed (or has never been
   open — a conversation with no inbound message yet), reject with 409.
   This is the honest current behavior; Phase 6 adds the template
   alternative.
4. Persist the message: `direction=OUTBOUND`, `status=PENDING`,
   `sender_user_id=<caller>`, `wa_message_id=null`.
5. Update `conversation.last_outbound_at = now()`.

No further authorization check beyond tenant membership — see §5.

## 4. Receiving (inbound) — domain method only

```java
MessageService.recordInbound(tenantId, conversationId, waMessageId, body, receivedAt)
```

- Idempotent on `(tenantId, waMessageId)`: if a message with that key
  already exists, return it unchanged — this is what makes Meta's
  at-least-once webhook retries safe (FR-WA-008, ADR-012).
- Otherwise: persist `direction=INBOUND`, `status=null`, `body`,
  `wa_message_id`, and update `conversation.last_inbound_at = receivedAt`.
- Not reachable over HTTP in Phase 5. Phase 6's webhook controller calls
  it after tenant resolution and signature verification
  (`whatsapp-integration.md` §3–4); Phase 5's integration tests call the
  service bean directly to set up window-open/closed scenarios, the same
  boundary `findOrOpenForCustomer`'s tests already exercise a layer below
  the HTTP surface.

## 5. Authorization

| Action | Who |
|---|---|
| Send a message | Any authenticated tenant member |
| View conversation history | Any authenticated tenant member |

Matches the existing pattern for close/reopen/priority (Phase 4 §7): no
FR asks for "only the assignee may reply," and inventing that boundary
now would be speculative (Architecture Principles Rule 5). If real usage
shows agents stepping on each other's replies, that's a claim-discipline
problem to solve via ADR-017's assignment model, not a new authorization
rule on send.

## 6. Why status lives on Message, not just Conversation

`Conversation.last_outbound_at` says *when* the business last replied;
it doesn't say whether that specific reply actually reached Meta. Each
outbound message needs its own delivery lifecycle (`PENDING → SENT →
DELIVERED → READ`, or `FAILED`) because a dashboard showing "message
sent" for something still stuck in an outbox retry loop would be a lie.
`domain-model.md`'s ER diagram already puts direction and `wa_message_id`
on `Message`, not `Conversation` — this just extends that with `status`.

## 7. Tenant isolation

Identical shape to every prior phase: `tenant_id` on the table,
`findByIdAndTenantId` (or the conversation-scoped equivalent) on every
lookup, tenant id sourced only from the authenticated principal, and a
cross-tenant test proving a guessed conversation id 404s rather than
leaking another tenant's messages.
