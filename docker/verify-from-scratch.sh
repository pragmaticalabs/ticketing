#!/usr/bin/env bash
# Run this repository's documented quickstart end to end, from a bare Linux image.
#
# WHY THIS EXISTS
# A build on a developer machine proves almost nothing about this project: three of its
# dependencies are unpublished (see #668), so a local ~/.m2 that happens to hold them makes a
# broken quickstart look green. This script deliberately supplies NOTHING from the host -- no
# ~/.m2 mount, no ~/.aether mount -- so every step the README omits becomes a hard failure
# instead of something the machine quietly provides.
#
# WHAT IT PROVES
# Not "the build succeeded". It asserts an HTTP status per documented route and fails loudly on
# any divergence, ending with a purchase that returns a real receipt.
#
# USAGE:  ./docker/verify-from-scratch.sh        (about 6-8 minutes on a cold Docker cache)
set -uo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PG=tkt-verify-pg
APP=tkt-verify-app
IMG=ticketing-bare:verify
KEY="local-dev-insecure-do-not-use"

log() { printf '\n=== %s ===\n' "$*"; }
fail() { printf '\nFAILED: %s\n' "$*" >&2; exit 1; }

log "0/7 cleanup any previous run"
docker rm -f "$PG" "$APP" >/dev/null 2>&1
docker volume rm tkt-verify-m2 >/dev/null 2>&1

log "1/7 build the bare toolchain image (ubuntu + JDK 25 + Maven)"
docker build -t "$IMG" "$REPO_ROOT/docker" || fail "image build"

log "2/7 start PostgreSQL 17"
docker run -d --name "$PG" -e POSTGRES_PASSWORD=postgres -e POSTGRES_DB=forge postgres:17 >/dev/null || fail "postgres start"
for _ in $(seq 1 60); do docker exec "$PG" pg_isready -U postgres >/dev/null 2>&1 && break; sleep 1; done
docker exec "$PG" pg_isready -U postgres || fail "postgres never became ready"

# The app container SHARES the postgres container's network namespace. That is what lets
# aether.toml's documented `localhost:5432`, the stub gateway's `localhost:9100` and the app's
# own ports all resolve exactly as they do on a laptop, with no config rewriting.
log "3/7 start the work container (shares the postgres network namespace)"
docker volume create tkt-verify-m2 >/dev/null
docker run -d --name "$APP" --network "container:$PG" -v tkt-verify-m2:/root/.m2 \
    "$IMG" sleep infinity >/dev/null || fail "app container start"

# Seed sources from the WORKING TREE, not from HEAD.
# This script exists to verify the repository you actually have, so it must copy what is on disk,
# including uncommitted edits. Seeding from `git archive HEAD` looks equivalent and is not: it
# silently verifies the committed tree while you are editing the working one, so a fix you just made
# is absent from the run that is supposed to prove it. That produced a confusing "app never served"
# here -- the cluster was healthy and the container was simply running the old config.
# A fresh `git clone` is not used because this repo's origin is SSH-only and the container must not
# hold credentials.
# COPYFILE_DISABLE=1 and the ._* exclude are both needed on macOS: BSD tar otherwise emits an
# AppleDouble "._Foo.java" sidecar next to every file, and the jbct formatter then tries to parse
# those binary sidecars as Java and fails the build with MalformedInputException on a file the
# developer has never seen.
COPYFILE_DISABLE=1 tar -cf /tmp/tkt-src.tar -C "$REPO_ROOT" \
    --exclude=.git --exclude=target --exclude=.m2-local --exclude='._*' . \
    || fail "tar working tree"
docker exec "$APP" mkdir -p /work/ticketing
docker cp /tmp/tkt-src.tar "$APP:/work/src.tar" >/dev/null
docker exec "$APP" bash -c 'cd /work/ticketing && tar xf /work/src.tar' || fail "unpack sources"

# Perform the documented [app-http] enabling step -- DELIBERATELY, and exactly as the README tells a
# reader to do it by hand.
#
# `aether.toml` ships with [app-http] commented out, so without this the walkthrough 401s on its
# first call. The temptation is to special-case that here, or to inject the key some other way. Do
# NOT: this script's value is that it exercises the REAL documented path, so that a wrong or
# incomplete instruction in the README shows up as a failing verification rather than as a reader's
# problem months later. If the enabling step ever stops working, this run SHOULD go red.
#
# The sed is the mechanical equivalent of the human instruction ("delete the leading '# ' from each
# line between the two ENABLE markers"): it strips the comment prefix inside the marked range and
# leaves the marker lines themselves alone.
log "3b/7 enable [app-http] -- the documented step, performed as documented"
docker exec "$APP" bash -c \
    'sed -i "/^# --- ENABLE BELOW ---$/,/^# --- ENABLE ABOVE ---$/{/^# --- ENABLE /!s/^# \?//}" /work/ticketing/aether.toml' \
    || fail "enabling [app-http]"
