# Decision Log

Every significant architectural decision lives here (Architecture Principles, Rule 6). Format: **Status / Context / Options Considered / Decision / Rationale / Consequences / Revisit Conditions.**

---

## ADR-011 — Phased Meta onboarding

**Status:** Accepted

### Context

The product vision is multi-tenant Tech Provider access — connecting
*other businesses'* WhatsApp Business Accounts, not just our own. Meta
gates that behind App Review and Advanced Access for
`whatsapp_business_messaging`/`whatsapp_business_management`, a
weeks-long review that can fail and that we do not control the
timeline of. Waiting on it before building or launching anything would
tie the entire project's first release date to a third party's queue.
RitaRock EduConsult, the first tenant, is a business we already have a
direct relationship with and can onboard without going through another
company's WABA at all. This decision was flagged since the Phase 0
audit as needing its full body authored before Phase 6 (WhatsApp
Integration) began, since Phase 6 is where the onboarding mode this ADR
picks actually gets built.

### Options Considered

1. **Wait for Tech Provider approval before building anything
   WhatsApp-related.** Simplest sequencing, but blocks all progress on
   an external, unpredictable timeline.
2. **Launch Phase A on RitaRock in direct-developer mode** (one owned
   WABA, System User token, no App Review needed) to prove the full
   product loop end-to-end, while submitting for Tech Provider / App
   Review **in parallel** (Phase B), and flipping on multi-tenant
   onboarding once approved (Phase C).
3. **Build a mock/stub WhatsApp integration** for development and defer
   all real Meta integration until Tech Provider status clears.

### Decision

**Option 2** — phased rollout: Phase A (direct-developer, RitaRock only)
now, Phase B (App Review submission) in parallel, Phase C (multi-tenant
onboarding) once approved. Critically, the **code is multi-tenant from
day one** regardless of phase — `WhatsAppConnection` is a tenant-scoped
table from its first migration (`whatsapp-domain.md` §2), not a
RitaRock-specific hardcoded config. Only the *onboarding path* differs
between phases: in Phase A, a `WhatsAppConnection` row is created by an
Owner/Admin pasting credentials generated manually in Meta Business
Manager; in Phase C, the same row is populated by the output of
Embedded Signup instead. The rest of the system — webhook processing,
tenant resolution, sending, status tracking — never needs to know which
phase produced the row it's reading.

### Rationale

- Option 1 makes the entire roadmap hostage to Meta's review queue,
  which can take weeks and can fail outright — unacceptable for
  proving out a real product with a real business waiting.
- Option 3 (mocking Meta entirely) means the riskiest, least-controlled
  part of the system (per `whatsapp-integration.md`'s own framing:
  "the highest-risk part of the system") gets validated last, not
  first — backwards. Real webhook signature verification, real
  message-status timing, and real API failure modes need a real
  integration exercised as early as possible, and RitaRock is exactly
  the willing real business that makes that possible without App
  Review.
- Building the data model tenant-scoped from the start (rather than
  RitaRock-specific now, generalized later) means Phase C is a change
  to *how a row is created*, not a schema migration and a rewrite of
  every module that reads `WhatsAppConnection` — the multi-tenant
  shape was nearly free to build correctly the first time and would
  have been real rework to retrofit.

### Consequences

**Gain:** the product ships and gets validated against a real WhatsApp
Business Account immediately, independent of Meta's review timeline;
the Tech Provider submission can proceed in parallel without blocking
anything; Phase C requires no data-model changes, only a new onboarding
UI/flow (Embedded Signup) that populates the same table a manual form
populates today.

**Sacrifice:** until Phase C, the platform can only serve tenants
willing to generate and hand over their own System User token manually
— real friction for onboarding a second or third tenant business before
Tech Provider status clears. Accepted as a bounded, known limitation of
Phase A, not a permanent one.

### Revisit Conditions

Revisit if App Review is denied or indefinitely delayed — at that
point, evaluate whether a semi-manual onboarding flow (Owner/Admin
still pastes credentials, like Phase A, but formalized as the permanent
onboarding path rather than a stopgap) is an acceptable long-term
compromise for growing beyond RitaRock without Tech Provider status.

---

## ADR-012 — Idempotent ingestion / transactional outbox

**Status:** Accepted

### Context

