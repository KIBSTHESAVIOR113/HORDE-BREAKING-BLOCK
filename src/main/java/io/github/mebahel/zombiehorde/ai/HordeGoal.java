package io.github.mebahel.zombiehorde.ai;

import com.destroystokyo.paper.entity.Pathfinder;
import com.destroystokyo.paper.entity.ai.Goal;
import com.destroystokyo.paper.entity.ai.GoalKey;
import com.destroystokyo.paper.entity.ai.GoalType;
import io.github.mebahel.zombiehorde.ZombieHordePlugin;
import io.github.mebahel.zombiehorde.horde.PatrolData;
import io.github.mebahel.zombiehorde.horde.PatrolData.BlockPos;
import io.github.mebahel.zombiehorde.util.PaperApiAdapter;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Horde patrol AI goal (FR-08, priority 4, GoalType.MOVE).
 * Handles group movement with WaypointLadder retry (N-02), throttled target sharing (T2),
 * deterministic leader promotion & periodic convergence (P2, D-03), far-player despawn (D-06),
 * path failure hysteresis (D-04, D-05), per-mob jitter (D3-08), and crash-safe MOVEMENT_SPEED = 0.27 (Q9 / T1).
 */
public final class HordeGoal implements Goal<Mob> {

    public static final int PRIORITY = 4;
    public static final double LEADER_SPEED = 0.93;
    public static final double FOLLOW_SPEED = 0.93;
    public static final double PATROL_MOVEMENT_SPEED = 0.27;
    public static final double DESPAWN_DISTANCE_SQ = 60000.0; // ~244.95 blocks (FR-08)
    public static final int MIN_DESPAWN_AGE_TICKS = 20;
    public static final double HORDE_SEARCH_RADIUS = 64.0;
    public static final double MAX_WAYPOINT_STEP = 32.0;
    public static final int WANDER_RADIUS = 8;

    private static final int SHARE_TARGET_COOLDOWN_TICKS = 10;
    private static final int LEADER_SCAN_COOLDOWN_TICKS = 20;
    private static final int LEADER_CONVERGENCE_INTERVAL_TICKS = 100;
    private static final int DESPAWN_CHECK_INTERVAL_TICKS = 20;
    private static final int PATH_RECALC_COOLDOWN_TICKS = 10;
    private static final int MAX_CONSECUTIVE_PATH_FAILURES = 3;
    private static final int MAX_PATH_ATTEMPTS_PER_CALL = 3;

    private final ZombieHordePlugin plugin;
    private final PatrolData patrolData;
    private final Mob mob;
    private final GoalKey<Mob> key;
    private final Random random;

    private final double baseSpeed;
    private UUID assignedLeaderUuid;
    private long lastShareTick = -100L;
    private long lastLeaderScanTick;
    private long lastLeaderConvergenceTick;
    private long lastPathRecalcTick;
    private int despawnCheckCounter;
    private int consecutivePathFailures = 0;

    public HordeGoal(@NotNull ZombieHordePlugin plugin, @NotNull PatrolData patrolData, @NotNull Mob mob) {
        this.plugin = plugin;
        this.patrolData = patrolData;
        this.mob = mob;
        this.key = getKey(plugin);
        this.random = new Random();

        // D3-08: Stagger initial scan/path/despawn phases so 60 simultaneous horde mobs don't spike the same tick
        int jitter = Math.floorMod(mob.getUniqueId().hashCode(), 10);
        this.despawnCheckCounter = Math.floorMod(mob.getUniqueId().hashCode(), DESPAWN_CHECK_INTERVAL_TICKS);
        this.lastLeaderScanTick = -LEADER_SCAN_COOLDOWN_TICKS + jitter;
        this.lastLeaderConvergenceTick = -LEADER_CONVERGENCE_INTERVAL_TICKS + jitter;
        this.lastPathRecalcTick = -PATH_RECALC_COOLDOWN_TICKS + jitter;

        // T1 (Q9): Recover original base MOVEMENT_SPEED if server crashed while goal was active
        AttributeInstance speedAttr = PaperApiAdapter.getMovementSpeedAttribute(mob);
        Double savedBase = patrolData.getSavedBaseSpeed(mob);
        if (savedBase != null) {
            this.baseSpeed = savedBase;
            if (speedAttr != null) {
                speedAttr.setBaseValue(savedBase);
            }
            patrolData.clearSavedBaseSpeed(mob);
        } else {
            this.baseSpeed = speedAttr != null ? speedAttr.getBaseValue() : 0.23;
        }
    }

