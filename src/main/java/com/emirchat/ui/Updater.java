package com.emirchat.ui;

import com.emirchat.AppInfo;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Duration;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.function.DoubleConsumer;

/**
 * Downloads and installs a newer release by itself, but only one whose files are signed by the
 * release key.
 *
 * <p>The release workflow lists every file's SHA-256 in {@code SHA256SUMS} and signs that list with
 * an Ed25519 key kept as a repository secret ({@code SHA256SUMS.sig}). The public half is built
 * into this class. An update is installed only if the list's signature checks out with it, the
 * list names the installer, the download has exactly that hash, and the version is newer than
 * the running one, so neither a changed download, a forged list nor an old release served again
 * gets installed. Without a signature (or for a jar started by hand) the app only opens the
 * download page, as before.
 */
final class Updater {
    /** Ed25519 public key (X.509 SubjectPublicKeyInfo, base64) whose signatures releases carry. */
    static final String RELEASE_KEY = "MCowBQYDK2VwAyEA2e4djl5IfBJNP4FjNGhZH21E4ejSzmFybaWwbA21FmQ=";
    /** Installers are about 130 MB; anything far bigger is not one of ours. */
    static final long MAX_SIZE = 600L * 1024 * 1024;

    /** How this copy of Oba was installed, which decides what an update replaces. */
    enum Kind {
        MAC_APP("macos-arm64", "dmg"),
        WINDOWS("windows-x64", "exe"),
        DEB("linux-x64", "deb"),
        /** a jar, or a portable folder: updated by hand */
        MANUAL(null, null);

        final String platform;
        final String extension;

        Kind(String platform, String extension) {
            this.platform = platform;
            this.extension = extension;
        }

        String assetName(String version) {
            return "Oba-" + version + "-" + platform + "." + extension;
        }
    }

    /** A verified installer on disk, ready to be started. */
    record Ready(String version, Kind kind, Path installer) {}

    private Updater() {}

    /** Works out from where the app runs whether it can replace itself. */
    static Kind kind() {
        return kind(System.getProperty("jpackage.app-path"), System.getProperty("os.name", ""),
                System.getProperty("os.arch", ""));
    }

    static Kind kind(String appPath, String os, String arch) {
        if (appPath == null || appPath.isBlank()) return Kind.MANUAL; // a jar
        String o = os.toLowerCase(Locale.ROOT);
        String a = arch.toLowerCase(Locale.ROOT);
        if (o.contains("mac")) return a.equals("aarch64") && appPath.contains(".app/") ? Kind.MAC_APP : Kind.MANUAL;
        if (o.contains("win")) {
            // the portable zip runs from wherever it was unpacked; the installer's copy is in AppData
            return appPath.toLowerCase(Locale.ROOT).contains("\\appdata\\local\\") ? Kind.WINDOWS : Kind.MANUAL;
        }
        if (o.contains("linux")) return appPath.startsWith("/opt/oba/") && a.equals("amd64") ? Kind.DEB : Kind.MANUAL;
        return Kind.MANUAL;
    }

    /**
     * Downloads the installer of {@code version} for this system and checks it.
     *
     * @throws IOException if anything is missing or does not match; nothing is left behind then
     */
    static Ready download(String version, Kind kind, Path folder, DoubleConsumer progress) throws IOException {
        if (kind == Kind.MANUAL) throw new IOException("This copy of Oba is updated by hand");
        if (AppInfo.version().equals("dev") || !UpdateCheck.isNewer(version, AppInfo.version())) throw new IOException("Not a newer version: " + version);
        String base = AppInfo.WEBSITE + "/releases/download/v" + version + "/";
        HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
        byte[] sums = fetch(http, base + "SHA256SUMS", 64 * 1024);
        byte[] signature;
        try {
            signature = Base64.getDecoder().decode(new String(fetch(http, base + "SHA256SUMS.sig", 4096),
                    StandardCharsets.US_ASCII).strip());
        } catch (IllegalArgumentException e) {
            throw new IOException("The release signature is damaged", e);
        }
        String name = kind.assetName(version);
        byte[] expected = expectedHash(sums, signature, publicKey(RELEASE_KEY), name);

        Files.createDirectories(folder);
        Path part = folder.resolve(name + ".part");
        Path target = folder.resolve(name);
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            HttpResponse<InputStream> r = send(http, base + name, HttpResponse.BodyHandlers.ofInputStream());
            long total = r.headers().firstValueAsLong("content-length").orElse(-1);
            if (total > MAX_SIZE) throw new IOException("The download is too large");
            long done = 0;
            try (InputStream in = r.body(); OutputStream out = Files.newOutputStream(part)) {
                byte[] buf = new byte[1 << 16];
                for (int n; (n = in.read(buf)) > 0; ) {
                    done += n;
                    if (done > MAX_SIZE) throw new IOException("The download is too large");
                    sha.update(buf, 0, n);
                    out.write(buf, 0, n);
                    if (total > 0) progress.accept((double) done / total);
                }
            }
            if (!MessageDigest.isEqual(sha.digest(), expected)) {
                throw new IOException("The download does not match the signed list of files");
            }
            Files.move(part, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            return new Ready(version, kind, target);
        } catch (GeneralSecurityException e) {
            throw new IOException(e);
        } finally {
            Files.deleteIfExists(part);
        }
    }

