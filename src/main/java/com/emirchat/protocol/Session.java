package com.emirchat.protocol;

/**
 * Result of a successful handshake: an encrypted channel bound to an authenticated peer.
 *
 * @param peerFingerprint SHA-256 fingerprint of the peer's Ed25519 identity key
 * @param peerIdentityKey the peer's Ed25519 identity public key (X.509 encoding)
 */
public record Session(SecureChannel channel, String peerName, String peerFingerprint, byte[] peerIdentityKey) {
    public Session {
        peerIdentityKey = peerIdentityKey.clone();
    }

    @Override
    public byte[] peerIdentityKey() {
        return peerIdentityKey.clone();
    }
}
