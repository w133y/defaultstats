package com.w1z4r_d.defaultstats;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Stores every player's statistics as ONE row with ONE column per stat
 * (a "wide" table), instead of the EAV (key-value rows) approach.
 * New columns are created automatically the first time a new stat is seen.
 */
public class Database {

    private final DefaultStats plugin;
    private Connection connection;
    private String type;
    private String tablePrefix;
    private String tableName;

    // Cache of columns we know exist, so we don't query/ALTER on every save.
    private final Set<String> knownColumns = new HashSet<>();

    public Database(DefaultStats plugin) {
        this.plugin = plugin;
    }

    public void connect() throws SQLException {
        this.type = plugin.getConfig().getString("database.type", "SQLITE").toUpperCase();
        this.tablePrefix = plugin.getConfig().getString("database.table-prefix", "defstats_");
        this.tableName = tablePrefix + "player_stats";

        if (type.equals("MYSQL")) {
            String host = plugin.getConfig().getString("database.mysql.host", "localhost");
            int port = plugin.getConfig().getInt("database.mysql.port", 3306);
            String db = plugin.getConfig().getString("database.mysql.database", "minecraft");
            String user = plugin.getConfig().getString("database.mysql.username", "root");
            String pass = plugin.getConfig().getString("database.mysql.password", "");
            boolean useSSL = plugin.getConfig().getBoolean("database.mysql.useSSL", false);

            String url = "jdbc:mysql://" + host + ":" + port + "/" + db
                    + "?useSSL=" + useSSL + "&autoReconnect=true&characterEncoding=utf8";
            connection = DriverManager.getConnection(url, user, pass);
        } else {
            plugin.getDataFolder().mkdirs();
            String fileName = plugin.getConfig().getString("database.sqlite.file", "stats.db");
            File file = new File(plugin.getDataFolder(), fileName);
            connection = DriverManager.getConnection("jdbc:sqlite:" + file.getAbsolutePath());
        }
    }

    public void createTables() throws SQLException {
        String sql;
        if (type.equals("MYSQL")) {
            sql = "CREATE TABLE IF NOT EXISTS " + tableName + " (" +
                    "uuid VARCHAR(36) NOT NULL PRIMARY KEY, " +
                    "player_name VARCHAR(16) NOT NULL, " +
                    "updated_at BIGINT NOT NULL" +
                    ")";
        } else {
            sql = "CREATE TABLE IF NOT EXISTS " + tableName + " (" +
                    "uuid TEXT NOT NULL PRIMARY KEY, " +
                    "player_name TEXT NOT NULL, " +
                    "updated_at INTEGER NOT NULL" +
                    ")";
        }
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
        loadExistingColumns();
    }

    private void loadExistingColumns() throws SQLException {
        knownColumns.clear();
        knownColumns.add("uuid");
        knownColumns.add("player_name");
        knownColumns.add("updated_at");

        if (type.equals("MYSQL")) {
            String sql = "SELECT COLUMN_NAME FROM INFORMATION_SCHEMA.COLUMNS " +
                    "WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = ?";
            try (PreparedStatement statement = connection.prepareStatement(sql)) {
                statement.setString(1, tableName);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        knownColumns.add(rs.getString(1).toLowerCase());
                    }
                }
            }
        } else {
            try (var statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("PRAGMA table_info(" + tableName + ")")) {
                while (rs.next()) {
                    knownColumns.add(rs.getString("name").toLowerCase());
                }
            }
        }
    }

    /**
     * Converts a raw stat key (e.g. "mine_block:diamond_ore") into a safe
     * SQL column name (e.g. "mine_block_diamond_ore"), truncated if needed
     * to stay under MySQL's 64-character identifier limit.
     */
    private String toColumnName(String statKey) {
        String name = statKey.toLowerCase().replaceAll("[^a-z0-9_]", "_");
        if (name.length() > 60) {
            int hash = Math.abs(name.hashCode());
            name = name.substring(0, 50) + "_" + hash;
        }
        return name;
    }

    /**
     * Makes sure every stat in the map has a matching column, creating any
     * missing ones on the fly (BIGINT/INTEGER, default 0).
     */
    private synchronized void ensureColumns(Set<String> columnNames) throws SQLException {
        String columnType = type.equals("MYSQL") ? "BIGINT" : "INTEGER";
        for (String column : columnNames) {
            if (knownColumns.contains(column)) continue;
            String alter = "ALTER TABLE " + tableName + " ADD COLUMN `" + column + "` " + columnType + " DEFAULT 0";
            try (var statement = connection.createStatement()) {
                statement.execute(alter);
                knownColumns.add(column);
            } catch (SQLException e) {
                String msg = e.getMessage() == null ? "" : e.getMessage().toLowerCase();
                // Column might already exist (race with another save) - ignore that case.
                if (!msg.contains("duplicate") && !msg.contains("exist")) {
                    throw e;
                }
                knownColumns.add(column);
            }
        }
    }

    /**
     * Writes (upserts) a single row for the player with one column per stat.
     */
    public void saveStats(java.util.UUID uuid, String playerName, Map<String, Long> stats) throws SQLException {
        // Build column-name -> value map first, so we only touch the DB once per save.
        Map<String, Long> columns = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, Long> entry : stats.entrySet()) {
            columns.put(toColumnName(entry.getKey()), entry.getValue());
        }

        ensureColumns(columns.keySet());

        StringBuilder colNames = new StringBuilder("uuid, player_name, updated_at");
        StringBuilder placeholders = new StringBuilder("?, ?, ?");
        StringBuilder updateClause = new StringBuilder("player_name = VALUES(player_name), updated_at = VALUES(updated_at)");
        StringBuilder sqliteUpdateClause = new StringBuilder("player_name = excluded.player_name, updated_at = excluded.updated_at");

        for (String column : columns.keySet()) {
            colNames.append(", `").append(column).append("`");
            placeholders.append(", ?");
            updateClause.append(", `").append(column).append("` = VALUES(`").append(column).append("`)");
            sqliteUpdateClause.append(", `").append(column).append("` = excluded.`").append(column).append("`");
        }

        String sql;
        if (type.equals("MYSQL")) {
            sql = "INSERT INTO " + tableName + " (" + colNames + ") VALUES (" + placeholders + ") " +
                    "ON DUPLICATE KEY UPDATE " + updateClause;
        } else {
            sql = "INSERT INTO " + tableName + " (" + colNames + ") VALUES (" + placeholders + ") " +
                    "ON CONFLICT(uuid) DO UPDATE SET " + sqliteUpdateClause;
        }

        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            int i = 1;
            statement.setString(i++, uuid.toString());
            statement.setString(i++, playerName);
            statement.setLong(i++, System.currentTimeMillis());
            for (Long value : columns.values()) {
                statement.setLong(i++, value);
            }
            statement.executeUpdate();
        }
    }

    public void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException e) {
                plugin.getLogger().warning("Error closing database connection: " + e.getMessage());
            }
        }
    }
}
