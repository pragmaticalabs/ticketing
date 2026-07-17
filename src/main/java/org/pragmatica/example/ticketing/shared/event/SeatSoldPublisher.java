package org.pragmatica.example.ticketing.shared.event;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import org.pragmatica.aether.slice.Publisher;
import org.pragmatica.aether.slice.annotation.ResourceQualifier;

import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;


/// Publisher qualifier for the `seat-sold` fact topic (typed constant `SeatSold.SEAT_SOLD`). Inject as `@SeatSoldPublisher Publisher<SeatSold>` in a slice factory.
@ResourceQualifier(type = Publisher.class, config = "SEAT_SOLD")
@Retention(RUNTIME)
@Target(PARAMETER)
public @interface SeatSoldPublisher {}
