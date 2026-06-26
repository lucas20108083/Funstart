package moe.hinakusoft.funstart.listener;

import moe.hinakusoft.funstart.FunstartPlugin;
import moe.hinakusoft.funstart.manager.FSTActionBar;
import moe.hinakusoft.funstart.model.ClaimRegion;
import moe.hinakusoft.funstart.model.CustomEnchantment;
import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.ProjectileHitEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.util.Vector;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 自定义附魔效果监听器。
 * <p>
 * 处理 9 种自定义附魔的全部游戏内效果，包括：
 * 矿工之敏（双倍矿石）、生命恢复、霜痕、龟壳移动、
 * 爆裂投掷、叶隐、时间减缓、四季夏目、密集射击。
 * <p>
 * 每 5 tick 对所有在线玩家执行被动效果检测（霜痕、叶隐、四季夏目持有）。
 * 主动效果（攻击、击杀、投射物）通过事件监听触发。
 * 所有随机数使用 ThreadLocalRandom 替代 Random 实例字段。
 */
public class CustomEnchantListener implements Listener {

    private final FunstartPlugin plugin;
    private final FSTActionBar actionBar;

    private static final Set<Material> ORES = Set.of(
        Material.COAL_ORE, Material.DEEPSLATE_COAL_ORE,
        Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE,
        Material.COPPER_ORE, Material.DEEPSLATE_COPPER_ORE,
        Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE,
        Material.REDSTONE_ORE, Material.DEEPSLATE_REDSTONE_ORE,
        Material.LAPIS_ORE, Material.DEEPSLATE_LAPIS_ORE,
        Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE,
        Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE,
        Material.NETHER_GOLD_ORE, Material.NETHER_QUARTZ_ORE
    );

    private final Map<UUID, Long> lastFrostTick = new HashMap<>();
    private final Map<UUID, Long> lastLeafTick = new HashMap<>();
    private final Map<UUID, Long> lastExplosiveThrow = new ConcurrentHashMap<>();
    private final Set<UUID> shikiEquipped = new HashSet<>();
    private static final long BURST_WINDOW_MS = 200L;
    private final Map<UUID, Long> lastMultishotBurst = new ConcurrentHashMap<>();
    private final Set<Block> processingMinerBlocks = new HashSet<>();

    public CustomEnchantListener(FunstartPlugin plugin) {
        this.plugin = plugin;
        this.actionBar = plugin.getActionBar();

        plugin.getServer().getScheduler().runTaskTimer(plugin, () -> {
            for (Player player : plugin.getServer().getOnlinePlayers()) {
                applyFrostTrail(player);
                applyLeafHidden(player);
                applyShikiHold(player);
            }
        }, 0L, 5L);
    }

    private int getHighestLevel(Player player, CustomEnchantment ce) {
        int level = 0;
        level = Math.max(level, EnchantGuiListener.getCustomLevel(player.getInventory().getItemInMainHand(), plugin, ce));
        level = Math.max(level, EnchantGuiListener.getCustomLevel(player.getInventory().getItemInOffHand(), plugin, ce));
        for (ItemStack armor : player.getInventory().getArmorContents()) {
            level = Math.max(level, EnchantGuiListener.getCustomLevel(armor, plugin, ce));
        }
        return level;
    }

    // ========== SHIKI_NATSUME ==========

    private List<String> getShikiMessages(String category) {
        return plugin.getConfig().getStringList("shiki-natsume.messages." + category);
    }

    private void sendShikiActionBar(Player player, String category) {
        List<String> msgs = getShikiMessages(category);
        if (msgs.isEmpty()) return;
        String msg = msgs.get(ThreadLocalRandom.current().nextInt(msgs.size()));
        actionBar.add(player, "§c§l四季 夏目： §r§c§o" + msg);
    }

    private boolean hasShikiInHand(Player player) {
        ItemStack main = player.getInventory().getItemInMainHand();
        if (main.getType() != org.bukkit.Material.DIAMOND_AXE) return false;
        return EnchantGuiListener.getCustomLevel(main, plugin, CustomEnchantment.SHIKI_NATSUME) > 0;
    }

    private void applyShikiHold(Player player) {
        boolean has = hasShikiInHand(player);
        UUID uid = player.getUniqueId();
        boolean was = shikiEquipped.contains(uid);
        if (has && !was) {
            shikiEquipped.add(uid);
            sendShikiActionBar(player, "hold");
        } else if (!has && was) {
            shikiEquipped.remove(uid);
        }
    }

