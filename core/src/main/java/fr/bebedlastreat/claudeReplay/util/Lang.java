package fr.bebedlastreat.claudeReplay.util;

import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Messages of the plugin, read from {@code plugins/ClaudeReplay/lang/<language>.yml}.
 * <p>
 * A key missing from that file is taken from the bundled file of the same language, then from the bundled English
 * file. Placeholders are written {@code {name}} and given as name / value pairs.
 */
public final class Lang {

    public static final String DEFAULT_LANGUAGE = "en";
    private static final List<String> BUNDLED = List.of("en", "fr");

    /** Lookup order: the server's file, the bundled file of its language, the bundled English file. */
    private static List<YamlConfiguration> sources = List.of();

    private Lang() {
    }

    public static void load(JavaPlugin plugin, String language) {
        String lang = language.toLowerCase(Locale.ROOT).trim();
        for (String bundled : BUNDLED) {
            if (!new File(plugin.getDataFolder(), "lang/" + bundled + ".yml").exists()) {
                plugin.saveResource("lang/" + bundled + ".yml", false);
            }
        }
        File file = new File(plugin.getDataFolder(), "lang/" + lang + ".yml");
        if (!file.exists()) {
            plugin.getLogger().warning("No language file lang/" + lang + ".yml, using " + DEFAULT_LANGUAGE);
            lang = DEFAULT_LANGUAGE;
            file = new File(plugin.getDataFolder(), "lang/" + lang + ".yml");
        }

        List<YamlConfiguration> loaded = new ArrayList<>(3);
        try (InputStream in = new FileInputStream(file)) {
            loaded.add(read(in));
        } catch (IOException e) {
            plugin.getLogger().warning("Cannot read " + file + ": " + e);
        }
        if (BUNDLED.contains(lang)) {
            loaded.add(bundled(plugin, lang));
        }
        if (!lang.equals(DEFAULT_LANGUAGE)) {
            loaded.add(bundled(plugin, DEFAULT_LANGUAGE));
        }
        sources = List.copyOf(loaded);
    }

    private static YamlConfiguration bundled(JavaPlugin plugin, String language) {
        try (InputStream in = plugin.getResource("lang/" + language + ".yml")) {
            return in == null ? new YamlConfiguration() : read(in);
        } catch (IOException e) {
            return new YamlConfiguration();
        }
    }

    /** Always UTF-8: old servers read YAML files with the platform charset. */
    private static YamlConfiguration read(InputStream in) throws IOException {
        try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return YamlConfiguration.loadConfiguration(reader);
        }
    }

    /** The message of a key, or the key itself if no file defines it. */
    public static String tr(String key, Object... placeholders) {
        for (YamlConfiguration source : sources) {
            if (source.isString(key)) {
                return replace(source.getString(key), placeholders);
            }
        }
        return key;
    }

    /** A message made of several lines (item lore). A single string gives one line. */
    public static List<String> lines(String key, Object... placeholders) {
        for (YamlConfiguration source : sources) {
            if (source.isList(key)) {
                List<String> lines = new ArrayList<>();
                for (String line : source.getStringList(key)) {
                    lines.add(replace(line, placeholders));
                }
                return lines;
            }
            if (source.isString(key)) {
                return List.of(replace(source.getString(key), placeholders));
            }
        }
        return List.of(key);
    }

    private static String replace(String message, Object[] placeholders) {
        for (int i = 0; i + 1 < placeholders.length; i += 2) {
            message = message.replace("{" + placeholders[i] + "}", String.valueOf(placeholders[i + 1]));
        }
        return message;
    }
}
