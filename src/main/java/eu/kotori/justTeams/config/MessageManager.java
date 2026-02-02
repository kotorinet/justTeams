package eu.kotori.justTeams.config;

import eu.kotori.justTeams.JustTeams;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.File;
import java.io.IOException;

public class MessageManager {
    private final JustTeams plugin;
    private final MiniMessage miniMessage;
    private File messagesFile;
    private FileConfiguration messagesConfig;
    private String prefix;

    public MessageManager(JustTeams plugin) {
        this.plugin = plugin;
        this.miniMessage = plugin.getMiniMessage();
        reload();
    }

    public void reload() {
        messagesFile = new File(plugin.getDataFolder(), "messages.yml");
        if (!messagesFile.exists()) {
            plugin.saveResource("messages.yml", false);
        }
        messagesConfig = new YamlConfiguration();
        try {
            messagesConfig.load(messagesFile);
            plugin.getLogger().info("Messages configuration loaded successfully");
        } catch (IOException | InvalidConfigurationException e) {
            plugin.getLogger().severe("Could not load messages.yml!");
            plugin.getLogger().log(java.util.logging.Level.SEVERE, "Message manager load error details", e);
            loadDefaultMessages();
        }
        this.prefix = messagesConfig.getString("prefix",
                "<bold><gradient:#4C9DDE:#4C96D2>ᴛᴇᴀᴍs</gradient></bold> <dark_gray>| <gray>");
    }

    private void loadDefaultMessages() {
        plugin.getLogger().warning("Loading default messages due to configuration error");
        messagesConfig = new YamlConfiguration();
        messagesConfig.set("prefix", "<bold><gradient:#4C9DDE:#4C96D2>ᴛᴇᴀᴍs</gradient></bold> <dark_gray>| <gray>");
        messagesConfig.set("player_blacklisted",
                "<green>You have blacklisted <white><target></white> from joining your team.</green>");
        messagesConfig.set("blacklist_failed", "<red>Failed to blacklist player. Please try again.</red>");
        messagesConfig.set("player_removed_from_blacklist",
                "<green>You have removed <white><target></white> from the blacklist.</green>");
        messagesConfig.set("remove_blacklist_failed",
                "<red>Failed to remove player from blacklist. Please try again.</red>");
        messagesConfig.set("gui_error", "<red>An error occurred. Please try again.</red>");
    }

    public void sendMessage(CommandSender target, String key, TagResolver... resolvers) {
        try {
            if (messagesConfig == null) {
                plugin.getLogger().warning("Messages config is null, attempting to reload...");
                reload();
            }
            String messageString = messagesConfig.getString(key, "<red>Message key not found: " + key + "</red>");
            if (messageString.startsWith("<red>Message key not found:")) {
                plugin.getLogger()
                        .warning("Message key not found: " + key + " - this may indicate a configuration issue");
            }
            Component message = miniMessage.deserialize(prefix + messageString, resolvers);
            target.sendMessage(message);
        } catch (Exception e) {
            plugin.getLogger().severe("Error sending message for key " + key + ": " + e.getMessage());
            target.sendMessage(Component.text(prefix + "<red>An error occurred while displaying the message.</red>"));
        }
    }

    public void sendRawMessage(CommandSender target, String message, TagResolver... resolvers) {
        Component component = miniMessage.deserialize(message, resolvers);
        target.sendMessage(component);
    }

    public String getRawMessage(String key) {
        try {
            if (messagesConfig == null) {
                plugin.getLogger().warning("Messages config is null in getRawMessage, attempting to reload...");
                reload();
            }
            String message = messagesConfig.getString(key, "Message key not found: " + key);
            if (message.startsWith("Message key not found:")) {
                plugin.getLogger()
                        .warning("Raw message key not found: " + key + " - this may indicate a configuration issue");
            }
            return message;
        } catch (Exception e) {
            plugin.getLogger().severe("Error getting raw message for key " + key + ": " + e.getMessage());
            return "Error loading message: " + key;
        }
    }

