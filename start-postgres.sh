#!/bin/bash
# Start PostgreSQL for local development
set -e

CONTAINER_NAME="ticketing-postgres"
VOLUME_NAME="ticketing-pgdata"
PG_PORT="${PG_PORT:-5432}"
PG_PASSWORD="${PG_PASSWORD:-postgres}"

# Auto-detect container runtime
if command -v docker >/dev/null 2>&1; then
    RUNTIME="docker"
elif command -v podman >/dev/null 2>&1; then
    RUNTIME="podman"
else
    echo "ERROR: Neither docker nor podman found."
    exit 1
fi

# Check if already running
if $RUNTIME ps --format '{{.Names}}' 2>/dev/null | grep -q "^${CONTAINER_NAME}$"; then
    echo "PostgreSQL is already running (container: $CONTAINER_NAME)"
# Check if stopped container exists
elif $RUNTIME ps -a --format '{{.Names}}' 2>/dev/null | grep -q "^${CONTAINER_NAME}$"; then
    echo "Starting existing container..."
    $RUNTIME start "$CONTAINER_NAME"
    echo "Waiting for PostgreSQL..."
    for i in $(seq 1 30); do
        if $RUNTIME exec "$CONTAINER_NAME" pg_isready -U postgres >/dev/null 2>&1; then
            break
        fi
        sleep 1
    done
else
    echo "Creating PostgreSQL container..."
    $RUNTIME run -d \
        --name "$CONTAINER_NAME" \
        -v "$VOLUME_NAME:/var/lib/postgresql/data" \
        -e POSTGRES_PASSWORD="$PG_PASSWORD" \
        -e POSTGRES_DB=forge \
        -p "$PG_PORT:5432" \
        postgres:17

    echo "Waiting for PostgreSQL..."
    for i in $(seq 1 30); do
        if $RUNTIME exec "$CONTAINER_NAME" pg_isready -U postgres >/dev/null 2>&1; then
            break
        fi
        sleep 1
    done
fi

# Always apply schema (idempotent — uses CREATE IF NOT EXISTS)
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
if [ -f "$SCRIPT_DIR/schema/init.sql" ]; then
    echo "Applying schema/init.sql..."
    $RUNTIME exec -i "$CONTAINER_NAME" psql -U postgres -d forge < "$SCRIPT_DIR/schema/init.sql"
fi

echo ""
echo "PostgreSQL running on port $PG_PORT"
echo "  Connection: postgresql://postgres:$PG_PASSWORD@localhost:$PG_PORT/forge"
