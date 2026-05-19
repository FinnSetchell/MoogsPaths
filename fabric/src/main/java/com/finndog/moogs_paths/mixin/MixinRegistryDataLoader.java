package com.finndog.moogs_paths.mixin;

import com.finndog.moogs_paths.fabric.FabricRegistryStore;
import net.minecraft.resources.RegistryDataLoader;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import java.util.ArrayList;
import java.util.List;

@Mixin(RegistryDataLoader.class)
public class MixinRegistryDataLoader {

    @ModifyVariable(
        method = "load(Lnet/minecraft/server/packs/resources/ResourceManager;Lnet/minecraft/core/RegistryAccess;Ljava/util/List;)Lnet/minecraft/core/RegistryAccess$Frozen;",
        at = @At("HEAD"),
        argsOnly = true,
        ordinal = 0
    )
    private static List<RegistryDataLoader.RegistryData<?>> injectCustomRegistries(
            List<RegistryDataLoader.RegistryData<?>> original) {
        // RegistryDataLoader.load is invoked twice per world load: once with WORLDGEN_REGISTRIES,
        // once with DIMENSION_REGISTRIES. Appending to both would land each custom registry in
        // two layers of the resulting LayeredRegistryAccess and trip "Duplicated registry" in
        // collectRegistries. Only inject into the worldgen pass.
        if (original != RegistryDataLoader.WORLDGEN_REGISTRIES) return original;
        List<RegistryDataLoader.RegistryData<?>> custom = FabricRegistryStore.getEntries();
        if (custom.isEmpty()) return original;
        List<RegistryDataLoader.RegistryData<?>> combined = new ArrayList<>(original);
        combined.addAll(custom);
        return combined;
    }
}
