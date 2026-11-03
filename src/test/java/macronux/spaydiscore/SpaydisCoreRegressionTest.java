package macronux.spaydiscore;

import java.nio.file.Files;
import java.nio.file.Path;

/** Dependency-free contract checks for the public SpaydisCore layout. */
public final class SpaydisCoreRegressionTest {
    private SpaydisCoreRegressionTest() {
    }

    public static void main(String[] args) throws Exception {
        Path resources = Path.of("src/main/resources");
        Path datapack = resources.resolve("spaydiscore_datapack");
        Path source = Path.of("src/main/java/macronux/spaydiscore");

        require(Files.exists(source.resolve("SpaydisCorePlugin.java")),
                "SpaydisCore plugin entrypoint must be present");
        require(Files.exists(source.resolve("SpaydisCoreBootstrap.java")),
                "SpaydisCore bootstrap must be present");
        require(!Files.exists(resources.resolve("plugin.yml")),
                "plugin.yml must not coexist with paper-plugin.yml");
        require(Files.exists(datapack.resolve("data/spaydiscore/dimension/limbo.json")),
                "SpaydisCore datapack must keep the Limbo dimension");
        require(Files.exists(datapack.resolve("data/spaydiscore/dimension_type/limbo.json")),
                "SpaydisCore datapack must keep the Limbo dimension type");
        try (var paths = Files.walk(datapack)) {
            require(paths.noneMatch(path -> {
                        String normalized = datapack.relativize(path).toString().replace('\\', '/');
                        return normalized.contains("/advancement/") || normalized.contains("/recipe/");
                    }),
                    "the bundled datapack must not contain advancement or recipe resources");
        }

        String paperYaml = Files.readString(resources.resolve("paper-plugin.yml"));
        require(paperYaml.contains("name: SpaydisCore"),
                "plugin metadata must identify SpaydisCore");
        require(paperYaml.contains("main: macronux.spaydiscore.SpaydisCorePlugin")
                        && paperYaml.contains("bootstrapper: macronux.spaydiscore.SpaydisCoreBootstrap"),
                "Paper metadata must point at the SpaydisCore classes");

        java.util.Set<String> english = languageKeys(resources.resolve("lang/en.yml"));
        for (String code : new String[]{"ru", "de"}) {
            java.util.Set<String> keys = languageKeys(resources.resolve("lang/" + code + ".yml"));
            java.util.Set<String> missing = new java.util.TreeSet<>(english);
            missing.removeAll(keys);
            require(missing.isEmpty(), code + ".yml is missing keys present in en.yml: " + missing);
        }
        require(english.contains("undead-food-denied") && english.contains("menu-title-main")
                        && english.contains("limbo-enter-success"),
                "new command and menu messages must exist");

        System.out.println("SpaydisCore contract checks passed");
        macronux.spaydiscore.features.limbo.LimboVisualTest.run();
        macronux.spaydiscore.features.limbo.LimboEffectClockTest.run();
        macronux.spaydiscore.features.limbo.LimboPerformanceTest.run();
        macronux.spaydiscore.features.limbo.LimboAdmissionTest.run();
        macronux.spaydiscore.features.limbo.LimboVoiceChatGroupGuardTest.run();
        macronux.spaydiscore.features.allergy.AllergyPersistenceTest.run();
        macronux.spaydiscore.sound.SoundConfigTest.run();
    }

    private static java.util.Set<String> languageKeys(Path file) throws Exception {
        org.bukkit.configuration.file.YamlConfiguration yaml =
                org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(file.toFile());
        return yaml.getKeys(true);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
