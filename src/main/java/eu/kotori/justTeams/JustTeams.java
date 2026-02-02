package eu.kotori.justTeams;

import eu.kotori.justTeams.commands.TeamCommand;
import eu.kotori.justTeams.commands.TeamMessageCommand;
import eu.kotori.justTeams.config.ConfigManager;
import eu.kotori.justTeams.config.MessageManager;
import eu.kotori.justTeams.gui.GUIManager;
import eu.kotori.justTeams.gui.TeamGUIListener;
import eu.kotori.justTeams.listeners.PlayerConnectionListener;
import eu.kotori.justTeams.listeners.PlayerStatsListener;
import eu.kotori.justTeams.listeners.PvPListener;
import eu.kotori.justTeams.listeners.TeamChatListener;
import eu.kotori.justTeams.listeners.TeamEnderChestListener;
import eu.kotori.justTeams.storage.StorageManager;
import eu.kotori.justTeams.storage.DatabaseStorage;
import eu.kotori.justTeams.storage.DatabaseMigrationManager;
import eu.kotori.justTeams.redis.RedisManager;
import eu.kotori.justTeams.team.TeamManager;
import eu.kotori.justTeams.util.AliasManager;
import eu.kotori.justTeams.util.BedrockSupport;
import eu.kotori.justTeams.util.ChatInputManager;
import eu.kotori.justTeams.util.CommandManager;
import eu.kotori.justTeams.util.ConfigUpdater;
import eu.kotori.justTeams.util.DataRecoveryManager;
import eu.kotori.justTeams.util.DebugLogger;
import eu.kotori.justTeams.util.DiscordWebhookManager;
import eu.kotori.justTeams.util.FeatureRestrictionManager;
import eu.kotori.justTeams.util.GuiConfigManager;
import eu.kotori.justTeams.util.StartupManager;
import eu.kotori.justTeams.util.TaskRunner;
import eu.kotori.justTeams.util.VersionChecker;
import eu.kotori.justTeams.util.WebhookHelper;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.chat.Chat;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.logging.Logger;
import java.util.Map;
import eu.kotori.justTeams.util.PAPIExpansion;
import eu.kotori.justTeams.util.StartupMessage;

public final class JustTeams extends JavaPlugin {
    private static JustTeams instance;
    private static NamespacedKey actionKey;
    private ConfigManager configManager;
    private MessageManager messageManager;
    private StorageManager storageManager;
    private RedisManager redisManager;
    private TeamManager teamManager;
    private eu.kotori.justTeams.team.GlowManager glowManager;
    private GUIManager guiManager;
    private TaskRunner taskRunner;
    private ChatInputManager chatInputManager;
    private CommandManager commandManager;
    private AliasManager aliasManager;
    private GuiConfigManager guiConfigManager;
    private DebugLogger debugLogger;
    private StartupManager startupManager;
    private BedrockSupport bedrockSupport;
    private TeamChatListener teamChatListener;
    private DiscordWebhookManager webhookManager;
    private WebhookHelper webhookHelper;
    private MiniMessage miniMessage;
    private Economy economy;
    private Chat chat;
    private FeatureRestrictionManager featureRestrictionManager;
    private DataRecoveryManager dataRecoveryManager;
    private VersionChecker versionChecker;
    private eu.kotori.justTeams.hooks.UltimateKothHook ultimateKothHook;
    private eu.kotori.justTeams.api.CustomDataManager customDataManager;
    public boolean updateAvailable = false;
    public String latestVersion = "";

