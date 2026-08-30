#!/usr/bin/env bash
# Start the local stub payment gateway (WireMock) that BuyTicket and CancelTicket call.
#
# Why a stub and not a real sandbox: the slices call POST /authorize, /refund and /void with this
# project's own JSON shapes (AuthRequest/AuthResult, RefundRequest/RefundResult). No third-party
# sandbox — Stripe, PayPal, Adyen — implements that contract, so using one would mean writing an
# adapter plus creating an account and handling API keys. The stubs in gateway-stubs/mappings/ are
# the contract, in executable form, and they run offline with no credentials.
#
# Mirrors ./start-postgres.sh: idempotent, auto-detects docker or podman, safe to re-run.
set -euo pipefail

PORT="${GATEWAY_PORT:-9100}"
NAME="ticketing-gateway"
IMAGE="wiremock/wiremock:3.9.1"
STUBS="$(cd "$(dirname "$0")" && pwd)/gateway-stubs"

RUNTIME=""
for candidate in docker podman; do
  if command -v "$candidate" >/dev/null 2>&1; then RUNTIME="$candidate"; break; fi
done
if [ -z "$RUNTIME" ]; then
  echo "ERROR: neither docker nor podman found on PATH." >&2
  exit 1
fi

if "$RUNTIME" ps --format '{{.Names}}' 2>/dev/null | grep -qx "$NAME"; then
  echo "Payment gateway stub already running (container: $NAME)"
else
  if "$RUNTIME" ps -a --format '{{.Names}}' 2>/dev/null | grep -qx "$NAME"; then
    echo "Removing stopped container $NAME..."
    "$RUNTIME" rm -f "$NAME" >/dev/null
  fi
  echo "Starting payment gateway stub (WireMock) on port $PORT..."
  "$RUNTIME" run -d --name "$NAME" \
    -p "${PORT}:8080" \
    -v "${STUBS}:/home/wiremock:ro" \
    "$IMAGE" --global-response-templating >/dev/null
fi

printf 'Waiting for gateway'
for _ in $(seq 1 30); do
  if curl -fsS "http://localhost:${PORT}/__admin/mappings" >/dev/null 2>&1; then
    echo
    echo "Payment gateway running on port ${PORT}"
    echo "  POST /authorize  -> {\"approved\":true,\"receiptId\":\"<uuid>\"}"
    echo "  POST /authorize  -> approved=false when amountMinor == 66600 (drives the 402 path)"
    echo "  POST /refund     -> {\"receiptId\":\"<uuid>\"}"
    echo "  POST /void       -> {\"receiptId\":\"<uuid>\"}"
    echo "  Admin/UI: http://localhost:${PORT}/__admin/"
    exit 0
  fi
  printf '.'
  sleep 1
done

echo >&2
echo "ERROR: gateway did not become ready on port ${PORT}. Container logs:" >&2
"$RUNTIME" logs --tail 30 "$NAME" >&2 || true
exit 1
