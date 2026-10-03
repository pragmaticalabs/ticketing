#!/bin/bash
# Deploy this slice to a test Aether cluster.
#
# Requires the `aether` CLI on PATH, pointed at your test cluster with either
#   -c <host:port>            (per invocation), or
#   AETHER_ENDPOINT=<host:port>  (environment).
#
# The cluster's artifact repository is write-once and takes no SNAPSHOT, so every push is stamped with a
# unique release version: <base>-<short git sha> from a clean git checkout, otherwise <base>-<UTC timestamp>
# (override with DEPLOY_STAMP). The pom is restored afterwards. The generated pom pins
# project.build.outputTimestamp, so the same commit builds byte-identical jars and re-running this script on it
# is an idempotent re-push. If the repository still refuses a version (409, different jar bytes), the sources
# changed: commit them, or set DEPLOY_STAMP.
set -e

BASE_VERSION=$(mvn -q -N help:evaluate -Dexpression=project.version -DforceStdout)
BASE_VERSION="${BASE_VERSION%-SNAPSHOT}"

if [ -n "${DEPLOY_STAMP:-}" ]; then
    STAMP="$DEPLOY_STAMP"
elif git rev-parse --is-inside-work-tree >/dev/null 2>&1 && [ -z "$(git status --porcelain)" ]; then
    STAMP=$(git rev-parse --short=8 HEAD)
else
    STAMP=$(date -u +%Y%m%d%H%M%S)
fi

VERSION="${BASE_VERSION}-${STAMP}"
COORDS="org.pragmatica.example:ticketing:${VERSION}"

POM_BACKUP=$(mktemp)
cp pom.xml "$POM_BACKUP"
trap 'cp "$POM_BACKUP" pom.xml; rm -f "$POM_BACKUP"' EXIT

echo "Stamping release version $VERSION..."
mvn -q versions:set -DnewVersion="$VERSION" -DgenerateBackupPoms=false

echo "Building and installing to local Maven repository..."
mvn clean install -DskipTests

echo ""
echo "Pushing blueprint + slice artifacts to the cluster repository..."
aether artifacts push "$COORDS"

echo ""
echo "Deploying blueprint $COORDS..."
aether blueprints deploy "$COORDS" --wait

echo ""
echo "Deployed $COORDS to test cluster."
