package org.pragmatica.example.ticketing.shared.event;

import org.pragmatica.aether.slice.topic.Topic;


/// Published by booking when a held or sold seat is released back to inventory. `SEAT_RELEASED` is
/// the typed topic constant (rc2): qualifier `config` strings reference it by identifier; the wire
/// name stays `seat-released`.
///
/// `version` is the per-seat sequence taken from the reservation slot (`reservations.version`, one
/// row per seat, bumped by every lifecycle transition). Consumers guard on it so a `SeatReleased`
/// that overtakes its `SeatSold` cannot leave the seat available when it is in fact sold.
@SuppressWarnings("JBCT-VO-01")
public record SeatReleased(String seatId, String eventId, long version) {
    public static final Topic<SeatReleased> SEAT_RELEASED = Topic.of("seat-released", SeatReleased.class);
}
