package eu.kotori.justTeams.team;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import eu.kotori.justTeams.JustTeams;
import eu.kotori.justTeams.config.ConfigManager;
import eu.kotori.justTeams.config.MessageManager;
import eu.kotori.justTeams.gui.ConfirmGUI;
import eu.kotori.justTeams.gui.TeamGUI;
import eu.kotori.justTeams.gui.admin.AdminGUI;
import eu.kotori.justTeams.gui.MemberEditGUI;
import eu.kotori.justTeams.storage.IDataStorage;
import eu.kotori.justTeams.storage.IDataStorage.CrossServerUpdate;

import eu.kotori.justTeams.util.CancellableTask;
import eu.kotori.justTeams.util.EffectsUtil;
import eu.kotori.justTeams.util.InventoryUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.Particle;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import java.io.IOException;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.Optional;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.Map;
import java.util.HashSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

public class TeamManager {
    private final JustTeams plugin;
    private final IDataStorage storage;
    private final MessageManager messageManager;
    private final ConfigManager configManager;
    private final Map<String, Team> teamNameCache = new ConcurrentHashMap<>();
    private final Map<UUID, Team> playerTeamCache = new ConcurrentHashMap<>();
    private final Cache<UUID, List<String>> teamInvites;
    private final Cache<UUID, Instant> joinRequestCooldowns;
    private final Map<UUID, Instant> homeCooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, CancellableTask> teleportTasks = new ConcurrentHashMap<>();
    private final Map<UUID, Instant> warpCooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, Instant> teamStatusCooldowns = new ConcurrentHashMap<>();
    private final Map<Integer, Long> teamLastModified = new ConcurrentHashMap<>();
    private final Object cacheLock = new Object();
    private final AtomicBoolean syncInProgress = new AtomicBoolean(false);

    public void markTeamModified(int teamId) {
        if (teamId <= 0) {
            return;
        }
        synchronized (cacheLock) {
            teamLastModified.put(teamId, System.currentTimeMillis());
        }
    }

    private String formatCurrency(double amount) {
        if (amount >= 1_000_000_000) {
            return String.format("%.2fB", amount / 1_000_000_000);
        } else if (amount >= 1_000_000) {
            return String.format("%.2fM", amount / 1_000_000);
        } else if (amount >= 1_000) {
            return String.format("%.2fK", amount / 1_000);
        } else {
            return String.format("%.2f", amount);
        }
    }

    private boolean hasTeamBeenModified(int teamId, long withinMs) {
        synchronized (cacheLock) {
            Long lastModified = teamLastModified.get(teamId);
            if (lastModified == null) {
                return false;
            }
            return (System.currentTimeMillis() - lastModified) < withinMs;
        }
    }

    public TeamManager(JustTeams plugin) {
        this.plugin = plugin;
        this.storage = plugin.getStorageManager().getStorage();
        this.messageManager = plugin.getMessageManager();
        this.configManager = plugin.getConfigManager();
        this.teamInvites = CacheBuilder.newBuilder()
                .expireAfterWrite(5, TimeUnit.MINUTES)
                .build();
        this.joinRequestCooldowns = CacheBuilder.newBuilder()
                .expireAfterWrite(1, TimeUnit.MINUTES)
                .build();
    }

    public void publishCrossServerUpdate(int teamId, String updateType, String playerUuid, String data) {
        if (plugin.getRedisManager() != null && plugin.getRedisManager().isAvailable()) {
            plugin.getRedisManager().publishTeamUpdate(teamId, updateType, playerUuid, data)
                    .thenAccept(success -> {
                        if (!success) {
                            plugin.getLogger()
                                    .info("Redis publish failed for " + updateType + ", using MySQL fallback");
                        }
                    });
        }

        plugin.getTaskRunner().runAsync(() -> {
            storage.addCrossServerUpdate(teamId, updateType,
                    playerUuid != null ? playerUuid : "",
                    "ALL_SERVERS");

            if (plugin.getConfigManager().isDebugEnabled()) {
                plugin.getLogger().info("✓ Wrote cross-server update to MySQL: " + updateType +
                        " for team " + teamId + " (fallback for non-Redis servers)");
            }
        });
    }

    public void handlePendingTeleport(Player player) {
        String currentServer = plugin.getConfigManager().getServerIdentifier();
        plugin.getDebugLogger()
                .log("Handling pending teleport check for " + player.getName() + " on server " + currentServer);
        plugin.getTaskRunner().runAsync(() -> {
            storage.getAndRemovePendingTeleport(player.getUniqueId(), currentServer).ifPresent(location -> {
                plugin.getDebugLogger().log("Found pending teleport for " + player.getName() + " to " + location);
                plugin.getTaskRunner().runEntityTaskLater(player, () -> {
                    teleportPlayer(player, location);
                }, 5L);
            });
        });
    }

    public List<Team> getAllTeams() {
        synchronized (cacheLock) {
            if (teamNameCache.isEmpty()) {
                List<Team> dbTeams = storage.getAllTeams();
                for (Team team : dbTeams) {
                    loadTeamIntoCache(team);
                }
            }
            return new ArrayList<>(teamNameCache.values());
        }
    }

    public void adminDisbandTeam(Player admin, String teamName) {
        plugin.getTaskRunner().runAsync(() -> {
            Optional<Team> teamOpt = storage.findTeamByName(teamName);
            plugin.getTaskRunner().runOnEntity(admin, () -> {
                if (teamOpt.isEmpty()) {
                    messageManager.sendMessage(admin, "team_not_found");
                    return;
                }
                Team team = teamOpt.get();
                new ConfirmGUI(plugin, admin, "Disband " + team.getName() + "?", (confirmed) -> {
                    if (confirmed) {
                        plugin.getTaskRunner().runAsync(() -> {
                            storage.deleteTeam(team.getId());

                            publishCrossServerUpdate(team.getId(), "TEAM_DISBANDED",
                                    admin.getUniqueId().toString(), team.getName());

                            plugin.getTaskRunner().run(() -> {
                                team.broadcast("admin_team_disbanded_broadcast");
                                uncacheTeam(team.getId());
                                messageManager.sendMessage(admin, "admin_team_disbanded",
                                        Placeholder.unparsed("team", team.getName()));
                                EffectsUtil.playSound(admin, EffectsUtil.SoundType.SUCCESS);
                            });
                        });
                    } else {
                        new AdminGUI(plugin, admin).open();
                    }
                }).open();
            });
        });
    }

    public void adminOpenEnderChest(Player admin, String teamNameOrTag) {

        if (!plugin.getConfigManager().isTeamEnderchestEnabled()) {
            messageManager.sendMessage(admin, "feature_disabled");
            return;
        }

        if (!admin.hasPermission("justteams.admin.enderchest")) {
            messageManager.sendMessage(admin, "no_permission");
            return;
        }

        if (teamNameOrTag == null || teamNameOrTag.trim().isEmpty() || teamNameOrTag.length() > 32) {
            messageManager.sendMessage(admin, "invalid_input");
            return;
        }

        plugin.getLogger().info("Admin " + admin.getName() + " accessing enderchest for team: " + teamNameOrTag);

        plugin.getTaskRunner().runAsync(() -> {
            Optional<Team> teamOpt = storage.findTeamByName(teamNameOrTag);

            if (teamOpt.isEmpty()) {
                teamOpt = storage.getAllTeams().stream()
                        .filter(team -> team != null && team.getTag() != null
                                && team.getTag().equalsIgnoreCase(teamNameOrTag))
                        .findFirst();
            }

            final Optional<Team> finalTeamOpt = teamOpt;
            plugin.getTaskRunner().runOnEntity(admin, () -> {
                if (finalTeamOpt.isEmpty()) {
                    messageManager.sendMessage(admin, "team_not_found");
                    return;
                }
                Team team = finalTeamOpt.get();
                if (plugin.getConfigManager().isSingleServerMode()) {
                    loadAndOpenEnderChestDirect(admin, team);
                } else {
                    if (team.tryLockEnderChest()) {
                        plugin.getTaskRunner().runAsync(() -> {
                            boolean lockAcquired = storage.acquireEnderChestLock(team.getId(),
                                    configManager.getServerIdentifier());
                            plugin.getTaskRunner().runOnEntity(admin, () -> {
                                if (lockAcquired) {
                                    loadAndOpenEnderChestDirect(admin, team);
                                } else {
                                    team.unlockEnderChest();
                                    messageManager.sendMessage(admin, "enderchest_in_use");
                                    EffectsUtil.playSound(admin, EffectsUtil.SoundType.ERROR);
                                }
                            });
                        });
                    } else {
                        messageManager.sendMessage(admin, "enderchest_in_use");
                        EffectsUtil.playSound(admin, EffectsUtil.SoundType.ERROR);
                    }
                }
            });
        });
    }

    private void loadTeamIntoCache(Team team) {
        synchronized (cacheLock) {
            String lowerCaseName = team.getName().toLowerCase();
            if (teamNameCache.containsKey(lowerCaseName)) {
                Team cachedTeam = teamNameCache.get(lowerCaseName);
                cachedTeam.getMembers().forEach(member -> playerTeamCache.put(member.getPlayerUuid(), cachedTeam));
                return;
            }
            team.getMembers().clear();
            team.getMembers().addAll(storage.getTeamMembers(team.getId()));
            List<UUID> joinRequests = storage.getJoinRequests(team.getId());
            team.getJoinRequests().clear();
            for (UUID requestUuid : joinRequests) {
                team.addJoinRequest(requestUuid);
            }
            teamNameCache.put(lowerCaseName, team);
            team.getMembers().forEach(member -> playerTeamCache.put(member.getPlayerUuid(), team));
            plugin.getLogger().info("Loaded team " + team.getName() + " with " + team.getMembers().size()
                    + " members and " + joinRequests.size() + " join requests");
        }
    }

    public void uncacheTeam(int teamId) {
        synchronized (cacheLock) {
            Team team = teamNameCache.values().stream()
                    .filter(t -> t.getId() == teamId)
                    .findFirst()
                    .orElse(null);
            if (team != null) {
                if (team.getEnderChest() != null) {
                    saveEnderChest(team);
                }
                teamNameCache.remove(team.getName().toLowerCase());
                team.getMembers().forEach(member -> playerTeamCache.remove(member.getPlayerUuid()));
                teamLastModified.remove(teamId);
            }
        }
    }

    public void addTeamToCache(Team team) {
        synchronized (cacheLock) {
            teamNameCache.put(team.getName().toLowerCase(), team);
            for (TeamPlayer member : team.getMembers()) {
                playerTeamCache.put(member.getPlayerUuid(), team);
            }
        }
    }

    public void removeFromPlayerTeamCache(UUID playerUuid) {
        synchronized (cacheLock) {
            playerTeamCache.remove(playerUuid);
        }
    }

    public void addPlayerToTeamCache(UUID playerUuid, Team team) {
        synchronized (cacheLock) {
            playerTeamCache.put(playerUuid, team);
        }
    }

    public Optional<Team> getTeamById(int teamId) {
        synchronized (cacheLock) {
            Team cachedTeam = teamNameCache.values().stream()
                    .filter(t -> t.getId() == teamId)
                    .findFirst()
                    .orElse(null);

            if (cachedTeam != null) {
                return Optional.of(cachedTeam);
            }

            Optional<Team> dbTeam = storage.findTeamById(teamId);
            if (dbTeam.isPresent()) {
                loadTeamIntoCache(dbTeam.get());
                return dbTeam;
            }

            return Optional.empty();
        }
    }

    public Team getPlayerTeam(UUID playerUuid) {
        UUID effectiveUuid = getEffectiveUuid(playerUuid);
        synchronized (cacheLock) {
            Team cachedTeam = playerTeamCache.get(effectiveUuid);
            if (cachedTeam != null) {
                return cachedTeam;
            }
        }

        try {
            Optional<Team> dbTeam = CompletableFuture
                    .supplyAsync(() -> storage.findTeamByPlayer(effectiveUuid))
                    .orTimeout(5, TimeUnit.SECONDS)
                    .exceptionally(ex -> {
                        plugin.getLogger()
                                .warning("Failed to fetch team for " + playerUuid + ": " + ex.getMessage());
                        if (plugin.getConfigManager().isDebugEnabled()) {
                            ex.printStackTrace();
                        }
                        return Optional.empty();
                    })
                    .join();

            if (dbTeam.isPresent()) {
                synchronized (cacheLock) {
                    loadTeamIntoCache(dbTeam.get());
                }
                return dbTeam.get();
            }
        } catch (CompletionException e) {
            plugin.getLogger().severe("Database query failed for player " + playerUuid + ": " + e.getMessage());
            if (plugin.getConfigManager().isDebugEnabled()) {
                e.printStackTrace();
            }
        }
        return null;
    }

    public CompletableFuture<Team> getPlayerTeamAsync(UUID playerUuid) {
        UUID effectiveUuid = getEffectiveUuid(playerUuid);
        synchronized (cacheLock) {
            Team cachedTeam = playerTeamCache.get(effectiveUuid);
            if (cachedTeam != null) {
                return CompletableFuture.completedFuture(cachedTeam);
            }
        }

        return CompletableFuture.supplyAsync(() -> storage.findTeamByPlayer(effectiveUuid))
                .orTimeout(5, TimeUnit.SECONDS)
                .handle((opt, ex) -> {
                    if (ex != null) {
                        plugin.getLogger().warning(
                                "Failed to fetch team asynchronously for " + playerUuid + ": " + ex.getMessage());
                        if (plugin.getConfigManager().isDebugEnabled()) {
                            ex.printStackTrace();
                        }
                        return null;
                    }
                    if (opt.isPresent()) {
                        synchronized (cacheLock) {
                            loadTeamIntoCache(opt.get());
                        }
                        return opt.get();
                    }
                    return null;
                });
    }

    public void adminTeleportTeamHome(Player admin, String teamName) {
        plugin.getTaskRunner().runAsync(() -> {
            Optional<Team> teamOpt = storage.findTeamByName(teamName);
            if (teamOpt.isEmpty()) {
                plugin.getTaskRunner().runOnEntity(admin, () -> {
                    plugin.getMessageManager().sendMessage(admin, "team_not_found");
                });
                return;
            }

            Team team = teamOpt.get();
            Location home = team.getHomeLocation();

            plugin.getTaskRunner().runOnEntity(admin, () -> {
                if (home == null) {
                    plugin.getMessageManager().sendMessage(admin, "admin_home_not_set",
                            Placeholder.unparsed("team", team.getName()));
                } else {
                    admin.teleport(home);
                    plugin.getMessageManager().sendMessage(admin, "admin_teleported_home",
                            Placeholder.unparsed("team", team.getName()));
                }
            });
        });
    }

    public Team getPlayerTeamCached(UUID playerUuid) {
        UUID effectiveUuid = getEffectiveUuid(playerUuid);
        return playerTeamCache.get(effectiveUuid);
    }

    private UUID getEffectiveUuid(UUID playerUuid) {
        if (!plugin.getConfigManager().isBedrockSupportEnabled()) {
            return playerUuid;
        }
        String uuidMode = plugin.getConfigManager().getUuidMode();
        switch (uuidMode.toLowerCase()) {
            case "floodgate":
                if (plugin.getBedrockSupport().isBedrockPlayer(playerUuid)) {
                    return plugin.getBedrockSupport().getJavaEditionUuid(playerUuid);
                }
                return playerUuid;
            case "bedrock":
                return playerUuid;
            case "auto":
            default:
                if (plugin.getBedrockSupport().isBedrockPlayer(playerUuid)) {
                    return plugin.getBedrockSupport().getJavaEditionUuid(playerUuid);
                }
                return playerUuid;
        }
    }

    public Team getTeamByName(String teamName) {
        synchronized (cacheLock) {
            Team cachedTeam = teamNameCache.get(teamName.toLowerCase());
            if (cachedTeam != null) {
                return cachedTeam;
            }
            Optional<Team> dbTeam = storage.findTeamByName(teamName);
            if (dbTeam.isPresent()) {
                loadTeamIntoCache(dbTeam.get());
                return dbTeam.get();
            }
            return null;
        }
    }

    public void unloadPlayer(Player player) {
        CancellableTask task = teleportTasks.remove(player.getUniqueId());
        if (task != null) {
            task.cancel();
        }
        Team team = getPlayerTeam(player.getUniqueId());
        if (team != null) {
            playerTeamCache.remove(player.getUniqueId());
            boolean isTeamEmptyOnline = team.getMembers().stream()
                    .allMatch(member -> member.getPlayerUuid().equals(player.getUniqueId()) || !member.isOnline());
            if (isTeamEmptyOnline) {
                if (team.getEnderChest() != null && !team.getEnderChest().getViewers().isEmpty()) {
                    saveEnderChest(team);
                }
                uncacheTeam(team.getId());
            }
        }
    }

    public void loadPlayerTeam(Player player) {
        if (playerTeamCache.containsKey(player.getUniqueId())) {
            return;
        }
        plugin.getTaskRunner().runAsync(() -> {
            Optional<Team> teamOpt = storage.findTeamByPlayer(player.getUniqueId());
            plugin.getTaskRunner().runOnEntity(player, () -> {
                if (teamOpt.isPresent()) {
                    Team team = teamOpt.get();
                    loadTeamIntoCache(team);
                    checkPendingJoinRequests(player, team);
                    plugin.getGlowManager().refreshGlow(player);
                }
            });
        });
    }

