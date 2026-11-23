package io.github.mebahel.zombiehorde.ai;

import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.GoalKey;
import com.destroystokyo.paper.entity.ai.GoalType;
import io.github.mebahel.zombiehorde.ZombieHordePlugin;
import io.github.mebahel.zombiehorde.config.MainConfig;
import io.github.mebahel.zombiehorde.horde.PatrolData;
import io.github.mebahel.zombiehorde.util.PaperApiAdapter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Glass and wooden fence breaking AI goal (FR-09, priority 1, no control flags).
 * Uses BreakProgressTracker for O(1) shared progress & stale expiry (N-01, Q6),
 * TickThrottle + bounded nearest-first raycast (N-01, NFR-04),
 * PaperApiAdapter crack semantics (N-08), and WorldGuard denied-block cooldown (D3-07).
 */
public final class BreakBlockGoal implements Goal<Mob> {

    public static final int PRIORITY = 1;
    public static final double VISION_RADIUS = 24.0;
    public static final int TICK_INTERVAL = 20;
    public static final int TARGET_SCAN_INTERVAL_TICKS = 20;
    public static final int MAX_PLAYERS_RAYCAST_PER_SCAN = 8;
    public static final int PROTECTED_BLOCK_COOLDOWN_TICKS = 200; // 10s backoff if WorldGuard cancels break (D3-07)
    private static final double CRACK_NOTIFY_RADIUS_SQ = 48.0 * 48.0;

    private static final BreakProgressTracker<BlockKey> TRACKER =
            new BreakProgressTracker<>(System::currentTimeMillis);

    private final ZombieHordePlugin plugin;
    private final PatrolData patrolData;
    private final Mob mob;
    private final GoalKey<Mob> key;
    private final TickThrottle scanThrottle = new TickThrottle();

    private int tickCounter;
    private BlockKey lastDeniedBlockKey;
    private long deniedCooldownUntilTick = Long.MIN_VALUE;

    public BreakBlockGoal(@NotNull ZombieHordePlugin plugin, @NotNull PatrolData patrolData, @NotNull Mob mob) {
        this.plugin = plugin;
        this.patrolData = patrolData;
        this.mob = mob;
        this.key = getKey(plugin);
        // D3-08: Lightweight per-mob phase jitter (0..9) to spread 60-mob CPU work across ticks
        this.tickCounter = Math.floorMod(mob.getUniqueId().hashCode(), 10);
    }

    @NotNull
    public static GoalKey<Mob> getKey(@NotNull ZombieHordePlugin plugin) {
        return GoalKey.of(Mob.class, new NamespacedKey(plugin, "break_block"));
    }

    /**
     * Q6 / N-09: Expires stale block-break entries (~5 seconds without update) and clears their crack overlays.
     * Called every 20 ticks from HordeManager's independent maintenance task.
     */
    public static void expireStale() {
        for (BlockKey expired : TRACKER.expire()) {
            resetCrackOverlay(expired);
        }
    }

    /**
     * Cleans up active block tracking when a mob dies or is removed from the world (Q6).
     */
    public static void onMobRemoved(@NotNull Mob mob) {
        BlockKey released = TRACKER.release(mob.getUniqueId());
        if (released != null) {
            resetCrackOverlay(released);
        }
    }

    /**
     * NFR-07: Clears all active crack animations and progress on plugin disable.
     */
    public static void clearAllProgress() {
        for (BlockKey key : TRACKER.clear()) {
            resetCrackOverlay(key);
        }
    }

    @Override
    public boolean shouldActivate() {
        if (!mob.isValid() || mob.isDead() || !patrolData.isPatrolling(mob)) {
            return false;
        }
        MainConfig cfg = plugin.getMainConfig();
        if (!cfg.isHordeMemberBreakGlass() && !cfg.isHordeMemberBreakFence()) {
            return false;
        }
        // Cheap path: already has a valid combat target, no raycast needed (N-01)
        if (isValidCombatTarget(mob.getTarget())) {
            return true;
        }
        // N-01: Throttle glass/fence vision scan in shouldActivate() to at most once per 20 ticks
        if (!scanThrottle.tryAcquire(mob.getTicksLived(), TARGET_SCAN_INTERVAL_TICKS)) {
            return false;
        }
        Player seen = findTargetThroughBreakable(cfg.isHordeMemberBreakGlass(), cfg.isHordeMemberBreakFence());
        if (seen == null) {
            return false;
        }
        mob.setTarget(seen);
        return true;
    }

