package io.github.mebahel.zombiehorde.horde;

import io.github.mebahel.zombiehorde.ZombieHordePlugin;
import io.github.mebahel.zombiehorde.config.CompositionConfig;
import io.github.mebahel.zombiehorde.config.MainConfig;
import io.github.mebahel.zombiehorde.horde.PatrolData.BlockPos;
import io.github.mebahel.zombiehorde.util.PaperApiAdapter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import java.util.function.BiPredicate;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.inventory.EntityEquipment;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Handles horde spawn position calculation, member counts, entity creation, equipment, and announcements (FR-04..FR-07).
 */
public final class HordeSpawner {

    public static final int MAX_TARGET_RETRIES = 16;
    public static final int MAX_SPAWN_COLUMN_RETRIES = 12;

    public enum SpawnResult {
        SPAWNED,
        NO_PLAYER,
        NO_COMPOSITION,
        NO_LOADED_COLUMN,
        ZERO_MEMBERS
    }

    private final ZombieHordePlugin plugin;
    private final PatrolData patrolData;
    private final Difficulty difficulty;
    private final Random random;

    public HordeSpawner(
            @NotNull ZombieHordePlugin plugin,
            @NotNull PatrolData patrolData,
            @NotNull Difficulty difficulty
    ) {
        this.plugin = plugin;
        this.patrolData = patrolData;
        this.difficulty = difficulty;
        this.random = new Random();
    }

    public record FollowerRoll(int baseFollowers, int reinforcements, int total) {
    }

    public record SpawnColumn(int x, int z) {
    }

    /**
     * Single source of truth for follower count calculation per PRD FR-05b (F-2):
     * base = (4 + 2*d) + rand(1 + 2*d), plus optional rand(maxReinforcements) when > 0.
     */
    @NotNull
    public static FollowerRoll rollFollowers(int difficultyLevel, int maxReinforcements, @NotNull Random rng) {
        int d = difficultyLevel >= 2 ? 2 : 1;
        int base = (4 + 2 * d) + rng.nextInt(1 + 2 * d);
        int bonus = maxReinforcements > 0 ? rng.nextInt(maxReinforcements) : 0;
        return new FollowerRoll(base, bonus, base + bonus);
    }

    public static int calculateFollowerCount(int difficultyLevel, int maxReinforcements, @NotNull Random rng) {
        return rollFollowers(difficultyLevel, maxReinforcements, rng).total();
    }

    /**
     * Pure offset calculation for FR-04 (30..59 per axis, random sign).
     */
    public static int randomSpawnOffset(@NotNull Random rng) {
        int mag = 30 + rng.nextInt(30);
        return rng.nextBoolean() ? mag : -mag;
    }

    /**
     * Pure offset calculation for FR-04 step 5 (150..299 per axis, random sign).
     */
    public static int randomPatrolTargetOffset(@NotNull Random rng) {
        int mag = 150 + rng.nextInt(150);
        return rng.nextBoolean() ? mag : -mag;
    }

    /**
     * Pure offset calculation for FR-05 getOffset (5..9 per axis, random sign).
     */
    public static int randomMemberOffset(@NotNull Random rng) {
        int mag = 5 + rng.nextInt(5);
        return rng.nextBoolean() ? mag : -mag;
    }

    /**
     * Pure retry logic for selecting an acceptable spawn column 30..59 blocks from origin (P1, T3).
     * Never forces chunk loads; callers supply a predicate checking chunk loaded + non-void surface.
     */
    @Nullable
    public static SpawnColumn findSpawnColumn(
            int originX,
            int originZ,
            int maxRetries,
            @NotNull Random rng,
            @NotNull BiPredicate<Integer, Integer> isAcceptableColumn
    ) {
        int attempts = Math.max(1, maxRetries);
        for (int i = 0; i < attempts; i++) {
            int candX = originX + randomSpawnOffset(rng);
            int candZ = originZ + randomSpawnOffset(rng);
            if (isAcceptableColumn.test(candX, candZ)) {
                return new SpawnColumn(candX, candZ);
            }
        }
        return null;
    }

