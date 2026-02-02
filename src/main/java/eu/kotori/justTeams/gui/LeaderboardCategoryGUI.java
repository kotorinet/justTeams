package eu.kotori.justTeams.gui;

import eu.kotori.justTeams.JustTeams;
import eu.kotori.justTeams.util.GuiConfigManager;
import eu.kotori.justTeams.util.ItemBuilder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import java.util.List;

public class LeaderboardCategoryGUI implements InventoryHolder {
    private final JustTeams plugin;
    private final Player viewer;
    private final Inventory inventory;

    public LeaderboardCategoryGUI(JustTeams plugin, Player viewer) {
        this.plugin = plugin;
        this.viewer = viewer;
        GuiConfigManager guiManager = plugin.getGuiConfigManager();
        ConfigurationSection guiConfig = guiManager.getGUI("leaderboard-category-gui");
        String title = guiConfig.getString("title", "ᴛᴇᴀᴍ ʟᴇᴀᴅᴇʀʙᴏᴀʀᴅ");
        int size = guiConfig.getInt("size", 27);
        this.inventory = Bukkit.createInventory(this, size, plugin.getMiniMessage().deserialize(title));
        initializeItems();
    }

    private void initializeItems() {
        inventory.clear();
        GuiConfigManager guiManager = plugin.getGuiConfigManager();
        ConfigurationSection guiConfig = guiManager.getGUI("leaderboard-category-gui");
        ConfigurationSection itemsConfig = guiConfig.getConfigurationSection("items");
        if (itemsConfig == null)
            return;
        setItemFromConfig(itemsConfig, "top-kills");
        setItemFromConfig(itemsConfig, "top-balance");
        setItemFromConfig(itemsConfig, "top-members");
        setItemFromConfig(itemsConfig, "back-button");
        ConfigurationSection fillConfig = guiConfig.getConfigurationSection("fill-item");
        if (fillConfig != null) {
            ItemStack fillItem = new ItemBuilder(
                    Material.matchMaterial(fillConfig.getString("material", "GRAY_STAINED_GLASS_PANE")))
                    .withName(fillConfig.getString("name", " "))
                    .build();
            for (int i = 0; i < inventory.getSize(); i++) {
                if (inventory.getItem(i) == null) {
                    inventory.setItem(i, fillItem);
                }
            }
        }
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
        inventory.setItem(slot, new ItemBuilder(material).withName(name).withLore(lore).withAction(key).build());
    }

    public void open() {
        viewer.openInventory(inventory);
    }

    public Inventory getInventory() {
        return inventory;
    }
}
