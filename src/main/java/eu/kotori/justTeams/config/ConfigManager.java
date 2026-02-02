package eu.kotori.justTeams.config;

import eu.kotori.justTeams.JustTeams;
import org.bukkit.configuration.file.FileConfiguration;
import java.util.List;

public class ConfigManager {
    private final JustTeams plugin;
    private FileConfiguration config;

    public ConfigManager(JustTeams plugin) {
        this.plugin = plugin;
        plugin.saveDefaultConfig();
        reloadConfig();
    }

    public void reloadConfig() {
        plugin.reloadConfig();
        this.config = plugin.getConfig();
    }

    public boolean isDebugEnabled() {
        return config.getBoolean("settings.debug", false);
    }

    public String getString(String path, String defaultValue) {
        return config.getString(path, defaultValue);
    }

    public int getInt(String path, int defaultValue) {
        return config.getInt(path, defaultValue);
    }

    public boolean getBoolean(String path, boolean defaultValue) {
        return config.getBoolean(path, defaultValue);
    }

    public String getServerIdentifier() {
        return config.getString("settings.server-identifier", "survival");
    }

    public String getMainColor() {
        return config.getString("settings.main_color", "#4C9DDE");
    }

    public String getAccentColor() {
        return config.getString("settings.accent_color", "#4C96D2");
    }

    public String getCurrencyFormat() {
        return config.getString("settings.currency_format", "#,##0.00");
    }

    public int getMaxTeamSize() {
        return config.getInt("settings.max_team_size", 10);
    }

    public int getMinNameLength() {
        return config.getInt("settings.min_name_length", 3);
    }

    public int getMaxNameLength() {
        return config.getInt("settings.max_name_length", 16);
    }

    public int getMaxTagLength() {
        return config.getInt("settings.max_tag_length", 6);
    }

    public int getMaxDescriptionLength() {
        return config.getInt("settings.max_description_length", 64);
    }

    public boolean getDefaultPvpStatus() {
        return config.getBoolean("settings.default_pvp_status", true);
    }

    public boolean isDefaultPublicStatus() {
        return config.getBoolean("settings.default_public_status", false);
    }

    public boolean isCrossServerSyncEnabled() {
        return config.getBoolean("settings.enable_cross_server_sync", true);
    }

    public boolean isSingleServerMode() {
        return !isCrossServerSyncEnabled() ||
                config.getBoolean("settings.single_server_mode", false) ||
                getServerIdentifier().equals("single-server");
    }

    public int getHeartbeatInterval() {
        return config.getInt("settings.sync_optimization.heartbeat_interval", 120);
    }

    public int getCrossServerSyncInterval() {
        return config.getInt("settings.sync_optimization.cross_server_sync_interval", 30);
    }

    public int getCriticalSyncInterval() {
        return config.getInt("settings.sync_optimization.critical_sync_interval", 5);
    }

    public int getMaxTeamsPerBatch() {
        return config.getInt("settings.sync_optimization.max_teams_per_batch", 50);
    }

    public boolean isLazyLoadingEnabled() {
        return config.getBoolean("settings.sync_optimization.enable_lazy_loading", true);
    }

    public int getTeamCacheTTL() {
        return config.getInt("settings.sync_optimization.team_cache_ttl", 300);
    }

    public boolean isOptimisticLockingEnabled() {
        return config.getBoolean("settings.sync_optimization.enable_optimistic_locking", true);
    }

    public int getMaxSyncRetries() {
        return config.getInt("settings.sync_optimization.max_sync_retries", 3);
    }

    public int getSyncRetryDelay() {
        return config.getInt("settings.sync_optimization.sync_retry_delay", 1000);
    }

    public int getMaxBatchSize() {
        return config.getInt("settings.sync_optimization.max_batch_size", 100);
    }

    public boolean isPerformanceMetricsEnabled() {
        return config.getBoolean("settings.performance.enable_metrics", false);
    }

    public boolean isSlowQueryLoggingEnabled() {
        return config.getBoolean("settings.performance.log_slow_queries", true);
    }

    public int getSlowQueryThreshold() {
        return config.getInt("settings.performance.slow_query_threshold", 100);
    }

