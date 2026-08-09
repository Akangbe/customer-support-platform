# Ritarock Customer Support Platform

A multi-tenant SaaS that turns WhatsApp into a structured customer-support operation. A business connects its own WhatsApp Business Account, invites support agents, and manages every customer conversation from one collaborative dashboard — with assignment, history, real-time updates, and management visibility.

> **Working name.** "Ritarock Customer Support Platform." RitaRock EduConsult is the first tenant, not the whole market. The architecture is multi-tenant from line one.

---

## Status

| | |
|---|---|
| **Version** | 0.1 — Architecture baseline |
| **Phase** | Design → first vertical slice |
| **Backend** | Java 21 · Spring Boot · Modular Monolith · base package `com.supportplatform` |
| **Frontend** | Next.js · TypeScript *(separate repository — not part of this codebase)* |
| **Data** | PostgreSQL (Neon) · Redis · Cloudflare R2 |
| **Channel** | WhatsApp Business Platform (Cloud API) |

This README and the `docs/` tree are the **source of truth**. When a decision changes, we update the document — we do not silently drift.

---

## The one call that shapes everything

Connecting *other companies'* WhatsApp accounts makes us a Meta **Tech Provider**, which requires **App Review + Advanced access** before a single external tenant can onboard. That is a hard gate measured in weeks and it can fail.

So we split the timeline deliberately (see [ADR-011](docs/08-decisions/decision-log.md#adr-011--phased-meta-onboarding)):

1. **Prove the product on RitaRock first** as a single owned WABA (direct-developer mode — no App Review needed).
2. **Pursue Tech Provider status in parallel**, and flip on multi-tenant onboarding (Embedded Signup) when it clears.

We **architect** for multi-tenant now (`tenant_id` everywhere) but we **don't block launch** on Meta's partner review. This is the difference between shipping in weeks and being stuck behind a review queue.

---

## Documentation index

| Area | Document |
|---|---|
| Product | [`docs/00-product/product-vision.md`](docs/00-product/product-vision.md) |
| Requirements | [`docs/01-requirements/functional-requirements.md`](docs/01-requirements/functional-requirements.md) · [`non-functional-requirements.md`](docs/01-requirements/non-functional-requirements.md) |
| Domain | [`docs/02-domain/domain-model.md`](docs/02-domain/domain-model.md) |
| Architecture | [`docs/03-architecture/system-architecture.md`](docs/03-architecture/system-architecture.md) · [`architecture-principles.md`](docs/03-architecture/architecture-principles.md) · [`whatsapp-integration.md`](docs/03-architecture/whatsapp-integration.md) · [`identity-and-access.md`](docs/03-architecture/identity-and-access.md) · [`customer-domain.md`](docs/03-architecture/customer-domain.md) · [`conversation-domain.md`](docs/03-architecture/conversation-domain.md) · [`message-domain.md`](docs/03-architecture/message-domain.md) · [`whatsapp-domain.md`](docs/03-architecture/whatsapp-domain.md) · [`realtime-domain.md`](docs/03-architecture/realtime-domain.md) |
| Decisions | [`docs/08-decisions/decision-log.md`](docs/08-decisions/decision-log.md) |

## Repository layout

This repository **is the backend** — a single Maven project at the repo root, not a monorepo. The frontend dashboard and infrastructure-as-code live in separate repositories once they exist.

```
customer-support-platform/
├── docs/            # source of truth (this tree)
├── src/main/java/com/supportplatform/   # Spring Boot modular monolith, package-by-feature
├── src/main/resources/
├── src/test/java/
└── pom.xml
```

## Running locally

```
./mvnw spring-boot:run -Dspring-boot.run.profiles=local   # against a local Postgres on :5432
./mvnw test                                                # unit + Testcontainers integration tests
```

Tests need Docker running (Testcontainers spins up a real Postgres per run). On Windows with Docker Desktop's `desktop-linux` context, Testcontainers' default named-pipe lookup can fail to connect — if `./mvnw test` reports "Could not find a valid Docker environment", set `DOCKER_HOST=npipe:////./pipe/dockerDesktopLinuxEngine` (check `docker context ls` for the exact pipe on your machine), or add it permanently to `~/.testcontainers.properties` as `docker.host=npipe:////./pipe/dockerDesktopLinuxEngine`.

**WhatsApp locally:** `application.yml` ships working local-dev defaults for `app.whatsapp.*` (verify-token, app secret, credential-encryption key) so tests and local runs work with no extra setup. `-dev`/`-prod` profiles require the real `WHATSAPP_VERIFY_TOKEN` / `WHATSAPP_APP_SECRET` / `WHATSAPP_CREDENTIAL_ENCRYPTION_KEY` env vars — no defaults there. The inbound/outbound pollers (`whatsapp-domain.md` §4, §6) run every 2s whenever the app is up; integration tests disable them (`app.scheduling.enabled=false`) and call the poller beans directly instead, so a poller never races a test's own assertions.

**Known local flakiness (not a code issue):** on at least one Windows/Docker Desktop setup, a single `./mvnw clean verify` run touching every test class (several minutes of sustained Docker API + container-port traffic) can hit a `Connection refused` to the Postgres container partway through, even though `docker ps` still shows it healthy — almost certainly the same Docker Desktop named-pipe/port-forwarding instability under sustained load, not a container crash (every test class passes cleanly and consistently when run individually or in small groups, e.g. `./mvnw test -Dtest=TenantIsolationTest`). If a combined run misbehaves, split it into a couple of `-Dtest=...` invocations rather than treating it as a regression.

## Reading order for a new engineer

Product vision → functional requirements → domain model → system architecture → architecture principles → WhatsApp integration → decision log. Ninety minutes, and you understand the whole system.
