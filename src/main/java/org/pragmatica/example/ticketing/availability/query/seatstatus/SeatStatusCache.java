package org.pragmatica.example.ticketing.availability.query.seatstatus;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import org.pragmatica.aether.resource.interceptor.CacheMethodInterceptor;
import org.pragmatica.aether.slice.annotation.ResourceQualifier;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;


/// Cache qualifier for the per-seat status read (rc3 `CacheMethodInterceptor`). Annotates
/// `SeatStatus.execute()`; the policy lives in `[cache.availability.seat_status]` in resources.toml.
///
/// `CACHE_ASIDE` over a `LOCAL` (in-process) backend. **Not** `TIERED`/`DISTRIBUTED`: those hold
/// app-typed results and so depend on the rc3 codec-scoping fix, which this repo has not exercised —
/// `LOCAL` needs no serialization at all, and its `InMemoryCache` never fails, which also sidesteps
/// the interceptor's fail-closed behaviour on a backend error.
///
/// The key is the whole `Request` record (no `@Key`, so the interceptor falls back to identity), and
/// only successes are cached — a `StoreUnavailable` is never memoized.
///
/// TTL is **2s**, and it is the *only* convergence mechanism: the slice runs `instances = 5`, each
/// with its own in-process cache and no cross-instance invalidation, so a seat sold right now stays
/// readable as available for up to one TTL per instance. Two seconds keeps that inside the seat-map
/// refresh a customer would make anyway, on top of the projection lag this read already carries. A
/// stale hit costs at most a wasted purchase attempt, which the contended-seat design-out fast-fails
/// with `SeatUnavailable` — it can never oversell.
@ResourceQualifier(type = CacheMethodInterceptor.class, config = "cache.availability.seat_status")
@Retention(RUNTIME)
@Target(METHOD)
public @interface SeatStatusCache {}
