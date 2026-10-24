package com.aesir.addon.modules;

import com.aesir.addon.AesirAddon;
import com.aesir.addon.AesirBadges;
import com.aesir.addon.mixin.PlayerListHudAccessor;
import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.packet.BrandCustomPayload;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.network.packet.c2s.common.CustomPayloadC2SPacket;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardEntry;
import net.minecraft.scoreboard.ScoreboardObjective;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class AesirSpoof extends Module implements WebSocket.Listener {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<String> username = sgGeneral.add(new StringSetting.Builder()
        .name("username")
        .description("Aesir account username used to authenticate.")
        .defaultValue("qownz")
        .build()
    );

    private final Setting<String> password = sgGeneral.add(new StringSetting.Builder()
        .name("password")
        .description("Aesir account password used to authenticate.")
        .defaultValue("31")
        .build()
    );

    private final Setting<String> apiUrl = sgGeneral.add(new StringSetting.Builder()
        .name("api-url")
        .description("Base URL of the Aesir auth backend.")
        .defaultValue("http://91.124.63.116:3456/api")
        .build()
    );

    private final Setting<String> wsUrl = sgGeneral.add(new StringSetting.Builder()
        .name("ws-url")
        .description("WebSocket URL. Leave empty to auto-derive from the API URL.")
        .defaultValue("")
        .build()
    );

    private final Setting<Boolean> autoLogin = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-login")
        .description("Automatically run /login <password> when the server asks for the password.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> presence = sgGeneral.add(new BoolSetting.Builder()
        .name("presence")
        .description("Send periodic presence updates to the Aesir backend.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> reportMods = sgGeneral.add(new BoolSetting.Builder()
        .name("report-mods")
        .description("Report the AesirLauncher mod list to the Aesir backend.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> launcherMods = sgGeneral.add(new BoolSetting.Builder()
        .name("launcher-mods")
        .description("Report the mod list that the official AesirLauncher installs, instead of your own installed mods.")
        .defaultValue(true)
        .visible(reportMods::get)
        .build()
    );

    private final Setting<String> contentUrl = sgGeneral.add(new StringSetting.Builder()
        .name("content-url")
        .description("URL of the AesirLauncher content file that defines the official mod list. Leave blank to use the built-in list.")
        .defaultValue("https://storage.aesirmc.com/files/aesircontent.json")
        .visible(() -> reportMods.get() && launcherMods.get())
        .build()
    );

    private final Setting<Boolean> spoofBrand = sgGeneral.add(new BoolSetting.Builder()
        .name("spoof-brand")
        .description("Spoof the client brand as AesirClient instead of Vanilla/Fabric.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showBadges = sgGeneral.add(new BoolSetting.Builder()
        .name("badges")
        .description("Show the AesirClient badge next to the nametag and in the tab list of AesirClient users.")
        .defaultValue(true)
        .build()
    );

    private final Setting<String> brand = sgGeneral.add(new StringSetting.Builder()
        .name("brand")
        .description("The client brand sent to the server when spoofing.")
        .defaultValue("AesirClient")
        .visible(spoofBrand::get)
        .build()
    );

    private final Setting<Boolean> debug = sgGeneral.add(new BoolSetting.Builder()
        .name("debug")
        .description("Log auth network activity to chat.")
        .defaultValue(false)
        .build()
    );

    private ExecutorService executor;
    private ScheduledExecutorService scheduler;
    private HttpClient http;
    private ExecutorService wsExecutor;
    private HttpClient wsHttp;

    private volatile WebSocket ws;
    private volatile String accessToken;
    private volatile long tokenExpiresAt;
    private volatile boolean authed;
    private volatile boolean loginInFlight;
    private volatile boolean connectInFlight;
    private volatile boolean connected;

    private int loginAttempts;
    private int wsAttempts;
    private long lastLoginAttempt;
    private long lastPresence;
    private long lastMods;
    private long lastRoomCheck;
    private long lastAutoLogin;
    private String lastRoom = "";
    private String detectedServer = "default";
    private String detectedSubServer = "default";

    private static final long PRESENCE_INTERVAL = 30000L;
    private static final long MODS_INTERVAL = 300000L;
    private static final long HEARTBEAT_INTERVAL = 25L;

    private static final Set<String> EXCLUDED_MODS = new HashSet<>(Set.of(
        "minecraft", "fabricloader", "java", "aesir-addon", "meteor-client",
        "baritone", "wurst", "impact", "aristois", "bleachhack", "liquidbounce",
        "futureclient", "rusherhack", "inertia", "inertia-client", "kamiblue",
        "kami-blue", "salhack", "seppuku", "lambda", "weave", "doomsday",
        "doomsday-client", "xray", "xraymod", "advanced-xray-fabric",
        "autoclicker", "autofish", "autofarming", "autofarm", "autofarmer",
        "auto-eater", "foodslot", "optimal-eat", "autotools", "invmove"
    ));

    private static final List<String[]> AESIR_LAUNCHER_MODS = List.of(
        new String[]{"aesirmc-client", "Aesir Client"},
        new String[]{"fabric-api", "Fabric API"},
        new String[]{"geckolib", "GeckoLib"},
        new String[]{"sodium", "Sodium"},
        new String[]{"sodium-extra", "Sodium Extra"},
        new String[]{"reeses-sodium-options", "Reese's Sodium Options"},
        new String[]{"iris", "Iris Shaders"},
        new String[]{"entityculling", "Entity Culling"},
        new String[]{"entity-model-features", "[EMF] Entity Model Features"},
        new String[]{"entitytexturefeatures", "[ETF] Entity Texture Features"},
        new String[]{"yacl", "YetAnotherConfigLib"},
        new String[]{"simple-voice-chat", "Simple Voice Chat"},
        new String[]{"appleskin", "AppleSkin"},
        new String[]{"shulkerboxtooltip", "Shulker Box Tooltip"},
        new String[]{"forcecloseworldloadingscreen", "Force Close World Loading Screen"},
        new String[]{"lambdynamiclights", "LambDynamicLights"},
        new String[]{"immediatelyfast", "ImmediatelyFast"},
        new String[]{"ferrite-core", "FerriteCore"},
        new String[]{"lithium", "Lithium"}
    );

    private static final Pattern LOBBY = Pattern.compile("(?:lobi|lobby)\\s*(\\d+)");
    private static final Pattern BOXPVP = Pattern.compile("box\\s*pvp\\s*(\\d+)");
    private static final Pattern EVENT = Pattern.compile("(?:event|etkinlik)\\s*(\\d+)");
    private static final Pattern FACTION = Pattern.compile("factions?\\s*(\\d+)");
    private static final Pattern MMORPG = Pattern.compile("mmorpg\\s*(\\d+)");
    private static final Pattern SKYBLOCK = Pattern.compile("skyblock\\s*(\\d+)");
    private static final Pattern ASMP = Pattern.compile("asmp\\s*(\\d+)");
    private static final Pattern TOWNY = Pattern.compile("towny");

    public AesirSpoof() {
        super(AesirAddon.CATEGORY, "aesir-spoof", "Authenticates with the Aesir backend and mimics the official AesirClient traffic (login, WebSocket presence, mod reports, brand spoof, auto-login).");
    }

    public static boolean badgesEnabled() {
        AesirSpoof mod = meteordevelopment.meteorclient.systems.modules.Modules.get().get(AesirSpoof.class);
        return mod != null && mod.isActive() && mod.showBadges.get();
    }

    @Override
    public void onActivate() {
        executor = Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r, "AesirAuth");
            t.setDaemon(true);
            return t;
        });
        scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "AesirAuthSched");
            t.setDaemon(true);
            return t;
        });
        http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .executor(executor)
            .build();
        wsExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "AesirWsConn");
            t.setDaemon(true);
            return t;
        });
        wsHttp = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(20))
            .executor(wsExecutor)
            .build();
        resetState();
        AesirBadges.clearAll();
    }

    @Override
    public void onDeactivate() {
        sendLeave();
        closeWs();
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        if (wsExecutor != null) {
            wsExecutor.shutdownNow();
            wsExecutor = null;
        }
        http = null;
        wsHttp = null;
        resetState();
        AesirBadges.clearAll();
    }

    private void resetState() {
        accessToken = null;
        tokenExpiresAt = 0;
        authed = false;
        loginInFlight = false;
        connectInFlight = false;
        connected = false;
        loginAttempts = 0;
        wsAttempts = 0;
        lastLoginAttempt = 0;
        lastPresence = 0;
        lastMods = 0;
        lastRoomCheck = 0;
        lastRoom = "";
        detectedServer = "default";
        detectedSubServer = "default";
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        if (mc.world == null || mc.player == null) return;
        if (debug.get()) info("Joined server, starting Aesir auth");
        detectedServer = detectServer();
        detectedSubServer = detectSubServer();
        tryLogin();
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        sendLeave();
        closeWs();
        resetState();
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;
        long now = System.currentTimeMillis();

        if (now - lastRoomCheck > 2000) {
            lastRoomCheck = now;
            String sub = detectSubServer();
            if (!sub.equals(detectedSubServer)) {
                detectedSubServer = sub;
                if (connected && authed) sendJoinRoom();
            }
            String srv = detectServer();
            if (!srv.equals(detectedServer)) {
                detectedServer = srv;
                if (connected && authed) sendJoinRoom();
            }
        }

        if (!authed && !loginInFlight && now - lastLoginAttempt > 2000 && loginAttempts < 6) {
            tryLogin();
        }

        if (!authed) return;

        if (presence.get() && now - lastPresence >= PRESENCE_INTERVAL + (long) (Math.random() * 1500)) {
            lastPresence = now;
            sendPresence();
        }

        if (reportMods.get() && now - lastMods >= MODS_INTERVAL) {
            lastMods = now;
            reportMods();
        }
    }

    @EventHandler
    private void onReceiveMessage(ReceiveMessageEvent event) {
        if (!autoLogin.get()) return;
        String msg = event.getMessage().getString();
        if (msg == null || msg.isEmpty()) return;
        long now = System.currentTimeMillis();
        if (now - lastAutoLogin < 4000) return;
        if (looksLikeLoginPrompt(msg)) {
            lastAutoLogin = now;
            String pass = password.get();
            if (pass != null && !pass.isBlank()) {
                if (debug.get()) info("Auto-login detected, sending /login");
                mc.getNetworkHandler().sendChatCommand("login " + pass);
            } else if (debug.get()) {
                info("Auto-login detected but no password is set");
            }
        }
    }

    @EventHandler
    private void onPacketSend(PacketEvent.Send event) {
        if (!spoofBrand.get()) return;
        if (event.packet instanceof CustomPayloadC2SPacket packet) {
            CustomPayload payload = packet.payload();
            if (payload.getId() != null && payload.getId().equals(BrandCustomPayload.ID)) {
                event.cancel();
                event.sendSilently(new CustomPayloadC2SPacket(new BrandCustomPayload(brand.get())));
            }
        }
    }

    private void tryLogin() {
        if (loginInFlight || mc == null) return;
        if (isTokenFresh()) {
            authed = true;
            connectWs();
            return;
        }
        loginInFlight = true;
        lastLoginAttempt = System.currentTimeMillis();
        String user = username.get();
        String pass = password.get();
        if (user == null || user.isEmpty()) {
            loginInFlight = false;
            return;
        }
        runAsync(() -> {
            try {
                String body = "{\"username\":\"" + jsonEscape(user) + "\",\"password\":\"" + jsonEscape(pass == null ? "" : pass) + "\"}";
                String resp = post("/auth/login", body, null);
                String token = extractAccessToken(resp);
                long expiresIn = extractExpiresIn(resp);
                if (token != null && !token.isEmpty()) {
                    accessToken = token;
                    tokenExpiresAt = System.currentTimeMillis() + Math.max(0, expiresIn) * 1000;
                    loginAttempts = 0;
                    authed = true;
                    AesirBadges.addActiveClient(user);
                    if (debug.get()) info("Login OK, token expires in " + expiresIn + "s");
                    connectWs();
                } else {
                    authed = false;
                    loginAttempts++;
                    if (debug.get()) warning("Login failed (attempt " + loginAttempts + "): " + resp);
                }
            } catch (Exception e) {
                authed = false;
                loginAttempts++;
                if (debug.get()) warning("Login error (attempt " + loginAttempts + "): " + e.getMessage());
            } finally {
                loginInFlight = false;
            }
        });
    }

    private boolean isTokenFresh() {
        return accessToken != null && !accessToken.isEmpty() && System.currentTimeMillis() < tokenExpiresAt - 120000;
    }

    private void connectWs() {
        if (connectInFlight || ws != null) return;
        connectInFlight = true;
        String token = accessToken;
        String user = username.get();
        if (token == null || token.isEmpty()) {
            connectInFlight = false;
            return;
        }
        HttpClient h = wsHttp;
        ExecutorService wse = wsExecutor;
        if (h == null || wse == null) {
            connectInFlight = false;
            return;
        }
        try {
            String base = buildWsBase();
            String url = base + "/ws?token=" + enc(token) + "&username=" + enc(user == null ? "" : user);
            h.newWebSocketBuilder()
                .header("Authorization", "Bearer " + token)
                .header("X-Username", user == null ? "" : user)
                .buildAsync(URI.create(url), this)
                .whenComplete((w, t) -> {
                    try {
                        if (t == null && w != null) {
                            ws = w;
                            wsAttempts = 0;
                        } else {
                            if (debug.get()) warning("WebSocket connect error: " + (t == null ? "null socket" : t));
                            closeWs();
                            scheduleWsReconnect();
                        }
                    } finally {
                        connectInFlight = false;
                    }
                });
        } catch (Exception e) {
            if (debug.get()) warning("WebSocket connect error: " + e);
            connectInFlight = false;
            closeWs();
            scheduleWsReconnect();
        }
    }

    private void scheduleWsReconnect() {
        if (mc == null) return;
        long delay = wsReconnectDelay();
        if (debug.get()) info("WebSocket reconnect in " + delay + "s (attempt " + wsAttempts + ")");
        schedule(this::reconnectWs, delay, TimeUnit.SECONDS);
    }

    private void reconnectWs() {
        if (mc == null || !isActive() || executor == null || scheduler == null) return;
        wsAttempts++;
        closeWs();
        connectWs();
    }

    private long wsReconnectDelay() {
        long base = 10L * (1L << Math.min(wsAttempts, 5));
        long jitter = (long) (Math.random() * base * 0.2);
        return Math.min(300, base + jitter);
    }

    private void closeWs() {
        WebSocket w = ws;
        ws = null;
        connected = false;
        if (w != null) {
            try {
                w.sendClose(1000, "Client shutting down");
            } catch (Exception ignored) {
            }
        }
    }

    private void sendJoinRoom() {
        WebSocket w = ws;
        if (w == null || w.isOutputClosed() || !authed) return;
        String room = detectedServer + ":" + detectedSubServer;
        if (room.equals(lastRoom)) return;
        lastRoom = room;
        try {
            w.sendText("{\"type\":\"join_room\",\"room\":\"" + room.replace("\"", "") + "\"}", true);
            if (debug.get()) info("WS join_room: " + room);
        } catch (Exception ignored) {
        }
    }

    private void sendHeartbeat() {
        WebSocket w = ws;
        if (w == null || w.isOutputClosed() || !authed) return;
        try {
            w.sendText("{\"type\":\"heartbeat\"}", true);
        } catch (Exception ignored) {
        }
    }

    private void sendPresence() {
        if (!authed || accessToken == null) return;
        runAsync(() -> {
            try {
                String body = "{\"server\":\"" + jsonEscape(detectedServer) + "\",\"subServer\":\"" + jsonEscape(detectedSubServer) + "\"}";
                post("/presence/update", body, "X-Aesir-Client", "1");
            } catch (Exception ignored) {
            }
        });
    }

    private void reportMods() {
        if (!authed || accessToken == null) return;
        String user = username.get();
        if (user == null || user.isEmpty()) return;
        List<String[]> mods = launcherMods.get() ? launcherModList() : installedModList();
        StringBuilder sb = new StringBuilder("{\"mods\":[");
        for (int i = 0; i < mods.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append("{\"id\":\"").append(jsonEscape(mods.get(i)[0])).append("\"");
            if (mods.get(i)[1] != null && !mods.get(i)[1].isEmpty()) {
                sb.append(",\"name\":\"").append(jsonEscape(mods.get(i)[1])).append("\"");
            }
            sb.append('}');
        }
        sb.append("]}");
        runAsync(() -> {
            try {
                post("/players/" + enc(user) + "/mods", sb.toString(), null);
            } catch (Exception ignored) {
            }
        });
    }

    private List<String[]> installedModList() {
        List<String[]> mods = new ArrayList<>();
        try {
            FabricLoader.getInstance().getAllMods().forEach(mod -> {
                String id = mod.getMetadata().getId().toLowerCase(Locale.ROOT);
                if (EXCLUDED_MODS.contains(id)) return;
                String name = null;
                try {
                    name = mod.getMetadata().getName();
                } catch (Throwable ignored) {
                }
                mods.add(new String[]{id, name});
            });
        } catch (Throwable ignored) {
        }
        return mods;
    }

    private volatile List<String[]> launcherModsCache;
    private volatile long launcherModsFetched;

    private List<String[]> launcherModList() {
        refreshLauncherModList();
        List<String[]> cached = launcherModsCache;
        return cached != null && !cached.isEmpty() ? cached : AESIR_LAUNCHER_MODS;
    }

    private void refreshLauncherModList() {
        String url = contentUrl.get();
        if (url == null || url.isBlank()) return;
        long now = System.currentTimeMillis();
        if (launcherModsCache != null && now - launcherModsFetched < 3600000) return;
        runAsync(() -> {
            try {
                String body = get(url);
                List<String[]> parsed = parseAesirContent(body);
                if (parsed != null && !parsed.isEmpty()) {
                    launcherModsCache = parsed;
                    launcherModsFetched = System.currentTimeMillis();
                    if (debug.get()) info("Loaded official AesirLauncher mod list (" + parsed.size() + " mods)");
                }
            } catch (Exception ignored) {
            }
        });
    }

    private String get(String url) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(10))
            .header("User-Agent", "AesirLauncher/5.0")
            .GET()
            .build();
        HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (resp.statusCode() != 200) throw new RuntimeException("HTTP " + resp.statusCode());
        return resp.body();
    }

    private static List<String[]> parseAesirContent(String body) {
        List<String[]> out = new ArrayList<>();
        for (String key : new String[]{"forcedMods", "optionalMods"}) {
            String arr = arrayField(body, key);
            if (arr == null) continue;
            int i = 0;
            while (i < arr.length()) {
                int s = arr.indexOf('{', i);
                if (s < 0) break;
                int e = objectEnd(arr, s);
                if (e < 0) break;
                String obj = arr.substring(s, e + 1);
                String id = stringField(obj, "id");
                String name = stringField(obj, "name");
                if (id != null && !id.isEmpty()) {
                    out.add(new String[]{id, name});
                }
                i = e + 1;
            }
        }
        return out;
    }

    private static String arrayField(String s, String key) {
        String pat = "\"" + key + "\":";
        int k = s.indexOf(pat);
        if (k < 0) return null;
        int start = k + pat.length();
        while (start < s.length() && Character.isWhitespace(s.charAt(start))) start++;
        if (start >= s.length() || s.charAt(start) != '[') return null;
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
                if (depth == 0) return s.substring(start + 1, i);
            }
        }
        return null;
    }

    private static int objectEnd(String s, int start) {
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

    private static String stringField(String s, String key) {
        String pat = "\"" + key + "\":";
        int k = s.indexOf(pat);
        if (k < 0) return null;
        int start = k + pat.length();
        while (start < s.length() && Character.isWhitespace(s.charAt(start))) start++;
        if (start >= s.length() || s.charAt(start) != '"') return null;
        StringBuilder sb = new StringBuilder();
        int i = start + 1;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                sb.append(s.charAt(i + 1));
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

    private static int intField(String s, String key) {
        String pat = "\"" + key + "\":";
        int k = s.indexOf(pat);
        if (k < 0) return 0;
        int start = k + pat.length();
        while (start < s.length() && Character.isWhitespace(s.charAt(start))) start++;
        int end = start;
        while (end < s.length() && (Character.isDigit(s.charAt(end)) || s.charAt(end) == '-')) end++;
        if (end <= start) return 0;
        try {
            return Integer.parseInt(s.substring(start, end));
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static List<String> stringArrayField(String s, String key) {
        List<String> out = new ArrayList<>();
        String arr = arrayField(s, key);
        if (arr == null) return out;
        int i = 0;
        while (i < arr.length()) {
            int q = arr.indexOf('"', i);
            if (q < 0) break;
            int qe = arr.indexOf('"', q + 1);
            if (qe < 0) break;
            out.add(arr.substring(q + 1, qe));
            i = qe + 1;
        }
        return out;
    }

    private static String objectField(String s, String key) {
        String pat = "\"" + key + "\":";
        int k = s.indexOf(pat);
        if (k < 0) return null;
        int start = k + pat.length();
        while (start < s.length() && Character.isWhitespace(s.charAt(start))) start++;
        if (start >= s.length() || s.charAt(start) != '{') return null;
        int end = objectEnd(s, start);
        if (end < 0) return null;
        return s.substring(start + 1, end);
    }

    private void runAsync(Runnable r) {
        ExecutorService ex = executor;
        if (ex == null || ex.isShutdown() || r == null) return;
        try {
            ex.execute(r);
        } catch (RejectedExecutionException ignored) {
        }
    }

    private boolean schedule(Runnable r, long delay, TimeUnit unit) {
        ScheduledExecutorService sc = scheduler;
        if (sc == null || sc.isShutdown() || r == null) return false;
        try {
            sc.schedule(r, delay, unit);
            return true;
        } catch (RejectedExecutionException ignored) {
            return false;
        }
    }

    private void sendLeave() {
        if (!authed || accessToken == null || executor == null) return;
        String token = accessToken;
        runAsync(() -> {
            try {
                post("/presence/leave", "{}", token, null, null);
            } catch (Exception ignored) {
            }
        });
    }

    private String post(String path, String jsonBody, String headerName, String headerValue) throws Exception {
        return post(path, jsonBody, accessToken, headerName, headerValue);
    }

    private String post(String path, String jsonBody, String token, String headerName, String headerValue) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(apiUrl.get().replaceAll("/+$", "") + path))
            .timeout(Duration.ofSeconds(10))
            .header("Content-Type", "application/json")
            .header("User-Agent", "AesirClient");
        if (token != null && !token.isEmpty()) {
            b.header("Authorization", "Bearer " + token);
        }
        if (headerName != null && headerValue != null) {
            b.header(headerName, headerValue);
        }
        b.POST(HttpRequest.BodyPublishers.ofString(jsonBody));
        HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        return resp.body();
    }

    private String post(String path, String jsonBody, String headerName) throws Exception {
        return post(path, jsonBody, headerName, "1");
    }

    private String buildWsBase() {
        String ws = wsUrl.get();
        if (ws != null && !ws.isBlank()) {
            return ws.replaceAll("/+$", "");
        }
        String api = apiUrl.get();
        try {
            URI u = URI.create(api);
            String scheme = "https".equalsIgnoreCase(u.getScheme()) ? "wss" : "ws";
            int port = u.getPort();
            if (port == -1) {
                port = "https".equalsIgnoreCase(u.getScheme()) ? 443 : 80;
            }
            port += 1;
            return scheme + "://" + u.getHost() + ":" + port;
        } catch (Exception e) {
            return "ws://91.124.63.116:3457";
        }
    }

    private String detectServer() {
        try {
            if (mc.world == null) return "local";
            if (mc.getCurrentServerEntry() != null && mc.getCurrentServerEntry().address != null) {
                String host = mc.getCurrentServerEntry().address.split(":")[0].toLowerCase(Locale.ROOT);
                if (host.contains("|")) host = host.split("\\|")[0];
                if (host.endsWith("aesirmc.com") || host.equals("aesirmc") || host.contains("aesir")) {
                    return "aesirmc";
                }
            }
            try {
                for (ScoreboardObjective obj : mc.world.getScoreboard().getObjectives()) {
                    if (obj == null) continue;
                    if (isAesirText(obj.getDisplayName().getString()) || isAesirText(obj.getName())) {
                        return "aesirmc";
                    }
                }
            } catch (Throwable ignored) {
            }
            try {
                if (mc.inGameHud != null && mc.inGameHud.getPlayerListHud() != null) {
                    PlayerListHudAccessor tab = (PlayerListHudAccessor) mc.inGameHud.getPlayerListHud();
                    if (tab != null) {
                        if (isAesirText(tab.aesir$getHeader() == null ? "" : tab.aesir$getHeader().getString())
                                || isAesirText(tab.aesir$getFooter() == null ? "" : tab.aesir$getFooter().getString())) {
                            return "aesirmc";
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            if (mc.getCurrentServerEntry() != null && mc.getCurrentServerEntry().address != null) {
                return mc.getCurrentServerEntry().address.split(":")[0].toLowerCase(Locale.ROOT).replace("|", "");
            }
            return "local";
        } catch (Exception e) {
            return "local";
        }
    }

    private String detectSubServer() {
        if (mc.world == null) return "default";
        try {
            Scoreboard sb = mc.world.getScoreboard();
            ScoreboardObjective obj = sb.getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);
            if (obj != null) {
                String s = matchSubServer(obj.getDisplayName().getString());
                if (s != null) return s;
                for (ScoreboardEntry entry : sb.getScoreboardEntries(obj)) {
                    s = matchSubServer(entry.name().getString());
                    if (s != null) return s;
                }
            }
        } catch (Exception ignored) {
        }
        return "default";
    }

    private String matchSubServer(String raw) {
        if (raw == null || raw.isEmpty()) return null;
        String s = normalize(raw);
        Matcher m;
        if ((m = LOBBY.matcher(s)).find()) return "lobby" + m.group(1);
        if ((m = BOXPVP.matcher(s)).find()) return "boxpvp" + m.group(1);
        if ((m = EVENT.matcher(s)).find()) return "event" + m.group(1);
        if ((m = FACTION.matcher(s)).find()) return "faction" + m.group(1);
        if ((m = MMORPG.matcher(s)).find()) return "mmorpg" + m.group(1);
        if ((m = SKYBLOCK.matcher(s)).find()) return "skyblock" + m.group(1);
        if ((m = ASMP.matcher(s)).find()) return "asmp" + m.group(1);
        if (TOWNY.matcher(s).find()) return "towny";
        if (s.contains("box pvp") || s.contains("boxpvp")) return "boxpvp";
        if (s.contains("mmorpg 2") || s.contains("mmorpg2")) return "mmorpg2";
        if (s.contains("mmorpg 1") || s.contains("mmorpg1")) return "mmorpg1";
        if (s.contains("faction 2") || s.contains("factions 2")) return "faction2";
        return null;
    }

    private String normalize(String s) {
        String n = Normalizer.normalize(s, Normalizer.Form.NFKD).toLowerCase(Locale.ROOT);
        n = n.replace('\u015f', 's').replace('\u0131', 'i').replace('\u011f', 'g')
             .replace('\u00fc', 'u').replace('\u00f6', 'o').replace('\u00e7', 'c')
             .replace("\u0307", "");
        return n;
    }

    private static boolean isAesirText(String s) {
        if (s == null || s.isEmpty()) return false;
        String n = Normalizer.normalize(s, Normalizer.Form.NFKD).toLowerCase(Locale.ROOT);
        return n.contains("aesir");
    }

    private boolean looksLikeLoginPrompt(String msg) {
        String s = normalize(msg);
        if (!s.contains("/login")) return false;
        if (!s.contains("sifre") && !s.contains("password")) return false;
        if (!s.contains("lutfen") && !s.contains("giris") && !"aesirmc".equals(detectedServer)) return false;
        return true;
    }

    private static String extractAccessToken(String resp) {
        return extractField(resp, "accessToken");
    }

    private static long extractExpiresIn(String resp) {
        String v = extractField(resp, "expiresIn");
        if (v == null) return 0;
        try {
            return (long) Double.parseDouble(v);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String extractField(String json, String key) {
        String pat = "\"" + key + "\":";
        int k = json.indexOf(pat);
        if (k < 0) return null;
        int start = k + pat.length();
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        if (start >= json.length()) return null;
        char c = json.charAt(start);
        if (c == '"') {
            StringBuilder sb = new StringBuilder();
            int i = start + 1;
            while (i < json.length()) {
                char ch = json.charAt(i);
                if (ch == '\\' && i + 1 < json.length()) {
                    sb.append(json.charAt(i + 1));
                    i += 2;
                } else if (ch == '"') {
                    break;
                } else {
                    sb.append(ch);
                    i++;
                }
            }
            return sb.toString();
        }
        int e = start;
        while (e < json.length() && (Character.isDigit(json.charAt(e)) || json.charAt(e) == '.' || json.charAt(e) == '-')) e++;
        return json.substring(start, e);
    }

    private static String jsonEscape(String s) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String enc(String s) {
        if (s == null) return "";
        try {
            return URLEncoder.encode(s, StandardCharsets.UTF_8);
        } catch (Exception e) {
            return s;
        }
    }

    @Override
    public void onOpen(WebSocket webSocket) {
        connected = true;
        ws = webSocket;
        webSocket.request(1);
        sendHeartbeat();
        sendJoinRoom();
        ScheduledExecutorService sc = scheduler;
        if (sc != null && !sc.isShutdown()) {
            try {
                sc.scheduleAtFixedRate(() -> {
                    if (isActive() && !sc.isShutdown()) {
                        sendHeartbeat();
                        sendJoinRoom();
                    }
                }, HEARTBEAT_INTERVAL, HEARTBEAT_INTERVAL, TimeUnit.SECONDS);
            } catch (RejectedExecutionException ignored) {
            }
        }
        if (debug.get()) info("WebSocket connected");
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
        webSocket.request(1);
        String msg = data.toString();
        if (showBadges.get()) {
            handleWsMessage(msg);
        }
        return null;
    }

    private void handleWsMessage(String msg) {
        try {
            String type = stringField(msg, "type");
            if (type == null) return;
            switch (type) {
                case "room_joined" -> {
                    List<String> members = stringArrayField(msg, "members");
                    if (debug.get()) info("WS room_joined: " + members.size() + " members");
                    if (!members.isEmpty()) {
                        AesirBadges.addActiveClients(members);
                        fetchBadges(members);
                    }
                    List<String> mobiles = stringArrayField(msg, "mobiles");
                    if (!mobiles.isEmpty()) {
                        for (String m : mobiles) AesirBadges.setMobile(m, true);
                    }
                }
                case "player_joined" -> {
                    String u = stringField(msg, "username");
                    if (u != null && !u.isEmpty()) AesirBadges.addActiveClient(u);
                }
                case "player_left" -> {
                    String u = stringField(msg, "username");
                    if (u != null && !u.isEmpty()) AesirBadges.removeActiveClient(u);
                }
                case "badge_updated" -> {
                    String u = stringField(msg, "username");
                    if (u != null && !u.isEmpty()) AesirBadges.setBadgeLevel(u, intField(msg, "level"));
                }
                case "platform_updated" -> {
                    String u = stringField(msg, "username");
                    String platform = stringField(msg, "platform");
                    if (u != null && !u.isEmpty()) AesirBadges.setMobile(u, "mobile".equalsIgnoreCase(platform));
                }
                default -> {
                }
            }
        } catch (Exception ignored) {
        }
    }

    private void fetchBadges(List<String> members) {
        if (members == null || members.isEmpty() || accessToken == null) return;
        List<String> batch = members.size() > 200 ? members.subList(0, 200) : members;
        StringBuilder sb = new StringBuilder("{\"usernames\":[");
        for (int i = 0; i < batch.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(jsonEscape(batch.get(i))).append('"');
        }
        sb.append("]}");
        runAsync(() -> {
            try {
                String resp = post("/badges/batch", sb.toString(), null);
                String levels = objectField(resp, "levels");
                if (levels == null) return;
                int i = 0;
                while (i < levels.length()) {
                    int k = levels.indexOf('"', i);
                    if (k < 0) break;
                    int kEnd = levels.indexOf('"', k + 1);
                    if (kEnd < 0) break;
                    String name = levels.substring(k + 1, kEnd);
                    int colon = levels.indexOf(':', kEnd);
                    if (colon < 0) break;
                    int vStart = colon + 1;
                    while (vStart < levels.length() && Character.isWhitespace(levels.charAt(vStart))) vStart++;
                    int vEnd = vStart;
                    while (vEnd < levels.length() && (Character.isDigit(levels.charAt(vEnd)) || levels.charAt(vEnd) == '-')) vEnd++;
                    if (vEnd > vStart) {
                        try {
                            AesirBadges.setBadgeLevel(name, Integer.parseInt(levels.substring(vStart, vEnd)));
                        } catch (NumberFormatException ignored) {
                        }
                    }
                    i = vEnd;
                }
            } catch (Exception ignored) {
            }
        });
    }

    @Override
    public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
        webSocket.request(1);
        return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
        connected = false;
        if (debug.get()) warning("WebSocket error: " + error.getMessage());
        closeWs();
        scheduleWsReconnect();
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
        connected = false;
        if (debug.get()) info("WebSocket closed (" + statusCode + ")");
        closeWs();
        scheduleWsReconnect();
        return null;
    }
}
