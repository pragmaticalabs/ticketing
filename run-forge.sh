#!/bin/bash
# Start local Aether Forge cluster
set -e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "Building ticketing slice..."
mvn clean install -DskipTests -q

# Forge persists cluster state under ~/.aether/forge-data. Starting over a previous run's state
# makes the deployment reconciler work from stale instance counts; the scale-up/scale-down
# corrections it issues then race, the losing side is classified "Deterministic failure ... will NOT
# retry", and the cluster wedges with some routes permanently 404. Rebuilding the slice does not
# clear it, which is why that symptom used to survive a full rebuild. Set FORGE_KEEP_DATA=1 only if
# you are deliberately testing restart recovery.
if [ -z "${FORGE_KEEP_DATA:-}" ]; then
    echo "Clearing ~/.aether/forge-data (set FORGE_KEEP_DATA=1 to keep it)..."
    rm -rf "$HOME/.aether/forge-data"
fi

# Find aether-forge
if command -v aether-forge >/dev/null 2>&1; then
    FORGE_CMD="aether-forge"
elif [ -f "$HOME/.aether/bin/aether-forge" ]; then
    FORGE_CMD="$HOME/.aether/bin/aether-forge"
else
    echo "ERROR: aether-forge not found."
    echo "Install: curl -fsSL https://raw.githubusercontent.com/pragmaticalabs/pragmatica/main/install.sh | sh"
    exit 1
fi

echo ""
echo "Starting Aether Forge..."
echo "  Dashboard:  http://localhost:8888"
echo "  App HTTP:   http://localhost:8070"
echo "  Management: http://localhost:5150"
echo ""
echo "Test: curl -s http://localhost:8070/api/v1/events -d '{\"venue\":\"O2\",\"onSaleAt\":\"2026-07-01T10:00:00Z\"}'"
echo ""

# Forge resolves the blueprint by ARTIFACT COORDINATE from the local repo (the `mvn install`
# above publishes it as ...:blueprint), NOT by file path. The coordinate tracks the pom's
# groupId:artifactId:version. (If a self-contained forge launcher symlink resolves its bundled JRE
# incorrectly, run via "$HOME/.aether/aether-forge-1.0.0-rc3/bin/aether-forge" -- rc3 is the version
# this repo targets and the one ./install.sh --version 1.0.0-rc3 lays down.)
exec $FORGE_CMD --config "$SCRIPT_DIR/forge.toml" \
     --blueprint org.pragmatica.example:ticketing:1.0.0-SNAPSHOT:blueprint
