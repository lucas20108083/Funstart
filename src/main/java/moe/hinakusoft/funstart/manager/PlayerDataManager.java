package moe.hinakusoft.funstart.manager;

import moe.hinakusoft.funstart.FunstartPlugin;
import moe.hinakusoft.funstart.model.PlayerData;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 玩家数据管理器。
 * <p>
 * 每个玩家一个 {uuid}.yml 文件，缓存到 ConcurrentHashMap 中。
 * 保存操作异步提交到独立线程池，不阻塞主线程。
 * 插件关闭时 saveAll() 同步写出所有脏数据。
 * <p>
 * == 异步保存 ==
 * savePlayerData() 通过 saveExecutor 在独立线程中执行 YAML 写操作。
 * saveAll() 在插件关闭时直接同步写入，确保数据完整。
 */
public class PlayerDataManager {
    private final FunstartPlugin plugin;
    private final File dataFolder;
    /**
     * 玩家数据缓存，UUID → PlayerData
     */
    private final Map<UUID, PlayerData> cache = new ConcurrentHashMap<>();
    /**
     * 单线程异步保存执行器
     */
    private final ExecutorService saveExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "funstart-playerdata-save");
        t.setDaemon(true);
        return t;
    });

    public PlayerDataManager(FunstartPlugin plugin) {
        this.plugin = plugin;
        this.dataFolder = new File(plugin.getDataFolder(), "data");
        this.dataFolder.mkdirs();
    }

    public PlayerData getPlayerData(UUID uuid) {
        return this.cache.computeIfAbsent(uuid, this::loadPlayerData);
    }

    public PlayerData getPlayerData(Player player) {
        return this.getPlayerData(plugin.getEffectiveUuid(player));
    }

    public void savePlayerData(UUID uuid) {
        PlayerData data = this.cache.get(uuid);
        if (data != null) {
            this.saveToFileAsync(uuid, data);
        }
    }

    public void savePlayerData(Player player) {
        UUID effUuid = plugin.getEffectiveUuid(player);
        PlayerData data = this.cache.get(effUuid);
        if (data != null) {
            this.saveToFileAsync(effUuid, data);
        }
    }

    public void saveAll() {
        for (Map.Entry<UUID, PlayerData> entry : this.cache.entrySet()) {
            this.saveToFile(entry.getKey(), entry.getValue());
        }
    }

    private void saveToFileAsync(UUID uuid, PlayerData data) {
        saveExecutor.submit(() -> saveToFile(uuid, data));
    }

    public void cleanAcceptedShares(String warpId) {
        for (Map.Entry<UUID, PlayerData> entry : this.cache.entrySet()) {
            if (entry.getValue().getAcceptedShares().remove(warpId)) {
                this.saveToFileAsync(entry.getKey(), entry.getValue());
            }
        }
    }

    private PlayerData loadPlayerData(UUID uuid) {
        File file = new File(this.dataFolder, uuid.toString() + ".yml");
        if (!file.exists()) {
            return new PlayerData(uuid);
        }
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        PlayerData data = new PlayerData(uuid);
        data.setPoints(config.getDouble("points", 0.0));
        data.setAutoHeal(config.getBoolean("auto-heal", false));
        data.setLastAutoHealTime(config.getLong("last-auto-heal-time", 0L));
        data.setTotalDamageDealt(config.getDouble("total-damage-dealt", 0.0));
        data.setChainEnabled(config.getBoolean("chain-enabled", false));
        data.setHarvestEnabled(config.getBoolean("harvest-enabled", false));
        data.setMaxChainBlocks(config.getInt("max-chain-blocks", 32));
        data.setLastDeathLocation(config.getString("last-death-location"));
        data.setAcceptedShares(new java.util.HashSet<>(config.getStringList("accepted-shares")));
        data.setAutoFix(config.getBoolean("auto-fix", false));
        data.setLastAutoFixTime(config.getLong("last-auto-fix-time", 0L));
        data.setTotalPointsEarned(config.getDouble("total-points-earned", 0.0));
        data.setHasPanelClock(config.getBoolean("has-panel-clock", false));
        data.setZanzhuDailyPoints(config.getDouble("zanzhu-daily-points", 0.0));
        data.setTaskDate(config.getString("tasks.date", ""));
        int[] tp = new int[3];
        for (int i = 0; i < 3; i++) tp[i] = config.getInt("tasks.progress." + i, 0);
        data.setTaskProgress(tp);
        boolean[] tc = new boolean[3];
        for (int i = 0; i < 3; i++) tc[i] = config.getBoolean("tasks.completed." + i, false);
        data.setTaskCompleted(tc);
        return data;
    }

    private void saveToFile(UUID uuid, PlayerData data) {
        File file = new File(this.dataFolder, uuid.toString() + ".yml");
        YamlConfiguration config = new YamlConfiguration();
        config.set("points", data.getPoints());
        config.set("auto-heal", data.isAutoHeal());
        config.set("last-auto-heal-time", data.getLastAutoHealTime());
        config.set("total-damage-dealt", data.getTotalDamageDealt());
        config.set("chain-enabled", data.isChainEnabled());
        config.set("harvest-enabled", data.isHarvestEnabled());
        config.set("max-chain-blocks", data.getMaxChainBlocks());
        config.set("last-death-location", data.getLastDeathLocation());
        config.set("accepted-shares", new java.util.ArrayList<>(data.getAcceptedShares()));
        config.set("auto-fix", data.isAutoFix());
        config.set("last-auto-fix-time", data.getLastAutoFixTime());
        config.set("has-panel-clock", data.hasPanelClock());
        config.set("total-points-earned", data.getTotalPointsEarned());
        config.set("zanzhu-daily-points", data.getZanzhuDailyPoints());
        config.set("tasks.date", data.getTaskDate());
        for (int i = 0; i < 3; i++) {
            config.set("tasks.progress." + i, data.getTaskProgress()[i]);
            config.set("tasks.completed." + i, data.getTaskCompleted()[i]);
        }
        try {
            config.save(file);
        }
        catch (IOException e) {
            this.plugin.getLogger().warning("无法保存玩家数据: " + uuid);
        }
    }
}
