# Running a secure-chat server on a VPS

This guide puts the server on a small Linux VPS (1 vCPU, 1 GB RAM is plenty for friends and
family) so you can chat from anywhere. The steps assume Ubuntu 24.04; other distributions work
the same way with their own package manager.

The server never sees message content, file contents, file names or group names. It does see
who talks to whom and when (see *Limitations* in the README), so run it on a machine you control.

## 1. Prepare the machine

```sh
# as root, once
adduser chat && usermod -aG sudo chat          # a normal user for logging in
# copy your SSH key, then in /etc/ssh/sshd_config: PasswordAuthentication no, PermitRootLogin no
systemctl restart ssh

apt update && apt install -y unattended-upgrades ufw
dpkg-reconfigure -plow unattended-upgrades    # security updates install themselves

ufw default deny incoming
ufw allow OpenSSH
ufw allow 5056/tcp                            # secure-chat
ufw allow 5056/udp                            # calls: direct paths, and the UDP relay when there is none
ufw enable
```

No TLS certificate or domain name is needed: every connection already runs secure-chat's own
authenticated, encrypted handshake, and clients pin the server's key.

## 2. The quickest way: the ready-made image (3.6 and later)

```sh
curl -fsSL https://get.docker.com | sh        # Docker, if it is not installed yet
git clone https://github.com/aemircicek/oba.git secure-chat && cd secure-chat
sudo deploy/oba-server.sh install             # pulls ghcr.io/aemircicek/oba-server, starts it, prints the fingerprint
```

Later, one command updates it: `sudo deploy/oba-server.sh update`. It backs up the data volume
first (into `/var/backups/oba-server`, the ten newest are kept), pulls the new image, restarts,
and **checks that the server key is still the same**. Other commands: `backup`, `restore <file>`,
`fingerprint`, `status`. The image is built by the release workflow for amd64 and arm64 and
carries a signed build-provenance attestation (`gh attestation verify oci://ghcr.io/aemircicek/oba-server:3.6.0 -R aemircicek/oba`).

A server set up earlier with `docker compose up -d --build` (section 2a) moves to the image with
`sudo deploy/oba-server.sh update`: the names match, so it keeps its volume and key.

## 2a. …or build the image yourself with Docker

```sh
curl -fsSL https://get.docker.com | sh        # or install docker.io + docker-compose-v2 from apt
git clone https://github.com/aemircicek/oba.git secure-chat && cd secure-chat   # the folder name keeps docker compose stable
sudo docker compose up -d --build
sudo docker compose logs secure-chat          # shows the server fingerprint
```

The container runs as an unprivileged user with a read-only file system, no capabilities and
limited memory; only the `securechat-data` volume is writable.

## 2b. …or with systemd

Download `secure-chat-linux-x64.jar` from the latest release (check its SHA-256 against
`SHA256SUMS`), install Java 21 (`apt install -y openjdk-21-jre-headless`), then:

```sh
sudo mkdir -p /opt/secure-chat
sudo cp secure-chat-linux-x64.jar /opt/secure-chat/secure-chat.jar
sudo cp deploy/secure-chat.service /etc/systemd/system/
sudo systemctl daemon-reload && sudo systemctl enable --now secure-chat
journalctl -u secure-chat                      # shows the server fingerprint
```

The unit runs under a throw-away user that can only write `/var/lib/secure-chat`.

## 3. Connect and verify the server

In the app, enter `<your VPS address>:5056`. On first connection the app shows the server's
fingerprint: **compare it with the one in the logs** before accepting. From then on the key is
pinned, and the app refuses a server presenting a different key. Give the fingerprint to your
friends over another channel (in person, or a call) so they can check it too.

## Metrics

Started with `--metrics [host:]port` (the ready-made set-up does this, on 127.0.0.1:9105), the
server serves counters in the Prometheus text format at `/metrics`: open connections, accounts
online, logins, relayed envelopes and call packets, voice rooms, packets over the limits, clients
cut off for falling behind, memory. Only totals: no user names, groups or addresses. Look at them
with `curl -s 127.0.0.1:9105/metrics` or `sudo deploy/oba-server.sh status`; keep the port off the
internet (Prometheus on the same machine, or an SSH tunnel).

## Updating

```sh
sudo deploy/oba-server.sh update                                     # ready-made image (backs up, checks the key)
cd secure-chat && git pull && sudo docker compose up -d --build     # Docker, built here
# systemd: replace /opt/secure-chat/secure-chat.jar, then: sudo systemctl restart secure-chat
```

The database is upgraded automatically. Update the server and the apps together when the
changelog says the protocol changed.

## Backing up the server

The data volume holds the server's identity key, the accounts database (password hashes,
friend lists, queued encrypted messages) and encrypted attachments.

```sh
sudo docker run --rm -v secure-chat_securechat-data:/data -v "$PWD":/backup debian \
    tar czf /backup/secure-chat-server.tgz -C /data .
# systemd: sudo tar czf secure-chat-server.tgz -C /var/lib/secure-chat .
```

Keep this archive private: the server key inside lets someone impersonate your server. If the
server key is ever lost, every client will see a "different key" warning; tell your friends why
before they accept the new one.
