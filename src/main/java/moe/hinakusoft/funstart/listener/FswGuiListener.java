package moe.hinakusoft.funstart.listener;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import moe.hinakusoft.funstart.FunstartPlugin;
import moe.hinakusoft.funstart.manager.WarpManager;
import moe.hinakusoft.funstart.manager.WarpManager.ShareRequest;
import moe.hinakusoft.funstart.model.PlayerData;
import moe.hinakusoft.funstart.model.WarpPoint;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
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
import org.bukkit.enchantments.Enchantment;
import moe.hinakusoft.funstart.model.ClaimRegion;

public class FswGuiListener implements Listener {

    private final FunstartPlugin plugin;

    public FswGuiListener(FunstartPlugin plugin) {
        this.plugin = plugin;
    }

    // ---- Open methods ----

    public static void openMainMenu(Player player, FunstartPlugin plugin) {
        Inventory inv = Bukkit.createInventory(new FswHolder(player, FswHolder.Type.MAIN), 27, "§8⚡ 传送点系统");

        inv.setItem(10, makeItem(Material.ENDER_PEARL, "§a§l传送传送点",
            "§7查看所有可用传送点", "§7点击传送或删除"));
        inv.setItem(12, makeItem(Material.COMPASS, "§6§l分享传送点",
            "§7分享你的传送点给其他玩家", "§7纯面板操作"));
        inv.setItem(14, makeItem(Material.NETHER_STAR, "§b§l创建传送点",
            "§7在当前位置创建传送点", "§a点击后在聊天框输入名称"));

        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        meta.setPlayerProfile(player.getPlayerProfile());
        meta.setDisplayName("§b" + player.getName());
        int warpCount = plugin.getWarpManager().getAvailableWarps(player).size();
        meta.setLore(List.of("§7可用传送点: §e" + warpCount,
            "§7拥有的传送点: §e" + plugin.getWarpManager().getOwnWarps(plugin.getEffectiveUuid(player)).size()));
        head.setItemMeta(meta);
        inv.setItem(22, head);

        player.openInventory(inv);
    }

