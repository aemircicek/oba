<p align="center"><img src="docs/oba-icon.png" width="96" alt="Oba icon: two tents around a campfire under a night sky"></p>

# Oba

[![CI](https://github.com/aemircicek/secure-chat/actions/workflows/ci.yml/badge.svg)](https://github.com/aemircicek/secure-chat/actions/workflows/ci.yml)

**Oba** is an end-to-end encrypted messenger for Windows, macOS and Linux (plus a terminal
client): accounts, friends, direct messages, groups, communities with channels, threads and
polls, voice calls, drop-in voice rooms, voice messages and files, in English and Turkish. It is
written in Java using only the JDK's standard cryptography (no third-party crypto libraries), and
the server relays and stores messages it cannot read.

*Oba* is a nomad camp: a few tents pitched together, everyone in their own, and everyone around
the fire in the evening. That is the app: your direct chats are your tent, communities and their
campfires the shared ground. Until 3.4 the app was called *Scorpion*, and this repository
*secure-chat* before that; data, saved passwords and autostart settings move over by themselves.

<p align="center">
  <img src="docs/screenshots/campfire-dark.png" width="420" alt="The campfire: a voice room you walk around in, voices by distance and side">
  <img src="docs/screenshots/map-dark.png" width="420" alt="A community as a map of oases">
  <img src="docs/screenshots/sky-dark.png" width="420" alt="A friend's night sky in the chat header (it is 4 a.m. in New York)">
  <img src="docs/screenshots/catchup-dark.png" width="420" alt="Welcome back: what happened while you were away">
</p>

It started as a small socket chat that "encrypted" messages with a hard-coded AES key in ECB
mode. Version 2 replaced that with an authenticated key exchange (SIGMA-I style, X25519 +
Ed25519) between two peers. **Version 3** turns it into a messenger with a server, using the
same ideas as Signal: X3DH to start sessions with offline users, the Double Ratchet for every
direct message, and Sender Keys for groups.

## Quick start

From the [latest release](https://github.com/aemircicek/secure-chat/releases/latest), download
Oba with Java included:

| | File | |
|---|---|---|
| Windows | `Oba-<version>-windows-x64.exe` | installer: no admin rights, Start menu and desktop shortcuts |
| Windows | `Oba-<version>-windows-x64-portable.zip` | unzip and run `Oba.exe` |
| macOS | `Oba-<version>-macos-arm64.dmg` | |
| Linux | `Oba-<version>-linux-x64.deb` | `sudo apt install ./Oba-*.deb` |

There is also a runnable jar per platform (Java 21+ required), or build it yourself with `mvn verify`.

Check a download before running it, with the checksum file or GitHub's signed build provenance:

```sh
sha256sum -c SHA256SUMS --ignore-missing                       # on Windows: Get-FileHash <file>
gh attestation verify Oba-3.4.0-windows-x64.exe -R aemircicek/secure-chat
```

The packages are not code-signed (that needs paid certificates), so Windows SmartScreen and macOS
Gatekeeper warn on first start.

```sh
java -jar secure-chat.jar server                    # run a server on port 5056 (or see docs/deploy.md)
java -jar secure-chat.jar                           # desktop app: register or log in
```

Register a username and password. Builds with a default server (set in
`src/main/resources/default-server.properties`, optionally with its pinned fingerprint) connect to
it directly; otherwise, or under *Advanced*, enter a server address such as `localhost:5056`. To
try it alone on one machine, open two app windows with different data folders:

```sh
java -jar secure-chat.jar gui --home ./alice
java -jar secure-chat.jar gui --home ./bob
```

### Desktop app

- **Accounts**: register and log in with a username and password; *Remember me* keeps the password
  in the operating system's protected storage (Windows DPAPI, macOS Keychain, Linux Secret
  Service) and signs in by itself.
- **Always there**: closing the window keeps Oba in the tray so messages and notifications
  still arrive; it can start with the computer, reconnects by itself after a network change or a
  server restart, and keeps one window (a second start brings it forward).
- **English and Turkish**, following the system or chosen on the login screen and in Settings.
- **Server verification**: on first connection the app shows the server's key fingerprint; after
  that it is pinned and a different key is refused.
- **Friends**: send, accept and decline requests; see who is online.
- **Direct messages**: end-to-end encrypted, delivered later if your friend is offline, with
  ✓ sent / ✓✓ delivered / blue ✓✓ read marks and unread counts.
- **Replies, reactions, edits and deletes**: right-click a message to reply to it, react, edit
  your own text, or delete it for everyone. Only the author's edits and deletions are accepted.
- **Typing and read receipts**: friends who are online see when you type, and blue ticks when
  you have read their messages; both can be turned off in Settings.
- **Groups**: create a group from your friends, add and remove members, rename it, leave; the
  owner role passes to the longest-standing member if the owner leaves. Group names are end-to-end
  encrypted: the server stores only ciphertext.
- **Communities**: a rail of community icons at the far left; channels with a topic, pinned
  messages, a member list by role, and threads in a side panel. Polls with live results
  (`/poll Question | A | B`), dice (`/roll 2d6`), `/flip`, `/remind 10m tea` (a reminder on this
  computer), `/me`, `/shrug`, `/topic`, `/help`.
- **Voice rooms**: every channel and group has a room to drop into without ringing; faces light up
  while people talk, with mute, deafen and leave. End-to-end encrypted like everything else.
- **Contact verification**: compare a 60-digit safety number on a call, or check a picture of
  your friend's QR code; both sides compute the same code from both keys.
- **Key-change warning**: if a friend's key changes (new device, or an attack), messages to them
  are held back until you verify the new key.
- **Files and images**: send any file up to 25 MB to a friend or a group (📎 next to the message
  box). Each file is encrypted with its own key before it leaves your computer; images are
  previewed in the chat.
- **Disappearing messages**: set a timer for a chat (5 minutes to 1 week) with the ⏱ button.
  Everyone in the chat gets the same setting, and new messages and files are deleted from every
  device's history that long after they arrive.
- **Encrypted history**: messages are stored on disk encrypted under a key derived from your password.
- **Search**: find messages and file names across all chats (decrypted locally, never sent anywhere).
- **Notifications**: a desktop notification when a message arrives while the window is in the
  background; showing the text in it is optional.
- **Encrypted backup**: Settings → *Back up this account* saves keys, sessions and history in one
  file encrypted with a passphrase of your choice; *Restore from a backup* on the login screen
  moves the account to another computer.
- **Updates and help**: a banner when a new version is out (can be turned off), a short tour on
  first start, an About dialog and a local log file for troubleshooting.
- **The Oba look**: a warm "desert night" dark theme and a "sand" light theme with an amber
  accent; the campfire from the icon is the loading spinner and the connection light (it dies
  down to a red ember while reconnecting), and the send button throws sparks.
- **Faces from keys**: every avatar is drawn from the person's key (a mirrored pattern over two
  colours), so a new key means a new face; verified friends get an accent ring, a changed key a red
  dashed one.
- **Formatting**: `**bold**`, `*italic*`, `~~strike~~`, `` `code` ``, quotes, links and fenced
  code blocks with colours for keywords, strings, numbers and comments. Shift+Enter starts a new
  line; ↑ in an empty box edits your last message.
- **Emoji**: type `:` and a name (English or Turkish: `:kalp`, `:akrep`) for suggestions, or open
  the picker; hover a message for quick reactions, reply and more.
- **Voice messages**: the microphone button records (with a live level meter); messages play with
  a waveform you can click to seek. They are ordinary encrypted attachments.
- **Pictures and files**: drag files onto a chat, paste a screenshot or copied files, and browse
  the pictures of a chat in a full-window viewer.
- **The campfire**: a voice room is a desert at night with three fires, seen from above. Sit
  anywhere: voices are as loud as people are near and come from their side (stereo), so a room
  can split into little circles. Drag yourself or click the sand; the rings around you show how
  far you hear.
- **The map of oases**: a community is shown as a desert where every channel is an oasis. Rings
  on the water mean unread messages, smoke means people talked in the last hour, sand covers a
  channel quiet for a week, and a campfire burns where people are in voice.
- **Your friend's sky**: the chat header shows the sky where your friend is right now (dawn,
  noon, sunset, stars and the moon) with their local time, if they share their time zone.