    private void checkPendingJoinRequests(Player player, Team team) {
        if (team.hasElevatedPermissions(player.getUniqueId())) {
            List<UUID> requests = storage.getJoinRequests(team.getId());
            if (!requests.isEmpty()) {
                plugin.getLogger()
                        .info("Player " + player.getName() + " has " + requests.size() + " pending join requests");
                messageManager.sendMessage(player, "join_request_count",
                        Placeholder.unparsed("count", String.valueOf(requests.size())));
                messageManager.sendMessage(player, "join_request_notification",
                        Placeholder.unparsed("player", "a player"));
            }
        }
    }

    public String validateTeamName(String name) {
        if (containsBlockedFormattingCodes(name)) {
            return messageManager.getRawMessage("name_contains_blocked_codes");
        }
        String plainName = stripColorCodes(name);
        if (plainName.length() < configManager.getMinNameLength()) {
            return messageManager.getRawMessage("name_too_short").replace("<min_length>",
                    String.valueOf(configManager.getMinNameLength()));
        }
        if (plainName.length() > configManager.getMaxNameLength()) {
            return messageManager.getRawMessage("name_too_long").replace("<max_length>",
                    String.valueOf(configManager.getMaxNameLength()));
        }
        if (!plainName.matches(
                "^[a-zA-Z0-9_]{" + configManager.getMinNameLength() + "," + configManager.getMaxNameLength() + "}$")) {
            return messageManager.getRawMessage("name_invalid");
        }
        if (storage.findTeamByName(plainName).isPresent() || teamNameCache.containsKey(plainName.toLowerCase())) {
            return messageManager.getRawMessage("team_name_exists").replace("<team>", plainName);
        }
        return null;
    }

    private String stripColorCodes(String text) {
        if (text == null)
            return "";
        return text.replaceAll("(?i)&[0-9A-FK-OR]", "").replaceAll("(?i)<#[0-9A-F]{6}>", "")
                .replaceAll("(?i)</#[0-9A-F]{6}>", "");
    }

    private boolean containsFormattingCodes(String text) {
        if (text == null)
            return false;
        return text.matches(".*(?i)&[0-9A-FK-OR].*");
    }

    private boolean containsBlockedFormattingCodes(String text) {
        if (text == null)
            return false;
        if (!configManager.areFormattingCodesAllowed()) {
            return containsFormattingCodes(text);
        }
        List<String> blockedCodes = configManager.getBlockedFormattingCodes();
        String lowerText = text.toLowerCase();
        for (String code : blockedCodes) {
            if (lowerText.contains(code.toLowerCase())) {
                return true;
            }
        }
        return false;
    }

    public void createTeam(Player owner, String name, String tag) {
        plugin.getTaskRunner().runAsync(() -> {
            if (getPlayerTeam(owner.getUniqueId()) != null) {
                plugin.getTaskRunner().runOnEntity(owner, () -> {
                    messageManager.sendMessage(owner, "already_in_team");
                    EffectsUtil.playSound(owner, EffectsUtil.SoundType.ERROR);
                });
                return;
            }
            String nameError = validateTeamName(name);
            if (nameError != null) {
                plugin.getTaskRunner().runOnEntity(owner, () -> {
                    messageManager.sendRawMessage(owner, messageManager.getRawMessage("prefix") + nameError);
                    EffectsUtil.playSound(owner, EffectsUtil.SoundType.ERROR);
                });
                return;
            }
            if (configManager.isTeamTagEnabled()) {
                String plainTag = stripColorCodes(tag);
                if (plainTag.length() > configManager.getMaxTagLength() || !plainTag.matches("[a-zA-Z0-9]+")) {
                    plugin.getTaskRunner().runOnEntity(owner, () -> {
                        messageManager.sendMessage(owner, "tag_invalid");
                        EffectsUtil.playSound(owner, EffectsUtil.SoundType.ERROR);
                    });
                    return;
                }
            }
            boolean defaultPvp = configManager.getDefaultPvpStatus();
            boolean defaultPublic = configManager.isDefaultPublicStatus();
            storage.createTeam(name, tag, owner.getUniqueId(), defaultPvp, defaultPublic, true).ifPresent(team -> {
                plugin.getTaskRunner().runOnEntity(owner, () -> {
                    loadTeamIntoCache(team);
                    messageManager.sendMessage(owner, "team_created", Placeholder.unparsed("team", team.getName()));
                    EffectsUtil.playSound(owner, EffectsUtil.SoundType.SUCCESS);

                    plugin.getWebhookHelper().sendTeamCreateWebhook(owner, team);

                    publishCrossServerUpdate(team.getId(), "TEAM_CREATED", owner.getUniqueId().toString(),
                            team.getName());

                    if (plugin.getConfigManager().isBroadcastTeamCreatedEnabled()) {
                        Component broadcastMessage = plugin.getMiniMessage().deserialize(
                                plugin.getMessageManager().getRawMessage("team_created_broadcast"),
                                Placeholder.unparsed("player", owner.getName()),
                                Placeholder.unparsed("team", team.getName()));
                        Bukkit.broadcast(broadcastMessage);
                    }
                });
            });
        });
    }

    public void setTeamColor(Player owner, String colorName) {
        Team team = getPlayerTeam(owner.getUniqueId());
        if (team == null) {
            messageManager.sendMessage(owner, "player_not_in_team");
            EffectsUtil.playSound(owner, EffectsUtil.SoundType.ERROR);
            return;
        }
        if (!team.hasElevatedPermissions(owner.getUniqueId())) {
            messageManager.sendMessage(owner, "must_be_owner_or_co_owner");
            EffectsUtil.playSound(owner, EffectsUtil.SoundType.ERROR);
            return;
        }

        org.bukkit.ChatColor color = null;
        if (!colorName.equalsIgnoreCase("reset")) {
            try {
                color = org.bukkit.ChatColor.valueOf(colorName.toUpperCase());
                if (!color.isColor()) {
                    messageManager.sendMessage(owner, "invalid_color");
                    EffectsUtil.playSound(owner, EffectsUtil.SoundType.ERROR);
                    return;
                }
            } catch (IllegalArgumentException e) {
                messageManager.sendMessage(owner, "invalid_color");
                EffectsUtil.playSound(owner, EffectsUtil.SoundType.ERROR);
                return;
            }
        }

        final org.bukkit.ChatColor finalColor = color;
        String finalColorName = (color != null) ? color.name() : null;

        plugin.getTaskRunner().runAsync(() -> {
            storage.setTeamColor(team.getId(), finalColorName);
            plugin.getTaskRunner().run(() -> {
                team.setColor(finalColor);
                for (TeamPlayer member : team.getMembers()) {
                    Player memberPlayer = Bukkit.getPlayer(member.getPlayerUuid());
                    if (memberPlayer != null && memberPlayer.isOnline()) {
                        plugin.getGlowManager().refreshGlow(memberPlayer);
                    }
                }
                if (finalColor != null) {
                    messageManager.sendMessage(owner, "team_color_updated",
                            Placeholder.unparsed("color", finalColor.name()));
                } else {
                    messageManager.sendMessage(owner, "team_color_reset");
                }
                EffectsUtil.playSound(owner, EffectsUtil.SoundType.SUCCESS);
            });
        });
    }

    public void disbandTeam(Player owner) {
        Team team = getPlayerTeam(owner.getUniqueId());
        if (team == null) {
            messageManager.sendMessage(owner, "player_not_in_team");
            EffectsUtil.playSound(owner, EffectsUtil.SoundType.ERROR);
            return;
        }
        if (!team.isOwner(owner.getUniqueId())) {
            messageManager.sendMessage(owner, "not_owner");
            EffectsUtil.playSound(owner, EffectsUtil.SoundType.ERROR);
            return;
        }
        new ConfirmGUI(plugin, owner, "Disband " + team.getName() + "?", confirmed -> {
            if (confirmed) {
                plugin.getTaskRunner().runAsync(() -> {
                    int memberCount = team.getMembers().size();

                    List<UUID> memberUuids = team.getMembers().stream()
                            .map(TeamPlayer::getPlayerUuid)
                            .collect(Collectors.toList());
                    String teamName = team.getName();
                    int teamId = team.getId();

                    storage.deleteTeam(teamId);

                    publishCrossServerUpdate(teamId, "TEAM_DISBANDED", owner.getUniqueId().toString(), teamName);

                    plugin.getTaskRunner().run(() -> {
                        for (UUID memberUuid : memberUuids) {
                            Player memberPlayer = Bukkit.getPlayer(memberUuid);
                            if (memberPlayer != null && memberPlayer.isOnline()) {
                                memberPlayer.closeInventory();

                                playerTeamCache.remove(memberUuid);

                                plugin.getGlowManager().stopGlowForPlayer(memberPlayer, team);

                                if (!memberUuid.equals(owner.getUniqueId())) {
                                    messageManager.sendMessage(memberPlayer, "team_disbanded_broadcast",
                                            Placeholder.unparsed("team", teamName));
                                    EffectsUtil.playSound(memberPlayer, EffectsUtil.SoundType.ERROR);
                                }
                            } else {
                                playerTeamCache.remove(memberUuid);
                            }
                        }

                        synchronized (cacheLock) {
                            teamNameCache.remove(teamName.toLowerCase());
                            teamLastModified.remove(teamId);
                        }

                        plugin.getWebhookHelper().sendTeamDeleteWebhook(owner, team, memberCount);

                        if (plugin.getConfigManager().isBroadcastTeamDisbandedEnabled()) {
                            Component broadcastMessage = plugin.getMiniMessage().deserialize(
                                    messageManager.getRawMessage("team_disbanded_broadcast"),
                                    Placeholder.unparsed("team", teamName));
                            Bukkit.broadcast(broadcastMessage);
                        }

                        messageManager.sendMessage(owner, "team_disbanded");
                        EffectsUtil.playSound(owner, EffectsUtil.SoundType.SUCCESS);
                    });
                });
            } else {
                new TeamGUI(plugin, team, owner).open();
            }
        }).open();
    }

    public void invitePlayer(Player inviter, Player target) {
        Team team = getPlayerTeam(inviter.getUniqueId());
        if (team == null) {
            messageManager.sendMessage(inviter, "player_not_in_team");
            EffectsUtil.playSound(inviter, EffectsUtil.SoundType.ERROR);
            return;
        }
        if (!team.hasElevatedPermissions(inviter.getUniqueId())) {
            messageManager.sendMessage(inviter, "must_be_owner_or_co_owner");
            EffectsUtil.playSound(inviter, EffectsUtil.SoundType.ERROR);
            return;
        }
        if (inviter.getUniqueId().equals(target.getUniqueId())) {
            messageManager.sendMessage(inviter, "invite_self");
            EffectsUtil.playSound(inviter, EffectsUtil.SoundType.ERROR);
            return;
        }
        if (getPlayerTeam(target.getUniqueId()) != null) {
            messageManager.sendMessage(inviter, "target_already_in_team",
                    Placeholder.unparsed("target", target.getName()));
            EffectsUtil.playSound(inviter, EffectsUtil.SoundType.ERROR);
            return;
        }
        if (team.getMembers().size() >= configManager.getMaxTeamSize()) {
            messageManager.sendMessage(inviter, "team_is_full");
            EffectsUtil.playSound(inviter, EffectsUtil.SoundType.ERROR);
            return;
        }
        try {
            if (storage.isPlayerBlacklisted(team.getId(), target.getUniqueId())) {
                List<BlacklistedPlayer> blacklist = storage.getTeamBlacklist(team.getId());
                BlacklistedPlayer blacklistedPlayer = blacklist.stream()
                        .filter(bp -> bp != null && bp.getPlayerUuid() != null
                                && bp.getPlayerUuid().equals(target.getUniqueId()))
                        .findFirst()
                        .orElse(null);
                String blacklisterName = blacklistedPlayer != null ? blacklistedPlayer.getBlacklistedByName()
                        : "Unknown";
                messageManager.sendMessage(inviter, "player_is_blacklisted",
                        Placeholder.unparsed("target", target.getName()),
                        Placeholder.unparsed("blacklister", blacklisterName));
                EffectsUtil.playSound(inviter, EffectsUtil.SoundType.ERROR);
                return;
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Could not check blacklist status for player " + target.getName()
                    + " being invited to team " + team.getName() + ": " + e.getMessage());
        }
        List<String> invites = teamInvites.getIfPresent(target.getUniqueId());
        if (invites != null && invites.contains(team.getName().toLowerCase())) {
            messageManager.sendMessage(inviter, "invite_spam");
            EffectsUtil.playSound(inviter, EffectsUtil.SoundType.ERROR);
            return;
        }
        if (invites == null) {
            invites = new ArrayList<>();
        }
        invites.add(team.getName().toLowerCase());
        teamInvites.put(target.getUniqueId(), invites);
        messageManager.sendMessage(inviter, "invite_sent", Placeholder.unparsed("target", target.getName()));
        messageManager.sendRawMessage(target,
                messageManager.getRawMessage("prefix") + messageManager.getRawMessage("invite_received")
                        .replace("<team>", team.getName()));
        EffectsUtil.playSound(inviter, EffectsUtil.SoundType.SUCCESS);
        EffectsUtil.playSound(target, EffectsUtil.SoundType.SUCCESS);
    }

    public void invitePlayerByUuid(Player inviter, UUID targetUuid, String targetName) {
        Team team = getPlayerTeam(inviter.getUniqueId());
        if (team == null) {
            messageManager.sendMessage(inviter, "player_not_in_team");
            EffectsUtil.playSound(inviter, EffectsUtil.SoundType.ERROR);
            return;
        }
        if (!team.hasElevatedPermissions(inviter.getUniqueId())) {
            messageManager.sendMessage(inviter, "must_be_owner_or_co_owner");
            EffectsUtil.playSound(inviter, EffectsUtil.SoundType.ERROR);
            return;
        }
        if (inviter.getUniqueId().equals(targetUuid)) {
            messageManager.sendMessage(inviter, "invite_self");
            EffectsUtil.playSound(inviter, EffectsUtil.SoundType.ERROR);
            return;
        }
        if (team.getMembers().size() >= configManager.getMaxTeamSize()) {
            messageManager.sendMessage(inviter, "team_is_full");
            EffectsUtil.playSound(inviter, EffectsUtil.SoundType.ERROR);
            return;
        }

        plugin.getTaskRunner().runAsync(() -> {
            try {
                Optional<Team> existingTeam = storage.findTeamByPlayer(targetUuid);
                if (existingTeam.isPresent()) {
                    plugin.getTaskRunner().runOnEntity(inviter, () -> {
                        messageManager.sendMessage(inviter, "target_already_in_team",
                                Placeholder.unparsed("target", targetName));
                        EffectsUtil.playSound(inviter, EffectsUtil.SoundType.ERROR);
                    });
                    return;
                }

                if (storage.isPlayerBlacklisted(team.getId(), targetUuid)) {
                    List<BlacklistedPlayer> blacklist = storage.getTeamBlacklist(team.getId());
                    BlacklistedPlayer blacklistedPlayer = blacklist.stream()
                            .filter(bp -> bp != null && bp.getPlayerUuid() != null
                                    && bp.getPlayerUuid().equals(targetUuid))
                            .findFirst()
                            .orElse(null);
                    String blacklisterName = blacklistedPlayer != null ? blacklistedPlayer.getBlacklistedByName()
                            : "Unknown";
                    plugin.getTaskRunner().runOnEntity(inviter, () -> {
                        messageManager.sendMessage(inviter, "player_is_blacklisted",
                                Placeholder.unparsed("target", targetName),
                                Placeholder.unparsed("blacklister", blacklisterName));
                        EffectsUtil.playSound(inviter, EffectsUtil.SoundType.ERROR);
                    });
                    return;
                }

                storage.addTeamInvite(team.getId(), targetUuid, inviter.getUniqueId());

                plugin.getTaskRunner().runOnEntity(inviter, () -> {
                    List<String> invites = teamInvites.getIfPresent(targetUuid);
                    if (invites == null) {
                        invites = new ArrayList<>();
                    }
                    if (!invites.contains(team.getName().toLowerCase())) {
                        invites.add(team.getName().toLowerCase());
                        teamInvites.put(targetUuid, invites);
                    }

                    messageManager.sendMessage(inviter, "invite_sent", Placeholder.unparsed("target", targetName));
                    EffectsUtil.playSound(inviter, EffectsUtil.SoundType.SUCCESS);

                    publishCrossServerUpdate(team.getId(), "PLAYER_INVITED", targetUuid.toString(), team.getName());
                });
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to send cross-server invite: " + e.getMessage());
                plugin.getTaskRunner().runOnEntity(inviter, () -> {
                    messageManager.sendMessage(inviter, "invite_failed");
                    EffectsUtil.playSound(inviter, EffectsUtil.SoundType.ERROR);
                });
            }
        });
    }

