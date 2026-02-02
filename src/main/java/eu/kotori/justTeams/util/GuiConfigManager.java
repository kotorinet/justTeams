package eu.kotori.justTeams.util;

import eu.kotori.justTeams.JustTeams;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.logging.Level;

public class GuiConfigManager {
    private final JustTeams plugin;
    private File guiConfigFile;
    private volatile FileConfiguration guiConfig;
    private File placeholdersConfigFile;
    private volatile FileConfiguration placeholdersConfig;
    private static final java.util.regex.Pattern PLACEHOLDER_PATTERN = java.util.regex.Pattern
            .compile("<placeholder:([^>]+)>");

    public GuiConfigManager(JustTeams plugin) {
        this.plugin = plugin;
        reload();
    }

    public synchronized void reload() {
        try {
            guiConfigFile = new File(plugin.getDataFolder(), "gui.yml");
            if (!guiConfigFile.exists()) {
                plugin.saveResource("gui.yml", false);
            }
            guiConfig = YamlConfiguration.loadConfiguration(guiConfigFile);

            int currentVersion = guiConfig.getInt("gui-version", 1);
            if (currentVersion < 12) {
                updateGuiConfig(currentVersion);
                guiConfig = YamlConfiguration.loadConfiguration(guiConfigFile);
            }

            placeholdersConfigFile = new File(plugin.getDataFolder(), "placeholders.yml");
            if (!placeholdersConfigFile.exists()) {
                plugin.saveResource("placeholders.yml", false);
            }
            placeholdersConfig = YamlConfiguration.loadConfiguration(placeholdersConfigFile);
            plugin.getLogger().info("GUI and placeholders configuration reloaded successfully!");
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to reload GUI configuration: " + e.getMessage());
        }
    }

    private void updateGuiConfig(int currentVersion) {
        plugin.getLogger().info("Updating GUI configuration from version " + currentVersion + " to 12...");

        if (currentVersion < 12) {
            if (!guiConfig.contains("blacklist-gui.items.no-blacklisted")) {
                guiConfig.set("blacklist-gui.items.no-blacklisted.slot", 22);
                guiConfig.set("blacklist-gui.items.no-blacklisted.enabled", true);
                guiConfig.set("blacklist-gui.items.no-blacklisted.material", "BOOK");
                guiConfig.set("blacklist-gui.items.no-blacklisted.name",
                        "<gray><bold>No Blacklisted Players</bold></gray>");
                guiConfig.set("blacklist-gui.items.no-blacklisted.lore", java.util.List.of(
                        "<gray>No players are currently blacklisted.</gray>",
                        "<gray>Use /team blacklist <player> to add someone.</gray>"));
                guiConfig.set("blacklist-gui.items.no-blacklisted.action", "no-blacklisted");
            }
            if (!guiConfig.contains("blacklist-gui.items.error-loading")) {
                guiConfig.set("blacklist-gui.items.error-loading.slot", 22);
                guiConfig.set("blacklist-gui.items.error-loading.enabled", true);
                guiConfig.set("blacklist-gui.items.error-loading.material", "BARRIER");
                guiConfig.set("blacklist-gui.items.error-loading.name",
                        "<red><bold>Error Loading Blacklist</bold></red>");
                guiConfig.set("blacklist-gui.items.error-loading.lore", java.util.List.of(
                        "<red>Could not load blacklisted players.</red>"));
                guiConfig.set("blacklist-gui.items.error-loading.action", "error");
            }

            guiConfig.set("gui-version", 12);
            try {
                guiConfig.save(guiConfigFile);
                plugin.getLogger().info("GUI configuration updated successfully!");
            } catch (Exception e) {
                plugin.getLogger().severe("Failed to save updated GUI configuration: " + e.getMessage());
            }
        }
    }

    public ConfigurationSection getGUI(String key) {
        return guiConfig.getConfigurationSection(key);
    }

    public String getString(String path, String def) {
        String value = guiConfig.getString(path, def);
        return replacePlaceholders(value);
    }

    public List<String> getStringList(String path) {
        if (!guiConfig.isSet(path)) {
            return Collections.emptyList();
        }
        List<String> list = guiConfig.getStringList(path);
        return list.stream()
                .map(this::replacePlaceholders)
                .collect(java.util.stream.Collectors.toList());
    }

    public Material getMaterial(String path, Material def) {
        String materialName = guiConfig.getString(path, def.name());
        try {
            return Material.valueOf(materialName.toUpperCase());
        } catch (IllegalArgumentException e) {
            plugin.getLogger().log(Level.WARNING, "Invalid material " + materialName + " found in gui.yml at path "
                    + path + ". Using default: " + def.name());
            return def;
        }
    }

    public String getPlaceholder(String path, String def) {
        if (placeholdersConfig == null) {
            plugin.getLogger().warning("Placeholders config not loaded, using default: " + def);
            return def;
        }
        return placeholdersConfig.getString(path, def);
    }

    public String getPlaceholder(String path) {
        if (placeholdersConfig == null) {
            plugin.getLogger().warning("Placeholders config not loaded, using empty string");
            return "";
        }
        return placeholdersConfig.getString(path, "");
    }

