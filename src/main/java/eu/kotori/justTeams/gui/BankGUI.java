package eu.kotori.justTeams.gui;

import eu.kotori.justTeams.JustTeams;
import eu.kotori.justTeams.team.Team;
import eu.kotori.justTeams.team.TeamPlayer;
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
import java.text.DecimalFormat;
import java.util.List;
import java.util.stream.Collectors;

public class BankGUI implements IRefreshableGUI, InventoryHolder {
    private final JustTeams plugin;
    private final Player viewer;
    private final Team team;
    private final Inventory inventory;

    public BankGUI(JustTeams plugin, Player viewer, Team team) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.team = team;
        GuiConfigManager guiManager = plugin.getGuiConfigManager();
        ConfigurationSection guiConfig = guiManager.getGUI("bank-gui");
        String title = guiConfig.getString("title", "ᴛᴇᴀᴍ ʙᴀɴᴋ");
        int size = guiConfig.getInt("size", 27);
        this.inventory = Bukkit.createInventory(this, size, plugin.getMiniMessage().deserialize(title));
        initializeItems();
    }

    private void initializeItems() {
        inventory.clear();
        GuiConfigManager guiManager = plugin.getGuiConfigManager();
        ConfigurationSection guiConfig = guiManager.getGUI("bank-gui");
        ConfigurationSection itemsSection = guiConfig.getConfigurationSection("items");
        if (itemsSection == null)
            return;
        setItemFromConfig(itemsSection, "deposit");
        setItemFromConfig(itemsSection, "balance");
        setItemFromConfig(itemsSection, "back-button");
        TeamPlayer member = team.getMember(viewer.getUniqueId());
        boolean canWithdraw = (member != null && member.canWithdraw())
                || viewer.hasPermission("justteams.bypass.bank.withdraw");
        if (canWithdraw) {
            setItemFromConfig(itemsSection, "withdraw");
        } else {
            setItemFromConfig(itemsSection, "withdraw-locked");
        }
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
        String name = replacePlaceholders(itemConfig.getString("name", ""));
        List<String> lore = itemConfig.getStringList("lore").stream()
                .map(this::replacePlaceholders)
                .collect(Collectors.toList());
        ItemBuilder builder = new ItemBuilder(material).withName(name).withLore(lore).withAction(key);
        if (key.equals("balance")) {
            builder.withGlow();
        }
        inventory.setItem(slot, builder.build());
    }

    private String replacePlaceholders(String text) {
        if (text == null)
            return "";
        DecimalFormat formatter = new DecimalFormat(plugin.getConfigManager().getCurrencyFormat());
        return text.replace("<balance>", formatter.format(team.getBalance()));
    }

    public void open() {
        viewer.openInventory(inventory);
    }

    public void refresh() {
        open();
    }

    public Team getTeam() {
        return team;
    }

    public Inventory getInventory() {
        return inventory;
    }
}