    public void acceptInvite(Player player, String teamName) {
        if (getPlayerTeam(player.getUniqueId()) != null) {
            messageManager.sendMessage(player, "already_in_team");
            EffectsUtil.playSound(player, EffectsUtil.SoundType.ERROR);
            return;
        }
        List<String> invites = teamInvites.getIfPresent(player.getUniqueId());
        if (invites == null || !invites.contains(teamName.toLowerCase())) {
            messageManager.sendMessage(player, "no_pending_invite");
            EffectsUtil.playSound(player, EffectsUtil.SoundType.ERROR);
            return;
        }
        plugin.getTaskRunner().runAsync(() -> {
            Team team = getTeamByName(teamName);
            plugin.getTaskRunner().runOnEntity(player, () -> {
                if (team == null) {
                    messageManager.sendMessage(player, "team_not_found");
                    EffectsUtil.playSound(player, EffectsUtil.SoundType.ERROR);
                    return;
                }
                if (team.getMembers().size() >= configManager.getMaxTeamSize()) {
                    messageManager.sendMessage(player, "team_is_full");
                    EffectsUtil.playSound(player, EffectsUtil.SoundType.ERROR);
                    return;
                }
                try {
                    if (storage.isPlayerBlacklisted(team.getId(), player.getUniqueId())) {
                        List<BlacklistedPlayer> blacklist = storage.getTeamBlacklist(team.getId());
                        BlacklistedPlayer blacklistedPlayer = blacklist.stream()
                                .filter(bp -> bp != null && bp.getPlayerUuid() != null
                                        && bp.getPlayerUuid().equals(player.getUniqueId()))
                                .findFirst()
                                .orElse(null);
                        String blacklisterName = blacklistedPlayer != null ? blacklistedPlayer.getBlacklistedByName()
                                : "Unknown";
                        messageManager.sendMessage(player, "player_is_blacklisted",
                                Placeholder.unparsed("target", player.getName()),
                                Placeholder.unparsed("blacklister", blacklisterName));
                        EffectsUtil.playSound(player, EffectsUtil.SoundType.ERROR);
                        return;
                    }
                } catch (Exception e) {
                    plugin.getLogger().warning("Could not check blacklist status for player " + player.getName()
                            + " accepting invite to team " + team.getName() + ": " + e.getMessage());
                }
                invites.remove(teamName.toLowerCase());
                if (invites.isEmpty()) {
                    teamInvites.invalidate(player.getUniqueId());
                }
                plugin.getTaskRunner().runAsync(() -> {
                    storage.addMemberToTeam(team.getId(), player.getUniqueId());
                    storage.clearAllJoinRequests(player.getUniqueId());

                    publishCrossServerUpdate(team.getId(), "MEMBER_JOINED", player.getUniqueId().toString(),
                            player.getName());

                    plugin.getTaskRunner().runOnEntity(player, () -> {
                        team.addMember(new TeamPlayer(player.getUniqueId(), TeamRole.MEMBER, Instant.now(), false, true,
                                false, true));
                        playerTeamCache.put(player.getUniqueId(), team);
                        messageManager.sendMessage(player, "invite_accepted",
                                Placeholder.unparsed("team", team.getName()));
                        team.broadcast("invite_accepted_broadcast", Placeholder.unparsed("player", player.getName()));
                        EffectsUtil.playSound(player, EffectsUtil.SoundType.SUCCESS);

                        plugin.getGlowManager().refreshGlow(player);

                        plugin.getWebhookHelper().sendPlayerJoinWebhook(player, team);
                    });
                });
            });
        });
    }

    public void denyInvite(Player player, String teamName) {
        List<String> invites = teamInvites.getIfPresent(player.getUniqueId());
        if (invites == null || !invites.contains(teamName.toLowerCase())) {
            messageManager.sendMessage(player, "no_pending_invite");
            EffectsUtil.playSound(player, EffectsUtil.SoundType.ERROR);
            return;
        }
        invites.remove(teamName.toLowerCase());
        if (invites.isEmpty()) {
            teamInvites.invalidate(player.getUniqueId());
        }
        messageManager.sendMessage(player, "invite_denied", Placeholder.unparsed("team", teamName));
        EffectsUtil.playSound(player, EffectsUtil.SoundType.SUCCESS);
        Team team = getTeamByName(teamName);
        if (team != null) {
            team.getMembers().stream()
                    .filter(member -> team.hasElevatedPermissions(member.getPlayerUuid()) && member.isOnline())
                    .forEach(privilegedMember -> {
                        messageManager.sendMessage(privilegedMember.getBukkitPlayer(), "invite_denied_broadcast",
                                Placeholder.unparsed("player", player.getName()));
                        EffectsUtil.playSound(privilegedMember.getBukkitPlayer(), EffectsUtil.SoundType.ERROR);
                    });
        }
    }

