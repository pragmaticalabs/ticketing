# Bare-Linux reproduction image for the ticketing quickstart.
# Deliberately starts from a plain Ubuntu base: every tool the README lists as a
# prerequisite is installed here explicitly, so anything the README omits fails
# hard instead of being supplied by a developer machine.
FROM ubuntu:24.04

ARG JDK_MAJOR=25
ARG MAVEN_VERSION=3.9.9

ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
        ca-certificates curl git tar gzip unzip python3 jq postgresql-client procps \
    && rm -rf /var/lib/apt/lists/*

# JDK 25 (README prerequisite: "JDK 25+", pom.xml maven.compiler.release=25)
RUN set -eux; \
    arch="$(dpkg --print-architecture)"; \
    case "$arch" in arm64) ADOPT_ARCH=aarch64 ;; amd64) ADOPT_ARCH=x64 ;; *) echo "unsupported $arch"; exit 1 ;; esac; \
    curl -fsSL -o /tmp/jdk.tar.gz \
      "https://api.adoptium.net/v3/binary/latest/${JDK_MAJOR}/ga/linux/${ADOPT_ARCH}/jdk/hotspot/normal/eclipse"; \
    mkdir -p /opt/jdk; \
    tar -xzf /tmp/jdk.tar.gz -C /opt/jdk --strip-components=1; \
    rm /tmp/jdk.tar.gz; \
    /opt/jdk/bin/java -version
ENV JAVA_HOME=/opt/jdk
ENV PATH=/opt/jdk/bin:$PATH

# Maven (README prerequisite: "3.9+")
RUN set -eux; \
    curl -fsSL -o /tmp/mvn.tar.gz \
      "https://archive.apache.org/dist/maven/maven-3/${MAVEN_VERSION}/binaries/apache-maven-${MAVEN_VERSION}-bin.tar.gz"; \
    mkdir -p /opt/maven; \
    tar -xzf /tmp/mvn.tar.gz -C /opt/maven --strip-components=1; \
    rm /tmp/mvn.tar.gz; \
    /opt/maven/bin/mvn -version
ENV PATH=/opt/maven/bin:$PATH

WORKDIR /work
CMD ["/bin/bash"]
