package moe.hinakusoft.funstart.manager;

import moe.hinakusoft.funstart.model.PlayerData;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;

/**
 * 每日任务管理器。
 * <p>
 * 玩家每天可完成的 3 个固定任务（挖掘、击杀、已弃用），
 * 跨日自动重置进度。日期比较使用缓存机制（60 秒刷新一次），
 * 避免每次事件（挖掘、击杀）都创建 Date 对象。
 * <p>
 * 所有方法均为静态，直接操作 PlayerData 中的任务字段。
 */
public class DailyTaskManager {

    /** 三个每日任务的定义：索引 0=挖掘，1=击杀，2=已弃用 */
    public static final List<TaskDef> TASKS = List.of(
        new TaskDef("挖掘方块", "挖掘指定数量的方块", 300, 30.0),
        new TaskDef("击杀生物", "击杀指定数量的生物", 15, 25.0),
        new TaskDef("已弃用", "Unknown", 0, 0.0)
    );
    /**
     * 日期格式化器（非线程安全，但 getTodayDate 单线程调用）
     */
    private static final SimpleDateFormat DATE_FMT = new SimpleDateFormat("yyyy-MM-dd");
    /**
     * 缓存的今日日期字符串 "yyyy-MM-dd"，最多 60 秒刷新一次
     */
    private static String cachedDate = "";
    /**
     * 上次刷新缓存的毫秒时间戳
     */
    private static long lastDateCheck = 0L;

    /**
     * 获取今日日期字符串。
     * 缓存 60 秒，60 秒内直接返回缓存的字符串，避免频繁 new Date()。
     */
    public static String getTodayDate() {
        long now = System.currentTimeMillis();
        if (now - lastDateCheck > 60000L) {
            cachedDate = DATE_FMT.format(new Date());
            lastDateCheck = now;
        }
        return cachedDate;
    }

    /** 任务定义：名称、描述、目标数量、奖励点数 */
    public record TaskDef(String name, String description, int target, double reward) {
    }

    public static void checkAndReset(PlayerData data) {
        String today = getTodayDate();
        if (!today.equals(data.getTaskDate())) {
            data.setTaskDate(today);
            data.setTaskProgress(new int[3]);
            data.setTaskCompleted(new boolean[3]);
        }
    }

    public static void incrementTask(PlayerData data, int index, int amount) {
        checkAndReset(data);
        if (index < 0 || index >= 3) return;
        if (data.getTaskCompleted()[index]) return;
        int[] progress = data.getTaskProgress();
        progress[index] = Math.min(progress[index] + amount, TASKS.get(index).target);
        if (progress[index] >= TASKS.get(index).target) {
            data.getTaskCompleted()[index] = true;
        }
    }

    public static boolean claimReward(PlayerData data, int index) {
        checkAndReset(data);
        if (index < 0 || index >= 3) return false;
        if (!data.getTaskCompleted()[index]) return false;
        data.getTaskCompleted()[index] = false;
        data.getTaskProgress()[index] = 0;
        data.addPoints(TASKS.get(index).reward);
        return true;
    }
}
