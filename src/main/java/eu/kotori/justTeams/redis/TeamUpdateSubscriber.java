package eu.kotori.justTeams.redis;

import eu.kotori.justTeams.JustTeams;
import eu.kotori.justTeams.team.Team;
import eu.kotori.justTeams.util.EffectsUtil;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import redis.clients.jedis.JedisPubSub;

import java.util.List;
import java.util.UUID;

public class TeamUpdateSubscriber extends JedisPubSub {
    private final JustTeams plugin;

    public TeamUpdateSubscriber(JustTeams plugin) {
        this.plugin = plugin;
    }

    @Override
    public void onMessage(String channel, String message) {
        try {
            String[] parts = message.split("\\|", 5);
            if (parts.length < 4) {
                plugin.getLogger().warning("Invalid Redis update format: " + message);
                return;
            }

            int teamId = Integer.parseInt(parts[0]);
            String updateType = parts[1];
            String playerUuid = parts[2];
            String data = parts[3];
            long timestamp = parts.length > 4 ? Long.parseLong(parts[4]) : System.currentTimeMillis();

            long latency = System.currentTimeMillis() - timestamp;

            Team team = plugin.getTeamManager().getTeamById(teamId).orElse(null);
            if (team == null) {
                plugin.getLogger().warning("Received update for unknown team ID: " + teamId);
                return;
            }

            plugin.getTaskRunner().run(() -> {
                processUpdate(team, updateType, playerUuid, data, latency);
            });

        } catch (Exception e) {
            plugin.getLogger().warning("Error processing Redis update: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void processUpdate(Team team, String updateType, String playerUuidStr, String data, long latency) {
        try {
            UUID playerUuid = playerUuidStr != null && !playerUuidStr.isEmpty()
                    ? UUID.fromString(playerUuidStr)
                    : null;

            switch (updateType) {
                case "MEMBER_KICKED" -> {
                    if (playerUuid == null)
                        return;

                    plugin.getTeamManager().forceTeamSync(team.getId());

                    plugin.getTeamManager().removeFromPlayerTeamCache(playerUuid);

                    team.removeMember(playerUuid);

                    Player kickedPlayer = Bukkit.getPlayer(playerUuid);
                    if (kickedPlayer != null && kickedPlayer.isOnline()) {
                        plugin.getTaskRunner().runOnEntity(kickedPlayer, () -> {
                            kickedPlayer.closeInventory();

                            plugin.getMessageManager().sendMessage(kickedPlayer, "you_were_kicked",
                                    Placeholder.unparsed("team", team.getName()));
                            EffectsUtil.playSound(kickedPlayer, EffectsUtil.SoundType.ERROR);
                        });
                    }

                    plugin.getLogger().info(String.format(
                            "✓ Redis update MEMBER_KICKED processed for %s (latency: %dms)",
                            playerUuid, latency));
                }

                case "MEMBER_PROMOTED" -> {
                    if (playerUuid == null)
                        return;

                    Player promotedPlayer = Bukkit.getPlayer(playerUuid);
                    if (promotedPlayer != null && promotedPlayer.isOnline()) {
                        plugin.getTaskRunner().runOnEntity(promotedPlayer, () -> {
                            plugin.getMessageManager().sendMessage(promotedPlayer, "player_promoted",
                                    Placeholder.unparsed("target", promotedPlayer.getName()));
                            EffectsUtil.playSound(promotedPlayer, EffectsUtil.SoundType.SUCCESS);
                        });
                    }

                    plugin.getTeamManager().forceTeamSync(team.getId());

                    plugin.getLogger().info(String.format(
                            "✓ Redis update MEMBER_PROMOTED processed (latency: %dms)", latency));
                }

                case "MEMBER_DEMOTED" -> {
                    if (playerUuid == null)
                        return;

                    Player demotedPlayer = Bukkit.getPlayer(playerUuid);
                    if (demotedPlayer != null && demotedPlayer.isOnline()) {
                        plugin.getTaskRunner().runOnEntity(demotedPlayer, () -> {
                            plugin.getMessageManager().sendMessage(demotedPlayer, "player_demoted",
                                    Placeholder.unparsed("target", demotedPlayer.getName()));
                            EffectsUtil.playSound(demotedPlayer, EffectsUtil.SoundType.SUCCESS);
                        });
                    }

                    plugin.getTeamManager().forceTeamSync(team.getId());

                    plugin.getLogger().info(String.format(
                            "✓ Redis update MEMBER_DEMOTED processed (latency: %dms)", latency));
                }

                case "MEMBER_LEFT" -> {
                    if (playerUuid == null)
                        return;

                    plugin.getTeamManager().forceTeamSync(team.getId());

                    plugin.getTeamManager().removeFromPlayerTeamCache(playerUuid);

                    team.removeMember(playerUuid);

                    Player leftPlayer = Bukkit.getPlayer(playerUuid);
                    if (leftPlayer != null && leftPlayer.isOnline()) {
                        plugin.getTaskRunner().runOnEntity(leftPlayer, () -> {
                            leftPlayer.closeInventory();
                        });
                    }

                    plugin.getLogger().info(String.format(
                            "✓ Redis update MEMBER_LEFT processed for %s (latency: %dms)",
                            playerUuid, latency));
                }

                case "MEMBER_JOINED" -> {
                    if (playerUuid == null)
                        return;

                    plugin.getTeamManager().forceTeamSync(team.getId());

                    Player joinedPlayer = Bukkit.getPlayer(playerUuid);
                    if (joinedPlayer != null && joinedPlayer.isOnline()) {
                        plugin.getTaskRunner().runOnEntity(joinedPlayer, () -> {
                            plugin.getTeamManager().addPlayerToTeamCache(joinedPlayer.getUniqueId(), team);
                        });
                    }

                    plugin.getLogger().info(String.format(
                            "✓ Redis update MEMBER_JOINED processed for %s (latency: %dms)",
                            playerUuid, latency));
                }

                case "TEAM_DISBANDED" -> {
                    List<UUID> memberUuids = team.getMembers().stream()
                            .map(member -> member.getPlayerUuid())
                            .collect(java.util.stream.Collectors.toList());
                    String teamName = team.getName();

                    for (UUID memberUuid : memberUuids) {
                        Player memberPlayer = Bukkit.getPlayer(memberUuid);
                        if (memberPlayer != null && memberPlayer.isOnline()) {
                            plugin.getTaskRunner().runOnEntity(memberPlayer, () -> {
                                plugin.getTeamManager().removeFromPlayerTeamCache(memberUuid);

                                memberPlayer.closeInventory();

                                plugin.getMessageManager().sendMessage(memberPlayer, "team_disbanded_broadcast",
                                        Placeholder.unparsed("team", teamName));
                                EffectsUtil.playSound(memberPlayer, EffectsUtil.SoundType.ERROR);
                            });
                        } else {
                            plugin.getTeamManager().removeFromPlayerTeamCache(memberUuid);
                        }
                    }

                    plugin.getTeamManager().uncacheTeam(team.getId());

                    plugin.getLogger().info(String.format(
                            "✓ Redis update TEAM_DISBANDED processed for team %s, notified %d members (latency: %dms)",
                            teamName, memberUuids.size(), latency));
                }

                case "TEAM_UPDATED" -> {
                    plugin.getTeamManager().forceTeamSync(team.getId());

                    plugin.getLogger().info(String.format(
                            "✓ Redis update TEAM_UPDATED processed (latency: %dms)", latency));
                }

                case "PLAYER_INVITED" -> {
                    if (playerUuid == null)
                        return;

                    Player invitedPlayer = Bukkit.getPlayer(playerUuid);
                    if (invitedPlayer != null && invitedPlayer.isOnline()) {
                        plugin.getTaskRunner().runOnEntity(invitedPlayer, () -> {
                            plugin.getMessageManager().sendRawMessage(invitedPlayer,
                                    plugin.getMessageManager().getRawMessage("prefix") +
                                            plugin.getMessageManager().getRawMessage("invite_received")
                                                    .replace("<team>", team.getName()));
                            EffectsUtil.playSound(invitedPlayer, EffectsUtil.SoundType.SUCCESS);
                        });
                    }

                    plugin.getLogger().info(String.format(
                            "✓ Redis update PLAYER_INVITED processed (latency: %dms)", latency));
                }

                case "PUBLIC_STATUS_CHANGED", "PVP_STATUS_CHANGED" -> {
                    plugin.getTeamManager().forceTeamSync(team.getId());
                    plugin.getLogger().info(String.format(
                            "✓ Redis admin update %s processed (latency: %dms)", updateType, latency));
                }

                case "ADMIN_BALANCE_SET", "ADMIN_STATS_SET" -> {
                    plugin.getTeamManager().forceTeamSync(team.getId());
                    plugin.getLogger().info(String.format(
                            "✓ Redis admin update %s processed (latency: %dms)", updateType, latency));
                }

                case "ADMIN_PERMISSION_UPDATE" -> {
                    String[] parts = data.split(":");
                    if (parts.length == 3) {
                        UUID memberUuid = UUID.fromString(parts[0]);
                        plugin.getTeamManager().forceMemberPermissionRefresh(team.getId(), memberUuid);
                        plugin.getLogger().info(String.format(
                                "✓ Redis admin permission update processed for member %s (latency: %dms)",
                                memberUuid, latency));
                    }
                }

                case "ADMIN_MEMBER_KICK" -> {
                    UUID kickedUuid = UUID.fromString(data);

                    plugin.getTeamManager().forceTeamSync(team.getId());

                    plugin.getTeamManager().removeFromPlayerTeamCache(kickedUuid);
                    team.removeMember(kickedUuid);

                    Player kickedPlayer = Bukkit.getPlayer(kickedUuid);
                    if (kickedPlayer != null && kickedPlayer.isOnline()) {
                        plugin.getTaskRunner().runOnEntity(kickedPlayer, () -> {
                            kickedPlayer.closeInventory();
                            plugin.getMessageManager().sendMessage(kickedPlayer, "you_were_kicked",
                                    Placeholder.unparsed("team", team.getName()));
                            EffectsUtil.playSound(kickedPlayer, EffectsUtil.SoundType.ERROR);
                        });
                    }

                    plugin.getLogger().info(String.format(
                            "✓ Redis admin kick processed for %s (latency: %dms)", kickedUuid, latency));
                }

                case "ADMIN_MEMBER_PROMOTE", "ADMIN_MEMBER_DEMOTE" -> {
                    UUID memberUuid = UUID.fromString(data);
                    plugin.getTeamManager().forceMemberPermissionRefresh(team.getId(), memberUuid);

                    Player targetPlayer = Bukkit.getPlayer(memberUuid);
                    if (targetPlayer != null && targetPlayer.isOnline()) {
                        plugin.getTaskRunner().runOnEntity(targetPlayer, () -> {
                            String messageKey = updateType.equals("ADMIN_MEMBER_PROMOTE") ? "player_promoted"
                                    : "player_demoted";
                            plugin.getMessageManager().sendMessage(targetPlayer, messageKey,
                                    Placeholder.unparsed("target", targetPlayer.getName()));
                            EffectsUtil.playSound(targetPlayer, EffectsUtil.SoundType.SUCCESS);
                        });
                    }

                    plugin.getLogger().info(String.format(
                            "✓ Redis admin %s processed for %s (latency: %dms)",
                            updateType, memberUuid, latency));
                }

                case "ENDERCHEST_UPDATED" -> {
                    plugin.getTaskRunner().runAsync(() -> {
                        try {
                            if (!team.isEnderChestLocked()) {
                                org.bukkit.inventory.Inventory enderChest = team.getEnderChest();
                                if (enderChest == null) {
                                    enderChest = Bukkit.createInventory(null, 27, "Team Enderchest");
                                    team.setEnderChest(enderChest);
                                }

                                final org.bukkit.inventory.Inventory finalEnderChest = enderChest;
                                eu.kotori.justTeams.util.InventoryUtil.deserializeInventory(finalEnderChest, data);

                                plugin.getTaskRunner().run(() -> {
                                    plugin.getTeamManager().refreshEnderChestInventory(team);
                                });

                                plugin.getLogger().info(String.format(
                                        "✓ Redis enderchest update processed for team %s (latency: %dms)",
                                        team.getName(), latency));
                            } else {
                                plugin.getLogger().info(String.format(
                                        "Skipped Redis enderchest update (lock held) for team: %s",
                                        team.getName()));
                            }
                        } catch (Exception e) {
                            plugin.getLogger().warning("Failed to process Redis ENDERCHEST_UPDATED for team " +
                                    team.getName() + ": " + e.getMessage());
                        }
                    });
                }

                default -> {
                    plugin.getLogger().warning("Unknown Redis update type: " + updateType);
                }
            }

        } catch (Exception e) {
            plugin.getLogger().warning("Error processing update: " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public void onSubscribe(String channel, int subscribedChannels) {
        plugin.getLogger().info("✓ Subscribed to Redis channel: " + channel + " (total: " + subscribedChannels + ")");
    }

    @Override
    public void onUnsubscribe(String channel, int subscribedChannels) {
        plugin.getLogger()
                .info("Unsubscribed from Redis channel: " + channel + " (remaining: " + subscribedChannels + ")");
    }
}
