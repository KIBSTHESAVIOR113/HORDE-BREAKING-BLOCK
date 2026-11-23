package io.github.mebahel.zombiehorde.ai;

import java.util.EnumSet;
import java.util.Set;
import org.bukkit.Material;
import org.jetbrains.annotations.Nullable;

/**
 * Explicit registry of blocks breakable by horde members (FR-09).
 * Isolated here so any adjustment to [I] inferred block lists requires editing only this file.
 */
public final class BreakableBlocks {

    // Plain glass + plain glass pane only (stained and tinted glass excluded per PRD FR-09)
    public static final Set<Material> GLASS_BLOCKS = EnumSet.of(
            Material.GLASS,
            Material.GLASS_PANE
    );

    // [I] 14 wooden fences and fence gates (oak through mangrove; excludes nether_brick, bamboo, cherry, crimson, warped)
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
}
