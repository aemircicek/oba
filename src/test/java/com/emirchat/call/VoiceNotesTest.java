package com.emirchat.call;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class VoiceNotesTest {
    private static byte[] tone(int millis, int amplitude) {
        int samples = millis * 8;
        byte[] pcm = new byte[samples * 2];
        for (int i = 0; i < samples; i++) {
            short s = (short) (Math.sin(2 * Math.PI * 440 * i / 8000.0) * amplitude);
            pcm[2 * i] = (byte) s;
            pcm[2 * i + 1] = (byte) (s >> 8);
        }
        return pcm;
    }

    @Test
    void wavRoundTripsThroughMulaw() throws IOException {
        byte[] pcm = tone(1000, 8000);
        byte[] wav = VoiceNotes.wav(pcm);
        assertEquals(58 + 8000, wav.length, "8 kHz μ-law: one byte per sample plus the header");
        byte[] back = VoiceNotes.decode(wav);
        assertEquals(pcm.length, back.length);
        assertArrayEquals(Mulaw.decode(Mulaw.encode(pcm)), back);
        assertEquals(1000, VoiceNotes.millis(back));
    }

    @Test
    void refusesOtherFiles() {
        assertThrows(IOException.class, () -> VoiceNotes.decode("not a wav file at all".getBytes()));
        byte[] wav = VoiceNotes.wav(tone(100, 1000));
        wav[24] = 0x44; // sample rate 44100-ish: not a voice message
        wav[25] = (byte) 0xAC;
        assertThrows(IOException.class, () -> VoiceNotes.decode(wav));
        assertThrows(IOException.class, () -> VoiceNotes.decode(new byte[0]));
    }

    @Test
    void waveformFollowsLoudness() {
        byte[] quietThenLoud = new byte[16000 * 2];
        System.arraycopy(tone(1000, 30000), 0, quietThenLoud, 16000, 16000);
        float[] bars = VoiceNotes.waveform(quietThenLoud, 10);
        assertEquals(10, bars.length);
        assertTrue(bars[0] < 0.05f);
        assertEquals(1f, bars[9], 1e-6);
        for (float b : bars) assertTrue(b >= 0 && b <= 1);
        assertEquals(0, VoiceNotes.waveform(new byte[0], 5)[0]);
    }

    @Test
    void recognisesVoiceMessages() {
        assertTrue(VoiceNotes.isVoice("audio/wav", "voice-20261103.wav"));
        assertTrue(!VoiceNotes.isVoice("application/pdf", "x.wav"));
        assertTrue(VoiceNotes.level(tone(20, 30000)) > 0.9f);
        assertEquals(0f, VoiceNotes.level(new byte[320]));
    }
}
