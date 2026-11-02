package com.emirchat.trust;

import com.emirchat.crypto.Identity;
import java.nio.file.Path;

/** A pinned peer presented a different identity key: possible man-in-the-middle attack. */
public final class KeyChangedException extends PeerRejectedException {
    private static final long serialVersionUID = 1L;

    private final String peerName;
    private final String expectedFingerprint;
    private final String receivedFingerprint;
    private final String knownPeersFile;

    public KeyChangedException(String peerName, String expected, String received, Path knownPeersFile) {
        super(String.join("\n",
                "@@@ WARNING: IDENTITY KEY FOR '" + peerName + "' HAS CHANGED! @@@",
                "Someone may be impersonating this peer (man-in-the-middle attack),",
                "or the peer generated a new identity.",
                "Expected: " + Identity.pretty(expected).replace('\n', ' '),
                "Received: " + Identity.pretty(received).replace('\n', ' '),
                "Verify the new fingerprint out-of-band, then remove the old line from " + knownPeersFile));
        this.peerName = peerName;
        this.expectedFingerprint = expected;
        this.receivedFingerprint = received;
        this.knownPeersFile = knownPeersFile.toString();
    }

    public String peerName() { return peerName; }

    public String expectedFingerprint() { return expectedFingerprint; }

    public String receivedFingerprint() { return receivedFingerprint; }

    public String knownPeersFile() { return knownPeersFile; }
}
