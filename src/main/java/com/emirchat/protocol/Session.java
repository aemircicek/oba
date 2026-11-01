package com.emirchat.protocol;

/**
 * Result of a successful handshake: an encrypted channel bound to an authenticated peer.
 *
 * @param peerFingerprint SHA-256 fingerprint of the peer's Ed25519 identity key
 */
public record Session(SecureChannel channel, String peerName, String peerFingerprint) {}