    // ========== MINER_AGILITY ==========

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockBreak(BlockBreakEvent event) {
        if (event.isCancelled()) return;

        Player player = event.getPlayer();

        if (hasShikiInHand(player) && ThreadLocalRandom.current().nextInt(100) < 16) {
            sendShikiActionBar(player, "break");
        }

        int level = getHighestLevel(player, CustomEnchantment.MINER_AGILITY);
        if (level <= 0) return;

        Block block = event.getBlock();
        Material type = block.getType();
        if (!ORES.contains(type)) return;

        ItemStack tool = player.getInventory().getItemInMainHand();
        if (tool.containsEnchantment(Enchantment.SILK_TOUCH)) return;
        if (!isPickaxe(tool)) return;

        if (processingMinerBlocks.contains(block)) return;

        if (ThreadLocalRandom.current().nextInt(100) < Math.min(level * 8, 80)) {
            processingMinerBlocks.add(block);
            Material oreType = block.getType();
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (block.getType() == Material.AIR) {
                    block.setType(oreType);
                    block.breakNaturally(tool);
                    tool.damage(1, player);
                }
                processingMinerBlocks.remove(block);
            });
            actionBar.add(player, "§5[矿工之敏] §a双倍触发!");
        }
    }

    private boolean isPickaxe(ItemStack item) {
        if (item == null || item.getType().isAir()) return false;
        return item.getType().name().contains("PICKAXE");
    }

    // ========== LIFE_STEAL ==========

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityDeath(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null) return;

        if (hasShikiInHand(killer) && ThreadLocalRandom.current().nextInt(100) < 75) {
            sendShikiActionBar(killer, "kill");
        }

        int level = getHighestLevel(killer, CustomEnchantment.LIFE_STEAL);
        if (level <= 0) return;

        double heal = Math.min(level * 2.0, killer.getMaxHealth() - killer.getHealth());
        if (heal <= 0) return;
        killer.setHealth(killer.getHealth() + heal);
        actionBar.add(killer, String.format("§5[生命恢复] §a恢复 §c❤ %.0f", heal));
    }

    // ========== FROST_TRAIL ==========

    private void applyFrostTrail(Player player) {
        int level = getHighestLevel(player, CustomEnchantment.FROST_TRAIL);
        if (level <= 0) return;

        UUID uid = player.getUniqueId();
        long now = System.currentTimeMillis();
        if (lastFrostTick.containsKey(uid) && now - lastFrostTick.get(uid) < 500) return;
        lastFrostTick.put(uid, now);

        if (!player.isOnGround()) return;

        Location loc = player.getLocation();
        World world = loc.getWorld();
        if (world == null) return;

        int radius = 2 + (int)((level - 1) * 2.0 / 3);
        int duration = 60 + (int)((level - 1) * 100.0 / 9);
        int amp = level >= 6 ? 1 : 0;

        world.spawnParticle(Particle.SNOWFLAKE, loc.add(0, 1, 0), 8, 1.5, 0.5, 1.5, 0);

        for (LivingEntity entity : world.getNearbyLivingEntities(loc, radius, 1.5, radius)) {
            if (entity == player) continue;
            if (entity instanceof Player) continue;
            if (entity instanceof Villager || entity instanceof Cat || entity instanceof Wolf) continue;
            entity.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, duration, amp, true, false));
            entity.addPotionEffect(new PotionEffect(PotionEffectType.WEAKNESS, duration, 0, true, false));
        }
    }

    // ========== ENDER_MOVE ==========

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityDamage(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player player)) return;

        if (hasShikiInHand(player) && ThreadLocalRandom.current().nextInt(100) < 66) {
            sendShikiActionBar(player, "damage");
        }

        int level = getHighestLevel(player, CustomEnchantment.ENDER_MOVE);
        if (level <= 0) return;

        if (player.hasPotionEffect(PotionEffectType.RESISTANCE)) return;

        int duration = level * 10;
        player.addPotionEffect(new PotionEffect(PotionEffectType.RESISTANCE, duration, 0, true, false));
        actionBar.add(player, String.format("§5[龟壳移动] §a抗性提升 §7(%.1fs)", duration / 20.0));
    }

    // ========== EXPLOSIVE_THROW ==========

    private final Map<UUID, Integer> explosionMsgCount = new ConcurrentHashMap<>();

    private long getExplosiveCooldown(int level) {
        long cd = 1950L - (level - 1) * 65L;
        return Math.max(cd, 1690L);
    }

    private long getCrossbowCooldown(int level, ItemStack weapon) {
        long base = getExplosiveCooldown(level);
        double multiplier = 1.4;
        int ms = weapon.getEnchantmentLevel(Enchantment.MULTISHOT);
        if (ms > 0) multiplier *= Math.pow(1.11, ms);
        int qc = weapon.getEnchantmentLevel(Enchantment.QUICK_CHARGE);
        if (qc > 0) multiplier *= Math.pow(1.12, qc);
        return (long) (base * multiplier);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onProjectileHit(ProjectileHitEvent event) {
        Projectile projectile = event.getEntity();
        if (!(projectile.getShooter() instanceof Player player)) return;

        if (event.getHitEntity() != null && event.getHitEntity().equals(player)) {
            projectile.remove();
            return;
        }

        int level = getHighestLevel(player, CustomEnchantment.EXPLOSIVE_THROW);
        if (level <= 0) return;

        ItemStack weapon = player.getInventory().getItemInMainHand();
        boolean isCrossbow = weapon != null && weapon.getType() == Material.CROSSBOW;
        boolean hasMultishot = isCrossbow && weapon.containsEnchantment(Enchantment.MULTISHOT);

        UUID uid = player.getUniqueId();
        long now = System.currentTimeMillis();
        boolean canExplode = false;

        if (hasMultishot) {
            Long lastBurst = lastMultishotBurst.get(uid);
            if (lastBurst != null && now - lastBurst < BURST_WINDOW_MS) {
                canExplode = true;
                lastMultishotBurst.put(uid, now);
            } else {
                long cd = getCrossbowCooldown(level, weapon);
                Long lastUse = lastExplosiveThrow.get(uid);
                if (lastUse == null || now - lastUse >= cd) {
                    lastExplosiveThrow.put(uid, now);
                    lastMultishotBurst.put(uid, now);
                    explosionMsgCount.remove(uid);
                    canExplode = true;
                }
            }
        } else {
            long cd = isCrossbow ? getCrossbowCooldown(level, weapon) : getExplosiveCooldown(level);
            Long lastUse = lastExplosiveThrow.get(uid);
            if (lastUse != null && now - lastUse < cd) {
                actionBar.add(player, "§5[爆裂投掷] §c冷却中 (" + ((cd - (now - lastUse)) / 1000 + 1) + "s)");
                projectile.remove();
                return;
            }
            lastExplosiveThrow.put(uid, now);
            explosionMsgCount.remove(uid);
            canExplode = true;
        }

        if (canExplode) {
            doExplosion(player, projectile, level);
        }
        projectile.remove();
    }

    private void doExplosion(Player player, Projectile projectile, int level) {
        Location hitLoc = projectile.getLocation();

        // Check per-claim explosive throw setting
        var claim = plugin.getClaimManager().getClaimAt(hitLoc);
        if (claim != null && !claim.isAllowExplosiveThrow()) return;

        float power = (0.3f + (level - 1) * 0.3f) * 1.35f;

        hitLoc.getWorld().createExplosion(hitLoc, power, false, false, player);

        double radius = power * 2.5;
        double maxDamage = (6.0 + level * 2.0) * 1.35;

        for (Entity entity : hitLoc.getWorld().getNearbyEntities(hitLoc, radius, radius, radius)) {
            if (!(entity instanceof LivingEntity living)) continue;
            if (entity.equals(player)) continue;
            if (entity instanceof Villager || entity instanceof Cat || entity instanceof Wolf) continue;

            double distance = entity.getLocation().distance(hitLoc);
            double damage = maxDamage * Math.max(0, 1 - distance / radius);
            if (damage < 1) continue;
            if (entity instanceof Player) damage *= 0.65;

            living.damage(damage, player);
        }

        UUID uid = player.getUniqueId();
        int cnt = explosionMsgCount.merge(uid, 1, Integer::sum);
        if (cnt <= 10) {
            actionBar.add(player, "§5[爆裂投掷] §a爆炸!");
        } else if (cnt == 11) {
            actionBar.add(player, "§5[爆裂投掷] §c*...");
        }
    }

    // ========== LEAF_HIDDEN ==========

    private static final Material[] LEAF_TYPES = {
        Material.OAK_LEAVES, Material.SPRUCE_LEAVES, Material.BIRCH_LEAVES,
        Material.JUNGLE_LEAVES, Material.ACACIA_LEAVES, Material.DARK_OAK_LEAVES,
        Material.MANGROVE_LEAVES, Material.CHERRY_LEAVES, Material.PALE_OAK_LEAVES,
        Material.AZALEA_LEAVES, Material.FLOWERING_AZALEA_LEAVES
    };

    private void applyLeafHidden(Player player) {
        int level = getHighestLevel(player, CustomEnchantment.LEAF_HIDDEN);
        if (level <= 0) return;

        UUID uid = player.getUniqueId();
        long now = System.currentTimeMillis();
        if (lastLeafTick.containsKey(uid) && now - lastLeafTick.get(uid) < 500) return;
        lastLeafTick.put(uid, now);

        Location loc = player.getLocation();
        Block below = loc.getBlock().getRelative(0, -1, 0);
        boolean onLeaves = false;
        for (Material leaf : LEAF_TYPES) {
            if (below.getType() == leaf) { onLeaves = true; break; }
        }

        // Check if any leaves within level-radius (horizontal, y: -1~+2)
        int r = Math.min(level, 8);
        boolean nearLeaves = onLeaves; // already on leaves counts
        if (!nearLeaves) {
            Block origin = loc.getBlock();
            for (int x = -r; x <= r && !nearLeaves; x++) {
                for (int z = -r; z <= r && !nearLeaves; z++) {
                    if (x*x + z*z > r*r) continue;
                    for (int y = -1; y <= 2 && !nearLeaves; y++) {
                        Block b = origin.getRelative(x, y, z);
                        for (Material leaf : LEAF_TYPES) {
                            if (b.getType() == leaf) { nearLeaves = true; break; }
                        }
                    }
                }
            }
        }

        if (!nearLeaves) return;

        // All effects: 5s (100 ticks), level I (amp 0)
        if (onLeaves) {
            player.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, 100, 0, true, false));
        }
        player.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 100, 0, true, false));
        player.addPotionEffect(new PotionEffect(PotionEffectType.REGENERATION, 100, 0, true, false));

        actionBar.add(player, "§5[叶隐] §a" + (onLeaves ? "隐身加速回复" : "加速回复"));
    }

    // ========== TIME_SLOW ==========

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) return;

        if (hasShikiInHand(player) && ThreadLocalRandom.current().nextInt(100) < 22) {
            sendShikiActionBar(player, "attack");
        }

        if (!(event.getEntity() instanceof LivingEntity target)) return;

        int level = getHighestLevel(player, CustomEnchantment.TIME_SLOW);
        if (level <= 0) return;

        if (ThreadLocalRandom.current().nextInt(100) < level * 20) {
            target.addPotionEffect(new PotionEffect(PotionEffectType.SLOWNESS, 60 + level * 20, level - 1, true, false));
            actionBar.add(player, "§5[时间减缓] §a减速目标");
        }
    }

    // ========== DENSE_SHOT ==========

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        Projectile projectile = event.getEntity();
        if (!(projectile.getShooter() instanceof Player player)) return;

        ItemStack weapon = player.getInventory().getItemInMainHand();

        // Dense shot convergence
        int denseLevel = getHighestLevel(player, CustomEnchantment.DENSE_SHOT);
        if (denseLevel > 0 && weapon.getType() == Material.CROSSBOW && weapon.containsEnchantment(Enchantment.MULTISHOT)) {
            double convergence = denseLevel / 10.0;
            Vector aimDir = player.getEyeLocation().getDirection().normalize();
            Vector currentVel = projectile.getVelocity();
            double speed = currentVel.length();
            if (speed >= 0.1) {
                Vector newDir = currentVel.normalize().multiply(1 - convergence)
                        .add(aimDir.multiply(convergence)).normalize();
                projectile.setVelocity(newDir.multiply(speed));
            }
        }

        // Schedule arrow removal after 1s for crossbows with explosive throw
        if (weapon.getType() == Material.CROSSBOW && projectile instanceof Arrow) {
            int exLevel = getHighestLevel(player, CustomEnchantment.EXPLOSIVE_THROW);
            if (exLevel > 0) {
                plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                    if (!projectile.isDead()) projectile.remove();
                }, 20L);
            }
        }
    }

    // Self-hit protection for dense shot (and general)
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDenseShotHit(ProjectileHitEvent event) {
        Projectile projectile = event.getEntity();
        if (!(projectile.getShooter() instanceof Player player)) return;
        if (event.getHitEntity() == null || !event.getHitEntity().equals(player)) return;

        int level = getHighestLevel(player, CustomEnchantment.DENSE_SHOT);
        if (level > 0) {
            projectile.remove();
            event.setCancelled(true);
        }
    }
}
