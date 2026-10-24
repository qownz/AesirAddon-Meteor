package com.aesir.addon.mixin;

import com.aesir.addon.AesirBadges;
import com.aesir.addon.modules.AesirSpoof;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(EntityRenderer.class)
public abstract class EntityRendererMixin {
    @Inject(method = "updateRenderState", at = @At("RETURN"))
    private void aesir$patchDisplayName(Entity entity, EntityRenderState state, float tickProgress, CallbackInfo ci) {
        if (!(entity instanceof PlayerEntity player)) return;
        if (!AesirSpoof.badgesEnabled()) return;
        if (state.displayName == null) return;
        String name = player.getName().getString();
        if (name == null || name.isEmpty()) return;
        if (!state.displayName.getString().contains(name)) return;
        Text prefixed = AesirBadges.buildPrefixedName(state.displayName, name);
        if (prefixed != null) state.displayName = prefixed;
    }
}
