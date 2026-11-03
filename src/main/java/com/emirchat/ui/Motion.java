package com.emirchat.ui;

/**
 * Whether decorative animation plays. Off when the user turned on "Reduce motion" in Settings
 * (and in screenshot runs, so pictures are taken at rest).
 */
final class Motion {
    private Motion() {}

    static boolean enabled() {
        if ("force".equals(System.getProperty("securechat.motion"))) return true; // the preview tool, for one picture
        return !AppPrefs.reduceMotion() && !Boolean.getBoolean("securechat.preview");
    }
}
