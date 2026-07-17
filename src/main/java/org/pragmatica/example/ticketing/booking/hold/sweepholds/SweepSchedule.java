package org.pragmatica.example.ticketing.booking.hold.sweepholds;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import org.pragmatica.aether.slice.Scheduled;
import org.pragmatica.aether.slice.annotation.ResourceQualifier;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;


/// Scheduler qualifier for the hold-expiry sweep (rc2 `Scheduled`). Annotates `SweepHolds.sweep()`;
/// the cadence lives in `[scheduling.sweep-holds]` in resources.toml.
@ResourceQualifier(type = Scheduled.class, config = "scheduling.sweep-holds")
@Retention(RUNTIME)
@Target(METHOD)
public @interface SweepSchedule {}
