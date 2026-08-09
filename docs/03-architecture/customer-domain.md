# Customer Domain Design (Phase 3)

**Status:** Proposed · v0.1

Covers the Customer model and the domain operations around it for Phase 3.
Short by design — most of the shape here was already settled in
`domain-model.md`; this fills in the operational and tenant-isolation
detail needed to implement it.

---

## 1. Scope

**In scope:** Customer entity, tenant-scoped profile CRUD (create, view,
list, update name), and the idempotent find-or-create-by-phone domain
operation that Phase 6's WhatsApp webhook will eventually call.

**Out of scope (deferred, not forgotten):**
- Conversation history — `Conversation` doesn't exist until Phase 4;
  FR-CUS-002 is satisfied then, not now.
- The WhatsApp webhook itself — Phase 6. `findOrCreateFromInbound` is built
  and tested now because FR-CUS-001 requires the operation to exist, but no
  HTTP endpoint calls it yet.
- Search/filtering — Product Vision's roadmap lists "advanced search" as a
  post-MVP (Phase 2) item explicitly.
- Deletion / data retention — a customer record represents a real person
  with (eventually) conversation history attached; deleting one is a
  data-retention/compliance decision that deserves its own design, not a
  casual `DELETE` endpoint added in passing.
- Blocking/archiving a customer — no requirement asks for it.

## 2. Customer model

| Column | Type | Notes |
|---|---|---|
| `id` | uuid, PK | |
| `tenant_id` | uuid, FK → tenant.id, not null | The security boundary column |
| `phone` | text, not null | The WhatsApp identity; light validation (leading `+`, digits), not full E.164 parsing — no need for a phone-number library yet |
| `name` | text, nullable | May be unknown until WhatsApp supplies a profile name (Phase 6) or an agent sets it |
| `created_at`, `updated_at` | timestamptz, not null | |

## 3. Identity & uniqueness

Uniqueness is scoped to **`(tenant_id, phone)`** — deliberately the
opposite of ADR-015's global-uniqueness choice for `User`. A `User` is one
platform identity bound to one tenant; a `Customer` is an external person
who may independently message several different businesses running on the
platform. The same real phone number legitimately appearing as a customer
in two different tenants is expected, not a conflict.

## 4. Domain operations

- **`createManually(tenantId, phone, name)`** — an explicit agent action
  (e.g., pre-adding a known contact). Rejects with a conflict if that phone
  already exists in the tenant — a human said "add this," so a silent
  no-op or silent merge would hide a mistake.
- **`findOrCreateFromInbound(tenantId, phone, name)`** — idempotent:
  returns the existing customer if the phone is already known, creates one
  otherwise. This is the operation Phase 6 calls on every inbound WhatsApp
  message; it must never fail just because the customer already exists.
- **`updateProfile(tenantId, customerId, name)`** — correct a name.
- **`get` / `list``** — tenant-scoped, paginated (a customer list is one of
  the first things in this system with genuine unbounded growth potential,
  so `Pageable` is in from the start rather than retrofitted later).

## 5. Authorization

No RBAC boundary beyond tenant membership — any authenticated user in the
tenant (Owner, Admin, Manager, or Agent) can view, create, and update
customer profiles. Unlike user management, this is operational data, not
sensitive workspace configuration; restricting it would just get in the
way of agents doing their job.

## 6. Tenant isolation

Same pattern as Phase 2: `tenantId` comes only from the authenticated
principal, every lookup is `findBy...AndTenantId`, and a cross-tenant test
proves a user in one tenant cannot see, list, or update a customer that
belongs to another — including by reusing a real ID.
