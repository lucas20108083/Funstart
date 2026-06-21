/*
 * Funstart —— Paper 1.21.4 服务器功能插件
 *
 * ================================================================
 *  全局工具与编码规范
 * ================================================================
 *
 * 1. 随机数: 禁止使用 new Random()，始终使用 ThreadLocalRandom.current()
 *    - ThreadLocalRandom 无锁、无对象分配开销，适用于所有线程
 *    - 示例: ThreadLocalRandom.current().nextInt(max) | nextDouble() | nextBoolean()
 *
 * 2. 方块/材料常量集: 禁止使用 Set<String> + type.name()
 *    - 优先使用 EnumSet<Material>，类型安全且性能更优（位运算 O(1)）
 *    - 示例: EnumSet.of(Material.TNT, Material.LAVA)
 *
 * 3. 距离比较: 避免 distance() 的开平方根开销
 *    - 比较距离时使用 distanceSquared()，预先计算阈值的平方
 *    - 示例: if (a.distanceSquared(b) < 25.0)    // 等价于 distance < 5.0
 *
 * 4. 文件 I/O 与持久化: 避免在主线程执行
 *    - LogManager 使用异步队列 + 虚拟线程消费者
 *    - PlayerDataManager 使用独立线程池异步保存
 *    - AuthManager 使用去重定时写入（3 秒合并窗口）
 *
 * 5. 定时扫描型任务: 优先事件驱动
 *    - CustomItemManager 不再每 tick 扫描全背包，改为事件触发重扫
 *    - 每 tick 仅迭代持有自定义物品的玩家
 *
 * 6. 并发容器: 使用 ConcurrentHashMap / ConcurrentHashMap.newKeySet()
 *    - 避免使用 HashMap / HashSet 在可能被多线程访问的字段上
 *
 * 7. 实体遍历: 避免 getEntities() + instanceof 过滤
 *    - 使用 getEntitiesByClass(T) 只获取目标类型实体
 *    - 示例: world.getEntitiesByClass(Item.class)
 *
 * ================================================================
 */

package moe.hinakusoft.funstart;

import moe.hinakusoft.funstart.command.FsrankCommand;
import moe.hinakusoft.funstart.command.FstGetCommand;
import moe.hinakusoft.funstart.custom.CustomItemManager;
import moe.hinakusoft.funstart.custom.FSTFood;
import moe.hinakusoft.funstart.listener.*;
import moe.hinakusoft.funstart.manager.*;
import moe.hinakusoft.funstart.model.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.TabCompleter;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.ShapedRecipe;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