    @Override
    public boolean shouldStayActive() {
        if (!mob.isValid() || mob.isDead() || !patrolData.isPatrolling(mob)) {
            return false;
        }
        MainConfig cfg = plugin.getMainConfig();
        if (!cfg.isHordeMemberBreakGlass() && !cfg.isHordeMemberBreakFence()) {
            return false;
        }
        return isValidCombatTarget(mob.getTarget());
    }

    @Override
    public void start() {
        tickCounter = Math.floorMod(mob.getUniqueId().hashCode(), 10);
    }

    @Override
    public void stop() {
        BlockKey released = TRACKER.release(mob.getUniqueId());
        if (released != null) {
            resetCrackOverlay(released);
        }
        tickCounter = 0;
    }

    @Override
    public void tick() {
        if (!mob.isValid() || mob.isDead()) {
            return;
        }
        tickCounter++;
        if (tickCounter < TICK_INTERVAL) {
            return;
        }
        tickCounter = 0;

        MainConfig cfg = plugin.getMainConfig();
        boolean breakGlass = cfg.isHordeMemberBreakGlass();
        boolean breakFence = cfg.isHordeMemberBreakFence();
        if (!breakGlass && !breakFence) {
            return;
        }

        // C4-05: shouldStayActive() already guarantees a valid combat target here (removed dead branch)
        Block foundBlock = findAdjacentBreakableBlock(breakGlass, breakFence);
        if (foundBlock == null) {
            BlockKey released = TRACKER.release(mob.getUniqueId());
            if (released != null) {
                resetCrackOverlay(released);
            }
            return;
        }

        BlockKey foundKey = BlockKey.of(foundBlock);
        // D3-07 + C4-05: Release any previously held block before backing off on a WorldGuard-denied block
        if (foundKey.equals(lastDeniedBlockKey) && mob.getTicksLived() < deniedCooldownUntilTick) {
            BlockKey released = TRACKER.release(mob.getUniqueId());
            if (released != null) {
                resetCrackOverlay(released);
            }
            return;
        }

        BreakProgressTracker.Result<BlockKey> result = TRACKER.contribute(mob.getUniqueId(), foundKey);
        if (result.released() != null) {
            resetCrackOverlay(result.released());
        }

        if (result.completed()) {
            resetCrackOverlay(foundKey);
            // Respect WorldGuard / protection plugins via EntityChangeBlockEvent (FR-09, N-10)
            if (PaperApiAdapter.canEntityBreakBlock(mob, foundBlock)) {
                foundBlock.breakNaturally();
                lastDeniedBlockKey = null;
            } else {
                lastDeniedBlockKey = foundKey;
                deniedCooldownUntilTick = mob.getTicksLived() + PROTECTED_BLOCK_COOLDOWN_TICKS;
            }
        } else {
            mob.swingMainHand();
            sendCrackOverlay(foundBlock.getLocation(), result.stage(), foundKey.deterministicSourceId());
        }
    }

