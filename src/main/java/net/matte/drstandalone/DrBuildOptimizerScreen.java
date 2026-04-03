package net.matte.drstandalone;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.Locale;

public class DrBuildOptimizerScreen extends Screen {
    private final Screen parent;
    private DrBuildOptimizerService.OptimizationReport report;
    private String exportMessage = "";

    public DrBuildOptimizerScreen(Screen parent) {
        super(Text.literal("Build Optimizer"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        clearChildren();
        int left = this.width / 2 - 190;
        int top = 14;

        addDrawableChild(ButtonWidget.builder(Text.literal("Scan current gear"), button -> {
            report = DrBuildOptimizerService.analyzeCurrentBuild();
            exportMessage = report.status().equals("OK")
                ? String.format(Locale.ROOT, "Analysis updated: best %s at %.1f DPS.", report.bestClass().classProfile(), report.bestClass().dps())
                : "Analysis incomplete: " + report.status();
        }).dimensions(left + 12, top + 30, 176, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Export HTML"), button -> {
            if (report == null) report = DrBuildOptimizerService.analyzeCurrentBuild();
            DrBuildHtmlExporter.ExportResult result = DrBuildHtmlExporter.exportCurrentBuild(report);
            exportMessage = result.message();
        }).dimensions(left + 194, top + 30, 174, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Close"), button -> close())
            .dimensions(this.width / 2 - 80, this.height - 36, 160, 20)
            .build());

        if (report == null) report = DrBuildOptimizerService.analyzeCurrentBuild();
    }

    @Override
    public void close() {
        this.client.setScreen(parent);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);

        int left = this.width / 2 - 190;
        int top = 14;
        int panelWidth = 380;
        int panelHeight = this.height - 28;

        context.fill(left, top, left + panelWidth, top + panelHeight, 0xD0151A22);
        context.drawBorder(left, top, panelWidth, panelHeight, 0xFF4A4F59);
        context.fill(left + 1, top + 1, left + panelWidth - 1, top + 24, 0xAA1F2631);
        context.drawTextWithShadow(this.textRenderer, Text.literal("Build Optimizer"), left + 12, top + 8, 0xFFF1E6B8);

        super.render(context, mouseX, mouseY, delta);
        if (exportMessage != null && !exportMessage.isBlank()) {
            context.drawTextWithShadow(this.textRenderer, Text.literal(exportMessage), left + 12, top + 60, 0xFF8ED4FF);
        }
        renderReport(context, left + 12, top + 90);
    }

    private void renderReport(DrawContext context, int left, int top) {
        if (report == null) return;

        int y = top;
        DrBuildOptimizerService.PlayerSnapshot snapshot = report.playerSnapshot();

        context.drawTextWithShadow(this.textRenderer, Text.literal("Status: " + report.status()), left, y, 0xFFD8DEEA);
        y += 12;

        context.drawTextWithShadow(this.textRenderer,
            Text.literal(String.format(Locale.ROOT, "Player: %s | Lvl %d %s | HP %.0f/%.0f | Armor %.0f",
                blankOr(snapshot.playerName(), "?"),
                snapshot.level(),
                blankOr(snapshot.hudClass(), ""),
                snapshot.health(),
                snapshot.maxHealth(),
                snapshot.armor())),
            left, y, 0xFFB6C1D0);
        y += 12;

        context.drawTextWithShadow(this.textRenderer,
            Text.literal(String.format(Locale.ROOT, "XP %.0f%% | Hunger %d | Pos %d %d %d",
                snapshot.xpPercent(),
                snapshot.hunger(),
                snapshot.blockX(),
                snapshot.blockY(),
                snapshot.blockZ())),
            left, y, 0xFF9FB0C2);
        y += 12;

        context.drawTextWithShadow(this.textRenderer,
            Text.literal(String.format(Locale.ROOT, "Current class: %s | DPS %.1f", report.currentClass().classProfile(), report.currentClass().dps())),
            left, y, 0xFFB6C1D0);
        y += 12;

        context.drawTextWithShadow(this.textRenderer,
            Text.literal(String.format(Locale.ROOT, "Best class: %s | DPS %.1f", report.bestClass().classProfile(), report.bestClass().dps())),
            left, y, 0xFFF1E6B8);
        y += 14;

        context.drawTextWithShadow(this.textRenderer, Text.literal("Top classes:"), left, y, 0xFFD8DEEA);
        y += 11;
        for (DpsMeterFeature.BuildScore score : report.classRanking()) {
            context.drawTextWithShadow(this.textRenderer,
                Text.literal(String.format(Locale.ROOT, "- %s: %.1f DPS | %.2f APS | TTK %.1fs", score.classProfile(), score.dps(), score.aps(), score.ttk())),
                left + 6, y, 0xFF9FB0C2);
            y += 11;
        }
    }

    private static String blankOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }
}
