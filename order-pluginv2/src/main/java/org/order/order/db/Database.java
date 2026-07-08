package org.order.order.db;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.configuration.file.FileConfiguration;
import org.order.order.Order;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Wraps a HikariCP connection pool and creates the schema on startup.
 * Supports SQLite (file based, zero setup) and MySQL (config based).
 */
public class Database {

    public enum Type {
        SQLITE, MYSQL
    }

    private final Order plugin;
    private final Type type;
    private HikariDataSource dataSource;

    public Database(Order plugin) {
        this.plugin = plugin;
        FileConfiguration cfg = plugin.getConfig();
        String configuredType = cfg.getString("database.type", "sqlite");
        this.type = "mysql".equalsIgnoreCase(configuredType) ? Type.MYSQL : Type.SQLITE;
    }

    public void connect() throws SQLException {
        HikariConfig hikariConfig = new HikariConfig();

        if (type == Type.MYSQL) {
            FileConfiguration cfg = plugin.getConfig();
            String host = cfg.getString("database.mysql.host", "localhost");
            int port = cfg.getInt("database.mysql.port", 3306);
            String database = cfg.getString("database.mysql.database", "order_plugin");
            String username = cfg.getString("database.mysql.username", "root");
            String password = cfg.getString("database.mysql.password", "");
            boolean useSSL = cfg.getBoolean("database.mysql.useSSL", false);
            int poolSize = Math.max(2, cfg.getInt("database.mysql.pool-size", 8));

            String jdbcUrl = "jdbc:mysql://" + host + ":" + port + "/" + database
                    + "?useSSL=" + useSSL
                    + "&autoReconnect=true"
                    + "&characterEncoding=utf8"
                    + "&useUnicode=true";

            hikariConfig.setJdbcUrl(jdbcUrl);
            hikariConfig.setUsername(username);
            hikariConfig.setPassword(password);
            hikariConfig.setMaximumPoolSize(poolSize);
            hikariConfig.setMinimumIdle(1);
            hikariConfig.setPoolName("OrderPlugin-MySQL");
            hikariConfig.setConnectionTimeout(10_000);
            hikariConfig.addDataSourceProperty("cachePrepStmts", "true");
            hikariConfig.addDataSourceProperty("prepStmtCacheSize", "250");
            hikariConfig.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        } else {
            if (!plugin.getDataFolder().exists()) {
                plugin.getDataFolder().mkdirs();
            }
            File dbFile = new File(plugin.getDataFolder(), "orders.db");
            hikariConfig.setJdbcUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
            hikariConfig.setMaximumPoolSize(1); // SQLite only supports one writer at a time
            hikariConfig.setPoolName("OrderPlugin-SQLite");
            hikariConfig.setConnectionTimeout(10_000);
            hikariConfig.addDataSourceProperty("journal_mode", "WAL");
            hikariConfig.addDataSourceProperty("busy_timeout", "5000");
        }

        this.dataSource = new HikariDataSource(hikariConfig);
        createSchema();
    }

    private void createSchema() throws SQLException {
        String autoIncrement = (type == Type.MYSQL) ? "AUTO_INCREMENT" : "AUTOINCREMENT";

        String ordersTable = "CREATE TABLE IF NOT EXISTS order_listings (" +
                "id INTEGER PRIMARY KEY " + autoIncrement + ", " +
                "item_data TEXT NOT NULL, " +
                "seller_uuid VARCHAR(36) NOT NULL, " +
                "seller_name VARCHAR(32) NOT NULL, " +
                "price DOUBLE NOT NULL, " +
                "created_at BIGINT NOT NULL, " +
                "status VARCHAR(16) NOT NULL" +
                ")";

        String payoutsTable = "CREATE TABLE IF NOT EXISTS order_pending_payouts (" +
                "id INTEGER PRIMARY KEY " + autoIncrement + ", " +
                "player_uuid VARCHAR(36) NOT NULL, " +
                "amount DOUBLE NOT NULL, " +
                "created_at BIGINT NOT NULL" +
                ")";

        try (Connection conn = getConnection(); Statement st = conn.createStatement()) {
            st.execute(ordersTable);
            st.execute(payoutsTable);
            try {
                st.execute("CREATE INDEX idx_order_status ON order_listings(status)");
            } catch (SQLException ignored) { /* index already exists */ }
            try {
                st.execute("CREATE INDEX idx_order_seller ON order_listings(seller_uuid)");
            } catch (SQLException ignored) { /* index already exists */ }
            try {
                st.execute("CREATE INDEX idx_payout_uuid ON order_pending_payouts(player_uuid)");
            } catch (SQLException ignored) { /* index already exists */ }
        }
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    public void close() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
    }

    public Type getType() {
        return type;
    }
}
