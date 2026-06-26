package moe.hinakusoft.funstart.listener;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import moe.hinakusoft.funstart.FunstartPlugin;
import moe.hinakusoft.funstart.model.ClaimRegion;
import moe.hinakusoft.funstart.model.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
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

public class ClaimGuiListener implements Listener {

    private final FunstartPlugin plugin;

    public ClaimGuiListener(FunstartPlugin plugin) {
        this.plugin = plugin;
    }

    public static void openMain(Player player, FunstartPlugin plugin) {
        Inventory inv = Bukkit.createInventory(new ClaimHolder(player), 27, "§6领地管理");
        ClaimRegion claim = plugin.getClaimManager().getClaimByOwner(plugin.getEffectiveUuid(player));

        if (claim == null) {
            inv.setItem(11, makeItem(Material.GRASS_BLOCK, "§a§l创建领地",
                "§7在当前位置圈地",
                "§7点击开始创建领地"));
            inv.setItem(15, makeItem(Material.BARRIER, "§7你没有领地",
                "§c点击创建领地按钮开始"));
        } else {
            long volume = claim.getVolume();
            inv.setItem(4, makeItem(Material.FILLED_MAP, "§6§l我的领地",
                "§7坐标: §f" + claim.getX1() + ", " + claim.getY1() + ", " + claim.getZ1(),
                "§7至: §f" + claim.getX2() + ", " + claim.getY2() + ", " + claim.getZ2(),
                "§7体积: §e" + volume + " §7方块",
                "§7信任人: §e" + claim.getTrustedPlayers().size() + " §7人"));

            inv.setItem(11, makeItem(Material.REDSTONE_BLOCK, "§c§l删除领地",
                "§7点击删除你的领地"));

            inv.setItem(13, makeItem(Material.PLAYER_HEAD, "§e§l管理信任人",
                "§7添加/移除信任人",
                "§7信任人可在领地内正常操作"));

            inv.setItem(15, makeItem(Material.LIME_DYE, "§a§l添加信任人",
                "§7点击后在聊天框输入玩家名"));

            // Settings section
            Material tntMat = claim.isAllowTnt() ? Material.GUNPOWDER : Material.BARRIER;
            String tntStatus = claim.isAllowTnt() ? "§a已启用" : "§c已禁用";
            inv.setItem(21, makeItem(tntMat, "§6§lTNT爆炸",
                "§7当前: " + tntStatus,
                "§7点击切换"));

            Material creeperMat = claim.isAllowCreeper() ? Material.GUNPOWDER : Material.BARRIER;
            String creeperStatus = claim.isAllowCreeper() ? "§a已启用" : "§c已禁用";
            inv.setItem(22, makeItem(creeperMat, "§6§l苦力怕爆炸",
                "§7当前: " + creeperStatus,
                "§7点击切换"));

            Material exMat = claim.isAllowExplosiveThrow() ? Material.FIREWORK_ROCKET : Material.BARRIER;
            String exStatus = claim.isAllowExplosiveThrow() ? "§a已启用" : "§c已禁用";
            inv.setItem(23, makeItem(exMat, "§5§l爆裂投掷",
                "§7当前: " + exStatus,
                "§7点击切换"));
        }

        inv.setItem(26, makeItem(Material.BARRIER, "§c关闭"));
        player.openInventory(inv);
    }

