# Changelog

## Unreleased

A security review of the whole app. The encryption (handshake, X3DH, Double Ratchet, sender keys,
files, calls) needed no change; the fixes are around it. Nothing in the protocol changes: every
version from 3.2 on keeps working with this one.

### Added
- **Creating an account shows the rules** for names and passwords and ticks them off while you
  type; both password fields can be shown as plain text.
- **First steps** on the empty screen: add a friend, compare keys with one, back up the account.
  Each is one click away and ticks itself off when done.
- The server verification screen says where the other copy of the fingerprint is.

### Accessibility
- **Screen readers**: buttons that show only an icon now have a name (their tooltip), text boxes
  without a label take their prompt, and the fields of the sign-in form are tied to their labels.
- **Keyboard**: chat rows, the community rail, the verification badge, pictures, reactions, poll
  options and links were reachable only with the mouse. Tab now stops on them, and Enter or Space
  activates them.
- **Contrast**: muted text in both themes, and the amber accent, green, red and warning colours
  of the light theme, now reach 4.5 to 1 (WCAG AA) against their backgrounds; a test keeps both
  themes and all five accents there. The light theme's amber is a little darker for it.
- The focus ring is shown when the keyboard moved the focus, not after a click, and no longer
  shifts what is around it.

### Changed
- `ConversationView` and `MainView` (2680 and 2343 lines) are split into fifteen parts by what
  they do. No change in behaviour.

### Security: server
- **Anyone could keep a person out of their account** by failing five logins on their username
  every 15 minutes. The device the account belongs to is no longer locked out by that.
- **No limit on new accounts or connections.** Now 10 registrations per address and hour, 64
  connections per address and 8192 in all. IPv6 addresses are counted by /64 network, where one
  customer otherwise had 2^64 separate limits.
- **Password hashing could take every processor**, so a flood of logins or registrations froze the
  server for everyone. At most half of the processors hash at once, and a taken username is
  refused before anything is hashed.
- **A computer that vanished stayed "online" for ever** (pulled cable, sleep) and kept its
  connection. TCP keep-alive now closes it after about two minutes.
- A client that floods requests is slowed down instead of keeping the database busy.
- The failed-login table could grow without limit; expired entries are now swept out.
- The data folder (password hashes, queued messages, the server key) is made owner-only at start.

### Fixed
- **A group message sent right after adding someone could stay unreadable for them for ever.**
  The news of the new member was still waiting to be handled, so the message went out without
  the sender key for them. Sending now waits until that news is handled. This was also the cause
  of a test that failed now and then.

