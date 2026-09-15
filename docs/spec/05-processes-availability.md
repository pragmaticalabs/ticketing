# Processes — Availability

Four processes: two customer-facing reads and two fact consumers that feed them. This subsystem is
**entirely derived**. It owns no invariant and is authoritative for nothing.

Read `09-guarantees.md` §2 first. Every process here is subject to it.

---

## A1. Seat status

- **Trigger** — `GET /api/v1/availability/seats/{seat}`, **public**.
- **Output** — the seat and its projected state.
- **Steps** — validate → read the projection → **a seat with no projection row reads as
  `available`** → respond.
- **Failures** — availability store unavailable (503); invalid request (400).

**The default is binding.** A seat that has never been sold or released has no projection row, and
absence means "nothing has happened to it", which is `available`. An implementation MUST NOT return
a not-found here — the projection's emptiness is not the seat's non-existence. (Asking whether a
seat *exists* is `E7`, which does answer 404.)

**Cached**: local per instance, unreplicated, **2-second** lifetime, successes only.

**Note** the projection only ever holds `available` or `sold`. Operator states — blocked, withdrawn
— live in event management and are **not** reflected here. A seat blocked by an operator still reads
`available` from this endpoint. Callers wanting sellability must ask `E7`.

---

## A2. Sold count

- **Trigger** — `GET /api/v1/availability/sold/{event}`, **public**.
- **Output** — the event and a count of sold seats.
- **Steps** — validate → count projected sold seats for the event.
- **Failures** — availability store unavailable (503); invalid request (400).

**Cached**: local per instance, unreplicated, **5-second** lifetime — the longest of the three read
caches, because this number is displayed and never decided upon.

**This count is not authoritative and MUST NOT be used for capacity enforcement**, settlement, or
any decision requiring correctness. It is a display figure. Under §2's loss characteristics it can
drift low or high indefinitely.

---

## A3 / A4. Projection consumers — project seat sold, project seat released

Fact consumers. **No route.**

| Process | Consumes | Sets projected state |
|---|---|---|
| project seat sold | seat-sold | sold |
| project seat released | seat-released | available |

- **Input** — the fact (seat, event, version; the sold fact also carries a booking identifier, which
  this subsystem ignores). The version is an ordering key and is not validated as a domain value.
- **Output** — always success.
- **Steps** — parse → **version-guarded upsert** → absorb any failure.

**Neither declares any failure.** A malformed fact and a store error are both swallowed, so the
subscription never wedges.

### The ordering guard

Update only when the incoming version strictly exceeds the stored one. Because seat state **cycles**
(available → sold → available), an unguarded last-write-wins upsert would let a redelivered or
delayed fact overwrite newer state — a delayed retransmit of an old sold-fact arriving after a
release must not flip the seat back to sold. The guard makes such a write a no-op regardless of
arrival order or duplicate count.

**This is the same race the convergence consumers defend** (`03-processes-eventmanagement.md`
§E9/E10) — defended independently at two layers, because the two projections are separate state with
separate consumers. An implementation MUST guard both; neither protects the other.

### Observability

The log line records **arrival and latency, never the swallowed cause** — the recovery sits inside
the process, so a logging wrapper only ever observes success.

What that earns, precisely: **a seat that has diverged with no log line was never delivered a fact
at all; one with a log line was delivered, and the store write is the remaining suspect.** That is a
genuine diagnostic distinction and it is the *only* one available. It does not tell you whether a
delivered fact actually converged the row.

There is no dead-letter, no retry, and no alert.

### The dangerous direction

**A lost release-fact is worse than a lost sold-fact.** It strands a seat as unavailable that nobody
holds — the seat is genuinely free, browsable inventory shows it taken, and nothing repairs it.
A lost sold-fact merely shows a taken seat as free, and the claim path refuses the resulting attempt
harmlessly (`09-guarantees.md` §1).

An implementation SHOULD weigh reconciliation accordingly: the release direction is where absence of
repair actually costs revenue.

---

## What this subsystem can and cannot cause

**Cannot cause an oversell.** A stale `available` read costs at most one wasted purchase attempt,
which the claim path fast-fails. Contention safety lives entirely in booking's design-out and does
not depend on anything here.

**Can cause** a false "unavailable" display (a seat nobody holds shown as taken), a wrong sold count,
and two clients receiving different answers for the same seat at the same moment from different
instances.

That asymmetry is the point of the design: **the read model is allowed to be wrong because nothing
correctness-critical consults it.** An implementation that starts gating decisions on these reads
breaks that reasoning and must revisit every guarantee in `09-guarantees.md` §2.
