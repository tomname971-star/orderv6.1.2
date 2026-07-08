// In der OrderData-Erstellung in der Methode promptAmount():

// GEÄNDERT: Speichert jetzt auch die Menge
orderCache.put(playerUuid, new OrderManager.OrderData(
    -1, 
    new ItemStack(material, amount), 
    player.getName(), 
    playerUuid, 
    0, 
    amount,  // NEU: Menge
    0,       // NEU: deliveredAmount
    System.currentTimeMillis(), 
    OrderManager.OrderStatus.ACTIVE
));

// In promptPrice():

// GEÄNDERT: addOrder nimmt jetzt amount als Parameter
plugin.getOrderManager().addOrder(
    realItem, 
    player.getName(), 
    playerUuid, 
    price, 
    pending.amount()  // NEU: Menge
);

// NEUE Methode für Teillieferung (wird in handleMyOrdersClick aufgerufen):

private void handleDeliverItems(Player p, OrderManager.OrderData order) {
    if (order == null) return;
    
    int remaining = order.amount() - order.deliveredAmount();
    if (remaining <= 0) {
        p.sendMessage(ChatColor.RED + "This order is already complete.");
        return;
    }
    
    // Hole Items aus Inventar
    int amount = 0;
    ItemStack template = null;
    for (ItemStack item : p.getInventory().getContents()) {
        if (item != null && item.getType() == order.item().getType()) {
            if (template == null) template = item.clone();
            amount += item.getAmount();
        }
    }
    
    if (amount <= 0) {
        p.sendMessage(ChatColor.RED + "You don't have any of this item.");
        return;
    }
    
    int deliverAmount = Math.min(amount, remaining);
    
    // Items aus Inventar nehmen
    int toRemove = deliverAmount;
    for (ItemStack item : p.getInventory().getContents()) {
        if (item == null || item.getType() != order.item().getType()) continue;
        if (toRemove <= 0) break;
        
        int take = Math.min(item.getAmount(), toRemove);
        item.setAmount(item.getAmount() - take);
        toRemove -= take;
        if (item.getAmount() <= 0) {
            p.getInventory().removeItem(item);
        }
    }
    
    plugin.getOrderManager().deliverToOrder(p, order, template);
    p.sendMessage(ChatColor.GREEN + "You delivered " + deliverAmount + " " + 
        ItemUtil.toSmallCaps(order.item().getType().name().replace("_", " ")) + "!");
    
    // GUI aktualisieren
    openMyOrders(p, 0);
}
