# Failure Catalog

Every failure the system can produce. This is the complete set — a conforming implementation
produces these and no others through its public surface.

**Two shapes.** A **fixed-text** failure carries no data; its meaning is entirely in its identity. A
**data-carrying** failure carries values a caller can act on. The distinction is binding: a failure
listed as data-carrying MUST expose those values as structured data, not only inside a message
string.

**Messages are for operators, not users.** Customer-facing text is composed at the boundary from a
failure's identity and data. An implementation MAY change the wording; it MUST NOT change identity
or data. Tests MUST assert on identity and data, never on message text.

---

## 1. Validation — every process that parses input

| Failure | Shape | Data | Status |
|---|---|---|---|
| invalid request | data-carrying | field, reason | 400 |
| unacceptable value | data-carrying | field, reason | 422 |

These two restate shared value-object failures locally (`07-http-api.md` §4.2). The underlying
value-object failures and their statuses:

| Condition | Reason text | Status |
|---|---|---|
| blank identifier | `<Noun> id must not be blank` | 400 |
| malformed identifier | `<Noun> id must be a valid UUID` | 400 |
| blank seat section | `Seat section must not be blank` | 400 |
| blank seat row | `Seat row must not be blank` | 400 |
| malformed amount | `Amount is malformed: <raw>` | 400 |
| non-positive seat number | `Seat number must be positive` | 422 |
| unknown price tier | `Unknown price tier: <raw>` | 422 |
| unknown currency | `Unknown currency: <raw>` | 422 |
| negative amount | `Amount must not be negative: <amount>` | 422 |
| non-positive percent | `Percent must be positive: <value>` | 422 |
| unknown seat state | `Unknown seat state: <raw>` | — internal |
| currency mismatch | `Currency mismatch: <left> vs <right>` | — unreachable |

**Multiple invalid fields report the first in declaration order.** All fields are still evaluated;
only the first failure is reported.

---

## 2. Not found — 404

| Failure | Raised by |
|---|---|
| event not found | open event, cancel event, add seat, sale status |
| seat not found | seat sellability |
| booking not found | cancel ticket |
| no price available | adjust price, both quote reads |

**Availability's seat status deliberately has no 404** — an absent projection row means `available`,
not a missing seat (`05-processes-availability.md` §A1).

---

## 3. State conflicts — 409

| Failure | Raised by | Meaning |
|---|---|---|
| seat unavailable | acquire hold, buy ticket | another customer holds or owns it |
| seat unavailable (to block) | block seat | not in a blockable state |
| seat not sellable | acquire hold, buy ticket | operator withheld it, or the gate could not answer |
| seat not blocked | release seat | not currently blocked |
| event not selling | buy ticket | the event is not on sale |
| event already open | open event | |
| event cancelled | open event, add seat | |
| transition raced | open event, cancel event | **data-carrying: the observed status** |
| already cancelled | cancel ticket | |

**Distinctions that MUST survive**, despite sharing a status:

- *seat unavailable* vs *seat not sellable* — one resolves by waiting, the other does not.
- *event already open* vs *transition raced* — one is a settled answer, the other means a concurrent
  change won and the caller may usefully retry.

---

## 4. Authorization — 403

| Failure | Raised by |
|---|---|
| not owner | cancel ticket |

Distinct from *not found*; see `09-guarantees.md` §7 for the disclosure trade-off.

---

## 5. Payment — 402

| Failure | Raised by |
|---|---|
| payment declined | buy ticket |

**402 is used for a declined payment only** — a payment provider that is *unavailable* is 503. The
distinction is binding: one means the customer's payment was refused, the other means the system
could not ask.

---

## 6. Policy — 422

| Failure | Raised by | Meaning |
|---|---|---|
| customer ineligible | buy ticket | at the limit of 5 simultaneous confirmed bookings |

Shares 422 with value refusals but is a **policy** refusal. See `07-http-api.md` §4.1.

---

## 7. Dependency unavailable — 503

One constant per owning subsystem, deliberately **not** a single shared failure:

| Failure | Raised by |
|---|---|
| booking store unavailable | all booking processes |
| event management store unavailable | all event management processes |
| pricing store unavailable | pricing processes |
| quote store unavailable | quote for customer |
| availability store unavailable | availability reads |
| payment provider unavailable | buy ticket |
| refund could not be completed | cancel ticket |

**The subsystem-specific naming is binding.** An operator seeing one of these must know *which*
dependency failed without consulting a trace.

> **A known weakness worth improving.** In the reference, a **lost price-version race** — a genuinely
> retryable conflict — is remapped by a blanket handler into *pricing store unavailable*. The caller
> cannot distinguish "the store is down" from "retry and you will succeed". An implementation SHOULD
> surface a distinct retryable-conflict failure. See `04-processes-pricing-quote.md`.

---

## 8. Fact-consumer failures — never routed

The five fact-consumer processes have no HTTP surface, so nothing here maps to a status. Only the two
convergence consumers declare failures at all; they are listed because they are the **only operator
signal** those processes emit.

| Failure | Shape | Data | Raised by |
|---|---|---|---|
| seat not found | data-carrying | seat | both convergence consumers |
| seat not convergible | data-carrying | seat, observed state | both convergence consumers |

**The two availability projection consumers and the price projection consumer declare no failures at
all** — they absorb everything. That is a deliberate design decision with a real observability cost,
documented at `05-processes-availability.md` and `04-processes-pricing-quote.md` §P5.

**Deliberately absorbed as success, not failure:**
- an unparseable identifier inside a fact — a poison message, discarded;
- a fact whose version does not exceed stored state — a redelivery or an overtaken transition.

---

## 9. Outcomes that are NOT failures

Listed because each is a plausible mistake.

| Situation | Correct outcome | Why |
|---|---|---|
| cancelling an already-cancelled **event** | **success** | the postcondition holds |
| a fact redelivered after it was applied | **success** | already converged |
| a fact overtaken by a later transition | **success** | settled |
| failing to publish a fact after a committed write | **success** | the operation happened; reporting otherwise lies |
| failing to send a confirmation notification | **success** | same |
| seat contention refusing a claim | **a listed failure, not a fault** | see below |
| no projection row for a seat | **`available`** | absence means nothing has happened |

**The last one on the failure side deserves repeating.** *Seat unavailable* is a first-class failure
returned to the caller — but it is the **designed outcome of contention**, not a malfunction. It MUST
NOT feed circuit breakers, retry budgets, or error-rate alerting. On a popular event it is the
majority outcome and the system is working correctly. See `09-guarantees.md` §1.1.
