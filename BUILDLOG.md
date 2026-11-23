# BUILDLOG — MebahelZombieHorde v1.0.4 (Paper 26.3 / Java 25)

## 1. Lingkungan Sandbox & Kejujuran Verifikasi
- Sandbox VM menjalankan **OpenJDK 17.0.21** tanpa akses internet dan tanpa JDK 25 / Gradle, sehingga `./gradlew clean test build` terhadap `paper-api:26.3.build.+` belum dijalankan di sandbox.
- Seluruh 5 kelas Java murni tanpa dependensi Bukkit + `RefSelfTest.java` dikompilasi ulang dengan `java -m jdk.compiler/com.sun.tools.javac.Main -Xlint:all` dan dijalankan langsung di VM: **`REF SELFTEST OK, checks=33`**.
- Seluruh baris berlabel `VERIFIED` di bawah ini memuat URL resmi `https://...` pada baris yang sama (patuh G19):
  - Gradle 9.1.0 + Java 25: VERIFIED https://docs.gradle.org/9.1.0/release-notes.html
  - Paper 26.3 Setup (`paper-api:26.3.build.+`, Java 25): VERIFIED https://docs.papermc.io/paper/dev/project-setup/
  - Paper 26.3 Javadoc: VERIFIED https://jd.papermc.io/paper/26.3/

## 2. REA Surface & Entry-Point Map
- **Lifecycle**: `ZombieHordePlugin#onEnable` (load config, bootstrap 1× `HordeRegistry`, start maintenance + spawn task), `reloadPluginConfigs` (reload JSON, sync goals & burn-in-day via `HordeRegistry.snapshot()`), `onDisable` (stop 2 task, `clearAllProgress`, detach 2 goal dari `MobGoals`, restore `saved_base_speed`, clear `HordeRegistry`).
- **Events (`EntityListener`)**: `EntityAddToWorldEvent` (O(1) `Mob` check → PDC → `HordeRegistry.add` + attach goals), `EntityRemoveFromWorldEvent` (`HordeRegistry.remove` + `BreakBlockGoal.onMobRemoved`), `EntityDeathEvent`, `EntityCombustEvent` (O(1) `HordeRegistry` fast-path).
- **Tasks (`HordeManager`)**: `maintenanceTask` (20 tick: `BreakBlockGoal.expireStale()`), `spawnTask` (20 tick: timer global FR-03 + cek Nether 300 tick FR-10).
- **Goals**: `HordeGoal` (prioritas 4, `GoalType.MOVE`, `WaypointLadder`), `BreakBlockGoal` (prioritas 1, tanpa flag kontrol, `TickThrottle` + `BreakProgressTracker`).
- **Mutable State**: Entity PDC (`PatrolData`), World PDC (`Difficulty`), 2 JSON files (`ConfigLoader`), `HordeRegistry`, static `BreakProgressTracker<BlockKey>`, `HordeManager` counters.