    public void onEnable() {
        instance = this;
        Logger logger = getLogger();
        logger.info("Starting JustTeams...");

        actionKey = new NamespacedKey(this, "action");
        logger.info("Action key initialized");

        String serverName = Bukkit.getServer().getName();
        String serverNameLower = serverName.toLowerCase();
        logger.info("Detected server: " + serverName);

        if (serverName.equals("Folia") || serverNameLower.contains("folia") ||
                serverNameLower.equals("canvas") || serverNameLower.equals("petal") || serverNameLower.equals("leaf")
                || serverNameLower.contains("luminol")) {
            logger.info("Folia-based server detected! Using threaded region scheduler support.");
        } else if (serverName.contains("Paper") || serverNameLower.contains("paper") ||
                serverName.equals("Purpur") || serverName.equals("Airplane") ||
                serverName.equals("Pufferfish") || serverNameLower.contains("universespigot") ||
                serverNameLower.equals("plazma") || serverNameLower.equals("mirai")) {
            logger.info("Paper-based server detected. Using optimized scheduler.");
        } else if (serverName.equals("Spigot") || serverNameLower.contains("spigot")) {
            logger.info("Spigot-based server detected. Using standard Bukkit scheduler.");
        } else if (serverName.equals("CraftBukkit") || serverNameLower.contains("bukkit")) {
            logger.info("CraftBukkit server detected. Using standard Bukkit scheduler.");
        } else {
            logger.info("Generic Bukkit-compatible server detected: " + serverName);
        }

        miniMessage = MiniMessage.miniMessage();

        try {
            logger.info("Initializing managers...");
            initializeManagers();

            logger.info("Setting up economy integration...");
            setupEconomy();

            if (getConfig().getBoolean("bstats.enabled", true)) {
                int pluginId = 28485;
                org.bstats.bukkit.Metrics metrics = new org.bstats.bukkit.Metrics(this, pluginId);
                logger.info("bStats Metrics enabled.");
            } else {
                logger.info("bStats Metrics disabled in config.");
            }

            logger.info("Registering event listeners...");
            registerListeners();

            logger.info("Registering commands...");
            registerCommands();

            logger.info("Registering PlaceholderAPI...");
            registerPlaceholderAPI();

            logger.info("Starting cross-server tasks...");

            logger.info("Registering UltimateKoth integration...");
            registerUltimateKoth();

            StartupMessage.send();
            logger.info("JustTeams has been enabled successfully!");
        } catch (Throwable e) {
            logger.severe("Failed to enable JustTeams: " + e.getMessage());
            logger.log(java.util.logging.Level.SEVERE, "JustTeams enable error details", e);
            e.printStackTrace();
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
    }

    public void onDisable() {
        Logger logger = getLogger();
        logger.info("Disabling JustTeams...");
        try {
            if (ultimateKothHook != null) {
                ultimateKothHook.unregisterGroupProvider();
            }
        } catch (Exception e) {
            logger.warning("Error unregistering UltimateKoth: " + e.getMessage());
        }
        try {
            if (taskRunner != null) {
                taskRunner.cancelAllTasks();
            }
        } catch (Exception e) {
            logger.warning("Error cancelling tasks: " + e.getMessage());
        }
        try {
            if (teamManager != null) {
                teamManager.shutdown();
            }
        } catch (Exception e) {
            logger.warning("Error shutting down team manager: " + e.getMessage());
        }
        try {
            if (guiManager != null && guiManager.getUpdateThrottle() != null) {
                guiManager.getUpdateThrottle().cleanup();
            }
        } catch (Exception e) {
            logger.warning("Error cleaning up GUI throttles: " + e.getMessage());
        }

        try {
            if (storageManager != null) {
                storageManager.shutdown();
            }
        } catch (Exception e) {
            logger.warning("Error shutting down storage manager: " + e.getMessage());
        }

        try {
            if (webhookManager != null) {
                webhookManager.shutdown();
            }
        } catch (Exception e) {
            logger.warning("Error shutting down webhook manager: " + e.getMessage());
        }

        try {
            if (redisManager != null) {
                redisManager.shutdown();
            }
        } catch (Exception e) {
            logger.warning("Error shutting down Redis manager: " + e.getMessage());
        }

        logger.info("JustTeams has been disabled.");
    }

    private void initializeManagers() {
        configManager = new ConfigManager(this);
        ConfigUpdater.updateAllConfigs(this);
        ConfigUpdater.migrateToPlaceholderSystem(this);
        messageManager = new MessageManager(this);
        storageManager = new StorageManager(this);
        if (!storageManager.init()) {
            getLogger().severe("═══════════════════════════════════════════════════════════");
            getLogger().severe("  DATABASE CONNECTION FAILED");
            getLogger().severe("═══════════════════════════════════════════════════════════");
            getLogger().severe("");
            getLogger().severe("  The plugin could not connect to the database.");
            getLogger().severe("");
            String storageType = getConfig().getString("storage.type", "unknown");
            if ("mysql".equalsIgnoreCase(storageType) || "mariadb".equalsIgnoreCase(storageType)) {
                getLogger().severe("  You are using MySQL/MariaDB storage.");
                getLogger().severe("  Please check:");
                getLogger().severe("    1. MySQL/MariaDB server is running");
                getLogger().severe("    2. Connection details in config.yml are correct");
                getLogger().severe("    3. Database exists and user has permissions");
                getLogger().severe("");
                getLogger().severe("  Or switch to H2 (local file storage):");
                getLogger().severe("    - Open config.yml");
                getLogger().severe("    - Change: storage.type: \"h2\"");
                getLogger().severe("    - Restart the server");
            } else {
                getLogger().severe("  Storage type: " + storageType);
                getLogger().severe("  Check your config.yml storage settings");
            }
            getLogger().severe("");
            getLogger().severe("═══════════════════════════════════════════════════════════");
            throw new RuntimeException("Failed to initialize storage manager - see above for details");
        }

        redisManager = new RedisManager(this);
        if (configManager.isRedisEnabled()) {
            getLogger().info("Redis is enabled, initializing...");
            try {
                redisManager.initialize();
                getLogger().info("✓ Redis initialized successfully!");
            } catch (Exception e) {
                getLogger().warning("Failed to initialize Redis: " + e.getMessage());
                getLogger().warning("Falling back to MySQL-only mode (1-second polling)");
                e.printStackTrace();
            }
        } else {
            getLogger().info("Redis is disabled in config.yml, using MySQL-only mode (1-second polling)");
        }

        if (storageManager.getStorage() instanceof DatabaseStorage) {
            DatabaseMigrationManager migrationManager = new DatabaseMigrationManager(this,
                    (DatabaseStorage) storageManager.getStorage());
            if (!migrationManager.performMigration()) {
                getLogger()
                        .warning("Database migration completed with warnings. Some features may not work correctly.");
            }
        }

        teamManager = new TeamManager(this);
        guiManager = new GUIManager(this);
        taskRunner = new TaskRunner(this);
        chatInputManager = new ChatInputManager(this);
        commandManager = new CommandManager(this);
        aliasManager = new AliasManager(this);
        guiConfigManager = new GuiConfigManager(this);
        debugLogger = new DebugLogger(this);

        getLogger().info("Initializing BedrockSupport...");
        bedrockSupport = new BedrockSupport(this);

        getLogger().info("Initializing WebhookManager...");
        webhookManager = new DiscordWebhookManager(this);
        webhookHelper = new WebhookHelper(this);

        getLogger().info("Initializing FeatureRestrictionManager...");
        featureRestrictionManager = new FeatureRestrictionManager(this);

        getLogger().info("Initializing DataRecoveryManager...");
        dataRecoveryManager = new DataRecoveryManager(this);

        getLogger().info("Initializing VersionChecker...");
        versionChecker = new VersionChecker(this);
        versionChecker.check();

        customDataManager = new eu.kotori.justTeams.api.CustomDataManager(getLogger());

        glowManager = new eu.kotori.justTeams.team.GlowManager(this);

        teamManager.cleanupEnderChestLocksOnStartup();
        if (storageManager.getStorage() instanceof DatabaseStorage) {
            startupManager = new StartupManager(this, (DatabaseStorage) storageManager.getStorage());
            if (!startupManager.performStartup()) {
                throw new RuntimeException("Startup sequence failed! Check logs for details.");
            }
            startupManager.schedulePeriodicHealthChecks();
            startupManager.schedulePeriodicPermissionSaves();
        }
        startCrossServerTasks();
    }

    private void startCrossServerTasks() {
        String serverName = configManager.getServerIdentifier();
        long heartbeatInterval = configManager.getHeartbeatInterval();
        long crossServerInterval = configManager.getCrossServerSyncInterval();
        long criticalInterval = configManager.getCriticalSyncInterval();
        long cacheCleanupInterval = configManager.getCacheCleanupInterval();

        taskRunner.runAsyncTaskTimer(() -> {
            try {
                if (storageManager.getStorage() instanceof DatabaseStorage dbStorage) {
                    dbStorage.updateServerHeartbeat(serverName);
                } else {
                    storageManager.getStorage().updateServerHeartbeat(serverName);
                }
                if (configManager.isDebugLoggingEnabled()) {
                    debugLogger.log("Updated server heartbeat for: " + serverName);
                }
            } catch (Exception e) {
                getLogger().warning("Error updating server heartbeat: " + e.getMessage());
            }
        }, heartbeatInterval, heartbeatInterval);

        if (configManager.isCrossServerSyncEnabled()) {
            taskRunner.runAsyncTaskTimer(() -> {
                try {
                    teamManager.syncCrossServerData();
                    if (configManager.isDebugLoggingEnabled()) {
                        debugLogger.log("Cross-server sync cycle completed");
                    }
                } catch (Exception e) {
                    getLogger().warning("Error in cross-server sync: " + e.getMessage());
                }
            }, crossServerInterval, crossServerInterval);

            taskRunner.runAsyncTaskTimer(() -> {
                try {
                    teamManager.syncCriticalUpdates();
                } catch (Exception e) {
                    getLogger().warning("Error in critical sync: " + e.getMessage());
                }
            }, criticalInterval, criticalInterval);

            taskRunner.runAsyncTaskTimer(() -> {
                try {
                    int processed = teamManager.processCrossServerMessages();
                    if (processed > 0 && configManager.isDebugLoggingEnabled()) {
                        debugLogger.log("Processed " + processed + " cross-server chat messages");
                    }
                } catch (Exception e) {
                    getLogger().warning("Error processing cross-server messages: " + e.getMessage());
                }
            }, criticalInterval, criticalInterval);

            taskRunner.runAsyncTaskTimer(() -> {
                try {
                    teamManager.flushCrossServerUpdates();
                    if (configManager.isDebugLoggingEnabled()) {
                        debugLogger.log("Flushed pending cross-server updates");
                    }
                } catch (Exception e) {
                    getLogger().warning("Error flushing cross-server updates: " + e.getMessage());
                }
            }, 120L, 120L);
        }

        taskRunner.runAsyncTaskTimer(() -> {
            try {
                teamManager.cleanupExpiredCache();
                if (configManager.isDebugLoggingEnabled()) {
                    debugLogger.log("Cleaned up expired cache entries");
                }
            } catch (Exception e) {
                getLogger().warning("Error cleaning up cache: " + e.getMessage());
            }
        }, cacheCleanupInterval, cacheCleanupInterval);

        if (configManager.isCrossServerSyncEnabled()) {
            taskRunner.runAsyncTaskTimer(() -> {
                try {
                    storageManager.getStorage().cleanupStaleSessions(15);
                    if (configManager.isDebugLoggingEnabled()) {
                        debugLogger.log("Cleaned up stale player sessions");
                    }
                } catch (Exception e) {
                    getLogger().warning("Error cleaning up stale sessions: " + e.getMessage());
                }
            }, 12000L, 12000L);
        }

        taskRunner.runAsyncTaskTimer(() -> {
            try {
                if (storageManager.getStorage() instanceof DatabaseStorage) {
                    ((DatabaseStorage) storageManager.getStorage()).cleanupOldCrossServerData();
                    if (configManager.isDebugLoggingEnabled()) {
                        debugLogger.log("Cleaned up old cross-server data");
                    }
                }
            } catch (Exception e) {
                getLogger().warning("Error cleaning up old cross-server data: " + e.getMessage());
            }
        }, 1200L, 1200L);

        if (configManager.isConnectionPoolMonitoringEnabled()) {
            taskRunner.runAsyncTaskTimer(() -> {
                try {
                    if (storageManager.getStorage() instanceof DatabaseStorage) {
                        DatabaseStorage dbStorage = (DatabaseStorage) storageManager.getStorage();
                        if (configManager.isDebugEnabled()) {
                            Map<String, Object> stats = dbStorage.getDatabaseStats();
                            debugLogger.log("Database stats: " + stats.toString());
                        }
                    }
                } catch (Exception e) {
                    getLogger().warning("Error monitoring connection pool: " + e.getMessage());
                }
            }, configManager.getConnectionPoolLogInterval() * 60L, configManager.getConnectionPoolLogInterval() * 60L);
        }
    }

    private void registerListeners() {
        getServer().getPluginManager().registerEvents(new TeamGUIListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerConnectionListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerStatsListener(this), this);
        getServer().getPluginManager().registerEvents(new PvPListener(this), this);
        teamChatListener = new TeamChatListener(this);
        getServer().getPluginManager().registerEvents(teamChatListener, this);

        if (configManager.isTeamEnderchestEnabled()) {
            getServer().getPluginManager().registerEvents(new TeamEnderChestListener(this), this);
            getLogger().info("Team Enderchest feature is enabled - listener registered");
        } else {
            getLogger().info("Team Enderchest feature is disabled - listener not registered");
        }

        if (getServer().getPluginManager().isPluginEnabled("UltimateKoth")) {
            try {
                ultimateKothHook = new eu.kotori.justTeams.hooks.UltimateKothHook(this);
                getServer().getPluginManager().registerEvents(ultimateKothHook, this);
                getLogger().info("UltimateKoth detected - listener registered");
            } catch (NoClassDefFoundError | Exception e) {
                getLogger().warning("Failed to register UltimateKoth hook: " + e.getMessage());
            }
        }
    }

    private void registerCommands() {
        TeamCommand teamCommand = new TeamCommand(this);
        TeamMessageCommand teamMessageCommand = new TeamMessageCommand(this);
        getCommand("team").setExecutor(teamCommand);
        getCommand("team").setTabCompleter(teamCommand);
        getCommand("guild").setExecutor(teamCommand);
        getCommand("guild").setTabCompleter(teamCommand);
        getCommand("clan").setExecutor(teamCommand);
        getCommand("clan").setTabCompleter(teamCommand);
        getCommand("party").setExecutor(teamCommand);
        getCommand("party").setTabCompleter(teamCommand);
        getCommand("teammsg").setExecutor(teamMessageCommand);
        getCommand("guildmsg").setExecutor(teamMessageCommand);
        getCommand("clanmsg").setExecutor(teamMessageCommand);
        getCommand("partymsg").setExecutor(teamMessageCommand);
        aliasManager.registerAliases();
        commandManager.registerCommands();
    }

    private void registerPlaceholderAPI() {
        if (getServer().getPluginManager().getPlugin("PlaceholderAPI") != null) {
            try {
                PAPIExpansion expansion = new PAPIExpansion(this);
                if (expansion.register()) {
                    getLogger().info("✓ PlaceholderAPI expansion registered successfully!");
                    getLogger().info("  Identifier: justteams");
                    getLogger().info("  Test with: /papi parse me %justteams_tag%");
                } else {
                    getLogger().warning("✗ Failed to register PlaceholderAPI expansion!");
                    getLogger().warning("  This may cause placeholders to not work.");
                }
            } catch (Exception e) {
                getLogger().severe("✗ Error registering PlaceholderAPI expansion: " + e.getMessage());
                e.printStackTrace();
            }
        } else {
            getLogger().warning("PlaceholderAPI not found! Placeholders will not work.");
            getLogger().warning("Download from: https://www.spigotmc.org/resources/6245/");
        }
    }

    private void registerUltimateKoth() {
        if (ultimateKothHook != null) {
            taskRunner.runTaskLater(() -> {
                ultimateKothHook.registerGroupProvider();
            }, 20L); 
        }
    }

    public static JustTeams getInstance() {
        return instance;
    }

    public static NamespacedKey getActionKey() {
        return actionKey;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public MessageManager getMessageManager() {
        return messageManager;
    }

    public StorageManager getStorageManager() {
        return storageManager;
    }

    public RedisManager getRedisManager() {
        return redisManager;
    }

    public TeamManager getTeamManager() {
        return teamManager;
    }

    public eu.kotori.justTeams.team.GlowManager getGlowManager() {
        return glowManager;
    }

    public TeamChatListener getTeamChatListener() {
        return teamChatListener;
    }

    public GUIManager getGuiManager() {
        return guiManager;
    }

    public TaskRunner getTaskRunner() {
        return taskRunner;
    }

    public ChatInputManager getChatInputManager() {
        return chatInputManager;
    }

    public CommandManager getCommandManager() {
        return commandManager;
    }

    public AliasManager getAliasManager() {
        return aliasManager;
    }

    public GuiConfigManager getGuiConfigManager() {
        return guiConfigManager;
    }

    public StartupManager getStartupManager() {
        return startupManager;
    }

    public DebugLogger getDebugLogger() {
        return debugLogger;
    }

    public FeatureRestrictionManager getFeatureRestrictionManager() {
        return featureRestrictionManager;
    }

    public DataRecoveryManager getDataRecoveryManager() {
        return dataRecoveryManager;
    }

    public DiscordWebhookManager getWebhookManager() {
        return webhookManager;
    }

    public WebhookHelper getWebhookHelper() {
        return webhookHelper;
    }

    public MiniMessage getMiniMessage() {
        return miniMessage;
    }

    public Economy getEconomy() {
        return economy;
    }

    public BedrockSupport getBedrockSupport() {
        return bedrockSupport;
    }

    public eu.kotori.justTeams.api.CustomDataManager getCustomDataManager() {
        return customDataManager;
    }

    private boolean setupEconomy() {
        if (getServer().getPluginManager().getPlugin("BoxChat") != null) {
            getLogger().info("BoxChat found! Using BoxChat API for chat messages.");
        }

        if (getServer().getPluginManager().getPlugin("packetevents") == null
                && getConfigManager().isTeamGlowEnabled()) {
            getLogger().warning("PacketEvents not found! Team Glow feature will be DISABLED.");
            getLogger().warning(
                    "Please install PacketEvents to use the glow feature: https://github.com/retrooper/packetevents");
        }
        if (getServer().getPluginManager().getPlugin("Vault") == null) {
            getLogger().warning("Vault plugin not found! Economy features will be disabled.");
            return false;
        }
        RegisteredServiceProvider<Economy> rsp = getServer().getServicesManager().getRegistration(Economy.class);
        if (rsp == null) {
            getLogger().warning("No economy provider found! Economy features will be disabled.");
            return false;
        }
        economy = rsp.getProvider();
        if (economy != null) {
            getLogger().info("Economy provider found: " + economy.getName());
        }

        RegisteredServiceProvider<Chat> chatProvider = getServer().getServicesManager().getRegistration(Chat.class);
        if (chatProvider != null) {
            chat = chatProvider.getProvider();
            getLogger().info("Chat provider found: " + chat.getName() + " (prefix/suffix support enabled)");
        } else {
            getLogger().info("No chat provider found. Player prefixes will not be available.");
        }

        return economy != null;
    }

    public String getPlayerPrefix(org.bukkit.entity.Player player) {
        if (chat == null) {
            return "";
        }
        try {
            String prefix = chat.getPlayerPrefix(player);
            return prefix != null ? prefix : "";
        } catch (Exception e) {
            return "";
        }
    }

    public String getPlayerSuffix(org.bukkit.entity.Player player) {
        if (chat == null) {
            return "";
        }
        try {
            String suffix = chat.getPlayerSuffix(player);
            return suffix != null ? suffix : "";
        } catch (Exception e) {
            return "";
        }
    }
}
