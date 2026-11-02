package com.emirchat.e2e;

/**
 * What a user publishes so others can start an encrypted session with them while they are
 * offline. The server stores and hands these out but cannot forge them: every key is signed by
 * the user's Ed25519 identity.
 *
 * @param oneTimePrekeyId -1 (and an empty key) when the server has run out of one-time prekeys
 */
public record PrekeyBundle(byte[] identityKey, byte[] dhIdentity, byte[] dhIdentitySignature,
                           int signedPrekeyId, byte[] signedPrekey, byte[] signedPrekeySignature,
                           int oneTimePrekeyId, byte[] oneTimePrekey) {
    public boolean hasOneTimePrekey() {
        return oneTimePrekey != null && oneTimePrekey.length > 0;
    }
}