# Verify by CONTENT that the step actually landed. An unverified edit inside a container is exactly
# the shape that reports success for work that never happened.
docker exec "$APP" bash -c '
    a=$(grep -c "^\[app-http\]$" /work/ticketing/aether.toml)
    b=$(grep -c "^security_mode = " /work/ticketing/aether.toml)
    c=$(grep -c "^\[app-http\.api-keys\." /work/ticketing/aether.toml)
    echo "  [app-http]=$a security_mode=$b api-keys-table=$c (each must be 1)"
    [ "$a" = "1" ] && [ "$b" = "1" ] && [ "$c" = "1" ]' \
    || fail "the documented [app-http] step did not take effect -- the README instruction is wrong or stale"

log "4/7 Step 0 -- build the three unpublished artifacts from the rc3 tag"
docker exec "$APP" bash -c '
set -e
git clone --depth 1 --branch v1.0.0-rc3 https://github.com/pragmaticalabs/pragmatica.git /work/pragmatica >/dev/null 2>&1
cd /work/pragmatica
mvn -B install -DskipTests -Djbct.skip=true -pl jbct/jbct-maven-plugin,jbct/slice-processor,aether/pg-tools/pg-codegen -am > /work/p1.log 2>&1
mvn -B install -DskipTests -Djbct.skip=true -pl aether/resource/api,aether/resource/notification,aether/resource/interceptors -am > /work/p2.log 2>&1
for a in resource-api resource-notification resource-interceptors; do
  ls /root/.m2/repository/org/pragmatica-lite/aether/$a/1.0.0-rc3/*.jar >/dev/null || { echo "MISSING $a"; exit 1; }
done' || fail "Step 0 (see: docker exec $APP tail -40 /work/p2.log)"
echo "three unpublished artifacts installed"

log "5/7 install Forge from the published release archive, and build this project"
docker exec "$APP" bash -c '
set -e
curl -fsSL https://raw.githubusercontent.com/pragmaticalabs/pragmatica/main/install.sh | sh -s -- --version 1.0.0-rc3 > /work/install.log 2>&1
test -x /root/.aether/bin/aether-forge
cd /work/ticketing && mvn -B clean install -DskipTests > /work/build.log 2>&1' \
    || fail "project build (see: docker exec $APP tail -40 /work/build.log)"
echo "aether-forge installed; ticketing built"

# ---------------------------------------------------------------------------
# Steps 6 and 7 are wrapped in functions so the cluster start can be attempted
# TWICE. This is a deliberate, bounded concession to a KNOWN product race --
# pragmaticalabs/pragmatica#1218 -- and not a way of making this script go green.
#
# The race: ForgeServer awaits its startup blueprint deploy on a HARDCODED
# `TimeSpan.timeSpan(10).seconds()`, issued without waiting for cluster
# readiness. Measured here: full membership at T+14s against a T+10s give-up,
# so Forge exits by design ("so the cluster does not run empty while appearing
# healthy"). `forge.toml`'s `startTimeoutSeconds` does NOT cover this step, so
# raising it changes nothing.
#
# The rules this retry obeys, because a silent retry would have exactly the
# defect this whole script exists to prevent -- an instrument that reports
# success without reporting what it took:
#   * it retries ONCE, never "until green";
#   * a first-attempt failure is announced LOUDLY when it happens;
#   * and it is announced AGAIN in the final verdict, so a passing run still
#     says the race fired;
#   * two failed attempts remain a hard failure.
# ---------------------------------------------------------------------------

start_cluster() {
  log "6/7 start the stub gateway and Forge (forge-data wiped first -- see README)"
  docker exec -d "$APP" bash -c 'python3 /work/ticketing/scripts/stub-gateway.py > /work/gateway.log 2>&1'
  # forge-data must go before EVERY attempt, not just the first: a failed start
  # can leave state behind, and re-running Forge over it is #668's root cause.
  docker exec "$APP" bash -c 'pkill -f "[a]ether-forge"; rm -rf /root/.aether/forge-data' >/dev/null 2>&1
  docker exec -d "$APP" bash -c 'cd /work/ticketing && exec /root/.aether/bin/aether-forge --config /work/ticketing/forge.toml --blueprint org.pragmatica.example:ticketing:1.0.0-SNAPSHOT:blueprint > /work/forge.log 2>&1'

  printf 'waiting for the app port to serve'
  local c
  for _ in $(seq 1 90); do
    c=$(docker exec "$APP" curl -s -o /dev/null -m 5 -w '%{http_code}' -X POST http://localhost:8070/api/v1/events/create \
          -H "X-API-Key: $KEY" -H 'Content-Type: application/json' -d '{"venue":"warmup","onSaleAt":"2026-07-01T10:00:00Z"}' 2>/dev/null)
    [ "$c" = "200" ] && { echo; return 0; }
    printf '.'; sleep 5
  done
  echo
  echo "  app never served (docker exec $APP tail -50 /work/forge.log)" >&2
  return 1
}

run_walkthrough() {
  log "7/7 drive the documented walkthrough and ASSERT each status"
  docker exec "$APP" bash -c '
  K="X-API-Key: '"$KEY"'"; J="Content-Type: application/json"; A=http://localhost:8070
  fails=0
  check() { # label expected method path body
    # NB: the body is passed through an array, not an unquoted ${5:+-d "$5"} expansion. That idiom
    # word-splits, so a body containing a space ("O2 Arena") is torn into two curl arguments and the
    # request silently malforms. Found the hard way.
    local out code
    local -a data=()
    [ -n "${5:-}" ] && data=(-d "$5")
    out=$(curl -s -w "\n%{http_code}" -X "$3" "$A$4" -H "$K" -H "$J" "${data[@]}")
    code=$(echo "$out" | tail -1); body=$(echo "$out" | sed \$d)
    if [ "$code" = "$2" ]; then printf "  ok   %-14s %s %s\n" "$1" "$code" "$body"
    else printf "  FAIL %-14s expected %s got %s %s\n" "$1" "$2" "$code" "$body"; fails=$((fails+1)); fi
    LAST="$body"
  }
  check create-event 200 POST /api/v1/events/create "{\"venue\":\"O2 Arena\",\"onSaleAt\":\"2026-07-01T10:00:00Z\"}"
  EV=$(echo "$LAST" | python3 -c "import json,sys;print(json.load(sys.stdin)[\"event\"])")
  check add-seat     200 POST /api/v1/seats/add "{\"event\":\"$EV\",\"section\":\"A\",\"row\":\"12\",\"number\":7,\"tier\":\"STANDARD\"}"
  SEAT=$(echo "$LAST" | python3 -c "import json,sys;print(json.load(sys.stdin)[\"seat\"])")
  check set-price    200 POST /api/v1/pricing/set "{\"event\":\"$EV\",\"tier\":\"STANDARD\",\"amount\":\"49.50\",\"currency\":\"USD\"}"
  check open-event   200 POST "/api/v1/events/open/$EV" "{}"
  check quote        200 GET  "/api/v1/pricing/quote/$EV/STANDARD" ""
  check seat-status  200 GET  "/api/v1/availability/seats/$SEAT" ""
  check buy          200 POST /api/v1/booking/buy "{\"customer\":\"11111111-1111-1111-1111-111111111111\",\"event\":\"$EV\",\"seat\":\"$SEAT\",\"tier\":\"STANDARD\"}"
  BK=$(echo "$LAST" | python3 -c "import json,sys;print(json.load(sys.stdin)[\"booking\"])")
  echo "$LAST" | grep -q receipt || { echo "  FAIL buy returned no receipt"; fails=$((fails+1)); }
  check cancel       200 POST /api/v1/booking/cancel "{\"booking\":\"$BK\",\"customer\":\"11111111-1111-1111-1111-111111111111\"}"
  # Negative control: the same admin route must NOT succeed without the key.
  nc=$(curl -s -o /dev/null -w "%{http_code}" -X POST $A/api/v1/events/create -H "$J" -d "{\"venue\":\"x\",\"onSaleAt\":\"2026-07-01T10:00:00Z\"}")
  if [ "$nc" = "401" ]; then echo "  ok   negative-ctl  401 (no key refused)"
  else echo "  FAIL negative-ctl expected 401 got $nc"; fails=$((fails+1)); fi
  echo
  [ "$fails" = "0" ] && echo "ALL CHECKS PASSED" || { echo "$fails CHECK(S) FAILED"; exit 1; }'
}

attempt() { start_cluster && run_walkthrough; }

retried=0
if attempt; then
  rc=0
else
  retried=1
  echo
  echo "############################################################################"
  echo "# ATTEMPT 1 FAILED. Retrying ONCE."
  echo "#"
  echo "# This is very likely pragmaticalabs/pragmatica#1218 -- Forge's startup"
  echo "# blueprint deploy gives up on a hardcoded 10s timeout that does not scale"
  echo "# with cluster formation. It is a REAL product race, not a flaw in the"
  echo "# documented sequence, and this retry is PAPERING OVER IT."
  echo "#"
  echo "# If you are reading this line, the run you are about to trust needed two"
  echo "# attempts. Say so wherever you quote the result."
  echo "############################################################################"
  echo
  if attempt; then rc=0; else rc=1; fi
fi

echo
if [ "$rc" = "0" ]; then
  if [ "$retried" = "1" ]; then
    echo "VERIFIED (ON THE SECOND ATTEMPT): attempt 1 FAILED, attempt 2 passed."
    echo "  The failure is pragmaticalabs/pragmatica#1218, not the documented sequence."
    echo "  Do not quote this run as a clean pass -- it took two attempts."
  else
    echo "VERIFIED: the documented sequence runs end to end from a bare image."
    echo "  First attempt, no retry."
  fi
else
  echo "VERIFICATION FAILED (rc=$rc) -- BOTH attempts failed, so this is not #1218 alone."
fi
echo "Containers left running for inspection:  docker exec -it $APP bash"
echo "Tear down with:  docker rm -f $APP $PG && docker volume rm tkt-verify-m2"
exit $rc
