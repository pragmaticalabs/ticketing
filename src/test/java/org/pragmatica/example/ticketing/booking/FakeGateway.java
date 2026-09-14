package org.pragmatica.example.ticketing.booking;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.pragmatica.aether.resource.http.HttpClient;
import org.pragmatica.aether.resource.http.HttpClientConfig;
import org.pragmatica.http.HttpResult;
import org.pragmatica.lang.Option;
import org.pragmatica.lang.Promise;
import org.pragmatica.lang.Unit;
import org.pragmatica.lang.type.TypeToken;
import org.pragmatica.lang.utils.Causes;
import org.pragmatica.example.ticketing.booking.cancellation.cancelticket.CancelTicket;
import org.pragmatica.example.ticketing.booking.purchase.buyticket.BuyTicket;


/// Shared fake payment gateway for the booking slice tests: the slices only use
/// `postJson(url, body, Class)`. Authorize approval is configurable; void/refund succeed unless their
/// URL is listed in `failUrls`, which lets a test drive a hard provider/refund failure (as opposed to
/// a soft decline). `receipt` overrides the receipt id an approved authorization returns, so a test
/// can drive an approval whose receipt the slice cannot parse. Every `postJson` URL is appended to
/// `calls`, so a test can assert that, e.g., the authorization was VOIDED during BER compensation, or
/// that a re-driven cancellation refunded exactly once. The rest of the HttpClient surface is unused.
/// Public so the deep-package slice tests can reuse it.
public record FakeGateway(boolean approved, Set<String> failUrls, List<String> calls, Option<String> receipt) implements HttpClient {
    public FakeGateway(boolean approved) {
        this(approved, Set.of(), new ArrayList<>(), Option.empty());
    }

    /// A gateway that approves authorizations but fails the given URL hard (mapped to the slice's
    /// provider/refund-unavailable failure).
    public static FakeGateway failing(String url) {
        return new FakeGateway(true, Set.of(url), new ArrayList<>(), Option.empty());
    }

    /// A gateway that approves the authorization but returns the given receipt id -- used to drive an
    /// approved payment whose receipt the slice cannot parse.
    public static FakeGateway approvingWith(String receipt) {
        return new FakeGateway(true, Set.of(), new ArrayList<>(), Option.present(receipt));
    }

    /// How many times the given URL was posted to.
    public long callCount(String url) {
        return calls.stream()
                    .filter(url::equals)
                    .count();
    }

    @Override
    public <T> Promise<T> postJson(String url, Object body, Class<T> type) {
        calls.add(url);
        if (failUrls.contains(url)) {
            return unused();
        }

        Promise<?> response = switch (url) {
            case "/authorize" -> Promise.success(new BuyTicket.AuthResult(approved, receiptId()));
            case "/void" -> Promise.success(new BuyTicket.VoidResult("voided"));
            case "/refund" -> Promise.success(new CancelTicket.RefundResult(UUID.randomUUID().toString()));
            default -> unused();
        };

        return response.map(type::cast);
    }

    private String receiptId() {
        return receipt.or(() -> UUID.randomUUID().toString());
    }

    @Override
    public Promise<HttpResult<String>> get(String url) {
        return unused();
    }

    @Override
    public Promise<HttpResult<String>> get(String url, Map<String, String> headers) {
        return unused();
    }

    @Override
    public Promise<HttpResult<String>> post(String url, String body) {
        return unused();
    }

    @Override
    public Promise<HttpResult<String>> post(String url, String body, Map<String, String> headers) {
        return unused();
    }

    @Override
    public Promise<HttpResult<String>> put(String url, String body) {
        return unused();
    }

    @Override
    public Promise<HttpResult<String>> put(String url, String body, Map<String, String> headers) {
        return unused();
    }

    @Override
    public Promise<HttpResult<String>> delete(String url) {
        return unused();
    }

    @Override
    public Promise<HttpResult<String>> delete(String url, Map<String, String> headers) {
        return unused();
    }

    @Override
    public Promise<HttpResult<String>> patch(String url, String body) {
        return unused();
    }

    @Override
    public Promise<HttpResult<String>> patch(String url, String body, Map<String, String> headers) {
        return unused();
    }

    @Override
    public Promise<HttpResult<byte[]>> getBytes(String url) {
        return unused();
    }

    @Override
    public Promise<HttpResult<byte[]>> getBytes(String url, Map<String, String> headers) {
        return unused();
    }

    @Override
    public HttpClientConfig config() {
        return null;  // never invoked by the booking slices; present only to satisfy the interface
    }

    @Override
    public <T> Promise<T> getJson(String url, TypeToken<T> type, Option<TypeToken<?>> errorType) {
        return unused();
    }

    @Override
    public <T> Promise<T> postJson(String url, Object body, TypeToken<T> type, Option<TypeToken<?>> errorType) {
        return unused();
    }

    @Override
    public <T> Promise<T> putJson(String url, Object body, TypeToken<T> type, Option<TypeToken<?>> errorType) {
        return unused();
    }

    @Override
    public <T> Promise<T> patchJson(String url, Object body, TypeToken<T> type, Option<TypeToken<?>> errorType) {
        return unused();
    }

    @Override
    public <T> Promise<T> deleteJson(String url, TypeToken<T> type, Option<TypeToken<?>> errorType) {
        return unused();
    }

    @Override
    public Promise<Unit> deleteJsonVoid(String url) {
        return unused();
    }

    private static <T> Promise<T> unused() {
        return Causes.cause("unused gateway operation").promise();
    }
}