    public static void openTeleportList(Player player, FunstartPlugin plugin, int page, boolean deleteMode) {
        UUID effUuid = plugin.getEffectiveUuid(player);
        ClaimRegion claim = plugin.getClaimManager().getClaimByOwner(effUuid);

        List<WarpPoint> list = new ArrayList<>();
        if (claim != null) {
            double cx = (claim.getX1() + claim.getX2()) / 2.0;
            double cy = claim.getY1();
            double cz = (claim.getZ1() + claim.getZ2()) / 2.0;
            list.add(new WarpPoint("__claim__", "§d我的领地", effUuid,
                claim.getWorldName(), cx, cy, cz, 0f, 0f));
        }
        list.addAll(plugin.getWarpManager().getAvailableWarps(player));

        int totalPages = Math.max(1, (list.size() + 44) / 45);
        if (page < 0) page = 0;
        if (page >= totalPages) page = totalPages - 1;

        String title = deleteMode ? "§c§l删除传送点" : "§8传送点 (第" + (page + 1) + "/" + totalPages + "页)";
        Inventory inv = Bukkit.createInventory(
            new FswHolder(player, FswHolder.Type.TELEPORT_LIST, page, deleteMode),
            54, title);

        int start = page * 45;
        int end = Math.min(start + 45, list.size());
        Location playerLoc = player.getLocation();

        for (int i = start; i < end; i++) {
            WarpPoint w = list.get(i);
            boolean isClaim = w.getId().equals("__claim__");
            boolean isShared = !isClaim && !w.getOwner().equals(plugin.getEffectiveUuid(player));

            Material mat;
            if (isClaim) {
                mat = Material.SHULKER_BOX;
            } else if (isShared) {
                mat = Material.PLAYER_HEAD;
            } else {
                String wn = w.getWorldName().toLowerCase();
                if (wn.contains("nether")) mat = Material.NETHERRACK;
                else if (wn.contains("end") || wn.equals("world_the_end")) mat = Material.END_STONE;
                else mat = Material.GRASS_BLOCK;
            }

            ItemStack item = new ItemStack(mat);
            ItemMeta im = item.getItemMeta();
            im.setDisplayName("§b" + w.getName());

            List<String> lore = new ArrayList<>();
            World warpWorld = Bukkit.getWorld(w.getWorldName());

            if (deleteMode) {
                lore.add("§c§l点击删除此传送点");
            } else {
                lore.add("§7世界: §f" + w.getWorldName());
                lore.add("§7坐标: §f" + (int) w.getX() + ", " + (int) w.getY() + ", " + (int) w.getZ());
                if (isClaim) {
                    lore.add("§a免费传送");
                } else if (warpWorld != null) {
                    Location warpLoc = new Location(warpWorld, w.getX(), w.getY(), w.getZ());
                    lore.add("§7距离: §f" + formatDistance(playerLoc, warpLoc));
                    double cost = calcCost(playerLoc, warpLoc);
                    lore.add("§e传送: " + PlayerData.fmt(cost) + " 点");
                } else {
                    lore.add("§c世界不可用");
                }
            }

            if (isShared) {
                String ownerName = Bukkit.getOfflinePlayer(w.getOwner()).getName();
                lore.add("§7分享者: §b" + (ownerName != null ? ownerName : "未知"));
            }
            im.setLore(lore);

            if (isShared) {
                SkullMeta sm = (SkullMeta) im;
                sm.setPlayerProfile(Bukkit.getOfflinePlayer(w.getOwner()).getPlayerProfile());
            }

            item.setItemMeta(im);
            inv.setItem(i - start, item);
        }

        if (page > 0)
            inv.setItem(45, makeItem(Material.ARROW, "§a上一页", "§7第" + page + "页"));

        if (deleteMode) {
            inv.setItem(46, makeItem(Material.REDSTONE_BLOCK, "§c§l退出删除模式",
                "§7点击退出删除模式"));
        } else {
            inv.setItem(46, makeItem(Material.TNT, "§c§l删除传送点",
                "§7点击进入删除模式", "§c注意: 只能删除自己的传送点"));
        }

        inv.setItem(49, makeItem(Material.BARRIER, "§c返回"));

        // Random teleport button (right side)
        inv.setItem(52, makeItem(Material.COMPASS, "§5§l随机传送",
            "§7消耗 §e10 §7点数",
            "§7优先在未探索区块传送",
            "§7避开领地周围5区块",
            "§a点击随机传送"));

        if (page + 1 < totalPages)
            inv.setItem(53, makeItem(Material.ARROW, "§a下一页", "§7第" + (page + 2) + "页"));

        player.openInventory(inv);
    }

    public static void openShareSelection(Player player, FunstartPlugin plugin) {
        List<WarpPoint> list = plugin.getWarpManager().getOwnWarps(plugin.getEffectiveUuid(player));
        Inventory inv = Bukkit.createInventory(
            new FswHolder(player, FswHolder.Type.SHARE_SELECT), 54,
            "§6选择要分享的传送点");

        int slot = 0;
        for (WarpPoint w : list) {
            if (slot >= 45) break;
            String wn = w.getWorldName().toLowerCase();
            Material mat = wn.contains("nether") ? Material.NETHERRACK
                : wn.contains("end") || wn.equals("world_the_end") ? Material.END_STONE
                : Material.GRASS_BLOCK;
            ItemStack item = makeItem(mat, "§b" + w.getName(),
                "§7世界: §f" + w.getWorldName(),
                "§7坐标: §f" + (int) w.getX() + ", " + (int) w.getY() + ", " + (int) w.getZ(),
                "§e点击选择分享");
            inv.setItem(slot++, item);
        }
        inv.setItem(49, makeItem(Material.BARRIER, "§c返回"));
        player.openInventory(inv);
    }