    public String getPrefix() {
        return prefix;
    }

    public String getReloadMessage() {
        return getRawMessage("reload");
    }

    public String getReloadCommandsNotice() {
        return getRawMessage("reload_commands_notice");
    }

    public String getNoPermission() {
        return getRawMessage("no_permission");
    }

    public String getPlayerOnly() {
        return getRawMessage("player_only");
    }

    public String getTeamNotFound() {
        return getRawMessage("team_not_found");
    }

    public String getPlayerNotFound() {
        return getRawMessage("player_not_found");
    }

    public String getPlayerNotInTeam() {
        return getRawMessage("player_not_in_team");
    }

    public String getTargetNotInYourTeam() {
        return getRawMessage("target_not_in_your_team");
    }

    public String getNotOwner() {
        return getRawMessage("not_owner");
    }

    public String getOwnerMustDisband() {
        return getRawMessage("owner_must_disband");
    }

    public String getMustBeOwnerOrCoOwner() {
        return getRawMessage("must_be_owner_or_co_owner");
    }

    public String getMustBeOwner() {
        return getRawMessage("must_be_owner");
    }

    public String getNoTeamPlaceholder() {
        return getRawMessage("no_team_placeholder");
    }

    public String getFeatureDisabled() {
        return getRawMessage("feature_disabled");
    }

    public String getGuiActionLocked() {
        return getRawMessage("gui_action_locked");
    }

    public String getUnknownCommand() {
        return getRawMessage("unknown_command");
    }

    public String getAdminDisbandConfirm() {
        return getRawMessage("admin_disband_confirm");
    }

    public String getAdminTeamDisbanded() {
        return getRawMessage("admin_team_disbanded");
    }

    public String getAdminTeamDisbandedBroadcast() {
        return getRawMessage("admin_team_disbanded_broadcast");
    }

    public String getTeamCreated() {
        return getRawMessage("team_created");
    }

    public String getTeamCreatedBroadcast() {
        return getRawMessage("team_created_broadcast");
    }

    public String getAlreadyInTeam() {
        return getRawMessage("already_in_team");
    }

    public String getNameTooShort() {
        return getRawMessage("name_too_short");
    }

    public String getNameTooLong() {
        return getRawMessage("name_too_long");
    }

    public String getNameInvalid() {
        return getRawMessage("name_invalid");
    }

    public String getTagTooLong() {
        return getRawMessage("tag_too_long");
    }

    public String getTagInvalid() {
        return getRawMessage("tag_invalid");
    }

    public String getTeamNameExists() {
        return getRawMessage("team_name_exists");
    }

    public String getDisbandConfirm() {
        return getRawMessage("disband_confirm");
    }

    public String getKickConfirm() {
        return getRawMessage("kick_confirm");
    }

    public String getTeamDisbanded() {
        return getRawMessage("team_disbanded");
    }

    public String getTeamDisbandedBroadcast() {
        return getRawMessage("team_disbanded_broadcast");
    }

    public String getYouLeftTeam() {
        return getRawMessage("you_left_team");
    }

    public String getPlayerLeftBroadcast() {
        return getRawMessage("player_left_broadcast");
    }

    public String getInviteSent() {
        return getRawMessage("invite_sent");
    }

    public String getInviteReceived() {
        return getRawMessage("invite_received");
    }

    public String getInviteSelf() {
        return getRawMessage("invite_self");
    }

    public String getInviteSpam() {
        return getRawMessage("invite_spam");
    }

    public String getTargetAlreadyInTeam() {
        return getRawMessage("target_already_in_team");
    }

    public String getTeamIsFull() {
        return getRawMessage("team_is_full");
    }

    public String getNoPendingInvite() {
        return getRawMessage("no_pending_invite");
    }

    public String getInviteAccepted() {
        return getRawMessage("invite_accepted");
    }

    public String getInviteAcceptedBroadcast() {
        return getRawMessage("invite_accepted_broadcast");
    }

    public String getInviteDenied() {
        return getRawMessage("invite_denied");
    }