    public boolean isDetailedSyncLoggingEnabled() {
        return config.getBoolean("settings.performance.detailed_sync_logging", false);
    }

    public boolean isConnectionPoolMonitoringEnabled() {
        return config.getBoolean("settings.performance.monitor_connection_pool", true);
    }

    public int getConnectionPoolLogInterval() {
        return config.getInt("settings.performance.connection_pool_log_interval", 15);
    }

    public boolean isDebugLoggingEnabled() {
        return config.getBoolean("settings.performance.enable_debug_logging", false);
    }

    public long getCacheCleanupInterval() {
        return config.getLong("settings.sync_optimization.cache_cleanup_interval", 600);
    }

    public long getGuiUpdateThrottleMs() {
        return config.getLong("settings.performance.gui_update_throttle_ms", 100);
    }

    public int getConnectionPoolMaxSize() {
        return config.getInt("storage.connection_pool.max_size", 8);
    }

    public int getConnectionPoolMinIdle() {
        return config.getInt("storage.connection_pool.min_idle", 2);
    }

    public long getConnectionPoolConnectionTimeout() {
        return config.getLong("storage.connection_pool.connection_timeout", 30000);
    }

    public long getConnectionPoolIdleTimeout() {
        return config.getLong("storage.connection_pool.idle_timeout", 600000);
    }

    public long getConnectionPoolMaxLifetime() {
        return config.getLong("storage.connection_pool.max_lifetime", 1800000);
    }

    public long getConnectionPoolLeakDetectionThreshold() {
        return config.getLong("storage.connection_pool.leak_detection_threshold", 60000);
    }

    public String getConnectionPoolConnectionTestQuery() {
        return config.getString("storage.connection_pool.connection_test_query", "SELECT 1");
    }

    public long getConnectionPoolValidationTimeout() {
        return config.getLong("storage.connection_pool.validation_timeout", 5000);
    }

    public boolean isMySQLEnabled() {
        return config.getBoolean("storage.mysql.enabled", false);
    }

    public String getMySQLHost() {
        return config.getString("storage.mysql.host", "localhost");
    }

    public int getMySQLPort() {
        return config.getInt("storage.mysql.port", 3306);
    }

    public String getMySQLDatabase() {
        return config.getString("storage.mysql.database", "donutsmp");
    }

    public String getMySQLUsername() {
        return config.getString("storage.mysql.username", "root");
    }

    public String getMySQLPassword() {
        return config.getString("storage.mysql.password", "");
    }

    public boolean isMySQLUseSSL() {
        return config.getBoolean("storage.mysql.use_ssl", false);
    }

    public boolean isMySQLAllowPublicKeyRetrieval() {
        return config.getBoolean("storage.mysql.allow_public_key_retrieval", true);
    }

    public boolean isMySQLUseUnicode() {
        return config.getBoolean("storage.mysql.use_unicode", true);
    }

    public String getMySQLCharacterEncoding() {
        return config.getString("storage.mysql.character_encoding", "utf8");
    }

    public String getMySQLCollation() {
        return config.getString("storage.mysql.collation", "utf8_general_ci");
    }

    public boolean isMySQLAutoReconnect() {
        return config.getBoolean("storage.mysql.auto_reconnect", true);
    }

    public boolean isMySQLFailOverReadOnly() {
        return config.getBoolean("storage.mysql.fail_over_read_only", false);
    }

    public int getMySQLMaxReconnects() {
        return config.getInt("storage.mysql.max_reconnects", 3);
    }

    public int getMySQLConnectionTimeout() {
        return config.getInt("storage.mysql.connection_timeout", 30000);
    }

    public int getMySQLSocketTimeout() {
        return config.getInt("storage.mysql.socket_timeout", 60000);
    }

    public boolean isRedisEnabled() {
        return config.getBoolean("redis.enabled", false);
    }

    public String getRedisHost() {
        return config.getString("redis.host", "localhost");
    }

    public int getRedisPort() {
        return config.getInt("redis.port", 6379);
    }

    public String getRedisPassword() {
        return config.getString("redis.password", "");
    }

    public boolean isRedisSslEnabled() {
        return config.getBoolean("redis.use_ssl", false);
    }

