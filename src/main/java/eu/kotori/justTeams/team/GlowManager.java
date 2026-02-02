package eu.kotori.justTeams.team;

import com.github.retrooper.packetevents.PacketEvents;
import com.github.retrooper.packetevents.event.PacketListener;
import com.github.retrooper.packetevents.event.PacketListenerPriority;
import com.github.retrooper.packetevents.event.PacketSendEvent;
import com.github.retrooper.packetevents.protocol.entity.data.EntityData;
import com.github.retrooper.packetevents.protocol.entity.data.EntityDataTypes;
import com.github.retrooper.packetevents.protocol.packettype.PacketType;

import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerEntityMetadata;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerSpawnPlayer;
import com.github.retrooper.packetevents.wrapper.play.server.WrapperPlayServerTeams;
import eu.kotori.justTeams.JustTeams;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.util.*;

@SuppressWarnings("deprecation")
public class GlowManager implements Listener, PacketListener {

    private final JustTeams plugin;
    private boolean enabled;
    private boolean usePacketEvents;
    private final boolean onlyShowOwnTeam;

    private final Map<UUID, Map<UUID, ChatColor>> glowingCache = new java.util.concurrent.ConcurrentHashMap<>();

    public GlowManager(JustTeams plugin) {
        this.plugin = plugin;
        this.enabled = plugin.getConfig().getBoolean("features.team_glow", true);
        this.onlyShowOwnTeam = plugin.getConfig().getBoolean("settings.glow.only_show_own_team", true);

        if (enabled) {
            if (plugin.getServer().getPluginManager().getPlugin("packetevents") != null) {
                this.usePacketEvents = true;
                plugin.getServer().getPluginManager().registerEvents(this, plugin);
                PacketEvents.getAPI().getEventManager().registerListener(this, PacketListenerPriority.NORMAL);
                startRangeCheckTask();

                for (Player p : Bukkit.getOnlinePlayers()) {
                    createColorTeams(p);
                    plugin.getTaskRunner().runTaskLater(() -> refreshGlow(p), 20L);
                }

                plugin.getLogger().info("Team Glow enabled using PacketEvents.");
            } else {
                plugin.getLogger().warning("PacketEvents not found! Team Glow has been disabled.");
                this.enabled = false;
                this.usePacketEvents = false;
            }
        }
    }

    public void setGlow(Player target, Player receiver, ChatColor color) {
        if (!enabled || !usePacketEvents)
            return;

        try {
            if (glowingCache.containsKey(receiver.getUniqueId())) {
                Map<UUID, ChatColor> targets = glowingCache.get(receiver.getUniqueId());
                if (targets.containsKey(target.getUniqueId()) && targets.get(target.getUniqueId()) == color) {
                    return;
                }
            }

            sendTeamPacket(target, receiver, color);

            sendMetadataPacket(target, receiver, true);

            glowingCache.computeIfAbsent(receiver.getUniqueId(), k -> new java.util.concurrent.ConcurrentHashMap<>())
                    .put(target.getUniqueId(), color);

            if (plugin.getConfigManager().isDebugEnabled()) {
                plugin.getLogger().info("[GlowDebug] SENT glow packets: Target=" + target.getName() + " Receiver="
                        + receiver.getName() + " Color=" + color.name());
            }

        } catch (Exception e) {
            if (plugin.getConfigManager().isDebugEnabled()) {
                plugin.getLogger().warning("[GlowDebug] Failed to set glow: " + e.getMessage());
                e.printStackTrace();
            }
        }
    }

    public void unsetGlow(Player target, Player receiver) {
        if (!enabled || !usePacketEvents)
            return;

        try {
            sendMetadataPacket(target, receiver, false);


            if (glowingCache.containsKey(receiver.getUniqueId())) {
                glowingCache.get(receiver.getUniqueId()).remove(target.getUniqueId());
            }

        } catch (Exception e) {
        }
    }

    private void sendMetadataPacket(Player target, Player receiver, boolean glowing) {
        byte status = 0;
        if (target.getFireTicks() > 0)
            status |= 0x01;
        if (target.isSneaking())
            status |= 0x02;
        if (target.isSprinting())
            status |= 0x08;
        if (target.isSwimming())
            status |= 0x10;
        if (target.isInvisible())
            status |= 0x20;
        if (glowing)
            status |= 0x40;
        if (target.isGliding())
            status |= 0x80;

        EntityData<Byte> entityData = new EntityData<>(0, EntityDataTypes.BYTE, status);

        WrapperPlayServerEntityMetadata packet = new WrapperPlayServerEntityMetadata(
                target.getEntityId(),
                Collections.singletonList(entityData));

        PacketEvents.getAPI().getPlayerManager().sendPacket(receiver, packet);
    }

    private void sendTeamPacket(Player target, Player receiver, ChatColor color) {
        String teamName = "JT_" + color.name();
        if (teamName.length() > 16)
            teamName = teamName.substring(0, 16);


        WrapperPlayServerTeams packet = new WrapperPlayServerTeams(
                teamName,
                WrapperPlayServerTeams.TeamMode.ADD_ENTITIES,
                (WrapperPlayServerTeams.ScoreBoardTeamInfo) null,
                Collections.singletonList(target.getName()));

        PacketEvents.getAPI().getPlayerManager().sendPacket(receiver, packet);
    }

