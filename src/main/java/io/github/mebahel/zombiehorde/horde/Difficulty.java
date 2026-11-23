package io.github.mebahel.zombiehorde.horde;

import io.github.mebahel.zombiehorde.ZombieHordePlugin;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.advancement.Advancement;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.jetbrains.annotations.NotNull;

/**
 * Manages per-world horde difficulty level (1 or 2) stored in World PDC (FR-10).
 */
public final class Difficulty {

    public static final int LEVEL_1 = 1;
    public static final int LEVEL_2 = 2;
    private static final NamespacedKey NETHER_ROOT_KEY = NamespacedKey.minecraft("nether/root");

    private final ZombieHordePlugin plugin;
    private final NamespacedKey difficultyLevelKey;

    public Difficulty(@NotNull ZombieHordePlugin plugin) {
        this.plugin = plugin;
        this.difficultyLevelKey = new NamespacedKey(plugin, "difficulty_level");
    }

    public int getStoredLevel(@NotNull World world) {
        Integer stored = world.getPersistentDataContainer().get(difficultyLevelKey, PersistentDataType.INTEGER);
        if (stored != null && stored >= LEVEL_2) {
            return LEVEL_2;
        }
        return LEVEL_1;
    }

    /**
     * Effective difficulty: forced to 1 when enableDifficultySystem is false,
     * without overwriting the persisted value (FR-10).
     */
    public int getEffectiveLevel(@NotNull World world) {
        if (!plugin.getMainConfig().isEnableDifficultySystem()) {
            return LEVEL_1;
        }
        return getStoredLevel(world);
    }

    public void setStoredLevel(@NotNull World world, int level) {
        int clamped = level >= LEVEL_2 ? LEVEL_2 : LEVEL_1;
        world.getPersistentDataContainer().set(difficultyLevelKey, PersistentDataType.INTEGER, clamped);
    }

    /**
     * Called every 300 ticks for Overworld. Promotes permanently to level 2
     * as soon as any player in the world has completed minecraft:nether/root.
     */
    public void checkNetherProgression(@NotNull World world) {
        if (world.getEnvironment() != World.Environment.NORMAL) {
            return;
        }
        if (!plugin.getMainConfig().isEnableDifficultySystem()) {
            return;
        }
        if (getStoredLevel(world) >= LEVEL_2) {
            return;
        }
        Advancement netherRoot = Bukkit.getAdvancement(NETHER_ROOT_KEY);
        if (netherRoot == null) {
            return;
        }
        for (Player player : world.getPlayers()) {
            if (player.getAdvancementProgress(netherRoot).isDone()) {
                setStoredLevel(world, LEVEL_2);
                plugin.getLogger().info("Horde difficulty permanently increased to level 2 in world '"
                        + world.getName() + "' because player " + player.getName() + " entered the Nether.");
                return;
            }
        }
    }
}
