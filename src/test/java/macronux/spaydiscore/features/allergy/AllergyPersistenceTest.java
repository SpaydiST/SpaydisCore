package macronux.spaydiscore.features.allergy;

import java.nio.file.Files;
import java.io.IOException;

public final class AllergyPersistenceTest {
    public static void run() throws Exception {
        long start = 1_000_000_000L;
        require(NetheriteAllergyModule.reliefActive(start, start + 119_999_999_999L), "relief lasts two minutes");
        require(!NetheriteAllergyModule.reliefActive(start, start + 120_000_000_000L), "relief expires at two minutes");
        require(!NetheriteAllergyModule.reliefActive(start, start - 1L), "future relief is rejected");
        var changed = org.bukkit.event.entity.EntityPotionEffectEvent.Action.CHANGED;
        require(!NetheriteAllergyModule.nauseaWasChanged(changed, false),
                "a weaker unaccepted effect leaves allergy nausea ownership intact");
        require(NetheriteAllergyModule.nauseaWasChanged(changed, true),
                "a foreign replacement relinquishes ownership");
        require(NetheriteAllergyModule.nauseaWasChanged(
                org.bukkit.event.entity.EntityPotionEffectEvent.Action.REMOVED, false),
                "removing an effect relinquishes ownership");
        var directory = Files.createTempDirectory("spaydiscore-save-test-");
        var target = directory.resolve("allergies.yml");
        var temporary = directory.resolve("allergies.yml.tmp");
        try {
            NetheriteAllergyModule.writeAtomically(target, "allergic: [player-one]\n");
            String update = "allergic: [player-two]\n# Проверка Unicode\nwear-progress-ticks: {player-two: 4800}\n";
            NetheriteAllergyModule.writeAtomically(target, update);
            require(Files.readString(target).equals(update), "replacement preserves complete UTF-8 contents");
            require(!Files.exists(temporary), "successful save leaves no pending file");
            Files.createDirectory(temporary);
            boolean failed = false;
            try {
                NetheriteAllergyModule.writeAtomically(target, "corrupt replacement");
            } catch (IOException expected) {
                failed = true;
            }
            require(failed, "write failure is reported");
            require(Files.readString(target).equals(update), "failed write preserves the last valid save");
        } finally {
            Files.deleteIfExists(temporary);
            Files.deleteIfExists(target);
            Files.deleteIfExists(directory);
        }
        System.out.println("Allergy relief and atomic persistence checks passed");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
