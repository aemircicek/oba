# Security report (3.6)

What was checked for 3.6, how, and what remains. Oba is a learning project and has not had an
outside audit; this report is the author's own.

## Fuzzing

Jazzer (coverage-guided fuzzing for the JVM) ran every decoder that reads bytes another party
controls. Each fuzz test also runs as a normal test with its saved inputs (`mvn test`); to fuzz:

```sh
JAZZER_FUZZ=1 mvn test -Dtest='PacketFuzzTest#decode'
```

| Target | What it reads | Inputs | Found |
|---|---|---:|---|
| `PacketFuzzTest#decode` | every client/server packet; a decoded packet must encode again | 12.2 M | nothing |
| `MessageFormatsFuzzTest#chatMessage` | texts inside end-to-end encryption | 6.6 M | nothing |
| `MessageFormatsFuzzTest#fileMessage` | file descriptions; names must come out safe | 6.8 M | nothing |
| `MessageFormatsFuzzTest#pollAndCapsule` | polls and amber messages, stored forms too | 2.8 M | nothing |
| `VideoPacketsFuzzTest#assemble` | video chunks; reassembly must stay bounded | 5.6 M | nothing |
| `DirectPathFuzzTest#candidates` | a peer's call addresses; none it must not point us at | 8.9 M | nothing |
| `UdpRelayFuzzTest#datagrams` | anything on the server's UDP port; no answer larger than its request, none to an unproven address | 14.0 M | nothing |
| `TextFormatsFuzzTest#markdownAndSearch` | Markdown, links, mentions, search words | 0.3 M | nothing |
| `TextFormatsFuzzTest#slashCommandsAndSettings` | slash commands, quiet hours, time zones | 2.0 M | nothing |
| `TextFormatsFuzzTest#imagesAndVoice` | pictures (ImageIO behind limits) and voice notes | 0.5 M | nothing |
| `CryptoInputsFuzzTest#directMessages` | Double Ratchet and X3DH messages from anyone | 0.07 M | nothing |
| `CryptoInputsFuzzTest#groupKeys` | sender key distributions and group messages | see below | nothing |

The first fuzzing run did find a test bug (empty input decoded to `null`) and showed that
malformed ciphertexts are refused with `SecurityException`, which the app reports as "could not
be decrypted"; the targets accept exactly the documented refusals and nothing else.

## Reviews of the 3.6 changes

Every change was reviewed for security as it was written. What the reviews found, all fixed
before the release:

- **Server install script**: a predictable temporary file under `/tmp` in a script run as root
  (symlink attack) → no temporary file.
- **Room video**: native scalers were never freed, so someone switching a camera on and off
  could exhaust memory on the others' computers → freed with their tile, late frames ignored,
  at most twelve tiles.
- **UDP relay**: a removed friend kept their relay ticket → tickets end when two people stop
  being friends, and after six hours in any case.
- **Device ids**: with the identity key shared between computers, a removed computer could claim
  another's device id → every device proves itself with a secret of its own; the first device's
  number is never handed out again.
- **Link checks**: the "wrong password" limit counted only existing usernames, which told which
  names exist → counted for every well-formed name, as for logins.
- **Link codes**: a code made from one computer's key alone could be matched by a server trying a
  million keys → commitment, then both keys (see the README).
- **Sealed sender**: the key sealed to came from the server unchecked, so a server could hand out
  its own and read who sends → only keys signed by the friend's identity key; and a removed friend
  kept the access key → a new one when a friend leaves.

## Remaining weaknesses

See *Limitations* in the README. The most important:

- Linked computers share the identity key, and removing one does not take it back.
- The server sees which address a sealed message comes from, and who sends to groups.
- The first computer's identity key is not encrypted at rest (it is used before signing in).
- No formal model of the handshake, X3DH or linking yet.
