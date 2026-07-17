package org.pragmatica.example.ticketing.shared.event;

import org.pragmatica.aether.slice.topic.Topic;


/// Published by pricing when a seat's price changes; version orders successive changes for a seat.
/// `PRICE_CHANGED` is the typed topic constant (rc2): qualifier `config` strings reference it by
/// identifier; the wire name stays `price-changed`.
@SuppressWarnings("JBCT-VO-01")
public record PriceChanged(String eventId,
                           String seatId,
                           String tier,
                           long amountMinor,
                           String currency,
                           long version) {
    public static final Topic<PriceChanged> PRICE_CHANGED = Topic.of("price-changed", PriceChanged.class);
}
