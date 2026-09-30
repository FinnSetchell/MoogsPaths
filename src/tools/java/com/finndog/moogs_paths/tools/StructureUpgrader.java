package com.finndog.moogs_paths.tools;

import com.mojang.datafixers.DataFixer;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.util.datafix.DataFixTypes;
import net.minecraft.util.datafix.DataFixers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/**
 * Build tool, not part of the mod: `./gradlew upgradeStructures` runs it once per Minecraft version
 * (see CONTRIBUTING.md). It passes every structure template through the game's own data fixer, the
 * same call the game makes when it loads a template, and writes the result at the game's data version.
 * A jar that ships those copies never runs the data fixer on its templates.
 *
 * Arguments: the source template folder and the output folder.
 */
public final class StructureUpgrader {
    private StructureUpgrader() {}

    public static void main(String[] args) throws IOException {
        Path source = Path.of(args[0]);
        Path target = Path.of(args[1]);
        SharedConstants.tryDetectVersion();
        DataFixer fixer = DataFixers.getDataFixer();

        List<Path> templates;
        try(Stream<Path> files = Files.walk(source)) {
            templates = files.filter(p -> p.toString().endsWith(".nbt")).sorted().toList();
        }
        int version = 0;
        for(Path file : templates) {
            CompoundTag tag = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            // As the game's structure template loader does: 500 when a template has no DataVersion.
            int from = NbtUtils.getDataVersion(tag, 500);
            CompoundTag fixed = NbtUtils.addCurrentDataVersion(DataFixTypes.STRUCTURE.updateToCurrentVersion(fixer, tag, from));
            version = NbtUtils.getDataVersion(fixed, 0);
            Path out = target.resolve(source.relativize(file).toString());
            Files.createDirectories(out.getParent());
            NbtIo.writeCompressed(fixed, out);
        }
        System.out.println("Upgraded " + templates.size() + " structure templates to data version " + version + " in " + target);
    }
}
