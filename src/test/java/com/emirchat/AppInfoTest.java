package com.emirchat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AppInfoTest {
    @TempDir Path dir;

    @Test
    void dataFolderFollowsEachPlatformsConvention() {
        Path home = Path.of("/home/emir");
        assertEquals(Path.of("C:/Users/emir/AppData/Roaming", "Oba"),
                AppInfo.platformHome("Windows 11", "C:/Users/emir/AppData/Roaming", null, home));
        assertEquals(home.resolve("Library/Application Support/Oba"), AppInfo.platformHome("Mac OS X", null, null, home));
        assertEquals(home.resolve(".local/share/oba"), AppInfo.platformHome("Linux", null, "", home));
        assertEquals(Path.of("/data/xdg", "oba"), AppInfo.platformHome("Linux", null, "/data/xdg", home));
    }

    @Test
    void theOldFolderIsMovedOnceAndNeverOverwritesNewData() throws Exception {
        Path legacy = dir.resolve(".securechat");
        Files.createDirectories(legacy.resolve("accounts"));
        Files.writeString(legacy.resolve("identity.key"), "old key");
        Path target = dir.resolve("AppData/Oba");

        assertEquals(target, AppInfo.adoptLegacy(legacy, target));
        assertEquals("old key", Files.readString(target.resolve("identity.key")));
        assertFalse(Files.exists(legacy));

        Files.createDirectories(legacy);
        Files.writeString(legacy.resolve("identity.key"), "another key");
        assertEquals(target, AppInfo.adoptLegacy(legacy, target), "an existing new folder wins");
        assertEquals("old key", Files.readString(target.resolve("identity.key")));
        assertTrue(Files.exists(legacy.resolve("identity.key")), "and the old one is left alone");
    }

    @Test
    void theScorpionFolderIsWhereTheOldDataIs() {
        Path home = Path.of("/home/emir");
        assertEquals(home.resolve("Library/Application Support/Scorpion"),
                AppInfo.platformHome("Mac OS X", null, null, home, AppInfo.FORMER_NAME));
        assertEquals(home.resolve(".local/share/scorpion"), AppInfo.platformHome("Linux", null, null, home, AppInfo.FORMER_NAME));
        assertEquals(Path.of("C:/Users/emir/AppData/Roaming", "Scorpion"),
                AppInfo.platformHome("Windows 11", "C:/Users/emir/AppData/Roaming", null, home, AppInfo.FORMER_NAME));
    }
}
