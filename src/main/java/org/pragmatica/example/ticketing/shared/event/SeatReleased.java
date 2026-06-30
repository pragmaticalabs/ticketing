package org.pragmatica.example.ticketing.shared.event;

/// Published by booking when a held or sold seat is released back to inventory.
@SuppressWarnings("JBCT-VO-01")
public record SeatReleased(String seatId, String eventId) {}
