package moe.hinakusoft.funstart.manager;

import moe.hinakusoft.funstart.FunstartPlugin;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 玩家认证管理器。
 * <p>
 * 使用 SHA-256 哈希密码，提供登录、注册、IP 自动登录等功能。
 * 数据以 YAML 格式持久化到 auth.yml。
 * <p>
 * == 异步去重保存 ==
 * 每次密码变更（注册、改密、重置）不会立即写文件，而是：
 * 1. 设置脏标记 (dirty = true)
 * 2. 调度 3 秒后执行 syncSave()
 * 3. 3 秒内的多次变更合并为一次写入
 * 这大幅降低了高频操作时的 YAML 全量写开销。
 * 插件关闭时通过 flush() 保证最后一批数据写出。
 */
public class AuthManager {

    private final FunstartPlugin plugin;
    /** auth.yml 文件引用 */
    private final File file;
    /** UUID → 账号数据 内存缓存 */
    private final Map<UUID, AccountData> accounts = new HashMap<>();
    /**
     * 异步保存线程池
     */
    private final ScheduledExecutorService saveExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "funstart-auth-save");
        t.setDaemon(true);
        return t;
    });
    private volatile boolean dirty = false;

    public AuthManager(FunstartPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "auth.yml");
        load();
    }

    private void markDirty() {
        if (!dirty) {
            dirty = true;
            saveExecutor.schedule(this::syncSave, 3, TimeUnit.SECONDS);
        }
    }

    private synchronized void syncSave() {
        if (!dirty) return;
        dirty = false;
        save();
    }

    public void flush() {
        syncSave();
    }

    public boolean isRegistered(UUID uuid) {
        return accounts.containsKey(uuid);
    }

    public void register(UUID uuid, String username, String password, String ip, boolean autoLogin) {
        String actualUsername = "0".equals(username) ? plugin.getServer().getOfflinePlayer(uuid).getName() : username;
        if (actualUsername == null) actualUsername = username;
        accounts.put(uuid, new AccountData(actualUsername, hashPassword(password), ip, autoLogin, System.currentTimeMillis()));
        markDirty();
    }

    public boolean authenticate(UUID uuid, String username, String password) {
        AccountData data = accounts.get(uuid);
        if (data == null) return false;
        return data.username.equals(username) && data.passwordHash.equals(hashPassword(password));
    }

    public UUID authenticateByCredentials(String username, String password) {
        String hash = hashPassword(password);
        for (Map.Entry<UUID, AccountData> e : accounts.entrySet()) {
            if (e.getValue().username.equals(username) && e.getValue().passwordHash.equals(hash)) {
                return e.getKey();
            }
        }
        return null;
    }

    public UUID findUuidByUsername(String username) {
        for (Map.Entry<UUID, AccountData> e : accounts.entrySet()) {
            if (e.getValue().username.equals(username)) {
                return e.getKey();
            }
        }
        return null;
    }

    public UUID findByAutoLoginIp(String ip, UUID excludeUuid) {
        for (Map.Entry<UUID, AccountData> e : accounts.entrySet()) {
            if (!e.getKey().equals(excludeUuid) && e.getValue().autoLogin && e.getValue().ip.equals(ip)) {
                return e.getKey();
            }
        }
        return null;
    }

    public boolean isAutoLogin(UUID uuid, String ip) {
        AccountData data = accounts.get(uuid);
        return data != null && data.autoLogin && data.ip.equals(ip);
    }

    public void setAutoLogin(UUID uuid, String ip, boolean autoLogin) {
        AccountData data = accounts.get(uuid);
        if (data == null) return;
        data.autoLogin = autoLogin;
        data.ip = ip;
        markDirty();
    }

    public String getUsername(UUID uuid) {
        AccountData data = accounts.get(uuid);
        return data != null ? data.username : null;
    }

    public void changePassword(UUID uuid, String newPassword) {
        AccountData data = accounts.get(uuid);
        if (data == null) return;
        data.passwordHash = hashPassword(newPassword);
        save();
    }

    public boolean resetPassword(UUID uuid, String newPassword) {
        AccountData data = accounts.get(uuid);
        if (data == null) return false;
        data.passwordHash = hashPassword(newPassword);
        markDirty();
        return true;
    }

    public boolean resetPassword(String username, String newPassword) {
        for (AccountData data : accounts.values()) {
            if (data.username.equals(username)) {
                data.passwordHash = hashPassword(newPassword);
                save();
                return true;
            }
        }
        return false;
    }

    public void save() {
        YamlConfiguration config = new YamlConfiguration();
        for (Map.Entry<UUID, AccountData> entry : accounts.entrySet()) {
            String key = entry.getKey().toString();
            AccountData data = entry.getValue();
            config.set("accounts." + key + ".username", data.username);
            config.set("accounts." + key + ".passwordHash", data.passwordHash);
            config.set("accounts." + key + ".ip", data.ip);
            config.set("accounts." + key + ".autoLogin", data.autoLogin);
            config.set("accounts." + key + ".registeredTime", data.registeredTime);
        }
        try {
            config.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("无法保存认证数据: " + e.getMessage());
        }
    }

    public void load() {
        accounts.clear();
        if (!file.exists()) return;
        YamlConfiguration config = YamlConfiguration.loadConfiguration(file);
        if (!config.contains("accounts")) return;
        for (String key : config.getConfigurationSection("accounts").getKeys(false)) {
            UUID uuid = UUID.fromString(key);
            String username = config.getString("accounts." + key + ".username");
            String passwordHash = config.getString("accounts." + key + ".passwordHash");
            String ip = config.getString("accounts." + key + ".ip");
            boolean autoLogin = config.getBoolean("accounts." + key + ".autoLogin");
            long registeredTime = config.getLong("accounts." + key + ".registeredTime");
            accounts.put(uuid, new AccountData(username, passwordHash, ip, autoLogin, registeredTime));
        }
    }

    public static String hashPassword(String password) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(password.getBytes());
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private static class AccountData {
        String username;
        String passwordHash;
        String ip;
        boolean autoLogin;
        long registeredTime;

        AccountData(String username, String passwordHash, String ip, boolean autoLogin, long registeredTime) {
            this.username = username;
            this.passwordHash = passwordHash;
            this.ip = ip;
            this.autoLogin = autoLogin;
            this.registeredTime = registeredTime;
        }
    }
}
