package com.emirchat.ui;

import com.emirchat.api.ApiException;
import com.emirchat.api.ErrorCode;
import java.util.Locale;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.prefs.Preferences;

/**
 * The app's texts in English and Turkish ({@code messages.properties},
 * {@code messages_tr.properties}). The language follows the system unless the user picked one in
 * Settings; a change takes effect at the next start.
 *
 * <p>Placeholders are {@code {0}}, {@code {1}} … replaced literally (not {@link java.text.MessageFormat},
 * whose quoting rules would swallow the apostrophes Turkish uses in "Efe'nin").
 */
final class I18n {
    static final String SYSTEM = "system";
    private static final Preferences PREFS = Preferences.userNodeForPackage(I18n.class).node("app");
    private static volatile ResourceBundle bundle = load(choice());

    private I18n() {}

    /** "system", "en" or "tr". */
    static String choice() {
        return PREFS.get("language", SYSTEM);
    }

    static void choose(String language) {
        PREFS.put("language", language);
    }

    /** The language in use: "tr" or "en". */
    static String language() {
        return bundle.getLocale().getLanguage().equals("tr") ? "tr" : "en";
    }

    static Locale locale() {
        return language().equals("tr") ? Locale.forLanguageTag("tr-TR") : Locale.ENGLISH;
    }

    private static ResourceBundle load(String choice) {
        String lang = choice.equals(SYSTEM) ? Locale.getDefault().getLanguage() : choice;
        Locale locale = "tr".equals(lang) ? Locale.forLanguageTag("tr") : Locale.ROOT;
        return ResourceBundle.getBundle("com.emirchat.ui.messages", locale,
                ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_PROPERTIES));
    }

    /** For tests and the preview tool: switch language immediately. */
    static void use(String language) {
        bundle = load(language);
    }

    /** The text for {@code key}, with {0}, {1} … replaced by {@code args}. */
    static String t(String key, Object... args) {
        String text;
        try {
            text = bundle.getString(key);
        } catch (MissingResourceException e) {
            text = key; // visible in the UI, and caught by the translation test
        }
        for (int i = 0; i < args.length; i++) text = text.replace("{" + i + "}", String.valueOf(args[i]));
        return text;
    }

    /** A server error in the user's language (the server only speaks English). */
    static String error(ApiException e) {
        return error(e.code(), e.getMessage());
    }

    static String error(ErrorCode code, String serverMessage) {
        String key = "error." + code.name().toLowerCase(Locale.ROOT);
        if (bundle.containsKey(key)) return bundle.getString(key);
        return serverMessage;
    }

    /** "1 minute", "5 dakika" … */
    static String plural(long n, String unit) {
        return t("unit." + unit + (n == 1 ? ".one" : ".many"), n);
    }
}
