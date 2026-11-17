package com.emirchat.call;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.emirchat.video.VideoFrame;
import com.emirchat.video.VideoSource;
import java.security.KeyPair;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class CallVideoTest {
    /** A camera that records whether anyone opened it. */
    private static final class Camera implements VideoSource {
        volatile boolean opened;

        @Override public boolean screen() { return false; }

        @Override public int fps() { return 15; }

        @Override public void start(Consumer<VideoFrame> frames) { opened = true; }

        @Override public void stop() { opened = false; }
    }

    @Test
    void aCallThatHasEndedNeverOpensTheCamera() {
        KeyPair a = CallCrypto.newCallKey();
        KeyPair b = CallCrypto.newCallKey();
        CallVideo video = new CallVideo(CallCrypto.deriveVideo(new byte[CallCrypto.CALL_ID_LENGTH], a,
                com.emirchat.crypto.X25519.encode(b.getPublic()), true), sealed -> {});
        video.close(); // the other side hung up while the camera button was being pressed
        Camera camera = new Camera();
        assertThrows(IllegalStateException.class, () -> video.start(camera));
        assertFalse(camera.opened, "the camera was never opened");
        assertFalse(video.sending());
    }

    @Test
    void endingTheCallClosesTheCamera() {
        KeyPair a = CallCrypto.newCallKey();
        KeyPair b = CallCrypto.newCallKey();
        CallVideo video = new CallVideo(CallCrypto.deriveVideo(new byte[CallCrypto.CALL_ID_LENGTH], a,
                com.emirchat.crypto.X25519.encode(b.getPublic()), true), sealed -> {});
        Camera camera = new Camera();
        video.start(camera);
        video.close();
        assertFalse(camera.opened, "closed with the call");
        assertFalse(video.sending());
    }
}
