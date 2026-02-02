package eu.kotori.justTeams.commands;

import eu.kotori.justTeams.JustTeams;
import eu.kotori.justTeams.team.Team;
import eu.kotori.justTeams.team.TeamManager;
import eu.kotori.justTeams.gui.BlacklistGUI;
import eu.kotori.justTeams.gui.JoinRequestGUI;
import eu.kotori.justTeams.gui.NoTeamGUI;
import eu.kotori.justTeams.gui.TeamGUI;
import eu.kotori.justTeams.gui.sub.TeamSettingsGUI;
import eu.kotori.justTeams.storage.IDataStorage;
import eu.kotori.justTeams.storage.DatabaseMigrationManager;
import eu.kotori.justTeams.storage.DatabaseFileManager;
import eu.kotori.justTeams.storage.DatabaseStorage;
import eu.kotori.justTeams.util.ConfigUpdater;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.concurrent.ConcurrentHashMap;

import java.sql.Timestamp;
import eu.kotori.justTeams.team.TeamPlayer;
import eu.kotori.justTeams.team.BlacklistedPlayer;

public class TeamCommand implements CommandExecutor, TabCompleter {
    private final JustTeams plugin;
    private final TeamManager teamManager;
    private final ConcurrentHashMap<UUID, Long> commandCooldowns = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Integer> commandCounts = new ConcurrentHashMap<>();
    private static final long COMMAND_COOLDOWN = 1000;
    private static final int MAX_COMMANDS_PER_MINUTE = 30;
    private static final long COMMAND_RESET_INTERVAL = 60000;