    public List<String> getPlaceholderList(String path) {
        if (placeholdersConfig == null) {
            plugin.getLogger().warning("Placeholders config not loaded, returning empty list");
            return Collections.emptyList();
        }
        if (!placeholdersConfig.isSet(path)) {
            return Collections.emptyList();
        }
        List<String> list = placeholdersConfig.getStringList(path);
        return list.stream()
                .map(this::replacePlaceholders)
                .collect(java.util.stream.Collectors.toList());
    }

    public ConfigurationSection getPlaceholderSection(String path) {
        if (placeholdersConfig == null) {
            plugin.getLogger().warning("Placeholders config not loaded, returning null for section: " + path);
            return null;
        }
        return placeholdersConfig.getConfigurationSection(path);
    }

    public String getRoleIcon(String role) {
        return getPlaceholder("roles." + role.toLowerCase() + ".icon", "");
    }

    public String getRoleName(String role) {
        return getPlaceholder("roles." + role.toLowerCase() + ".name", role);
    }

    public String getRoleColor(String role) {
        return getPlaceholder("roles." + role.toLowerCase() + ".color", "#FFFFFF");
    }

    public String getStatusIcon(boolean isOnline) {
        String status = isOnline ? "online" : "offline";
        return getPlaceholder("status." + status + ".icon", isOnline ? "●" : "●");
    }

    public String getStatusColor(boolean isOnline) {
        String status = isOnline ? "online" : "offline";
        return getPlaceholder("status." + status + ".color", isOnline ? "#00FF00" : "#FF0000");
    }

    public String getSortName(String sortType) {
        return getPlaceholder("sort." + sortType.toLowerCase() + ".name", sortType);
    }

    public String getSortIcon(String sortType) {
        return getPlaceholder("sort." + sortType.toLowerCase() + ".icon", "");
    }

    public String getSortSelectedPrefix() {
        return getPlaceholder("sort.selected_prefix", "<green>▪ <white>");
    }

    public String getSortUnselectedPrefix() {
        return getPlaceholder("sort.unselected_prefix", "<gray>▪ <white>");
    }

    public String getColor(String colorKey) {
        return getPlaceholder("colors." + colorKey, "#FFFFFF");
    }

    public String getPermissionIcon(String permissionKey) {
        return getPlaceholder("permissions." + permissionKey + "_icon", "🚫");
    }

    public String getErrorIcon(String errorKey) {
        return getPlaceholder("errors." + errorKey + "_icon", "❌");
    }

    public String getSuccessIcon(String successKey) {
        return getPlaceholder("success." + successKey + "_icon", "✅");
    }

    public String getTeamDisplayFormat() {
        return getPlaceholder("team_display.format", "<team_color><team_icon><team_tag></team_color>");
    }

    public String getTeamDisplayIcon() {
        return getPlaceholder("team_display.team_icon", "⚔ ");
    }

    public String getTeamDisplayColor() {
        return getPlaceholder("team_display.team_color", "#4C9DDE");
    }

    public String getTeamDisplayNoTeam() {
        return getPlaceholder("team_display.no_team", "<gray>No Team</gray>");
    }

    public boolean getTeamDisplayShowIcon() {
        return getPlaceholder("team_display.show_icon", "true").equals("true");
    }

    public boolean getTeamDisplayShowTag() {
        return getPlaceholder("team_display.show_tag", "true").equals("true");
    }

    public boolean getTeamDisplayShowName() {
        return getPlaceholder("team_display.show_name", "false").equals("true");
    }

    private String replacePlaceholders(String text) {
        if (text == null || !text.contains("<placeholder:")) {
            return text;
        }
        java.util.regex.Matcher matcher = PLACEHOLDER_PATTERN.matcher(text);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String key = matcher.group(1);
            String replacement = getPlaceholder(key, matcher.group(0));
            if (plugin.getConfigManager().isDebugEnabled()) {
                plugin.getLogger().info("Replacing placeholder: " + matcher.group(0) + " -> " + replacement);
            }
            matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    public void testPlaceholders() {
        try {
            plugin.getLogger().info("Testing placeholder system...");
            String roleIcon = getRoleIcon("owner");
            plugin.getLogger().info("Owner role icon: " + roleIcon);
            String sortName = getSortName("join_date");
            plugin.getLogger().info("Join date sort name: " + sortName);
            String statusIcon = getStatusIcon(true);
            plugin.getLogger().info("Online status icon: " + statusIcon);
            String testText = "Test <placeholder:roles.owner.icon> and <placeholder:sort.join_date.name>";
            String replaced = replacePlaceholders(testText);
            plugin.getLogger().info("Placeholder replacement test: " + testText + " -> " + replaced);
            plugin.getLogger().info("Placeholder system test completed!");
        } catch (Exception e) {
            plugin.getLogger().severe("Error during placeholder system test: " + e.getMessage());
            plugin.getLogger().severe("Failed to reload GUI config: " + e.getMessage());
        }
    }
}