- **Welcome back**: after a while away, a screen with your own sky, how long you were gone, and
  what is waiting: mentions of you, polls without your vote, replies in your threads, the busiest
  chats. **@mentions** are highlighted and suggested while typing.
- **Messages turn to sand**: deleted and disappearing messages blow away as grains of sand.
- **Quick switcher**: Ctrl+K (⌘K) jumps to any chat, channel, friend or action.
- **Appearance**: light, dark or system theme, five accent colours, text size, compact mode,
  reduce motion, and a choice of notification sounds.

<p align="center">
  <img src="docs/screenshots/account-files-dark.png" width="420" alt="An encrypted image and document in a chat">
  <img src="docs/screenshots/account-rich-light.png" width="420" alt="A reply, an edit, a reaction, read ticks and typing">
  <img src="docs/screenshots/voice-message-light.png" width="420" alt="A voice message with its waveform">
  <img src="docs/screenshots/thread-dark.png" width="420" alt="A thread in the side panel">
  <img src="docs/screenshots/members-dark.png" width="420" alt="Members by role">
  <img src="docs/screenshots/palette-dark.png" width="420" alt="The quick switcher">
  <img src="docs/screenshots/emoji-dark.png" width="420" alt="The emoji picker">
  <img src="docs/screenshots/empty-light.png" width="420" alt="An empty screen in the light theme">
