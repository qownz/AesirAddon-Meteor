package com.aesir.addon.modules;

import com.aesir.addon.AesirAddon;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.Utils;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.Items;

public class AnchorBind extends Module {
    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Keybind> anchorBind = sgGeneral.add(new KeybindSetting.Builder()
        .name("anchor-bind")
        .description("Keybind to trigger the anchor sequence.")
        .defaultValue(Keybind.none())
        .build()
    );

    private final Setting<Integer> delay = sgGeneral.add(new IntSetting.Builder()
        .name("delay")
        .description("Delay in milliseconds between each action.")
        .defaultValue(50)
        .min(0)
        .sliderMax(500)
        .build()
    );

    private int step = -1;
    private int timer = 0;
    private int anchorSlot = -1;
    private int glowstoneSlot = -1;

    public AnchorBind() {
        super(AesirAddon.CATEGORY, "anchor-bind", "Automatically places anchor, glowstone, and detonates with a single keybind.");
    }

    @Override
    public void onActivate() {
        step = -1;
        timer = 0;
        anchorSlot = -1;
        glowstoneSlot = -1;
    }

    @Override
    public void onDeactivate() {
        step = -1;
        timer = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        if (anchorBind.get().isPressed() && step == -1) {
            anchorSlot = findItemSlot(Items.RESPAWN_ANCHOR);
            glowstoneSlot = findItemSlot(Items.GLOWSTONE);

            if (anchorSlot == -1 || glowstoneSlot == -1) {
                if (anchorSlot == -1) warning("No anchor found in hotbar.");
                if (glowstoneSlot == -1) warning("No glowstone found in hotbar.");
                return;
            }

            step = 0;
            timer = 0;
        }

        if (step == -1) return;

        if (timer > 0) {
            timer--;
            return;
        }

        switch (step) {
            case 0 -> {
                mc.player.getInventory().setSelectedSlot(anchorSlot);
                Utils.rightClick();
                step = 1;
                timer = delay.get() / 50;
            }
            case 1 -> {
                mc.player.getInventory().setSelectedSlot(glowstoneSlot);
                Utils.rightClick();
                step = 2;
                timer = delay.get() / 50;
            }
            case 2 -> {
                mc.player.getInventory().setSelectedSlot(8);
                Utils.rightClick();
                step = -1;
            }
        }
    }

    private int findItemSlot(net.minecraft.item.Item item) {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).getItem() == item) {
                return i;
            }
        }
        return -1;
    }
}
