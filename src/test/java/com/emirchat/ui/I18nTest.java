package com.emirchat.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** Every text exists in both languages, with the same placeholders, and every key in the code is defined. */
class I18nTest {
    private static Properties load(String name) throws IOException {
        Properties p = new Properties();
        try (InputStream in = I18nTest.class.getResourceAsStream("/com/emirchat/ui/" + name)) {
            p.load(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        return p;
    }

    private static Set<String> placeholders(String text) {
        Set<String> found = new TreeSet<>();
        Matcher m = Pattern.compile("\\{\\d}").matcher(text);
        while (m.find()) found.add(m.group());
        return found;
    }

    @AfterEach
    void reset() {
        I18n.use(I18n.SYSTEM);
    }

    @Test
    void bothLanguagesHaveTheSameKeysAndPlaceholders() throws IOException {
        Properties en = load("messages.properties");
        Properties tr = load("messages_tr.properties");
        assertEquals(new TreeSet<>(en.stringPropertyNames()), new TreeSet<>(tr.stringPropertyNames()));
        for (String key : en.stringPropertyNames()) {
            assertEquals(placeholders(en.getProperty(key)), placeholders(tr.getProperty(key)), key);
            assertTrue(!tr.getProperty(key).isBlank(), key + " is empty in Turkish");
        }
    }

    @Test
    void everyKeyUsedInTheCodeIsDefined() throws IOException {
        Properties en = load("messages.properties");
        Pattern call = Pattern.compile("I18n\\.(?:t|plural)\\(\\s*\"([a-zA-Z0-9.]+)\"");
        Set<String> missing = new TreeSet<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path f : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                Matcher m = call.matcher(Files.readString(f));
                while (m.find()) {
                    String key = m.group(1);
                    if (m.group(0).contains("plural")) continue; // unit names, checked below
                    if (!en.containsKey(key)) missing.add(key + " (" + f.getFileName() + ")");
                }
            }
        }
        for (String unit : new String[] {"second", "minute", "hour", "day", "week"}) {
            for (String n : new String[] {"one", "many"}) {
                if (!en.containsKey("unit." + unit + "." + n)) missing.add("unit." + unit + "." + n);
            }
        }
        assertTrue(missing.isEmpty(), "missing texts: " + missing);
    }

    @Test
    void placeholdersAndApostrophesSurvive() {
        I18n.use("tr");
        assertEquals("tr", I18n.language());
        assertEquals("efe kişisinin anahtarı değişti. Önce doğrula.", I18n.t("conv.keyChangedFirst", "efe"));
        assertEquals("5 dakika", I18n.plural(5, "minute"));
        I18n.use("en");
        assertEquals("efe's key changed. Verify it first.", I18n.t("conv.keyChangedFirst", "efe"));
        assertEquals("1 minute", I18n.plural(1, "minute"));
        assertEquals("5 minutes", I18n.plural(5, "minute"));
        assertEquals("no.such.key", I18n.t("no.such.key"));
    }
}
