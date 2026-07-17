package org.pragmatica.example.ticketing.shared.event;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import org.pragmatica.aether.slice.Publisher;
import org.pragmatica.aether.slice.annotation.ResourceQualifier;

import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.RetentionPolicy.RUNTIME;


/// Publisher qualifier for the `price-changed` fact topic (typed constant `PriceChanged.PRICE_CHANGED`). Inject as `@PriceChangedPublisher Publisher<PriceChanged>` in a slice factory.
@ResourceQualifier(type = Publisher.class, config = "PRICE_CHANGED")
@Retention(RUNTIME)
@Target(PARAMETER)
public @interface PriceChangedPublisher {}
