package com.aesir.addon.modules;

import com.aesir.addon.AesirAddon;
import com.aesir.addon.AesirApi;
import meteordevelopment.meteorclient.events.render.Render2DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.Renderer2D;
import meteordevelopment.meteorclient.renderer.text.TextRenderer;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.config.Config;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.NametagUtils;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import org.joml.Vector3d;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static meteordevelopment.meteorclient.MeteorClient.mc;

public class AesirRadar extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRender = settings.createGroup("Render");

    private final Setting<String> apiUrl = sgGeneral.add(new StringSetting.Builder()
        .name("api-url")
        .description("Base URL of the Aesir web API.")
        .defaultValue("https://towny.aesirmc.com")
        .build()
    );

    private final Setting<Integer> range = sgGeneral.add(new IntSetting.Builder()
        .name("range")
        .description("Only show players within this many blocks.")
        .defaultValue(5000)
        .min(100)
        .sliderRange(100, 20000)
        .build()
    );

    private final Setting<Integer> updateInterval = sgGeneral.add(new IntSetting.Builder()
        .name("update-interval")
        .description("How often to fetch player positions from the API, in milliseconds.")
        .defaultValue(500)
        .min(250)
        .sliderRange(250, 5000)
        .build()
    );

    private final Setting<String> world = sgGeneral.add(new StringSetting.Builder()
        .name("world")
        .description("Which world to track. 'auto' maps from your dimension (overworld -> dunya, nether -> dunya_nether, end -> dunya_the_end). Any other value is used as the exact world name.")
        .defaultValue("auto")
        .build()
    );

    private final Setting<Boolean> ignoreSelf = sgGeneral.add(new BoolSetting.Builder()
        .name("ignore-self")
        .description("Don't show your own nametag.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> onlyOutsideTowns = sgGeneral.add(new BoolSetting.Builder()
        .name("only-outside-towns")
        .description("Only show players who are outside of any town borders.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> outsideTownColor = sgGeneral.add(new BoolSetting.Builder()
        .name("outside-town-color")
        .description("Color players who are outside of any town borders. The color changes back to normal when they enter a town.")
        .defaultValue(false)
        .build()
    );

    private final Setting<SettingColor> outsideTownColorValue = sgGeneral.add(new ColorSetting.Builder()
        .name("outside-town-color-value")
        .description("Color used for players who are outside of town borders.")
        .defaultValue(new SettingColor(120, 255, 180))
        .visible(outsideTownColor::get)
        .build()
    );

    private final Setting<Boolean> showDistance = sgGeneral.add(new BoolSetting.Builder()
        .name("distance")
        .description("Show the distance to the player.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showHealth = sgGeneral.add(new BoolSetting.Builder()
        .name("health")
        .description("Show the player's health.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> showArmor = sgGeneral.add(new BoolSetting.Builder()
        .name("armor")
        .description("Show the player's armor value (same number shown on the web map).")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> rankColor = sgGeneral.add(new BoolSetting.Builder()
        .name("rank-color")
        .description("Color the name with the player's rank/title color from the API.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> rankName = sgGeneral.add(new BoolSetting.Builder()
        .name("rank-name")
        .description("Prefix the player's rank/title name. May show unsupported characters.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> scale = sgRender.add(new DoubleSetting.Builder()
        .name("scale")
        .description("Scale of the nametag.")
        .defaultValue(1.1)
        .min(0.1)
        .sliderRange(0.1, 5)
        .build()
    );

    private final Setting<SettingColor> textColor = sgRender.add(new ColorSetting.Builder()
        .name("text-color")
        .description("Color of the name when no rank color is available.")
        .defaultValue(new SettingColor(255, 255, 255))
        .build()
    );

    private final Setting<SettingColor> distanceColor = sgRender.add(new ColorSetting.Builder()
        .name("distance-color")
        .description("Color of the distance text.")
        .defaultValue(new SettingColor(120, 200, 255))
        .build()
    );

    private final Setting<SettingColor> healthColor = sgRender.add(new ColorSetting.Builder()
        .name("health-color")
        .description("Color of the health text.")
        .defaultValue(new SettingColor(80, 255, 120))
        .build()
    );

    private final Setting<SettingColor> armorColor = sgRender.add(new ColorSetting.Builder()
        .name("armor-color")
        .description("Color of the armor text.")
        .defaultValue(new SettingColor(200, 200, 200))
        .build()
    );

    private final Setting<Boolean> background = sgRender.add(new BoolSetting.Builder()
        .name("background")
        .description("Draw a dark background behind the nametag.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> backgroundColor = sgRender.add(new ColorSetting.Builder()
        .name("background-color")
        .description("Color of the nametag background.")
        .defaultValue(new SettingColor(0, 0, 0, 75))
        .visible(background::get)
        .build()
    );

    private final ConcurrentHashMap<String, AesirApi.PlayerInfo> players = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> titleColors = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> titleLabels = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> rankColors = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> rankLabels = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, List<Ring>> townRings = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Boolean> inTown = new ConcurrentHashMap<>();

    private ExecutorService executor;
    private volatile boolean playersInFlight;
    private volatile boolean markersInFlight;
    private volatile String ownUuid;
    private volatile String markersWorld;
    private long lastPlayersFetch;
    private long lastMetaFetch;
    private long lastMarkersFetch;

    private record Tracked(String name, double x, double y, double z, int health, int armor, String label, String colorHex, double dist) {
    }

    private static final class Ring {
        final double minX, maxX, minZ, maxZ;
        final List<double[]> points;

        Ring(List<double[]> points) {
            this.points = points;
            double loX = Double.MAX_VALUE, hiX = -Double.MAX_VALUE, loZ = Double.MAX_VALUE, hiZ = -Double.MAX_VALUE;
            for (double[] p : points) {
                loX = Math.min(loX, p[0]);
                hiX = Math.max(hiX, p[0]);
                loZ = Math.min(loZ, p[1]);
                hiZ = Math.max(hiZ, p[1]);
            }
            this.minX = loX;
            this.maxX = hiX;
            this.minZ = loZ;
            this.maxZ = hiZ;
        }
    }

    public AesirRadar() {
        super(AesirAddon.CATEGORY, "aesir-radar", "Shows real-time nametags for players tracked by the Aesir towny web map.");
    }

    @Override
    public void onActivate() {
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "Aesir-API");
            t.setDaemon(true);
            return t;
        });
        playersInFlight = false;
        markersInFlight = false;
        ownUuid = null;
        lastPlayersFetch = 0;
        lastMetaFetch = 0;
        lastMarkersFetch = 0;
        townRings.clear();
        inTown.clear();
    }

    @Override
    public void onDeactivate() {
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
        players.clear();
        townRings.clear();
        inTown.clear();
        markersWorld = null;
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null || executor == null) return;

        long now = System.currentTimeMillis();
        if (now - lastPlayersFetch >= updateInterval.get()) {
            lastPlayersFetch = now;
            fetchPlayers();
        }
        if (now - lastMetaFetch >= 60000) {
            lastMetaFetch = now;
            fetchRanks();
            fetchTitles();
        }
        if (ownUuid == null || !ownUuid.equals(mc.player.getUuid().toString().replace("-", "").toLowerCase(Locale.ROOT))) {
            ownUuid = mc.player.getUuid().toString().replace("-", "").toLowerCase(Locale.ROOT);
        }
        if (needsMarkers()) {
            String w = currentWorld();
            if (w != null && (!w.equals(markersWorld) || now - lastMarkersFetch >= 1800000)) {
                markersWorld = w;
                lastMarkersFetch = now;
                fetchMarkers(w);
            }
        }
    }

    private boolean needsMarkers() {
        return onlyOutsideTowns.get() || outsideTownColor.get();
    }
    @EventHandler
    private void onRender2D(Render2DEvent event) {
        if (mc.world == null || mc.player == null) return;

        String worldName = currentWorld();
        if (worldName == null) return;

        double px = mc.player.getX();
        double py = mc.player.getY();
        double pz = mc.player.getZ();
        long now = System.currentTimeMillis();

        List<Tracked> list = new ArrayList<>();
        for (AesirApi.PlayerInfo p : players.values()) {
            if (!p.world().equals(worldName)) continue;
            if (now - p.lastUpdated() > 60000) continue;
            if (ignoreSelf.get() && p.uuid().equals(ownUuid)) continue;
            boolean insideTown = Boolean.TRUE.equals(inTown.get(p.uuid()));
            if (onlyOutsideTowns.get() && insideTown) continue;

            double dx = p.x() - px;
            double dy = p.y() - py;
            double dz = p.z() - pz;
            double dist = Math.sqrt(dx * dx + dy * dy + dz * dz);
            if (dist > range.get()) continue;

            String key = p.name().toLowerCase(Locale.ROOT);
            String label = rankName.get() ? (titleLabels.getOrDefault(key, rankLabels.get(key))) : null;
            String colorHex = rankColor.get() ? (titleColors.getOrDefault(key, rankColors.get(key))) : null;
            if (outsideTownColor.get() && !insideTown) {
                SettingColor c = outsideTownColorValue.get();
                colorHex = String.format("#%02X%02X%02X", c.r, c.g, c.b);
            }

            list.add(new Tracked(p.name(), p.x() + 0.5, p.y() + 2.2, p.z() + 0.5, p.health(), p.armor(), label, colorHex, dist));
        }
        if (list.isEmpty()) return;

        list.sort(Comparator.comparingDouble(Tracked::dist));
        boolean shadow = Config.get().customFont.get();

        for (int i = list.size() - 1; i >= 0; i--) {
            Tracked t = list.get(i);
            Vector3d pos = new Vector3d(t.x, t.y, t.z);
            if (NametagUtils.to2D(pos, scale.get())) {
                renderNametag(event, t, pos, shadow);
            }
        }
    }

    private void renderNametag(Render2DEvent event, Tracked t, Vector3d pos, boolean shadow) {
        TextRenderer text = TextRenderer.get();
        NametagUtils.begin(pos, event.drawContext);

        String name = t.label != null ? t.label + " " + t.name : t.name;
        Color nameColor = t.colorHex != null ? fromHex(t.colorHex) : textColor.get();
        if (nameColor == null) nameColor = textColor.get();

        String healthText = " " + t.health;
        String armorText = " " + t.armor;
        String distText = " " + Math.round(t.dist) + "m";

        double nameWidth = text.getWidth(name, shadow);
        double healthWidth = showHealth.get() ? text.getWidth(healthText, shadow) : 0;
        double armorWidth = showArmor.get() ? text.getWidth(armorText, shadow) : 0;
        double distWidth = showDistance.get() ? text.getWidth(distText, shadow) : 0;

        double width = nameWidth + armorWidth + healthWidth + distWidth;
        double widthHalf = width / 2;
        double heightDown = text.getHeight(shadow);

        if (background.get()) {
            Renderer2D.COLOR.begin();
            Renderer2D.COLOR.quad(-widthHalf - 1, -heightDown - 1, width + 2, heightDown + 2, backgroundColor.get());
            Renderer2D.COLOR.render();
        }

        text.beginBig();
        double hX = -widthHalf;
        double hY = -heightDown;

        hX = text.render(name, hX, hY, nameColor, shadow);
        if (showHealth.get()) hX = text.render(healthText, hX, hY, healthColor.get(), shadow);
        if (showArmor.get()) hX = text.render(armorText, hX, hY, armorColor.get(), shadow);
        if (showDistance.get()) text.render(distText, hX, hY, distanceColor.get(), shadow);
        text.end();

        NametagUtils.end(event.drawContext);
    }

    private Color fromHex(String hex) {
        int[] rgb = AesirApi.parseHexColor(hex);
        if (rgb == null) return null;
        return new Color(rgb[0], rgb[1], rgb[2], 255);
    }

    private String currentWorld() {
        String w = world.get();
        if (!w.equalsIgnoreCase("auto")) return w;
        if (mc.world == null) return null;
        String dim = mc.world.getRegistryKey().getValue().getPath();
        return switch (dim) {
            case "overworld" -> "dunya";
            case "the_nether" -> "dunya_nether";
            case "the_end" -> "dunya_the_end";
            default -> dim;
        };
    }

    private void fetchPlayers() {
        if (playersInFlight) return;
        playersInFlight = true;
        String url = apiUrl.get().replaceAll("/+$", "") + "/townymap/tiles/players.json";
        executor.execute(() -> {
            try {
                String body = AesirApi.get(url);
                List<AesirApi.PlayerInfo> list = AesirApi.parsePlayers(body);
                players.clear();
                for (AesirApi.PlayerInfo p : list) players.put(p.uuid(), p);
                boolean useTowns = needsMarkers();
                inTown.clear();
                for (AesirApi.PlayerInfo p : list) {
                    if (useTowns) inTown.put(p.uuid(), isInsideTown(p.world(), p.x(), p.z()));
                }
            } catch (Exception ignored) {
            } finally {
                playersInFlight = false;
            }
        });
    }

    private void fetchRanks() {
        String url = apiUrl.get().replaceAll("/+$", "") + "/api/map/player-ranks";
        executor.execute(() -> {
            try {
                String body = AesirApi.get(url);
                rankColors.clear();
                rankLabels.clear();
                AesirApi.parseRanks(body, rankColors, rankLabels);
            } catch (Exception ignored) {
            }
        });
    }

    private void fetchTitles() {
        String url = apiUrl.get().replaceAll("/+$", "") + "/api/map/player-titles";
        executor.execute(() -> {
            try {
                String body = AesirApi.get(url);
                titleColors.clear();
                titleLabels.clear();
                AesirApi.parseTitles(body, titleColors, titleLabels);
            } catch (Exception ignored) {
            }
        });
    }

    private void fetchMarkers(String worldName) {
        if (worldName == null || markersInFlight) return;
        markersInFlight = true;
        String url = apiUrl.get().replaceAll("/+$", "") + "/townymap/tiles/" + worldName + "/markers.json";
        executor.execute(() -> {
            try {
                String body = AesirApi.get(url);
                List<AesirApi.TownPolygon> polys = AesirApi.parseMarkers(body);
                List<Ring> rings = new ArrayList<>();
                for (AesirApi.TownPolygon poly : polys) {
                    for (List<double[]> r : poly.rings()) rings.add(new Ring(r));
                }
                townRings.put(worldName, rings);
                for (AesirApi.PlayerInfo p : players.values()) {
                    if (p.world().equals(worldName)) inTown.put(p.uuid(), isInsideTown(worldName, p.x(), p.z()));
                }
            } catch (Exception ignored) {
            } finally {
                markersInFlight = false;
            }
        });
    }

    private boolean isInsideTown(String worldName, double x, double z) {
        List<Ring> rings = townRings.get(worldName);
        if (rings == null) return false;
        for (Ring r : rings) {
            if (x < r.minX || x > r.maxX || z < r.minZ || z > r.maxZ) continue;
            if (AesirApi.isInside(x, z, r.points)) return true;
        }
        return false;
    }
}
