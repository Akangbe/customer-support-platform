# Identity & Access Design (Phase 2)

**Status:** Proposed · v0.1

Covers the tenant model, user model, role model, authentication flow, and
authorization model for Phase 2. Tenant isolation is a security invariant
(Architecture Principles, Rule 3) — this document exists so that invariant
has a single, explicit home before any code enforces it.

---

## 1. Scope

**In scope:** Tenant, User, RBAC (Owner/Admin/Manager/Agent), tenant
self-registration, session-based login/logout, inviting and activating
users, tenant-scoped authorization, cross-tenant isolation.

**Out of scope (deferred, not forgotten):**
- Self-service password reset — no functional requirement demands it yet;
  revisit when it does.
- MFA, SSO/OIDC — no stated requirement.
- A cross-tenant "platform admin" role for us as the SaaS operator — every
  user is strictly tenant-scoped in v1. Support tooling, if ever needed,
  is a separate decision with its own ADR.

## 2. Tenant model

| Column | Type | Notes |
|---|---|---|
| `id` | uuid, PK | |
| `name` | text, not null | Display name |
| `slug` | text, unique, not null | URL-safe identifier; groundwork for subdomain routing, not used by v1 login |
| `status` | enum: `ACTIVE`, `SUSPENDED` | Suspension is a manual operational action, not user-triggered |
| `created_at` | timestamptz, not null | |

A tenant is created exactly one way in v1: **self-registration**
(`POST /api/v1/auth/register-tenant`), which creates the `Tenant` and its
first `User` (role `OWNER`, status `ACTIVE`) in a single transaction. This
satisfies FR-TEN-001 and is also how the cross-tenant isolation tests get
two independent tenants to prove isolation against.

## 3. User model

| Column | Type | Notes |
|---|---|---|
| `id` | uuid, PK | |
| `tenant_id` | uuid, FK → tenant.id, not null | The security boundary column |
| `email` | citext (or text + lower-indexed), unique, not null | **Globally** unique — see ADR-015 |
| `password_hash` | text, nullable | Null while `PENDING` (no password set yet) |
| `name` | text, not null | |
| `role` | enum: `OWNER`, `ADMIN`, `MANAGER`, `AGENT` | One role per user, no multi-role |
| `status` | enum: `PENDING`, `ACTIVE`, `DISABLED` | |
| `invite_token` | text, nullable, unique when present | Set on invite, cleared on activation |
| `invite_token_expires_at` | timestamptz, nullable | |
| `created_at`, `updated_at` | timestamptz, not null | |
| `last_login_at` | timestamptz, nullable | |

**Status lifecycle:**

```
PENDING ──accept-invite (sets password)──▶ ACTIVE ──disable──▶ DISABLED
                                              ▲                    │
                                              └────── re-enable ───┘
```

Registering a tenant creates its Owner directly in `ACTIVE` (no invite
step for the first user — there's no one to invite them yet).

## 4. Role model

Four fixed roles, matching Product Vision §4 and FR-AUTH-004. No custom
roles in v1 (Rule 5 — no speculative flexibility without a validated need).

| Action | Owner | Admin | Manager | Agent |
|---|:---:|:---:|:---:|:---:|
| Invite / view / disable a Manager or Agent | ✓ | ✓ | ✗ | ✗ |
| Invite / view / disable an Owner or Admin | ✓ | ✗ | ✗ | ✗ |
| Change own password, view own profile | ✓ | ✓ | ✓ | ✓ |
| Connect/manage WhatsApp, workspace config | ✓ | ✓ | ✗ | ✗ |
| Assign conversations, monitor agents *(Phase 4+)* | ✓ | ✓ | ✓ | ✗ |
| Reply to assigned conversations *(Phase 4+)* | ✓ | ✓ | ✓ | ✓ |

**Privilege boundary:** Admin manages the *operational* roles (Manager,
Agent) but can never touch Owner or Admin accounts — no promote-self,
no demote-a-peer. Only an Owner can manage Owner/Admin accounts.

**Invariant:** a tenant can never be left with zero `OWNER` users. Enforced
in the application layer on every role-change/disable operation (Postgres
has no clean way to express "at least one row of this kind exists").
See ADR-016.

## 5. Authentication flow

Session-based (HttpOnly, Secure, SameSite=Lax cookie) — see ADR-014 for
why, not JWT.

```
POST /api/v1/auth/register-tenant  { tenantName, ownerName, email, password }
  → creates Tenant + User(OWNER, ACTIVE) in one transaction
  → establishes a session (auto-login)

POST /api/v1/auth/login  { email, password }
  → Spring Security authenticates against User by email (globally unique,
    so no tenant selector is needed)
  → establishes a session; principal carries (userId, tenantId, role)

POST /api/v1/auth/logout
  → invalidates the session

POST /api/v1/users/invite  { email, name, role }   [OWNER/ADMIN only,
                                                      target role Manager/Agent
                                                      unless caller is OWNER]
  → creates User(PENDING) in the caller's tenant, with an invite token
  → returns the token in the response, and (after commit, best-effort)
    emails an accept-invite link to the invitee via SES — see §8

POST /api/v1/auth/accept-invite  { token, password }
  → validates token + expiry, sets password_hash, status → ACTIVE
```

## 6. Authorization model

- The authenticated principal (`userId`, `tenantId`, `role`) comes **only**
  from the session, resolved server-side — never from a client-supplied
  header, path segment, or body field. This is the concrete application of
  Rule 3.
- Tenant-scoped repository methods take `tenantId` from the resolved
  principal (via a `CurrentUser`-style holder populated by a Spring
  Security filter), not from request parameters.
- Coarse-grained checks (role vs. action) are enforced at the application
  service layer, close to the use case — not scattered across controllers.

## 7. Tenant context & isolation enforcement

Every tenant-owned repository query is `findBy...AndTenantId(...)`, sourced
from the authenticated principal. This phase's completion criterion,
per the engineering process, is a test that provisions two tenants (via
the real registration endpoint) and proves a user authenticated in Tenant A
cannot read, list, or modify anything belonging to Tenant B — including via
guessed/enumerated IDs.

## 8. Invite email delivery

`UserService.invite` publishes a `UserInvitedEvent` (identifiers only)
alongside its audit event. `email.InviteEmailListener` picks it up
`AFTER_COMMIT`, re-fetches the user, and sends an accept-invite link via
`email.SesEmailGateway` — the same interface/impl split used for object
storage (`storage.StorageGateway` / `R2StorageGateway`).

- **Best-effort, never fatal.** A send failure (SES misconfigured, AWS
  outage, bad address) is caught and logged, not rethrown — a delivery
  problem must not turn an already-committed invite into a failed HTTP
  response for the inviter. The raw token in `InviteUserResponse` remains
  the fallback: the inviter can always relay the accept-invite link
  manually if the email never arrives.
- **Credentials/region** resolve from the AWS SDK's default provider/region
  chains (`AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY` / `AWS_REGION`), not
  a bespoke app property — same env vars a Node/SES integration would use.
  `SesClient` construction is lazy so an unconfigured environment (local
  dev) degrades to a warning instead of a startup failure.
- **`app.email.accept-invite-url-template`** (`INVITE_ACCEPT_URL_TEMPLATE`)
  is a `%s`-templated URL pointing at the frontend's accept-invite page —
  the backend doesn't know the frontend's routing, so this is configured
  per environment.
