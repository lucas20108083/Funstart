package moe.hinakusoft.funstart.listener;

import moe.hinakusoft.funstart.FunstartPlugin;
import moe.hinakusoft.funstart.model.CustomEnchantment;
import moe.hinakusoft.funstart.model.PlayerData;
import org.bukkit.*;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 附魔 GUI 监听器。
 * <p>
 * 提供 5 种材料等级（青金石→下界之星）的附魔界面，
 * 支持 7 个分类的原版附魔 + 8 种自定义附魔。
 * 使用随机偏移成功率系统。所有随机数改用 ThreadLocalRandom
 * 以避免 Random 实例的分配与竞争开销。
 */
public class EnchantGuiListener implements Listener {

    public enum EnchantMaterial {
        LAPIS(Material.LAPIS_LAZULI, "§b青金石", 1, 3, 8.0, 4, false),
        AMETHYST(Material.AMETHYST_SHARD, "§d紫水晶", 2, 5, 12.0, 3, true),
        BLAZE_ROD(Material.BLAZE_ROD, "§6烈焰棒", 3, 6, 16.0, 2, true),
        ENDER_PEARL(Material.ENDER_PEARL, "§a末影珍珠", 4, 7, 20.0, 1, true),
        NETHER_STAR(Material.NETHER_STAR, "§c下界之星", 6, 10, 30.0, 1, true);

        final Material item;
        final String displayName;
        final int minLevel;
        final int maxLevel;
        final double pointCostPerLevel;
        final int materialCostPerLevel;
        final boolean hasCustomEnchants;

        EnchantMaterial(Material item, String displayName, int minLevel, int maxLevel,
                        double pointCostPerLevel, int materialCostPerLevel, boolean hasCustomEnchants) {
            this.item = item;
            this.displayName = displayName;
            this.minLevel = minLevel;
            this.maxLevel = maxLevel;
            this.pointCostPerLevel = pointCostPerLevel;
            this.materialCostPerLevel = materialCostPerLevel;
            this.hasCustomEnchants = hasCustomEnchants;
        }
    }

    private static final int PER_PAGE = 45;
    private static final NamespacedKey ENCH_KEY = new NamespacedKey("funstart", "ench_key");
    private static final NamespacedKey CUSTOM_KEY = new NamespacedKey("funstart", "cust_key");
    private static final double[] NETHER_RATES = {100, 90, 80, 70, 60};
    private static final String LORE_SEP = "§7§m--------------------------";

    private record Category(String name, Material icon, List<String> enchants) {}
    private static final List<Category> CATEGORIES = List.of(
        new Category("§f剑", Material.DIAMOND_SWORD, List.of("sharpness","smite","bane_of_arthropods","knockback","fire_aspect","looting","sweeping_edge")),
        new Category("§f工具", Material.DIAMOND_PICKAXE, List.of("efficiency","silk_touch","fortune","unbreaking")),
        new Category("§f弓", Material.BOW, List.of("power","punch","flame","infinity")),
        new Category("§f弩", Material.CROSSBOW, List.of("multishot","quick_charge","piercing")),
        new Category("§f三叉戟/重锤", Material.TRIDENT, List.of("loyalty","impaling","riptide","channeling","density","breach","wind_burst")),
        new Category("§f盔甲", Material.DIAMOND_CHESTPLATE, List.of("protection","fire_protection","blast_protection","projectile_protection","feather_falling","respiration","aqua_affinity","thorns","depth_strider","frost_walker")),
        new Category("§f通用", Material.ENCHANTED_BOOK, List.of("mending","soul_speed","swift_sneak","luck_of_the_sea","lure"))
    );

    private final FunstartPlugin plugin;

    public EnchantGuiListener(FunstartPlugin plugin) {
        this.plugin = plugin;
    }

    // ========== Material Selection GUI ==========

