package moe.hinakusoft.funstart.listener;

import moe.hinakusoft.funstart.FunstartPlugin;
import moe.hinakusoft.funstart.manager.BlockUtils;
import moe.hinakusoft.funstart.model.ClaimRegion;
import moe.hinakusoft.funstart.model.PlayerData;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 批量收割监听器。
 * <p>
 * 玩家潜行 + 锄头破坏成熟作物时，通过 2D 泛洪填充收割相连的
 * 成熟作物并自动补种。非下界疣作物自动补种，消耗点数。
 * 区块保护检查在泛洪填充的 removeIf 中完成。
 */
public class HarvestListener implements Listener {

    private static final Set<Material> CROPS = Set.of(
        Material.WHEAT, Material.CARROTS, Material.POTATOES,
        Material.BEETROOTS, Material.NETHER_WART
    );

    private final FunstartPlugin plugin;

    public HarvestListener(FunstartPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (event.isCancelled()) return;

        Player player = event.getPlayer();
        if (!player.isSneaking()) return;

        PlayerData data = plugin.getPlayerDataManager().getPlayerData(player);
        if (!data.isHarvestEnabled()) return;

        ItemStack tool = player.getInventory().getItemInMainHand();
        if (tool == null) return;
        Material t = tool.getType();
        boolean isHoe = t == Material.WOODEN_HOE || t == Material.STONE_HOE
            || t == Material.IRON_HOE || t == Material.GOLDEN_HOE
            || t == Material.DIAMOND_HOE || t == Material.NETHERITE_HOE;
        if (!isHoe) return;

        Block origin = event.getBlock();
        Material type = origin.getType();
        if (!isMatureCrop(origin)) return;

        Set<Block> crops = BlockUtils.floodFill2D(origin, b -> b.getType() == type && isMatureCrop(b), 64);
        // Remove protected blocks
        crops.removeIf(b -> {
            if (plugin.getClaimManager().isInSpawnProtection(b.getLocation())) return true;
            ClaimRegion c = plugin.getClaimManager().getClaimAt(b.getLocation());
            return c != null && !c.getOwner().equals(player.getUniqueId())
                && !c.getTrustedPlayers().contains(player.getUniqueId());
        });
        if (crops.size() <= 1) return;

        int estimatedReplant = 0;
        for (Block b : crops) {
            if (b.getType() != Material.NETHER_WART) estimatedReplant++;
        }
        double estimatedCost = estimatedReplant / 4.0;
        if (estimatedCost > 0 && data.getPoints() < estimatedCost) {
            player.sendMessage("§c点数不足，无法范围收割!");
            return;
        }

        event.setCancelled(true);

        int replanted = 0;
        List<Location> brokenLocations = new ArrayList<>();

        for (Block block : crops) {
            brokenLocations.add(block.getLocation());
            block.breakNaturally(tool);
            if (type != Material.NETHER_WART) {
                block.setType(type);
                replanted++;
            }
        }

        Location originLoc = origin.getLocation().add(0.5, 0.5, 0.5);
        for (Location loc : brokenLocations) {
            for (Item item : loc.getNearbyEntitiesByType(Item.class, 1.5)) {
                item.teleport(originLoc);
            }
        }

        double actualCost = replanted / 4.0;
        if (actualCost > 0) {
            data.deductPoints(actualCost);
        }

        player.sendMessage("§e[Funstart] §a范围收割 §b" + crops.size() + " §a个作物, 播种 §b" + replanted + " §a个, 消耗 §e" + PlayerData.fmt(actualCost) + " §a点, 剩余 §e" + PlayerData.fmt(data.getPoints()) + " §a点");
    }

    private boolean isMatureCrop(Block block) {
        Material type = block.getType();
        if (!CROPS.contains(type)) return false;
        BlockData data = block.getBlockData();
        if (data instanceof Ageable ageable) {
            return ageable.getAge() >= ageable.getMaximumAge();
        }
        return false;
    }

}