public class FunstartPlugin
extends JavaPlugin implements Listener {
    private PlayerDataManager playerDataManager;
    private TpaManager tpaManager;
    private WarpManager warpManager;
    private final Map<UUID, PendingChatAction> pendingChatActions = new ConcurrentHashMap<>();
    private final Map<UUID, SessionData> sessions = new ConcurrentHashMap<>();
    private final Set<UUID> pendingTeleports = ConcurrentHashMap.newKeySet();
    private final Map<UUID, Location> pendingWarpCreation = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> effectiveUuids = new ConcurrentHashMap<>();
    private FSTActionBar actionBar;
    private CustomItemManager customItemManager;
    private FSTFood fstFood;
    private ClaimManager claimManager;
    private moe.hinakusoft.funstart.manager.MarketManager marketManager;
    private moe.hinakusoft.funstart.listener.MarketGuiListener marketGuiListener;
    private LogManager logManager;
    private FstItemIdManager fstItemIdManager;
    private AuthManager authManager;
    private ClaimParticleManager claimParticleManager;
    private RestApiServer restApiServer;
    private FeatureConfig featureConfig;
    private MultiThreadManager multiThreadManager;
    private RankManager rankManager;

    public void onEnable() {
        saveDefaultConfig();
        this.featureConfig = new FeatureConfig(getDataFolder());
        this.multiThreadManager = new MultiThreadManager(this, this.featureConfig);
        this.rankManager = new RankManager(this);
        this.playerDataManager = new PlayerDataManager(this);
        this.tpaManager = new TpaManager();
        this.warpManager = new WarpManager(this);
        this.warpManager.load();
        this.actionBar = new FSTActionBar(this);
        this.claimManager = new ClaimManager(this);
        this.claimManager.load();
        this.claimParticleManager = new ClaimParticleManager(this);
        this.claimParticleManager.start();
        this.authManager = new AuthManager(this);
        this.getServer().getPluginManager().registerEvents(new moe.hinakusoft.funstart.auth.AuthListener(this, authManager), this);
        this.customItemManager = new CustomItemManager();
        this.fstFood = new FSTFood(new NamespacedKey(this, "fst_food"));
        this.fstFood.setDefaultHandler(new moe.hinakusoft.funstart.custom.handler.DefaultFood());
        this.fstFood.registerHandler(new moe.hinakusoft.funstart.custom.handler.CandyFood());
        this.fstFood.registerHandler(new moe.hinakusoft.funstart.custom.handler.MeatStewFood());
        this.customItemManager.register(fstFood);
        this.customItemManager.startTicking(this);
        moe.hinakusoft.funstart.api.FunstartAPI.init(this);
        regCmd("fstget", new FstGetCommand(this), null);
        this.marketManager = new moe.hinakusoft.funstart.manager.MarketManager(this);
        this.marketManager.load();
        regCmd("fsshop", new moe.hinakusoft.funstart.command.FsshopCommand(this), null);
        regCmd("fsrank", new FsrankCommand(this), null);
        this.getServer().getPluginManager().registerEvents(new PlayerListener(this), this);
        this.getServer().getPluginManager().registerEvents(new TpaListener(this), this);
        this.getServer().getPluginManager().registerEvents(new ChainListener(this), this);
        this.getServer().getPluginManager().registerEvents(new GuiListener(this), this);
        this.getServer().getPluginManager().registerEvents(new HarvestListener(this), this);
        this.getServer().getPluginManager().registerEvents(new TpagGuiListener(this), this);
        this.getServer().getPluginManager().registerEvents(new FswGuiListener(this), this);
        this.getServer().getPluginManager().registerEvents(new ChatListener(this), this);
        this.getServer().getPluginManager().registerEvents(new PanelClockListener(this), this);
        this.getServer().getPluginManager().registerEvents(new EnchantGuiListener(this), this);
        this.getServer().getPluginManager().registerEvents(new CustomEnchantListener(this), this);
        this.getServer().getPluginManager().registerEvents(new EnchantDowngradeListener(this), this);
        this.getServer().getPluginManager().registerEvents(new DailyTaskListener(this), this);
        moe.hinakusoft.funstart.listener.PrankGuiListener prankListener = new moe.hinakusoft.funstart.listener.PrankGuiListener(this);
        this.getServer().getPluginManager().registerEvents(new ClaimListener(this), this);
        this.getServer().getPluginManager().registerEvents(new ClaimGuiListener(this), this);
        this.getServer().getPluginManager().registerEvents(new moe.hinakusoft.funstart.listener.AdminGuiListener(this), this);
        this.getServer().getPluginManager().registerEvents(new moe.hinakusoft.funstart.listener.DataEditGuiListener(this), this);
        this.getServer().getPluginManager().registerEvents(new moe.hinakusoft.funstart.listener.NbtEditGuiListener(this), this);
        this.marketGuiListener = new moe.hinakusoft.funstart.listener.MarketGuiListener(this);
        this.getServer().getPluginManager().registerEvents(this.marketGuiListener, this);
        this.logManager = new LogManager(this);
        this.fstItemIdManager = new FstItemIdManager(getDataFolder());
        this.getServer().getPluginManager().registerEvents(new moe.hinakusoft.funstart.listener.LogListener(this), this);
        this.getServer().getPluginManager().registerEvents(prankListener, this);
        this.getServer().getPluginManager().registerEvents(this, this);
        this.startAutoFixTask();
        this.startPointsEarningTask();
        this.startCleanupTask();
        prankListener.startInvisibleTick();
        // Market restock task (every 5 minutes)
        Bukkit.getScheduler().runTaskTimer(this, () -> marketManager.tickRestocks(), 6000L, 6000L);
        try {
            this.restApiServer = new RestApiServer(this, 29966);
            this.restApiServer.start();
        } catch (Exception e) {
            getLogger().warning("无法启动 REST API: " + e.getMessage());
        }
        registerShikiRecipe();
        registerCandyRecipe();
        registerMeatStewRecipe();
        this.getLogger().info("Funstart 已启用");
    }

    private void registerShikiRecipe() {
        ItemStack result = new ItemStack(org.bukkit.Material.DIAMOND_AXE);
        ItemMeta meta = result.getItemMeta();
        meta.setDisplayName("§r§c§l四季 夏目§f");
        result.setItemMeta(meta);
        result.addUnsafeEnchantment(Enchantment.SHARPNESS, 10);
        result.addUnsafeEnchantment(Enchantment.UNBREAKING, 10);
        result.addUnsafeEnchantment(Enchantment.MENDING, 1);
        result.addUnsafeEnchantment(Enchantment.SILK_TOUCH, 1);
        result.addUnsafeEnchantment(Enchantment.EFFICIENCY, 6);
        result.addUnsafeEnchantment(Enchantment.SMITE, 6);
        result.addUnsafeEnchantment(Enchantment.BANE_OF_ARTHROPODS, 6);
        EnchantGuiListener.setCustomLevel(result, this, moe.hinakusoft.funstart.model.CustomEnchantment.SHIKI_NATSUME, 3);

        NamespacedKey key = new NamespacedKey(this, "shiki_axe");
        ShapedRecipe recipe = new ShapedRecipe(key, result);
        recipe.shape("DN ", "EB ", " R ");
        recipe.setIngredient('D', org.bukkit.Material.DIAMOND);
        recipe.setIngredient('N', org.bukkit.Material.NETHER_STAR);
        recipe.setIngredient('E', org.bukkit.Material.ENDER_EYE);
        recipe.setIngredient('B', org.bukkit.Material.BONE);
        recipe.setIngredient('R', org.bukkit.Material.BLAZE_ROD);
        Bukkit.addRecipe(recipe);
        getLogger().info("已注册四季夏目合成配方");
    }

    private void registerCandyRecipe() {
        ItemStack result = new ItemStack(org.bukkit.Material.SPIDER_EYE, 4);
        ItemMeta meta = result.getItemMeta();
        meta.setDisplayName("§r§a糖§f");
        meta.setLore(List.of("§f这是一颗普通的糖"));
        meta.addEnchant(Enchantment.MENDING, 1, true);
        result.setItemMeta(meta);
        moe.hinakusoft.funstart.custom.FSTFood.tagItem(result, new NamespacedKey(this, "fst_food"), "candy");

        NamespacedKey key = new NamespacedKey(this, "candy");
        ShapedRecipe recipe = new ShapedRecipe(key, result);
        recipe.shape("AB", "BA");
        recipe.setIngredient('A', org.bukkit.Material.SPIDER_EYE);
        recipe.setIngredient('B', org.bukkit.Material.FERMENTED_SPIDER_EYE);
        Bukkit.addRecipe(recipe);
        getLogger().info("已注册糖合成配方");
    }

    private void registerMeatStewRecipe() {
        ItemStack result = new ItemStack(org.bukkit.Material.RABBIT_STEW, 1);
        ItemMeta meta = result.getItemMeta();
        meta.setDisplayName("§6§l肉类杂炖");
        meta.setLore(java.util.List.of(
            "§7由多种肉类熬制而成",
            "§7食用后: §c抗性提升 II §7(20s)",
            "§7       §b速度 I §7(5s)",
            "§7       §a恢复 16 饱食度"
        ));
        result.setItemMeta(meta);
        moe.hinakusoft.funstart.custom.FSTFood.tagItem(result, new NamespacedKey(this, "fst_food"), "stew");

        NamespacedKey key = new NamespacedKey(this, "meat_stew");
        org.bukkit.inventory.ShapedRecipe recipe = new org.bukkit.inventory.ShapedRecipe(key, result);
        recipe.shape("AC", "BD");
        recipe.setIngredient('A', org.bukkit.Material.BEEF);
        recipe.setIngredient('C', org.bukkit.Material.MUTTON);
        recipe.setIngredient('B', org.bukkit.Material.CHICKEN);
        recipe.setIngredient('D', org.bukkit.Material.PORKCHOP);
        Bukkit.addRecipe(recipe);
        getLogger().info("已注册肉类杂炖合成配方");
    }

    @EventHandler
    public void onFstFoodInteract(org.bukkit.event.player.PlayerInteractEvent event) {
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_AIR
            && event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = event.getItem();
        if (item == null || !this.fstFood.isItem(item)) return;
        event.setCancelled(true);
    }

    public void onDisable() {
        if (this.restApiServer != null) {
            this.restApiServer.stop();
        }
        if (this.customItemManager != null) {
            this.customItemManager.stop();
        }
        this.playerDataManager.saveAll();
        this.warpManager.save();
        if (this.claimParticleManager != null) {
            this.claimParticleManager.stop();
        }
        if (this.logManager != null) {
            this.logManager.onDisable();
        }
        if (this.multiThreadManager != null) {
            this.multiThreadManager.shutdown();
        }
        if (this.authManager != null) {
            this.authManager.flush();
        }
        this.getLogger().info("Funstart 已禁用");
    }

    public PlayerDataManager getPlayerDataManager() { return this.playerDataManager; }
    public TpaManager getTpaManager() { return this.tpaManager; }
    public WarpManager getWarpManager() { return this.warpManager; }
    public FSTActionBar getActionBar() { return this.actionBar; }
    public CustomItemManager getCustomItemManager() { return customItemManager; }
    public FSTFood getFstFood() { return fstFood; }
    public ClaimManager getClaimManager() { return claimManager; }
    public moe.hinakusoft.funstart.manager.MarketManager getMarketManager() { return marketManager; }
    public moe.hinakusoft.funstart.listener.MarketGuiListener getMarketGuiListener() { return marketGuiListener; }
    public LogManager getLogManager() { return logManager; }
    public FstItemIdManager getFstItemIdManager() { return fstItemIdManager; }
    public AuthManager getAuthManager() { return authManager; }

    public FeatureConfig getFeatureConfig() {
        return featureConfig;
    }

    public MultiThreadManager getMultiThreadManager() {
        return multiThreadManager;
    }

    public RankManager getRankManager() {
        return rankManager;
    }

    public Map<UUID, PendingChatAction> getPendingChatActions() { return pendingChatActions; }

    // ---- Effective UUID (cross-account login) ----

    public UUID getEffectiveUuid(UUID realUuid) {
        return effectiveUuids.getOrDefault(realUuid, realUuid);
    }

    public UUID getEffectiveUuid(Player player) {
        return getEffectiveUuid(player.getUniqueId());
    }

    public void setEffectiveUuid(UUID realUuid, UUID effectiveUuid) {
        if (effectiveUuid != null && !effectiveUuid.equals(realUuid)) {
            effectiveUuids.put(realUuid, effectiveUuid);
        } else {
            effectiveUuids.remove(realUuid);
        }
    }

    public void clearEffectiveUuid(UUID realUuid) {
        effectiveUuids.remove(realUuid);
    }

    public void addPendingChatAction(UUID uuid, PendingChatAction.Type type, Object data) {
        addPendingChatAction(uuid, type, data, 1200L);
    }

    public void addPendingChatAction(UUID uuid, PendingChatAction.Type type, Object data, long timeoutTicks) {
        PendingChatAction existing = pendingChatActions.remove(uuid);
        if (existing != null && existing.timeoutTaskId >= 0) {
            Bukkit.getScheduler().cancelTask(existing.timeoutTaskId);
        }
        int taskId = Bukkit.getScheduler().runTaskLater(this, () -> {
            PendingChatAction removed = pendingChatActions.remove(uuid);
            if (removed != null) {
                if (removed.type == PendingChatAction.Type.ADD_WARP) {
                    pendingWarpCreation.remove(uuid);
                }
                Player p = Bukkit.getPlayer(uuid);
                if (p != null && p.isOnline()) {
                    if (removed.type == PendingChatAction.Type.TPA_RESPONSE && removed.data instanceof TpaResponseData trd) {
                        tpaManager.removeRequest(trd.requester());
                        Player requester = Bukkit.getPlayer(trd.requester());
                        if (requester != null && requester.isOnline()) {
                            requester.sendMessage("§e[Funstart] §c传送请求已超时");
                        }
                        p.sendMessage("§e[Funstart] §c传送请求已超时");
                    } else {
                        String t = switch (removed.type) {
                            case ADD_WARP -> "名称输入";
                            case TELEPORT_CONFIRM -> "传送确认";
                            case SHARE_RESPONSE -> "分享确认";
                            case DELETE_WARP -> "传送点删除";
                            case ALL_FIX_CONFIRM -> "全部修复";
                            case BANK_TRANSFER -> "转账输入";
                            case BANK_AMOUNT -> "转账金额";
                            case CLAIM_POSITION -> "圈地坐标";
                            case ADD_TRUSTED -> "添加信任人";
                            case ENCHANT_CONFIRM -> "附魔确认";
                            case DISENCHANT_CONFIRM -> "驱魔确认";
                            case DATA_EDIT_CONFIRM -> "数据修改";
                            case NBT_EDIT_CONFIRM -> "NBT修改";
                            case NBT_ADD_KEY -> "NBT标签添加(键名)";
                            case NBT_ADD_VALUE -> "NBT标签添加(值)";
                            case MARKET_LIST_QTY -> "上架数量输入";
                            case MARKET_LIST_PRICE -> "上架价格输入";
                            case MARKET_LIST_DURATION -> "上架时长输入";
                            case MARKET_BUY_QTY -> "购买数量输入";
                            case CHANGE_PASSWORD -> "修改密码";
                            case ADMIN_RESET_PASSWORD -> "重置密码";
                            case TPA_RESPONSE -> ""; // handled above
                        };
                        p.sendMessage("§c" + t + " 已超时");
                    }
                }
            }
        }, timeoutTicks).getTaskId();
        long expireTime = System.currentTimeMillis() + timeoutTicks * 50;
        pendingChatActions.put(uuid, new PendingChatAction(type, data, taskId, expireTime));
    }

    public PendingChatAction removePendingChatAction(UUID uuid) {
        PendingChatAction action = pendingChatActions.remove(uuid);
        if (action != null && action.timeoutTaskId >= 0) {
            Bukkit.getScheduler().cancelTask(action.timeoutTaskId);
        }
        return action;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uid = event.getPlayer().getUniqueId();
        removePendingChatAction(uid);
        sessions.remove(uid);
        pendingTeleports.remove(uid);
        pendingWarpCreation.remove(uid);
        tpaManager.removeAllRequestsByPlayer(uid).forEach(req -> {
            Bukkit.getScheduler().cancelTask(req.getTaskId());
        });
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player p = event.getPlayer();
        PlayerData data = this.playerDataManager.getPlayerData(p);
        p.sendMessage("§e[Funstart] §a欢迎回来! 你的点数: §b" + PlayerData.fmt(data.getPoints()));
        p.sendMessage("§7右键面板钟打开功能面板, 输入 §e/fstget §7获取面板钟");

        // Check pending market rewards
        if (marketManager != null && marketManager.hasPendingRewards(p.getUniqueId())) {
            double pts = marketManager.getPendingPointsTotal(p.getUniqueId());
            int items = marketManager.getPendingItemCount(p.getUniqueId());
            StringBuilder sb = new StringBuilder("§e[市场] §a你有待领取的");
            if (pts > 0) sb.append(" §e" + String.format("%.1f", pts) + " §a点数");
            if (pts > 0 && items > 0) sb.append(" §7和");
            if (items > 0) sb.append(" §e" + items + " §a个物品");
            sb.append(", 请在面板中领取");
            p.sendMessage(sb.toString());
        }

        // Give panel clock on first join (or if missed previous)
        if (!data.hasPanelClock()) {
            boolean hasSlot = false;
            for (int i = 0; i < 36; i++) {
                if (p.getInventory().getItem(i) == null || p.getInventory().getItem(i).getType().isAir()) {
                    hasSlot = true;
                    break;
                }
            }
            if (hasSlot) {
                p.getInventory().addItem(moe.hinakusoft.funstart.listener.PanelClockListener.createPanelClock());
                data.setHasPanelClock(true);
                this.playerDataManager.savePlayerData(p.getUniqueId());
                p.sendMessage("§e[Funstart] §a已发放面板钟, 右键打开功能面板");
            } else {
                p.sendMessage("§e[Funstart] §c背包已满, 下次进服再给你~");
            }
        }

        Location loc = p.getLocation().clone();
        sessions.put(p.getUniqueId(), new SessionData(
            System.currentTimeMillis(), loc, 0.0));
    }

    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_AIR
            && event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) return;
        ItemStack item = event.getItem();
        if (moe.hinakusoft.funstart.listener.PanelClockListener.isPanelClock(item)) {
            event.setCancelled(true);
            moe.hinakusoft.funstart.listener.PanelClockListener.openMainMenu(event.getPlayer(), this);
        }
    }

    private void startAutoFixTask() {
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            long now = System.currentTimeMillis();
            for (Player player : Bukkit.getOnlinePlayers()) {
                PlayerData data = this.playerDataManager.getPlayerData(player);
                if (!data.isAutoFix()) continue;
                if (now - data.getLastAutoFixTime() < 5000L) continue;

                ItemStack[] contents = player.getInventory().getContents();
                double totalCost = 0.0;
                int count = 0;

                for (ItemStack item : contents) {
                    if (item == null || item.getType().isAir()) continue;
                    if (!(item.getItemMeta() instanceof Damageable damageable)) continue;
                    int maxDurability = item.getType().getMaxDurability();
                    if (maxDurability <= 0) continue;
                    int currentDurability = maxDurability - damageable.getDamage();
                    if ((double) currentDurability / maxDurability >= 0.2) continue;
                    totalCost += (double) (maxDurability - currentDurability) / 50.0;
                    count++;
                }

                if (count == 0) continue;
                if (data.getPoints() < totalCost) continue;

                data.deductPoints(totalCost);
                data.setLastAutoFixTime(now);

                for (ItemStack item : contents) {
                    if (item == null || item.getType().isAir()) continue;
                    if (!(item.getItemMeta() instanceof Damageable damageable)) continue;
                    int maxDurability = item.getType().getMaxDurability();
                    if (maxDurability <= 0) continue;
                    int currentDurability = maxDurability - damageable.getDamage();
                    if ((double) currentDurability / maxDurability >= 0.2) continue;
                    damageable.setDamage(0);
                    item.setItemMeta(damageable);
                }
                player.sendMessage("§e[Funstart] §a自动修复 §b" + count + " §a个物品 (耐久低于20%), 消耗 §e" + PlayerData.fmt(totalCost) + " §a点数, 剩余 §e" + PlayerData.fmt(data.getPoints()) + " §a点数");
            }
        }, 0L, 100L);
    }

    private void startPointsEarningTask() {
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            long now = System.currentTimeMillis();
            for (Player player : Bukkit.getOnlinePlayers()) {
                UUID uid = player.getUniqueId();
                SessionData sd = sessions.get(uid);
                if (sd == null) {
                    Location loc = player.getLocation().clone();
                    sessions.put(uid, new SessionData(now, loc, 0.0));
                    continue;
                }
                PlayerData data = this.playerDataManager.getPlayerData(player);

                // Online time: 3-5 point every 60 seconds
                if (now - sd.lastOnlinePointTime >= 60000L) {
                    sd.lastOnlinePointTime = now;
                    int earned = 3 + ThreadLocalRandom.current().nextInt(3);
                    data.addPoints(earned);
                    actionBar.add(player, String.format("§e在线 +%d点数", earned));
                }

                // Distance: 4-8 points per 1000 blocks
                Location current = player.getLocation();
                if (sd.lastPosition != null && current.getWorld() != null
                        && current.getWorld().equals(sd.lastPosition.getWorld())) {
                    double dist = sd.lastPosition.distance(current);
                    if (dist > 0.0 && dist < 1000.0) {
                        sd.distanceSinceLastPoint += dist;
                        if (sd.distanceSinceLastPoint >= 1000.0) {
                            int earned = (int) (sd.distanceSinceLastPoint / 1000.0);
                            sd.distanceSinceLastPoint -= earned * 1000.0;
                            earned *= (4 + ThreadLocalRandom.current().nextInt(8));
                            data.addPoints(earned);
                            actionBar.add(player, String.format("§e移动 +%d点数", earned));
                        }
                    }
                }
                sd.lastPosition = current.clone();

                // Check warp creation distance cancel
                Location warpStart = pendingWarpCreation.get(uid);
                if (warpStart != null && warpStart.getWorld().equals(current.getWorld())
                        && warpStart.distanceSquared(current) >= 25.0) {
                    pendingWarpCreation.remove(uid);
                    PendingChatAction action = removePendingChatAction(uid);
                    if (action != null && action.type == PendingChatAction.Type.ADD_WARP) {
                        player.sendMessage("§c已取消创建传送点 (移动距离过远)");
                    }
                }
            }
        }, 100L, 100L);
    }

    // ---- Cleanup Task (扫地机器人) ----

    private void startCleanupTask() {
        long interval = 6000L; // 300 seconds
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            // 60s warning
            Bukkit.getScheduler().runTaskLater(this, () -> {
                Bukkit.broadcastMessage("§e[扫地机器人] §a60秒后将清理掉落物");
            }, 4800L);

            // 5s warning
            Bukkit.getScheduler().runTaskLater(this, () -> {
                Bukkit.broadcastMessage("§e[扫地机器人] §c§l§n5秒后清理掉落物!");
            }, 5900L);

            // Cleanup
            Bukkit.getScheduler().runTaskLater(this, () -> {
                int count = 0;
                int arrowCount = 0;
                long nowCleanup = System.currentTimeMillis();
                for (org.bukkit.World world : Bukkit.getWorlds()) {
                    for (org.bukkit.entity.Item item : world.getEntitiesByClass(org.bukkit.entity.Item.class)) {
                        item.remove();
                        count++;
                    }
                    for (org.bukkit.entity.Arrow arrow : world.getEntitiesByClass(org.bukkit.entity.Arrow.class)) {
                        if (arrow.isInBlock() && nowCleanup - arrow.getTicksLived() * 50L > 30000L) {
                            arrow.remove();
                            arrowCount++;
                        }
                    }
                }
                String msg = "§e[扫地机器人] §a已清理 §e" + count + " §a个掉落物";
                if (arrowCount > 0) msg += "§7, §e" + arrowCount + " §7支箭矢";
                Bukkit.broadcastMessage(msg);
            }, 6000L);
        }, interval, interval);
    }

    // ---- Session tracking for online/distance points ----

    private static class SessionData {
        long lastOnlinePointTime;
        Location lastPosition;
        double distanceSinceLastPoint;

        SessionData(long lastOnlinePointTime, Location lastPosition, double distanceSinceLastPoint) {
            this.lastOnlinePointTime = lastOnlinePointTime;
            this.lastPosition = lastPosition;
            this.distanceSinceLastPoint = distanceSinceLastPoint;
        }
    }

    public Map<UUID, SessionData> getSessions() { return sessions; }
    public Map<UUID, Location> getPendingWarpCreation() { return pendingWarpCreation; }
    public boolean hasPendingTeleport(UUID uuid) { return pendingTeleports.contains(uuid); }
    public void setPendingTeleport(UUID uuid, boolean pending) {
        if (pending) pendingTeleports.add(uuid);
        else pendingTeleports.remove(uuid);
    }

    private void regCmd(String name, CommandExecutor exec, TabCompleter tab) {
        var cmd = getCommand(name);
        if (cmd == null) {
            getLogger().warning("命令 /" + name + " 未在 plugin.yml 中定义");
            return;
        }
        cmd.setExecutor(exec);
        if (tab != null) cmd.setTabCompleter(tab);
    }

    public static boolean checkPortal(Player player) {
        if (player.getLocation().getBlock().getType() == org.bukkit.Material.NETHER_PORTAL) {
            player.sendMessage("§c在地狱门内无法执行此操作");
            return true;
        }
        return false;
    }

    // ---- Pending chat action system ----

    public static class PendingChatAction {
        public enum Type { ADD_WARP, TELEPORT_CONFIRM, SHARE_RESPONSE, DELETE_WARP, ALL_FIX_CONFIRM, BANK_TRANSFER, BANK_AMOUNT, TPA_RESPONSE, CLAIM_POSITION, ADD_TRUSTED, ENCHANT_CONFIRM, DISENCHANT_CONFIRM, DATA_EDIT_CONFIRM, NBT_EDIT_CONFIRM, NBT_ADD_KEY, NBT_ADD_VALUE, MARKET_LIST_QTY, MARKET_LIST_PRICE, MARKET_LIST_DURATION, MARKET_BUY_QTY, CHANGE_PASSWORD, ADMIN_RESET_PASSWORD }
        public final Type type;
        public final Object data;
        public final long expireTime;
        public final int timeoutTaskId;

        PendingChatAction(Type type, Object data, int timeoutTaskId) {
            this(type, data, timeoutTaskId, System.currentTimeMillis() + 60000);
        }

        PendingChatAction(Type type, Object data, int timeoutTaskId, long expireTime) {
            this.type = type;
            this.data = data;
            this.expireTime = expireTime;
            this.timeoutTaskId = timeoutTaskId;
        }
    }

    public static class TeleportData {
        public final String warpId;
        public final double cost;
        public TeleportData(String warpId, double cost) {
            this.warpId = warpId;
            this.cost = cost;
        }
    }

    public static class ShareData {
        public final UUID from;
        public final String warpId;
        public ShareData(UUID from, String warpId) {
            this.from = from;
            this.warpId = warpId;
        }
    }

    public record TpaResponseData(UUID requester, moe.hinakusoft.funstart.manager.TpaManager.TpaType type) {}
}
