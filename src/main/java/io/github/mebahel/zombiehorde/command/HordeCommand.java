package io.github.mebahel.zombiehorde.command;

import io.github.mebahel.zombiehorde.ZombieHordePlugin;
import io.github.mebahel.zombiehorde.horde.HordeManager;
import io.github.mebahel.zombiehorde.horde.HordeSpawner.SpawnResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Phase 2 admin command: /mebahelzombiehorde <spawnhorde|reloadconfig|nextspawn> (PRD §6).
 */
public final class HordeCommand implements CommandExecutor, TabCompleter {

    public static final String PERMISSION = "mebahelzombiehorde.admin";
    private static final List<String> SUBCOMMANDS = List.of("spawnhorde", "reloadconfig", "nextspawn");

    private final ZombieHordePlugin plugin;

    public HordeCommand(@NotNull ZombieHordePlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String label,
            @NotNull String[] args
    ) {
        if (!sender.hasPermission(PERMISSION)) {
            sender.sendMessage(Component.text("You do not have permission to use this command.", NamedTextColor.RED));
            return true;
        }
        if (args.length == 0) {
            sender.sendMessage(Component.text("Usage: /" + label + " <spawnhorde|reloadconfig|nextspawn>", NamedTextColor.YELLOW));
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "spawnhorde" -> handleSpawnHorde(sender);
            case "reloadconfig" -> handleReloadConfig(sender);
            case "nextspawn" -> handleNextSpawn(sender);
            default -> sender.sendMessage(Component.text(
                    "Unknown subcommand. Usage: /" + label + " <spawnhorde|reloadconfig|nextspawn>",
                    NamedTextColor.RED));
        }
        return true;
    }

    private void handleSpawnHorde(@NotNull CommandSender sender) {
        World targetWorld;
        Player anchor = null;
        if (sender instanceof Player player) {
            targetWorld = player.getWorld();
            anchor = player;
        } else {
            targetWorld = plugin.getHordeManager().findOverworld();
        }
        if (targetWorld == null) {
            sender.sendMessage(Component.text("No eligible world found to spawn a horde.", NamedTextColor.RED));
            return;
        }

        SpawnResult result = plugin.getHordeSpawner().spawnHorde(targetWorld, anchor);
        switch (result) {
            case SPAWNED -> sender.sendMessage(Component.text(
                    "Manually triggered a horde spawn in " + targetWorld.getName() + ".", NamedTextColor.GREEN));
            case NO_PLAYER -> sender.sendMessage(Component.text(
                    "Could not spawn horde: no living player in " + targetWorld.getName() + ".", NamedTextColor.RED));
            case NO_COMPOSITION -> sender.sendMessage(Component.text(
                    "Could not spawn horde: no matching composition for dimension '" + targetWorld.getKey().asString() + "'.",
                    NamedTextColor.RED));
            case NO_LOADED_COLUMN -> sender.sendMessage(Component.text(
                    "Could not spawn horde: no loaded non-void chunk found 30..59 blocks away.", NamedTextColor.RED));
            case ZERO_MEMBERS -> sender.sendMessage(Component.text(
                    "Could not spawn horde: all selected mob types were invalid or spawn was cancelled.", NamedTextColor.RED));
        }
    }

    private void handleReloadConfig(@NotNull CommandSender sender) {
        plugin.reloadPluginConfigs();
        sender.sendMessage(Component.text("MebahelZombieHorde configuration reloaded.", NamedTextColor.GREEN));
    }

    private void handleNextSpawn(@NotNull CommandSender sender) {
        HordeManager manager = plugin.getHordeManager();
        if (!plugin.getMainConfig().isHordeSpawning() || !manager.isRunning()) {
            sender.sendMessage(Component.text("Horde spawning is currently disabled.", NamedTextColor.YELLOW));
            return;
        }
        long remainingTicks = manager.getRemainingTicksToNextSpawn();
        long totalSeconds = remainingTicks / 20L;
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;

        World overworld = manager.findOverworld();
        int diff = overworld != null ? plugin.getDifficulty().getEffectiveLevel(overworld) : 1;
        sender.sendMessage(Component.text(
                String.format("Next horde spawn check in %02dm %02ds (%d ticks) | Difficulty: %d",
                        minutes, seconds, remainingTicks, diff),
                NamedTextColor.AQUA));
    }

    @Override
    public @Nullable List<String> onTabComplete(
            @NotNull CommandSender sender,
            @NotNull Command command,
            @NotNull String alias,
            @NotNull String[] args
    ) {
        if (!sender.hasPermission(PERMISSION)) {
            return List.of();
        }
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            List<String> matches = new ArrayList<>();
            for (String sub : SUBCOMMANDS) {
                if (sub.startsWith(prefix)) {
                    matches.add(sub);
                }
            }
            return matches;
        }
        return List.of();
    }
}
