package com.finndog.moogs_paths.mixin;

import com.finndog.moogs_paths.fabric.FabricRegistryStore;
import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.resources.RegistryDataLoader;
import net.minecraft.resources.ResourceLocation;
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

    // Vanilla 1.20.0 registryDirPath returns just id.getPath(), ignoring the namespace, so JSONs
    // for a registry like moogs_paths:path_network would have to live at data/<dp>/path_network/.
    // Mojang added the namespace prefix in 1.20.1 (Forge backports it via ForgeHooks.prefixNamespace,
    // which is why Forge picks our files up correctly). Backport the 1.20.1 behaviour so the same
    // data/<dp>/moogs_paths/path_network/ layout works on plain 1.20 too. minecraft-namespaced
    // registries are unchanged.
    @ModifyReturnValue(
        method = "registryDirPath(Lnet/minecraft/resources/ResourceLocation;)Ljava/lang/String;",
        at = @At("RETURN")
    )
    private static String prefixCustomNamespace(String original, ResourceLocation id) {
        if (id.getNamespace().equals("minecraft")) return original;
        return id.getNamespace() + "/" + original;
    }
}
