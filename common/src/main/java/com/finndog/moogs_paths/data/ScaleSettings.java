package com.finndog.moogs_paths.data;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;

public class ScaleSettings {
    public static final ScaleSettings TRAIL    = new ScaleSettings(100,  400);
    public static final ScaleSettings LOCAL    = new ScaleSettings(400,  1000);
    public static final ScaleSettings REGIONAL = new ScaleSettings(1000, 4000);
    public static final ScaleSettings GRAND    = new ScaleSettings(4000, 8000);

    public final int lengthMin;
    public final int lengthMax;

    public ScaleSettings(int lengthMin, int lengthMax) {
        this.lengthMin = lengthMin;
        this.lengthMax = lengthMax;
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
            if (settings.equals(TRAIL))    return DataResult.success("trail");
            if (settings.equals(LOCAL))    return DataResult.success("local");
            if (settings.equals(REGIONAL)) return DataResult.success("regional");
            if (settings.equals(GRAND))    return DataResult.success("grand");
            return DataResult.error(() -> "Cannot serialize non-preset ScaleSettings as string");
        }
    );

    private static final Codec<ScaleSettings> OBJECT_CODEC = RecordCodecBuilder.create(instance -> instance.group(
        Codec.INT.fieldOf("length_min").forGetter(s -> s.lengthMin),
        Codec.INT.fieldOf("length_max").forGetter(s -> s.lengthMax)
    ).apply(instance, ScaleSettings::new));

    public static final Codec<ScaleSettings> CODEC = Codec.either(STRING_CODEC, OBJECT_CODEC)
        .xmap(
            either -> either.map(l -> l, r -> r),
            settings -> {
                if (settings.equals(TRAIL))    return Either.left(TRAIL);
                if (settings.equals(LOCAL))    return Either.left(LOCAL);
                if (settings.equals(REGIONAL)) return Either.left(REGIONAL);
                if (settings.equals(GRAND))    return Either.left(GRAND);
                return Either.right(settings);
            }
        );

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ScaleSettings that)) return false;
        return lengthMin == that.lengthMin && lengthMax == that.lengthMax;
    }

    @Override
    public int hashCode() {
        return 31 * lengthMin + lengthMax;
    }
}
