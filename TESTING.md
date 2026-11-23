# TESTING — MebahelZombieHorde (Paper 26.3)

## 1. Tes Unit (Logika Murni)
1. Bootstrap wrapper (sekali): `gradle wrapper --gradle-version 9.1.0`
2. Jalankan tes unit pada JDK 25:
   ```bash
   ./gradlew clean test
   ```
Cakupan `PureLogicTest.java` (23 metode uji JUnit 5):
- `WeightedPicker`, `MainConfig` (batas FR-01 ±1, round-trip JSON, array `[n, "minute"|"day"]`, unit tak dikenal E-06), `HordeSpawner` (`rollFollowers`, offset, `findSpawnColumn` retry 12×), `HordeGoal.selectLowestUuid`, `CompositionConfig` (senjata, null/blank, sanitasi `dimensions` D3-04, `Environment.NORMAL`), `BreakableBlocks` (matriks 4 kombinasi), serta seluruh 5 kelas referensi (`BreakProgressTracker`, `TickThrottle`, `WaypointLadder`, `ConfigBackup`, `HordeRegistry`) helper murni (`PaperApiAdapter.crackStageToProgress`, `yawToCardinal`, `BlockKey.deterministicSourceId`), serta regresi `C4-01` (`ConfigLoader` `@TempDir` backup + atomic rewrite) dan `C4-10` (`WeightedPicker` overflow `Integer.MAX_VALUE`).

## 2. Uji Manual Wajib untuk `RUNTIME OPEN-RISK` & `[I]`
- **A-012 (`Pathfinder` >32 blok / N-02)**: Amati horde berjalan >100 blok melintasi bukit/sungai; pastikan `WaypointLadder` (`32, 24, 16, 8`) berhasil memajukan horde tanpa tersangkut dan tanpa sync chunk load (cek Spark/Timings).
- **A-013 (`Player#sendBlockDamage` / N-08)**: Periksa retakan blok naik bertahap 1–9 dan **hilang sepenuhnya** (bukan macet di stage 0) saat zombie mati, pindah target, atau `/stop`.
- **A-014 (`Zombie#setShouldBurnInDay` + `EntityCombustEvent`)**: Uji siang hari dengan Zombie & Skeleton; ubah `spawnInDayLight` dari `true` → `false`, jalankan `/mebahelzombiehorde reloadconfig`, pastikan zombie lama ikut terbakar.
- **A-021 (`EntityChangeBlockEvent` / N-10)**: Di wilayah WorldGuard dengan `block-break: deny`, pastikan kaca tidak pecah, tidak ada error konstruktor event di log, dan zombie tidak spam retakan terus-menerus (cooldown 10 detik D3-07).
- **A-003..A-006 (`[I]`)**: Verifikasi armor besi, senjata Pillager/Vindicator, spawn tidak di pucuk daun, dan 14 blok pagar/gerbang kayu + kaca polos.

## 3. 9 Skenario Pengujian Manual PRD §11 (+ Tambahan N-01/N-03/N-05/N-09)
1. **Inisialisasi, Config Default & Backup Korup (FR-01/02, N-03)**:
   - Jalankan server → `mebahel-zombie-horde_config.json` (11 key) & `mebahel-zombie-hordemob_type_config.json` terbentuk.
   - Rusak JSON secara sengaja (`{broken`), jalankan `/mebahelzombiehorde reloadconfig` → file `.corrupt-YYYYMMDD-HHMMSS.bak` tercipta dan default baru ditulis secara atomic.
2. **Spawn Timer & Pesan (FR-03..06, N-06)**:
   - Set `"hordeSpawnDelay": 1`, reload, tunggu ≤60 detik → horde muncul 30–59 blok, chat `A horde has spawned near X, Y, Z.` tampil.
3. **Patroli Kelompok, WaypointLadder & Berbagi Target (FR-08, N-02)**:
   - Horde berjalan sebagai kelompok lewat waypoint ≤32 blok; saat 1 anggota melihat pemain, seluruh anggota radius 64 blok menyerang; bila leader mati, anggota UUID terkecil menjadi leader baru.
