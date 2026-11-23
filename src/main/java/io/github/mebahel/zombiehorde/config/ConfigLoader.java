package io.github.mebahel.zombiehorde.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.util.logging.Logger;
import org.jetbrains.annotations.NotNull;

public final class ConfigLoader {

    public static final String MAIN_CONFIG_FILE = "mebahel-zombie-horde_config.json";
    public static final String COMPOSITION_CONFIG_FILE = "mebahel-zombie-hordemob_type_config.json";

    private final File dataFolder;
    private final Logger logger;
    private final Gson gson;

    public ConfigLoader(@NotNull File dataFolder, @NotNull Logger logger) {
        this.dataFolder = dataFolder;
        this.logger = logger;
        this.gson = new GsonBuilder()
                .setPrettyPrinting()
                .disableHtmlEscaping()
                .create();
    }

    @NotNull
    public MainConfig loadMainConfig() {
        ensureDataFolder();
        File file = new File(dataFolder, MAIN_CONFIG_FILE);
        if (!file.exists()) {
            MainConfig defaults = MainConfig.createDefault();
            writeMainConfig(file, defaults);
            return defaults;
        }

        // C4-01: Read entire file first so the OS file handle is closed before any backup or atomic rename (Windows safe)
        String raw;
        try {
            raw = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            Path backup = null;
            try {
                backup = ConfigBackup.backupCorrupt(file.toPath(), LocalDateTime.now());
            } catch (IOException backupEx) {
                logger.warning("Could not back up corrupt config " + file.getName() + ": " + backupEx.getMessage());
            }
            logger.severe("Failed to read " + MAIN_CONFIG_FILE + " (" + ex.getMessage() + ")."
                    + (backup != null ? " Backed up to " + backup.getFileName() + "." : "")
                    + " Using defaults.");
            MainConfig defaults = MainConfig.createDefault();
            writeMainConfig(file, defaults);
            return defaults;
        }

        try {
            JsonElement root = JsonParser.parseString(raw);
            if (root == null || !root.isJsonObject()) {
                Path backup = null;
                try {
                    backup = ConfigBackup.backupCorrupt(file.toPath(), LocalDateTime.now());
                } catch (IOException backupEx) {
                    logger.warning("Could not back up corrupt config " + file.getName() + ": " + backupEx.getMessage());
                }
                logger.severe("Config file " + MAIN_CONFIG_FILE + " is empty or not a JSON object."
                        + (backup != null ? " Backed up to " + backup.getFileName() + "." : "")
                        + " Reverting to defaults.");
                MainConfig defaults = MainConfig.createDefault();
                writeMainConfig(file, defaults);
                return defaults;
            }
            JsonObject obj = root.getAsJsonObject();
            MainConfig config = MainConfig.fromJson(obj, logger);
            if (config.validateAndFix(logger)) {
                writeMainConfig(file, config);
            }
            return config;
        } catch (JsonParseException | IllegalStateException ex) {
            Path backup = null;
            try {
                backup = ConfigBackup.backupCorrupt(file.toPath(), LocalDateTime.now());
            } catch (IOException backupEx) {
                logger.warning("Could not back up corrupt config " + file.getName() + ": " + backupEx.getMessage());
            }
            logger.severe("Failed to parse " + MAIN_CONFIG_FILE + " (" + ex.getMessage() + ")."
                    + (backup != null ? " Backed up to " + backup.getFileName() + "." : "")
                    + " Using defaults.");
            MainConfig defaults = MainConfig.createDefault();
            writeMainConfig(file, defaults);
            return defaults;
        }
    }

    @NotNull
    public CompositionConfig loadCompositionConfig() {
        ensureDataFolder();
        File file = new File(dataFolder, COMPOSITION_CONFIG_FILE);
        if (!file.exists()) {
            CompositionConfig defaults = CompositionConfig.createDefault();
            writeJson(file, defaults);
            return defaults;
        }

        // C4-01: Read entire file first so the OS file handle is closed before any backup or atomic rename (Windows safe)
        String raw;
        try {
            raw = Files.readString(file.toPath(), StandardCharsets.UTF_8);
        } catch (IOException ex) {
            Path backup = null;
            try {
                backup = ConfigBackup.backupCorrupt(file.toPath(), LocalDateTime.now());
            } catch (IOException backupEx) {
                logger.warning("Could not back up corrupt config " + file.getName() + ": " + backupEx.getMessage());
            }
            logger.severe("Failed to read " + COMPOSITION_CONFIG_FILE + " (" + ex.getMessage() + ")."
                    + (backup != null ? " Backed up to " + backup.getFileName() + "." : "")
                    + " Using defaults.");
            CompositionConfig defaults = CompositionConfig.createDefault();
            writeJson(file, defaults);
            return defaults;
        }

        try {
            CompositionConfig config = gson.fromJson(raw, CompositionConfig.class);
            if (config == null || config.getHordeCompositions() == null || config.getHordeCompositions().isEmpty()) {
                Path backup = null;
                try {
                    backup = ConfigBackup.backupCorrupt(file.toPath(), LocalDateTime.now());
                } catch (IOException backupEx) {
                    logger.warning("Could not back up corrupt config " + file.getName() + ": " + backupEx.getMessage());
                }
                logger.severe("Config file " + COMPOSITION_CONFIG_FILE + " is empty or missing hordeCompositions."
                        + (backup != null ? " Backed up to " + backup.getFileName() + "." : "")
                        + " Using defaults.");
                CompositionConfig defaults = CompositionConfig.createDefault();
                writeJson(file, defaults);
                return defaults;
            }
            boolean modified = config.validateAndFix(logger);
            if (modified) {
                writeJson(file, config);
            }
            return config;
        } catch (JsonParseException | IllegalStateException ex) {
            Path backup = null;
            try {
                backup = ConfigBackup.backupCorrupt(file.toPath(), LocalDateTime.now());
            } catch (IOException backupEx) {
                logger.warning("Could not back up corrupt config " + file.getName() + ": " + backupEx.getMessage());
            }
            logger.severe("Failed to parse " + COMPOSITION_CONFIG_FILE + " (" + ex.getMessage() + ")."
                    + (backup != null ? " Backed up to " + backup.getFileName() + "." : "")
                    + " Using defaults.");
            CompositionConfig defaults = CompositionConfig.createDefault();
            writeJson(file, defaults);
            return defaults;
        }
    }

    private void ensureDataFolder() {
        if (!dataFolder.exists() && !dataFolder.mkdirs()) {
            logger.warning("Could not create plugin data folder: " + dataFolder.getAbsolutePath());
        }
    }

    private void writeMainConfig(@NotNull File file, @NotNull MainConfig config) {
        writeJson(file, config.toJson());
    }

    /**
     * N-03 + C4-06: Atomic write via temporary sibling .tmp file, preferring ATOMIC_MOVE
     * with safe fallback to REPLACE_EXISTING if the filesystem does not support atomic move.
     */
    private void writeJson(@NotNull File file, @NotNull Object payload) {
        Path target = file.toPath();
        Path tmp = target.resolveSibling(file.getName() + ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                gson.toJson(payload, writer);
            }
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException ex) {
            logger.severe("Failed to write config file " + file.getName() + ": " + ex.getMessage());
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
                // Best-effort cleanup of temp file
            }
        }
    }
}
