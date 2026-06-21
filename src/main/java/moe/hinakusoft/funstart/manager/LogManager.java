package moe.hinakusoft.funstart.manager;

import moe.hinakusoft.funstart.FunstartPlugin;
import moe.hinakusoft.funstart.model.ClaimRegion;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 异步日志管理器。
 * <p>
 * 设计目标：将所有文件 I/O 从 Bukkit 主线程剥离，杜绝日志写入导致的 TPS 抖动。
 * <p>
 * == 异步架构 ==
 * 所有公开的 logXxx() 方法仅执行两件事：
 * 1. 在事件线程（主线程）中格式化日志字符串（轻量，毫秒级）
 * 2. 将格式化后的字符串封装为 LogEntry 投入 LinkedBlockingQueue
 * 虚拟线程消费者 (funstart-logger) 持续从队列批量拉取（最多 100 条/批），
 * 在独立线程中执行实际的 File I/O。
 * <p>
 * == 队列行为 ==
 * - 消费者每 2 秒检查一次队列（poll + 超时），空闲时不会空转
 * - 生产速度超过消费速度时，队列自然堆积，但不阻塞主线程
 * - 插件关闭时 (onDisable)，等待消费者最多 5 秒排空剩余条目
 * <p>
 * == 材料常量 ==
 * DANGEROUS / VALUABLE / CONTAINER_BLOCKS 使用 EnumSet<Material> 替代 String 集合，
 * 避免每次判断时进行 type.name() 字符串构造和哈希。
 */
public class LogManager {

    /**
     * 单文件上限：5MB
     */
    private static final long MAX_FILE_SIZE = 5L * 1024 * 1024;
    private static final SimpleDateFormat DATE_FMT = new SimpleDateFormat("yyyy-MM-dd");
    private static final SimpleDateFormat TS_FMT = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.S");

    /**
     * 危险物品/方块集合：被破坏或拾取时将额外记录到 .other 文件。
     * 包括 TNT、岩浆、打火石、凋零骷髅头等可能造成破坏的物品。
     */
    private static final Set<Material> DANGEROUS = EnumSet.of(
            Material.TNT, Material.TNT_MINECART, Material.RESPAWN_ANCHOR, Material.END_CRYSTAL,
            Material.FLINT_AND_STEEL, Material.FIRE_CHARGE, Material.LAVA_BUCKET, Material.LAVA,
            Material.WITHER_SKELETON_SKULL, Material.CREEPER_HEAD
    );
    /**
     * 贵重物品集合：钻石、下界合金、附魔金苹果、鞘翅、潜影盒等。
     * 被挖掘或拾取时将额外记录到 .other 文件。
     */
    private static final Set<Material> VALUABLE = EnumSet.of(
            Material.DIAMOND, Material.DIAMOND_BLOCK, Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE,
            Material.NETHERITE_INGOT, Material.NETHERITE_BLOCK, Material.NETHERITE_SCRAP,
            Material.NETHERITE_SWORD, Material.NETHERITE_PICKAXE, Material.NETHERITE_AXE,
            Material.NETHERITE_SHOVEL, Material.NETHERITE_HOE, Material.NETHERITE_HELMET,
            Material.NETHERITE_CHESTPLATE, Material.NETHERITE_LEGGINGS, Material.NETHERITE_BOOTS,
            Material.ENCHANTED_GOLDEN_APPLE, Material.TRIDENT, Material.ELYTRA,
            Material.SHULKER_BOX, Material.WHITE_SHULKER_BOX, Material.ORANGE_SHULKER_BOX,
            Material.MAGENTA_SHULKER_BOX, Material.LIGHT_BLUE_SHULKER_BOX, Material.YELLOW_SHULKER_BOX,
            Material.LIME_SHULKER_BOX, Material.PINK_SHULKER_BOX, Material.GRAY_SHULKER_BOX,
            Material.LIGHT_GRAY_SHULKER_BOX, Material.CYAN_SHULKER_BOX, Material.PURPLE_SHULKER_BOX,
            Material.BLUE_SHULKER_BOX, Material.BROWN_SHULKER_BOX, Material.GREEN_SHULKER_BOX,
            Material.RED_SHULKER_BOX, Material.BLACK_SHULKER_BOX
    );
    /**
     * 容器方块集合：箱子、熔炉、漏斗、潜影盒等。
     * 在领地内破坏容器时会触发额外标记，用于反盗保护。
     */
    private static final Set<Material> CONTAINER_BLOCKS = EnumSet.of(
            Material.CHEST, Material.TRAPPED_CHEST, Material.FURNACE, Material.BLAST_FURNACE,
            Material.SMOKER, Material.BARREL, Material.HOPPER, Material.DROPPER, Material.DISPENSER,
            Material.BREWING_STAND, Material.COMPOSTER,
            Material.SHULKER_BOX,
            Material.WHITE_SHULKER_BOX, Material.ORANGE_SHULKER_BOX, Material.MAGENTA_SHULKER_BOX,
            Material.LIGHT_BLUE_SHULKER_BOX, Material.YELLOW_SHULKER_BOX, Material.LIME_SHULKER_BOX,
            Material.PINK_SHULKER_BOX, Material.GRAY_SHULKER_BOX, Material.LIGHT_GRAY_SHULKER_BOX,
            Material.CYAN_SHULKER_BOX, Material.PURPLE_SHULKER_BOX, Material.BLUE_SHULKER_BOX,
            Material.BROWN_SHULKER_BOX, Material.GREEN_SHULKER_BOX, Material.RED_SHULKER_BOX,
            Material.BLACK_SHULKER_BOX
    );

