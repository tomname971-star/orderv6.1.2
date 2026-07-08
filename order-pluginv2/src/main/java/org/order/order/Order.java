cat > src/main/java/org/order/order/Order.java << 'EOF'
package org.order.order;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.order.order.db.Database;
import org.order.order.manager.OrderManager;
import org.order.order.util.ItemUtil;
import org.money.money.Money;

import java.sql.SQLException;
import java.util.Objects;

public final class Order extends JavaPlugin implements Listener {

    private static Order instance;
    private Database database;
    private OrderManager orderManager;
    private OrderListener orderListener;

    public static final String ORDER_TITLE_PREFIX = "§8Order System";

    @Override
    public void onEnable() {
        instance = this;

        saveDefaultConfig();
        getConfig().options().copyDefaults(true);
        saveConfig();

        this.database = new Database(this);
        try {
            database.connect();
        } catch (SQLException e) {
            getLogger().severe("=================================================");
            getLogger().severe("Could not connect to the database! Disabling Order.");
            getLogger().severe(e.getMessage());
            getLogger().severe("=================================================");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        this.orderManager = new OrderManager(this, database);
        orderManager.loadAll();

        this.orderListener = new OrderListener(this);
        getServer().getPluginManager().registerEvents(orderListener, this);
        getServer().getPluginManager().registerEvents(this, this);

        Objects.requireNonNull(getCommand("orders")).setExecutor(this::onOrdersCommand);

        new BukkitRunnable() {
            @Override
            public void run() {
                orderManager.cleanupExpired();
            }
        }.runTaskTimer(this, 1200L, 1200L);

        getLogger().info("Order plugin enabled successfully! (" + database.getType() + " backend)");
    }

    @Override
    public void onDisable() {
        if (database != null) {
            database.close();
        }
    }

    private boolean onOrdersCommand(org.bukkit.command.CommandSender sender, org.bukkit.command.Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("This command can only be used by players.");
            return true;
        }

        if (!p.hasPermission("order.use")) {
            p.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
            return true;
        }

        if (args.length > 0) {
            String sub = args[0].toLowerCase();

            switch (sub) {
                case "create" -> {
                    if (!p.hasPermission("order.create")) {
                        p.sendMessage(ChatColor.RED + "You don't have permission to create orders.");
                        return true;
                    }
                    orderListener.openCreateOrder(p);
                    return true;
                }
                case "list" -> {
                    orderListener.openOrderList(p, 0);
                    return true;
                }
                case "my" -> {
                    orderListener.openMyOrders(p, 0);
                    return true;
                }
                case "claim" -> {
                    if (!p.hasPermission("order.claim")) {
                        p.sendMessage(ChatColor.RED + "You don't have permission to claim orders.");
                        return true;
                    }
                    claimAllRewards(p);
                    return true;
                }
                case "searchbook" -> {
                    orderListener.giveSearchBook(p);
                    return true;
                }
                case "help" -> {
                    sendHelp(p);
                    return true;
                }
                case "reload" -> {
                    if (!p.hasPermission("order.admin")) {
                        p.sendMessage(ChatColor.RED + "You don't have permission to do this.");
                        return true;
                    }
                    reloadConfig();
                    orderManager.reloadSettings();
                    p.sendMessage(ChatColor.GREEN + "Order config reloaded.");
                    return true;
                }
                case "clear" -> {
                    if (!p.hasPermission("order.admin")) {
                        p.sendMessage(ChatColor.RED + "You don't have permission to do this.");
                        return true;
                    }
                    orderManager.clearAllOrders();
                    p.sendMessage(ChatColor.GREEN + "All orders have been cleared.");
                    return true;
                }
                default -> {
                    sendHelp(p);
                    return true;
                }
            }
        }

        orderListener.openOrderList(p, 0);
        return true;
    }

    private void claimAllRewards(Player p) {
        int count = orderManager.getUnclaimedRewardsCount(p.getUniqueId());
        if (count == 0) {
            p.sendMessage(ChatColor.RED + "You have no unclaimed rewards.");
            return;
        }

        java.util.List<ItemStack> rewards = orderManager.claimRewards(p);
        int claimed = 0;
        for (ItemStack item : rewards) {
            java.util.Map<Integer, ItemStack> leftover = p.getInventory().addItem(item);
            if (!leftover.isEmpty()) {
                for (ItemStack extra : leftover.values()) {
                    p.getWorld().dropItemNaturally(p.getLocation(), extra);
                }
            }
            claimed++;
        }

        p.sendMessage(ChatColor.GREEN + "You claimed " + claimed + " reward(s)!");
        p.playSound(p.getLocation(), org.bukkit.Sound.ENTITY_PLAYER_LEVELUP, 1f, 1f);
    }

    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        orderManager.collectPendingPayouts(p.getUniqueId(), amount -> {
            Player online = Bukkit.getPlayer(p.getUniqueId());
            if (online == null || !online.isOnline()) return;
            deposit(online, amount);
            online.sendMessage(ChatColor.GREEN + ItemUtil.toSmallCaps("you received") + " §6$" + ItemUtil.formatPrice(amount)
                    + ChatColor.GREEN + " " + ItemUtil.toSmallCaps("from orders sold while you were offline"));
        });
    }

    public static Order getInstance() {
        return instance;
    }

    public OrderManager getOrderManager() {
        return orderManager;
    }

    public OrderListener getOrderListener() {
        return orderListener;
    }

    // ==================== MONEY INTEGRATION ====================
    public boolean hasEnough(Player p, double amount) {
        return Money.hasEnoughMoney(p, amount);
    }

    public void withdraw(Player p, double amount) {
        Money.removeMoney(p, amount);
    }

    public void deposit(Player p, double amount) {
        Money.addMoney(p, amount);
    }

    public void payoutSeller(java.util.UUID sellerUuid, double amount) {
        Player seller = Bukkit.getPlayer(sellerUuid);
        if (seller != null && seller.isOnline()) {
            deposit(seller, amount);
            seller.sendMessage(ChatColor.GREEN + ItemUtil.toSmallCaps("your order was purchased for") + " §6$" + ItemUtil.formatPrice(amount));
        } else {
            orderManager.queuePayout(sellerUuid, amount);
        }
    }

    private void sendHelp(Player p) {
        p.sendMessage("§8§m----------------------------------------");
        p.sendMessage("§6§lORDER SYSTEM HELP");
        p.sendMessage("§8§m----------------------------------------");
        p.sendMessage("§e/orders §7- " + ItemUtil.toSmallCaps("open order list"));
        p.sendMessage("§e/orders create §7- " + ItemUtil.toSmallCaps("create a new order"));
        p.sendMessage("§e/orders my §7- " + ItemUtil.toSmallCaps("view your orders"));
        p.sendMessage("§e/orders claim §7- " + ItemUtil.toSmallCaps("claim completed rewards"));
        p.sendMessage("§e/orders searchbook §7- " + ItemUtil.toSmallCaps("get a search book"));
        if (p.hasPermission("order.admin")) {
            p.sendMessage("§e/orders reload §7- " + ItemUtil.toSmallCaps("reload the config"));
            p.sendMessage("§e/orders clear §7- " + ItemUtil.toSmallCaps("clear all orders"));
        }
        p.sendMessage("§8§m----------------------------------------");
    }
}
EOF
