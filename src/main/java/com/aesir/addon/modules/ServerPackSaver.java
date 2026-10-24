package com.aesir.addon.modules;

import com.aesir.addon.AesirAddon;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.MinecraftClient;
import net.minecraft.network.packet.c2s.common.ResourcePackStatusC2SPacket;
import net.minecraft.network.packet.s2c.common.ResourcePackSendS2CPacket;
import net.minecraft.util.Downloader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class ServerPackSaver extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> autoApply = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-apply")
        .description("When the server sends a resource pack you already saved, use the saved copy and skip the download.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> addToList = sgGeneral.add(new BoolSetting.Builder()
        .name("add-to-list")
        .description("Add the saved server resource pack to your resource pack list so you can toggle it manually.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> overwrite = sgGeneral.add(new BoolSetting.Builder()
        .name("overwrite")
        .description("Overwrite the saved pack when the server provides an updated version.")
        .defaultValue(false)
        .build()
    );

    private static final ConcurrentMap<UUID, String> PACK_HASHES = new ConcurrentHashMap<>();

    public ServerPackSaver() {
        super(AesirAddon.CATEGORY, "server-pack-saver", "Saves the server resource pack into your resourcepacks folder so it is only downloaded once.");
    }

    @Override
    public void onDeactivate() {
        PACK_HASHES.clear();
    }

    private static ServerPackSaver get() {
        return Modules.get().get(ServerPackSaver.class);
    }

    private static boolean active() {
        ServerPackSaver mod = get();
        return mod != null && mod.isActive();
    }

    public static void rememberHash(UUID id, com.google.common.hash.HashCode hashCode) {
        if (!active() || id == null || hashCode == null) return;
        PACK_HASHES.put(id, hashCode.toString());
    }

    public static void onServerPacksDownloaded(Downloader.DownloadResult result) {
        if (!active() || result == null || result.downloaded().isEmpty()) return;
        for (Map.Entry<UUID, Path> entry : result.downloaded().entrySet()) {
            UUID id = entry.getKey();
            String hash = PACK_HASHES.get(id);
            if (hash == null) continue;
            copyPack(hash, entry.getValue());
        }
    }

    private static void copyPack(String hash, Path downloaded) {
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            Path dir = mc.getResourcePackDir();
            if (dir == null) return;
            Files.createDirectories(dir);

            String name = "serverpack_" + hash + ".zip";
            Path target = dir.resolve(name);
            if (Files.exists(target) && !isOverwrite()) return;

            Files.copy(downloaded, target, StandardCopyOption.REPLACE_EXISTING);
            ServerPackSaver mod = get();
            if (mod != null) mod.info("Saved server resource pack: " + name);
            addToPackList(name);
        } catch (IOException e) {
            ServerPackSaver mod = get();
            if (mod != null) mod.error("Failed to save server resource pack: " + e.getMessage());
        }
    }

    public static boolean useSavedPack(ResourcePackSendS2CPacket packet) {
        if (!active() || packet == null || !isAutoApply()) return false;

        String hash = packet.hash();
        if (hash == null || hash.isBlank()) return false;

        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            Path dir = mc.getResourcePackDir();
            if (dir == null) return false;

            Path saved = dir.resolve("serverpack_" + hash + ".zip");
            if (!Files.exists(saved)) return false;

            UUID id = packet.id();
            addToPackList(saved.getFileName().toString());
            mc.execute(() -> mc.reloadResources().whenComplete((v, t) -> sendStatus(id, ResourcePackStatusC2SPacket.Status.SUCCESSFULLY_LOADED)));

            ServerPackSaver mod = get();
            if (mod != null) mod.info("Using saved server resource pack, skipped re-download.");
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static void addToPackList(String fileName) {
        if (!isAddToList()) return;
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            String packId = "file/" + fileName;
            List<String> packs = mc.options.resourcePacks;
            boolean changed = false;
            synchronized (packs) {
                if (!packs.contains(packId)) {
                    packs.add(packId);
                    changed = true;
                }
            }
            if (changed) mc.options.write();
        } catch (Exception ignored) {
        }
    }

    private static void sendStatus(UUID id, ResourcePackStatusC2SPacket.Status status) {
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.getNetworkHandler() != null && mc.getNetworkHandler().getConnection() != null) {
                mc.getNetworkHandler().getConnection().send(new ResourcePackStatusC2SPacket(id, status));
            }
        } catch (Exception ignored) {
        }
    }

    private static boolean isAutoApply() {
        ServerPackSaver mod = get();
        return mod != null && mod.autoApply.get();
    }

    private static boolean isAddToList() {
        ServerPackSaver mod = get();
        return mod != null && mod.addToList.get();
    }

    private static boolean isOverwrite() {
        ServerPackSaver mod = get();
        return mod != null && mod.overwrite.get();
    }
}