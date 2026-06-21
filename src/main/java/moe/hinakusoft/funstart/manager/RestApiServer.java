package moe.hinakusoft.funstart.manager;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import moe.hinakusoft.funstart.FunstartPlugin;
import moe.hinakusoft.funstart.model.PlayerData;
import org.bukkit.Bukkit;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Executors;

/**
 * 内嵌 REST API 服务器。
 * <p>
 * 在端口 29966 上提供 HTTP 接口：
 * - /              Web 管理面板（静态 HTML）
 * - /api/status    服务器状态（TPS、内存、在线人数）
 * - /api/players   在线玩家详细信息
 * - /api/ranking   点数排行榜前 50
 * <p>
 * 使用 Java 21 虚拟线程执行器，每个请求独立协程处理，
 * 替代原单线程执行器，消除并发请求相互阻塞的问题。
 */
public class RestApiServer {

    private final FunstartPlugin plugin;
    private final HttpServer server;
    private final byte[] webHtml;

    public RestApiServer(FunstartPlugin plugin, int port) throws IOException {
        this.plugin = plugin;
        this.server = HttpServer.create(new InetSocketAddress(port), 0);
        this.server.createContext("/", new WebHandler());
        this.server.createContext("/api/ranking", new RankingHandler());
        this.server.createContext("/api/players", new PlayersHandler());
        this.server.createContext("/api/status", new StatusHandler());
        this.server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());

