package com.aesir.addon.mixin;

import com.aesir.addon.modules.ServerPackSaver;
import net.minecraft.client.network.ClientCommonNetworkHandler;
import net.minecraft.network.packet.s2c.common.ResourcePackSendS2CPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientCommonNetworkHandler.class)
public abstract class ClientCommonNetworkHandlerMixin {
    @Inject(method = "onResourcePackSend(Lnet/minecraft/network/packet/s2c/common/ResourcePackSendS2CPacket;)V", at = @At("HEAD"), cancellable = true)
    private void aesir$useSavedServerPack(ResourcePackSendS2CPacket packet, CallbackInfo ci) {
        if (ServerPackSaver.useSavedPack(packet)) {
            ci.cancel();
        }
    }
}