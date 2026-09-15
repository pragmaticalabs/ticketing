# Processes — Pricing and Quoting

Five processes across two subsystems. **Pricing** is authoritative and owns the append-only price
history. **Quote** is a derived read model serving customers.

The split matters: there are **two different "read a price" processes**, and using the wrong one is
a correctness bug, not a performance choice.

---

## P1. Set price

- **Trigger** — `POST /api/v1/pricing/set/`, **admin**.
- **Input** — event, tier, amount, currency.
- **Output** — the new version.
- **Steps** — validate → **append** a new price entry, allocating its version atomically → update the
  authoritative latest-value projection → publish a price-changed fact → return the version.

| Failure | Status |
|---|---|
| invalid request (bad identifier, malformed amount) | 400 |
| unacceptable value (unknown tier, negative amount, unknown currency) | 422 |
| pricing store unavailable | 503 |

**No not-found outcome** — this process never reads before writing.

**Recovery — design-out.** A correction is a new appended version, never an overwrite.

---

## P2. Adjust price

Scale the current price by a demand percentage.

- **Trigger** — `POST /api/v1/pricing/adjust/`, **admin**.
- **Input** — event, tier, percent (strictly positive; 110 raises by 10%, 90 lowers by 10%).
- **Steps** — validate → read the current price (absent → *no price available*, **404**) →
  scale → append a new version → update the projection → publish → return the version.

Failures are those of P1 **plus no-price-available (404)**.

Because the percentage is strictly positive by construction, adjustment can neither zero nor invert a
price (`01-domain-model.md` §1.3). Rounding is half-up at minor-unit scale and is binding.

---

## P3. Quote price — the authoritative read

- **Trigger** — `GET /api/v1/pricing/quote/{event}/{tier}`, **public**.
- **Steps** — validate → read the **authoritative** latest-value projection → absent → 404.
- **Failures** — no price available (404); invalid request (400); unacceptable value (422); pricing
  store unavailable (503).

**Deliberately uncached.** This is the always-fresh path, and it is what purchase uses to decide what
to charge. An implementation MUST NOT put a cache in front of it without recognizing that it is
changing what the system charges customers.

---

## P4. Quote for customer — the derived read

Same question, different guarantee.

- **Trigger** — `GET /api/v1/quotes/{event}/{tier}`, **public**.
- **Steps** — validate → read the **quote subsystem's own projection** → absent → 404.
- **Failures** — same shape as P3, but *quote store unavailable* is a distinct outcome from *pricing
  store unavailable*.

**Cached**: read-through, **local to each instance, unreplicated, 5-second lifetime**, keyed by the
whole request. **Only successes are cached** — a not-found is never memoized, so a newly priced scope
appears as soon as it exists rather than after a cache expiry.

### The two read paths, side by side

| | P3 authoritative | P4 derived |
|---|---|---|
| Reads | pricing's own projection | the quote subsystem's projection |
| Written | synchronously, in the same operation as the append | asynchronously, from facts |
| Cached | no | yes, 5s, per instance |
| Freshness | current within pricing | see `09-guarantees.md` §2 |
| Use for | **deciding what to charge** | **displaying a price** |

**Using P4 to decide a charge is a correctness bug.** Using P3 for high-volume display forgoes the
cache but is merely wasteful. The staleness in P4 cannot cause a wrong charge **only because**
purchase reads P3 — an implementation that reroutes purchase through the cached path turns a display
inconsistency into a financial one.

---

## P5. Project price

Fact consumer. **No route.**

- **Trigger** — subscription to the price-changed topic.
- **Input** — the fact: event, seat, tier, amount, currency, version.
- **Output** — always success.
- **Steps** — parse → **monotonic upsert** into the quote projection → absorb any failure.

**This process declares no failures at all.** Both a malformed fact and a store error are swallowed,
so the subscription never wedges.

> **Observability consequence, stated plainly.** A lost or failed update is surfaced **nowhere**
> except an arrival log line. There is no version-gap detector, no dead-letter, no retry, and no
> cache-invalidation hook. A *missing* log line is the only trace that the quote projection — and
> therefore every customer-facing quote for that scope — is now **permanently** stale. Nothing
> self-heals it.
>
> An implementation SHOULD add a gap detector: versions are monotonic per scope, so a consumer can
> notice a skipped version and alert. The reference does not.

---

## Price allocation — the race and what the loser sees

Appending allocates the next version **within a single statement**, deriving it from the current
maximum, under a **uniqueness constraint on (event, tier, version)**.

Two concurrent appends for the same scope can compute the same next version before either commits.
The constraint lets exactly one succeed; the loser's write is rejected.

> **A weakness worth improving on.** In the reference, the loser's rejection is remapped by a blanket
> handler into the generic *pricing store unavailable* (503). The caller cannot distinguish "the
> database is down" from "you lost a version race, retry and you will succeed", and no automatic
> retry happens. The **guarantee** — that history is never corrupted — holds. The **diagnosis** is
> poor. An implementation SHOULD surface a distinct retryable-conflict outcome.

A read-then-insert equivalent is **not conforming**: it reintroduces the race, and the uniqueness
constraint then surfaces it as a spurious error rather than preventing corruption.

---

## Projection upserts are monotonic

Both projections apply the same rule: **update only when the incoming version strictly exceeds the
stored one.** An equal or lower version is a silent no-op — not an error, not an overwrite.

This makes replay and out-of-order delivery converge without coordination, and is binding for both
projections.

---

## Scope keys

Prices are scoped by `(event, tier)`. The reference composes a scope key by joining the event
identifier and tier name.

The data model reserves seat-level price scoping and **it is never exercised** — no process reads or
writes a seat-scoped price. An implementation MAY omit seat-level scoping entirely.