`architecture-principles.md` Rules 7–8 and `whatsapp-integration.md` §5–6
already commit the system to two related guarantees: inbound webhook
events must be safe to process more than once (Meta retries — delivery
is at-least-once, never exactly-once), and outbound sends must survive a
transient failure without silently dropping a reply. Phase 5 (Message
domain) is where both guarantees first need a concrete data-model shape,
because that's where `Message` rows and their `wa_message_id` come into
existence — this ADR was flagged since the Phase 0 audit as needing its
full body authored before that happened, rather than staying an
operational description scattered across two other docs.

### Options Considered

1. **Application-level dedupe check** — before inserting, `SELECT` for an
   existing row with the same `(tenant_id, wa_message_id)`; skip if found.
2. **Database-enforced uniqueness** — a unique constraint on
   `(tenant_id, wa_message_id)`; treat the resulting constraint violation
   as "already processed," not an error.
3. **External dedupe store** (Redis `SETNX` on the message id, short TTL)
   ahead of touching Postgres at all.

For outbound reliability specifically:

A. **Synchronous send, no outbox** — call Meta inline during the HTTP
   request that persists the message.
B. **Persist-then-async-send (outbox)** — persist the message as
   `PENDING` in the same transaction as the domain write, then hand off
   to an async sender that calls Meta and updates status, retrying with
   backoff on failure.

### Decision

**Option 2** for inbound idempotency, **Option B** for outbound
reliability.

Inbound: a unique index on `(tenant_id, wa_message_id)` where
`wa_message_id IS NOT NULL` is the source of truth for "have we seen
this before" — not an application-level check-then-insert, which has a
race window under concurrent delivery (two webhook retries landing at
once) that only the database can actually close. `recordInbound` treats
the resulting constraint violation as a no-op success, not a failure.

Outbound: every outbound message is persisted as `PENDING` in the same
transaction as the API request that created it, before any attempt to
reach Meta. A separate sender component (the "outbox" — Phase 6) picks
up `PENDING` messages and drives them to `SENT`, retrying transient
failures with backoff (`4^X` seconds, per `whatsapp-integration.md` §6)
rather than the request thread trying once and giving up.

### Rationale

- Option 1 (app-level check-then-insert) is a TOCTOU race: two concurrent
  deliveries of the same retried webhook can both pass the `SELECT`
  before either finishes its `INSERT`, producing a duplicate — exactly
  the failure FR-WA-008 calls "load-bearing" to prevent. A unique
  constraint has no such window; Postgres itself is the serialization
  point.
- Option 3 (Redis dedupe) introduces a second system that has to agree
  with Postgres about what's been processed, with its own failure modes
  (TTL expiry before the corresponding DB write commits, cache/DB
  divergence on partial failure). Redis is already scoped in this
  project as *ephemeral, never authoritative* (`system-architecture.md`
  §2) — using it as the source of truth for "has this message been
  ingested" would violate that scoping for no real gain over a DB
  constraint.
- Option A (synchronous send) ties the API response — and the record of
  "we tried to reply" — to Meta's availability and latency in the same
  request. A slow or momentarily-down Meta call would make persisting the
  agent's reply fail too, which is backwards: the reply is real the
  moment the agent hits send, and the record of that intent must survive
  independent of whether the network hop to Meta succeeds on the first
  try.

### Consequences

**Gain:** duplicate webhook deliveries cannot create duplicate messages,
enforced structurally rather than by discipline in application code; a
transient Meta outage degrades to slower delivery (retried by the
outbox) rather than silently lost replies or a failed agent-facing
request.

**Sacrifice:** outbound status is now eventually consistent from the
agent's point of view — a freshly sent message shows `PENDING` until the
outbox actually dispatches it, not `SENT` immediately. The dashboard has
to render that state honestly rather than assuming synchronous success.
The outbox consumer itself (polling vs. a scheduled sweep vs. an event
trigger) is a Phase 6 implementation decision, not decided here — this
ADR fixes the data-model contract (`PENDING`-first, unique dedupe key),
not the sender's execution mechanism.

### Revisit Conditions

Revisit the dedupe mechanism only if a future provider integration
can't supply a stable per-message id to key the unique index on — not a
concern for WhatsApp, which guarantees one. Revisit the outbox mechanism
once Phase 6 has real send volume to observe (queue-backed vs.
scheduled-sweep tradeoffs are easier to judge with actual latency data
than in the abstract).

---

## ADR-013 — Conversation reopen semantics

**Status:** Accepted

### Context

