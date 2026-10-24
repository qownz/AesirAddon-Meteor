package com.aesir.addon.mixin;

import com.aesir.addon.modules.ServerPackSaver;
import net.minecraft.client.resource.server.ServerResourcePackManager;
import net.minecraft.util.Downloader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.net.URL;
import java.util.Collection;
import java.util.UUID;

@Mixin(ServerResourcePackManager.class)
public abstract class ServerResourcePackManagerMixin {
    @Inject(method = "addResourcePack(Ljava/util/UUID;Ljava/net/URL;Lcom/google/common/hash/HashCode;)V", at = @At("HEAD"))
    private void aesir$rememberHash(UUID id, URL url, com.google.common.hash.HashCode hashCode, CallbackInfo ci) {
        ServerPackSaver.rememberHash(id, hashCode);
    }

    @Inject(method = "onDownload", at = @At("HEAD"))
    private void aesir$saveServerPacks(Collection<?> packs, Downloader.DownloadResult result, CallbackInfo ci) {
        ServerPackSaver.onServerPacksDownloaded(result);
    }
}