#!/usr/bin/env python3
"""Static self-check for MebahelZombieHorde. Usage: python3 verify_project.py <project_root>
Exit code 0 = all gates pass. Gemini: run it (or emulate it by hand) before every ZIP and paste the report in BUILDLOG.md."""
import re, sys, pathlib

root = pathlib.Path(sys.argv[1] if len(sys.argv) > 1 else ".")
PKG = "io.github.mebahel.zombiehorde"
src = root / "src/main/java" / PKG.replace(".", "/")
fails, passes = [], []

def gate(name, ok, detail=""):
    (passes if ok else fails).append(f"{name}{(' -> ' + detail) if detail and not ok else ''}")

java = {p: p.read_text(encoding="utf-8") for p in (root / "src").rglob("*.java")}

# G1 required files (PRD section 8)
required = ["build.gradle.kts", "settings.gradle.kts", "gradle.properties", "src/main/resources/plugin.yml",
            "README.md", "ASSUMPTIONS.md", "TESTING.md", "CHANGELOG.md"]
required += [f"src/main/java/{PKG.replace('.', '/')}/{f}" for f in [
    "ZombieHordePlugin.java", "config/MainConfig.java", "config/CompositionConfig.java", "config/ConfigLoader.java",
    "horde/HordeManager.java", "horde/HordeSpawner.java", "horde/Difficulty.java", "horde/PatrolData.java",
    "ai/HordeGoal.java", "ai/BreakBlockGoal.java", "ai/BreakableBlocks.java", "listener/EntityListener.java",
    "util/WeightedPicker.java", "util/PaperApiAdapter.java", "command/HordeCommand.java"]]
missing = [f for f in required if not (root / f).exists()]
gate("G1 required files exist", not missing, ", ".join(missing))

# G2 every internal import resolves to a file
bad = []
for p, t in java.items():
    for m in re.finditer(r"^import\s+(" + re.escape(PKG) + r"\.[\w.]+);", t, re.M):
        parts = m.group(1).split(".")
        # allow nested types: try progressively shorter paths
        ok = any((root / "src/main/java" / pathlib.Path(*parts[:i]).with_suffix(".java")).exists() for i in range(len(parts), 0, -1))
        if not ok:
            bad.append(f"{p.name}: {m.group(1)}")
gate("G2 internal imports resolve", not bad, "; ".join(bad))

# G3 brace/paren/bracket balance per file (proper tokenizer: skips comments, strings, char literals)
def _strip(src):
    out, i, n = [], 0, len(src)
    while i < n:
        c = src[i]
        if src.startswith("//", i):
            j = src.find("\n", i); i = n if j < 0 else j
        elif src.startswith("/*", i):
            j = src.find("*/", i + 2); i = n if j < 0 else j + 2
        elif src.startswith('"""', i):
            j = src.find('"""', i + 3); i = n if j < 0 else j + 3
        elif c in "\"'":
            q = c; i += 1
            while i < n and src[i] != q:
                i += 2 if src[i] == "\\" else 1
            i += 1
        else:
            out.append(c); i += 1
    return "".join(out)
unb = []
for p, t in java.items():
    s = _strip(t)
    if s.count("{") != s.count("}") or s.count("(") != s.count(")") or s.count("[") != s.count("]"):
        unb.append(p.name)
gate("G3 braces/parens balanced", not unb, ", ".join(unb))

# G4 NFR-01: no NMS / craftbukkit / reflection / mixin
forb = re.compile(r"net\.minecraft\.|org\.bukkit\.craftbukkit|org\.spongepowered|Class\.forName|getDeclaredField|getDeclaredMethod|sun\.misc")
hits = [f"{p.name}: {m.group(0)}" for p, t in java.items() if "/test/" not in str(p) for m in forb.finditer(t)]
gate("G4 NFR-01 no NMS/reflection", not hits, "; ".join(hits))

# G5 exact config file names and keys (drop-in requirement, FR-01/02)
loader = "".join(t for p, t in java.items() if p.name == "ConfigLoader.java")
gate("G5a config file names", "mebahel-zombie-horde_config.json" in loader and "mebahel-zombie-hordemob_type_config.json" in loader)
mc = "".join(t for p, t in java.items() if p.name == "MainConfig.java")
keys = ["spawnInDayLight", "enableDifficultySystem", "hordeSpawning", "hordeSpawnDelay", "hordeSpawnChance",
        "randomNumberHordeReinforcements", "hordeNumber", "hordeMemberBonusHealth", "hordeMemberBreakGlass",
        "hordeMemberBreakFence", "showHordeSpawningMessage"]
