package org.pragmatica.example.ticketing.shared.event;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import org.pragmatica.aether.slice.Subscriber;
import org.pragmatica.aether.slice.annotation.ResourceQualifier;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;


/// Subscription qualifier for the `seat-released` fact topic (typed constant `SeatReleased.SEAT_RELEASED`). Annotate a slice's `execute(SeatReleased)` method.
@ResourceQualifier(type = Subscriber.class, config = "SEAT_RELEASED")
@Retention(RUNTIME)
@Target(METHOD)
public @interface SeatReleasedSubscription {}
