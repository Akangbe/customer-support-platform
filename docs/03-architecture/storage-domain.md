# Storage / Media Design

**Status:** Proposed · v0.1

FR-MSG-005 asks for "text, media/file" message types; every phase since
Phase 5 has deferred the media half to this module, which
`system-architecture.md` already named (Cloudflare R2, S3-compatible)
and `domain-model.md` already gave a glossary entry (`Attachment`).
This document is where both promises get kept. Per the engineering
process, it's paired with ADR-020, since the media-sending strategy
below is a real fork with consequences for how `whatsapp-domain.md`'s
outbound path behaves.

---

## 1. Scope

**In scope:**
- `Attachment`: tenant-scoped metadata row; bytes live in object
  storage, never in Postgres.
- `StorageGateway`: the boundary interface (Rule 4's pattern, same as
  `WhatsAppGateway`) — `R2StorageGateway` is the only class allowed to
  import an S3/AWS SDK shape.
- Outbound: `POST /api/v1/attachments` (upload), then
  `SendMessageRequest` gains an optional `attachmentId` — the same
  extension pattern `templateName` used in Phase 6.
- Inbound: extending `WebhookEventHandler` to handle Meta's
  `image`/`document`/`audio`/`video` message types, not just `text`.
- `GET /api/v1/attachments/{id}`: a short-lived presigned URL, not a
  byte-proxying download — matches `system-architecture.md`'s own
  description of the module ("object-storage read/write **and signed
  URLs**").
- ADR-020: link-based media sending to Meta, not upload-to-Meta-first.
- MinIO via Testcontainers for local/test — R2's own S3-compatible
  protocol, so the same client code path is exercised without needing
  real Cloudflare credentials in CI.

**Out of scope (deferred, not forgotten):**
- Stickers, location, contact-card message types. FR-MSG-005 says
  "media/file" — stickers are a WhatsApp-specific format with their
  own constraints (webp, fixed dimensions) that don't map cleanly to
  "a file an agent or customer attached," and location/contacts aren't
  files at all. Add if a real requirement shows up.
- Virus/content scanning on upload. No requirement asks for it;
  revisit if this platform ever handles regulated content.
- Thumbnails/transcoding. The dashboard can render whatever the
  browser natively supports from a signed URL; no processing pipeline
  exists or is asked for.
- Per-tenant storage quotas or billing. Real future need, not a Phase
  concern today.

## 2. Attachment model

| Column | Type | Notes |
|---|---|---|
| `id` | uuid, PK | |
| `tenant_id` | uuid, FK → tenant.id, not null | |
| `message_id` | uuid, FK → message.id, nullable, unique | Null until a message actually references it (an uploaded-but-not-yet-sent attachment); unique because WhatsApp's Cloud API model gives each media message exactly one media object — never a join table for many-to-one |
| `object_key` | varchar, not null | `tenants/{tenantId}/attachments/{attachmentId}/{fileName}` — tenant-namespaced so a key collision across tenants is structurally impossible, even though only our backend ever holds bucket credentials |
| `content_type` | varchar, not null | |
| `file_name` | varchar, nullable | Original filename, if the client sent one — display purposes only |
| `size_bytes` | bigint, not null | |
| `created_at` | timestamptz, not null | |

**Message gets no new column.** The FK lives on `Attachment`, not
`Message` — avoids an ALTER on a table four prior phases already
depend on, and matches the natural direction of the relationship
(`domain-model.md`: "Attachment... associated with a message").
`SendMessageRequest.body` becomes optional at the DTO level: WhatsApp
allows a media message with no caption, so requiring text when an
attachment is present would reject a legitimate send. `MessageService`
still rejects a request with neither body, template, nor attachment —
same `ResponseStatusException(BAD_REQUEST)` pattern `whatsapp-domain.md`
§8 already established for the template/language-code check.

## 3. The storage boundary

```java
public interface StorageGateway {
    void upload(String objectKey, byte[] content, String contentType);
    byte[] download(String objectKey);
    URI generatePresignedGetUrl(String objectKey, Duration ttl);
}
```

`R2StorageGateway` implements this with the AWS SDK v2 S3 client,
endpoint-overridden to R2's S3-compatible URL (`region = "auto"`, R2's
own convention) — exactly the same shape Rule 4 already established
for `WhatsAppGateway`/`MetaWhatsAppGateway`: one boundary class per
external system, everything else depends only on the interface.

## 4. Outbound: upload, then send

