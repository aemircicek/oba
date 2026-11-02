# Changelog

## Unreleased (3.2.0)

### Added
- **Replies, reactions, edits and "delete for everyone"**: messages carry an id inside the
  ciphertext; reactions, edits and deletions refer to it, and edits and deletions are accepted
  only from the author. Right-click a message in the desktop app.
- **Read receipts and typing indicators** for direct chats, both optional (Settings). Typing
  notices are only sent to friends who are online, so none are queued on the server.
- **Search** across all chats, decrypted locally.
- **Desktop notifications** through the system tray, with optional message preview.
- **Safety numbers and QR codes** for contact verification: 60 digits to read out, or check a
  picture of the friend's QR code.
- **Encrypted backup and restore** of keys, sessions and history (desktop and
  `secure-chat backup` / `restore`), protected by a separate passphrase.
- **Server deployment**: Dockerfile, docker-compose.yml, a hardened systemd unit and
  [docs/deploy.md](docs/deploy.md).
- **Release packages** with Java included (Windows zip, macOS dmg, Linux deb), Sigstore build
  provenance for every file, and `SHA256SUMS`.
- 21 new tests (160 in total).

### Changed
- New messages use a format with message ids; 3.1 messages are still read. Update all clients
  together.

## Unreleased (3.1.0)

### Added
- **Encrypted file and image sharing** in direct messages and groups, up to 25 MB per file. Each
  file is encrypted with a fresh AES-256-GCM key on the sender's device; the server stores only
  the ciphertext and lets only the recipient or current group members download it. The key and
  the SHA-256 digest of the blob travel inside the end-to-end encrypted message, so the receiver
  detects a swapped or modified blob. Images are previewed in the chat.
- Server: attachment storage with per-user quota (250 MB), at most 3 uploads in progress, and
  deletion after 30 days (unfinished uploads after an hour). Database schema version 5.
- **Disappearing messages**: a per-chat timer (5 minutes to 1 week in the app, up to 4 weeks in
  the protocol) that every participant receives end-to-end encrypted. Messages and files sent
  with a timer are deleted from local history that long after they arrive, together with cached
  attachment copies.
- **Encrypted group names**: the admin encrypts the name with a name key that members receive
  end-to-end; the server stores only ciphertext. A new key is made whenever someone leaves.
  Admins can rename groups. Groups named in plain text by 3.0 are migrated automatically.
- Terminal client: `file`, `gfile`, `save`, `timer` and `grename` commands.
- 42 new tests (139 in total).

### Changed
- The client–server protocol changed (attachments, group info): update the server and all
  clients to 3.1 together. Server data is migrated automatically (schema version 6).

## 3.0.0 (2026-11-02)

secure-chat becomes an end-to-end encrypted messenger with a server.

### Added
- **Server** (`secure-chat server`): accounts in SQLite, one virtual thread per connection,
  SIGMA-I encrypted transport with the server key pinned by clients on first use.
- **Accounts**: registration and login with PBKDF2-HMAC-SHA256 (600,000 iterations), rate limits
  per username and per IP address, identical errors and timing for unknown usernames.
- **Friends**: requests, accept/decline, removal, online status, and a warning to friends when
  someone logs in from a new device.
- **End-to-end encrypted direct messages**: X3DH with signed and one-time prekeys, the Double
  Ratchet, an offline queue, delivery receipts, pinned contact keys, and messages held back after
  a key change until the user verifies the new key.
- **End-to-end encrypted groups**: Sender Keys with signed messages, key rotation when anyone
  leaves or is removed (also when the removal happened while a member was offline), and no access
  to earlier messages for new members.
- **Desktop app for accounts**: login and registration, server verification, chats and friends
  tabs, unread counts, ✓✓ delivery marks, contact verification, key-change warnings, group
  creation and management, encrypted local history.
- **Terminal client** (`register`, `login`, `client`) with an interactive shell.
- 97 tests, including attack scenarios for the ratchet, groups and the server.

### Changed
- The desktop app opens the login screen by default. The version 2 peer-to-peer mode is still
  available from the login screen (*Direct connection without an account*) and as `host` /
  `connect` on the command line.

## 2.1.0 (unreleased, included in 3.0.0)

### Added
- JavaFX desktop app for the peer-to-peer mode: host/join, colour fingerprint verification,
  key-change warning, light and dark themes.

## 2.0.0 (2026-11-01)

A security rewrite of the original socket chat.

### Added
- SIGMA-I style handshake: ephemeral X25519 (forward secrecy) and Ed25519 identity signatures
  (mutual authentication).
- HKDF-SHA256 key derivation with separate keys per direction.
- AES-256-GCM records with counter nonces (tampering, replay and reordering protection).
- SSH-style trust on first use for identity keys.
- Terminal escape sanitising, size limits, handshake timeout, `/quit`.
- Maven build, JUnit tests, GitHub Actions CI with a Semgrep scan.

### Removed
- The hard-coded AES key in ECB mode and spoofable user names of version 1.

### Changed
- The default port is 5055 (5000 is taken by AirPlay on macOS).
