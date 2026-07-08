package org.order.order.manager;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.order.order.Order;
import org.order.order.db.Database;
import org.order.order.util.ItemUtil;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * Manages all order listings. Reads/writes go through a SQLite/MySQL database
 * (see {@link Database}), but an in-memory cache is kept so that the GUI
 * (opened very frequently by players) never has to block on disk/network I/O.
 * All database writes are dispatched asynchronously; the in-memory cache is
 * only ever mutated on the main thread to stay thread-safe with Bukkit's API.
 */
public class OrderManager {

    public enum OrderStatus {
        ACTIVE,
        EXPIRED
    }

    public static final class OrderData {
        private final long id;
        private final ItemStack item;
        private final String seller;
        private final UUID uuid;
        private final double price;
        private final long time;
        private volatile OrderStatus status;

        public OrderData(long id, ItemStack item, String seller, UUID uuid, double price, long time, OrderStatus status) {
            this.id = id;
            this.item = item;
            this.seller = seller;
            this.uuid = uuid;
            this.price = price;
            this.time = time;
            this.status = status;
        }

        public long id() { return id; }
        public ItemStack item() { return item; }
        public String seller() { return seller; }
        public UUID uuid() { return uuid; }
        public double price() { return price; }
        public long time() { return time; }
        public OrderStatus status() { return status; }

        public ItemStack getDisplay() {
            if (item == null) return new ItemStack(Material.AIR);

            ItemStack display = item.clone();
            ItemMeta meta = display.getItemMeta();
            if (meta == null) return display;

            List<String> lore = new ArrayList<>();
            lore.add("§8§m----------------------------------------");
            lore.add("§7" + ItemUtil.toSmallCaps("seller") + " §8» §f" + seller);
            lore.add("§7" + ItemUtil.toSmallCaps("price") + " §8» §6$" + ItemUtil.formatPrice(price));
            lore.add("§7" + ItemUtil.toSmallCaps("status") + " §8» §a" + ItemUtil.toSmallCaps("active"));
            lore.add("§8§m----------------------------------------");
            lore.add("§e" + ItemUtil.toSmallCaps("click to view details"));

            meta.setLore(lore);
            meta.setDisplayName("§6" + ItemUtil.toSmallCaps(item.getType().name().replace("_", " ")));
            display.setItemMeta(meta);
            return display;
        }

        public ItemStack getClaimDisplay() {
            if (item == null) return new ItemStack(Material.AIR);

            ItemStack display = item.clone();
            ItemMeta meta = display.getItemMeta();
            if (meta == null) return display;

            List<String> lore = new ArrayList<>();
            lore.add("§8§m----------------------------------------");
            lore.add("§7" + ItemUtil.toSmallCaps("price was") + " §8» §6$" + ItemUtil.formatPrice(price));
            lore.add("§7" + ItemUtil.toSmallCaps("status") + " §8» §e" + ItemUtil.toSmallCaps("expired - unsold"));
            lore.add("§8§m----------------------------------------");
            lore.add("§a" + ItemUtil.toSmallCaps("click to reclaim item"));

            meta.setLore(lore);
            meta.setDisplayName("§6" + ItemUtil.toSmallCaps(item.getType().name().replace("_", " ")));
            display.setItemMeta(meta);
            return display;
        }
    }

    private final Order plugin;
    private final Database database;
    private final List<OrderData> cache = new CopyOnWriteArrayList<>();
    private final ReentrantLock writeLock = new ReentrantLock();
    private volatile long durationMs;
    private volatile long graceMs;

    public OrderManager(Order plugin, Database database) {
        this.plugin = plugin;
        this.database = database;
        reloadSettings();
    }

    public void reloadSettings() {
        this.durationMs = plugin.getConfig().getLong("settings.order-duration-hours", 24) * 3600L * 1000L;
        long graceDays = plugin.getConfig().getLong("settings.claim-grace-days", 7);
        this.graceMs = graceDays * 24L * 3600L * 1000L;
    }

