package eu.kotori.justTeams.gui;

import eu.kotori.justTeams.JustTeams;
import eu.kotori.justTeams.storage.IDataStorage;
import eu.kotori.justTeams.team.Team;
import eu.kotori.justTeams.team.TeamPlayer;
import eu.kotori.justTeams.team.TeamRole;
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
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

public class TeamGUI implements IRefreshableGUI, InventoryHolder {
    private final JustTeams plugin;
    private final Team team;
    private final Inventory inventory;
    private final Player viewer;
    private Team.SortType currentSort;

    public TeamGUI(JustTeams plugin, Team team, Player viewer) {
        this.plugin = plugin;
        this.team = team;
        this.viewer = viewer;
        this.currentSort = team.getCurrentSortType();
        GuiConfigManager guiManager = plugin.getGuiConfigManager();
        ConfigurationSection guiConfig = guiManager.getGUI("team-gui");
        String title = guiConfig.getString("title", "Team")
                .replace("<members>", String.valueOf(team.getMembers().size()))
                .replace("<max_members>", String.valueOf(plugin.getConfigManager().getMaxTeamSize()));
        int size = guiConfig.getInt("size", 54);
        this.inventory = Bukkit.createInventory(this, size, plugin.getMiniMessage().deserialize(title));
        initializeItems();
    }

    public void initializeItems() {
        try {
            inventory.clear();
            GuiConfigManager guiManager = plugin.getGuiConfigManager();
            if (guiManager == null) {
                plugin.getLogger().severe("GUI Config Manager not available!");
                return;
            }
            ConfigurationSection guiConfig = guiManager.getGUI("team-gui");
            if (guiConfig == null) {
                plugin.getLogger().warning("Team GUI configuration not found!");
                return;
            }
            ConfigurationSection itemsConfig = guiConfig.getConfigurationSection("items");
            if (itemsConfig == null) {
                plugin.getLogger().warning("Team GUI items configuration not found!");
                return;
            }
            ItemStack border = new ItemBuilder(
                    guiManager.getMaterial("team-gui.fill-item.material", Material.GRAY_STAINED_GLASS_PANE))
                    .withName(guiManager.getString("team-gui.fill-item.name", " "))
                    .build();
            for (int i = 0; i < 9; i++)
                inventory.setItem(i, border);
            for (int i = 45; i < 54; i++)
                inventory.setItem(i, border);

            loadCustomDummyItems(guiConfig);

            int memberSlot = 9;
            for (TeamPlayer member : team.getSortedMembers(currentSort)) {
                if (memberSlot >= 45)
                    break;
                inventory.setItem(memberSlot++,
                        createMemberHead(member, itemsConfig.getConfigurationSection("player-head")));
            }
            TeamPlayer viewerMember = team.getMember(viewer.getUniqueId());
            if (viewerMember == null) {
                viewer.closeInventory();
                return;
            }
            if (plugin.getConfigManager().isTeamJoinRequestsEnabled()
                    && team.hasElevatedPermissions(viewer.getUniqueId())) {
                setItemFromConfig(itemsConfig, "join-requests");
            } else {
                setItemFromConfig(itemsConfig, "join-requests-locked");
            }
            setItemFromConfig(itemsConfig, "sort");
            if (plugin.getConfigManager().isTeamPvpToggleEnabled()) {
                setItemFromConfig(itemsConfig, "pvp-toggle");
            } else {
                setItemFromConfig(itemsConfig, "pvp-toggle-locked");
            }
            if (team.hasElevatedPermissions(viewer.getUniqueId())) {
                setItemFromConfig(itemsConfig, "team-settings-button");
            }
            if (plugin.getConfigManager().isTeamDisbandEnabled() && team.isOwner(viewer.getUniqueId())) {
                setItemFromConfig(itemsConfig, "disband-button");
            } else if (plugin.getConfigManager().isMemberLeaveEnabled()) {
                setItemFromConfig(itemsConfig, "leave-button");
            }
            if (plugin.getConfigManager().isTeamBankEnabled()) {
                setItemFromConfig(itemsConfig, "bank");
            } else {
                setItemFromConfig(itemsConfig, "bank-locked");
            }
            if (plugin.getConfigManager().isTeamEnderchestEnabled()) {
                boolean hasAccess = viewer.hasPermission("justteams.bypass.enderchest.use")
                        || viewerMember.canUseEnderChest();
                if (plugin.getConfigManager().isDebugEnabled()) {
                    plugin.getDebugLogger().log("Setting enderchest item for " + viewer.getName() + " - hasAccess: "
                            + hasAccess + ", canUseEnderChest: " + viewerMember.canUseEnderChest());
                }
                String itemKey = hasAccess ? "ender-chest" : "ender-chest-locked";
                if (plugin.getConfigManager().isDebugEnabled()) {
                    plugin.getDebugLogger().log("Player " + viewer.getName() + " will see enderchest item: " + itemKey
                            + " (hasAccess: " + hasAccess + ")");
                    plugin.getDebugLogger()
                            .log("DEBUG: " + viewer.getName() + " - viewerMember UUID: " + viewerMember.getPlayerUuid()
                                    +
                                    ", canUseEnderChest: " + viewerMember.canUseEnderChest() +
                                    ", hasBypass: " + viewer.hasPermission("justteams.bypass.enderchest.use") +
                                    ", team: " + team.getName() + ", teamId: " + team.getId());
                }
                setItemFromConfig(itemsConfig, itemKey);
            } else {
                if (plugin.getConfigManager().isDebugEnabled()) {
                    plugin.getDebugLogger().log("Enderchest disabled in config for " + viewer.getName());
                }
                setItemFromConfig(itemsConfig, "ender-chest-locked");
            }
            if (plugin.getConfigManager().isTeamWarpsEnabled()) {
                setItemFromConfig(itemsConfig, "warps");
            } else {
                setItemFromConfig(itemsConfig, "warps-locked");
            }
            setHomeItemAsync(itemsConfig);
        } catch (Exception e) {
            plugin.getLogger().severe("Error initializing Team GUI items: " + e.getMessage());
            if (plugin.getConfigManager().isDebugEnabled()) {
                plugin.getLogger().severe("Error in TeamGUI: " + e.getMessage());
            }
        }
    }