    private NamedTextColor getNamedTextColor(ChatColor color) {
        return switch (color) {
            case RED -> NamedTextColor.RED;
            case DARK_RED -> NamedTextColor.DARK_RED;
            case BLUE -> NamedTextColor.BLUE;
            case GREEN -> NamedTextColor.GREEN;
            case AQUA -> NamedTextColor.AQUA;
            case GOLD -> NamedTextColor.GOLD;
            case GRAY -> NamedTextColor.GRAY;
            case WHITE -> NamedTextColor.WHITE;
            case BLACK -> NamedTextColor.BLACK;
            case YELLOW -> NamedTextColor.YELLOW;
            case LIGHT_PURPLE -> NamedTextColor.LIGHT_PURPLE;
            case DARK_PURPLE -> NamedTextColor.DARK_PURPLE;
            case DARK_BLUE -> NamedTextColor.DARK_BLUE;
            case DARK_GREEN -> NamedTextColor.DARK_GREEN;
            case DARK_AQUA -> NamedTextColor.DARK_AQUA;
            case DARK_GRAY -> NamedTextColor.DARK_GRAY;
            default -> NamedTextColor.WHITE;
        };
    }


    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!enabled || !usePacketEvents)
            return;
        Player player = event.getPlayer();

        createColorTeams(player);

        plugin.getTaskRunner().runAsyncTaskLater(() -> refreshGlow(player), 20L);
    }

    private void createColorTeams(Player receiver) {
        for (ChatColor color : ChatColor.values()) {
            if (!color.isColor())
                continue;
            String teamName = "JT_" + color.name();
            if (teamName.length() > 16)
                teamName = teamName.substring(0, 16);

            WrapperPlayServerTeams.ScoreBoardTeamInfo info = new WrapperPlayServerTeams.ScoreBoardTeamInfo(
                    Component.text(teamName),
                    Component.text(color.toString()),
                    Component.empty(),
                    WrapperPlayServerTeams.NameTagVisibility.ALWAYS,
                    WrapperPlayServerTeams.CollisionRule.ALWAYS,
                    getNamedTextColor(color),
                    WrapperPlayServerTeams.OptionData.NONE);

            WrapperPlayServerTeams packet = new WrapperPlayServerTeams(
                    teamName,
                    WrapperPlayServerTeams.TeamMode.CREATE,
                    info,
                    new ArrayList<>()
            );
            PacketEvents.getAPI().getPlayerManager().sendPacket(receiver, packet);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        glowingCache.remove(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        if (!enabled || !usePacketEvents)
            return;
        Player player = event.getPlayer();

        plugin.getTaskRunner().runAsyncTaskLater(() -> {
            if (player.isOnline()) {
                createColorTeams(player);
                refreshGlow(player);

                for (Player other : plugin.getServer().getOnlinePlayers()) {
                    if (!other.getUniqueId().equals(player.getUniqueId())) {
                        refreshGlow(other);
                    }
                }
            }
        }, 20L);
    }

    @EventHandler
    public void onPlayerRespawn(PlayerRespawnEvent event) {
        if (!enabled || !usePacketEvents)
            return;
        Player player = event.getPlayer();

        plugin.getTaskRunner().runAsyncTaskLater(() -> {
            if (player.isOnline()) {
                createColorTeams(player);
                refreshGlow(player);

                for (Player other : plugin.getServer().getOnlinePlayers()) {
                    if (!other.getUniqueId().equals(player.getUniqueId())) {
                        refreshGlow(other);
                    }
                }
            }
        }, 10L);
    }

    public void updateGlowForTeam(Team team) {
        if (!enabled || team == null || !usePacketEvents)
            return;

        for (TeamPlayer member : team.getMembers()) {
            Player p = Bukkit.getPlayer(member.getPlayerUuid());
            if (p != null && p.isOnline()) {
                refreshGlow(p);
            }
        }
    }

    public void stopGlowForPlayer(Player player, Team team) {
        if (!enabled || !usePacketEvents)
            return;
        for (Player p : Bukkit.getOnlinePlayers()) {
            unsetGlow(player, p);
        }
    }

    private ChatColor getRoleColor(TeamRole role) {
        String configKey = "settings.glow.colors." + role.name().toLowerCase();
        String colorName = plugin.getConfig().getString(configKey);

        if (colorName == null) {
            if (role == TeamRole.CO_OWNER)
                return ChatColor.RED;
            if (role == TeamRole.OWNER)
                return ChatColor.DARK_RED;
            return ChatColor.WHITE;
        }

        try {
            return ChatColor.valueOf(colorName.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ChatColor.WHITE;
        }
    }

    @Override
    public void onPacketSend(PacketSendEvent event) {
        if (!enabled || !usePacketEvents)
            return;

        if (event.getPacketType() == PacketType.Play.Server.ENTITY_METADATA) {
            WrapperPlayServerEntityMetadata metadataPacket = new WrapperPlayServerEntityMetadata(event);
            int entityId = metadataPacket.getEntityId();
            Player receiver = (Player) event.getPlayer();



            Player target = null;
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getEntityId() == entityId) {
                    target = p;
                    break;
                }
            }

            if (target == null || target.getUniqueId().equals(receiver.getUniqueId())) {
                return;
            }

            if (glowingCache.containsKey(receiver.getUniqueId()) &&
                    glowingCache.get(receiver.getUniqueId()).containsKey(target.getUniqueId())) {

                boolean modified = false;
                List<EntityData<?>> dataList = metadataPacket.getEntityMetadata();

                for (EntityData<?> data : dataList) {
                    if (data.getIndex() == 0 && data.getType() == EntityDataTypes.BYTE) {
                        @SuppressWarnings("unchecked")
                        EntityData<Byte> byteData = (EntityData<Byte>) data;
                        byte status = byteData.getValue();
                        status |= 0x40;
                        byteData.setValue(status);
                        modified = true;
                        break;
                    }
                }


                if (modified) {
                }
            }
            return;
        }

        if (event.getPacketType() == PacketType.Play.Server.SPAWN_PLAYER) {
            WrapperPlayServerSpawnPlayer spawnPacket = new WrapperPlayServerSpawnPlayer(event);
            Player receiver = (Player) event.getPlayer();

            if (receiver == null)
                return;

            int spawnedEntityId = spawnPacket.getEntityId();

            plugin.getTaskRunner().runAsyncTaskLater(() -> {
                if (!receiver.isOnline())
                    return;

                Player spawnedPlayer = null;
                for (Player p : Bukkit.getOnlinePlayers()) {
                    if (p.getEntityId() == spawnedEntityId) {
                        spawnedPlayer = p;
                        break;
                    }
                }

                if (spawnedPlayer != null && !spawnedPlayer.getUniqueId().equals(receiver.getUniqueId())) {
                    if (glowingCache.containsKey(receiver.getUniqueId())) {
                        glowingCache.get(receiver.getUniqueId()).remove(spawnedPlayer.getUniqueId());
                    }

                    final Player finalSpawnedPlayer = spawnedPlayer;
                    plugin.getTeamManager().getPlayerTeamAsync(spawnedPlayer.getUniqueId()).thenAccept(team -> {
                        refreshGlowForReceiver(finalSpawnedPlayer, receiver, team);
                    });
                }
            }, 5L);
        }
    }

    private void startRangeCheckTask() {
        int interval = plugin.getConfig().getInt("settings.glow.check_interval", 20);

        if (usePacketEvents) {
            plugin.getTaskRunner().runAsyncTaskTimer(() -> {
                if (!enabled || !usePacketEvents) {
                    return;
                }

                for (Player player : Bukkit.getOnlinePlayers()) {
                    refreshGlow(player);
                }
            }, interval, interval);
        }
    }

    private void refreshGlowForReceiver(Player target, Player receiver, Team team) {

        if (team == null || !team.isGlowEnabled()) {
            unsetGlow(target, receiver);
            return;
        }

        int range = plugin.getConfig().getInt("settings.glow.range", 30);
        if (!target.getWorld().getUID().equals(receiver.getWorld().getUID())
                || target.getLocation().distanceSquared(receiver.getLocation()) > range * range) {
            unsetGlow(target, receiver);
            return;
        }

        if (plugin.getConfigManager().isDebugEnabled()) {
        }

        if (onlyShowOwnTeam) {
            Team receiverTeam = plugin.getTeamManager().getPlayerTeam(receiver.getUniqueId());
            if (receiverTeam != null && receiverTeam.getId() == team.getId()) {
                ChatColor color = team.getColor() != null ? team.getColor()
                        : getRoleColor(team.getMember(target.getUniqueId()).getRole());
                setGlow(target, receiver, color);
            } else {
                unsetGlow(target, receiver);
            }
        } else {
            ChatColor color = team.getColor() != null ? team.getColor()
                    : getRoleColor(team.getMember(target.getUniqueId()).getRole());
            setGlow(target, receiver, color);
        }
    }

    public void refreshGlow(Player player) {
        if (!enabled || !usePacketEvents)
            return;
        if (!player.isOnline())
            return;

        plugin.getTeamManager().getPlayerTeamAsync(player.getUniqueId()).thenAccept(team -> {
            if (!player.isOnline())
                return;


            if (team == null || !team.isGlowEnabled()) {
                for (Player receiver : Bukkit.getOnlinePlayers()) {
                    if (!receiver.getUniqueId().equals(player.getUniqueId())) {
                        unsetGlow(player, receiver);
                    }
                }
                return;
            }

            for (Player receiver : Bukkit.getOnlinePlayers()) {
                if (receiver.getUniqueId().equals(player.getUniqueId()))
                    continue;
                refreshGlowForReceiver(player, receiver, team);
            }
        }).exceptionally(ex -> {
            if (plugin.getConfigManager().isDebugEnabled()) {
                plugin.getLogger().warning("Error refreshing glow for " + player.getName() + ": " + ex.getMessage());
                ex.printStackTrace();
            }
            return null;
        });
    }
}
