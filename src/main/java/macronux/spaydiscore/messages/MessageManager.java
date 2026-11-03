package macronux.spaydiscore.messages;

import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Loads localized message bundles and formats placeholders for command and feature output. */
public final class MessageManager {

    private static final String DEFAULT_FALLBACK = "en";
    private static final List<String> DEFAULT_LANGS = List.of("en", "ru", "de");
    private static final Pattern PLACEHOLDER_PATTERN = Pattern.compile("\\{([^}]+)}");

    private final JavaPlugin plugin;
    private final Map<String, FileConfiguration> configs = new HashMap<>();
    private String fallback = DEFAULT_FALLBACK;
    private List<String> languages = DEFAULT_LANGS;

    public MessageManager(JavaPlugin plugin) {
        this.plugin = plugin;
        loadAll();
    }

    public void reload() {
        loadAll();
    }

    private void loadAll() {
        configs.clear();

        String configuredFallback = plugin.getConfig().getString("language.fallback", DEFAULT_FALLBACK);
        String normalizedFallback = configuredFallback == null ? null : configuredFallback.trim();
        fallback = isSafeLanguageCode(normalizedFallback)
                ? normalizedFallback.toLowerCase(Locale.ROOT)
                : DEFAULT_FALLBACK;

        Set<String> configuredLanguages = new LinkedHashSet<>();
        for (String configuredCode : plugin.getConfig().getStringList("language.supported")) {
            String code = configuredCode == null ? null : configuredCode.trim();
            if (isSafeLanguageCode(code)) {
                configuredLanguages.add(code.toLowerCase(Locale.ROOT));
            }
        }
        if (configuredLanguages.isEmpty()) {
            configuredLanguages.addAll(DEFAULT_LANGS);
        }
        configuredLanguages.add(fallback);
        configuredLanguages.add(DEFAULT_FALLBACK);
        languages = List.copyOf(configuredLanguages);

        for (String code : languages) {
            configs.put(code, load(code));
        }
    }

    private FileConfiguration load(String code) {
        String resourcePath = "lang/" + code + ".yml";
        File file = new File(plugin.getDataFolder(), resourcePath);
        if (!file.exists()) {
            boolean resourceExists;
            try (InputStream resource = plugin.getResource(resourcePath)) {
                if (resource == null) {
                    return new YamlConfiguration();
                }
                resourceExists = true;
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to probe language resource for " + code + ": " + e.getMessage());
                return new YamlConfiguration();
            }
            if (resourceExists) {
                plugin.saveResource(resourcePath, false);
            }
        }
        YamlConfiguration cfg = YamlConfiguration.loadConfiguration(file);

        try (InputStream defaults = plugin.getResource(resourcePath)) {
            if (defaults != null) {
                cfg.setDefaults(YamlConfiguration.loadConfiguration(
                        new InputStreamReader(defaults, StandardCharsets.UTF_8)));
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load language defaults for " + code + ": " + e.getMessage());
        }
        return cfg;
    }

    private boolean isSafeLanguageCode(String code) {
        return code != null && code.matches("[a-zA-Z]{2,8}");
    }

    public String get(CommandSender sender, String key) {
        Locale locale = sender instanceof Player player ? player.locale() : null;
        return get(locale, key);
    }

    public String get(Locale locale, String key) {
        String code = locale == null ? fallback : locale.getLanguage().toLowerCase(Locale.ROOT);
        FileConfiguration cfg = configs.get(code);
        String raw = getLiteralString(cfg, key);
        if (raw == null) {
            FileConfiguration fallbackConfig = configs.get(fallback);
            raw = getLiteralString(fallbackConfig, key);
        }
        if (raw == null && !DEFAULT_FALLBACK.equals(fallback)) {
            FileConfiguration defaultConfig = configs.get(DEFAULT_FALLBACK);
            raw = getLiteralString(defaultConfig, key);
        }
        return raw != null ? raw : key;
    }

    private String getLiteralString(FileConfiguration cfg, String key) {
        if (cfg == null || key == null) return null;

        Object direct = cfg.getValues(false).get(key);
        if (direct == null && cfg.getDefaults() != null) {
            direct = cfg.getDefaults().getValues(false).get(key);
        }
        if (direct instanceof String value) return value;

        return cfg.getString(key);
    }

    public String format(CommandSender sender, String key, Object... placeholderPairs) {
        return format(sender instanceof Player player ? player.locale() : null, key, placeholderPairs);
    }

    public String format(Locale locale, String key, Object... placeholderPairs) {
        String msg = get(locale, key);
        if (placeholderPairs == null || placeholderPairs.length == 0) {
            return msg;
        }

        // Convert pairs to map for regex replacement
        Map<String, String> replacements = new HashMap<>();
        for (int i = 0; i + 1 < placeholderPairs.length; i += 2) {
            String k = String.valueOf(placeholderPairs[i]);
            String v = String.valueOf(placeholderPairs[i + 1]);
            // Remove braces from key for regex lookup
            if (k.startsWith("{") && k.endsWith("}")) {
                k = k.substring(1, k.length() - 1);
            }
            replacements.put(k, v);
        }

        // Replace {key} patterns
        Matcher matcher = PLACEHOLDER_PATTERN.matcher(msg);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String placeholder = matcher.group(1);
            String replacement = replacements.getOrDefault(placeholder, "{" + placeholder + "}");
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