    public List<Team> getPendingInvites(UUID playerUuid) {
        List<String> inviteNames = teamInvites.getIfPresent(playerUuid);
        if (inviteNames == null || inviteNames.isEmpty()) {
            return new ArrayList<>();
        }
        return inviteNames.stream()
                .map(teamName -> getTeamByName(teamName))
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    public void leaveTeam(Player player) {
        Team team = getPlayerTeam(player.getUniqueId());
        if (team == null) {
            messageManager.sendMessage(player, "player_not_in_team");
            EffectsUtil.playSound(player, EffectsUtil.SoundType.ERROR);
            return;
        }
        if (team.isOwner(player.getUniqueId())) {
            messageManager.sendMessage(player, "owner_must_disband");
            EffectsUtil.playSound(player, EffectsUtil.SoundType.ERROR);
            return;
        }
        plugin.getTaskRunner().runAsync(() -> {
            storage.removeMemberFromTeam(player.getUniqueId());

            publishCrossServerUpdate(team.getId(), "MEMBER_LEFT", player.getUniqueId().toString(), player.getName());

            plugin.getWebhookHelper().sendPlayerLeaveWebhook(player.getName(), team);

            plugin.getTaskRunner().runOnEntity(player, () -> {
                team.removeMember(player.getUniqueId());
                playerTeamCache.remove(player.getUniqueId());
                plugin.getGlowManager().stopGlowForPlayer(player, team);
                messageManager.sendMessage(player, "you_left_team", Placeholder.unparsed("team", team.getName()));
                team.broadcast("player_left_broadcast", Placeholder.unparsed("player", player.getName()));
                EffectsUtil.playSound(player, EffectsUtil.SoundType.SUCCESS);
                player.closeInventory();
            });
        });
    }

    public void kickPlayer(Player kicker, UUID targetUuid) {
        Team team = getPlayerTeam(kicker.getUniqueId());
        if (team == null) {
            messageManager.sendMessage(kicker, "player_not_in_team");
            EffectsUtil.playSound(kicker, EffectsUtil.SoundType.ERROR);
            return;
        }
        if (!team.hasElevatedPermissions(kicker.getUniqueId())) {
            messageManager.sendMessage(kicker, "must_be_owner_or_co_owner");
            EffectsUtil.playSound(kicker, EffectsUtil.SoundType.ERROR);
            return;
        }
        TeamPlayer targetMember = team.getMember(targetUuid);
        String targetName = Bukkit.getOfflinePlayer(targetUuid).getName();
        String safeTargetName = targetName != null ? targetName : "Unknown";
        if (targetMember == null) {
            messageManager.sendMessage(kicker, "target_not_in_your_team",
                    Placeholder.unparsed("target", safeTargetName));
            EffectsUtil.playSound(kicker, EffectsUtil.SoundType.ERROR);
            return;
        }
        if (targetMember.getRole() == TeamRole.OWNER) {
            messageManager.sendMessage(kicker, "cannot_kick_owner");
            EffectsUtil.playSound(kicker, EffectsUtil.SoundType.ERROR);
            return;
        }
        if (targetMember.getRole() == TeamRole.CO_OWNER && !team.isOwner(kicker.getUniqueId())) {
            messageManager.sendMessage(kicker, "cannot_kick_co_owner");
            EffectsUtil.playSound(kicker, EffectsUtil.SoundType.ERROR);
            return;
        }
        new ConfirmGUI(plugin, kicker, "Kick " + safeTargetName + "?", confirmed -> {
            if (confirmed) {
                plugin.getTaskRunner().runAsync(() -> {
                    storage.removeMemberFromTeam(targetUuid);

                    publishCrossServerUpdate(team.getId(), "MEMBER_KICKED", targetUuid.toString(), safeTargetName);

                    plugin.getWebhookHelper().sendPlayerKickWebhook(safeTargetName, kicker.getName(), team);

                    plugin.getTaskRunner().run(() -> {
                        team.removeMember(targetUuid);
                        playerTeamCache.remove(targetUuid);
                        messageManager.sendMessage(kicker, "player_kicked",
                                Placeholder.unparsed("target", safeTargetName));
                        team.broadcast("player_left_broadcast", Placeholder.unparsed("player", safeTargetName));
                        EffectsUtil.playSound(kicker, EffectsUtil.SoundType.SUCCESS);
                        Player targetPlayer = Bukkit.getPlayer(targetUuid);
                        if (targetPlayer != null) {
                            plugin.getGlowManager().stopGlowForPlayer(targetPlayer, team);
                            messageManager.sendMessage(targetPlayer, "you_were_kicked",
                                    Placeholder.unparsed("team", team.getName()));
                            EffectsUtil.playSound(targetPlayer, EffectsUtil.SoundType.ERROR);
                        }
                    });
                });
            } else {
                new MemberEditGUI(plugin, team, kicker, targetUuid).open();
            }
        }).open();
    }

    public void kickPlayerDirect(Player kicker, UUID targetUuid) {
        Team team = getPlayerTeam(kicker.getUniqueId());
        if (team == null) {
            return;
        }
        if (!team.hasElevatedPermissions(kicker.getUniqueId())) {
            return;
        }
        TeamPlayer targetMember = team.getMember(targetUuid);
        if (targetMember == null) {
            return;
        }
        if (targetMember.getRole() == TeamRole.OWNER) {
            return;
        }
        if (targetMember.getRole() == TeamRole.CO_OWNER && !team.isOwner(kicker.getUniqueId())) {
            return;
        }
        plugin.getTaskRunner().runAsync(() -> {
            try {
                if (team.isMember(targetUuid)) {
                    storage.removeMemberFromTeam(targetUuid);

                    String targetName = Bukkit.getOfflinePlayer(targetUuid).getName();
                    String safeTargetName = targetName != null ? targetName : "Unknown";
                    publishCrossServerUpdate(team.getId(), "MEMBER_KICKED", targetUuid.toString(), safeTargetName);

                    plugin.getWebhookHelper().sendPlayerKickWebhook(safeTargetName, kicker.getName(), team);

                    plugin.getTaskRunner().run(() -> {
                        try {
                            if (team.isMember(targetUuid)) {
                                team.removeMember(targetUuid);
                                playerTeamCache.remove(targetUuid);
                                team.broadcast("player_left_broadcast", Placeholder.unparsed("player", safeTargetName));
                                Player targetPlayer = Bukkit.getPlayer(targetUuid);
                                if (targetPlayer != null) {
                                    messageManager.sendMessage(targetPlayer, "you_were_kicked",
                                            Placeholder.unparsed("team", team.getName()));
                                    EffectsUtil.playSound(targetPlayer, EffectsUtil.SoundType.ERROR);
                                }
                            }
                        } catch (Exception e) {
                            plugin.getLogger().severe("Error during kick operation on main thread: " + e.getMessage());
                        }
                    });
                }
            } catch (Exception e) {
                plugin.getLogger().severe("Error during kick operation on async thread: " + e.getMessage());
            }
        });
    }

    public void promotePlayer(Player promoter, UUID targetUuid) {
        Team team = getPlayerTeam(promoter.getUniqueId());
        if (team == null || !team.isOwner(promoter.getUniqueId())) {
            messageManager.sendMessage(promoter, "not_owner");
            EffectsUtil.playSound(promoter, EffectsUtil.SoundType.ERROR);
            return;
        }
        TeamPlayer target = team.getMember(targetUuid);
        String targetName = Bukkit.getOfflinePlayer(targetUuid).getName();
        String safeTargetName = targetName != null ? targetName : "Unknown";
        if (target == null) {
            messageManager.sendMessage(promoter, "target_not_in_your_team",
                    Placeholder.unparsed("target", safeTargetName));
            return;
        }
        if (target.getRole() == TeamRole.CO_OWNER) {
            messageManager.sendMessage(promoter, "already_that_role", Placeholder.unparsed("target", safeTargetName));
            return;
        }
        if (target.getRole() == TeamRole.OWNER) {
            messageManager.sendMessage(promoter, "cannot_promote_owner");
            return;
        }
        target.setRole(TeamRole.CO_OWNER);
        target.setCanWithdraw(true);
        target.setCanUseEnderChest(true);
        target.setCanSetHome(true);
        target.setCanUseHome(true);
        try {
            storage.updateMemberRole(team.getId(), targetUuid, TeamRole.CO_OWNER);
            storage.updateMemberPermissions(team.getId(), targetUuid, true, true, true, true);
            storage.updateMemberEditingPermissions(team.getId(), targetUuid, true, false, true, false, false);
            plugin.getLogger().info(
                    "Successfully promoted " + targetUuid + " in team " + team.getName() + " with updated permissions");
            markTeamModified(team.getId());

            publishCrossServerUpdate(team.getId(), "MEMBER_PROMOTED", targetUuid.toString(), safeTargetName);

            plugin.getWebhookHelper().sendPlayerPromoteWebhook(safeTargetName, promoter.getName(), team,
                    TeamRole.MEMBER, TeamRole.CO_OWNER);
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to update member permissions in database: " + e.getMessage());
            target.setRole(TeamRole.MEMBER);
            target.setCanWithdraw(false);
            target.setCanSetHome(false);
            messageManager.sendMessage(promoter, "promotion_failed");
            EffectsUtil.playSound(promoter, EffectsUtil.SoundType.ERROR);
            return;
        }
        team.broadcast("player_promoted", Placeholder.unparsed("target", safeTargetName));
        EffectsUtil.playSound(promoter, EffectsUtil.SoundType.SUCCESS);
        Player targetPlayer = Bukkit.getPlayer(targetUuid);
        if (targetPlayer != null) {
            plugin.getGlowManager().refreshGlow(targetPlayer);
        }
    }

    public void demotePlayer(Player demoter, UUID targetUuid) {
        Team team = getPlayerTeam(demoter.getUniqueId());
        if (team == null || !team.isOwner(demoter.getUniqueId())) {
            messageManager.sendMessage(demoter, "not_owner");
            EffectsUtil.playSound(demoter, EffectsUtil.SoundType.ERROR);
            return;
        }
        TeamPlayer target = team.getMember(targetUuid);
        String targetName = Bukkit.getOfflinePlayer(targetUuid).getName();
        String safeTargetName = targetName != null ? targetName : "Unknown";
        if (target == null) {
            messageManager.sendMessage(demoter, "target_not_in_your_team",
                    Placeholder.unparsed("target", safeTargetName));
            return;
        }
        if (target.getRole() == TeamRole.MEMBER) {
            messageManager.sendMessage(demoter, "already_that_role", Placeholder.unparsed("target", safeTargetName));
            return;
        }
        if (target.getRole() == TeamRole.OWNER) {
            messageManager.sendMessage(demoter, "cannot_demote_owner");
            return;
        }
        target.setRole(TeamRole.MEMBER);
        target.setCanWithdraw(false);
        target.setCanUseEnderChest(true);
        target.setCanSetHome(false);
        target.setCanUseHome(true);
        try {
            storage.updateMemberRole(team.getId(), targetUuid, TeamRole.MEMBER);
            storage.updateMemberPermissions(team.getId(), targetUuid, false, true, false, true);
            storage.updateMemberEditingPermissions(team.getId(), targetUuid, false, false, false, false, false);
            plugin.getLogger().info(
                    "Successfully demoted " + targetUuid + " in team " + team.getName() + " with updated permissions");
            markTeamModified(team.getId());

            publishCrossServerUpdate(team.getId(), "MEMBER_DEMOTED", targetUuid.toString(), safeTargetName);

            plugin.getWebhookHelper().sendPlayerDemoteWebhook(safeTargetName, demoter.getName(), team,
                    TeamRole.CO_OWNER, TeamRole.MEMBER);
        } catch (SQLException e) {
            plugin.getLogger().severe("Failed to update member permissions in database: " + e.getMessage());
            target.setRole(TeamRole.CO_OWNER);
            target.setCanWithdraw(true);
            target.setCanSetHome(true);
            messageManager.sendMessage(demoter, "demotion_failed");
            EffectsUtil.playSound(demoter, EffectsUtil.SoundType.ERROR);
            return;
        }
        team.broadcast("player_demoted", Placeholder.unparsed("target", safeTargetName));
        EffectsUtil.playSound(demoter, EffectsUtil.SoundType.SUCCESS);
        Player targetPlayer = Bukkit.getPlayer(targetUuid);
        if (targetPlayer != null) {
            plugin.getGlowManager().refreshGlow(targetPlayer);
        }
    }

    public void setTeamTag(Player player, String newTag) {
        if (!configManager.isTeamTagEnabled()) {
            messageManager.sendMessage(player, "feature_disabled");
            return;
        }
        Team team = getPlayerTeam(player.getUniqueId());
        if (team == null) {
            messageManager.sendMessage(player, "player_not_in_team");
            return;
        }
        if (!team.hasElevatedPermissions(player.getUniqueId())) {
            messageManager.sendMessage(player, "must_be_owner_or_co_owner");
            return;
        }
        if (containsBlockedFormattingCodes(newTag)) {
            messageManager.sendMessage(player, "tag_contains_blocked_codes");
            return;
        }
        String plainTag = stripColorCodes(newTag);
        if (plainTag.length() > configManager.getMaxTagLength() || !plainTag.matches("[a-zA-Z0-9]+")) {
            messageManager.sendMessage(player, "tag_invalid");
            return;
        }
        team.setTag(newTag);
        plugin.getTaskRunner().runAsync(() -> storage.setTeamTag(team.getId(), newTag));
        markTeamModified(team.getId());
        messageManager.sendMessage(player, "tag_set", Placeholder.unparsed("tag", newTag));

        publishCrossServerUpdate(team.getId(), "TEAM_UPDATED", player.getUniqueId().toString(), "tag_change|" + newTag);

        forceTeamSync(team.getId());
        EffectsUtil.playSound(player, EffectsUtil.SoundType.SUCCESS);

        refreshPlayerGUIIfOpen(player);
    }

    public void setTeamDescription(Player player, String newDescription) {
        Team team = getPlayerTeam(player.getUniqueId());
        if (team == null) {
            messageManager.sendMessage(player, "player_not_in_team");
            return;
        }
        if (!team.hasElevatedPermissions(player.getUniqueId())) {
            messageManager.sendMessage(player, "must_be_owner_or_co_owner");
            return;
        }
        if (newDescription.length() > configManager.getMaxDescriptionLength()) {
            messageManager.sendMessage(player, "description_too_long",
                    Placeholder.unparsed("max_length", String.valueOf(configManager.getMaxDescriptionLength())));
            return;
        }
        team.setDescription(newDescription);
        plugin.getTaskRunner().runAsync(() -> storage.setTeamDescription(team.getId(), newDescription));
        markTeamModified(team.getId());
        messageManager.sendMessage(player, "description_set");

        publishCrossServerUpdate(team.getId(), "TEAM_UPDATED", player.getUniqueId().toString(), "description_change");

        EffectsUtil.playSound(player, EffectsUtil.SoundType.SUCCESS);

        refreshPlayerGUIIfOpen(player);
    }

    public void transferOwnership(Player oldOwner, UUID newOwnerUuid) {
        Team team = getPlayerTeam(oldOwner.getUniqueId());
        if (team == null || !team.isOwner(oldOwner.getUniqueId())) {
            messageManager.sendMessage(oldOwner, "not_owner");
            EffectsUtil.playSound(oldOwner, EffectsUtil.SoundType.ERROR);
            return;
        }
        if (oldOwner.getUniqueId().equals(newOwnerUuid)) {
            messageManager.sendMessage(oldOwner, "cannot_transfer_to_self");
            EffectsUtil.playSound(oldOwner, EffectsUtil.SoundType.ERROR);
            return;
        }
        if (!team.isMember(newOwnerUuid)) {
            String targetName = Bukkit.getOfflinePlayer(newOwnerUuid).getName();
            messageManager.sendMessage(oldOwner, "target_not_in_your_team",
                    Placeholder.unparsed("target", targetName != null ? targetName : "Unknown"));
            EffectsUtil.playSound(oldOwner, EffectsUtil.SoundType.ERROR);
            return;
        }
        new ConfirmGUI(plugin, oldOwner, "Transfer ownership?", confirmed -> {
            if (confirmed) {
                plugin.getTaskRunner().runAsync(() -> {
                    storage.transferOwnership(team.getId(), newOwnerUuid, oldOwner.getUniqueId());

                    String newOwnerName = Bukkit.getOfflinePlayer(newOwnerUuid).getName();
                    String safeNewOwnerName = newOwnerName != null ? newOwnerName : "Unknown";

                    if (plugin.getRedisManager() != null && plugin.getRedisManager().isAvailable()) {
                        plugin.getRedisManager().publishTeamUpdate(
                                team.getId(),
                                "TEAM_UPDATED",
                                newOwnerUuid.toString(),
                                "ownership_transfer|" + safeNewOwnerName);
                    }

                    plugin.getWebhookHelper().sendOwnershipTransferWebhook(oldOwner.getName(), safeNewOwnerName, team);

                    plugin.getTaskRunner().run(() -> {
                        team.setOwnerUuid(newOwnerUuid);
                        TeamPlayer newOwnerMember = team.getMember(newOwnerUuid);
                        if (newOwnerMember != null) {
                            newOwnerMember.setRole(TeamRole.OWNER);
                            newOwnerMember.setCanWithdraw(true);
                            newOwnerMember.setCanUseEnderChest(true);
                            newOwnerMember.setCanSetHome(true);
                            newOwnerMember.setCanUseHome(true);
                        }
                        TeamPlayer oldOwnerMember = team.getMember(oldOwner.getUniqueId());
                        if (oldOwnerMember != null) {
                            oldOwnerMember.setRole(TeamRole.MEMBER);
                        }
                        messageManager.sendMessage(oldOwner, "transfer_success",
                                Placeholder.unparsed("player", safeNewOwnerName));
                        team.broadcast("transfer_broadcast",
                                Placeholder.unparsed("owner", oldOwner.getName()),
                                Placeholder.unparsed("player", safeNewOwnerName));
                        EffectsUtil.playSound(oldOwner, EffectsUtil.SoundType.SUCCESS);
                    });
                });
            } else {
                new MemberEditGUI(plugin, team, oldOwner, newOwnerUuid).open();
            }
        }).open();
    }

    public void togglePvp(Player player) {
        Team team = getPlayerTeam(player.getUniqueId());
        if (team == null) {
            messageManager.sendMessage(player, "player_not_in_team");
            return;
        }
        if (!team.hasElevatedPermissions(player.getUniqueId())) {
            messageManager.sendMessage(player, "must_be_owner_or_co_owner");
            return;
        }
        boolean newStatus = !team.isPvpEnabled();
        team.setPvpEnabled(newStatus);

        plugin.getTaskRunner().runAsync(() -> {
            storage.setPvpStatus(team.getId(), newStatus);
            markTeamModified(team.getId());

            publishCrossServerUpdate(team.getId(), "TEAM_UPDATED", player.getUniqueId().toString(),
                    "pvp_toggle|" + newStatus);
        });

        if (newStatus) {
            team.broadcast("team_pvp_enabled");
        } else {
            team.broadcast("team_pvp_disabled");
        }
        EffectsUtil.playSound(player, EffectsUtil.SoundType.SUCCESS);
    }

    public void togglePvpStatus(Player player) {
        togglePvp(player);
    }

    public void setTeamHome(Player player) {
        Team team = getPlayerTeam(player.getUniqueId());
        TeamPlayer member = team != null ? team.getMember(player.getUniqueId()) : null;
        if (team == null || member == null) {
            messageManager.sendMessage(player, "player_not_in_team");
            return;
        }
        if (!member.canSetHome()) {
            messageManager.sendMessage(player, "no_permission");
            return;
        }
        Location home = player.getLocation();
        String serverName = plugin.getConfigManager().getServerIdentifier();
        team.setHomeLocation(home);
        team.setHomeServer(serverName);
        plugin.getTaskRunner().runAsync(() -> storage.setTeamHome(team.getId(), home, serverName));
        markTeamModified(team.getId());

        publishCrossServerUpdate(team.getId(), "HOME_SET", player.getUniqueId().toString(), serverName);

        messageManager.sendMessage(player, "home_set");
        EffectsUtil.playSound(player, EffectsUtil.SoundType.SUCCESS);
    }

    public void deleteTeamHome(Player player) {
        Team team = getPlayerTeam(player.getUniqueId());
        TeamPlayer member = team != null ? team.getMember(player.getUniqueId()) : null;
        if (team == null || member == null) {
            messageManager.sendMessage(player, "player_not_in_team");
            return;
        }
        if (!member.canSetHome()) {
            messageManager.sendMessage(player, "no_permission");
            return;
        }
        if (team.getHomeLocation() == null) {
            messageManager.sendMessage(player, "home_not_set");
            return;
        }
        team.setHomeLocation(null);
        team.setHomeServer(null);
        plugin.getTaskRunner().runAsync(() -> storage.deleteTeamHome(team.getId()));
        markTeamModified(team.getId());

        publishCrossServerUpdate(team.getId(), "HOME_DELETED", player.getUniqueId().toString(), "");

        messageManager.sendMessage(player, "home_deleted");
        EffectsUtil.playSound(player, EffectsUtil.SoundType.SUCCESS);
    }

    public void teleportToHome(Player player) {
        Team team = getPlayerTeam(player.getUniqueId());
        if (team == null) {
            messageManager.sendMessage(player, "player_not_in_team");
            return;
        }
        plugin.getTaskRunner().runAsync(() -> {
            Optional<IDataStorage.TeamHome> teamHomeOpt = storage.getTeamHome(team.getId());
            plugin.getTaskRunner().runOnEntity(player, () -> {
                if (teamHomeOpt.isEmpty()) {
                    messageManager.sendMessage(player, "home_not_set");
                    return;
                }
                IDataStorage.TeamHome teamHome = teamHomeOpt.get();
                TeamPlayer member = team.getMember(player.getUniqueId());
                if (member == null || !member.canUseHome()) {
                    messageManager.sendMessage(player, "no_permission");
                    return;
                }
                if (!player.hasPermission("justteams.bypass.home.cooldown")) {
                    if (homeCooldowns.containsKey(player.getUniqueId())) {
                        Instant cooldownEnd = homeCooldowns.get(player.getUniqueId());
                        if (Instant.now().isBefore(cooldownEnd)) {
                            long secondsLeft = Duration.between(Instant.now(), cooldownEnd).toSeconds();
                            messageManager.sendMessage(player, "teleport_cooldown",
                                    Placeholder.unparsed("time", secondsLeft + "s"));
                            return;
                        }
                    }
                }
                String currentServer = plugin.getConfigManager().getServerIdentifier();
                String homeServer = teamHome.serverName();
                plugin.getDebugLogger().log("Teleport initiated for " + player.getName() + ". Current Server: "
                        + currentServer + ", Home Server: " + homeServer);
                if (currentServer.equalsIgnoreCase(homeServer)) {
                    plugin.getDebugLogger().log("Player is on the correct server. Initiating local teleport.");
                    initiateLocalTeleport(player, teamHome.location());
                } else {
                    plugin.getDebugLogger()
                            .log("Player is on the wrong server. Initiating cross-server teleport via database.");
                    plugin.getTaskRunner().runAsync(() -> {
                        storage.addPendingTeleport(player.getUniqueId(), homeServer, teamHome.location());
                        plugin.getTaskRunner().runOnEntity(player, () -> {
                            String connectChannel = "BungeeCord";
                            messageManager.sendMessage(player, "proxy_not_enabled");
                        });
                    });
                }
            });
        });
    }

    private void initiateLocalTeleport(Player player, Location location) {
        int warmup = configManager.getWarmupSeconds();
        if (warmup <= 0 || player.hasPermission("justteams.bypass.home.cooldown")) {
            teleportPlayer(player, location);
            setCooldown(player);
            return;
        }
        Location startLocation = player.getLocation();
        AtomicInteger countdown = new AtomicInteger(warmup);
        CancellableTask task = plugin.getTaskRunner().runEntityTaskTimer(player, () -> {
            if (!player.isOnline() || !Objects.equals(player.getWorld(), startLocation.getWorld())
                    || player.getLocation().distanceSquared(startLocation) > 1) {
                if (player.isOnline()) {
                    messageManager.sendMessage(player, "teleport_moved");
                    EffectsUtil.playSound(player, EffectsUtil.SoundType.ERROR);
                }
                CancellableTask runningTask = teleportTasks.remove(player.getUniqueId());
                if (runningTask != null)
                    runningTask.cancel();
                return;
            }
            if (countdown.get() > 0) {
                messageManager.sendMessage(player, "teleport_warmup",
                        Placeholder.unparsed("seconds", String.valueOf(countdown.get())));
                EffectsUtil.spawnParticles(player.getLocation().add(0, 1, 0),
                        Particle.valueOf(configManager.getWarmupParticle()), 10);
                countdown.decrementAndGet();
            } else {
                teleportPlayer(player, location);
                setCooldown(player);
                CancellableTask runningTask = teleportTasks.remove(player.getUniqueId());
                if (runningTask != null)
                    runningTask.cancel();
            }
        }, 0L, 20L);
        teleportTasks.put(player.getUniqueId(), task);
    }

    public void teleportPlayer(Player player, Location location) {
        plugin.getDebugLogger().log("Executing final teleport for " + player.getName() + " to " + location);
        plugin.getTaskRunner().runAtLocation(location, () -> {
            player.teleportAsync(location).thenAccept(success -> {
                if (success) {
                    messageManager.sendMessage(player, "teleport_success");
                    EffectsUtil.playSound(player, EffectsUtil.SoundType.TELEPORT);
                    EffectsUtil.spawnParticles(player.getLocation(),
                            Particle.valueOf(configManager.getSuccessParticle()), 30);
                } else {
                    messageManager.sendMessage(player, "teleport_moved");
                    EffectsUtil.playSound(player, EffectsUtil.SoundType.ERROR);
                }
            });
        });
    }

    private void setCooldown(Player player) {
        if (player.hasPermission("justteams.bypass.home.cooldown")) {
            return;
        }
        int cooldownSeconds = configManager.getHomeCooldownSeconds();
        if (cooldownSeconds > 0) {
            homeCooldowns.put(player.getUniqueId(), Instant.now().plusSeconds(cooldownSeconds));
        }
    }

    private void startWarpTeleportWarmup(Player player, Location location) {
        int warmup = 5;
        if (warmup <= 0 || player.hasPermission("justteams.bypass.warp.cooldown")) {
            teleportPlayer(player, location);
            setWarpCooldown(player);
            return;
        }
        Location startLocation = player.getLocation();
        AtomicInteger countdown = new AtomicInteger(warmup);
        CancellableTask task = plugin.getTaskRunner().runEntityTaskTimer(player, () -> {
            if (!player.isOnline() || !Objects.equals(player.getWorld(), startLocation.getWorld())
                    || player.getLocation().distanceSquared(startLocation) > 1) {
                if (player.isOnline()) {
                    messageManager.sendMessage(player, "teleport_moved");
                    EffectsUtil.playSound(player, EffectsUtil.SoundType.ERROR);
                }
                CancellableTask runningTask = teleportTasks.remove(player.getUniqueId());
                if (runningTask != null)
                    runningTask.cancel();
                return;
            }
            if (countdown.get() > 0) {
                messageManager.sendMessage(player, "teleport_warmup",
                        Placeholder.unparsed("seconds", String.valueOf(countdown.get())));
                EffectsUtil.spawnParticles(player.getLocation().add(0, 1, 0),
                        Particle.valueOf(configManager.getWarmupParticle()), 10);
                countdown.decrementAndGet();
            } else {
                teleportPlayer(player, location);
                setWarpCooldown(player);
                CancellableTask runningTask = teleportTasks.remove(player.getUniqueId());
                if (runningTask != null)
                    runningTask.cancel();
            }
        }, 0L, 20L);
        teleportTasks.put(player.getUniqueId(), task);
    }

    private void setWarpCooldown(Player player) {
        if (player.hasPermission("justteams.bypass.warp.cooldown")) {
            return;
        }
        int cooldownSeconds = configManager.getWarpCooldownSeconds();
        if (cooldownSeconds > 0) {
            warpCooldowns.put(player.getUniqueId(), Instant.now().plusSeconds(cooldownSeconds));
        }
    }

    public void deposit(Player player, double amount) {
        if (!configManager.isBankEnabled()) {
            messageManager.sendMessage(player, "feature_disabled");
            return;
        }
        Team team = getPlayerTeam(player.getUniqueId());
        if (team == null) {
            messageManager.sendMessage(player, "player_not_in_team");
            return;
        }
        if (amount <= 0) {
            messageManager.sendMessage(player, "bank_invalid_amount");
            return;
        }
        if (plugin.getEconomy() == null) {
            messageManager.sendMessage(player, "economy_not_available");
            return;
        }
        if (!plugin.getEconomy().has(player, amount)) {
            messageManager.sendMessage(player, "bank_insufficient_player_funds");
            return;
        }
        plugin.getEconomy().withdrawPlayer(player, amount);
        team.addBalance(amount);
        plugin.getTaskRunner().runAsync(() -> storage.updateTeamBalance(team.getId(), team.getBalance()));
        markTeamModified(team.getId());

        publishCrossServerUpdate(team.getId(), "BANK_DEPOSIT", player.getUniqueId().toString(), String.valueOf(amount));

        String formattedAmount = formatCurrency(amount);
        String formattedBalance = formatCurrency(team.getBalance());
        messageManager.sendMessage(player, "bank_deposit_success",
                Placeholder.unparsed("amount", formattedAmount),
                Placeholder.unparsed("balance", formattedBalance));
        EffectsUtil.playSound(player, EffectsUtil.SoundType.SUCCESS);
    }

    public void withdraw(Player player, double amount) {
        if (!configManager.isBankEnabled()) {
            messageManager.sendMessage(player, "feature_disabled");
            return;
        }
        Team team = getPlayerTeam(player.getUniqueId());
        if (team == null) {
            messageManager.sendMessage(player, "player_not_in_team");
            return;
        }
        TeamPlayer member = team.getMember(player.getUniqueId());
        if (member == null || (!member.canWithdraw() && !player.hasPermission("justteams.bypass.bank.withdraw"))) {
            messageManager.sendMessage(player, "no_permission");
            return;
        }
        if (amount <= 0) {
            messageManager.sendMessage(player, "bank_invalid_amount");
            return;
        }
        if (team.getBalance() < amount) {
            messageManager.sendMessage(player, "bank_insufficient_funds");
            return;
        }
        if (plugin.getEconomy() == null) {
            messageManager.sendMessage(player, "economy_not_available");
            return;
        }
        team.removeBalance(amount);
        plugin.getEconomy().depositPlayer(player, amount);
        plugin.getTaskRunner().runAsync(() -> storage.updateTeamBalance(team.getId(), team.getBalance()));
        markTeamModified(team.getId());

        publishCrossServerUpdate(team.getId(), "BANK_WITHDRAW", player.getUniqueId().toString(),
                String.valueOf(amount));

        String formattedAmount = formatCurrency(amount);
        String formattedBalance = formatCurrency(team.getBalance());
        messageManager.sendMessage(player, "bank_withdraw_success",
                Placeholder.unparsed("amount", formattedAmount),
                Placeholder.unparsed("balance", formattedBalance));
        EffectsUtil.playSound(player, EffectsUtil.SoundType.SUCCESS);
    }

    public void openEnderChest(Player player) {
        if (!plugin.getConfigManager().isTeamEnderchestEnabled()) {
            messageManager.sendMessage(player, "feature_disabled");
            EffectsUtil.playSound(player, EffectsUtil.SoundType.ERROR);
            return;
        }

        Team team = getPlayerTeam(player.getUniqueId());
        if (team == null) {
            messageManager.sendMessage(player, "not_in_team");
            EffectsUtil.playSound(player, EffectsUtil.SoundType.ERROR);
            return;
        }
        TeamPlayer member = team.getMember(player.getUniqueId());
        if (member == null
                || (!member.canUseEnderChest() && !player.hasPermission("justteams.bypass.enderchest.use"))) {
            messageManager.sendMessage(player, "no_permission");
            EffectsUtil.playSound(player, EffectsUtil.SoundType.ERROR);
            return;
        }
        if (plugin.getConfigManager().isSingleServerMode()) {
            loadAndOpenEnderChestDirect(player, team);
        } else {
            if (team.tryLockEnderChest()) {
                plugin.getTaskRunner().runAsync(() -> {
                    boolean lockAcquired = storage.acquireEnderChestLock(team.getId(),
                            configManager.getServerIdentifier());
                    plugin.getTaskRunner().runOnEntity(player, () -> {
                        if (lockAcquired) {
                            loadAndOpenEnderChest(player, team);
                        } else {
                            team.unlockEnderChest();
                            messageManager.sendMessage(player, "enderchest_locked_by_proxy");
                            EffectsUtil.playSound(player, EffectsUtil.SoundType.ERROR);
                        }
                    });
                });
            } else {
                messageManager.sendMessage(player, "enderchest_locked_by_proxy");
                EffectsUtil.playSound(player, EffectsUtil.SoundType.ERROR);
            }
        }
    }

    private void loadAndOpenEnderChest(Player player, Team team) {
        plugin.getTaskRunner().runAsync(() -> {
            String data = storage.getEnderChest(team.getId());
            if (plugin.getConfigManager().isDebugEnabled()) {
                plugin.getLogger().info("Loaded enderchest data for team " + team.getName() + ": "
                        + (data != null ? "data length: " + data.length() : "null"));
            }
            plugin.getTaskRunner().runOnEntity(player, () -> {
                int rows = configManager.getEnderChestRows();
                Inventory enderChest = Bukkit.createInventory(team, rows * 9, Component.text("ᴛᴇᴀᴍ ᴇɴᴅᴇʀ ᴄʜᴇsᴛ"));
                if (data != null && !data.isEmpty()) {
                    try {
                        InventoryUtil.deserializeInventory(enderChest, data);
                        if (plugin.getConfigManager().isDebugEnabled()) {
                            plugin.getLogger().info("Successfully deserialized enderchest for team " + team.getName());
                        }
                    } catch (IOException e) {
                        plugin.getLogger().warning(
                                "Could not deserialize ender chest for team " + team.getName() + ": " + e.getMessage());
                    }
                }
                team.setEnderChest(enderChest);
                if (plugin.getConfigManager().isDebugEnabled()) {
                    plugin.getLogger().info("Opening enderchest inventory for player " + player.getName());
                }
                player.openInventory(team.getEnderChest());
                messageManager.sendMessage(player, "enderchest_opened");
                EffectsUtil.playSound(player, EffectsUtil.SoundType.SUCCESS);
            });
        });
    }

    private void loadAndOpenEnderChestDirect(Player player, Team team) {
        if (!team.tryLockEnderChest()) {
            messageManager.sendMessage(player, "enderchest_locked_by_proxy");
            EffectsUtil.playSound(player, EffectsUtil.SoundType.ERROR);
            return;
        }

        plugin.getTaskRunner().runAsync(() -> {
            String data = storage.getEnderChest(team.getId());
            if (plugin.getConfigManager().isDebugEnabled()) {
                plugin.getLogger()
                        .info("Loading enderchest directly for team " + team.getName() + " (single-server mode)");
            }
            plugin.getTaskRunner().runOnEntity(player, () -> {
                int rows = configManager.getEnderChestRows();
                Inventory enderChest = Bukkit.createInventory(team, rows * 9, Component.text("ᴛᴇᴀᴍ ᴇɴᴅᴇʀ ᴄʜᴇsᴛ"));
                if (data != null && !data.isEmpty()) {
                    try {
                        InventoryUtil.deserializeInventory(enderChest, data);
                        if (plugin.getConfigManager().isDebugEnabled()) {
                            plugin.getLogger().info("Successfully deserialized enderchest for team " + team.getName());
                        }
                    } catch (IOException e) {
                        plugin.getLogger().warning(
                                "Could not deserialize ender chest for team " + team.getName() + ": " + e.getMessage());
                    }
                }
                team.setEnderChest(enderChest);
                if (plugin.getConfigManager().isDebugEnabled()) {
                    plugin.getLogger().info(
                            "Opening enderchest inventory for player " + player.getName() + " (single-server mode)");
                }

                if (player.hasPermission("justteams.admin.enderchest")) {
                    plugin.getLogger().info("Admin " + player.getName() + " successfully accessed enderchest for team: "
                            + team.getName());
                    messageManager.sendMessage(player, "admin_opened_enderchest",
                            Placeholder.unparsed("team_name", team.getName()));
                }

                player.openInventory(team.getEnderChest());
                messageManager.sendMessage(player, "enderchest_opened");
                EffectsUtil.playSound(player, EffectsUtil.SoundType.SUCCESS);
            });
        });
    }

    public void saveEnderChest(Team team) {
        if (team == null || team.getEnderChest() == null)
            return;

        if (!team.isEnderChestLocked()) {
            if (plugin.getConfigManager().isDebugEnabled()) {
                plugin.getLogger()
                        .warning("Attempted to save enderchest for team " + team.getName() + " without holding lock!");
            }
            return;
        }

        try {
            String data = InventoryUtil.serializeInventory(team.getEnderChest());
            storage.saveEnderChest(team.getId(), data);

            if (isCrossServerEnabled()) {
                sendCrossServerEnderChestUpdate(team.getId(), data);
            }

            if (plugin.getConfigManager().isDebugEnabled()) {
                plugin.getLogger().info(
                        "✓ Saved enderchest for team " + team.getName() + " (data length: " + data.length() + ")");
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Could not save ender chest for team " + team.getName() + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    public void saveAndReleaseEnderChest(Team team) {
        if (team == null || team.getEnderChest() == null)
            return;

        if (!team.isEnderChestLocked()) {
            if (plugin.getConfigManager().isDebugEnabled()) {
                plugin.getLogger()
                        .warning("Attempted to save enderchest for team " + team.getName() + " without holding lock!");
            }
            return;
        }

        try {
            String data = InventoryUtil.serializeInventory(team.getEnderChest());
            try {
                storage.saveEnderChest(team.getId(), data);
            } catch (Exception e) {
                plugin.getLogger()
                        .severe("Could not save ender chest for team " + team.getName() + ": " + e.getMessage());
                e.printStackTrace();
            }

            try {
                storage.releaseEnderChestLock(team.getId());
            } catch (Exception e) {
                plugin.getLogger().warning(
                        "Failed to release ender chest lock for team " + team.getName() + ": " + e.getMessage());
            }

            if (isCrossServerEnabled()) {
                sendCrossServerEnderChestUpdate(team.getId(), data);
            }

            if (plugin.getConfigManager().isDebugEnabled()) {
                plugin.getLogger().info("✓ Saved and released enderchest for team " + team.getName() + " (data length: "
                        + data.length() + ")");
            }
        } catch (Exception e) {
            plugin.getLogger()
                    .severe("Detailed error saving ender chest for team " + team.getName() + ": " + e.getMessage());
            e.printStackTrace();
        } finally {
            team.unlockEnderChest();
        }
    }

    public void saveAllOnlineTeamEnderChests() {
        teamNameCache.values().forEach(this::saveEnderChest);
    }

    public boolean isCrossServerEnabled() {
        return plugin.getConfigManager().isCrossServerSyncEnabled() && !plugin.getConfigManager().isSingleServerMode();
    }

    public void sendCrossServerEnderChestUpdate(int teamId, String enderChestData) {
        if (!isCrossServerEnabled())
            return;
        plugin.getTaskRunner().runAsync(() -> {
            try {
                publishCrossServerUpdate(teamId, "ENDERCHEST_UPDATED", "", enderChestData);
                plugin.getLogger().info("✓ Published cross-server enderchest update for team " + teamId
                        + " (data length: " + enderChestData.length() + ")");
            } catch (Exception e) {
                plugin.getLogger().warning(
                        "Failed to send cross-server enderchest update for team " + teamId + ": " + e.getMessage());
            }
        });
    }

    public void refreshEnderChestInventory(Team team) {
        if (team.getEnderChest() == null || team.getEnderChestViewers().isEmpty())
            return;
        plugin.getTaskRunner().run(() -> {
            for (UUID viewerUuid : team.getEnderChestViewers()) {
                Player viewer = Bukkit.getPlayer(viewerUuid);
                if (viewer != null && viewer.isOnline()) {
                    try {
                        viewer.closeInventory();
                        plugin.getTaskRunner().runOnEntity(viewer, () -> {
                            viewer.openInventory(team.getEnderChest());
                        });
                    } catch (Exception e) {
                        plugin.getLogger().warning(
                                "Failed to refresh enderchest for viewer " + viewer.getName() + ": " + e.getMessage());
                    }
                }
            }
        });
    }

    public void updateMemberPermissions(Player owner, UUID targetUuid, boolean canWithdraw, boolean canUseEnderChest,
            boolean canSetHome, boolean canUseHome) {
        Team team = getPlayerTeam(owner.getUniqueId());
        if (team == null || !team.isOwner(owner.getUniqueId())) {
            messageManager.sendMessage(owner, "not_owner");
            return;
        }
        TeamPlayer member = team.getMember(targetUuid);
        if (member == null) {
            return;
        }
        member.setCanWithdraw(canWithdraw);
        member.setCanUseEnderChest(canUseEnderChest);
        member.setCanSetHome(canSetHome);
        member.setCanUseHome(canUseHome);
        try {
            storage.updateMemberPermissions(team.getId(), targetUuid, canWithdraw, canUseEnderChest, canSetHome,
                    canUseHome);
            plugin.getLogger()
                    .info("Successfully updated permissions for " + targetUuid + " in team " + team.getName() +
                            " - canUseEnderChest: " + canUseEnderChest);
            markTeamModified(team.getId());
            forceMemberPermissionRefresh(team.getId(), targetUuid);
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to update permissions in database for " + targetUuid + " in team "
                    + team.getName() + ": " + e.getMessage());
            member.setCanWithdraw(!canWithdraw);
            member.setCanUseEnderChest(!canUseEnderChest);
            member.setCanSetHome(!canSetHome);
            member.setCanUseHome(!canUseHome);
            messageManager.sendMessage(owner, "permission_update_failed");
            EffectsUtil.playSound(owner, EffectsUtil.SoundType.ERROR);
            return;
        }
        Player targetPlayer = Bukkit.getPlayer(targetUuid);
        if (targetPlayer != null && targetPlayer.isOnline()) {
            plugin.getTaskRunner().runOnEntity(targetPlayer, () -> {
                if (targetPlayer.getOpenInventory().getTopInventory().getHolder() instanceof TeamGUI) {
                    new TeamGUI(plugin, team, targetPlayer).open();
                }
            });
        }
        if (owner.isOnline()) {
            plugin.getTaskRunner().runOnEntity(owner, () -> {
                if (owner.getOpenInventory().getTopInventory().getHolder() instanceof TeamGUI) {
                    new TeamGUI(plugin, team, owner).open();
                }
            });
        }
        forceTeamSync(team.getId());
        messageManager.sendMessage(owner, "permissions_updated");
        EffectsUtil.playSound(owner, EffectsUtil.SoundType.SUCCESS);
    }

    public void updateMemberEditingPermissions(Player owner, UUID targetUuid, boolean canEditMembers,
            boolean canEditCoOwners,
            boolean canKickMembers, boolean canPromoteMembers, boolean canDemoteMembers) {
        Team team = getPlayerTeam(owner.getUniqueId());
        if (team == null || !team.isOwner(owner.getUniqueId())) {
            messageManager.sendMessage(owner, "not_owner");
            return;
        }
        TeamPlayer member = team.getMember(targetUuid);
        if (member == null) {
            return;
        }
        member.setCanEditMembers(canEditMembers);
        member.setCanEditCoOwners(canEditCoOwners);
        member.setCanKickMembers(canKickMembers);
        member.setCanPromoteMembers(canPromoteMembers);
        member.setCanDemoteMembers(canDemoteMembers);
        try {
            storage.updateMemberEditingPermissions(team.getId(), targetUuid, canEditMembers, canEditCoOwners,
                    canKickMembers, canPromoteMembers, canDemoteMembers);
            plugin.getLogger()
                    .info("Successfully updated editing permissions for " + targetUuid + " in team " + team.getName());
            markTeamModified(team.getId());
            forceMemberPermissionRefresh(team.getId(), targetUuid);
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to update editing permissions in database for " + targetUuid + " in team "
                    + team.getName() + ": " + e.getMessage());
            member.setCanEditMembers(!canEditMembers);
            member.setCanEditCoOwners(!canEditCoOwners);
            member.setCanKickMembers(!canKickMembers);
            member.setCanPromoteMembers(!canPromoteMembers);
            member.setCanDemoteMembers(!canDemoteMembers);
            messageManager.sendMessage(owner, "permission_update_failed");
            EffectsUtil.playSound(owner, EffectsUtil.SoundType.ERROR);
            return;
        }
        Player targetPlayer = Bukkit.getPlayer(targetUuid);
        if (targetPlayer != null && targetPlayer.isOnline()) {
            plugin.getTaskRunner().runOnEntity(targetPlayer, () -> {
                if (targetPlayer.getOpenInventory().getTopInventory().getHolder() instanceof TeamGUI) {
                    new TeamGUI(plugin, team, targetPlayer).open();
                }
            });
        }
        if (owner.isOnline()) {
            plugin.getTaskRunner().runOnEntity(owner, () -> {
                if (owner.getOpenInventory().getTopInventory().getHolder() instanceof TeamGUI) {
                    new TeamGUI(plugin, team, owner).open();
                }
            });
        }
        forceTeamSync(team.getId());
        messageManager.sendMessage(owner, "editing_permissions_updated");
        EffectsUtil.playSound(owner, EffectsUtil.SoundType.SUCCESS);
    }

    public void toggleGlow(Player player) {
        if (!configManager.isTeamGlowEnabled()) {
            messageManager.sendMessage(player, "feature_disabled");
            return;
        }
        Team team = getPlayerTeam(player.getUniqueId());
        if (team == null) {
            messageManager.sendMessage(player, "player_not_in_team");
            return;
        }
        if (!team.hasElevatedPermissions(player.getUniqueId())) {
            messageManager.sendMessage(player, "must_be_owner_or_co_owner");
            return;
        }
        boolean newStatus = !team.isGlowEnabled();
        team.setGlowEnabled(newStatus);

        plugin.getTaskRunner().runAsync(() -> {
            storage.setTeamGlow(team.getId(), newStatus);
            markTeamModified(team.getId());

            publishCrossServerUpdate(team.getId(), "TEAM_UPDATED", player.getUniqueId().toString(),
                    "glow_toggle|" + newStatus);
        });

        plugin.getTaskRunner().runLater(() -> {
            plugin.getGlowManager().updateGlowForTeam(team);
        }, 5L);
        plugin.getGlowManager().updateGlowForTeam(team);

        if (newStatus) {
            messageManager.sendMessage(player, "team_glow_enabled");
        } else {
            messageManager.sendMessage(player, "team_glow_disabled");
        }
        EffectsUtil.playSound(player, EffectsUtil.SoundType.SUCCESS);
    }

    public void togglePublicStatus(Player player) {
        Team team = getPlayerTeam(player.getUniqueId());
        if (team == null) {
            messageManager.sendMessage(player, "player_not_in_team");
            return;
        }
        if (!team.hasElevatedPermissions(player.getUniqueId())) {
            messageManager.sendMessage(player, "must_be_owner_or_co_owner");
            return;
        }
        boolean newStatus = !team.isPublic();
        team.setPublic(newStatus);

        plugin.getTaskRunner().runAsync(() -> {
            storage.setPublicStatus(team.getId(), newStatus);
            markTeamModified(team.getId());

            publishCrossServerUpdate(team.getId(), "TEAM_UPDATED", player.getUniqueId().toString(),
                    "public_toggle|" + newStatus);
        });

        if (newStatus) {
            messageManager.sendMessage(player, "team_made_public");
        } else {
            messageManager.sendMessage(player, "team_made_private");
        }
        EffectsUtil.playSound(player, EffectsUtil.SoundType.SUCCESS);
    }

    public void joinTeam(Player player, String teamName) {
        if (getPlayerTeam(player.getUniqueId()) != null) {
            messageManager.sendMessage(player, "already_in_team");
            EffectsUtil.playSound(player, EffectsUtil.SoundType.ERROR);
            return;
        }
        Instant cooldown = joinRequestCooldowns.getIfPresent(player.getUniqueId());
        if (cooldown != null && Instant.now().isBefore(cooldown)) {
            long secondsLeft = Duration.between(Instant.now(), cooldown).toSeconds();
            messageManager.sendMessage(player, "teleport_cooldown", Placeholder.unparsed("time", secondsLeft + "s"));
            EffectsUtil.playSound(player, EffectsUtil.SoundType.ERROR);
            return;
        }
        plugin.getTaskRunner().runAsync(() -> {
            Optional<Team> teamOpt = storage.findTeamByName(teamName);
            if (teamOpt.isEmpty()) {
                plugin.getTaskRunner().runOnEntity(player, () -> {
                    messageManager.sendMessage(player, "team_not_found", Placeholder.unparsed("team", teamName));
                    EffectsUtil.playSound(player, EffectsUtil.SoundType.ERROR);
                });
                return;
            }
            Team team = teamOpt.get();
            if (team.getMembers().size() >= configManager.getMaxTeamSize()) {
                plugin.getTaskRunner().runOnEntity(player, () -> {
                    messageManager.sendMessage(player, "team_is_full");
                    EffectsUtil.playSound(player, EffectsUtil.SoundType.ERROR);
                });
                return;
            }
            try {
                if (storage.isPlayerBlacklisted(team.getId(), player.getUniqueId())) {
                    List<BlacklistedPlayer> blacklist = storage.getTeamBlacklist(team.getId());
                    BlacklistedPlayer blacklistedPlayer = blacklist.stream()
                            .filter(bp -> bp != null && bp.getPlayerUuid() != null
                                    && bp.getPlayerUuid().equals(player.getUniqueId()))
                            .findFirst()
                            .orElse(null);
                    String blacklisterName = blacklistedPlayer != null ? blacklistedPlayer.getBlacklistedByName()
                            : "Unknown";
                    messageManager.sendMessage(player, "player_is_blacklisted",
                            Placeholder.unparsed("target", player.getName()),
                            Placeholder.unparsed("blacklister", blacklisterName));
                    EffectsUtil.playSound(player, EffectsUtil.SoundType.ERROR);
                    return;
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Could not check blacklist status for player " + player.getName()
                        + " accepting invite to team " + team.getName() + ": " + e.getMessage());
            }
            ensureTeamFullyLoaded(team);
            if (team.isMember(player.getUniqueId())) {
                plugin.getTaskRunner().runOnEntity(player, () -> {
                    messageManager.sendMessage(player, "already_in_team");
                    EffectsUtil.playSound(player, EffectsUtil.SoundType.ERROR);
                });
                return;
            }
            if (team.isPublic()) {
                handlePublicTeamJoin(player, team);
            } else {
                plugin.getTaskRunner().runAsync(() -> {
                    if (storage.hasJoinRequest(team.getId(), player.getUniqueId())) {
                        plugin.getTaskRunner().runOnEntity(player, () -> messageManager.sendMessage(player,
                                "already_requested_to_join", Placeholder.unparsed("team", team.getName())));
                    } else {
                        storage.addJoinRequest(team.getId(), player.getUniqueId());
                        team.addJoinRequest(player.getUniqueId());
                        plugin.getTaskRunner().runOnEntity(player, () -> {
                            messageManager.sendMessage(player, "join_request_sent",
                                    Placeholder.unparsed("team", team.getName()));
                            team.getMembers().stream()
                                    .filter(m -> m.isOnline())
                                    .forEach(member -> {
                                        Player bukkitPlayer = member.getBukkitPlayer();
                                        if (bukkitPlayer != null) {
                                            messageManager.sendMessage(bukkitPlayer, "join_request_received",
                                                    Placeholder.unparsed("player", player.getName()));
                                        }
                                    });
                            joinRequestCooldowns.put(player.getUniqueId(), Instant.now().plusSeconds(60));
                        });
                    }
                });
            }
        });
    }

    private void handlePublicTeamJoin(Player player, Team team) {
        try {
            storage.addMemberToTeam(team.getId(), player.getUniqueId());
            storage.clearAllJoinRequests(player.getUniqueId());

            publishCrossServerUpdate(team.getId(), "MEMBER_JOINED", player.getUniqueId().toString(), player.getName());

            TeamPlayer newMember = new TeamPlayer(
                    player.getUniqueId(),
                    TeamRole.MEMBER,
                    Instant.now(),
                    false,
                    true,
                    false,
                    true);
            team.addMember(newMember);
            playerTeamCache.put(player.getUniqueId(), team);
            refreshTeamMembers(team);
            messageManager.sendMessage(player, "player_joined_public_team",
                    Placeholder.unparsed("team", team.getName()));
            team.broadcast("player_joined_team", Placeholder.unparsed("player", player.getName()));
            EffectsUtil.playSound(player, EffectsUtil.SoundType.SUCCESS);

            plugin.getGlowManager().refreshGlow(player);

            plugin.getWebhookHelper().sendPlayerJoinWebhook(player, team);
        } catch (Exception e) {
            plugin.getLogger()
                    .severe("Error handling public team join for " + player.getName() + ": " + e.getMessage());
            messageManager.sendMessage(player, "team_join_error");
            EffectsUtil.playSound(player, EffectsUtil.SoundType.ERROR);
        }
    }

    private void ensureTeamFullyLoaded(Team team) {
        try {
            synchronized (cacheLock) {
                List<TeamPlayer> freshMembers = storage.getTeamMembers(team.getId());
                team.getMembers().clear();
                team.getMembers().addAll(freshMembers);
                team.getMembers().forEach(member -> playerTeamCache.put(member.getPlayerUuid(), team));
                plugin.getLogger().info("Ensured team " + team.getName() + " is fully loaded with "
                        + team.getMembers().size() + " members");
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Error ensuring team " + team.getName() + " is fully loaded: " + e.getMessage());
        }
    }

    public void withdrawJoinRequest(Player player, String teamName) {
        plugin.getTaskRunner().runAsync(() -> {
            Optional<Team> teamOpt = storage.findTeamByName(teamName);
            plugin.getTaskRunner().runOnEntity(player, () -> {
                if (teamOpt.isEmpty()) {
                    messageManager.sendMessage(player, "team_not_found");
                    return;
                }
                Team team = teamOpt.get();
                if (storage.hasJoinRequest(team.getId(), player.getUniqueId())) {
                    storage.removeJoinRequest(team.getId(), player.getUniqueId());
                    team.removeJoinRequest(player.getUniqueId());
                    messageManager.sendMessage(player, "join_request_withdrawn",
                            Placeholder.unparsed("team", team.getName()));
                } else {
                    messageManager.sendMessage(player, "join_request_not_found",
                            Placeholder.unparsed("team", team.getName()));
                }
            });
        });
    }

    public void acceptJoinRequest(Team team, UUID targetUuid) {
        if (team == null)
            return;
        Player target = Bukkit.getPlayer(targetUuid);
        if (target == null) {
            plugin.getLogger()
                    .info("Accepting join request for offline player " + targetUuid + " to team " + team.getName());
        }
        if (team.isMember(targetUuid)) {
            if (target != null) {
                messageManager.sendMessage(target, "already_in_team");
            }
            return;
        }
        if (team.getMembers().size() >= configManager.getMaxTeamSize()) {
            if (target != null) {
                messageManager.sendMessage(target, "already_in_team");
            }
            return;
        }
        try {
            if (storage.isPlayerBlacklisted(team.getId(), targetUuid)) {
                if (target != null) {
                    messageManager.sendMessage(target, "player_is_blacklisted",
                            Placeholder.unparsed("target", target.getName()));
                }
                return;
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Could not check blacklist status for player " + targetUuid
                    + " accepting join request to team " + team.getName() + ": " + e.getMessage());
        }
        storage.removeJoinRequest(team.getId(), targetUuid);
        team.removeJoinRequest(targetUuid);
        storage.addMemberToTeam(team.getId(), targetUuid);
        TeamPlayer newMember = new TeamPlayer(targetUuid, TeamRole.MEMBER, Instant.now(), false, true, false, true);
        team.addMember(newMember);
        playerTeamCache.put(targetUuid, team);
        team.broadcast("player_joined_team",
                Placeholder.unparsed("player", target != null ? target.getName() : "Unknown Player"));
        if (target != null) {
            messageManager.sendMessage(target, "joined_team", Placeholder.unparsed("team", team.getName()));
            EffectsUtil.playSound(target, EffectsUtil.SoundType.SUCCESS);
        }
        forceTeamSync(team.getId());
        sendCrossServerTeamUpdate(team.getId(), "MEMBER_ADDED", targetUuid);
        refreshAllTeamMemberGUIs(team);
    }

    public void denyJoinRequest(Team team, UUID targetUuid) {
        storage.removeJoinRequest(team.getId(), targetUuid);
        team.removeJoinRequest(targetUuid);
        OfflinePlayer target = Bukkit.getOfflinePlayer(targetUuid);
        team.broadcast("request_denied_team",
                Placeholder.unparsed("player", target.getName() != null ? target.getName() : "A player"));
        if (target.isOnline()) {
            messageManager.sendMessage(target.getPlayer(), "request_denied_player",
                    Placeholder.unparsed("team", team.getName()));
        }
    }

    private String locationToString(Location location) {
        if (location == null)
            return null;
        return String.format("%s,%f,%f,%f,%f,%f",
                location.getWorld().getName(),
                location.getX(),
                location.getY(),
                location.getZ(),
                location.getYaw(),
                location.getPitch());
    }

    private Location stringToLocation(String locationString) {
        if (locationString == null || locationString.isEmpty())
            return null;
        String[] parts = locationString.split(",");
        if (parts.length != 6)
            return null;
        try {
            World world = Bukkit.getWorld(parts[0]);
            if (world == null)
                return null;
            double x = Double.parseDouble(parts[1]);
            double y = Double.parseDouble(parts[2]);
            double z = Double.parseDouble(parts[3]);
            float yaw = Float.parseFloat(parts[4]);
            float pitch = Float.parseFloat(parts[5]);
            return new Location(world, x, y, z, yaw, pitch);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public void setTeamWarp(Player player, String warpName, String password) {
        Team team = getPlayerTeam(player.getUniqueId());
        if (team == null) {
            messageManager.sendMessage(player, "player_not_in_team");
            return;
        }
        if (!team.hasElevatedPermissions(player.getUniqueId())) {
            messageManager.sendMessage(player, "must_be_owner_or_co_owner");
            return;
        }
        plugin.getTaskRunner().runAsync(() -> {
            int currentWarps = storage.getTeamWarpCount(team.getId());
            int maxWarps = 5;
            if (currentWarps >= maxWarps && !storage.teamWarpExists(team.getId(), warpName)) {
                plugin.getTaskRunner().runOnEntity(player, () -> messageManager.sendMessage(player,
                        "warp_limit_reached", Placeholder.unparsed("limit", String.valueOf(maxWarps))));
                return;
            }
            String serverName = configManager.getServerIdentifier();
            String locationString = locationToString(player.getLocation());
            if (storage.setTeamWarp(team.getId(), warpName, locationString, serverName, password)) {
                publishCrossServerUpdate(team.getId(), "WARP_CREATED", player.getUniqueId().toString(), warpName);

                plugin.getTaskRunner().runOnEntity(player,
                        () -> messageManager.sendMessage(player, "warp_set", Placeholder.unparsed("warp", warpName)));
            }
        });
    }

    public void deleteTeamWarp(Player player, String warpName) {
        Team team = getPlayerTeam(player.getUniqueId());
        if (team == null) {
            messageManager.sendMessage(player, "player_not_in_team");
            return;
        }
        plugin.getTaskRunner().runAsync(() -> {
            Optional<IDataStorage.TeamWarp> warpOpt = storage.getTeamWarp(team.getId(), warpName);
            if (warpOpt.isEmpty()) {
                plugin.getTaskRunner().runOnEntity(player, () -> messageManager.sendMessage(player, "warp_not_found"));
                return;
            }
            IDataStorage.TeamWarp warp = warpOpt.get();
            boolean canDelete = team.hasElevatedPermissions(player.getUniqueId()) ||
                    warp.name().equals(player.getName());
            if (!canDelete) {
                plugin.getTaskRunner().runOnEntity(player,
                        () -> messageManager.sendMessage(player, "must_be_owner_or_co_owner"));
                return;
            }
            if (storage.deleteTeamWarp(team.getId(), warpName)) {
                publishCrossServerUpdate(team.getId(), "WARP_DELETED", player.getUniqueId().toString(), warpName);

                plugin.getTaskRunner().runOnEntity(player, () -> messageManager.sendMessage(player, "warp_deleted",
                        Placeholder.unparsed("warp", warpName)));
            }
        });
    }

    public void teleportToTeamWarp(Player player, String warpName, String password) {
        Team team = getPlayerTeam(player.getUniqueId());
        if (team == null) {
            messageManager.sendMessage(player, "player_not_in_team");
            return;
        }
        if (checkWarpCooldown(player)) {
            return;
        }
        plugin.getTaskRunner().runAsync(() -> {
            Optional<IDataStorage.TeamWarp> warpOpt = storage.getTeamWarp(team.getId(), warpName);
            plugin.getTaskRunner().runOnEntity(player, () -> {
                if (warpOpt.isEmpty()) {
                    messageManager.sendMessage(player, "warp_not_found");
                    return;
                }
                IDataStorage.TeamWarp warp = warpOpt.get();
                if (warp.password() != null && !warp.password().equals(password)) {
                    if (password == null) {
                        messageManager.sendMessage(player, "warp_password_protected");
                        messageManager.sendMessage(player, "prompt_warp_password",
                                Placeholder.unparsed("warp", warpName));
                        plugin.getChatInputManager().awaitInput(player, null, input -> {
                            if (input.equalsIgnoreCase("cancel")) {
                                messageManager.sendMessage(player, "action_cancelled");
                                return;
                            }
                            teleportToTeamWarp(player, warpName, input);
                        });
                    } else {
                        messageManager.sendMessage(player, "warp_incorrect_password",
                                Placeholder.unparsed("warp", warpName));
                    }
                    return;
                }
                messageManager.sendMessage(player, "warp_teleport", Placeholder.unparsed("warp", warpName));
                String currentServer = configManager.getServerIdentifier();
                if (warp.serverName().equals(currentServer)) {
                    Location location = stringToLocation(warp.location());
                    if (location != null) {
                        startWarpTeleportWarmup(player, location);
                    }
                } else {
                    Location location = stringToLocation(warp.location());
                    if (location != null) {
                        messageManager.sendMessage(player, "proxy_not_enabled");
                    }
                }
            });
        });
    }

    private boolean checkWarpCooldown(Player player) {
        if (player.hasPermission("justteams.bypass.warp.cooldown")) {
            return false;
        }
        Instant cooldownEnd = warpCooldowns.get(player.getUniqueId());
        if (cooldownEnd != null && Instant.now().isBefore(cooldownEnd)) {
            long remainingSeconds = cooldownEnd.getEpochSecond() - Instant.now().getEpochSecond();
            messageManager.sendMessage(player, "warp_cooldown",
                    Placeholder.unparsed("seconds", String.valueOf(remainingSeconds)));
            return true;
        }
        return false;
    }

    public void openWarpsGUI(Player player) {
        Team team = getPlayerTeam(player.getUniqueId());
        if (team == null) {
            messageManager.sendMessage(player, "player_not_in_team");
            return;
        }
        try {
            Class<?> warpsGUIClass = Class.forName("eu.kotori.justTeams.gui.WarpsGUI");
            Object warpsGUI = warpsGUIClass.getConstructor(plugin.getClass(), Team.class, Player.class)
                    .newInstance(plugin, team, player);
            warpsGUIClass.getMethod("open").invoke(warpsGUI);
        } catch (Exception e) {
            listTeamWarps(player);
        }
    }

    public void listTeamWarps(Player player) {
        Team team = getPlayerTeam(player.getUniqueId());
        if (team == null) {
            messageManager.sendMessage(player, "player_not_in_team");
            return;
        }
        plugin.getTaskRunner().runAsync(() -> {
            List<IDataStorage.TeamWarp> warps = storage.getTeamWarps(team.getId());
            plugin.getTaskRunner().runOnEntity(player, () -> {
                if (warps.isEmpty()) {
                    messageManager.sendMessage(player, "no_warps_set");
                    return;
                }
                messageManager.sendMessage(player, "warp_list_header");
                for (IDataStorage.TeamWarp warp : warps) {
                    String statusIcon = warp.password() != null ? "🔒" : "";
                    messageManager.sendMessage(player, "warp_list_entry",
                            Placeholder.unparsed("warp_name", warp.name()),
                            Placeholder.unparsed("status_icon", statusIcon));
                }
                messageManager.sendMessage(player, "warp_list_footer");
            });
        });
    }

    public void syncCrossServerData() {
        if (!plugin.getConfigManager().isCrossServerSyncEnabled()) {
            return;
        }

        if (syncInProgress.get()) {
            return;
        }

        syncInProgress.set(true);
        plugin.getTaskRunner().runAsync(() -> {
            try {
                long startTime = System.currentTimeMillis();

                Set<String> teamNames = new HashSet<>();
                synchronized (cacheLock) {
                    teamNames.addAll(teamNameCache.keySet());
                }

                if (teamNames.isEmpty()) {
                    return;
                }

                int maxBatchSize = plugin.getConfigManager().getMaxTeamsPerBatch();
                List<String> teamNamesList = new ArrayList<>(teamNames);

                for (int i = 0; i < teamNamesList.size(); i += maxBatchSize) {
                    int endIndex = Math.min(i + maxBatchSize, teamNamesList.size());
                    List<String> batch = teamNamesList.subList(i, endIndex);

                    plugin.getTaskRunner().runAsync(() -> {
                        for (String teamName : batch) {
                            try {
                                Optional<Team> dbTeam = storage.findTeamByName(teamName);
                                if (dbTeam.isPresent()) {
                                    synchronized (cacheLock) {
                                        Team cachedTeam = teamNameCache.get(teamName);
                                        if (cachedTeam != null) {
                                            syncTeamDataAsync(cachedTeam, dbTeam.get());
                                        }
                                    }
                                }
                            } catch (Exception e) {
                                plugin.getLogger().warning("Error syncing team " + teamName + ": " + e.getMessage());
                            }
                        }
                    });

                    if (endIndex < teamNamesList.size()) {
                        try {
                            Thread.sleep(20);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }

                long duration = System.currentTimeMillis() - startTime;
                if (plugin.getConfigManager().isDebugEnabled() && duration > 100) {
                    plugin.getLogger().info(
                            "Cross-server sync completed in " + duration + "ms for " + teamNames.size() + " teams");
                }

            } catch (Exception e) {
                plugin.getLogger().warning("Error during cross-server sync: " + e.getMessage());
                if (plugin.getConfigManager().isDebugEnabled()) {
                    plugin.getLogger().log(java.util.logging.Level.FINE, "Cross-server sync error details", e);
                }
            } finally {
                syncInProgress.set(false);
            }
        });
    }

    private void syncTeamDataAsync(Team cachedTeam, Team databaseTeam) {
        plugin.getTaskRunner().runAsync(() -> {
            try {
                List<UUID> databaseJoinRequests = storage.getJoinRequests(databaseTeam.getId());
                plugin.getTaskRunner().run(() -> {
                    List<UUID> cachedJoinRequests = cachedTeam.getJoinRequests();
                    for (UUID requestUuid : databaseJoinRequests) {
                        if (!cachedJoinRequests.contains(requestUuid)) {
                            cachedTeam.addJoinRequest(requestUuid);
                            if (plugin.getConfigManager().isDebugEnabled()) {
                                plugin.getLogger().info("Synced join request for team " + databaseTeam.getName()
                                        + " from player " + requestUuid);
                            }
                            for (TeamPlayer member : cachedTeam.getMembers()) {
                                if (member.isOnline() && cachedTeam.hasElevatedPermissions(member.getPlayerUuid())) {
                                    messageManager.sendMessage(member.getBukkitPlayer(), "join_request_notification",
                                            net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
                                                    .unparsed("player", "a player"));
                                }
                            }
                        }
                    }
                });
            } catch (Exception e) {
                plugin.getLogger()
                        .warning("Error in async team sync for " + cachedTeam.getName() + ": " + e.getMessage());
            }
        });
    }

    private void syncTeamData(Team cachedTeam, Team databaseTeam) {
        List<UUID> databaseJoinRequests = storage.getJoinRequests(databaseTeam.getId());
        List<UUID> cachedJoinRequests = cachedTeam.getJoinRequests();
        for (UUID requestUuid : databaseJoinRequests) {
            if (!cachedJoinRequests.contains(requestUuid)) {
                cachedTeam.addJoinRequest(requestUuid);
                plugin.getLogger()
                        .info("Synced join request for team " + databaseTeam.getName() + " from player " + requestUuid);
                for (TeamPlayer member : cachedTeam.getMembers()) {
                    if (member.isOnline() && cachedTeam.hasElevatedPermissions(member.getPlayerUuid())) {
                        messageManager.sendMessage(member.getBukkitPlayer(), "join_request_notification",
                                net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("player",
                                        "a player"));
                    }
                }
            }
        }
        for (UUID requestUuid : cachedJoinRequests) {
            if (!databaseJoinRequests.contains(requestUuid)) {
                cachedTeam.removeJoinRequest(requestUuid);
                plugin.getLogger().info("Removed stale join request for team " + databaseTeam.getName()
                        + " from player " + requestUuid);
            }
        }
    }

    public void syncCriticalUpdates() {
        if (!plugin.getConfigManager().isCrossServerSyncEnabled()) {
            return;
        }
        plugin.getTaskRunner().runAsync(() -> {
            try {
                List<CrossServerUpdate> pendingUpdates = storage
                        .getCrossServerUpdates(plugin.getConfigManager().getServerIdentifier());
                if (pendingUpdates.isEmpty()) {
                    return;
                }
                int processedCount = processCrossServerUpdatesWithRetry();
                if (processedCount > 0 && plugin.getConfigManager().isDebugLoggingEnabled()) {
                    plugin.getDebugLogger().log("Processed " + processedCount + " cross-server updates");
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Error during critical updates sync: " + e.getMessage());
                if (plugin.getConfigManager().isDebugEnabled()) {
                    plugin.getLogger().log(java.util.logging.Level.FINE, "Critical updates sync error details", e);
                }
            }
        });
    }

    private int processCrossServerUpdatesWithRetry() {
        int maxRetries = plugin.getConfigManager().getMaxSyncRetries();
        int retryDelay = plugin.getConfigManager().getSyncRetryDelay();
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                return processCrossServerUpdates();
            } catch (Exception e) {
                if (attempt == maxRetries) {
                    plugin.getLogger().severe("Failed to process cross-server updates after " + maxRetries
                            + " attempts: " + e.getMessage());
                    if (plugin.getConfigManager().isDebugEnabled()) {
                        plugin.getLogger().log(java.util.logging.Level.FINE, "Cross-server updates retry error details",
                                e);
                    }
                    return 0;
                } else {
                    plugin.getLogger().warning("Cross-server update attempt " + (attempt + 1) + " failed, retrying in "
                            + retryDelay + "ms: " + e.getMessage());
                    try {
                        Thread.sleep(retryDelay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        return 0;
    }

    private final ConcurrentHashMap<Integer, Long> lastSyncTimes = new ConcurrentHashMap<>();
    private static final long SYNC_COOLDOWN = 5000;

    public void forceTeamSync(int teamId) {
        if (!plugin.getConfigManager().isCrossServerSyncEnabled()) {
            return;
        }
        long currentTime = System.currentTimeMillis();
        long lastSyncTime = lastSyncTimes.getOrDefault(teamId, 0L);
        if (currentTime - lastSyncTime < SYNC_COOLDOWN) {
            if (plugin.getConfigManager().isDebugEnabled()) {
                plugin.getLogger().fine("Skipping force sync for team " + teamId + " due to cooldown");
            }
            return;
        }
        lastSyncTimes.put(teamId, currentTime);
        plugin.getTaskRunner().runAsync(() -> {
            try {
                Optional<Team> databaseTeamOpt = storage.findTeamById(teamId);
                if (databaseTeamOpt.isPresent()) {
                    Team databaseTeam = databaseTeamOpt.get();
                    Team cachedTeam = teamNameCache.values().stream()
                            .filter(team -> team.getId() == teamId)
                            .findFirst()
                            .orElse(null);
                    if (cachedTeam != null) {
                        syncTeamDataOptimized(cachedTeam, databaseTeam);
                        if (plugin.getConfigManager().isDebugEnabled()) {
                            plugin.getDebugLogger()
                                    .log("Force synced team " + databaseTeam.getName() + " (ID: " + teamId + ")");
                        }
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Error during force team sync for ID " + teamId + ": " + e.getMessage());
                if (plugin.getConfigManager().isDebugEnabled()) {
                    plugin.getLogger().log(java.util.logging.Level.FINE, "Force team sync error details", e);
                }
            }
        });
    }

    private void refreshPlayerGUIIfOpen(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }

        plugin.getTaskRunner().runOnEntity(player, () -> {
            try {
                org.bukkit.inventory.InventoryView openInventory = player.getOpenInventory();
                if (openInventory != null && openInventory.getTopInventory()
                        .getHolder() instanceof eu.kotori.justTeams.gui.TeamGUI teamGUI) {
                    teamGUI.refresh();

                    if (plugin.getConfigManager().isDebugEnabled()) {
                        plugin.getDebugLogger()
                                .log("Refreshed TeamGUI for " + player.getName() + " after team data change");
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to refresh GUI for " + player.getName() + ": " + e.getMessage());
                if (plugin.getConfigManager().isDebugEnabled()) {
                    plugin.getLogger().log(java.util.logging.Level.FINE, "GUI refresh error details", e);
                }
            }
        });
    }

    public void refreshTeamGUIsForAllMembers(Team team) {
        if (team == null) {
            return;
        }

        for (TeamPlayer member : team.getMembers()) {
            Player player = member.getBukkitPlayer();
            if (player != null && player.isOnline()) {
                refreshPlayerGUIIfOpen(player);
            }
        }
    }

    private void syncTeamDataOptimized(Team cachedTeam, Team databaseTeam) {
        try {
            boolean needsUpdate = false;
            if (!cachedTeam.getName().equals(databaseTeam.getName()) ||
                    !cachedTeam.getTag().equals(databaseTeam.getTag()) ||
                    cachedTeam.isPvpEnabled() != databaseTeam.isPvpEnabled() ||
                    cachedTeam.isPublic() != databaseTeam.isPublic() ||
                    cachedTeam.getBalance() != databaseTeam.getBalance() ||
                    cachedTeam.getKills() != databaseTeam.getKills() ||
                    cachedTeam.getDeaths() != databaseTeam.getDeaths()) {
                needsUpdate = true;
            }
            if (!cachedTeam.getOwnerUuid().equals(databaseTeam.getOwnerUuid())) {
                needsUpdate = true;
            }
            if ((cachedTeam.getHomeLocation() == null) != (databaseTeam.getHomeLocation() == null) ||
                    (cachedTeam.getHomeLocation() != null && databaseTeam.getHomeLocation() != null &&
                            !cachedTeam.getHomeLocation().equals(databaseTeam.getHomeLocation()))) {
                needsUpdate = true;
            }
            if (needsUpdate) {
                updateCachedTeamFromDatabase(cachedTeam, databaseTeam);
                plugin.getDebugLogger().log("Synced team " + cachedTeam.getName() + " with database changes");
            }
        } catch (Exception e) {
            plugin.getLogger()
                    .warning("Error during optimized team sync for " + cachedTeam.getName() + ": " + e.getMessage());
            if (plugin.getConfigManager().isDebugEnabled()) {
                plugin.getLogger().log(java.util.logging.Level.FINE, "Optimized team sync error details", e);
            }
        }
    }

    private void updateCachedTeamFromDatabase(Team cachedTeam, Team databaseTeam) {
        try {
            cachedTeam.setName(databaseTeam.getName());
            cachedTeam.setTag(databaseTeam.getTag());
            cachedTeam.setDescription(databaseTeam.getDescription());
            cachedTeam.setPvpEnabled(databaseTeam.isPvpEnabled());
            cachedTeam.setPublic(databaseTeam.isPublic());
            cachedTeam.setBalance(databaseTeam.getBalance());
            cachedTeam.setKills(databaseTeam.getKills());
            cachedTeam.setDeaths(databaseTeam.getDeaths());
            if (databaseTeam.getHomeLocation() != null) {
                cachedTeam.setHomeLocation(databaseTeam.getHomeLocation());
                cachedTeam.setHomeServer(databaseTeam.getHomeServer());
            }
            if (!cachedTeam.getOwnerUuid().equals(databaseTeam.getOwnerUuid())) {
                cachedTeam.setOwnerUuid(databaseTeam.getOwnerUuid());
                plugin.getLogger()
                        .info("Team " + cachedTeam.getName() + " ownership changed to " + databaseTeam.getOwnerUuid());
            }
            List<TeamPlayer> databaseMembers = storage.getTeamMembers(cachedTeam.getId());
            cachedTeam.getMembers().clear();
            for (TeamPlayer member : databaseMembers) {
                cachedTeam.addMember(member);
            }
        } catch (Exception e) {
            plugin.getLogger().warning(
                    "Error updating cached team " + cachedTeam.getName() + " from database: " + e.getMessage());
        }
    }

    private void sendCrossServerTeamUpdate(int teamId, String updateType, UUID playerUuid) {
        if (!plugin.getConfigManager().isCrossServerSyncEnabled()) {
            return;
        }
        plugin.getTaskRunner().runAsync(() -> {
            try {
                storage.addCrossServerUpdate(teamId, updateType, playerUuid.toString(),
                        plugin.getConfigManager().getServerIdentifier());
                plugin.getLogger().fine("Sent cross-server update: " + updateType + " for team " + teamId);
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to send cross-server update: " + e.getMessage());
            }
        });
    }

    private final List<CrossServerUpdate> pendingCrossServerUpdates = new CopyOnWriteArrayList<>();
    private final Object crossServerUpdateLock = new Object();

    private void sendCrossServerTeamUpdateBatch(int teamId, String updateType, UUID playerUuid) {
        if (!plugin.getConfigManager().isCrossServerSyncEnabled()) {
            return;
        }
        synchronized (crossServerUpdateLock) {
            pendingCrossServerUpdates.add(new CrossServerUpdate(0, teamId, updateType, playerUuid.toString(),
                    plugin.getConfigManager().getServerIdentifier(),
                    new java.sql.Timestamp(System.currentTimeMillis())));
            if (pendingCrossServerUpdates.size() >= plugin.getConfigManager().getMaxBatchSize()) {
                flushCrossServerUpdates();
            }
        }
    }

    public void flushCrossServerUpdates() {
        if (pendingCrossServerUpdates.isEmpty())
            return;
        List<CrossServerUpdate> updatesToSend;
        synchronized (crossServerUpdateLock) {
            updatesToSend = new ArrayList<>(pendingCrossServerUpdates);
            pendingCrossServerUpdates.clear();
        }
        if (!updatesToSend.isEmpty()) {
            plugin.getTaskRunner().runAsync(() -> {
                try {
                    storage.addCrossServerUpdatesBatch(updatesToSend);
                    plugin.getLogger().fine("Sent " + updatesToSend.size() + " cross-server updates in batch");
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to send cross-server updates batch: " + e.getMessage());
                }
            });
        }
    }

    public int processCrossServerMessages() {
        if (!plugin.getConfigManager().isCrossServerSyncEnabled()) {
            return 0;
        }
        try {
            List<IDataStorage.CrossServerMessage> messages = storage.getCrossServerMessages(
                    plugin.getConfigManager().getServerIdentifier());
            int processedCount = 0;

            for (IDataStorage.CrossServerMessage msg : messages) {
                try {
                    processCrossServerMessage(msg);
                    storage.removeCrossServerMessage(msg.id());
                    processedCount++;
                } catch (Exception e) {
                    plugin.getLogger()
                            .warning("Failed to process cross-server message " + msg.id() + ": " + e.getMessage());
                }
            }

            if (processedCount > 0) {
                plugin.getLogger().info("Processed " + processedCount + " cross-server team chat messages from MySQL");
            }

            return processedCount;
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to process cross-server messages: " + e.getMessage());
            return 0;
        }
    }

    private void processCrossServerMessage(IDataStorage.CrossServerMessage msg) {
        plugin.getTaskRunner().run(() -> {
            try {
                Team team = teamNameCache.values().stream()
                        .filter(t -> t.getId() == msg.teamId())
                        .findFirst()
                        .orElse(null);

                if (team == null) {
                    Optional<Team> dbTeam = storage.findTeamById(msg.teamId());
                    if (dbTeam.isPresent()) {
                        team = dbTeam.get();
                    } else {
                        plugin.getLogger().warning("Team " + msg.teamId() + " not found for cross-server message");
                        return;
                    }
                }

                final Team finalTeam = team;

                UUID senderUuid = UUID.fromString(msg.playerUuid());
                Optional<String> playerNameOpt = storage.getPlayerNameByUuid(senderUuid);
                String playerName = playerNameOpt.orElse("Unknown");

                String playerPrefix = "";
                String playerSuffix = "";
                Player onlineSender = plugin.getServer().getPlayer(senderUuid);
                if (onlineSender != null && onlineSender.isOnline()) {
                    playerPrefix = plugin.getPlayerPrefix(onlineSender);
                    playerSuffix = plugin.getPlayerSuffix(onlineSender);
                }
                String format = messageManager.getRawMessage("team_chat_format");
                Component formattedMessage = plugin.getMiniMessage().deserialize(format,
                        net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("player", playerName),
                        net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("prefix", playerPrefix),
                        net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("player_prefix",
                                playerPrefix),
                        net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("suffix", playerSuffix),
                        net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("player_suffix",
                                playerSuffix),
                        net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("team_name",
                                finalTeam.getName()),
                        net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("message",
                                msg.message()));

                int recipientCount = 0;
                for (TeamPlayer member : finalTeam.getMembers()) {
                    Player onlinePlayer = member.getBukkitPlayer();
                    if (onlinePlayer != null && onlinePlayer.isOnline()) {
                        onlinePlayer.sendMessage(formattedMessage);
                        recipientCount++;
                    }
                }

                if (recipientCount > 0) {
                    plugin.getLogger().info("Delivered cross-server chat from " + playerName +
                            " (Server: " + msg.serverName() + ") to " + recipientCount + " players on this server");
                }

            } catch (Exception e) {
                plugin.getLogger().warning("Failed to process cross-server chat message: " + e.getMessage());
                if (plugin.getConfigManager().isDebugEnabled()) {
                    e.printStackTrace();
                }
            }
        });
    }

    public int processCrossServerUpdates() {
        if (!plugin.getConfigManager().isCrossServerSyncEnabled()) {
            return 0;
        }
        try {
            List<CrossServerUpdate> updates = storage
                    .getCrossServerUpdates(plugin.getConfigManager().getServerIdentifier());
            int processedCount = 0;
            for (CrossServerUpdate update : updates) {
                try {
                    processCrossServerUpdate(update);
                    storage.removeCrossServerUpdate(update.id());
                    processedCount++;
                } catch (Exception e) {
                    plugin.getLogger()
                            .warning("Failed to process cross-server update " + update.id() + ": " + e.getMessage());
                }
            }
            return processedCount;
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to process cross-server updates: " + e.getMessage());
            return 0;
        }
    }

    private void processCrossServerUpdate(CrossServerUpdate update) {
        plugin.getTaskRunner().run(() -> {
            try {
                Team team = teamNameCache.values().stream()
                        .filter(t -> t.getId() == update.teamId())
                        .findFirst()
                        .orElse(null);
                if (team == null) {
                    Optional<Team> dbTeam = storage.findTeamById(update.teamId());
                    if (dbTeam.isPresent()) {
                        loadTeamIntoCache(dbTeam.get());
                        team = dbTeam.get();
                    }
                }

                final Team finalTeam = team;

                if (finalTeam != null) {
                    switch (update.updateType()) {
                        case "PLAYER_INVITED" -> {
                            try {
                                UUID invitedPlayerUuid = UUID.fromString(update.playerUuid());

                                List<String> invites = teamInvites.getIfPresent(invitedPlayerUuid);
                                if (invites == null) {
                                    invites = new ArrayList<>();
                                }
                                if (!invites.contains(finalTeam.getName().toLowerCase())) {
                                    invites.add(finalTeam.getName().toLowerCase());
                                    teamInvites.put(invitedPlayerUuid, invites);
                                }

                                Player onlinePlayer = Bukkit.getPlayer(invitedPlayerUuid);
                                if (onlinePlayer != null && onlinePlayer.isOnline()) {
                                    plugin.getTaskRunner().runOnEntity(onlinePlayer, () -> {
                                        messageManager.sendRawMessage(onlinePlayer,
                                                messageManager.getRawMessage("prefix") +
                                                        messageManager.getRawMessage("invite_received")
                                                                .replace("<team>", finalTeam.getName()));
                                        messageManager.sendMessage(onlinePlayer, "pending_invites_singular");
                                        EffectsUtil.playSound(onlinePlayer, EffectsUtil.SoundType.SUCCESS);
                                    });
                                }

                                plugin.getLogger().info("Processed cross-server invite for player " + invitedPlayerUuid
                                        + " to team: " + finalTeam.getName());
                            } catch (IllegalArgumentException e) {
                                plugin.getLogger().warning(
                                        "Invalid player UUID in PLAYER_INVITED update: " + update.playerUuid());
                            }
                        }
                        case "MEMBER_ADDED" -> {
                            forceTeamSync(finalTeam.getId());
                            plugin.getLogger()
                                    .info("Processed cross-server member addition for team: " + finalTeam.getName());
                        }
                        case "MEMBER_REMOVED" -> {
                            forceTeamSync(finalTeam.getId());
                            plugin.getLogger()
                                    .info("Processed cross-server member removal for team: " + finalTeam.getName());
                        }
                        case "TEAM_UPDATED", "GLOW_TOGGLED" -> {
                            forceTeamSync(finalTeam.getId());
                            plugin.getLogger()
                                    .info("Processed cross-server team update for team: " + finalTeam.getName());
                            plugin.getTaskRunner().runLater(() -> {
                                plugin.getGlowManager().updateGlowForTeam(finalTeam);
                            }, 40L);
                        }
                        case "PUBLIC_STATUS_CHANGED", "PVP_STATUS_CHANGED" -> {
                            forceTeamSync(finalTeam.getId());
                            plugin.getLogger().info("Processed cross-server " + update.updateType() + " for team: "
                                    + finalTeam.getName());
                        }
                        case "ADMIN_BALANCE_SET", "ADMIN_STATS_SET" -> {
                            forceTeamSync(finalTeam.getId());
                            plugin.getLogger().info("Processed cross-server admin update (" + update.updateType()
                                    + ") for team: " + finalTeam.getName());
                        }
                        case "ADMIN_PERMISSION_UPDATE" -> {
                            try {
                                String[] parts = update.playerUuid().split(":");
                                if (parts.length == 3) {
                                    UUID memberUuid = UUID.fromString(parts[0]);
                                    String permission = parts[1];
                                    boolean value = Boolean.parseBoolean(parts[2]);

                                    forceMemberPermissionRefresh(finalTeam.getId(), memberUuid);
                                    plugin.getLogger().info("Processed cross-server admin permission update for member "
                                            + memberUuid + " in team: " + finalTeam.getName());
                                }
                            } catch (Exception e) {
                                plugin.getLogger().warning(
                                        "Failed to parse ADMIN_PERMISSION_UPDATE data: " + update.playerUuid());
                            }
                        }
                        case "ADMIN_MEMBER_KICK" -> {
                            try {
                                UUID memberUuid = UUID.fromString(update.playerUuid());
                                finalTeam.removeMember(memberUuid);
                                playerTeamCache.remove(memberUuid);
                                plugin.getLogger().info("Processed cross-server admin kick for member " + memberUuid
                                        + " from team: " + finalTeam.getName());
                            } catch (Exception e) {
                                plugin.getLogger().warning(
                                        "Failed to parse ADMIN_MEMBER_KICK playerUuid: " + update.playerUuid());
                            }
                        }
                        case "ADMIN_MEMBER_PROMOTE", "ADMIN_MEMBER_DEMOTE" -> {
                            try {
                                UUID memberUuid = UUID.fromString(update.playerUuid());
                                forceMemberPermissionRefresh(finalTeam.getId(), memberUuid);
                                plugin.getLogger().info("Processed cross-server admin " + update.updateType()
                                        + " for member " + memberUuid + " in team: " + finalTeam.getName());
                            } catch (Exception e) {
                                plugin.getLogger().warning("Failed to parse " + update.updateType() + " playerUuid: "
                                        + update.playerUuid());
                            }
                        }
                        case "ENDERCHEST_UPDATED" -> {
                            plugin.getTaskRunner().runAsync(() -> {
                                try {
                                    if (!finalTeam.isEnderChestLocked()) {
                                        String serializedData = storage.getEnderChest(finalTeam.getId());
                                        if (serializedData != null && !serializedData.isEmpty()) {
                                            Inventory enderChest = finalTeam.getEnderChest();
                                            if (enderChest == null) {
                                                enderChest = Bukkit.createInventory(null, 27, "Team Enderchest");
                                                finalTeam.setEnderChest(enderChest);
                                            }
                                            final Inventory finalEnderChest = enderChest;
                                            InventoryUtil.deserializeInventory(finalEnderChest, serializedData);
                                            plugin.getTaskRunner().run(() -> {
                                                refreshEnderChestInventory(finalTeam);
                                            });
                                            plugin.getLogger().info(
                                                    "✓ Enderchest reloaded from database (MySQL fallback) for team: "
                                                            + finalTeam.getName());
                                        }
                                    } else {
                                        plugin.getLogger().info("Skipped enderchest update (lock held) for team: "
                                                + finalTeam.getName());
                                    }
                                } catch (Exception e) {
                                    plugin.getLogger()
                                            .warning("Failed to process ENDERCHEST_UPDATED (MySQL fallback) for team "
                                                    + finalTeam.getName() + ": " + e.getMessage());
                                }
                            });
                        }
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Failed to process cross-server update: " + e.getMessage());
            }
        });
    }

    public void cleanupExpiredCache() {
        synchronized (cacheLock) {
            try {
                homeCooldowns.entrySet().removeIf(entry -> Instant.now().isAfter(entry.getValue()));
                warpCooldowns.entrySet().removeIf(entry -> Instant.now().isAfter(entry.getValue()));
                teamStatusCooldowns.entrySet().removeIf(entry -> Instant.now().isAfter(entry.getValue()));
                teleportTasks.entrySet().removeIf(entry -> {
                    CancellableTask task = entry.getValue();
                    return task == null;
                });
                plugin.getLogger().fine("Cache cleanup completed. Team cache size: " + teamNameCache.size());
            } catch (Exception e) {
                plugin.getLogger().warning("Error during cache cleanup: " + e.getMessage());
            }
        }
    }

    public void shutdown() {
        synchronized (cacheLock) {
            plugin.getLogger().info("TeamManager shutdown initiated. Saving all pending changes...");
            try {
                saveAllOnlineTeamEnderChests();
                forceSaveAllTeamData();
                flushCrossServerUpdates();
                cleanupExpiredCache();
                plugin.getLogger().info("TeamManager shutdown completed successfully.");
            } catch (Exception e) {
                plugin.getLogger().severe("Error during TeamManager shutdown: " + e.getMessage());
            }
        }
    }

    private void refreshTeamMembers(Team team) {
        long onlineCount = team.getMembers().stream().filter(TeamPlayer::isOnline).count();
        if (plugin.getConfigManager().isDebugEnabled()) {
            plugin.getLogger().info("Team " + team.getName() + " has " + team.getMembers().size() +
                    " total members (" + onlineCount + " online).");
        }
    }

    public void refreshAllTeamMemberGUIs(Team team) {
        if (team == null)
            return;
        team.getMembers().stream()
                .filter(TeamPlayer::isOnline)
                .forEach(member -> {
                    Player player = member.getBukkitPlayer();
                    if (player != null) {
                        plugin.getTaskRunner().runOnEntity(player, () -> {
                            if (player.getOpenInventory().getTopInventory().getHolder() instanceof TeamGUI) {
                                new TeamGUI(plugin, team, player).open();
                            }
                        });
                    }
                });
    }

    private void refreshTeamData(int teamId) {
        plugin.getTaskRunner().runAsync(() -> {
            Optional<Team> refreshedTeam = storage.findTeamById(teamId);
            if (refreshedTeam.isPresent()) {
                Team team = refreshedTeam.get();
                List<TeamPlayer> members = storage.getTeamMembers(teamId);
                team.getMembers().clear();
                team.getMembers().addAll(members);
                teamNameCache.put(team.getName().toLowerCase(), team);
                for (TeamPlayer member : members) {
                    playerTeamCache.put(member.getPlayerUuid(), team);
                }
                plugin.getLogger().info(
                        "Refreshed team " + team.getName() + " with " + members.size() + " members from database");
                if (plugin.getConfigManager().isDebugEnabled()) {
                    for (TeamPlayer member : members) {
                        plugin.getLogger().info("Member " + member.getPlayerUuid()
                                + " permissions after refresh - canUseEnderChest: " + member.canUseEnderChest());
                    }
                }
            }
        });
    }

    public void forceTeamRefresh(int teamId) {
        plugin.getTaskRunner().runAsync(() -> {
            Optional<Team> teamOpt = storage.findTeamById(teamId);
            if (teamOpt.isPresent()) {
                Team team = teamOpt.get();
                refreshTeamData(teamId);
                plugin.getTaskRunner().run(() -> refreshAllTeamMemberGUIs(team));
            }
        });
    }

    public void forceTeamRefreshFromDatabase(int teamId) {
        plugin.getTaskRunner().runAsync(() -> {
            try {
                Optional<Team> freshTeam = storage.findTeamById(teamId);
                if (freshTeam.isPresent()) {
                    Team team = freshTeam.get();
                    teamNameCache.put(team.getName().toLowerCase(), team);
                    plugin.getLogger().info("Successfully refreshed team " + team.getName() + " from database");
                    refreshAllTeamMemberGUIs(team);
                } else {
                    plugin.getLogger().warning("Could not find team with ID " + teamId + " in database");
                }
            } catch (Exception e) {
                plugin.getLogger().severe("Error refreshing team " + teamId + " from database: " + e.getMessage());
            }
        });
    }

    public void forceMemberPermissionRefresh(int teamId, UUID memberUuid) {
        try {
            Optional<Team> teamOpt = storage.findTeamById(teamId);
            if (teamOpt.isPresent()) {
                Team team = teamOpt.get();
                TeamPlayer member = team.getMember(memberUuid);
                if (member != null) {
                    List<TeamPlayer> freshMembers = storage.getTeamMembers(teamId);
                    TeamPlayer freshMember = freshMembers.stream()
                            .filter(m -> m != null && m.getPlayerUuid() != null && m.getPlayerUuid().equals(memberUuid))
                            .findFirst()
                            .orElse(null);
                    if (freshMember != null) {
                        member.setCanWithdraw(freshMember.canWithdraw());
                        member.setCanUseEnderChest(freshMember.canUseEnderChest());
                        member.setCanSetHome(freshMember.canSetHome());
                        member.setCanUseHome(freshMember.canUseHome());
                        plugin.getLogger().info("Refreshed member " + memberUuid
                                + " permissions from database - canUseEnderChest: " + member.canUseEnderChest());
                        Player player = Bukkit.getPlayer(memberUuid);
                        if (player != null && player.isOnline()) {
                            plugin.getTaskRunner().runOnEntity(player, () -> {
                                if (player.getOpenInventory().getTopInventory().getHolder() instanceof TeamGUI) {
                                    new TeamGUI(plugin, team, player).open();
                                }
                            });
                        }
                    }
                }
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Error refreshing member permissions for " + memberUuid + " in team " + teamId
                    + ": " + e.getMessage());
        }
    }

    public void cleanupEnderChestLocksOnStartup() {
        if (plugin.getConfigManager().isSingleServerMode()) {
            plugin.getLogger().info("Single-server mode detected. Cleaning up any existing enderchest locks...");
            plugin.getTaskRunner().runAsync(() -> {
                try {
                    storage.cleanupAllEnderChestLocks();
                    plugin.getLogger().info("Enderchest locks cleanup completed for single-server mode");
                } catch (Exception e) {
                    plugin.getLogger().warning("Could not cleanup enderchest locks on startup: " + e.getMessage());
                }
            });
        }
    }

    public void forceSaveTeamData(int teamId) {
        Team team = teamNameCache.values().stream()
                .filter(t -> t.getId() == teamId)
                .findFirst()
                .orElse(null);
        if (team == null) {
            plugin.getLogger()
                    .warning("Could not force save team data for team ID " + teamId + " - team not found in cache");
            return;
        }
        try {
            for (TeamPlayer member : team.getMembers()) {
                plugin.getLogger()
                        .info("Force saving permissions for member " + member.getPlayerUuid() + " in team "
                                + team.getName() +
                                " - canUseEnderChest: " + member.canUseEnderChest() +
                                ", canEditMembers: " + member.canEditMembers());
                storage.updateMemberPermissions(team.getId(), member.getPlayerUuid(),
                        member.canWithdraw(), member.canUseEnderChest(),
                        member.canSetHome(), member.canUseHome());
                storage.updateMemberEditingPermissions(team.getId(), member.getPlayerUuid(),
                        member.canEditMembers(), member.canEditCoOwners(),
                        member.canKickMembers(), member.canPromoteMembers(),
                        member.canDemoteMembers());
            }
            plugin.getLogger().info("Successfully force saved team: " + team.getName());
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to force save team " + team.getName() + ": " + e.getMessage());
        }
    }

    public void forceSaveAllTeamData() {
        if (plugin.getConfigManager().isDebugEnabled()) {
            plugin.getDebugLogger().log("Force saving all team data to database...");
        }
        int savedCount = 0;
        int errorCount = 0;
        synchronized (cacheLock) {
            for (Team team : teamNameCache.values()) {
                try {
                    for (TeamPlayer member : team.getMembers()) {
                        plugin.getLogger()
                                .info("Saving permissions for member " + member.getPlayerUuid() + " in team "
                                        + team.getName() +
                                        " - canUseEnderChest: " + member.canUseEnderChest() +
                                        ", canEditMembers: " + member.canEditMembers());
                        storage.updateMemberPermissions(team.getId(), member.getPlayerUuid(),
                                member.canWithdraw(), member.canUseEnderChest(),
                                member.canSetHome(), member.canUseHome());
                        storage.updateMemberEditingPermissions(team.getId(), member.getPlayerUuid(),
                                member.canEditMembers(), member.canEditCoOwners(),
                                member.canKickMembers(), member.canPromoteMembers(),
                                member.canDemoteMembers());
                    }
                    savedCount++;
                    plugin.getLogger().fine("Force saved team: " + team.getName());
                } catch (Exception e) {
                    errorCount++;
                    plugin.getLogger().warning("Failed to force save team " + team.getName() + ": " + e.getMessage());
                }
            }
        }
        if (errorCount > 0) {
            plugin.getLogger().warning("Force save completed with " + errorCount + " errors out of "
                    + (savedCount + errorCount) + " teams");
        } else {
            if (plugin.getConfigManager().isDebugEnabled()) {
                plugin.getDebugLogger().log("Successfully force saved all " + savedCount + " teams");
            }
        }
    }

    public Map<String, Team> getTeamNameCache() {
        return teamNameCache;
    }

    public Map<UUID, Team> getPlayerTeamCache() {
        return playerTeamCache;
    }
}