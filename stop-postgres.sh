#!/bin/bash
# Stop PostgreSQL development container
set -e

CONTAINER_NAME="ticketing-postgres"
VOLUME_NAME="ticketing-pgdata"

# Auto-detect container runtime
if command -v docker >/dev/null 2>&1; then
    RUNTIME="docker"
elif command -v podman >/dev/null 2>&1; then
    RUNTIME="podman"
else
    echo "ERROR: Neither docker nor podman found."
    exit 1
fi

$RUNTIME stop "$CONTAINER_NAME" 2>/dev/null && echo "Stopped $CONTAINER_NAME" || echo "Container not running"
$RUNTIME rm "$CONTAINER_NAME" 2>/dev/null && echo "Removed $CONTAINER_NAME" || true

if [ "$1" = "--purge" ]; then
    $RUNTIME volume rm "$VOLUME_NAME" 2>/dev/null && echo "Removed volume $VOLUME_NAME" || true
fi
