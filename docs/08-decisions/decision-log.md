# Decision Log

Every significant architectural decision lives here (Architecture Principles, Rule 6). Format: **Status / Context / Options Considered / Decision / Rationale / Consequences / Revisit Conditions.**

---

## ADR-011 — Phased Meta onboarding

**Status:** Referenced elsewhere as Accepted — original ADR body not recovered

During the Phase 0 repository audit (2026-08-09) this decision was found cited by `README.md` and `whatsapp-integration.md` as an accepted, load-bearing decision, but no file containing its full ADR body (Context / Options / Rationale) exists anywhere in the repository or its build output. Per instruction, it has not been reconstructed or guessed at.

The decision itself is **not lost** — it is fully specified operationally in [`whatsapp-integration.md` §1–2](../03-architecture/whatsapp-integration.md#1-the-provider-status-fork-the-thing-most-people-miss): launch on RitaRock in direct-developer mode (single owned WABA, no App Review) while pursuing Tech Provider status in parallel, flipping on multi-tenant onboarding once App Review clears. That document is the authoritative reference until this entry is properly ratified.

**Action:** author the full ADR body here before Phase 6 (WhatsApp Integration) begins, so the trade-offs are captured formally rather than only operationally.

---

## ADR-012 — Idempotent ingestion / transactional outbox

**Status:** Referenced elsewhere as Accepted — original ADR body not recovered

Same situation as ADR-011: cited by `non-functional-requirements.md` (Reliability section) and implied by Architecture Principles Rules 7–8, but no standalone ADR body exists in the repository.

The decision is fully specified operationally in [`architecture-principles.md` Rules 7–8](../03-architecture/architecture-principles.md#rule-7--webhooks-are-at-least-once-ingestion-must-be-idempotent) and [`whatsapp-integration.md` §5–6](../03-architecture/whatsapp-integration.md#5-idempotency): dedupe inbound events on `(tenant_id, wa_message_id)` before they become domain facts, and route outbound sends through an outbox so a transient Meta failure is a retry, not a lost message.

**Action:** author the full ADR body here before Phase 5 (Message domain) and Phase 6 (WhatsApp Integration) begin.

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