    /** 日志分类：方块变更、玩家移动、加入/退出、物品拾取、战斗 */
    private static final String[] CATEGORIES = {"playerChunk", "playerMove", "playerJoin", "playerItem", "playerAttack"};
    /** 插件主类引用 */
    private final FunstartPlugin plugin;
    /** 日志根目录 {插件数据目录}/logs/ */
    private final File logsDir;
    /** 当前归档文件缓存 key="{分类}/{日期}" → 文件对象，超 5MB 自动滚动 */
    private final Map<String, File> currentArchive = new HashMap<>();
    /** 归档分卷号 key={分类}/{日期} → 当前卷号 */
    private final Map<String, Integer> archivePart = new HashMap<>();
    /** 已打开的 BufferedWriter 缓存（全文写入，按绝对路径索引） */
    private final Map<String, BufferedWriter> openWriters = new HashMap<>();
    /**
     * 异步日志队列：事件线程入队，消费者线程出队
     */
    private final LinkedBlockingQueue<LogEntry> logQueue = new LinkedBlockingQueue<>();
    /** 攻击时间戳列表（用于 1 秒内攻击频率检测） */
    private final Map<UUID, List<Long>> attackTimes = new HashMap<>();
    /** 攻击目标集合（用于多目标攻击标记） */
    private final Map<UUID, Set<UUID>> attackTargets = new HashMap<>();
    /** 玩家受伤日志节流（同一玩家 500ms 内只记一条） */
    private final Map<UUID, Long> lastDamageLog = new HashMap<>();

    // ===================================================================
    // 以下状态仅在事件线程中读写，不需要同步
    // ===================================================================
    /**
     * 玩家最后位置记录（用于判断是否跨世界）
     */
    private final Map<UUID, Location> lastMoveLoc = new ConcurrentHashMap<>();
    /**
     * 玩家最后移动时间戳（用于速度计算）
     */
    private final Map<UUID, Long> lastMoveTime = new ConcurrentHashMap<>();
    /**
     * IP → UUID 映射（用于检测同 IP 多账号）
     */
    private final Map<String, UUID> ipPlayerMap = new ConcurrentHashMap<>();
    /**
     * 消费者线程运行标志
     */
    private volatile boolean running = true;
    /**
     * 消费者线程引用
     */
    private Thread consumerThread;
    public LogManager(FunstartPlugin plugin) {
        this.plugin = plugin;
        this.logsDir = new File(plugin.getDataFolder(), "logs");
        initDirectories();
        this.consumerThread = Thread.ofVirtual().name("funstart-logger").start(this::logConsumer);
    }