### Security: app
- **On Linux the data folder could be readable by other users of the computer** (it was created
  with the system's default permissions when the log or lock file came first), exposing pinned
  contacts and who-wrote-when in the history. It is now created owner-only and closed again at
  every start. Private keys and message texts were not readable.
- **A picture could crash the app for everyone in a chat**: a small file can declare billions of
  pixels. Pictures are now measured before decoding; enormous ones are refused, large ones shrunk.
- macOS "remember me": a password with a line break in it could have run a second Keychain
  command; such a password is no longer stored.
- Links are handed to the browser only if they are `http(s)` web addresses.
- File names that Windows would misread (`photo.exe.`, `CON.txt`) are changed.
- Call offers are accepted from friends only, not from every member of a shared community.
- The temporary copy of the history made during a backup is created in a private folder.

## 3.4.1 (2026-11-04)

### Fixed
- **Oba 3.4.0 could not stay connected to a 3.3 server**: right after signing in it asked for
  the voice rooms, a request the older server does not know, so the server closed the
  connection and the app reconnected in a loop ("offline"). The app now remembers such a server
  (by its key) for six hours, sends it no voice packets and works without voice rooms, then asks
  again in case the server was updated. On such a server the voice room button is hidden.
- **A 3.4 server could disconnect 3.3 clients**: it told every member of a group who was in its
  voice room, a packet 3.3 clients do not know. It now tells only clients that asked for voice
  rooms or joined one.

## 3.4.0 (2026-11-04)

**Scorpion is now Oba**: a nomad camp of tents around a fire. New name and icon (two tents and
a campfire under a night sky); the flame replaces the scorpion's tail in the app. On first start
the Scorpion data folder, the password saved with "remember me" and the start-with-the-computer
entry move over by themselves; Windows installs Oba over Scorpion. The protocol keeps its
labels, so Oba talks to Scorpion 3.2 and 3.3 as before.

A redesign and a round of features. Messages use new kinds and actions (polls, thread replies,
pins, topics, votes, voice room keys): **update all clients together**; a 3.3 client shows those
as messages it could not read.

### Added
- **New look**: "desert night" (dark) and "sand" (light) themes with an amber accent, the
  campfire flame as the loading spinner and the connection light, sparks from the send button.
- **Avatars drawn from keys**: a mirrored pattern over two colours from each person's key; a new
  key gives a new face. Rings for verified friends and changed keys.
- **Formatting**: bold, italic, strikethrough, inline code, quotes, links and fenced code blocks
  with syntax colouring; a multi-line composer (Shift+Enter), ↑ to edit your last message.
- **Emoji**: `:name` suggestions (English and Turkish names) and a picker with categories and
  search; hover tools on every message (quick reactions, any reaction, reply, more); reactions pop in.
- **Voice messages** with a live meter while recording and a seekable waveform; sent as ordinary
  encrypted attachments (μ-law WAV).
- **Pictures and files**: drag and drop, paste files or screenshots, and a full-window viewer.
- **Date separators** and sender avatars in groups.
- **Communities**: a rail of community icons, channel topics, pinned messages, a member list by
  role, threads in a side panel, and polls with live results.
- **Commands**: `/poll`, `/roll`, `/flip`, `/remind`, `/shrug`, `/me`, `/topic`, `/help`, with
  suggestions while typing.
- **Voice rooms** in every channel and group: drop in and out, end-to-end encrypted with per-person
  keys that change whenever the room changes; speaking indicators, mute, deafen.
- **Quick switcher** (Ctrl+K / ⌘K) for chats, channels, friends and actions.
- **Appearance settings**: theme, five accents, text size, compact mode, reduce motion and
  synthesised notification sounds.
- Empty screens with a small desert scene.
- **The campfire**: voice rooms as a desert at night with three fires; sit anywhere, voices by
  distance and from their side (stereo), drag yourself or click the sand. Places travel inside
  the room's encryption and are never stored.
- **The map of oases**: a community as a desert map, each channel an oasis that shows unread
  messages, recent talk, a week of quiet or people in voice; pan, zoom, click to go in.
- **Your friend's sky** in the chat header, from the time zone they share (optional).
- **Welcome back** after a while away: your own sky, how long you were gone, mentions of you,
  polls without your vote, replies in your threads and the busiest chats; **@mentions** with
  suggestions.
- **Messages turn to sand** when deleted or when they disappear.

### Fixed
- Leaving a direct (no-server) conversation could be reported as a lost connection when the peer
  hung up first, right after the goodbye.

### Changed
- Conversations load 80 messages at a time (older ones when scrolling up) and decrypt pictures only
  when they come into view; the view follows new messages unless you have scrolled up.
- Windows packages use AppCDS (`-XX:+AutoCreateSharedArchive`) for faster starts.

### Security
- Incoming text keeps line breaks but still has every other control character and bidi override
  replaced; every field of a received poll is sanitized the same way.
- Voice rooms: membership is swept every two seconds, so a removed member is cut off even if they
  never speak; clients drop a removed member's keys and replace their own at once.
- Voice messages are never sent without the user: switching chats discards a recording, and at
  the length limit the recording waits for Send. Oversized voice files are not decoded.

## 3.3.0 (not released on its own; included in 3.4.0)

### Added
- **Communities with channels**: a "Communities" tab; each community has a "# general" channel and
  up to 50 more; owner and admins; invite codes (valid 1 hour to 30 days, optionally limited in
  uses, revocable, stored only as a hash) let people join without being friends. Channel names are
  end-to-end encrypted.
- **Voice calls** between friends: a call button in each chat, a call bar with answer, decline,
  mute and hang up, ringing and missed-call notifications. Every call gets its own keys; audio is
  end-to-end encrypted and relayed by the server without being stored. The macOS app asks for
  microphone access.
- **A default server**: the app connects to the project's server out of the box, with its key
  built in (no fingerprint question on first connection). Other servers stay under "Advanced".

### Fixed
- A group message that arrived before its sender's key (for example from a friend's new device)
  was lost as "could not be decrypted"; it now waits for the key.

## 3.2.0 (2026-11-02)

### Added
- **Scorpion desktop app**: new name and icon; installers with Java included (Windows installer
  and portable zip, macOS dmg, Linux deb); data in each OS's usual folder (the old
  `~/.securechat` is moved there).
- **English and Turkish** interface.
- **Default server** with an optional built-in fingerprint; other servers under "Advanced".
- **Remember me** with the OS's protected storage (DPAPI, Keychain, Secret Service).
- **Runs in the tray**, starts with the computer if wanted, **reconnects by itself**, one window
  per data folder, `.scbackup` files open the restore dialog.
- Update banner, About dialog, third-party notices, local log file, first-start tour.
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
- 31 new tests (170 in total).

### Fixed
- A message delivered again after its acknowledgement was lost (the app closed right after
  receiving it) is now acknowledged quietly instead of showing a "could not be decrypted"
  warning. Handled envelope ids are remembered across restarts.

### Changed
- New messages use a format with message ids; 3.1 messages are still read. Update all clients
  together.

## 3.1.0 (unreleased, included in 3.2.0)

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