    private void setItemFromConfig(ConfigurationSection itemsConfig, String key) {
        ConfigurationSection itemConfig = itemsConfig.getConfigurationSection(key);
        if (itemConfig == null)
            return;
        if (!itemConfig.getBoolean("enabled", true))
            return;
        int slot = itemConfig.getInt("slot", -1);
        if (slot == -1)
            return;
        Material material = Material.matchMaterial(itemConfig.getString("material", "STONE"));
        ItemBuilder builder = new ItemBuilder(material);
        String name = replacePlaceholders(itemConfig.getString("name", ""));
        builder.withName(name);
        List<String> lore = new ArrayList<>(itemConfig.getStringList("lore"));
        builder.withLore(lore.stream().map(this::replacePlaceholders).collect(Collectors.toList()));
        String action = itemConfig.getString("action", key);
        builder.withAction(action);
        inventory.setItem(slot, builder.build());
    }

    private void setHomeItemAsync(ConfigurationSection itemsConfig) {
        ConfigurationSection itemConfig = itemsConfig.getConfigurationSection("home");
        if (itemConfig == null)
            return;
        int slot = itemConfig.getInt("slot", -1);
        if (slot == -1)
            return;
        Material homeMaterial = Material.matchMaterial(itemConfig.getString("material", "ENDER_PEARL"));
        ItemStack loadingItem = new ItemBuilder(homeMaterial).withName("<gray>Loading Home Status...").build();
        inventory.setItem(slot, loadingItem);
        plugin.getTaskRunner().runAsync(() -> {
            Optional<IDataStorage.TeamHome> teamHomeOpt = plugin.getStorageManager().getStorage()
                    .getTeamHome(team.getId());
            plugin.getTaskRunner().runOnEntity(viewer, () -> {
                ItemBuilder builder = new ItemBuilder(homeMaterial);
                String name = replacePlaceholders(itemConfig.getString("name", ""));
                builder.withName(name);
                List<String> lore = itemConfig.getStringList(teamHomeOpt.isPresent() ? "lore-set" : "lore-not-set");
                builder.withLore(lore.stream().map(this::replacePlaceholders).collect(Collectors.toList()));
                builder.withAction("home");
                inventory.setItem(slot, builder.build());
            });
        });
    }