    @NotNull
    public static GoalKey<Mob> getKey(@NotNull ZombieHordePlugin plugin) {
        return GoalKey.of(Mob.class, new NamespacedKey(plugin, "horde_patrol"));
    }

    /**
     * Pure deterministic leader election helper: picks the lexicographically smallest UUID (P2, T3).
     */
    @Nullable
    public static UUID selectLowestUuid(@Nullable UUID initial, @Nullable Iterable<UUID> candidates) {
        UUID best = initial;
        if (candidates != null) {
            for (UUID candidate : candidates) {
                if (candidate == null) {
                    continue;
                }
                if (best == null || candidate.compareTo(best) < 0) {
                    best = candidate;
                }
            }
        }
        return best;
    }

    @Override
    public boolean shouldActivate() {
        if (!mob.isValid() || mob.isDead() || !patrolData.isPatrolling(mob)) {
            return false;
        }
        LivingEntity currentTarget = mob.getTarget();
        if (isAliveTarget(currentTarget)) {
            shareTargetWithHordeThrottled(currentTarget);
            return false;
        }

        Mob leader = resolveOrFindLeader();
        return leader != null && leader.isValid() && !leader.isDead();
    }

    @Override
    public boolean shouldStayActive() {
        if (!mob.isValid() || mob.isDead() || !patrolData.isPatrolling(mob)) {
            return false;
        }
        LivingEntity currentTarget = mob.getTarget();
        if (isAliveTarget(currentTarget)) {
            shareTargetWithHordeThrottled(currentTarget);
            // E-05: Paper GoalSelector invokes stop() automatically when shouldStayActive() returns false
            return false;
        }
        return true;
    }

    @Override
    public void start() {
        consecutivePathFailures = 0;
        AttributeInstance speedAttr = PaperApiAdapter.getMovementSpeedAttribute(mob);
        if (speedAttr != null) {
            patrolData.setSavedBaseSpeed(mob, baseSpeed);
            speedAttr.setBaseValue(PATROL_MOVEMENT_SPEED);
        }
    }

    @Override
    public void stop() {
        AttributeInstance speedAttr = PaperApiAdapter.getMovementSpeedAttribute(mob);
        if (speedAttr != null) {
            speedAttr.setBaseValue(baseSpeed);
        }
        patrolData.clearSavedBaseSpeed(mob);
    }

    @Override
    public void tick() {
        if (!mob.isValid() || mob.isDead()) {
            return;
        }

        // 1. Despawn if no player in world is within distance^2 <= 60000 and age > 20 ticks (FR-08, T6, D-06)
        despawnCheckCounter++;
        if (despawnCheckCounter >= DESPAWN_CHECK_INTERVAL_TICKS) {
            despawnCheckCounter = 0;
            if (shouldDespawnFar()) {
                stop();
                BreakBlockGoal.onMobRemoved(mob);
                mob.remove();
                return;
            }
        }

        // 2. Only recalculate navigation when pathfinder is idle and cooldown elapsed (T4)
        if (PaperApiAdapter.hasPath(mob)) {
            consecutivePathFailures = 0;
            return;
        }
        long nowTick = mob.getTicksLived();
        if (nowTick - lastPathRecalcTick < PATH_RECALC_COOLDOWN_TICKS) {
            return;
        }
        lastPathRecalcTick = nowTick;

        Mob leader = resolveOrFindLeader();
        if (leader == null || !leader.isValid() || leader.isDead()) {
            return;
        }

        // C4-03: Set follower's own target before sharing (getNearbyEntities excludes this)
        LivingEntity leaderTarget = leader.getTarget();
        if (isAliveTarget(leaderTarget)) {
            if (!isAliveTarget(mob.getTarget())) {
                mob.setTarget(leaderTarget);
            }
            shareTargetWithHordeThrottled(leaderTarget);
            return;
        }

        boolean selfIsLeader = leader.getUniqueId().equals(mob.getUniqueId());
        BlockPos leaderPatrolTarget = patrolData.getPatrolTarget(leader);

        // Sync follower patrol target with leader (FR-08)
        if (!selfIsLeader && leaderPatrolTarget != null) {
            BlockPos ownTarget = patrolData.getPatrolTarget(mob);
            if (!leaderPatrolTarget.equals(ownTarget)) {
                patrolData.setPatrolTarget(mob, leaderPatrolTarget);
            }
        }

        BlockPos activeTarget = selfIsLeader ? leaderPatrolTarget : patrolData.getPatrolTarget(mob);
        double moveSpeed = selfIsLeader ? LEADER_SPEED : FOLLOW_SPEED;

        if (activeTarget != null) {
            navigateTowardPatrolTarget(activeTarget, selfIsLeader, moveSpeed);
        } else {
            wanderAroundCurrentPosition(LEADER_SPEED);
        }
    }