gate("G5b 11 config keys present", all(f'"{k}"' in mc for k in keys), ", ".join(k for k in keys if f'"{k}"' not in mc))

# G6 plugin.yml main class + command exist
yml = (root / "src/main/resources/plugin.yml")
if yml.exists():
    y = yml.read_text(encoding="utf-8")
    m = re.search(r"^main:\s*(\S+)", y, re.M)
    gate("G6a plugin.yml main resolves", bool(m) and (root / "src/main/java" / (m.group(1).replace(".", "/") + ".java")).exists())
    gate("G6b plugin.yml api-version 26.3", re.search(r"api-version:\s*'?26\.3'?", y) is not None)
    gate("G6c command + permission declared", "mebahelzombiehorde" in y and "mebahelzombiehorde.admin" in y)

# G7 build.gradle.kts Java 25
b = (root / "build.gradle.kts")
gate("G7 Java 25 toolchain", b.exists() and "JavaLanguageVersion.of(25)" in b.read_text(encoding="utf-8"))

# G8 contracts used by ZombieHordePlugin
allsrc = "\n".join(java.values())
for sig, label in [("attachHordeGoals(", "EntityListener#attachHordeGoals"), ("clearAllProgress(", "BreakBlockGoal.clearAllProgress"),
                   ("reloadPluginConfigs(", "plugin.reloadPluginConfigs"), ("findOverworld(", "HordeManager#findOverworld"),
                   ("getRemainingTicksToNextSpawn(", "HordeManager#getRemainingTicksToNextSpawn")]:
    gate(f"G8 contract {label}", sig in allsrc)

# G9 no TODO/FIXME/stub left behind
todo = [f"{p.name}" for p, t in java.items() if re.search(r"\b(TODO|FIXME|NotImplemented|UnsupportedOperationException\(\"stub)", t)]
gate("G9 no TODO/FIXME/stub", not todo, ", ".join(todo))

# G10 Q5/NFR-07: onDisable cancels task and clears cracks
plug = "".join(t for p, t in java.items() if p.name == "ZombieHordePlugin.java")
gate("G10 onDisable cleanup", "hordeManager.stop()" in plug and "clearAllProgress()" in plug)

# G11 every VERIFY-API marker lives in PaperApiAdapter or is listed in ASSUMPTIONS.md
amd = (root / "ASSUMPTIONS.md").read_text(encoding="utf-8") if (root / "ASSUMPTIONS.md").exists() else ""
stray = [p.name for p, t in java.items() if "VERIFY-API" in t and p.name != "PaperApiAdapter.java" and p.name not in amd]
gate("G11 VERIFY-API markers tracked", not stray, ", ".join(stray))


# ---------------- v3 gates (G12..G21): each one maps to an audit defect N-xx in PROMPT_GEMINI_FASE_3.md ----------------
def read(rel):
    f = root / rel
    return f.read_text(encoding="utf-8") if f.exists() else ""
bbg = "".join(t for p, t in java.items() if p.name == "BreakBlockGoal.java")
hg = "".join(t for p, t in java.items() if p.name == "HordeGoal.java")
hm = "".join(t for p, t in java.items() if p.name == "HordeManager.java")
el = "".join(t for p, t in java.items() if p.name == "EntityListener.java")
docs = {n: read(n) for n in ["README.md", "ASSUMPTIONS.md", "TESTING.md", "BUILDLOG.md", "CHANGELOG.md"]}

# G12 (N-04) Gradle wrapper must exist so the user can run ./gradlew build
gate("G12 gradle wrapper present", (root / "gradlew").exists() and (root / "gradle/wrapper/gradle-wrapper.properties").exists(),
     "missing gradlew and/or gradle/wrapper/gradle-wrapper.properties (jar may be absent: README must say how to generate it)")

# G13 (N-01) BreakBlockGoal target scan must be throttled (canStart runs ~every 2 ticks per mob)
gate("G13 BreakBlockGoal scan throttled", "TARGET_SCAN_INTERVAL_TICKS" in bbg and "TickThrottle" in bbg,
     "need constant TARGET_SCAN_INTERVAL_TICKS and a TickThrottle guarding findTargetThroughBreakable in shouldActivate")

# G14 (N-02) HordeGoal must retry shorter waypoints inside one attempt
gate("G14 path ladder in HordeGoal", "WaypointLadder" in hg, "HordeGoal must iterate WaypointLadder.candidates(...) and try findPath/moveTo per candidate")