    public static void openMaterialSelection(Player player, FunstartPlugin plugin) {
        Inventory inv = Bukkit.createInventory(new MaterialHolder(player), 54, "§5选择注魔材料");
        int[] slots = {20, 21, 22, 23, 24};
        for (int i = 0; i < EnchantMaterial.values().length; i++) {
            EnchantMaterial mat = EnchantMaterial.values()[i];
            boolean has = player.getInventory().contains(mat.item, 1);
            ItemStack is = new ItemStack(has ? mat.item : Material.BARRIER);
            ItemMeta meta = is.getItemMeta();
            meta.setDisplayName((has ? "§a" : "§c") + mat.displayName);
            List<String> lore = new ArrayList<>();
            if (has) {
                lore.add("§7等级范围: §e" + mat.minLevel + "§7-§e" + mat.maxLevel);
                lore.add(String.format("§7点数消耗: §b%.0f§7/级", mat.pointCostPerLevel));
                lore.add("§7材料消耗: §e" + mat.materialCostPerLevel + "§7/级");
                if (mat.hasCustomEnchants) lore.add("§d附带自定义附魔");
                lore.add("§e点击选择");
            } else {
                lore.add("§c你没有该材料");
            }
            meta.setLore(lore);
            is.setItemMeta(meta);
            inv.setItem(slots[i], is);
        }
        inv.setItem(49, makeSimple(Material.BARRIER, "§c关闭"));
        player.openInventory(inv);
    }

    // ========== Enchant GUI ==========

    public static void openEnchantGUI(Player player, FunstartPlugin plugin, EnchantMaterial material, int page) {
        List<ItemStack> allItems = buildEnchantItems(player, plugin, material);
        int totalPages = Math.max(1, (int) Math.ceil((double) allItems.size() / PER_PAGE));
        page = Math.max(0, Math.min(page, totalPages - 1));

        Inventory inv = Bukkit.createInventory(new EnchantHolder(player, material, page), 54,
            "§5" + material.displayName + " §8(" + (page + 1) + "/" + totalPages + ")");

        int start = page * PER_PAGE;
        int end = Math.min(start + PER_PAGE, allItems.size());
        for (int i = start; i < end; i++) {
            inv.setItem(i - start, allItems.get(i));
        }

        // Bottom bar
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held != null && !held.getType().isAir()) {
            ItemStack display = held.clone();
            ItemMeta dm = display.getItemMeta();
            List<String> dl = dm.hasLore() ? new ArrayList<>(dm.getLore()) : new ArrayList<>();
            dl.add(LORE_SEP);
            dl.add("§7点击附魔不会关闭界面");
            dm.setLore(dl);
            display.setItemMeta(dm);
            inv.setItem(45, display);
        }

