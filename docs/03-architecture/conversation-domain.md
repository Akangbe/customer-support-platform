# Conversation Domain Design (Phase 4)

**Status:** Proposed · v0.1

Conversation is the central aggregate (domain-model.md) — it owns its
status, priority, and current assignment. This document settles the
lifecycle invariants Section 30 of the engineering process requires before
coding: who can assign, whether closed conversations reopen, whether a
conversation can have multiple active agents, what happens when an
assigned agent is disabled, and what "open" means.

---

## 1. Scope

**In scope:** Conversation entity and lifecycle (OPEN → ASSIGNED → CLOSED,
reopen per ADR-013), priority, assignment (claim + privileged reassignment,
ADR-017), tenant-scoped CRUD, and `findOrOpenForCustomer` — the idempotent
operation Phase 6's WhatsApp webhook will call on every inbound message.

**Out of scope (deferred, not forgotten):**
- Message persistence and history — `Message` doesn't exist until Phase 5;
  FR-CON-005 ("maintain conversation history") is fully satisfied then.
- The real-time collision lock FR-CON-007 asks for. What it actually
  guards against — two agents both hitting *send* within the same
  instant — can't happen until Phase 5 has a send-message action to
  protect. The Redis-based lock `system-architecture.md` already
  earmarks for this ("conversation claim-locks") belongs there. Phase 4
  covers the coarser case instead: an unassigned conversation getting
  worked by two people at once, which the claim model below (§4) already
  prevents by making "claimed" a visible, persisted fact.
- WhatsApp inbound wiring — Phase 6.
- Auto-unassigning a disabled user's conversations — see §6.

## 2. Conversation model

| Column | Type | Notes |
|---|---|---|
| `id` | uuid, PK | |
| `tenant_id` | uuid, FK → tenant.id, not null | |
| `customer_id` | uuid, FK → customer.id, not null | |
| `status` | enum: `OPEN`, `ASSIGNED`, `CLOSED` | |
| `priority` | enum: `LOW`, `NORMAL`, `HIGH`, `URGENT`, default `NORMAL` | Undefined in prior docs; low-risk, easily-changed default — not treated as a blocking question |
| `assigned_agent_id` | uuid, FK → app_user.id, nullable | One assignee, never several — see §5 |
| `created_at` | timestamptz, not null | |
| `last_inbound_at`, `last_outbound_at` | timestamptz, nullable | Columns exist now per FR-CON-006; populated for real starting Phase 5/6 |
| `closed_at` | timestamptz, nullable | |

**A customer has at most one non-closed conversation at a time**, enforced
with a partial unique index (`WHERE status IN ('OPEN','ASSIGNED')`), not
just in application code — this is the same precondition ADR-013 already
assumes for reopen semantics, now made structurally impossible to violate.

## 3. Lifecycle

```
OPEN ──assign──▶ ASSIGNED ──close──▶ CLOSED
  ▲                  │                  │
  └──── unassign ─────┘                  │
  ▲                                       │
  └─────────────── reopen ────────────────┘
```

- **OPEN**: exists, not closed, nobody has claimed it yet.
- **ASSIGNED**: exactly one agent has it.
- **CLOSED**: resolved. `reopen` is the only way out, and it always lands
  back in `OPEN`, unassigned — per ADR-013, "the previous assignment is
  not silently restored."
- `close` is valid from `OPEN` or `ASSIGNED`. `reopen` is valid only from
  `CLOSED`. Both reject with a conflict if the conversation isn't in a
  valid source state — status changes are a state machine, not a free-form
  field.

## 4. Assignment (ADR-017)

- **Claim**: any authenticated tenant member may assign an `OPEN`
  (unassigned) conversation to themselves.
- **Reassign**: moving an already-`ASSIGNED` conversation to someone else
  requires Owner, Admin, or Manager.
- **Unassign**: the currently assigned agent may release their own claim;
  so can Owner, Admin, or Manager.
- The assignment target must be an `ACTIVE` user in the same tenant —
  validated against `UserRepository`, a deliberate one-directional
  dependency (conversation depends on user, never the reverse).

See ADR-017 in the decision log for the full reasoning.

## 5. One assignee, not several

`assigned_agent_id` is a single nullable column, not a join table. A
conversation belongs to at most one agent at a time — matches the existing
ER diagram in `domain-model.md` and keeps "who owns this" always a single
unambiguous answer.

## 6. When an assigned agent is disabled

Deliberately **not** handled automatically in Phase 4: disabling a user
(Phase 2) does not cascade into unassigning their conversations. Doing
that would mean the `user` module reaching into `conversation` (or vice
versa) — real coupling for a corner case with no reported operational
need yet. The conversation stays assigned to a now-disabled user; a
manager can reassign it manually when they notice. Revisit if this proves
to be an actual operational problem — at that point a domain-event
mechanism (user disabled → conversation module reacts) is the right shape,
not a direct cross-module call.

## 7. Authorization summary

| Action | Who |
|---|---|
| View / list / start a conversation | Any authenticated tenant member |
| Claim an unassigned conversation | Any authenticated tenant member |
| Reassign an already-assigned conversation | Owner, Admin, Manager |
| Unassign | The current assignee, or Owner/Admin/Manager |
| Close / reopen / change priority | Any authenticated tenant member |

Everything here is tenant-scoped the same way as Phase 2/3: `tenantId`
comes only from the authenticated principal, every lookup is
`findBy...AndTenantId`, and a cross-tenant test proves it.
