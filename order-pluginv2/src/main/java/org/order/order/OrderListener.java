package org.order.order;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.*;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.order.order.manager.OrderManager;
import org.order.order.util.ItemUtil;

import java.util.*;
import java.util.stream.Collectors;

public class OrderListener implements Listener {
    private final Order plugin;
    private final NamespacedKey searchBookKey;

    private final Map<UUID, String> chatCache = new HashMap<>();
    private final Map<UUID, OrderManager.OrderData> orderCache = new HashMap<>();
    private final Map<UUID, Long> lastClickTime = new HashMap<>();
    private final Map<UUID, InventoryType> openInventoryType = new HashMap<>();
    private final Map<UUID, Material> selectedMaterialCache = new HashMap<>();
    private final Map<UUID, Integer> currentPageCache = new HashMap<>();
    private final Map<UUID, String> currentCategoryCache = new HashMap<>();
    private final Map<UUID, String> searchQueryCache = new HashMap<>();
    private final Map<UUID, List<Material>> searchResultsCache = new HashMap<>();

    private static final String PRIMARY = "#FF6B6B";
    private static final String SECONDARY = "#4ECDC4";
    private static final String ACCENT = "#FFE66D";
    private static final String GREEN = "#2ECC71";
    private static final String GRAY = "#636E72";
    private static final String WHITE = "#FFFFFF";
    private static final String GOLD = "#FDCB6E";

    private static List<Material> allItems = null;
    private static Map<String, Material[]> categoriesCache = null;

    private enum InventoryType {
        ORDER_LIST,
        MY_ORDERS,
        CLAIM_ORDERS,
        ORDER_DETAILS,
        MAIN_MENU,
        ITEM_CATEGORY,
        CREATE_ORDER,
        SEARCH_RESULTS
    }

    public OrderListener(Order plugin) {
        this.plugin = plugin;
        this.searchBookKey = new NamespacedKey(plugin, "order_search_book");
        loadAllItems();
        loadCategories();
    }

    private String hex(String hexColor) {
        return net.md_5.bungee.api.ChatColor.of(hexColor).toString();
    }

    private void loadAllItems() {
        if (allItems != null) return;
        allItems = Arrays.stream(Material.values())
                .filter(ItemUtil::isAllowed)
                .sorted(Comparator.comparing(Enum::name))
                .collect(Collectors.toList());
        plugin.getLogger().info("Loaded " + allItems.size() + " legitimate items for selection.");
    }

    private void loadCategories() {
        if (categoriesCache != null) return;
        categoriesCache = buildCategories();
        plugin.getLogger().info("Loaded " + categoriesCache.size() + " categories.");
    }

    // ==================== SEARCH BOOK ====================

