package org.pragmatica.example.ticketing.shared.event;

import java.lang.annotation.Retention;
import java.lang.annotation.Target;

import org.pragmatica.aether.slice.Subscriber;
import org.pragmatica.aether.slice.annotation.ResourceQualifier;

import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.RetentionPolicy.RUNTIME;


/// Subscription qualifier for the `price-changed` fact topic (typed constant `PriceChanged.PRICE_CHANGED`). Annotate a slice's `execute(PriceChanged)` method.
@ResourceQualifier(type = Subscriber.class, config = "PRICE_CHANGED")
@Retention(RUNTIME)
@Target(METHOD)
public @interface PriceChangedSubscription {}
