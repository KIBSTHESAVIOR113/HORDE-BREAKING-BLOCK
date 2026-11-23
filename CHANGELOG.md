# Changelog

## [1.0.4] - 2026-11-23
### Fixed (10-Round REA Audit: C4-01..C4-10)
- **C4-01 [CRITICAL — Windows File-Lock]** (`ConfigLoader`): Read JSON fully via `Files.readString(...)` before backup and atomic move so the OS file handle is closed prior to `Files.move(..., REPLACE_EXISTING)`.
- **C4-02 [CRITICAL — Goal Leak on Unload]** (`ZombieHordePlugin#onDisable`): Detached `HordeGoal` and `BreakBlockGoal` from `Bukkit.getMobGoals()` for all tracked patrollers before restoring `saved_base_speed` and clearing `HordeRegistry`.
- **C4-03 [HIGH — Follower Ignores Leader Target]** (`HordeGoal#tick`): Assigned `mob.setTarget(leaderTarget)` on the follower itself before `shareTargetWithHordeThrottled(leaderTarget)` (since `getNearbyEntities` excludes `this`).
- **C4-04 [HIGH — Phantom Broadcast on Cancelled Spawn]** (`PaperApiAdapter#spawnMobWithoutRandomization`): Returned `null` when `spawned == null || !spawned.isValid()` so WorldGuard-cancelled spawns never count toward `spawnedMemberIds` or trigger broadcasts.
- **C4-05 [MEDIUM — Stale Crack on Cooldown & Dead Branch]** (`BreakBlockGoal#tick`): Released any previously held block in `BreakProgressTracker` before returning on WorldGuard cooldown backoff; removed dead `!isValidCombatTarget` branch.
- **C4-06** (`ConfigLoader#writeJson`): Preferred `StandardCopyOption.ATOMIC_MOVE` with safe fallback to `REPLACE_EXISTING` on `AtomicMoveNotSupportedException`.
- **C4-07** (`EntityListener#onEntityCombust`): Added O(1) `instanceof Mob` + `HordeRegistry.contains(...)` fast-path to avoid PDC reads on non-horde burning mobs at dawn.
- **C4-08** (`HordeSpawner#scheduleAsyncTargetYResolution`): Re-rolled void far-target columns relative to horde spawn origin `(surfaceX, surfaceZ)` rather than the void point.
- **C4-09** (`HordeGoal#isAliveTarget`): Excluded `GameMode.CREATIVE` and `GameMode.SPECTATOR` players from combat target checks/sharing (consistent with `BreakBlockGoal`).
- **C4-10** (`WeightedPicker#pick`): Switched weight accumulation and roll to 64-bit `long` (`random.nextLong(totalWeight)`) to eliminate 32-bit integer overflow on large weights.

### Added
- 4 new JUnit 5 regression tests in `PureLogicTest.java` (total 23 tests): `ConfigLoader` `@TempDir` corrupt-backup & atomic rewrite (main + composition), `WeightedPicker` `Integer.MAX_VALUE` overflow resistance, and `BreakProgressTracker` idempotent release.
- Full 10-round REA audit log (`R1..R10`) in `BUILDLOG.md`.

## [1.0.3] - 2026-11-23
### Added
- Gradle wrapper files (`gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.properties` targeting Gradle 9.1.0 for Java 25) + 1-line bootstrap instructions in `README.md` (N-04).
- Integrated 5 compiled reference pure classes: `BreakProgressTracker`, `TickThrottle`, `WaypointLadder`, `ConfigBackup`, and `HordeRegistry` (N-01, N-02, N-03, N-07).
- `HordeSpawner.SpawnResult` enum (`SPAWNED`, `NO_PLAYER`, `NO_COMPOSITION`, `NO_LOADED_COLUMN`, `ZERO_MEMBERS`) with ~5-second retry on `NO_LOADED_COLUMN` (N-06, D3-03) and detailed `/mebahelzombiehorde spawnhorde` feedback (D3-10).
- Independent maintenance task `HordeManager.ensureMaintenanceStarted()` so `BreakBlockGoal.expireStale()` runs even when `hordeSpawning=false` (N-09).
- Isolated `PaperApiAdapter.crackStageToProgress(int)` and `clearCrack(...)` for crack overlay semantics (N-08).
- Expanded `PureLogicTest.java` to 19 JUnit 5 tests covering all 5 reference classes (G21), `crackStageToProgress`, `yawToCardinal`, `BlockKey`, `rollFollowers`, `dimensions` sanitization, and `MainConfig` JSON round-trip.
- Documented `simulation-distance` requirement for >245-block despawn testing in `README.md`, `TESTING.md`, and `ASSUMPTIONS.md` (N-05).

### Fixed (Adversarial Round 3: D3-01..D3-10 & Baseline E-01..E-06 preserved)
- **D3-01 (`HordeSpawner`)**: Prevented stale `getChunkAtAsync` callback from overwriting a newer `patrolTarget` chosen by the leader.
- **D3-02 (`PaperApiAdapter`)**: Disabled ground item pickup (`setCanPickupItems(false)`) on spawned horde members.
- **D3-03 (`HordeManager`)**: Prevented partial `NO_LOADED_COLUMN` failure in multi-horde (`hordeNumber > 1`) loops from multiplying spawns.
- **D3-04 (`CompositionConfig`)**: Sanitized optional `dimensions` list (trimmed whitespace, dropped `null`/blank entries).
- **D3-05 (`ZombieHordePlugin`)**: Restored `saved_base_speed` on all active patrollers in `onDisable()` before clearing `HordeRegistry`.
- **D3-06 (`EntityListener`)**: Ordered cheapest `Mob` check first on `EntityAddToWorldEvent` hot path and removed UUID unconditionally on `EntityRemoveFromWorldEvent`.
- **D3-07 (`BreakBlockGoal`)**: Added 200-tick backoff cooldown on WorldGuard-denied blocks to prevent infinite crack animation spam.
- **D3-08 (`HordeGoal`, `BreakBlockGoal`)**: Added deterministic per-mob UUID phase jitter to prevent synchronized 60-mob CPU spikes.
- **D3-09 (`Difficulty`)**: Guarded `checkNetherProgression` to `World.Environment.NORMAL` worlds only.
- **D3-10 (`HordeCommand`)**: Surfaced exact `SpawnResult` failure reasons to command senders.
- Preserved all previous patches: `P1–P4`, `D-01..D-10`, and `E-01..E-06`.

## [1.0.2] - 2026-11-23
- Second adversarial audit fixes (`D-01..D-10`), pure unit tests for `selectLowestUuid` & `findSpawnColumn`, strict verification honesty.

## [1.0.1] - 2026-11-23
- Reviewer audit fixes (`P1–P4`).

## [1.0.0] - 2026-11-23
- Initial clean-room Paper 26.3 / Java 25 port of *Mebahel's Zombie Horde* v1.0.11.
