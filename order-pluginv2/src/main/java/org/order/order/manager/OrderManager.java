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

public class OrderManager {

    public enum OrderStatus {
        ACTIVE,
        EXPIRED,
        COMPLETED  // NEU
    }

    public static final class OrderData {
        private final long id;
        private final ItemStack item;
        private final String seller;
        private final UUID uuid;
        private final double price; // Preis PRO ITEM
        private final int amount; // Gesamtmenge (NEU)
        private int deliveredAmount; // Gelieferte Menge (NEU)
        private final long time;
        private volatile OrderStatus status;

        public OrderData(long id, ItemStack item, String seller, UUID uuid, double price, int amount, int deliveredAmount, long time, OrderStatus status) {
            this.id = id;
            this.item = item;
            this.seller = seller;
            this.uuid = uuid;
            this.price = price;
            this.amount = amount;
            this.deliveredAmount = deliveredAmount;
            this.time = time;
            this.status = status;
        }

        public long id() { return id; }
        public ItemStack item() { return item; }
        public String seller() { return seller; }
        public UUID uuid() { return uuid; }
        public double price() { return price; }
        public int amount() { return amount; }
        public int deliveredAmount() { return deliveredAmount; }
        public long time() { return time; }
        public OrderStatus status() { return status; }
        public double totalPrice() { return price * amount; } // NEU

        public void setDeliveredAmount(int deliveredAmount) { // NEU
            this.deliveredAmount = deliveredAmount;
        }

        // NEU: Fortschrittsbalken
        private String getProgressBar() {
            int progress = amount > 0 ? (deliveredAmount * 100 / amount) : 0;
            int filled = Math.min(progress / 10, 10);
            StringBuilder bar = new StringBuilder("§7[");
            for (int i = 0; i < 10; i++) {
                if (i < filled) {
                    bar.append("§a█");
                } else {
                    bar.append("§8█");
                }
            }
            bar.append("§7] §f").append(deliveredAmount).append("§7/§f").append(amount);
            return bar.toString();
        }

        public ItemStack getDisplay() {
            if (item == null) return new ItemStack(Material.AIR);

            ItemStack display = item.clone();
            ItemMeta meta = display.getItemMeta();
            if (meta == null) return display;

            List<String> lore = new ArrayList<>();
            lore.add("§8§m----------------------------------------");
            lore.add("§7" + ItemUtil.toSmallCaps("seller") + " §8» §f" + seller);
            lore.add("§7" + ItemUtil.toSmallCaps("price per item") + " §8» §6$" + ItemUtil.formatPrice(price));
            lore.add("§7" + ItemUtil.toSmallCaps("total price") + " §8» §6$" + ItemUtil.formatPrice(totalPrice()));
            lore.add("§7" + ItemUtil.toSmallCaps("progress") + " §8» " + getProgressBar()); // NEU
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
            if (status == OrderStatus.COMPLETED) { // NEU
                lore.add("§7" + ItemUtil.toSmallCaps("status") + " §8» §a" + ItemUtil.toSmallCaps("completed"));
                lore.add("§7" + ItemUtil.toSmallCaps("progress") + " §8» §a" + deliveredAmount + "§7/§f" + amount + " §a✓");
                lore.add("§8§m----------------------------------------");
                lore.add("§a" + ItemUtil.toSmallCaps("click to claim rewards"));
            } else {
                lore.add("§7" + ItemUtil.toSmallCaps("price per item") + " §8» §6$" + ItemUtil.formatPrice(price));
                lore.add("§7" + ItemUtil.toSmallCaps("progress") + " §8» " + getProgressBar());
                lore.add("§7" + ItemUtil.toSmallCaps("status") + " §8» §e" + ItemUtil.toSmallCaps("expired - unsold"));
                lore.add("§8§m----------------------------------------");
                lore.add("§a" + ItemUtil.toSmallCaps("click to reclaim item"));
            }

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

    public void loadAll() {
        List<OrderData> loaded = new ArrayList<>();
        try (Connection conn = database.getConnection();
             Statement st = conn.createStatement();
             ResultSet rs = st.executeQuery("SELECT id, item_data, seller_uuid, seller_name, price, amount, delivered_amount, created_at, status FROM order_listings")) {

            while (rs.next()) {
                ItemStack item = ItemUtil.deserialize(rs.getString("item_data"));
                if (item == null || item.getType() == Material.AIR) continue;

                OrderStatus status;
                try {
                    status = OrderStatus.valueOf(rs.getString("status"));
                } catch (IllegalArgumentException ex) {
                    status = OrderStatus.ACTIVE;
                }

                int amount = rs.getInt("amount");
                int deliveredAmount = rs.getInt("delivered_amount");
                if (amount <= 0) amount = 1;
                
                loaded.add(new OrderData(
                        rs.getLong("id"),
                        item,
                        rs.getString("seller_name"),
                        UUID.fromString(rs.getString("seller_uuid")),
                        rs.getDouble("price"),
                        amount,
                        deliveredAmount,
                        rs.getLong("created_at"),
                        status
                ));
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not load orders: " + e.getMessage());
            return;
        }

        cache.clear();
        cache.addAll(loaded);
        plugin.getLogger().info("Loaded " + cache.size() + " orders.");
    }

    // GEÄNDERT: Nimmt jetzt amount als Parameter
    public void addOrder(ItemStack item, String sellerName, UUID sellerUuid, double price, int amount) {
        if (item == null || item.getType() == Material.AIR || sellerUuid == null || amount <= 0) return;

        long now = System.currentTimeMillis();
        OrderData placeholder = new OrderData(-1, item.clone(), sellerName, sellerUuid, price, amount, 0, now, OrderStatus.ACTIVE);
        cache.add(placeholder);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            String serialized = ItemUtil.serialize(item);
            String sql = "INSERT INTO order_listings (item_data, seller_uuid, seller_name, price, amount, delivered_amount, created_at, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
            try (Connection conn = database.getConnection();
                 PreparedStatement ps = conn.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, serialized);
                ps.setString(2, sellerUuid.toString());
                ps.setString(3, sellerName);
                ps.setDouble(4, price);
                ps.setInt(5, amount);
                ps.setInt(6, 0);
                ps.setLong(7, now);
                ps.setString(8, OrderStatus.ACTIVE.name());
                ps.executeUpdate();

                try (ResultSet keys = ps.getGeneratedKeys()) {
                    if (keys.next()) {
                        long id = keys.getLong(1);
                        replaceInCache(placeholder, new OrderData(id, placeholder.item(), sellerName, sellerUuid, price, amount, 0, now, OrderStatus.ACTIVE));
                    }
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("Could not save order: " + e.getMessage());
                cache.remove(placeholder);
            }
        });
    }

