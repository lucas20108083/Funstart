package moe.hinakusoft.funstart.manager;

import moe.hinakusoft.funstart.FunstartPlugin;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class RewardBagManager {

    private final FunstartPlugin plugin;
    private final File file;
    private final Map<UUID, ClaimRecord> claims = new HashMap<>();
    private final Map<String, RewardBag> bags = new HashMap<>();

    public record RewardBag(String id, String displayName, List<ItemStack> items, double points) {}

    private record ClaimRecord(String account, String playerName, Set<String> bagIds) {}

    public RewardBagManager(FunstartPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "rewardBags.yml");
        defineBags();
        load();
    }

    private void defineBags() {
        bags.put("firstReward", new RewardBag(
            "firstReward", "新手礼包",
            List.of(
                new ItemStack(Material.COPPER_INGOT, 3),
                new ItemStack(Material.COOKED_COD, 3),
                new ItemStack(Material.STONE, 5),
                new ItemStack(Material.DARK_OAK_SAPLING, 4),
                createCandy()
            ),
            300.0
        ));
    }

    private ItemStack createCandy() {
        ItemStack item = new ItemStack(Material.SPIDER_EYE);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§r§a糖§f");
        meta.setLore(List.of("§f这是一颗普通的糖"));
        meta.addEnchant(Enchantment.MENDING, 1, true);
        item.setItemMeta(meta);
        if (plugin.getFstFood() != null) {
            moe.hinakusoft.funstart.custom.FSTFood.tagItem(
                item, plugin.getFstFood().getKey(), "candy");
        }
        return item;
    }

    public RewardBag getBag(String bagId) {
        return bags.get(bagId);
    }

    public boolean isClaimed(UUID uuid, String bagId) {
        ClaimRecord record = claims.get(uuid);
        return record != null && record.bagIds().contains(bagId);
    }

    public enum ClaimResult {
        SUCCESS, ALREADY_CLAIMED, NO_SPACE, NOT_FOUND
    }

    public ClaimResult claimReward(Player player, String bagId, String accountName, String playerName) {
        RewardBag bag = bags.get(bagId);
        if (bag == null) return ClaimResult.NOT_FOUND;

        UUID uuid = player.getUniqueId();

        if (isClaimed(uuid, bagId)) return ClaimResult.ALREADY_CLAIMED;

        int freeSlots = 0;
        for (ItemStack item : player.getInventory().getStorageContents()) {
            if (item == null || item.getType().isAir()) freeSlots++;
        }
        int neededSlots = 0;
        for (ItemStack bagItem : bag.items()) {
            ItemStack clone = bagItem.clone();
            int maxStack = clone.getMaxStackSize();
            int amount = clone.getAmount();
            neededSlots += (amount + maxStack - 1) / maxStack;
        }
        if (freeSlots < neededSlots) return ClaimResult.NO_SPACE;

        for (ItemStack bagItem : bag.items()) {
            ItemStack give = bagItem.clone();
            if (give.getType() == Material.SPIDER_EYE
                && give.hasItemMeta()
                && "§r§a糖§f".equals(give.getItemMeta().getDisplayName())) {
                if (plugin.getFstFood() != null) {
                    moe.hinakusoft.funstart.custom.FSTFood.tagItem(
                        give, plugin.getFstFood().getKey(), "candy");
                }
            }
            Map<Integer, ItemStack> leftover = player.getInventory().addItem(give);
            if (!leftover.isEmpty()) {
                for (ItemStack left : leftover.values()) {
                    player.getWorld().dropItemNaturally(player.getLocation(), left);
                }
            }
        }

        if (bag.points() > 0) {
            var pd = plugin.getPlayerDataManager().getPlayerData(player);
            pd.addPoints(bag.points());
            plugin.getPlayerDataManager().savePlayerData(player);
        }

        claims.computeIfAbsent(uuid, k -> new ClaimRecord(accountName, playerName, new HashSet<>()))
              .bagIds().add(bagId);
        save();

        return ClaimResult.SUCCESS;
    }

    public void load() {
        claims.clear();
        if (!file.exists()) return;
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        if (!config.contains("claims")) return;
        for (String key : config.getConfigurationSection("claims").getKeys(false)) {
            UUID uuid = UUID.fromString(key);
            String account = config.getString("claims." + key + ".account", "");
            String pName = config.getString("claims." + key + ".playerName", "");
            Set<String> bagIds = new HashSet<>(config.getStringList("claims." + key + ".bags"));
            claims.put(uuid, new ClaimRecord(account, pName, bagIds));
        }
    }

    public void save() {
        YamlConfiguration config = new YamlConfiguration();
        for (Map.Entry<UUID, ClaimRecord> entry : claims.entrySet()) {
            String key = entry.getKey().toString();
            ClaimRecord cr = entry.getValue();
            config.set("claims." + key + ".account", cr.account());
            config.set("claims." + key + ".playerName", cr.playerName());
            config.set("claims." + key + ".bags", new ArrayList<>(cr.bagIds()));
        }
        try {
            config.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("无法保存礼包数据: " + e.getMessage());
        }
    }
}
