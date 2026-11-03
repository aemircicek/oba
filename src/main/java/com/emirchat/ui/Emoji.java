package com.emirchat.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Emoji for the picker and for {@code :name} suggestions while typing. Names are English
 * shortcodes (as on Slack, Discord and GitHub) plus Turkish aliases, so {@code :kalp} and
 * {@code :heart} both find ❤️.
 */
final class Emoji {
    record Entry(String emoji, String name, List<String> aliases) {
        boolean matches(String prefix) {
            if (name.startsWith(prefix)) return true;
            for (String a : aliases) if (a.startsWith(prefix)) return true;
            return false;
        }
    }

    /** Category name (translation key suffix) → its emoji, in picker order. */
    static final Map<String, List<Entry>> CATEGORIES = new LinkedHashMap<>();
    static final List<Entry> ALL = new ArrayList<>();

    private static List<Entry> current;

    private static void category(String key) {
        current = new ArrayList<>();
        CATEGORIES.put(key, current);
    }

    private static void e(String emoji, String name, String... aliases) {
        Entry entry = new Entry(emoji, name, List.of(aliases));
        current.add(entry);
        ALL.add(entry);
    }

    static {
        category("smileys");
        e("😀", "grinning", "gulen");
        e("😃", "smiley", "mutlu");
        e("😄", "smile", "gulumseme");
        e("😁", "grin", "sirit");
        e("😆", "laughing", "kahkaha");
        e("😅", "sweat_smile", "terli");
        e("🤣", "rofl", "yerlerde");
        e("😂", "joy", "gozyasi", "kahkaha2");
        e("🙂", "slight_smile", "hafif");
        e("😉", "wink", "goz_kirp");
        e("😊", "blush", "utangac");
        e("😇", "innocent", "melek");
        e("🥰", "smiling_hearts", "asik");
        e("😍", "heart_eyes", "kalp_goz");
        e("🤩", "star_struck", "yildiz_goz");
        e("😘", "kiss", "opucuk");
        e("😋", "yum", "lezzetli");
        e("😛", "tongue", "dil");
        e("😜", "wink_tongue", "deli");
        e("🤪", "zany", "cilgin");
        e("🤔", "thinking", "dusunen");
        e("🤨", "raised_eyebrow", "kas");
        e("😐", "neutral", "notr");
        e("😑", "expressionless", "ifadesiz");
        e("😶", "no_mouth", "agizsiz");
        e("🙄", "eye_roll", "goz_devir");
        e("😏", "smirk", "sinsi");
        e("😬", "grimace", "gergin");
        e("😌", "relieved", "rahat");
        e("😴", "sleeping", "uyku");
        e("🤤", "drooling", "salya");
        e("😷", "mask", "maske");
        e("🤒", "sick", "hasta");
        e("🤯", "mind_blown", "beyin_yandi");
        e("🥳", "party", "parti");
        e("😎", "sunglasses", "havali");
        e("🤓", "nerd", "inek");
        e("😕", "confused", "kafasi_karisik");
        e("😟", "worried", "endiseli");
        e("😮", "open_mouth", "sasirmis");
        e("😲", "astonished", "saskin");
        e("🥺", "pleading", "yalvaran");
        e("😢", "cry", "aglayan");
        e("😭", "sob", "hungur");
        e("😱", "scream", "ciglik");
        e("😤", "triumph", "hirs");
        e("😡", "rage", "kizgin");
        e("🤬", "cursing", "kufur");
        e("💀", "skull", "kafatasi");
        e("💩", "poop", "kaka");
        e("🤡", "clown", "palyaco");
        e("👻", "ghost", "hayalet");
        e("👽", "alien", "uzayli");
        e("🤖", "robot", "robot");

        category("gestures");
        e("👍", "thumbsup", "begen", "like");
        e("👎", "thumbsdown", "begenme");
        e("👌", "ok_hand", "tamam");
        e("✌️", "v", "zafer");
        e("🤞", "crossed_fingers", "sans");
        e("🤟", "love_you", "seviyorum_el");
        e("🤘", "metal", "rock");
        e("👏", "clap", "alkis");
        e("🙌", "raised_hands", "yasasin");
        e("👐", "open_hands", "acik_el");
        e("🙏", "pray", "lutfen", "dua");
        e("🤝", "handshake", "anlastik");
        e("💪", "muscle", "guc", "kas_kol");
        e("👋", "wave", "selam", "merhaba");
        e("👉", "point_right", "sag");
        e("👈", "point_left", "sol");
        e("👆", "point_up", "yukari");
        e("👇", "point_down", "asagi");
        e("✍️", "writing", "yaziyor");
        e("👀", "eyes", "gozler");
        e("🧠", "brain", "beyin");

        category("hearts");
        e("❤️", "heart", "kalp");
        e("🧡", "orange_heart", "turuncu_kalp");
        e("💛", "yellow_heart", "sari_kalp");
        e("💚", "green_heart", "yesil_kalp");
        e("💙", "blue_heart", "mavi_kalp");
        e("💜", "purple_heart", "mor_kalp");
        e("🖤", "black_heart", "siyah_kalp");
        e("🤍", "white_heart", "beyaz_kalp");
        e("💔", "broken_heart", "kirik_kalp");
        e("💕", "two_hearts", "iki_kalp");
        e("💖", "sparkling_heart", "parlayan_kalp");
        e("💯", "100", "yuz");
        e("💥", "boom", "patlama");
        e("💫", "dizzy", "bas_donmesi");
        e("💬", "speech", "konusma");
        e("💤", "zzz", "uyuyor");

        category("nature");
        e("🦂", "scorpion", "akrep");
        e("🐍", "snake", "yilan");
        e("🦎", "lizard", "kertenkele");
        e("🐢", "turtle", "kaplumbaga");
        e("🐶", "dog", "kopek");
        e("🐱", "cat", "kedi");
        e("🦊", "fox", "tilki");
        e("🐻", "bear", "ayi");
        e("🐼", "panda", "panda");
        e("🦁", "lion", "aslan");
        e("🐸", "frog", "kurbaga");
        e("🐝", "bee", "ari");
        e("🦋", "butterfly", "kelebek");
        e("🌵", "cactus", "kaktus");
        e("🌴", "palm", "palmiye");
        e("🌲", "tree", "agac");
        e("🌸", "blossom", "cicek");
        e("🌹", "rose", "gul");
        e("🌻", "sunflower", "aycicegi");
        e("☀️", "sun", "gunes");
        e("🌙", "moon", "ay");
        e("⭐", "star", "yildiz");
        e("🌈", "rainbow", "gokkusagi");
        e("⚡", "zap", "simsek");
        e("🔥", "fire", "ates", "yangin");
        e("❄️", "snowflake", "kar");
        e("🌊", "wave_sea", "dalga");

        category("food");
        e("☕", "coffee", "kahve");
        e("🍵", "tea", "cay");
        e("🍺", "beer", "bira");
        e("🍷", "wine", "sarap");
        e("🍕", "pizza", "pizza");
        e("🍔", "burger", "hamburger");
        e("🌯", "wrap", "durum");
        e("🥙", "doner", "doner", "kebap");
        e("🍟", "fries", "patates");
        e("🍰", "cake", "pasta");
        e("🎂", "birthday", "dogum_gunu");
        e("🍫", "chocolate", "cikolata");
        e("🍎", "apple", "elma");
        e("🍉", "watermelon", "karpuz");
        e("🍋", "lemon", "limon");

        category("activities");
        e("⚽", "soccer", "futbol", "top");
        e("🏀", "basketball", "basketbol");
        e("🏊", "swimmer", "yuzme");
        e("🏆", "trophy", "kupa");
        e("🥇", "first", "birinci", "altin");
        e("🎮", "video_game", "oyun");
        e("🎲", "dice", "zar");
        e("🎯", "dart", "hedef");
        e("🎉", "tada", "kutlama", "konfeti");
        e("🎊", "confetti", "konfeti2");
        e("🎁", "gift", "hediye");
        e("🎵", "music", "muzik");
        e("🎧", "headphones", "kulaklik");
        e("🎨", "art", "sanat");
        e("🎬", "movie", "film");
        e("📸", "camera", "kamera", "foto");

        category("objects");
        e("💻", "laptop", "bilgisayar");
        e("⌨️", "keyboard", "klavye");
        e("📱", "phone", "telefon");
        e("🔒", "lock", "kilit");
        e("🔓", "unlock", "acik_kilit");
        e("🔑", "key", "anahtar");
        e("🛡️", "shield", "kalkan");
        e("🐛", "bug", "hata", "bocek");
        e("🔧", "wrench", "anahtar_alet");
        e("🔨", "hammer", "cekic");
        e("⚙️", "gear", "ayar");
        e("📎", "paperclip", "atac");
        e("📌", "pin", "raptiye", "sabitle");
        e("📅", "calendar", "takvim");
        e("⏰", "alarm", "alarm", "saat");
        e("⏳", "hourglass", "kum_saati");
        e("📚", "books", "kitaplar");
        e("✏️", "pencil", "kalem");
        e("📝", "memo", "not");
        e("📐", "triangle_ruler", "gonye", "mimari");
        e("🏠", "house", "ev");
        e("🏛️", "classical_building", "bina");
        e("🚀", "rocket", "roket");
        e("✈️", "airplane", "ucak");
        e("🚗", "car", "araba");
        e("💡", "bulb", "fikir", "ampul");
        e("📈", "chart_up", "yukselis");
        e("💰", "money", "para");

        category("symbols");
        e("✅", "check", "tamamlandi", "onay");
        e("❌", "x", "carpi", "hayir");
        e("⚠️", "warning", "uyari");
        e("❗", "exclamation", "unlem");
        e("❓", "question", "soru");
        e("➕", "plus", "arti");
        e("➖", "minus", "eksi");
        e("🔴", "red_circle", "kirmizi");
        e("🟢", "green_circle", "yesil");
        e("🔵", "blue_circle", "mavi");
        e("✨", "sparkles", "parilti");
        e("🆗", "ok", "ok");
        e("🆕", "new", "yeni");
        e("🇹🇷", "tr", "turkiye", "bayrak");
    }

