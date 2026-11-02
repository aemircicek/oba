# secure-chat server image.
#   docker compose up -d        (see docker-compose.yml and docs/deploy.md)

# ---- build ---------------------------------------------------------------
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /src
COPY pom.xml .
RUN mvn -B -q dependency:go-offline
COPY src ./src
# Tests run in CI; the image build only packages.
RUN mvn -B -q -DskipTests package

# ---- run -----------------------------------------------------------------
FROM eclipse-temurin:21-jre
RUN groupadd --system securechat && useradd --system --gid securechat --home-dir /data --shell /usr/sbin/nologin securechat \
    && mkdir -p /data && chown securechat:securechat /data
COPY --from=build /src/target/secure-chat.jar /app/secure-chat.jar
USER securechat
VOLUME /data
EXPOSE 5056
# A TCP connect is enough: the server answers every connection with its handshake.
HEALTHCHECK --interval=60s --timeout=5s --start-period=20s --retries=3 \
    CMD bash -c 'exec 3<>/dev/tcp/127.0.0.1/5056' || exit 1
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-Dorg.sqlite.tmpdir=/data", "-jar", "/app/secure-chat.jar", \
            "server", "--data", "/data", "--bind", "0.0.0.0", "--port", "5056"]