    private void logConsumer() {
        List<LogEntry> batch = new ArrayList<>();
        while (running) {
            try {
                batch.clear();
                LogEntry first = logQueue.poll(2, TimeUnit.SECONDS);
                if (first == null) continue;
                batch.add(first);
                logQueue.drainTo(batch, 100);
                for (LogEntry entry : batch) {
                    writeToFile(entry.category(), entry.line(), entry.isOther());
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        List<LogEntry> remaining = new ArrayList<>();
        logQueue.drainTo(remaining);
        for (LogEntry entry : remaining) {
            writeToFile(entry.category(), entry.line(), entry.isOther());
        }
        closeAllWriters();
    }

    private void queue(String category, String line) {
        logQueue.add(new LogEntry(category, line, false));
    }

    private void queueOther(String category, String line) {
        logQueue.add(new LogEntry(category, line, true));
    }

    private void writeToFile(String category, String line, boolean isOther) {
        if (isOther) {
            appendLine(getOtherFile(category), line);
        } else {
            appendLine(getArchiveFile(category), line);
        }
    }

    // ---- File I/O (consumer thread only) ----

    private File getArchiveFile(String category) {
        String date = DATE_FMT.format(new Date());
        String key = category + "/" + date;
        File f = currentArchive.get(key);
        if (f != null && f.exists() && f.length() < MAX_FILE_SIZE) return f;

        File dir = new File(logsDir, category + "/archive");
        int part = archivePart.getOrDefault(key, 0) + 1;
        File candidate;
        do {
            String name = part == 1 ? date + ".log" : date + "_" + part + ".log";
            candidate = new File(dir, name);
            part++;
        } while (candidate.exists() && candidate.length() >= MAX_FILE_SIZE);

        archivePart.put(key, part - 1);
        currentArchive.put(key, candidate);
        return candidate;
    }

    private File getOtherFile(String category) {
        String date = DATE_FMT.format(new Date());
        return new File(new File(logsDir, category + "/other"), date + ".log");
    }

    private void closeAllWriters() {
        for (BufferedWriter w : openWriters.values()) {
            try {
                w.close();
            } catch (IOException ignored) {
            }
        }
        openWriters.clear();
    }

    private BufferedWriter getWriter(File file) {
        String path = file.getAbsolutePath();
        BufferedWriter w = openWriters.get(path);
        if (w != null) return w;

        try {
            file.getParentFile().mkdirs();
            w = new BufferedWriter(new FileWriter(file, true));
            openWriters.put(path, w);
            return w;
        } catch (IOException e) {
            plugin.getLogger().warning("日志打开失败 (" + file + "): " + e.getMessage());
            return null;
        }
    }

    private void appendLine(File file, String line) {
        try {
            BufferedWriter w = getWriter(file);
            if (w == null) return;
            w.write(line);
            w.newLine();
        } catch (IOException e) {
            plugin.getLogger().warning("日志写入失败 (" + file + "): " + e.getMessage());
        }
    }

    private void initDirectories() {
        for (String cat : CATEGORIES) {
            new File(logsDir, cat + "/latest").mkdirs();
            new File(logsDir, cat + "/archive").mkdirs();
            new File(logsDir, cat + "/other").mkdirs();
        }
    }

    private String timestamp() {
        return TS_FMT.format(new Date());
    }

    public void onDisable() {
        running = false;
        if (consumerThread != null) {
            try {
                consumerThread.join(5000);
            } catch (InterruptedException ignored) {}
        }
        for (String cat : CATEGORIES) {
            File latestDir = new File(logsDir, cat + "/latest");
            File latestFile = new File(latestDir, "current.log");
            File archiveDir = new File(logsDir, cat + "/archive");
            File[] files = archiveDir.listFiles((d, n) -> n.endsWith(".log"));
            if (files == null || files.length == 0) {
                latestFile.delete();
                continue;
            }
            File newest = null;
            long newestMod = 0;
            for (File f : files) {
                if (f.lastModified() > newestMod) {
                    newestMod = f.lastModified();
                    newest = f;
                }
            }
            if (newest == null) continue;
            try {
                Files.copy(newest.toPath(), latestFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                plugin.getLogger().warning("无法更新最新日志: " + e.getMessage());
            }
        }
    }

    // ---- Public API (called from event thread, queues file I/O) ----

    public void logBlockBreak(Player player, Block block, boolean isOp, ItemStack tool) {
        try {
            String ts = timestamp();
            Location loc = block.getLocation();
            String world = loc.getWorld() != null ? loc.getWorld().getName() : "?";
            String coords = loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
            Material type = block.getType();
            String claimInfo = getClaimInfo(loc);

            String toolInfo = "空手";
            if (tool != null && tool.getType() != Material.AIR) {
                String fstId = plugin.getFstItemIdManager().getOrCreateItemId(tool);
                toolInfo = tool.getType().name() + " [fst:" + fstId + "]";
            }

            String line = String.format("[%s] %s [%s] 使用 %s 挖掘了 %s 在 %s (%s)%s",
                    ts, player.getName(), player.getUniqueId(), toolInfo, type.name(), world, coords, claimInfo);
            queue("playerChunk", line);

            boolean isDangerous = DANGEROUS.contains(type);
            boolean isValuable = VALUABLE.contains(type);
            boolean noClaimPerm = claimInfo.isEmpty() && plugin.getClaimManager().getClaimAt(loc) != null && !isOp;
            boolean claimContainer = CONTAINER_BLOCKS.contains(type) && !claimInfo.isEmpty();

            if (isDangerous || isValuable || noClaimPerm || claimContainer) {
                queueOther("playerChunk", "§c[!] " + line);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("logBlockBreak 异常: " + e.getMessage());
        }
    }

    // ========== playerChunk ==========

    public void logBlockPlace(Player player, Block block, boolean isOp, ItemStack tool) {
        try {
            String ts = timestamp();
            Location loc = block.getLocation();
            String world = loc.getWorld() != null ? loc.getWorld().getName() : "?";
            String coords = loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
            Material type = block.getType();
            String claimInfo = getClaimInfo(loc);

            String toolInfo = "空手";
            if (tool != null && tool.getType() != Material.AIR) {
                String fstId = plugin.getFstItemIdManager().getOrCreateItemId(tool);
                toolInfo = tool.getType().name() + " [fst:" + fstId + "]";
            }

            String line = String.format("[%s] %s [%s] 使用 %s 放置了 %s 在 %s (%s)%s",
                    ts, player.getName(), player.getUniqueId(), toolInfo, type.name(), world, coords, claimInfo);
            queue("playerChunk", line);

            boolean isDangerous = DANGEROUS.contains(type);
            boolean noClaimPerm = claimInfo.isEmpty() && plugin.getClaimManager().getClaimAt(loc) != null && !isOp;
            if (isDangerous || noClaimPerm) {
                queueOther("playerChunk", "§c[!] " + line);
            }
        } catch (Exception e) {
            plugin.getLogger().warning("logBlockPlace 异常: " + e.getMessage());
        }
    }

    public void logPlayerMove(Player player, Location from, Location to) {
        if (from.getWorld() == null || !from.getWorld().equals(to.getWorld())) return;
        double dist = from.distance(to);
        long now = System.currentTimeMillis();
        UUID uid = player.getUniqueId();

        Long lastT = lastMoveTime.get(uid);
        double speed = 0;
        if (lastT != null) {
            double dt = (now - lastT) / 1000.0;
            if (dt > 0) speed = dist / dt;
        }
        lastMoveTime.put(uid, now);
        lastMoveLoc.put(uid, to);

        if (speed <= 5) return;

        boolean hasElytra = player.getInventory().getChestplate() != null
            && player.getInventory().getChestplate().getType() == Material.ELYTRA;
        String elytraStr = hasElytra ? " [穿戴鞘翅]" : "";
        String world = to.getWorld().getName();
        String coords = to.getBlockX() + "," + to.getBlockY() + "," + to.getBlockZ();
        String line = String.format("[%s] %s [%s] 移动 %.1f块/秒 在 %s (%s)%s",
                timestamp(), player.getName(), uid, speed, world, coords, elytraStr);
        queue("playerMove", line);

        if (speed > 20) {
            queueOther("playerMove", "§c[!] " + line);
        }
    }

    // ========== playerMove ==========

    public void logJoin(Player player) {
        String ts = timestamp();
        String ip = player.getAddress() != null ? player.getAddress().getAddress().getHostAddress() : "?";
        UUID uid = player.getUniqueId();
        String name = player.getName();

        String line = String.format("[%s] %s [%s] 加入 IP=%s", ts, name, uid, ip);
        queue("playerJoin", line);

        String offlineName = Bukkit.getOfflinePlayer(uid).getName();
        if (offlineName != null && !offlineName.equals(name)) {
            queueOther("playerJoin", "§c[!] UUID/名称不匹配: " + line);
        }

        UUID existing = ipPlayerMap.get(ip);
        if (existing != null && !existing.equals(uid)) {
            String otherName = Bukkit.getOfflinePlayer(existing).getName();
            queueOther("playerJoin", "§c[!] 相同IP不同玩家: " + line + " (之前: " + otherName + " [" + existing + "])");
        }
        ipPlayerMap.put(ip, uid);
    }

    // ========== playerJoin ==========

    public void logQuit(Player player, boolean isKicked, String kickReason) {
        String ts = timestamp();
        String ip = player.getAddress() != null ? player.getAddress().getAddress().getHostAddress() : "?";
        UUID uid = player.getUniqueId();
        String name = player.getName();
        String reason = isKicked ? "踢出: " + kickReason : "正常退出";

        String line = String.format("[%s] %s [%s] 断开 IP=%s 原因: %s", ts, name, uid, ip, reason);
        queue("playerJoin", line);

        if (isKicked) {
            queueOther("playerJoin", "§c[!] " + line);
        }
    }

    public void logItemPickup(Player player, ItemStack item) {
        String ts = timestamp();
        Material type = item.getType();
        int amount = item.getAmount();
        UUID uid = player.getUniqueId();

        ItemMeta meta = item.getItemMeta();
        boolean hasEnchants = meta != null && meta.hasEnchants();
        boolean hasCustomTags = meta != null && !meta.getPersistentDataContainer().isEmpty();
        String extras = "";
        if (hasEnchants) extras += " [附魔]";
        if (hasCustomTags) extras += " [自定义标签]";

        String line = String.format("[%s] %s [%s] 拾取 %s x%d%s",
                ts, player.getName(), uid, type.name(), amount, extras);
        queue("playerItem", line);

        boolean isDangerous = DANGEROUS.contains(type);
        boolean isValuable = VALUABLE.contains(type);
        if (isDangerous || isValuable || hasEnchants || hasCustomTags) {
            queueOther("playerItem", "§c[!] " + line);
        }
    }

    // ========== playerItem ==========

    public void logAttack(Player damager, org.bukkit.entity.Entity target, double damage, double remainingHealth) {
        String ts = timestamp();
        UUID duid = damager.getUniqueId();
        Location loc = damager.getLocation();
        String world = loc.getWorld() != null ? loc.getWorld().getName() : "?";
        String coords = loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
        String targetName = target instanceof Player ? ((Player) target).getName() : target.getType().name();
        UUID tuid = target.getUniqueId();

        String line = String.format("[%s] %s [%s] 在 %s (%s) 攻击了 %s [%s] 伤害=%.1f 目标剩余=%.1f",
                ts, damager.getName(), duid, world, coords, targetName, tuid, damage, remainingHealth);
        queue("playerAttack", line);

        long now = System.currentTimeMillis();
        attackTimes.computeIfAbsent(duid, k -> new ArrayList<>()).add(now);
        attackTimes.get(duid).removeIf(t -> now - t > 1000);
        int attacksInSec = attackTimes.get(duid).size();

        attackTargets.computeIfAbsent(duid, k -> new HashSet<>()).add(tuid);

        boolean highDmg = damage >= 10;
        boolean highFreq = attacksInSec >= 3;
        boolean multiTarget = attackTargets.get(duid).size() >= 3;

        if (highDmg || highFreq || multiTarget) {
            StringBuilder flags = new StringBuilder();
            if (highDmg) flags.append(" [高伤害]");
            if (highFreq) flags.append(" [高频 " + attacksInSec + "次/秒]");
            if (multiTarget) flags.append(" [多目标]");
            queueOther("playerAttack", "§c[!] " + line + flags.toString());
        }
    }

    // ========== playerAttack ==========

    public void logPlayerDamageTaken(Player victim, double damage, EntityDamageEvent.DamageCause cause, double remainingHealth) {
        UUID uid = victim.getUniqueId();
        long nowMs = System.currentTimeMillis();
        Long last = lastDamageLog.get(uid);
        if (last != null && nowMs - last < 500) return;
        lastDamageLog.put(uid, nowMs);

        try {
            Location loc = victim.getLocation();
            String world = loc.getWorld() != null ? loc.getWorld().getName() : "?";
            String coords = loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();

            String line = String.format("[%s] %s [%s] 受到伤害 %.1f 来源: %s 剩余血量: %.1f 在 %s (%s)",
                    timestamp(), victim.getName(), uid, damage, cause.name(), remainingHealth, world, coords);
            queue("playerAttack", line);

            if (damage >= 10) {
                queueOther("playerAttack", "§c[!] " + line + " [高伤害]");
            }
        } catch (Exception e) {
            plugin.getLogger().warning("logPlayerDamageTaken 异常: " + e.getMessage());
        }
    }

    public void resetAttackTracking() {
        attackTargets.clear();
    }

    // ========== playerDamageTaken ==========

    public void logPlayerDeath(Player player, String deathMessage, EntityDamageEvent.DamageCause cause) {
        String ts = timestamp();
        Location loc = player.getLocation();
        String world = loc.getWorld() != null ? loc.getWorld().getName() : "?";
        String coords = loc.getBlockX() + "," + loc.getBlockY() + "," + loc.getBlockZ();
        UUID puid = player.getUniqueId();

        String line = String.format("[%s] %s [%s] 死亡 原因: %s 死亡消息: %s 在 %s (%s)",
                ts, player.getName(), puid, cause.name(), deathMessage, world, coords);
        queue("playerAttack", line);
        queueOther("playerAttack", "§c[!] " + line);
    }

    // ========== playerDeath ==========

    /**
     * 日志条目。
     *
     * @param category 分类（对应 CATEGORIES），决定写入哪个子目录
     * @param line     格式化后的日志文本（不含换行符）
     * @param isOther  为 true 时写入 .other 文件，否则写入 .archive 文件
     */
    private record LogEntry(String category, String line, boolean isOther) {}

    // ========== Helpers ==========

    private String getClaimInfo(Location loc) {
        ClaimRegion claim = plugin.getClaimManager().getClaimAt(loc);
        if (claim == null) return "";
        String ownerName = plugin.getServer().getOfflinePlayer(claim.getOwner()).getName();
        return " (在 " + (ownerName != null ? ownerName : "?") + " 的领地)";
    }
}
