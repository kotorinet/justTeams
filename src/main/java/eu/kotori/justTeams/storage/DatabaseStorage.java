package eu.kotori.justTeams.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import eu.kotori.justTeams.JustTeams;
import eu.kotori.justTeams.team.Team;
import eu.kotori.justTeams.team.TeamPlayer;
import eu.kotori.justTeams.team.TeamRole;
import eu.kotori.justTeams.team.BlacklistedPlayer;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import java.io.File;
import java.sql.*;
import java.time.Instant;
import java.util.*;

public class DatabaseStorage implements IDataStorage {
    private final JustTeams plugin;
    private HikariDataSource hikari;
    private final String storageType;

    public DatabaseStorage(JustTeams plugin) {
        this.plugin = plugin;
        this.storageType = plugin.getConfig().getString("storage.type", "h2").toLowerCase();
    }

    private boolean isUsingH2() {
        return !storageType.equals("mysql") && !storageType.equals("mariadb");
    }

    @Override
    public boolean init() {
        HikariConfig config = new HikariConfig();
        config.setPoolName("justTeams-Pool");

        int maxPoolSize = plugin.getConfig().getInt("storage.connection_pool.max_size", 8);
        int minIdle = plugin.getConfig().getInt("storage.connection_pool.min_idle", 2);
        long idleTimeout = plugin.getConfig().getLong("storage.connection_pool.idle_timeout", 300000);
        long maxLifetime = plugin.getConfig().getLong("storage.connection_pool.max_lifetime", 1800000);
        long connectionTimeout = plugin.getConfig().getLong("storage.connection_pool.connection_timeout", 20000);
        long validationTimeout = plugin.getConfig().getLong("storage.connection_pool.validation_timeout", 3000);
        long leakDetectionThreshold = plugin.getConfig().getLong("storage.connection_pool.leak_detection_threshold", 0);

        config.setMaximumPoolSize(maxPoolSize);
        config.setMinimumIdle(minIdle);
        config.setIdleTimeout(idleTimeout);
        config.setMaxLifetime(maxLifetime);
        config.setConnectionTimeout(connectionTimeout);
        config.setValidationTimeout(validationTimeout);
        config.setConnectionTestQuery("/* ping */ SELECT 1");
        config.setInitializationFailTimeout(connectionTimeout);
        config.setIsolateInternalQueries(false);
        config.setAllowPoolSuspension(false);
        config.setReadOnly(false);
        config.setRegisterMbeans(false);

        config.setKeepaliveTime(300000);
        config.setMaxLifetime(1800000);
        config.setIdleTimeout(600000);

        if (leakDetectionThreshold > 0) {
            config.setLeakDetectionThreshold(leakDetectionThreshold);
        } else if (plugin.getConfig().getBoolean("storage.connection_pool.enable_leak_detection", false)) {
            config.setLeakDetectionThreshold(60000);
        }

        boolean useMySQL = storageType.equals("mysql") || storageType.equals("mariadb");
        if (useMySQL) {
            config.addDataSourceProperty("cachePrepStmts", "true");
            config.addDataSourceProperty("prepStmtCacheSize", "250");
            config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
            config.addDataSourceProperty("useServerPrepStmts", "true");
            config.addDataSourceProperty("useLocalSessionState", "true");
            config.addDataSourceProperty("rewriteBatchedStatements", "true");
            config.addDataSourceProperty("cacheResultSetMetadata", "true");
            config.addDataSourceProperty("cacheServerConfiguration", "true");
            config.addDataSourceProperty("elideSetAutoCommits", "true");
            config.addDataSourceProperty("maintainTimeStats", "false");

            config.addDataSourceProperty("characterEncoding", "UTF-8");
            config.addDataSourceProperty("useUnicode", "true");

            config.addDataSourceProperty("tcpKeepAlive", "true");
        }
        if (useMySQL) {
            plugin.getLogger().info("Connecting to MySQL/MariaDB database...");
            config.setDriverClassName("com.mysql.cj.jdbc.Driver");

            String host = plugin.getConfig().getString("storage.mysql.host", "localhost");
            int port = plugin.getConfig().getInt("storage.mysql.port", 3306);
            String database = plugin.getConfig().getString("storage.mysql.database", "teams");
            String username = plugin.getConfig().getString("storage.mysql.username", "root");
            String password = plugin.getConfig().getString("storage.mysql.password", "");
            boolean useSSL = plugin.getConfig().getBoolean("storage.mysql.use_ssl", false);
            String charset = plugin.getConfig().getString("storage.mysql.character_encoding", "utf8mb4");
            String collation = plugin.getConfig().getString("storage.mysql.collation", "utf8mb4_unicode_ci");

            if (host == null || host.isEmpty()) {
                plugin.getLogger().severe("MySQL host is not configured! Please set storage.mysql.host in config.yml");
                return false;
            }
            if (database == null || database.isEmpty()) {
                plugin.getLogger()
                        .severe("MySQL database is not configured! Please set storage.mysql.database in config.yml");
                return false;
            }

            String jdbcUrl = String.format(
                    "jdbc:mysql:%s:%d/%s?useSSL=%s&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                    host, port, database, useSSL);

            config.setJdbcUrl(jdbcUrl);
            config.setUsername(username);
            config.setPassword(password);

            int mysqlConnectionTimeout = plugin.getConfig().getInt("storage.mysql.connection_timeout", 30000);

            config.setConnectionTimeout(mysqlConnectionTimeout);
            config.setValidationTimeout(5000);
            config.setInitializationFailTimeout(mysqlConnectionTimeout);

            config.setMaximumPoolSize(Math.max(maxPoolSize, 10));
            config.setMinimumIdle(0);

            plugin.getLogger().info("MySQL connection configured:");
            plugin.getLogger().info("  Host: " + host + ":" + port);
            plugin.getLogger().info("  Database: " + database);
            plugin.getLogger().info("  Username: " + username);
            plugin.getLogger().info("  SSL: " + useSSL);
            plugin.getLogger().info("  Connection timeout: " + mysqlConnectionTimeout + "ms");
        } else {
            plugin.getLogger().info("MySQL not enabled or configured. Falling back to H2 file-based storage.");
            File dataFolder = new File(plugin.getDataFolder(), "data");
            if (!dataFolder.exists()) {
                dataFolder.mkdirs();
            }
            config.setDriverClassName("org.h2.Driver");

            String h2Url = "jdbc:h2:" + dataFolder.getAbsolutePath().replace("\\", "/") + "/teams" +
                    ";AUTO_SERVER=FALSE" +
                    ";DB_CLOSE_ON_EXIT=FALSE";

            config.setJdbcUrl(h2Url);
            plugin.getLogger().info("H2 JDBC URL: " + h2Url);

            config.setConnectionTimeout(8000);
            config.setValidationTimeout(2000);
            config.setMaximumPoolSize(3);
            config.setMinimumIdle(1);
            config.setIdleTimeout(300000);
            config.setMaxLifetime(1800000);

            config.getDataSourceProperties().clear();
        }
        if (useMySQL) {
            try {
                plugin.getLogger().info("Pre-testing direct JDBC connection...");
                String host = plugin.getConfig().getString("storage.mysql.host", "localhost");
                int port = plugin.getConfig().getInt("storage.mysql.port", 3306);
                String database = plugin.getConfig().getString("storage.mysql.database", "teams");
                String username = plugin.getConfig().getString("storage.mysql.username", "root");
                String password = plugin.getConfig().getString("storage.mysql.password", "");
                boolean useSSL = plugin.getConfig().getBoolean("storage.mysql.use_ssl", false);

                String testUrl = String.format(
                        "jdbc:mysql://%s:%d/%s?useSSL=%s&allowPublicKeyRetrieval=true&connectTimeout=10000&socketTimeout=10000",
                        host, port, database, useSSL);

                Class.forName("com.mysql.cj.jdbc.Driver");
                try (Connection testConn = DriverManager.getConnection(testUrl, username, password)) {
                    plugin.getLogger().info("✓ Direct JDBC connection successful!");
                    try (Statement stmt = testConn.createStatement(); ResultSet rs = stmt.executeQuery("SELECT 1")) {
                        if (rs.next()) {
                            plugin.getLogger().info("✓ Database query test successful!");
                        }
                    }
                }
            } catch (ClassNotFoundException e) {
                plugin.getLogger().severe("✗ MySQL JDBC Driver not found! This is a critical error.");
                plugin.getLogger().severe("The MySQL driver should be shaded into the plugin JAR.");
                return false;
            } catch (SQLException e) {
                plugin.getLogger().severe("✗ Direct JDBC connection failed!");
                plugin.getLogger().severe("Error: " + e.getMessage());
                plugin.getLogger().severe("SQLState: " + e.getSQLState());
                plugin.getLogger().severe("This means MySQL is not accessible. Fix this before continuing.");
                return false;
            } catch (Exception e) {
                plugin.getLogger().warning("Pre-test connection check failed: " + e.getMessage());
                plugin.getLogger().warning("Continuing with HikariCP initialization...");
            }
        }

        try {
            plugin.getLogger().info("Attempting to initialize HikariCP connection pool...");
            long startTime = System.currentTimeMillis();

            this.hikari = new HikariDataSource(config);
            plugin.getLogger().info("HikariCP pool created successfully");

            plugin.getLogger().info("Testing database connection (this may take up to "
                    + config.getConnectionTimeout() / 1000 + " seconds)...");

            try (Connection testConn = this.hikari.getConnection()) {
                long connectionTime = System.currentTimeMillis() - startTime;
                plugin.getLogger().info("Database connection test successful! (took " + connectionTime + "ms)");

                DatabaseMetaData metaData = testConn.getMetaData();
                plugin.getLogger().info("Connected to: " + metaData.getDatabaseProductName() + " "
                        + metaData.getDatabaseProductVersion());
                plugin.getLogger().info("JDBC URL: " + metaData.getURL());

                runMigrationsAndSchemaChecks();

                plugin.getLogger().info("Database initialization completed successfully!");
                return true;
            }
        } catch (Exception e) {
            if (!useMySQL && (e.getMessage().contains("90030") || e.getMessage().contains("File is corrupted"))) {
                plugin.getLogger().severe("============================================");
                plugin.getLogger().severe("DETECTED H2 DATABASE CORRUPTION!");
                plugin.getLogger().severe("============================================");
                plugin.getLogger().severe("The database file is corrupted and cannot be read.");
                plugin.getLogger().severe("Attempting automatic recovery by backing up and resetting...");

                if (recoverFromCorruption()) {
                    plugin.getLogger().info("Recovery successful! Retrying initialization...");
                    return init();
                } else {
                    plugin.getLogger().severe("Automatic recovery failed.");
                }
            }

            plugin.getLogger().severe("============================================");
            plugin.getLogger().severe("DATABASE CONNECTION FAILED!");
            plugin.getLogger().severe("============================================");
            plugin.getLogger().severe("Storage type: " + storageType);
            plugin.getLogger().severe("Error type: " + e.getClass().getSimpleName());
            plugin.getLogger().severe("Error message: " + e.getMessage());

            if (useMySQL) {
                plugin.getLogger().severe("");
                plugin.getLogger().severe("Troubleshooting steps:");
                plugin.getLogger()
                        .severe("1. Verify MySQL server is running at "
                                + plugin.getConfig().getString("storage.mysql.host") + ":"
                                + plugin.getConfig().getInt("storage.mysql.port"));
                plugin.getLogger().severe(
                        "2. Verify database '" + plugin.getConfig().getString("storage.mysql.database") + "' exists");
                plugin.getLogger().severe("3. Verify username and password are correct");
                plugin.getLogger().severe("4. Check firewall allows connection to MySQL port");
                plugin.getLogger().severe("5. Verify MySQL user has proper permissions (GRANT ALL on database)");
                plugin.getLogger().severe("");
                plugin.getLogger().severe("To use H2 (local file storage) instead, change:");
                plugin.getLogger().severe("  storage.type: \"h2\"");
                plugin.getLogger().severe("in config.yml");
            }

            plugin.getLogger().severe("============================================");

            if (this.hikari != null && !this.hikari.isClosed()) {
                try {
                    this.hikari.close();
                } catch (Exception cleanup) {
                    plugin.getLogger().warning("Error during cleanup: " + cleanup.getMessage());
                }
            }

            if (useMySQL) {
                plugin.getLogger().severe("MySQL connection failed. Plugin will NOT start.");
                plugin.getLogger().severe("Fix the database configuration or switch to H2 storage.");
                return false;
            }

            if (!storageType.equals("mysql")) {
                return tryMinimalH2Configuration();
            }

            return false;
        }
    }

    private boolean recoverFromCorruption() {
        try {
            if (this.hikari != null && !this.hikari.isClosed()) {
                try {
                    this.hikari.close();
                } catch (Exception ignored) {
                }
            }

            File dataFolder = new File(plugin.getDataFolder(), "data");
            File dbFile = new File(dataFolder, "teams.mv.db");

            if (!dbFile.exists()) {
                plugin.getLogger().warning("Recovery: Database file not found, nothing to backup.");
                return true;
            }

            String timestamp = String.valueOf(System.currentTimeMillis());
            File backupFile = new File(dataFolder, "teams.mv.db.corrupted." + timestamp);

            if (dbFile.renameTo(backupFile)) {
                plugin.getLogger().info("✓ Corrupted database backed up to: " + backupFile.getName());
                plugin.getLogger().info("✓ A new empty database will be created.");
                return true;
            } else {
                plugin.getLogger().severe("✗ Failed to rename corrupted database file!");
                return false;
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Error during recovery: " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    private void runMigrationsAndSchemaChecks() throws SQLException {
        plugin.getLogger().info("Verifying database schema...");

        try (Connection conn = getConnection()) {
            conn.setAutoCommit(true);

            try (Statement stmt = conn.createStatement()) {
                if (!storageType.equals("mysql")) {
                    try {
                        stmt.execute("SET MODE MySQL");
                        plugin.getLogger().info("H2 MySQL compatibility mode enabled");
                    } catch (SQLException e) {
                        plugin.getLogger().info("Could not set MySQL mode (not critical): " + e.getMessage());
                    }

                    try {
                        stmt.execute("SET IGNORECASE TRUE");
                        plugin.getLogger().info("H2 ignore case mode enabled");
                    } catch (SQLException e) {
                        plugin.getLogger().info("Could not set ignore case mode (not critical): " + e.getMessage());
                    }
                }

                if (!isUsingH2()) {
                    createMySQLTables(stmt);
                } else {
                    createH2Tables(stmt);
                }

                try {
                    stmt.execute("ALTER TABLE `donut_teams` ADD COLUMN `is_public` BOOLEAN DEFAULT FALSE");
                    plugin.getLogger().info("Added is_public column to donut_teams table");
                } catch (SQLException e) {
                    if (e.getErrorCode() == 42121 || e.getErrorCode() == 1060 || "42S21".equals(e.getSQLState()) ||
                            e.getMessage().toLowerCase().contains("already exists") ||
                            e.getMessage().toLowerCase().contains("duplicate")) {
                        if (plugin.getConfigManager().isDebugEnabled()) {
                            plugin.getLogger().info("is_public column already exists in donut_teams table");
                        }
                    } else {
                        plugin.getLogger().warning("Could not add is_public column: " + e.getMessage());
                    }
                }

                try {
                    if (!isUsingH2()) {
                        stmt.execute(
                                "ALTER TABLE `donut_cross_server_updates` ADD COLUMN `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP");
                    } else {
                        stmt.execute(
                                "ALTER TABLE `donut_cross_server_updates` ADD COLUMN `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP");
                    }
                    plugin.getLogger().info("Added created_at column to donut_cross_server_updates table");
                } catch (SQLException e) {
                    if (e.getErrorCode() == 42121 || e.getErrorCode() == 1060 || "42S21".equals(e.getSQLState()) ||
                            e.getMessage().toLowerCase().contains("already exists") ||
                            e.getMessage().toLowerCase().contains("duplicate")) {
                        if (plugin.getConfigManager().isDebugEnabled()) {
                            plugin.getLogger()
                                    .info("created_at column already exists in donut_cross_server_updates table");
                        }
                    } else {
                        plugin.getLogger().warning(
                                "Could not add created_at column to donut_cross_server_updates: " + e.getMessage());
                    }
                }

                try {
                    if (!isUsingH2()) {
                        stmt.execute(
                                "ALTER TABLE `donut_cross_server_messages` ADD COLUMN `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP");
                    } else {
                        stmt.execute(
                                "ALTER TABLE `donut_cross_server_messages` ADD COLUMN `created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP");
                    }
                    plugin.getLogger().info("Added created_at column to donut_cross_server_messages table");
                } catch (SQLException e) {
                    if (e.getErrorCode() == 42121 || e.getErrorCode() == 1060 || "42S21".equals(e.getSQLState()) ||
                            e.getMessage().toLowerCase().contains("already exists") ||
                            e.getMessage().toLowerCase().contains("duplicate")) {
                        if (plugin.getConfigManager().isDebugEnabled()) {
                            plugin.getLogger()
                                    .info("created_at column already exists in donut_cross_server_messages table");
                        }
                    } else {
                        plugin.getLogger().warning(
                                "Could not add created_at column to donut_cross_server_messages: " + e.getMessage());
                    }
                }

                try {
                    stmt.execute("ALTER TABLE `donut_teams` ADD COLUMN `alias` VARCHAR(32) DEFAULT NULL");
                    plugin.getLogger().info("Added alias column to donut_teams table");
                } catch (SQLException e) {
                    if (e.getErrorCode() == 42121 || e.getErrorCode() == 1060 || "42S21".equals(e.getSQLState()) ||
                            e.getMessage().toLowerCase().contains("already exists") ||
                            e.getMessage().toLowerCase().contains("duplicate")) {
                        if (plugin.getConfigManager().isDebugEnabled()) {
                            plugin.getLogger().info("alias column already exists in donut_teams table");
                        }
                    } else {
                        plugin.getLogger().warning("Could not add alias column to donut_teams: " + e.getMessage());
                    }
                }

                try {
                    stmt.execute("ALTER TABLE `donut_teams` ADD COLUMN `glow_enabled` BOOLEAN DEFAULT TRUE");
                    plugin.getLogger().info("Added glow_enabled column to donut_teams table");
                } catch (SQLException e) {
                    if (e.getErrorCode() == 42121 || e.getErrorCode() == 1060 || "42S21".equals(e.getSQLState()) ||
                            e.getMessage().toLowerCase().contains("already exists") ||
                            e.getMessage().toLowerCase().contains("duplicate")) {
                        if (plugin.getConfigManager().isDebugEnabled()) {
                            plugin.getLogger().info("glow_enabled column already exists in donut_teams table");
                        }
                    } else {
                        plugin.getLogger()
                                .warning("Could not add glow_enabled column to donut_teams: " + e.getMessage());
                    }
                }

                try {
                    stmt.execute("ALTER TABLE `donut_teams` ADD COLUMN `color` VARCHAR(32) DEFAULT NULL");
                    plugin.getLogger().info("Added color column to donut_teams table");
                } catch (SQLException e) {
                    if (e.getErrorCode() == 42121 || e.getErrorCode() == 1060 || "42S21".equals(e.getSQLState()) ||
                            e.getMessage().toLowerCase().contains("already exists") ||
                            e.getMessage().toLowerCase().contains("duplicate")) {
                        if (plugin.getConfigManager().isDebugEnabled()) {
                            plugin.getLogger().info("color column already exists in donut_teams table");
                        }
                    } else {
                        plugin.getLogger()
                                .warning("Could not add color column to donut_teams: " + e.getMessage());
                    }
                }

                plugin.getLogger().info("Database schema verified successfully.");
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Error during database schema setup: " + e.getMessage());
            throw e;
        }
    }

    private void createTable(Statement stmt, String tableName, String sql) {
        try {
            stmt.execute(sql);
            plugin.getLogger().info("✓ Table " + tableName + " verified/created successfully");
        } catch (SQLException e) {
            plugin.getLogger().warning("✗ Failed to create table " + tableName + ": " + e.getMessage());
            throw new RuntimeException("Failed to create table " + tableName, e);
        }
    }

    private void createIndex(Statement stmt, String indexName, String tableName, String columns) {
        try {
            if ("mysql".equals(storageType)) {
                stmt.execute("ALTER TABLE " + tableName + " ADD INDEX " + indexName + " (" + columns + ")");
            } else {
                stmt.execute("CREATE INDEX IF NOT EXISTS " + indexName + " ON " + tableName + " (" + columns + ")");
            }
            plugin.getLogger().info("✓ Index " + indexName + " created successfully");
        } catch (SQLException e) {
            plugin.getLogger()
                    .info("Note: Could not create index " + indexName + " (may already exist): " + e.getMessage());
        }
    }

    private void createUniqueIndex(Statement stmt, String indexName, String tableName, String columns) {
        try {
            if ("mysql".equals(storageType)) {
                stmt.execute("ALTER TABLE " + tableName + " ADD UNIQUE INDEX " + indexName + " (" + columns + ")");
            } else {
                stmt.execute(
                        "CREATE UNIQUE INDEX IF NOT EXISTS " + indexName + " ON " + tableName + " (" + columns + ")");
            }
            plugin.getLogger().info("✓ Unique index " + indexName + " created successfully");
        } catch (SQLException e) {
            plugin.getLogger().info(
                    "Note: Could not create unique index " + indexName + " (may already exist): " + e.getMessage());
        }
    }

    private void createMySQLTables(Statement stmt) throws SQLException {
        createTable(stmt, "donut_teams",
                "CREATE TABLE IF NOT EXISTS `donut_teams` (" +
                        "`id` INT AUTO_INCREMENT, " +
                        "`name` VARCHAR(16) NOT NULL UNIQUE, " +
                        "`tag` VARCHAR(6) NOT NULL, " +
                        "`owner_uuid` VARCHAR(36) NOT NULL, " +
                        "`home_location` VARCHAR(255), " +
                        "`home_server` VARCHAR(255), " +
                        "`pvp_enabled` BOOLEAN DEFAULT TRUE, " +
                        "`is_public` BOOLEAN DEFAULT FALSE, " +
                        "`glow_enabled` BOOLEAN DEFAULT TRUE, " +
                        "`color` VARCHAR(32) DEFAULT NULL, " +
                        "`created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                        "`description` VARCHAR(64) DEFAULT NULL, " +
                        "`balance` DOUBLE DEFAULT 0.0, " +
                        "`kills` INT DEFAULT 0, " +
                        "`deaths` INT DEFAULT 0, " +
                        "PRIMARY KEY (`id`))");

        createTable(stmt, "donut_team_members",
                "CREATE TABLE IF NOT EXISTS `donut_team_members` (" +
                        "`player_uuid` VARCHAR(36) NOT NULL, " +
                        "`team_id` INT NOT NULL, " +
                        "`role` VARCHAR(16) NOT NULL, " +
                        "`join_date` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, " +
                        "`can_withdraw` BOOLEAN DEFAULT FALSE, " +
                        "`can_use_enderchest` BOOLEAN DEFAULT TRUE, " +
                        "`can_set_home` BOOLEAN DEFAULT FALSE, " +
                        "`can_use_home` BOOLEAN DEFAULT TRUE, " +
                        "PRIMARY KEY (`player_uuid`), " +
                        "FOREIGN KEY (`team_id`) REFERENCES `donut_teams`(`id`) ON DELETE CASCADE)");

        createTable(stmt, "donut_team_enderchest",
                "CREATE TABLE IF NOT EXISTS `donut_team_enderchest` (" +
                        "`team_id` INT NOT NULL, " +
                        "`inventory_data` TEXT, " +
                        "PRIMARY KEY (`team_id`), " +
                        "FOREIGN KEY (`team_id`) REFERENCES `donut_teams`(`id`) ON DELETE CASCADE)");

        createTable(stmt, "donut_pending_teleports",
                "CREATE TABLE IF NOT EXISTS `donut_pending_teleports` (" +
                        "`player_uuid` VARCHAR(36) NOT NULL, " +
                        "`destination_server` VARCHAR(255) NOT NULL, " +
                        "`destination_location` VARCHAR(255) NOT NULL, " +
                        "`created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, " +
                        "PRIMARY KEY (`player_uuid`))");

        createTable(stmt, "donut_servers",
                "CREATE TABLE IF NOT EXISTS `donut_servers` (" +
                        "`server_name` VARCHAR(64) PRIMARY KEY, " +
                        "`last_heartbeat` TIMESTAMP NOT NULL)");

        createTable(stmt, "donut_team_locks",
                "CREATE TABLE IF NOT EXISTS `donut_team_locks` (" +
                        "`team_id` INT PRIMARY KEY, " +
                        "`server_identifier` VARCHAR(255) NOT NULL, " +
                        "`lock_time` TIMESTAMP NOT NULL, " +
                        "FOREIGN KEY (`team_id`) REFERENCES `donut_teams`(`id`) ON DELETE CASCADE)");

        createTable(stmt, "donut_cross_server_updates",
                "CREATE TABLE IF NOT EXISTS `donut_cross_server_updates` (" +
                        "`id` INT AUTO_INCREMENT PRIMARY KEY, " +
                        "`team_id` INT NOT NULL, " +
                        "`update_type` VARCHAR(50) NOT NULL, " +
                        "`player_uuid` VARCHAR(36) NOT NULL, " +
                        "`server_name` VARCHAR(64) NOT NULL, " +
                        "`created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                        "FOREIGN KEY (`team_id`) REFERENCES `donut_teams`(`id`) ON DELETE CASCADE)");

        createTable(stmt, "donut_cross_server_messages",
                "CREATE TABLE IF NOT EXISTS `donut_cross_server_messages` (" +
                        "`id` INT AUTO_INCREMENT PRIMARY KEY, " +
                        "`team_id` INT NOT NULL, " +
                        "`player_uuid` VARCHAR(36) NOT NULL, " +
                        "`message` TEXT NOT NULL, " +
                        "`server_name` VARCHAR(64) NOT NULL, " +
                        "`created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                        "FOREIGN KEY (`team_id`) REFERENCES `donut_teams`(`id`) ON DELETE CASCADE)");

        createTable(stmt, "donut_team_homes",
                "CREATE TABLE IF NOT EXISTS `donut_team_homes` (" +
                        "`team_id` INT PRIMARY KEY, " +
                        "`location` VARCHAR(255) NOT NULL, " +
                        "`server_name` VARCHAR(64) NOT NULL, " +
                        "FOREIGN KEY (`team_id`) REFERENCES `donut_teams`(`id`) ON DELETE CASCADE)");

        createTable(stmt, "donut_team_warps",
                "CREATE TABLE IF NOT EXISTS `donut_team_warps` (" +
                        "`id` INT AUTO_INCREMENT PRIMARY KEY, " +
                        "`team_id` INT NOT NULL, " +
                        "`warp_name` VARCHAR(32) NOT NULL, " +
                        "`location` VARCHAR(255) NOT NULL, " +
                        "`server_name` VARCHAR(64) NOT NULL, " +
                        "`password` VARCHAR(64), " +
                        "`created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                        "UNIQUE KEY `team_warp` (`team_id`, `warp_name`), " +
                        "FOREIGN KEY (`team_id`) REFERENCES `donut_teams`(`id`) ON DELETE CASCADE)");

        createTable(stmt, "donut_join_requests",
                "CREATE TABLE IF NOT EXISTS `donut_join_requests` (" +
                        "`id` INT AUTO_INCREMENT PRIMARY KEY, " +
                        "`team_id` INT NOT NULL, " +
                        "`player_uuid` VARCHAR(36) NOT NULL, " +
                        "`created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                        "UNIQUE KEY `team_player_request` (`team_id`, `player_uuid`), " +
                        "FOREIGN KEY (`team_id`) REFERENCES `donut_teams`(`id`) ON DELETE CASCADE)");

        createTable(stmt, "donut_ender_chest_locks",
                "CREATE TABLE IF NOT EXISTS `donut_ender_chest_locks` (" +
                        "`team_id` INT PRIMARY KEY, " +
                        "`server_identifier` VARCHAR(255) NOT NULL, " +
                        "`lock_time` TIMESTAMP NOT NULL, " +
                        "FOREIGN KEY (`team_id`) REFERENCES `donut_teams`(`id`) ON DELETE CASCADE)");

        createTable(stmt, "donut_player_cache",
                "CREATE TABLE IF NOT EXISTS `donut_player_cache` (" +
                        "`player_uuid` VARCHAR(36) PRIMARY KEY, " +
                        "`player_name` VARCHAR(16) NOT NULL, " +
                        "`last_seen` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, " +
                        "INDEX `idx_player_name` (`player_name`))");

        createTable(stmt, "donut_team_invites",
                "CREATE TABLE IF NOT EXISTS `donut_team_invites` (" +
                        "`id` INT AUTO_INCREMENT PRIMARY KEY, " +
                        "`team_id` INT NOT NULL, " +
                        "`player_uuid` VARCHAR(36) NOT NULL, " +
                        "`inviter_uuid` VARCHAR(36) NOT NULL, " +
                        "`created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                        "UNIQUE KEY `team_player_invite` (`team_id`, `player_uuid`), " +
                        "FOREIGN KEY (`team_id`) REFERENCES `donut_teams`(`id`) ON DELETE CASCADE)");

        createTable(stmt, "donut_team_blacklist",
                "CREATE TABLE IF NOT EXISTS `donut_team_blacklist` (" +
                        "`id` INT AUTO_INCREMENT PRIMARY KEY, " +
                        "`team_id` INT NOT NULL, " +
                        "`player_uuid` VARCHAR(36) NOT NULL, " +
                        "`player_name` VARCHAR(16) NOT NULL, " +
                        "`reason` TEXT, " +
                        "`blacklisted_by_uuid` VARCHAR(36) NOT NULL, " +
                        "`blacklisted_by_name` VARCHAR(16) NOT NULL, " +
                        "`blacklisted_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                        "UNIQUE KEY `team_player_blacklist` (`team_id`, `player_uuid`), " +
                        "FOREIGN KEY (`team_id`) REFERENCES `donut_teams`(`id`) ON DELETE CASCADE)");

        createTable(stmt, "donut_player_sessions",
                "CREATE TABLE IF NOT EXISTS `donut_player_sessions` (" +
                        "`player_uuid` VARCHAR(36) PRIMARY KEY, " +
                        "`server_name` VARCHAR(255) NOT NULL, " +
                        "`last_seen` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, " +
                        "INDEX `idx_server_name` (`server_name`), " +
                        "INDEX `idx_last_seen` (`last_seen`))");

        createTable(stmt, "donut_server_aliases",
                "CREATE TABLE IF NOT EXISTS `donut_server_aliases` (" +
                        "`server_name` VARCHAR(255) PRIMARY KEY, " +
                        "`alias` VARCHAR(64) NOT NULL, " +
                        "`created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                        "`updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP)");

        createTable(stmt, "donut_team_rename_cooldowns",
                "CREATE TABLE IF NOT EXISTS `donut_team_rename_cooldowns` (" +
                        "`team_id` INT PRIMARY KEY, " +
                        "`last_rename` TIMESTAMP NOT NULL, " +
                        "`created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                        "FOREIGN KEY (`team_id`) REFERENCES `donut_teams`(`id`) ON DELETE CASCADE)");

        createTable(stmt, "donut_team_custom_data",
                "CREATE TABLE IF NOT EXISTS `donut_team_custom_data` (" +
                        "`team_id` INT NOT NULL, " +
                        "`data_key` VARCHAR(255) NOT NULL, " +
                        "`data_value` TEXT NOT NULL, " +
                        "`created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                        "`updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP, " +
                        "PRIMARY KEY (`team_id`, `data_key`), " +
                        "FOREIGN KEY (`team_id`) REFERENCES `donut_teams`(`id`) ON DELETE CASCADE)");
    }

    private void createH2Tables(Statement stmt) throws SQLException {
        createTable(stmt, "donut_teams",
                "CREATE TABLE IF NOT EXISTS `donut_teams` (" +
                        "`id` INT AUTO_INCREMENT, " +
                        "`name` VARCHAR(16) NOT NULL UNIQUE, " +
                        "`tag` VARCHAR(6) NOT NULL, " +
                        "`owner_uuid` VARCHAR(36) NOT NULL, " +
                        "`home_location` VARCHAR(255), " +
                        "`home_server` VARCHAR(255), " +
                        "`pvp_enabled` BOOLEAN DEFAULT TRUE, " +
                        "`is_public` BOOLEAN DEFAULT FALSE, " +
                        "`glow_enabled` BOOLEAN DEFAULT TRUE, " +
                        "`color` VARCHAR(32) DEFAULT NULL, " +
                        "`created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                        "`description` VARCHAR(64) DEFAULT NULL, " +
                        "`balance` DOUBLE DEFAULT 0.0, " +
                        "`kills` INT DEFAULT 0, " +
                        "`deaths` INT DEFAULT 0, " +
                        "PRIMARY KEY (`id`))");

        createTable(stmt, "donut_team_members",
                "CREATE TABLE IF NOT EXISTS `donut_team_members` (" +
                        "`player_uuid` VARCHAR(36) NOT NULL, " +
                        "`team_id` INT NOT NULL, " +
                        "`role` VARCHAR(16) NOT NULL, " +
                        "`join_date` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, " +
                        "`can_withdraw` BOOLEAN DEFAULT FALSE, " +
                        "`can_use_enderchest` BOOLEAN DEFAULT TRUE, " +
                        "`can_set_home` BOOLEAN DEFAULT FALSE, " +
                        "`can_use_home` BOOLEAN DEFAULT TRUE, " +
                        "PRIMARY KEY (`player_uuid`), " +
                        "FOREIGN KEY (`team_id`) REFERENCES `donut_teams`(`id`) ON DELETE CASCADE)");

        createTable(stmt, "donut_team_enderchest",
                "CREATE TABLE IF NOT EXISTS `donut_team_enderchest` (" +
                        "`team_id` INT NOT NULL, " +
                        "`inventory_data` TEXT, " +
                        "PRIMARY KEY (`team_id`), " +
                        "FOREIGN KEY (`team_id`) REFERENCES `donut_teams`(`id`) ON DELETE CASCADE)");

        createTable(stmt, "donut_pending_teleports",
                "CREATE TABLE IF NOT EXISTS `donut_pending_teleports` (" +
                        "`player_uuid` VARCHAR(36) NOT NULL, " +
                        "`destination_server` VARCHAR(255) NOT NULL, " +
                        "`destination_location` VARCHAR(255) NOT NULL, " +
                        "`created_at` TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP, " +
                        "PRIMARY KEY (`player_uuid`))");

        createTable(stmt, "donut_servers",
                "CREATE TABLE IF NOT EXISTS `donut_servers` (" +
                        "`server_name` VARCHAR(64) PRIMARY KEY, " +
                        "`last_heartbeat` TIMESTAMP NOT NULL)");

        createTable(stmt, "donut_team_locks",
                "CREATE TABLE IF NOT EXISTS `donut_team_locks` (" +
                        "`team_id` INT PRIMARY KEY, " +
                        "`server_identifier` VARCHAR(255) NOT NULL, " +
                        "`lock_time` TIMESTAMP NOT NULL, " +
                        "FOREIGN KEY (`team_id`) REFERENCES `donut_teams`(`id`) ON DELETE CASCADE)");

        createTable(stmt, "donut_cross_server_updates",
                "CREATE TABLE IF NOT EXISTS `donut_cross_server_updates` (" +
                        "`id` INT AUTO_INCREMENT PRIMARY KEY, " +
                        "`team_id` INT NOT NULL, " +
                        "`update_type` VARCHAR(50) NOT NULL, " +
                        "`player_uuid` VARCHAR(36) NOT NULL, " +
                        "`server_name` VARCHAR(64) NOT NULL, " +
                        "`created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                        "FOREIGN KEY (`team_id`) REFERENCES `donut_teams`(`id`) ON DELETE CASCADE)");

        createTable(stmt, "donut_cross_server_messages",
                "CREATE TABLE IF NOT EXISTS `donut_cross_server_messages` (" +
                        "`id` INT AUTO_INCREMENT PRIMARY KEY, " +
                        "`team_id` INT NOT NULL, " +
                        "`player_uuid` VARCHAR(36) NOT NULL, " +
                        "`message` TEXT NOT NULL, " +
                        "`server_name` VARCHAR(64) NOT NULL, " +
                        "`created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                        "FOREIGN KEY (`team_id`) REFERENCES `donut_teams`(`id`) ON DELETE CASCADE)");

        createTable(stmt, "donut_team_homes",
                "CREATE TABLE IF NOT EXISTS `donut_team_homes` (" +
                        "`team_id` INT PRIMARY KEY, " +
                        "`location` VARCHAR(255) NOT NULL, " +
                        "`server_name` VARCHAR(64) NOT NULL, " +
                        "FOREIGN KEY (`team_id`) REFERENCES `donut_teams`(`id`) ON DELETE CASCADE)");

        createTable(stmt, "donut_team_warps",
                "CREATE TABLE IF NOT EXISTS `donut_team_warps` (" +
                        "`id` INT AUTO_INCREMENT PRIMARY KEY, " +
                        "`team_id` INT NOT NULL, " +
                        "`warp_name` VARCHAR(32) NOT NULL, " +
                        "`location` VARCHAR(255) NOT NULL, " +
                        "`server_name` VARCHAR(64) NOT NULL, " +
                        "`password` VARCHAR(64), " +
                        "`created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                        "FOREIGN KEY (`team_id`) REFERENCES `donut_teams`(`id`) ON DELETE CASCADE)");

        createUniqueIndex(stmt, "idx_team_warp", "`donut_team_warps`", "`team_id`, `warp_name`");

        createTable(stmt, "donut_join_requests",
                "CREATE TABLE IF NOT EXISTS `donut_join_requests` (" +
                        "`id` INT AUTO_INCREMENT PRIMARY KEY, " +
                        "`team_id` INT NOT NULL, " +
                        "`player_uuid` VARCHAR(36) NOT NULL, " +
                        "`created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                        "FOREIGN KEY (`team_id`) REFERENCES `donut_teams`(`id`) ON DELETE CASCADE)");

        createUniqueIndex(stmt, "idx_team_player_request", "`donut_join_requests`", "`team_id`, `player_uuid`");

        createTable(stmt, "donut_ender_chest_locks",
                "CREATE TABLE IF NOT EXISTS `donut_ender_chest_locks` (" +
                        "`team_id` INT PRIMARY KEY, " +
                        "`server_identifier` VARCHAR(255) NOT NULL, " +
                        "`lock_time` TIMESTAMP NOT NULL, " +
                        "FOREIGN KEY (`team_id`) REFERENCES `donut_teams`(`id`) ON DELETE CASCADE)");

        createTable(stmt, "donut_player_cache",
                "CREATE TABLE IF NOT EXISTS `donut_player_cache` (" +
                        "`player_uuid` VARCHAR(36) PRIMARY KEY, " +
                        "`player_name` VARCHAR(16) NOT NULL, " +
                        "`last_seen` TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");

        createIndex(stmt, "idx_player_name", "`donut_player_cache`", "`player_name`");

        createTable(stmt, "donut_team_invites",
                "CREATE TABLE IF NOT EXISTS `donut_team_invites` (" +
                        "`id` INT AUTO_INCREMENT PRIMARY KEY, " +
                        "`team_id` INT NOT NULL, " +
                        "`player_uuid` VARCHAR(36) NOT NULL, " +
                        "`inviter_uuid` VARCHAR(36) NOT NULL, " +
                        "`created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                        "FOREIGN KEY (`team_id`) REFERENCES `donut_teams`(`id`) ON DELETE CASCADE)");

        createUniqueIndex(stmt, "idx_team_player_invite", "`donut_team_invites`", "`team_id`, `player_uuid`");

        createTable(stmt, "donut_team_blacklist",
                "CREATE TABLE IF NOT EXISTS `donut_team_blacklist` (" +
                        "`id` INT AUTO_INCREMENT PRIMARY KEY, " +
                        "`team_id` INT NOT NULL, " +
                        "`player_uuid` VARCHAR(36) NOT NULL, " +
                        "`player_name` VARCHAR(16) NOT NULL, " +
                        "`reason` TEXT, " +
                        "`blacklisted_by_uuid` VARCHAR(36) NOT NULL, " +
                        "`blacklisted_by_name` VARCHAR(16) NOT NULL, " +
                        "`blacklisted_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                        "FOREIGN KEY (`team_id`) REFERENCES `donut_teams`(`id`) ON DELETE CASCADE)");

        createUniqueIndex(stmt, "idx_team_player_blacklist", "`donut_team_blacklist`", "`team_id`, `player_uuid`");

        createTable(stmt, "donut_player_sessions",
                "CREATE TABLE IF NOT EXISTS `donut_player_sessions` (" +
                        "`player_uuid` VARCHAR(36) PRIMARY KEY, " +
                        "`server_name` VARCHAR(255) NOT NULL, " +
                        "`last_seen` TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");

        createIndex(stmt, "idx_session_server", "`donut_player_sessions`", "`server_name`");
        createIndex(stmt, "idx_session_last_seen", "`donut_player_sessions`", "`last_seen`");

        createTable(stmt, "donut_server_aliases",
                "CREATE TABLE IF NOT EXISTS `donut_server_aliases` (" +
                        "`server_name` VARCHAR(255) PRIMARY KEY, " +
                        "`alias` VARCHAR(64) NOT NULL, " +
                        "`created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                        "`updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");

        createTable(stmt, "donut_team_rename_cooldowns",
                "CREATE TABLE IF NOT EXISTS `donut_team_rename_cooldowns` (" +
                        "`team_id` INT PRIMARY KEY, " +
                        "`last_rename` TIMESTAMP NOT NULL, " +
                        "`created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                        "FOREIGN KEY (`team_id`) REFERENCES `donut_teams`(`id`) ON DELETE CASCADE)");

        createTable(stmt, "donut_team_custom_data",
                "CREATE TABLE IF NOT EXISTS `donut_team_custom_data` (" +
                        "`team_id` INT NOT NULL, " +
                        "`data_key` VARCHAR(255) NOT NULL, " +
                        "`data_value` TEXT NOT NULL, " +
                        "`created_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                        "`updated_at` TIMESTAMP DEFAULT CURRENT_TIMESTAMP, " +
                        "PRIMARY KEY (`team_id`, `data_key`), " +
                        "FOREIGN KEY (`team_id`) REFERENCES `donut_teams`(`id`) ON DELETE CASCADE)");
    }

    private boolean tryMinimalH2Configuration() {
        plugin.getLogger().info("Attempting minimal H2 configuration...");

        try {
            HikariConfig fallbackConfig = new HikariConfig();
            fallbackConfig.setPoolName("justTeams-Pool-Fallback");

            fallbackConfig.setMaximumPoolSize(2);
            fallbackConfig.setMinimumIdle(1);
            fallbackConfig.setConnectionTimeout(5000);
            fallbackConfig.setValidationTimeout(1000);
            fallbackConfig.setIdleTimeout(300000);
            fallbackConfig.setMaxLifetime(600000);
            fallbackConfig.setConnectionTestQuery("SELECT 1");

            File dataFolder = new File(plugin.getDataFolder(), "data");
            if (!dataFolder.exists()) {
                dataFolder.mkdirs();
            }

            fallbackConfig.setDriverClassName("org.h2.Driver");
            fallbackConfig.setJdbcUrl("jdbc:h2:" + dataFolder.getAbsolutePath().replace("\\", "/") + "/teams");

            plugin.getLogger().info("Testing minimal H2 configuration...");
            this.hikari = new HikariDataSource(fallbackConfig);

            try (Connection testConn = this.hikari.getConnection()) {
                plugin.getLogger().info("Minimal H2 configuration successful!");

                runMigrationsAndSchemaChecks();

                plugin.getLogger().info("Fallback H2 initialization completed successfully!");
                return true;
            }

        } catch (Exception fallbackError) {
            plugin.getLogger().severe("Even minimal H2 configuration failed: " + fallbackError.getMessage());
            fallbackError.printStackTrace();

            if (this.hikari != null && !this.hikari.isClosed()) {
                try {
                    this.hikari.close();
                } catch (Exception cleanup) {
                    plugin.getLogger().warning("Error during fallback cleanup: " + cleanup.getMessage());
                }
            }
            return false;
        }
    }

    @Override
    public void shutdown() {
        plugin.getLogger().info("Shutting down database storage...");

        synchronized (heartbeatLock) {
            try {
                if (heartbeatStatement != null && !heartbeatStatement.isClosed()) {
                    heartbeatStatement.close();
                    plugin.getLogger().info("Heartbeat statement closed");
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Error closing heartbeat statement: " + e.getMessage());
            } finally {
                heartbeatStatement = null;
            }
        }

        if (isConnected()) {
            try {
                plugin.getLogger().info("Closing database connection pool...");
                hikari.close();
                plugin.getLogger().info("Database connection pool closed successfully");
            } catch (Exception e) {
                plugin.getLogger().warning("Error closing database connection pool: " + e.getMessage());
            }
        } else {
            plugin.getLogger().info("Database connection was already closed");
        }
    }

    @Override
    public boolean isConnected() {
        try {
            return hikari != null && !hikari.isClosed();
        } catch (Exception e) {
            plugin.getLogger().warning("Error checking connection status: " + e.getMessage());
            return false;
        }
    }

    public Connection getConnection() throws SQLException {
        if (!isConnected()) {
            throw new SQLException("Database connection pool is not available");
        }

        try {
            Connection conn = hikari.getConnection();
            if (!conn.isValid(3)) {
                conn.close();
                throw new SQLException("Connection validation failed");
            }
            return conn;
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to get database connection: " + e.getMessage());
            throw e;
        }
    }

    public boolean attemptConnectionRecovery() {
        if (isConnected()) {
            return true;
        }

        plugin.getLogger().warning("Database connection lost, attempting recovery...");

        try {
            shutdown();
            Thread.sleep(1000);
            return init();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            plugin.getLogger().severe("Connection recovery interrupted");
            return false;
        } catch (Exception e) {
            plugin.getLogger().severe("Failed to recover database connection: " + e.getMessage());
            return false;
        }
    }

    @Override
    public Optional<Team> createTeam(String name, String tag, UUID ownerUuid, boolean defaultPvp,
            boolean defaultPublic, boolean defaultGlowStatus) {
        String insertTeamSQL = "INSERT INTO donut_teams (name, tag, owner_uuid, pvp_enabled, is_public, glow_enabled, color) VALUES (?, ?, ?, ?, ?, ?, ?)";
        String insertMemberSQL = "INSERT INTO donut_team_members (player_uuid, team_id, role, join_date, can_withdraw, can_use_enderchest, can_set_home, can_use_home) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement teamStmt = conn.prepareStatement(insertTeamSQL, Statement.RETURN_GENERATED_KEYS)) {
                teamStmt.setString(1, name);
                teamStmt.setString(2, tag);
                teamStmt.setString(3, ownerUuid.toString());
                teamStmt.setBoolean(4, defaultPvp);
                teamStmt.setBoolean(5, defaultPublic);
                teamStmt.setBoolean(6, defaultGlowStatus);
                teamStmt.setString(7, null);
                teamStmt.executeUpdate();
                ResultSet generatedKeys = teamStmt.getGeneratedKeys();
                if (generatedKeys.next()) {
                    int teamId = generatedKeys.getInt(1);
                    try (PreparedStatement memberStmt = conn.prepareStatement(insertMemberSQL)) {
                        memberStmt.setString(1, ownerUuid.toString());
                        memberStmt.setInt(2, teamId);
                        memberStmt.setString(3, TeamRole.OWNER.name());
                        memberStmt.setTimestamp(4, Timestamp.from(Instant.now()));
                        memberStmt.setBoolean(5, true);
                        memberStmt.setBoolean(6, true);
                        memberStmt.setBoolean(7, true);
                        memberStmt.setBoolean(8, true);
                        memberStmt.executeUpdate();
                    }
                    conn.commit();
                    return findTeamById(teamId);
                } else {
                    conn.rollback();
                    return Optional.empty();
                }
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not create team in database: " + e.getMessage());
            return Optional.empty();
        }
    }

    @Override
    public void deleteTeam(int teamId) {
        String sql = "DELETE FROM donut_teams WHERE id = ?";
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, teamId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not delete team with ID " + teamId + ": " + e.getMessage());
        }
    }

    @Override
    public boolean addMemberToTeam(int teamId, UUID playerUuid) {
        String checkSql = "SELECT team_id FROM donut_team_members WHERE player_uuid = ? FOR UPDATE";
        String insertSql = "INSERT INTO donut_team_members (player_uuid, team_id, role, join_date, can_withdraw, can_use_enderchest, can_set_home, can_use_home) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";

        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);

            try {
                try (PreparedStatement checkStmt = conn.prepareStatement(checkSql)) {
                    checkStmt.setString(1, playerUuid.toString());
                    ResultSet rs = checkStmt.executeQuery();
                    if (rs.next()) {
                        conn.rollback();
                        if (plugin.getConfigManager().isDebugEnabled()) {
                            plugin.getDebugLogger().log(
                                    "Player " + playerUuid + " is already in a team, cannot add to team " + teamId);
                        }
                        return false;
                    }
                }

                try (PreparedStatement stmt = conn.prepareStatement(insertSql)) {
                    stmt.setString(1, playerUuid.toString());
                    stmt.setInt(2, teamId);
                    stmt.setString(3, TeamRole.MEMBER.name());
                    stmt.setTimestamp(4, Timestamp.from(Instant.now()));
                    stmt.setBoolean(5, false);
                    stmt.setBoolean(6, true);
                    stmt.setBoolean(7, false);
                    stmt.setBoolean(8, true);
                    int rowsAffected = stmt.executeUpdate();

                    if (rowsAffected > 0) {
                        conn.commit();
                        return true;
                    } else {
                        conn.rollback();
                        return false;
                    }
                }
            } catch (SQLException e) {
                try {
                    conn.rollback();
                } catch (SQLException rollbackEx) {
                    plugin.getLogger().severe("Error rolling back transaction: " + rollbackEx.getMessage());
                }

                if (e.getMessage().contains("Duplicate entry") || e.getMessage().contains("unique constraint")
                        || e.getMessage().contains("UNIQUE")) {
                    if (plugin.getConfigManager().isDebugEnabled()) {
                        plugin.getDebugLogger().log("Duplicate key detected when adding member " + playerUuid
                                + " to team " + teamId + " - race condition prevented");
                    }
                    return false;
                }

                plugin.getLogger()
                        .severe("Could not add member " + playerUuid + " to team " + teamId + ": " + e.getMessage());
                if (plugin.getConfigManager().isDebugEnabled()) {
                    e.printStackTrace();
                }
                throw e;
            } finally {
                try {
                    conn.setAutoCommit(true);
                } catch (SQLException e) {
                    plugin.getLogger().warning("Error restoring auto-commit: " + e.getMessage());
                }
            }
        } catch (SQLException e) {
            return false;
        }
    }

    @Override
    public void removeMemberFromTeam(UUID playerUuid) {
        String sql = "DELETE FROM donut_team_members WHERE player_uuid = ?";
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, playerUuid.toString());
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not remove member " + playerUuid + ": " + e.getMessage());
        }
    }

    @Override
    public Optional<Team> findTeamByPlayer(UUID playerUuid) {
        String sql = "SELECT t.* FROM donut_teams t JOIN donut_team_members tm ON t.id = tm.team_id WHERE tm.player_uuid = ?";
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, playerUuid.toString());
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return Optional.of(mapTeamFromResultSet(rs));
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Error finding team by player: " + e.getMessage());
        }
        return Optional.empty();
    }

    @Override
    public Optional<Team> findTeamByName(String name) {
        String sql = "SELECT * FROM donut_teams WHERE name = ?";
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, name);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return Optional.of(mapTeamFromResultSet(rs));
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Error finding team by name: " + e.getMessage());
        }
        return Optional.empty();
    }

    @Override
    public Optional<Team> findTeamById(int id) {
        String sql = "SELECT * FROM donut_teams WHERE id = ?";
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, id);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return Optional.of(mapTeamFromResultSet(rs));
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Error finding team by id: " + e.getMessage());
        }
        return Optional.empty();
    }

    @Override
    public List<Team> getAllTeams() {
        List<Team> teams = new ArrayList<>();
        String sql = "SELECT * FROM donut_teams ORDER BY created_at DESC";
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                teams.add(mapTeamFromResultSet(rs));
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Error getting all teams: " + e.getMessage());
        }
        return teams;
    }

    @Override
    public List<TeamPlayer> getTeamMembers(int teamId) {
        List<TeamPlayer> members = new ArrayList<>();
        String sql = "SELECT * FROM donut_team_members WHERE team_id = ?";
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, teamId);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                members.add(mapPlayerFromResultSet(rs));
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Error getting team members: " + e.getMessage());
        }
        return members;
    }

    @Override
    public void setTeamHome(int teamId, Location location, String serverName) {
        String sql = "UPDATE donut_teams SET home_location = ?, home_server = ? WHERE id = ?";
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, serializeLocation(location));
            stmt.setString(2, serverName);
            stmt.setInt(3, teamId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not set team home for team " + teamId + ": " + e.getMessage());
        }
    }

    @Override
    public Optional<TeamHome> getTeamHome(int teamId) {
        String sql = "SELECT home_location, home_server FROM donut_teams WHERE id = ?";
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, teamId);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                String locStr = rs.getString("home_location");
                String server = rs.getString("home_server");
                Location loc = deserializeLocation(locStr);
                if (loc != null && server != null && !server.isEmpty()) {
                    return Optional.of(new TeamHome(loc, server));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not retrieve team home for team " + teamId + ": " + e.getMessage());
        }
        return Optional.empty();
    }

    @Override
    public void setTeamTag(int teamId, String tag) {
        String sql = "UPDATE donut_teams SET tag = ? WHERE id = ?";
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, tag);
            stmt.setInt(2, teamId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not set team tag for team " + teamId + ": " + e.getMessage());
        }
    }

    @Override
    public void setTeamDescription(int teamId, String description) {
        String sql = "UPDATE donut_teams SET description = ? WHERE id = ?";
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, description);
            stmt.setInt(2, teamId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not set team description for team " + teamId + ": " + e.getMessage());
        }
    }

    @Override
    public void transferOwnership(int teamId, UUID newOwnerUuid, UUID oldOwnerUuid) {
        String updateTeamOwner = "UPDATE donut_teams SET owner_uuid = ? WHERE id = ?";
        String updateNewOwnerRole = "UPDATE donut_team_members SET role = ?, can_withdraw = TRUE, can_use_enderchest = TRUE, can_set_home = TRUE, can_use_home = TRUE WHERE player_uuid = ?";
        String updateOldOwnerRole = "UPDATE donut_team_members SET role = ?, can_withdraw = FALSE, can_use_enderchest = TRUE, can_set_home = FALSE, can_use_home = TRUE WHERE player_uuid = ?";
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement teamStmt = conn.prepareStatement(updateTeamOwner);
                    PreparedStatement newOwnerStmt = conn.prepareStatement(updateNewOwnerRole);
                    PreparedStatement oldOwnerStmt = conn.prepareStatement(updateOldOwnerRole)) {
                teamStmt.setString(1, newOwnerUuid.toString());
                teamStmt.setInt(2, teamId);
                teamStmt.executeUpdate();
                newOwnerStmt.setString(1, TeamRole.OWNER.name());
                newOwnerStmt.setString(2, newOwnerUuid.toString());
                newOwnerStmt.executeUpdate();
                oldOwnerStmt.setString(1, TeamRole.MEMBER.name());
                oldOwnerStmt.setString(2, oldOwnerUuid.toString());
                oldOwnerStmt.executeUpdate();
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not transfer team ownership for team " + teamId + ": " + e.getMessage());
        }
    }

    @Override
    public void setPvpStatus(int teamId, boolean status) {
        String sql = "UPDATE donut_teams SET pvp_enabled = ? WHERE id = ?";
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setBoolean(1, status);
            stmt.setInt(2, teamId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not set pvp status for team " + teamId + ": " + e.getMessage());
        }
    }

    @Override
    public void setTeamGlow(int teamId, boolean enabled) {
        String sql = "UPDATE donut_teams SET glow_enabled = ? WHERE id = ?";
        try (Connection conn = getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setBoolean(1, enabled);
            stmt.setInt(2, teamId);
            stmt.executeUpdate();

            plugin.getTeamManager().markTeamModified(teamId);
        } catch (SQLException e) {
            plugin.getLogger().severe("Error updating team glow status: " + e.getMessage());
        }
    }

    @Override
    public void setTeamColor(int teamId, String colorName) {
        String sql = "UPDATE donut_teams SET color = ? WHERE id = ?";
        try (Connection conn = getConnection();
                PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, colorName);
            stmt.setInt(2, teamId);
            stmt.executeUpdate();

            plugin.getTeamManager().markTeamModified(teamId);
        } catch (SQLException e) {
            plugin.getLogger().severe("Error updating team color: " + e.getMessage());
        }
    }

    @Override
    public void updateTeamBalance(int teamId, double balance) {
        String sql = "UPDATE donut_teams SET balance = ? WHERE id = ?";
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setDouble(1, balance);
            stmt.setInt(2, teamId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not update balance for team " + teamId + ": " + e.getMessage());
        }
    }

    @Override
    public void updateTeamStats(int teamId, int kills, int deaths) {
        String sql = "UPDATE donut_teams SET kills = ?, deaths = ? WHERE id = ?";
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, kills);
            stmt.setInt(2, deaths);
            stmt.setInt(3, teamId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not update stats for team " + teamId + ": " + e.getMessage());
        }
    }

    @Override
    public void saveEnderChest(int teamId, String serializedInventory) {
        String sql;
        if (!isUsingH2()) {
            sql = "INSERT INTO donut_team_enderchest (team_id, inventory_data) VALUES (?, ?) ON DUPLICATE KEY UPDATE inventory_data = VALUES(inventory_data)";
        } else {
            sql = "MERGE INTO donut_team_enderchest (team_id, inventory_data) KEY(team_id) VALUES (?, ?)";
        }

        if (plugin.getConfig().getBoolean("debug", false)) {
            plugin.getLogger()
                    .info("[DEBUG] Saving enderchest to " + storageType.toUpperCase() + " - Team ID: " + teamId
                            + ", Data length: "
                            + (serializedInventory != null ? serializedInventory.length() : "null"));
        }

        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, teamId);
            stmt.setString(2, serializedInventory);
            int rowsAffected = stmt.executeUpdate();

            if (plugin.getConfig().getBoolean("debug", false)) {
                plugin.getLogger().info("[DEBUG] Database save successful - Rows affected: " + rowsAffected);
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not save ender chest for team " + teamId + ": " + e.getMessage());
            e.printStackTrace();
        }
    }

    @Override
    public String getEnderChest(int teamId) {
        String sql = "SELECT inventory_data FROM donut_team_enderchest WHERE team_id = ?";

        if (plugin.getConfig().getBoolean("debug", false)) {
            plugin.getLogger()
                    .info("[DEBUG] Loading enderchest from " + storageType.toUpperCase() + " - Team ID: " + teamId);
        }

        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, teamId);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                String data = rs.getString("inventory_data");
                if (plugin.getConfig().getBoolean("debug", false)) {
                    plugin.getLogger().info("[DEBUG] Database load successful - Data length: "
                            + (data != null ? data.length() : "null"));
                }
                return data;
            } else {
                if (plugin.getConfig().getBoolean("debug", false)) {
                    plugin.getLogger().info("[DEBUG] No enderchest data found in database for team " + teamId);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not load ender chest for team " + teamId + ": " + e.getMessage());
            e.printStackTrace();
        }
        return null;
    }

    @Override
    public void updateMemberPermissions(int teamId, UUID memberUuid, boolean canWithdraw, boolean canUseEnderChest,
            boolean canSetHome, boolean canUseHome) {
        String sql = "UPDATE donut_team_members SET can_withdraw = ?, can_use_enderchest = ?, can_set_home = ?, can_use_home = ? WHERE player_uuid = ? AND team_id = ?";
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setBoolean(1, canWithdraw);
            stmt.setBoolean(2, canUseEnderChest);
            stmt.setBoolean(3, canSetHome);
            stmt.setBoolean(4, canUseHome);
            stmt.setString(5, memberUuid.toString());
            stmt.setInt(6, teamId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not update permissions for member " + memberUuid + ": " + e.getMessage());
        }
    }

    @Override
    public void updateMemberPermission(int teamId, UUID memberUuid, String permission, boolean value)
            throws SQLException {
        String columnName = switch (permission.toLowerCase()) {
            case "withdraw" -> "can_withdraw";
            case "enderchest" -> "can_use_enderchest";
            case "sethome" -> "can_set_home";
            case "usehome" -> "can_use_home";
            default -> throw new IllegalArgumentException("Invalid permission: " + permission);
        };

        String sql = "UPDATE donut_team_members SET " + columnName + " = ? WHERE player_uuid = ? AND team_id = ?";
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setBoolean(1, value);
            stmt.setString(2, memberUuid.toString());
            stmt.setInt(3, teamId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe(
                    "Could not update " + permission + " permission for member " + memberUuid + ": " + e.getMessage());
            throw e;
        }
    }

    @Override
    public void updateMemberRole(int teamId, UUID memberUuid, TeamRole role) {
        String sql;
        if (role == TeamRole.CO_OWNER) {
            sql = "UPDATE donut_team_members SET role = ?, can_withdraw = TRUE, can_use_enderchest = TRUE, can_set_home = TRUE, can_use_home = TRUE WHERE player_uuid = ? AND team_id = ?";
        } else {
            sql = "UPDATE donut_team_members SET role = ?, can_withdraw = FALSE, can_use_enderchest = TRUE, can_set_home = FALSE, can_use_home = TRUE WHERE player_uuid = ? AND team_id = ?";
        }
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, role.name());
            stmt.setString(2, memberUuid.toString());
            stmt.setInt(3, teamId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not update role for member " + memberUuid + ": " + e.getMessage());
        }
    }

    private Map<Integer, Team> getTopTeams(String orderBy, int limit) {
        Map<Integer, Team> topTeams = new LinkedHashMap<>();
        String sql = "SELECT * FROM donut_teams ORDER BY " + orderBy + " DESC LIMIT ?";
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, limit);
            ResultSet rs = stmt.executeQuery();
            int rank = 1;
            while (rs.next()) {
                topTeams.put(rank++, mapTeamFromResultSet(rs));
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not get top teams: " + e.getMessage());
        }
        return topTeams;
    }

    @Override
    public Map<Integer, Team> getTopTeamsByKills(int limit) {
        return getTopTeams("kills", limit);
    }

    @Override
    public Map<Integer, Team> getTopTeamsByBalance(int limit) {
        return getTopTeams("balance", limit);
    }

    @Override
    public Map<Integer, Team> getTopTeamsByMembers(int limit) {
        Map<Integer, Team> topTeams = new LinkedHashMap<>();
        String sql = "SELECT t.*, COUNT(tm.player_uuid) as member_count " +
                "FROM donut_teams t JOIN donut_team_members tm ON t.id = tm.team_id " +
                "GROUP BY t.id ORDER BY member_count DESC LIMIT ?";
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, limit);
            ResultSet rs = stmt.executeQuery();
            int rank = 1;
            while (rs.next()) {
                topTeams.put(rank++, mapTeamFromResultSet(rs));
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not get top teams by members: " + e.getMessage());
        }
        return topTeams;
    }

    private static final String HEARTBEAT_SQL_MYSQL = "INSERT INTO donut_servers (server_name, last_heartbeat) VALUES (?, NOW()) ON DUPLICATE KEY UPDATE last_heartbeat = NOW()";
    private static final String HEARTBEAT_SQL_H2 = "MERGE INTO donut_servers (server_name, last_heartbeat) KEY(server_name) VALUES (?, NOW())";
    private volatile PreparedStatement heartbeatStatement;
    private final Object heartbeatLock = new Object();

    @Override
    public void updateServerHeartbeat(String serverName) {
        if (serverName == null || serverName.trim().isEmpty()) {
            plugin.getLogger().warning("Cannot update heartbeat: server name is null or empty");
            return;
        }

        synchronized (heartbeatLock) {
            try (Connection conn = getConnection()) {
                String sql = !isUsingH2() ? HEARTBEAT_SQL_MYSQL : HEARTBEAT_SQL_H2;
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, serverName);
                    stmt.executeUpdate();
                }
            } catch (SQLException e) {
                plugin.getLogger()
                        .warning("Could not update server heartbeat for " + serverName + ": " + e.getMessage());
            }
        }
    }

    @Override
    public Map<String, Timestamp> getActiveServers() {
        Map<String, Timestamp> activeServers = new HashMap<>();
        String sql = "SELECT server_name, last_heartbeat FROM donut_servers WHERE last_heartbeat > NOW() - INTERVAL 2 MINUTE";
        if (isUsingH2()) {
            sql = "SELECT server_name, last_heartbeat FROM donut_servers WHERE last_heartbeat > DATEADD(MINUTE, -2, NOW())";
        }
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                activeServers.put(rs.getString("server_name"), rs.getTimestamp("last_heartbeat"));
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not retrieve active servers: " + e.getMessage());
        }
        return activeServers;
    }

    @Override
    public void addPendingTeleport(UUID playerUuid, String serverName, Location location) {
        String sql;
        if (!isUsingH2()) {
            sql = "INSERT INTO donut_pending_teleports (player_uuid, destination_server, destination_location) VALUES (?, ?, ?) ON DUPLICATE KEY UPDATE destination_server = VALUES(destination_server), destination_location = VALUES(destination_location)";
        } else {
            sql = "MERGE INTO donut_pending_teleports (player_uuid, destination_server, destination_location) KEY(player_uuid) VALUES (?, ?, ?)";
        }
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setString(1, playerUuid.toString());
            stmt.setString(2, serverName);
            stmt.setString(3, serializeLocation(location));
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not add pending teleport for " + playerUuid + ": " + e.getMessage());
        }
    }

    @Override
    public Optional<Location> getAndRemovePendingTeleport(UUID playerUuid, String currentServer) {
        String selectSql = "SELECT destination_location FROM donut_pending_teleports WHERE player_uuid = ? AND destination_server = ?";
        String deleteSql = "DELETE FROM donut_pending_teleports WHERE player_uuid = ?";
        try (Connection conn = getConnection()) {
            conn.setAutoCommit(false);
            try (PreparedStatement selectStmt = conn.prepareStatement(selectSql)) {
                selectStmt.setString(1, playerUuid.toString());
                selectStmt.setString(2, currentServer);
                ResultSet rs = selectStmt.executeQuery();
                if (rs.next()) {
                    Location loc = deserializeLocation(rs.getString("destination_location"));
                    try (PreparedStatement deleteStmt = conn.prepareStatement(deleteSql)) {
                        deleteStmt.setString(1, playerUuid.toString());
                        deleteStmt.executeUpdate();
                    }
                    conn.commit();
                    return Optional.ofNullable(loc);
                }
            } catch (SQLException e) {
                conn.rollback();
                throw e;
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Error handling pending teleport for " + playerUuid + ": " + e.getMessage());
        }
        return Optional.empty();
    }

    private String serializeLocation(Location loc) {
        if (loc == null || loc.getWorld() == null)
            return null;
        return loc.getWorld().getName() + "," + loc.getX() + "," + loc.getY() + "," + loc.getZ() + "," + loc.getYaw()
                + "," + loc.getPitch();
    }

    private Location deserializeLocation(String s) {
        if (s == null || s.isEmpty())
            return null;
        String[] parts = s.split(",");
        if (parts.length != 6)
            return null;
        World w = Bukkit.getWorld(parts[0]);
        if (w == null)
            return null;
        try {
            double x = Double.parseDouble(parts[1]);
            double y = Double.parseDouble(parts[2]);
            double z = Double.parseDouble(parts[3]);
            float yaw = Float.parseFloat(parts[4]);
            float pitch = Float.parseFloat(parts[5]);
            return new Location(w, x, y, z, yaw, pitch);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Team mapTeamFromResultSet(ResultSet rs) throws SQLException {
        int id = rs.getInt("id");
        String name = rs.getString("name");
        String tag = rs.getString("tag");
        UUID ownerUuid = UUID.fromString(rs.getString("owner_uuid"));
        boolean pvpEnabled = rs.getBoolean("pvp_enabled");
        boolean isPublic = rs.getBoolean("is_public");
        boolean glowEnabled = true;
        try {
            glowEnabled = rs.getBoolean("glow_enabled");
        } catch (SQLException e) {
        }
        String colorName = null;
        try {
            colorName = rs.getString("color");
        } catch (SQLException e) {
        }

        String description = rs.getString("description");
        double balance = rs.getDouble("balance");
        int kills = rs.getInt("kills");
        int deaths = rs.getInt("deaths");
        Timestamp createdAt = rs.getTimestamp("created_at");
        Team team = new Team(id, name, tag, ownerUuid, pvpEnabled, isPublic, glowEnabled, createdAt);
        if (colorName != null) {
            try {
                team.setColor(org.bukkit.ChatColor.valueOf(colorName));
            } catch (IllegalArgumentException e) {
            }
        }
        team.setHomeLocation(deserializeLocation(rs.getString("home_location")));
        team.setHomeServer(rs.getString("home_server"));
        if (description != null) {
            team.setDescription(description);
        }
        team.setBalance(balance);
        team.setKills(kills);
        team.setDeaths(deaths);
        try {
            List<TeamPlayer> members = getTeamMembers(id);
            for (TeamPlayer member : members) {
                team.addMember(member);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load members for team " + id + ": " + e.getMessage());
        }
        return team;
    }

    private TeamPlayer mapPlayerFromResultSet(ResultSet rs) throws SQLException {
        UUID playerUuid = UUID.fromString(rs.getString("player_uuid"));
        TeamRole role = TeamRole.valueOf(rs.getString("role"));
        Instant joinDate = rs.getTimestamp("join_date").toInstant();
        boolean canWithdraw = rs.getBoolean("can_withdraw");
        boolean canUseEnderChest = rs.getBoolean("can_use_enderchest");
        boolean canSetHome = rs.getBoolean("can_set_home");
        boolean canUseHome = rs.getBoolean("can_use_home");
        return new TeamPlayer(playerUuid, role, joinDate, canWithdraw, canUseEnderChest, canSetHome, canUseHome);
    }

    @Override
    public boolean acquireEnderChestLock(int teamId, String serverIdentifier) {
        String insertSql = "INSERT INTO donut_team_locks (team_id, server_identifier, lock_time) VALUES (?, ?, NOW())";
        String updateSql = "UPDATE donut_team_locks SET server_identifier = ?, lock_time = NOW() WHERE team_id = ? AND server_identifier = ?";

        try (Connection conn = getConnection()) {
            try (PreparedStatement stmt = conn.prepareStatement(insertSql)) {
                stmt.setInt(1, teamId);
                stmt.setString(2, serverIdentifier);
                stmt.executeUpdate();
                return true;
            } catch (SQLException e) {
            }

            Optional<TeamEnderChestLock> currentLock = getEnderChestLock(teamId);
            if (currentLock.isPresent()) {
                String currentServer = currentLock.get().serverName();
                Map<String, Timestamp> activeServers = getActiveServers();

                if (currentServer.equals(serverIdentifier)) {
                    try (PreparedStatement stmt = conn.prepareStatement(updateSql)) {
                        stmt.setString(1, serverIdentifier);
                        stmt.setInt(2, teamId);
                        stmt.setString(3, currentServer);
                        stmt.executeUpdate();
                        return true;
                    }
                }

                if (activeServers.containsKey(currentServer)) {
                    plugin.getDebugLogger().log("Ender chest for team " + teamId + " is locked by an active server: "
                            + currentServer);
                    return false;
                }

                plugin.getDebugLogger()
                        .log("Ender chest for team " + teamId + " is locked by an inactive server (" + currentServer
                                + "). Attempting to override lock.");

                try (PreparedStatement stmt = conn.prepareStatement(updateSql)) {
                    stmt.setString(1, serverIdentifier);
                    stmt.setInt(2, teamId);
                    stmt.setString(3, currentServer);
                    int affected = stmt.executeUpdate();
                    return affected > 0;
                }
            } else {
                try (PreparedStatement stmt = conn.prepareStatement(insertSql)) {
                    stmt.setInt(1, teamId);
                    stmt.setString(2, serverIdentifier);
                    stmt.executeUpdate();
                    return true;
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Error acquiring ender chest lock for team " + teamId + ": " + e.getMessage());
            return false;
        }
    }

    @Override
    public void releaseEnderChestLock(int teamId) {
        String sql = "DELETE FROM donut_team_locks WHERE team_id = ?";
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, teamId);
            stmt.executeUpdate();
        } catch (SQLException e) {
            plugin.getLogger().severe("Error releasing ender chest lock for team " + teamId + ": " + e.getMessage());
        }
    }

    @Override
    public Optional<TeamEnderChestLock> getEnderChestLock(int teamId) {
        String sql = "SELECT server_identifier, lock_time FROM donut_team_locks WHERE team_id = ?";
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, teamId);
            ResultSet rs = stmt.executeQuery();
            if (rs.next()) {
                return Optional.of(new TeamEnderChestLock(teamId, rs.getString("server_identifier"),
                        rs.getTimestamp("lock_time")));
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Error checking ender chest lock for team " + teamId + ": " + e.getMessage());
        }
        return Optional.empty();
    }

    public void cleanupOldCrossServerData() {
        if (isConnected()) {
            try (Connection conn = getConnection()) {

                if (tableExists(conn, "donut_cross_server_updates")
                        && tableExists(conn, "donut_cross_server_messages")) {

                    if (columnExists(conn, "donut_cross_server_updates", "created_at") &&
                            columnExists(conn, "donut_cross_server_messages", "created_at")) {
                        if (!isUsingH2()) {
                            conn.createStatement().execute(
                                    "DELETE FROM donut_cross_server_updates WHERE created_at < DATE_SUB(NOW(), INTERVAL 7 DAY)");
                            conn.createStatement().execute(
                                    "DELETE FROM donut_cross_server_messages WHERE created_at < DATE_SUB(NOW(), INTERVAL 7 DAY)");
                        } else {
                            conn.createStatement().execute(
                                    "DELETE FROM donut_cross_server_updates WHERE created_at < DATEADD(DAY, -7, NOW())");
                            conn.createStatement().execute(
                                    "DELETE FROM donut_cross_server_messages WHERE created_at < DATEADD(DAY, -7, NOW())");
                        }
                    } else {
                        if (plugin.getConfigManager().isDebugEnabled()) {
                            plugin.getLogger().info(
                                    "created_at column not found in cross-server tables. Skipping cleanup until migration is complete.");
                        }
                    }
                }
            } catch (SQLException e) {
                if (plugin.getConfigManager().isDebugEnabled()) {
                    plugin.getLogger().warning("Failed to cleanup old cross-server data: " + e.getMessage());
                }
            }
        }
    }

    private boolean tableExists(Connection conn, String tableName) {
        try {
            var md = conn.getMetaData();
            try (var rs = md.getTables(null, null, tableName, null)) {
                return rs.next();
            }
        } catch (SQLException e) {
            return false;
        }
    }

    private boolean columnExists(Connection conn, String tableName, String columnName) {
        try {
            var md = conn.getMetaData();
            try (var rs = md.getColumns(null, null, tableName, columnName)) {
                return rs.next();
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Error checking if column exists: " + e.getMessage());
            return false;
        }
    }

    public Map<String, Object> getDatabaseStats() {
        Map<String, Object> stats = new HashMap<>();
        if (isConnected()) {
            try (Connection conn = getConnection()) {
                stats.put("active_connections", hikari.getHikariPoolMXBean().getActiveConnections());
                stats.put("idle_connections", hikari.getHikariPoolMXBean().getIdleConnections());
                stats.put("total_connections", hikari.getHikariPoolMXBean().getTotalConnections());
                stats.put("threads_awaiting_connection", hikari.getHikariPoolMXBean().getThreadsAwaitingConnection());
            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to get database stats: " + e.getMessage());
            }
        }
        return stats;
    }

    public void optimizeConnectionPool() {
        if (isConnected()) {
            try (Connection conn = getConnection()) {
                conn.createStatement().execute("OPTIMIZE TABLE donut_teams, donut_team_members, donut_team_enderchest");
            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to optimize connection pool: " + e.getMessage());
            }
        }
    }

    @Override
    public List<BlacklistedPlayer> getTeamBlacklist(int teamId) throws SQLException {
        String sql = "SELECT player_uuid, player_name, reason, blacklisted_by_uuid, blacklisted_by_name, blacklisted_at FROM donut_team_blacklist WHERE team_id = ?";
        List<BlacklistedPlayer> blacklist = new ArrayList<>();
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, teamId);
            ResultSet rs = stmt.executeQuery();
            while (rs.next()) {
                blacklist.add(new BlacklistedPlayer(
                        UUID.fromString(rs.getString("player_uuid")),
                        rs.getString("player_name"),
                        rs.getString("reason"),
                        UUID.fromString(rs.getString("blacklisted_by_uuid")),
                        rs.getString("blacklisted_by_name"),
                        rs.getTimestamp("blacklisted_at").toInstant()));
            }
        }
        return blacklist;
    }

    @Override
    public boolean isPlayerBlacklisted(int teamId, UUID playerUuid) throws SQLException {
        String sql = "SELECT 1 FROM donut_team_blacklist WHERE team_id = ? AND player_uuid = ?";
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, teamId);
            stmt.setString(2, playerUuid.toString());
            ResultSet rs = stmt.executeQuery();
            return rs.next();
        }
    }

    @Override
    public boolean removePlayerFromBlacklist(int teamId, UUID playerUuid) throws SQLException {
        String sql = "DELETE FROM donut_team_blacklist WHERE team_id = ? AND player_uuid = ?";
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, teamId);
            stmt.setString(2, playerUuid.toString());
            return stmt.executeUpdate() > 0;
        }
    }

    @Override
    public boolean addPlayerToBlacklist(int teamId, UUID playerUuid, String playerName, String reason,
            UUID blacklistedByUuid, String blacklistedByName) throws SQLException {
        String sql = "INSERT INTO donut_team_blacklist (team_id, player_uuid, player_name, reason, blacklisted_by_uuid, blacklisted_by_name) VALUES (?, ?, ?, ?, ?, ?)";
        try (Connection conn = getConnection(); PreparedStatement stmt = conn.prepareStatement(sql)) {
            stmt.setInt(1, teamId);
            stmt.setString(2, playerUuid.toString());
            stmt.setString(3, playerName);
            stmt.setString(4, reason);
            stmt.setString(5, blacklistedByUuid.toString());
            stmt.setString(6, blacklistedByName);
            return stmt.executeUpdate() > 0;
        }
    }

    @Override
    public void cleanupStaleEnderChestLocks(int hoursOld) {
        if (isConnected()) {
            try (Connection conn = getConnection()) {
                String sql = !isUsingH2()
                        ? "DELETE FROM donut_team_locks WHERE lock_time < DATE_SUB(NOW(), INTERVAL ? HOUR)"
                        : "DELETE FROM donut_team_locks WHERE lock_time < DATEADD(HOUR, -?, NOW())";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setInt(1, hoursOld);
                    int deleted = stmt.executeUpdate();
                    if (deleted > 0) {
                        plugin.getLogger().info("Cleaned up " + deleted + " stale ender chest locks");
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to cleanup stale ender chest locks: " + e.getMessage());
            }
        }
    }

    @Override
    public void cleanupAllEnderChestLocks() {
        if (isConnected()) {
            try (Connection conn = getConnection()) {
                String sql = "DELETE FROM donut_team_locks";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    int deleted = stmt.executeUpdate();
                    if (deleted > 0) {
                        plugin.getLogger().info("Cleaned up all " + deleted + " ender chest locks");
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to cleanup all ender chest locks: " + e.getMessage());
            }
        }
    }

    @Override
    public void removeCrossServerMessage(int messageId) {
        if (isConnected()) {
            try (Connection conn = getConnection()) {
                String sql = "DELETE FROM donut_cross_server_messages WHERE id = ?";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setInt(1, messageId);
                    stmt.executeUpdate();
                }
            } catch (SQLException e) {
                if (plugin.getConfigManager().isDebugEnabled()) {
                    plugin.getLogger().warning("Failed to remove cross server message: " + e.getMessage());
                }
            }
        }
    }

    @Override
    public List<CrossServerMessage> getCrossServerMessages(String serverName) {
        List<CrossServerMessage> messages = new ArrayList<>();
        if (isConnected()) {
            try (Connection conn = getConnection()) {
                boolean hasCreatedAt = columnExists(conn, "donut_cross_server_messages", "created_at");

                String sql;
                if (hasCreatedAt) {
                    sql = "SELECT id, team_id, player_uuid, message, server_name, created_at FROM donut_cross_server_messages WHERE server_name != ? ORDER BY created_at ASC LIMIT 100";
                } else {
                    sql = "SELECT id, team_id, player_uuid, message, server_name FROM donut_cross_server_messages WHERE server_name != ? LIMIT 100";
                }

                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, serverName);
                    ResultSet rs = stmt.executeQuery();
                    while (rs.next()) {
                        Timestamp timestamp = hasCreatedAt ? rs.getTimestamp("created_at")
                                : new Timestamp(System.currentTimeMillis());
                        messages.add(new CrossServerMessage(
                                rs.getInt("id"),
                                rs.getInt("team_id"),
                                rs.getString("player_uuid"),
                                rs.getString("message"),
                                rs.getString("server_name"),
                                timestamp));
                    }
                }
            } catch (SQLException e) {
                if (plugin.getConfigManager().isDebugEnabled()) {
                    plugin.getLogger().warning("Failed to get cross server messages: " + e.getMessage());
                }
            }
        }
        return messages;
    }

    @Override
    public void addCrossServerMessage(int teamId, String playerUuid, String message, String serverName) {
        if (isConnected()) {
            try (Connection conn = getConnection()) {
                String sql = "INSERT INTO donut_cross_server_messages (team_id, player_uuid, message, server_name) VALUES (?, ?, ?, ?)";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setInt(1, teamId);
                    stmt.setString(2, playerUuid);
                    stmt.setString(3, message);
                    stmt.setString(4, serverName);
                    stmt.executeUpdate();
                }
            } catch (SQLException e) {
                if (plugin.getConfigManager().isDebugEnabled()) {
                    plugin.getLogger().warning("Failed to add cross server message: " + e.getMessage());
                }
            }
        }
    }

    @Override
    public void removeCrossServerUpdate(int updateId) {
        if (isConnected()) {
            try (Connection conn = getConnection()) {
                String sql = "DELETE FROM donut_cross_server_updates WHERE id = ?";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setInt(1, updateId);
                    stmt.executeUpdate();
                }
            } catch (SQLException e) {
                if (plugin.getConfigManager().isDebugEnabled()) {
                    plugin.getLogger().warning("Failed to remove cross server update: " + e.getMessage());
                }
            }
        }
    }

    @Override
    public List<CrossServerUpdate> getCrossServerUpdates(String serverName) {
        List<CrossServerUpdate> updates = new ArrayList<>();
        if (isConnected()) {
            try (Connection conn = getConnection()) {
                boolean hasCreatedAt = columnExists(conn, "donut_cross_server_updates", "created_at");

                String sql;
                if (hasCreatedAt) {
                    sql = "SELECT id, team_id, update_type, player_uuid, server_name, created_at FROM donut_cross_server_updates WHERE server_name = ? OR server_name = 'ALL_SERVERS' ORDER BY created_at ASC LIMIT 100";
                } else {
                    sql = "SELECT id, team_id, update_type, player_uuid, server_name FROM donut_cross_server_updates WHERE server_name = ? OR server_name = 'ALL_SERVERS' LIMIT 100";
                }

                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, serverName);
                    ResultSet rs = stmt.executeQuery();
                    while (rs.next()) {
                        Timestamp timestamp = hasCreatedAt ? rs.getTimestamp("created_at")
                                : new Timestamp(System.currentTimeMillis());
                        updates.add(new CrossServerUpdate(
                                rs.getInt("id"),
                                rs.getInt("team_id"),
                                rs.getString("update_type"),
                                rs.getString("player_uuid"),
                                rs.getString("server_name"),
                                timestamp));
                    }
                }
            } catch (SQLException e) {
                if (plugin.getConfigManager().isDebugEnabled()) {
                    plugin.getLogger().warning("Failed to get cross server updates: " + e.getMessage());
                }
            }
        }
        return updates;
    }

    @Override
    public void addCrossServerUpdatesBatch(List<CrossServerUpdate> updates) {
        if (isConnected() && !updates.isEmpty()) {
            try (Connection conn = getConnection()) {
                String sql = "INSERT INTO donut_cross_server_updates (team_id, update_type, player_uuid, server_name) VALUES (?, ?, ?, ?)";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    for (CrossServerUpdate update : updates) {
                        stmt.setInt(1, update.teamId());
                        stmt.setString(2, update.updateType());
                        stmt.setString(3, update.playerUuid());
                        stmt.setString(4, update.serverName());
                        stmt.addBatch();
                    }
                    stmt.executeBatch();
                }
            } catch (SQLException e) {
                if (plugin.getConfigManager().isDebugEnabled()) {
                    plugin.getLogger().warning("Failed to add cross server updates batch: " + e.getMessage());
                }
            }
        }
    }

    @Override
    public void addCrossServerUpdate(int teamId, String updateType, String playerUuid, String serverName) {
        if (isConnected()) {
            try (Connection conn = getConnection()) {
                String sql = "INSERT INTO donut_cross_server_updates (team_id, update_type, player_uuid, server_name) VALUES (?, ?, ?, ?)";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setInt(1, teamId);
                    stmt.setString(2, updateType);
                    stmt.setString(3, playerUuid);
                    stmt.setString(4, serverName);
                    stmt.executeUpdate();
                }
            } catch (SQLException e) {
                if (plugin.getConfigManager().isDebugEnabled()) {
                    plugin.getLogger().warning("Failed to add cross server update: " + e.getMessage());
                }
            }
        }
    }

    @Override
    public List<TeamWarp> getTeamWarps(int teamId) {
        List<TeamWarp> warps = new ArrayList<>();
        if (isConnected()) {
            try (Connection conn = getConnection()) {
                String sql = "SELECT warp_name, location, server_name, password FROM donut_team_warps WHERE team_id = ?";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setInt(1, teamId);
                    ResultSet rs = stmt.executeQuery();
                    while (rs.next()) {
                        warps.add(new TeamWarp(
                                rs.getString("warp_name"),
                                rs.getString("location"),
                                rs.getString("server_name"),
                                rs.getString("password")));
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to get team warps: " + e.getMessage());
            }
        }
        return warps;
    }

    @Override
    public Optional<TeamWarp> getTeamWarp(int teamId, String warpName) {
        if (isConnected()) {
            try (Connection conn = getConnection()) {
                String sql = "SELECT warp_name, location, server_name, password FROM donut_team_warps WHERE team_id = ? AND warp_name = ?";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setInt(1, teamId);
                    stmt.setString(2, warpName);
                    ResultSet rs = stmt.executeQuery();
                    if (rs.next()) {
                        return Optional.of(new TeamWarp(
                                rs.getString("warp_name"),
                                rs.getString("location"),
                                rs.getString("server_name"),
                                rs.getString("password")));
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to get team warp: " + e.getMessage());
            }
        }
        return Optional.empty();
    }

    @Override
    public boolean deleteTeamWarp(int teamId, String warpName) {
        if (isConnected()) {
            try (Connection conn = getConnection()) {
                String sql = "DELETE FROM donut_team_warps WHERE team_id = ? AND warp_name = ?";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setInt(1, teamId);
                    stmt.setString(2, warpName);
                    return stmt.executeUpdate() > 0;
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to delete team warp: " + e.getMessage());
            }
        }
        return false;
    }

    @Override
    public boolean setTeamWarp(int teamId, String warpName, String locationString, String serverName, String password) {
        if (isConnected()) {
            try (Connection conn = getConnection()) {
                String sql;
                if (!isUsingH2()) {
                    sql = "INSERT INTO donut_team_warps (team_id, warp_name, location, server_name, password) VALUES (?, ?, ?, ?, ?) ON DUPLICATE KEY UPDATE location = VALUES(location), server_name = VALUES(server_name), password = VALUES(password)";
                } else {
                    sql = "MERGE INTO donut_team_warps (team_id, warp_name, location, server_name, password) KEY(team_id, warp_name) VALUES (?, ?, ?, ?, ?)";
                }
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setInt(1, teamId);
                    stmt.setString(2, warpName);
                    stmt.setString(3, locationString);
                    stmt.setString(4, serverName);
                    stmt.setString(5, password);
                    return stmt.executeUpdate() > 0;
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to set team warp: " + e.getMessage());
            }
        }
        return false;
    }

    @Override
    public boolean teamWarpExists(int teamId, String warpName) {
        if (isConnected()) {
            try (Connection conn = getConnection()) {
                String sql = "SELECT 1 FROM donut_team_warps WHERE team_id = ? AND warp_name = ?";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setInt(1, teamId);
                    stmt.setString(2, warpName);
                    ResultSet rs = stmt.executeQuery();
                    return rs.next();
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to check team warp existence: " + e.getMessage());
            }
        }
        return false;
    }

    @Override
    public int getTeamWarpCount(int teamId) {
        if (isConnected()) {
            try (Connection conn = getConnection()) {
                String sql = "SELECT COUNT(*) FROM donut_team_warps WHERE team_id = ?";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setInt(1, teamId);
                    ResultSet rs = stmt.executeQuery();
                    if (rs.next()) {
                        return rs.getInt(1);
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to get team warp count: " + e.getMessage());
            }
        }
        return 0;
    }

    @Override
    public List<TeamWarp> getWarps(int teamId) {
        return getTeamWarps(teamId);
    }

    @Override
    public Optional<TeamWarp> getWarp(int teamId, String warpName) {
        return getTeamWarp(teamId, warpName);
    }

    @Override
    public void deleteWarp(int teamId, String warpName) {
        deleteTeamWarp(teamId, warpName);
    }

    @Override
    public void setWarp(int teamId, String warpName, Location location, String serverName, String password) {
        String locationString = location.getWorld().getName() + ":" + location.getX() + ":" + location.getY() + ":"
                + location.getZ() + ":" + location.getYaw() + ":" + location.getPitch();
        setTeamWarp(teamId, warpName, locationString, serverName, password);
    }

    @Override
    public void clearAllJoinRequests(UUID playerUuid) {
        if (isConnected()) {
            try (Connection conn = getConnection()) {
                String sql = "DELETE FROM donut_join_requests WHERE player_uuid = ?";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setString(1, playerUuid.toString());
                    stmt.executeUpdate();
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to clear all join requests: " + e.getMessage());
            }
        }
    }

    @Override
    public boolean hasJoinRequest(int teamId, UUID playerUuid) {
        if (isConnected()) {
            try (Connection conn = getConnection()) {
                String sql = "SELECT 1 FROM donut_join_requests WHERE team_id = ? AND player_uuid = ?";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setInt(1, teamId);
                    stmt.setString(2, playerUuid.toString());
                    ResultSet rs = stmt.executeQuery();
                    return rs.next();
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to check join request: " + e.getMessage());
            }
        }
        return false;
    }

    @Override
    public List<UUID> getJoinRequests(int teamId) {
        List<UUID> requests = new ArrayList<>();
        if (isConnected()) {
            try (Connection conn = getConnection()) {
                String sql = "SELECT player_uuid FROM donut_join_requests WHERE team_id = ?";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setInt(1, teamId);
                    ResultSet rs = stmt.executeQuery();
                    while (rs.next()) {
                        requests.add(UUID.fromString(rs.getString("player_uuid")));
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to get join requests: " + e.getMessage());
            }
        }
        return requests;
    }

    @Override
    public void removeJoinRequest(int teamId, UUID playerUuid) {
        if (isConnected()) {
            try (Connection conn = getConnection()) {
                String sql = "DELETE FROM donut_join_requests WHERE team_id = ? AND player_uuid = ?";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setInt(1, teamId);
                    stmt.setString(2, playerUuid.toString());
                    stmt.executeUpdate();
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to remove join request: " + e.getMessage());
            }
        }
    }

    @Override
    public void addJoinRequest(int teamId, UUID playerUuid) {
        if (isConnected()) {
            try (Connection conn = getConnection()) {
                String sql = "INSERT INTO donut_join_requests (team_id, player_uuid) VALUES (?, ?)";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setInt(1, teamId);
                    stmt.setString(2, playerUuid.toString());
                    stmt.executeUpdate();
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to add join request: " + e.getMessage());
            }
        }
    }

    @Override
    public void updateMemberEditingPermissions(int teamId, UUID memberUuid, boolean canEditMembers,
            boolean canEditCoOwners, boolean canKickMembers, boolean canPromoteMembers, boolean canDemoteMembers) {
        if (isConnected()) {
            try (Connection conn = getConnection()) {
                String sql = "UPDATE donut_team_members SET can_edit_members = ?, can_edit_co_owners = ?, can_kick_members = ?, can_promote_members = ?, can_demote_members = ? WHERE team_id = ? AND player_uuid = ?";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setBoolean(1, canEditMembers);
                    stmt.setBoolean(2, canEditCoOwners);
                    stmt.setBoolean(3, canKickMembers);
                    stmt.setBoolean(4, canPromoteMembers);
                    stmt.setBoolean(5, canDemoteMembers);
                    stmt.setInt(6, teamId);
                    stmt.setString(7, memberUuid.toString());
                    stmt.executeUpdate();
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to update member editing permissions: " + e.getMessage());
            }
        }
    }

    @Override
    public void setPublicStatus(int teamId, boolean isPublic) {
        if (isConnected()) {
            try (Connection conn = getConnection()) {
                String sql = "UPDATE donut_teams SET is_public = ? WHERE id = ?";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setBoolean(1, isPublic);
                    stmt.setInt(2, teamId);
                    stmt.executeUpdate();
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to set public status: " + e.getMessage());
            }
        }
    }

    @Override
    public void deleteTeamHome(int teamId) {
        if (isConnected()) {
            try (Connection conn = getConnection()) {
                String sql = "UPDATE donut_teams SET home_location = NULL, home_server = NULL WHERE id = ?";
                try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                    stmt.setInt(1, teamId);
                    stmt.executeUpdate();
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to delete team home: " + e.getMessage());
            }
        }
    }

    @Override
    public void cleanup() {
        if (isConnected()) {
            try (Connection conn = getConnection()) {

                if (tableExists(conn, "donut_cross_server_updates")
                        && tableExists(conn, "donut_cross_server_messages")) {

                    if (columnExists(conn, "donut_cross_server_updates", "created_at") &&
                            columnExists(conn, "donut_cross_server_messages", "created_at")) {
                        if (!isUsingH2()) {
                            conn.createStatement().execute(
                                    "DELETE FROM donut_cross_server_updates WHERE created_at < DATE_SUB(NOW(), INTERVAL 7 DAY)");
                            conn.createStatement().execute(
                                    "DELETE FROM donut_cross_server_messages WHERE created_at < DATE_SUB(NOW(), INTERVAL 7 DAY)");
                        } else {
                            conn.createStatement().execute(
                                    "DELETE FROM donut_cross_server_updates WHERE created_at < DATEADD(DAY, -7, NOW())");
                            conn.createStatement().execute(
                                    "DELETE FROM donut_cross_server_messages WHERE created_at < DATEADD(DAY, -7, NOW())");
                        }
                    } else {
                        plugin.getLogger().info(
                                "created_at column not found in cross-server tables. Skipping cleanup until migration is complete.");
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().warning("Failed to cleanup old data: " + e.getMessage());
            }
        }
    }

    public void optimizeDatabase() {
        if (!isConnected()) {
            plugin.getLogger().warning("Cannot optimize database - not connected");
            return;
        }

        try (Connection conn = getConnection()) {
            if (!isUsingH2()) {
                conn.createStatement().execute(
                        "OPTIMIZE TABLE donut_teams, donut_team_members, donut_team_invites, donut_team_blacklist, donut_team_settings, donut_team_warps, donut_team_enderchest_locks, donut_servers, donut_cross_server_updates, donut_cross_server_messages, donut_player_cache");
                plugin.getLogger().info("MySQL database optimization completed");
            } else {
                conn.createStatement().execute("ANALYZE");
                plugin.getLogger().info("H2 database analysis completed");
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to optimize database: " + e.getMessage());
        }
    }

    @Override
    public Optional<UUID> getPlayerUuidByName(String playerName) {
        if (!isConnected() || playerName == null || playerName.isEmpty()) {
            return Optional.empty();
        }

        try (Connection conn = getConnection()) {
            String sql = "SELECT player_uuid FROM donut_player_cache WHERE LOWER(player_name) = LOWER(?) ORDER BY last_seen DESC LIMIT 1";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, playerName);
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    return Optional.of(UUID.fromString(rs.getString("player_uuid")));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to get player UUID by name: " + e.getMessage());
        }
        return Optional.empty();
    }

    @Override
    public void cachePlayerName(UUID playerUuid, String playerName) {
        if (!isConnected() || playerUuid == null || playerName == null || playerName.isEmpty()) {
            return;
        }

        try (Connection conn = getConnection()) {
            String sql;
            if (!isUsingH2()) {
                sql = "INSERT INTO donut_player_cache (player_uuid, player_name, last_seen) VALUES (?, ?, NOW()) " +
                        "ON DUPLICATE KEY UPDATE player_name = VALUES(player_name), last_seen = NOW()";
            } else {
                sql = "MERGE INTO donut_player_cache (player_uuid, player_name, last_seen) " +
                        "KEY(player_uuid) VALUES (?, ?, NOW())";
            }
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, playerUuid.toString());
                stmt.setString(2, playerName);
                stmt.executeUpdate();
            }
        } catch (SQLException e) {
            if (plugin.getConfigManager().isDebugEnabled()) {
                plugin.getLogger().warning("Failed to cache player name: " + e.getMessage());
            }
        }
    }

    @Override
    public Optional<String> getPlayerNameByUuid(UUID playerUuid) {
        if (!isConnected() || playerUuid == null) {
            return Optional.empty();
        }

        try (Connection conn = getConnection()) {
            String sql = "SELECT player_name FROM donut_player_cache WHERE player_uuid = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, playerUuid.toString());
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    return Optional.of(rs.getString("player_name"));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to get player name by UUID: " + e.getMessage());
        }
        return Optional.empty();
    }

    @Override
    public void addTeamInvite(int teamId, UUID playerUuid, UUID inviterUuid) {
        if (!isConnected() || playerUuid == null || inviterUuid == null) {
            return;
        }

        try (Connection conn = getConnection()) {
            String sql;
            if (!isUsingH2()) {
                sql = "INSERT INTO donut_team_invites (team_id, player_uuid, inviter_uuid) VALUES (?, ?, ?) " +
                        "ON DUPLICATE KEY UPDATE inviter_uuid = VALUES(inviter_uuid), created_at = CURRENT_TIMESTAMP";
            } else {
                sql = "MERGE INTO donut_team_invites (team_id, player_uuid, inviter_uuid) " +
                        "KEY(team_id, player_uuid) VALUES (?, ?, ?)";
            }
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, teamId);
                stmt.setString(2, playerUuid.toString());
                stmt.setString(3, inviterUuid.toString());
                stmt.executeUpdate();
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to add team invite: " + e.getMessage());
        }
    }

    @Override
    public void removeTeamInvite(int teamId, UUID playerUuid) {
        if (!isConnected() || playerUuid == null) {
            return;
        }

        try (Connection conn = getConnection()) {
            String sql = "DELETE FROM donut_team_invites WHERE team_id = ? AND player_uuid = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, teamId);
                stmt.setString(2, playerUuid.toString());
                stmt.executeUpdate();
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to remove team invite: " + e.getMessage());
        }
    }

    @Override
    public boolean hasTeamInvite(int teamId, UUID playerUuid) {
        if (!isConnected() || playerUuid == null) {
            return false;
        }

        try (Connection conn = getConnection()) {
            String sql = "SELECT 1 FROM donut_team_invites WHERE team_id = ? AND player_uuid = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, teamId);
                stmt.setString(2, playerUuid.toString());
                ResultSet rs = stmt.executeQuery();
                return rs.next();
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to check team invite: " + e.getMessage());
        }
        return false;
    }

    @Override
    public List<Integer> getPlayerInvites(UUID playerUuid) {
        List<Integer> teamIds = new ArrayList<>();
        if (!isConnected() || playerUuid == null) {
            return teamIds;
        }

        try (Connection conn = getConnection()) {
            String sql = "SELECT team_id FROM donut_team_invites WHERE player_uuid = ? ORDER BY created_at DESC";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, playerUuid.toString());
                ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    teamIds.add(rs.getInt("team_id"));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to get player invites: " + e.getMessage());
        }
        return teamIds;
    }

    @Override
    public void clearPlayerInvites(UUID playerUuid) {
        if (!isConnected() || playerUuid == null) {
            return;
        }

        try (Connection conn = getConnection()) {
            String sql = "DELETE FROM donut_team_invites WHERE player_uuid = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, playerUuid.toString());
                stmt.executeUpdate();
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to clear player invites: " + e.getMessage());
        }
    }

    @Override
    public List<TeamInvite> getPlayerInvitesWithDetails(UUID playerUuid) {
        List<TeamInvite> invites = new ArrayList<>();
        if (!isConnected() || playerUuid == null) {
            return invites;
        }

        try (Connection conn = getConnection()) {
            String sql = "SELECT i.team_id, t.name as team_name, i.inviter_uuid, i.created_at " +
                    "FROM donut_team_invites i " +
                    "JOIN donut_teams t ON i.team_id = t.id " +
                    "WHERE i.player_uuid = ? " +
                    "ORDER BY i.created_at DESC";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, playerUuid.toString());
                ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    int teamId = rs.getInt("team_id");
                    String teamName = rs.getString("team_name");
                    UUID inviterUuid = UUID.fromString(rs.getString("inviter_uuid"));
                    Timestamp createdAt = rs.getTimestamp("created_at");

                    String inviterName = getPlayerNameByUuid(inviterUuid).orElse("Unknown");

                    invites.add(new TeamInvite(teamId, teamName, inviterUuid, inviterName, createdAt));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to get player invites with details: " + e.getMessage());
        }
        return invites;
    }

    @Override
    public void updatePlayerSession(UUID playerUuid, String serverName) {
        if (!isConnected() || playerUuid == null || serverName == null) {
            return;
        }

        try (Connection conn = getConnection()) {
            String sql;
            if (!isUsingH2()) {
                sql = "INSERT INTO donut_player_sessions (player_uuid, server_name, last_seen) " +
                        "VALUES (?, ?, CURRENT_TIMESTAMP) " +
                        "ON DUPLICATE KEY UPDATE server_name = VALUES(server_name), last_seen = CURRENT_TIMESTAMP";
            } else {
                sql = "MERGE INTO donut_player_sessions (player_uuid, server_name, last_seen) " +
                        "KEY(player_uuid) VALUES (?, ?, CURRENT_TIMESTAMP)";
            }

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, playerUuid.toString());
                stmt.setString(2, serverName);
                stmt.executeUpdate();
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to update player session: " + e.getMessage());
        }
    }

    @Override
    public Optional<PlayerSession> getPlayerSession(UUID playerUuid) {
        if (!isConnected() || playerUuid == null) {
            return Optional.empty();
        }

        try (Connection conn = getConnection()) {
            String sql = "SELECT player_uuid, server_name, last_seen FROM donut_player_sessions WHERE player_uuid = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, playerUuid.toString());
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    return Optional.of(new PlayerSession(
                            UUID.fromString(rs.getString("player_uuid")),
                            rs.getString("server_name"),
                            rs.getTimestamp("last_seen")));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to get player session: " + e.getMessage());
        }
        return Optional.empty();
    }

    @Override
    public Map<UUID, PlayerSession> getTeamPlayerSessions(int teamId) {
        Map<UUID, PlayerSession> sessions = new HashMap<>();
        if (!isConnected()) {
            return sessions;
        }

        try (Connection conn = getConnection()) {
            String sql = "SELECT s.player_uuid, s.server_name, s.last_seen " +
                    "FROM donut_player_sessions s " +
                    "JOIN donut_team_members m ON s.player_uuid = m.player_uuid " +
                    "WHERE m.team_id = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, teamId);
                ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    UUID playerUuid = UUID.fromString(rs.getString("player_uuid"));
                    PlayerSession session = new PlayerSession(
                            playerUuid,
                            rs.getString("server_name"),
                            rs.getTimestamp("last_seen"));
                    sessions.put(playerUuid, session);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to get team player sessions: " + e.getMessage());
        }
        return sessions;
    }

    @Override
    public void cleanupStaleSessions(int minutesOld) {
        if (!isConnected()) {
            return;
        }

        try (Connection conn = getConnection()) {
            String sql;
            if (!isUsingH2()) {
                sql = "DELETE FROM donut_player_sessions WHERE last_seen < DATE_SUB(NOW(), INTERVAL ? MINUTE)";
            } else {
                sql = "DELETE FROM donut_player_sessions WHERE last_seen < DATEADD('MINUTE', ?, CURRENT_TIMESTAMP)";
            }

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, -minutesOld);
                int deleted = stmt.executeUpdate();
                if (deleted > 0) {
                    plugin.getLogger().info("Cleaned up " + deleted + " stale player sessions");
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to cleanup stale sessions: " + e.getMessage());
        }
    }

    @Override
    public void setServerAlias(String serverName, String alias) {
        if (!isConnected() || serverName == null || alias == null) {
            return;
        }

        try (Connection conn = getConnection()) {
            String sql = "INSERT INTO donut_server_aliases (server_name, alias, updated_at) VALUES (?, ?, CURRENT_TIMESTAMP) "
                    +
                    "ON DUPLICATE KEY UPDATE alias = ?, updated_at = CURRENT_TIMESTAMP";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, serverName);
                stmt.setString(2, alias);
                stmt.setString(3, alias);
                stmt.executeUpdate();
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to set server alias: " + e.getMessage());
        }
    }

    @Override
    public Optional<String> getServerAlias(String serverName) {
        if (!isConnected() || serverName == null) {
            return Optional.empty();
        }

        try (Connection conn = getConnection()) {
            String sql = "SELECT alias FROM donut_server_aliases WHERE server_name = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, serverName);
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    return Optional.of(rs.getString("alias"));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to get server alias: " + e.getMessage());
        }
        return Optional.empty();
    }

    @Override
    public Map<String, String> getAllServerAliases() {
        Map<String, String> aliases = new HashMap<>();
        if (!isConnected()) {
            return aliases;
        }

        try (Connection conn = getConnection()) {
            String sql = "SELECT server_name, alias FROM donut_server_aliases";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    aliases.put(rs.getString("server_name"), rs.getString("alias"));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to get all server aliases: " + e.getMessage());
        }
        return aliases;
    }

    @Override
    public void removeServerAlias(String serverName) {
        if (!isConnected() || serverName == null) {
            return;
        }

        try (Connection conn = getConnection()) {
            String sql = "DELETE FROM donut_server_aliases WHERE server_name = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, serverName);
                stmt.executeUpdate();
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to remove server alias: " + e.getMessage());
        }
    }

    @Override
    public void setTeamRenameTimestamp(int teamId, Timestamp timestamp) {
        if (!isConnected()) {
            return;
        }

        try (Connection conn = getConnection()) {
            String sql = "INSERT INTO donut_team_rename_cooldowns (team_id, last_rename) VALUES (?, ?) " +
                    "ON DUPLICATE KEY UPDATE last_rename = ?";

            if (storageType.equals("h2")) {
                sql = "MERGE INTO donut_team_rename_cooldowns (team_id, last_rename) KEY(team_id) VALUES (?, ?)";
            }

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, teamId);
                stmt.setTimestamp(2, timestamp);
                if (!storageType.equals("h2")) {
                    stmt.setTimestamp(3, timestamp);
                }
                stmt.executeUpdate();
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to set team rename timestamp: " + e.getMessage());
        }
    }

    @Override
    public Optional<Timestamp> getTeamRenameTimestamp(int teamId) {
        if (!isConnected()) {
            return Optional.empty();
        }

        try (Connection conn = getConnection()) {
            String sql = "SELECT last_rename FROM donut_team_rename_cooldowns WHERE team_id = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, teamId);
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    return Optional.of(rs.getTimestamp("last_rename"));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to get team rename timestamp: " + e.getMessage());
        }
        return Optional.empty();
    }

    @Override
    public void setTeamName(int teamId, String newName) {
        if (!isConnected() || newName == null || newName.isEmpty()) {
            return;
        }

        try (Connection conn = getConnection()) {
            String sql = "UPDATE donut_teams SET name = ? WHERE id = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setString(1, newName);
                stmt.setInt(2, teamId);
                int rowsAffected = stmt.executeUpdate();
                if (rowsAffected > 0) {
                    plugin.getLogger().info("Team ID " + teamId + " renamed to: " + newName);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to set team name: " + e.getMessage());
        }
    }


    @Override
    public boolean setTeamCustomData(int teamId, String key, String value) {
        if (!isConnected() || key == null || key.isEmpty()) {
            return false;
        }

        try (Connection conn = getConnection()) {
            String sql = isUsingH2()
                    ? "MERGE INTO `donut_team_custom_data` (`team_id`, `data_key`, `data_value`, `updated_at`) KEY(`team_id`, `data_key`) VALUES (?, ?, ?, CURRENT_TIMESTAMP)"
                    : "INSERT INTO `donut_team_custom_data` (`team_id`, `data_key`, `data_value`) VALUES (?, ?, ?) " +
                            "ON DUPLICATE KEY UPDATE `data_value` = VALUES(`data_value`), `updated_at` = CURRENT_TIMESTAMP";

            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, teamId);
                stmt.setString(2, key);
                stmt.setString(3, value);
                stmt.executeUpdate();
                return true;
            }
        } catch (SQLException e) {
            plugin.getLogger().warning(
                    "Failed to set custom data for team " + teamId + " with key '" + key + "': " + e.getMessage());
            return false;
        }
    }

    @Override
    public Optional<String> getTeamCustomData(int teamId, String key) {
        if (!isConnected() || key == null || key.isEmpty()) {
            return Optional.empty();
        }

        try (Connection conn = getConnection()) {
            String sql = "SELECT `data_value` FROM `donut_team_custom_data` WHERE `team_id` = ? AND `data_key` = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, teamId);
                stmt.setString(2, key);
                ResultSet rs = stmt.executeQuery();
                if (rs.next()) {
                    return Optional.of(rs.getString("data_value"));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning(
                    "Failed to get custom data for team " + teamId + " with key '" + key + "': " + e.getMessage());
        }
        return Optional.empty();
    }

    @Override
    public boolean removeTeamCustomData(int teamId, String key) {
        if (!isConnected() || key == null || key.isEmpty()) {
            return false;
        }

        try (Connection conn = getConnection()) {
            String sql = "DELETE FROM `donut_team_custom_data` WHERE `team_id` = ? AND `data_key` = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, teamId);
                stmt.setString(2, key);
                int rowsAffected = stmt.executeUpdate();
                return rowsAffected > 0;
            }
        } catch (SQLException e) {
            plugin.getLogger().warning(
                    "Failed to remove custom data for team " + teamId + " with key '" + key + "': " + e.getMessage());
            return false;
        }
    }

    @Override
    public Map<String, String> getAllTeamCustomData(int teamId) {
        Map<String, String> customData = new HashMap<>();
        if (!isConnected()) {
            return customData;
        }

        try (Connection conn = getConnection()) {
            String sql = "SELECT `data_key`, `data_value` FROM `donut_team_custom_data` WHERE `team_id` = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, teamId);
                ResultSet rs = stmt.executeQuery();
                while (rs.next()) {
                    customData.put(rs.getString("data_key"), rs.getString("data_value"));
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to get all custom data for team " + teamId + ": " + e.getMessage());
        }
        return customData;
    }

    @Override
    public boolean hasTeamCustomData(int teamId, String key) {
        if (!isConnected() || key == null || key.isEmpty()) {
            return false;
        }

        try (Connection conn = getConnection()) {
            String sql = "SELECT 1 FROM `donut_team_custom_data` WHERE `team_id` = ? AND `data_key` = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, teamId);
                stmt.setString(2, key);
                ResultSet rs = stmt.executeQuery();
                return rs.next();
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to check custom data existence for team " + teamId + " with key '" + key
                    + "': " + e.getMessage());
            return false;
        }
    }

    @Override
    public int clearAllTeamCustomData(int teamId) {
        if (!isConnected()) {
            return 0;
        }

        try (Connection conn = getConnection()) {
            String sql = "DELETE FROM `donut_team_custom_data` WHERE `team_id` = ?";
            try (PreparedStatement stmt = conn.prepareStatement(sql)) {
                stmt.setInt(1, teamId);
                return stmt.executeUpdate();
            }
        } catch (SQLException e) {
            plugin.getLogger().warning("Failed to clear all custom data for team " + teamId + ": " + e.getMessage());
            return 0;
        }
    }
}
