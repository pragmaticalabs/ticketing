package org.pragmatica.example.ticketing.shared;

import org.pragmatica.lang.Cause;
import org.pragmatica.lang.Option;


/// Unwraps the composite cause that `Result.all(...)` builds around field-level validation failures.
///
/// `Result.all` reports every failing field, and it does so by wrapping the failures -- even a single
/// one -- in `Causes.CompositeCause`, a core type that lives outside every slice's package. The Aether
/// slice processor generates the router's error switch from the `Cause` types it finds in the routed
/// slice's own package alone, so a composite reaches that switch unmatched and falls through to
/// `default` (HTTP 500) however carefully each individual field was mapped to a slice-local cause. A
/// `valid...()` factory that parses more than one field must therefore finish with
/// `.mapError(Validation::firstFailure)`.
///
/// Guarantee earned, precisely: the response names the **first failing field in request order**, with
/// the HTTP status that field's own slice-local cause type earns; later failures are still evaluated
/// (`Result.all` inspects every input) but are dropped from the response. `Cause.stream()` yields a
/// single element for a non-composite cause, so applying this to an already-unwrapped cause is a no-op
/// and the helper is safe at the end of any validation chain.
public sealed interface Validation {
    /// The first cause carried by a composite, or the cause itself when it carries no others.
    static Cause firstFailure(Cause cause) {
        return Option.from(cause.stream().findFirst()).or(cause);
    }

    record unused() implements Validation {}
}
