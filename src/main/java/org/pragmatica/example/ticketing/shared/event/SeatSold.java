package org.pragmatica.example.ticketing.shared.event;

/// Published by booking when a seat is confirmed sold.
@SuppressWarnings("JBCT-VO-01")
public record SeatSold(String seatId, String eventId, String bookingId) {}