    public TeamCommand(JustTeams plugin) {
        this.plugin = plugin;
        this.teamManager = plugin.getTeamManager();
        plugin.getTaskRunner().runTimer(() -> {
            commandCounts.clear();
        }, 20L * 60, 20L * 60);
    }

    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("reload")) {
            handleReload(sender);
            return true;
        }

        if (!(sender instanceof Player player)) {
            plugin.getMessageManager().sendMessage(sender, "player_only");
            return true;
        }
        if (!checkCommandSpam(player)) {
            plugin.getMessageManager().sendMessage(player, "command_spam_protection");
            return true;
        }
        if (args.length == 0) {
            handleGUI(player);
            return true;
        }
        String subCommand = args[0].toLowerCase();
        switch (subCommand) {
            case "create" -> handleCreate(player, args);
            case "disband" -> handleDisband(player);
            case "invite" -> handleInvite(player, args);
            case "invites" -> handleInvites(player);
            case "accept" -> handleAccept(player, args);
            case "deny" -> handleDeny(player, args);
            case "join" -> handleJoin(player, args);
            case "unjoin" -> handleUnjoin(player, args);
            case "kick" -> handleKick(player, args);
            case "leave" -> handleLeave(player);
            case "promote" -> handlePromote(player, args);
            case "demote" -> handleDemote(player, args);
            case "info" -> handleInfo(player, args);
            case "sethome" -> handleSetHome(player);
            case "delhome" -> handleDelHome(player);
            case "home" -> handleHome(player);
            case "settag" -> handleSetTag(player, args);
            case "setcolor" -> handleSetColor(player, args);
            case "setdesc" -> handleSetDescription(player, args);
            case "rename" -> handleRename(player, args);
            case "transfer" -> handleTransfer(player, args);
            case "pvp" -> handlePvpToggle(player);
            case "bank" -> handleBank(player, args);
            case "enderchest", "ec" -> handleEnderChest(player);
            case "public" -> handlePublicToggle(player);
            case "glow" -> handleToggleGlow(player);
            case "requests" -> handleRequests(player);
            case "setwarp" -> handleSetWarp(player, args);
            case "delwarp" -> handleDelWarp(player, args);
            case "warp" -> handleWarp(player, args);
            case "warps" -> handleWarps(player);
            case "blacklist" -> handleBlacklist(player, args);
            case "unblacklist" -> handleUnblacklist(player, args);
            case "settings" -> handleSettings(player);
            case "top" -> handleTop(player, args);
            case "admin" -> handleAdmin(player, args);
            case "serveralias" -> handleServerAlias(player, args);
            case "platform" -> handlePlatform(player);
            case "help" -> handleHelp(player);
            case "chat" -> handleChat(player);
            case "chatspy", "spy" -> handleChatSpy(player);
            case "debug-permissions" -> {
                if (!hasAdminPermission(player)) {
                    return false;
                }
                plugin.getTaskRunner().runAsync(() -> {
                    try {
                        plugin.getLogger().info("=== DEBUG: Team "
                                + teamManager.getPlayerTeam(player.getUniqueId()).getName() + " Permissions ===");
                        for (TeamPlayer member : teamManager.getPlayerTeam(player.getUniqueId()).getMembers()) {
                            plugin.getLogger().info("Member: " + member.getPlayerUuid() +
                                    " - Role: " + member.getRole() +
                                    " - canUseEnderChest: " + member.canUseEnderChest() +
                                    " - canWithdraw: " + member.canWithdraw() +
                                    " - canSetHome: " + member.canSetHome() +
                                    " - canUseHome: " + member.canUseHome());
                        }
                        plugin.getLogger().info("=== END DEBUG ===");
                        plugin.getTaskRunner().runOnEntity(player, () -> {
                            plugin.getMessageManager().sendRawMessage(player,
                                    "<green>Team permissions debug info sent to console. Check server logs.");
                        });
                    } catch (Exception e) {
                        plugin.getLogger().severe("Error in debug-permissions command: " + e.getMessage());
                        plugin.getTaskRunner().runOnEntity(player, () -> {
                            plugin.getMessageManager().sendRawMessage(player,
                                    "<red>Error occurred while checking permissions. Check server logs.");
                        });
                    }
                });
                return true;
            }
            case "debug-placeholders" -> {
                if (!hasAdminPermission(player)) {
                    return false;
                }
                plugin.getTaskRunner().runAsync(() -> {
                    try {
                        plugin.getLogger().info("=== DEBUG: PlaceholderAPI Test for " + player.getName() + " ===");
                        if (plugin.getServer().getPluginManager().getPlugin("PlaceholderAPI") == null) {
                            plugin.getLogger().warning("PlaceholderAPI is not installed!");
                            plugin.getTaskRunner().runOnEntity(player, () -> {
                                plugin.getMessageManager().sendRawMessage(player,
                                        "<red>PlaceholderAPI is not installed!");
                            });
                            return;
                        }
                        String[] placeholders = {
                                "justteams_has_team", "justteams_name", "justteams_tag", "justteams_description",
                                "justteams_owner", "justteams_role", "justteams_member_count", "justteams_max_members",
                                "justteams_members_online", "justteams_kills", "justteams_deaths", "justteams_kdr",
                                "justteams_bank_balance", "justteams_is_owner", "justteams_is_co_owner",
                                "justteams_is_member"
                        };
                        for (String placeholder : placeholders) {
                            String result = me.clip.placeholderapi.PlaceholderAPI.setPlaceholders(player,
                                    "%" + placeholder + "%");
                            plugin.getLogger().info(placeholder + ": " + result);
                        }
                        plugin.getLogger().info("=== END PLACEHOLDER DEBUG ===");
                        plugin.getTaskRunner().runOnEntity(player, () -> {
                            plugin.getMessageManager().sendRawMessage(player,
                                    "<green>PlaceholderAPI test completed. Check server logs for results.");
                        });
                    } catch (Exception e) {
                        plugin.getLogger().severe("Error in debug-placeholders command: " + e.getMessage());
                        plugin.getTaskRunner().runOnEntity(player, () -> {
                            plugin.getMessageManager().sendRawMessage(player,
                                    "<red>Error occurred while testing placeholders. Check server logs.");
                        });
                    }
                });
                return true;
            }
            case "debug-conflict" -> {
                if (!hasAdminPermission(player)) {
                    return false;
                }
                if (args.length < 2) {
                    plugin.getMessageManager().sendMessage(player, "usage_debug_conflict");

                    return true;
                }
                String targetName = args[1];
                Player target = Bukkit.getPlayer(targetName);
                if (target == null) {
                    plugin.getMessageManager().sendRawMessage(player, "<red>Player not found.");
                    return true;
                }

                org.bukkit.scoreboard.Scoreboard mainScoreboard = Bukkit.getScoreboardManager().getMainScoreboard();
                org.bukkit.scoreboard.Team sbTeam = mainScoreboard.getEntryTeam(target.getName());

                plugin.getMessageManager().sendRawMessage(player,
                        "<gold>=== Debug Conflict: " + target.getName() + " ===");
                if (sbTeam == null) {
                    plugin.getMessageManager().sendRawMessage(player, "<yellow>Vanilla Scoreboard Team: <red>NONE");
                    plugin.getMessageManager().sendRawMessage(player,
                            "<gray>If they are White, it might be client-side defaults or a packet-only plugin (like TAB) sending teams without registering them in Bukkit API.");
                } else {
                    plugin.getMessageManager().sendRawMessage(player,
                            "<yellow>Vailla Scoreboard Team: <green>" + sbTeam.getName());
                    plugin.getMessageManager().sendRawMessage(player,
                            "<yellow>Team Color: <green>" + sbTeam.getColor().name());
                    plugin.getMessageManager().sendRawMessage(player,
                            "<yellow>Display Name: <green>" + sbTeam.getDisplayName());
                    plugin.getMessageManager().sendRawMessage(player, "<yellow>Prefix: <green>" + sbTeam.getPrefix());
                    plugin.getMessageManager().sendRawMessage(player, "<yellow>Suffix: <green>" + sbTeam.getSuffix());
                }
                plugin.getMessageManager().sendRawMessage(player, "<gold>==============================");
                return true;
            }
            default -> {
                plugin.getMessageManager().sendMessage(player, "unknown_command");
                return false;
            }
        }
        return true;
    }

    private boolean checkCommandSpam(Player player) {
        long currentTime = System.currentTimeMillis();
        UUID playerId = player.getUniqueId();
        Long lastCommand = commandCooldowns.get(playerId);
        if (lastCommand != null && currentTime - lastCommand < COMMAND_COOLDOWN) {
            return false;
        }
        int count = commandCounts.getOrDefault(playerId, 0);
        if (count >= MAX_COMMANDS_PER_MINUTE) {
            return false;
        }
        commandCooldowns.put(playerId, currentTime);
        commandCounts.put(playerId, count + 1);
        return true;
    }

    private boolean checkFeatureEnabled(Player player, String feature) {
        if (!plugin.getConfigManager().isFeatureEnabled(feature)) {
            plugin.getMessageManager().sendMessage(player, "feature_disabled");
            return false;
        }
        return true;
    }

    private boolean validateTeamNameAndTag(String name, String tag) {
        if (name == null || name.length() < plugin.getConfigManager().getMinNameLength()
                || name.length() > plugin.getConfigManager().getMaxNameLength()) {
            return false;
        }
        if (tag == null || tag.length() < 2 || tag.length() > plugin.getConfigManager().getMaxTagLength()) {
            return false;
        }
        String plainName = stripColorCodes(name);
        String plainTag = stripColorCodes(tag);

        if (!plainName.matches("^[a-zA-Z0-9_]+$")) {
            return false;
        }
        if (!plainTag.matches("^[a-zA-Z0-9_]+$")) {
            return false;
        }

        if (plainName.matches("^[0-9_]+$") || plainTag.matches("^[0-9_]+$")) {
            return false;
        }

        String[] sqlPatterns = { "--", ";", "/*", "*/", "xp_", "sp_", "union", "select", "insert", "update", "delete",
                "drop", "create" };
        String lowerName = plainName.toLowerCase();
        String lowerTag = plainTag.toLowerCase();

        for (String pattern : sqlPatterns) {
            if (lowerName.contains(pattern) || lowerTag.contains(pattern)) {
                plugin.getLogger()
                        .warning("Potential SQL injection attempt detected in team name/tag: " + name + "/" + tag);
                return false;
            }
        }

        String[] inappropriate = { "admin", "mod", "staff", "owner", "server", "minecraft", "bukkit", "spigot",
                "console", "system", "root" };
        for (String word : inappropriate) {
            if (lowerName.contains(word) || lowerTag.contains(word)) {
                return false;
            }
        }

        return true;
    }

    private String stripColorCodes(String text) {
        if (text == null)
            return "";
        text = text.replaceAll("(?i)&[0-9A-FK-OR]", "");
        text = text.replaceAll("(?i)<#[0-9A-F]{6}>", "");
        text = text.replaceAll("(?i)</#[0-9A-F]{6}>", "");
        text = text.replaceAll("(?i)<[^>]+>", "");
        return text.trim();
    }

    private boolean isValidPlayerName(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }

        int minLength = plugin.getConfigManager().getMinNameLength();
        int maxLength = plugin.getConfigManager().getMaxNameLength();

        if (name.length() < minLength || name.length() > maxLength) {
            return false;
        }

        if (!name.matches("^[a-zA-Z0-9_. ]+$")) {
            return false;
        }

        String lowerName = name.toLowerCase();
        if (lowerName.contains("--") || lowerName.contains(";") || lowerName.contains("'")
                || lowerName.contains("\"")) {
            plugin.getLogger().warning("Potential injection attempt in player name: " + name);
            return false;
        }

        return true;
    }

    private void handleGUI(Player player) {
        Team team = teamManager.getPlayerTeam(player.getUniqueId());
        if (team == null) {
            new NoTeamGUI(plugin, player).open();
        } else {
            new TeamGUI(plugin, team, player).open();
        }
    }

    private void handleCreate(Player player, String[] args) {
        if (!checkFeatureEnabled(player, "team_creation")) {
            return;
        }

        boolean tagEnabled = plugin.getConfigManager().isTeamTagEnabled();

        if (tagEnabled && args.length < 3) {
            plugin.getMessageManager().sendMessage(player, "usage_create");
            return;
        } else if (!tagEnabled && args.length < 2) {
            plugin.getMessageManager().sendMessage(player, "usage_create_no_tag");
            return;
        }

        String teamName = args[1];
        String teamTag = tagEnabled && args.length >= 3 ? args[2] : "";

        if (tagEnabled) {
            if (!validateTeamNameAndTag(teamName, teamTag)) {
                plugin.getMessageManager().sendMessage(player, "invalid_team_name_or_tag");
                return;
            }
        } else {
            String plainName = stripColorCodes(teamName);
            if (!plainName.matches("^[a-zA-Z0-9_]+$")) {
                plugin.getMessageManager().sendMessage(player, "invalid_team_name");
                return;
            }
            String lowerName = plainName.toLowerCase();
            String[] inappropriate = { "admin", "mod", "staff", "owner", "server", "minecraft", "bukkit", "spigot" };
            for (String word : inappropriate) {
                if (lowerName.contains(word)) {
                    plugin.getMessageManager().sendMessage(player, "invalid_team_name");
                    return;
                }
            }
        }

        if (teamManager.getPlayerTeam(player.getUniqueId()) != null) {
            plugin.getMessageManager().sendMessage(player, "already_in_team");
            return;
        }
        teamManager.createTeam(player, teamName, teamTag);
    }

    private void handleDisband(Player player) {
        if (!checkFeatureEnabled(player, "team_disband")) {
            return;
        }
        Team team = teamManager.getPlayerTeam(player.getUniqueId());
        if (team == null) {
            plugin.getMessageManager().sendMessage(player, "player_not_in_team");
            return;
        }
        if (!team.isOwner(player.getUniqueId())) {
            plugin.getMessageManager().sendMessage(player, "must_be_owner");
            return;
        }
        teamManager.disbandTeam(player);
    }

    private void handleInvite(Player player, String[] args) {
        if (!checkFeatureEnabled(player, "team_invites")) {
            return;
        }
        if (args.length < 2) {
            plugin.getMessageManager().sendMessage(player, "usage_invite");
            return;
        }
        String targetName = String.join(" ", Arrays.copyOfRange(args, 1, args.length));

        if (!isValidPlayerName(targetName)) {
            plugin.getMessageManager().sendMessage(player, "invalid_player_name");
            return;
        }

        if (targetName.equalsIgnoreCase(player.getName())) {
            plugin.getMessageManager().sendMessage(player, "cannot_invite_yourself");
            return;
        }

        Player target = Bukkit.getPlayer(targetName);
        if (target != null && target.isOnline()) {
            if (teamManager.getPlayerTeam(target.getUniqueId()) != null) {
                plugin.getMessageManager().sendMessage(player, "player_already_in_team",
                        net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("target",
                                target.getName()));
                return;
            }
            teamManager.invitePlayer(player, target);
            return;
        }

        plugin.getTaskRunner().runAsync(() -> {
            plugin.getStorageManager().getStorage().cachePlayerName(player.getUniqueId(), player.getName());

            Optional<UUID> targetUuidOpt = plugin.getStorageManager().getStorage().getPlayerUuidByName(targetName);

            if (targetUuidOpt.isEmpty()) {
                String normalizedName = plugin.getBedrockSupport().normalizePlayerName(targetName);
                if (!normalizedName.equals(targetName)) {
                    targetUuidOpt = plugin.getStorageManager().getStorage().getPlayerUuidByName(normalizedName);
                }
            }

            if (targetUuidOpt.isEmpty()) {
                OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayerIfCached(targetName);
                if (offlinePlayer != null && offlinePlayer.hasPlayedBefore()) {
                    UUID targetUuid = offlinePlayer.getUniqueId();
                    plugin.getStorageManager().getStorage().cachePlayerName(targetUuid, targetName);
                    String normalizedName = plugin.getBedrockSupport().normalizePlayerName(targetName);
                    if (!normalizedName.equals(targetName)) {
                        plugin.getStorageManager().getStorage().cachePlayerName(targetUuid, normalizedName);
                    }
                    targetUuidOpt = Optional.of(targetUuid);
                } else {
                    plugin.getTaskRunner().runOnEntity(player, () -> {
                        plugin.getMessageManager().sendMessage(player, "player_not_found",
                                Placeholder.unparsed("target", targetName));
                    });
                    return;
                }
            }

            UUID targetUuid = targetUuidOpt.get();

            Optional<Team> existingTeam = plugin.getStorageManager().getStorage().findTeamByPlayer(targetUuid);
            if (existingTeam.isPresent()) {
                plugin.getTaskRunner().runOnEntity(player, () -> {
                    plugin.getMessageManager().sendMessage(player, "player_already_in_team",
                            net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("target",
                                    targetName));
                });
                return;
            }

            plugin.getTaskRunner().runOnEntity(player, () -> {
                teamManager.invitePlayerByUuid(player, targetUuid, targetName);
            });
        });
    }

    private void handleInvites(Player player) {
        if (!checkFeatureEnabled(player, "team_invites")) {
            return;
        }

        new eu.kotori.justTeams.gui.InvitesGUI(plugin, player).open();
    }

    private void handleAccept(Player player, String[] args) {
        if (!checkFeatureEnabled(player, "team_invites")) {
            return;
        }
        if (args.length < 2) {
            plugin.getMessageManager().sendMessage(player, "usage_accept");
            return;
        }
        String teamName = args[1];

        if (teamName == null || teamName.isEmpty() || teamName.length() < plugin.getConfigManager().getMinNameLength()
                || teamName.length() > plugin.getConfigManager().getMaxNameLength()) {
            plugin.getMessageManager().sendMessage(player, "invalid_team_name");
            return;
        }

        String plainTeamName = stripColorCodes(teamName);
        if (plainTeamName.isEmpty() || !plainTeamName.matches("^[a-zA-Z0-9_]+$")) {
            plugin.getMessageManager().sendMessage(player, "invalid_team_name");
            return;
        }

        if (teamManager.getPlayerTeam(player.getUniqueId()) != null) {
            plugin.getMessageManager().sendMessage(player, "already_in_team");
            return;
        }
        teamManager.acceptInvite(player, plainTeamName);
    }

    private void handleDeny(Player player, String[] args) {
        if (!checkFeatureEnabled(player, "team_invites")) {
            return;
        }
        if (args.length < 2) {
            plugin.getMessageManager().sendMessage(player, "usage_deny");
            return;
        }
        String teamName = args[1];
        if (teamName.length() < plugin.getConfigManager().getMinNameLength()
                || teamName.length() > plugin.getConfigManager().getMaxNameLength()
                || !teamName.matches("^[a-zA-Z0-9_]+$")) {
            plugin.getMessageManager().sendMessage(player, "invalid_team_name");
            return;
        }
        teamManager.denyInvite(player, teamName);
    }

    private void handleJoin(Player player, String[] args) {
        if (!checkFeatureEnabled(player, "team_join_requests")) {
            return;
        }
        if (args.length < 2) {
            plugin.getMessageManager().sendMessage(player, "usage_join");
            return;
        }
        String teamName = args[1];

        if (teamName == null || teamName.isEmpty() || teamName.length() < plugin.getConfigManager().getMinNameLength()
                || teamName.length() > plugin.getConfigManager().getMaxNameLength()) {
            plugin.getMessageManager().sendMessage(player, "invalid_team_name");
            return;
        }

        String plainTeamName = stripColorCodes(teamName);
        if (plainTeamName.isEmpty() || !plainTeamName.matches("^[a-zA-Z0-9_]+$")) {
            plugin.getMessageManager().sendMessage(player, "invalid_team_name");
            return;
        }

        if (teamManager.getPlayerTeam(player.getUniqueId()) != null) {
            plugin.getMessageManager().sendMessage(player, "already_in_team");
            return;
        }
        teamManager.joinTeam(player, plainTeamName);
    }

    private void handleKick(Player player, String[] args) {
        if (!checkFeatureEnabled(player, "member_kick")) {
            return;
        }
        if (args.length < 2) {
            plugin.getMessageManager().sendMessage(player, "usage_kick");
            return;
        }
        String targetName = String.join(" ", Arrays.copyOfRange(args, 1, args.length));

        if (!isValidPlayerName(targetName)) {
            plugin.getMessageManager().sendMessage(player, "invalid_player_name");
            return;
        }

        if (targetName.equalsIgnoreCase(player.getName())) {
            plugin.getMessageManager().sendMessage(player, "cannot_kick_yourself");
            return;
        }

        Player target = Bukkit.getPlayer(targetName);
        if (target == null) {
            plugin.getMessageManager().sendMessage(player, "player_not_found",
                    Placeholder.unparsed("target", targetName));
            return;
        }

        Team playerTeam = teamManager.getPlayerTeam(player.getUniqueId());
        Team targetTeam = teamManager.getPlayerTeam(target.getUniqueId());
        if (playerTeam == null || targetTeam == null || playerTeam.getId() != targetTeam.getId()) {
            plugin.getMessageManager().sendMessage(player, "player_not_in_same_team");
            return;
        }
        teamManager.kickPlayer(player, target.getUniqueId());
    }

    private void handleLeave(Player player) {
        if (!checkFeatureEnabled(player, "member_leave")) {
            return;
        }
        Team team = teamManager.getPlayerTeam(player.getUniqueId());
        if (team == null) {
            plugin.getMessageManager().sendMessage(player, "player_not_in_team");
            return;
        }
        if (team.isOwner(player.getUniqueId())) {
            plugin.getMessageManager().sendMessage(player, "owner_cannot_leave");
            return;
        }
        teamManager.leaveTeam(player);
    }

    private void handlePromote(Player player, String[] args) {
        if (!checkFeatureEnabled(player, "member_promote")) {
            return;
        }
        if (args.length < 2) {
            plugin.getMessageManager().sendMessage(player, "usage_promote");
            return;
        }
        String targetName = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        if (targetName.equalsIgnoreCase(player.getName())) {
            plugin.getMessageManager().sendMessage(player, "cannot_promote_yourself");
            return;
        }
        Player target = Bukkit.getPlayer(targetName);
        if (target == null) {
            plugin.getMessageManager().sendMessage(player, "player_not_found",
                    Placeholder.unparsed("target", targetName));
            return;
        }
        teamManager.promotePlayer(player, target.getUniqueId());
    }

    private void handleDemote(Player player, String[] args) {
        if (!checkFeatureEnabled(player, "member_demote")) {
            return;
        }
        if (args.length < 2) {
            plugin.getMessageManager().sendMessage(player, "usage_demote");
            return;
        }
        String targetName = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        if (targetName.equalsIgnoreCase(player.getName())) {
            plugin.getMessageManager().sendMessage(player, "cannot_demote_yourself");
            return;
        }
        Player target = Bukkit.getPlayer(targetName);
        if (target == null) {
            plugin.getMessageManager().sendMessage(player, "player_not_found",
                    Placeholder.unparsed("target", targetName));
            return;
        }
        teamManager.demotePlayer(player, target.getUniqueId());
    }

    private void handleInfo(Player player, String[] args) {
        if (!checkFeatureEnabled(player, "team_info")) {
            return;
        }
        if (args.length > 1) {
            String teamName = args[1];
            if (teamName.length() < plugin.getConfigManager().getMinNameLength()
                    || teamName.length() > plugin.getConfigManager().getMaxNameLength()
                    || !teamName.matches("^[a-zA-Z0-9_]+$")) {
                plugin.getMessageManager().sendMessage(player, "invalid_team_name");
                return;
            }
            Team team = teamManager.getAllTeams().stream()
                    .filter(t -> t.getName().equalsIgnoreCase(teamName))
                    .findFirst()
                    .orElse(null);
            if (team == null) {
                plugin.getMessageManager().sendMessage(player, "team_not_found");
                return;
            }
            displayTeamInfo(player, team);
        } else {
            Team team = teamManager.getPlayerTeam(player.getUniqueId());
            if (team == null) {
                plugin.getMessageManager().sendMessage(player, "player_not_in_team");
                return;
            }
            displayTeamInfo(player, team);
        }
    }

    private void displayTeamInfo(Player player, Team team) {
        if (player == null || team == null) {
            return;
        }
        String ownerName = Bukkit.getOfflinePlayer(team.getOwnerUuid()).getName();
        String safeOwnerName = ownerName != null ? ownerName : "Unknown";
        String coOwners = team.getCoOwners().stream()
                .map(co -> Bukkit.getOfflinePlayer(co.getPlayerUuid()).getName())
                .filter(Objects::nonNull)
                .collect(Collectors.joining(", "));
        plugin.getMessageManager().sendRawMessage(player, plugin.getMessageManager().getRawMessage("team_info_header"),
                Placeholder.unparsed("team", team.getName()));

        if (plugin.getConfigManager().isTeamTagEnabled()) {
            plugin.getMessageManager().sendRawMessage(player, plugin.getMessageManager().getRawMessage("team_info_tag"),
                    Placeholder.unparsed("tag", team.getTag()));
        }

        plugin.getMessageManager().sendRawMessage(player,
                plugin.getMessageManager().getRawMessage("team_info_description"),
                Placeholder.unparsed("description", team.getDescription()));
        plugin.getMessageManager().sendRawMessage(player, plugin.getMessageManager().getRawMessage("team_info_owner"),
                Placeholder.unparsed("owner", safeOwnerName));
        if (!coOwners.isEmpty()) {
            plugin.getMessageManager().sendRawMessage(player,
                    plugin.getMessageManager().getRawMessage("team_info_co_owners"),
                    Placeholder.unparsed("co_owners", coOwners));
        }
        double kdr = (team.getDeaths() == 0) ? team.getKills() : (double) team.getKills() / team.getDeaths();
        plugin.getMessageManager().sendRawMessage(player, plugin.getMessageManager().getRawMessage("team_info_stats"),
                Placeholder.unparsed("kills", String.valueOf(team.getKills())),
                Placeholder.unparsed("deaths", String.valueOf(team.getDeaths())),
                Placeholder.unparsed("kdr", String.format("%.2f", kdr)));
        plugin.getMessageManager().sendRawMessage(player, plugin.getMessageManager().getRawMessage("team_info_members"),
                Placeholder.unparsed("member_count", String.valueOf(team.getMembers().size())),
                Placeholder.unparsed("max_members", String.valueOf(plugin.getConfigManager().getMaxTeamSize())));
        for (TeamPlayer member : team.getMembers()) {
            String memberName = Bukkit.getOfflinePlayer(member.getPlayerUuid()).getName();
            String safeMemberName = memberName != null ? memberName : "Unknown";
            plugin.getMessageManager().sendRawMessage(player,
                    plugin.getMessageManager().getRawMessage("team_info_member_list"),
                    Placeholder.unparsed("player", safeMemberName));
        }
        plugin.getMessageManager().sendRawMessage(player, plugin.getMessageManager().getRawMessage("team_info_footer"));
    }

    private void handleSetHome(Player player) {
        if (!checkFeatureEnabled(player, "team_home_set")) {
            return;
        }

        if (!plugin.getFeatureRestrictionManager().isFeatureAllowed(player, "sethome")) {
            plugin.getMessageManager().sendMessage(player, "feature_disabled_in_world",
                    Placeholder.unparsed("feature", "sethome"),
                    Placeholder.unparsed("world", player.getWorld().getName()));
            return;
        }

        Team team = teamManager.getPlayerTeam(player.getUniqueId());
        if (team == null) {
            plugin.getMessageManager().sendMessage(player, "player_not_in_team");
            return;
        }
        if (!team.hasElevatedPermissions(player.getUniqueId())) {
            plugin.getMessageManager().sendMessage(player, "must_be_owner_or_co_owner");
            return;
        }

        if (!plugin.getFeatureRestrictionManager().canAffordAndPay(player, "sethome")) {
            return;
        }

        teamManager.setTeamHome(player);
    }

    private void handleDelHome(Player player) {
        if (!checkFeatureEnabled(player, "team_home_set")) {
            return;
        }
        Team team = teamManager.getPlayerTeam(player.getUniqueId());
        if (team == null) {
            plugin.getMessageManager().sendMessage(player, "player_not_in_team");
            return;
        }
        if (!team.hasElevatedPermissions(player.getUniqueId())) {
            plugin.getMessageManager().sendMessage(player, "must_be_owner_or_co_owner");
            return;
        }
        teamManager.deleteTeamHome(player);
    }

    private void handleHome(Player player) {
        if (!checkFeatureEnabled(player, "team_home_teleport")) {
            return;
        }

        if (!plugin.getFeatureRestrictionManager().isFeatureAllowed(player, "home")) {
            plugin.getMessageManager().sendMessage(player, "feature_disabled_in_world",
                    Placeholder.unparsed("feature", "home"),
                    Placeholder.unparsed("world", player.getWorld().getName()));
            return;
        }

        Team team = teamManager.getPlayerTeam(player.getUniqueId());
        if (team == null) {
            plugin.getMessageManager().sendMessage(player, "player_not_in_team");
            return;
        }

        if (!plugin.getFeatureRestrictionManager().canAffordAndPay(player, "home")) {
            return;
        }

        teamManager.teleportToHome(player);
    }

    private void handleSetTag(Player player, String[] args) {
        if (!checkFeatureEnabled(player, "team_tag")) {
            return;
        }
        if (args.length < 2) {
            plugin.getMessageManager().sendMessage(player, "usage_settag");
            return;
        }
        String tag = args[1];
        String plainTag = stripColorCodes(tag);
        if (plainTag.length() < 2 || plainTag.length() > plugin.getConfigManager().getMaxTagLength()
                || !plainTag.matches("^[a-zA-Z0-9_]+$")) {
            plugin.getMessageManager().sendMessage(player, "invalid_team_tag");
            return;
        }
        teamManager.setTeamTag(player, tag);
    }

    private void handleSetDescription(Player player, String[] args) {
        if (!checkFeatureEnabled(player, "team_description")) {
            return;
        }
        if (args.length < 2) {
            plugin.getMessageManager().sendMessage(player, "usage_setdesc");
            return;
        }
        String description = String.join(" ", args).substring(args[0].length() + 1);
        if (description.length() > plugin.getConfigManager().getMaxDescriptionLength()) {
            plugin.getMessageManager().sendMessage(player, "description_too_long");
            return;
        }
        String lowerDesc = description.toLowerCase();
        String[] inappropriate = { "admin", "mod", "staff", "owner", "server", "minecraft", "bukkit", "spigot" };
        for (String word : inappropriate) {
            if (lowerDesc.contains(word)) {
                plugin.getMessageManager().sendMessage(player, "inappropriate_description");
                return;
            }
        }
        teamManager.setTeamDescription(player, description);
    }

    private void handleRename(Player player, String[] args) {
        if (!checkFeatureEnabled(player, "team_rename")) {
            return;
        }

        if (!player.hasPermission("justteams.rename") && !player.hasPermission("justteams.admin")) {
            plugin.getMessageManager().sendMessage(player, "no_permission");
            return;
        }

        if (args.length < 2) {
            plugin.getMessageManager().sendMessage(player, "rename_usage");
            return;
        }

        Team team = teamManager.getPlayerTeam(player.getUniqueId());
        if (team == null) {
            plugin.getMessageManager().sendMessage(player, "not_in_team");
            return;
        }

        if (!team.isOwner(player.getUniqueId())) {
            plugin.getMessageManager().sendMessage(player, "must_be_owner");
            return;
        }

        String newName = args[1];
        String oldName = team.getName();

        if (newName.equalsIgnoreCase(oldName)) {
            plugin.getMessageManager().sendMessage(player, "rename_same_name");
            return;
        }

        String plainName = stripColorCodes(newName);
        int minLength = plugin.getConfigManager().getMinNameLength();
        int maxLength = plugin.getConfigManager().getMaxNameLength();

        if (plainName.length() < minLength) {
            plugin.getMessageManager().sendMessage(player, "name_too_short",
                    Placeholder.unparsed("min_length", String.valueOf(minLength)));
            return;
        }

        if (plainName.length() > maxLength) {
            plugin.getMessageManager().sendMessage(player, "name_too_long",
                    Placeholder.unparsed("max_length", String.valueOf(maxLength)));
            return;
        }

        if (!plainName.matches("^[a-zA-Z0-9_]+$")) {
            plugin.getMessageManager().sendMessage(player, "invalid_team_name");
            return;
        }

        if (teamManager.getTeamByName(newName) != null) {
            plugin.getMessageManager().sendMessage(player, "team_name_exists",
                    Placeholder.unparsed("team", newName));
            return;
        }

        plugin.getTaskRunner().runAsync(() -> {
            Optional<Timestamp> lastRename = plugin.getStorageManager().getStorage()
                    .getTeamRenameTimestamp(team.getId());
            long cooldownSeconds = plugin.getConfig().getLong("settings.rename_cooldown", 604800);

            if (lastRename.isPresent() && cooldownSeconds > 0) {
                long secondsSinceRename = (System.currentTimeMillis() - lastRename.get().getTime()) / 1000;
                long remaining = cooldownSeconds - secondsSinceRename;

                if (remaining > 0) {
                    String timeLeft = formatTime(remaining);
                    plugin.getTaskRunner().runOnEntity(player, () -> {
                        plugin.getMessageManager().sendMessage(player, "rename_cooldown",
                                Placeholder.unparsed("time", timeLeft));
                    });
                    return;
                }
            }

            double cost = plugin.getConfig().getDouble("feature_costs.economy.rename", 500.0);
            boolean economyEnabled = plugin.getConfig().getBoolean("feature_costs.economy.enabled", true);

            if (economyEnabled && cost > 0 && plugin.getEconomy() != null) {
                double balance = plugin.getEconomy().getBalance(player);
                if (balance < cost) {
                    plugin.getTaskRunner().runOnEntity(player, () -> {
                        plugin.getMessageManager().sendMessage(player, "rename_too_expensive",
                                Placeholder.unparsed("cost", String.format("%.2f", cost)),
                                Placeholder.unparsed("balance", String.format("%.2f", balance)));
                    });
                    return;
                }

                plugin.getEconomy().withdrawPlayer(player, cost);
            }

            team.setName(newName);
            plugin.getStorageManager().getStorage().setTeamName(team.getId(), newName);
            plugin.getStorageManager().getStorage().setTeamRenameTimestamp(team.getId(),
                    new Timestamp(System.currentTimeMillis()));

            plugin.getWebhookHelper().sendTeamRenameWebhook(player.getName(), oldName, newName);

            plugin.getTaskRunner().runAsync(() -> {
                if (plugin.getRedisManager() != null && plugin.getRedisManager().isAvailable()) {
                    plugin.getRedisManager().publishTeamUpdate(team.getId(), "TEAM_RENAMED",
                            player.getUniqueId().toString(), oldName + "|" + newName);
                }

                plugin.getStorageManager().getStorage().addCrossServerUpdate(team.getId(), "TEAM_RENAMED",
                        player.getUniqueId().toString(), "ALL_SERVERS");
            });

            plugin.getTaskRunner().runOnEntity(player, () -> {
                plugin.getMessageManager().sendMessage(player, "rename_success",
                        Placeholder.unparsed("old_name", oldName),
                        Placeholder.unparsed("new_name", newName));

                team.broadcast("rename_broadcast",
                        Placeholder.unparsed("old_name", oldName),
                        Placeholder.unparsed("new_name", newName));

                teamManager.refreshTeamGUIsForAllMembers(team);
            });
        });
    }

    private String formatTime(long seconds) {
        if (seconds < 60) {
            return seconds + " second" + (seconds != 1 ? "s" : "");
        } else if (seconds < 3600) {
            long minutes = seconds / 60;
            return minutes + " minute" + (minutes != 1 ? "s" : "");
        } else if (seconds < 86400) {
            long hours = seconds / 3600;
            return hours + " hour" + (hours != 1 ? "s" : "");
        } else {
            long days = seconds / 86400;
            return days + " day" + (days != 1 ? "s" : "");
        }
    }

    private void handleTransfer(Player player, String[] args) {
        if (!checkFeatureEnabled(player, "team_transfer")) {
            return;
        }
        if (args.length < 2) {
            plugin.getMessageManager().sendMessage(player, "usage_transfer");
            return;
        }
        String targetName = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        if (targetName.equalsIgnoreCase(player.getName())) {
            plugin.getMessageManager().sendMessage(player, "cannot_transfer_to_yourself");
            return;
        }
        Player target = Bukkit.getPlayer(targetName);
        if (target == null) {
            plugin.getMessageManager().sendMessage(player, "player_not_found",
                    Placeholder.unparsed("target", targetName));
            return;
        }
        Team playerTeam = teamManager.getPlayerTeam(player.getUniqueId());
        Team targetTeam = teamManager.getPlayerTeam(target.getUniqueId());
        if (playerTeam == null || targetTeam == null || playerTeam.getId() != targetTeam.getId()) {
            plugin.getMessageManager().sendMessage(player, "player_not_in_same_team");
            return;
        }
        teamManager.transferOwnership(player, target.getUniqueId());
    }

    private void handlePvpToggle(Player player) {
        if (!checkFeatureEnabled(player, "team_pvp_toggle")) {
            return;
        }
        Team team = teamManager.getPlayerTeam(player.getUniqueId());
        if (team == null) {
            plugin.getMessageManager().sendMessage(player, "player_not_in_team");
            return;
        }
        if (!team.hasElevatedPermissions(player.getUniqueId())) {
            plugin.getMessageManager().sendMessage(player, "must_be_owner_or_co_owner");
            return;
        }
        teamManager.togglePvp(player);
    }

    private void handleBank(Player player, String[] args) {
        if (!checkFeatureEnabled(player, "team_bank")) {
            return;
        }
        if (args.length < 2) {
            Team team = teamManager.getPlayerTeam(player.getUniqueId());
            if (team == null) {
                plugin.getMessageManager().sendMessage(player, "player_not_in_team");
                return;
            }
            new TeamGUI(plugin, team, player).open();
            return;
        }
        String action = args[1].toLowerCase();
        if (action.equals("deposit") || action.equals("withdraw")) {
            if (args.length < 3) {
                plugin.getMessageManager().sendMessage(player, "usage_bank");
                return;
            }
            try {
                double amount = Double.parseDouble(args[2]);
                if (amount <= 0) {
                    plugin.getMessageManager().sendMessage(player, "bank_invalid_amount");
                    return;
                }
                if (amount > 1_000_000_000) {
                    plugin.getMessageManager().sendMessage(player, "bank_amount_too_large");
                    return;
                }
                if (action.equals("deposit")) {
                    teamManager.deposit(player, amount);
                } else {
                    teamManager.withdraw(player, amount);
                }
            } catch (NumberFormatException e) {
                plugin.getMessageManager().sendMessage(player, "bank_invalid_amount");
            }
        } else {
            plugin.getMessageManager().sendMessage(player, "usage_bank");
        }
    }

    private void handleEnderChest(Player player) {
        if (!checkFeatureEnabled(player, "team_enderchest")) {
            return;
        }

        if (!plugin.getFeatureRestrictionManager().isFeatureAllowed(player, "enderchest")) {
            plugin.getMessageManager().sendMessage(player, "feature_disabled_in_world",
                    Placeholder.unparsed("feature", "enderchest"),
                    Placeholder.unparsed("world", player.getWorld().getName()));
            return;
        }

        Team team = teamManager.getPlayerTeam(player.getUniqueId());
        if (team == null) {
            plugin.getMessageManager().sendMessage(player, "player_not_in_team");
            return;
        }

        if (!plugin.getFeatureRestrictionManager().canAffordAndPay(player, "enderchest")) {
            return;
        }

        teamManager.openEnderChest(player);
    }

    private void handlePublicToggle(Player player) {
        if (!checkFeatureEnabled(player, "team_public_toggle")) {
            return;
        }
        Team team = teamManager.getPlayerTeam(player.getUniqueId());
        if (team == null) {
            plugin.getMessageManager().sendMessage(player, "player_not_in_team");
            return;
        }
        if (!team.hasElevatedPermissions(player.getUniqueId())) {
            plugin.getMessageManager().sendMessage(player, "must_be_owner_or_co_owner");
            return;
        }
        teamManager.togglePublicStatus(player);
    }

    private void handleToggleGlow(Player player) {
        if (!checkFeatureEnabled(player, "team_glow")) {
            return;
        }
        Team team = teamManager.getPlayerTeam(player.getUniqueId());
        if (team == null) {
            plugin.getMessageManager().sendMessage(player, "player_not_in_team");
            return;
        }
        if (!team.hasElevatedPermissions(player.getUniqueId())) {
            plugin.getMessageManager().sendMessage(player, "must_be_owner_or_co_owner");
            return;
        }
        teamManager.toggleGlow(player);
    }

    private void handleRequests(Player player) {
        if (!checkFeatureEnabled(player, "team_join_requests")) {
            return;
        }
        Team team = teamManager.getPlayerTeam(player.getUniqueId());
        if (team == null) {
            plugin.getMessageManager().sendMessage(player, "player_not_in_team");
            return;
        }
        if (!team.hasElevatedPermissions(player.getUniqueId())) {
            plugin.getMessageManager().sendMessage(player, "must_be_owner_or_co_owner");
            return;
        }
        new JoinRequestGUI(plugin, player, team).open();
    }

    private void handleSetWarp(Player player, String[] args) {
        if (!checkFeatureEnabled(player, "team_warp_set")) {
            return;
        }
        if (!plugin.getFeatureRestrictionManager().isFeatureAllowed(player, "setwarp")) {
            plugin.getMessageManager().sendMessage(player, "feature_disabled_in_world",
                    Placeholder.unparsed("feature", "setwarp"),
                    Placeholder.unparsed("world", player.getWorld().getName()));
            return;
        }

        if (args.length < 2) {
            plugin.getMessageManager().sendMessage(player, "usage_setwarp");
            return;
        }
        String warpName = args[1];
        if (warpName.length() < 2 || warpName.length() > plugin.getConfigManager().getMaxNameLength()
                || !warpName.matches("^[a-zA-Z0-9_]+$")) {
            plugin.getMessageManager().sendMessage(player, "invalid_warp_name");
            return;
        }
        String password = args.length > 2 ? args[2] : null;
        if (password != null && (password.length() < 3 || password.length() > 20)) {
            plugin.getMessageManager().sendMessage(player, "invalid_warp_password");
            return;
        }

        if (!plugin.getFeatureRestrictionManager().canAffordAndPay(player, "setwarp")) {
            return;
        }

        teamManager.setTeamWarp(player, warpName, password);
    }

    private void handleDelWarp(Player player, String[] args) {
        if (!checkFeatureEnabled(player, "team_warp_delete")) {
            return;
        }
        if (args.length < 2) {
            plugin.getMessageManager().sendMessage(player, "usage_delwarp");
            return;
        }
        String warpName = args[1];
        if (warpName.length() < 2 || warpName.length() > plugin.getConfigManager().getMaxNameLength()
                || !warpName.matches("^[a-zA-Z0-9_]+$")) {
            plugin.getMessageManager().sendMessage(player, "invalid_warp_name");
            return;
        }
        teamManager.deleteTeamWarp(player, warpName);
    }

    private void handleWarp(Player player, String[] args) {
        if (!checkFeatureEnabled(player, "team_warp_teleport")) {
            return;
        }

        if (!plugin.getFeatureRestrictionManager().isFeatureAllowed(player, "warp")) {
            plugin.getMessageManager().sendMessage(player, "feature_disabled_in_world",
                    Placeholder.unparsed("feature", "warp"),
                    Placeholder.unparsed("world", player.getWorld().getName()));
            return;
        }

        if (args.length < 2) {
            plugin.getMessageManager().sendMessage(player, "usage_warp");
            return;
        }
        String warpName = args[1];
        if (warpName.length() < 2 || warpName.length() > plugin.getConfigManager().getMaxNameLength()
                || !warpName.matches("^[a-zA-Z0-9_]+$")) {
            plugin.getMessageManager().sendMessage(player, "invalid_warp_name");
            return;
        }
        String password = args.length > 2 ? args[2] : null;

        if (!plugin.getFeatureRestrictionManager().canAffordAndPay(player, "warp")) {
            return;
        }

        teamManager.teleportToTeamWarp(player, warpName, password);
    }

    private void handleWarps(Player player) {
        if (!checkFeatureEnabled(player, "team_warps")) {
            return;
        }
        try {
            Class.forName("eu.kotori.justTeams.gui.WarpsGUI");
            teamManager.openWarpsGUI(player);
        } catch (ClassNotFoundException e) {
            teamManager.listTeamWarps(player);
        }
    }

    private void handleChat(Player player) {
        if (!checkFeatureEnabled(player, "team_chat")) {
            return;
        }
        plugin.getTeamChatListener().toggleTeamChat(player);
    }

    private void handleChatSpy(Player player) {
        if (!player.hasPermission("justteams.chatspy")) {
            plugin.getMessageManager().sendMessage(player, "no_permission");
            return;
        }
        plugin.getTeamChatListener().toggleChatSpy(player);
    }

    private void handleHelp(Player player) {
        plugin.getMessageManager().sendMessage(player, "help_header");

        sendHelpEntry(player, "gui", "gui", "Opens the team GUI.");

        if (plugin.getConfigManager().isTeamTagEnabled()) {
            sendHelpEntry(player, "create", "create <name> <tag>", "Creates a team.");
        } else {
            if (plugin.getMessageManager().hasMessage("help_entries.create_no_tag")) {
                sendHelpEntry(player, "create_no_tag", "create <name>", "Creates a team.");
            } else {
                sendHelpEntry(player, "create", "create <name>", "Creates a team.");
            }
        }

        sendHelpEntry(player, "disband", "disband", "Disbands your team.");
        sendHelpEntry(player, "invite", "invite <player>", "Invites a player.");
        sendHelpEntry(player, "join", "join <teamName>", "Joins a public team.");
        sendHelpEntry(player, "unjoin", "unjoin <teamName>", "Cancels a join request to a team.");
        sendHelpEntry(player, "kick", "kick <player>", "Kicks a player.");
        sendHelpEntry(player, "leave", "leave", "Leaves your current team.");
        sendHelpEntry(player, "promote", "promote <player>", "Promotes a member to Co-Owner.");
        sendHelpEntry(player, "demote", "demote <player>", "Demotes a Co-Owner to Member.");
        sendHelpEntry(player, "info", "info [team]", "Shows team info.");
        sendHelpEntry(player, "sethome", "sethome", "Sets the team home.");
        sendHelpEntry(player, "delhome", "delhome", "Deletes the team home.");
        sendHelpEntry(player, "home", "home", "Teleports to the team home.");

        if (plugin.getConfigManager().isTeamTagEnabled()) {
            sendHelpEntry(player, "settag", "settag <tag>", "Changes the team tag.");
        }

        sendHelpEntry(player, "setdesc", "setdesc <description>", "Changes the team description.");

        if (plugin.getConfig().getBoolean("features.team_rename", true)) {
            sendHelpEntry(player, "rename", "rename <newName>", "Renames the team (cooldown applies).");
        }

        sendHelpEntry(player, "transfer", "transfer <player>", "Transfers ownership.");
        sendHelpEntry(player, "pvp", "pvp", "Toggles team PvP.");
        sendHelpEntry(player, "bank", "bank [deposit|withdraw] [amount]", "Manages the team bank.");
        sendHelpEntry(player, "enderchest", "enderchest", "Opens the team ender chest.");
        sendHelpEntry(player, "public", "public", "Toggles public join status.");
        sendHelpEntry(player, "requests", "requests", "View join requests.");
        sendHelpEntry(player, "setwarp", "setwarp <name> [password]", "Sets a team warp.");
        sendHelpEntry(player, "delwarp", "delwarp <name>", "Deletes a team warp.");
        sendHelpEntry(player, "warp", "warp <name> [password]", "Teleports to a team warp.");
        sendHelpEntry(player, "warps", "warps", "Lists all team warps.");
        sendHelpEntry(player, "top", "top", "Shows team leaderboards.");
        sendHelpEntry(player, "blacklist", "blacklist <player> [reason]", "Blacklists a player from your team.");
        sendHelpEntry(player, "unblacklist", "unblacklist <player>", "Unblacklists a player from your team.");

        if (player.hasPermission("justteams.admin.disband")) {
            sendHelpEntry(player, "admin_disband", "admin disband <teamName>", "Admin command to disband a team.");
        }

        sendHelpEntry(player, "platform", "platform", "Shows your platform information (Java/Bedrock).");

        if (player.hasPermission("justteams.admin.debug")) {
            sendHelpEntry(player, "debug_permissions", "debug-permissions",
                    "Debugs the current permissions of your team.");
            sendHelpEntry(player, "debug_placeholders", "debug-placeholders",
                    "Tests all PlaceholderAPI placeholders for your team.");
        }
    }

    private void sendHelpEntry(Player player, String key, String defaultUsage, String defaultDesc) {
        if (!plugin.getMessageManager().hasMessage("help_entries." + key)) {
            return;
        }

        String entry = plugin.getMessageManager().getRawMessage("help_entries." + key);
        String usage = defaultUsage;
        String description = defaultDesc;

        if (entry.contains(" - ")) {
            String[] parts = entry.split(" - ", 2);
            usage = parts[0];
            description = parts[1];
        } else {
            description = entry;
        }

        plugin.getMessageManager().sendMessage(player, "help_format",
                net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("command", usage),
                net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("description", description));
    }

    private void handleReload(CommandSender sender) {
        if (sender instanceof Player player && !hasAdminPermission(player)) {
            plugin.getMessageManager().sendMessage(sender, "no_permission");
            return;
        }
        try {
            plugin.getLogger().info("Reloading JustTeams configuration...");
            plugin.getConfigManager().reloadConfig();
            plugin.getMessageManager().reload();
            plugin.getGuiConfigManager().reload();
            plugin.getCommandManager().reload();
            plugin.getAliasManager().reload();
            plugin.getGuiConfigManager().testPlaceholders();

            plugin.getMessageManager().sendMessage(sender, "reload");
            if (sender instanceof Player) {
                plugin.getMessageManager().sendMessage(sender, "reload_commands_notice");
            }
            plugin.getLogger().info("✓ JustTeams configuration reloaded successfully!");
        } catch (Exception e) {
            plugin.getLogger().severe("✗ Failed to reload configuration: " + e.getMessage());
            e.printStackTrace();
            sender.sendMessage("§c✗ Failed to reload configuration. Check console for details.");
        }
    }

    private void handleBlacklist(Player player, String[] args) {
        if (!checkFeatureEnabled(player, "team_blacklist")) {
            return;
        }
        Team team = teamManager.getPlayerTeam(player.getUniqueId());
        if (team == null) {
            plugin.getMessageManager().sendMessage(player, "not_in_team");
            return;
        }
        if (!team.hasElevatedPermissions(player.getUniqueId())) {
            plugin.getMessageManager().sendMessage(player, "must_be_owner_or_co_owner");
            return;
        }
        if (args.length == 1) {
            new BlacklistGUI(plugin, team, player).open();
            return;
        }
        if (args.length < 2) {
            plugin.getMessageManager().sendMessage(player, "usage_blacklist");
            return;
        }
        String targetName = args[1];
        Player target = Bukkit.getPlayer(targetName);
        if (target == null) {
            plugin.getMessageManager().sendMessage(player, "player_not_found",
                    Placeholder.unparsed("target", targetName));
            return;
        }
        if (target.getUniqueId().equals(player.getUniqueId())) {
            plugin.getMessageManager().sendMessage(player, "cannot_blacklist_self");
            return;
        }
        if (team.isMember(target.getUniqueId())) {
            plugin.getMessageManager().sendMessage(player, "cannot_blacklist_team_member");
            return;
        }
        plugin.getTaskRunner().runAsync(() -> {
            try {
                if (plugin.getStorageManager().getStorage().isPlayerBlacklisted(team.getId(), target.getUniqueId())) {
                    List<BlacklistedPlayer> blacklist = plugin.getStorageManager().getStorage()
                            .getTeamBlacklist(team.getId());
                    BlacklistedPlayer blacklistedPlayer = blacklist.stream()
                            .filter(bp -> bp.getPlayerUuid().equals(target.getUniqueId()))
                            .findFirst()
                            .orElse(null);
                    String blacklisterName = blacklistedPlayer != null ? blacklistedPlayer.getBlacklistedByName()
                            : "Unknown";
                    plugin.getTaskRunner().runOnEntity(player, () -> {
                        plugin.getMessageManager().sendMessage(player, "player_already_blacklisted",
                                Placeholder.unparsed("target", target.getName()),
                                Placeholder.unparsed("blacklister", blacklisterName));
                    });
                    return;
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Could not check if player is already blacklisted: " + e.getMessage());
            }
        });
        String reason = args.length > 2 ? String.join(" ", Arrays.copyOfRange(args, 2, args.length))
                : "No reason specified";
        plugin.getTaskRunner().runAsync(() -> {
            try {
                boolean success = plugin.getStorageManager().getStorage().addPlayerToBlacklist(
                        team.getId(), target.getUniqueId(), target.getName(), reason,
                        player.getUniqueId(), player.getName());
                if (success) {
                    plugin.getTaskRunner().runOnEntity(player, () -> {
                        try {
                            plugin.getMessageManager().sendMessage(player, "player_blacklisted",
                                    Placeholder.unparsed("target", target.getName()));
                        } catch (Exception e) {
                            plugin.getLogger().severe("Error sending blacklist success message: " + e.getMessage());
                        }
                    });
                } else {
                    plugin.getTaskRunner().runOnEntity(player, () -> {
                        try {
                            plugin.getMessageManager().sendMessage(player, "blacklist_failed");
                        } catch (Exception e) {
                            plugin.getLogger().severe("Error sending blacklist failed message: " + e.getMessage());
                        }
                    });
                }
            } catch (Exception e) {
                plugin.getLogger().severe("Error adding player to blacklist: " + e.getMessage());
                plugin.getTaskRunner().runOnEntity(player, () -> {
                    try {
                        plugin.getMessageManager().sendMessage(player, "blacklist_failed");
                    } catch (Exception e2) {
                        plugin.getLogger().severe("Error sending blacklist error message: " + e2.getMessage());
                    }
                });
            }
        });
    }

    private void handleUnblacklist(Player player, String[] args) {
        if (!checkFeatureEnabled(player, "team_blacklist")) {
            return;
        }
        Team team = teamManager.getPlayerTeam(player.getUniqueId());
        if (team == null) {
            plugin.getMessageManager().sendMessage(player, "not_in_team");
            return;
        }
        if (!team.hasElevatedPermissions(player.getUniqueId())) {
            plugin.getMessageManager().sendMessage(player, "must_be_owner_or_co_owner");
            return;
        }
        if (args.length < 2) {
            plugin.getMessageManager().sendMessage(player, "usage_unblacklist");
            return;
        }
        String targetName = String.join(" ", Arrays.copyOfRange(args, 1, args.length));
        Player target = Bukkit.getPlayer(targetName);
        if (target == null) {
            plugin.getMessageManager().sendMessage(player, "player_not_found",
                    Placeholder.unparsed("target", targetName));
            return;
        }
        if (target.getUniqueId().equals(player.getUniqueId())) {
            plugin.getMessageManager().sendMessage(player, "cannot_unblacklist_self");
            return;
        }
        plugin.getTaskRunner().runAsync(() -> {
            try {
                if (!plugin.getStorageManager().getStorage().isPlayerBlacklisted(team.getId(), target.getUniqueId())) {
                    plugin.getTaskRunner().runOnEntity(player, () -> {
                        plugin.getMessageManager().sendMessage(player, "player_not_blacklisted",
                                Placeholder.unparsed("target", target.getName()));
                    });
                    return;
                }
                boolean success = plugin.getStorageManager().getStorage().removePlayerFromBlacklist(
                        team.getId(), target.getUniqueId());
                if (success) {
                    plugin.getTaskRunner().runOnEntity(player, () -> {
                        try {
                            plugin.getMessageManager().sendMessage(player, "player_unblacklisted",
                                    Placeholder.unparsed("target", target.getName()));
                        } catch (Exception e) {
                            plugin.getLogger().severe("Error sending unblacklist success message: " + e.getMessage());
                        }
                    });
                } else {
                    plugin.getTaskRunner().runOnEntity(player, () -> {
                        try {
                            plugin.getMessageManager().sendMessage(player, "unblacklist_failed");
                        } catch (Exception e) {
                            plugin.getLogger().severe("Error sending unblacklist failed message: " + e.getMessage());
                        }
                    });
                }
            } catch (Exception e) {
                plugin.getLogger().severe("Error removing player from blacklist: " + e.getMessage());
                plugin.getTaskRunner().runOnEntity(player, () -> {
                    try {
                        plugin.getMessageManager().sendMessage(player, "unblacklist_failed");
                    } catch (Exception e2) {
                        plugin.getLogger().severe("Error sending unblacklist error message: " + e2.getMessage());
                    }
                });
            }
        });
    }

    private void handleSettings(Player player) {
        Team team = teamManager.getPlayerTeam(player.getUniqueId());
        if (team == null) {
            plugin.getMessageManager().sendMessage(player, "player_not_in_team");
            return;
        }
        if (!team.hasElevatedPermissions(player.getUniqueId())) {
            plugin.getMessageManager().sendMessage(player, "settings_permission_denied");
            return;
        }
        new TeamSettingsGUI(plugin, player, team).open();
    }

    private void handleTop(Player player, String[] args) {
        if (!checkFeatureEnabled(player, "team_leaderboard")) {
            return;
        }
        try {
            Class.forName("eu.kotori.justTeams.gui.LeaderboardCategoryGUI");
            new eu.kotori.justTeams.gui.LeaderboardCategoryGUI(plugin, player).open();
        } catch (ClassNotFoundException e) {
            plugin.getTaskRunner().runAsync(() -> {
                try {
                    Map<Integer, Team> topTeams = plugin.getStorageManager().getStorage().getTopTeamsByKills(10);
                    plugin.getTaskRunner().runOnEntity(player, () -> {
                        plugin.getMessageManager().sendMessage(player, "leaderboard_header");
                        for (Map.Entry<Integer, Team> entry : topTeams.entrySet()) {
                            Team team = entry.getValue();
                            plugin.getMessageManager().sendRawMessage(player,
                                    plugin.getMessageManager().getRawMessage("leaderboard_entry"),
                                    Placeholder.unparsed("rank", String.valueOf(entry.getKey())),
                                    Placeholder.unparsed("team", team.getName()),
                                    Placeholder.unparsed("score", String.valueOf(team.getKills())));
                        }
                        plugin.getMessageManager().sendMessage(player, "leaderboard_footer");
                    });
                } catch (Exception ex) {
                    plugin.getLogger().severe("Error loading top teams: " + ex.getMessage());
                    plugin.getTaskRunner().runOnEntity(player, () -> {
                        plugin.getMessageManager().sendMessage(player, "error_loading_leaderboard");
                    });
                }
            });
        }
    }

    private void handleUnjoin(Player player, String[] args) {
        if (args.length < 2) {
            plugin.getMessageManager().sendMessage(player, "usage_unjoin");
            return;
        }
        String teamName = args[1];
        if (teamName.length() < plugin.getConfigManager().getMinNameLength()
                || teamName.length() > plugin.getConfigManager().getMaxNameLength()
                || !teamName.matches("^[a-zA-Z0-9_]+$")) {
            plugin.getMessageManager().sendMessage(player, "invalid_team_name");
            return;
        }
        if (teamManager.getPlayerTeam(player.getUniqueId()) != null) {
            plugin.getMessageManager().sendMessage(player, "already_in_team");
            return;
        }
        teamManager.withdrawJoinRequest(player, teamName);
    }

    private void handleAdmin(Player player, String[] args) {
        if (!hasAdminPermission(player)) {
            return;
        }

        if (args.length < 2 || args[1].equalsIgnoreCase("gui")) {
            new eu.kotori.justTeams.gui.admin.AdminGUI(plugin, player).open();
            return;
        }

        String action = args[1].toLowerCase();
        switch (action) {
            case "disband" -> {
                if (args.length < 3) {
                    plugin.getMessageManager().sendMessage(player, "usage_admin_disband");
                    return;
                }
                String teamName = args[2];
                teamManager.adminDisbandTeam(player, teamName);
            }
            case "enderchest" -> {
                if (args.length < 3) {
                    plugin.getMessageManager().sendMessage(player, "usage_admin_enderchest");
                    return;
                }
                String teamName = args[2];
                teamManager.adminOpenEnderChest(player, teamName);
            }
            case "home" -> {
                if (args.length < 3) {
                    plugin.getMessageManager().sendMessage(player, "usage_admin_home");
                    return;
                }
                String teamName = args[2];
                teamManager.adminTeleportTeamHome(player, teamName);
            }
            case "testmigration" -> handleTestMigration(player, args);
            case "performance" -> handlePerformance(player, args);
            default -> {
                player.sendMessage(
                        "§cUsage: /team admin <gui|disband|home|testmigration|enderchest|performance> [args]");
            }
        }
    }

    private void handleServerAlias(Player player, String[] args) {
        if (!hasAdminPermission(player)) {
            plugin.getMessageManager().sendMessage(player, "no_permission");
            return;
        }

        if (args.length < 2) {
            plugin.getMessageManager().sendMessage(player, "usage_serveralias");
            return;
        }

        String action = args[1].toLowerCase();

        switch (action) {
            case "set" -> {
                if (args.length < 4) {
                    plugin.getMessageManager().sendMessage(player, "usage_serveralias");
                    return;
                }

                String serverName = args[2];
                String alias = String.join(" ", Arrays.copyOfRange(args, 3, args.length));

                if (alias.length() > 64) {
                    player.sendMessage("§cServer alias too long! Maximum 64 characters.");
                    return;
                }

                plugin.getTaskRunner().runAsync(() -> {
                    plugin.getStorageManager().getStorage().setServerAlias(serverName, alias);

                    plugin.getTaskRunner().runOnEntity(player, () -> {
                        plugin.getMessageManager().sendMessage(player, "serveralias_set",
                                Placeholder.unparsed("server", serverName),
                                Placeholder.unparsed("alias", alias));
                    });
                });
            }

            case "remove" -> {
                if (args.length < 3) {
                    plugin.getMessageManager().sendMessage(player, "usage_serveralias");
                    return;
                }

                String serverName = args[2];

                plugin.getTaskRunner().runAsync(() -> {
                    plugin.getStorageManager().getStorage().removeServerAlias(serverName);

                    plugin.getTaskRunner().runOnEntity(player, () -> {
                        plugin.getMessageManager().sendMessage(player, "serveralias_removed",
                                Placeholder.unparsed("server", serverName));
                    });
                });
            }

            case "list" -> {
                plugin.getTaskRunner().runAsync(() -> {
                    Map<String, String> aliases = plugin.getStorageManager().getStorage().getAllServerAliases();

                    plugin.getTaskRunner().runOnEntity(player, () -> {
                        if (aliases.isEmpty()) {
                            player.sendMessage("§eNo server aliases configured.");
                            return;
                        }

                        plugin.getMessageManager().sendMessage(player, "serveralias_list_header");
                        for (Map.Entry<String, String> entry : aliases.entrySet()) {
                            plugin.getMessageManager().sendMessage(player, "serveralias_list_entry",
                                    Placeholder.unparsed("server", entry.getKey()),
                                    Placeholder.unparsed("alias", entry.getValue()));
                        }
                    });
                });
            }

            default -> plugin.getMessageManager().sendMessage(player, "usage_serveralias");
        }
    }

    private boolean hasAdminPermission(Player player) {
        return player.isOp() ||
                player.hasPermission("*") ||
                player.hasPermission("justteams.admin");
    }

    private void handleTestMigration(Player player, String[] args) {
        if (args.length == 2) {
            player.sendMessage("§eTesting database migration system...");
            try {
                DatabaseFileManager fileManager = new DatabaseFileManager(plugin);
                boolean fileMigrationResult = fileManager.migrateOldDatabaseFiles();
                player.sendMessage("§aFile migration result: " + (fileMigrationResult ? "SUCCESS" : "FAILED"));
                boolean backupResult = fileManager.backupDatabase();
                player.sendMessage("§aBackup creation result: " + (backupResult ? "SUCCESS" : "FAILED"));
                boolean validationResult = fileManager.validateDatabaseFiles();
                player.sendMessage("§aFile validation result: " + (validationResult ? "SUCCESS" : "FAILED"));
                DatabaseMigrationManager migrationManager = new DatabaseMigrationManager(plugin,
                        (DatabaseStorage) plugin.getStorageManager().getStorage());
                boolean migrationResult = migrationManager.performMigration();
                player.sendMessage("§aSchema migration result: " + (migrationResult ? "SUCCESS" : "FAILED"));
                boolean configHealthy = ConfigUpdater.isConfigurationSystemHealthy(plugin);
                player.sendMessage("§aConfiguration system health: " + (configHealthy ? "HEALTHY" : "UNHEALTHY"));
                if (fileMigrationResult && migrationResult && configHealthy) {
                    player.sendMessage(
                            "§aAll migration tests passed! Database and configuration should be working correctly.");
                } else {
                    player.sendMessage("§cSome migration tests failed. Check the console for details.");
                }
            } catch (Exception e) {
                player.sendMessage("§cMigration test failed with exception: " + e.getMessage());
                plugin.getLogger().severe("Migration test failed: " + e.getMessage());
            }
        } else {
            String action = args[2].toLowerCase();
            try {
                DatabaseFileManager fileManager = new DatabaseFileManager(plugin);
                DatabaseMigrationManager migrationManager = new DatabaseMigrationManager(plugin,
                        (DatabaseStorage) plugin.getStorageManager().getStorage());
                switch (action) {
                    case "test":
                        player.sendMessage("§eRunning full migration test...");
                        boolean fileResult = fileManager.migrateOldDatabaseFiles();
                        boolean backupResult = fileManager.backupDatabase();
                        boolean validationResult = fileManager.validateDatabaseFiles();
                        boolean migrationResult = migrationManager.performMigration();
                        player.sendMessage("§aFile migration: " + (fileResult ? "SUCCESS" : "FAILED"));
                        player.sendMessage("§aBackup creation: " + (backupResult ? "SUCCESS" : "FAILED"));
                        player.sendMessage("§aFile validation: " + (validationResult ? "SUCCESS" : "FAILED"));
                        player.sendMessage("§aSchema migration: " + (migrationResult ? "SUCCESS" : "FAILED"));
                        break;
                    case "migrate":
                        player.sendMessage("§eRunning database migration...");
                        boolean migrateResult = migrationManager.performMigration();
                        player.sendMessage("§aMigration result: " + (migrateResult ? "SUCCESS" : "FAILED"));
                        break;
                    case "validate":
                        player.sendMessage("§eValidating database files...");
                        boolean validateResult = fileManager.validateDatabaseFiles();
                        player.sendMessage("§aValidation result: " + (validateResult ? "SUCCESS" : "FAILED"));
                        break;
                    case "backup":
                        player.sendMessage("§eCreating database backup...");
                        boolean backupResult2 = fileManager.backupDatabase();
                        player.sendMessage("§aBackup result: " + (backupResult2 ? "SUCCESS" : "FAILED"));
                        break;
                    case "config":
                        player.sendMessage("§eTesting configuration system...");
                        ConfigUpdater.testConfigurationSystem(plugin);
                        boolean configHealthy = ConfigUpdater.isConfigurationSystemHealthy(plugin);
                        player.sendMessage(
                                "§aConfiguration system health: " + (configHealthy ? "HEALTHY" : "UNHEALTHY"));
                        break;
                    case "update-config":
                        player.sendMessage("§eUpdating configuration files...");
                        ConfigUpdater.updateAllConfigs(plugin);
                        player.sendMessage("§aConfiguration update completed! Check console for details.");
                        break;
                    case "force-update-config":
                        player.sendMessage("§eForce updating all configuration files...");
                        ConfigUpdater.forceUpdateAllConfigs(plugin);
                        player.sendMessage("§aForce update completed! Check console for details.");
                        break;
                    case "backup-config":
                        player.sendMessage("§eCreating configuration backups...");
                        for (String configFile : List.of("config.yml", "messages.yml", "gui.yml", "commands.yml")) {
                            ConfigUpdater.createConfigBackup(plugin, configFile);
                        }
                        player.sendMessage("§aConfiguration backups created! Check backups folder.");
                        break;
                    case "cleanup-backups":
                        player.sendMessage("§eCleaning up old backup files...");
                        ConfigUpdater.cleanupAllOldBackups(plugin);
                        player.sendMessage("§aBackup cleanup completed! Check console for details.");
                        break;
                    default:
                        player.sendMessage("§cUnknown action: " + action);
                        player.sendMessage(
                                "§7Available actions: test, migrate, validate, backup, config, update-config, force-update-config, backup-config, cleanup-backups");
                        break;
                }
            } catch (Exception e) {
                player.sendMessage("§cCommand failed with exception: " + e.getMessage());
                plugin.getLogger().severe("TestMigrationCommand failed: " + e.getMessage());
            }
        }
    }

    private void handlePerformance(Player player, String[] args) {
        if (!player.hasPermission("justteams.admin.performance")) {
            plugin.getMessageManager().sendMessage(player, "no_permission");
            return;
        }

        if (args.length < 3) {
            showPerformanceHelp(player);
            return;
        }

        String action = args[2].toLowerCase();
        switch (action) {
            case "database" -> showDatabaseStats(player);
            case "cache" -> showCacheStats(player);
            case "tasks" -> showTaskStats(player);
            case "optimize" -> optimizeDatabase(player);
            case "cleanup" -> cleanupCaches(player);
            default -> showPerformanceHelp(player);
        }
    }

    private void showPerformanceHelp(Player player) {
        player.sendMessage("§6=== JustTeams Performance Commands ===");
        player.sendMessage("§e/team admin performance database §7- Show database statistics");
        player.sendMessage("§e/team admin performance cache §7- Show cache statistics");
        player.sendMessage("§e/team admin performance tasks §7- Show task statistics");
        player.sendMessage("§e/team admin performance optimize §7- Optimize database");
        player.sendMessage("§e/team admin performance cleanup §7- Cleanup caches");
    }

    private void showDatabaseStats(Player player) {
        player.sendMessage("§6=== Database Statistics ===");

        if (plugin.getStorageManager().getStorage() instanceof DatabaseStorage dbStorage) {
            try {
                Map<String, Object> stats = dbStorage.getDatabaseStats();
                stats.forEach((key, value) -> player.sendMessage("§e" + key + ": §f" + value));
            } catch (Exception e) {
                player.sendMessage("§cError retrieving database stats: " + e.getMessage());
            }
        } else {
            player.sendMessage("§cDatabase storage not in use");
        }
    }

    private void showCacheStats(Player player) {
        player.sendMessage("§6=== Cache Statistics ===");

        try {
            if (plugin.getTeamManager() != null) {
                player.sendMessage("§eTeam Cache: §f" + plugin.getTeamManager().getTeamNameCache().size() + " teams");
                player.sendMessage(
                        "§ePlayer Cache: §f" + plugin.getTeamManager().getPlayerTeamCache().size() + " players");
            }

            player.sendMessage("§eGUI Update Throttle: §aActive");
            player.sendMessage("§eTask Runner: §f" + plugin.getTaskRunner().getActiveTaskCount() + " active tasks");
        } catch (Exception e) {
            player.sendMessage("§cError retrieving cache statistics: " + e.getMessage());
        }
    }

    private void showTaskStats(Player player) {
        player.sendMessage("§6=== Task Statistics ===");
        player.sendMessage("§eActive Tasks: §f" + plugin.getTaskRunner().getActiveTaskCount());
        player.sendMessage("§eFolia Support: §f" + (plugin.getTaskRunner().isFolia() ? "Enabled" : "Disabled"));
        player.sendMessage("§ePaper Support: §f" + (plugin.getTaskRunner().isPaper() ? "Enabled" : "Disabled"));
    }

    private void optimizeDatabase(Player player) {
        player.sendMessage("§eOptimizing database...");
        try {
            if (plugin.getStorageManager().getStorage() instanceof DatabaseStorage dbStorage) {
                dbStorage.optimizeDatabase();
                player.sendMessage("§aDatabase optimization completed!");
            } else {
                player.sendMessage("§cDatabase optimization not available for current storage type");
            }
        } catch (Exception e) {
            player.sendMessage("§cDatabase optimization failed: " + e.getMessage());
        }
    }

    private void cleanupCaches(Player player) {
        player.sendMessage("§eCleaning up caches...");
        try {
            if (plugin.getTeamManager() != null) {
                plugin.getTeamManager().getTeamNameCache().clear();
                plugin.getTeamManager().getPlayerTeamCache().clear();
            }
            player.sendMessage("§aCache cleanup completed!");
        } catch (Exception e) {
            player.sendMessage("§cCache cleanup failed: " + e.getMessage());
        }
    }

    private void handlePlatform(Player player) {
        if (!plugin.getConfigManager().isBedrockSupportEnabled()) {
            plugin.getMessageManager().sendMessage(player, "feature_disabled");
            return;
        }
        boolean isBedrock = plugin.getBedrockSupport().isBedrockPlayer(player);
        String platform = isBedrock ? "Bedrock Edition" : "Java Edition";
        String platformColor = isBedrock ? "<#00D4FF>" : "<#00FF00>";
        plugin.getMessageManager().sendRawMessage(player,
                "<white>Your Platform: " + platformColor + platform + "</white>");
        if (isBedrock) {
            String gamertag = plugin.getBedrockSupport().getBedrockGamertag(player);
            if (gamertag != null && !gamertag.equals(player.getName())) {
                plugin.getMessageManager().sendRawMessage(player,
                        "<gray>Xbox Gamertag: <white>" + gamertag + "</white>");
            }
            UUID javaUuid = plugin.getBedrockSupport().getJavaEditionUuid(player);
            if (!javaUuid.equals(player.getUniqueId())) {
                plugin.getMessageManager().sendRawMessage(player,
                        "<gray>Java Edition UUID: <white>" + javaUuid.toString() + "</white>");
            }
        }
        plugin.getMessageManager().sendRawMessage(player,
                "<gray>Current UUID: <white>" + player.getUniqueId().toString() + "</white>");
    }

    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!(sender instanceof Player player)) {
            return new ArrayList<>();
        }
        if (args.length == 1) {
            List<String> completions = new ArrayList<>();
            completions.add("accept");
            completions.add("create");
            completions.add("deny");
            completions.add("disband");
            completions.add("invite");
            completions.add("invites");
            completions.add("join");
            completions.add("unjoin");
            completions.add("kick");
            completions.add("leave");
            completions.add("promote");
            completions.add("demote");
            completions.add("info");
            completions.add("sethome");
            completions.add("delhome");
            completions.add("home");

            if (plugin.getConfigManager().isTeamTagEnabled()) {
                completions.add("settag");
            }

            completions.add("setdesc");

            if (plugin.getConfig().getBoolean("features.team_rename", true)) {
                completions.add("rename");
            }

            completions.add("transfer");
            completions.add("pvp");
            completions.add("bank");
            completions.add("blacklist");
            completions.add("unblacklist");
            completions.add("settings");
            completions.add("enderchest");
            completions.add("public");
            completions.add("requests");
            completions.add("setwarp");
            completions.add("delwarp");
            completions.add("warp");
            completions.add("warps");
            completions.add("top");
            completions.add("admin");
            completions.add("platform");
            completions.add("reload");
            completions.add("chat");
            completions.add("setcolor");
            completions.add("help");
            return completions.stream()
                    .filter(cmd -> cmd.toLowerCase().startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        if (args.length == 2) {
            String subCommand = args[0].toLowerCase();
            switch (subCommand) {
                case "accept", "deny" -> {
                    return teamManager.getPendingInvites(player.getUniqueId()).stream()
                            .map(Team::getName)
                            .filter(name -> name.toLowerCase().startsWith(args[1].toLowerCase()))
                            .collect(Collectors.toList());
                }
                case "invite" -> {
                    Team team = teamManager.getPlayerTeam(player.getUniqueId());
                    if (team != null) {
                        return Bukkit.getOnlinePlayers().stream()
                                .filter(target -> !team.isMember(target.getUniqueId()) &&
                                        teamManager.getPlayerTeam(target.getUniqueId()) == null)
                                .map(Player::getName)
                                .filter(name -> name.toLowerCase().startsWith(args[1].toLowerCase()))
                                .collect(Collectors.toList());
                    }
                }
                case "kick", "promote", "demote", "transfer" -> {
                    Team team = teamManager.getPlayerTeam(player.getUniqueId());
                    if (team != null) {
                        return team.getMembers().stream()
                                .map(member -> Bukkit.getOfflinePlayer(member.getPlayerUuid()).getName())
                                .filter(name -> name != null && name.toLowerCase().startsWith(args[1].toLowerCase()))
                                .collect(Collectors.toList());
                    }
                }
                case "join" -> {
                    return teamManager.getAllTeams().stream()
                            .map(Team::getName)
                            .filter(name -> name.toLowerCase().startsWith(args[1].toLowerCase()))
                            .collect(Collectors.toList());
                }
                case "info" -> {
                    return teamManager.getAllTeams().stream()
                            .map(Team::getName)
                            .filter(name -> name.toLowerCase().startsWith(args[1].toLowerCase()))
                            .collect(Collectors.toList());
                }
                case "setwarp", "delwarp", "warp" -> {
                    Team team = teamManager.getPlayerTeam(player.getUniqueId());
                    if (team != null) {
                        List<IDataStorage.TeamWarp> warps = plugin.getStorageManager().getStorage()
                                .getWarps(team.getId());
                        return warps.stream()
                                .map(IDataStorage.TeamWarp::name)
                                .filter(name -> name.toLowerCase().startsWith(args[1].toLowerCase()))
                                .collect(Collectors.toList());
                    }
                }
                case "blacklist" -> {
                    Team team = teamManager.getPlayerTeam(player.getUniqueId());
                    if (team != null) {
                        try {
                            List<BlacklistedPlayer> blacklist = plugin.getStorageManager().getStorage()
                                    .getTeamBlacklist(team.getId());
                            return blacklist.stream()
                                    .map(BlacklistedPlayer::getPlayerName)
                                    .filter(name -> name.toLowerCase().startsWith(args[1].toLowerCase()))
                                    .collect(Collectors.toList());
                        } catch (Exception e) {
                            plugin.getLogger().warning("Could not get blacklist for tab completion: " + e.getMessage());
                            return new ArrayList<>();
                        }
                    }
                }
                case "unblacklist" -> {
                    Team team = teamManager.getPlayerTeam(player.getUniqueId());
                    if (team != null) {
                        try {
                            List<BlacklistedPlayer> blacklist = plugin.getStorageManager().getStorage()
                                    .getTeamBlacklist(team.getId());
                            return blacklist.stream()
                                    .map(BlacklistedPlayer::getPlayerName)
                                    .filter(name -> name.toLowerCase().startsWith(args[1].toLowerCase()))
                                    .collect(Collectors.toList());
                        } catch (Exception e) {
                            plugin.getLogger().warning("Could not get blacklist for tab completion: " + e.getMessage());
                            return new ArrayList<>();
                        }
                    }
                }
                case "admin" -> {
                    if (hasAdminPermission(player)) {
                        return List.of("disband", "testmigration", "enderchest", "performance").stream()
                                .filter(cmd -> cmd.toLowerCase().startsWith(args[1].toLowerCase()))
                                .collect(Collectors.toList());
                    }
                }
                case "serveralias" -> {
                    if (hasAdminPermission(player)) {
                        return List.of("set", "remove", "list").stream()
                                .filter(cmd -> cmd.toLowerCase().startsWith(args[1].toLowerCase()))
                                .collect(Collectors.toList());
                    }
                }
                case "setcolor" -> {
                    List<String> colors = Arrays.stream(org.bukkit.ChatColor.values())
                            .filter(org.bukkit.ChatColor::isColor)
                            .map(Enum::name)
                            .collect(Collectors.toList());
                    colors.add("RESET");
                    return colors.stream()
                            .filter(name -> name.toUpperCase().startsWith(args[1].toUpperCase()))
                            .collect(Collectors.toList());
                }
            }
        }
        if (args.length == 3) {
            String subCommand = args[0].toLowerCase();
            if (subCommand.equals("admin")) {
                if (hasAdminPermission(player)) {
                    if (args[1].toLowerCase().equals("disband")) {
                        return teamManager.getAllTeams().stream()
                                .map(Team::getName)
                                .filter(name -> name.toLowerCase().startsWith(args[2].toLowerCase()))
                                .collect(Collectors.toList());
                    } else if (args[1].toLowerCase().equals("enderchest")) {
                        return teamManager.getAllTeams().stream()
                                .map(Team::getName)
                                .filter(name -> name.toLowerCase().startsWith(args[2].toLowerCase()))
                                .collect(Collectors.toList());
                    } else if (args[1].toLowerCase().equals("testmigration")) {
                        return List
                                .of("test", "migrate", "validate", "backup", "config", "update-config",
                                        "force-update-config", "backup-config", "cleanup-backups")
                                .stream()
                                .filter(cmd -> cmd.toLowerCase().startsWith(args[2].toLowerCase()))
                                .collect(Collectors.toList());
                    } else if (args[1].toLowerCase().equals("performance")) {
                        return List.of("database", "cache", "tasks", "optimize", "cleanup").stream()
                                .filter(cmd -> cmd.toLowerCase().startsWith(args[2].toLowerCase()))
                                .collect(Collectors.toList());
                    }
                }
            }
        }
        return new ArrayList<>();
    }

    private void handleSetColor(Player player, String[] args) {
        if (!checkFeatureEnabled(player, "team_settings")) {
            return;
        }
        if (args.length < 2) {
            plugin.getMessageManager().sendRawMessage(player, "<gray>Usage: /team setcolor <color|reset></gray>");
            return;
        }
        String colorName = args[1];
        teamManager.setTeamColor(player, colorName);
    }
}