</p>

<p align="center">
  <img src="docs/screenshots/account-login-light.png" width="280" alt="Login">
  <img src="docs/screenshots/account-verify-dark.png" width="280" alt="Contact verification">
  <img src="docs/screenshots/account-key-changed-dark.png" width="280" alt="Key change warning">
</p>

### Terminal

```sh
java -jar secure-chat.jar server [--port 5056] [--bind 0.0.0.0] [--data ~/.securechat-server]
java -jar secure-chat.jar register --server localhost:5056 --username emir
java -jar secure-chat.jar client   --server localhost:5056 --username emir
java -jar secure-chat.jar backup   --out emir.scbackup [--home ~/.securechat]
java -jar secure-chat.jar restore  --in emir.scbackup  [--home ~/.securechat]
```

`client` opens an interactive shell (`msg`, `file`, `save`, `timer`, `friends`, `add`, `accept`, `group`, `gmsg`,
`verify`, `trust`, ...; type `help` for the full list). The desktop app and the terminal client
use the same protocol and can talk to each other.

### Direct mode (no server)

The version 2 peer-to-peer mode is still available: one person hosts, the other connects
directly, and nothing is stored anywhere. In the desktop app, choose *Direct connection without
an account* on the login screen; in the terminal:

```sh
java -jar secure-chat.jar host --name emir                  # wait for a peer on port 5055
java -jar secure-chat.jar connect 192.168.1.3 --name efe    # connect to a host
java -jar secure-chat.jar fingerprint                       # show your identity key
```

## Hosting a server

[docs/deploy.md](docs/deploy.md) walks through running the server on a small VPS with Docker (or
systemd): firewall, verifying the server fingerprint, updates and server backups.

## Security design

### Threat model

The attacker controls the network: they can read, drop, modify, replay, reorder and inject
packets. **The server is not trusted with message content**: a malicious or compromised server
must not be able to read or forge messages. User devices are trusted.

