package org.pragmatica.example.ticketing.shared.event;

import org.pragmatica.aether.slice.topic.Topic;


/// Published by booking when a seat is confirmed sold. `SEAT_SOLD` is the typed topic constant
/// (rc2): qualifier `config` strings reference it by identifier; the wire name stays `seat-sold`.
@SuppressWarnings("JBCT-VO-01")
public record SeatSold(String seatId, String eventId, String bookingId) {
    public static final Topic<SeatSold> SEAT_SOLD = Topic.of("seat-sold", SeatSold.class);
}
