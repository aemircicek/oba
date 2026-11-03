package com.emirchat.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SingleInstanceTest {
    @TempDir Path dir;

    @Test
    void aSecondStartHandsItsRequestToTheFirst() throws Exception {
        BlockingQueue<String> requests = new LinkedBlockingQueue<>();
        try (SingleInstance first = SingleInstance.acquire(dir, "show", null)) {
            assertNotNull(first);
            first.onRequest((command, argument) -> requests.add(command + ":" + argument));

            assertNull(SingleInstance.acquire(dir, "show", null), "the second start defers to the first");
            assertEquals("show:", requests.poll(5, TimeUnit.SECONDS));
            assertNull(SingleInstance.acquire(dir, "open", "C:\\Users\\emir\\yedek dosyası.scbackup"));
            assertEquals("open:C:\\Users\\emir\\yedek dosyası.scbackup", requests.poll(5, TimeUnit.SECONDS));
        }
        try (SingleInstance again = SingleInstance.acquire(dir, "show", null)) {
            assertNotNull(again, "after the first one quits, the next start runs normally");
        }
    }

    @Test
    void requestsWithoutTheTokenAreIgnored() throws Exception {
        BlockingQueue<String> requests = new LinkedBlockingQueue<>();
        try (SingleInstance first = SingleInstance.acquire(dir, "show", null)) {
            first.onRequest((command, argument) -> requests.add(command));
            int port = Integer.parseInt(Files.readString(dir.resolve("app.port")).split(" ")[0]);
            try (Socket s = new Socket(InetAddress.getLoopbackAddress(), port); OutputStream out = s.getOutputStream()) {
                out.write("wrong-token open /etc/passwd\n".getBytes(StandardCharsets.UTF_8));
            }
            assertNull(requests.poll(500, TimeUnit.MILLISECONDS), "another program on this computer cannot drive the app");
        }
    }

    @Test
    void startupEntriesStartHiddenInTheTray() {
        Path linux = Path.of("/opt/oba/bin/Oba");
        assertTrue(Autostart.desktopEntry(linux).contains("Exec=\"" + linux + "\" --background"));
        Path mac = Path.of("/Applications/Oba & Co.app/Contents/MacOS/Oba");
        String plist = Autostart.launchAgent(mac);
        assertTrue(plist.contains("<string>" + mac.toString().replace("&", "&amp;") + "</string>"));
        assertTrue(plist.contains("<string>--background</string>"));
    }
}