    private String replacePlaceholders(String text) {
        if (text == null)
            return "";
        String pvpStatus = team.isPvpEnabled()
                ? plugin.getGuiConfigManager().getString("team-gui.items.pvp-toggle.status-on", "<green>ON")
                : plugin.getGuiConfigManager().getString("team-gui.items.pvp-toggle.status-off", "<red>OFF");
        String pvpPrompt = team.hasElevatedPermissions(viewer.getUniqueId())
                ? plugin.getGuiConfigManager().getString("team-gui.items.pvp-toggle.can-toggle-prompt",
                        "<yellow>Click to toggle")
                : plugin.getGuiConfigManager().getString("team-gui.items.pvp-toggle.cannot-toggle-prompt",
                        "<red>Permission denied");
        String currencyFormat = plugin.getConfigManager().getCurrencyFormat();
        DecimalFormat formatter = new DecimalFormat(currencyFormat);
        return text
                .replace("<balance>", formatter.format(team.getBalance()))
                .replace("<status>", pvpStatus)
                .replace("<permission_prompt>", pvpPrompt)
                .replace("<sort_status_join_date>", getSortLore(Team.SortType.JOIN_DATE))
                .replace("<sort_status_alphabetical>", getSortLore(Team.SortType.ALPHABETICAL))
                .replace("<sort_status_online_status>", getSortLore(Team.SortType.ONLINE_STATUS))
                .replace("<team_name>", team.getName())
                .replace("<team_tag>", team.getTag())
                .replace("<team_description>", team.getDescription());
    }

    private ItemStack createMemberHead(TeamPlayer member, ConfigurationSection headConfig) {
        String playerName = Bukkit.getOfflinePlayer(member.getPlayerUuid()).getName();

        boolean isBedrockPlayer = plugin.getBedrockSupport() != null &&
                plugin.getBedrockSupport().isBedrockPlayer(member.getPlayerUuid());
        String platformIndicator = "";
        if (plugin.getGuiConfigManager().getPlaceholder("platform.show_in_gui", "true").equals("true")) {
            if (isBedrockPlayer
                    && plugin.getGuiConfigManager().getPlaceholder("platform.bedrock.enabled", "true").equals("true")) {
                platformIndicator = plugin.getGuiConfigManager().getPlaceholder("platform.bedrock.format",
                        " <#00D4FF>[BE]</#00D4FF>");
            } else if (!isBedrockPlayer
                    && plugin.getGuiConfigManager().getPlaceholder("platform.java.enabled", "true").equals("true")) {
                platformIndicator = plugin.getGuiConfigManager().getPlaceholder("platform.java.format",
                        " <#00FF00>[JE]</#00FF00>");
            }
        }

        String crossServerStatus = "";
        if (member.isOnline() && plugin.getConfigManager().isCrossServerSyncEnabled() &&
                plugin.getConfigManager().getBoolean("features.show_cross_server_status", true)) {

            Optional<IDataStorage.PlayerSession> sessionOpt = plugin.getStorageManager().getStorage()
                    .getPlayerSession(member.getPlayerUuid());

            if (sessionOpt.isPresent()) {
                String currentServer = plugin.getConfigManager().getServerIdentifier();
                String playerServer = sessionOpt.get().serverName();

                if (!currentServer.equalsIgnoreCase(playerServer)) {
                    String displayServer = plugin.getStorageManager().getStorage()
                            .getServerAlias(playerServer).orElse(playerServer);
                    crossServerStatus = " <gray>(<yellow>" + displayServer + "</yellow>)</gray>";
                }
            }
        }

        String nameFormat = member.isOnline() ? headConfig.getString("online-name-format", "<green><player>")
                : headConfig.getString("offline-name-format", "<gray><player>");
        String name = nameFormat
                .replace("<status_indicator>", getStatusIndicator(member.isOnline()))
                .replace("<role_icon>", getRoleIcon(member.getRole()))
                .replace("<player>", playerName != null ? playerName : "Unknown") + platformIndicator
                + crossServerStatus;
        String joinDateStr = formatJoinDate(member.getJoinDate(), playerName);

        final String serverInfo;
        if (member.isOnline() && plugin.getConfigManager().isCrossServerSyncEnabled() &&
                plugin.getConfigManager().getBoolean("features.show_cross_server_status", true)) {

            Optional<IDataStorage.PlayerSession> sessionOpt = plugin.getStorageManager().getStorage()
                    .getPlayerSession(member.getPlayerUuid());

            if (sessionOpt.isPresent()) {
                String currentServer = plugin.getConfigManager().getServerIdentifier();
                String playerServer = sessionOpt.get().serverName();

                if (!currentServer.equalsIgnoreCase(playerServer)) {
                    String displayServer = plugin.getStorageManager().getStorage()
                            .getServerAlias(playerServer).orElse(playerServer);
                    serverInfo = displayServer;
                } else {
                    serverInfo = currentServer;
                }
            } else {
                serverInfo = "Local";
            }
        } else if (!member.isOnline()) {
            serverInfo = "<dark_gray>Offline</dark_gray>";
        } else {
            serverInfo = "Local";
        }

        List<String> loreLines = new ArrayList<>(headConfig.getStringList("lore").stream()
                .map(line -> line
                        .replace("<role>", getRoleName(member.getRole()))
                        .replace("<joindate>", joinDateStr)
                        .replace("<server>", serverInfo))
                .collect(Collectors.toList()));

        if (isBedrockPlayer
                && plugin.getGuiConfigManager().getPlaceholder("platform.show_gamertags", "true").equals("true")) {
            String gamertag = plugin.getBedrockSupport().getBedrockGamertag(member.getPlayerUuid());
            if (gamertag != null && !gamertag.equals(playerName)) {
                String gamertagColor = plugin.getGuiConfigManager().getPlaceholder("platform.bedrock.color", "#00D4FF");
                loreLines.add("<gray>Gamertag: <" + gamertagColor + ">" + gamertag + "</" + gamertagColor + ">");
            }
        }

        ItemBuilder builder = new ItemBuilder(Material.PLAYER_HEAD)
                .asPlayerHead(member.getPlayerUuid())
                .withName(name)
                .withLore(loreLines);
        TeamPlayer viewerMember = team.getMember(viewer.getUniqueId());
        if (viewerMember != null) {
            boolean canEdit = false;
            boolean isSelfClick = member.getPlayerUuid().equals(viewer.getUniqueId());
            if (viewerMember.getRole() == TeamRole.OWNER) {
                canEdit = !isSelfClick;
            } else if (viewerMember.getRole() == TeamRole.CO_OWNER) {
                canEdit = !isSelfClick && member.getRole() == TeamRole.MEMBER;
            }
            if (canEdit) {
                builder.withAction("player-head");
            }
        }
        if (member.getRole() == TeamRole.OWNER) {
            builder.withGlow();
        }
        return builder.build();
    }

