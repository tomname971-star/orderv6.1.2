package org.order.order.util;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.EnumSet;
import java.util.Set;

public class ItemUtil {

    /**
     * Materials that must never be listed / sold through the order system,
     * either because they are creative-only, admin/utility blocks, or could
     * otherwise be abused (spawners, command blocks, structure blocks, etc).
     * This is a blacklist enforced both in the GUI item lists and again as a
     * hard server-side check right before any order is actually created, so
     * it cannot be bypassed by feeding the plugin a crafted ItemStack.
     */
    private static final Set<Material> DISALLOWED = EnumSet.noneOf(Material.class);

    static {
        for (Material m : Material.values()) {
            String name = m.name();
            if (m.isAir()) continue;
            if (name.contains("LEGACY")) continue; // legacy materials aren't real items
            if (name.equals("COMMAND_BLOCK") || name.equals("CHAIN_COMMAND_BLOCK")
                    || name.equals("REPEATING_COMMAND_BLOCK") || name.equals("COMMAND_BLOCK_MINECART")) {
                DISALLOWED.add(m);
            } else if (name.equals("BARRIER") || name.equals("STRUCTURE_BLOCK")
                    || name.equals("STRUCTURE_VOID") || name.equals("JIGSAW")
                    || name.equals("DEBUG_STICK") || name.equals("KNOWLEDGE_BOOK")
                    || name.equals("BEDROCK") || name.equals("SPAWNER")
                    || name.equals("END_PORTAL_FRAME") || name.equals("LIGHT")) {
                DISALLOWED.add(m);
            } else if (name.contains("SPAWN_EGG")) {
                DISALLOWED.add(m);
            } else if (name.contains("DEBUG")) {
                DISALLOWED.add(m);
            }
        }
    }

    /** Whether this material is a legitimate, listable survival item. */
    public static boolean isAllowed(Material material) {
        if (material == null) return false;
        if (material.isAir()) return false;
        if (!material.isItem()) return false;
        return !DISALLOWED.contains(material);
    }

    public static String serialize(ItemStack item) {
        if (item == null) return "";

        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
             BukkitObjectOutputStream dataStream = new BukkitObjectOutputStream(outputStream)) {

            dataStream.writeObject(item);
            return Base64.getEncoder().withoutPadding().encodeToString(outputStream.toByteArray());

        } catch (Exception e) {
            e.printStackTrace();
            return "";
        }
    }

    public static ItemStack deserialize(String data) {
        if (data == null || data.isEmpty()) return null;

        try (ByteArrayInputStream inputStream = new ByteArrayInputStream(Base64.getDecoder().decode(data));
             BukkitObjectInputStream dataStream = new BukkitObjectInputStream(inputStream)) {

            return (ItemStack) dataStream.readObject();

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static String toSmallCaps(String input) {
        if (input == null || input.isEmpty()) return "";

        String normal = "abcdefghijklmnopqrstuvwxyz0123456789";
        String small  = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀsᴛᴜᴠᴡxʏᴢ⁰¹²³⁴⁵⁶⁷⁸⁹";

        StringBuilder sb = new StringBuilder(input.length());

        for (char c : input.toLowerCase().toCharArray()) {
            int idx = normal.indexOf(c);
            if (idx != -1) {
                sb.append(small.charAt(idx));
            } else if (c == '_' || c == '-') {
                sb.append(' ');
            } else if (c == ' ') {
                sb.append(' ');
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    public static String formatPrice(double price) {
        if (price >= 1_000_000_000) {
            return String.format("%.2f", price / 1_000_000_000) + "B";
        }
        if (price >= 1_000_000) {
            return String.format("%.2f", price / 1_000_000) + "M";
        }
        if (price >= 1_000) {
            return String.format("%.1f", price / 1_000) + "K";
        }

        if (price == (long) price) {
            return String.format("%d", (long) price);
        }
        return String.format("%.2f", price);
    }

    public static double parsePrice(String input) throws NumberFormatException {
        if (input == null || input.isEmpty()) {
            throw new NumberFormatException("Empty price");
        }

        input = input.trim().toLowerCase();
        input = input.replace(",", "").replace(" ", "");

        char lastChar = input.charAt(input.length() - 1);

        if (!Character.isLetter(lastChar)) {
            return Double.parseDouble(input);
        }

        String numberPart = input.substring(0, input.length() - 1);
        double baseValue;
        try {
            baseValue = Double.parseDouble(numberPart);
        } catch (NumberFormatException e) {
            throw new NumberFormatException("Invalid number: " + numberPart);
        }

        return switch (lastChar) {
            case 'k' -> baseValue * 1_000;
            case 'm' -> baseValue * 1_000_000;
            case 'b' -> baseValue * 1_000_000_000;
            default -> throw new NumberFormatException("Unknown suffix: " + lastChar);
        };
    }

    public static String getCleanName(ItemStack item) {
        if (item == null) return "Unknown";

        ItemMeta meta = item.getItemMeta();
        if (meta != null && meta.hasDisplayName()) {
            return ChatColor.stripColor(meta.getDisplayName());
        }
        return toSmallCaps(item.getType().name().replace("_", " "));
    }
}
