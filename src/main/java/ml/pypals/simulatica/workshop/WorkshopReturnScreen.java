package ml.pypals.simulatica.workshop;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineTextWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

public final class WorkshopReturnScreen extends Screen {
    private int regionPage;
    private boolean busy;
    public WorkshopReturnScreen() { super(Component.translatable("simulatica.workshop.return_title")); }

    @Override
    protected void init() {
        busy = WorkshopManager.isBusy();
        int buttonWidth = Math.min(240, width - 24);
        int x = (width - buttonWidth) / 2;
        int rows = height < 220 ? 1 : 2;
        int y = text(title, 12, 0xFFFFFFFF, rows) + 4;
        y = text(Component.translatable(WorkshopManager.isBusy()
                ? "simulatica.workshop.applying" : "simulatica.workshop.return_description"), y, 0xFFAAAAAA, rows) + 6;
        if (!WorkshopManager.error().getString().isEmpty()) {
            y = text(WorkshopManager.error(), y, 0xFFFF7777, height < 220 ? 1 : 3) + 6;
        }
        Button apply = Button.builder(Component.translatable("simulatica.workshop.apply"), button -> WorkshopManager.finish(true))
                .bounds(x, y, buttonWidth, 20).build();
        var session = WorkshopSession.current();
        boolean disconnected = session != null && session.localFailure() != null;
        apply.active = !WorkshopManager.isBusy() && (session == null || !session.server().isShutdown());
        addRenderableWidget(apply);
        Button discard = Button.builder(Component.translatable("simulatica.workshop.discard"), button -> WorkshopManager.finish(false))
                .bounds(x, y + 24, buttonWidth, 20).build();
        discard.active = !WorkshopManager.isBusy();
        addRenderableWidget(discard);
        Button cancel = Button.builder(Component.translatable("simulatica.workshop.continue"), button -> onClose())
                .bounds(x, y + 48, buttonWidth, 20).build();
        cancel.active = !WorkshopManager.isBusy() && !disconnected;
        addRenderableWidget(cancel);
        int row = y + 80;
        int pageSize = Math.max(1, (height - row - 32) / 24);
        int pages = Math.max(1, (WorkshopManager.conflicts().size() + pageSize - 1) / pageSize);
        regionPage = Math.min(regionPage, pages - 1);
        for (String region : WorkshopManager.conflicts().stream().skip((long) regionPage * pageSize).limit(pageSize).toList()) {
            Button enable = Button.builder(Component.translatable("simulatica.workshop.enable_region", region),
                    button -> WorkshopManager.enableRegion(region)).bounds(x, row, buttonWidth, 20).build();
            enable.active = !busy;
            addRenderableWidget(enable);
            row += 24;
        }
        if (pages > 1) {
            Button previous = Button.builder(Component.translatable("gui.back"), button -> { regionPage--; rebuildWidgets(); })
                    .bounds(x, height - 26, (buttonWidth - 4) / 2, 20).build();
            previous.active = regionPage > 0 && !WorkshopManager.isBusy();
            addRenderableWidget(previous);
            Button next = Button.builder(Component.translatable("simulatica.workshop.next_regions"), button -> { regionPage++; rebuildWidgets(); })
                    .bounds(x + (buttonWidth + 4) / 2, height - 26, (buttonWidth - 4) / 2, 20).build();
            next.active = regionPage < pages - 1 && !WorkshopManager.isBusy();
            addRenderableWidget(next);
        }
    }

    private int text(Component message, int y, int color, int rows) {
        var widget = new MultiLineTextWidget(message.copy().withStyle(style -> style.withColor(color & 0xFFFFFF)), font).setMaxWidth(Math.max(1, width - 24))
                .setMaxRows(rows).setCentered(true);
        widget.setPosition((width - widget.getWidth()) / 2, y);
        widget.setTooltip(Tooltip.create(message));
        addRenderableOnly(widget);
        return y + widget.getHeight();
    }

    @Override
    public void onClose() {
        if (WorkshopSession.current() != null && WorkshopSession.current().localFailure() != null) return;
        if (!WorkshopManager.isBusy()) minecraft.gui.setScreen(null);
    }

    @Override
    public void tick() {
        if (busy != WorkshopManager.isBusy()) rebuildWidgets();
    }
}
