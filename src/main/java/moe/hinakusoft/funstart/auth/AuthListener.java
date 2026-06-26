package moe.hinakusoft.funstart.auth;

import moe.hinakusoft.funstart.FunstartPlugin;
import moe.hinakusoft.funstart.manager.AuthManager;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.*;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class AuthListener implements Listener {

    private final FunstartPlugin plugin;
    private final AuthManager authManager;

    private final Map<UUID, Boolean> authenticated = new HashMap<>();
    private final Map<UUID, RegStep> regSteps = new HashMap<>();
    private final Map<UUID, LoginStep> loginSteps = new HashMap<>();
    private final Map<UUID, Location> joinLocations = new HashMap<>();

    public AuthListener(FunstartPlugin plugin, AuthManager authManager) {
        this.plugin = plugin;
        this.authManager = authManager;
    }

    public boolean isAuthenticated(Player player) {
        return authenticated.getOrDefault(player.getUniqueId(), false);
    }

    public void setAuthenticated(UUID uuid, boolean value) {
        if (value) {
            authenticated.put(uuid, true);
        } else {
            authenticated.remove(uuid);
        }
    }

    public void setUnauthenticated(UUID uuid) {
        authenticated.remove(uuid);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (isAuthenticated(player)) return;

        event.setCancelled(true);
        String msg = event.getMessage();

        if (regSteps.containsKey(uuid)) {
            runSync(() -> processRegStep(player, msg));
        } else if (loginSteps.containsKey(uuid)) {
            runSync(() -> processLoginStep(player, msg));
        } else if (msg.equalsIgnoreCase("register") || msg.equals("注册")) {
            runSync(() -> startRegistration(player));
        } else if (msg.equalsIgnoreCase("login") || msg.equals("登录")) {
            runSync(() -> startLogin(player));
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        joinLocations.put(uuid, player.getLocation().clone());

        String ip = getIp(player);

        if (!authManager.isRegistered(uuid)) {
            player.sendMessage("§c请注册 - 输入 §e register §c或 §e注册");
        } else if (authManager.isAutoLogin(uuid, ip)) {
            setAuthenticated(uuid, true);
            plugin.setEffectiveUuid(uuid, uuid);
            player.sendMessage("§a已自动登录");
        } else {
            player.sendMessage("§c请登录 - 输入 §e login §c或 §e登录");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onMove(PlayerMoveEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();
        if (isAuthenticated(player)) return;

        Location joinLoc = joinLocations.get(uuid);
        if (joinLoc == null) return;
        if (!joinLoc.getWorld().equals(player.getWorld())) return;

        if (player.getLocation().distanceSquared(joinLoc) > 25) {
            player.teleport(joinLoc);
            player.sendMessage("§c请先注册或登录");
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player && !isAuthenticated(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!isAuthenticated(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (!isAuthenticated(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent event) {
        if (!isAuthenticated(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && !isAuthenticated(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player && !isAuthenticated(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        authenticated.remove(uuid);
        regSteps.remove(uuid);
        loginSteps.remove(uuid);
        joinLocations.remove(uuid);
    }

    private void startRegistration(Player player) {
        UUID uuid = player.getUniqueId();
        if (authManager.isRegistered(uuid)) {
            player.sendMessage("§c你已经注册过账号了，无需重复注册");
            player.sendMessage("§c如需重新注册请联系管理员");
            return;
        }
        regSteps.put(uuid, new RegStep());
        player.sendMessage("§e请输入账号名 (仅字母和数字, 输入0使用你的游戏名):");
    }

    private void processRegStep(Player player, String msg) {
        UUID uuid = player.getUniqueId();
        RegStep step = regSteps.get(uuid);
        if (step == null) return;

        switch (step.step) {
            case 0 -> {
                if (!msg.matches("[a-zA-Z0-9]+")) {
                    player.sendMessage("§c账号名只能包含字母和数字, 请重新输入:");
                    return;
                }
                step.username = msg;
                step.step = 1;
                player.sendMessage("§e请输入密码:");
            }
            case 1 -> {
                if (msg.length() < 4) {
                    player.sendMessage("§c密码长度不能少于4位, 请重新输入:");
                    return;
                }
                step.password = msg;
                step.step = 2;
                player.sendMessage("§e请再次输入密码确认:");
            }
            case 2 -> {
                if (!msg.equals(step.password)) {
                    player.sendMessage("§c两次密码输入不一致, 请重新输入密码:");
                    step.step = 1;
                    player.sendMessage("§e请输入密码:");
                    return;
                }
                step.step = 3;
                player.sendMessage("§e是否启用IP自动登录? (y/n)");
            }
            case 3 -> {
                boolean autoLogin = msg.equalsIgnoreCase("y") || msg.equalsIgnoreCase("yes") || msg.equals("是");
                regSteps.remove(uuid);
                String ip = getIp(player);
                authManager.register(uuid, step.username, step.password, ip, autoLogin);
                setAuthenticated(uuid, true);
                player.sendMessage("§a注册成功! 欢迎 " + authManager.getUsername(uuid));
            }
        }
    }

    private void startLogin(Player player) {
        UUID uuid = player.getUniqueId();
        if (isAuthenticated(player)) {
            player.sendMessage("§a你已经登录了");
            return;
        }
        loginSteps.put(uuid, new LoginStep());
        player.sendMessage("§e请输入账号名:");
    }

    private void processLoginStep(Player player, String msg) {
        UUID uuid = player.getUniqueId();
        LoginStep step = loginSteps.get(uuid);
        if (step == null) return;

        switch (step.step) {
            case 0 -> {
                step.username = msg;
                step.step = 1;
                player.sendMessage("§e请输入密码:");
            }
            case 1 -> {
                loginSteps.remove(uuid);
                if (authManager.authenticate(uuid, step.username, msg)) {
                    setAuthenticated(uuid, true);
                    plugin.setEffectiveUuid(uuid, uuid);
                    player.sendMessage("§a登录成功! 欢迎回来");
                } else {
                    player.sendMessage("§c账号名或密码错误! 请重新登录 (/login)");
                }
            }
        }
    }

    private void runSync(Runnable runnable) {
        plugin.getServer().getScheduler().runTask(plugin, runnable);
    }

    private String getIp(Player player) {
        InetSocketAddress addr = player.getAddress();
        return addr != null ? addr.getAddress().getHostAddress() : "unknown";
    }

    static class RegStep {
        String username;
        String password;
        int step;
    }

    static class LoginStep {
        String username;
        int step;
    }
}
