# WhatsApp Integration Boundary

**Status:** Accepted · v0.1
**This is the highest-risk part of the system.** Read it carefully.

The WhatsApp module is the *only* place that talks to Meta. Everything else speaks to its interface. This doc defines that boundary, and — just as important — the **operational reality of onboarding with Meta**, which shapes the whole go-to-market timeline.

---

## 1. The provider-status fork (the thing most people miss)

How we access WhatsApp depends on *whose* accounts we connect:

| Mode | Whose WABA | Token type | App Review? |
|---|---|---|---|
| **Direct developer** | Only our own (one owned WABA) | System User token | **No** |
| **Tech Provider** | Other companies' WABAs | Business Integration System User token (per tenant, via Embedded Signup) | **Yes — approved** |

The multi-tenant SaaS vision is **Tech Provider**. Tech Provider status requires **App Review and Advanced access** for `whatsapp_business_messaging` / `whatsapp_business_management` before *any* external tenant can grant your app access — without it, calls to WABAs you don't own fail with **error code 200** (an auth error, not HTTP 200). That review is a weeks-long gate that can fail — ours didn't; it's approved.

## 2. Our phased plan (see ADR-011)

We refused to let Meta's partner queue block first launch, so:

1. **Phase A — RitaRock, direct-developer mode.** One owned WABA, a System User token, no App Review. Proved the entire product end-to-end with a real business. **Shipped.**
2. **Phase B — Tech Provider, in parallel.** Submit for App Review / Advanced access, build Embedded Signup onboarding. **App Review approved.**
3. **Phase C — multi-tenant onboarding.** **Live** — Embedded Signup (`whatsapp-domain.md` §6) is the second of two ways a `WhatsAppConnection` row gets created, alongside Phase A's manual paste.

The code was multi-tenant from day one; only the *onboarding path* differs between phases. In Phase A the "connection" is a single configured record; via Phase C it's the output of Embedded Signup — both land in the same tenant-scoped table.

## 3. Webhook → tenant resolution (critical)

In multi-tenant mode there is **one shared webhook URL** for every tenant. Meta does not tell us "this is RitaRock." We resolve the tenant ourselves:

```
inbound event → read metadata.phone_number_id
             → look up whatsapp_connection by phone_number_id
             → that row's tenant_id is the owning tenant
```

- The `phone_number_id → tenant` mapping is the join between Meta's world and ours.
- If no mapping is found, the event is logged and dropped (never guessed).
- The `tenant_id` from this lookup is *trusted* — it is one of only two legitimate sources of tenant identity (the other being the authenticated user's context).

## 4. Inbound contract

1. **Verify the signature** (`X-Hub-Signature-256`, HMAC-SHA256 with the app secret) on every `POST`. Reject on mismatch.
2. **Answer the verification handshake** (`GET` with `hub.challenge`) when the webhook is registered.
3. **ACK within seconds** (`200`), then hand the raw payload to async processing.
4. **Deduplicate** on `wa_message_id` before creating any domain record (see idempotency below).

## 5. Idempotency

Store a uniqueness constraint on `(tenant_id, wa_message_id)` for inbound messages and on the provider message id for status events. Processing is:

```
on event:
  if already-seen(dedupe_key): ack + ignore
  else: process, then record dedupe_key in the same transaction
```

This makes reprocessing safe, which is mandatory because Meta retries.

## 6. Outbound contract

- Send through the WhatsApp module using **that tenant's** credentials — never a global token.
- Persist the message as `PENDING` **before** the API call; update to `SENT` on the returned message id; let status webhooks drive `DELIVERED / READ / FAILED`.
- Route sends through an **outbox** so transient Meta failures retry with backoff (`4^X` seconds on repeated failure) rather than dropping the reply.

## 7. The 24-hour customer-service window

- Inside 24h of the customer's last inbound message, agents may send free-form replies.
- Outside it, only **approved template messages** are allowed — free-form sends are rejected by Meta.
- The Messaging module checks `conversation.last_inbound_at` before an outbound send and, when the window is closed, surfaces this to the agent instead of failing silently.

## 8. Credential storage

- Each tenant's WhatsApp credentials (token, `phone_number_id`, `waba_id`, app-secret material) are stored **encrypted at rest** (envelope encryption; keys in a secrets manager, not in the DB or code).
- Credentials are **never** sent to the frontend. All Meta calls originate server-side.

## 9. Per-tenant billing note `[open decision]`

Under Tech Provider, WhatsApp conversation charges can flow via the tenant's own WABA billing or via a shared credit line we extend. This affects pricing and margins and is **not yet decided** — tracked as an open question, not an assumption. It does not block Phase A (RitaRock pays its own WABA).

## 10. The boundary interface (shape, not final signature)

```java
interface WhatsAppGateway {
    // outbound
    SendResult sendText(TenantId tenant, WaPhone to, String body);
    SendResult sendTemplate(TenantId tenant, WaPhone to, TemplateRef tpl, Map<String,String> vars);
    MediaRef   uploadMedia(TenantId tenant, byte[] bytes, String mime);

    // inbound is push, handled by the webhook controller, normalized into:
    // InboundMessage / StatusUpdate domain events published to the rest of the system
}
```

No domain module sees a Meta JSON shape. The gateway normalizes Meta's payloads into our domain events on the way in, and our intent into Meta calls on the way out. That is the whole point of the boundary.
