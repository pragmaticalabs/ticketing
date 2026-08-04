package org.pragmatica.example.ticketing.shared.event;

import org.pragmatica.aether.slice.topic.Topic;


/// Published by booking when a seat is confirmed sold. `SEAT_SOLD` is the typed topic constant
/// (rc2): qualifier `config` strings reference it by identifier; the wire name stays `seat-sold`.
///
/// `version` is the per-seat sequence taken from the reservation slot (`reservations.version`, one
/// row per seat, bumped by every lifecycle transition). Consumers guard on it so a redelivered or
/// overtaking fact cannot move seat state backwards -- seat state is not terminal, so replay is not
/// self-correcting. Mirrors [PriceChanged].
@SuppressWarnings("JBCT-VO-01")
public record SeatSold(String seatId, String eventId, String bookingId, long version) {
    public static final Topic<SeatSold> SEAT_SOLD = Topic.of("seat-sold", SeatSold.class);
}
