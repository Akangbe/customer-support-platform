# Functional Requirements

**Status:** Accepted · v0.1

Every requirement has a stable ID so architecture, code, and tests can cite it. IDs never get reused; retired requirements are struck through, not deleted.

---

## Authentication & authorization

| ID | Requirement |
|---|---|
| FR-AUTH-001 | The system shall authenticate users securely. |
| FR-AUTH-002 | The system shall bind every authenticated user to exactly one tenant. |
| FR-AUTH-003 | The system shall prevent any user from accessing another tenant's resources. |
| FR-AUTH-004 | The system shall support role-based authorization (Owner, Admin, Manager, Agent). |

## Tenant management

| ID | Requirement |
|---|---|
| FR-TEN-001 | The system shall let a business create an organization/workspace. |
| FR-TEN-002 | The system shall let authorized users invite additional agents. |
| FR-TEN-003 | The system shall associate every tenant-owned resource with its tenant. |
| FR-TEN-004 | The system shall maintain strict tenant isolation. |

## WhatsApp integration

| ID | Requirement |
|---|---|
| FR-WA-001 | The system shall let an authorized business connect its WhatsApp Business integration. |
| FR-WA-002 | The system shall receive incoming events via the official WhatsApp Business Platform webhook. |
| FR-WA-003 | The system shall validate incoming webhook requests (signature verification). |
| FR-WA-004 | The system shall resolve the owning tenant for every incoming WhatsApp event. |
| FR-WA-005 | The system shall persist relevant incoming messages. |
| FR-WA-006 | The system shall let authorized agents send responses through the connected account. |
| FR-WA-007 | The system shall process outbound message-status events (sent/delivered/read/failed). |
| FR-WA-008 | The system shall handle duplicate webhook events safely (idempotency). |
| FR-WA-009 | The system shall enforce the 24-hour customer-service window and require an approved template outside it. |

> **FR-WA-008 is load-bearing.** Webhooks are at-least-once, not exactly-once. Idempotency is a correctness requirement, not a nicety — see [WhatsApp integration](../03-architecture/whatsapp-integration.md).

## Customer management

| ID | Requirement |
|---|---|
| FR-CUS-001 | The system shall create or identify a customer from available WhatsApp identity info. |
| FR-CUS-002 | The system shall maintain per-customer conversation history. |
| FR-CUS-003 | Authorized agents shall be able to view customer information. |
| FR-CUS-004 | The system shall never expose a customer across tenants. |

## Conversations

| ID | Requirement |
|---|---|
| FR-CON-001 | The system shall create a conversation when appropriate. |
| FR-CON-002 | The system shall associate conversations with customers. |
| FR-CON-003 | The system shall let authorized users assign conversations. |
| FR-CON-004 | The system shall let authorized users change conversation status. |
| FR-CON-005 | The system shall maintain conversation history. |
| FR-CON-006 | The system shall track relevant timestamps (created, last-inbound, last-outbound, closed). |
| FR-CON-007 | The system shall prevent two agents from silently replying to the same conversation at once (claim/lock). |

## Messaging

| ID | Requirement |
|---|---|
| FR-MSG-001 | Agents shall be able to send messages. |
| FR-MSG-002 | The system shall persist messages durably. |
| FR-MSG-003 | The system shall associate messages with conversations. |
| FR-MSG-004 | The system shall distinguish inbound from outbound messages. |
| FR-MSG-005 | The system shall process supported message types (text, media/file). |

## Realtime

| ID | Requirement |
|---|---|
| FR-RT-001 | New incoming messages shall appear in the dashboard without a full refresh. |
| FR-RT-002 | Conversation assignments shall reflect in real time. |
| FR-RT-003 | Unread counts shall update in real time where appropriate. |

## Audit

| ID | Requirement |
|---|---|
| FR-AUD-001 | The system shall record important administrative actions. |
| FR-AUD-002 | The system shall record assignment changes. |
| FR-AUD-003 | The system shall record configuration changes. |
