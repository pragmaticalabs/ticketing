package org.pragmatica.example.ticketing.shared;

import org.pragmatica.example.ticketing.availability.query.seatstatus.SeatStatus;
import org.pragmatica.example.ticketing.availability.query.seatstatus.SeatStatusRoutes;
import org.pragmatica.example.ticketing.booking.purchase.buyticket.BuyTicket;
import org.pragmatica.example.ticketing.booking.purchase.buyticket.BuyTicketRoutes;
import org.pragmatica.example.ticketing.eventmanagement.capacity.addseat.AddSeat;
import org.pragmatica.example.ticketing.eventmanagement.capacity.addseat.AddSeatRoutes;
import org.pragmatica.example.ticketing.pricing.schedule.setprice.SetPrice;
import org.pragmatica.example.ticketing.pricing.schedule.setprice.SetPriceRoutes;
import org.pragmatica.example.ticketing.quote.query.quoteforcustomer.QuoteForCustomer;
import org.pragmatica.example.ticketing.quote.query.quoteforcustomer.QuoteForCustomerRoutes;
import org.pragmatica.http.HttpStatus;
import org.pragmatica.lang.Cause;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;


/// Cross-cutting contract: a malformed request reaches the client as 400, and a well-formed but
/// unacceptable value as 422 -- never as 500. Pinned once here because it is a whole-API property,
/// across all five subsystems.
///
/// It is fragile for a structural reason. Slices validate through the shared value objects in this
/// package, whose failures are `SeatId.Error`, `CustomerId.Error` and friends. The slice processor
/// builds each generated router's error switch from the Cause types in that slice's OWN package
/// alone, so a shared cause arrives unmatched and falls through to `default ->
/// INTERNAL_SERVER_ERROR`. Every slice must restate the failure as its own typed cause at the
/// validation boundary; these tests assert the generated routers actually see the result.
///
/// The companion half -- that each slice returns its own cause rather than a shared or composite one,
/// including the `Result.all` multi-field case -- lives in the per-slice tests, because the
/// `valid...()` factories are package-private by design. Those were verified by mutation: removing a
/// slice's closing `Validation::firstFailure` turns them red, since `Result.all` wraps even a single
/// failure in a composite that the router cannot match.
class ValidationMappingTest {
    @Test
    void errorMapper_buyTicketInvalidRequest_mapsToBadRequest() {
        assertThat(new BuyTicketRoutes().errorMapper().map(BuyTicket.BuyError.invalidSeat(reason())).status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void errorMapper_buyTicketUnacceptableTier_mapsToUnprocessableEntity() {
        assertThat(new BuyTicketRoutes().errorMapper().map(BuyTicket.BuyError.unacceptableTier(reason())).status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void errorMapper_addSeatInvalidRequest_mapsToBadRequest() {
        assertThat(new AddSeatRoutes().errorMapper().map(AddSeat.AddSeatError.invalidEvent(reason())).status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void errorMapper_setPriceInvalidRequest_mapsToBadRequest() {
        assertThat(new SetPriceRoutes().errorMapper().map(SetPrice.PricingError.invalidEvent(reason())).status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void errorMapper_seatStatusInvalidRequest_mapsToBadRequest() {
        assertThat(new SeatStatusRoutes().errorMapper().map(SeatStatus.AvailabilityError.invalidSeat(reason())).status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void errorMapper_quoteForCustomerInvalidRequest_mapsToBadRequest() {
        assertThat(new QuoteForCustomerRoutes().errorMapper()
                                               .map(QuoteForCustomer.QuoteError.invalidEvent(reason()))
                                               .status()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /// A shared value-object failure -- exactly the kind that used to reach the router unmatched.
    private static Cause reason() {
        return SeatId.Error.malformed();
    }
}
