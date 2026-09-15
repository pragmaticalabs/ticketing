# Processes — Event Management

Ten processes: eight request-driven, two fact consumers. This subsystem owns **events** and
**seats** and is authoritative for both. It never reaches into booking.

---

## E1. Create event

- **Trigger** — `POST /api/v1/events/create/`, **admin**.
- **Input** — venue (non-blank), on-sale time (ISO-8601).
- **Output** — the new event identifier.
- **Steps** — validate → generate identifier → insert in `draft`.

| Failure | Status |
|---|---|
| blank venue | 400 |
| malformed on-sale time (carries the offending value) | 400 |
| store unavailable | 503 |

The on-sale time MUST be parsed, not stored verbatim — an unparseable timestamp reaching storage is
the defect this prevents.

---

## E2. Open event

- **Trigger** — `POST /api/v1/events/open/{event}`, **admin**.
- **Steps** — guarded transition `draft → on_sale`. On refusal, issue a **follow-up read** to
  diagnose *why*, and map the observed status:

| Observed | Failure | Status |
|---|---|---|
| on sale | already open | 409 |
| cancelled | event cancelled | 409 |
| **still draft** | **transition raced** | 409 |

**The third case is the interesting one.** Observing the guard's own admitted status after it
refused means a concurrent change won the race between the update and the read. An implementation
MUST have an outcome for this rather than retrying blindly or reporting an impossible state.

**The diagnosis read is not atomic with the guard.** The guard is authoritative about *that* it
refused; the read is a best-effort explanation of *why*. Publishes no fact.

---

## E3. Cancel event

- **Trigger** — `POST /api/v1/events/cancel/{event}`, **admin**.
- **Steps** — guarded transition to `cancelled` from any non-cancelled status. On refusal, read; if
  already cancelled, **return success**; otherwise *transition raced*.

| Failure | Status |
|---|---|
| event not found | 404 |
| transition raced | 409 |
| invalid request / store unavailable | 400 / 503 |

**Does not cascade** — see `01-domain-model.md` §2. **Publishes no fact.**

---

## E4. Add seat

- **Trigger** — `POST /api/v1/seats/add/`, **admin**.
- **Input** — event, section, row, number, tier.
- **Steps** — validate → read the event → refuse if cancelled (draft and on-sale both accept) →
  insert the seat as `available`.

| Failure | Status |
|---|---|
| event not found | 404 |
| event cancelled | 409 |
| invalid request (blank section, blank row, bad identifier) | 400 |
| unacceptable value (non-positive number, unknown tier) | 422 |
| store unavailable | 503 |

> **Known race, inherited from the reference.** The status check is a plain read followed by an
> unguarded insert. An event cancelled between the two admits a seat onto a cancelled event. The
> reference has no outcome for this. An implementation MAY close it (a conditional insert predicated
> on the event's status) and doing so is an improvement, not a deviation.

---

## E5 / E6. Block seat and release seat

Mutual inverses, each a guarded transition. **Admin.**

| Process | Route | Transition | Refusal | Status |
|---|---|---|---|---|
| block | `POST /api/v1/seats/block/{seat}` | available → blocked | seat unavailable | 409 |
| release | `POST /api/v1/seats/release/{seat}` | blocked → available | seat not blocked | 409 |

Each is the other's compensating action. Blocking expresses **operator intent** and is authoritative
immediately, unlike sold-ness, which converges (`01-domain-model.md` §3.1).

---

## E7. Seat sellability

The seam between booking and the seats table. Booking never reads seats directly.

- **Trigger** — `GET /api/v1/seats/sellability/{seat}`, **public** (the response carries no customer,
  booking or price data).
- **Output** — seat, state, and a sellable flag.
- **Failures** — seat not found (404); invalid request (400); store unavailable (503).

The mapping and the reasoning behind `SOLD → sellable` are in `01-domain-model.md` §3.3 and are
binding. **An unknown seat is 404, never coerced to "not sellable"** — "I don't know this seat" and
"this seat is withheld" are different answers and callers act differently on them.

---

## E8. Sale status

- **Trigger** — `GET /api/v1/events/status/{event}`, **public**.
- **Output** — event, an on-sale flag, and the on-sale time.
- **Failures** — event not found (404); invalid request (400); store unavailable (503).

**Two binding details.**

1. **A missing on-sale time is rendered as an empty string, never as a null.** This is a deliberate
   wire encoding.
2. The on-sale determination compares a **parsed status value**, never a raw string. A corrupt or
   renamed stored status therefore fails decoding loudly rather than silently reading as
   "not on sale" — which would take an event off sale with no error anywhere.

---

## E9 / E10. Convergence consumers — mark seat sold, mark seat released

Fact consumers. **No route, no security block, no HTTP surface.** Their input is the fact itself.

| Process | Consumes | Transition | Authority guard |
|---|---|---|---|
| mark seat sold | seat-sold | available → sold | prior state is available |
| mark seat released | seat-released | sold → available | prior state is sold |

Both additionally require **stored version < fact version** (`01-domain-model.md` §3, §4.2).

### Handling, in order

1. **Unparseable identifier in the fact → discard silently.** A poison message cannot be retried
   into validity; retrying it forever is worse than dropping it.
2. **Guarded update applies → success.**
3. **Guard refuses → read the row and classify:**
   - stored version **≥** fact version → **absorb as success**: this is a redelivery, or the fact was
     overtaken by a later transition. Settled, nothing to do.
   - stored version **<** fact version → **genuine divergence** → fail with *not convergible*,
     carrying the seat and the observed state.

**That classification is binding.** Treating a redelivery as an error produces permanent false
alarms; treating a genuine divergence as success hides real corruption.

### What these guards do and do not earn

They order what arrives; they **do not** guarantee that anything arrives. Under at-most-once
delivery, a dropped sold-fact leaves this subsystem believing a seat is available while booking holds
it sold — **permanently, and undetectably**, since a version guard cannot see a fact it never
received. No reconciliation sweep exists.

**The failed outcome is the only operator signal**, surfaced through logging. An implementation
SHOULD do better: a reconciliation pass comparing authoritative reservation state against converged
seat state would close the gap, and is the clearest single improvement available over the reference.

### Ordering demonstrates its worth

A released-fact that overtakes its own superseding sold-fact carries the **lower** version and is
refused — so the seat correctly stays sold. Without the version guard, last-write-wins would free a
legitimately re-sold seat. Because the seat lifecycle **cycles**, this is reachable without any node
failure: consumer-side retries racing the delivery loop suffice.
