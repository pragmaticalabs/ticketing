#!/bin/bash
# Start local Aether Forge cluster
set -e
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "Building ticketing slice..."
mvn clean install -DskipTests -q

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

# rc1 forge resolves the blueprint by ARTIFACT COORDINATE from the local repo (the `mvn install`
# above publishes it as ...:blueprint), NOT by file path. The coordinate tracks the pom's
# groupId:artifactId:version. (Note: the rc1 self-contained forge launcher symlink can resolve its
# bundled JRE incorrectly; if so, run via "$HOME/.aether/aether-forge-1.0.0-rc1/bin/aether-forge".)
exec $FORGE_CMD --config "$SCRIPT_DIR/forge.toml" \
     --blueprint org.pragmatica.example:ticketing:1.0.0-SNAPSHOT:blueprint