        byte[] html = null;
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("web/index.html")) {
            if (is != null) {
                html = is.readAllBytes();
            }
        } catch (Exception ignored) {}
        this.webHtml = html;
    }

    public void start() {
        server.start();
        plugin.getLogger().info("REST API 已启动, 端口: " + server.getAddress().getPort());
    }

    public void stop() {
        server.stop(0);
        plugin.getLogger().info("REST API 已停止");
    }

    private void jsonResponse(HttpExchange exchange, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private void errorResponse(HttpExchange exchange, int code, String msg) throws IOException {
        String json = "{\"error\":\"" + escapeJson(msg) + "\"}";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String escapeJson(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
            .replace("\n", "\\n").replace("\r", "\\r")
            .replace("\t", "\\t");
    }

    private static String toJsonString(String s) {
        return "\"" + escapeJson(s) + "\"";
    }

    // ========== Web UI ==========

    private class WebHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            if (!path.equals("/") && !path.equals("/index.html")) {
                byte[] notFound = "404 Not Found".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(404, notFound.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(notFound);
                }
                return;
            }
            if (webHtml == null) {
                byte[] msg = "Web UI not available".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(503, msg.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(msg);
                }
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
            exchange.sendResponseHeaders(200, webHtml.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(webHtml);
            }
        }
    }

    // ========== /api/ranking ==========

    private class RankingHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                errorResponse(exchange, 405, "Method not allowed");
                return;
            }

            PlayerDataManager pdm = plugin.getPlayerDataManager();
            List<PlayerData> all = new ArrayList<>();

            for (Player online : Bukkit.getOnlinePlayers()) {
                all.add(pdm.getPlayerData(online));
            }

            all.sort(Comparator.comparingDouble(PlayerData::getTotalPointsEarned).reversed());

            StringBuilder json = new StringBuilder("[");
            boolean first = true;
            for (int i = 0; i < Math.min(all.size(), 50); i++) {
                PlayerData data = all.get(i);
                if (!first) json.append(",");
                first = false;
                Player player = Bukkit.getPlayer(data.getUuid());
                json.append("{");
                json.append("\"rank\":").append(i + 1).append(",");
                json.append("\"uuid\":").append(toJsonString(data.getUuid().toString())).append(",");
                json.append("\"name\":").append(toJsonString(player != null ? player.getName() : "未知")).append(",");
                json.append("\"totalPointsEarned\":").append(String.format("%.2f", data.getTotalPointsEarned())).append(",");
                json.append("\"totalDamageDealt\":").append(String.format("%.1f", data.getTotalDamageDealt())).append(",");
                json.append("\"health\":").append(player != null ? String.format("%.1f", player.getHealth()) : "0").append(",");
                json.append("\"level\":").append(player != null ? player.getLevel() : 0).append(",");
                json.append("\"playTime\":").append(player != null ? player.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20 : 0);
                json.append("}");
            }
            json.append("]");

            jsonResponse(exchange, json.toString());
        }
    }

    // ========== /api/players ==========

    private class PlayersHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                errorResponse(exchange, 405, "Method not allowed");
                return;
            }

            String query = exchange.getRequestURI().getQuery();
            String name = null;
            if (query != null) {
                for (String param : query.split("&")) {
                    String[] parts = param.split("=", 2);
                    if (parts.length == 2 && "name".equalsIgnoreCase(parts[0])) {
                        name = parts[1];
                    }
                }
            }

            PlayerDataManager pdm = plugin.getPlayerDataManager();

            if (name != null && !name.isEmpty()) {
                Player player = Bukkit.getPlayerExact(name);
                if (player == null) {
                    errorResponse(exchange, 404, "Player not found");
                    return;
                }
                PlayerData data = pdm.getPlayerData(player);
                jsonResponse(exchange, playerToJson(player, data));
            } else {
                StringBuilder json = new StringBuilder("[");
                boolean first = true;
                for (Player online : Bukkit.getOnlinePlayers()) {
                    if (!first) json.append(",");
                    first = false;
                    PlayerData data = pdm.getPlayerData(online);
                    json.append(playerToJson(online, data));
                }
                json.append("]");
                jsonResponse(exchange, json.toString());
            }
        }

        private String playerToJson(Player player, PlayerData data) {
            org.bukkit.Location loc = player.getLocation();
            StringBuilder j = new StringBuilder("{");
            j.append("\"uuid\":").append(toJsonString(player.getUniqueId().toString())).append(",");
            j.append("\"name\":").append(toJsonString(player.getName())).append(",");
            j.append("\"displayName\":").append(toJsonString(player.getDisplayName())).append(",");
            j.append("\"health\":").append(String.format("%.1f", player.getHealth())).append(",");
            j.append("\"maxHealth\":").append(String.format("%.1f", player.getMaxHealth())).append(",");
            j.append("\"foodLevel\":").append(player.getFoodLevel()).append(",");
            j.append("\"saturation\":").append(String.format("%.1f", player.getSaturation())).append(",");
            j.append("\"level\":").append(player.getLevel()).append(",");
            j.append("\"expProgress\":").append(String.format("%.2f", player.getExp())).append(",");
            j.append("\"gameMode\":").append(toJsonString(player.getGameMode().name())).append(",");
            j.append("\"world\":").append(toJsonString(player.getWorld().getName())).append(",");
            j.append("\"ping\":").append(player.getPing()).append(",");
            j.append("\"x\":").append(String.format("%.1f", loc.getX())).append(",");
            j.append("\"y\":").append(String.format("%.1f", loc.getY())).append(",");
            j.append("\"z\":").append(String.format("%.1f", loc.getZ())).append(",");
            j.append("\"biome\":").append(toJsonString(loc.getBlock().getBiome().getKey().toString())).append(",");
            j.append("\"totalPointsEarned\":").append(String.format("%.2f", data.getTotalPointsEarned())).append(",");
            j.append("\"totalDamageDealt\":").append(String.format("%.1f", data.getTotalDamageDealt())).append(",");
            j.append("\"autoHeal\":").append(data.isAutoHeal()).append(",");
            j.append("\"autoFix\":").append(data.isAutoFix()).append(",");
            j.append("\"chainEnabled\":").append(data.isChainEnabled()).append(",");
            j.append("\"harvestEnabled\":").append(data.isHarvestEnabled()).append(",");
            j.append("\"maxChainBlocks\":").append(data.getMaxChainBlocks()).append(",");
            j.append("\"playTime\":").append(player.getStatistic(Statistic.PLAY_ONE_MINUTE) / 20).append(",");
            j.append("\"walkDistance\":").append(String.format("%.1f", player.getStatistic(Statistic.WALK_ONE_CM) / 100.0)).append(",");
            j.append("\"jumps\":").append(player.getStatistic(Statistic.JUMP)).append(",");
            j.append("\"mobKills\":").append(player.getStatistic(Statistic.MOB_KILLS)).append(",");
            j.append("\"deaths\":").append(player.getStatistic(Statistic.DEATHS)).append(",");
            j.append("\"playerKills\":").append(player.getStatistic(Statistic.PLAYER_KILLS)).append(",");
            j.append("\"blocksBroken\":").append(player.getStatistic(Statistic.MINE_BLOCK));
            j.append("}");
            return j.toString();
        }
    }

    // ========== /api/status ==========

    private class StatusHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                errorResponse(exchange, 405, "Method not allowed");
                return;
            }

            Runtime rt = Runtime.getRuntime();
            long usedMem = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
            long totalMem = rt.totalMemory() / (1024 * 1024);
            double[] tps = Bukkit.getTPS();

            StringBuilder json = new StringBuilder("{");
            json.append("\"server\":").append(toJsonString("Funstart")).append(",");
            json.append("\"version\":").append(toJsonString(Bukkit.getVersion())).append(",");
            json.append("\"bukkitVersion\":").append(toJsonString(Bukkit.getBukkitVersion())).append(",");
            json.append("\"onlinePlayers\":").append(Bukkit.getOnlinePlayers().size()).append(",");
            json.append("\"maxPlayers\":").append(Bukkit.getMaxPlayers()).append(",");
            json.append("\"motd\":").append(toJsonString(Bukkit.getMotd())).append(",");
            json.append("\"tps1m\":").append(String.format("%.2f", tps[0])).append(",");
            json.append("\"tps5m\":").append(String.format("%.2f", tps[1])).append(",");
            json.append("\"tps15m\":").append(String.format("%.2f", tps[2])).append(",");
            json.append("\"usedMemory\":").append(usedMem).append(",");
            json.append("\"totalMemory\":").append(totalMem).append(",");
            json.append("\"worlds\":").append(Bukkit.getWorlds().size()).append(",");
            json.append("\"gameVersion\":").append(toJsonString(Bukkit.getMinecraftVersion()));
            json.append("}");

            jsonResponse(exchange, json.toString());
        }
    }

}