4. **Pemecahan Kaca & Pagar + WorldGuard (FR-09, N-01, N-08, N-10)**:
   - Berlindung di balik `GLASS`/`GLASS_PANE`/`OAK_FENCE` → zombie mendeteksi dalam ≤1 detik (throttle 20 tick), memecahkan dalam ≈10 detik (lebih cepat bila >1 zombie); dengan `hordeMemberBreakGlass=false` kaca tidak pecah; di wilayah WorldGuard terlindungi blok tidak pecah dan masuk cooldown 10 detik.
5. **Persistensi Lintas Restart (FR-09b, N-07, D3-05)**:
   - `/stop` saat horde berpatroli lalu nyalakan ulang → `EntityAddToWorldEvent` memasang ulang goal & mendaftarkan ke `HordeRegistry`; kecepatan dasar pulih normal saat tidak patroli; `/mebahelzombiehorde reloadconfig` menyapu via `HordeRegistry` tanpa `world.getEntities()`.
6. **Difficulty Nether (FR-10)**:
   - Raih advancement `minecraft:nether/root`, kembali ke Overworld, tunggu ≤15 detik → log mencatat level 2, `/mebahelzombiehorde nextspawn` menampilkan `Difficulty: 2`, bertahan lintas restart.
7. **Siang Hari (FR-11, D-09)**:
   - `spawnInDayLight=true` → anggota horde tidak terbakar siang & timer jalan siang; `false` (termasuk setelah reload) → terbakar siang & timer hanya malam.
8. **Despawn Jarak Jauh (`simulation-distance=16`, N-05)**:
   - **Prasyarat wajib**: Setel `simulation-distance=16` di `server.properties` (default 10 chunk = 160 blok membekukan AI sebelum 245 blok).
   - Menjauh >245 blok dari horde → anggota hilang dalam ≤1 detik. Mati di dekat horde (death screen) TIDAK langsung menghapus horde.
9. **Clean Shutdown & Maintenance Independen (NFR-07, N-09)**:
   - Set `hordeSpawning=false`, spawn horde manual via `/mebahelzombiehorde spawnhorde`, biarkan zombie meretakkan kaca lalu bunuh/pindahkan → retakan tetap kedaluwarsa bersih dalam ≤5 detik karena `ensureMaintenanceStarted()` berjalan independen.
   - `/stop` saat retakan aktif → tidak ada error, kedua task dibatalkan, semua retakan di-reset, `saved_base_speed` dipulihkan.

## 4. Uji Manual Tambahan Regresi v1.0.4 (C4-01..C4-05)
- **C4-01 (Windows File-Lock)**: Pada server Paper 26.3 di Windows, tulis JSON rusak di `mebahel-zombie-horde_config.json` lalu jalankan `/mebahelzombiehorde reloadconfig` → file `.corrupt-*.bak` tercipta dan default baru tersimpan tanpa `AccessDeniedException`.
- **C4-02 (Unload tanpa Restart)**: Saat horde aktif, unload plugin (atau `/stop`) → tidak ada `IllegalPluginAccessException` dan mob tidak lagi menjalankan goal horde.
- **C4-03 (Follower Target Leader)**: Buat hanya leader yang melihat pemain sementara follower berada di belakang dinding → follower ikut menargetkan pemain yang sama dan bergerak menyerang.
- **C4-04 (WorldGuard `mob-spawning: deny`)**: Jalankan `/mebahelzombiehorde spawnhorde` di dalam region `mob-spawning: deny` → command melaporkan `ZERO_MEMBERS` dan tidak ada broadcast `"A horde has spawned near..."`.
- **C4-05 (WorldGuard Block-Break Cooldown)**: Biarkan zombie meretakkan kaca tak terlindungi lalu pindah ke kaca dalam region `block-break: deny` yang sedang cooldown → retakan kaca pertama langsung hilang bersih (tidak menggantung 5 detik).