    public String getInviteDeniedBroadcast() {
        return getRawMessage("invite_denied_broadcast");
    }

    public String getTeamIsPrivate() {
        return getRawMessage("team_is_private");
    }

    public String getJoinRequestSent() {
        return getRawMessage("join_request_sent");
    }

    public String getAlreadyRequestedToJoin() {
        return getRawMessage("already_requested_to_join");
    }

    public String getJoinRequestReceived() {
        return getRawMessage("join_request_received");
    }

    public String getNoJoinRequests() {
        return getRawMessage("no_join_requests");
    }

    public String getPlayerJoinedPublicTeam() {
        return getRawMessage("player_joined_public_team");
    }

    public String getJoinedTeam() {
        return getRawMessage("joined_team");
    }

    public String getPlayerJoinedTeam() {
        return getRawMessage("player_joined_team");
    }

    public String getJoinRequestWithdrawn() {
        return getRawMessage("join_request_withdrawn");
    }

    public String getJoinRequestNotFound() {
        return getRawMessage("join_request_not_found");
    }

    public String getRequestAcceptedPlayer() {
        return getRawMessage("request_accepted_player");
    }

    public String getRequestDeniedPlayer() {
        return getRawMessage("request_denied_player");
    }

    public String getRequestAcceptedTeam() {
        return getRawMessage("request_accepted_team");
    }

    public String getRequestDeniedTeam() {
        return getRawMessage("request_denied_team");
    }

    public String getPlayerKicked() {
        return getRawMessage("player_kicked");
    }

    public String getYouWereKicked() {
        return getRawMessage("you_were_kicked");
    }

    public String getCannotKickOwner() {
        return getRawMessage("cannot_kick_owner");
    }

    public String getCannotKickCoOwner() {
        return getRawMessage("cannot_kick_co_owner");
    }

    public String getTransferSuccess() {
        return getRawMessage("transfer_success");
    }

    public String getTransferBroadcast() {
        return getRawMessage("transfer_broadcast");
    }

    public String getCannotTransferToSelf() {
        return getRawMessage("cannot_transfer_to_self");
    }

    public String getTagSet() {
        return getRawMessage("tag_set");
    }

    public String getDescriptionSet() {
        return getRawMessage("description_set");
    }

    public String getDescriptionTooLong() {
        return getRawMessage("description_too_long");
    }

    public String getPlayerPromoted() {
        return getRawMessage("player_promoted");
    }

    public String getPlayerDemoted() {
        return getRawMessage("player_demoted");
    }

    public String getAlreadyThatRole() {
        return getRawMessage("already_that_role");
    }

    public String getCannotPromoteOwner() {
        return getRawMessage("cannot_promote_owner");
    }

    public String getCannotDemoteOwner() {
        return getRawMessage("cannot_demote_owner");
    }

    public String getTeamMadePublic() {
        return getRawMessage("team_made_public");
    }

    public String getTeamMadePrivate() {
        return getRawMessage("team_made_private");
    }

    public String getJoinRequestNotification() {
        return getRawMessage("join_request_notification");
    }

    public String getJoinRequestPending() {
        return getRawMessage("join_request_pending");
    }

    public String getJoinRequestCount() {
        return getRawMessage("join_request_count");
    }

    public String getCommandSpamProtection() {
        return getRawMessage("command_spam_protection");
    }

    public String getMessageSpamProtection() {
        return getRawMessage("message_spam_protection");
    }

    public String getMessageTooLong() {
        return getRawMessage("message_too_long");
    }

    public String getInappropriateMessage() {
        return getRawMessage("inappropriate_message");
    }

    public String getInvalidTeamNameOrTag() {
        return getRawMessage("invalid_team_name_or_tag");
    }

    public String getInvalidTeamName() {
        return getRawMessage("invalid_team_name");
    }

    public String getInvalidTeamTag() {
        return getRawMessage("invalid_team_tag");
    }

    public String getInvalidPlayerName() {
        return getRawMessage("invalid_player_name");
    }

