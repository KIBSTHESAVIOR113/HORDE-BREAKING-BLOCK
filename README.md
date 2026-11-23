# MebahelZombieHorde (Paper 26.3 / Java 25)

---

## Bahasa Indonesia

Port **clean-room** untuk Paper 26.3 (Java 25) yang mereplikasi perilaku yang dapat diamati dari mod Fabric *Mebahel's Zombie Horde* v1.0.11 menggunakan Paper API murni (tanpa NMS, tanpa refleksi, tanpa Mixin).

### Atribusi & Lisensi
> **Perilaku terinspirasi dari Mebahel's Zombie Horde oleh Mebahel. Implementasi ini ditulis ulang dari spesifikasi perilaku.**
>
> **Catatan Distribusi:** Mod asli menyatakan lisensi *All Rights Reserved* di halaman CurseForge. Penggunaan di server pribadi berisiko rendah, namun distribusi publik (Hangar, Modrinth, GitHub publik) sebaiknya meminta izin tertulis dari penulis asli (Mebahel).

### Fitur Utama
- **Fase 1 (Setara v1.0.11)**: Konfigurasi JSON drop-in (`mebahel-zombie-horde_config.json` & `mebahel-zombie-hordemob_type_config.json`) dengan cadangan otomatis saat korup (`.corrupt-*.bak`) & penulisan atomic, timer horde Overworld, sistem difficulty permanen berbasis advancement Nether (`minecraft:nether/root`), AI patroli kelompok dengan tangga waypoint (`WaypointLadder`), pemecahan kaca polos & 14 blok pagar/gerbang kayu (`BreakProgressTracker`), kekebalan sinar matahari siang hari, persistensi PDC lintas restart.
- **Fase 2 (Opsional, kompatibel ke belakang)**: Command `/mebahelzombiehorde <spawnhorde|reloadconfig|nextspawn>`, format delay array `[n, "minute"|"day"]`, filter `dimensions`, `spawnWithWeaponProbability`, dan batas `hordeNumber` hingga 20 (melalui `enablePhase2Features: true`).
- **Catatan Quirk Q4**: Sesuai spesifikasi FR-07, mob seperti Skeleton atau tipe lain yang dipilih dari tabel komposisi dapat menerima pedang/senjata tangan utama.
- **Catatan `simulation-distance` (N-05)**: Di Paper, AI mob hanya ber-tick di dalam radius `simulation-distance` (default 10 chunk = 160 blok). Untuk menguji despawn >245 blok (FR-08 / `TESTING.md` #8), setel `simulation-distance=16` pada `server.properties` atau `config/paper-world-defaults.yml`; pada nilai default 10 chunk, horde di luar 160 blok akan berhenti tick (dibekukan) hingga pemain kembali mendekat.

### Cara Membangun & Instal
1. Pastikan **JDK 25** terpasang (Gradle >= 9.1.0 mendukung Java 25 — VERIFIED https://docs.gradle.org/9.1.0/release-notes.html).
2. `gradle/wrapper/gradle-wrapper.jar` (biner) tidak disertakan dalam arsip teks ini. Lakukan bootstrap wrapper satu kali:
   ```bash
   gradle wrapper --gradle-version 9.1.0
   ```
   (Alternatif: buka proyek di IntelliJ IDEA lalu klik **Sync Gradle**).
3. Jalankan build & tes unit:
   ```bash
   ./gradlew clean test build
   ```
   (Jika resolusi `io.papermc.paper:paper-api:26.3.build.+` bermasalah, cek versi terbaru di `https://repo.papermc.io/repository/maven-public/io/papermc/paper/paper-api/`).
4. Salin `build/libs/MebahelZombieHorde-1.0.4.jar` ke folder `plugins/` server Paper 26.3, lalu nyalakan server.

---

## English

A **clean-room** Paper 26.3 (Java 25) port replicating the observable behavior of the Fabric mod *Mebahel's Zombie Horde* v1.0.11 using pure Paper API (zero NMS, zero reflection, zero Mixin).

### Attribution & Licensing
> **Behavior inspired by Mebahel's Zombie Horde by Mebahel. This implementation is rewritten from a behavioral specification.**
>
> **Distribution Notice:** The original mod is listed as *All Rights Reserved* on CurseForge. Private server use is low risk, but public redistribution should obtain written permission from the original author (Mebahel).

### Note on `simulation-distance` (N-05)
On Paper servers, mob AI goals only tick within `simulation-distance` (default 10 chunks = 160 blocks). Testing the >245-block far-despawn rule (FR-08) requires setting `simulation-distance=16` in `server.properties` (or `paper-world-defaults.yml`); at lower values, mobs outside simulation distance pause ticking rather than immediately despawning.

### Build & Installation
1. Install **JDK 25** (Gradle >= 9.1.0 supports Java 25 — VERIFIED https://docs.gradle.org/9.1.0/release-notes.html).
2. Bootstrap the omitted binary `gradle-wrapper.jar` once:
   ```bash
   gradle wrapper --gradle-version 9.1.0
   ```
3. Run `./gradlew clean test build` to execute unit tests and produce `build/libs/MebahelZombieHorde-1.0.4.jar`.
4. Copy the JAR into your Paper 26.3 `plugins/` directory and start the server.

---

## Tabel Traceability / Traceability Matrix

| ID | Deskripsi Singkat | File / Kelas / Metode |
|---|---|---|
| FR-01 | Konfigurasi umum JSON drop-in + validasi, backup korup & tulis atomic | `config/MainConfig.java`, `config/ConfigLoader.java#loadMainConfig`, `config/ConfigBackup.java` |
| FR-02 | Konfigurasi komposisi horde JSON drop-in + sanitasi null/dimensions | `config/CompositionConfig.java`, `config/ConfigLoader.java#loadCompositionConfig` |
| FR-03 | Timer spawn global Overworld (task 20 tick + retry NO_LOADED_COLUMN) | `horde/HordeManager.java#onTickStep` |
| FR-04 | Penentuan titik spawn 30–59 (retry 12×) & target patroli 150–299 | `horde/HordeSpawner.java#spawnHorde`, `findSpawnColumn`, `pickFarPatrolTarget` |
| FR-05 | Offset anggota 5–9 blok | `horde/HordeSpawner.java#randomMemberOffset` |
| FR-05b | Jumlah follower `(4+2d)+rand(1+2d)` + reinforcement + roll berbobot | `horde/HordeSpawner.java#rollFollowers`, `util/WeightedPicker.java#pick` |
| FR-06 | Spawn leader & follower, `randomizeData=false`, PDC pre-spawn, pesan chat | `horde/HordeSpawner.java#spawnSingleMember`, `util/PaperApiAdapter.java#spawnMobWithoutRandomization` |
| FR-07 | Armor `0.06*d`, senjata `0.13*d`, default Pillager/Vindicator | `horde/HordeSpawner.java#equipMember`, `util/PaperApiAdapter.java` |
| FR-08 | AI patroli, `WaypointLadder`, share target 64 blok, leader deterministik, despawn >245 blok | `ai/HordeGoal.java`, `ai/WaypointLadder.java` |
| FR-09 | Pemecahan kaca/pagar, raycast 24 blok (`TickThrottle`), `BreakProgressTracker`, WorldGuard | `ai/BreakBlockGoal.java`, `ai/BreakProgressTracker.java`, `ai/TickThrottle.java`, `ai/BreakableBlocks.java` |
| FR-09b | Pemasangan goal idempoten + pelacakan `HordeRegistry` | `listener/EntityListener.java`, `horde/HordeRegistry.java`, `ZombieHordePlugin.java` |
| FR-10 | Difficulty dunia 1/2 di PDC World + cek Nether tiap 300 tick | `horde/Difficulty.java#checkNetherProgression` |
| FR-11 | Kekebalan sinar matahari siang hari + sinkron reload | `listener/EntityListener.java#onEntityCombust`, `PaperApiAdapter.java#setShouldBurnInDay` |
| FR-12 | Persistensi data patroli di PDC entity | `horde/PatrolData.java` |
| FR-13 | Logging terstruktur via plugin logger | `ZombieHordePlugin.java`, `ConfigLoader.java`, `HordeSpawner.java`, `Difficulty.java` |
| NFR-01 | Hanya Paper API (tanpa NMS/refleksi/Mixin) | Seluruh proyek |
| NFR-02 | Reload config aman tanpa memutus horde / mereset timer | `ZombieHordePlugin.java#reloadPluginConfigs`, `HordeManager.java#ensureStarted`, `HordeRegistry.java` |
| NFR-03 | Tahan config rusak (backup `.corrupt-*.bak` + fallback default + tulis atomic) | `config/ConfigLoader.java`, `config/ConfigBackup.java` |
| NFR-04 | Kinerja <0.5 ms/tick (`TickThrottle`, `HordeRegistry`, jitter, `MAX_PLAYERS_RAYCAST_PER_SCAN`) | `ai/HordeGoal.java`, `ai/BreakBlockGoal.java`, `horde/HordeRegistry.java` |
| NFR-05 | Tanpa sync chunk load (`isChunkLoaded`, `getChunkAtAsync`, `WaypointLadder`) | `horde/HordeSpawner.java`, `ai/HordeGoal.java` |
| NFR-06 | Kode & log bahasa Inggris; README dwibahasa | Seluruh proyek & `README.md` |
| NFR-07 | `onDisable` bersih (batal 2 task, reset retakan, pulihkan `baseSpeed`, clear registry) | `ZombieHordePlugin.java#onDisable`, `HordeManager.java#stop`, `BreakBlockGoal.java#clearAllProgress` |
| Q1–Q9 | Keputusan quirk mod asli | `MainConfig` (Q1), `CompositionConfig` (Q2, Q3, Q7), `HordeSpawner` (Q4, Q8), `HordeManager` (Q5), `BreakBlockGoal` (Q6), `HordeGoal`+`PatrolData`+`ZombieHordePlugin` (Q9) |
