package org.pragmatica.example.ticketing.availability.query.soldcount;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import org.pragmatica.aether.resource.interceptor.CacheMethodInterceptor;
import org.pragmatica.aether.slice.annotation.ResourceQualifier;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;


/// Cache qualifier for the per-event sold counter (rc3 `CacheMethodInterceptor`). Annotates
/// `SoldCount.execute()`; the policy lives in `[cache.availability.sold-count]` in resources.toml.
///
/// `CACHE_ASIDE` over a `LOCAL` (in-process) backend. **Not** `TIERED`/`DISTRIBUTED`: those hold
/// app-typed results and so depend on the rc3 codec-scoping fix, which this repo has not exercised.
///
/// The key is the whole `Request` record (identity fallback — no `@Key`); only successes are cached.
///
/// TTL is **5s**, the longest of the three read caches, and it is the only convergence mechanism —
/// `instances = 5`, each with a private in-process cache and no cross-instance invalidation. This is
/// the one read backed by an aggregate (`count(*)` over `seat_availability`), so it is both the most
/// expensive query to repeat and the most tolerant of lag: the number is displayed, never decided
/// on. Sales move it continuously, so a longer TTL would visibly freeze a ticker that customers
/// watch; five seconds absorbs a burst of sales without doing that.
@ResourceQualifier(type = CacheMethodInterceptor.class, config = "cache.availability.sold-count")
@Retention(RUNTIME)
@Target(METHOD)
public @interface SoldCountCache {}
