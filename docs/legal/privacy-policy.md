# Privacy Policy

**Effective date:** [EFFECTIVE DATE]
**Last updated:** [LAST UPDATED DATE]

> ⚠️ **Draft — not legal advice.** This document was drafted to match the platform's actual technical data handling and to satisfy Meta's WhatsApp Business Platform App Review requirements. It has not been reviewed by a lawyer. Before publishing, fill in every `[BRACKETED]` placeholder and have it reviewed by counsel familiar with your operating jurisdiction(s) and any applicable data protection law (e.g. NDPR, GDPR) that covers your business or your tenants' customers.

[COMPANY LEGAL NAME] ("**we**," "**us**," "**the Platform**") operates a multi-tenant customer-support software platform (the "**Service**") that lets a business ("**Tenant**," "**you**," when you are the business customer) manage its WhatsApp Business customer conversations from a shared, team-based dashboard. This policy explains what data we collect, why, and how it's handled.

This policy covers two different groups of people, and they are not treated the same way:

- **Tenant users** — the business and its staff (owners, admins, managers, agents) who create an account and use our dashboard directly.
- **End customers** — the people who message a Tenant's business on WhatsApp. These individuals never create an account with us or access our dashboard; we process their data **on behalf of the Tenant they're messaging**, not as our own customer relationship.

---

## 1. What we collect

### 1.1 From Tenant users (the business and its staff)
- Business/workspace name.
- Staff account details: name, email address, hashed password (we never store passwords in plaintext), role (Owner, Admin, Manager, Agent).
- Authentication session data (see §4).
- Actions taken in the dashboard that are administratively significant (role changes, user invites/disables, conversation assignment, WhatsApp connection changes) — retained as an **audit log** for accountability and security.

### 1.2 From end customers (via WhatsApp, on a Tenant's behalf)
When someone messages a Tenant's connected WhatsApp Business number, we receive and store, as part of operating that Tenant's support inbox:
- WhatsApp phone number and any profile name WhatsApp provides.
- Message content (text) sent and received.
- Media attachments sent and received (images, documents, etc.).
- Message metadata: timestamps, delivery/read status, which staff member handled the conversation.

We receive this data because the Tenant has connected their own WhatsApp Business Account to our Service — we are a **data processor** for this category of data, and the Tenant (the business the end customer is messaging) is the data controller responsible for their own customers' data.

### 1.3 What we do not collect
We do not collect data from end customers through any channel other than the WhatsApp conversation itself. End customers never interact with our dashboard, sign up for an account with us, or are tracked outside the conversation they choose to have with a Tenant.

---

## 2. How we use data

- To operate the core Service: routing inbound WhatsApp messages to the right Tenant, displaying conversation history, enabling staff to reply, and tracking delivery/read status.
- To enforce access control and multi-tenant data isolation — every record is scoped to the Tenant it belongs to, and Tenants cannot access each other's data.
- To maintain security and accountability (audit logging of administrative actions).
- To operate real-time updates (e.g. new-message notifications) within a Tenant's own dashboard session.

We do **not** use end-customer message content for advertising, and we do not sell personal data to third parties.

---

## 3. Third parties and sub-processors

Operating the Service requires sharing data with the following providers, each acting under contract as a processor:

| Provider | Purpose | Data involved |
|---|---|---|
| **Meta / WhatsApp Business Platform** | The messaging channel itself — all inbound/outbound WhatsApp messages pass through Meta's infrastructure. | Message content, phone numbers, media |
| **[DATABASE PROVIDER, e.g. Neon]** (PostgreSQL) | Primary, authoritative data store | All account, conversation, message, and audit data |
| **[CACHE PROVIDER, e.g. Redis host]** | Ephemeral session/presence data only — never the source of truth for any record | Session identifiers, connection presence |
| **Cloudflare R2** | Storage for message attachments/media | File attachments sent/received over WhatsApp |
| **[HOSTING PROVIDER]** | Application hosting | N/A (infrastructure only) |

We do not share Tenant or end-customer data with any party outside this list except where required by law.

---

## 4. How we secure data

- **Encryption in transit**: all connections to the Service use HTTPS/TLS.
- **Encryption at rest for credentials**: each Tenant's WhatsApp access credentials are stored encrypted, using envelope encryption with keys held in a secrets manager — never in application code or the database in plaintext, and never exposed to the frontend or any dashboard user.
- **Authentication**: staff sessions use server-side, HttpOnly, Secure session cookies — not tokens stored in browser-accessible storage, which reduces exposure to cross-site scripting attacks.
- **Tenant isolation**: every data record is scoped to a `tenant_id`; access-control checks enforce that a Tenant's staff can only ever read or act on that Tenant's own data.
- **Audit trail**: administrative actions (role changes, WhatsApp reconfiguration, assignment changes) are recorded durably and cannot be silently lost — the record is written in the same transaction as the action itself.

No system is perfectly secure, and we cannot guarantee absolute security, but the above are structural properties of how the Service is built, not just stated intentions.

---

## 5. Data retention

- **Tenant account data** is retained for as long as the Tenant's account is active, plus [RETENTION PERIOD, e.g. 30 days] after account closure, to allow for recovery of an accidental deletion, after which it is permanently deleted.
- **Conversation and message data** is retained for as long as the Tenant's account is active, or until the Tenant deletes it, or for [RETENTION PERIOD] after conversation closure — *[confirm and finalize this figure; not yet fixed as of this draft]*.
- **Audit logs** are retained for [AUDIT RETENTION PERIOD, e.g. 1 year] for accountability and security purposes.
- We do not currently support automatic, Tenant-configurable retention windows; this may become configurable in a future version.

---

## 6. Your rights

Depending on your jurisdiction, you (whether a Tenant or an end customer whose data a Tenant has shared with us) may have rights to access, correct, export, or request deletion of your personal data.

- **Tenant users**: contact [CONTACT EMAIL] to request access to, correction of, or deletion of your account data.
- **End customers**: because we process your data on behalf of the business you messaged, please contact that business directly. We will assist any Tenant in fulfilling a verified request from their own customer.

---

## 7. Children's privacy

The Service is intended for business use and is not directed at children. We do not knowingly collect data from children through the Service.

---

## 8. Changes to this policy

We may update this policy as the Service evolves. Material changes will be reflected by updating the "Last updated" date above. Continued use of the Service after a change constitutes acceptance of the updated policy.

---

## 9. Contact

Questions about this policy or how your data is handled: [CONTACT EMAIL]

[COMPANY LEGAL NAME]
[BUSINESS ADDRESS]