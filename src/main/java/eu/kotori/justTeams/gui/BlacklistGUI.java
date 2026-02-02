package eu.kotori.justTeams.gui;

import eu.kotori.justTeams.JustTeams;
import eu.kotori.justTeams.team.BlacklistedPlayer;
import eu.kotori.justTeams.team.Team;
import eu.kotori.justTeams.util.GuiConfigManager;
import eu.kotori.justTeams.util.ItemBuilder;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

public class BlacklistGUI implements InventoryHolder, IRefreshableGUI {
    private final JustTeams plugin;
    private final Team team;
    private final Player viewer;
    private Inventory inventory;

    public BlacklistGUI(JustTeams plugin, Team team, Player viewer) {
        this.plugin = plugin;
        this.team = team;
        this.viewer = viewer;
        GuiConfigManager guiManager = plugin.getGuiConfigManager();
        ConfigurationSection guiConfig = guiManager.getGUI("blacklist-gui");
        String title = guiConfig != null ? guiConfig.getString("title", "ᴛᴇᴀᴍ ʙʟᴀᴄᴋʟɪsᴛ") : "ᴛᴇᴀᴍ ʙʟᴀᴄᴋʟɪsᴛ";
        int size = guiConfig != null ? guiConfig.getInt("size", 54) : 54;
        this.inventory = Bukkit.createInventory(this, size, Component.text(title));
        initializeItems();
    }

    public void initializeItems() {
        inventory.clear();
        ConfigurationSection itemsSection = plugin.getGuiConfigManager().getGUI("blacklist-gui")
                .getConfigurationSection("items");

        ConfigurationSection fillConfig = plugin.getGuiConfigManager().getGUI("blacklist-gui")
                .getConfigurationSection("fill-item");
        if (fillConfig != null) {
            ItemStack fillItem = new ItemBuilder(
                    Material.matchMaterial(fillConfig.getString("material", "GRAY_STAINED_GLASS_PANE")))
                    .withName(fillConfig.getString("name", " "))
                    .build();
            for (int i = 0; i < inventory.getSize(); i++) {
                inventory.setItem(i, fillItem);
            }
        }

        if (itemsSection != null) {
            setItemFromConfig(itemsSection, "blacklist-header");
            setItemFromConfig(itemsSection, "back-button");
        }

        loadBlacklistedPlayers();
    }

    private void setItemFromConfig(ConfigurationSection itemsSection, String key) {
        ConfigurationSection itemConfig = itemsSection.getConfigurationSection(key);
        if (itemConfig == null)
            return;
        if (!itemConfig.getBoolean("enabled", true))
            return;

        int slot = itemConfig.getInt("slot", -1);
        if (slot == -1)
            return;

        Material material = Material.matchMaterial(itemConfig.getString("material", "STONE"));
        String name = itemConfig.getString("name", "");
        List<String> lore = itemConfig.getStringList("lore");
        String action = itemConfig.getString("action", key);

        inventory.setItem(slot, new ItemBuilder(material)
                .withName(name)
                .withLore(lore)
                .withAction(action)
                .build());
    }

