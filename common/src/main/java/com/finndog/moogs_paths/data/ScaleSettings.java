package com.finndog.moogs_paths.data;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;

public class ScaleSettings {
    public static final ScaleSettings TRAIL    = new ScaleSettings(100,  400,  0, 1);
    public static final ScaleSettings LOCAL    = new ScaleSettings(400,  1000, 1, 3);
    public static final ScaleSettings REGIONAL = new ScaleSettings(1000, 4000, 3, 8);
    public static final ScaleSettings GRAND    = new ScaleSettings(4000, 8000, 8, 16);

    public final int lengthMin;
    public final int lengthMax;
    public final int branchesMin;
    public final int branchesMax;

    public ScaleSettings(int lengthMin, int lengthMax, int branchesMin, int branchesMax) {
        this.lengthMin = lengthMin;
        this.lengthMax = lengthMax;
        this.branchesMin = branchesMin;
        this.branchesMax = branchesMax;
    }

    private static final Codec<ScaleSettings> STRING_CODEC = Codec.STRING.flatXmap(
        s -> switch (s.toLowerCase()) {
            case "trail"    -> DataResult.success(TRAIL);
            case "local"    -> DataResult.success(LOCAL);
            case "regional" -> DataResult.success(REGIONAL);
            case "grand"    -> DataResult.success(GRAND);
            default         -> DataResult.error(() -> "Unknown ScaleSettings preset: " + s);
        },
        settings -> {
            if (settings == TRAIL)    return DataResult.success("trail");
            if (settings == LOCAL)    return DataResult.success("local");
            if (settings == REGIONAL) return DataResult.success("regional");
            if (settings == GRAND)    return DataResult.success("grand");
            return DataResult.error(() -> "Cannot serialize non-preset ScaleSettings as string");
        }
    );

    private static final Codec<ScaleSettings> OBJECT_CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Codec.INT.fieldOf("length_min").forGetter(s -> s.lengthMin),
        Codec.INT.fieldOf("length_max").forGetter(s -> s.lengthMax),
        Codec.INT.fieldOf("branches_min").forGetter(s -> s.branchesMin),
        Codec.INT.fieldOf("branches_max").forGetter(s -> s.branchesMax)
    ).apply(instance, ScaleSettings::new));

    public static final Codec<ScaleSettings> CODEC = Codec.either(STRING_CODEC, OBJECT_CODEC)
        .xmap(
            either -> either.map(l -> l, r -> r),
            settings -> Either.right(settings)
        );
}