    public static void openShareTarget(Player player, String warpId, FunstartPlugin plugin) {
        Inventory inv = Bukkit.createInventory(
            new FswHolder(player, FswHolder.Type.SHARE_TARGET, warpId), 54,
            "§6选择要分享的玩家");

        int slot = 0;
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.equals(player)) continue;
            if (slot >= 45) break;
            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta sm = (SkullMeta) head.getItemMeta();
            sm.setPlayerProfile(online.getPlayerProfile());
            sm.setDisplayName("§b" + online.getName());
            head.setItemMeta(sm);
            inv.setItem(slot++, head);
        }
        inv.setItem(49, makeItem(Material.BARRIER, "§c返回"));
        player.openInventory(inv);
    }

    public static void openPendingShares(Player player, FunstartPlugin plugin) {
        List<ShareRequest> pending = plugin.getWarpManager().getPendingShares(player.getUniqueId());
        Inventory inv = Bukkit.createInventory(
            new FswHolder(player, FswHolder.Type.PENDING), 54,
            "§d待处理的分享请求");

        int slot = 0;
        for (ShareRequest sr : pending) {
            if (slot + 2 >= 54) break;
            UUID fromUuid = sr.getFrom();
            String fromName = Bukkit.getOfflinePlayer(fromUuid).getName();
            WarpPoint wp = plugin.getWarpManager().getWarp(sr.getWarpId());
            String warpName = wp != null ? wp.getName() : "§c已删除";

            ItemStack head = new ItemStack(Material.PLAYER_HEAD);
            SkullMeta sm = (SkullMeta) head.getItemMeta();
            sm.setPlayerProfile(Bukkit.getOfflinePlayer(fromUuid).getPlayerProfile());
            sm.setDisplayName("§b" + (fromName != null ? fromName : "未知"));
            sm.setLore(List.of("§7传送点: §f" + warpName));
            head.setItemMeta(sm);
            inv.setItem(slot++, head);

            inv.setItem(slot++, makeItem(Material.GREEN_CONCRETE, "§a§l接受",
                "§7点击接受 §b" + (fromName != null ? fromName : "未知") + " §7的分享"));
            inv.setItem(slot++, makeItem(Material.RED_CONCRETE, "§c§l拒绝",
                "§7点击拒绝 §b" + (fromName != null ? fromName : "未知") + " §7的分享"));
        }
        inv.setItem(49, makeItem(Material.BARRIER, "§c返回"));
        player.openInventory(inv);
    }

    // ---- Event handlers ----

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof FswHolder holder)) return;
        event.setCancelled(true);

        Player player = (Player) event.getWhoClicked();
        if (!holder.getPlayer().equals(player)) return;

        int slot = event.getRawSlot();
        if (slot < 0 || slot >= event.getInventory().getSize()) return;

        switch (holder.getType()) {
            case MAIN -> handleMainClick(player, slot);
            case TELEPORT_LIST -> handleTeleportListClick(player, slot, holder.getPage(), holder.isDeleteMode());
            case SHARE_SELECT -> handleShareSelectClick(player, slot);
            case SHARE_TARGET -> handleShareTargetClick(player, slot, holder.getWarpId());
            case PENDING -> handlePendingClick(player, slot);
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof FswHolder) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {}

    // ---- Click handlers ----

    private void handleMainClick(Player player, int slot) {
        switch (slot) {
            case 10 -> openTeleportList(player, plugin, 0, false);
            case 12 -> openShareSelection(player, plugin);
            case 14 -> {
                player.closeInventory();
                plugin.addPendingChatAction(player.getUniqueId(),
                    FunstartPlugin.PendingChatAction.Type.ADD_WARP, null);
                plugin.getPendingWarpCreation().put(player.getUniqueId(), player.getLocation().clone());
                player.sendMessage("§a请输入传送点名称 (1-20字):");
                player.sendMessage("§7移动5格距离可取消创建");
            }
        }
    }

    private void handleTeleportListClick(Player player, int slot, int page, boolean deleteMode) {
        if (slot == 49) {
            openMainMenu(player, plugin);
            return;
        }
        if (slot == 45 && page > 0) {
            openTeleportList(player, plugin, page - 1, deleteMode);
            return;
        }
        if (slot == 46) {
            openTeleportList(player, plugin, page, !deleteMode);
            return;
        }
        if (slot == 52) {
            handleRandomTeleport(player);
            return;
        }
        if (slot == 53) {
            openTeleportList(player, plugin, page + 1, deleteMode);
            return;
        }
        if (slot < 0 || slot >= 45) return;

        UUID effUuid = plugin.getEffectiveUuid(player);
        ClaimRegion claim = plugin.getClaimManager().getClaimByOwner(effUuid);

        List<WarpPoint> list = new ArrayList<>();
        if (claim != null) {
            double cx = (claim.getX1() + claim.getX2()) / 2.0;
            double cy = claim.getY1();
            double cz = (claim.getZ1() + claim.getZ2()) / 2.0;
            list.add(new WarpPoint("__claim__", "§d我的领地", effUuid,
                claim.getWorldName(), cx, cy, cz, 0f, 0f));
        }
        list.addAll(plugin.getWarpManager().getAvailableWarps(player));

        int idx = page * 45 + slot;
        if (idx < 0 || idx >= list.size()) return;

        WarpPoint wp = list.get(idx);
        boolean isClaim = wp.getId().equals("__claim__");
        boolean isOwn = !isClaim && wp.getOwner().equals(effUuid);

        if (deleteMode) {
            if (isClaim) {
                player.sendMessage("§c请在领地管理界面删除领地");
                return;
            } else if (!isOwn) {
                // Delete shared warp locally
                plugin.getPlayerDataManager().getPlayerData(player).getAcceptedShares().remove(wp.getId());
                plugin.getPlayerDataManager().savePlayerData(player);
                player.sendMessage("§e[Funstart] §a已移除分享传送点 §b" + wp.getName());
                openTeleportList(player, plugin, page, deleteMode);
            } else {
                plugin.getWarpManager().deleteWarp(wp.getId(), player.getUniqueId());
                plugin.getPlayerDataManager().cleanAcceptedShares(wp.getId());
                player.sendMessage("§e[Funstart] §a已删除传送点 §b" + wp.getName());
                openTeleportList(player, plugin, page, deleteMode);
            }
            return;
        }

        // Teleport mode
        World world = Bukkit.getWorld(wp.getWorldName());
        if (world == null) {
            player.sendMessage("§c传送点所在世界不可用");
            return;
        }
        Location loc = new Location(world, wp.getX(), wp.getY(), wp.getZ(), wp.getYaw(), wp.getPitch());
        PlayerData data = plugin.getPlayerDataManager().getPlayerData(player);

        if (isClaim) {
            // Free teleport to claim
            player.closeInventory();
            player.teleportAsync(loc).thenAccept(success -> {
                if (success) {
                    player.sendMessage("§e[Funstart] §a已传送到领地, §a免费");
                } else {
                    player.sendMessage("§c传送失败");
                }
            });
            return;
        }

        double cost = calcCost(player.getLocation(), loc);
        if (data.getPoints() < cost) {
            player.sendMessage("§c点数不足! 需要 §e" + PlayerData.fmt(cost) + " §c点");
            return;
        }
        data.deductPoints(cost);
        double remaining = data.getPoints();
        player.closeInventory();
        player.teleportAsync(loc).thenAccept(success -> {
            if (success) {
                player.sendMessage("§e[Funstart] §a已传送到 §b" + wp.getName() + " §a, 消耗 §e" + PlayerData.fmt(cost) + " §a点, 剩余 §e" + PlayerData.fmt(remaining) + " §a点");
            } else {
                data.addPoints(cost);
                plugin.getPlayerDataManager().savePlayerData(player);
                player.sendMessage("§c传送失败，点数已退还");
            }
        });
    }

    private void handleShareSelectClick(Player player, int slot) {
        if (slot == 49) {
            openMainMenu(player, plugin);
            return;
        }
        if (slot < 0 || slot >= 45) return;

        List<WarpPoint> list = plugin.getWarpManager().getOwnWarps(plugin.getEffectiveUuid(player));
        if (slot >= list.size()) return;
        WarpPoint wp = list.get(slot);
        openShareTarget(player, wp.getId(), plugin);
    }

    private void handleShareTargetClick(Player player, int slot, String warpId) {
        if (slot == 49) {
            openShareSelection(player, plugin);
            return;
        }
        if (slot < 0 || slot >= 45) return;

        WarpPoint wp = plugin.getWarpManager().getWarp(warpId);
        if (wp == null) {
            player.sendMessage("§c该传送点已不存在");
            player.closeInventory();
            return;
        }

        List<Player> online = new ArrayList<>(Bukkit.getOnlinePlayers());
        online.remove(player);
        if (slot >= online.size()) {
            player.sendMessage("§c玩家列表已变化");
            player.closeInventory();
            return;
        }
        Player target = online.get(slot);

        plugin.getWarpManager().sendShareRequest(player.getUniqueId(), target.getUniqueId(), warpId);
        String wName = wp.getName();
        player.closeInventory();
        player.sendMessage("§e[Funstart] §a已向 §b" + target.getName() + " §a分享传送点 §b" + wName);

        // Open acceptance GUI for target
        openPendingShares(target, plugin);
    }

    private void handlePendingClick(Player player, int slot) {
        if (slot == 49) {
            player.closeInventory();
            return;
        }
        if (slot < 0 || slot >= 54) return;

        List<ShareRequest> pending = plugin.getWarpManager().getPendingShares(player.getUniqueId());
        int reqIdx = slot / 3;
        int subSlot = slot % 3;

        if (reqIdx >= pending.size()) return;
        ShareRequest sr = pending.get(reqIdx);
        UUID fromUuid = sr.getFrom();
        String fromName = Bukkit.getOfflinePlayer(fromUuid).getName();

        if (subSlot == 0) return;

        if (subSlot == 1) {
            ShareRequest accepted = plugin.getWarpManager().acceptShare(player.getUniqueId(), fromUuid);
            if (accepted == null) {
                player.sendMessage("§c该分享请求已失效");
                openPendingShares(player, plugin);
                return;
            }
            WarpPoint wp = plugin.getWarpManager().getWarp(accepted.getWarpId());
            if (wp == null) {
                player.sendMessage("§c该传送点已不存在");
                openPendingShares(player, plugin);
                return;
            }
            PlayerData data = plugin.getPlayerDataManager().getPlayerData(player);
            data.getAcceptedShares().add(accepted.getWarpId());
            plugin.getPlayerDataManager().savePlayerData(player);
            player.sendMessage("§e[Funstart] §a已接受 §b" + (fromName != null ? fromName : "未知") + " §a的传送点 §b" + wp.getName());
            Player from = Bukkit.getPlayer(fromUuid);
            if (from != null && from.isOnline()) {
                from.sendMessage("§e[Funstart] §b" + player.getName() + " §a已接受你分享的传送点 §b" + wp.getName());
            }
            openPendingShares(player, plugin);
        } else if (subSlot == 2) {
            plugin.getWarpManager().denyShare(player.getUniqueId(), fromUuid);
            player.sendMessage("§c已拒绝 §b" + (fromName != null ? fromName : "未知") + " §c的分享请求");
            Player from = Bukkit.getPlayer(fromUuid);
            if (from != null && from.isOnline()) {
                from.sendMessage("§e[Funstart] §b" + player.getName() + " §c已拒绝你的分享请求");
            }
            openPendingShares(player, plugin);
        }
    }

    // ---- InventoryHolder ----

    public static class FswHolder implements InventoryHolder {
        enum Type { MAIN, TELEPORT_LIST, SHARE_SELECT, SHARE_TARGET, PENDING }

        private final Player player;
        private final Type type;
        private final int page;
        private final String warpId;
        private final boolean deleteMode;

        public FswHolder(Player player, Type type) {
            this(player, type, 0, false, null);
        }

        public FswHolder(Player player, Type type, int page, boolean deleteMode) {
            this(player, type, page, deleteMode, null);
        }

        public FswHolder(Player player, Type type, String warpId) {
            this(player, type, 0, false, warpId);
        }

        private FswHolder(Player player, Type type, int page, boolean deleteMode, String warpId) {
            this.player = player;
            this.type = type;
            this.page = page;
            this.deleteMode = deleteMode;
            this.warpId = warpId;
        }

        public Player getPlayer() { return player; }
        public Type getType() { return type; }
        public int getPage() { return page; }
        public boolean isDeleteMode() { return deleteMode; }
        public String getWarpId() { return warpId; }

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

    public static String formatDistance(Location from, Location to) {
        if (from.getWorld() == null || to.getWorld() == null || !from.getWorld().equals(to.getWorld())) {
            return "§c跨世界";
        }
        double dist = from.distance(to);
        if (dist < 1000) {
            return String.format("%.0f §7米", dist);
        }
        return String.format("%.1f §7千米", dist / 1000.0);
    }

    public static double calcCost(Location from, Location to) {
        if (from.getWorld() == null || to.getWorld() == null || !from.getWorld().equals(to.getWorld())) {
            return 100.0;
        }
        return Math.max(1.0, from.distance(to) / 100.0);
    }

    private void handleRandomTeleport(Player player) {
        PlayerData data = plugin.getPlayerDataManager().getPlayerData(player);
        if (data.getPoints() < 10) {
            player.sendMessage("§c点数不足! 需要 §e10 §c点");
            return;
        }

        World world = player.getWorld();
        List<ClaimRegion> allClaims = plugin.getClaimManager().getAllClaims();
        int radius = 10000;
        int centerX = 0, centerZ = 0;
        int minY = world.getMinHeight() + 5;
        int maxY = world.getMaxHeight() - 5;
        int attempts = 100;

        Location bestLoc = null;
        boolean foundUnexplored = false;

        for (int i = 0; i < attempts; i++) {
            int x = centerX + (int) (Math.random() * 2 * radius - radius);
            int z = centerZ + (int) (Math.random() * 2 * radius - radius);

            int chunkX = x >> 4, chunkZ = z >> 4;
            boolean isUnexplored = !world.isChunkGenerated(chunkX, chunkZ);

            if (!foundUnexplored && !isUnexplored) continue;

            boolean nearClaim = false;
            for (ClaimRegion claim : allClaims) {
                if (claim.getWorldName().equals(world.getName())) {
                    double cx = (claim.getX1() + claim.getX2()) / 2.0;
                    double cz = (claim.getZ1() + claim.getZ2()) / 2.0;
                    if (Math.abs(x - cx) < 5 * 16 || Math.abs(z - cz) < 5 * 16) {
                        nearClaim = true;
                        break;
                    }
                }
            }
            if (nearClaim) continue;

            int y = getSafeY(world, x, z, minY, maxY);
            if (y < 0) continue;

            if (isUnexplored || !foundUnexplored) {
                bestLoc = new Location(world, x + 0.5, y, z + 0.5);
                if (isUnexplored) foundUnexplored = true;
                break;
            }
        }

        if (bestLoc == null) {
            // Fallback: just find any spot away from claims
            for (int i = 0; i < attempts * 2; i++) {
                int fx = centerX + (int) (Math.random() * 2 * radius - radius);
                int fz = centerZ + (int) (Math.random() * 2 * radius - radius);

                boolean nearClaim = false;
                for (ClaimRegion claim : allClaims) {
                    if (claim.getWorldName().equals(world.getName())) {
                        double cx = (claim.getX1() + claim.getX2()) / 2.0;
                        double cz = (claim.getZ1() + claim.getZ2()) / 2.0;
                        if (Math.abs(fx - cx) < 5 * 16 || Math.abs(fz - cz) < 5 * 16) {
                            nearClaim = true;
                            break;
                        }
                    }
                }
                if (nearClaim) continue;

                int y = getSafeY(world, fx, fz, minY, maxY);
                if (y < 0) continue;

                bestLoc = new Location(world, fx + 0.5, y, fz + 0.5);
                break;
            }
        }

        if (bestLoc == null) {
            player.sendMessage("§c未找到合适的传送位置，请稍后再试");
            return;
        }

        data.deductPoints(10);
        plugin.getPlayerDataManager().savePlayerData(player.getUniqueId());
        player.closeInventory();
        player.teleportAsync(bestLoc).thenAccept(success -> {
            if (success) {
                player.sendMessage("§5[随机传送] §a已传送, 消耗 §e10 §a点, 剩余 §e" + PlayerData.fmt(data.getPoints()) + " §a点");
            } else {
                data.addPoints(10);
                plugin.getPlayerDataManager().savePlayerData(player.getUniqueId());
                player.sendMessage("§5[随机传送] §c传送失败，点数已退还");
            }
        });
    }

    private int getSafeY(World world, int x, int z, int minY, int maxY) {
        int y = world.getHighestBlockYAt(x, z);
        if (y < minY || y > maxY) return -1;
        if (world.getBlockAt(x, y + 1, z).getType().isSolid()) return -1;
        return y + 1;
    }
}