`domain-model.md` leaves one lifecycle question explicitly open: when a new inbound WhatsApp message arrives for a customer whose most recent conversation is `CLOSED`, does the system reopen that conversation, or create a new one? The original docs deferred this to a `database-design.md` that does not exist yet. It has to be settled now because it is a domain invariant (Phase 4 scope, per the engineering process), not a schema detail — the schema just encodes whatever the domain decides.

### Options Considered

1. **Reopen the existing (most recent) conversation.** The customer's conversation history stays as one continuous thread across close/reopen cycles.
2. **Always create a new conversation** on inbound-after-close. Each conversation is a clean, bounded interaction; history is reconstructed by querying all conversations for a customer.
3. **Configurable per tenant.** Defer the choice to tenant settings.

### Decision

**Option 1 — reopen the most recent closed conversation** when a new inbound message arrives for that customer, provided no other conversation for that customer is already `OPEN` or `ASSIGNED`. Reopening transitions status back to `OPEN`, clears `closed_at`, and updates `last_inbound_at`; the previous assignment is not silently restored (assignment on reopen is a matter for the Assignment module — falls back to unassigned/routing, not carried over automatically).

### Rationale

- Matches FR-CUS-002 ("maintain per-customer conversation history") most directly — agents see one continuous thread per customer relationship rather than a fragmented list they must mentally stitch together.
- Matches how support platforms customers are already familiar with behave (Zendesk, Intercom, Front) — least surprising default for both agents and, later, for support-manager reporting.
- Simpler aggregate lifecycle: one open conversation per customer at a time, rather than needing logic to decide which of several conversations an inbound message belongs to.
- Option 3 (configurable) adds real complexity (a tenant setting, plus code paths for both behaviors) with no validated demand yet — violates Architecture Principles Rule 5 (don't build for hypothetical requirements).

### Consequences

**Gain:** a single, unambiguous rule for conversation resolution on inbound messages; simpler queries ("the customer's conversation" is well-defined); cleaner history for managers reviewing a customer relationship.

**Sacrifice:** a conversation's timeline can span a long calendar period across multiple close/reopen cycles, so "conversation duration" as a metric needs care later (response-time analytics should likely measure per open/close cycle, not the conversation's full lifetime) — flagged for Phase 9 (Support Operations).

### Revisit Conditions

Revisit if a real tenant asks for strict per-incident conversation boundaries (e.g., billing or reporting tied to discrete "tickets" rather than continuous threads) — at that point Option 3 becomes justified by actual demand rather than speculation.

---

## ADR-014 — Session-based authentication, not JWT

**Status:** Accepted

### Context

Phase 2 needs to pick how users authenticate (FR-AUTH-001). The backend is a single deployable modular monolith; the frontend is a separate Next.js origin calling it as a REST API. No external third-party API consumers are in scope.

### Options Considered

1. **Server-side session + HttpOnly cookie**, via Spring Security's built-in session management.
2. **Stateless JWT** (access + refresh tokens), validated per-request without server-side state.
3. **OAuth2/OIDC via an external identity provider.**

### Decision

**Option 1 — session-based authentication** with an HttpOnly, Secure, SameSite=Lax cookie.

### Rationale

- Single deployable, no cross-service token validation to support — the usual argument for stateless JWTs (independent services validating tokens without a shared session store) doesn't apply here.
- Revocation is immediate and simple: logout invalidates the session server-side. A compromised or leaked JWT is valid until expiry unless a blocklist is built and checked on every request — extra machinery with no current justification.
- No token sitting in `localStorage`/JS-accessible storage, removing a common XSS exfiltration target.
- Phase 7 (Realtime/WebSocket) can authenticate the upgrade handshake with the same cookie, no separate token-passing scheme needed.
- Option 3 (OIDC) solves a problem we don't have (no external IdP requirement, no "log in with Google" ask) and adds a real integration to maintain for no current benefit.

### Consequences

**Gain:** simplest secure default for a single-origin-consuming-API web dashboard; trivial logout semantics; no client-side token storage/refresh logic to write and maintain.

**Sacrifice:** horizontal scaling to multiple backend instances needs a shared session store (Spring Session + Redis — Redis is already an approved ephemeral store per `architecture-principles.md`) once real load justifies running more than one instance; not needed for Phase A. A future native mobile agent app would likely prefer tokens, but none is in scope — Product Vision keeps customers WhatsApp-only and agents on the web dashboard.

### Revisit Conditions

Revisit — for the specific client that needs it, not system-wide — if we ever ship a public third-party API or a native mobile app that can't hold cookies naturally.

---

## ADR-015 — Globally unique email; one tenant per user