    private void navigateTowardPatrolTarget(@NotNull BlockPos target, boolean selfIsLeader, double speed) {
        World world = mob.getWorld();
        Location mobLoc = mob.getLocation();

        // Check arrival within 3 blocks horizontally
        double dx = (target.x() + 0.5) - mobLoc.getX();
        double dz = (target.z() + 0.5) - mobLoc.getZ();
        double horizDistSq = dx * dx + dz * dz;
        if (horizDistSq <= 9.0) {
            consecutivePathFailures = 0;
            if (selfIsLeader) {
                BlockPos next = plugin.getHordeSpawner().pickFarPatrolTarget(world, mobLoc.getBlockX(), mobLoc.getBlockZ());
                patrolData.setPatrolTarget(mob, next);
            }
            return;
        }

        // Lazy Y resolution if target chunk is loaded (T3, NFR-05)
        BlockPos resolvedTarget = target;
        if (world.isChunkLoaded(target.x() >> 4, target.z() >> 4)) {
            if (!target.hasResolvedY()) {
                int y = PaperApiAdapter.getHighestSurfaceY(world, target.x(), target.z());
                if (y <= world.getMinHeight()) {
                    if (selfIsLeader) {
                        BlockPos next = plugin.getHordeSpawner().pickFarPatrolTarget(world, mobLoc.getBlockX(), mobLoc.getBlockZ());
                        patrolData.setPatrolTarget(mob, next);
                    }
                    return;
                }
                resolvedTarget = new BlockPos(target.x(), y + 1, target.z());
                patrolData.setPatrolTarget(mob, resolvedTarget);
            }
        }

        // N-02 + D-05: Try WaypointLadder candidates in order (up to MAX_PATH_ATTEMPTS_PER_CALL findPath calls)
        if (tryNavigate(world, mobLoc, resolvedTarget, speed)) {
            consecutivePathFailures = 0;
            return;
        }

        // D-04: Avoid thrashing target on single transient block/crowd failure
        consecutivePathFailures++;
        if (selfIsLeader && consecutivePathFailures >= MAX_CONSECUTIVE_PATH_FAILURES) {
            consecutivePathFailures = 0;
            BlockPos next = plugin.getHordeSpawner().pickFarPatrolTarget(world, mobLoc.getBlockX(), mobLoc.getBlockZ());
            patrolData.setPatrolTarget(mob, next);
        }
    }

