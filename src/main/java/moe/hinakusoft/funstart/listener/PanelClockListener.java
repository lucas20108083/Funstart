package moe.hinakusoft.funstart.listener;

import moe.hinakusoft.funstart.FunstartPlugin;
import moe.hinakusoft.funstart.model.CustomEnchantment;
import moe.hinakusoft.funstart.model.FeatureFlag;
import moe.hinakusoft.funstart.model.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class PanelClockListener implements Listener {

    private final FunstartPlugin plugin;

    public PanelClockListener(FunstartPlugin plugin) {
        this.plugin = plugin;
    }

    // ---- 面板钟 item ----

    public static ItemStack createPanelClock() {
        ItemStack item = new ItemStack(Material.CLOCK);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§6§l面板钟");
        meta.setLore(List.of("§7右键打开 Funstart 功能面板"));
        item.setItemMeta(meta);
        return item;
    }

    public static boolean isPanelClock(ItemStack item) {
        if (item == null || item.getType() != Material.CLOCK || !item.hasItemMeta()) return false;
        return "§6§l面板钟".equals(item.getItemMeta().getDisplayName());
    }

    // ---- Open main menu ----

    public static void openMainMenu(Player player, FunstartPlugin plugin) {
        PlayerData data = plugin.getPlayerDataManager().getPlayerData(player);
        Inventory inv = Bukkit.createInventory(new PanelHolder(player, PanelHolder.View.MAIN), 54, "§8§lFunstart 功能面板");

        // Row 1: FunStart面板 (附魔的粗金块)
        inv.setItem(0, makeFeatureItem(plugin, FeatureFlag.MAIN_MENU, Material.GOLD_BLOCK, true,
                "§6§lFunStart 面板", "§7打开主要功能设置", "§7连锁挖掘 / 范围收割 / 自动修复等"));

        // 传送点面板 (哭泣黑曜石 - 地狱传送门样式)
        inv.setItem(1, makeFeatureItem(plugin, FeatureFlag.WARP, Material.CRYING_OBSIDIAN, true,
                "§5§l传送点面板", "§7传送点管理", "§7创建 / 传送 / 分享传送点"));

        // tpa面板 (玩家头颅)
        ItemStack tpaHead = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta tpaMeta = (SkullMeta) tpaHead.getItemMeta();
        tpaMeta.setDisplayName("§b§lTPA 面板");
        tpaMeta.setLore(List.of("§7请求传送到其他玩家"));
        tpaHead.setItemMeta(tpaMeta);
        inv.setItem(2, tpaHead);

        // tpah面板 (末地传送门框架)
        inv.setItem(3, makeEnchantedItem(Material.END_PORTAL_FRAME, "§d§lTPAH 面板",
            "§7邀请其他玩家传送到你身边"));

        // 附魔 (附魔书)
        inv.setItem(4, makeFeatureItem(plugin, FeatureFlag.ENCHANT, Material.ENCHANTED_BOOK, true,
                "§d§l注魔附魔", "§7为装备添加附魔", "§7支持多种附魔与自定义附魔"));

        // 驱魔 (砂轮)
        inv.setItem(5, makeFeatureItem(plugin, FeatureFlag.DISENCHANT, Material.GRINDSTONE, false,
                "§c§l驱魔", "§7移除物品上的所有附魔", "§7手持物品点击"));

        // 领地管理 (草方块)
        inv.setItem(6, makeFeatureItem(plugin, FeatureFlag.CLAIM, Material.GRASS_BLOCK, false,
                "§a§l领地管理", "§7创建/管理你的圈地"));

        // 关于 (闹钟)
        inv.setItem(7, makeItem(Material.CLOCK, "§a§l关于 Funstart",
            "§7查看插件版本和玩家信息"));

        // 市场 (金锭)
        inv.setItem(8, makeFeatureItem(plugin, FeatureFlag.MARKET, Material.GOLD_INGOT, false,
                "§6§l市场", "§7浏览服务器商店与玩家市场", "§7购买/出售物品"));

        // 领取奖励 (slot 18)
        if (plugin.getMarketManager().hasPendingRewards(player.getUniqueId())) {
            inv.setItem(18, makeItem(Material.CHEST, "§6§l领取奖励",
                "§7你有待领取的市场收益",
                "§a点击领取"));
        }

        // Row 2: OP-only admin/prank panel
        if (player.isOp()) {
            inv.setItem(9, makeFeatureItem(plugin, FeatureFlag.ADMIN, Material.REDSTONE_BLOCK, false,
                    "§4§l管理面板", "§7踢出玩家 / 切换模式", "§7免费传送 / TPA / TPAH"));
            inv.setItem(17, makeFeatureItem(plugin, FeatureFlag.PRANK, Material.TNT, false,
                    "§5§l恶搞面板", "§7隐身 / 闪电 / 爆炸", "§7移动物品栏"));
        }

        // 新手礼包 (slot 45 - bottom-left)
        inv.setItem(45, makeItem(Material.CHEST, "§a§l新手礼包",
            "§7首次领取可获得",
            "§73×铜锭 3×熟鳕鱼 5×石头",
            "§74×深色橡木树苗 1×糖",
            "§7并赠送 §e300 点数"));

        // Bottom-right: player head
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        meta.setPlayerProfile(player.getPlayerProfile());
        meta.setDisplayName("§b" + player.getName());
        meta.setLore(List.of(
            "§7❤ 生命: §c" + String.format("%.0f", player.getHealth()) + "§7/§c" + String.format("%.0f", player.getMaxHealth()),
            "§7💎 点数: §b" + PlayerData.fmt(data.getPoints())
        ));
        head.setItemMeta(meta);
        inv.setItem(53, head);

        player.openInventory(inv);
    }

    private static ItemStack makeFeatureItem(FunstartPlugin plugin, FeatureFlag flag, Material mat, boolean enchanted, String name, String... lore) {
        if (!plugin.getFeatureConfig().isEnabled(flag)) {
            ItemStack barrier = new ItemStack(Material.BARRIER);
            ItemMeta meta = barrier.getItemMeta();
            meta.setDisplayName("§c§l" + flag.getDisplayName() + " §7(已禁用)");
            List<String> disabledLore = new ArrayList<>();
            for (String line : lore) disabledLore.add(line);
            disabledLore.add("");
            disabledLore.add("§c该功能已被管理员禁用");
            meta.setLore(disabledLore);
            barrier.setItemMeta(meta);
            return barrier;
        }
        if (enchanted) {
            return makeEnchantedItem(mat, name, lore);
        }
        return makeItem(mat, name, lore);
    }

    // ---- Open about panel ----

    public static void openAbout(Player player, FunstartPlugin plugin) {
        Inventory inv = Bukkit.createInventory(new PanelHolder(player, PanelHolder.View.ABOUT), 27, "§8§l关于 Funstart");

        ItemStack versionItem = makeItem(Material.KNOWLEDGE_BOOK, "§6§l插件版本",
            "§7Funstart §ev" + plugin.getDescription().getVersion(),
            "§7Paper 1.21.4");

        inv.setItem(11, versionItem);

        ItemStack uuidItem = new ItemStack(Material.NAME_TAG);
        ItemMeta uuidMeta = uuidItem.getItemMeta();
        uuidMeta.setDisplayName("§b§l你的 UUID");
        uuidMeta.setLore(List.of(
            "§7" + player.getUniqueId().toString(),
            "",
            "§c§l请勿向他人分享你的 UUID!",
            "§7这可能导致安全隐患"
        ));
        uuidItem.setItemMeta(uuidMeta);
        inv.setItem(13, uuidItem);

        ItemStack creditItem = makeItem(Material.SUNFLOWER, "§e§l感谢使用",
            "§7Funstart 功能面板",
            "§7为你提供更好的游戏体验");
        inv.setItem(15, creditItem);

        // Account settings button (if registered)
        var authManager = plugin.getAuthManager();
        if (authManager != null && authManager.isRegistered(player.getUniqueId())) {
            inv.setItem(10, makeItem(Material.REDSTONE_TORCH, "§c§l账号设置",
                "§7自动登录 / 修改密码"));
        }

        inv.setItem(26, makeItem(Material.BARRIER, "§c关闭"));

        player.openInventory(inv);
    }

    // ---- Auth settings ----

    public static void openAuthSettings(Player player, FunstartPlugin plugin) {
        var authManager = plugin.getAuthManager();
        if (authManager == null || !authManager.isRegistered(player.getUniqueId())) {
            player.sendMessage("§c你尚未注册账号");
            return;
        }

        Inventory inv = Bukkit.createInventory(new PanelHolder(player, PanelHolder.View.AUTH_SETTINGS), 27, "§c§l账号设置");

        String username = authManager.getUsername(player.getUniqueId());
        inv.setItem(4, makeItem(Material.PLAYER_HEAD, "§b" + (username != null ? username : player.getName()),
            "§7账号设置"));

        boolean autoLogin = authManager.isAutoLogin(player.getUniqueId(), getCurrentIp(player));
        inv.setItem(11, makeItem(
            autoLogin ? Material.LIME_DYE : Material.GRAY_DYE,
            "§" + (autoLogin ? "a" : "c") + "§l自动登录: " + (autoLogin ? "§a✔ 开启" : "§c✘ 关闭"),
            "§7点击" + (autoLogin ? "关闭" : "开启") + "自动登录",
            "§7开启后, 同一IP进入服务器将自动登录"));

        inv.setItem(15, makeItem(Material.ENCHANTED_BOOK, "§6§l修改密码",
            "§7点击后在聊天框输入新密码"));

        inv.setItem(26, makeItem(Material.BARRIER, "§c返回"));

        player.openInventory(inv);
    }

    private static String getCurrentIp(Player player) {
        java.net.InetSocketAddress addr = player.getAddress();
        return addr != null ? addr.getAddress().getHostAddress() : "unknown";
    }

    // ---- Warp creation confirmation GUI ----

    public static void openWarpCreateConfirm(Player player, FunstartPlugin plugin, String warpName) {
        Inventory inv = Bukkit.createInventory(
            new PanelHolder(player, PanelHolder.View.WARP_CREATE_CONFIRM, warpName),
            9, "§6确认创建传送点");

        inv.setItem(2, makeItem(Material.GREEN_CONCRETE, "§a§l确认",
            "§7创建传送点 §b" + warpName));
        inv.setItem(4, makeItem(Material.YELLOW_CONCRETE, "§e§l修改",
            "§7重新输入传送点名称"));
        inv.setItem(6, makeItem(Material.RED_CONCRETE, "§c§l取消",
            "§7取消创建传送点"));
        inv.setItem(8, makeItem(Material.BARRIER, "§c关闭"));

        player.openInventory(inv);
    }

    // ---- Disenchant GUI ----

    private static final Set<String> TARGET_ENCHANTS = Set.of(
        "mending", "silk_touch", "frost_walker", "thorns",
        "soul_speed", "swift_sneak"
    );

    public static void openDisenchantFor(Player player, FunstartPlugin plugin) {
        Inventory inv = Bukkit.createInventory(
            new PanelHolder(player, PanelHolder.View.DISENCHANT), 27, "§c驱魔");

        ItemStack held = player.getInventory().getItemInMainHand();
        if (held == null || held.getType().isAir()) {
            inv.setItem(13, makeItem(Material.BARRIER, "§c请手持需要驱魔的物品"));
            inv.setItem(26, makeItem(Material.BARRIER, "§c关闭"));
            player.openInventory(inv);
            return;
        }

        // Find target vanilla enchants on the item
        List<Enchantment> targetEnchants = new ArrayList<>();
        for (Map.Entry<Enchantment, Integer> e : held.getEnchantments().entrySet()) {
            if (TARGET_ENCHANTS.contains(e.getKey().getKey())) {
                targetEnchants.add(e.getKey());
            }
        }

        // Find custom enchants on the item
        List<CustomEnchantment> targetCustom = new ArrayList<>();
        for (CustomEnchantment ce : CustomEnchantment.VALUES) {
            if (EnchantGuiListener.getCustomLevel(held, plugin, ce) > 0) {
                targetCustom.add(ce);
            }
        }

        boolean hasAny = !targetEnchants.isEmpty() || !targetCustom.isEmpty();

        // Held item display
        ItemStack display = held.clone();
        ItemMeta dm = display.getItemMeta();
        List<String> lore = dm.hasLore() ? new ArrayList<>(dm.getLore()) : new ArrayList<>();
        if (hasAny) {
            lore.add("");
            lore.add("§7--- 可驱魔附魔 ---");
            for (Enchantment ench : targetEnchants) {
                lore.add(" §e- " + getEnchName(ench) + " " + EnchantGuiListener.toRoman(held.getEnchantmentLevel(ench)));
            }
            for (CustomEnchantment ce : targetCustom) {
                int lvl = EnchantGuiListener.getCustomLevel(held, plugin, ce);
                lore.add(" §5- " + ce.getDisplayName() + " " + EnchantGuiListener.toRoman(lvl));
            }
            lore.add("");
            lore.add("§c点击上方附魔书单独移除");
            lore.add("§7每个消耗 §e10 §7点");
        } else {
            lore.add("");
            lore.add("§7该物品没有需要驱魔的附魔");
        }
        dm.setLore(lore);
        display.setItemMeta(dm);
        inv.setItem(13, display);

        PlayerData data = plugin.getPlayerDataManager().getPlayerData(player);

        // Place clickable enchant books for target vanilla enchants
        int[] vanillaSlots = {10, 11, 12, 14, 15, 16};
        int vi = 0;
        for (Enchantment ench : targetEnchants) {
            if (vi >= vanillaSlots.length) break;
            ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
            ItemMeta bm = book.getItemMeta();
            bm.setDisplayName("§c[驱魔] §e" + getEnchName(ench) + " " + EnchantGuiListener.toRoman(held.getEnchantmentLevel(ench)));
            List<String> blore = new ArrayList<>();
            blore.add("§7消耗: §e10 §7点");
            blore.add(data.getPoints() >= 10 ? "§a余额充足" : "§c余额不足");
            blore.add("§e点击移除");
            bm.setLore(blore);
            bm.getPersistentDataContainer().set(new NamespacedKey(plugin, "dis_vanilla"), PersistentDataType.STRING, ench.getKey().getKey());
            book.setItemMeta(bm);
            inv.setItem(vanillaSlots[vi++], book);
        }

        // Place clickable books for custom enchants
        int[] customSlots = {18, 19, 20, 21, 22, 23, 24, 25};
        int ci = 0;
        for (CustomEnchantment ce : targetCustom) {
            if (ci >= customSlots.length) break;
            int lvl = EnchantGuiListener.getCustomLevel(held, plugin, ce);
            ItemStack book = new ItemStack(Material.ENCHANTED_BOOK);
            ItemMeta bm = book.getItemMeta();
            bm.setDisplayName("§c[驱魔] §5" + ce.getDisplayName() + " " + EnchantGuiListener.toRoman(lvl));
            List<String> blore = new ArrayList<>();
            blore.add("§7消耗: §e10 §7点");
            blore.add(data.getPoints() >= 10 ? "§a余额充足" : "§c余额不足");
            blore.add("§e点击移除");
            bm.setLore(blore);
            bm.getPersistentDataContainer().set(new NamespacedKey(plugin, "dis_custom"), PersistentDataType.STRING, ce.getKey());
            book.setItemMeta(bm);
            inv.setItem(customSlots[ci++], book);
        }

        // Decoration
        ItemStack pane = makeItem(Material.GRAY_STAINED_GLASS_PANE, "§c驱魔");
        for (int i = 0; i < 27; i++) {
            if (inv.getItem(i) == null) inv.setItem(i, pane.clone());
        }

        inv.setItem(26, makeItem(Material.BARRIER, "§c关闭"));
        player.openInventory(inv);
    }

    private void openDisenchant(Player player) {
        openDisenchantFor(player, this.plugin);
    }

    private void handleAuthSettingsClick(Player player, int slot) {
        var authManager = plugin.getAuthManager();
        if (authManager == null) return;

        if (slot == 11) {
            // Toggle auto-login
            boolean current = authManager.isAutoLogin(player.getUniqueId(), getCurrentIp(player));
            String ip = getCurrentIp(player);
            authManager.setAutoLogin(player.getUniqueId(), ip, !current);
            player.sendMessage("§a自动登录已" + (!current ? "开启" : "关闭"));
            openAuthSettings(player, plugin);
        } else if (slot == 15) {
            // Change password - start chat flow
            player.closeInventory();
            plugin.addPendingChatAction(player.getUniqueId(),
                FunstartPlugin.PendingChatAction.Type.CHANGE_PASSWORD, null, 600L);
            player.sendMessage("§e请输入新密码 (至少4位):");
        } else if (slot == 26) {
            openAbout(player, plugin);
        }
    }

    private void handleDisenchantClick(Player player, int slot, InventoryClickEvent event) {
        ItemStack held = player.getInventory().getItemInMainHand();
        if (held == null || held.getType().isAir()) {
            player.sendMessage("§c手持物品为空");
            player.closeInventory();
            return;
        }

        if (slot == 26) {
            player.closeInventory();
            return;
        }

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) {
            openDisenchant(player);
            return;
        }

        var pdc = clicked.getItemMeta().getPersistentDataContainer();
        NamespacedKey vk = new NamespacedKey(plugin, "dis_vanilla");
        NamespacedKey ck = new NamespacedKey(plugin, "dis_custom");
        String enchKey = pdc.get(vk, PersistentDataType.STRING);
        String custKey = pdc.get(ck, PersistentDataType.STRING);

        if (enchKey == null && custKey == null) {
            openDisenchant(player);
            return;
        }

        PlayerData data = plugin.getPlayerDataManager().getPlayerData(player);
        if (data.getPoints() < 10) {
            player.sendMessage("§c点数不足! 需要 §e10 §c点");
            return;
        }

        data.deductPoints(10);

        if (enchKey != null) {
            Enchantment ench = Registry.ENCHANTMENT.get(NamespacedKey.fromString("minecraft:" + enchKey));
            if (ench != null && held.containsEnchantment(ench)) {
                held.removeEnchantment(ench);
                player.sendMessage("§e[Funstart] §a已移除附魔 §e" + getEnchName(ench) + "§a, 消耗 §e10 §a点");
                plugin.getLogger().info("[Disenchant] " + player.getName() + " 移除了 " + enchKey);
            }
        }

        if (custKey != null) {
            for (CustomEnchantment ce : CustomEnchantment.VALUES) {
                if (ce.getKey().equals(custKey)) {
                    EnchantGuiListener.setCustomLevel(held, plugin, ce, 0);
                    player.sendMessage("§e[Funstart] §a已移除自定义附魔 §5" + ce.getDisplayName() + "§a, 消耗 §e10 §a点");
                    plugin.getLogger().info("[Disenchant] " + player.getName() + " 移除了自定义附魔 " + custKey);
                    break;
                }
            }
        }

        plugin.getPlayerDataManager().savePlayerData(player.getUniqueId());
        openDisenchant(player);
    }

    private static String getEnchName(Enchantment ench) {
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

    // ---- Event handlers ----

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof PanelHolder holder)) return;
        event.setCancelled(true);

        Player player = (Player) event.getWhoClicked();
        if (!holder.getPlayer().equals(player)) return;

        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getInventory().getSize()) return;

        switch (holder.getView()) {
            case MAIN -> handleMainClick(player, slot);
            case ABOUT -> handleAboutClick(player, slot);
            case WARP_CREATE_CONFIRM -> handleWarpCreateConfirmClick(player, slot, holder.getWarpName());
            case DISENCHANT -> handleDisenchantClick(player, slot, event);
            case AUTH_SETTINGS -> handleAuthSettingsClick(player, slot);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof PanelHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {}

    // ---- Click handlers ----

    private boolean checkFeature(Player player, FeatureFlag flag) {
        if (!plugin.getFeatureConfig().isEnabled(flag)) {
            player.sendMessage("§c该功能已被管理员禁用");
            return false;
        }
        return true;
    }

    private void handleMainClick(Player player, int slot) {
        switch (slot) {
            case 0 -> {
                if (checkFeature(player, FeatureFlag.MAIN_MENU)) GuiListener.openFor(player, plugin);
            }
            case 1 -> {
                if (checkFeature(player, FeatureFlag.WARP)) FswGuiListener.openMainMenu(player, plugin);
            }
            case 2 -> TpagGuiListener.openFor(player, plugin, false);
            case 3 -> TpagGuiListener.openFor(player, plugin, true);
            case 4 -> {
                if (!checkFeature(player, FeatureFlag.ENCHANT)) return;
                player.closeInventory();
                plugin.addPendingChatAction(player.getUniqueId(),
                    FunstartPlugin.PendingChatAction.Type.ENCHANT_CONFIRM, null, 600L);
                player.sendMessage("§d[附魔] 请手持要附魔的物品, 聊天框输入 §ef §d继续");
            }
            case 5 -> {
                if (!checkFeature(player, FeatureFlag.DISENCHANT)) return;
                player.closeInventory();
                plugin.addPendingChatAction(player.getUniqueId(),
                    FunstartPlugin.PendingChatAction.Type.DISENCHANT_CONFIRM, null, 600L);
                player.sendMessage("§c[驱魔] 请手持要输入 §ef §c继续");
            }
            case 6 -> {
                if (checkFeature(player, FeatureFlag.CLAIM)) ClaimGuiListener.openMain(player, plugin);
            }
            case 7 -> openAbout(player, plugin);
            case 8 -> {
                if (checkFeature(player, FeatureFlag.MARKET)) MarketGuiListener.openMarket(player, plugin);
            }
            case 18 -> {
                if (plugin.getMarketManager().hasPendingRewards(player.getUniqueId())) {
                    MarketGuiListener.openClaimGui(player, plugin);
                }
            }
            case 9 -> {
                if (player.isOp() && checkFeature(player, FeatureFlag.ADMIN)) AdminGuiListener.openMain(player, plugin);
            }
            case 17 -> {
                if (player.isOp() && checkFeature(player, FeatureFlag.PRANK)) PrankGuiListener.openMain(player, plugin);
            }
            case 45 -> {
                var rbm = plugin.getRewardBagManager();
                if (rbm == null) return;
                String accountName = plugin.getAuthManager() != null ? plugin.getAuthManager().getUsername(player.getUniqueId()) : "";
                if (accountName == null) accountName = "";
                String result = switch (rbm.claimReward(player, "firstReward", accountName, player.getName())) {
                    case SUCCESS -> "§a新手礼包领取成功! 获得 §e300 点数 §a和多种物品";
                    case ALREADY_CLAIMED -> "§c你已经领取过新手礼包了";
                    case NO_SPACE -> "§c背包空间不足! 需要至少 §e5 §c个空格";
                    case NOT_FOUND -> "§c该礼包不存在";
                };
                player.sendMessage("§e[Funstart] " + result);
                openMainMenu(player, plugin);
            }
            case 53 -> openMainMenu(player, plugin);
        }
    }

    private void handleAboutClick(Player player, int slot) {
        if (slot == 10) {
            openAuthSettings(player, plugin);
        } else if (slot == 26) {
            player.closeInventory();
        }
    }

    private void handleWarpCreateConfirmClick(Player player, int slot, String warpName) {
        if (slot == 2) {
            // Confirm - create warp
            if (warpName == null || warpName.isEmpty()) {
                player.sendMessage("§c传送点名称无效");
                player.closeInventory();
                return;
            }
            if (plugin.getWarpManager().warpExists(warpName, player.getUniqueId())) {
                player.sendMessage("§c已存在同名传送点");
                openWarpCreateConfirm(player, plugin, warpName);
                return;
            }
            plugin.getWarpManager().createWarp(warpName, player.getUniqueId(), player.getLocation());
            player.sendMessage("§e[Funstart] §a已创建传送点 §b" + warpName);
            player.closeInventory();
        } else if (slot == 4) {
            // Modify - re-enter name
            player.closeInventory();
            plugin.addPendingChatAction(player.getUniqueId(),
                FunstartPlugin.PendingChatAction.Type.ADD_WARP, null);
            plugin.getPendingWarpCreation().put(player.getUniqueId(), player.getLocation().clone());
            player.sendMessage("§a请输入新的传送点名称 (1-20字):");
            player.sendMessage("§7移动5格距离可取消");
        } else if (slot == 6 || slot == 8) {
            player.sendMessage("§c已取消创建传送点");
            player.closeInventory();
        }
    }

    // ---- InventoryHolder ----

    public static class PanelHolder implements InventoryHolder {
        enum View { MAIN, ABOUT, WARP_CREATE_CONFIRM, DISENCHANT, AUTH_SETTINGS }

        private final Player player;
        private final View view;
        private final String warpName;

        public PanelHolder(Player player, View view) {
            this(player, view, null);
        }

        public PanelHolder(Player player, View view, String warpName) {
            this.player = player;
            this.view = view;
            this.warpName = warpName;
        }

        public Player getPlayer() { return player; }
        public View getView() { return view; }
        public String getWarpName() { return warpName; }

        @Override
        public Inventory getInventory() { return null; }
    }

    // ---- Helpers ----

    private static ItemStack makeItem(Material mat, String name, String... lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(List.of(lore));
        item.setItemMeta(meta);
        return item;
    }

    private static ItemStack makeEnchantedItem(Material mat, String name, String... lore) {
        ItemStack item = new ItemStack(mat);
        item.addUnsafeEnchantment(Enchantment.UNBREAKING, 1);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(List.of(lore));
        item.setItemMeta(meta);
        return item;
    }
}