| Goal | How it is achieved |
|---|---|
| Confidentiality against the server | Messages are encrypted on the sender's device; the server stores and relays opaque bytes |
| Confidentiality and integrity on the wire | Every connection is a SIGMA-I channel (X25519 + Ed25519, AES-256-GCM); the server's key is pinned on first use |
| Asynchronous session setup | X3DH with signed prekeys (rotated weekly) and one-time prekeys (deleted after use) |
| Forward secrecy | Double Ratchet: every message has its own key, which is deleted after use |
| Post-compromise security | Double Ratchet DH steps: a stolen session state stops working once both sides send again |
| No fake keys from the server | Every published key is signed by the user's Ed25519 identity; friends' keys are pinned (TOFU) and can be verified by fingerprint |
| Key changes | A changed key holds outgoing messages until the user verifies it |
| Group confidentiality | Sender Keys: each member's chain key is sent to other members over the pairwise encrypted channel |
| Group authenticity | Each group message is signed with the sender's group signing key; members cannot forge each other |
| Group names | Encrypted with a name key that the admin sends to members end-to-end, bound to the group id; a new key after anyone leaves |
| Removed members | Everyone rotates to a fresh sender key that departed members never receive; new members cannot read earlier messages |
| Replay | Ratchet and chain keys only move forward; decryption is transactional, so a forged or replayed message cannot corrupt state |
| Password storage | PBKDF2-HMAC-SHA256, 600,000 iterations, 16-byte random salt per user |
| Online guessing | 5 failed logins per username and 20 per IP address in 15 minutes |
| Username enumeration on login | Same error and same hashing cost for unknown usernames |
| Stolen disk | Message history encrypted with AES-256-GCM under a key from the account password (PBKDF2, 600k) |
| File confidentiality | Each file is encrypted with a fresh AES-256-GCM key; the server stores only the ciphertext, and the key travels in an end-to-end encrypted message |
| File integrity | The receiver checks the SHA-256 digest of the blob (sent end-to-end) before decrypting; GCM authenticates the content |
| File access | Only the uploader and the named audience (the friend, or current group members) can download; unknown and forbidden ids look the same |
| Edits and deletions | Messages carry a random id inside the ciphertext; an edit or "delete for everyone" is applied only if it comes from the message's author |
| Contact verification | A safety number (60 digits or a QR code) derived from both identity keys; both sides compute the same value, so a substituted key shows up as a mismatch |
| Backups | Zip of the key directory encrypted with AES-256-GCM under PBKDF2-HMAC-SHA256 (600k) of a separate passphrase; restore refuses paths outside the target folder |
| Disappearing messages | The timer travels inside the encrypted message; expired rows are deleted with SQLite `secure_delete` and cached files are removed |
| Resource exhaustion | Frame and message size limits, offline queue and prekey caps, prekey-fetch rate limits, 25 MB per file, 250 MB of stored files per user |

### Direct messages: X3DH and the Double Ratchet

```mermaid
sequenceDiagram
    participant A as Alice
    participant S as Server
    participant B as Bob
    B->>S: publish identity keys, signed prekey, one-time prekeys (all signed)
    A->>S: fetch Bob's prekey bundle
    S->>A: bundle (Bob's signatures checked by Alice)
    Note over A: X3DH: 4 DH operations → shared secret<br/>start Double Ratchet
    A->>S: prekey message (ephemeral key, prekey ids, first ciphertext)
    S->>B: deliver now, or when Bob comes online
    Note over B: X3DH with the same keys → same secret<br/>delete the one-time prekey
    B->>S: ✓✓ delivery receipt (relayed to Alice)
```

This follows Signal's X3DH (revision 1) and Double Ratchet (revision 4) specifications, with one
change taken from Matrix's Olm: the Ed25519 signing identity and the X25519 agreement identity are
separate keys, and the X25519 key is signed by the Ed25519 one. Every signature is checked before
any DH is computed. The associated data of every message is both parties' identity keys, so a
ciphertext cannot be moved into a session between different people.

### Groups: Sender Keys

Each member owns a sender key per group: a hash chain (each message key is used once and the
chain only moves forward) and an Ed25519 signing key. A message is encrypted once, signed, and
fanned out by the server. When anyone leaves or is removed, every remaining member rotates to a
new sender key. Removal is also checked at send time, so a member removed while you were offline
does not receive your next message.

### Group names

The group's admin creates a random 32-byte name key, encrypts the name with AES-256-GCM (the group
id and key version are authenticated as associated data, so the server cannot move a name to
another group) and stores only that ciphertext on the server. The key travels to each member over
the pairwise Double Ratchet channel and is accepted only from the current admin. When anyone
leaves or is removed, the admin makes a new key version and re-encrypts the name, so former
members cannot read later names. Groups named in plain text by version 3.0 are moved to an
encrypted name the next time their admin's app connects.

### Communities and channels

A community is a group that is also its "# general" channel, with up to 50 more channels under it.
Everyone in the community is in every channel; the server keeps channel membership in step, and
each channel is an ordinary encrypted group (its own sender keys and its own encrypted name). The
owner appoints admins; the owner and admins add channels, rename them, remove members and make
invite codes. If the owner leaves, the longest-standing member takes over.

Invite codes are 26 random symbols (130 bits) typed in groups of five, valid from an hour to 30
days, optionally limited in uses and revocable. The server stores only their SHA-256, so a copy
of its database does not contain working codes. People who join with a code do not have to be
anyone's friend. Channel name keys come from the owner or an admin, and a newer key version
always wins, so two admins renaming at once cannot lock members out.