# G15 (N-03) corrupt config must be backed up before it is overwritten
cl = "".join(t for p, t in java.items() if p.name == "ConfigLoader.java")
gate("G15 corrupt config backed up", "ConfigBackup" in cl and cl.count("ConfigBackup.backupCorrupt") >= 2,
     "ConfigLoader must call ConfigBackup.backupCorrupt before every defaults overwrite caused by a read/parse failure (main + composition)")

# G16 (N-09) stale-crack cleanup must not depend on hordeSpawning=true
plug = "".join(t for p, t in java.items() if p.name == "ZombieHordePlugin.java")
gate("G16 maintenance task independent of hordeSpawning", "ensureMaintenanceStarted(" in plug and "expireStale(" in hm,
     "ZombieHordePlugin must start HordeManager.ensureMaintenanceStarted() unconditionally; expireStale() must run there")

# G17 (N-07) at most ONE server-wide entity walk (one-time bootstrap at enable); reload must use HordeRegistry
gate("G17 single bootstrap walk + registry", plug.count(".getEntities()") <= 1 and "HordeRegistry" in "\n".join(java.values()),
     "ZombieHordePlugin may call world.getEntities() at most once (one-time bootstrap in onEnable because POSTWORLD plugins miss earlier AddToWorld events); reloadPluginConfigs must iterate HordeRegistry.snapshot()")
gate("G17b listener maintains registry", "registry" in el.lower() and "EntityRemoveFromWorldEvent" in el)

# G18 (N-05) documented simulation-distance behaviour
gate("G18 simulation-distance documented", "simulation-distance" in docs["README.md"] + docs["TESTING.md"] + docs["ASSUMPTIONS.md"],
     "README/TESTING must explain that horde AI only ticks inside simulation distance (despawn >245 blocks needs simulation-distance>=16 to test)")

# G19 honesty: a line that says VERIFIED (not UNVERIFIED) must carry an https URL on the same line
liars = []
for n, t in docs.items():
    for i, line in enumerate(t.splitlines(), 1):
        if re.search(r"(?<!UN)VERIFIED", line) and "https://" not in line:
            liars.append(f"{n}:{i}")
gate("G19 VERIFIED claims carry a URL", not liars, ", ".join(liars[:8]))

# G20 (N-08) crack-overlay API: ASSUMPTIONS must state how progress 0.0 / stage mapping was resolved
gate("G20 sendBlockDamage semantics recorded", re.search(r"sendBlockDamage", docs["ASSUMPTIONS.md"]) is not None and
     re.search(r"(clear|reset)", docs["ASSUMPTIONS.md"], re.I) is not None)

# G21 new pure classes have tests
tests = "\n".join(t for p, t in java.items() if "/test/" in str(p))
gate("G21 tests cover tracker/ladder/backup/throttle/registry",
     all(k in tests for k in ["BreakProgressTracker", "WaypointLadder", "ConfigBackup", "TickThrottle", "HordeRegistry"]),
     ", ".join(k for k in ["BreakProgressTracker", "WaypointLadder", "ConfigBackup", "TickThrottle", "HordeRegistry"] if k not in tests))


# G22 (E-01) Java string literal sanity check (catches unescaped quotes like "{"hordeSpawnDelay": [10, "minute"]}")
bad_strings = []
for p, t in java.items():
    for lineno, line in enumerate(t.splitlines(), 1):
        code = line.split("//", 1)[0]
        code_nc = re.sub(r"'(\\.|[^'\\])'", "''", code)
        if re.search(r'"[^"\\]*\{[^"\\]*"[A-Za-z_]', code_nc) or re.search(r'"[A-Za-z_][A-Za-z0-9_]*"\s*:', code_nc):
            bad_strings.append(f"{p.name}:{lineno}")
        if '"""' not in code_nc:
            quotes, esc = 0, False
            for ch in code_nc:
                if esc:
                    esc = False
                elif ch == "\\":
                    esc = True
                elif ch == '"':
                    quotes += 1
            if quotes % 2 != 0:
                bad_strings.append(f"{p.name}:{lineno} (odd quotes)")
gate("G22 Java string literals valid (no unescaped quotes)", not bad_strings, "; ".join(bad_strings))

print("PASS:", len(passes), " FAIL:", len(fails))
for f in fails:
    print("  [FAIL]", f)
sys.exit(1 if fails else 0)