    private String formatJoinDate(Instant joinDate, String playerName) {
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
            plugin.getLogger().warning("Error formatting join date for " + playerName + ": " + e.getMessage());
            return plugin.getGuiConfigManager().getPlaceholder("date_time.unknown_date", "Unknown");
        }
    }

    private String getSortLore(Team.SortType type) {
        String sortTypeKey = type.name().toLowerCase();
        String name = plugin.getGuiConfigManager().getSortName(sortTypeKey);
        String icon = plugin.getGuiConfigManager().getSortIcon(sortTypeKey);
        String prefix = (currentSort == type ? plugin.getGuiConfigManager().getSortSelectedPrefix()
                : plugin.getGuiConfigManager().getSortUnselectedPrefix());
        return prefix + icon + name;
    }

    private String getRoleIcon(TeamRole role) {
        return plugin.getGuiConfigManager().getRoleIcon(role.name());
    }

    private String getStatusIndicator(boolean isOnline) {
        String icon = plugin.getGuiConfigManager().getStatusIcon(isOnline);
        String color = plugin.getGuiConfigManager().getStatusColor(isOnline);
        return "<" + color + ">" + icon + " </" + color + ">";
    }

    private String getRoleName(TeamRole role) {
        return plugin.getGuiConfigManager().getRoleName(role.name());
    }

    private void loadCustomDummyItems(ConfigurationSection guiConfig) {
        GUIManager.loadDummyItems(inventory, guiConfig);
    }

    public void open() {
        viewer.openInventory(inventory);
    }

    public void refresh() {
        initializeItems();
    }

    public void cycleSort() {
        currentSort = switch (currentSort) {
            case JOIN_DATE -> Team.SortType.ALPHABETICAL;
            case ALPHABETICAL -> Team.SortType.ONLINE_STATUS;
            case ONLINE_STATUS -> Team.SortType.JOIN_DATE;
        };
        team.setSortType(currentSort);
        initializeItems();
    }

    public Team getTeam() {
        return team;
    }

    public Inventory getInventory() {
        return inventory;
    }
}
