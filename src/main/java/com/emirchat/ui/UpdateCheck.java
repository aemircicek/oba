package com.emirchat.ui;

import com.emirchat.AppInfo;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Asks GitHub for the latest release and compares it with this version. Only the public release
 * list is fetched; nothing about the user is sent (GitHub sees an IP address, like any download).
 * Can be turned off in Settings.
 */
final class UpdateCheck {
    static final String RELEASES = AppInfo.WEBSITE + "/releases/latest";
    private static final URI API = URI.create("https://api.github.com/repos/" + AppInfo.REPOSITORY + "/releases/latest");
    private static final Pattern TAG = Pattern.compile("\"tag_name\"\\s*:\\s*\"v?([0-9][^\"]*)\"");

    private UpdateCheck() {}

    /** @return the newer version, if there is one */
    static Optional<String> newerVersion() {
        String current = AppInfo.version();
        if (current.equals("dev")) return Optional.empty();
        try {
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
                    .followRedirects(HttpClient.Redirect.NORMAL) // GitHub redirects renamed repositories
                    .build();
            HttpRequest request = HttpRequest.newBuilder(API).timeout(Duration.ofSeconds(10))
                    .header("Accept", "application/vnd.github+json").header("User-Agent", "Oba/" + current).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) return Optional.empty();
            Matcher m = TAG.matcher(response.body());
            if (!m.find()) return Optional.empty();
            return isNewer(m.group(1), current) ? Optional.of(m.group(1)) : Optional.empty();
        } catch (Exception e) {
            return Optional.empty(); // offline, rate-limited… try again next time
        }
    }

    /** Compares dotted version numbers ("3.10.0" is newer than "3.9.2"; suffixes after "-" are ignored). */
    static boolean isNewer(String candidate, String current) {
        int[] a = parse(candidate);
        int[] b = parse(current);
        for (int i = 0; i < Math.max(a.length, b.length); i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) return x > y;
        }
        return false;
    }

    private static int[] parse(String version) {
        String[] parts = version.split("-", 2)[0].split("\\.");
        int[] numbers = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                numbers[i] = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                numbers[i] = 0;
            }
        }
        return numbers;
    }
}