    /**
     * Scans y from +2 down to -2, distance 1..2 along mob's horizontal facing (FR-09).
     */
    @Nullable
    private Block findAdjacentBreakableBlock(boolean breakGlass, boolean breakFence) {
        World world = mob.getWorld();
        Location loc = mob.getLocation();
        int baseX = loc.getBlockX();
        int baseY = loc.getBlockY();
        int baseZ = loc.getBlockZ();
        BlockFace facing = PaperApiAdapter.getHorizontalFacing(mob);
        int modX = facing.getModX();
        int modZ = facing.getModZ();

        for (int dy = 2; dy >= -2; dy--) {
            int y = baseY + dy;
            if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
                continue;
            }
            for (int dist = 1; dist <= 2; dist++) {
                int bx = baseX + modX * dist;
                int bz = baseZ + modZ * dist;
                if (!world.isChunkLoaded(bx >> 4, bz >> 4)) {
                    continue;
                }
                Block block = world.getBlockAt(bx, y, bz);
                if (BreakableBlocks.isBreakable(block.getType(), breakGlass, breakFence)) {
                    return block;
                }
            }
        }
        return null;
    }

    /**
     * FR-09 + N-01: Players within 24-block expanded box sorted nearest-first, raycasting at most
     * MAX_PLAYERS_RAYCAST_PER_SCAN players from eye-to-eye to bound worst-case CPU cost.
     */
    @Nullable
    private Player findTargetThroughBreakable(boolean breakGlass, boolean breakFence) {
        World world = mob.getWorld();
        Location mobEyes = mob.getEyeLocation();
        List<Player> candidates = new ArrayList<>();
        for (Entity nearby : mob.getNearbyEntities(VISION_RADIUS, VISION_RADIUS, VISION_RADIUS)) {
            if (nearby instanceof Player player && isValidCombatTarget(player)) {
                candidates.add(player);
            }
        }
        if (candidates.isEmpty()) {
            return null;
        }
        if (candidates.size() > 1) {
            candidates.sort(Comparator.comparingDouble(p -> p.getLocation().distanceSquared(mobEyes)));
        }

        int limit = Math.min(candidates.size(), MAX_PLAYERS_RAYCAST_PER_SCAN);
        for (int i = 0; i < limit; i++) {
            Player player = candidates.get(i);
            Location playerEyes = player.getEyeLocation();
            Vector delta = playerEyes.toVector().subtract(mobEyes.toVector());
            double dist = delta.length();
            if (dist < 0.01) {
                continue;
            }
            Vector direction = delta.multiply(1.0 / dist);
            RayTraceResult hit = PaperApiAdapter.rayTraceBlocks(world, mobEyes, direction, dist);
            if (hit != null && hit.getHitBlock() != null
                    && BreakableBlocks.isBreakable(hit.getHitBlock().getType(), breakGlass, breakFence)) {
                return player;
            }
        }
        return null;
    }

    private static boolean isValidCombatTarget(@Nullable LivingEntity target) {
        if (target == null || !target.isValid() || target.isDead()) {
            return false;
        }
        if (target instanceof Player player) {
            GameMode gm = player.getGameMode();
            return gm != GameMode.CREATIVE && gm != GameMode.SPECTATOR;
        }
        return true;
    }

    private static void sendCrackOverlay(@NotNull Location blockLoc, int stage, int sourceId) {
        World world = blockLoc.getWorld();
        if (world == null) {
            return;
        }
        float progress = PaperApiAdapter.crackStageToProgress(stage);
        for (Player player : world.getPlayers()) {
            // E-03: Consistent with D-06, use isOnline() rather than isValid()
            if (player.isOnline() && player.getLocation().distanceSquared(blockLoc) <= CRACK_NOTIFY_RADIUS_SQ) {
                PaperApiAdapter.sendBlockDamage(player, blockLoc, progress, sourceId);
            }
        }
    }

    private static void resetCrackOverlay(@NotNull BlockKey key) {
        World world = Bukkit.getWorld(key.worldId());
        if (world == null) {
            return;
        }
        Location blockLoc = new Location(world, key.x(), key.y(), key.z());
        int sourceId = key.deterministicSourceId();
        for (Player player : world.getPlayers()) {
            if (player.isOnline() && player.getLocation().distanceSquared(blockLoc) <= CRACK_NOTIFY_RADIUS_SQ) {
                PaperApiAdapter.clearCrack(player, blockLoc, sourceId);
            }
        }
    }

    @Override
    public @NotNull GoalKey<Mob> getKey() {
        return key;
    }

    @Override
    public @NotNull EnumSet<GoalType> getTypes() {
        return EnumSet.noneOf(GoalType.class);
    }

    public record BlockKey(@NotNull UUID worldId, int x, int y, int z) {
        @NotNull
        public static BlockKey of(@NotNull Block block) {
            return new BlockKey(block.getWorld().getUID(), block.getX(), block.getY(), block.getZ());
        }

        public int deterministicSourceId() {
            int hash = hashCode();
            return hash == Integer.MIN_VALUE ? 1 : Math.abs(hash) + 100000;
        }
    }
}