    /** Loads every order from the database into the in-memory cache. Call once, at startup, before the server accepts joins. */
    public void loadAll() {
        List<OrderData> loaded = new ArrayList<>();
        try (Connection conn = database.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, item_data, seller_uuid, seller_name, price, created_at, status FROM order_listings")) {

            while (rs.next()) {
                ItemStack item = ItemUtil.deserialize(rs.getString("item_data"));
                if (item == null || item.getType() == Material.AIR) continue;

                OrderStatus status;
                try {
                    status = OrderStatus.valueOf(rs.getString("status"));
                } catch (IllegalArgumentException ex) {
                    status = OrderStatus.ACTIVE;
                }

                loaded.add(new OrderData(
                        rs.getLong("id"),
                        item,
                        rs.getString("seller_name"),
                        UUID.fromString(rs.getString("seller_uuid")),
                        rs.getDouble("price"),
                        rs.getLong("created_at"),
                        status
                ));
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not load orders from database: " + e.getMessage());
            return;
        }

        cache.clear();
        cache.addAll(loaded);
        plugin.getLogger().info("Loaded " + cache.size() + " orders from the database.");
    }

    /**
     * Creates a new order. Must be called on the main thread (it touches the
     * cache synchronously so the GUI reflects the new order immediately);
     * the database write itself happens asynchronously.
     */
    public void addOrder(ItemStack item, String sellerName, UUID sellerUuid, double price) {
        if (item == null || item.getType() == Material.AIR || sellerUuid == null) return;

        long now = System.currentTimeMillis();
        OrderData placeholder = new OrderData(-1, item.clone(), sellerName, sellerUuid, price, now, OrderStatus.ACTIVE);
        cache.add(placeholder);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String serialized = ItemUtil.serialize(item);
            String sql = "INSERT INTO order_listings (item_data, seller_uuid, seller_name, price, created_at, status) VALUES (?, ?, ?, ?, ?, ?)";
            try (Connection conn = database.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, serialized);
                ps.setString(2, sellerUuid.toString());
                ps.setString(3, sellerName);
                ps.setDouble(4, price);
                ps.setLong(5, now);
                ps.setString(6, OrderStatus.ACTIVE.name());
                ps.executeUpdate();

                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next()) {
                        long id = keys.getLong(1);
                        replaceInCache(placeholder, new OrderData(id, placeholder.item(), sellerName, sellerUuid, price, now, OrderStatus.ACTIVE));
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("Could not save new order: " + e.getMessage());
                cache.remove(placeholder);
            }
        });
    }

    private void replaceInCache(OrderData oldData, OrderData newData) {
        cache.remove(oldData);
        cache.add(newData);
    }

    /** Removes an order (bought, cancelled, or reclaimed). Safe to call from the main thread. */
    public void removeOrder(OrderData order) {
        if (order == null) return;
        cache.remove(order);

        if (order.id() < 0) return; // never made it into the DB, nothing to delete
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Connection conn = database.getConnection();
                 PreparedStatement ps = conn.prepareStatement("DELETE FROM order_listings WHERE id = ?")) {
                ps.setLong(1, order.id());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Could not delete order #" + order.id() + ": " + e.getMessage());
            }
        });
    }

    public List<OrderData> getActiveOrders() {
        return cache.stream()
                .filter(o -> o.status() == OrderStatus.ACTIVE)
                .sorted((a, b) -> Long.compare(b.time(), a.time()))
                .collect(Collectors.toList());
    }

    public List<OrderData> getPlayerOrders(UUID uuid) {
        if (uuid == null) return new ArrayList<>();
        return cache.stream()
                .filter(o -> o.status() == OrderStatus.ACTIVE && uuid.equals(o.uuid()))
                .sorted((a, b) -> Long.compare(b.time(), a.time()))
                .collect(Collectors.toList());
    }

    /** Only the seller may see and reclaim their own expired, unsold orders. */
    public List<OrderData> getClaimableOrders(UUID uuid) {
        if (uuid == null) return new ArrayList<>();
        return cache.stream()
                .filter(o -> o.status() == OrderStatus.EXPIRED && uuid.equals(o.uuid()))
                .sorted((a, b) -> Long.compare(b.time(), a.time()))
                .collect(Collectors.toList());
    }

    public int countActiveOrders(UUID uuid) {
        if (uuid == null) return 0;
        int count = 0;
        for (OrderData o : cache) {
            if (o.status() == OrderStatus.ACTIVE && uuid.equals(o.uuid())) count++;
        }
        return count;
    }

    /** Moves timed-out ACTIVE orders to EXPIRED (they become claimable by their seller), and permanently purges long-unclaimed EXPIRED orders. */
    public void cleanupExpired() {
        if (!writeLock.tryLock()) return;
        try {
            long now = System.currentTimeMillis();
            List<OrderData> toExpire = new ArrayList<>();
            List<OrderData> toPurge = new ArrayList<>();

            for (OrderData o : cache) {
                if (o.status() == OrderStatus.ACTIVE && (now - o.time()) >= durationMs) {
                    toExpire.add(o);
                } else if (o.status() == OrderStatus.EXPIRED && (now - o.time()) >= (durationMs + graceMs)) {
                    toPurge.add(o);
                }
            }

            if (toExpire.isEmpty() && toPurge.isEmpty()) return;

            for (OrderData o : toExpire) {
                o.status = OrderStatus.EXPIRED;
            }
            cache.removeAll(toPurge);

            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try (Connection conn = database.getConnection()) {
                    conn.setAutoCommit(false);
                    try (PreparedStatement expirePs = conn.prepareStatement("UPDATE order_listings SET status = ? WHERE id = ?")) {
                        for (OrderData o : toExpire) {
                            if (o.id() < 0) continue;
                            expirePs.setString(1, OrderStatus.EXPIRED.name());
                            expirePs.setLong(2, o.id());
                            expirePs.addBatch();
                        }
                        expirePs.executeBatch();
                    }
                    try (PreparedStatement purgePs = conn.prepareStatement("DELETE FROM order_listings WHERE id = ?")) {
                        for (OrderData o : toPurge) {
                            if (o.id() < 0) continue;
                            purgePs.setLong(1, o.id());
                            purgePs.addBatch();
                        }
                        purgePs.executeBatch();
                    }
                    conn.commit();
                } catch (SQLException e) {
                    plugin.getLogger().severe("Could not persist order cleanup: " + e.getMessage());
                }
            });

            if (!toExpire.isEmpty() || !toPurge.isEmpty()) {
                plugin.getLogger().info("Order cleanup: " + toExpire.size() + " order(s) expired, " + toPurge.size() + " order(s) purged permanently.");
            }
        } finally {
            writeLock.unlock();
        }
    }

    public int getOrderCount() {
        return cache.size();
    }

    /** Admin command: wipes every order from cache and database. */
    public void clearAllOrders() {
        cache.clear();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Connection conn = database.getConnection(); Statement st = conn.createStatement()) {
                st.execute("DELETE FROM order_listings");
            } catch (SQLException e) {
                plugin.getLogger().severe("Could not clear orders table: " + e.getMessage());
            }
        });
    }

    // ==================== PENDING PAYOUTS ====================
    // Used when a buyer purchases an order from a seller who is currently
    // offline; the money is queued here and paid out automatically the next
    // time the seller logs back in (see Order#onEnable's join listener).

    public void queuePayout(UUID sellerUuid, double amount) {
        if (sellerUuid == null || amount <= 0) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String sql = "INSERT INTO order_pending_payouts (player_uuid, amount, created_at) VALUES (?, ?, ?)";
            try (Connection conn = database.getConnection(); PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, sellerUuid.toString());
                ps.setDouble(2, amount);
                ps.setLong(3, System.currentTimeMillis());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Could not queue pending payout for " + sellerUuid + ": " + e.getMessage());
            }
        });
    }

    /** Fetches and deletes all pending payouts for a player, then hands the total back on the main thread via the callback. */
    public void collectPendingPayouts(UUID playerUuid, java.util.function.DoubleConsumer onMainThread) {
        if (playerUuid == null) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            double total = 0;
            List<Long> ids = new ArrayList<>();
            String select = "SELECT id, amount FROM order_pending_payouts WHERE player_uuid = ?";
            try (Connection conn = database.getConnection()) {
                try (PreparedStatement ps = conn.prepareStatement(select)) {
                    ps.setString(1, playerUuid.toString());
                    try (ResultSet rs = ps.executeQuery()) {
                        while (rs.next()) {
                            total += rs.getDouble("amount");
                            ids.add(rs.getLong("id"));
                        }
                    }
                }
                if (!ids.isEmpty()) {
                    try (PreparedStatement del = conn.prepareStatement("DELETE FROM order_pending_payouts WHERE id = ?")) {
                        for (Long id : ids) {
                            del.setLong(1, id);
                            del.addBatch();
                        }
                        del.executeBatch();
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("Could not collect pending payouts for " + playerUuid + ": " + e.getMessage());
                return;
            }

            if (total > 0) {
                double finalTotal = total;
                Bukkit.getScheduler().runTask(plugin, () -> onMainThread.accept(finalTotal));
            }
        });
    }
}
