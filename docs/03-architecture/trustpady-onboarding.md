# Onboarding a Notification API Tenant (Trustpady)

The runbook for the flow ADR-018 designed. Three credentials exist in it,
and the whole point of the design is that only two parties ever hold one:

```
  YOU  ──> Meta permanent access token ──> stored encrypted in OUR backend
  YOU  ──> rd_live_ API key            ──> handed to Trustpady
TRUSTPADY ──> API key ──> POST /api/v1/notifications/send ──> we send with the Meta token
```

Trustpady never sees the Meta token, never learns the `phone_number_id`,
and cannot name a tenant in a request body — the tenant is read off the
API key row (`ApiKeyPrincipal`, Rule 3). Revoking the API key ends their
access instantly and touches nothing about the WhatsApp connection.

Tenant: **RitaRock EduConsult** (`9ffb463b-763d-4c11-b435-3e2469815874`),
Owner `akangbehenry28@gmail.com`. Trustpady sends through **our own
WhatsApp number** (phone number ID
`1187105427829298`), so their API key is issued on our own tenant — a key
that can send any allowlisted template to any recipient. The rate limit and
deactivation are the only controls on it.

Everything below assumes `BASE` is the deployed backend, e.g.
`export BASE=https://customer-support-platform.onrender.com`.

---

## Step 1 — Generate the Meta access token and put it in the backend

### 1a. Mint a permanent token in Meta

A user access token from Graph Explorer expires in about an hour and is
useless here. Use a **System User** token, which does not expire:

1. **business.facebook.com** → Business Settings → **Users → System Users**
2. **Add** → name it (e.g. `support-platform-sender`) → role **Admin**
3. **Add Assets** → *Apps* → select the WhatsApp app → toggle **Manage app**
4. **Add Assets** → *WhatsApp Accounts* → select the WABA → **Manage**
5. **Generate New Token** → pick the app → select scopes:
   - `whatsapp_business_messaging` (required — this is what sends)
   - `whatsapp_business_management` (required — template + WABA reads)
6. Copy the token. Meta shows it exactly once.

Collect two more values while you are in there, from
**WhatsApp → API Setup**: the **Phone number ID** and the **WhatsApp
Business Account ID**. Neither is a secret, but both are required below.

### 1b. Store it

Log in as the tenant's Owner or Admin — `connect` is Owner/Admin only
(`WhatsAppConnectionService.requireOwnerOrAdmin`), same as issuing keys:

```bash
curl -sS -c jar.txt -X POST "$BASE/api/v1/auth/login" \
  -H 'Content-Type: application/json' \
  -d '{"email":"owner@trustpady.com","password":"..."}'

curl -sS -b jar.txt -X POST "$BASE/api/v1/whatsapp/connection" \
  -H 'Content-Type: application/json' \
  -d '{
        "phoneNumberId": "1187105427829298",
        "wabaId": "2105257327052101",
        "accessToken": "<PERMANENT_SYSTEM_USER_TOKEN>"
      }'
```

The response deliberately has no `accessToken` field
(`WhatsAppConnectionResponse`). The token is encrypted at rest by
`CredentialConverter` under `WHATSAPP_CREDENTIAL_ENCRYPTION_KEY` and is
decrypted only inside `MetaWhatsAppGateway`, in-process, per send.

`connect` is an upsert, so **token rotation is this same call again** —
no downtime, no second row, and Trustpady's API key is unaffected.

> Alternative: if the tenant owns their own WABA and would rather
> authorize us than hand over a token, `POST /api/v1/whatsapp/connection/embedded-signup`
> (ADR-011 Phase C) does the OAuth code exchange and stores the resulting
> token itself. Same end state; nobody emails a credential.

### 1c. Allowlist the templates

A send is rejected before it reaches Meta unless the template is
registered for the tenant **and** `APPROVED` (`WhatsAppTemplateService`).
Register each template Meta has approved:

```bash
curl -sS -b jar.txt -X POST "$BASE/api/v1/whatsapp/templates" \
  -H 'Content-Type: application/json' \
  -d '{"name":"trustpady_notification_utility","status":"APPROVED"}'
```

Re-posting a known name updates its status — that is how you record a
template Meta later `PAUSED` or `REJECTED`. Only `APPROVED` is sendable.

---

## Step 2 — Issue Trustpady's API key

Still as Owner/Admin, on the session-authenticated chain:

```bash
curl -sS -b jar.txt -X POST "$BASE/api/v1/api-keys" \
  -H 'Content-Type: application/json' \
  -d '{"name":"Trustpady production","rateLimit":60}'
```

```json
{
  "key": { "id": "…", "keyId": "9f2c…", "name": "Trustpady production",
           "rateLimit": 60, "active": true, "createdAt": "…" },
  "apiKey": "rd_live_9f2c….<secret>"
}
```

`apiKey` appears in this response and nowhere else, ever — only a BCrypt
hash of the secret half is stored, so it cannot be recovered from the
database or from us. If it is lost, issue a new key and deactivate the
old one.

- `rateLimit` is requests per minute for this key (default 60). Size it
  to Trustpady's real peak, not their average.
- Send it over something that does not retain history — a password
  manager share link, not email or Slack.
- Issue **one key per partner per environment**, so revoking one never
  takes another integration down with it.

Kill switch, effective on the very next request (nothing is cached):

```bash
curl -sS -b jar.txt -X POST "$BASE/api/v1/api-keys/{apiKeyId}/deactivate"
curl -sS -b jar.txt -X POST "$BASE/api/v1/api-keys/{apiKeyId}/activate"
```

Issue, deactivate and reactivate are all audited (FR-AUD-003). The audit
detail records the `keyId`, never the secret.

---

## Step 3 — Trustpady calls the endpoint

Their side, server to server. No cookie, no CORS, no session:

```bash
curl -sS -X POST "$BASE/api/v1/notifications/send" \
  -H "Authorization: Bearer rd_live_9f2c….<secret>" \
  -H 'Content-Type: application/json' \
  -d '{
        "recipient": "+2348012345678",
        "templateName": "order_shipped",
        "languageCode": "en",
        "bodyParams": ["Ada", "TP-40192"],
        "buttonUrlParam": "orders/TP-40192"
      }'
```

`202 Accepted`:

```json
{ "notificationId": "…", "status": "SENT", "metaMessageId": "wamid.…", "createdAt": "…" }
```

`X-API-Key: rd_live_…` is accepted as an alternative header for clients
that reserve `Authorization`.

The full caller-facing contract — parameters, every error code, status
polling — is the partner-facing guide; do not paste this runbook to them,
it names internals they should not need.

### Rehearsing without touching Meta

Set `WHATSAPP_MOCK=true` on a **non-production** instance and
`MockWhatsAppGateway` takes over: every send "succeeds" with a
`wamid.MOCK-…` id and nothing reaches Meta. That lets Trustpady exercise
auth, validation, rate limits and the status endpoints against a real
deployment before a single real message goes out. It is deliberately
absent from `application-prod.yml` so production cannot land there.

---

## Checklist

- [ ] System User token generated with both `whatsapp_business_*` scopes
- [ ] `POST /api/v1/whatsapp/connection` returns 200; response carries no token
- [ ] Every template Trustpady will send is registered and `APPROVED`
- [ ] API key issued, plaintext delivered securely, our copy destroyed
- [ ] Trustpady's smoke test passes against mock mode
- [ ] One real send verified end to end, then `GET /api/v1/notifications/{id}` shows `DELIVERED`
- [ ] Meta webhook still configured (`/api/v1/whatsapp/webhook`) — without it, statuses never advance past `SENT`
