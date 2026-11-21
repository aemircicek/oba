package com.emirchat;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarFile;

/**
 * The packaging step's training run ({@code --cds-train}): loads the app's own classes and the
 * JavaFX classes a window needs, without opening anything, so the JVM can write them into a
 * class-data-sharing archive that later starts read from instead of from the jar.
 */
final class CdsTraining {
    /** Packages whose classes a normal start loads anyway. */
    private static final String[] PREFIXES = {"com/emirchat/", "javafx/", "com/sun/javafx/", "com/sun/prism/",
            "com/sun/glass/", "com/sun/scenario/", "org/sqlite/"};

    private CdsTraining() {}

    static void run() {
        ClassLoader loader = CdsTraining.class.getClassLoader();
        int loaded = 0;
        try {
            Path jar = Path.of(CdsTraining.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            if (!Files.isRegularFile(jar)) {
                System.out.println("not running from a jar: nothing to train");
                return;
            }
            try (JarFile file = new JarFile(jar.toFile())) {
                var entries = file.entries();
                while (entries.hasMoreElements()) {
                    String name = entries.nextElement().getName();
                    if (!name.endsWith(".class") || name.contains("-") || !wanted(name)) continue;
                    String cls = name.substring(0, name.length() - 6).replace('/', '.');
                    try {
                        Class.forName(cls, false, loader); // load (the archive links them), never initialise
                        loaded++;
                    } catch (Throwable ignored) {
                        // a class for another system, or one with a missing optional dependency
                    }
                }
            }
        } catch (IOException | URISyntaxException e) {
            System.out.println("training failed: " + e.getMessage());
            return;
        }
        System.out.println("loaded " + loaded + " classes for the archive");
    }

    private static boolean wanted(String name) {
        for (String p : PREFIXES) if (name.startsWith(p)) return true;
        return false;
    }
}
