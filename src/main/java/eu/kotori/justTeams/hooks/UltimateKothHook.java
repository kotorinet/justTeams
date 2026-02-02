package eu.kotori.justTeams.hooks;

import eu.kotori.justTeams.JustTeams;
import eu.kotori.justTeams.team.Team;
import me.ulrich.koth.Koth;
import me.ulrich.koth.events.KothCaptureEvent;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;

/**
 * UltimateKoth integration hook for JustTeams.
 * 
 * Registers JustTeams as a faction/group provider with UltimateKoth's API,
 * allowing UltimateKoth to recognize team membership during KOTH events.
 */
public class UltimateKothHook implements Listener {

    private final JustTeams plugin;
    private JustTeamsGroupImplement groupImplement;
    private boolean registered = false;

    public UltimateKothHook(JustTeams plugin) {
        this.plugin = plugin;
    }

    /**
     * Registers JustTeams with UltimateKoth's GroupAPI.
     */
    public void registerGroupProvider() {
        try {
            groupImplement = new JustTeamsGroupImplement(plugin);

            boolean success = Koth.getCore().getImpAPI().getGroupAPI()
                    .addImplementation("JustTeams", groupImplement);

            if (success) {
                plugin.getLogger().info("✓ JustTeams registered as UltimateKoth group provider!");
                registered = true;
            } else {
                plugin.getLogger().warning("Failed to register JustTeams with UltimateKoth GroupAPI");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Error registering JustTeams with UltimateKoth: " + e.getMessage());
            if (plugin.getConfigManager().isDebugEnabled()) {
                e.printStackTrace();
            }
        }
    }

    /**
     * Unregisters JustTeams from UltimateKoth's GroupAPI.
     */
    public void unregisterGroupProvider() {
        if (!registered)
            return;

        try {
            Koth.getCore().getImpAPI().getGroupAPI().removeImplementation("JustTeams");
            plugin.getLogger().info("JustTeams unregistered from UltimateKoth");
            registered = false;
        } catch (Exception e) {
            plugin.getLogger().warning("Error unregistering from UltimateKoth: " + e.getMessage());
        }
    }

    @EventHandler
    public void onKothCapture(KothCaptureEvent event) {
        Player player = event.getPlayer();
        if (player == null)
            return;

        Team team = plugin.getTeamManager().getPlayerTeam(player.getUniqueId());
        if (team != null) {

            plugin.getLogger().info("[UKoth Integration] " + player.getName() + " from team '"
                    + team.getName() + "' captured KOTH: " + event.getKothUUID());

            team.broadcast("koth_capture_team",
                    net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("player", player.getName()),
                    net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("team", team.getName()));
        }
    }

    public boolean isRegistered() {
        return registered;
    }
}
