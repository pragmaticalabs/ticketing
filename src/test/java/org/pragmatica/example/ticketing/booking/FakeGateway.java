package org.pragmatica.example.ticketing.booking;

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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;


/// Shared fake payment gateway for the booking slice tests: the slices only use
/// `postJson(url, body, Class)`. Authorize approval is configurable; void/refund succeed unless their
/// URL is listed in `failUrls`, which lets a test drive a hard provider/refund failure (as opposed to
/// a soft decline). Every `postJson` URL is appended to `calls`, so a test can assert that, e.g., the
/// authorization was VOIDED during BER compensation. The rest of the HttpClient surface is unused.
/// Public so the deep-package slice tests can reuse it.
public record FakeGateway(boolean approved, Set<String> failUrls, List<String> calls) implements HttpClient {
    public FakeGateway(boolean approved) {
        this(approved, Set.of(), new ArrayList<>());
    }

    /// A gateway that approves authorizations but fails the given URL hard (mapped to the slice's
    /// provider/refund-unavailable failure).
    public static FakeGateway failing(String url) {
        return new FakeGateway(true, Set.of(url), new ArrayList<>());
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> Promise<T> postJson(String url, Object body, Class<T> type) {
        calls.add(url);

        if (failUrls.contains(url)) {
            return unused();
        }

        Promise<?> response = switch (url) {
            case "/authorize" -> Promise.success(new BuyTicket.AuthResult(approved,
                                                                          UUID.randomUUID().toString()));
            case "/void" -> Promise.success(new BuyTicket.VoidResult("voided"));
            case "/refund" -> Promise.success(new CancelTicket.RefundResult(UUID.randomUUID().toString()));
            default -> unused();
        };

        return (Promise<T>) response;
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