    public String getInvalidWarpName() {
        return getRawMessage("invalid_warp_name");
    }

    public String getInvalidWarpPassword() {
        return getRawMessage("invalid_warp_password");
    }

    public String getTeamChatEnabled() {
        return getRawMessage("team_chat_enabled");
    }

    public String getTeamChatDisabled() {
        return getRawMessage("team_chat_disabled");
    }

    public String getTeamChatFormat() {
        return getRawMessage("team_chat_format");
    }

    public String getTeamChatPasswordWarning() {
        return getRawMessage("team_chat_password_warning");
    }

    public String getHomeNotSet() {
        return getRawMessage("home_not_set");
    }

    public String getHomeSet() {
        return getRawMessage("home_set");
    }

    public String getTeleportWarmup() {
        return getRawMessage("teleport_warmup");
    }

    public String getTeleportSuccess() {
        return getRawMessage("teleport_success");
    }

    public String getTeleportMoved() {
        return getRawMessage("teleport_moved");
    }

    public String getTeleportCooldown() {
        return getRawMessage("teleport_cooldown");
    }

    public String getTeamStatusCooldown() {
        return getRawMessage("team_status_cooldown");
    }

    public String getProxyNotEnabled() {
        return getRawMessage("proxy_not_enabled");
    }

    public String getWarpSet() {
        return getRawMessage("warp_set");
    }

    public String getWarpDeleted() {
        return getRawMessage("warp_deleted");
    }

    public String getWarpNotFound() {
        return getRawMessage("warp_not_found");
    }

    public String getWarpLimitReached() {
        return getRawMessage("warp_limit_reached");
    }

    public String getWarpTeleport() {
        return getRawMessage("warp_teleport");
    }

    public String getWarpPasswordProtected() {
        return getRawMessage("warp_password_protected");
    }

    public String getWarpIncorrectPassword() {
        return getRawMessage("warp_incorrect_password");
    }

    public String getWarpCooldown() {
        return getRawMessage("warp_cooldown");
    }

    public String getTeamPvpEnabled() {
        return getRawMessage("team_pvp_enabled");
    }

    public String getTeamPvpDisabled() {
        return getRawMessage("team_pvp_disabled");
    }

    public String getEconomyNotFound() {
        return getRawMessage("economy_not_found");
    }

    public String getEconomyError() {
        return getRawMessage("economy_error");
    }

    public String getBankDepositSuccess() {
        return getRawMessage("bank_deposit_success");
    }

    public String getBankWithdrawSuccess() {
        return getRawMessage("bank_withdraw_success");
    }

    public String getBankInsufficientFunds() {
        return getRawMessage("bank_insufficient_funds");
    }

    public String getPlayerInsufficientFunds() {
        return getRawMessage("player_insufficient_funds");
    }

    public String getBankInvalidAmount() {
        return getRawMessage("bank_invalid_amount");
    }

    public String getBankMaxBalanceReached() {
        return getRawMessage("bank_max_balance_reached");
    }

    public String getEnderchestLockedByProxy() {
        return getRawMessage("enderchest_locked_by_proxy");
    }

    public String getTeamInfoHeader() {
        return getRawMessage("team_info_header");
    }

    public String getTeamInfoTag() {
        return getRawMessage("team_info_tag");
    }

    public String getTeamInfoDescription() {
        return getRawMessage("team_info_description");
    }

    public String getTeamInfoOwner() {
        return getRawMessage("team_info_owner");
    }

    public String getTeamInfoCoOwners() {
        return getRawMessage("team_info_co_owners");
    }

    public String getTeamInfoMembers() {
        return getRawMessage("team_info_members");
    }

    public String getTeamInfoMemberList() {
        return getRawMessage("team_info_member_list");
    }

    public String getTeamInfoStats() {
        return getRawMessage("team_info_stats");
    }

    public String getTeamInfoBank() {
        return getRawMessage("team_info_bank");
    }

    public String getTeamInfoFooter() {
        return getRawMessage("team_info_footer");
    }