### Voice calls

```mermaid
sequenceDiagram
    participant A as Alice
    participant S as Server
    participant B as Bob
    Note over A: callId = 16 random bytes<br/>a = fresh X25519 key
    A->>S: Double Ratchet message {OFFER, callId, a.public}
    S->>B: deliver
    Note over B: b = fresh X25519 key
    B->>S: Double Ratchet message {ANSWER, callId, b.public}
    S->>A: deliver
    Note over A,B: keys = HKDF(salt = callId, X25519(a, b))<br/>one AES-256-GCM key per direction
    A->>S: callId ‖ seq ‖ AES-GCM(audio), 50 a second
    S->>B: relayed live (not stored)
```

Call offers and answers are ordinary end-to-end encrypted messages, so they are authenticated by
the same keys as chat messages: the server cannot place or answer a call in someone's name, and
an offer from a friend whose key changed waits until the key is verified. Each call has its own
X25519 keys, forgotten when it ends. Audio is 8 kHz G.711 μ-law (64 kbit/s) in 20 ms packets with
a sequence number; old, repeated and modified packets are dropped. The server relays audio only
between friends, at most 100 packets a second, and drops packets for a device that falls behind
instead of queueing them. Calls are one-to-one; an offer that arrives more than a minute late is
shown as a missed call.

### Files

```mermaid
sequenceDiagram
    participant A as Alice
    participant S as Server
    participant B as Bob
    Note over A: key = random 32 bytes<br/>blob = nonce ‖ AES-256-GCM(key, file)<br/>digest = SHA-256(blob)
    A->>S: UploadStart(size, audience = Bob)
    S->>A: random attachment id
    A->>S: blob in 32 KiB chunks, then UploadFinish
    A->>S: Double Ratchet message {id, key, digest, name, size}
    S->>B: deliver the message
    B->>S: fetch blob by id (Bob is the audience)
    Note over B: check SHA-256(blob) = digest<br/>then decrypt with key
```

The server never sees the key, the file name or the content. Blobs are deleted after 30 days
(unfinished uploads after an hour). File names from other people are reduced to a plain name
(no folders, control characters or right-to-left tricks) before they are shown or saved.

### Transport

Clients and the server talk over the same SIGMA-I channel as the direct mode: an ephemeral X25519
exchange, Ed25519 signatures over the transcript, HKDF-SHA256 keys per direction, and AES-256-GCM
records with implicit counters, so replayed, reordered or modified records close the connection.
Users then authenticate with their password inside the channel. Low-order X25519 points are rejected.

### Code map

| Package | Responsibility |
|---|---|
| `com.emirchat.crypto` | `Hkdf` (RFC 5869), `Identity` (Ed25519 keys, fingerprints), `X25519` |
| `com.emirchat.protocol` | `FrameIO` (bounded framing), `Wire` (strict encoding), `Handshake` (SIGMA-I), `SecureChannel` |
| `com.emirchat.e2e` | `X3dh`, `DoubleRatchet`, `GroupKeys` (Sender Keys), `GroupNames`, `Attachments` (file encryption), `Messenger`, `DeviceKeys`, `PrekeyBundle` |
| `com.emirchat.server` | `ChatServer`, accounts, friends, groups and communities, offline queue, call audio relay, `VoiceRooms`, `AttachmentService`, `PasswordHasher`, `RateLimiter`, SQLite |
| `com.emirchat.call` | `Calls` (call signalling and state), `CallCrypto` (per-call keys, audio packets), `VoiceChannels` and `VoiceCrypto` (voice rooms), `VoiceNotes`, `VoiceRecorder`, `VoicePlayer` (voice messages), `Mulaw` (G.711), `SoundDevice` (microphone and speaker) |
| `com.emirchat.client` | `ChatClient`, `ServerConnection`, encrypted `History`, `Backup` |
| `com.emirchat.api` | `Packet` (client–server messages), error codes |
| `com.emirchat.trust` | `KnownPeers` (TOFU store for servers and contacts), `SafetyNumber` |
| `com.emirchat.ui` | JavaFX desktop app: screens, theme and `Appearance`, avatars and fingerprint art, `Markdown`, `Emoji`, `SlashCommands`, `Palette` (quick switcher), `Flame` and `Illustrations`, `Sounds`, QR codes, notifications |
| `com.emirchat.chat` | `ChatMessage`, `FileMessage` (file description and safe names), `Poll`, terminal sanitising |
| `com.emirchat.net` | Direct mode: connect/host |
| `com.emirchat` | `Main`, `SecureChat`, `AccountCli` and `BackupCli` command lines, `ClientShell` |

