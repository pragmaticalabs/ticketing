package org.pragmatica.example.ticketing.shared.event;

/// Published by pricing when a seat's price changes; version orders successive changes for a seat.
@SuppressWarnings("JBCT-VO-01")
public record PriceChanged(String eventId,
                           String seatId,
                           String tier,
                           long amountMinor,
                           String currency,
                           long version) {}
