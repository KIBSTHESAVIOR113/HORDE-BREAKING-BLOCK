package io.github.mebahel.zombiehorde.ai;

import java.util.EnumSet;
import java.util.Set;
import org.bukkit.Material;
import org.jetbrains.annotations.Nullable;

/**
 * Registry of blocks breakable by horde members and zombies.
 */
public final class BreakableBlocks {

    public static final Set<Material> GLASS_BLOCKS = EnumSet.of(
            Material.GLASS,
            Material.GLASS_PANE
    );

    public static final Set<Material> FENCE_BLOCKS = EnumSet.of(
            Material.OAK_FENCE,
            Material.SPRUCE_FENCE,
            Material.BIRCH_FENCE,
            Material.JUNGLE_FENCE,
            Material.ACACIA_FENCE,
            Material.DARK_OAK_FENCE,
            Material.MANGROVE_FENCE,
            Material.OAK_FENCE_GATE,
            Material.SPRUCE_FENCE_GATE,
            Material.BIRCH_FENCE_GATE,
            Material.JUNGLE_FENCE_GATE,
            Material.ACACIA_FENCE_GATE,
            Material.DARK_OAK_FENCE_GATE,
            Material.MANGROVE_FENCE_GATE
    );

    private static final Set<Material> NON_BREAKABLE = EnumSet.of(
            Material.WATER,
            Material.LAVA,
            Material.BUBBLE_COLUMN,
            Material.FIRE,
            Material.SOUL_FIRE,
            Material.LIGHT,
            Material.BEDROCK,
            Material.BARRIER,
            Material.END_PORTAL,
            Material.END_PORTAL_FRAME,
            Material.NETHER_PORTAL,
            Material.COMMAND_BLOCK,
            Material.CHAIN_COMMAND_BLOCK,
            Material.REPEATING_COMMAND_BLOCK,
            Material.STRUCTURE_BLOCK,
            Material.JIGSAW
    );

    private BreakableBlocks() {
    }

    public static boolean isBreakable(@Nullable Material material, boolean breakGlass, boolean breakFence) {
        if (material == null) {
            return false;
        }
        if (breakGlass && GLASS_BLOCKS.contains(material)) {
            return true;
        }
        return breakFence && FENCE_BLOCKS.contains(material);
    }

    /**
     * Returns true for any normal survival-breakable block (excludes air, liquids, and unbreakable blocks).
     */
    public static boolean isAnyBreakableBlock(@Nullable Material material) {
        if (material == null || !material.isBlock() || material.isAir()) {
            return false;
        }
        if (NON_BREAKABLE.contains(material)) {
            return false;
        }
        return material.getHardness() >= 0.0f;
    }
}
