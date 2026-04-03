package net.matte.drstandalone;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class DrBuildOptimizerScreen extends Screen {
    private final Screen parent;
    private DrBuildOptimizerService.OptimizationReport report;

    private TextFieldWidget apiKeyField;
    private TextFieldWidget endpointField;
    private String exportMessage = "";

    public DrBuildOptimizerScreen(Screen parent) {
        super(Text.literal("Build Optimizer"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        clearChildren();
        DrStandaloneConfig config = DrStandaloneMod.config();

        int left = this.width / 2 - 190;
        int top = 14;

        addDrawableChild(ButtonWidget.builder(Text.literal("Scan current gear"), button -> {
            report = DrBuildOptimizerService.analyzeCurrentBuild();
        }).dimensions(left + 12, top + 30, 176, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("API cloud: " + (config.optimizerCloudAdvisorEnabled ? "ON" : "OFF")), button -> {
            config.optimizerCloudAdvisorEnabled = !config.optimizerCloudAdvisorEnabled;
            button.setMessage(Text.literal("API cloud: " + (config.optimizerCloudAdvisorEnabled ? "ON" : "OFF")));
            config.save();
        }).dimensions(left + 194, top + 30, 174, 20).build());

        apiKeyField = new TextFieldWidget(this.textRenderer, left + 12, top + 56, 356, 18, Text.literal("API key"));
        apiKeyField.setPlaceholder(Text.literal("API key (optional)"));
        apiKeyField.setText(DrBuildOptimizerService.getSessionApiKey());
        addDrawableChild(apiKeyField);

        endpointField = new TextFieldWidget(this.textRenderer, left + 12, top + 78, 356, 18, Text.literal("Endpoint"));
        endpointField.setPlaceholder(Text.literal("API endpoint (optional)"));
        endpointField.setText(config.optimizerApiEndpoint == null ? "" : config.optimizerApiEndpoint);
        addDrawableChild(endpointField);

        addDrawableChild(ButtonWidget.builder(Text.literal("Save API"), button -> {
            String endpoint = endpointField.getText().trim();
            if (!DrBuildOptimizerService.isTrustedEndpoint(endpoint)) {
                exportMessage = "Endpoint rejected: use HTTPS and an allowlisted host.";
                return;
            }

            DrBuildOptimizerService.setSessionApiKey(apiKeyField.getText());
            config.optimizerApiEndpoint = endpoint;
            config.save();
            exportMessage = "API settings saved (key is session-only).";
        }).dimensions(left + 12, top + 102, 110, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Export HTML"), button -> {
            if (report == null) report = DrBuildOptimizerService.analyzeCurrentBuild();
            DrBuildHtmlExporter.ExportResult result = DrBuildHtmlExporter.exportCurrentBuild(report);
            exportMessage = result.message();
        }).dimensions(left + 126, top + 102, 116, 20).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("Close"), button -> close())
            .dimensions(this.width / 2 - 80, this.height - 36, 160, 20)
            .build());

        if (report == null) report = DrBuildOptimizerService.analyzeCurrentBuild();
        setInitialFocus(apiKeyField);
    }

    @Override
    public void tick() {
        super.tick();
        if (apiKeyField != null) apiKeyField.tick();
        if (endpointField != null) endpointField.tick();
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (apiKeyField != null && apiKeyField.isFocused() && apiKeyField.charTyped(chr, modifiers)) return true;
        if (endpointField != null && endpointField.isFocused() && endpointField.charTyped(chr, modifiers)) return true;
        return super.charTyped(chr, modifiers);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (apiKeyField != null && apiKeyField.keyPressed(keyCode, scanCode, modifiers)) return true;
        if (endpointField != null && endpointField.keyPressed(keyCode, scanCode, modifiers)) return true;
        return super.keyPressed(keyCode, scanCode, modifiers);
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
            context.drawTextWithShadow(this.textRenderer, Text.literal(exportMessage), left + 12, top + 126, 0xFF8ED4FF);
        }
        renderSecurityChecks(context, left + 12, top + 138);
        renderReport(context, left + 12, top + 176);
    }

    private void renderSecurityChecks(DrawContext context, int left, int top) {
        DrStandaloneConfig config = DrStandaloneMod.config();
        String keyStatus = DrBuildOptimizerService.getSessionApiKey().isBlank() ?
            "API key: not set (safe)" :
            "API key: loaded in memory only (not saved)";
        String endpointStatus = "Endpoint: " + DrBuildOptimizerService.endpointSecurityMessage(config.optimizerApiEndpoint);

        context.drawTextWithShadow(this.textRenderer, Text.literal("Security checks:"), left, top, 0xFFD8DEEA);
        context.drawTextWithShadow(this.textRenderer, Text.literal("- " + keyStatus), left + 6, top + 11, 0xFF9FD7A8);
        int endpointColor = DrBuildOptimizerService.isTrustedEndpoint(config.optimizerApiEndpoint) ? 0xFF9FD7A8 : 0xFFFF8E8E;
        context.drawTextWithShadow(this.textRenderer, Text.literal("- " + endpointStatus), left + 6, top + 22, endpointColor);
    }

    private void renderReport(DrawContext context, int left, int top) {
        if (report == null) return;

        int y = top;
        context.drawTextWithShadow(this.textRenderer, Text.literal("Status: " + report.status()), left, y, 0xFFD8DEEA);
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

        y += 4;
        context.drawTextWithShadow(this.textRenderer, Text.literal("Recommendations:"), left, y, 0xFFD8DEEA);
        y += 11;
        for (String rec : report.recommendations()) {
            for (String wrapped : wrap(rec, 56)) {
                context.drawTextWithShadow(this.textRenderer, Text.literal("• " + wrapped), left + 6, y, 0xFFB8BDC8);
                y += 11;
            }
        }
    }

    private static List<String> wrap(String text, int maxLength) {
        List<String> lines = new ArrayList<>();
        String remaining = text == null ? "" : text;
        while (remaining.length() > maxLength) {
            int breakAt = remaining.lastIndexOf(' ', maxLength);
            if (breakAt <= 0) breakAt = maxLength;
            lines.add(remaining.substring(0, breakAt).trim());
            remaining = remaining.substring(breakAt).trim();
        }
        if (!remaining.isEmpty()) lines.add(remaining);
        if (lines.isEmpty()) lines.add("");
        return lines;
    }
}
