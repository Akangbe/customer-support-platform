# Realtime Design (Phase 7)

**Status:** Proposed · v0.1

FR-RT-001–003 want new messages, conversation/assignment changes, and
unread counts to reach the dashboard without a refresh. This document
picks the transport, the auth model, the event contract, and — per the
engineering process — is paired with a new ADR (ADR-018) since the
transport choice is exactly the kind of reversible-but-important
decision Rule 6 asks for.

---

## 1. Scope

**In scope:**
- STOMP over WebSocket (Spring's built-in support — no new
  infrastructure) as the transport.
- Session-cookie authentication at the WebSocket handshake, per
  ADR-014's own prediction ("Phase 7 can authenticate the upgrade
  handshake with the same cookie").
- Per-tenant topics with subscription-time tenant-isolation
  enforcement — the WebSocket-world equivalent of Rule 3.
- Two event types — conversation-changed, message-event — published by
  the Conversation and Message modules via Spring's own
  `ApplicationEventPublisher` (framework-native, zero coupling to
  WebSocket mechanics) and fanned out by a new `notification` module,
  which is the only module that knows STOMP exists (mirrors how the
  `whatsapp` module is the only one that knows Meta exists).
- ADR-018: in-memory STOMP broker now, not Redis-backed — for the same
  single-instance reason ADR-014 deferred a shared session store.

**Out of scope (deferred, not forgotten):**
- Agent presence ("who's online"). `system-architecture.md` names this
  as a future Redis use, but no FR in the MVP scope asks for it yet —
  Rule 5.
- A persisted, per-agent "last read" concept for FR-RT-003. See §5.
- Horizontal scaling of the broker (Redis/RabbitMQ STOMP relay) — the
  subject of ADR-018's revisit condition, not a Phase 7 build.
- Replaying missed events after a disconnect. See §6 — the frontend
  re-fetches via REST on reconnect; WebSocket is a refresh signal, not
  an event log.
- SockJS fallback. This is a first-party dashboard we control, not a
  public API with unknown browser support to accommodate.

## 2. Transport: STOMP over WebSocket

Spring's `spring-boot-starter-websocket` gives a full STOMP broker,
subscription management, and heartbeats for free — building a
hand-rolled `WebSocketHandler` registry keyed by tenant would be
reinventing exactly that (Rule 5, the other direction: don't build
infrastructure the framework already hands you).

- Endpoint: `/ws` (raw WebSocket, no SockJS).
- Broker prefix: `/topic` (server → client only; agents never publish
  over the socket — replies still go through the REST API Phase 5
  already built, so no `/app` destination prefix is needed).
- Destinations, one topic per concern per tenant:
  - `/topic/tenants/{tenantId}/conversations`
  - `/topic/tenants/{tenantId}/messages`

## 3. Authentication and tenant isolation

The `/ws` handshake is a normal HTTP `GET` first — Spring Security's
existing session-cookie authentication (ADR-014) already gates it via
the same `anyRequest().authenticated()` rule every other endpoint uses.
No new auth code is needed to reject an unauthenticated handshake.

What *is* new: a `HandshakeInterceptor` copies the already-authenticated
`AuthenticatedPrincipal` from the `HttpSession` into the WebSocket
session's attributes, so it's available for the life of that socket
without re-hitting the session store per message.

**Subscription-time tenant check** (the actual Rule-3 equivalent): a
`ChannelInterceptor` on `SUBSCRIBE` frames parses the `{tenantId}`
segment out of the destination and compares it against the
principal's own `tenantId` from the handshake attributes. A mismatch
is rejected — same posture as every REST 404-on-guess, adapted to
STOMP's frame model (there's no response body to 404 into, so the
subscribe attempt is simply refused).

## 4. Event contract

Two plain Java records, owned by the modules that raise them —
`ConversationChangedEvent(UUID tenantId, UUID conversationId)` in
`conversation`, `MessageEvent(UUID tenantId, UUID conversationId, UUID
messageId)` in `message`. Deliberately **identifiers only, not a
payload** — see §4.1.