### Tests

`mvn verify` runs 234 tests, including RFC 5869 test vectors and attack scenarios:

- **Transport**: tampered, replayed, reordered and reflected records; an impersonated client or
  server presenting a different key; tampered handshake messages.
- **Direct messages**: out-of-order delivery, replays, tampering that must not break the session,
  authenticated headers, a prekey bundle for a different identity, forged signed prekeys,
  one-time prekeys used only once, simultaneous first messages, sessions surviving a restart.
- **Groups**: removed members cannot read after rotation (even if the removal happened while a
  member was offline), new members cannot read old messages, members cannot forge each other,
  redistributing an old chain state cannot enable replays; group names are unreadable to the
  server, bound to their group, re-keyed after a removal, and old name keys cannot be replayed.
- **Communities**: strangers join with an invite code (typed in any case, with spaces), single-use,
  expired and revoked codes fail, only the owner appoints admins, admins cannot remove the owner,
  removal reaches every channel, the longest member takes over, the server stores no channel
  names; a group message that arrives before its sender's key waits for it instead of being lost.
- **Calls**: audio both ways through the server, mute sends silence, a reflected, replayed, older or
  modified packet is dropped, another call id gives other keys, busy and declined calls, and the
  server relays audio only between friends.
- **Voice rooms**: members hear each other through the server and outsiders cannot join; packets
  replayed, rewound, altered, from another room or passed off as someone else's are dropped; old
  keys expire after rotations; muting sends nothing; a removed listener who never speaks is cut off.
- **Polls, pins, topics, threads**: delivered whole in chats and groups, one vote per person, poll
  limits, and a text message can never pose as a poll.
- **The campfire**: places reach the others and distance decides who is heard (far away is
  silent, close again is heard); seats never overlap; volume and left-right panning by distance.
- **Desktop logic**: the welcome-back digest (mentions but not e-mail addresses, polls without
  your vote, replies in your threads), the sky's colours, sun and moon through the day, time zone
  validation, the Markdown parser (nesting, `snake_case`, unclosed markers, code
  colouring), emoji names, slash commands, the quick switcher's matching, avatar patterns, voice
  message files and waveforms, and line breaks surviving the sanitizer while control characters
  do not.
- **Files**: a blob swapped or modified on the server is refused, a wrong key fails, only the
  recipient or current group members can download, uploads must arrive in order and in full,
  expiry and per-user quotas, hostile file names (`../../etc/passwd`, right-to-left overrides).
- **Messages**: ids shared by both sides, replies, reactions, edits and deletes accepted only from
  the author, read receipts that only touch our own messages and are never undone by a late
  "sent", search that skips deleted messages.
- **Verification and backups**: safety numbers are symmetric and change when a key is swapped, QR
  codes survive a round trip through a picture; backups detect a wrong passphrase and tampering,
  never delete existing data, reject zip-slip paths, and a restored account keeps chatting with no
  key change.
- **Server**: registration and login rules, identical errors for wrong password and unknown user,
  brute-force limits, friend and group permissions, the offline queue in order, delivery
  receipts, a message delivered again after a lost acknowledgement, a tampering server being caught,
  and the server storing only ciphertext.

CI also runs a Semgrep security scan. `src/test/java/com/emirchat/ui/AccountPreview.java` drives
the real desktop app against an in-process server and scripted users and saves a screenshot of
every step; the images above come from it.

### Voice rooms

Every group and community channel has a voice room. The server keeps who is in which room in
memory, relays each packet only to the others in the same room (rate limited) and tells the
group's members who is there; it checks membership on joining and sweeps the rooms every two
seconds, so someone removed from the group is taken out even if they stay silent.

```
key      = 32 random bytes and a random 32-bit id, new each time anyone joins or leaves
handover = an action inside the group's Sender Keys encryption (the server cannot read it)
packet   = u32 keyId ‖ u64 seq ‖ AES-256-GCM(key, nonce = keyId ‖ seq,
                                  aad = "scorpion voice v1" ‖ groupId ‖ sender, μ-law audio)
(the labels keep the app's former name: they are part of the protocol)
```

