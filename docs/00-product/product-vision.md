# Product Vision

**Status:** Accepted · v0.1
**Owner:** Founding engineer

---

## 1. What we are building

A reliable, multi-tenant customer-communication platform that lets a business manage its WhatsApp customer conversations from a centralized workspace, where multiple support agents collaborate — responding, assigning, viewing history, and monitoring operations.

The platform focuses **exclusively on WhatsApp Business**. It is deliberately **not** an omnichannel product in v1.

## 2. The shift we sell

We move a business from:

> *"Everyone is using the company's WhatsApp."*

to:

> *"The company runs a structured customer-support operation built around WhatsApp."*

That sentence is the product. Every feature either serves it or waits.

## 3. Problem statement

WhatsApp is a primary support channel for a huge number of businesses, but a single phone or shared login collapses under growth:

- Multiple agents cannot collaborate on one number without stepping on each other.
- Conversations get missed; customers wait; nobody knows who owns a chat.
- Managers have no visibility into load, response time, or accountability.
- Customer history is scattered and hard to organize.
- Scaling the team means increasingly manual coordination.
- The business owns almost no operational data about its own support.

We provide a shared, controlled, auditable workspace that fixes exactly these.

## 4. Target users

| Role | Needs |
|---|---|
| **Business Owner** | Connect WhatsApp, manage staff, see operations, review performance |
| **Administrator** | Manage users, roles, workspace and WhatsApp configuration |
| **Support Manager** | See active conversations, assign work, monitor agents and response times |
| **Support Agent** | See assigned chats, reply, view history, add internal notes, set status |
| **Customer** | Talks to the business **through WhatsApp only** — never touches our dashboard |

## 5. Product goals

The platform must let a business:

1. Connect its own WhatsApp Business account.
2. Let multiple agents work one business number safely.
3. Centralize customer conversations and history.
4. Assign conversations to agents.
5. Get real-time updates without refreshing.
6. Give managers operational visibility.
7. **Isolate every tenant's data** as a security invariant.
8. Stand on an architecture that can grow into a commercial SaaS.

## 6. Non-goals (scope guard)

Explicitly **out** of the initial product, to prevent scope creep:

- **Other channels:** Instagram, Messenger, Telegram, Email, SMS, Voice, web chat.
- **Heavy capability:** AI agents/chatbots, marketing automation, full CRM, accounting, ERP, complex workflow automation, an advanced payment platform.

These may come later. Listing them here is a decision, not an oversight.

## 7. Core workflow

```
Customer ──WhatsApp──▶ WhatsApp Business Platform ──webhook──▶ Backend
   │                                                             │
   │  identify tenant → identify customer → find/create          │
   │  conversation → persist message → notify agents             │
   ▼                                                             ▼
Customer ◀──WhatsApp── WhatsApp Business Platform ◀──send──── Agent (Dashboard)
```

This loop is the system. Everything else is built around it.

## 8. MVP scope

**Organization & access** — company registration, workspace, user management, auth (login/logout/session), RBAC (Owner · Admin · Manager · Agent).

**WhatsApp** — connect a business account, webhook handling, inbound + outbound messages, delivery/read status where the platform provides it.

**Customers** — profile (name, phone), conversation history, tenant-scoped.

**Conversations** — inbox (open / assigned / closed), status, priority, assignment.

**Messaging** — text, basic media/file handling, inbound/outbound distinction.

**Realtime** — new-message, conversation-update, assignment, and unread-count events.

**Audit** — administrative actions, assignment changes, configuration changes.

## 9. Roadmap (post-MVP)

| Phase | Adds |
|---|---|
| **2** | Departments, tags, internal notes, canned responses, advanced search, response-time reporting |
| **3** | Payment integrations, payment status, virtual-account workflows, smarter routing |
| **4** | AI-assisted replies, conversation summaries, intent classification |

Each phase **extends the core conversation system** — none is a separate product.
