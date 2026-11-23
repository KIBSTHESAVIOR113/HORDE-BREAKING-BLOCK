package io.github.mebahel.zombiehorde.horde;

import io.github.mebahel.zombiehorde.ZombieHordePlugin;
import io.github.mebahel.zombiehorde.ai.BreakBlockGoal;
import io.github.mebahel.zombiehorde.config.MainConfig;
import io.github.mebahel.zombiehorde.horde.HordeSpawner.SpawnResult;
import java.util.Random;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.scheduler.BukkitTask;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Manages the repeating 20-tick spawn/difficulty task (FR-03, FR-10) and an independent
 * 20-tick maintenance task for stale block-break expiry (N-09, Q6).
 */
public final class HordeManager {

    public static final long TASK_PERIOD_TICKS = 20L;
    public static final long DIFFICULTY_CHECK_INTERVAL_TICKS = 300L;
    public static final long RETRY_DELAY_TICKS = 100L; // ~5 seconds retry on NO_LOADED_COLUMN (N-06)

    private final ZombieHordePlugin plugin;
    private final HordeSpawner spawner;
    private final Difficulty difficulty;
    private final Random random;

    private BukkitTask spawnTask;
    private BukkitTask maintenanceTask;
    private long spawnTickCounter;
    private long difficultyTickCounter;

    public HordeManager(
            @NotNull ZombieHordePlugin plugin,
            @NotNull HordeSpawner spawner,
            @NotNull Difficulty difficulty
    ) {
        this.plugin = plugin;
        this.spawner = spawner;
        this.difficulty = difficulty;
        this.random = new Random();
    }

    /**
     * N-09: Starts the maintenance task (BreakBlockGoal.expireStale) unconditionally,
     * even when hordeSpawning=false (so manual /spawnhorde hordes still clean up stale cracks).
     */
    public void ensureMaintenanceStarted() {
        if (maintenanceTask == null || maintenanceTask.isCancelled()) {
            this.maintenanceTask = Bukkit.getScheduler().runTaskTimer(
                    plugin, () -> BreakBlockGoal.expireStale(), TASK_PERIOD_TICKS, TASK_PERIOD_TICKS);
        }
    }

    /**
     * Starts the spawn/difficulty task if not already running, preserving accumulators across reloads (F-5, NFR-02).
     */
    public void ensureStarted() {
        if (isRunning()) {
            return;
        }
        this.spawnTask = Bukkit.getScheduler().runTaskTimer(plugin, this::onTickStep, TASK_PERIOD_TICKS, TASK_PERIOD_TICKS);
    }

    /**
     * Stops ONLY the automatic spawn task (used by reloadPluginConfigs when hordeSpawning=false, N-09).
     */
    public void stopSpawning() {
        if (spawnTask != null) {
            spawnTask.cancel();
            spawnTask = null;
        }
    }

    /**
     * NFR-07 / Q5: Stops BOTH spawn task and maintenance task on plugin disable.
     */
    public void stop() {
        stopSpawning();
        if (maintenanceTask != null) {
            maintenanceTask.cancel();
            maintenanceTask = null;
        }
    }

    public boolean isRunning() {
        return spawnTask != null && !spawnTask.isCancelled();
    }

    public long getRemainingTicksToNextSpawn() {
        long threshold = plugin.getMainConfig().getSpawnDelayTicks();
        return Math.max(0L, threshold - spawnTickCounter);
    }

    private void onTickStep() {
        World overworld = findOverworld();
        if (overworld == null) {
            return;
        }

        // Difficulty check every 300 ticks (FR-10)
        difficultyTickCounter += TASK_PERIOD_TICKS;
        if (difficultyTickCounter >= DIFFICULTY_CHECK_INTERVAL_TICKS) {
            difficultyTickCounter = 0L;
            difficulty.checkNetherProgression(overworld);
        }

        // Horde timer runs only if at least one player is in Overworld (FR-03)
        if (overworld.getPlayers().isEmpty()) {
            return;
        }

        MainConfig config = plugin.getMainConfig();
        boolean canCount = config.isSpawnInDayLight() || isNight(overworld);
        if (!canCount) {
            return;
        }

        spawnTickCounter += TASK_PERIOD_TICKS;
        long threshold = config.getSpawnDelayTicks();
        if (spawnTickCounter >= threshold) {
            int hordeCount = config.getHordeNumber();
            float spawnChance = config.getHordeSpawnChance();
            boolean anySpawned = false;
            boolean anyNoLoadedColumn = false;
            spawnTickCounter = 0L;

            for (int i = 0; i < hordeCount; i++) {
                if (random.nextFloat() <= spawnChance) {
                    SpawnResult result = spawner.spawnHorde(overworld, null);
                    if (result == SpawnResult.SPAWNED) {
                        anySpawned = true;
                    } else if (result == SpawnResult.NO_LOADED_COLUMN) {
                        anyNoLoadedColumn = true;
                    }
                } else {
                    plugin.getLogger().info("You are lucky... The Horde didn't spawn this time...");
                }
            }

            // N-06 + D3-03: Retry in ~5s only if NO horde spawned and at least one failed due to unloaded columns
            if (!anySpawned && anyNoLoadedColumn) {
                spawnTickCounter = Math.max(0L, threshold - RETRY_DELAY_TICKS);
            }
        }
    }

    /**
     * Night = non-fixed-time dimension (Overworld) and not daytime (FR-03).
     */
    private boolean isNight(@NotNull World world) {
        return world.getEnvironment() == World.Environment.NORMAL && !world.isDayTime();
    }

    @Nullable
    public World findOverworld() {
        for (World world : Bukkit.getWorlds()) {
            if (world.getEnvironment() == World.Environment.NORMAL) {
                return world;
            }
        }
        return null;
    }
}