        if (page > 0) inv.setItem(48, makeSimple(Material.ARROW, "§a上一页"));
        inv.setItem(49, makeSimple(Material.BARRIER, "§c关闭"));
        if (page < totalPages - 1) inv.setItem(50, makeSimple(Material.ARROW, "§a下一页"));
        inv.setItem(53, makeSimple(Material.STRUCTURE_VOID, "§e返回材料选择"));
        player.openInventory(inv);
    }

    private static List<ItemStack> buildEnchantItems(Player player, FunstartPlugin plugin, EnchantMaterial material) {
        List<ItemStack> items = new ArrayList<>();
        ItemStack held = player.getInventory().getItemInMainHand();

        for (Category cat : CATEGORIES) {
            ItemStack header = new ItemStack(cat.icon());
            ItemMeta hMeta = header.getItemMeta();
            hMeta.setDisplayName(cat.name());
            hMeta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            header.setItemMeta(hMeta);
            items.add(header);

            for (String enchKey : cat.enchants()) {
                Enchantment ench = Registry.ENCHANTMENT.get(NamespacedKey.fromString("minecraft:" + enchKey));
                if (ench == null || ench.isCursed()) continue;
                items.add(makeEnchantItem(held, ench, material));
            }
        }

        if (material.hasCustomEnchants) {
            ItemStack cHeader = new ItemStack(Material.NETHER_STAR);
            ItemMeta chm = cHeader.getItemMeta();
            chm.setDisplayName("§5§l自定义附魔");
            cHeader.setItemMeta(chm);
            items.add(cHeader);

            for (CustomEnchantment ce : CustomEnchantment.GUI_VALUES) {
                items.add(makeCustomItem(held, ce, material, plugin));
            }
        }

        return items;
    }

    private static ItemStack makeEnchantItem(ItemStack held, Enchantment ench, EnchantMaterial material) {
        ItemStack is = new ItemStack(Material.ENCHANTED_BOOK);
        ItemMeta meta = is.getItemMeta();
        meta.setDisplayName("§d" + getCN(ench));
        meta.getPersistentDataContainer().set(ENCH_KEY, PersistentDataType.STRING, ench.getKey().getKey());

        int currentLevel = (held != null && !held.getType().isAir()) ? held.getEnchantmentLevel(ench) : 0;
        int targetLevel = currentLevel > 0 ? currentLevel + 1 : material.minLevel;
        if (targetLevel < material.minLevel) targetLevel = material.minLevel;
        if (targetLevel > material.maxLevel) targetLevel = material.maxLevel;

        List<String> lore = new ArrayList<>();
        lore.add("§7当前: " + (currentLevel > 0 ? "§e" + toRoman(currentLevel) : "§c无"));

        if (currentLevel >= material.maxLevel || targetLevel > material.maxLevel) {
            lore.add("§c已达此材料最高等级");
        } else if (currentLevel >= targetLevel && currentLevel > 0) {
            lore.add("§e点击升级到 " + toRoman(targetLevel));
            addCostLore(lore, material, targetLevel);
        } else {
            lore.add("§7目标: §e" + toRoman(targetLevel));
            addCostLore(lore, material, targetLevel);
        }
        meta.setLore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        is.setItemMeta(meta);
        return is;
    }

    private static ItemStack makeCustomItem(ItemStack held, CustomEnchantment ce, EnchantMaterial material, FunstartPlugin plugin) {
        ItemStack is = new ItemStack(Material.ENCHANTED_BOOK);
        ItemMeta meta = is.getItemMeta();
        meta.setDisplayName("§5" + ce.getDisplayName());
        meta.getPersistentDataContainer().set(CUSTOM_KEY, PersistentDataType.STRING, ce.getKey());

        int currentLevel = getCustomLevel(held, plugin, ce);
        int targetLevel = currentLevel > 0 ? currentLevel + 1 : material.minLevel;
        if (targetLevel < material.minLevel) targetLevel = material.minLevel;
        if (targetLevel > material.maxLevel) targetLevel = material.maxLevel;

        List<String> lore = new ArrayList<>();
        lore.add("§7" + ce.getDescription());
        String reqLine = getRequirementLine(ce, held);
        if (!reqLine.isEmpty()) lore.add(reqLine);
        lore.add("§7当前: " + (currentLevel > 0 ? "§e" + toRoman(currentLevel) : "§c无"));

        if (currentLevel >= material.maxLevel || targetLevel > material.maxLevel) {
            lore.add("§c已达此材料最高等级");
        } else if (!canApplyToItemStatic(held, ce)) {
            lore.add("§c当前物品不满足附魔条件");
        } else {
            lore.add("§7目标: §e" + toRoman(targetLevel));
            addCostLore(lore, material, targetLevel);
        }
        meta.setLore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        is.setItemMeta(meta);
        return is;
    }

    private static String getRequirementLine(CustomEnchantment ce, ItemStack held) {
        return switch (ce) {
            case EXPLOSIVE_THROW -> "§7适用: 弓/弩/三叉戟/雪球";
            case DENSE_SHOT -> "§7需求: 弩 + 多重射击";
            case MINER_AGILITY -> "§7适用: 镐";
            default -> "";
        };
    }

    private static boolean canApplyToItemStatic(ItemStack item, CustomEnchantment ce) {
        if (item == null || item.getType().isAir()) return true;
        Material type = item.getType();
        return switch (ce) {
            case EXPLOSIVE_THROW ->
                    type == Material.BOW || type == Material.CROSSBOW || type == Material.TRIDENT || type == Material.SNOWBALL;
            case DENSE_SHOT -> type == Material.CROSSBOW && item.containsEnchantment(Enchantment.MULTISHOT);
            case MINER_AGILITY -> type.name().contains("PICKAXE");
            default -> true;
        };
    }

    private static void addCostLore(List<String> lore, EnchantMaterial material, int level) {
        double points = level * material.pointCostPerLevel;
        int matCost = level * material.materialCostPerLevel;
        double rate = getBaseSuccessRate(material, level);
        lore.add(String.format("§7消耗: §b%.0f §7点 + §e%d §7%s", points, matCost, getMatName(material)));
        lore.add(String.format("§7成功率: %s%.0f%% §7(随机偏移±5~7)", getRateColor(rate), rate));
        lore.add("§e点击附魔");
    }

    // ========== Event Handlers ==========

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (event.getInventory().getHolder() instanceof MaterialHolder mh) {
            event.setCancelled(true);
            if (!mh.getPlayer().equals(event.getWhoClicked())) return;
            Player player = (Player) event.getWhoClicked();
            int slot = event.getRawSlot();
            if (slot >= 20 && slot <= 24) {
                int idx = slot - 20;
                if (idx < EnchantMaterial.values().length) {
                    EnchantMaterial mat = EnchantMaterial.values()[idx];
                    if (player.getInventory().contains(mat.item, 1)) {
                        openEnchantGUI(player, plugin, mat, 0);
                    } else {
                        player.sendMessage("§c你没有该材料");
                    }
                }
            } else if (slot == 49) {
                player.closeInventory();
            }
            return;
        }

        if (event.getInventory().getHolder() instanceof EnchantHolder eh) {
            event.setCancelled(true);
            if (!eh.getPlayer().equals(event.getWhoClicked())) return;
            Player player = (Player) event.getWhoClicked();
            int slot = event.getRawSlot();
            if (slot < 0 || slot >= 54) return;

            if (slot == 48) { openEnchantGUI(player, plugin, eh.getMaterial(), eh.getPage() - 1); return; }
            if (slot == 49) { player.closeInventory(); return; }
            if (slot == 50) { openEnchantGUI(player, plugin, eh.getMaterial(), eh.getPage() + 1); return; }
            if (slot == 53) { openMaterialSelection(player, plugin); return; }
            if (slot >= 45) return;

            ItemStack clicked = event.getCurrentItem();
            if (clicked == null || !clicked.hasItemMeta()) return;
            var pdc = clicked.getItemMeta().getPersistentDataContainer();

            String enchKey = pdc.get(ENCH_KEY, PersistentDataType.STRING);
            if (enchKey != null) {
                applyEnchant(player, eh.getMaterial(), enchKey, eh.getPage());
                return;
            }

            String custKey = pdc.get(CUSTOM_KEY, PersistentDataType.STRING);
            if (custKey != null) {
                for (CustomEnchantment ce : CustomEnchantment.GUI_VALUES) {
                    if (ce.getKey().equals(custKey)) {
                        applyCustomEnchant(player, eh.getMaterial(), ce, eh.getPage());
                        return;
                    }
                }
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof MaterialHolder
            || event.getInventory().getHolder() instanceof EnchantHolder) {
            event.setCancelled(true);
        }
    }

    // ========== Apply Regular Enchant ==========

    private void applyEnchant(Player player, EnchantMaterial material, String enchKey, int page) {
        Enchantment ench = Registry.ENCHANTMENT.get(NamespacedKey.fromString("minecraft:" + enchKey));
        if (ench == null) return;

        ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null || item.getType().isAir()) {
            player.sendMessage("§c请手持要附魔的物品");
            return;
        }

        int currentLevel = item.getEnchantmentLevel(ench);
        int targetLevel = currentLevel > 0 ? currentLevel + 1 : material.minLevel;
        if (targetLevel < material.minLevel) targetLevel = material.minLevel;
        if (targetLevel > material.maxLevel) {
            player.sendMessage("§c该附魔已达此材料最高等级");
            return;
        }
        if (currentLevel >= targetLevel && currentLevel > 0) {
            player.sendMessage("§c该附魔等级不低于目标等级");
            return;
        }

        if (!deductCost(player, material, targetLevel)) return;

        double baseRate = getBaseSuccessRate(material, targetLevel);
        double actualRate = baseRate + (ThreadLocalRandom.current().nextDouble() * 12 - 5);
        boolean success = ThreadLocalRandom.current().nextDouble() * 100 < actualRate;

        String name = getCN(ench);
        String roman = toRoman(targetLevel);

        if (success) {
            item.addUnsafeEnchantment(ench, targetLevel);
            player.sendMessage(String.format("§e[Funstart] §a附魔成功! §d%s §e%s", name, roman));
            playEffects(player, true);
        } else {
            player.sendMessage(String.format("§e[Funstart] §c附魔失败! §7%s §e%s §7(实际成功率: §c%.1f%%§7), 材料已消耗", name, roman, actualRate));
            playEffects(player, false);
        }
        openEnchantGUI(player, plugin, material, page);
        player.sendMessage(String.format("  §7基础成功率: §e%.0f%% §7| 实际: %s%.1f%%", baseRate, success ? "§a" : "§c", actualRate));
        player.sendMessage("  §7消耗: §b" + (int)(targetLevel * material.pointCostPerLevel) + " §7点 + §e" + (targetLevel * material.materialCostPerLevel) + " §7" + getMatName(material) + " | 剩余: §b" + PlayerData.fmt(plugin.getPlayerDataManager().getPlayerData(player).getPoints()) + " §7点");
    }

    // ========== Apply Custom Enchant ==========

    private void applyCustomEnchant(Player player, EnchantMaterial material, CustomEnchantment ce, int page) {
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item == null || item.getType().isAir()) {
            player.sendMessage("§c请手持要附魔的物品");
            return;
        }

        int currentLevel = getCustomLevel(item, plugin, ce);
        if (!canApplyToItem(item, ce, player)) return;
        int targetLevel = currentLevel > 0 ? currentLevel + 1 : material.minLevel;
        if (targetLevel < material.minLevel) targetLevel = material.minLevel;
        if (targetLevel > material.maxLevel) {
            player.sendMessage("§c该自定义附魔已达此材料最高等级");
            return;
        }
        if (currentLevel >= targetLevel && currentLevel > 0) {
            player.sendMessage("§c该自定义附魔等级不低于目标等级");
            return;
        }

        if (!deductCost(player, material, targetLevel)) return;

        double baseRate = getBaseSuccessRate(material, targetLevel);
        double actualRate = baseRate + (ThreadLocalRandom.current().nextDouble() * 12 - 5);
        boolean success = ThreadLocalRandom.current().nextDouble() * 100 < actualRate;

        if (success) {
            setCustomLevel(item, plugin, ce, targetLevel);
            player.sendMessage(String.format("§e[Funstart] §a自定义附魔成功! §5%s §e%s", ce.getDisplayName(), toRoman(targetLevel)));
            playEffects(player, true);
        } else {
            player.sendMessage(String.format("§e[Funstart] §c自定义附魔失败! §7%s §e%s §7(实际成功率: §c%.1f%%§7), 材料已消耗", ce.getDisplayName(), toRoman(targetLevel), actualRate));
            playEffects(player, false);
        }
        openEnchantGUI(player, plugin, material, page);
        player.sendMessage(String.format("  §7基础成功率: §e%.0f%% §7| 实际: %s%.1f%%", baseRate, success ? "§a" : "§c", actualRate));
        player.sendMessage("  §7消耗: §b" + (int)(targetLevel * material.pointCostPerLevel) + " §7点 + §e" + (targetLevel * material.materialCostPerLevel) + " §7" + getMatName(material) + " | 剩余: §b" + PlayerData.fmt(plugin.getPlayerDataManager().getPlayerData(player).getPoints()) + " §7点");
    }

    // ========== Cost Deduction ==========

    private boolean deductCost(Player player, EnchantMaterial material, int level) {
        double pointCost = level * material.pointCostPerLevel;
        int matCost = level * material.materialCostPerLevel;
        PlayerData data = plugin.getPlayerDataManager().getPlayerData(player);
        if (data.getPoints() < pointCost) {
            player.sendMessage("§c点数不足! 需要 §e" + PlayerData.fmt(pointCost) + " §c点");
            return false;
        }
        if (!player.getInventory().contains(material.item, matCost)) {
            player.sendMessage(String.format("§c材料不足! 需要 §e%d §c个%s", matCost, getMatName(material)));
            return false;
        }
        data.deductPoints(pointCost);
        removeItems(player, material.item, matCost);
        PlayerData jd = plugin.getPlayerDataManager().getPlayerData(player);
        player.sendMessage(String.format("§7已消耗 §b%.0f §7点 + §e%d §7%s", pointCost, matCost, getMatName(material)));
        return true;
    }

    // ========== Success Rate ==========

    private static double getBaseSuccessRate(EnchantMaterial material, int level) {
        return switch (material) {
            case LAPIS -> Math.max(10, 60 - (level - 1) * 10);
            case AMETHYST -> Math.max(10, 50 - (level - 2) * 8);
            case BLAZE_ROD -> Math.max(10, 40 - (level - 3) * 6);
            case ENDER_PEARL -> Math.max(10, 30 - (level - 4) * 5);
            case NETHER_STAR -> {
                int idx = level - 6;
                if (idx >= 0 && idx < NETHER_RATES.length) yield NETHER_RATES[idx];
                yield Math.max(10, 100 - (level - 6) * 10);
            }
        };
    }

    private boolean canApplyToItem(ItemStack item, CustomEnchantment ce, Player player) {
        Material type = item.getType();
        switch (ce) {
            case EXPLOSIVE_THROW:
                if (type != Material.BOW && type != Material.CROSSBOW && type != Material.TRIDENT && type != Material.SNOWBALL) {
                    player.sendMessage("§c爆裂投掷只能附魔在 弓/弩/三叉戟/雪球 上");
                    return false;
                }
                return true;
            case DENSE_SHOT:
                if (type != Material.CROSSBOW) {
                    player.sendMessage("§c密集射击只能附魔在弩上");
                    return false;
                }
                if (!item.containsEnchantment(Enchantment.MULTISHOT)) {
                    player.sendMessage("§c密集射击需要弩拥有多重射击附魔");
                    return false;
                }
                return true;
            case MINER_AGILITY:
                if (!type.name().contains("PICKAXE")) {
                    player.sendMessage("§c矿工之敏只能附魔在镐上");
                    return false;
                }
                return true;
            default:
                return true;
        }
    }

    // ========== PDC / Lore for Custom Enchants ==========

    private static NamespacedKey ceKey(FunstartPlugin plugin, CustomEnchantment ce) {
        return new NamespacedKey(plugin, "ce_" + ce.getKey());
    }

    public static int getCustomLevel(ItemStack item, FunstartPlugin plugin, CustomEnchantment ce) {
        if (item == null || !item.hasItemMeta()) return 0;
        return item.getItemMeta().getPersistentDataContainer()
            .getOrDefault(ceKey(plugin, ce), PersistentDataType.INTEGER, 0);
    }

    public static void setCustomLevel(ItemStack item, FunstartPlugin plugin, CustomEnchantment ce, int level) {
        ItemMeta meta = item.getItemMeta();
        if (level <= 0) {
            meta.getPersistentDataContainer().remove(ceKey(plugin, ce));
        } else {
            meta.getPersistentDataContainer().set(ceKey(plugin, ce), PersistentDataType.INTEGER, level);
        }
        item.setItemMeta(meta);
        updateCELore(item, plugin);
    }

    public static void updateCELore(ItemStack item, FunstartPlugin plugin) {
        if (item == null || !item.hasItemMeta()) return;
        ItemMeta meta = item.getItemMeta();
        List<String> lore = meta.hasLore() ? new ArrayList<>(meta.getLore()) : new ArrayList<>();

        // Remove existing custom enchant lines
        lore.removeIf(line -> {
            for (CustomEnchantment ce : CustomEnchantment.VALUES) {
                if (line.contains(ce.getDisplayName())) return true;
            }
            return line.equals(LORE_SEP);
        });

        // Add current custom enchants
        List<String> ceLines = new ArrayList<>();
        for (CustomEnchantment ce : CustomEnchantment.VALUES) {
            int lvl = getCustomLevel(item, plugin, ce);
            if (lvl > 0) {
                ceLines.add("§7§d" + ce.getDisplayName() + " " + toRoman(lvl));
            }
        }
        if (!ceLines.isEmpty()) {
            lore.add(LORE_SEP);
            lore.addAll(ceLines);
        }

        meta.setLore(lore);
        item.setItemMeta(meta);
    }

    // ========== Helpers ==========

    private static void removeItems(Player player, Material material, int amount) {
        for (ItemStack item : player.getInventory().getContents()) {
            if (item == null || item.getType() != material) continue;
            int toRemove = Math.min(item.getAmount(), amount);
            item.setAmount(item.getAmount() - toRemove);
            amount -= toRemove;
            if (amount <= 0) break;
        }
    }

    private static ItemStack makeSimple(Material mat, String name) {
        ItemStack is = new ItemStack(mat);
        ItemMeta meta = is.getItemMeta();
        meta.setDisplayName(name);
        is.setItemMeta(meta);
        return is;
    }

    private static String getMatName(EnchantMaterial mat) {
        return switch (mat) {
            case LAPIS -> "青金石";
            case AMETHYST -> "紫水晶";
            case BLAZE_ROD -> "烈焰棒";
            case ENDER_PEARL -> "末影珍珠";
            case NETHER_STAR -> "下界之星";
        };
    }

    private static String getRateColor(double rate) {
        if (rate >= 70) return "§a";
        if (rate >= 40) return "§e";
        if (rate >= 20) return "§6";
        return "§c";
    }

    private static void playEffects(Player player, boolean success) {
        try {
            if (success) {
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 1.0f, 1.0f);
                player.getWorld().spawnParticle(Particle.ENCHANT, player.getLocation().add(0, 1, 0), 30, 0.5, 0.5, 0.5, 0.5);
            } else {
                player.playSound(player.getLocation(), Sound.BLOCK_NOTE_BLOCK_BASS, 1.0f, 0.5f);
                player.getWorld().spawnParticle(Particle.SMOKE, player.getLocation().add(0, 1, 0), 20, 0.5, 0.5, 0.5, 0.05);
            }
        } catch (Exception ignored) {}
    }

    // ========== Holders ==========

    public static class MaterialHolder implements InventoryHolder {
        private final Player player;
        public MaterialHolder(Player player) { this.player = player; }
        public Player getPlayer() { return player; }
        @Override public Inventory getInventory() { return null; }
    }

    public static class EnchantHolder implements InventoryHolder {
        private final Player player;
        private final EnchantMaterial material;
        private final int page;
        public EnchantHolder(Player player, EnchantMaterial material, int page) {
            this.player = player; this.material = material; this.page = page;
        }
        public Player getPlayer() { return player; }
        public EnchantMaterial getMaterial() { return material; }
        public int getPage() { return page; }
        @Override public Inventory getInventory() { return null; }
    }

    // ========== Chinese Names & Roman ==========

    private static String getCN(Enchantment ench) {
        return switch (ench.getKey().getKey()) {
            case "protection" -> "保护"; case "fire_protection" -> "火焰保护";
            case "feather_falling" -> "摔落保护"; case "blast_protection" -> "爆炸保护";
            case "projectile_protection" -> "弹射物保护"; case "respiration" -> "水下呼吸";
            case "aqua_affinity" -> "水下速掘"; case "thorns" -> "荆棘";
            case "depth_strider" -> "深海探索者"; case "frost_walker" -> "冰霜行者";
            case "sharpness" -> "锋利"; case "smite" -> "亡灵杀手";
            case "bane_of_arthropods" -> "节肢杀手"; case "knockback" -> "击退";
            case "fire_aspect" -> "火焰附加"; case "looting" -> "抢夺";
            case "sweeping_edge" -> "横扫之刃"; case "efficiency" -> "效率";
            case "silk_touch" -> "精准采集"; case "unbreaking" -> "耐久";
            case "fortune" -> "时运"; case "power" -> "力量";
            case "punch" -> "冲击"; case "flame" -> "火焰";
            case "infinity" -> "无限"; case "luck_of_the_sea" -> "海之眷顾";
            case "lure" -> "饵钓"; case "loyalty" -> "忠诚";
            case "impaling" -> "穿刺"; case "riptide" -> "激流";
            case "channeling" -> "引雷"; case "multishot" -> "多重射击";
            case "quick_charge" -> "快速装填"; case "piercing" -> "穿透";
            case "density" -> "致密"; case "breach" -> "破甲";
            case "wind_burst" -> "风爆"; case "mending" -> "经验修补";
            case "soul_speed" -> "灵魂疾行"; case "swift_sneak" -> "迅捷潜行";
            default -> ench.getKey().getKey();
        };
    }

    public static String toRoman(int n) {
        return switch (n) {
            case 1 -> "I"; case 2 -> "II"; case 3 -> "III"; case 4 -> "IV";
            case 5 -> "V"; case 6 -> "VI"; case 7 -> "VII"; case 8 -> "VIII";
            case 9 -> "IX"; case 10 -> "X";
            default -> String.valueOf(n);
        };
    }
}
