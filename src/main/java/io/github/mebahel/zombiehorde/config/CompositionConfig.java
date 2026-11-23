package io.github.mebahel.zombiehorde.config;

import io.github.mebahel.zombiehorde.util.WeightedPicker;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.logging.Logger;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public final class CompositionConfig {

    private List<HordeComposition> hordeCompositions;

    public CompositionConfig() {
        this.hordeCompositions = new ArrayList<>();
    }

    @NotNull
    public static CompositionConfig createDefault() {
        CompositionConfig config = new CompositionConfig();
        List<WeaponEntry> weapons = new ArrayList<>();
        weapons.add(new WeaponEntry("minecraft:iron_sword", 0.65f));
        weapons.add(new WeaponEntry("minecraft:stone_sword", 0.35f));

        List<MobTypeEntry> mobTypes = new ArrayList<>();
        mobTypes.add(new MobTypeEntry("minecraft:zombie", 30, weapons));

        List<HordeComposition> compositions = new ArrayList<>();
        compositions.add(new HordeComposition(1, mobTypes));
        config.setHordeCompositions(compositions);
        return config;
    }

    /**
     * Validates composition weights (FR-02), removes null/blank entries (F-3),
     * validates mob/weapon entries (Q7), and logs warnings for unknown registry IDs (Q3, FR-07).
     *
     * @return true if any value was fixed and the file should be rewritten.
     */
    public boolean validateAndFix(@Nullable Logger logger) {
        return validateAndFix(logger, true);
    }

    /**
     * Package-accessible overload allowing pure unit tests to skip Bukkit Registry lookups.
     */
    public boolean validateAndFix(@Nullable Logger logger, boolean checkRegistry) {
        boolean modified = false;
        if (hordeCompositions == null || hordeCompositions.isEmpty()) {
            CompositionConfig defaults = createDefault();
            this.hordeCompositions = defaults.hordeCompositions;
            return true;
        }

        Iterator<HordeComposition> compIter = hordeCompositions.iterator();
        while (compIter.hasNext()) {
            HordeComposition comp = compIter.next();
            if (comp == null) {
                compIter.remove();
                modified = true;
                continue;
            }
            if (comp.weight <= 0) {
                if (logger != null) {
                    logger.warning("Composition weight <= 0 (" + comp.weight + "); correcting to 1.");
                }
                comp.weight = 1;
                modified = true;
            }
            // D3-04: Sanitize optional dimensions list (remove null/blank, trim whitespace)
            if (comp.dimensions != null) {
                List<String> cleanedDims = new ArrayList<>();
                for (String dim : comp.dimensions) {
                    if (dim == null || dim.isBlank()) {
                        modified = true;
                        continue;
                    }
                    String trimmed = dim.trim();
                    if (!trimmed.equals(dim)) {
                        modified = true;
                    }
                    cleanedDims.add(trimmed);
                }
                if (cleanedDims.isEmpty()) {
                    comp.dimensions = null;
                    modified = true;
                } else if (cleanedDims.size() != comp.dimensions.size() || modified) {
                    comp.dimensions = cleanedDims;
                }
            }
            if (comp.mobTypes == null) {
                comp.mobTypes = new ArrayList<>();
                modified = true;
            }

            Iterator<MobTypeEntry> mobIter = comp.mobTypes.iterator();
            while (mobIter.hasNext()) {
                MobTypeEntry mob = mobIter.next();
                if (mob == null || mob.id == null || mob.id.isBlank()) {
                    if (logger != null) {
                        logger.warning("Removed null or blank mobType entry in composition config.");
                    }
                    mobIter.remove();
                    modified = true;
                    continue;
                }
                if (mob.weight <= 0) {
                    if (logger != null) {
                        logger.warning("MobType '" + mob.id + "' has weight <= 0 (" + mob.weight + "); correcting to 1.");
                    }
                    mob.weight = 1;
                    modified = true;
                }
                if (mob.spawnWithWeaponProbability != null
                        && (!Float.isFinite(mob.spawnWithWeaponProbability)
                        || mob.spawnWithWeaponProbability < 0.0f
                        || mob.spawnWithWeaponProbability > 1.0f)) {
                    if (logger != null) {
                        logger.warning("MobType '" + mob.id + "' has invalid spawnWithWeaponProbability="
                                + mob.spawnWithWeaponProbability + "; clearing.");
                    }
                    mob.spawnWithWeaponProbability = null;
                    modified = true;
                }
                if (checkRegistry && logger != null && resolveEntityType(mob.id) == null) {
                    logger.warning("Unknown or non-mob entity id in composition config: '" + mob.id + "'. It will be skipped.");
                }
                if (mob.weapons == null) {
                    mob.weapons = new ArrayList<>();
                    modified = true;
                }

                Iterator<WeaponEntry> weaponIter = mob.weapons.iterator();
                while (weaponIter.hasNext()) {
                    WeaponEntry weapon = weaponIter.next();
                    if (weapon == null || weapon.itemId == null || weapon.itemId.isBlank()) {
                        if (logger != null) {
                            logger.warning("Removed null or blank weapon entry for mobType '" + mob.id + "'.");
                        }
                        weaponIter.remove();
                        modified = true;
                        continue;
                    }
                    if (!Float.isFinite(weapon.chance) || weapon.chance < 0.0f) {
                        if (logger != null) {
                            logger.warning("Weapon '" + weapon.itemId + "' has invalid chance (" + weapon.chance + "); correcting to 0.0.");
                        }
                        weapon.chance = 0.0f;
                        modified = true;
                    }
                    if (checkRegistry && logger != null && resolveMaterial(weapon.itemId) == null) {
                        logger.warning("Unknown weapon itemId in composition config: '" + weapon.itemId + "'. It will be skipped.");
                    }
                }
            }

            if (comp.mobTypes.isEmpty()) {
                if (logger != null) {
                    logger.severe("Composition had no valid mobTypes after cleanup; restoring default zombie entry.");
                }
                comp.mobTypes.addAll(createDefault().getHordeCompositions().get(0).getMobTypes());
                modified = true;
            }
        }

        if (hordeCompositions.isEmpty()) {
            this.hordeCompositions = createDefault().hordeCompositions;
            modified = true;
        }
        return modified;
    }

    @Nullable
    public HordeComposition pickComposition(@NotNull Random random, @NotNull World world) {
        return pickComposition(random, world.getKey().asString(), world.getEnvironment());
    }

    @Nullable
    public HordeComposition pickComposition(
            @NotNull Random random,
            @Nullable String worldKey,
            @Nullable World.Environment environment
    ) {
        if (hordeCompositions == null || hordeCompositions.isEmpty()) {
            return null;
        }
        List<HordeComposition> eligible = new ArrayList<>();
        for (HordeComposition comp : hordeCompositions) {
            if (comp != null && comp.matchesDimension(worldKey, environment)) {
                eligible.add(comp);
            }
        }
        if (eligible.isEmpty()) {
            return null;
        }
        return WeightedPicker.pick(eligible, HordeComposition::getWeight, random);
    }

    @Nullable
    public static WeaponEntry pickWeapon(@Nullable List<WeaponEntry> weapons, @NotNull Random random) {
        if (weapons == null || weapons.isEmpty()) {
            return null;
        }
        float totalChance = 0.0f;
        for (WeaponEntry entry : weapons) {
            if (entry != null && entry.chance > 0.0f && Float.isFinite(entry.chance)) {
                totalChance += entry.chance;
            }
        }
        if (totalChance <= 0.0f) {
            return null;
        }
        float roll = random.nextFloat() * totalChance;
        WeaponEntry lastValid = null;
        for (WeaponEntry entry : weapons) {
            if (entry == null || entry.chance <= 0.0f || !Float.isFinite(entry.chance)) {
                continue;
            }
            lastValid = entry;
            roll -= entry.chance;
            if (roll <= 0.0f) {
                return entry;
            }
        }
        return lastValid;
    }

    @Nullable
    public static EntityType resolveEntityType(@Nullable String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        NamespacedKey key = NamespacedKey.fromString(id.toLowerCase(Locale.ROOT).trim());
        if (key == null) {
            return null;
        }
        EntityType type = Registry.ENTITY_TYPE.get(key);
        if (type == null || !type.isSpawnable()) {
            return null;
        }
        return type;
    }

    @Nullable
    public static Material resolveMaterial(@Nullable String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return null;
        }
        NamespacedKey key = NamespacedKey.fromString(itemId.toLowerCase(Locale.ROOT).trim());
        if (key == null) {
            return null;
        }
        Material material = Registry.MATERIAL.get(key);
        if (material == null || !material.isItem()) {
            return null;
        }
        return material;
    }

    public List<HordeComposition> getHordeCompositions() {
        return hordeCompositions;
    }

    public void setHordeCompositions(List<HordeComposition> hordeCompositions) {
        this.hordeCompositions = hordeCompositions;
    }

    public static final class HordeComposition {
        private int weight;
        private List<String> dimensions; // Optional Phase 2 field; null/empty = any Overworld (Environment.NORMAL)
        private List<MobTypeEntry> mobTypes;

        public HordeComposition() {
            this.weight = 1;
            this.mobTypes = new ArrayList<>();
        }

        public HordeComposition(int weight, List<MobTypeEntry> mobTypes) {
            this.weight = weight;
            this.mobTypes = mobTypes;
        }

        public int getWeight() {
            return weight;
        }

        public void setWeight(int weight) {
            this.weight = weight;
        }

        @Nullable
        public List<String> getDimensions() {
            return dimensions;
        }

        public void setDimensions(@Nullable List<String> dimensions) {
            this.dimensions = dimensions;
        }

        public List<MobTypeEntry> getMobTypes() {
            return mobTypes;
        }

        public void setMobTypes(List<MobTypeEntry> mobTypes) {
            this.mobTypes = mobTypes;
        }

        /**
         * Matches world by Environment.NORMAL when dimensions is null/empty (F-4),
         * or by explicit namespaced world key when dimensions is specified (Phase 2).
         */
        public boolean matchesDimension(@Nullable String worldKey, @Nullable World.Environment environment) {
            if (dimensions == null || dimensions.isEmpty()) {
                if (environment != null) {
                    return environment == World.Environment.NORMAL;
                }
                return worldKey == null || "minecraft:overworld".equalsIgnoreCase(worldKey);
            }
            if (worldKey == null) {
                return false;
            }
            for (String dim : dimensions) {
                if (dim != null && dim.equalsIgnoreCase(worldKey)) {
                    return true;
                }
            }
            return false;
        }

        @Nullable
        public MobTypeEntry pickMobType(@NotNull Random random) {
            if (mobTypes == null || mobTypes.isEmpty()) {
                return null;
            }
            return WeightedPicker.pick(mobTypes, MobTypeEntry::getWeight, random);
        }
    }

    public static final class MobTypeEntry {
        private String id;
        private int weight;
        private Float spawnWithWeaponProbability; // Optional Phase 2 override
        private List<WeaponEntry> weapons;

        public MobTypeEntry() {
            this.weight = 1;
            this.weapons = new ArrayList<>();
        }

        public MobTypeEntry(String id, int weight, List<WeaponEntry> weapons) {
            this.id = id;
            this.weight = weight;
            this.weapons = weapons;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public int getWeight() {
            return weight;
        }

        public void setWeight(int weight) {
            this.weight = weight;
        }

        @Nullable
        public Float getSpawnWithWeaponProbability() {
            return spawnWithWeaponProbability;
        }

        public void setSpawnWithWeaponProbability(@Nullable Float spawnWithWeaponProbability) {
            this.spawnWithWeaponProbability = spawnWithWeaponProbability;
        }

        public List<WeaponEntry> getWeapons() {
            return weapons;
        }

        public void setWeapons(List<WeaponEntry> weapons) {
            this.weapons = weapons;
        }
    }

    public static final class WeaponEntry {
        private String itemId;
        private float chance;

        public WeaponEntry() {
        }

        public WeaponEntry(String itemId, float chance) {
            this.itemId = itemId;
            this.chance = chance;
        }

        public String getItemId() {
            return itemId;
        }

        public void setItemId(String itemId) {
            this.itemId = itemId;
        }

        public float getChance() {
            return chance;
        }

        public void setChance(float chance) {
            this.chance = chance;
        }
    }
}
