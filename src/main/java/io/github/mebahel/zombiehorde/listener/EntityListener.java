package io.github.mebahel.zombiehorde.listener;

import com.destroystokyo.paper.entity.ai.GoalKey;
import com.destroystokyo.paper.entity.ai.MobGoals;
import com.destroystokyo.paper.event.entity.EntityAddToWorldEvent;
import com.destroystokyo.paper.event.entity.EntityRemoveFromWorldEvent;
import io.github.mebahel.zombiehorde.ZombieHordePlugin;
import io.github.mebahel.zombiehorde.ai.BreakBlockGoal;
import io.github.mebahel.zombiehorde.ai.HordeGoal;
import io.github.mebahel.zombiehorde.config.MainConfig;
import io.github.mebahel.zombiehorde.horde.HordeRegistry;
import io.github.mebahel.zombiehorde.horde.PatrolData;
import io.github.mebahel.zombiehorde.util.PaperApiAdapter;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustByBlockEvent;
import org.bukkit.event.entity.EntityCombustByEntityEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Attaches horde goals on world/chunk load (FR-09b), maintains HordeRegistry (N-07),
 * handles daylight immunity for all undead horde members (FR-11), and cleans up block-break overlays (Q6).
 */
public final class EntityListener implements Listener {

    private final ZombieHordePlugin plugin;
    private final PatrolData patrolData;
    private final HordeRegistry registry;

    public EntityListener(
            @NotNull ZombieHordePlugin plugin,
            @NotNull PatrolData patrolData,
            @NotNull HordeRegistry registry
    ) {
        this.plugin = plugin;
        this.patrolData = patrolData;
        this.registry = registry;
    }

    @EventHandler(priority = EventPriority.NORMAL)
    public void onEntityAddToWorld(@NotNull EntityAddToWorldEvent event) {
        // D3-06: Cheapest check first (skips items/arrows/XP orbs/players before any PDC access)
        Entity entity = event.getEntity();
        if (!(entity instanceof Mob mob)) {
            return;
        }
        if (!patrolData.isPatrolling(mob)) {
            return;
        }
        registry.add(mob.getUniqueId());
        attachHordeGoals(mob);
        PaperApiAdapter.setShouldBurnInDay(mob, !plugin.getMainConfig().isSpawnInDayLight());
    }

    /**
     * Idempotently attaches HordeGoal (priority 4) and synchronizes BreakBlockGoal (priority 1)
     * with the current config (removing it cleanly if both break flags are turned off on reload, D-10).
     */
    public void attachHordeGoals(@NotNull Mob mob) {
        MobGoals mobGoals = Bukkit.getMobGoals();
        GoalKey<Mob> hordeKey = HordeGoal.getKey(plugin);
        if (!mobGoals.hasGoal(mob, hordeKey)) {
            mobGoals.addGoal(mob, HordeGoal.PRIORITY, new HordeGoal(plugin, patrolData, mob));
        }

        MainConfig cfg = plugin.getMainConfig();
        GoalKey<Mob> breakKey = BreakBlockGoal.getKey(plugin);
        if (cfg.isHordeMemberBreakGlass() || cfg.isHordeMemberBreakFence()) {
            if (!mobGoals.hasGoal(mob, breakKey)) {
                mobGoals.addGoal(mob, BreakBlockGoal.PRIORITY, new BreakBlockGoal(plugin, patrolData, mob));
            }
        } else if (mobGoals.hasGoal(mob, breakKey)) {
            mobGoals.removeGoal(mob, breakKey);
            BreakBlockGoal.onMobRemoved(mob);
        }
    }

    /**
     * FR-11: Prevents daylight combustion for patrolling horde members when spawnInDayLight=true.
     * Covers all undead mob types (Zombie, Skeleton, Stray, Bogged, Drowned, Phantom, etc.),
     * while still allowing fire from entities (EntityCombustByEntityEvent) or blocks (lava/fire).
     */
    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onEntityCombust(@NotNull EntityCombustEvent event) {
        if (event instanceof EntityCombustByEntityEvent || event instanceof EntityCombustByBlockEvent) {
            return;
        }
        if (!plugin.getMainConfig().isSpawnInDayLight()) {
            return;
        }
        // C4-07: O(1) Mob + HordeRegistry fast-path avoids PDC reads for non-horde burning mobs at dawn
        Entity entity = event.getEntity();
        if (entity instanceof Mob && (registry.contains(entity.getUniqueId()) || patrolData.isPatrolling(entity))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDeath(@NotNull EntityDeathEvent event) {
        if (event.getEntity() instanceof Mob mob) {
            registry.remove(mob.getUniqueId());
            if (patrolData.isPatrolling(mob)) {
                BreakBlockGoal.onMobRemoved(mob);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityRemoveFromWorld(@NotNull EntityRemoveFromWorldEvent event) {
        Entity entity = event.getEntity();
        // N-07 + D3-06: Always remove UUID from registry (O(1) HashSet) even if PDC changed externally
        registry.remove(entity.getUniqueId());
        if (entity instanceof Mob mob && patrolData.isPatrolling(mob)) {
            BreakBlockGoal.onMobRemoved(mob);
        }
    }
}
