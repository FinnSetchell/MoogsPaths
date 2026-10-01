package com.finndog.moogs_paths.forge.client;

import com.finndog.moogs_paths.client.ClothRequiredScreen;
import com.finndog.moogs_paths.client.PathsConfigScreen;
import net.minecraftforge.client.ConfigScreenHandler;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.fml.ModLoadingContext;

/**
 * Client only: the mod list's config button, opening the sliders with Cloth Config and a screen
 * asking for it without. Called from the mod constructor on the client dist only.
 */
public final class MoogsPathsForgeClient {
    private MoogsPathsForgeClient() {}

    public static void registerConfigScreen() {
        ModLoadingContext.get().registerExtensionPoint(
            ConfigScreenHandler.ConfigScreenFactory.class,
            () -> new ConfigScreenHandler.ConfigScreenFactory(
                (minecraft, parent) -> ModList.get().isLoaded("cloth_config")
                    ? PathsConfigScreen.create(parent)
                    : new ClothRequiredScreen(parent)));
    }
}