    public void giveSearchBook(Player p) {
        ItemStack book = new ItemStack(Material.WRITABLE_BOOK);
        ItemMeta meta = book.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(hex(ACCENT) + ItemUtil.toSmallCaps("order search book"));
            meta.setLore(Arrays.asList(
                    hex(GRAY) + "----------------------------------------",
                    hex(SECONDARY) + ItemUtil.toSmallCaps("right click to search for items"),
                    hex(GRAY) + "----------------------------------------"
            ));
            meta.getPersistentDataContainer().set(searchBookKey, PersistentDataType.BYTE, (byte) 1);
            book.setItemMeta(meta);
        }
        p.getInventory().addItem(book);
        p.sendMessage(hex(GREEN) + ItemUtil.toSmallCaps("you received a search book - right click it to search for items"));
    }

    private boolean isSearchBook(ItemStack item) {
        if (item == null || item.getType() != Material.WRITABLE_BOOK) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        Byte flag = meta.getPersistentDataContainer().get(searchBookKey, PersistentDataType.BYTE);
        return flag != null && flag == (byte) 1;
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Action action = e.getAction();
        if (action != Action.RIGHT_CLICK_AIR && action != Action.RIGHT_CLICK_BLOCK) return;

        ItemStack item = e.getItem();
        if (!isSearchBook(item)) return;

        e.setCancelled(true);
        Player p = e.getPlayer();
        if (!p.hasPermission("order.use")) return;
        openSearchMenu(p);
    }

    // ==================== MENUS ====================

    public void openOrderList(Player p, int page) {
        if (p == null) return;
        String title = hex(WHITE) + ItemUtil.toSmallCaps("order list") + " §7- " + ItemUtil.toSmallCaps("page") + " " + (page + 1);
        Inventory inv = Bukkit.createInventory(null, 54, title);
        List<OrderManager.OrderData> orders = plugin.getOrderManager().getActiveOrders();
        int maxPage = Math.max(0, orders.isEmpty() ? 0 : (orders.size() - 1) / 45);
        page = Math.min(Math.max(page, 0), maxPage);

        for (int i = 0; i < 45; i++) {
            int index = page * 45 + i;
            if (index >= orders.size()) break;
            inv.setItem(i, orders.get(index).getDisplay());
        }

        fillBottomBar(inv, page, maxPage);
        int finalPage = page;
        p.closeInventory();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            p.openInventory(inv);
            openInventoryType.put(p.getUniqueId(), InventoryType.ORDER_LIST);
            currentPageCache.put(p.getUniqueId(), finalPage);
        }, 1L);
    }

    public void openMyOrders(Player p, int page) {
        if (p == null) return;
        String title = hex(WHITE) + ItemUtil.toSmallCaps("my orders") + " §7- " + ItemUtil.toSmallCaps("page") + " " + (page + 1);
        Inventory inv = Bukkit.createInventory(null, 54, title);
        List<OrderManager.OrderData> orders = plugin.getOrderManager().getPlayerOrders(p.getUniqueId());
        int maxPage = Math.max(0, orders.isEmpty() ? 0 : (orders.size() - 1) / 45);
        page = Math.min(Math.max(page, 0), maxPage);

        for (int i = 0; i < 45; i++) {
            int index = page * 45 + i;
            if (index >= orders.size()) break;
            inv.setItem(i, orders.get(index).getDisplay());
        }

        fillBottomBar(inv, page, maxPage);
        int finalPage = page;
        p.closeInventory();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            p.openInventory(inv);
            openInventoryType.put(p.getUniqueId(), InventoryType.MY_ORDERS);
            currentPageCache.put(p.getUniqueId(), finalPage);
        }, 1L);
    }

    public void openClaimOrders(Player p, int page) {
        if (p == null) return;
        String title = hex(WHITE) + ItemUtil.toSmallCaps("claim orders") + " §7- " + ItemUtil.toSmallCaps("page") + " " + (page + 1);
        Inventory inv = Bukkit.createInventory(null, 54, title);
        List<OrderManager.OrderData> orders = plugin.getOrderManager().getClaimableOrders(p.getUniqueId());
        int maxPage = Math.max(0, orders.isEmpty() ? 0 : (orders.size() - 1) / 45);
        page = Math.min(Math.max(page, 0), maxPage);

        for (int i = 0; i < 45; i++) {
            int index = page * 45 + i;
            if (index >= orders.size()) break;
            inv.setItem(i, orders.get(index).getClaimDisplay());
        }

        fillBottomBar(inv, page, maxPage);
        int finalPage = page;
        p.closeInventory();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            p.openInventory(inv);
            openInventoryType.put(p.getUniqueId(), InventoryType.CLAIM_ORDERS);
            currentPageCache.put(p.getUniqueId(), finalPage);
        }, 1L);
    }

    public void openCreateOrder(Player p) {
        if (p == null) return;
        openCategoryMenu(p);
    }

    private void openCategoryMenu(Player p) {
        if (p == null) return;
        String title = hex(WHITE) + ItemUtil.toSmallCaps("select category");
        Inventory inv = Bukkit.createInventory(null, 54, title);

        inv.setItem(4, createButton(Material.WRITABLE_BOOK,
                hex(ACCENT) + ItemUtil.toSmallCaps("search items"),
                hex(GRAY) + "----------------------------------------",
                hex(SECONDARY) + ItemUtil.toSmallCaps("click to search for items"),
                hex(GRAY) + ItemUtil.toSmallCaps("type the name of the item"),
                hex(GRAY) + "----------------------------------------"));

        inv.setItem(0, createButton(Material.BOOK,
                hex(SECONDARY) + ItemUtil.toSmallCaps("select item category"),
                hex(GRAY) + "----------------------------------------",
                hex(GRAY) + ItemUtil.toSmallCaps("choose a category to browse items"),
                hex(GRAY) + "----------------------------------------"));

        int slot = 9;
        for (Map.Entry<String, Material[]> entry : categoriesCache.entrySet()) {
            if (slot >= 45) break;
            Material icon = entry.getValue().length > 0 ? entry.getValue()[0] : Material.CHEST;
            String categoryName = entry.getKey();
            ItemStack catItem = createButton(icon,
                    hex(ACCENT) + ItemUtil.toSmallCaps(categoryName),
                    hex(GRAY) + "----------------------------------------",
                    "§fCATEGORY:" + categoryName,
                    hex(GRAY) + ItemUtil.toSmallCaps("click to view") + " " + ItemUtil.toSmallCaps(categoryName),
                    hex(GRAY) + "----------------------------------------");
            inv.setItem(slot++, catItem);
        }

        inv.setItem(53, createButton(Material.RED_DYE,
                hex(PRIMARY) + ItemUtil.toSmallCaps("cancel"),
                hex(GRAY) + ItemUtil.toSmallCaps("click to cancel order creation")));

        p.closeInventory();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            p.openInventory(inv);
            openInventoryType.put(p.getUniqueId(), InventoryType.ITEM_CATEGORY);
        }, 1L);
    }

    public void openSearchMenu(Player p) {
        if (p == null) return;
        p.closeInventory();
        p.sendMessage(hex(SECONDARY) + ItemUtil.toSmallCaps("enter the item name to search"));
        p.sendMessage(hex(GRAY) + ItemUtil.toSmallCaps("examples") + ": diamond, iron, oak_log");
        p.sendMessage(hex(GRAY) + ItemUtil.toSmallCaps("type 'cancel' to abort"));
        chatCache.put(p.getUniqueId(), "SEARCH_ITEM");
    }

    private void performSearch(Player p, String query) {
        if (p == null || query == null || query.isEmpty()) return;

        String lowerQuery = query.toLowerCase().trim();
        List<Material> results = allItems.stream()
                .filter(mat -> mat.name().toLowerCase().contains(lowerQuery))
                .sorted(Comparator.comparing(Enum::name))
                .collect(Collectors.toList());

        searchQueryCache.put(p.getUniqueId(), query);
        searchResultsCache.put(p.getUniqueId(), results);
        openSearchResults(p, 0);
    }

    private void openSearchResults(Player p, int page) {
        if (p == null) return;

        List<Material> results = searchResultsCache.get(p.getUniqueId());
        if (results == null || results.isEmpty()) {
            p.sendMessage(hex(PRIMARY) + ItemUtil.toSmallCaps("no items found"));
            openCategoryMenu(p);
            return;
        }

        String query = searchQueryCache.get(p.getUniqueId());
        int maxPage = Math.max(0, (results.size() - 1) / 45);
        page = Math.min(Math.max(page, 0), maxPage);

        String title = hex(WHITE) + ItemUtil.toSmallCaps("search") + " §7- " + ItemUtil.toSmallCaps(query) + " §7- " + ItemUtil.toSmallCaps("page") + " " + (page + 1);
        Inventory inv = Bukkit.createInventory(null, 54, title);

        for (int i = 0; i < 45; i++) {
            int index = page * 45 + i;
            if (index >= results.size()) break;
            inv.setItem(i, createSelectableItem(results.get(index)));
        }

        fillCreateOrderBar(inv, page, maxPage, true);

        int finalPage = page;
        p.closeInventory();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            p.openInventory(inv);
            openInventoryType.put(p.getUniqueId(), InventoryType.SEARCH_RESULTS);
            currentPageCache.put(p.getUniqueId(), finalPage);
        }, 1L);
    }

    private void openItemsInCategory(Player p, String category, int page) {
        if (p == null || category == null) return;
        Material[] items = categoriesCache.get(category);

        if (items == null || items.length == 0) {
            openCategoryMenu(p);
            return;
        }

        int maxPage = Math.max(0, (items.length - 1) / 45);
        page = Math.min(Math.max(page, 0), maxPage);

        String title = hex(WHITE) + ItemUtil.toSmallCaps(category) + " §7- " + ItemUtil.toSmallCaps("page") + " " + (page + 1);
        Inventory inv = Bukkit.createInventory(null, 54, title);

        for (int i = 0; i < 45; i++) {
            int index = page * 45 + i;
            if (index >= items.length) break;
            inv.setItem(i, createSelectableItem(items[index]));
        }

        fillCreateOrderBar(inv, page, maxPage, false);

        currentCategoryCache.put(p.getUniqueId(), category);
        int finalPage = page;
        p.closeInventory();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            p.openInventory(inv);
            openInventoryType.put(p.getUniqueId(), InventoryType.CREATE_ORDER);
            currentPageCache.put(p.getUniqueId(), finalPage);
        }, 1L);
    }

    private ItemStack createSelectableItem(Material mat) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            String displayName = mat.name().replace("_", " ").toLowerCase();
            meta.setDisplayName(hex(WHITE) + ItemUtil.toSmallCaps(displayName));
            meta.setLore(Arrays.asList(
                    hex(GRAY) + "----------------------------------------",
                    hex(SECONDARY) + ItemUtil.toSmallCaps("click to select this item"),
                    hex(GRAY) + ItemUtil.toSmallCaps("you will then be asked for the amount"),
                    hex(GRAY) + "----------------------------------------"
            ));
            item.setItemMeta(meta);
        }
        return item;
    }

    private void openOrderDetails(Player p, OrderManager.OrderData order) {
        if (p == null || order == null) return;
        String title = hex(WHITE) + ItemUtil.toSmallCaps("order details");
        Inventory inv = Bukkit.createInventory(null, 27, title);

        ItemStack display = order.item().clone();
        ItemMeta meta = display.getItemMeta();
        if (meta != null) {
            List<String> lore = new ArrayList<>();
            lore.add(hex(GRAY) + "----------------------------------------");
            lore.add(hex(GRAY) + ItemUtil.toSmallCaps("order details") + ":");
            lore.add("");
            lore.add(hex(GRAY) + ItemUtil.toSmallCaps("seller") + ": " + hex(WHITE) + order.seller());
            lore.add(hex(GRAY) + ItemUtil.toSmallCaps("price per item") + ": " + hex(GOLD) + "$" + ItemUtil.formatPrice(order.price()));
            lore.add(hex(GRAY) + ItemUtil.toSmallCaps("total price") + ": " + hex(GOLD) + "$" + ItemUtil.formatPrice(order.totalPrice()));
            lore.add(hex(GRAY) + ItemUtil.toSmallCaps("progress") + ": " + order.deliveredAmount() + "/" + order.amount());
            lore.add(hex(GRAY) + ItemUtil.toSmallCaps("status") + ": " + hex(GREEN) + ItemUtil.toSmallCaps("active"));
            lore.add("");
            lore.add(hex(GRAY) + "----------------------------------------");
            if (order.deliveredAmount() >= order.amount()) {
                lore.add(hex(SECONDARY) + ItemUtil.toSmallCaps("click to accept order"));
            } else {
                lore.add(hex(PRIMARY) + ItemUtil.toSmallCaps("order not fully delivered yet"));
                lore.add(hex(GRAY) + "Delivered: " + order.deliveredAmount() + "/" + order.amount());
            }
            meta.setLore(lore);
            display.setItemMeta(meta);
        }
        inv.setItem(13, display);

        if (order.deliveredAmount() >= order.amount()) {
            inv.setItem(22, createButton(Material.LIME_DYE,
                    hex(GREEN) + ItemUtil.toSmallCaps("accept order"),
                    hex(GRAY) + ItemUtil.toSmallCaps("you will pay the price"),
                    hex(GRAY) + ItemUtil.toSmallCaps("and receive the item")));
        } else {
            inv.setItem(22, createButton(Material.RED_DYE,
                    hex(PRIMARY) + ItemUtil.toSmallCaps("not fully delivered"),
                    hex(GRAY) + "Delivered: " + order.deliveredAmount() + "/" + order.amount()));
        }

        inv.setItem(26, createButton(Material.RED_DYE,
                hex(PRIMARY) + ItemUtil.toSmallCaps("back"),
                hex(GRAY) + ItemUtil.toSmallCaps("return to order list")));

        p.closeInventory();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            p.openInventory(inv);
            openInventoryType.put(p.getUniqueId(), InventoryType.ORDER_DETAILS);
            orderCache.put(p.getUniqueId(), order);
        }, 1L);
    }

    private void openMainMenu(Player p) {
        if (p == null) return;
        String title = hex(WHITE) + ItemUtil.toSmallCaps("order system");
        Inventory inv = Bukkit.createInventory(null, 27, title);

        inv.setItem(11, createButton(Material.CHEST,
                hex(SECONDARY) + ItemUtil.toSmallCaps("view orders"),
                hex(GRAY) + ItemUtil.toSmallCaps("view all available orders")));

        inv.setItem(13, createButton(Material.BOOK,
                hex(ACCENT) + ItemUtil.toSmallCaps("my orders"),
                hex(GRAY) + ItemUtil.toSmallCaps("view your own orders")));

        inv.setItem(15, createButton(Material.HOPPER,
                hex(GREEN) + ItemUtil.toSmallCaps("claim orders"),
                hex(GRAY) + ItemUtil.toSmallCaps("reclaim expired, unsold orders")));

        inv.setItem(22, createButton(Material.GOLD_INGOT,
                hex(GOLD) + ItemUtil.toSmallCaps("create order"),
                hex(GRAY) + ItemUtil.toSmallCaps("create a new order")));

        p.closeInventory();
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            p.openInventory(inv);
            openInventoryType.put(p.getUniqueId(), InventoryType.MAIN_MENU);
        }, 1L);
    }

    // ==================== SHARED UI HELPERS ====================

    private void fillBottomBar(Inventory inv, int currentPage, int maxPage) {
        if (inv == null) return;
        ItemStack glass = createGlass();
        for (int i = 45; i < 54; i++) {
            inv.setItem(i, glass);
        }

        if (currentPage > 0) {
            inv.setItem(45, createButton(Material.ARROW,
                    hex(PRIMARY) + ItemUtil.toSmallCaps("previous page"),
                    hex(GRAY) + ItemUtil.toSmallCaps("go to page") + " " + currentPage));
        }

        inv.setItem(49, createButton(Material.COMPASS,
                hex(SECONDARY) + ItemUtil.toSmallCaps("main menu"),
                hex(GRAY) + ItemUtil.toSmallCaps("return to main menu")));

        inv.setItem(51, createButton(Material.SUNFLOWER,
                hex(GREEN) + ItemUtil.toSmallCaps("refresh"),
                hex(GRAY) + ItemUtil.toSmallCaps("refresh the list")));

        if (currentPage < maxPage) {
            inv.setItem(53, createButton(Material.ARROW,
                    hex(SECONDARY) + ItemUtil.toSmallCaps("next page"),
                    hex(GRAY) + ItemUtil.toSmallCaps("go to page") + " " + (currentPage + 2)));
        }
    }

    private void fillCreateOrderBar(Inventory inv, int page, int maxPage, boolean isSearch) {
        ItemStack glass = createGlass();
        for (int i = 45; i < 54; i++) {
            inv.setItem(i, glass);
        }

        if (page > 0) {
            inv.setItem(45, createButton(Material.ARROW,
                    hex(PRIMARY) + ItemUtil.toSmallCaps("previous page"),
                    hex(GRAY) + ItemUtil.toSmallCaps("go to page") + " " + page));
        }

        inv.setItem(47, createButton(Material.WRITABLE_BOOK,
                hex(ACCENT) + ItemUtil.toSmallCaps(isSearch ? "new search" : "search items"),
                hex(GRAY) + ItemUtil.toSmallCaps("search for specific items")));

        inv.setItem(49, createButton(Material.COMPASS,
                hex(SECONDARY) + ItemUtil.toSmallCaps("back to categories"),
                hex(GRAY) + ItemUtil.toSmallCaps("return to category selection")));

        inv.setItem(51, createButton(Material.SUNFLOWER,
                hex(GREEN) + ItemUtil.toSmallCaps("refresh"),
                hex(GRAY) + ItemUtil.toSmallCaps("refresh the list")));

        if (page < maxPage) {
            inv.setItem(53, createButton(Material.ARROW,
                    hex(SECONDARY) + ItemUtil.toSmallCaps("next page"),
                    hex(GRAY) + ItemUtil.toSmallCaps("go to page") + " " + (page + 2)));
        }
    }

    private ItemStack createGlass() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(" ");
            meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "glass_filler"), PersistentDataType.BYTE, (byte) 1);
            item.setItemMeta(meta);
        }
        return item;
    }

    private boolean isGlassFiller(ItemStack item) {
        if (item == null || item.getType() != Material.GRAY_STAINED_GLASS_PANE) return false;
        ItemMeta meta = item.getItemMeta();
        if (meta == null) return false;
        Byte flag = meta.getPersistentDataContainer().get(new NamespacedKey(plugin, "glass_filler"), PersistentDataType.BYTE);
        return flag != null && flag == (byte) 1;
    }

    private ItemStack createButton(Material material, String name, String... lore) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.setDisplayName(name);
            if (lore.length > 0) {
                meta.setLore(Arrays.asList(lore));
            }
        }
        item.setItemMeta(meta);
        return item;
    }

    private int getPageFromTitle(String title) {
        if (title == null) return 0;
        try {
            String cleanTitle = ChatColor.stripColor(title);
            if (cleanTitle == null) return 0;
            String[] parts = cleanTitle.split(" ");
            for (int i = 0; i < parts.length; i++) {
                if (parts[i].equalsIgnoreCase("page") && i + 1 < parts.length) {
                    try {
                        return Integer.parseInt(parts[i + 1]) - 1;
                    } catch (NumberFormatException ignored) {}
                }
            }
        } catch (Exception ignored) {}
        return 0;
    }

    private int getCurrentPage(Player p) {
        if (p == null) return 0;
        Integer cached = currentPageCache.get(p.getUniqueId());
        if (cached != null) return cached;
        try {
            String title = p.getOpenInventory().getTitle();
            if (title != null && !title.isEmpty()) {
                return getPageFromTitle(title);
            }
        } catch (Exception ignored) {}
        return 0;
    }

    // ==================== CLICK HANDLING ====================

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        UUID uuid = p.getUniqueId();
        long now = System.currentTimeMillis();

        if (lastClickTime.containsKey(uuid) && (now - lastClickTime.get(uuid)) < 150) {
            e.setCancelled(true);
            return;
        }

        InventoryType type = openInventoryType.get(uuid);
        if (type == null) return;

        e.setCancelled(true);
        lastClickTime.put(uuid, now);

        if (e.getRawSlot() >= 54 || e.getRawSlot() < 0) return;

        int slot = e.getRawSlot();
        ItemStack clicked = e.getCurrentItem();

        if (clicked == null || clicked.getType() == Material.AIR) return;
        if (isGlassFiller(clicked)) return;

        switch (type) {
            case ITEM_CATEGORY -> handleCategoryClick(p, slot);
            case CREATE_ORDER -> handleCreateOrderClick(p, slot);
            case ORDER_LIST -> handleOrderListClick(p, slot);
            case MY_ORDERS -> handleMyOrdersClick(p, slot);
            case CLAIM_ORDERS -> handleClaimOrdersClick(p, slot);
            case ORDER_DETAILS -> handleOrderDetailsClick(p, slot);
            case MAIN_MENU -> handleMainMenuClick(p, slot);
            case SEARCH_RESULTS -> handleSearchResultsClick(p, slot);
        }
    }

    private void handleCategoryClick(Player p, int slot) {
        if (slot == 4) {
            openSearchMenu(p);
            return;
        }

        if (slot == 53) {
            p.closeInventory();
            openInventoryType.remove(p.getUniqueId());
            p.sendMessage(hex(PRIMARY) + ItemUtil.toSmallCaps("order creation cancelled"));
            return;
        }

        if (slot >= 9 && slot < 45) {
            Inventory inv = p.getOpenInventory().getTopInventory();
            ItemStack clicked = inv.getItem(slot);
            if (clicked == null || clicked.getType() == Material.AIR) return;

            ItemMeta meta = clicked.getItemMeta();
            if (meta == null) return;

            String category = null;
            List<String> lore = meta.getLore();
            if (lore != null) {
                for (String line : lore) {
                    if (line.contains("CATEGORY:")) {
                        String cleanLine = ChatColor.stripColor(line);
                        int idx = cleanLine.indexOf("CATEGORY:");
                        if (idx >= 0) {
                            category = cleanLine.substring(idx + 9).trim();
                            break;
                        }
                    }
                }
            }

            if (category != null && !category.isEmpty()) {
                openItemsInCategory(p, category, 0);
            }
        }
    }

    private void handleCreateOrderClick(Player p, int slot) {
        if (slot == 45) {
            int page = getCurrentPage(p);
            String category = currentCategoryCache.get(p.getUniqueId());
            if (category != null && page > 0) openItemsInCategory(p, category, page - 1);
            return;
        }
        if (slot == 47) {
            openSearchMenu(p);
            return;
        }
        if (slot == 49) {
            currentCategoryCache.remove(p.getUniqueId());
            openCategoryMenu(p);
            return;
        }
        if (slot == 51) {
            String category = currentCategoryCache.get(p.getUniqueId());
            if (category != null) openItemsInCategory(p, category, getCurrentPage(p));
            return;
        }
        if (slot == 53) {
            String category = currentCategoryCache.get(p.getUniqueId());
            if (category != null) openItemsInCategory(p, category, getCurrentPage(p) + 1);
            return;
        }

        if (slot < 45) {
            Inventory inv = p.getOpenInventory().getTopInventory();
            ItemStack clicked = inv.getItem(slot);
            if (clicked == null || clicked.getType() == Material.AIR) return;
            promptAmount(p, clicked.getType());
        }
    }

    private void handleSearchResultsClick(Player p, int slot) {
        if (slot == 45) {
            int page = getCurrentPage(p);
            if (page > 0) openSearchResults(p, page - 1);
            return;
        }
        if (slot == 47) {
            openSearchMenu(p);
            return;
        }
        if (slot == 49) {
            searchQueryCache.remove(p.getUniqueId());
            searchResultsCache.remove(p.getUniqueId());
            openCategoryMenu(p);
            return;
        }
        if (slot == 51) {
            openSearchResults(p, getCurrentPage(p));
            return;
        }
        if (slot == 53) {
            openSearchResults(p, getCurrentPage(p) + 1);
            return;
        }

        if (slot < 45) {
            List<Material> results = searchResultsCache.get(p.getUniqueId());
            if (results == null) return;

            int page = getCurrentPage(p);
            int index = page * 45 + slot;
            if (index >= results.size()) return;

            promptAmount(p, results.get(index));
        }
    }

    private void promptAmount(Player p, Material material) {
        if (!ItemUtil.isAllowed(material)) {
            p.sendMessage(hex(PRIMARY) + ItemUtil.toSmallCaps("this item cannot be sold through the order system"));
            return;
        }

        int owned = countMaterialInInventory(p, material);
        if (owned <= 0) {
            p.sendMessage(hex(PRIMARY) + ItemUtil.toSmallCaps("you don't have any of that item"));
            return;
        }

        selectedMaterialCache.put(p.getUniqueId(), material);
        p.closeInventory();
        openInventoryType.remove(p.getUniqueId());
        p.sendMessage(hex(SECONDARY) + ItemUtil.toSmallCaps("enter the amount of") + " " + hex(WHITE)
                + ItemUtil.toSmallCaps(material.name().replace("_", " ")) + hex(SECONDARY) + ItemUtil.toSmallCaps(" you want to order"));
        p.sendMessage(hex(GRAY) + ItemUtil.toSmallCaps("you currently have") + " " + hex(WHITE) + owned);
        p.sendMessage(hex(GRAY) + ItemUtil.toSmallCaps("examples") + ": 64, 1k, 1m, 1b");
        p.sendMessage(hex(GRAY) + ItemUtil.toSmallCaps("type 'cancel' to abort"));
        chatCache.put(p.getUniqueId(), "ORDER_AMOUNT");
    }

    private int countMaterialInInventory(Player p, Material material) {
        int total = 0;
        for (ItemStack stack : p.getInventory().getStorageContents()) {
            if (stack != null && stack.getType() == material) {
                total += stack.getAmount();
            }
        }
        return total;
    }

    private ItemStack takeFromInventory(Player p, Material material, int amount) {
        if (countMaterialInInventory(p, material) < amount) return null;

        ItemStack template = null;
        int remaining = amount;
        ItemStack[] contents = p.getInventory().getStorageContents();

        for (int i = 0; i < contents.length && remaining > 0; i++) {
            ItemStack stack = contents[i];
            if (stack == null || stack.getType() != material) continue;
            if (template == null) template = stack.clone();

            int take = Math.min(stack.getAmount(), remaining);
            remaining -= take;
            if (stack.getAmount() - take <= 0) {
                p.getInventory().setItem(i, null);
            } else {
                stack.setAmount(stack.getAmount() - take);
                p.getInventory().setItem(i, stack);
            }
        }

        if (template == null) return null;
        template.setAmount(amount);
        p.updateInventory();
        return template;
    }

    private void handleOrderListClick(Player p, int slot) {
        if (slot >= 45) {
            handleBottomBar(p, slot, InventoryType.ORDER_LIST);
            return;
        }
        int page = getCurrentPage(p);
        List<OrderManager.OrderData> orders = plugin.getOrderManager().getActiveOrders();
        int index = page * 45 + slot;
        if (index >= orders.size()) return;
        openOrderDetails(p, orders.get(index));
    }

    private void handleMyOrdersClick(Player p, int slot) {
        if (slot >= 45) {
            handleBottomBar(p, slot, InventoryType.MY_ORDERS);
            return;
        }
        int page = getCurrentPage(p);
        List<OrderManager.OrderData> orders = plugin.getOrderManager().getPlayerOrders(p.getUniqueId());
        int index = page * 45 + slot;
        if (index >= orders.size()) return;

        OrderManager.OrderData order = orders.get(index);

        // Check if order is completed - if so, allow claiming
        if (order.status() == OrderManager.OrderStatus.COMPLETED) {
            claimCompletedOrder(p, order);
            return;
        }

        // If delivered amount >= required amount, complete it
        if (order.deliveredAmount() >= order.amount()) {
            p.sendMessage(hex(GREEN) + "This order is already complete! Use /orders claim to collect.");
            return;
        }

        // Allow delivery
        handleDeliverItems(p, order);
    }

    private void handleDeliverItems(Player p, OrderManager.OrderData order) {
        if (order == null) return;

        int remaining = order.amount() - order.deliveredAmount();
        if (remaining <= 0) {
            p.sendMessage(hex(PRIMARY) +
