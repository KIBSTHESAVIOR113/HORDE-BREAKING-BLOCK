package io.github.mebahel.zombiehorde;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.mebahel.zombiehorde.ai.BreakBlockGoal.BlockKey;
import io.github.mebahel.zombiehorde.ai.BreakProgressTracker;
import io.github.mebahel.zombiehorde.ai.BreakableBlocks;
import io.github.mebahel.zombiehorde.ai.HordeGoal;
import io.github.mebahel.zombiehorde.ai.TickThrottle;
import io.github.mebahel.zombiehorde.ai.WaypointLadder;
import io.github.mebahel.zombiehorde.config.CompositionConfig;
import io.github.mebahel.zombiehorde.config.CompositionConfig.HordeComposition;
import io.github.mebahel.zombiehorde.config.CompositionConfig.MobTypeEntry;
import io.github.mebahel.zombiehorde.config.CompositionConfig.WeaponEntry;
import io.github.mebahel.zombiehorde.config.ConfigBackup;
import io.github.mebahel.zombiehorde.config.ConfigLoader;
import io.github.mebahel.zombiehorde.config.MainConfig;
import io.github.mebahel.zombiehorde.horde.HordeRegistry;
import io.github.mebahel.zombiehorde.horde.HordeSpawner;
import io.github.mebahel.zombiehorde.horde.HordeSpawner.FollowerRoll;
import io.github.mebahel.zombiehorde.horde.HordeSpawner.SpawnColumn;
import io.github.mebahel.zombiehorde.util.PaperApiAdapter;
import io.github.mebahel.zombiehorde.util.WeightedPicker;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PureLogicTest {

    // --- WeightedPicker tests ---

    @Test
    void weightedPickerFollowsWeights() {
        record Item(String name, int weight) {}
        List<Item> items = List.of(new Item("heavy", 90), new Item("light", 10));
        Random rng = new Random(42L);
        int heavyCount = 0;
        for (int i = 0; i < 10_000; i++) {
            Item picked = WeightedPicker.pick(items, Item::weight, rng);
            assertNotNull(picked);
            if ("heavy".equals(picked.name())) {
                heavyCount++;
            }
        }
        assertTrue(heavyCount > 8500 && heavyCount < 9500, "Expected ~90% heavy, got " + heavyCount);
    }

    @Test
    void weightedPickerHandlesZeroNegativeAndEmpty() {
        record Item(String name, int weight) {}
        Random rng = new Random(7L);

        assertNull(WeightedPicker.pick(null, Item::weight, rng));
        assertNull(WeightedPicker.pick(List.of(), Item::weight, rng));

        List<Item> allZeroOrNeg = List.of(new Item("a", 0), new Item("b", -5));
        assertEquals("a", WeightedPicker.pick(allZeroOrNeg, Item::weight, rng).name());

        List<Item> mixed = List.of(new Item("zero", 0), new Item("neg", -3), new Item("pos", 5));
        for (int i = 0; i < 100; i++) {
            assertEquals("pos", WeightedPicker.pick(mixed, Item::weight, rng).name());
        }
    }

    // --- MainConfig FR-01 boundary validation & round-trip tests ---

    @Test
    void mainConfigDefaultsAreValidAndUnmodified() {
        MainConfig cfg = MainConfig.createDefault();
        assertFalse(cfg.validateAndFix(null));
        assertTrue(cfg.isSpawnInDayLight());
        assertTrue(cfg.isEnableDifficultySystem());
        assertTrue(cfg.isHordeSpawning());
        assertEquals(15, cfg.getHordeSpawnDelay());
        assertEquals(18_000L, cfg.getSpawnDelayTicks());
        assertEquals(1.0f, cfg.getHordeSpawnChance());
        assertEquals(0, cfg.getRandomNumberHordeReinforcements());
        assertEquals(1, cfg.getHordeNumber());
        assertEquals(0, cfg.getHordeMemberBonusHealth());
        assertTrue(cfg.isHordeMemberBreakGlass());
        assertTrue(cfg.isHordeMemberBreakFence());
        assertTrue(cfg.isShowHordeSpawningMessage());

        JsonObject json = cfg.toJson();
        assertEquals(11, json.size());
        assertFalse(json.has("enablePhase2Features"));
    }

    @Test
    void mainConfigJsonRoundTripPreservesAllValues() {
        MainConfig original = MainConfig.createDefault();
        original.setSpawnInDayLight(false);
        original.setHordeSpawnDelay(25);
        original.setHordeSpawnChance(0.75f);
        original.setRandomNumberHordeReinforcements(4);
        original.setHordeNumber(3);
        original.setHordeMemberBonusHealth(12);
        original.setHordeMemberBreakGlass(false);
        original.setShowHordeSpawningMessage(false);

        JsonObject serialized = original.toJson();
        MainConfig restored = MainConfig.fromJson(serialized, null);
        assertFalse(restored.validateAndFix(null));
        assertEquals(original.isSpawnInDayLight(), restored.isSpawnInDayLight());
        assertEquals(original.getHordeSpawnDelay(), restored.getHordeSpawnDelay());
        assertEquals(original.getHordeSpawnChance(), restored.getHordeSpawnChance(), 0.0001f);
        assertEquals(original.getRandomNumberHordeReinforcements(), restored.getRandomNumberHordeReinforcements());
        assertEquals(original.getHordeNumber(), restored.getHordeNumber());
        assertEquals(original.getHordeMemberBonusHealth(), restored.getHordeMemberBonusHealth());
        assertEquals(original.isHordeMemberBreakGlass(), restored.isHordeMemberBreakGlass());
        assertEquals(original.isShowHordeSpawningMessage(), restored.isShowHordeSpawningMessage());
    }

    @Test
    void mainConfigValidatesEveryBoundaryPlusMinusOne() {
        MainConfig cfg = MainConfig.createDefault();
        cfg.setHordeSpawnDelay(1);
        assertFalse(cfg.validateAndFix(null));
        cfg.setHordeSpawnDelay(60);
        assertFalse(cfg.validateAndFix(null));
        cfg.setHordeSpawnDelay(0);
        assertTrue(cfg.validateAndFix(null));
        assertEquals(15, cfg.getHordeSpawnDelay());
        cfg.setHordeSpawnDelay(61);
        assertTrue(cfg.validateAndFix(null));
        assertEquals(15, cfg.getHordeSpawnDelay());

        cfg.setHordeSpawnChance(0.0f);
        assertFalse(cfg.validateAndFix(null));
        cfg.setHordeSpawnChance(1.0f);
        assertFalse(cfg.validateAndFix(null));
        cfg.setHordeSpawnChance(-0.01f);
        assertTrue(cfg.validateAndFix(null));
        assertEquals(1.0f, cfg.getHordeSpawnChance());
        cfg.setHordeSpawnChance(1.01f);
        assertTrue(cfg.validateAndFix(null));
        assertEquals(1.0f, cfg.getHordeSpawnChance());
        cfg.setHordeSpawnChance(Float.NaN);
        assertTrue(cfg.validateAndFix(null));
        assertEquals(1.0f, cfg.getHordeSpawnChance());

        cfg.setRandomNumberHordeReinforcements(0);
        assertFalse(cfg.validateAndFix(null));
        cfg.setRandomNumberHordeReinforcements(10);
        assertFalse(cfg.validateAndFix(null));
        cfg.setRandomNumberHordeReinforcements(-1);
        assertTrue(cfg.validateAndFix(null));
        assertEquals(0, cfg.getRandomNumberHordeReinforcements());
        cfg.setRandomNumberHordeReinforcements(11);
        assertTrue(cfg.validateAndFix(null));
        assertEquals(0, cfg.getRandomNumberHordeReinforcements());

        cfg.setHordeNumber(1);
        assertFalse(cfg.validateAndFix(null));
        cfg.setHordeNumber(5);
        assertFalse(cfg.validateAndFix(null));
        cfg.setHordeNumber(0);
        assertTrue(cfg.validateAndFix(null));
        assertEquals(1, cfg.getHordeNumber());
        cfg.setHordeNumber(6);
        assertTrue(cfg.validateAndFix(null));
        assertEquals(1, cfg.getHordeNumber());

        cfg.setEnablePhase2Features(true);
        cfg.setHordeNumber(20);
        assertFalse(cfg.validateAndFix(null));
        cfg.setHordeNumber(21);
        assertTrue(cfg.validateAndFix(null));
        assertEquals(1, cfg.getHordeNumber());
        cfg.setEnablePhase2Features(null);

        cfg.setHordeMemberBonusHealth(0);
        assertFalse(cfg.validateAndFix(null));
        cfg.setHordeMemberBonusHealth(40);
        assertFalse(cfg.validateAndFix(null));
        cfg.setHordeMemberBonusHealth(-1);
        assertTrue(cfg.validateAndFix(null));
        assertEquals(0, cfg.getHordeMemberBonusHealth());
        cfg.setHordeMemberBonusHealth(41);
        assertTrue(cfg.validateAndFix(null));
        assertEquals(0, cfg.getHordeMemberBonusHealth());
    }

    @Test
    void mainConfigSupportsPhase2DelayArrayFormat() {
        JsonObject minuteJson = JsonParser.parseString("{\"hordeSpawnDelay\": [10, \"minute\"]}").getAsJsonObject();
        MainConfig minuteCfg = MainConfig.fromJson(minuteJson, null);
        minuteCfg.validateAndFix(null);
        assertEquals(10, minuteCfg.getHordeSpawnDelay());
        assertEquals("minute", minuteCfg.getHordeSpawnDelayUnit());
        assertEquals(12_000L, minuteCfg.getSpawnDelayTicks());

        JsonObject dayJson = JsonParser.parseString("{\"hordeSpawnDelay\": [2, \"day\"]}").getAsJsonObject();
        MainConfig dayCfg = MainConfig.fromJson(dayJson, null);
        dayCfg.validateAndFix(null);
        assertEquals(2, dayCfg.getHordeSpawnDelay());
        assertEquals("day", dayCfg.getHordeSpawnDelayUnit());
        assertEquals(48_000L, dayCfg.getSpawnDelayTicks());

        // E-06: Unknown unit must flag modified=true and normalize to "minute"
        JsonObject badUnitJson = JsonParser.parseString("{\"hordeSpawnDelay\": [5, \"week\"]}").getAsJsonObject();
        MainConfig badUnitCfg = MainConfig.fromJson(badUnitJson, null);
        assertTrue(badUnitCfg.validateAndFix(null));
        assertEquals("minute", badUnitCfg.getHordeSpawnDelayUnit());
    }

    // --- Follower count & offset tests (FR-04, FR-05, FR-05b) ---

    @Test
    void followerCountAndRollFollowersMatchPrdFormulas() {
        Random rng = new Random(1234L);
        int minD1 = Integer.MAX_VALUE, maxD1 = Integer.MIN_VALUE;
        int minD2 = Integer.MAX_VALUE, maxD2 = Integer.MIN_VALUE;
        int minD1Reinf = Integer.MAX_VALUE, maxD1Reinf = Integer.MIN_VALUE;

        for (int i = 0; i < 5_000; i++) {
            FollowerRoll r1 = HordeSpawner.rollFollowers(1, 0, rng);
            assertEquals(0, r1.reinforcements());
            assertEquals(r1.baseFollowers(), r1.total());
            minD1 = Math.min(minD1, r1.total());
            maxD1 = Math.max(maxD1, r1.total());

            int f2 = HordeSpawner.calculateFollowerCount(2, 0, rng);
            minD2 = Math.min(minD2, f2);
            maxD2 = Math.max(maxD2, f2);

            FollowerRoll r1r = HordeSpawner.rollFollowers(1, 5, rng);
            assertTrue(r1r.reinforcements() >= 0 && r1r.reinforcements() < 5);
            assertEquals(r1r.baseFollowers() + r1r.reinforcements(), r1r.total());
            minD1Reinf = Math.min(minD1Reinf, r1r.total());
            maxD1Reinf = Math.max(maxD1Reinf, r1r.total());
        }

        assertEquals(6, minD1);
        assertEquals(8, maxD1);
        assertEquals(8, minD2);
        assertEquals(12, maxD2);
        assertEquals(6, minD1Reinf);
        assertEquals(12, maxD1Reinf);
    }

    @Test
    void spawnPatrolAndMemberOffsetsWithinExactBounds() {
        Random rng = new Random(99L);
        for (int i = 0; i < 2_000; i++) {
            int spawn = Math.abs(HordeSpawner.randomSpawnOffset(rng));
            assertTrue(spawn >= 30 && spawn <= 59, "spawn offset out of range: " + spawn);

            int patrol = Math.abs(HordeSpawner.randomPatrolTargetOffset(rng));
            assertTrue(patrol >= 150 && patrol <= 299, "patrol offset out of range: " + patrol);

            int member = Math.abs(HordeSpawner.randomMemberOffset(rng));
            assertTrue(member >= 5 && member <= 9, "member offset out of range: " + member);
        }
    }

    // --- Spawn column retry & deterministic leader election tests ---

    @Test
    void spawnColumnRetriesUntilAcceptableAndRespectsBounds() {
        Random rng = new Random(2026L);
        AtomicInteger calls = new AtomicInteger(0);

        SpawnColumn col = HordeSpawner.findSpawnColumn(100, -50, HordeSpawner.MAX_SPAWN_COLUMN_RETRIES, rng, (x, z) -> {
            int dx = Math.abs(x - 100);
            int dz = Math.abs(z - (-50));
            assertTrue(dx >= 30 && dx <= 59, "dx out of range: " + dx);
            assertTrue(dz >= 30 && dz <= 59, "dz out of range: " + dz);
            return calls.incrementAndGet() >= 5;
        });
        assertNotNull(col);
        assertEquals(5, calls.get());

        AtomicInteger failCalls = new AtomicInteger(0);
        SpawnColumn none = HordeSpawner.findSpawnColumn(0, 0, HordeSpawner.MAX_SPAWN_COLUMN_RETRIES, rng, (x, z) -> {
            failCalls.incrementAndGet();
            return false;
        });
        assertNull(none);
        assertEquals(HordeSpawner.MAX_SPAWN_COLUMN_RETRIES, failCalls.get());
    }

    @Test
    void deterministicLeaderElectionAlwaysConvergesToLowestUuid() {
        UUID u1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID u2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
        UUID u3 = UUID.fromString("00000000-0000-0000-0000-000000000003");

        assertEquals(u1, HordeGoal.selectLowestUuid(u3, List.of(u2, u1, u3)));
        assertEquals(u1, HordeGoal.selectLowestUuid(u2, List.of(u3, u1)));
        assertEquals(u1, HordeGoal.selectLowestUuid(u1, List.of(u3, u2)));
        assertEquals(u2, HordeGoal.selectLowestUuid(null, List.of(u3, u2)));

        List<UUID> withNull = new ArrayList<>();
        withNull.add(null);
        withNull.add(u3);
        withNull.add(u2);
        assertEquals(u2, HordeGoal.selectLowestUuid(null, withNull));
        assertNull(HordeGoal.selectLowestUuid(null, List.of()));
    }

    // --- Weapon selection & CompositionConfig validation tests (FR-02, FR-07, Q7, F-3, F-4, D3-04) ---

    @Test
    void weaponSelectionNormalizesAndHandlesEmptyOrZeroSum() {
        Random rng = new Random(55L);
        assertNull(CompositionConfig.pickWeapon(null, rng));
        assertNull(CompositionConfig.pickWeapon(List.of(), rng));
        assertNull(CompositionConfig.pickWeapon(List.of(new WeaponEntry("minecraft:iron_sword", 0.0f)), rng));

        List<WeaponEntry> weapons = List.of(
                new WeaponEntry("minecraft:iron_sword", 3.0f),
                new WeaponEntry("minecraft:stone_sword", 1.0f)
        );
        int ironCount = 0;
        for (int i = 0; i < 10_000; i++) {
            WeaponEntry picked = CompositionConfig.pickWeapon(weapons, rng);
            assertNotNull(picked);
            if ("minecraft:iron_sword".equals(picked.getItemId())) {
                ironCount++;
            }
        }
        assertTrue(ironCount > 7100 && ironCount < 7900, "Expected ~75% iron_sword, got " + ironCount);
    }

    @Test
    void compositionConfigValidateFixesWeightsChancesNullsAndDimensions() {
        CompositionConfig cfg = new CompositionConfig();
        List<WeaponEntry> weapons = new ArrayList<>();
        weapons.add(null);
        weapons.add(new WeaponEntry("   ", 0.5f));
        weapons.add(new WeaponEntry("minecraft:iron_sword", -0.5f));

        List<MobTypeEntry> mobs = new ArrayList<>();
        mobs.add(null);
        mobs.add(new MobTypeEntry("", 10, weapons));
        MobTypeEntry zombie = new MobTypeEntry("minecraft:zombie", 0, weapons);
        zombie.setSpawnWithWeaponProbability(1.5f); // invalid > 1.0
        mobs.add(zombie);

        HordeComposition comp = new HordeComposition(-2, mobs);
        List<String> dims = new ArrayList<>();
        dims.add(null);
        dims.add("   ");
        dims.add(" minecraft:overworld ");
        comp.setDimensions(dims);

        List<HordeComposition> comps = new ArrayList<>();
        comps.add(null);
        comps.add(comp);
        cfg.setHordeCompositions(comps);

        assertTrue(cfg.validateAndFix(null, false));
        assertEquals(1, cfg.getHordeCompositions().size());
        HordeComposition fixedComp = cfg.getHordeCompositions().get(0);
        assertEquals(1, fixedComp.getWeight());
        assertEquals(List.of("minecraft:overworld"), fixedComp.getDimensions());
        assertEquals(1, fixedComp.getMobTypes().size());
        assertEquals(1, fixedComp.getMobTypes().get(0).getWeight());
        assertNull(fixedComp.getMobTypes().get(0).getSpawnWithWeaponProbability());
        assertEquals(1, fixedComp.getMobTypes().get(0).getWeapons().size());
        assertEquals(0.0f, fixedComp.getMobTypes().get(0).getWeapons().get(0).getChance());
    }

    @Test
    void compositionMatchesNormalEnvironmentRegardlessOfCustomWorldKey() {
        HordeComposition defaultComp = CompositionConfig.createDefault().getHordeCompositions().get(0);
        assertTrue(defaultComp.matchesDimension("custom:my_survival_world", World.Environment.NORMAL));
        assertFalse(defaultComp.matchesDimension("minecraft:the_nether", World.Environment.NETHER));

        defaultComp.setDimensions(List.of("minecraft:the_nether"));
        assertTrue(defaultComp.matchesDimension("minecraft:the_nether", World.Environment.NETHER));
        assertFalse(defaultComp.matchesDimension("minecraft:overworld", World.Environment.NORMAL));
    }

    // --- BreakableBlocks 4-way matrix test (FR-09) ---

    @Test
    void breakableBlocksFourWayMatrixAndExclusions() {
        assertEquals(2, BreakableBlocks.GLASS_BLOCKS.size());
        assertEquals(14, BreakableBlocks.FENCE_BLOCKS.size());

        assertTrue(BreakableBlocks.isBreakable(Material.GLASS, true, true));
        assertTrue(BreakableBlocks.isBreakable(Material.GLASS_PANE, true, true));
        assertTrue(BreakableBlocks.isBreakable(Material.OAK_FENCE, true, true));
        assertTrue(BreakableBlocks.isBreakable(Material.MANGROVE_FENCE_GATE, true, true));

        assertTrue(BreakableBlocks.isBreakable(Material.GLASS, true, false));
        assertFalse(BreakableBlocks.isBreakable(Material.OAK_FENCE, true, false));

        assertFalse(BreakableBlocks.isBreakable(Material.GLASS, false, true));
        assertTrue(BreakableBlocks.isBreakable(Material.BIRCH_FENCE_GATE, false, true));

        assertFalse(BreakableBlocks.isBreakable(Material.GLASS, false, false));
        assertFalse(BreakableBlocks.isBreakable(Material.OAK_FENCE, false, false));

        Set<Material> excluded = Set.of(
                Material.TINTED_GLASS,
                Material.WHITE_STAINED_GLASS,
                Material.WHITE_STAINED_GLASS_PANE,
                Material.NETHER_BRICK_FENCE,
                Material.BAMBOO_FENCE,
                Material.CHERRY_FENCE,
                Material.CRIMSON_FENCE,
                Material.WARPED_FENCE,
                Material.STONE
        );
        for (Material m : excluded) {
            assertFalse(BreakableBlocks.isBreakable(m, true, true), "Should NOT be breakable: " + m);
        }
    }

    // --- G21: Tests for all 5 reference pure classes ---

    @Test
    void breakProgressTrackerSharesStagesReleasesAndExpires() {
        long[] clock = {1_000L};
        BreakProgressTracker<String> tracker = new BreakProgressTracker<>(() -> clock[0]);
        UUID mobA = UUID.randomUUID();
        UUID mobB = UUID.randomUUID();

        for (int i = 0; i < 4; i++) {
            assertFalse(tracker.contribute(mobA, "glass").completed());
            assertFalse(tracker.contribute(mobB, "glass").completed());
        }
        assertEquals(8, tracker.stageOf("glass"));
        assertFalse(tracker.contribute(mobA, "glass").completed());
        BreakProgressTracker.Result<String> done = tracker.contribute(mobB, "glass");
        assertTrue(done.completed());
        assertEquals(10, done.stage());
        assertEquals(0, tracker.size());

        // Leaving while another mob still holds -> no overlay reset
        tracker.contribute(mobA, "fence");
        tracker.contribute(mobB, "fence");
        assertNull(tracker.release(mobA));
        assertEquals(2, tracker.stageOf("fence"));
        assertEquals("fence", tracker.release(mobB));
        assertEquals(0, tracker.size());

        // Switching blocks releases previous only if no other mob holds it
        tracker.contribute(mobA, "p");
        assertEquals("p", tracker.contribute(mobA, "q").released());
        tracker.contribute(mobB, "q");
        assertNull(tracker.contribute(mobA, "r").released());

        // Expiry after > 5000ms
        clock[0] += 5_001L;
        List<String> expired = tracker.expire();
        assertEquals(2, expired.size());
        assertTrue(expired.contains("q") && expired.contains("r"));
        assertEquals(0, tracker.size());

        // Update keeps alive + clear() returns all active keys
        tracker.contribute(mobA, "k");
        clock[0] += 4_000L;
        tracker.contribute(mobB, "k");
        clock[0] += 4_000L;
        assertTrue(tracker.expire().isEmpty());
        assertEquals(1, tracker.clear().size());
        assertEquals(0, tracker.size());
    }

    @Test
    void tickThrottleEnforcesIntervalAndReset() {
        TickThrottle throttle = new TickThrottle();
        assertTrue(throttle.tryAcquire(100, 20));
        assertFalse(throttle.tryAcquire(119, 20));
        assertTrue(throttle.tryAcquire(120, 20));
        assertFalse(throttle.tryAcquire(139, 20));
        throttle.reset();
        assertTrue(throttle.tryAcquire(121, 20));
    }

    @Test
    void waypointLadderOrdersExactTargetFirstWhenNearAndStepsWhenFar() {
        List<WaypointLadder.Point> near = WaypointLadder.candidates(0, 0, 10, 0, WaypointLadder.DEFAULT_STEPS);
        assertEquals(new WaypointLadder.Point(10, 0), near.get(0));

        List<WaypointLadder.Point> far = WaypointLadder.candidates(0, 0, 300, 0, WaypointLadder.DEFAULT_STEPS);
        assertEquals(4, far.size());
        assertEquals(32, far.get(0).x());
        assertEquals(24, far.get(1).x());
        assertEquals(16, far.get(2).x());
        assertEquals(8, far.get(3).x());

        assertTrue(WaypointLadder.candidates(5, 5, 5, 5, WaypointLadder.DEFAULT_STEPS).isEmpty());
    }

    @Test
    void configBackupCreatesUniqueNonOverwritingBackups(@TempDir Path tempDir) throws Exception {
        Path cfgFile = tempDir.resolve("c.json");
        assertNull(ConfigBackup.backupCorrupt(cfgFile, LocalDateTime.now()));

        Files.writeString(cfgFile, "{broken");
        LocalDateTime stamp = LocalDateTime.of(2026, 11, 23, 10, 0, 0);
        Path b1 = ConfigBackup.backupCorrupt(cfgFile, stamp);
        Path b2 = ConfigBackup.backupCorrupt(cfgFile, stamp);
        assertNotNull(b1);
        assertNotNull(b2);
        assertNotEquals(b1, b2);
        assertEquals("{broken", Files.readString(b1));
        assertEquals("{broken", Files.readString(b2));
    }

    @Test
    void hordeRegistryTracksMembersAndSnapshotsSafely() {
        HordeRegistry reg = new HordeRegistry();
        UUID u1 = UUID.randomUUID();
        UUID u2 = UUID.randomUUID();
        assertTrue(reg.add(u1));
        assertFalse(reg.add(u1));
        assertTrue(reg.add(u2));
        assertEquals(2, reg.size());
        assertTrue(reg.contains(u1));

        for (UUID id : reg.snapshot()) {
            reg.remove(id);
        }
        assertEquals(0, reg.size());
    }

    // --- Additional pure helper tests (N-08, yawToCardinal, BlockKey) ---

    @Test
    void crackStageToProgressMapsStagesAndClampsBounds() {
        assertEquals(0.0f, PaperApiAdapter.crackStageToProgress(-1), 0.0001f);
        assertEquals(0.0f, PaperApiAdapter.crackStageToProgress(0), 0.0001f);
        assertEquals(0.1f, PaperApiAdapter.crackStageToProgress(1), 0.0001f);
        assertEquals(0.5f, PaperApiAdapter.crackStageToProgress(5), 0.0001f);
        assertEquals(0.9f, PaperApiAdapter.crackStageToProgress(9), 0.0001f);
        assertEquals(1.0f, PaperApiAdapter.crackStageToProgress(10), 0.0001f);
        assertEquals(1.0f, PaperApiAdapter.crackStageToProgress(15), 0.0001f);
    }

    @Test
    void yawToCardinalMapsAllFourQuadrants() {
        assertEquals(BlockFace.SOUTH, PaperApiAdapter.yawToCardinal(0.0f));
        assertEquals(BlockFace.SOUTH, PaperApiAdapter.yawToCardinal(350.0f));
        assertEquals(BlockFace.WEST, PaperApiAdapter.yawToCardinal(90.0f));
        assertEquals(BlockFace.NORTH, PaperApiAdapter.yawToCardinal(180.0f));
        assertEquals(BlockFace.EAST, PaperApiAdapter.yawToCardinal(270.0f));
        assertEquals(BlockFace.EAST, PaperApiAdapter.yawToCardinal(-90.0f));
    }

    @Test
    void blockKeyDeterministicSourceIdIsPositiveAndStable() {
        UUID w1 = UUID.fromString("00000000-0000-0000-0000-000000000010");
        UUID w2 = UUID.fromString("00000000-0000-0000-0000-000000000020");
        BlockKey k1a = new BlockKey(w1, 10, 64, -20);
        BlockKey k1b = new BlockKey(w1, 10, 64, -20);
        BlockKey k2 = new BlockKey(w2, 10, 64, -20);

        assertEquals(k1a, k1b);
        assertEquals(k1a.deterministicSourceId(), k1b.deterministicSourceId());
        assertTrue(k1a.deterministicSourceId() > 0);
        assertNotEquals(k1a, k2);
    }

    // --- R8: Regression tests for C4-01 (ConfigLoader @TempDir) & C4-10 (WeightedPicker overflow) ---

    @Test
    void configLoaderBacksUpCorruptMainConfigAndRewritesAtomically(@TempDir Path tempDir) throws Exception {
        ConfigLoader loader = new ConfigLoader(tempDir.toFile(), Logger.getLogger("test-main"));
        Path mainFile = tempDir.resolve(ConfigLoader.MAIN_CONFIG_FILE);
        Files.writeString(mainFile, "{not valid json");

        MainConfig loaded = loader.loadMainConfig();
        assertEquals(15, loaded.getHordeSpawnDelay());
        assertTrue(Files.exists(mainFile));
        assertFalse(Files.exists(tempDir.resolve(ConfigLoader.MAIN_CONFIG_FILE + ".tmp")));

        long backupCount;
        try (var stream = Files.list(tempDir)) {
            backupCount = stream.filter(p -> p.getFileName().toString().contains(".corrupt-")).count();
        }
        assertEquals(1L, backupCount, "Expected exactly 1 corrupt backup file");

        // Auto-rewrite out-of-range config without creating a corrupt backup
        Files.writeString(mainFile, "{\"hordeSpawnDelay\": 999}");
        MainConfig fixed = loader.loadMainConfig();
        assertEquals(15, fixed.getHordeSpawnDelay());
        try (var stream = Files.list(tempDir)) {
            backupCount = stream.filter(p -> p.getFileName().toString().contains(".corrupt-")).count();
        }
        assertEquals(1L, backupCount, "Out-of-range rewrite should not create a corrupt backup");
    }

    @Test
    void configLoaderBacksUpCorruptCompositionConfigAndRewritesAtomically(@TempDir Path tempDir) throws Exception {
        ConfigLoader loader = new ConfigLoader(tempDir.toFile(), Logger.getLogger("test-comp"));
        Path compFile = tempDir.resolve(ConfigLoader.COMPOSITION_CONFIG_FILE);
        Files.writeString(compFile, "");

        CompositionConfig loaded = loader.loadCompositionConfig();
        assertNotNull(loaded.getHordeCompositions());
        assertEquals(1, loaded.getHordeCompositions().size());
        assertFalse(Files.exists(tempDir.resolve(ConfigLoader.COMPOSITION_CONFIG_FILE + ".tmp")));

        long backupCount;
        try (var stream = Files.list(tempDir)) {
            backupCount = stream.filter(p -> p.getFileName().toString().contains(".corrupt-")).count();
        }
        assertEquals(1L, backupCount, "Expected backup for empty composition config");
    }

    @Test
    void weightedPickerDoesNotOverflowOnMaxIntWeights() {
        record Item(String name, int weight) {}
        List<Item> items = List.of(
                new Item("first", Integer.MAX_VALUE),
                new Item("second", Integer.MAX_VALUE)
        );
        Random rng = new Random(101L);
        int secondCount = 0;
        for (int i = 0; i < 2_000; i++) {
            Item picked = WeightedPicker.pick(items, Item::weight, rng);
            assertNotNull(picked);
            if ("second".equals(picked.name())) {
                secondCount++;
            }
        }
        assertTrue(secondCount > 850 && secondCount < 1150, "Expected ~50% second, got " + secondCount);
    }

    @Test
    void breakProgressTrackerReleaseIdempotentWhenMobHasNoActiveBlock() {
        BreakProgressTracker<String> tracker = new BreakProgressTracker<>(() -> 1_000L);
        UUID mob = UUID.randomUUID();
        assertNull(tracker.release(mob));
        tracker.contribute(mob, "glass");
        assertEquals("glass", tracker.release(mob));
        assertNull(tracker.release(mob));
        assertEquals(0, tracker.size());
    }
}
