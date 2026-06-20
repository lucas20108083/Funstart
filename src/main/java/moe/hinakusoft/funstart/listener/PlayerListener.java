package moe.hinakusoft.funstart.listener;

import io.papermc.paper.event.player.PlayerTradeEvent;
import moe.hinakusoft.funstart.FunstartPlugin;
import moe.hinakusoft.funstart.manager.DailyTaskManager;
import moe.hinakusoft.funstart.model.PlayerData;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.entity.EntityBreedEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDeathEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerLevelChangeEvent;

import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

public class PlayerListener
implements Listener {
    private static final double DAMAGE_THRESHOLD = 15.0;
    private final FunstartPlugin plugin;

    private static final Set<Material> ORES = Set.of(
        Material.COAL_ORE, Material.DEEPSLATE_COAL_ORE,
        Material.COPPER_ORE, Material.DEEPSLATE_COPPER_ORE,
        Material.IRON_ORE, Material.DEEPSLATE_IRON_ORE,
        Material.LAPIS_ORE, Material.DEEPSLATE_LAPIS_ORE,
        Material.REDSTONE_ORE, Material.DEEPSLATE_REDSTONE_ORE,
        Material.GOLD_ORE, Material.DEEPSLATE_GOLD_ORE,
        Material.DIAMOND_ORE, Material.DEEPSLATE_DIAMOND_ORE,
        Material.EMERALD_ORE, Material.DEEPSLATE_EMERALD_ORE,
        Material.ANCIENT_DEBRIS, Material.NETHER_GOLD_ORE,
        Material.NETHER_QUARTZ_ORE
    );

    private static final Set<Material> LOGS = Set.of(
        Material.OAK_LOG, Material.SPRUCE_LOG, Material.BIRCH_LOG,
        Material.JUNGLE_LOG, Material.ACACIA_LOG, Material.DARK_OAK_LOG,
        Material.MANGROVE_LOG, Material.CHERRY_LOG,
        Material.PALE_OAK_LOG,
        Material.CRIMSON_STEM, Material.WARPED_STEM
    );

    public PlayerListener(FunstartPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onEntityDeath(EntityDeathEvent event) {
        Player killer = event.getEntity().getKiller();
        if (killer == null) return;
        PlayerData data = this.plugin.getPlayerDataManager().getPlayerData(killer);
        if (event.getEntity() instanceof Player) {
            data.addPoints(5);
            plugin.getActionBar().add(killer, "§e击杀玩家 +5点数");
        } else {
            int points = ThreadLocalRandom.current().nextInt(1, 4);
            data.addPoints(points);
            DailyTaskManager.incrementTask(data, 1, 1);
            plugin.getActionBar().add(killer, "§e击杀生物 +" + points + "点数");
        }
    }

    @EventHandler
    public void onEntityDamageByEntity(EntityDamageByEntityEvent event) {
        Entity entity = event.getDamager();
        if (!(entity instanceof Player player)) return;
        if (!(event.getEntity() instanceof LivingEntity)) return;
        PlayerData data = this.plugin.getPlayerDataManager().getPlayerData(player);
        data.addDamage(event.getFinalDamage());
        if (data.getTotalDamageDealt() >= DAMAGE_THRESHOLD) {
            data.setTotalDamageDealt(data.getTotalDamageDealt() - DAMAGE_THRESHOLD);
            data.addPoints(1);
            plugin.getActionBar().add(player, "§e伤害 +1点数");
        }
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        Location loc = player.getLocation();
        String locStr = loc.getWorld().getName() + ","
            + loc.getX() + ","
            + loc.getY() + ","
            + loc.getZ() + ","
            + loc.getYaw() + ","
            + loc.getPitch();
        PlayerData data = this.plugin.getPlayerDataManager().getPlayerData(player);
        data.setLastDeathLocation(locStr);
        this.plugin.getPlayerDataManager().savePlayerData(player.getUniqueId());
    }

    @EventHandler
    public void onPlayerLevelChange(PlayerLevelChangeEvent event) {
        Player player = event.getPlayer();
        int oldLevel = event.getOldLevel();
        int newLevel = event.getNewLevel();
        if (newLevel <= oldLevel) return;
        int gained = newLevel - oldLevel;
        int points = gained * (2 + ThreadLocalRandom.current().nextInt(4));
        PlayerData data = this.plugin.getPlayerDataManager().getPlayerData(player);
        data.addPoints(points);
        player.sendMessage("§e[Funstart] §a升级获得 §e+" + points + " §a点");
    }

    @EventHandler
    public void onPlayerFish(PlayerFishEvent event) {
        if (event.getState() != PlayerFishEvent.State.CAUGHT_FISH) return;
        if (ThreadLocalRandom.current().nextInt(3) != 0) return;
        int points = ThreadLocalRandom.current().nextInt(3);
        if (points == 0) return;
        Player player = event.getPlayer();
        PlayerData data = this.plugin.getPlayerDataManager().getPlayerData(player);
        data.addPoints(points);
        player.sendMessage("§e[Funstart] §a钓鱼获得 §e+" + points + " §a点");
    }

    @EventHandler
    public void onEntityBreed(EntityBreedEvent event) {
        if (!(event.getBreeder() instanceof Player player)) return;
        int points = 1 + ThreadLocalRandom.current().nextInt(2);
        PlayerData data = this.plugin.getPlayerDataManager().getPlayerData(player);
        data.addPoints(points);
        player.sendMessage("§e[Funstart] §a繁殖动物获得 §e+" + points + " §a点");
    }

    @EventHandler
    public void onPlayerTrade(PlayerTradeEvent event) {
        Player player = event.getPlayer();
        int points = ThreadLocalRandom.current().nextInt(2);
        if (points == 0) return;
        PlayerData data = this.plugin.getPlayerDataManager().getPlayerData(player);
        data.addPoints(points);
        player.sendMessage("§e[Funstart] §a交易获得 §e+" + points + " §a点");
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        Player player = event.getPlayer();
        Block block = event.getBlock();
        Material type = block.getType();

        PlayerData data = this.plugin.getPlayerDataManager().getPlayerData(player);
        DailyTaskManager.incrementTask(data, 0, 1);

        if (ORES.contains(type)) {
            int points = ThreadLocalRandom.current().nextInt(3);
            if (points == 0) return;
            data.addPoints(points);
            player.sendMessage("§e[Funstart] §a挖矿获得 §e+" + points + " §a点");
            return;
        }


    }
}
