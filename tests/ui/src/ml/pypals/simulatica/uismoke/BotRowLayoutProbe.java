package ml.pypals.simulatica.uismoke;

import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * [测试专用] {@code PlacementConfigScreen} 假人行布局探针。
 *
 * <p>直接读出屏幕上真实的按钮控件（不是另抄一份布局算式），按「同一列必须同一个 x」
 * 检查纵向成列，并检查按钮是否越出窗口。</p>
 *
 * <p>背景：假人行的按钮起点以前是跟着该行名字的长度算的（{@code flowX += nameWidth + 4}），
 * 所以 "Bot1" 和 "Bot10" 的行按钮起点不同、多行之间不在一条竖线上；同时整块还比
 * 上面那排工具按钮往右偏移了一个名字的宽度。这里就是钉死这两个回归。</p>
 */
public final class BotRowLayoutProbe {

    private BotRowLayoutProbe() {}

    /** 面板里跟假人相关的按钮标签。工具按钮和原生按钮不含这些词。 */
    private static final List<String> SLOT_LABELS = List.of("背包", "传送", "移除");

    /** 一个假人行里的四个按钮，按屏幕上的先后顺序。 */
    public record Row(int y, List<Integer> xs, List<Integer> rights, List<String> labels) {}

    /**
     * 从真实屏幕上抓出所有假人行的控件位置。
     *
     * @param screen 已 {@code resize()} 过的面板
     * @param botCount 期望的假人行数
     */
    public static List<Row> rows(Screen screen, int botCount) {
        // 按钮在 children() 里是按创建顺序的：工具按钮、然后每个假人 4 个、最后原生 2 个。
        // 用「模式:」标签定位每个假人行的起点最稳，因为只有那一列带冒号前缀。
        List<AbstractWidget> widgets = new ArrayList<>();
        for (var child : screen.children()) {
            if (child instanceof AbstractWidget w) {
                List<String> labels = SLOT_LABELS;
                String msg = w.getMessage().getString();
                boolean slot = labels.stream().anyMatch(msg::equals) || msg.startsWith("模式:");
                if (slot) {
                    widgets.add(w);
                }
            }
        }

        // 按 y 分行
        Map<Integer, List<AbstractWidget>> byY = new LinkedHashMap<>();
        for (AbstractWidget w : widgets) {
            byY.computeIfAbsent(w.getY(), k -> new ArrayList<>()).add(w);
        }

        List<Row> rows = new ArrayList<>();
        for (var entry : byY.entrySet()) {
            List<AbstractWidget> line = entry.getValue();
            line.sort((a, b) -> Integer.compare(a.getX(), b.getX()));
            List<Integer> xs = new ArrayList<>();
            List<Integer> rights = new ArrayList<>();
            List<String> labels = new ArrayList<>();
            for (AbstractWidget w : line) {
                xs.add(w.getX());
                rights.add(w.getX() + w.getWidth());
                labels.add(w.getMessage().getString());
            }
            rows.add(new Row(entry.getKey(), xs, rights, labels));
        }
        return rows;
    }

    /**
     * 检查纵向成列 + 不越界。
     *
     * @return 问题列表，空表示通过
     */
    public static List<String> check(Screen screen, int botCount, int width, int height) {
        List<String> issues = new ArrayList<>();
        List<Row> rows = rows(screen, botCount);
        if (rows.size() != botCount) {
            issues.add(String.format("假人行数 %d != 期望 %d（抓到的行 y=%s）",
                    rows.size(), botCount, yOf(rows)));
        }
        if (rows.isEmpty()) {
            return issues;
        }

        // 1. 每一列在所有行里必须是同一个 x —— 这就是「错位」的本质
        Row first = rows.get(0);
        int cols = first.xs().size();
        for (Row r : rows) {
            if (r.xs().size() != cols) {
                issues.add(String.format("行 y=%d 有 %d 个按钮，首行有 %d 个", r.y(), r.xs().size(), cols));
                continue;
            }
            for (int c = 0; c < cols; c++) {
                if (!r.xs().get(c).equals(first.xs().get(c))) {
                    issues.add(String.format("列 %d 错位：行 y=%d x=%d，首行 y=%d x=%d（相差 %d）",
                            c, r.y(), r.xs().get(c), first.y(), first.xs().get(c),
                            r.xs().get(c) - first.xs().get(c)));
                }
            }
        }

        // 2. 按钮不得越出窗口左右边界
        for (Row r : rows) {
            for (int c = 0; c < r.xs().size(); c++) {
                int x = r.xs().get(c);
                int right = r.rights().get(c);
                if (x < 0 || right > width) {
                    issues.add(String.format("行 y=%d 第 %d 列越界：x=%d right=%d 窗口宽=%d",
                            r.y(), c, x, right, width));
                }
            }
        }

        // 3. 同一行内按钮不得互相重叠
        for (Row r : rows) {
            for (int c = 1; c < r.xs().size(); c++) {
                if (r.xs().get(c) < r.rights().get(c - 1)) {
                    issues.add(String.format("行 y=%d 第 %d 列与第 %d 列重叠", r.y(), c - 1, c));
                }
            }
        }
        return issues;
    }

    /** 假人行第一个按钮的 x，用来跟上面那排工具按钮的左边缘对比偏移量。 */
    public static int firstColumnX(Screen screen) {
        List<Row> rows = rows(screen, 2);
        return rows.isEmpty() ? Integer.MIN_VALUE : rows.get(0).xs().get(0);
    }

    private static String yOf(List<Row> rows) {
        List<Integer> ys = new ArrayList<>();
        for (Row r : rows) {
            ys.add(r.y());
        }
        return ys.toString();
    }
}
