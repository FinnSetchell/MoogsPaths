package com.finndog.moogs_paths.fabric.client;

import com.finndog.moogs_paths.client.ClothRequiredScreen;
import com.finndog.moogs_paths.client.PathsConfigScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.fabricmc.loader.api.FabricLoader;

// Mod Menu's config button: the sliders with Cloth Config, otherwise a screen asking for it.
public class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        boolean cloth = FabricLoader.getInstance().isModLoaded("cloth-config") || FabricLoader.getInstance().isModLoaded("cloth-config2");
        return cloth ? PathsConfigScreen::create : ClothRequiredScreen::new;
    }
}