Each participant sends with their own key and receives with everyone else's, mixing the voices
locally. The campfire's places travel in the same encrypted packets (the first byte of the
plaintext tells audio from a place), so the server neither stores where people sit nor can tell
movement from speech; each client sets every voice's volume by distance and pans it left or right. Because keys change whenever the room changes, a person who left cannot decrypt what is
said after; clients also replace their key as soon as the member list shows a removal. Packets
are bound to the room and the sender (one cannot pass off another's audio), and a receiver takes
each sequence number once and never an older one.

### Time zones

A friend's sky needs their time zone, which is sent as an end-to-end encrypted action in a
direct chat, only while they are online (never queued on the server), and only as a region id
such as `Europe/Istanbul`; anything else is refused. Sharing can be turned off in Settings.

### Polls, pins, topics and threads

These travel inside the same end-to-end encryption as messages. A poll is a message kind; a vote
is an action naming one answer of that poll (one vote per person, the last one counts). In a
community channel only the owner and admins may pin messages or set the topic; receivers check
this against the member list. A thread reply is a message kind that names its first message and
is shown in the thread instead of the channel.

## Limitations

Honest notes on what this project does **not** do:

- **Not audited.** It is a learning and portfolio project. For real-world secrecy use Signal.
- **The server sees metadata**: who is friends with whom, who messages whom and when, group
  member lists, online status, and the approximate size of each file. It cannot see message
  content, group names, file names or file content. For calls it sees who calls whom, when and
  for how long, but not what is said.
- **The server decides who is in a community.** Channel names and messages are encrypted for the
  members the server lists, so a malicious server could add a member (who would then appear in
  the member list). Check the member list of sensitive communities.
- **Calls go through the server** (no direct peer-to-peer connection), so they add a little delay;
  voice quality is that of a telephone (8 kHz). There are no video calls yet.
- **Voice rooms**: the server sees who is in which room and when; at most 12 people per room. Room
  keys are handed to every member of the channel (anyone who can read the channel may also
  listen), and nobody else can join.
- **A time zone says roughly where you are.** Friends you chat with see it unless you turn
  sharing off. In a voice room, everyone in it sees where you sit at the fire.
- **The welcome-back summary and the map** are computed from the history on this computer, so
  they only know what this computer has received.
- **Topics and pins are not stored on the server**, so people who join a channel later do not see
  earlier ones. Dice and coins are thrown on the sender's computer; reminders stay on this
  computer and are lost if the app is closed.
- **The server can hide or delay messages**, and can claim a user has a new device. The app then
  shows a key-change warning; users must compare fingerprints to detect a fake key.
- **TOFU trusts first contact.** Until friends compare fingerprints, a malicious server could
  present a fake key on the very first exchange.
- **One device per account.** Logging in on a new device replaces the old key.
- **Keys at rest are not encrypted**: identity keys and ratchet sessions are protected only by
  owner-only file permissions (message history is encrypted).
- **Disappearing messages rely on the other side's app.** They protect against a device being
  lost or seized later, not against a recipient who takes a screenshot or runs a modified client.
  The terminal client keeps no history at all.
- **Typing notices and read receipts** tell friends when you are active (turn them off in Settings);
  their timing, like any message, is visible to the server even though their content is encrypted.
- **Notifications** may show message text in the operating system's notification centre unless
  you turn that off.
- **A backup is as strong as its passphrase**, and restoring it moves the account: keep using
  only one computer afterwards.
- **Releases are not code-signed** (no Authenticode or Apple notarization). Check downloads with
  `SHA256SUMS` and `gh attestation verify`.
- **Registration reveals whether a username is taken.**
- **Best-effort key wiping** only: the JVM may keep copies of key material in memory.

## Roadmap

- Passphrase-encrypted identity keys and session state
- Fuzzing the decoders (Jazzer) and a written security report
- Sealed sender and message-size padding (less metadata for the server)
- A formal model of the handshake and X3DH (ProVerif or Tamarin)
- Multiple devices per account; video calls and screen sharing; a better voice codec (Opus)

## Security

See [SECURITY.md](SECURITY.md) for how to report a vulnerability.

## License

[MIT](LICENSE) © 2026 Emir Çiçek. Third-party components and their licenses: [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).
