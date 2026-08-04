package org.pragmatica.example.ticketing.eventmanagement.convergence.markseatsold;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import org.pragmatica.aether.resource.interceptor.LoggingMethodInterceptor;
import org.pragmatica.aether.slice.annotation.ResourceQualifier;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;


/// Logging qualifier for the `SeatSold` convergence consumer (rc3 `LoggingMethodInterceptor`).
/// Annotates `MarkSeatSold.execute()`; the policy lives in `[log.eventmanagement.mark_seat_sold]` in
/// resources.toml. The interceptor carries its own slf4j binding, which is why it is the right
/// answer here: log4j/slf4j are test-scope only, so `src/main/java` has no logger of its own.
///
/// This consumer **propagates** its failures — a store outage and a `SeatNotConvergible` divergence
/// both leave `execute` as a failed `Promise` — so the interceptor sees them and `log_result = true`
/// is load-bearing: it is what turns "booking and eventmanagement genuinely disagree about this
/// seat" from an unobserved `Promise` into an operator-visible line. Nothing observes the returned
/// `Promise` under today's ephemeral pub-sub, so without this the divergence is lost entirely.
///
/// `INFO`, because the interceptor has no failure-only level and any lower level would hide the
/// divergence under a default production config. Volume is bounded by the sale rate, not by read
/// traffic, and a seat sale is an event worth an audit line. `log_args = true` is safe and necessary
/// here: `SeatSold` carries only identifiers and a version — no customer, payment, or contact data —
/// and without the seat id an error line names no seat to investigate.
@ResourceQualifier(type = LoggingMethodInterceptor.class, config = "log.eventmanagement.mark_seat_sold")
@Retention(RUNTIME)
@Target(METHOD)
public @interface MarkSeatSoldLog {}
