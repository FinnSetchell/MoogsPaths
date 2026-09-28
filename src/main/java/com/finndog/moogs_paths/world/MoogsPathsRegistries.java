package com.finndog.moogs_paths.world;

import com.finndog.moogs_paths.Constants;
import net.minecraft.resources.ResourceLocation;

public final class MoogsPathsRegistries {
    //? if >=1.21.1 {
    public static final ResourceLocation PATH_GEN_ID = ResourceLocation.fromNamespaceAndPath(Constants.MOD_ID, "path_gen");
    //?} else {
    /*public static final ResourceLocation PATH_GEN_ID = new ResourceLocation(Constants.MOD_ID, "path_gen");
    *///?}

    private MoogsPathsRegistries() {}
}