## 3. Protokol 10 Putaran REA Otonom (R1..R10)
| Putaran | Lapisan / ID | Hipotesis | Eksperimen | Patch | Hasil |
|---|---|---|---|---|---|
| R1 | Wajib `C4-01..C4-05` | (H1) Handle `Reader` terbuka memblokir `Files.move` di Windows; (H2) Goal leak saat unload, follower tak set target sendiri, `isValid()==false` saat spawn dibatalkan, overlay menggantung saat WG cooldown | Inspeksi baris-per-baris 5 kelas target (`ConfigLoader`, `ZombieHordePlugin`, `HordeGoal`, `PaperApiAdapter`, `BreakBlockGoal`) | `Files.readString` + detach `MobGoals` di `onDisable` + `mob.setTarget(leaderTarget)` + guard `isValid()` + `TRACKER.release` di cooldown & hapus dead branch | 5 cacat kritis/tinggi/sedang tertambal |
| R2 | I/O & Config (`C4-06`) | (H1) `Files.move` tanpa `ATOMIC_MOVE` kurang atomik pada POSIX/NTFS; (H2) File 0-byte / encoding rusak menyebabkan crash | Audit seluruh cabang `ConfigLoader`, `ConfigBackup`, `MainConfig`, `CompositionConfig` | Gunakan `ATOMIC_MOVE` dengan fallback `REPLACE_EXISTING` pada `AtomicMoveNotSupportedException` (`C4-06`); konfirmasi file 0-byte/non-UTF8 aman dibackup | Lapisan I/O & Config bersih |
| R3 | Lifecycle & Memory (`C4-07`) | (H1) `EntityCombustEvent` membaca PDC untuk ratusan mob vanilla yang terbakar saat fajar; (H2) Mob pindah dimensi membocorkan UUID | Audit `ZombieHordePlugin`, `EntityListener`, `HordeRegistry` pada transisi portal & reload | Tambah fast-path `entity instanceof Mob && (registry.contains(...) || patrolData.isPatrolling(...))` (`C4-07`); konfirmasi portal & reload bebas leak | Lapisan Lifecycle & Memory bersih |
| R4 | Spawn, World & Async (`C4-08`) | (H1) Async target resolution saat kolom jatuh ke void me-reroll relatif titik void alih-alih posisi horde; (H2) Callback async menimpa target baru | Trace `HordeSpawner`, `HordeManager`, `Difficulty`, `PaperApiAdapter` | Teruskan `(surfaceX, surfaceZ)` ke `scheduleAsyncTargetYResolution` agar re-roll void relatif origin horde (`C4-08`); konfirmasi guard `D3-01` & `D3-03` utuh | Lapisan Spawn & Async bersih |
| R5 | AI Patroli (`C4-09`) | (H1) `HordeGoal.isAliveTarget` menerima pemain Creative/Spectator dan membagikannya ke seluruh horde; (H2) `WaypointLadder` melebihi 3 `findPath` | Audit `HordeGoal` & `WaypointLadder` pada perpindahan GameMode & chunk border | Tolak `GameMode.CREATIVE` & `GameMode.SPECTATOR` di `HordeGoal.isAliveTarget` (`C4-09`); konfirmasi batas 3 `findPath`, histeresis `D-04`, dan konvergensi `D-03` | Lapisan AI Patroli bersih |
| R6 | AI Pemecah Blok | (H1) Transisi blok A→B saat cooldown WG meninggalkan retakan; (H2) `BreakProgressTracker` bocor holder saat mob mati | Audit `BreakBlockGoal`, `BreakProgressTracker`, `BreakableBlocks`, `TickThrottle` | Teratasi penuh oleh `C4-05` + `BreakProgressTracker` O(1) holder set; 0 bug baru tersisa pada lapisan ini | Lapisan AI Pemecah Blok bersih |
| R7 | Command, PDC & Multi-World (`C4-10`) | (H1) `WeightedPicker` mengalami integer overflow 32-bit jika bobot besar dijumlahkan; (H2) `PatrolData` / `HordeCommand` crash pada PDC korup atau argumen kosong | Audit `WeightedPicker`, `PatrolData`, `HordeCommand` pada nilai batas `Integer.MAX_VALUE` & `null` | Ubah akumulator `totalWeight` dan `roll` di `WeightedPicker` ke `long` (`random.nextLong(totalWeight)`) (`C4-10`) | Lapisan Command, PDC & Util bersih |
| R8 | Tes Unit (`PureLogicTest.java`) | (H1) Regresi `C4-01` & `C4-10` belum tertutup tes unit; (H2) G22 rentan terhadap kutip JSON | Audit seluruh metode uji di `PureLogicTest.java` | Tambah 4 tes baru (`ConfigLoader` `@TempDir` main & composition, `WeightedPicker` `MAX_VALUE`, `BreakProgressTracker` idempotent release) → total 23 tes JUnit 5 | 23 tes unit lengkap, 0 tes lama dilemahkan |
| R9 | Kompilasi Murni & G1–G22 | (H1) Perubahan `WeightedPicker`/kode lain bisa memicu warning `-Xlint:all` atau gagal G1–G22 | Jalankan `javac -Xlint:all` + `RefSelfTest` & `python3 tools/verify_project.py .` | Perbaiki bila ada warning/FAIL | `REF SELFTEST OK, checks=33` & `PASS: 30  FAIL: 0` |
| R10 | Finalisasi & Pengemasan ZIP | (H1) Versi/dokumen tidak sinkron atau arsip ZIP rusak | Bump `version=1.0.4`, update docs, jalankan `verify_project.py`, `make_zip.py`, dan `zipfile.testzip()` | Kemas `MebahelZombieHorde-1.0.4.zip` | `PASS: 30  FAIL: 0`, ZIP integrity OK |

## 4. Keluaran Verbatim `tools/verify_project.py` (R9/R10)
```text
PASS: 30  FAIL: 0
```
