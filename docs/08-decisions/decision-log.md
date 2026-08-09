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