```
ConversationService  ─┐
                       ├─ publishes via ApplicationEventPublisher
MessageService        ─┘

notification module ── @TransactionalEventListener(AFTER_COMMIT) ── SimpMessagingTemplate
```

`ConversationService` and `MessageService` depend on nothing but
Spring's own `ApplicationEventPublisher` — they have no idea WebSocket
exists. The `notification` module depends on `conversation` and
`message` (to know the event shapes and to re-fetch state), the same
dependency direction the `whatsapp` module already has. Nothing points
the other way.

**Publish sites:**
- `ConversationChangedEvent`: `findOrOpenForCustomer` (new or reopened
  conversation), `assign`, `unassign`, `close`, `reopen`,
  `updatePriority`.
- `MessageEvent`: `recordInbound`, `sendOutbound`, and
  `applyDeliveryStatus`'s transitions (an agent watching a thread sees
  delivery ticks move without refreshing).

### 4.1 Why identifiers only, and why AFTER_COMMIT

`@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)` —
not the default `BEFORE_COMMIT` — so a subscriber is only ever told
about a fact that has actually survived to Postgres (Rule 2: Postgres
is the single source of truth; nobody should be notified of a change
that could still roll back).

Because the listener runs after commit, the entity the publisher
touched is detached and possibly stale by the time the listener runs.
So the event carries only `tenantId`/`conversationId`/`messageId`, and
the listener re-fetches through the same tenant-scoped service methods
everything else uses — `ConversationService.getWithinTenant`,
a new `MessageService.getWithinTenant` (message-domain.md didn't need
one; the WebSocket path does). The broadcast payload is then just
`ConversationResponse.from(...)` / `MessageResponse.from(...)` — the
**same DTOs the REST API already returns**, so the frontend can reuse
one TypeScript type for both a fetch response and a socket push.

## 5. FR-RT-003: unread counts, scoped down

Nothing in the domain model tracks "has this agent seen this
conversation" — there's no `last_read_at` per user per conversation,
and no FR elsewhere asks for one. Building that now, for a count that
FR-RT-003 itself hedges with "where appropriate," would be exactly the
kind of speculative feature-add Rule 5 blocks.

**Phase 7's answer:** the `messages` topic already gives the frontend
everything it needs to maintain unread counts *client-side* — increment
per new inbound `MessageEvent` on a conversation the agent doesn't have
open, clear on open. No new backend concept. Revisit only if a real
requirement surfaces for unread state that has to survive a page
reload or sync across an agent's devices — at that point it's a
`last_read_at` column and a real backend feature, not a guess made now.

## 6. Reconnection semantics

WebSocket here is a **refresh hint, not an event log.** If a socket
drops and reconnects, nothing replays what was missed — the dashboard
re-fetches current state via the REST endpoints Phases 3–6 already
built. This is a direct consequence of Rule 2: the socket was never
the source of truth, so there's nothing to replay from it that Postgres
doesn't already have. Simpler than building server-side event buffering
for a gap that a normal REST re-fetch already closes correctly.

## 7. Why in-memory, not Redis (ADR-018)

Spring's default `SimpleBroker` (in-process, in-memory) handles
Phase A's single instance completely. Redis is already scoped in this
project as the store for exactly this kind of ephemeral, multi-instance
fan-out (`system-architecture.md` §2) — but standing it up now, for a
single process that doesn't need cross-instance delivery yet, is the
same premature-infrastructure mistake Rule 5 exists to block. Full
reasoning in ADR-018.

## 8. Authorization

| Action | Who |
|---|---|
| Open a WebSocket connection | Any authenticated tenant member (same session cookie as REST) |
| Subscribe to `/topic/tenants/{tenantId}/...` | Only if `{tenantId}` matches the caller's own tenant |
| Receive conversation/message events | Everyone subscribed — matches the existing REST authorization table (any tenant member can view any conversation, Phase 4 §7) |

No new privilege tier — realtime visibility mirrors REST visibility
exactly, because it's the same data.
