package com.emirchat.ui;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * What the server asks of a new account, checked while typing so nobody has to send the form to
 * learn the rules. The server checks again; these must say the same as its {@code AccountService}.
 * Pure logic (no JavaFX), so it is tested on its own.
 */
final class SignUpRules {
    private static final Pattern USERNAME = Pattern.compile("[a-z0-9_.]{3,20}");
    static final int MIN_PASSWORD = 8;
    static final int MAX_PASSWORD = 128;

    /**
     * One rule and whether what was typed meets it.
     *
     * @param text  the rule, in the user's language
     * @param ok    met
     * @param typed false while the field it is about is still empty (nothing to complain about yet)
     */
    record Check(String id, String text, boolean ok, boolean typed) {}

    private SignUpRules() {}

    /** The name as the server will store it: without surrounding spaces, in lowercase. */
    static String normalize(String username) {
        return username == null ? "" : username.strip().toLowerCase(Locale.ROOT);
    }

    static List<Check> checks(String username, String password, String repeat) {
        String name = normalize(username);
        String pass = password == null ? "" : password;
        String again = repeat == null ? "" : repeat;
        boolean length = pass.length() >= MIN_PASSWORD && pass.length() <= MAX_PASSWORD;
        boolean apart = name.isEmpty() || !pass.toLowerCase(Locale.ROOT).contains(name);
        return List.of(
                new Check("name", I18n.t("signup.ruleName"), USERNAME.matcher(name).matches(), !name.isEmpty()),
                new Check("length", I18n.t("signup.ruleLength"), length, !pass.isEmpty()),
                new Check("apart", I18n.t("signup.ruleApart"), apart && !pass.isEmpty(), !pass.isEmpty() && !name.isEmpty()),
                new Check("repeat", I18n.t("signup.ruleRepeat"), !pass.isEmpty() && pass.equals(again), !again.isEmpty()));
    }

    static boolean allMet(String username, String password, String repeat) {
        return checks(username, password, repeat).stream().allMatch(Check::ok);
    }
}