**Status:** Accepted

### Context

FR-AUTH-002 requires binding every authenticated user to exactly one tenant. This decision determines the scope of email uniqueness and, as a direct consequence, whether login needs a tenant-selection step.

### Options Considered

1. **Globally unique email.** One `User` row per person, system-wide; that row belongs to exactly one tenant. Login is plain email + password.
2. **Per-tenant unique email.** The same email can have separate `User` rows in different tenants. Login needs a way to pick which tenant's account to authenticate against (subdomain, slug, or a picker screen).
3. **Full membership model.** Identity separated from tenant membership via a many-to-many join, so one identity can hold different roles in different tenants simultaneously.

### Decision

**Option 1 — globally unique email, one tenant per user.**

### Rationale

- Matches FR-AUTH-002's literal wording most directly: a user *is* one identity bound to one tenant, not a login shared across memberships.
- Keeps the login flow trivial — no tenant-selection UI, which particularly matters given Phase A (ADR-011) is a single tenant anyway.
- Option 3 is real, validated-nowhere complexity — no requirement or product signal asks for one person operating across multiple tenant businesses under one identity (Rule 5).

### Consequences

**Gain:** simplest possible login and account model; the `email` column alone is enough to resolve a user, with no ambiguity.

**Sacrifice:** the same person cannot be an agent at two different tenant businesses on the platform using one email address — they would sign up with a second email for a second tenant. Accepted as a standard, well-understood trade-off for a v1 B2B SaaS product.

### Revisit Conditions

Revisit toward a membership model only if a real tenant needs one person to hold accounts across multiple tenants under a single identity — not before.

---

## ADR-016 — Owner/Admin privilege boundary and the last-Owner invariant

**Status:** Accepted

### Context

RBAC needs a concrete rule for who can manage whom. Left undefined, two failure modes are easy to hit by accident: an Admin quietly promoting themselves to Owner (privilege escalation), or a tenant ending up with zero Owners (nobody left who can manage the workspace).

### Options Considered

1. **Flat rule:** any of Owner/Admin can manage any user, including other Owners/Admins.
2. **Privilege boundary:** Admin manages only the operational roles (Manager, Agent); only an Owner can manage Owner/Admin accounts. Plus: a tenant may never be left with zero Owners.
3. **No enforcement**, rely on UI-level hiding of dangerous actions only.

### Decision

**Option 2.** Admin can invite, view, and disable Manager/Agent users. Only an Owner can invite, view, disable, or change the role of an Owner or Admin account. Every role-change or disable operation checks, in the application layer, that it would not leave the tenant with zero `OWNER` users — that operation is rejected if so.

### Rationale

- Removes a direct self-escalation path (an Admin cannot make themselves — or a collaborator — an Owner or Admin).
- The last-Owner check prevents a tenant from being permanently locked out of its own workspace administration, which would otherwise require manual database intervention to fix.
- Option 3 is not real enforcement (Section 17 — security must be enforced server-side, never UI-only).

### Consequences

**Gain:** a clear, small authorization surface for user management (two tiers: Owner-only actions, Owner-or-Admin actions) and a structural guarantee against workspace lockout.

**Sacrifice:** Postgres cannot express "at least one row of this kind" as a declarative constraint, so this invariant lives in application code and must be checked on every relevant mutation path (role change, disable) — it is not free, and any new path that mutates a user's role/status must remember to include this check.

### Revisit Conditions

Revisit if a tenant legitimately needs co-equal Owners with no hierarchy distinction from Admins — not currently requested, and the current model already allows multiple Owners.

---

## ADR-017 — Conversation assignment: self-claim + privileged reassignment

**Status:** Accepted

### Context

FR-CON-003 requires letting "authorized users" assign conversations, and FR-CON-007 wants to prevent two agents from silently working the same conversation at once. Product Vision assigns "assign work" to the Support Manager role specifically, while the Support Agent's listed duty is "see assigned chats, reply" — the docs don't say outright whether an Agent may claim unassigned work for themselves.

### Options Considered

1. **Manager/Admin/Owner-only assignment** — Agents never self-assign; all work is routed to them by a human (or, later, automatic routing).
2. **Self-claim + privileged reassignment** — any authenticated tenant member can claim an unassigned conversation for themselves; assigning to someone else, or reassigning an already-claimed conversation, requires Owner, Admin, or Manager.
3. **Fully open** — anyone can assign or reassign to anyone at any time.

### Decision

**Option 2.**

