package com.aesir.addon;

import com.aesir.addon.modules.AesirSpoof;
import com.aesir.addon.modules.AnchorBind;
import com.aesir.addon.modules.AesirRadar;
import com.aesir.addon.modules.ServerPackSaver;
import com.mojang.logging.LogUtils;
import meteordevelopment.meteorclient.addons.GithubRepo;
import meteordevelopment.meteorclient.addons.MeteorAddon;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Modules;
import org.slf4j.Logger;

public class AesirAddon extends MeteorAddon {
    public static final Logger LOG = LogUtils.getLogger();
    public static final Category CATEGORY = new Category("Aesir");

    @Override
    public void onInitialize() {
        LOG.info("Initializing Aesir Addon");

        Modules.get().add(new AesirSpoof());
        Modules.get().add(new AnchorBind());
        Modules.get().add(new AesirRadar());
        Modules.get().add(new ServerPackSaver());
    }

    @Override
    public void onRegisterCategories() {
        Modules.registerCategory(CATEGORY);
    }

    @Override
    public String getPackage() {
        return "com.aesir.addon";
    }

    @Override
    public GithubRepo getRepo() {
        return new GithubRepo("AesirMC", "AesirAddon");
    }
}
