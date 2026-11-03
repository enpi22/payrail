# ADR 0001: Store money as integer minor units

## Status
Accepted

## Context
Floating-point types cannot represent most decimal values exactly (0.1 + 0.2 ≠ 0.3).
In a payments system, rounding errors accumulate and break reconciliation.

## Decision
Store all amounts as `BIGINT` in minor units (paise for INR) with an explicit currency code.
In Java, use `long` for amounts and never `double` or `float`. Fee calculations round with an
explicit, documented rule (half-up to the nearest paisa).

## Consequences
- Exact arithmetic everywhere; ledger sums are always precise.
- APIs accept and return amounts in paise (₹10.50 is `1050`), matching how real gateways work.
- Display formatting happens only at the edges (UI, reports).
