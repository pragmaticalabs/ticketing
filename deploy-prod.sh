#!/bin/bash
# Deploy this slice to a PRODUCTION Aether cluster.
#
# Prerequisites:
#   1. A provisioned cluster. To create one, use `aether cluster bootstrap`
#      with a bootstrap TOML — see `aether cluster bootstrap --help` and the
#      bootstrap configuration reference in the Aether docs (docs/reference/).
#   2. The `aether` CLI on PATH, pointed at your cluster with either
#        -c <host:port>              (per invocation), or
#        AETHER_ENDPOINT=<host:port>    (environment).
#   3. A release version in the pom (not a SNAPSHOT).
set -e

VERSION=$(mvn -q -N help:evaluate -Dexpression=project.version -DforceStdout)

case "$VERSION" in
    *-SNAPSHOT)
        echo "ERROR: the project version is $VERSION, a SNAPSHOT." >&2
        echo "A production deploy needs a release version: the cluster's artifact repository is" >&2
        echo "write-once and takes no SNAPSHOT. Set one, commit it, and run this script again:" >&2
        echo "    mvn versions:set -DnewVersion=1.0.0" >&2
        exit 1
        ;;
esac

COORDS="org.pragmatica.example:ticketing:${VERSION}"

echo "WARNING: Deploying $COORDS to PRODUCTION"
echo ""
read -p "Are you sure? (yes/no): " confirm

if [ "$confirm" != "yes" ]; then
    echo "Deployment cancelled."
    exit 1
fi

echo ""
echo "Building and verifying..."
mvn clean verify

echo ""
echo "Pushing blueprint + slice artifacts to the cluster repository..."
aether artifacts push "$COORDS"

echo ""
echo "Deploying blueprint $COORDS..."
aether blueprints deploy "$COORDS" --wait

echo ""
echo "Deployed to production."
