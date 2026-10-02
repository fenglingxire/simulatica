package ml.pypals.simulatica.counter;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import ml.pypals.simulatica.simulation.server.SimulationLevel;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * [SIMULATICA-新增] 羊毛漏斗计数器（参考 Carpet 的 hopperCounters）。
 *
 * <p>羊毛方块放在漏斗的朝向（FACING）方向上时，漏斗弹出（ejectItems）的物品按羊毛颜色累计计数，
 * 并被清空（吞掉），用于测量机器/农场的物品产出速率。每个投影按颜色计数，以模拟 tick 计时。</p>
 */
public final class HopperCounter {

    private static final Map<SimulationLevel, Map<DyeColor, HopperCounter>> COUNTERS = new java.util.LinkedHashMap<>();

    private final DyeColor color;
    private final SimulationLevel level;
    private final Map<Item, Long> counts = new HashMap<>();
    private long total;
    private long startTick = -1;

    private HopperCounter(SimulationLevel level, DyeColor color) {
        this.level = level;
        this.color = color;
    }

    public static HopperCounter getCounter(SimulationLevel level, DyeColor color) {
        return COUNTERS.computeIfAbsent(level, ignored -> new EnumMap<>(DyeColor.class))
                .computeIfAbsent(color, ignored -> new HopperCounter(level, color));
    }

    /** 累计一个物品堆（按数量），记录首个物品到达时间用于速率计算。 */
    public void add(ItemStack stack) {
        long n = stack.getCount();
        this.counts.merge(stack.getItem(), n, Long::sum);
        this.total += n;
        if (this.startTick < 0) {
            this.startTick = level.clock.ticks();
        }
    }

    public void reset() {
        this.counts.clear();
        this.total = 0;
        this.startTick = -1;
    }

    public static void resetAll() {
        COUNTERS.values().forEach(colors -> colors.values().forEach(HopperCounter::reset));
    }

    public static void clearAll() { COUNTERS.clear(); }

    public long count(Item item) { return counts.getOrDefault(item, 0L); }

    public DyeColor color() {
        return this.color;
    }

    public long total() {
        return this.total;
    }

    /**
     * 所有正在运行的计数器汇总（参考 Carpet 的 /counter 显示）：每个颜色一行，列出物品与数量
     * （按数量降序）、总数与每小时速率，字体用对应羊毛颜色渲染。
     */
    public static List<Component> formatAll() {
        List<Component> out = new ArrayList<>();
        COUNTERS.forEach((level, colors) -> {
            if (colors.values().stream().noneMatch(counter -> counter.total > 0)) return;
            out.add(Component.literal("投影：" + level.projectionName()));
            for (DyeColor color : DyeColor.values()) {
                HopperCounter counter = colors.get(color);
                if (counter != null && counter.total > 0) out.addAll(counter.formatLines());
            }
        });
        if (!out.isEmpty()) out.add(Component.literal("全局颜色汇总（正常速度产量）"));
        for (DyeColor color : DyeColor.values()) {
            Map<Item, Long> counts = new HashMap<>();
            Map<Item, Double> rates = new HashMap<>();
            long total = 0;
            double rate = 0;
            for (Map<DyeColor, HopperCounter> colors : COUNTERS.values()) {
                HopperCounter counter = colors.get(color);
                if (counter == null) continue;
                total += counter.total;
                rate += counter.perHour(counter.total);
                counter.counts.forEach((item, count) -> {
                    counts.merge(item, count, Long::sum);
                    rates.merge(item, counter.perHour(count), Double::sum);
                });
            }
            if (total == 0) continue;
            out.add(Component.literal(colorName(color) + ": 共 " + total + "，" + formatRate(rate) + "/h")
                    .withColor(color.getTextColor()));
            counts.entrySet().stream().sorted(Map.Entry.<Item, Long>comparingByValue().reversed()).forEach(entry ->
                    out.add(Component.literal("  " + Component.translatable(entry.getKey().getDescriptionId()).getString()
                            + " x" + entry.getValue() + "（" + formatRate(rates.get(entry.getKey())) + "/h）")
                            .withColor(color.getTextColor())));
        }
        if (out.isEmpty()) {
            out.add(Component.literal("尚未统计到任何物品。").withColor(0xFFAAAAAA));
        }
        return out;
    }

    private List<Component> formatLines() {
        List<Map.Entry<Item, Long>> entries = new ArrayList<>(this.counts.entrySet());
        entries.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));

        int textColor = this.color.getTextColor();
        List<Component> lines = new ArrayList<>();

        // 标题行：颜色名 + 总数 + 总速率
        lines.add(Component.literal(colorName(this.color) + ": 共 " + this.total
                + "，" + ratePerHour(this.total) + "/h").withColor(textColor));

        // 每种物品一行（竖式，参考 Carpet /counter）：物品名 x数量（该物品每小时产量）
        for (Map.Entry<Item, Long> entry : entries) {
            long count = entry.getValue();
            String itemName = Component.translatable(entry.getKey().getDescriptionId()).getString();
            lines.add(Component.literal("  " + itemName + " x" + count
                    + "（" + ratePerHour(count) + "/h）").withColor(textColor));
        }
        return lines;
    }

    /** Normal-speed production, independent of requested and achieved wall-clock TPS. */
    private String ratePerHour(long count) {
        return formatRate(perHour(count));
    }

    public double perHour(long count) {
        long elapsed = level.clock.ticks() - startTick;
        return startTick < 0 || elapsed <= 0 ? 0 : count * 72_000.0 / elapsed;
    }

    private static String formatRate(double rate) { return String.format(java.util.Locale.ROOT, "%.1f", rate); }

    private static String colorName(DyeColor color) {
        return switch (color) {
            case WHITE -> "白色";
            case ORANGE -> "橙色";
            case MAGENTA -> "品红";
            case LIGHT_BLUE -> "淡蓝";
            case YELLOW -> "黄色";
            case LIME -> "黄绿";
            case PINK -> "粉色";
            case GRAY -> "灰色";
            case LIGHT_GRAY -> "淡灰";
            case CYAN -> "青色";
            case PURPLE -> "紫色";
            case BLUE -> "蓝色";
            case BROWN -> "棕色";
            case GREEN -> "绿色";
            case RED -> "红色";
            case BLACK -> "黑色";
        };
    }
}