    public int getRedisTimeout() {
        return config.getInt("redis.timeout", 5000);
    }

    public int getRedisPoolMaxTotal() {
        return config.getInt("redis.pool.max_total", 20);
    }

    public int getRedisPoolMaxIdle() {
        return config.getInt("redis.pool.max_idle", 10);
    }

    public int getRedisPoolMinIdle() {
        return config.getInt("redis.pool.min_idle", 2);
    }

    public boolean isBankEnabled() {
        return config.getBoolean("team_bank.enabled", true);
    }

    public double getMaxBankBalance() {
        return config.getDouble("team_bank.max_balance", 1000000.0);
    }

    public boolean isHomeEnabled() {
        return config.getBoolean("team_home.enabled", true);
    }

    public int getWarmupSeconds() {
        return config.getInt("team_home.warmup_seconds", 5);
    }

    public int getHomeCooldownSeconds() {
        return config.getInt("team_home.cooldown_seconds", 300);
    }

    public boolean isEnderChestEnabled() {
        return config.getBoolean("team_enderchest.enabled", true);
    }

    public int getEnderChestRows() {
        return config.getInt("team_enderchest.rows", 3);
    }

    public boolean isSoundsEnabled() {
        return config.getBoolean("effects.sounds.enabled", true);
    }

    public boolean isParticlesEnabled() {
        return config.getBoolean("effects.particles.enabled", true);
    }

    public double getDouble(String path, double defaultValue) {
        return config.getDouble(path, defaultValue);
    }

    public long getLong(String path, long defaultValue) {
        return config.getLong(path, defaultValue);
    }

    public List<String> getStringList(String path) {
        return config.getStringList(path);
    }

    public boolean contains(String path) {
        return config.contains(path);
    }

    public java.util.Set<String> getKeys(boolean deep) {
        return config.getKeys(deep);
    }

    public org.bukkit.configuration.ConfigurationSection getConfigurationSection(String path) {
        return config.getConfigurationSection(path);
    }

    public int getMaxWarpsPerTeam() {
        return config.getInt("settings.max_warps_per_team", 5);
    }

    public int getMaxInvitesPerTeam() {
        return config.getInt("settings.max_invites_per_team", 10);
    }

    public int getInviteExpirationMinutes() {
        return config.getInt("settings.invite_expiration_minutes", 30);
    }

    public int getJoinRequestExpirationMinutes() {
        return config.getInt("settings.join_request_expiration_minutes", 60);
    }

    public boolean isEconomyEnabled() {
        return config.getBoolean("team_bank.enabled", true);
    }

    public double getMinBankTransaction() {
        return config.getDouble("team_bank.min_transaction", 0.01);
    }

    public double getMaxBankTransaction() {
        return config.getDouble("team_bank.max_transaction", 1000000.0);
    }

    public boolean isTeamPvpEnabled() {
        return isFeatureEnabled("team_pvp");
    }

    public int getPvpToggleCooldown() {
        return config.getInt("team_pvp.toggle_cooldown", 300);
    }

    public boolean isTeamHomeEnabled() {
        return isFeatureEnabled("team_home");
    }

    public int getHomeWarmupSeconds() {
        return config.getInt("team_home.warmup_seconds", 5);
    }

    public boolean isTeamWarpsEnabled() {
        return isFeatureEnabled("team_warps");
    }

    public int getWarpCooldownSeconds() {
        return config.getInt("team_warps.cooldown_seconds", 10);
    }

    public int getMaxWarpPasswordLength() {
        return config.getInt("team_warps.max_password_length", 20);
    }

    public boolean isTeamEnderchestEnabled() {
        return isFeatureEnabled("team_enderchest") && config.getBoolean("team_enderchest.enabled", true);
    }

    public int getEnderchestRows() {
        return config.getInt("team_enderchest.rows", 3);
    }

    public int getEnderchestLockTimeout() {
        return config.getInt("team_enderchest.lock_timeout", 300);
    }

    public boolean areSoundsEnabled() {
        return config.getBoolean("effects.sounds.enabled", true);
    }

    public boolean areParticlesEnabled() {
        return config.getBoolean("effects.particles.enabled", true);
    }

