package eu.kotori.justTeams.gui.sub;

import eu.kotori.justTeams.JustTeams;
import eu.kotori.justTeams.gui.IRefreshableGUI;
import eu.kotori.justTeams.team.Team;
import eu.kotori.justTeams.team.TeamPlayer;
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
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import eu.kotori.justTeams.team.TeamRole;

public class MemberPermissionsEditGUI implements InventoryHolder, IRefreshableGUI {
    private final JustTeams plugin;
    private final Player viewer;
    private final Team team;
    private final TeamPlayer targetMember;
    private final Inventory inventory;
    private final ConfigurationSection guiConfig;

    public MemberPermissionsEditGUI(JustTeams plugin, Player viewer, Team team, UUID targetUuid) {
        this.plugin = plugin;
        this.viewer = viewer;
        this.team = team;
        this.targetMember = team.getMember(targetUuid);
        this.guiConfig = plugin.getGuiConfigManager().getGUI("member-permissions-edit-menu");
        String targetName = Bukkit.getOfflinePlayer(targetUuid).getName();
        String title = guiConfig.getString("title", "ᴘᴇʀᴍs: <target_name>").replace("<target_name>",
                targetName != null ? targetName : "Unknown");
        int size = guiConfig.getInt("size", 27);
        this.inventory = Bukkit.createInventory(this, size, Component.text(title));
        initializeItems();
    }

    public void initializeItems() {
        inventory.clear();
        if (targetMember == null)
            return;

        ConfigurationSection itemsSection = guiConfig.getConfigurationSection("items");
        if (itemsSection == null)
            return;

        boolean isSelfView = viewer.getUniqueId().equals(targetMember.getPlayerUuid());
        boolean canEdit = !isSelfView && (viewer.getUniqueId().equals(team.getOwnerUuid()) ||
                team.getMember(viewer.getUniqueId()) != null &&
                        team.getMember(viewer.getUniqueId()).getRole() == TeamRole.CO_OWNER);

        setItemFromConfig("header", itemsSection);

        if (canEdit) {
            setItemFromConfig("withdraw-permission", itemsSection);
            setItemFromConfig("enderchest-permission", itemsSection);
            setItemFromConfig("sethome-permission", itemsSection);
            setItemFromConfig("usehome-permission", itemsSection);

            if (targetMember.getRole() == TeamRole.MEMBER) {
                setItemFromConfig("promote-button", itemsSection);
            } else if (targetMember.getRole() == TeamRole.CO_OWNER
                    && !targetMember.getPlayerUuid().equals(team.getOwnerUuid())) {
                setItemFromConfig("demote-button", itemsSection);
            }
        } else {

            if (guiConfig.contains("items.withdraw-permission-view")) {
                setItemFromConfig("withdraw-permission-view", itemsSection);
            } else {
                setItemFromConfig("withdraw-permission", itemsSection);
            }

            if (guiConfig.contains("items.enderchest-permission-view")) {
                setItemFromConfig("enderchest-permission-view", itemsSection);
            } else {
                setItemFromConfig("enderchest-permission", itemsSection);
            }

            if (guiConfig.contains("items.sethome-permission-view")) {
                setItemFromConfig("sethome-permission-view", itemsSection);
            } else {
                setItemFromConfig("sethome-permission", itemsSection);
            }

            if (guiConfig.contains("items.usehome-permission-view")) {
                setItemFromConfig("usehome-permission-view", itemsSection);
            } else {
                setItemFromConfig("usehome-permission", itemsSection);
            }
        }

        setItemFromConfig("back-button", itemsSection);

        ConfigurationSection fillConfig = guiConfig.getConfigurationSection("fill-item");
        if (fillConfig != null) {
            ItemStack fillItem = new ItemBuilder(
                    Material.matchMaterial(fillConfig.getString("material", "LIGHT_BLUE_STAINED_GLASS_PANE")))
                    .withName(fillConfig.getString("name", " "))
                    .build();
            for (int i = 0; i < inventory.getSize(); i++) {
                if (inventory.getItem(i) == null) {
                    inventory.setItem(i, fillItem);
                }
            }
        }
    }

    private void setItemFromConfig(String key, ConfigurationSection parentSection) {
        ConfigurationSection itemConfig = parentSection.getConfigurationSection(key);
        if (itemConfig == null)
            return;
        if (!itemConfig.getBoolean("enabled", true))
            return;

        Material material = Material.matchMaterial(itemConfig.getString("material", "STONE"));
        String name = replacePlaceholders(itemConfig.getString("name", ""));
        List<String> lore = itemConfig.getStringList("lore").stream()
                .map(this::replacePlaceholders)
                .collect(Collectors.toList());
        int slot = itemConfig.getInt("slot", -1);
        if (slot == -1)
            return;

        ItemBuilder builder = new ItemBuilder(material).withName(name).withLore(lore).withAction(key);
        if (key.contains("header")) {
            builder.asPlayerHead(targetMember.getPlayerUuid());
        }

        inventory.setItem(slot, builder.build());
    }

    private String replacePlaceholders(String text) {
        if (text == null)
            return "";
        OfflinePlayer targetPlayer = Bukkit.getOfflinePlayer(targetMember.getPlayerUuid());
        String targetName = targetPlayer.getName() != null ? targetPlayer.getName() : "Unknown";
        String roleName = plugin.getGuiConfigManager().getRoleName(targetMember.getRole().name());
        String joinDate = formatJoinDate(targetMember.getJoinDate());

        boolean currentStatus = false;
        if (text.contains("<withdraw_status>") || text.contains("<toggle_text>")) {
            currentStatus = targetMember.canWithdraw();
        } else if (text.contains("<enderchest_status>")) {
            currentStatus = targetMember.canUseEnderChest();
        } else if (text.contains("<set_home_status>")) {
            currentStatus = targetMember.canSetHome();
        } else if (text.contains("<use_home_status>")) {
            currentStatus = targetMember.canUseHome();
        }

        String toggleText = currentStatus ? "<red>Click to DISABLE" : "<green>Click to ENABLE";

        return text
                .replace("<target_name>", targetName)
                .replace("<role>", roleName)
                .replace("<joindate>", joinDate)
                .replace("<withdraw_status>", getStatus(targetMember.canWithdraw()))
                .replace("<enderchest_status>", getStatus(targetMember.canUseEnderChest()))
                .replace("<set_home_status>", getStatus(targetMember.canSetHome()))
                .replace("<use_home_status>", getStatus(targetMember.canUseHome()))
                .replace("<toggle_text>", toggleText);
    }

    private String getStatus(boolean hasPerm) {
        return hasPerm ? "<green>ENABLED" : "<red>DISABLED";
    }

    private String formatJoinDate(Instant joinDate) {
        try {
            if (joinDate != null) {
                String dateFormat = plugin.getGuiConfigManager().getPlaceholder("date_time.join_date_format",
                        "dd MMM yyyy");
                DateTimeFormatter formatter = DateTimeFormatter.ofPattern(dateFormat)
                        .withZone(ZoneOffset.UTC);
                return formatter.format(joinDate);
            } else {
                return plugin.getGuiConfigManager().getPlaceholder("date_time.unknown_date", "Unknown");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Error formatting join date: " + e.getMessage());
            return plugin.getGuiConfigManager().getPlaceholder("date_time.unknown_date", "Unknown");
        }
    }

    public void open() {
        viewer.openInventory(inventory);
    }

    public Team getTeam() {
        return team;
    }

    public TeamPlayer getTargetMember() {
        return targetMember;
    }

    public void refresh() {
        open();
    }

    public Inventory getInventory() {
        return inventory;
    }
}
