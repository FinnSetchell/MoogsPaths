package com.finndog.moogs_paths.neoforge.client;

import com.finndog.moogs_paths.Constants;
import com.finndog.moogs_paths.client.ClothRequiredScreen;
import com.finndog.moogs_paths.client.PathsConfigScreen;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.ModList;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/**
 * Client-only entrypoint: the mod list's config button, opening the sliders with Cloth Config and a
 * screen asking for it without. Never loaded on a dedicated server.
 */
@Mod(value = Constants.MOD_ID, dist = Dist.CLIENT)
public class MoogsPathsNeoForgeClient {

    public MoogsPathsNeoForgeClient(ModContainer modContainer) {
        modContainer.registerExtensionPoint(IConfigScreenFactory.class,
            (minecraft, parent) -> ModList.get().isLoaded("cloth_config")
                ? PathsConfigScreen.create(parent)
                : new ClothRequiredScreen(parent));
    }
}
