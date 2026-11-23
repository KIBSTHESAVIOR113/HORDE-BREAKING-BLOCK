package io.github.mebahel.zombiehorde.util;

import com.destroystokyo.paper.entity.Pathfinder;
import java.util.EnumSet;
import java.util.Set;
import java.util.function.Consumer;
import org.bukkit.Bukkit;
import org.bukkit.FluidCollisionMode;
import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.event.entity.CreatureSpawnEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Isolates Paper 26.3 API calls and inferred constants ([I] / [verify]) in one class
 * so any future API signature or material adjustment requires editing only this file.
 */
public final class PaperApiAdapter {

    // [I] Inferred armor set from original mod (FR-07)
    public static final Material ARMOR_HELMET = Material.IRON_HELMET;
    public static final Material ARMOR_CHESTPLATE = Material.IRON_CHESTPLATE;
    public static final Material ARMOR_LEGGINGS = Material.IRON_LEGGINGS;
    public static final Material ARMOR_BOOTS = Material.IRON_BOOTS;

    // [I] Inferred default weapons for Pillager and Vindicator (FR-07)
    public static final Material PILLAGER_DEFAULT_WEAPON = Material.CROSSBOW;
    public static final Material VINDICATOR_DEFAULT_WEAPON = Material.IRON_AXE;

    // [I] Entity types that always receive a weapon from the composition table or default (FR-07)
    public static final Set<EntityType> ALWAYS_ARMED_TYPES = EnumSet.of(
            EntityType.PILLAGER,
            EntityType.VINDICATOR
    );

    // [I] Heightmap matching original mod surface projection without tree leaves (FR-04)
    public static final HeightMap SURFACE_HEIGHTMAP = HeightMap.MOTION_BLOCKING_NO_LEAVES;

    public static final int CRACK_MAX_STAGE = 10;

    private PaperApiAdapter() {
    }

    /**
     * Spawns a Mob with randomizeData=false and SpawnReason.CUSTOM, running preSpawn before world addition.
     * Ensures no vanilla baby zombie, jockey, random equipment, or ground item pickup is applied (FR-06, D3-02).
     */
    @Nullable
    public static Mob spawnMobWithoutRandomization(
            @NotNull World world,
            @NotNull Location location,
            @NotNull EntityType entityType,
            @NotNull Consumer<Mob> preSpawn
    ) {
        Class<? extends Entity> entityClass = entityType.getEntityClass();
        if (entityClass == null || !Mob.class.isAssignableFrom(entityClass)) {
            return null;
        }
        @SuppressWarnings("unchecked")
        Class<? extends Mob> mobClass = (Class<? extends Mob>) entityClass;

        // VERIFY-API: World#spawn(Location, Class, SpawnReason, boolean randomizeData, Consumer)
        Mob spawned = world.spawn(
                location,
                mobClass,
                CreatureSpawnEvent.SpawnReason.CUSTOM,
                false,
                mob -> {
                    if (mob instanceof Ageable ageable) {
                        ageable.setAdult();
                    }
                    // D3-02: Prevent horde mobs from picking up dropped items that override FR-07 gear
                    mob.setCanPickupItems(false);
                    EntityEquipment equipment = mob.getEquipment();
                    if (equipment != null) {
                        equipment.clear();
                    }
                    preSpawn.accept(mob);
                }
        );
        // C4-04: If CreatureSpawnEvent was cancelled (e.g. WorldGuard), spawned.isValid() is false
        if (spawned == null || !spawned.isValid()) {
            return null;
        }
        return spawned;
    }

    public static int getHighestSurfaceY(@NotNull World world, int blockX, int blockZ) {
        return world.getHighestBlockYAt(blockX, blockZ, SURFACE_HEIGHTMAP);
    }

    public static boolean hasPath(@NotNull Mob mob) {
        // VERIFY-API: Pathfinder#hasPath()
        return mob.getPathfinder().hasPath();
    }

    @Nullable
    public static Pathfinder.PathResult findPath(@NotNull Mob mob, @NotNull Location target) {
        // VERIFY-API: Pathfinder#findPath(Location)
        return mob.getPathfinder().findPath(target);
    }

    public static boolean moveTo(@NotNull Mob mob, @NotNull Pathfinder.PathResult path, double speed) {
        // VERIFY-API: Pathfinder#moveTo(PathResult, double)
        return mob.getPathfinder().moveTo(path, speed);
    }

