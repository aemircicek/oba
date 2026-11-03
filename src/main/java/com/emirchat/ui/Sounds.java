package com.emirchat.ui;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.SourceDataLine;

/**
 * Notification sounds, synthesised (no audio files to ship): a soft chime, a wooden "tock", a
 * water drop and the crackle of a campfire. Chosen in Settings; "off" is silent.
 */
final class Sounds {
    static final java.util.List<String> NAMES = java.util.List.of("chime", "tock", "drop", "crackle", "off");
    private static final float RATE = 22050f;

    private Sounds() {}

    /** Plays the chosen notification sound (off the UI thread; failures are silent). */
    static void play() {
        play(AppPrefs.sound());
    }

    static void play(String name) {
        if ("off".equals(name) || Boolean.getBoolean("securechat.preview")
                || !Boolean.parseBoolean(System.getProperty("securechat.notifications", "true"))) return;
        byte[] pcm = synth(name);
        Thread.ofPlatform().daemon().name("sound").start(() -> {
            AudioFormat format = new AudioFormat(RATE, 16, 1, true, false);
            try (SourceDataLine line = AudioSystem.getSourceDataLine(format)) {
                line.open(format);
                line.start();
                line.write(pcm, 0, pcm.length);
                line.drain();
            } catch (Exception ignored) {
                // no sound device: the notification still shows
            }
        });
    }

    /** Pops at fixed moments (so the sound is the same every time) over a faint hiss. */
    private static double crackle(java.util.Random noise, double t) {
        double hiss = (noise.nextDouble() * 2 - 1) * 0.08 * Math.exp(-t * 3);
        double pops = 0;
        for (double at : new double[] {0.0, 0.07, 0.16, 0.21, 0.33}) {
            double d = t - at;
            if (d >= 0 && d < 0.02) pops += (noise.nextDouble() * 2 - 1) * Math.exp(-d * 260) * 0.9;
        }
        return hiss + pops;
    }

    /** 16-bit mono samples for a sound. */
    static byte[] synth(String name) {
        double seconds = switch (name) {
            case "tock" -> 0.12;
            case "drop" -> 0.25;
            case "crackle", "rattle" -> 0.45;
            default -> 0.6;
        };
        int n = (int) (seconds * RATE);
        byte[] out = new byte[n * 2];
        java.util.Random noise = new java.util.Random(3);
        for (int i = 0; i < n; i++) {
            double t = i / RATE;
            double v = switch (name) {
                // two bell partials, fading
                case "tock" -> Math.sin(2 * Math.PI * 820 * t) * Math.exp(-t * 45) + 0.4 * Math.sin(2 * Math.PI * 1640 * t) * Math.exp(-t * 70);
                // a falling pitch, like a drop
                case "drop" -> Math.sin(2 * Math.PI * (1400 - 2600 * t) * t) * Math.exp(-t * 14);
                // a few sharp pops and a soft hiss: wood crackling in a fire
                case "crackle", "rattle" -> crackle(noise, t) * 0.9;
                default -> (Math.sin(2 * Math.PI * 880 * t) + 0.5 * Math.sin(2 * Math.PI * 1320 * t)) * Math.exp(-t * 6) * 0.6;
            };
            double attack = Math.min(1, t / 0.004); // no click at the start
            short s = (short) (Math.max(-1, Math.min(1, v * attack)) * 12000);
            out[2 * i] = (byte) s;
            out[2 * i + 1] = (byte) (s >> 8);
        }
        return out;
    }
}
