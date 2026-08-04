package org.pragmatica.example.ticketing.availability.projection.projectseatsold;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import org.pragmatica.aether.resource.interceptor.LoggingMethodInterceptor;
import org.pragmatica.aether.slice.annotation.ResourceQualifier;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;


/// Logging qualifier for the `SeatSold` availability projection (rc3 `LoggingMethodInterceptor`).
/// Annotates `ProjectSeatSold.execute()`; the policy lives in `[log.availability.project_seat_sold]`
/// in resources.toml. The interceptor carries its own slf4j binding, which is why it is the right
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
/// What the entry/exit pair does settle is the question this slice's own docs call out as its
/// unclosed gap: at-most-once delivery. A seat that diverges with no log line here was never
/// delivered; one with a line was delivered and the store call is the remaining suspect. `INFO` so
/// that holds under a default production config; volume is bounded by the sale rate. `log_args =
/// true` is safe — `SeatSold` carries only identifiers and a version, no customer or payment data —
/// and is what names the seat to investigate.
@ResourceQualifier(type = LoggingMethodInterceptor.class, config = "log.availability.project_seat_sold")
@Retention(RUNTIME)
@Target(METHOD)
public @interface ProjectSeatSoldLog {}