    private void loadBlacklistedPlayers() {
        plugin.getTaskRunner().runAsync(() -> {
            try {
                List<BlacklistedPlayer> blacklist = plugin.getStorageManager().getStorage()
                        .getTeamBlacklist(team.getId());
                ConfigurationSection itemsSection = plugin.getGuiConfigManager().getGUI("blacklist-gui")
                        .getConfigurationSection("items");

                if (blacklist.isEmpty()) {
                    plugin.getTaskRunner().runOnEntity(viewer, () -> {
                        if (itemsSection != null && itemsSection.contains("no-blacklisted")) {
                            setItemFromConfig(itemsSection, "no-blacklisted");
                        }
                    });
                    return;
                }

                int slot = 9;
                for (BlacklistedPlayer blacklistedPlayer : blacklist) {
                    if (slot >= 45)
                        break;
                    final int currentSlot = slot;
                    plugin.getTaskRunner().runOnEntity(viewer, () -> {
                        inventory.setItem(currentSlot, createBlacklistedPlayerItem(blacklistedPlayer));
                    });
                    slot++;
                    if ((slot - 9) % 9 == 0) {
                        slot += 0;
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().severe("Error loading team blacklist: " + e.getMessage());
                plugin.getTaskRunner().runOnEntity(viewer, () -> {
                    ConfigurationSection itemsSection = plugin.getGuiConfigManager().getGUI("blacklist-gui")
                            .getConfigurationSection("items");
                    if (itemsSection != null && itemsSection.contains("error-loading")) {
                        setItemFromConfig(itemsSection, "error-loading");
                    }
                });
            }
        });
    }

    private ItemStack createBlacklistedPlayerItem(BlacklistedPlayer blacklistedPlayer) {
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(blacklistedPlayer.getPlayerUuid());
        OfflinePlayer blacklistedBy = Bukkit.getOfflinePlayer(blacklistedPlayer.getBlacklistedByUuid());
        String timeAgo = formatTimeAgo(blacklistedPlayer.getBlacklistedAt());
        String actionKey = "remove-blacklist:" + blacklistedPlayer.getPlayerUuid().toString();

        ConfigurationSection itemsSection = plugin.getGuiConfigManager().getGUI("blacklist-gui")
                .getConfigurationSection("items");
        ConfigurationSection itemConfig = itemsSection != null ? itemsSection.getConfigurationSection("player-head")
                : null;

        Material material = Material.PLAYER_HEAD;
        String name = "<red><bold>" + blacklistedPlayer.getPlayerName() + "</bold></red>";
        List<String> lore = List.of();

        if (itemConfig != null) {
            material = Material.matchMaterial(itemConfig.getString("material", "PLAYER_HEAD"));
            name = itemConfig.getString("name", name)
                    .replace("<player_name>", blacklistedPlayer.getPlayerName());
            lore = itemConfig.getStringList("lore").stream()
                    .map(line -> line
                            .replace("<blacklister>",
                                    blacklistedBy.getName() != null ? blacklistedBy.getName() : "Unknown")
                            .replace("<date>", timeAgo)
                            .replace("<reason>", blacklistedPlayer.getReason()))
                    .toList();
        }

        plugin.getLogger().info(
                "Creating blacklist item for " + blacklistedPlayer.getPlayerName() + " with action: " + actionKey);

        ItemStack itemStack = new ItemStack(material);
        if (material == Material.PLAYER_HEAD && itemStack.getItemMeta() instanceof SkullMeta skullMeta) {
            skullMeta.setOwningPlayer(offlinePlayer);
            itemStack.setItemMeta(skullMeta);
        }

        itemStack = new ItemBuilder(itemStack)
                .withName(name)
                .withLore(lore)
                .withAction(actionKey)
                .build();

        if (itemStack.getItemMeta() != null) {
            String actualAction = itemStack.getItemMeta().getPersistentDataContainer().get(JustTeams.getActionKey(),
                    PersistentDataType.STRING);
            if (!actionKey.equals(actualAction)) {
                ItemMeta meta = itemStack.getItemMeta();
                if (meta != null) {
                    meta.getPersistentDataContainer().set(JustTeams.getActionKey(), PersistentDataType.STRING,
                            actionKey);
                    itemStack.setItemMeta(meta);
                }
            }
        }
        return itemStack;
    }

    private String formatTimeAgo(Instant blacklistedAt) {
        Duration duration = Duration.between(blacklistedAt, Instant.now());
        if (duration.toDays() > 0) {
            return duration.toDays() + " day" + (duration.toDays() == 1 ? "" : "s") + " ago";
        } else if (duration.toHours() > 0) {
            return duration.toHours() + " hour" + (duration.toHours() == 1 ? "" : "s") + " ago";
        } else if (duration.toMinutes() > 0) {
            return duration.toMinutes() + " minute" + (duration.toMinutes() == 1 ? "" : "s") + " ago";
        } else {
            return "Just now";
        }
    }

    public void open() {
        viewer.openInventory(inventory);
    }

    public Inventory getInventory() {
        return inventory;
    }

    public Team getTeam() {
        return team;
    }

    public void refresh() {
        if (plugin.getConfigManager().isDebugEnabled()) {
            plugin.getLogger().info("Refreshing blacklist GUI for team " + team.getName());
        }

        if (viewer != null && viewer.isOnline()) {
            plugin.getGuiManager().getUpdateThrottle().scheduleUpdate(viewer.getUniqueId(), () -> {
                plugin.getTaskRunner().runOnEntity(viewer, () -> {
                    try {
                        initializeItems();
                        if (plugin.getConfigManager().isDebugEnabled()) {
                            plugin.getLogger().info("Blacklist GUI refresh completed for team " + team.getName());
                        }
                    } catch (Exception e) {
                        plugin.getLogger().severe(
                                "Error refreshing blacklist GUI for team " + team.getName() + ": " + e.getMessage());
                    }
                });
            });
        }
    }
}
