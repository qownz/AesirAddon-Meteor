package com.aesir.addon;

import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.StyleSpriteSource;
import net.minecraft.text.Text;
import net.minecraft.text.TextColor;
import net.minecraft.util.Identifier;

import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public final class AesirBadges {
    public static final Identifier BADGE_FONT = Identifier.of("aesir", "badge");
    public static final char MOBILE_ICON = '\uE11F';
    private static final TextColor WHITE = TextColor.fromRgb(0xFFFFFF);

    private static final Set<String> ACTIVE_CLIENTS = ConcurrentHashMap.newKeySet();
    private static final Set<String> MOBILE_PLAYERS = ConcurrentHashMap.newKeySet();
    private static final Map<String, Integer> BADGE_LEVELS = new ConcurrentHashMap<>();

    private AesirBadges() {
    }

    public static void clearAll() {
        ACTIVE_CLIENTS.clear();
        MOBILE_PLAYERS.clear();
        BADGE_LEVELS.clear();
    }

    public static void addActiveClient(String name) {
        if (name != null && !name.isEmpty()) ACTIVE_CLIENTS.add(name.toLowerCase(Locale.ROOT));
    }

    public static void removeActiveClient(String name) {
        if (name != null && !name.isEmpty()) {
            String k = name.toLowerCase(Locale.ROOT);
            ACTIVE_CLIENTS.remove(k);
            MOBILE_PLAYERS.remove(k);
        }
    }

    public static void addActiveClients(Iterable<String> names) {
        if (names == null) return;
        for (String n : names) addActiveClient(n);
    }

    public static void setMobile(String name, boolean mobile) {
        if (name == null || name.isEmpty()) return;
        String k = name.toLowerCase(Locale.ROOT);
        if (mobile) MOBILE_PLAYERS.add(k);
        else MOBILE_PLAYERS.remove(k);
    }

    public static void setBadgeLevel(String name, int level) {
        if (name == null || name.isEmpty()) return;
        BADGE_LEVELS.put(name.toLowerCase(Locale.ROOT), Math.max(0, Math.min(30, level)));
    }

    public static boolean isClientUser(String name) {
        return name != null && !name.isEmpty() && ACTIVE_CLIENTS.contains(name.toLowerCase(Locale.ROOT));
    }

    public static boolean isMobilePlayer(String name) {
        return name != null && !name.isEmpty() && MOBILE_PLAYERS.contains(name.toLowerCase(Locale.ROOT));
    }

    public static int getBadgeLevel(String name) {
        Integer lvl = BADGE_LEVELS.get(name == null ? "" : name.toLowerCase(Locale.ROOT));
        return lvl != null ? lvl : 0;
    }

    public static boolean hasBadgeData() {
        return !ACTIVE_CLIENTS.isEmpty();
    }

    public static char badgeChar(int level) {
        int clamped = Math.max(0, Math.min(30, level));
        int step = clamped < 5 ? 0 : clamped < 10 ? 5 : clamped < 15 ? 10 : clamped < 20 ? 15 : clamped < 25 ? 20 : 25;
        return (char) (0xE100 + step);
    }

    public static Text buildPrefixedName(Text original, String name) {
        if (original == null || name == null || name.isEmpty()) return null;
        boolean isClient = isClientUser(name);
        boolean isMobile = isMobilePlayer(name);
        if (isMobile && !isClient) isClient = true;
        if (!isClient && !isMobile) return null;
        MutableText out = Text.literal("");
        if (isMobile) {
            out.append(Text.literal(String.valueOf(MOBILE_ICON)).setStyle(Style.EMPTY.withFont(new StyleSpriteSource.Font(BADGE_FONT)).withColor(WHITE)));
            out.append(" ");
        }
        if (isClient) {
            char c = badgeChar(getBadgeLevel(name));
            out.append(Text.literal(String.valueOf(c)).setStyle(Style.EMPTY.withFont(new StyleSpriteSource.Font(BADGE_FONT)).withColor(WHITE)));
            out.append(" ");
        }
        return out.append(original);
    }
}
