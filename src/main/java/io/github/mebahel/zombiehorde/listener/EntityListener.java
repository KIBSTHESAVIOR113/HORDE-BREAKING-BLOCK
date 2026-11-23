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
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityCombustByBlockEvent;
import org.bukkit.event.entity.EntityCombustByEntityEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.jetbrains.annotations.NotNull;

/**
 * Attaches horde goals to patrolling mobs, attaches fast BreakBlockGoal to both horde mobs
 * and regular Zombies, maintains HordeRegistry, and handles daylight immunity.
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
        Entity entity = event.getEntity();
        if (!(entity instanceof Mob mob)) {
            return;
        }
        if (patrolData.isPatrolling(mob)) {
            registry.add(mob.getUniqueId());
            attachHordeGoals(mob);
            PaperApiAdapter.setShouldBurnInDay(mob, !plugin.getMainConfig().isSpawnInDayLight());
        } else if (mob instanceof Zombie) {
            attachBreakGoalOnly(mob);
        }
    }

    public void attachHordeGoals(@NotNull Mob mob) {
        MobGoals mobGoals = Bukkit.getMobGoals();
        GoalKey<Mob> hordeKey = HordeGoal.getKey(plugin);
        if (!mobGoals.hasGoal(mob, hordeKey)) {
            mobGoals.addGoal(mob, HordeGoal.PRIORITY, new HordeGoal(plugin, patrolData, mob));
        }
        attachBreakGoalOnly(mob);
    }

    private void attachBreakGoalOnly(@NotNull Mob mob) {
        MobGoals mobGoals = Bukkit.getMobGoals();
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

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onEntityCombust(@NotNull EntityCombustEvent event) {
        if (event instanceof EntityCombustByEntityEvent || event instanceof EntityCombustByBlockEvent) {
            return;
        }
        if (!plugin.getMainConfig().isSpawnInDayLight()) {
            return;
        }
        Entity entity = event.getEntity();
        if (entity instanceof Mob && (registry.contains(entity.getUniqueId()) || patrolData.isPatrolling(entity))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityDeath(@NotNull EntityDeathEvent event) {
        if (event.getEntity() instanceof Mob mob) {
            registry.remove(mob.getUniqueId());
            if (patrolData.isPatrolling(mob) || mob instanceof Zombie) {
                BreakBlockGoal.onMobRemoved(mob);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onEntityRemoveFromWorld(@NotNull EntityRemoveFromWorldEvent event) {
        Entity entity = event.getEntity();
        registry.remove(entity.getUniqueId());
        if (entity instanceof Mob mob && (patrolData.isPatrolling(mob) || mob instanceof Zombie)) {
            BreakBlockGoal.onMobRemoved(mob);
        }
    }
}
