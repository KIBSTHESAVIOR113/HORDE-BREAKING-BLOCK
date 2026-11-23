package io.github.mebahel.zombiehorde.horde;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Entity;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Manages PersistentDataContainer keys for horde patrol state on entities (FR-12, T1/Q9).
 */
public final class PatrolData {

    /**
     * Sentinel Y value indicating that a far target's surface Y has not yet been resolved
     * because its chunk was unloaded at spawn time (NFR-05).
     */
    public static final int UNRESOLVED_Y = Integer.MIN_VALUE;

    private final NamespacedKey patrolIdKey;
    private final NamespacedKey patrolLeaderKey;
    private final NamespacedKey patrollingKey;
    private final NamespacedKey patrolTargetKey;
    private final NamespacedKey savedBaseSpeedKey;

    public PatrolData(@NotNull Plugin plugin) {
        this.patrolIdKey = new NamespacedKey(plugin, "patrol_id");
        this.patrolLeaderKey = new NamespacedKey(plugin, "patrol_leader");
        this.patrollingKey = new NamespacedKey(plugin, "patrolling");
        this.patrolTargetKey = new NamespacedKey(plugin, "patrol_target");
        this.savedBaseSpeedKey = new NamespacedKey(plugin, "saved_base_speed");
    }

    public boolean isPatrolling(@NotNull Entity entity) {
        Byte val = entity.getPersistentDataContainer().get(patrollingKey, PersistentDataType.BYTE);
        return val != null && val == (byte) 1;
    }

    public void setPatrolling(@NotNull Entity entity, boolean patrolling) {
        entity.getPersistentDataContainer().set(patrollingKey, PersistentDataType.BYTE, patrolling ? (byte) 1 : (byte) 0);
    }

    public boolean isLeader(@NotNull Entity entity) {
        Byte val = entity.getPersistentDataContainer().get(patrolLeaderKey, PersistentDataType.BYTE);
        return val != null && val == (byte) 1;
    }

    public void setLeader(@NotNull Entity entity, boolean leader) {
        entity.getPersistentDataContainer().set(patrolLeaderKey, PersistentDataType.BYTE, leader ? (byte) 1 : (byte) 0);
    }

    @NotNull
    public String getPatrolId(@NotNull Entity entity) {
        String id = entity.getPersistentDataContainer().get(patrolIdKey, PersistentDataType.STRING);
        return id != null ? id : "";
    }

    public void setPatrolId(@NotNull Entity entity, @NotNull String patrolId) {
        entity.getPersistentDataContainer().set(patrolIdKey, PersistentDataType.STRING, patrolId);
    }

    @Nullable
    public BlockPos getPatrolTarget(@NotNull Entity entity) {
        int[] coords = entity.getPersistentDataContainer().get(patrolTargetKey, PersistentDataType.INTEGER_ARRAY);
        if (coords == null || coords.length != 3) {
            return null;
        }
        return new BlockPos(coords[0], coords[1], coords[2]);
    }

    public void setPatrolTarget(@NotNull Entity entity, @Nullable BlockPos target) {
        PersistentDataContainer pdc = entity.getPersistentDataContainer();
        if (target == null) {
            pdc.remove(patrolTargetKey);
        } else {
            pdc.set(patrolTargetKey, PersistentDataType.INTEGER_ARRAY, new int[]{target.x(), target.y(), target.z()});
        }
    }

    @Nullable
    public Double getSavedBaseSpeed(@NotNull Entity entity) {
        return entity.getPersistentDataContainer().get(savedBaseSpeedKey, PersistentDataType.DOUBLE);
    }

    public void setSavedBaseSpeed(@NotNull Entity entity, double baseSpeed) {
        entity.getPersistentDataContainer().set(savedBaseSpeedKey, PersistentDataType.DOUBLE, baseSpeed);
    }

    public void clearSavedBaseSpeed(@NotNull Entity entity) {
        entity.getPersistentDataContainer().remove(savedBaseSpeedKey);
    }

    public record BlockPos(int x, int y, int z) {
        public boolean hasResolvedY() {
            return y != UNRESOLVED_Y;
        }
    }
}