    /**
     * Spawns one horde in the given world near a random living player (or preferred anchor if provided).
     * Never performs synchronous chunk loads (NFR-05, F-1, P1, D-01, E-02, N-06).
     */
    @NotNull
    public SpawnResult spawnHorde(@NotNull World world, @Nullable Player preferredAnchor) {
        Player anchor = preferredAnchor;
        if (anchor == null || anchor.isDead() || !anchor.isValid() || !anchor.getWorld().equals(world)) {
            anchor = pickRandomAlivePlayer(world);
        }
        if (anchor == null) {
            return SpawnResult.NO_PLAYER;
        }

        CompositionConfig.HordeComposition composition = plugin.getCompositionConfig().pickComposition(random, world);
        if (composition == null || composition.getMobTypes() == null || composition.getMobTypes().isEmpty()) {
            plugin.getLogger().warning("No eligible horde composition found for world '" + world.getKey().asString() + "'.");
            return SpawnResult.NO_COMPOSITION;
        }

        Location playerLoc = anchor.getLocation();
        int minHeight = world.getMinHeight();

        // P1 + D-01: re-roll spawn column up to 12 times until chunk is loaded AND non-void
        SpawnColumn surfaceCol = findSpawnColumn(
                playerLoc.getBlockX(),
                playerLoc.getBlockZ(),
                MAX_SPAWN_COLUMN_RETRIES,
                random,
                (x, z) -> world.isChunkLoaded(x >> 4, z >> 4)
                        && PaperApiAdapter.getHighestSurfaceY(world, x, z) > minHeight
        );
        if (surfaceCol == null) {
            plugin.getLogger().warning("Aborted horde spawn: no loaded non-void chunk found 30..59 blocks from anchor player after "
                    + MAX_SPAWN_COLUMN_RETRIES + " attempts (avoiding sync chunk load per NFR-05).");
            return SpawnResult.NO_LOADED_COLUMN;
        }
        int surfaceX = surfaceCol.x();
        int surfaceZ = surfaceCol.z();

        int leaderX = surfaceX + randomMemberOffset(random);
        int leaderZ = surfaceZ + randomMemberOffset(random);
        if (!world.isChunkLoaded(leaderX >> 4, leaderZ >> 4)
                || PaperApiAdapter.getHighestSurfaceY(world, leaderX, leaderZ) <= minHeight) {
            leaderX = surfaceX;
            leaderZ = surfaceZ;
        }
        int leaderY = PaperApiAdapter.getHighestSurfaceY(world, leaderX, leaderZ) + 1;

        BlockPos finalTarget = pickFarPatrolTarget(world, surfaceX, surfaceZ);
        String patrolId = UUID.randomUUID().toString();
        int diffLevel = difficulty.getEffectiveLevel(world);

        MainConfig mainConfig = plugin.getMainConfig();
        FollowerRoll followerRoll = rollFollowers(diffLevel, mainConfig.getRandomNumberHordeReinforcements(), random);
        if (mainConfig.getRandomNumberHordeReinforcements() > 0) {
            plugin.getLogger().info("Horde reinforcements added: +" + followerRoll.reinforcements() + " members.");
        }

        List<UUID> spawnedMemberIds = new ArrayList<>(followerRoll.total() + 1);

        // Spawn leader (FR-06)
        Mob leader = spawnLeader(world, leaderX, leaderY, leaderZ, composition, finalTarget, patrolId, diffLevel);
        if (leader != null) {
            spawnedMemberIds.add(leader.getUniqueId());
        }

        // Spawn followers with cumulative random walk cursor (FR-06, F-1 chunk-loaded & non-void guard)
        int curX = surfaceX;
        int curZ = surfaceZ;
        for (int i = 0; i < followerRoll.total(); i++) {
            CompositionConfig.MobTypeEntry entry = composition.pickMobType(random);
            if (entry == null) {
                plugin.getLogger().warning("Failed to pick mob type for follower; skipping iteration.");
                continue;
            }
            EntityType entityType = CompositionConfig.resolveEntityType(entry.getId());
            if (entityType == null) {
                plugin.getLogger().warning("Invalid mob type '" + entry.getId() + "' for follower; skipping iteration (Q3).");
                continue;
            }

            int spawnX = curX;
            int spawnZ = curZ;
            if (!world.isChunkLoaded(spawnX >> 4, spawnZ >> 4)
                    || PaperApiAdapter.getHighestSurfaceY(world, spawnX, spawnZ) <= minHeight) {
                // F-1 + D-01 (A-001): Clamp back to loaded non-void surfacePos
                spawnX = surfaceX;
                spawnZ = surfaceZ;
                curX = surfaceX;
                curZ = surfaceZ;
            }
            int spawnY = PaperApiAdapter.getHighestSurfaceY(world, spawnX, spawnZ) + 1;
            Location spawnLoc = new Location(world, spawnX + 0.5, spawnY, spawnZ + 0.5);
            Mob spawned = spawnSingleMember(world, spawnLoc, entityType, entry, finalTarget, patrolId, false, diffLevel);
            if (spawned == null) {
                plugin.getLogger().warning("Failed to spawn follower entity '" + entry.getId() + "'; skipping iteration.");
                continue;
            }
            spawnedMemberIds.add(spawned.getUniqueId());
            curX += randomMemberOffset(random);
            curZ += randomMemberOffset(random);
        }

        // E-02: Abort cleanly without phantom broadcast if zero members were spawned
        if (spawnedMemberIds.isEmpty()) {
            plugin.getLogger().warning("Horde spawn produced 0 members (all mob types invalid or spawn cancelled); skipping broadcast.");
            return SpawnResult.ZERO_MEMBERS;
        }

        // Async resolution for far patrol target if chunk was unloaded (NFR-05, F-8a, D3-01)
        if (!finalTarget.hasResolvedY()) {
            scheduleAsyncTargetYResolution(world, surfaceX, surfaceZ, finalTarget, spawnedMemberIds);
        }

        plugin.getLogger().info("Spawned horde (id=" + patrolId + ", difficulty=" + diffLevel
                + ", members=" + spawnedMemberIds.size() + ") near " + leaderX + ", " + leaderY + ", " + leaderZ
                + " in " + world.getName() + ".");

        // Broadcast spawn message to all online players across all worlds (FR-06, Q8)
        if (mainConfig.isShowHordeSpawningMessage()) {
            Component msg = Component.text("A horde has spawned near " + leaderX + ", " + leaderY + ", " + leaderZ + ".");
            for (Player online : Bukkit.getOnlinePlayers()) {
                online.sendMessage(msg);
            }
        }
        return SpawnResult.SPAWNED;
    }

