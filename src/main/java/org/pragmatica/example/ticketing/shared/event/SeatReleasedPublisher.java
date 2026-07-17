package org.pragmatica.example.ticketing.shared.event;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import org.pragmatica.aether.slice.Publisher;
import org.pragmatica.aether.slice.annotation.ResourceQualifier;

import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;


/// Publisher qualifier for the `seat-released` fact topic (typed constant `SeatReleased.SEAT_RELEASED`). Inject as `@SeatReleasedPublisher Publisher<SeatReleased>` in a slice factory.
@ResourceQualifier(type = Publisher.class, config = "SEAT_RELEASED")
@Retention(RUNTIME)
@Target(PARAMETER)
public @interface SeatReleasedPublisher {}
