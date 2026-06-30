package org.pragmatica.example.ticketing.shared.event;

import org.pragmatica.aether.slice.Publisher;
import org.pragmatica.aether.slice.annotation.ResourceQualifier;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;


/// Publisher qualifier for the `seat-sold` fact topic. Inject as `@SeatSoldPublisher Publisher<SeatSold>` in a slice factory.
@ResourceQualifier(type = Publisher.class, config = "seat-sold")
@Retention(RUNTIME)
@Target(PARAMETER)
public @interface SeatSoldPublisher {}
