package moe.hinakusoft.funstart.manager;

import org.bukkit.block.Block;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Set;
import java.util.function.Predicate;

/**
 * 方块泛洪填充（连通块搜索）工具类。
 * <p>
 * 提供 3D 和 2D 两种泛洪填充实现，用于连锁挖掘和批量收割。
 * 核心优化：利用 Set.add() 的返回值同时完成"存在性检查"与"插入"，
 * 将每个邻居的处理从两次哈希操作减少为一次。
 * 所有方法均在 Bukkit 主线程调用（Block API 线程安全约束），
 * 因此应配合合理的 max 限制使用，避免单次操作过度耗时。
 */
public class BlockUtils {

    /**
     * 3D 泛洪填充：从 origin 出发，沿 26 连通方向扩散，
     * 收集所有满足 matcher 条件的方块，最多收集 max 个。
     *
     * @param origin 起始方块
     * @param matcher 方块匹配条件
     * @param max     最多收集数量
     * @return 匹配方块的集合
     */
    public static Set<Block> floodFill(Block origin, Predicate<Block> matcher, int max) {
        Set<Block> result = new HashSet<>();
        Queue<Block> queue = new LinkedList<>();
        queue.add(origin);
        result.add(origin);

        while (!queue.isEmpty() && result.size() < max) {
            Block current = queue.poll();
            for (int x = -1; x <= 1; x++) {
                for (int y = -1; y <= 1; y++) {
                    for (int z = -1; z <= 1; z++) {
                        if (x == 0 && y == 0 && z == 0) continue;
                        if (result.size() >= max) return result;
                        Block neighbor = current.getRelative(x, y, z);
                        if (matcher.test(neighbor) && result.add(neighbor)) {
                            queue.add(neighbor);
                        }
                    }
                }
            }
        }
        return result;
    }

    /**
     * 2D 泛洪填充：从 origin 出发，沿水平 8 连通方向扩散（忽略 Y 轴），
     * 收集所有满足 matcher 条件的方块，最多收集 max 个。
     * 用于批量收割作物等仅需水平扩散的场景。
     */
    public static Set<Block> floodFill2D(Block origin, Predicate<Block> matcher, int max) {
        Set<Block> result = new HashSet<>();
        Queue<Block> queue = new LinkedList<>();
        queue.add(origin);
        result.add(origin);

        while (!queue.isEmpty() && result.size() < max) {
            Block current = queue.poll();
            for (int x = -1; x <= 1 && result.size() < max; x++) {
                for (int z = -1; z <= 1 && result.size() < max; z++) {
                    if (x == 0 && z == 0) continue;
                    Block neighbor = current.getRelative(x, 0, z);
                    if (matcher.test(neighbor) && result.add(neighbor)) {
                        queue.add(neighbor);
                    }
                }
            }
        }
        return result;
    }
}
