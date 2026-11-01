package com.emirchat.trust;

/** Decides whether an authenticated peer (name + identity key fingerprint) may be talked to. */
@FunctionalInterface
public interface TrustPolicy {
    void check(String peerName, String fingerprint) throws PeerRejectedException;
}
