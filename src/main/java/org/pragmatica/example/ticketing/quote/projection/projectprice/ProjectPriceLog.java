package org.pragmatica.example.ticketing.quote.projection.projectprice;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import org.pragmatica.aether.resource.interceptor.LoggingMethodInterceptor;
import org.pragmatica.aether.slice.annotation.ResourceQualifier;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;


/// Logging qualifier for the `PriceChanged` quote projection (rc3 `LoggingMethodInterceptor`).
/// Annotates `ProjectPrice.execute()`; the policy lives in `[log.quote.project_price]` in
/// resources.toml. The interceptor carries its own slf4j binding, which is why it is the right
/// answer here: log4j/slf4j are test-scope only, so `src/main/java` has no logger of its own.
///
/// **What this earns, precisely:** arrival and latency, not the swallowed cause. `execute` ends in
/// `.recover(_ -> Unit.unit())` *inside* the slice, and the interceptor wraps `execute` from the
/// outside, so it observes `Success(())` on every invocation — including the ones where the upsert
/// failed. `log_result` is therefore `false`: it would print a constant. Moving the `recover`
/// outward would expose the cause but is a delivery-semantics change and is deliberately not made —
/// under today's ephemeral pub-sub nothing observes the returned `Promise`, and after a future
/// stream migration a propagated failure would commit the cursor and lose the event outright.
///
/// What the entry/exit pair does settle is at-most-once delivery, and it matters most here: a lost
/// `PriceChanged` leaves `price_view` quoting a superseded price indefinitely, with no version guard
/// able to notice a version that never arrived and — since `QuoteCache` holds `LOCAL` entries with
/// no invalidation — no cache expiry able to correct it either. A repricing with no line here was
/// never delivered. `INFO` so it holds under a default production config; volume is bounded by the
/// repricing rate, the lowest of the five consumers. `log_args = true` is safe — `PriceChanged`
/// carries an event id, tier, amount, currency and version, no customer or payment data.
@ResourceQualifier(type = LoggingMethodInterceptor.class, config = "log.quote.project_price")
@Retention(RUNTIME)
@Target(METHOD)
public @interface ProjectPriceLog {}
