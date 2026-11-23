package io.github.mebahel.zombiehorde;

import com.destroystokyo.paper.entity.ai.GoalKey;
import com.destroystokyo.paper.entity.ai.MobGoals;
import io.github.mebahel.zombiehorde.ai.BreakBlockGoal;
import io.github.mebahel.zombiehorde.ai.HordeGoal;
import io.github.mebahel.zombiehorde.command.HordeCommand;
import io.github.mebahel.zombiehorde.config.CompositionConfig;
import io.github.mebahel.zombiehorde.config.ConfigLoader;
import io.github.mebahel.zombiehorde.config.MainConfig;
import io.github.mebahel.zombiehorde.horde.Difficulty;
import io.github.mebahel.zombiehorde.horde.HordeManager;
import io.github.mebahel.zombiehorde.horde.HordeRegistry;
import io.github.mebahel.zombiehorde.horde.HordeSpawner;
import io.github.mebahel.zombiehorde.horde.PatrolData;
import io.github.mebahel.zombiehorde.listener.EntityListener;
import io.github.mebahel.zombiehorde.util.PaperApiAdapter;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.command.PluginCommand;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.plugin.java.JavaPlugin;

public final class ZombieHordePlugin extends JavaPlugin {

    private ConfigLoader configLoader;
    private MainConfig mainConfig;
    private CompositionConfig compositionConfig;
    private PatrolData patrolData;
    private HordeRegistry hordeRegistry;
    private Difficulty difficulty;
    private HordeSpawner hordeSpawner;
    private HordeManager hordeManager;
    private EntityListener entityListener;

    @Override
    public void onEnable() {
        this.configLoader = new ConfigLoader(getDataFolder(), getLogger());
        this.mainConfig = configLoader.loadMainConfig();
        this.compositionConfig = configLoader.loadCompositionConfig();

        this.patrolData = new PatrolData(this);
        this.hordeRegistry = new HordeRegistry();
        this.difficulty = new Difficulty(this);
        this.hordeSpawner = new HordeSpawner(this, patrolData, difficulty);
        this.hordeManager = new HordeManager(this, hordeSpawner, difficulty);
        this.entityListener = new EntityListener(this, patrolData, hordeRegistry);

        getServer().getPluginManager().registerEvents(entityListener, this);
        registerCommands();

        // N-07: Single one-time bootstrap walk at onEnable (POSTWORLD plugins miss earlier AddToWorld events)
        bootstrapLoadedPatrollers();
        attachGoalsToLoadedPatrollers();

        // N-09: Maintenance task runs unconditionally so stale crack overlays always expire
        hordeManager.ensureMaintenanceStarted();

        if (mainConfig.isHordeSpawning()) {
            hordeManager.ensureStarted();
            getLogger().info("MebahelZombieHorde initialized successfully (horde spawning enabled).");
        } else {
            getLogger().info("MebahelZombieHorde initialized, but hordeSpawning is disabled in config.");
        }
    }

    @Override
    public void onDisable() {
        if (hordeManager != null) {
            hordeManager.stop();
        }
        BreakBlockGoal.clearAllProgress();
        // C4-02 + D3-05: Detach plugin goals and restore saved base MOVEMENT_SPEED before clearing registry
        if (hordeRegistry != null && patrolData != null) {
            MobGoals mobGoals = Bukkit.getMobGoals();
            GoalKey<Mob> hordeKey = HordeGoal.getKey(this);
            GoalKey<Mob> breakKey = BreakBlockGoal.getKey(this);
            for (UUID id : hordeRegistry.snapshot()) {
                Entity entity = Bukkit.getEntity(id);
                if (entity instanceof Mob mob && mob.isValid()) {
                    mobGoals.removeGoal(mob, hordeKey);
                    mobGoals.removeGoal(mob, breakKey);
                    Double savedBase = patrolData.getSavedBaseSpeed(mob);
                    if (savedBase != null) {
                        AttributeInstance speedAttr = PaperApiAdapter.getMovementSpeedAttribute(mob);
                        if (speedAttr != null) {
                            speedAttr.setBaseValue(savedBase);
                        }
                        patrolData.clearSavedBaseSpeed(mob);
                    }
                }
            }
            hordeRegistry.clear();
        }
        getLogger().info("MebahelZombieHorde disabled cleanly.");
    }

    private void bootstrapLoadedPatrollers() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (entity instanceof Mob mob && patrolData.isPatrolling(mob)) {
                    hordeRegistry.add(mob.getUniqueId());
                }
            }
        }
    }

    /**
     * N-07 + P3 + D-09: Iterates HordeRegistry.snapshot() instead of scanning all server entities.
     */
    private void attachGoalsToLoadedPatrollers() {
        if (entityListener == null || patrolData == null || mainConfig == null || hordeRegistry == null) {
            return;
        }
        boolean shouldBurnInDay = !mainConfig.isSpawnInDayLight();
        for (UUID id : hordeRegistry.snapshot()) {
            Entity entity = Bukkit.getEntity(id);
            if (!(entity instanceof Mob mob) || !mob.isValid() || !patrolData.isPatrolling(mob)) {
                hordeRegistry.remove(id);
                continue;
            }
            entityListener.attachHordeGoals(mob);
            PaperApiAdapter.setShouldBurnInDay(mob, shouldBurnInDay);
        }
    }

    private void registerCommands() {
        PluginCommand command = getCommand("mebahelzombiehorde");
        if (command != null) {
            HordeCommand executor = new HordeCommand(this);
            command.setExecutor(executor);
            command.setTabCompleter(executor);
        }
    }

    /**
     * Safely reloads both JSON configs without resetting the spawn timer accumulator,
     * stopping the maintenance task, or interrupting currently active horde goals (NFR-02, F-5, P3, N-07, N-09).
     */
    public void reloadPluginConfigs() {
        this.mainConfig = configLoader.loadMainConfig();
        this.compositionConfig = configLoader.loadCompositionConfig();
        attachGoalsToLoadedPatrollers();
        if (hordeManager != null) {
            if (mainConfig.isHordeSpawning()) {
                hordeManager.ensureStarted();
            } else {
                hordeManager.stopSpawning();
            }
        }
    }

    public MainConfig getMainConfig() {
        return mainConfig;
    }

    public CompositionConfig getCompositionConfig() {
        return compositionConfig;
    }

    public PatrolData getPatrolData() {
        return patrolData;
    }

    public HordeRegistry getHordeRegistry() {
        return hordeRegistry;
    }

    public Difficulty getDifficulty() {
        return difficulty;
    }

    public HordeSpawner getHordeSpawner() {
        return hordeSpawner;
    }

    public HordeManager getHordeManager() {
        return hordeManager;
    }
}
