# ADR 0002: Double-entry ledger enforced by the database

## Status
Accepted

## Context
Payment state ("succeeded") tells us what happened, but not where money is owed. Finance,
settlements, and reconciliation need a complete, auditable record of every money movement.

## Decision
Record every money movement as a ledger transaction with entries that sum to zero.
Enforce this in PostgreSQL with a deferred constraint trigger, and make ledger tables
append-only with triggers that reject UPDATE and DELETE. Corrections use reversing entries.
Postings happen in the same DB transaction as the payment state change.

## Consequences
- An unbalanced or edited ledger is impossible, even with application bugs.
- Account balances are computed by summing entries (cache them later if needed).
- Slightly more write volume per payment (one transaction, three entries).
