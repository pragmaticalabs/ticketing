package org.pragmatica.example.ticketing.shared.event;

import org.pragmatica.aether.slice.Subscriber;
import org.pragmatica.aether.slice.annotation.ResourceQualifier;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;


/// Subscription qualifier for the `seat-sold` fact topic. Annotate a slice's `execute(SeatSold)` method.
@ResourceQualifier(type = Subscriber.class, config = "seat-sold")
@Retention(RUNTIME)
@Target(METHOD)
public @interface SeatSoldSubscription {}