    public String getSuccessSound() {
        return config.getString("effects.sounds.success", "ENTITY_PLAYER_LEVELUP");
    }

    public String getErrorSound() {
        return config.getString("effects.sounds.error", "ENTITY_VILLAGER_NO");
    }

    public String getTeleportSound() {
        return config.getString("effects.sounds.teleport", "ENTITY_ENDERMAN_TELEPORT");
    }

    public String getWarmupParticle() {
        return config.getString("effects.particles.teleport_warmup", "PORTAL");
    }

    public String getSuccessParticle() {
        return config.getString("effects.particles.teleport_success", "END_ROD");
    }

    public boolean isBroadcastTeamCreatedEnabled() {
        return config.getBoolean("broadcasts.team-created", true);
    }

    public boolean isBroadcastTeamDisbandedEnabled() {
        return config.getBoolean("broadcasts.team-disbanded", true);
    }

    public boolean isBroadcastPlayerJoinedEnabled() {
        return config.getBoolean("broadcasts.player-joined", true);
    }

    public boolean isBroadcastPlayerLeftEnabled() {
        return config.getBoolean("broadcasts.player-left", true);
    }

    public boolean isBroadcastPlayerKickedEnabled() {
        return config.getBoolean("broadcasts.player-kicked", true);
    }

    public boolean isBroadcastOwnershipTransferredEnabled() {
        return config.getBoolean("broadcasts.ownership-transferred", true);
    }

    public boolean isSpamProtectionEnabled() {
        return config.getBoolean("security.spam_protection.enabled", true);
    }

    public int getCommandSpamThreshold() {
        return config.getInt("security.spam_protection.command_threshold", 5);
    }

    public int getMessageSpamThreshold() {
        return config.getInt("security.spam_protection.message_threshold", 3);
    }

    public int getSpamCooldownSeconds() {
        return config.getInt("security.spam_protection.cooldown_seconds", 10);
    }

    public boolean isContentFilteringEnabled() {
        return config.getBoolean("security.content_filtering.enabled", true);
    }

    public List<String> getBannedWords() {
        return config.getStringList("security.content_filtering.banned_words");
    }

    public int getMaxMessageLength() {
        return config.getInt("security.content_filtering.max_message_length", 200);
    }

    public boolean isBedrockSupportEnabled() {
        return config.getBoolean("bedrock_support.enabled", true);
    }

    public boolean isShowPlatformIndicators() {
        return config.getBoolean("bedrock_support.show_platform_indicators", true);
    }

    public boolean isShowGamertags() {
        return config.getBoolean("bedrock_support.show_gamertags", true);
    }

    public boolean isFullFeatureSupport() {
        return config.getBoolean("bedrock_support.full_feature_support", true);
    }

    public boolean isCrossPlatformTeams() {
        return config.getBoolean("bedrock_support.cross_platform_teams", true);
    }

    public String getUuidMode() {
        return config.getString("bedrock_support.uuid_mode", "auto");
    }

    public boolean isFeatureEnabled(String feature) {
        return config.getBoolean("features." + feature, true);
    }

    public boolean isTeamCreationEnabled() {
        return isFeatureEnabled("team_creation");
    }

    public boolean isTeamDisbandEnabled() {
        return isFeatureEnabled("team_disband");
    }

    public boolean isTeamInvitesEnabled() {
        return isFeatureEnabled("team_invites");
    }

    public boolean isTeamJoinRequestsEnabled() {
        return isFeatureEnabled("team_join_requests");
    }

    public boolean isTeamBlacklistEnabled() {
        return isFeatureEnabled("team_blacklist");
    }

    public boolean isTeamTransferEnabled() {
        return isFeatureEnabled("team_transfer");
    }

    public boolean isTeamPublicToggleEnabled() {
        return isFeatureEnabled("team_public_toggle");
    }

    public boolean isMemberKickEnabled() {
        return isFeatureEnabled("member_kick");
    }

    public boolean isMemberPromoteEnabled() {
        return isFeatureEnabled("member_promote");
    }

    public boolean isMemberDemoteEnabled() {
        return isFeatureEnabled("member_demote");
    }