    /**
     * N-02: Iterates WaypointLadder.candidates (exact target first when within 32 blocks & Y resolved),
     * skipping unloaded chunks without loading them (NFR-05) and capping findPath calls to MAX_PATH_ATTEMPTS_PER_CALL.
     */
    private boolean tryNavigate(
            @NotNull World world,
            @NotNull Location mobLoc,
            @NotNull BlockPos resolvedTarget,
            double speed
    ) {
        int tried = 0;
        int minHeight = world.getMinHeight();
        List<WaypointLadder.Point> candidates = WaypointLadder.candidates(
                mobLoc.getX(), mobLoc.getZ(),
                resolvedTarget.x() + 0.5, resolvedTarget.z() + 0.5,
                WaypointLadder.DEFAULT_STEPS);

        for (WaypointLadder.Point p : candidates) {
            if (tried >= MAX_PATH_ATTEMPTS_PER_CALL) {
                break;
            }
            if (!world.isChunkLoaded(p.x() >> 4, p.z() >> 4)) {
                continue;
            }
            int destY;
            if (p.x() == resolvedTarget.x() && p.z() == resolvedTarget.z() && resolvedTarget.hasResolvedY()) {
                destY = resolvedTarget.y();
            } else {
                int surfY = PaperApiAdapter.getHighestSurfaceY(world, p.x(), p.z());
                if (surfY <= minHeight) {
                    continue;
                }
                destY = surfY + 1;
            }
            tried++;
            Pathfinder.PathResult path = PaperApiAdapter.findPath(
                    mob, new Location(world, p.x() + 0.5, destY, p.z() + 0.5));
            if (path != null && PaperApiAdapter.moveTo(mob, path, speed)) {
                return true;
            }
        }
        return false;
    }

    private void wanderAroundCurrentPosition(double speed) {
        World world = mob.getWorld();
        Location loc = mob.getLocation();
        int wx = loc.getBlockX() + random.nextInt(WANDER_RADIUS * 2 + 1) - WANDER_RADIUS;
        int wz = loc.getBlockZ() + random.nextInt(WANDER_RADIUS * 2 + 1) - WANDER_RADIUS;
        if (!world.isChunkLoaded(wx >> 4, wz >> 4)) {
            return;
        }
        int wy = PaperApiAdapter.getHighestSurfaceY(world, wx, wz);
        if (wy > world.getMinHeight()) {
            PaperApiAdapter.moveTo(mob, new Location(world, wx + 0.5, wy + 1, wz + 0.5), speed);
        }
    }

    /**
     * D-06: Checks online players in this world without requiring player.isValid() so a player
     * momentarily on the death screen next to the horde does not cause immediate despawn.
     */
    private boolean shouldDespawnFar() {
        if (mob.getTicksLived() <= MIN_DESPAWN_AGE_TICKS) {
            return false;
        }
        World world = mob.getWorld();
        Location mobLoc = mob.getLocation();
        for (Player player : world.getPlayers()) {
            if (player.isOnline() && player.getLocation().distanceSquared(mobLoc) <= DESPAWN_DISTANCE_SQ) {
                return false;
            }
        }
        return true;
    }

    /**
     * FR-08b: Share combat target with all same-patrolId patrolling mobs within 64 blocks
     * whose target is null or dead. Throttled to avoid O(N^2) per-tick scans (T2, NFR-04).
     */
    private void shareTargetWithHordeThrottled(@NotNull LivingEntity target) {
        long nowTick = mob.getTicksLived();
        if (nowTick - lastShareTick < SHARE_TARGET_COOLDOWN_TICKS) {
            return;
        }
        lastShareTick = nowTick;

        String myPatrolId = patrolData.getPatrolId(mob);
        if (myPatrolId.isEmpty()) {
            return;
        }
        for (Entity nearby : mob.getNearbyEntities(HORDE_SEARCH_RADIUS, HORDE_SEARCH_RADIUS, HORDE_SEARCH_RADIUS)) {
            if (nearby instanceof Mob ally
                    && ally.isValid()
                    && !ally.isDead()
                    && patrolData.isPatrolling(ally)
                    && myPatrolId.equals(patrolData.getPatrolId(ally))) {
                LivingEntity allyTarget = ally.getTarget();
                if (allyTarget == null || allyTarget.isDead() || !allyTarget.isValid()) {
                    ally.setTarget(target);
                }
            }
        }
    }