    public static void openTrustedList(Player player, FunstartPlugin plugin) {
        ClaimRegion claim = plugin.getClaimManager().getClaimByOwner(plugin.getEffectiveUuid(player));
        if (claim == null) {
            player.sendMessage("§c你没有领地");
            return;
        }
        Inventory inv = Bukkit.createInventory(new ClaimHolder(player, ClaimHolder.View.TRUSTED), 54, "§6信任人列表");
        int slot = 0;
        for (UUID tuid : claim.getTrustedPlayers()) {
            if (slot >= 45) break;
            OfflinePlayer op = Bukkit.getOfflinePlayer(tuid);
            String name = op.getName() != null ? op.getName() : "未知";
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta meta = (SkullMeta) head.getItemMeta();
            meta.setPlayerProfile(op.getPlayerProfile());
            meta.setDisplayName("§b" + name);
            meta.setLore(List.of("§c点击移除信任人"));
            head.setItemMeta(meta);
            inv.setItem(slot++, head);
        }
        if (slot == 0) {
            inv.setItem(22, makeItem(Material.BARRIER, "§7暂无信任人"));
        }
        inv.setItem(49, makeItem(Material.ARROW, "§a返回"));
        player.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof ClaimHolder holder)) return;
        event.setCancelled(true);
        Player player = (Player) event.getWhoClicked();
        if (!holder.getPlayer().equals(player)) return;
        int slot = event.getRawSlot();

        if (holder.getView() == ClaimHolder.View.MAIN) {
            if (slot == 11) {
                UUID effUuid = plugin.getEffectiveUuid(player);
                ClaimRegion existing = plugin.getClaimManager().getClaimByOwner(effUuid);
                if (existing == null) {
                    // Create claim
                    player.closeInventory();
                    plugin.getClaimManager().startClaimSession(player);
                } else {
                    // Delete claim
                    player.closeInventory();
                    plugin.getClaimManager().deleteClaim(effUuid);
                    player.sendMessage("§e[Funstart] §a已删除你的领地");
                }
            } else if (slot == 13) {
                openTrustedList(player, plugin);
            } else if (slot == 15) {
                if (plugin.getClaimManager().getClaimByOwner(plugin.getEffectiveUuid(player)) == null) return;
                // Add trusted - use chat
                player.closeInventory();
                plugin.addPendingChatAction(player.getUniqueId(),
                    FunstartPlugin.PendingChatAction.Type.ADD_TRUSTED, null);
                player.sendMessage("§a请输入要添加的信任人玩家名:");
            } else if (slot == 21 || slot == 22 || slot == 23) {
                ClaimRegion claim = plugin.getClaimManager().getClaimByOwner(plugin.getEffectiveUuid(player));
                if (claim == null) return;
                if (slot == 21) claim.setAllowTnt(!claim.isAllowTnt());
                if (slot == 22) claim.setAllowCreeper(!claim.isAllowCreeper());
                if (slot == 23) claim.setAllowExplosiveThrow(!claim.isAllowExplosiveThrow());
                plugin.getClaimManager().save();
                openMain(player, plugin);
            } else if (slot == 26) {
                player.closeInventory();
            }
        } else if (holder.getView() == ClaimHolder.View.TRUSTED) {
            if (slot == 49) {
                openMain(player, plugin);
                return;
            }
            if (slot < 0 || slot >= 45) return;
            ClaimRegion claim = plugin.getClaimManager().getClaimByOwner(plugin.getEffectiveUuid(player));
            if (claim == null) return;
            List<UUID> trusts = new ArrayList<>(claim.getTrustedPlayers());
            if (slot < trusts.size()) {
                UUID tid = trusts.get(slot);
                claim.getTrustedPlayers().remove(tid);
                plugin.getClaimManager().save();
                String name = Bukkit.getOfflinePlayer(tid).getName();
                player.sendMessage("§e[Funstart] §a已移除信任人 §b" + (name != null ? name : "未知"));
                openTrustedList(player, plugin);
            }
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof ClaimHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {}

    private static ItemStack makeItem(Material mat, String name, String... lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(List.of(lore));
        item.setItemMeta(meta);
        return item;
    }

    public static class ClaimHolder implements InventoryHolder {
        public enum View { MAIN, TRUSTED }
        private final Player player;
        private final View view;
        public ClaimHolder(Player player) { this(player, View.MAIN); }
        public ClaimHolder(Player player, View view) { this.player = player; this.view = view; }
        public Player getPlayer() { return player; }
        public View getView() { return view; }
        @Override public Inventory getInventory() { return null; }
    }
}