    // NEU: Teillieferung
    public boolean deliverToOrder(Player player, OrderData order, ItemStack items) {
        if (order == null || player == null || items == null) return false;
        if (order.status() != OrderStatus.ACTIVE) return false;
        if (!order.uuid().equals(player.getUniqueId())) return false;
        
        int amountToDeliver = items.getAmount();
        int remaining = order.amount() - order.deliveredAmount();
        if (remaining <= 0) return false;
        
        int deliverAmount = Math.min(amountToDeliver, remaining);
        
        order.setDeliveredAmount(order.deliveredAmount() + deliverAmount);
        
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Connection conn = database.getConnection();
                 PreparedStatement ps = conn.prepareStatement("UPDATE order_listings SET delivered_amount = ? WHERE id = ?")) {
                ps.setInt(1, order.deliveredAmount());
                ps.setLong(2, order.id());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Could not update delivery progress: " + e.getMessage());
            }
        });
        
        ItemStack toRemove = items.clone();
        toRemove.setAmount(deliverAmount);
        player.getInventory().removeItem(toRemove);
        
        if (order.deliveredAmount() >= order.amount()) {
            completeOrder(order);
        }
        
        return true;
    }

    // NEU: Bestellung abschließen
    private void completeOrder(OrderData order) {
        if (order == null || order.status() != OrderStatus.ACTIVE) return;
        
        order.status = OrderStatus.COMPLETED;
        
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Connection conn = database.getConnection();
                 PreparedStatement ps = conn.prepareStatement("INSERT INTO order_claim_storage (player_uuid, item_data, claimed, created_at) VALUES (?, ?, ?, ?)")) {
                ps.setString(1, order.uuid().toString());
                ps.setString(2, ItemUtil.serialize(order.item()));
                ps.setBoolean(3, false);
                ps.setLong(4, System.currentTimeMillis());
                ps.executeUpdate();
                
                try (PreparedStatement updatePs = conn.prepareStatement("UPDATE order_listings SET status = ? WHERE id = ?")) {
                    updatePs.setString(1, OrderStatus.COMPLETED.name());
                    updatePs.setLong(2, order.id());
                    updatePs.executeUpdate();
                }
            } catch (SQLException e) {
                plugin.getLogger().severe("Could not complete order: " + e.getMessage());
            }
        });
    }

    // NEU: Belohnungen abholen
    public List<ItemStack> claimRewards(Player player) {
        List<ItemStack> rewards = new ArrayList<>();
        UUID playerUuid = player.getUniqueId();
        
        try (Connection conn = database.getConnection();
             PreparedStatement selectPs = conn.prepareStatement("SELECT id, item_data FROM order_claim_storage WHERE player_uuid = ? AND claimed = FALSE")) {
            selectPs.setString(1, playerUuid.toString());
            
            try (ResultSet rs = selectPs.executeQuery()) {
                List<Long> ids = new ArrayList<>();
                while (rs.next()) {
                    ItemStack item = ItemUtil.deserialize(rs.getString("item_data"));
                    if (item != null && item.getType() != Material.AIR) {
                        rewards.add(item);
                    }
                    ids.add(rs.getLong("id"));
                }
                
                if (!ids.isEmpty()) {
                    try (PreparedStatement updatePs = conn.prepareStatement("UPDATE order_claim_storage SET claimed = TRUE WHERE id = ?")) {
                        for (Long id : ids) {
                            updatePs.setLong(1, id);
                            updatePs.addBatch();
                        }
                        updatePs.executeBatch();
                    }
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not claim rewards: " + e.getMessage());
        }
        
        return rewards;
    }

    private void replaceInCache(OrderData oldData, OrderData newData) {
        cache.remove(oldData);
        cache.add(newData);
    }

    public void removeOrder(OrderData order) {
        if (order == null) return;
        cache.remove(order);

        if (order.id() < 0) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Connection conn = database.getConnection();
                 PreparedStatement ps = conn.prepareStatement("DELETE FROM order_listings WHERE id = ?")) {
                ps.setLong(1, order.id());
                ps.executeUpdate();
            } catch (SQLException e) {
                plugin.getLogger().severe("Could not delete order: " + e.getMessage());
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

    public List<OrderData> getClaimableOrders(UUID uuid) {
        if (uuid == null) return new ArrayList<>();
        return cache.stream()
                .filter(o -> (o.status() == OrderStatus.EXPIRED || o.status() == OrderStatus.COMPLETED) && uuid.equals(o.uuid()))
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

    public void cleanupExpired() {
        if (!writeLock.tryLock()) return;
        try {
            long now = System.currentTimeMillis();
            List<OrderData> toExpire = new ArrayList<>();

            for (OrderData o : cache) {
                if (o.status() == OrderStatus.ACTIVE && (now - o.time()) >= durationMs) {
                    toExpire.add(o);
                }
            }

            if (toExpire.isEmpty()) return;

            for (OrderData o : toExpire) {
                o.status = OrderStatus.EXPIRED;
            }

            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                try (Connection conn = database.getConnection();
                     PreparedStatement ps = conn.prepareStatement("UPDATE order_listings SET status = ? WHERE id = ?")) {
                    for (OrderData o : toExpire) {
                        if (o.id() < 0) continue;
                        ps.setString(1, OrderStatus.EXPIRED.name());
                        ps.setLong(2, o.id());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                } catch (SQLException e) {
                    plugin.getLogger().severe("Could not update expired orders: " + e.getMessage());
                }
            });

            plugin.getLogger().info("Order cleanup: " + toExpire.size() + " order(s) expired.");
        } finally {
            writeLock.unlock();
        }
    }

    public int getOrderCount() {
        return cache.size();
    }

    public void clearAllOrders() {
        cache.clear();
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try (Connection conn = database.getConnection(); Statement st = conn.createStatement()) {
                st.execute("DELETE FROM order_listings");
                st.execute("DELETE FROM order_claim_storage");
            } catch (SQLException e) {
                plugin.getLogger().severe("Could not clear orders: " + e.getMessage());
            }
        });
    }

    // NEU: Anzahl ungeclaimter Belohnungen
    public int getUnclaimedRewardsCount(UUID playerUuid) {
        int count = 0;
        try (Connection conn = database.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM order_claim_storage WHERE player_uuid = ? AND claimed = FALSE")) {
            ps.setString(1, playerUuid.toString());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    count = rs.getInt(1);
                }
            }
        } catch (SQLException e) {
            plugin.getLogger().severe("Could not count unclaimed rewards: " + e.getMessage());
        }
        return count;
    }

    // ==================== PENDING PAYOUTS (unverändert) ====================

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
                plugin.getLogger().severe("Could not queue payout: " + e.getMessage());
            }
        });
    }

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
                plugin.getLogger().severe("Could not collect payouts: " + e.getMessage());
                return;
            }

            if (total > 0) {
                double finalTotal = total;
                Bukkit.getScheduler().runTask(plugin, () -> onMainThread.accept(finalTotal));
            }
        });
    }
}