    /**
     * FR-08c: Returns cached leader if still valid & alive in same world; otherwise scans within 64 blocks
     * (throttled) and promotes the lowest-UUID alive member (P2). Periodically converges duplicate leaders
     * even when self is already marked leader (D-03, T5).
     */
    @Nullable
    private Mob resolveOrFindLeader() {
        long nowTick = mob.getTicksLived();

        if (patrolData.isLeader(mob)) {
            if (nowTick - lastLeaderConvergenceTick >= LEADER_CONVERGENCE_INTERVAL_TICKS) {
                lastLeaderConvergenceTick = nowTick;
                Mob converged = scanAndElectLeader();
                if (converged != null) {
                    return converged;
                }
            }
            assignedLeaderUuid = mob.getUniqueId();
            return mob;
        }

        if (assignedLeaderUuid != null) {
            Entity cached = Bukkit.getEntity(assignedLeaderUuid);
            if (cached instanceof Mob cachedLeader
                    && cachedLeader.isValid()
                    && !cachedLeader.isDead()
                    && cachedLeader.getWorld().equals(mob.getWorld())
                    && patrolData.isLeader(cachedLeader)) {
                return cachedLeader;
            }
            assignedLeaderUuid = null;
        }

        if (nowTick - lastLeaderScanTick < LEADER_SCAN_COOLDOWN_TICKS) {
            return null;
        }
        lastLeaderScanTick = nowTick;
        return scanAndElectLeader();
    }

    @Nullable
    private Mob scanAndElectLeader() {
        String myPatrolId = patrolData.getPatrolId(mob);
        if (myPatrolId.isEmpty()) {
            return null;
        }

        List<Mob> existingLeaders = new ArrayList<>();
        if (patrolData.isLeader(mob)) {
            existingLeaders.add(mob);
        }

        List<Mob> nonLeaderCandidates = new ArrayList<>();
        nonLeaderCandidates.add(mob);

        for (Entity nearby : mob.getNearbyEntities(HORDE_SEARCH_RADIUS, HORDE_SEARCH_RADIUS, HORDE_SEARCH_RADIUS)) {
            if (nearby instanceof Mob ally
                    && ally.isValid()
                    && !ally.isDead()
                    && patrolData.isPatrolling(ally)
                    && myPatrolId.equals(patrolData.getPatrolId(ally))) {
                if (patrolData.isLeader(ally)) {
                    existingLeaders.add(ally);
                } else {
                    nonLeaderCandidates.add(ally);
                }
            }
        }

        if (!existingLeaders.isEmpty()) {
            List<UUID> leaderIds = new ArrayList<>(existingLeaders.size());
            for (Mob l : existingLeaders) {
                leaderIds.add(l.getUniqueId());
            }
            UUID canonicalId = selectLowestUuid(null, leaderIds);
            Mob canonical = null;
            for (Mob l : existingLeaders) {
                if (l.getUniqueId().equals(canonicalId)) {
                    canonical = l;
                } else {
                    patrolData.setLeader(l, false);
                }
            }
            if (canonical != null) {
                assignedLeaderUuid = canonical.getUniqueId();
                return canonical;
            }
        }

        // P2: Deterministic promotion (lowest UUID among alive non-leader members)
        List<UUID> candidateIds = new ArrayList<>(nonLeaderCandidates.size());
        for (Mob c : nonLeaderCandidates) {
            candidateIds.add(c.getUniqueId());
        }
        UUID winnerId = selectLowestUuid(mob.getUniqueId(), candidateIds);
        Mob promotionCandidate = mob;
        for (Mob c : nonLeaderCandidates) {
            if (c.getUniqueId().equals(winnerId)) {
                promotionCandidate = c;
                break;
            }
        }

        if (promotionCandidate.isValid() && !promotionCandidate.isDead()) {
            patrolData.setLeader(promotionCandidate, true);
            assignedLeaderUuid = promotionCandidate.getUniqueId();
            return promotionCandidate;
        }
        return null;
    }

    /**
     * C4-09: Consistent with BreakBlockGoal, ignores Creative/Spectator players as combat targets.
     */
    private static boolean isAliveTarget(@Nullable LivingEntity target) {
        if (target == null || !target.isValid() || target.isDead()) {
            return false;
        }
        if (target instanceof Player player) {
            GameMode gm = player.getGameMode();
            return gm != GameMode.CREATIVE && gm != GameMode.SPECTATOR;
        }
        return true;
    }

    @Override
    public @NotNull GoalKey<Mob> getKey() {
        return key;
    }

    @Override
    public @NotNull EnumSet<GoalType> getTypes() {
        return EnumSet.of(GoalType.MOVE);
    }
}