    public boolean isMemberLeaveEnabled() {
        return isFeatureEnabled("member_leave");
    }

    public boolean isTeamInfoEnabled() {
        return isFeatureEnabled("team_info");
    }

    public boolean isTeamTagEnabled() {
        return isFeatureEnabled("team_tag");
    }

    public boolean isTeamDescriptionEnabled() {
        return isFeatureEnabled("team_description");
    }

    public boolean isTeamLeaderboardEnabled() {
        return isFeatureEnabled("team_leaderboard");
    }

    public boolean isTeamHomeSetEnabled() {
        return isFeatureEnabled("team_home_set");
    }

    public boolean isTeamHomeTeleportEnabled() {
        return isFeatureEnabled("team_home_teleport");
    }

    public boolean isTeamWarpSetEnabled() {
        return isFeatureEnabled("team_warp_set");
    }

    public boolean isTeamWarpDeleteEnabled() {
        return isFeatureEnabled("team_warp_delete");
    }

    public boolean isTeamWarpTeleportEnabled() {
        return isFeatureEnabled("team_warp_teleport");
    }

    public boolean isTeamPvpToggleEnabled() {
        return isFeatureEnabled("team_pvp_toggle");
    }

    public boolean isTeamBankEnabled() {
        return isFeatureEnabled("team_bank");
    }

    public boolean isTeamBankDepositEnabled() {
        return isFeatureEnabled("team_bank_deposit");
    }

    public boolean isTeamBankWithdrawEnabled() {
        return isFeatureEnabled("team_bank_withdraw");
    }

    public boolean isTeamChatEnabled() {
        return isFeatureEnabled("team_chat");
    }

    public boolean isTeamGlowEnabled() {
        return isFeatureEnabled("team_glow");
    }

    public boolean isTeamMessageCommandEnabled() {
        return isFeatureEnabled("team_message_command");
    }

    public boolean isWorldRestrictionsEnabled() {
        return config.getBoolean("world_restrictions.enabled", true);
    }

    public boolean isFeatureDisabledInWorld(String feature, String worldName) {
        if (!isWorldRestrictionsEnabled()) {
            return false;
        }
        List<String> disabledWorlds = config.getStringList("world_restrictions.disabled_worlds." + feature);
        return disabledWorlds.contains(worldName);
    }

    public List<String> getDisabledWorldsForFeature(String feature) {
        return config.getStringList("world_restrictions.disabled_worlds." + feature);
    }

    public boolean isSetHomeDisabledInWorld(String worldName) {
        return isFeatureDisabledInWorld("sethome", worldName);
    }

    public boolean isSetWarpDisabledInWorld(String worldName) {
        return isFeatureDisabledInWorld("setwarp", worldName);
    }

    public boolean isHomeDisabledInWorld(String worldName) {
        return isFeatureDisabledInWorld("home", worldName);
    }

    public boolean isWarpDisabledInWorld(String worldName) {
        return isFeatureDisabledInWorld("warp", worldName);
    }

    public boolean isFeatureCostsEnabled() {
        return config.getBoolean("feature_costs.enabled", true);
    }

    public boolean isEconomyCostsEnabled() {
        return isFeatureCostsEnabled() && config.getBoolean("feature_costs.economy.enabled", true);
    }

    public boolean isItemCostsEnabled() {
        return isFeatureCostsEnabled() && config.getBoolean("feature_costs.items.enabled", false);
    }

    public double getFeatureEconomyCost(String feature) {
        return config.getDouble("feature_costs.economy." + feature, 0.0);
    }

    public List<String> getFeatureItemCosts(String feature) {
        return config.getStringList("feature_costs.items." + feature);
    }

    public boolean shouldConsumeItemsOnUse() {
        return config.getBoolean("feature_costs.items.consume_on_use", true);
    }

    public boolean areFormattingCodesAllowed() {
        return config.getBoolean("allow_formatting_codes", true);
    }

    public List<String> getBlockedFormattingCodes() {
        return config.getStringList("blocked_formatting_codes");
    }

    public boolean isFormattingCodeBlocked(String code) {
        List<String> blockedCodes = getBlockedFormattingCodes();
        return blockedCodes.contains(code.toLowerCase());
    }
}
