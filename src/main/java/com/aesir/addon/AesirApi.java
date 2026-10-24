package com.aesir.addon;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class AesirApi {
    private static final HttpClient HTTP = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build();

    public record PlayerInfo(String uuid, String name, String world, double x, double y, double z, int health, int armor, long lastUpdated) {
    }

    public record TownPolygon(List<List<double[]>> rings) {
    }

    private static final Map<String, String> LEGACY = Map.ofEntries(
        Map.entry("§0", "#000000"), Map.entry("§1", "#0000AA"), Map.entry("§2", "#00AA00"), Map.entry("§3", "#00AAAA"),
        Map.entry("§4", "#AA0000"), Map.entry("§5", "#AA00AA"), Map.entry("§6", "#FFAA00"), Map.entry("§7", "#AAAAAA"),
        Map.entry("§8", "#555555"), Map.entry("§9", "#5555FF"), Map.entry("§a", "#55FF55"), Map.entry("§b", "#55FFFF"),
        Map.entry("§c", "#FF5555"), Map.entry("§d", "#FF55FF"), Map.entry("§e", "#FFFF55"), Map.entry("§f", "#FFFFFF")
    );

    public static String get(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(6))
            .header("Accept", "application/json")
            .header("User-Agent", "AesirAddon/1.0")
            .GET()
            .build();
        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new RuntimeException("HTTP " + response.statusCode());
        return response.body();
    }

    public static List<PlayerInfo> parsePlayers(String json) {
        List<PlayerInfo> out = new ArrayList<>();
        int arr = json.indexOf("\"players\"");
        if (arr < 0) return out;
        int open = json.indexOf('[', arr);
        int close = json.indexOf(']', open);
        if (open < 0 || close < 0) return out;

        String body = json.substring(open + 1, close);
        int i = 0;
        while (i < body.length()) {
            int s = body.indexOf('{', i);
            if (s < 0) break;
            int e = findObjectEnd(body, s);
            if (e < 0) break;

            String obj = body.substring(s, e + 1);
            String uuid = fieldString(obj, "uuid");
            String name = fieldString(obj, "name");
            String world = fieldString(obj, "world");
            if (uuid != null && name != null && world != null) {
                double x = fieldDouble(obj, "x", 0);
                double y = fieldDouble(obj, "y", 64);
                double z = fieldDouble(obj, "z", 0);
                int health = fieldInt(obj, "health", 20);
                int armor = fieldInt(obj, "armor", 0);
                long lastUpdated = fieldLong(obj, "lastUpdated", 0);
                out.add(new PlayerInfo(uuid, name, world, x, y, z, health, armor, lastUpdated));
            }
            i = e + 1;
        }
        return out;
    }

    public static List<TownPolygon> parseMarkers(String json) {
        List<TownPolygon> out = new ArrayList<>();
        int first = json.indexOf('[');
        if (first < 0) return out;
        int last = json.lastIndexOf(']');
        if (last <= first) return out;
        String body = json.substring(first + 1, last);
        int i = 0;
        while (i < body.length()) {
            int s = body.indexOf('{', i);
            if (s < 0) break;
            int e = findObjectEnd(body, s);
            if (e < 0) break;
            String group = body.substring(s, e + 1);
            String markers = arrayField(group, "markers");
            if (markers != null) {
                for (String marker : splitObjects(markers)) {
                    if ("polygon".equals(fieldString(marker, "type"))) {
                        String points = arrayField(marker, "points");
                        if (points != null) {
                            List<List<double[]>> rings = parsePointsArray(points);
                            if (!rings.isEmpty()) out.add(new TownPolygon(rings));
                        }
                    }
                }
            }
            i = e + 1;
        }
        return out;
    }

    public static boolean isInside(double x, double z, List<double[]> ring) {
        boolean inside = false;
        for (int k = 0, prev = ring.size() - 1; k < ring.size(); prev = k++) {
            double[] n = ring.get(k);
            double[] t = ring.get(prev);
            if ((n[1] > z) != (t[1] > z) && x < ((t[0] - n[0]) * (z - n[1])) / (t[1] - n[1]) + n[0]) {
                inside = !inside;
            }
        }
        return inside;
    }

    private static List<List<double[]>> parsePointsArray(String body) {
        List<List<double[]>> rings = new ArrayList<>();
        int i = 0;
        while (i < body.length()) {
            int s = body.indexOf('[', i);
            if (s < 0) break;
            int e = findArrayEnd(body, s);
            if (e < 0) break;
            String ringBody = body.substring(s + 1, e);
            List<double[]> pts = new ArrayList<>();
            int j = 0;
            while (j < ringBody.length()) {
                int os = ringBody.indexOf('{', j);
                if (os < 0) break;
                int oe = findObjectEnd(ringBody, os);
                if (oe < 0) break;
                String p = ringBody.substring(os, oe + 1);
                double x = fieldDouble(p, "x", 0);
                double z = fieldDouble(p, "z", 0);
                pts.add(new double[]{x, z});
                j = oe + 1;
            }
            if (!pts.isEmpty()) rings.add(pts);
            i = e + 1;
        }
        return rings;
    }

    private static List<String> splitObjects(String body) {
        List<String> out = new ArrayList<>();
        int i = 0;
        while (i < body.length()) {
            int s = body.indexOf('{', i);
            if (s < 0) break;
            int e = findObjectEnd(body, s);
            if (e < 0) break;
            out.add(body.substring(s, e + 1));
            i = e + 1;
        }
        return out;
    }

    private static String arrayField(String obj, String key) {
        String pat = "\"" + key + "\":";
        int k = obj.indexOf(pat);
        if (k < 0) return null;
        int start = k + pat.length();
        while (start < obj.length() && Character.isWhitespace(obj.charAt(start))) start++;
        if (start >= obj.length() || obj.charAt(start) != '[') return null;
        int end = findArrayEnd(obj, start);
        if (end < 0) return null;
        return obj.substring(start + 1, end);
    }

    private static int findArrayEnd(String s, int start) {
        int depth = 0;
        boolean inStr = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (c == '\\') i++;
                else if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') inStr = true;
            else if (c == '[') depth++;
            else if (c == ']') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    public static void parseTitles(String json, Map<String, String> colorsOut, Map<String, String> labelsOut) {
        Map<String, String> byIndex = new HashMap<>();
        String titlesObj = objectField(json, "titles");
        if (titlesObj != null) {
            for (Entry e : entries(titlesObj)) byIndex.put(e.key, e.value);
        }
        String playersObj = objectField(json, "players");
        if (playersObj != null) {
            for (Entry e : entries(playersObj)) {
                String raw = byIndex.get(e.value);
                if (raw == null) continue;
                String color = titleColor(raw);
                String label = stripCodes(raw);
                String key = e.key.toLowerCase(Locale.ROOT);
                if (color != null) colorsOut.put(key, color);
                if (label != null && !label.isBlank()) labelsOut.put(key, label);
            }
        }
    }

    public static void parseRanks(String json, Map<String, String> colorsOut, Map<String, String> labelsOut) {
        String playersObj = objectField(json, "players");
        if (playersObj == null) return;
        for (Entry e : entries(playersObj)) {
            String nameRaw = fieldString(e.value, "name");
            if (nameRaw == null) continue;
            String color = legacyColor(nameRaw);
            String label = stripCodes(nameRaw);
            String key = e.key.toLowerCase(Locale.ROOT);
            if (color != null) colorsOut.put(key, color);
            if (label != null && !label.isBlank()) labelsOut.put(key, label);
        }
    }

    public static String hexToColorCode(int r, int g, int b) {
        return String.format("#%02X%02X%02X", r, g, b);
    }

    public static int[] parseHexColor(String hex) {
        if (hex == null || hex.length() != 7 || hex.charAt(0) != '#') return null;
        try {
            int r = Integer.parseInt(hex.substring(1, 3), 16);
            int g = Integer.parseInt(hex.substring(3, 5), 16);
            int b = Integer.parseInt(hex.substring(5, 7), 16);
            return new int[]{r, g, b};
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static String titleColor(String s) {
        if (s.startsWith("&#") && s.length() >= 8 && allHex(s, 2, 8)) return "#" + s.substring(2, 8);
        if (s.startsWith("#") && s.length() >= 7 && allHex(s, 1, 7)) return s.substring(0, 7);
        if (s.startsWith("&") && s.length() >= 2) {
            String c = legacy(s.substring(0, 2));
            if (c != null) return c;
        }
        return null;
    }

    private static String legacyColor(String s) {
        if (s.startsWith("§x")) {
            StringBuilder hex = new StringBuilder("#");
            int i = 2;
            while (i + 1 < s.length() && hex.length() < 7 && s.charAt(i) == '§' && isHex(s.charAt(i + 1))) {
                hex.append(s.charAt(i + 1));
                i += 2;
            }
            if (hex.length() == 7) return hex.toString();
        }
        if (s.startsWith("§")) {
            String c = legacy(s.substring(0, 2));
            if (c != null) return c;
        }
        return null;
    }

    private static String legacy(String code) {
        if (code == null || code.length() != 2) return null;
        return LEGACY.get("§" + code.charAt(1));
    }

    private static String stripCodes(String s) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '&') {
                if (i + 1 < s.length() && s.charAt(i + 1) == '#') {
                    if (i + 8 <= s.length() && allHex(s, i + 2, i + 8)) {
                        i += 8;
                        continue;
                    }
                } else if (i + 1 < s.length() && legacy(s.substring(i, i + 2)) != null) {
                    i += 2;
                    continue;
                }
            } else if (c == '§') {
                if (i + 1 < s.length() && s.charAt(i + 1) == 'x') {
                    i += 14;
                    continue;
                } else if (i + 1 < s.length() && legacy(s.substring(i, i + 2)) != null) {
                    i += 2;
                    continue;
                }
            }
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    private static boolean allHex(String s, int from, int to) {
        if (to > s.length()) return false;
        for (int i = from; i < to; i++) if (!isHex(s.charAt(i))) return false;
        return true;
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    private static String objectField(String json, String key) {
        String pat = "\"" + key + "\":";
        int k = json.indexOf(pat);
        if (k < 0) return null;
        int start = k + pat.length();
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        if (start >= json.length() || json.charAt(start) != '{') return null;
        int end = findObjectEnd(json, start);
        if (end < 0) return null;
        return json.substring(start + 1, end);
    }

    private static String fieldString(String obj, String key) {
        String pat = "\"" + key + "\":";
        int k = obj.indexOf(pat);
        if (k < 0) return null;
        int start = k + pat.length();
        while (start < obj.length() && Character.isWhitespace(obj.charAt(start))) start++;
        if (start >= obj.length() || obj.charAt(start) != '"') return null;
        return readString(obj, start);
    }

    private static double fieldDouble(String obj, String key, double def) {
        String v = fieldNumber(obj, key);
        if (v == null) return def;
        try {
            return Double.parseDouble(v);
        } catch (NumberFormatException ignored) {
            return def;
        }
    }

    private static int fieldInt(String obj, String key, int def) {
        String v = fieldNumber(obj, key);
        if (v == null) return def;
        try {
            return (int) Double.parseDouble(v);
        } catch (NumberFormatException ignored) {
            return def;
        }
    }

    private static long fieldLong(String obj, String key, long def) {
        String v = fieldNumber(obj, key);
        if (v == null) return def;
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException ignored) {
            return def;
        }
    }

    private static String fieldNumber(String obj, String key) {
        String pat = "\"" + key + "\":";
        int k = obj.indexOf(pat);
        if (k < 0) return null;
        int start = k + pat.length();
        while (start < obj.length() && Character.isWhitespace(obj.charAt(start))) start++;
        if (start >= obj.length()) return null;
        char c = obj.charAt(start);
        if (c == '"') {
            String s = readString(obj, start);
            return s.isEmpty() ? null : s;
        }
        if (c == '-' || Character.isDigit(c)) {
            int e = start + 1;
            while (e < obj.length() && (Character.isDigit(obj.charAt(e)) || obj.charAt(e) == '.' || obj.charAt(e) == '-' || obj.charAt(e) == '+' || obj.charAt(e) == 'e' || obj.charAt(e) == 'E')) e++;
            return obj.substring(start, e);
        }
        return null;
    }

    private static List<Entry> entries(String objBody) {
        List<Entry> out = new ArrayList<>();
        int i = 0;
        int n = objBody.length();
        while (i < n) {
            char c = objBody.charAt(i);
            if (c == '"') {
                String key = readString(objBody, i);
                int e = stringEnd(objBody, i);
                int colon = objBody.indexOf(':', e);
                if (colon < 0) break;
                int v = colon + 1;
                while (v < n && Character.isWhitespace(objBody.charAt(v))) v++;
                if (v >= n) break;
                String value;
                if (objBody.charAt(v) == '"') {
                    value = readString(objBody, v);
                    i = stringEnd(objBody, v);
                } else if (objBody.charAt(v) == '{') {
                    int ve = findObjectEnd(objBody, v);
                    if (ve < 0) break;
                    value = objBody.substring(v, ve + 1);
                    i = ve + 1;
                } else {
                    int se = v;
                    while (se < n && (Character.isDigit(objBody.charAt(se)) || objBody.charAt(se) == '-' || objBody.charAt(se) == '+' || objBody.charAt(se) == '.')) se++;
                    value = objBody.substring(v, se);
                    i = se;
                }
                out.add(new Entry(key, value));
            } else {
                i++;
            }
        }
        return out;
    }

    private static int findObjectEnd(String s, int start) {
        int depth = 0;
        boolean inStr = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inStr) {
                if (c == '\\') i++;
                else if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') inStr = true;
            else if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1;
    }

    private static String readString(String s, int start) {
        StringBuilder sb = new StringBuilder();
        int i = start + 1;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\') {
                if (i + 1 < s.length()) sb.append(s.charAt(i + 1));
                i += 2;
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    private static int stringEnd(String s, int start) {
        for (int i = start + 1; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\') i++;
            else if (c == '"') return i + 1;
        }
        return s.length();
    }

    private record Entry(String key, String value) {
    }
}
