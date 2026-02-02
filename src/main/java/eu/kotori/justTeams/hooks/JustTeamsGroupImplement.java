package eu.kotori.justTeams.hooks;

import eu.kotori.justTeams.JustTeams;
import eu.kotori.justTeams.team.Team;
import eu.kotori.justTeams.team.TeamPlayer;
import me.ulrich.koth.interfaces.GroupImplement;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * UltimateKoth GroupImplement integration for JustTeams.
 * This class provides team/faction data to UltimateKoth so it can recognize
 * JustTeams teams during KOTH captures and other faction-based mechanics.
 */
public class JustTeamsGroupImplement implements GroupImplement {

    private final JustTeams plugin;

    public JustTeamsGroupImplement(JustTeams plugin) {
        this.plugin = plugin;
    }

    @Override
    public Optional<String> getGroupName(Player player) {
        if (player == null)
            return Optional.empty();
        return getGroupName(player.getUniqueId());
    }

    @Override
    public Optional<String> getGroupName(UUID playerUuid) {
        if (playerUuid == null)
            return Optional.empty();

        Team team = plugin.getTeamManager().getPlayerTeam(playerUuid);
        if (team != null) {
            return Optional.of(team.getName());
        }
        return Optional.empty();
    }

    @Override
    public boolean playerHasGroup(Player player) {
        if (player == null)
            return false;
        return playerHasGroup(player.getUniqueId());
    }

    @Override
    public boolean playerHasGroup(UUID playerUuid) {
        if (playerUuid == null)
            return false;
        return plugin.getTeamManager().getPlayerTeam(playerUuid) != null;
    }

    @Override
    public List<UUID> getGroupOnlineMembers(Player player) {
        if (player == null)
            return new ArrayList<>();
        return getGroupOnlineMembers(player.getUniqueId());
    }

    @Override
    public List<UUID> getGroupOnlineMembers(UUID playerUuid) {
        if (playerUuid == null)
            return new ArrayList<>();

        Team team = plugin.getTeamManager().getPlayerTeam(playerUuid);
        if (team == null)
            return new ArrayList<>();

        return team.getMembers().stream()
                .filter(TeamPlayer::isOnline)
                .map(TeamPlayer::getPlayerUuid)
                .collect(Collectors.toList());
    }

    @Override
    public List<String> getMembersName(Player player) {
        if (player == null)
            return new ArrayList<>();
        return getMembersName(player.getUniqueId());
    }

    @Override
    public List<String> getMembersName(UUID playerUuid) {
        if (playerUuid == null)
            return new ArrayList<>();

        Team team = plugin.getTeamManager().getPlayerTeam(playerUuid);
        if (team == null)
            return new ArrayList<>();

        return team.getMembers().stream()
                .map(member -> {
                    Player p = Bukkit.getPlayer(member.getPlayerUuid());
                    if (p != null) {
                        return p.getName();
                    }

                    return Bukkit.getOfflinePlayer(member.getPlayerUuid()).getName();
                })
                .filter(name -> name != null)
                .collect(Collectors.toList());
    }

    @Override
    public Optional<String> getPluginVersion() {
        return Optional.of(plugin.getDescription().getVersion());
    }

    @Override
    public Optional<String> getPluginName() {
        return Optional.of("JustTeams");
    }
}
