package moe.hinakusoft.funstart.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public class ClaimRegion {

    private UUID owner;
    private String worldName;
    private int x1, y1, z1;
    private int x2, y2, z2;
    private Set<UUID> trustedPlayers = new HashSet<>();
    private boolean allowTnt = false;
    private boolean allowCreeper = false;
    private boolean allowExplosiveThrow = true;

    public ClaimRegion() {}

    public ClaimRegion(UUID owner, String worldName, int x1, int y1, int z1, int x2, int y2, int z2) {
        this.owner = owner;
        this.worldName = worldName;
        this.x1 = Math.min(x1, x2);
        this.y1 = Math.min(y1, y2);
        this.z1 = Math.min(z1, z2);
        this.x2 = Math.max(x1, x2);
        this.y2 = Math.max(y1, y2);
        this.z2 = Math.max(z1, z2);
    }

    // ---- Manual serialization (avoids SnakeYAML global tags) ----

    public Map<String, Object> serialize() {
        Map<String, Object> map = new HashMap<>();
        map.put("owner", owner.toString());
        map.put("world", worldName);
        map.put("x1", x1); map.put("y1", y1); map.put("z1", z1);
        map.put("x2", x2); map.put("y2", y2); map.put("z2", z2);
        map.put("allowTnt", allowTnt);
        map.put("allowCreeper", allowCreeper);
        map.put("allowExplosiveThrow", allowExplosiveThrow);
        List<String> trusted = new ArrayList<>();
        for (UUID u : trustedPlayers) trusted.add(u.toString());
        map.put("trusted", trusted);
        return map;
    }

    @SuppressWarnings("unchecked")
    public static ClaimRegion deserialize(Map<String, Object> map) {
        ClaimRegion c = new ClaimRegion();
        c.owner = UUID.fromString((String) map.get("owner"));
        c.worldName = (String) map.get("world");
        c.x1 = (int) map.get("x1"); c.y1 = (int) map.get("y1"); c.z1 = (int) map.get("z1");
        c.x2 = (int) map.get("x2"); c.y2 = (int) map.get("y2"); c.z2 = (int) map.get("z2");
        if (map.containsKey("allowTnt")) c.allowTnt = (boolean) map.get("allowTnt");
        if (map.containsKey("allowCreeper")) c.allowCreeper = (boolean) map.get("allowCreeper");
        if (map.containsKey("allowExplosiveThrow")) c.allowExplosiveThrow = (boolean) map.get("allowExplosiveThrow");
        List<String> trusted = (List<String>) map.getOrDefault("trusted", new ArrayList<>());
        for (String s : trusted) c.trustedPlayers.add(UUID.fromString(s));
        return c;
    }

    // ---- Methods ----

    public boolean contains(String worldName, int x, int y, int z) {
        if (!this.worldName.equals(worldName)) return false;
        return x >= x1 && x <= x2 && y >= y1 && y <= y2 && z >= z1 && z <= z2;
    }

    public boolean overlaps(ClaimRegion other) {
        if (!this.worldName.equals(other.worldName)) return false;
        return this.x1 <= other.x2 && this.x2 >= other.x1
            && this.y1 <= other.y2 && this.y2 >= other.y1
            && this.z1 <= other.z2 && this.z2 >= other.z1;
    }

    public long getVolume() {
        long dx = (long) x2 - x1 + 1;
        long dy = (long) y2 - y1 + 1;
        long dz = (long) z2 - z1 + 1;
        return dx * dy * dz;
    }

    // ---- Getters/Setters ----

    public UUID getOwner() { return owner; }
    public void setOwner(UUID owner) { this.owner = owner; }
    public String getWorldName() { return worldName; }
    public void setWorldName(String worldName) { this.worldName = worldName; }
    public int getX1() { return x1; }
    public void setX1(int x1) { this.x1 = x1; }
    public int getY1() { return y1; }
    public void setY1(int y1) { this.y1 = y1; }
    public int getZ1() { return z1; }
    public void setZ1(int z1) { this.z1 = z1; }
    public int getX2() { return x2; }
    public void setX2(int x2) { this.x2 = x2; }
    public int getY2() { return y2; }
    public void setY2(int y2) { this.y2 = y2; }
    public int getZ2() { return z2; }
    public void setZ2(int z2) { this.z2 = z2; }
    public Set<UUID> getTrustedPlayers() { return trustedPlayers; }
    public void setTrustedPlayers(Set<UUID> trustedPlayers) { this.trustedPlayers = trustedPlayers; }
    public boolean isAllowTnt() { return allowTnt; }
    public void setAllowTnt(boolean allowTnt) { this.allowTnt = allowTnt; }
    public boolean isAllowCreeper() { return allowCreeper; }
    public void setAllowCreeper(boolean allowCreeper) { this.allowCreeper = allowCreeper; }
    public boolean isAllowExplosiveThrow() { return allowExplosiveThrow; }
    public void setAllowExplosiveThrow(boolean allowExplosiveThrow) { this.allowExplosiveThrow = allowExplosiveThrow; }
}
