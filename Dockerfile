# secure-chat server image.
#   docker compose up -d        (see docker-compose.yml and docs/deploy.md)

# ---- build ---------------------------------------------------------------
# The jar is the same on every processor, so it is built once on the builder's own platform.
FROM --platform=$BUILDPLATFORM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
# Tests run in CI; the image build only packages.
RUN mvn -B -q -DskipTests package

# ---- run -----------------------------------------------------------------
FROM eclipse-temurin:21-jre
ARG OBA_VERSION=dev
LABEL org.opencontainers.image.title="Oba server" \
      org.opencontainers.image.description="End-to-end encrypted messenger server (it cannot read messages)" \
      org.opencontainers.image.source="https://github.com/aemircicek/oba" \
      org.opencontainers.image.licenses="MIT" \
      org.opencontainers.image.version="$OBA_VERSION"
RUN groupadd --system securechat && useradd --system --gid securechat --home-dir /data --shell /usr/sbin/nologin securechat \
    && mkdir -p /data && chown securechat:securechat /data
COPY --from=build /src/target/secure-chat.jar /app/secure-chat.jar
USER securechat
VOLUME /data
EXPOSE 5056 5056/udp 9105
# A TCP connect is enough: the server answers every connection with its handshake.
HEALTHCHECK --interval=60s --timeout=5s --start-period=20s --retries=3 \
    CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/5056' || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-Dorg.sqlite.tmpdir=/data", "-jar", "/app/secure-chat.jar", \
            "server", "--data", "/data", "--bind", "0.0.0.0", "--port", "5056"]
