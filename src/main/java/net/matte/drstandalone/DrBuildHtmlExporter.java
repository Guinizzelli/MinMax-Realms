package net.matte.drstandalone;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

public final class DrBuildHtmlExporter {
    private static final DateTimeFormatter FILE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT).withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter DISPLAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z", Locale.ROOT).withZone(ZoneId.systemDefault());

    private DrBuildHtmlExporter() {
    }

    public static ExportResult exportCurrentBuild(DrBuildOptimizerService.OptimizationReport report) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return ExportResult.error("Export failed: player not found.");

        Instant now = Instant.now();
        String timestamp = DISPLAY_FORMAT.format(now);
        String fileStamp = FILE_FORMAT.format(now);

        String html = renderHtml(
            mc.player.getName().getString(),
            timestamp,
            report
        );

        Path exportDir = FabricLoader.getInstance().getConfigDir().resolve("dr-standalone").resolve("exports");
        Path output = exportDir.resolve("build-" + fileStamp + ".html");

        try {
            Files.createDirectories(exportDir);
            Files.writeString(output, html);
            return ExportResult.success(output);
        } catch (IOException e) {
            return ExportResult.error("HTML export error: " + e.getMessage());
        }
    }

    private static String renderHtml(String playerName, String timestamp, DrBuildOptimizerService.OptimizationReport report) {
        StringBuilder classRows = new StringBuilder();
        for (DpsMeterFeature.BuildScore score : report.classRanking()) {
            classRows.append("<tr>")
                .append("<td>").append(esc(score.classProfile())).append("</td>")
                .append("<td>").append(format(score.dps())).append("</td>")
                .append("<td>").append(format(score.aps())).append("</td>")
                .append("<td>").append(format(score.ttk())).append("</td>")
                .append("</tr>");
        }

        StringBuilder itemRows = new StringBuilder();
        for (DrBuildOptimizerService.ItemCheck check : report.itemChecks()) {
            itemRows.append("<tr>")
                .append("<td>").append(esc(check.slotLabel())).append("</td>")
                .append("<td>").append(esc(check.itemName())).append("</td>")
                .append("<td>").append(check.averageRollPercent() < 0 ? "N/A" : format(check.averageRollPercent()) + "%").append("</td>")
                .append("<td>").append(check.matchedStats()).append("</td>")
                .append("</tr>");
        }

        StringBuilder recommendations = new StringBuilder();
        for (String rec : report.recommendations()) {
            recommendations.append("<li>").append(esc(rec)).append("</li>");
        }

        return """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8" />
              <meta name="viewport" content="width=device-width, initial-scale=1" />
              <title>MinMax Realms - Build Export</title>
              <style>
                body{font-family:Inter,Segoe UI,Arial,sans-serif;background:#10141b;color:#e8edf6;margin:0;padding:24px}
                .wrap{max-width:980px;margin:0 auto}
                .card{background:#171d27;border:1px solid #2f3948;border-radius:10px;padding:16px;margin-bottom:14px}
                h1,h2{margin:0 0 10px 0}
                .meta{color:#9eb0c8;font-size:14px}
                table{width:100%;border-collapse:collapse;margin-top:8px}
                th,td{border-bottom:1px solid #2b3443;padding:8px;text-align:left}
                th{color:#9ec0ff;font-weight:600}
                .pill{display:inline-block;padding:3px 8px;border-radius:999px;background:#29364a;color:#d6e3ff;font-size:12px}
              </style>
            </head>
            <body>
              <div class="wrap">
                <div class="card">
                  <h1>MinMax Realms - Build Optimizer Export</h1>
                  <div class="meta">Player: %s</div>
                  <div class="meta">Date: %s</div>
                  <div class="meta">Status: %s</div>
                  <p><span class="pill">Current class: %s (DPS %s)</span> <span class="pill">Best class: %s (DPS %s)</span></p>
                </div>

                <div class="card">
                  <h2>DPS ranking</h2>
                  <table>
                    <thead><tr><th>Class</th><th>DPS</th><th>APS</th><th>TTK</th></tr></thead>
                    <tbody>%s</tbody>
                  </table>
                </div>

                <div class="card">
                  <h2>Analyzed gear</h2>
                  <table>
                    <thead><tr><th>Slot</th><th>Item</th><th>Avg roll</th><th>Matched stats</th></tr></thead>
                    <tbody>%s</tbody>
                  </table>
                </div>

                <div class="card">
                  <h2>Recommendations</h2>
                  <ul>%s</ul>
                </div>
              </div>
            </body>
            </html>
            """.formatted(
            esc(playerName),
            esc(timestamp),
            esc(report.status()),
            esc(report.currentClass().classProfile()),
            format(report.currentClass().dps()),
            esc(report.bestClass().classProfile()),
            format(report.bestClass().dps()),
            classRows,
            itemRows,
            recommendations
        );
    }

    private static String format(double value) {
        if (!Double.isFinite(value)) return "∞";
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static String esc(String value) {
        if (value == null) return "";
        return value.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;");
    }

    public record ExportResult(boolean ok, String message, Path path) {
        public static ExportResult success(Path path) {
            return new ExportResult(true, "HTML export created: " + path.toAbsolutePath(), path);
        }

        public static ExportResult error(String message) {
            return new ExportResult(false, message, null);
        }
    }
}
