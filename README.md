# Payrail

A payment gateway backend: merchant APIs, smart routing across multiple providers,
a double-entry ledger, signed webhooks with retries, daily reconciliation, and an
AI ops assistant.

> Status: in progress. Full design in [`docs/design.md`](docs/design.md).

## Highlights
- **No double charges:** idempotency keys on every write API.
- **Ledger that can't be wrong:** double-entry, enforced by PostgreSQL to always balance, append-only.
- **Never lose an event:** transactional outbox → Kafka.
- **Resilient to provider outages:** success-rate routing with circuit breakers.
- **Reliable webhooks:** HMAC-signed, exponential backoff, dead-letter queue.

## Tech stack
Java 21 · Spring Boot 3 · PostgreSQL 16 · Redis 7 · Kafka · Python (FastAPI) ·
Docker · Kubernetes + Helm · Terraform · GitHub Actions · Prometheus + Grafana · k6

## Run locally
```bash
cp .env.example .env
docker compose up -d        # Postgres, Redis, Kafka, Kafka UI (http://localhost:8090)
```

## Repository layout
```
services/
  payment-service/     core API, routing, ledger, outbox (Spring Boot)
  mock-providers/      three simulated payment providers
  webhook-dispatcher/  Kafka consumer delivering signed webhooks
  reconciliation/      daily settlement reconciliation job
  ops-assistant/       LLM-powered ops assistant (FastAPI)
infra/                 Terraform and Kubernetes manifests
load-tests/            k6 scripts
docs/                  design doc and architecture decision records
```