    @Nullable
    private Mob spawnLeader(
            @NotNull World world,
            int leaderX,
            int leaderY,
            int leaderZ,
            @NotNull CompositionConfig.HordeComposition composition,
            @NotNull BlockPos finalTarget,
            @NotNull String patrolId,
            int diffLevel
    ) {
        CompositionConfig.MobTypeEntry leaderEntry = composition.pickMobType(random);
        if (leaderEntry == null) {
            plugin.getLogger().severe("Failed to select leader mob type; followers will still spawn.");
            return null;
        }
        EntityType entityType = CompositionConfig.resolveEntityType(leaderEntry.getId());
        if (entityType == null) {
            plugin.getLogger().severe("Invalid leader mob type '" + leaderEntry.getId() + "'; followers will still spawn.");
            return null;
        }
        Location leaderLoc = new Location(world, leaderX + 0.5, leaderY, leaderZ + 0.5);
        Mob leader = spawnSingleMember(world, leaderLoc, entityType, leaderEntry, finalTarget, patrolId, true, diffLevel);
        if (leader == null) {
            plugin.getLogger().severe("Failed to spawn leader entity '" + leaderEntry.getId() + "'; followers will still spawn.");
        }
        return leader;
    }

    @Nullable
    private Mob spawnSingleMember(
            @NotNull World world,
            @NotNull Location location,
            @NotNull EntityType entityType,
            @NotNull CompositionConfig.MobTypeEntry entry,
            @NotNull BlockPos finalTarget,
            @NotNull String patrolId,
            boolean isLeader,
            int diffLevel
    ) {
        MainConfig mainConfig = plugin.getMainConfig();
        return PaperApiAdapter.spawnMobWithoutRandomization(world, location, entityType, mob -> {
            // Set PDC BEFORE entity enters world so EntityAddToWorldEvent sees it (FR-06, FR-09b)
            patrolData.setPatrolling(mob, true);
            patrolData.setPatrolId(mob, patrolId);
            patrolData.setLeader(mob, isLeader);
            patrolData.setPatrolTarget(mob, finalTarget);

            mob.setRemoveWhenFarAway(false);

            if (mainConfig.isSpawnInDayLight()) {
                PaperApiAdapter.setShouldBurnInDay(mob, false);
            }

            int bonusHealth = mainConfig.getHordeMemberBonusHealth();
            if (bonusHealth > 0) {
                AttributeInstance maxHealthAttr = PaperApiAdapter.getMaxHealthAttribute(mob);
                if (maxHealthAttr != null) {
                    double newMax = maxHealthAttr.getBaseValue() + bonusHealth;
                    maxHealthAttr.setBaseValue(newMax);
                    // D-02: Use clamped effective value to avoid IllegalArgumentException
                    double effectiveMax = maxHealthAttr.getValue();
                    if (effectiveMax > 0.0) {
                        mob.setHealth(effectiveMax);
                    }
                }
            }

            equipMember(mob, entityType, entry, diffLevel);
        });
    }