    private Emoji() {}

    /**
     * Up to {@code limit} emoji whose name or an alias starts with {@code prefix} (case and
     * Turkish letters ignored), exact matches first.
     */
    static List<Entry> suggest(String prefix, int limit) {
        String p = fold(prefix);
        if (p.isEmpty()) return List.of();
        // exact name, exact alias, name prefix, alias prefix
        List<List<Entry>> ranks = List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        for (Entry e : ALL) {
            if (e.name().equals(p)) ranks.get(0).add(e);
            else if (e.aliases().contains(p)) ranks.get(1).add(e);
            else if (e.name().startsWith(p)) ranks.get(2).add(e);
            else if (e.matches(p)) ranks.get(3).add(e);
        }
        List<Entry> out = new ArrayList<>();
        ranks.forEach(out::addAll);
        return out.size() > limit ? out.subList(0, limit) : out;
    }

    /**
     * The {@code :name} being typed just before the caret, without the colon, or null. It needs
     * at least two letters and a space, line start or "(" before the colon, so times like
     * "10:30" and links never pop up suggestions.
     */
    static String typedShortcode(String text, int caret) {
        if (caret > text.length()) return null;
        int i = caret - 1;
        while (i >= 0 && (Character.isLetterOrDigit(text.charAt(i)) || text.charAt(i) == '_')) i--;
        if (i < 0 || text.charAt(i) != ':') return null;
        if (i > 0 && !Character.isWhitespace(text.charAt(i - 1)) && text.charAt(i - 1) != '(') return null;
        String code = text.substring(i + 1, caret);
        return code.length() >= 2 ? code : null;
    }

    /** Lowercase and Turkish letters folded to ASCII (ı→i, ş→s ...), as the aliases are written. */
    static String fold(String s) {
        StringBuilder b = new StringBuilder();
        for (char c : s.toLowerCase(Locale.ROOT).toCharArray()) {
            b.append(switch (c) {
                case 'ı', 'İ' -> 'i';
                case 'ş' -> 's';
                case 'ğ' -> 'g';
                case 'ü' -> 'u';
                case 'ö' -> 'o';
                case 'ç' -> 'c';
                default -> c;
            });
        }
        return b.toString();
    }
}