```
POST /api/v1/attachments   (multipart)
  → validates size/content-type, uploads to R2, persists an
    Attachment row with message_id = null, returns its id

POST /api/v1/conversations/{id}/messages
  { "attachmentId": "...", "body": "optional caption" }
  → MessageService links the attachment (sets message_id), same
    24h-window / closed-conversation checks Phase 5 already enforces
```

An attachment is only ever writable once, at upload — nothing updates
`object_key` or `content_type` afterward. Linking it to a message is
the only mutation, and it's one-way: once linked, an attachment stays
linked (no requirement asks for detaching/reusing one across messages,
and WhatsApp's own model doesn't support that either).

**Authorization:** same as sending a message — any authenticated
tenant member (message-domain.md §5). Uploading a file isn't a more
privileged act than typing a caption.

## 5. Outbound to Meta: link-based, not upload-first (ADR-020)

`MetaWhatsAppGateway` gains:

```java
SendResult sendMedia(WhatsAppConnection connection, String toPhone, String mediaType, URI link, String caption);
```

`OutboundMessageSender`/`MessageDispatcher` (whatsapp-domain.md §6),
when a message has a linked attachment, calls
`storageGateway.generatePresignedGetUrl(objectKey, SHORT_TTL)` and
passes that URL straight to `sendMedia` — Meta fetches the bytes
itself from the presigned URL rather than us pushing them through a
separate Meta upload call first. Full trade-off reasoning in ADR-020.

## 6. Inbound: media messages from a customer

`WebhookEventHandler.handleInboundMessage` currently assumes every
message is `type: "text"`. It now branches on Meta's `type` field:

```
type = "text"                        → existing path, unchanged
type in {image, document, audio, video} →
    1. read the type-keyed object (e.g. message.image) for its media_id
    2. GET https://graph.facebook.com/{version}/{media_id} (connection's token)
       → a temporary Meta-hosted URL + mime_type, valid briefly
    3. GET that URL (same token) → raw bytes
    4. storageGateway.upload(objectKey, bytes, mimeType)
    5. persist Attachment (message_id set once the Message below exists)
    6. messageService.recordInbound(..., body = caption if present else "")
       then link the Attachment to the resulting Message
```

This is a new `WhatsAppGateway` method,
`byte[] downloadMedia(WhatsAppConnection connection, String mediaId)`
wrapping steps 2–3 — `MetaWhatsAppGateway` stays the only class that
ever sees Meta's media-retrieval shape. A media message with a
`media_id` Meta can't resolve (deleted, expired) fails this event the
same way any other processing exception does — retried with the
existing `4^attempt` backoff (whatsapp-domain.md §4), not a new failure
mode.

## 7. Reading an attachment

```
GET /api/v1/attachments/{id}
  → { "url": "https://...presigned...", "expiresAt": "..." }
```

Tenant-scoped like everything else (`findByIdAndTenantId`), any
authenticated tenant member (matches viewing the conversation it
belongs to). The backend never proxies attachment bytes through
itself — it hands out a time-boxed URL and steps out of the way,
matching `system-architecture.md`'s description of the module's job.

## 8. Testing: MinIO, not real R2

Cloudflare R2 speaks the S3 protocol, so a `MinIOContainer`
(Testcontainers' official module) stands in for it in tests exactly
the way the Postgres Testcontainer already stands in for Neon — same
client code path (`R2StorageGateway`, real S3 SDK calls), zero real
Cloudflare credentials needed to run the suite.

## 9. Authorization summary

| Action | Who |
|---|---|
| Upload an attachment | Any authenticated tenant member |
| Send a message with an attachment | Any authenticated tenant member (unchanged from message-domain.md §5) |
| Read/download an attachment | Any authenticated tenant member |

## 10. Tenant isolation

Same shape as every prior phase: `tenant_id` on `attachment`, every
lookup `findByIdAndTenantId`, object keys tenant-namespaced as
defense-in-depth even though bucket credentials never leave the
backend.

## 11. Configuration

```yaml
app:
  storage:
    endpoint: ${STORAGE_ENDPOINT}                # R2 account S3 endpoint; a local MinIO URL in dev
    bucket: ${STORAGE_BUCKET}
    access-key: ${STORAGE_ACCESS_KEY}
    secret-key: ${STORAGE_SECRET_KEY}
    presigned-url-ttl: ${STORAGE_PRESIGNED_URL_TTL:PT15M}
```

Platform-level credentials, not tenant data — one bucket for the whole
deployment, same reasoning `whatsapp-domain.md` §12 already applied to
the Meta app secret (one app, one environment, never per-tenant).
Unlike `WhatsAppConnection.accessToken`, these never touch Postgres at
all, so no `CredentialConverter`-style encryption is needed here —
they're config, read once at startup, exactly like the DB password
already is.