    public String getHelpHeader() {
        return getRawMessage("help_header");
    }

    public String getHelpFormat() {
        return getRawMessage("help_format");
    }

    public String getUsageCreate() {
        return getRawMessage("usage_create");
    }

    public String getUsageInvite() {
        return getRawMessage("usage_invite");
    }

    public String getUsageAccept() {
        return getRawMessage("usage_accept");
    }

    public String getUsageDeny() {
        return getRawMessage("usage_deny");
    }

    public String getUsageKick() {
        return getRawMessage("usage_kick");
    }

    public String getUsageSetTag() {
        return getRawMessage("usage_settag");
    }

    public String getUsageTransfer() {
        return getRawMessage("usage_transfer");
    }

    public String getUsageBank() {
        return getRawMessage("usage_bank");
    }

    public String getUsageSetDescription() {
        return getRawMessage("usage_setdescription");
    }

    public String getUsagePromote() {
        return getRawMessage("usage_promote");
    }

    public String getUsageDemote() {
        return getRawMessage("usage_demote");
    }

    public String getUsageJoin() {
        return getRawMessage("usage_join");
    }

    public String getUsageUnjoin() {
        return getRawMessage("usage_unjoin");
    }

    public String getUsageSetWarp() {
        return getRawMessage("usage_setwarp");
    }

    public String getUsageDelWarp() {
        return getRawMessage("usage_delwarp");
    }

    public String getUsageWarp() {
        return getRawMessage("usage_warp");
    }

    public String getUsageWarps() {
        return getRawMessage("usage_warps");
    }

    public String getUsageAdminDisband() {
        return getRawMessage("usage_admin_disband");
    }

    public String getUsageTeamPvp() {
        return getRawMessage("usage_team_pvp");
    }

    public String getTeamNotExists() {
        return getRawMessage("team_not_exists");
    }

    public String getPlayerNotInTeamPlaceholder() {
        return getRawMessage("player_not_in_team_placeholder");
    }

    public String getTeamFull() {
        return getRawMessage("team_full");
    }

    public String getTeamPrivate() {
        return getRawMessage("team_private");
    }

    public String getTeamPublic() {
        return getRawMessage("team_public");
    }

    public String getNoInvite() {
        return getRawMessage("no_invite");
    }

    public String getInviteExpired() {
        return getRawMessage("invite_expired");
    }

    public String getInviteAlreadyAccepted() {
        return getRawMessage("invite_already_accepted");
    }

    public String getInviteAlreadyDenied() {
        return getRawMessage("invite_already_denied");
    }

    public String getCannotInviteFullTeam() {
        return getRawMessage("cannot_invite_full_team");
    }

    public String getCannotInvitePrivateTeam() {
        return getRawMessage("cannot_invite_private_team");
    }

    public String getPlayerOffline() {
        return getRawMessage("player_offline");
    }

    public String getPlayerNotFoundOffline() {
        return getRawMessage("player_not_found_offline");
    }

    public String getTeamNameTaken() {
        return getRawMessage("team_name_taken");
    }

    public String getTeamTagTaken() {
        return getRawMessage("team_tag_taken");
    }

    public String getInvalidTeamNameFormat() {
        return getRawMessage("invalid_team_name_format");
    }

    public String getInvalidTeamTagFormat() {
        return getRawMessage("invalid_team_tag_format");
    }

    public String getCannotEditOwnPermissions() {
        return getRawMessage("cannot_edit_own_permissions");
    }

    public String getViewOnlyMode() {
        return getRawMessage("view_only_mode");
    }

    public String getMessage(String key) {
        return getRawMessage(key);
    }

    public String getMessage(String key, String defaultValue) {
        return messagesConfig.getString(key, defaultValue);
    }

    public boolean hasMessage(String key) {
        return messagesConfig != null && messagesConfig.contains(key);
    }

    public FileConfiguration getMessagesConfig() {
        return messagesConfig;
    }

    public java.util.Set<String> getMessageKeys() {
        return messagesConfig.getKeys(true);
    }
}
