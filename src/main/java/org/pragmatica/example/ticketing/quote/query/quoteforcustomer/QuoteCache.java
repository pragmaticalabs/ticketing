package org.pragmatica.example.ticketing.quote.query.quoteforcustomer;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import org.pragmatica.aether.resource.interceptor.CacheMethodInterceptor;
import org.pragmatica.aether.slice.annotation.ResourceQualifier;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;


/// Cache qualifier for the customer-facing price read (rc3 `CacheMethodInterceptor`). Annotates
/// `QuoteForCustomer.execute()`; the policy lives in `[cache.quote.quote_for_customer]` in
/// resources.toml.
///
/// `CACHE_ASIDE` over a `LOCAL` (in-process) backend. **Not** `TIERED`/`DISTRIBUTED`: those hold
/// app-typed results and so depend on the rc3 codec-scoping fix, which this repo has not exercised.
///
/// The key is the whole `Request` record (identity fallback — no `@Key`); only successes are cached,
/// so a `PriceNotFound` for a price that is about to appear is never memoized.
///
/// TTL is **5s** and is the only convergence mechanism — `instances = 5`, each with a private
/// in-process cache, and nothing invalidates it when a `PriceChanged` fact lands. A repricing
/// therefore reaches customers one TTL after the projection catches up. This cache cannot cause a
/// wrong charge: `BuyTicket` reads the authoritative amount synchronously from the pricing slice via
/// `QuotePrice` and never from this projection. What a long TTL would cost is trust — a customer
/// quoted the old price and charged the new one — which is why it stays short despite prices
/// changing far less often than seat state.
@ResourceQualifier(type = CacheMethodInterceptor.class, config = "cache.quote.quote_for_customer")
@Retention(RUNTIME)
@Target(METHOD)
public @interface QuoteCache {}