### Rationale

- Matches how support tools commonly work (Zendesk/Intercom-style "claim" patterns) — an idle agent picking up open work is normal, healthy behavior that shouldn't need manager gatekeeping.
- Directly addresses the coarse-grained collision case FR-CON-007 cares about at this phase: claiming a conversation is exactly the act that marks it "already someone's," so two agents can't both start working something nobody had picked up. (The finer-grained "both hit send in the same instant" case is Phase 5's Redis-lock territory — see `conversation-domain.md` §1.)
- Reassignment — moving a conversation that's already someone's — is the case that actually needs oversight; an agent quietly grabbing a peer's work would undermine accountability, so it's gated to Owner/Admin/Manager.
- Option 1 adds friction with no requirement backing it. Option 3 removes the one place accountability actually matters.

### Consequences

**Gain:** agents self-serve idle work without waiting on a manager; reassignment still has a clear approval boundary; no schema complexity (single `assigned_agent_id` column is enough).

**Sacrifice:** none significant — this is a low-risk, easily-revisited authorization rule with no data-model implications.

### Revisit Conditions

Revisit if a tenant's workflow needs strict manager-mediated routing (e.g., skill-based routing where self-claim would cause misrouting) — at that point, make self-claim configurable per tenant rather than changing the global default.

---

## ADR-018 — Realtime transport: in-memory STOMP broker, not Redis-backed

**Status:** Accepted

### Context

FR-RT-001–003 need conversation and message changes to reach the
dashboard without a refresh. `system-architecture.md` already names
Redis as the store for "agent presence, WebSocket session data,
conversation claim-locks" — ephemeral, never authoritative — which
raises the question of whether Phase 7's realtime fan-out should be
Redis-backed (a STOMP relay to Redis pub/sub, so any backend instance
can deliver to any connected client) from the start, or something
simpler.

### Options Considered

1. **Spring's built-in in-memory `SimpleBroker`.** STOMP subscription
   management and message routing all run in-process; zero new
   infrastructure.
2. **Redis-backed STOMP relay** (or a RabbitMQ STOMP broker). Any
   backend instance can publish an event that reaches a client
   connected to a *different* instance — required for horizontal
   scaling.
3. **Long-polling / Server-Sent Events instead of WebSocket.** Avoids
   the WebSocket upgrade entirely.

### Decision

**Option 1** — the in-memory `SimpleBroker`, for the same single-instance
reasoning ADR-014 already applied to sessions ("horizontal scaling...
needs a shared session store... not needed for Phase A").

### Rationale

- This is a single Spring Boot instance today (`system-architecture.md`
  §7 — no Kubernetes, no service mesh on day one). A message published
  in-process reaches every client connected to that same process,
  which is every client there is right now. Option 2 solves a problem
  — "the client is connected to a different instance than the one that
  published the event" — that cannot occur yet.
- Standing up Redis-backed STOMP relay now would be exactly the
  premature-infrastructure mistake Rule 5 exists to block: real cost
  (a new store dependency, relay configuration, another thing that can
  fail) for a scaling need with no current measurement behind it.
- Option 3 (SSE/long-polling) gives up bidirectional framing and
  Spring's built-in STOMP subscription/topic model for no benefit here
  — nothing in Phase 7 needs the client to push anything over the
  realtime channel (replies still go through the REST API), so
  WebSocket's extra capability isn't wasted, and STOMP's topic model is
  a better fit for "broadcast to everyone subscribed to this tenant"
  than reimplementing routing on top of raw SSE.
- Redis is already approved in this project for exactly this future
  need (`system-architecture.md` §2) — this ADR doesn't reject Redis,
  it defers turning it on until more than one backend instance is
  actually running.

### Consequences

**Gain:** zero new infrastructure to deploy, operate, or fail for
Phase 7; Spring's STOMP support handles subscription management and
heartbeats without custom code.

**Sacrifice:** the moment a second backend instance runs (real
horizontal scaling), a client connected to instance A will silently
miss an event published by instance B — the in-memory broker has no
cross-instance awareness. This is a hard requirement to catch before
scaling out, not a gradual degradation, so it must be revisited
deliberately rather than discovered in production.

### Revisit Conditions

Revisit the moment more than one backend instance is planned to run
concurrently — swap the `SimpleBroker` for a Redis- or RabbitMQ-backed
STOMP relay at that point, not before. The event contract (§4 of
`realtime-domain.md`) doesn't change; only the broker configuration
does.
