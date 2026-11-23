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
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Zombie;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Fast obstructing-block breaking AI goal for both horde members and regular Zombies.
 * Breaks only blocks directly obstructing the path/line-of-sight within realistic melee reach:
 * max 2.2 blocks distance, vertically bounded to [mobY - 1, mobY + 2] (at most 1 block above head).
 */
public final class BreakBlockGoal implements Goal<Mob> {

    public static final int PRIORITY = 1;
    public static final double VISION_RADIUS = 24.0;
    public static final double BREAK_REACH = 2.2;
    public static final double MAX_HORIZ_REACH_SQ = 2.3 * 2.3;
    public static final int MAX_ABOVE_FEET_Y = 2; // feet=Y, head=Y+1, ceiling directly above head=Y+2
    public static final int TICK_INTERVAL = 5; // 5 ticks (0.25s) per stage -> ~2.5s for 10 stages (1 zombie)
    public static final int TARGET_SCAN_INTERVAL_TICKS = 20;
    public static final int MAX_PLAYERS_RAYCAST_PER_SCAN = 8;
    public static final int PROTECTED_BLOCK_COOLDOWN_TICKS = 200;
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
        this.tickCounter = Math.floorMod(mob.getUniqueId().hashCode(), TICK_INTERVAL);
    }

    @NotNull
    public static GoalKey<Mob> getKey(@NotNull ZombieHordePlugin plugin) {
        return GoalKey.of(Mob.class, new NamespacedKey(plugin, "break_block"));
    }

    public static void expireStale() {
        for (BlockKey expired : TRACKER.expire()) {
            resetCrackOverlay(expired);
        }
    }

    public static void onMobRemoved(@NotNull Mob mob) {
        BlockKey released = TRACKER.release(mob.getUniqueId());
        if (released != null) {
            resetCrackOverlay(released);
        }
    }

    public static void clearAllProgress() {
        for (BlockKey key : TRACKER.clear()) {
            resetCrackOverlay(key);
        }
    }

    private boolean isEligibleBreaker() {
        return patrolData.isPatrolling(mob) || mob instanceof Zombie;
    }

    @Override
    public boolean shouldActivate() {
        if (!mob.isValid() || mob.isDead() || !isEligibleBreaker()) {
            return false;
        }
        MainConfig cfg = plugin.getMainConfig();
        if (!cfg.isHordeMemberBreakGlass() && !cfg.isHordeMemberBreakFence()) {
            return false;
        }
        if (isValidCombatTarget(mob.getTarget())) {
            return true;
        }
        if (!scanThrottle.tryAcquire(mob.getTicksLived(), TARGET_SCAN_INTERVAL_TICKS)) {
            return false;
        }
        Player seen = findTargetThroughBreakable();
        if (seen == null) {
            return false;
        }
        mob.setTarget(seen);
        return true;
    }

    @Override
    public boolean shouldStayActive() {
        if (!mob.isValid() || mob.isDead() || !isEligibleBreaker()) {
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
        tickCounter = Math.floorMod(mob.getUniqueId().hashCode(), TICK_INTERVAL);
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
        if (!cfg.isHordeMemberBreakGlass() && !cfg.isHordeMemberBreakFence()) {
            return;
        }

        LivingEntity target = mob.getTarget();
        if (!isValidCombatTarget(target)) {
            return;
        }

        Block foundBlock = findObstructingBlock(target);
        if (foundBlock == null) {
            BlockKey released = TRACKER.release(mob.getUniqueId());
            if (released != null) {
                resetCrackOverlay(released);
            }
            return;
        }

        BlockKey foundKey = BlockKey.of(foundBlock);
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
     * Finds the closest breakable block directly obstructing the mob from reaching its target
     * via eye-to-eye and body-to-body raycasts within BREAK_REACH (2.2 blocks).
     * Strictly limits vertical reach to [mobY - 1, mobY + 2] (never 3-5 blocks above head),
     * never digs floor blocks unless target is below the mob, and skips jumpable 1-block terrain steps.
     */
    @Nullable
    private Block findObstructingBlock(@NotNull LivingEntity target) {
        World world = mob.getWorld();
        if (!world.equals(target.getWorld())) {
            return null;
        }

        Location mobLoc = mob.getLocation();
        int mobBlockY = mobLoc.getBlockY();
        int targetBlockY = target.getLocation().getBlockY();

        // 1. Eye-to-eye raycast (detects walls, windows, ceiling directly above head)
        Block eyeHit = raycastObstructingBlock(world, mobLoc, mob.getEyeLocation(), target.getEyeLocation(), mobBlockY, targetBlockY);
        if (eyeHit != null) {
            return eyeHit;
        }

        // 2. Body-to-body raycast (detects fences, lower walls, doors blocking movement)
        Location mobBody = mobLoc.clone().add(0.0, 0.5, 0.0);
        Location targetBody = target.getLocation().add(0.0, 0.5, 0.0);
        return raycastObstructingBlock(world, mobLoc, mobBody, targetBody, mobBlockY, targetBlockY);
    }

    @Nullable
    private Block raycastObstructingBlock(
            @NotNull World world,
            @NotNull Location mobLoc,
            @NotNull Location from,
            @NotNull Location to,
            int mobBlockY,
            int targetBlockY
    ) {
        Vector delta = to.toVector().subtract(from.toVector());
        double dist = delta.length();
        if (dist < 0.05) {
            return null;
        }
        double maxDist = Math.min(dist, BREAK_REACH);
        Vector dir = delta.multiply(1.0 / dist);
        RayTraceResult hit = PaperApiAdapter.rayTraceBlocks(world, from, dir, maxDist);
        if (hit == null || hit.getHitBlock() == null) {
            return null;
        }

        Block block = hit.getHitBlock();
        if (!BreakableBlocks.isAnyBreakableBlock(block.getType())) {
            return null;
        }

        int hitY = block.getY();

        // Vertical ceiling clamp: never break blocks more than 1 block above the zombie's head (mobY + 2)
        if (hitY > mobBlockY + MAX_ABOVE_FEET_Y) {
            return null;
        }

        // Never break blocks more than 1 block below feet, and never dig floor unless target is below the mob
        if (hitY < mobBlockY - 1) {
            return null;
        }
        if (hitY < mobBlockY && targetBlockY >= mobBlockY - 1) {
            return null;
        }

        // Horizontal distance guard (<= 2.3 blocks from mob center to block center)
        double hdx = (block.getX() + 0.5) - mobLoc.getX();
        double hdz = (block.getZ() + 0.5) - mobLoc.getZ();
        if (hdx * hdx + hdz * hdz > MAX_HORIZ_REACH_SQ) {
            return null;
        }

        // Skip normal 1-block jumpable steps (like grass hills/stairs) when the space above is open
        if (hitY == mobBlockY) {
            Block above = world.getBlockAt(block.getX(), hitY + 1, block.getZ());
            if (above.isPassable() && block.getBoundingBox().getHeight() <= 1.0) {
                return null;
            }
        }

        return block;
    }

    @Nullable
    private Player findTargetThroughBreakable() {
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
                    && BreakableBlocks.isAnyBreakableBlock(hit.getHitBlock().getType())) {
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