    private void equipMember(
            @NotNull Mob mob,
            @NotNull EntityType entityType,
            @NotNull CompositionConfig.MobTypeEntry entry,
            int diffLevel
    ) {
        EntityEquipment equipment = mob.getEquipment();
        if (equipment == null) {
            return;
        }

        float armorChance = 0.06f * diffLevel;
        if (random.nextFloat() < armorChance) {
            equipment.setHelmet(new ItemStack(PaperApiAdapter.ARMOR_HELMET));
        }
        if (random.nextFloat() < armorChance) {
            equipment.setChestplate(new ItemStack(PaperApiAdapter.ARMOR_CHESTPLATE));
        }
        if (random.nextFloat() < armorChance) {
            equipment.setLeggings(new ItemStack(PaperApiAdapter.ARMOR_LEGGINGS));
        }
        if (random.nextFloat() < armorChance) {
            equipment.setBoots(new ItemStack(PaperApiAdapter.ARMOR_BOOTS));
        }

        // Main-hand weapon (FR-07)
        if (PaperApiAdapter.ALWAYS_ARMED_TYPES.contains(entityType)) {
            Material chosen = pickValidWeaponMaterial(entry.getWeapons());
            if (chosen == null) {
                chosen = (entityType == EntityType.PILLAGER)
                        ? PaperApiAdapter.PILLAGER_DEFAULT_WEAPON
                        : PaperApiAdapter.VINDICATOR_DEFAULT_WEAPON;
            }
            equipment.setItemInMainHand(new ItemStack(chosen));
            return;
        }

        float weaponChance = entry.getSpawnWithWeaponProbability() != null
                ? entry.getSpawnWithWeaponProbability()
                : (0.13f * diffLevel);
        if (random.nextFloat() < weaponChance) {
            Material chosen = pickValidWeaponMaterial(entry.getWeapons());
            if (chosen != null) {
                equipment.setItemInMainHand(new ItemStack(chosen));
            }
        }
    }

    @Nullable
    private Material pickValidWeaponMaterial(@Nullable List<CompositionConfig.WeaponEntry> weapons) {
        CompositionConfig.WeaponEntry picked = CompositionConfig.pickWeapon(weapons, random);
        if (picked == null) {
            return null;
        }
        return CompositionConfig.resolveMaterial(picked.getItemId());
    }

    /**
     * Picks a far patrol target 150..299 blocks away per axis (FR-04 step 5) without forcing sync chunk loads (NFR-05).
     */
    @NotNull
    public BlockPos pickFarPatrolTarget(@NotNull World world, int originX, int originZ) {
        int minHeight = world.getMinHeight();
        for (int attempt = 0; attempt < MAX_TARGET_RETRIES; attempt++) {
            int tx = originX + randomPatrolTargetOffset(random);
            int tz = originZ + randomPatrolTargetOffset(random);
            if (world.isChunkLoaded(tx >> 4, tz >> 4)) {
                int ty = PaperApiAdapter.getHighestSurfaceY(world, tx, tz);
                if (ty > minHeight) {
                    return new BlockPos(tx, ty + 1, tz);
                }
            } else {
                return new BlockPos(tx, PatrolData.UNRESOLVED_Y, tz);
            }
        }
        int tx = originX + randomPatrolTargetOffset(random);
        int tz = originZ + randomPatrolTargetOffset(random);
        return new BlockPos(tx, PatrolData.UNRESOLVED_Y, tz);
    }

    /**
     * D3-01: Only updates a member's patrolTarget if it still equals the original unresolved target,
     * preventing stale async callbacks from overwriting a newer patrolTarget chosen by the leader.
     */
    private void scheduleAsyncTargetYResolution(
            @NotNull World world,
            int originX,
            int originZ,
            @NotNull BlockPos expectedUnresolvedTarget,
            @NotNull List<UUID> memberIds
    ) {
        int chunkX = expectedUnresolvedTarget.x() >> 4;
        int chunkZ = expectedUnresolvedTarget.z() >> 4;
        world.getChunkAtAsync(chunkX, chunkZ, false).thenAccept(chunk -> {
            if (chunk == null || !plugin.isEnabled()) {
                return;
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (!plugin.isEnabled() || !world.isChunkLoaded(chunkX, chunkZ)) {
                    return;
                }
                int ty = PaperApiAdapter.getHighestSurfaceY(world, expectedUnresolvedTarget.x(), expectedUnresolvedTarget.z());
                BlockPos resolved = (ty > world.getMinHeight())
                        ? new BlockPos(expectedUnresolvedTarget.x(), ty + 1, expectedUnresolvedTarget.z())
                        : pickFarPatrolTarget(world, originX, originZ);
                for (UUID id : memberIds) {
                    Entity entity = Bukkit.getEntity(id);
                    if (entity instanceof Mob mob
                            && mob.isValid()
                            && patrolData.isPatrolling(mob)
                            && expectedUnresolvedTarget.equals(patrolData.getPatrolTarget(mob))) {
                        patrolData.setPatrolTarget(mob, resolved);
                    }
                }
            });
        });
    }

    @Nullable
    private Player pickRandomAlivePlayer(@NotNull World world) {
        List<Player> alive = new ArrayList<>();
        for (Player player : world.getPlayers()) {
            if (!player.isDead() && player.isValid()) {
                alive.add(player);
            }
        }
        if (alive.isEmpty()) {
            return null;
        }
        return alive.get(random.nextInt(alive.size()));
    }
}