    public static boolean moveTo(@NotNull Mob mob, @NotNull Location target, double speed) {
        // VERIFY-API: Pathfinder#moveTo(Location, double)
        return mob.getPathfinder().moveTo(target, speed);
    }

    public static void stopPathfinding(@NotNull Mob mob) {
        // VERIFY-API: Pathfinder#stopPathfinding()
        mob.getPathfinder().stopPathfinding();
    }

    /**
     * Pure mapping from discrete crack stage (0..10) to Paper's [0.0f, 1.0f] progress float (N-08).
     * Stages 1..9 map to 0.1f..0.9f; <= 0 maps to 0.0f (clear); >= 10 maps to 1.0f.
     */
    public static float crackStageToProgress(int stage) {
        if (stage <= 0) {
            return 0.0f;
        }
        if (stage >= CRACK_MAX_STAGE) {
            return 1.0f;
        }
        return stage / (float) CRACK_MAX_STAGE;
    }

    /**
     * Sends block break crack animation to a player (N-08).
     * Paper expects progress in [0.0f, 1.0f]; 0.0f clears the crack overlay on the client (RUNTIME OPEN-RISK A-013).
     */
    public static void sendBlockDamage(@NotNull Player player, @NotNull Location blockLocation, float progress, int sourceId) {
        float clamped = Math.max(0.0f, Math.min(1.0f, progress));
        // VERIFY-API: Player#sendBlockDamage(Location, float, int)
        player.sendBlockDamage(blockLocation, clamped, sourceId);
    }

    /**
     * Clears any client-side block crack overlay sent for sourceId (N-08, RUNTIME OPEN-RISK A-013).
     */
    public static void clearCrack(@NotNull Player player, @NotNull Location blockLocation, int sourceId) {
        sendBlockDamage(player, blockLocation, 0.0f, sourceId);
    }

    public static void setShouldBurnInDay(@NotNull Mob mob, boolean shouldBurnInDay) {
        // VERIFY-API: Zombie#setShouldBurnInDay(boolean)
        if (mob instanceof Zombie zombie) {
            zombie.setShouldBurnInDay(shouldBurnInDay);
        }
    }

    /**
     * Single isolated call site for EntityChangeBlockEvent (N-10) to respect region protection plugins (e.g. WorldGuard).
     * Returns true if the block break is allowed (event was not cancelled).
     */
    public static boolean canEntityBreakBlock(@NotNull Mob mob, @NotNull Block block) {
        // VERIFY-API: EntityChangeBlockEvent(Entity, Block, BlockData) - constructor noted non-stable in Javadoc (N-10)
        EntityChangeBlockEvent event = new EntityChangeBlockEvent(mob, block, Material.AIR.createBlockData());
        Bukkit.getPluginManager().callEvent(event);
        return !event.isCancelled();
    }

    @Nullable
    public static RayTraceResult rayTraceBlocks(
            @NotNull World world,
            @NotNull Location start,
            @NotNull Vector direction,
            double maxDistance
    ) {
        return world.rayTraceBlocks(start, direction, maxDistance, FluidCollisionMode.NEVER, false);
    }

    @NotNull
    public static BlockFace getHorizontalFacing(@NotNull Entity entity) {
        BlockFace facing = entity.getFacing();
        return switch (facing) {
            case NORTH, SOUTH, EAST, WEST -> facing;
            default -> yawToCardinal(entity.getLocation().getYaw());
        };
    }

    @NotNull
    public static BlockFace yawToCardinal(float yaw) {
        float normalized = ((yaw % 360.0f) + 360.0f) % 360.0f;
        if (normalized >= 315.0f || normalized < 45.0f) {
            return BlockFace.SOUTH;
        }
        if (normalized < 135.0f) {
            return BlockFace.WEST;
        }
        if (normalized < 225.0f) {
            return BlockFace.NORTH;
        }
        return BlockFace.EAST;
    }

    @Nullable
    public static AttributeInstance getMaxHealthAttribute(@NotNull Mob mob) {
        return mob.getAttribute(Attribute.MAX_HEALTH);
    }

    @Nullable
    public static AttributeInstance getMovementSpeedAttribute(@NotNull Mob mob) {
        return mob.getAttribute(Attribute.MOVEMENT_SPEED);
    }
}
