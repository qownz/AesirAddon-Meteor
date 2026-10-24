package com.aesir.addon.mixin;

import com.aesir.addon.AesirBadges;
import com.aesir.addon.modules.AesirSpoof;
import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(PlayerListHud.class)
public abstract class PlayerListHudMixin {
    @Inject(method = "getPlayerName", at = @At("RETURN"), cancellable = true)
    private void aesir$appendBadgeInTab(PlayerListEntry entry, CallbackInfoReturnable<Text> cir) {
        if (!AesirSpoof.badgesEnabled()) return;
        if (entry.getProfile() == null || entry.getProfile().name() == null || entry.getProfile().name().isEmpty()) return;
        String name = entry.getProfile().name();
        Text original = cir.getReturnValue();
        if (original == null) return;
        if (original.getString().indexOf(0xE100) >= 0) return;
        if (!AesirBadges.isClientUser(name) && !AesirBadges.isMobilePlayer(name)) return;
        Text prefixed = AesirBadges.buildPrefixedName(original, name);
        if (prefixed != null) cir.setReturnValue(prefixed);
    }
}