    /**
     * Checks the signature over {@code SHA256SUMS} and returns the hash it lists for {@code name}.
     *
     * @throws IOException if the signature is wrong or the file is not listed
     */
    static byte[] expectedHash(byte[] sums, byte[] signature, PublicKey key, String name) throws IOException {
        try {
            Signature v = Signature.getInstance("Ed25519");
            v.initVerify(key);
            v.update(sums);
            if (!v.verify(signature)) throw new IOException("The release signature is not valid");
        } catch (GeneralSecurityException e) {
            throw new IOException("The release signature is not valid", e);
        }
        for (String line : new String(sums, StandardCharsets.UTF_8).split("\n")) {
            String[] parts = line.strip().split("\\s+\\*?", 2);
            if (parts.length == 2 && parts[1].equals(name) && parts[0].matches("[0-9a-fA-F]{64}")) {
                return HexFormat.of().parseHex(parts[0].toLowerCase(Locale.ROOT));
            }
        }
        throw new IOException("The signed list does not name " + name);
    }

    static PublicKey publicKey(String base64) {
        try {
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    /**
     * Starts the installer and returns; the caller then quits so files can be replaced. macOS:
     * the new app is copied out of the disk image and a small script swaps it in once this
     * process has ended, then opens it. Windows: the installer (which replaces the old version
     * itself). Linux: {@code pkexec apt-get install}, then the new version is started.
     */
    static void install(Ready ready) throws IOException {
        long pid = ProcessHandle.current().pid();
        switch (ready.kind()) {
            case WINDOWS -> new ProcessBuilder(ready.installer().toString()).start();
            case MAC_APP -> installMac(ready, pid);
            case DEB -> helper(ready.installer().getParent().resolve("install.sh"), String.join("\n",
                    "#!/bin/sh",
                    waitFor(pid),
                    "pkexec apt-get install -y " + quote(ready.installer().toString()) + " || xdg-open "
                            + quote(ready.installer().toString()),
                    "exec /opt/oba/bin/Oba"));
            case MANUAL -> throw new IOException("This copy of Oba is updated by hand");
        }
    }

    /** The .app the running Java belongs to (…/Oba.app). */
    static Optional<Path> macBundle(String appPath) {
        int i = appPath == null ? -1 : appPath.indexOf(".app/");
        return i < 0 ? Optional.empty() : Optional.of(Path.of(appPath.substring(0, i + 4)));
    }

    private static void installMac(Ready ready, long pid) throws IOException {
        Path bundle = macBundle(System.getProperty("jpackage.app-path"))
                .orElseThrow(() -> new IOException("Not running from an app bundle"));
        if (!Files.isWritable(bundle.getParent())) {
            // no rights to replace it (another user's /Applications): open the disk image instead
            new ProcessBuilder("open", ready.installer().toString()).start();
            return;
        }
        Path folder = ready.installer().getParent();
        Path mount = Files.createTempDirectory(folder, "mount");
        Path staged = folder.resolve("Oba-" + ready.version() + ".app");
        run("hdiutil", "attach", "-nobrowse", "-readonly", "-noautoopen", "-mountpoint", mount.toString(),
                ready.installer().toString());
        try {
            deleteTree(staged);
            run("ditto", mount.resolve("Oba.app").toString(), staged.toString());
        } finally {
            run("hdiutil", "detach", "-force", mount.toString());
            Files.deleteIfExists(mount);
        }
        run("codesign", "--verify", "--deep", "--strict", staged.toString());
        Path old = folder.resolve("Oba-previous.app");
        helper(folder.resolve("install.sh"), String.join("\n",
                "#!/bin/sh",
                waitFor(pid),
                "rm -rf " + quote(old.toString()),
                "mv " + quote(bundle.toString()) + " " + quote(old.toString()) + " && mv " + quote(staged.toString()) + " "
                        + quote(bundle.toString()) + " && rm -rf " + quote(old.toString()) + " " + quote(ready.installer().toString()),
                "open " + quote(bundle.toString())));
    }

    private static String waitFor(long pid) {
        return "while kill -0 " + pid + " 2>/dev/null; do sleep 0.3; done";
    }

    private static void helper(Path script, String body) throws IOException {
        Files.writeString(script, body + "\n");
        script.toFile().setExecutable(true, true);
        new ProcessBuilder("/bin/sh", script.toString()).redirectErrorStream(true)
                .redirectOutput(script.resolveSibling("install.log").toFile()).start();
    }

    private static void run(String... command) throws IOException {
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (p.waitFor() != 0) throw new IOException(command[0] + " failed: " + out.strip());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }

    static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    private static void deleteTree(Path p) throws IOException {
        if (!Files.exists(p)) return;
        try (var walk = Files.walk(p)) {
            for (Path x : walk.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(x);
        }
    }

    private static byte[] fetch(HttpClient http, String url, int max) throws IOException {
        byte[] body = send(http, url, HttpResponse.BodyHandlers.ofByteArray()).body();
        if (body.length > max) throw new IOException("Unexpectedly large: " + url);
        return body;
    }

    private static <T> HttpResponse<T> send(HttpClient http, String url, HttpResponse.BodyHandler<T> handler)
            throws IOException {
        URI uri = URI.create(url);
        if (!uri.getScheme().equals("https")) throw new IOException("Not https: " + url);
        try {
            HttpResponse<T> r = http.send(HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(10))
                    .header("User-Agent", "Oba/" + AppInfo.version()).build(), handler);
            if (r.statusCode() != 200) throw new IOException("HTTP " + r.statusCode() + " for " + url);
            return r;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted", e);
        }
    }
}
