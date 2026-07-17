package org.pragmatica.example.ticketing.shared.event;

import org.pragmatica.aether.slice.topic.Topic;


/// Published by booking when a held or sold seat is released back to inventory. `SEAT_RELEASED` is
/// the typed topic constant (rc2): qualifier `config` strings reference it by identifier; the wire
/// name stays `seat-released`.
@SuppressWarnings("JBCT-VO-01")
public record SeatReleased(String seatId, String eventId) {
    public static final Topic<SeatReleased> SEAT_RELEASED = Topic.of("seat-released", SeatReleased.class);
}
