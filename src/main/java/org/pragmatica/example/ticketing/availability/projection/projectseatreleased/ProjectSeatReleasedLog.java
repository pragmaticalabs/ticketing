package org.pragmatica.example.ticketing.availability.projection.projectseatreleased;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import org.pragmatica.aether.resource.interceptor.LoggingMethodInterceptor;
import org.pragmatica.aether.slice.annotation.ResourceQualifier;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;


/// Logging qualifier for the `SeatReleased` availability projection (rc3
/// `LoggingMethodInterceptor`). Annotates `ProjectSeatReleased.execute()`; the policy lives in
/// `[log.availability.project_seat_released]` in resources.toml. The interceptor carries its own
/// slf4j binding, which is why it is the right answer here: log4j/slf4j are test-scope only, so
/// `src/main/java` has no logger of its own.
///
/// **What this earns, precisely:** arrival and latency, not the swallowed cause. `execute` ends in
/// `.recover(_ -> Unit.unit())` *inside* the slice, and the interceptor wraps `execute` from the
/// outside, so it observes `Success(())` on every invocation — including the ones where the upsert
/// failed. `log_result` is therefore `false`: it would print a constant. Moving the `recover`
/// outward would expose the cause but is a delivery-semantics change and is deliberately not made —
/// under today's ephemeral pub-sub nothing observes the returned `Promise`, and after a future
/// stream migration a propagated failure would commit the cursor and lose the event outright.
///
/// What the entry/exit pair does settle is at-most-once delivery: a seat left stale with no log line
/// here never received its fact; one with a line did, and the store call is the remaining suspect.
/// This is the release side, where a lost fact strands a seat as unavailable that nobody holds —
/// the failure mode the sweep reaper exists to catch, and the one worth being able to date. `INFO`
/// so it holds under a default production config; volume is bounded by the release rate. `log_args =
/// true` is safe — `SeatReleased` carries only identifiers and a version, no customer or payment
/// data — and is what names the seat to investigate.
@ResourceQualifier(type = LoggingMethodInterceptor.class, config = "log.availability.project_seat_released")
@Retention(RUNTIME)
@Target(METHOD)
public @interface ProjectSeatReleasedLog {}
