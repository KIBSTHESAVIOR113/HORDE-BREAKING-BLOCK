package io.github.mebahel.zombiehorde.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * REFERENCE: before overwriting an unreadable/corrupt config with defaults, keep the user's bytes
 * (NFR-03). Never throws for "file missing"; never overwrites an older backup.
 */
public final class ConfigBackup {
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private ConfigBackup() {}

    /** @return the backup path, or null if the source did not exist. */
    public static Path backupCorrupt(Path file, LocalDateTime now) throws IOException {
        if (!Files.exists(file)) {
            return null;
        }
        String base = file.getFileName() + ".corrupt-" + now.format(STAMP);
        Path target = file.resolveSibling(base + ".bak");
        int n = 1;
        while (Files.exists(target)) {
            target = file.resolveSibling(base + "-" + n++ + ".bak");
        }
        return Files.copy(file, target, StandardCopyOption.COPY_ATTRIBUTES);
    }
}
