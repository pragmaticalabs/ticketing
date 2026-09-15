# Ticketing Platform — Implementation Specification

A **language-neutral, framework-neutral** specification of an event-ticketing platform, written so
that an independent team — or an autonomous agent — can re-implement it on any stack and arrive at a
system with the same observable behavior and the same guarantees.

The reference implementation is Java on a distributed slice runtime. **Nothing in that stack is
part of the specification.** Where this document must describe a mechanism (an upsert that
serializes contention, an append-only log), it describes the *property the mechanism must have*, and
names the reference technique only as an existence proof.

---

## 1. How to read this specification

| Document | Contains |
|---|---|
| `01-domain-model.md` | Entities, value objects, their invariants, and the two state machines |
| `02-processes-booking.md` | Holds, purchase, cancellation, hold expiry |
| `03-processes-eventmanagement.md` | Event lifecycle, seat capacity, sellability, convergence |
| `04-processes-pricing-quote.md` | Price scheduling, price history, quoting, the quote read model |
| `05-processes-availability.md` | Seat status and sold-count read models |
| `06-data-model.md` | Persistent state, every constraint, and the invariant each one defends |
| `07-http-api.md` | The complete wire contract: routes, payloads, statuses, authorization |
| `08-failure-catalog.md` | Every failure the system can produce, its data, and its status |
| `09-guarantees.md` | What each operation actually guarantees under concurrency and partial failure |
| `10-conformance.md` | The behaviors an implementation must demonstrate to claim conformance |

Read `09-guarantees.md` before writing any code. It is the document most likely to be skipped and
most expensive to get wrong: several operations here look like ordinary CRUD and are not.

---

## 2. What this system is

An event-ticketing platform: an operator creates events and seats, puts them on sale, and prices
them; a customer sees what is available, holds a seat, buys it, and may cancel. Seats are
individually identified and individually sold — this is reserved seating, not general admission.

The platform is decomposed into **five subsystems**:

| Subsystem | Owns | Nature |
|---|---|---|
| **event management** | events, seats, their lifecycles | authoritative write |
| **booking** | reservations, bookings, payments | authoritative write |
| **pricing** | the price history | authoritative write |
| **availability** | seat status and sold counts for readers | derived read model |
| **quote** | customer-facing prices for readers | derived read model |

The split between the three **authoritative** subsystems and the two **derived** ones is the single
most important structural fact in this document. Authoritative subsystems own state and enforce
invariants transactionally. Derived subsystems own **copies** that lag, may be read stale, and must
never be used to make a decision that requires correctness. A conforming implementation may deploy
all five as one process or as five services; it may not blur that distinction.

---

## 3. The unit of design is the process

This specification is organized around **processes**, not entities. A process is a named unit of
behavior with six properties, and every process document states all six:

1. **Trigger** — what starts it (a request, a schedule, a published fact).
2. **Typed input** — what it accepts, and the rules that make an input valid.
3. **Typed output** — what it returns on success.
4. **Typed failures** — the **closed, enumerable** set of ways it can fail.
5. **Steps** — the ordered work it performs.
6. **Dependencies** — what it needs to do that work.

There are **24 processes**. They are the specification's backbone: a conforming implementation
implements all 24 with the stated inputs, outputs and failure sets.

### 3.1 Typed failures are binding; their encoding is not

Every process declares a **closed set** of failures. Closed means: the process fails in exactly
those ways and no others, and a caller can enumerate them at design time and handle each one.

**This is binding.** An implementation must be able to distinguish every listed failure of a process
from every other one, and must not collapse two listed failures into one indistinguishable outcome.

**The encoding is free.** The reference implementation uses a sealed sum type per process. A target
language without sum types may use error codes, tagged unions, subclassed exceptions, a result
enum — whatever is idiomatic — provided the distinctions survive. What does **not** conform:

- collapsing distinct failures into one generic error, or into a bare string;
- signalling failure only through a message intended for humans;
- letting an unlisted failure escape (an unhandled runtime fault reaching the caller as a 500 where
  the specification names a typed outcome);
- making the failure set open, so callers cannot exhaustively handle it.

The reason the set is closed is that **failure is part of the contract**. Every failure listed here
maps to a defined HTTP status in `07-http-api.md`, and several of them are *expected outcomes of
correct operation under contention*, not faults — a distinction `09-guarantees.md` returns to,
because treating them as faults is the most likely way to build something that fails under load.

### 3.2 What "language-neutral" does and does not permit

**Binding:** the 24 processes and their six properties; every invariant in `01-domain-model.md` and
`06-data-model.md`; every guarantee in `09-guarantees.md`; the wire contract in `07-http-api.md`;
the failure catalog in `08-failure-catalog.md`; the conformance behaviors in `10-conformance.md`.

**Free:** language, runtime, concurrency model, framework, deployment topology, persistence engine
(subject to the constraints in `06-data-model.md`), synchronous vs asynchronous style, and the
internal structure of each process.

Where a document says **MUST**, an implementation that does otherwise is non-conforming. **SHOULD**
marks a strong recommendation whose violation should be deliberate. **MAY** marks genuine latitude.

---

## 4. Vocabulary

Terms used precisely throughout. Where a word has a loose industry meaning, the meaning here is the
narrow one.

| Term | Meaning in this specification |
|---|---|
| **Event** | A performance that can be sold: has a lifecycle, a venue, an on-sale time |
| **Seat** | An individually identified, individually sellable place at an event |
| **Hold** | A time-bounded exclusive claim on a seat by one customer, before purchase |
| **Reservation** | The persistent record of a claim on a seat, in some state |
| **Booking** | The record of a completed sale |
| **Claim** | A single attempt to take a seat; identified separately from the seat itself |
| **Fact** | A statement that something happened, published for derived subsystems to consume |
| **Projection** | Derived state built by consuming facts |
| **Authoritative read** | A read served from the state that owns the invariant |
| **Derived read** | A read served from a projection, which may lag |

**Deliberately avoided:** "eventually consistent", "strongly consistent", "highly available",
"exactly-once". These name a system with one bit and hide the operation-level truth. Every guarantee
in this specification is stated **per operation**, with the mechanism that earns it and the behavior
when that mechanism fails. See `09-guarantees.md`.

---

## 5. Conformance in one paragraph

An implementation conforms if: it implements all 24 processes with the specified inputs, outputs and
closed failure sets; it upholds every invariant in `01-domain-model.md` and `06-data-model.md`; it
provides the guarantees in `09-guarantees.md` under the concurrency and failure conditions described
there; it exposes the contract in `07-http-api.md`; and it demonstrates the behaviors in
`10-conformance.md`. It need not resemble the reference implementation in any other respect.
