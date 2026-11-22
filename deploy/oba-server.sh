#!/usr/bin/env bash
# Installs, updates and backs up an Oba server running in Docker, with the ready-made image from
# GitHub's container registry. Run as root (or with sudo) on the server.
#
#   oba-server.sh install [version]   Docker check, firewall hints, start, print the fingerprint
#   oba-server.sh update  [version]   back up, pull the new image, restart, check the key did not change
#   oba-server.sh backup              the data volume (server key included) into $BACKUPS
#   oba-server.sh restore <file.tgz>  put a backup back (stops the server while it does)
#   oba-server.sh fingerprint         the server's key fingerprint, as apps show it
#   oba-server.sh status              container state, version and a few counters
#
# Without a version the newest release is used. Set OBA_BUILD=1 to build the image from this
# checkout instead of pulling it (for a fork, or if the registry cannot be reached).
set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
COMPOSE_FILE="$HERE/docker-compose.yml"
BACKUPS="${BACKUPS:-/var/backups/oba-server}"
IMAGE="ghcr.io/aemircicek/oba-server"
VOLUME="secure-chat_securechat-data"
CONTAINER="secure-chat"

say() { printf '\033[1m%s\033[0m\n' "$*"; }
die() { printf 'error: %s\n' "$*" >&2; exit 1; }

need_docker() {
  command -v docker > /dev/null || die "Docker is not installed. Install it first: curl -fsSL https://get.docker.com | sh"
  docker compose version > /dev/null 2>&1 || die "the docker compose plugin is missing (apt install docker-compose-v2)"
}

latest_version() {
  local tag
  tag=$(curl -fsSL https://api.github.com/repos/aemircicek/oba/releases/latest | sed -n 's/.*"tag_name": *"v\{0,1\}\([^"]*\)".*/\1/p' | head -n 1)
  [ -n "$tag" ] || die "could not find the latest release; give a version, for example: $0 update 3.6.0"
  echo "$tag"
}

compose() {
  OBA_VERSION="$VERSION" docker compose -f "$COMPOSE_FILE" "$@"
}

fetch_image() {
  if [ "${OBA_BUILD:-0}" = "1" ]; then
    say "Building the image from $(cd "$HERE/.." && pwd)"
    docker build -t "$IMAGE:$VERSION" "$HERE/.."
  else
    say "Pulling $IMAGE:$VERSION"
    docker pull "$IMAGE:$VERSION" || die "could not pull the image; try again with OBA_BUILD=1 to build it here"
  fi
}

fingerprint() {
  # 3.6 and later print it on request; older servers only in their start-up log (two lines)
  docker exec "$CONTAINER" java -jar /app/secure-chat.jar server-fingerprint --data /data 2> /dev/null | tr -d '\r' \
    || docker logs "$CONTAINER" 2>&1 | awk '/Server fingerprint/{n=2; out=""; next} n>0{out=out $0 "\n"; n--} END{printf "%s", out}'
}

wait_running() {
  for _ in $(seq 1 60); do
    if docker exec "$CONTAINER" bash -c 'exec 3<>/dev/tcp/127.0.0.1/5056' 2> /dev/null; then return 0; fi
    sleep 1
  done
  docker logs --tail 50 "$CONTAINER" >&2 || true
  die "the server did not start within a minute (logs above)"
}

backup() {
  docker volume inspect "$VOLUME" > /dev/null 2>&1 || die "no data volume $VOLUME yet"
  mkdir -p "$BACKUPS" && chmod 700 "$BACKUPS"
  local file
  file="$BACKUPS/oba-server-$(date -u +%Y%m%d-%H%M%S).tgz"
  # The database is copied while the server runs; SQLite's WAL keeps a consistent state on disk.
  docker run --rm -v "$VOLUME":/data:ro -v "$BACKUPS":/backup debian:stable-slim \
    tar czf "/backup/$(basename "$file")" -C /data .
  chmod 600 "$file"
  # keep the ten newest
  ls -1t "$BACKUPS"/oba-server-*.tgz 2> /dev/null | tail -n +11 | xargs -r rm -f
  echo "$file"
}

firewall_hints() {
  if command -v ufw > /dev/null && ufw status 2> /dev/null | grep -q "Status: active"; then
    ufw status | grep -q "5056/tcp" || echo "  firewall: run  ufw allow 5056/tcp"
    ufw status | grep -q "5056/udp" || echo "  firewall: run  ufw allow 5056/udp   (direct calls and the call relay)"
  fi
}

cmd="${1:-}"
case "$cmd" in
  install)
    need_docker
    VERSION="${2:-$(latest_version)}"
    fetch_image
    compose up -d
    wait_running
    say "Oba server $VERSION is running."
    echo "Fingerprint (compare it in the app on first connection, and give it to your friends):"
    fingerprint
    firewall_hints ;;
  update)
    need_docker
    VERSION="${2:-$(latest_version)}"
    before=""
    if docker inspect "$CONTAINER" > /dev/null 2>&1; then
      before=$(fingerprint || true)
      say "Backing up first"
      backup
    fi
    fetch_image
    compose up -d
    wait_running
    after=$(fingerprint)
    if [ -n "$before" ] && [ "$before" != "$after" ]; then
      die "the server key changed during the update! Restore the backup above before anyone connects."
    fi
    say "Updated to $VERSION; the server key is unchanged."
    firewall_hints ;;
  backup)
    need_docker
    say "Backed up to $(backup)"
    echo "Keep it private: the server key inside lets someone pose as your server." ;;
  restore)
    need_docker
    file="${2:-}"
    [ -f "$file" ] || die "give the backup file: $0 restore $BACKUPS/oba-server-....tgz"
    VERSION="${OBA_VERSION:-$(docker inspect -f '{{index .Config.Labels "org.opencontainers.image.version"}}' "$CONTAINER" 2> /dev/null || echo latest)}"
    [ -n "$VERSION" ] || VERSION=latest
    compose stop || true
    docker run --rm -v "$VOLUME":/data -v "$(cd "$(dirname "$file")" && pwd)":/backup:ro debian:stable-slim \
      bash -c "find /data -mindepth 1 -delete && tar xzf /backup/$(basename "$file") -C /data"
    compose up -d
    wait_running
    say "Restored. Fingerprint:"
    fingerprint ;;
  fingerprint)
    fingerprint ;;
  status)
    docker ps --filter "name=^${CONTAINER}$" --format 'container: {{.Status}}  image: {{.Image}}'
    if page=$(curl -fsS --max-time 3 http://127.0.0.1:9105/metrics 2> /dev/null); then
      printf '%s\n' "$page" | grep -E '^oba_(build_info|uptime_seconds|users_online|connections_open|voice_participants|udp_relay_sessions)[ {]' || true
    else
      echo "metrics: not reachable on 127.0.0.1:9105 (servers before 3.6 do not have them)"
    fi ;;
  *)
    sed -n '2,13p' "$0" | sed 's/^# \{0,1\}//'
    exit 2 ;;
esac
