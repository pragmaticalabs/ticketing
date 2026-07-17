package org.pragmatica.example.ticketing.shared.event;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import org.pragmatica.aether.slice.Subscriber;
import org.pragmatica.aether.slice.annotation.ResourceQualifier;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;


/// Subscription qualifier for the `seat-sold` fact topic (typed constant `SeatSold.SEAT_SOLD`). Annotate a slice's `execute(SeatSold)` method.
@ResourceQualifier(type = Subscriber.class, config = "SEAT_SOLD")
@Retention(RUNTIME)
@Target(METHOD)
public @interface SeatSoldSubscription {}
