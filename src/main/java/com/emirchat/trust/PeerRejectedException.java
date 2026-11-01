package com.emirchat.trust;

/** Thrown when the authenticated peer identity is not (or no longer) trusted. */
public final class PeerRejectedException extends Exception {
    private static final long serialVersionUID = 1L;

    public PeerRejectedException(String message) {
        super(message);
    }
}
