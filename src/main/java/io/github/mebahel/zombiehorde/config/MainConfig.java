package io.github.mebahel.zombiehorde.config;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Locale;
import java.util.logging.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class MainConfig {

    public static final int DEFAULT_SPAWN_DELAY_MINUTES = 15;
    public static final int MIN_SPAWN_DELAY = 1;
    public static final int MAX_SPAWN_DELAY = 60;
    public static final float DEFAULT_SPAWN_CHANCE = 1.0f;
    public static final int DEFAULT_REINFORCEMENTS = 0;
    public static final int MIN_REINFORCEMENTS = 0;
    public static final int MAX_REINFORCEMENTS = 10;
    public static final int DEFAULT_HORDE_NUMBER = 1;
    public static final int MIN_HORDE_NUMBER = 1;
    public static final int MAX_HORDE_NUMBER_PHASE1 = 5;
    public static final int MAX_HORDE_NUMBER_PHASE2 = 20;
    public static final int DEFAULT_BONUS_HEALTH = 0;
    public static final int MIN_BONUS_HEALTH = 0;
    public static final int MAX_BONUS_HEALTH = 40;

    public static final long TICKS_PER_MINUTE = 1200L;
    public static final long TICKS_PER_MINECRAFT_DAY = 24000L;

    private Boolean spawnInDayLight;
    private Boolean enableDifficultySystem;
    private Boolean hordeSpawning;
    private Integer hordeSpawnDelay;
    private String hordeSpawnDelayUnit; // null for Phase 1 integer form; "minute" or "day" for Phase 2 array form
    private Float hordeSpawnChance;
    private Integer randomNumberHordeReinforcements;
    private Integer hordeNumber;
    private Integer hordeMemberBonusHealth;
    private Boolean hordeMemberBreakGlass;
    private Boolean hordeMemberBreakFence;
    private Boolean showHordeSpawningMessage;

    // Optional Phase 2 toggle (omitted from JSON unless explicitly present so Phase 1 stays drop-in identical)
    private Boolean enablePhase2Features;

    public MainConfig() {
    }

    @NotNull
    public static MainConfig createDefault() {
        MainConfig cfg = new MainConfig();
        cfg.spawnInDayLight = true;
        cfg.enableDifficultySystem = true;
        cfg.hordeSpawning = true;
        cfg.hordeSpawnDelay = DEFAULT_SPAWN_DELAY_MINUTES;
        cfg.hordeSpawnDelayUnit = null;
        cfg.hordeSpawnChance = DEFAULT_SPAWN_CHANCE;
        cfg.randomNumberHordeReinforcements = DEFAULT_REINFORCEMENTS;
        cfg.hordeNumber = DEFAULT_HORDE_NUMBER;
        cfg.hordeMemberBonusHealth = DEFAULT_BONUS_HEALTH;
        cfg.hordeMemberBreakGlass = true;
        cfg.hordeMemberBreakFence = true;
        cfg.showHordeSpawningMessage = true;
        cfg.enablePhase2Features = null;
        return cfg;
    }

    @NotNull
    public static MainConfig fromJson(@NotNull JsonObject obj, @Nullable Logger logger) {
        MainConfig cfg = new MainConfig();
        cfg.spawnInDayLight = readBoolean(obj, "spawnInDayLight");
        cfg.enableDifficultySystem = readBoolean(obj, "enableDifficultySystem");
        cfg.hordeSpawning = readBoolean(obj, "hordeSpawning");
        cfg.enablePhase2Features = readBoolean(obj, "enablePhase2Features");

        JsonElement delayElem = obj.get("hordeSpawnDelay");
        if (delayElem != null && delayElem.isJsonArray()) {
            JsonArray arr = delayElem.getAsJsonArray();
            if (arr.size() >= 2 && arr.get(0).isJsonPrimitive() && arr.get(1).isJsonPrimitive()) {
                try {
                    cfg.hordeSpawnDelay = arr.get(0).getAsInt();
                    String unit = arr.get(1).getAsString().toLowerCase(Locale.ROOT).trim();
                    if ("minute".equals(unit) || "minutes".equals(unit)) {
                        cfg.hordeSpawnDelayUnit = "minute";
                    } else if ("day".equals(unit) || "days".equals(unit)) {
                        cfg.hordeSpawnDelayUnit = "day";
                    } else {
                        if (logger != null) {
                            logger.warning("Unknown hordeSpawnDelay unit '" + unit + "'; will reset to 'minute' on validation.");
                        }
                        // E-06: Keep raw invalid unit so validateAndFix() flags modified=true and rewrites file
                        cfg.hordeSpawnDelayUnit = unit;
                    }
                } catch (NumberFormatException ex) {
                    cfg.hordeSpawnDelay = null;
                    cfg.hordeSpawnDelayUnit = null;
                }
            }
        } else {
            cfg.hordeSpawnDelay = readInt(obj, "hordeSpawnDelay");
            cfg.hordeSpawnDelayUnit = null;
        }

        cfg.hordeSpawnChance = readFloat(obj, "hordeSpawnChance");
        cfg.randomNumberHordeReinforcements = readInt(obj, "randomNumberHordeReinforcements");
        cfg.hordeNumber = readInt(obj, "hordeNumber");
        cfg.hordeMemberBonusHealth = readInt(obj, "hordeMemberBonusHealth");
        cfg.hordeMemberBreakGlass = readBoolean(obj, "hordeMemberBreakGlass");
        cfg.hordeMemberBreakFence = readBoolean(obj, "hordeMemberBreakFence");
        cfg.showHordeSpawningMessage = readBoolean(obj, "showHordeSpawningMessage");
        return cfg;
    }

    /**
     * Validates all keys against PRD FR-01 bounds. Replaces null or out-of-range values with defaults.
     *
     * @return true if any value was corrected (signaling the file must be rewritten).
     */
    public boolean validateAndFix(@Nullable Logger logger) {
        boolean modified = false;

        if (spawnInDayLight == null) {
            spawnInDayLight = true;
            modified = true;
        }
        if (enableDifficultySystem == null) {
            enableDifficultySystem = true;
            modified = true;
        }
        if (hordeSpawning == null) {
            hordeSpawning = true;
            modified = true;
        }
        if (hordeSpawnDelayUnit != null
                && !"minute".equals(hordeSpawnDelayUnit)
                && !"day".equals(hordeSpawnDelayUnit)) {
            hordeSpawnDelayUnit = "minute";
            modified = true;
        }
        if (hordeSpawnDelay == null || hordeSpawnDelay < MIN_SPAWN_DELAY || hordeSpawnDelay > MAX_SPAWN_DELAY) {
            if (logger != null && hordeSpawnDelay != null) {
                logger.warning("Invalid hordeSpawnDelay=" + hordeSpawnDelay + "; resetting to " + DEFAULT_SPAWN_DELAY_MINUTES);
            }
            hordeSpawnDelay = DEFAULT_SPAWN_DELAY_MINUTES;
            if (hordeSpawnDelayUnit != null) {
                hordeSpawnDelayUnit = "minute";
            }
            modified = true;
        }
        if (hordeSpawnChance == null
                || !Float.isFinite(hordeSpawnChance)
                || hordeSpawnChance < 0.0f
                || hordeSpawnChance > 1.0f) {
            if (logger != null && hordeSpawnChance != null) {
                logger.warning("Invalid hordeSpawnChance=" + hordeSpawnChance + "; resetting to " + DEFAULT_SPAWN_CHANCE);
            }
            hordeSpawnChance = DEFAULT_SPAWN_CHANCE;
            modified = true;
        }
        if (randomNumberHordeReinforcements == null
                || randomNumberHordeReinforcements < MIN_REINFORCEMENTS
                || randomNumberHordeReinforcements > MAX_REINFORCEMENTS) {
            if (logger != null && randomNumberHordeReinforcements != null) {
                logger.warning("Invalid randomNumberHordeReinforcements=" + randomNumberHordeReinforcements
                        + "; resetting to " + DEFAULT_REINFORCEMENTS);
            }
            randomNumberHordeReinforcements = DEFAULT_REINFORCEMENTS;
            modified = true;
        }
        int maxHordeNum = isPhase2Enabled() ? MAX_HORDE_NUMBER_PHASE2 : MAX_HORDE_NUMBER_PHASE1;
        if (hordeNumber == null || hordeNumber < MIN_HORDE_NUMBER || hordeNumber > maxHordeNum) {
            if (logger != null && hordeNumber != null) {
                logger.warning("Invalid hordeNumber=" + hordeNumber + "; resetting to " + DEFAULT_HORDE_NUMBER);
            }
            hordeNumber = DEFAULT_HORDE_NUMBER;
            modified = true;
        }
        if (hordeMemberBonusHealth == null
                || hordeMemberBonusHealth < MIN_BONUS_HEALTH
                || hordeMemberBonusHealth > MAX_BONUS_HEALTH) {
            if (logger != null && hordeMemberBonusHealth != null) {
                logger.warning("Invalid hordeMemberBonusHealth=" + hordeMemberBonusHealth
                        + "; resetting to " + DEFAULT_BONUS_HEALTH);
            }
            hordeMemberBonusHealth = DEFAULT_BONUS_HEALTH;
            modified = true;
        }
        if (hordeMemberBreakGlass == null) {
            hordeMemberBreakGlass = true;
            modified = true;
        }
        if (hordeMemberBreakFence == null) {
            hordeMemberBreakFence = true;
            modified = true;
        }
        if (showHordeSpawningMessage == null) {
            showHordeSpawningMessage = true;
            modified = true;
        }

        return modified;
    }

    @NotNull
    public JsonObject toJson() {
        JsonObject obj = new JsonObject();
        obj.addProperty("spawnInDayLight", isSpawnInDayLight());
        obj.addProperty("enableDifficultySystem", isEnableDifficultySystem());
        obj.addProperty("hordeSpawning", isHordeSpawning());
        if (hordeSpawnDelayUnit != null) {
            JsonArray arr = new JsonArray();
            arr.add(getHordeSpawnDelay());
            arr.add(hordeSpawnDelayUnit);
            obj.add("hordeSpawnDelay", arr);
        } else {
            obj.addProperty("hordeSpawnDelay", getHordeSpawnDelay());
        }
        obj.addProperty("hordeSpawnChance", getHordeSpawnChance());
        obj.addProperty("randomNumberHordeReinforcements", getRandomNumberHordeReinforcements());
        obj.addProperty("hordeNumber", getHordeNumber());
        obj.addProperty("hordeMemberBonusHealth", getHordeMemberBonusHealth());
        obj.addProperty("hordeMemberBreakGlass", isHordeMemberBreakGlass());
        obj.addProperty("hordeMemberBreakFence", isHordeMemberBreakFence());
        obj.addProperty("showHordeSpawningMessage", isShowHordeSpawningMessage());
        if (enablePhase2Features != null) {
            obj.addProperty("enablePhase2Features", enablePhase2Features);
        }
        return obj;
    }

    public long getSpawnDelayTicks() {
        int amount = getHordeSpawnDelay();
        if ("day".equals(hordeSpawnDelayUnit)) {
            return amount * TICKS_PER_MINECRAFT_DAY;
        }
        return amount * TICKS_PER_MINUTE;
    }

    public boolean isSpawnInDayLight() {
        return spawnInDayLight != null ? spawnInDayLight : true;
    }

    public void setSpawnInDayLight(Boolean spawnInDayLight) {
        this.spawnInDayLight = spawnInDayLight;
    }

    public boolean isEnableDifficultySystem() {
        return enableDifficultySystem != null ? enableDifficultySystem : true;
    }

    public void setEnableDifficultySystem(Boolean enableDifficultySystem) {
        this.enableDifficultySystem = enableDifficultySystem;
    }

    public boolean isHordeSpawning() {
        return hordeSpawning != null ? hordeSpawning : true;
    }

    public void setHordeSpawning(Boolean hordeSpawning) {
        this.hordeSpawning = hordeSpawning;
    }

    public int getHordeSpawnDelay() {
        return hordeSpawnDelay != null ? hordeSpawnDelay : DEFAULT_SPAWN_DELAY_MINUTES;
    }

    public void setHordeSpawnDelay(Integer hordeSpawnDelay) {
        this.hordeSpawnDelay = hordeSpawnDelay;
    }

    @Nullable
    public String getHordeSpawnDelayUnit() {
        return hordeSpawnDelayUnit;
    }

    public void setHordeSpawnDelayUnit(@Nullable String hordeSpawnDelayUnit) {
        this.hordeSpawnDelayUnit = hordeSpawnDelayUnit;
    }

    public float getHordeSpawnChance() {
        return hordeSpawnChance != null ? hordeSpawnChance : DEFAULT_SPAWN_CHANCE;
    }

    public void setHordeSpawnChance(Float hordeSpawnChance) {
        this.hordeSpawnChance = hordeSpawnChance;
    }

    public int getRandomNumberHordeReinforcements() {
        return randomNumberHordeReinforcements != null ? randomNumberHordeReinforcements : DEFAULT_REINFORCEMENTS;
    }

    public void setRandomNumberHordeReinforcements(Integer randomNumberHordeReinforcements) {
        this.randomNumberHordeReinforcements = randomNumberHordeReinforcements;
    }

    public int getHordeNumber() {
        return hordeNumber != null ? hordeNumber : DEFAULT_HORDE_NUMBER;
    }

    public void setHordeNumber(Integer hordeNumber) {
        this.hordeNumber = hordeNumber;
    }

    public int getHordeMemberBonusHealth() {
        return hordeMemberBonusHealth != null ? hordeMemberBonusHealth : DEFAULT_BONUS_HEALTH;
    }

    public void setHordeMemberBonusHealth(Integer hordeMemberBonusHealth) {
        this.hordeMemberBonusHealth = hordeMemberBonusHealth;
    }

    public boolean isHordeMemberBreakGlass() {
        return hordeMemberBreakGlass != null ? hordeMemberBreakGlass : true;
    }

    public void setHordeMemberBreakGlass(Boolean hordeMemberBreakGlass) {
        this.hordeMemberBreakGlass = hordeMemberBreakGlass;
    }

    public boolean isHordeMemberBreakFence() {
        return hordeMemberBreakFence != null ? hordeMemberBreakFence : true;
    }

    public void setHordeMemberBreakFence(Boolean hordeMemberBreakFence) {
        this.hordeMemberBreakFence = hordeMemberBreakFence;
    }

    public boolean isShowHordeSpawningMessage() {
        return showHordeSpawningMessage != null ? showHordeSpawningMessage : true;
    }

    public void setShowHordeSpawningMessage(Boolean showHordeSpawningMessage) {
        this.showHordeSpawningMessage = showHordeSpawningMessage;
    }

    public boolean isPhase2Enabled() {
        return Boolean.TRUE.equals(enablePhase2Features);
    }

    public void setEnablePhase2Features(@Nullable Boolean enablePhase2Features) {
        this.enablePhase2Features = enablePhase2Features;
    }

    @Nullable
    private static Boolean readBoolean(@NotNull JsonObject obj, @NotNull String key) {
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull() || !el.isJsonPrimitive() || !el.getAsJsonPrimitive().isBoolean()) {
            return null;
        }
        return el.getAsBoolean();
    }

    @Nullable
    private static Integer readInt(@NotNull JsonObject obj, @NotNull String key) {
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull() || !el.isJsonPrimitive() || !el.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        try {
            return el.getAsInt();
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    @Nullable
    private static Float readFloat(@NotNull JsonObject obj, @NotNull String key) {
        JsonElement el = obj.get(key);
        if (el == null || el.isJsonNull() || !el.isJsonPrimitive() || !el.getAsJsonPrimitive().isNumber()) {
            return null;
        }
        try {
            return el.getAsFloat();
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
