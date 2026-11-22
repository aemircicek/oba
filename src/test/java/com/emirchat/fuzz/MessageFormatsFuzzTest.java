package com.emirchat.fuzz;

import com.code_intelligence.jazzer.api.FuzzedDataProvider;
import com.code_intelligence.jazzer.junit.FuzzTest;
import com.emirchat.chat.Capsule;
import com.emirchat.chat.ChatMessage;
import com.emirchat.chat.FileMessage;
import com.emirchat.chat.Poll;
import java.net.ProtocolException;

/** The formats inside end-to-end encryption: a friend's app writes them, so anything may arrive. */
class MessageFormatsFuzzTest {
    @FuzzTest(maxDuration = "1m")
    void chatMessage(byte[] data) {
        try {
            ChatMessage.decode(data);
        } catch (ProtocolException ignored) {
            // refused
        }
    }

    @FuzzTest(maxDuration = "1m")
    void fileMessage(byte[] data) {
        try {
            FileMessage f = FileMessage.decode(data);
            // a name that came in is shown and used to save: it must be safe already
            if (f.name().contains("/") || f.name().contains("\\") || f.name().contains("\0")) {
                throw new AssertionError("unsafe file name accepted: " + f.name());
            }
        } catch (ProtocolException ignored) {
            // refused
        }
    }

    @FuzzTest(maxDuration = "1m")
    void pollAndCapsule(FuzzedDataProvider in) {
        byte[] data = in.consumeBytes(4096);
        String stored = in.consumeRemainingAsString();
        try {
            Poll.decode(data);
        } catch (ProtocolException ignored) {
            // refused
        }
        try {
            Capsule.decode(data);
        } catch (ProtocolException ignored) {
            // refused
        }
        Poll.fromStored(stored);
        Capsule.fromStored(stored);
    }
}
