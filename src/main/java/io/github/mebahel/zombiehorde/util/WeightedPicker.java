package io.github.mebahel.zombiehorde.util;

import java.util.List;
import java.util.Random;
import java.util.function.ToIntFunction;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Weighted random picker matching PRD FR-05b:
 * r = rand(totalWeight); iterate subtracting weight; return first where r - weight < 0; fallback to first non-null element.
 * Uses 64-bit long accumulation to prevent 32-bit integer overflow on large weights (C4-10).
 */
public final class WeightedPicker {

    private WeightedPicker() {
    }

    @Nullable
    public static <T> T pick(
            @Nullable List<T> items,
            @NotNull ToIntFunction<T> weightExtractor,
            @NotNull Random random
    ) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        long totalWeight = 0L;
        T firstNonNull = null;
        for (T item : items) {
            if (item == null) {
                continue;
            }
            if (firstNonNull == null) {
                firstNonNull = item;
            }
            int weight = weightExtractor.applyAsInt(item);
            if (weight > 0) {
                totalWeight += weight;
            }
        }
        if (firstNonNull == null) {
            return null;
        }
        if (totalWeight <= 0L) {
            return firstNonNull;
        }

        long roll = random.nextLong(totalWeight);
        for (T item : items) {
            if (item == null) {
                continue;
            }
            int weight = weightExtractor.applyAsInt(item);
            if (weight <= 0) {
                continue;
            }
            roll -= weight;
            if (roll < 0L) {
                return item;
            }
        }
        return firstNonNull;
    }
}
