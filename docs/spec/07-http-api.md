# HTTP API

The complete wire contract: 19 endpoints, covering 19 of the 24 processes. The remaining five — the
fact consumers — have no HTTP surface at all. The hold sweep has **both** a schedule and a route.

---

## 1. Complete surface

| Method | Path | Access | Failure statuses |
|---|---|---|---|
| GET | `/api/v1/availability/seats/{seat}` | public | 400, 503 |
| GET | `/api/v1/availability/sold/{event}` | public | 400, 503 |
| GET | `/api/v1/seats/sellability/{seat}` | public | 400, 404, 503 |
| GET | `/api/v1/events/status/{event}` | public | 400, 404, 503 |
| GET | `/api/v1/pricing/quote/{event}/{tier}` | public | 400, 404, 422, 503 |
| GET | `/api/v1/quotes/{event}/{tier}` | public | 400, 404, 422, 503 |
| POST | `/api/v1/booking/holds/` | authenticated | 400, 409, 503 |
| GET | `/api/v1/booking/holds/{seat}` | authenticated | 400, 503 |
| POST | `/api/v1/booking/buy/` | authenticated | 400, 402, 409, 422, 503 |
| POST | `/api/v1/booking/cancel/` | authenticated | 400, 403, 404, 409, 503 |
| POST | `/api/v1/booking/holds/sweep/` | **operator** | 503 |
| POST | `/api/v1/events/create/` | **admin** | 400, 503 |
| POST | `/api/v1/events/open/{event}` | **admin** | 400, 404, 409, 503 |
| POST | `/api/v1/events/cancel/{event}` | **admin** | 400, 404, 409, 503 |
| POST | `/api/v1/seats/add/` | **admin** | 400, 404, 409, 422, 503 |
| POST | `/api/v1/seats/block/{seat}` | **admin** | 400, 409, 503 |
| POST | `/api/v1/seats/release/{seat}` | **admin** | 400, 409, 503 |
| POST | `/api/v1/pricing/set/` | **admin** | 400, 422, 503 |
| POST | `/api/v1/pricing/adjust/` | **admin** | 400, 404, 422, 503 |

**Every endpoint may also return 500.** See §4.

Access distribution: 6 public, 4 authenticated, 8 admin, 1 operator.

---

## 2. Payloads

Path parameters carry identifiers; request bodies carry the remaining fields. **All identifiers cross
the wire as strings** and are parsed at the boundary.

### Requests

| Endpoint | Body |
|---|---|
| create event | `venue`, `onSaleAt` |
| add seat | `event`, `section`, `row`, `number` (integer), `tier` |
| set price | `event`, `tier`, `amount`, `currency` |
| adjust price | `event`, `tier`, `percent` (integer) |
| acquire hold | `customer`, `event`, `seat` |
| buy ticket | `customer`, `event`, `seat`, `tier` |
| cancel ticket | `booking`, `customer` |
| sweep holds | empty |

Open event, cancel event, block seat, release seat, check hold, seat status, sold count, sale status
and both quote reads take their input entirely from the path.

### Responses

| Endpoint | Body |
|---|---|
| create event | `event` |
| add seat | `seat` |
| open / cancel event | `event` |
| block / release seat | `seat` |
| sale status | `event`, `onSale` (boolean), `onSaleAt` (**empty string when absent, never null**) |
| seat sellability | `seat`, `state`, `sellable` (boolean) |
| seat status | `seat`, `state` |
| sold count | `event`, `sold` (integer) |
| both quote reads | `event`, `tier`, `amountMinor` (integer), `currency`, `version` |
| set / adjust price | `version` |
| acquire hold | `reservation`, `state` (always `FRESH`) |
| check hold | `seat`, `state` (one of `FRESH`, `STALE`, `EXPIRED`, `SOLD`, `NONE`) |
| buy ticket | `booking`, `ticket`, `seat`, `receipt`, `amountMinor`, `currency` |
| cancel ticket | `booking`, `receipt` |
| sweep holds | `released` (integer) |

**Money crosses the wire as an integer minor-unit amount plus a currency code** — never a decimal
string, never a float.

**Seat state is rendered lowercase** on the wire; price tiers uppercase. Both are parsed
case-insensitively on input.

---

## 3. Access levels

Four levels: **public**, **authenticated**, **admin**, **operator**.

Every route declares one explicitly; there is no implicit default. An implementation MUST NOT leave
a route's access level implicit — before this was made explicit in the reference, the entire
administrative write surface was reachable by anyone.

**Ownership is checked separately from authentication.** Being authenticated does not entitle a
customer to another customer's booking; cancellation checks ownership and refuses distinctly. See
`09-guarantees.md` §7, including the disclosure trade-off that distinctness implies.

---

## 4. Failure mapping

Every failure listed in `08-failure-catalog.md` maps to exactly one status, and **500 is the default
for anything unmapped**.

**In the reference, an unmapped failure is a build error, not a runtime 500.** Totality is enforced
at compile time, so the closed failure sets in the process documents are provably complete with
respect to routing.

**An implementation SHOULD reproduce that property by whatever means its stack allows** — an
exhaustive match over a sum type, a total lookup checked by a test that enumerates every failure, a
generated table. Reaching the 500 default should be *impossible*, not merely unlikely, because the
default is what silently swallows a failure that deserved a specific answer.

### 4.1 The 400/422 distinction

Applied consistently and binding:

| | Meaning | Examples |
|---|---|---|
| **400** | **Malformed** — could not be parsed into its domain type | blank or non-UUID identifier, unparseable amount, blank section or row, malformed timestamp |
| **422** | **Well-formed but unacceptable** — parsed fine, value not admitted | unknown tier, unknown currency, negative amount, non-positive seat number, non-positive percent, customer over the booking limit |

Note the last one: *customer over the booking limit* is a **policy** refusal, not a value problem,
and it shares 422 with value refusals. An implementation MUST keep the two distinguishable as
failures even though they share a status.

### 4.2 A structural trap worth knowing

In the reference, a router matches only failures declared **in the routed process's own scope**. A
failure raised by a shared value object therefore matches nothing and falls through to 500.

The consequence: **every process restates shared validation failures as its own** — typically as an
invalid-request or unacceptable-value failure carrying the offending field and reason. That is why
the same two failure shapes recur in nearly every process in this specification.

Additionally, a combinator that validates several fields at once wraps even a single failure in a
composite type, which likewise matches nothing. The reference unwraps it, yielding **the first
failing field in declaration order**.

**Neither of these is inherent** — they are artifacts of the reference's routing. An implementation
whose error mapping handles shared failures directly SHOULD simplify accordingly. What is **binding**
is the observable result: a malformed field produces a 400 (or 422) naming that field and its reason,
never a 500, and **multiple invalid fields report the first in declaration order**.

---

## 5. Success statuses

**No route file in the reference declares a success status** — 2xx comes from framework defaults, so
it is not part of the reference's contract files.

An implementation SHOULD use 200 for reads and for operations returning a body, and MAY use 201 for
the three creating operations (create event, add seat, buy ticket). Clients MUST NOT depend on the
distinction, since the reference does not specify it.
