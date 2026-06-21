package moe.hinakusoft.funstart.listener;

import moe.hinakusoft.funstart.FunstartPlugin;
import moe.hinakusoft.funstart.manager.BlockUtils;
import moe.hinakusoft.funstart.model.ClaimRegion;
import moe.hinakusoft.funstart.model.CustomEnchantment;
import moe.hinakusoft.funstart.model.PlayerData;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/**
 * 连锁挖掘监听器。
 * <p>
 * 玩家潜行时使用工具破坏方块，通过 3D 泛洪填充自动破坏所有
 * 相连的同类方块。矿石方块计入免费额度，非矿石方块消耗点数。
 * 矿工之敏附魔可在连锁中二次触发双倍掉落。
 * <p>
 * 性能要点：BlockUtils.floodFill 的 max 由 PlayerData.maxChainBlocks 控制，
 * 后续的 actuallyBroken 循环合并了非矿石统计与破坏逻辑，避免二次遍历。
 */
public class ChainListener implements Listener {

    private static final Set<Material> CROPS = Set.of(
        Material.WHEAT, Material.CARROTS, Material.POTATOES,
        Material.BEETROOTS, Material.NETHER_WART
    );

    private final FunstartPlugin plugin;

    public ChainListener(FunstartPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onBlockBreak(BlockBreakEvent event) {
        if (event.isCancelled()) return;

        Player player = event.getPlayer();
        if (!player.isSneaking()) return;

        PlayerData data = this.plugin.getPlayerDataManager().getPlayerData(player);
        if (!data.isChainEnabled()) return;

        ItemStack tool = player.getInventory().getItemInMainHand();
        if (!isValidChainTool(tool)) return;

        Block origin = event.getBlock();
        Material type = origin.getType();
        if (type.isAir()) return;

        if (isCropBlock(origin)) return;

        int maxBlocks = data.getMaxChainBlocks();

        Set<Block> toBreak = BlockUtils.floodFill(origin, b -> b.getType() == type, maxBlocks);
        if (toBreak.size() <= 1) return;

        int estimatedNonOre = 0;
        for (Block b : toBreak) {
            if (!isOreBlock(b)) estimatedNonOre++;
        }
        double estimatedCost = estimatedNonOre / 16.0;
        if (estimatedCost > 0 && data.getPoints() < estimatedCost) {
            player.sendMessage("§c点数不足，无法连锁!");
            return;
        }

        event.setCancelled(true);

        List<Block> actuallyBroken = new ArrayList<>();
        int actualNonOre = 0;

        for (Block block : toBreak) {
            // Skip protected blocks
            if (plugin.getClaimManager().isInSpawnProtection(block.getLocation())) continue;
            ClaimRegion claim = plugin.getClaimManager().getClaimAt(block.getLocation());
            if (claim != null && !claim.getOwner().equals(player.getUniqueId())
                && !claim.getTrustedPlayers().contains(player.getUniqueId())) continue;

            tool = player.getInventory().getItemInMainHand();
            if (tool == null || tool.getType().isAir() || !isValidChainTool(tool)) {
                if (!actuallyBroken.isEmpty()) {
                    player.sendMessage("§c工具已损坏，连锁停止");
                }
                break;
            }
            actuallyBroken.add(block);
            if (!isOreBlock(block)) actualNonOre++;
            boolean wasOre = isOreBlock(block);
            Material oreType = block.getType();
            block.breakNaturally(tool);
            tool.damage(1, player);

            if (wasOre && !tool.containsEnchantment(Enchantment.SILK_TOUCH)) {
                int minerLevel = EnchantGuiListener.getCustomLevel(tool, plugin, CustomEnchantment.MINER_AGILITY);
                if (minerLevel > 0 && isPickaxeType(tool.getType()) && ThreadLocalRandom.current().nextInt(100) < Math.min(minerLevel * 8, 80)) {
                    block.setType(oreType);
                    block.breakNaturally(tool);
                    tool.damage(1, player);
                }
            }
        }
        double actualCost = actualNonOre / 16.0;

        Location originLoc = origin.getLocation().add(0.5, 0.5, 0.5);
        for (Block b : actuallyBroken) {
            for (Item item : b.getLocation().getNearbyEntitiesByType(Item.class, 1.5)) {
                item.teleport(originLoc);
            }
        }

        if (actualCost > 0) {
            data.deductPoints(actualCost);
            player.sendMessage("§e[Funstart] §a连锁挖掘 §b" + actuallyBroken.size() + " §a个方块, 消耗 §e" + PlayerData.fmt(actualCost) + " §a点, 剩余 §e" + PlayerData.fmt(data.getPoints()) + " §a点");
        } else {
            player.sendMessage("§e[Funstart] §a连锁挖掘 §b" + actuallyBroken.size() + " §a个方块");
        }
    }

    private boolean isValidChainTool(ItemStack item) {
        if (item == null || item.getType().isAir()) return false;
        Material mat = item.getType();
        String name = mat.name();
        return name.contains("PICKAXE") || name.contains("SHOVEL") ||
               name.contains("HOE") || name.contains("SWORD") ||
               name.contains("AXE") || name.contains("SHEARS");
    }

    private boolean isOreBlock(Block block) {
        Material type = block.getType();
        if (type == Material.ANCIENT_DEBRIS) return true;
        return type.name().endsWith("_ORE");
    }

    private boolean isPickaxeType(Material mat) {
        return mat.name().contains("PICKAXE");
    }

    private boolean isCropBlock(Block block) {
        Material type = block.getType();
        if (!CROPS.contains(type)) return false;
        BlockData data = block.getBlockData();
        return data instanceof Ageable;
    }
}
