package eu.kotori.justTeams.gui;

import eu.kotori.justTeams.JustTeams;
import eu.kotori.justTeams.util.GuiConfigManager;
import eu.kotori.justTeams.util.ItemBuilder;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.NotNull;
import java.util.List;
import java.util.function.Consumer;

public class ConfirmGUI implements InventoryHolder {
    private final JustTeams plugin;
    private final Player viewer;
    private final Inventory inventory;
    private final Consumer<Boolean> callback;

    public ConfirmGUI(JustTeams plugin, Player viewer, String title, Consumer<Boolean> callback) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.callback = callback;
        GuiConfigManager guiManager = plugin.getGuiConfigManager();
        ConfigurationSection guiConfig = guiManager.getGUI("confirm-gui");
        int size = guiConfig.getInt("size", 27);
        this.inventory = Bukkit.createInventory(this, size, Component.text(title));
        initializeItems(guiConfig);
    }

    private void initializeItems(ConfigurationSection guiConfig) {
        inventory.clear();
        ConfigurationSection itemsSection = guiConfig.getConfigurationSection("items");
        if (itemsSection == null)
            return;
        setItemFromConfig(itemsSection, "confirm");
        setItemFromConfig(itemsSection, "cancel");
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
        int slot = itemConfig.getInt("slot");
        Material material = Material.matchMaterial(itemConfig.getString("material", "STONE"));
        String name = itemConfig.getString("name", "");
        List<String> lore = itemConfig.getStringList("lore");
        inventory.setItem(slot, new ItemBuilder(material).withName(name).withLore(lore).withAction(key).build());
    }

    public void open() {
        viewer.openInventory(inventory);
    }

    public void handleConfirm() {
        viewer.closeInventory();
        callback.accept(true);
    }

    public void handleCancel() {
        viewer.closeInventory();
        callback.accept(false);
    }

    public Inventory getInventory() {
        return inventory;
    }
}
