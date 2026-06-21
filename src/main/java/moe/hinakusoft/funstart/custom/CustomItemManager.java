package moe.hinakusoft.funstart.custom;

import moe.hinakusoft.funstart.FunstartPlugin;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 自定义物品管理器。
 * <p>
 * 职责：注册并定期 tick 所有 CustomItem 实例，同时通过事件监听维护
 * "持有自定义物品的玩家 × 槽位"映射。
 * <p>
 * == 事件驱动设计 ==
 * 原方案：每 3 tick 遍历所有在线玩家 × 41 个背包槽位
 * （100 人时 ≈ 27,000 次 API 调用/秒）。
 * 现方案：仅在玩家加入或切换物品时全量扫描一次 → 记录持有自定义物品的槽位
 * → 每 tick 仅迭代实际持有物品的玩家，消除无效扫描。
 * <p>
 * == 限定条件 ==
 * 当前仅 FSTFood 注册为自定义物品，其 onTick 仅响应主手持物品。
 * 若新增需监听其他槽位的物品，可扩展 trackedSlots 的槽位范围。
 */
public class CustomItemManager implements Listener {

    /** 已注册的自定义物品类型列表，在 onEnable 时填充 */
    private final List<CustomItem> items = new ArrayList<>();
    /**
     * 玩家 → 持有自定义物品的槽位集合。
     * 仅包含实际持有物品的玩家，空玩家不在 map 中。
     * 槽位：0-39 背包+快捷栏，40 副手。
     */
    private final Map<UUID, Set<Integer>> trackedSlots = new ConcurrentHashMap<>();
    /** 定时 tick 任务 */
    private BukkitTask task;
    /**
     * 插件主类引用
     */
    private FunstartPlugin plugin;

    /** 注册一个自定义物品类型，在 startTicking 前调用 */
    public void register(CustomItem item) {
        items.add(item);
    }

    public void startTicking(FunstartPlugin plugin) {
        this.plugin = plugin;
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
        task = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            for (Player player : Bukkit.getOnlinePlayers()) {
                Set<Integer> slots = trackedSlots.get(player.getUniqueId());
                if (slots == null || slots.isEmpty()) continue;
                for (int slot : slots) {
                    ItemStack item = getItemBySlot(player, slot);
                    if (item == null || item.getType().isAir()) continue;
                    for (CustomItem ci : items) {
                        if (ci.isItem(item)) {
                            ci.onTick(player, item, slot);
                        }
                    }
                }
            }
        }, 0L, 3L);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /**
     * 玩家加入服务器时扫描背包，初始化 trackedSlots
     */
    @EventHandler
    public void onPlayerJoin(PlayerJoinEvent event) {
        rescanPlayer(event.getPlayer());
    }

    /**
     * 玩家退出服务器时清理 trackedSlots，避免内存泄漏
     */
    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        trackedSlots.remove(event.getPlayer().getUniqueId());
    }

    /**
     * 玩家切换手持物品时重新扫描，确保 trackedSlots 准确
     */
    @EventHandler
    public void onItemHeld(PlayerItemHeldEvent event) {
        rescanPlayer(event.getPlayer());
    }

    /**
     * 全量扫描玩家的 41 个槽位，更新 trackedSlots。
     * 耗时与在线玩家总数无关，仅在事件触发时执行（非每 tick）。
     * 若玩家无自定义物品，从映射中移除以节省 tick 开销。
     */
    public void rescanPlayer(Player player) {
        if (items.isEmpty()) return;
        UUID uuid = player.getUniqueId();
        Set<Integer> slots = new HashSet<>();
        for (int slot = 0; slot <= 40; slot++) {
            ItemStack item = getItemBySlot(player, slot);
            if (item == null || item.getType().isAir()) continue;
            for (CustomItem ci : items) {
                if (ci.isItem(item)) {
                    slots.add(slot);
                    break;
                }
            }
        }
        if (slots.isEmpty()) {
            trackedSlots.remove(uuid);
        } else {
            trackedSlots.put(uuid, slots);
        }
    }

    private ItemStack getItemBySlot(Player player, int slot) {
        if (slot >= 0 && slot <= 39) {
            return player.getInventory().getItem(slot);
        } else if (slot == 40) {
            return player.getInventory().getItemInOffHand();
        }
        return null;
    }
}
