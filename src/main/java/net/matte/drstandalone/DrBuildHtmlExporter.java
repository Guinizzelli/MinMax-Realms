package net.matte.drstandalone;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.Identifier;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

public final class DrBuildHtmlExporter {
    private static final DateTimeFormatter FILE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss", Locale.ROOT).withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter DISPLAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss z", Locale.ROOT).withZone(ZoneId.systemDefault());
    private static final List<String> CORE_STAT_PRIORITY = List.of(
        "STR", "DEX", "VIT", "INT", "HP", "ENERGY REGEN", "DMG", "ACCURACY", "EXECUTE", "BLOCK", "ITEM FIND", "KEY FIND"
    );
    private static final List<String> ATTRIBUTE_PRIORITY = List.of("STR", "DEX", "VIT", "INT");
    private static final List<String> OFFENSE_PRIORITY = List.of(
        "DMG", "PURE DMG", "FIRE DMG", "ICE DMG", "POISON DMG",
        "ACCURACY", "CRITICAL HIT", "EXECUTE", "BLEEDING", "PIERCING",
        "SHATTER", "CRUSHING", "CLEAVE", "LIFE STEAL", "GLOWING",
        "BLINDING", "SLOWNESS", "VS. MONSTERS", "VS. PLAYERS"
    );
    private static final List<String> DEFENSE_PRIORITY = List.of(
        "HP", "ARMOR", "HP REGEN", "ENERGY REGEN", "BLOCK", "DODGE",
        "REFLECT", "THORNS", "ABSORPTION", "FIRE RESIST", "ICE RESIST",
        "POISON RESIST", "PURE RESIST", "ELEMENTAL RESIST"
    );
    private static final List<String> UTILITY_PRIORITY = List.of(
        "MOVE SPEED", "COOLDOWN RECOVERY", "HEALING", "ENERGY DRAIN", "HP RECOVERY", "ITEM FIND", "KEY FIND", "GEM FIND"
    );
    private static final List<String> RESISTANCE_PRIORITY = List.of(
        "FIRE RESIST", "ICE RESIST", "POISON RESIST", "PURE RESIST", "ELEMENTAL RESIST"
    );

    private DrBuildHtmlExporter() {
    }

    public static ExportResult exportCurrentBuild(DrBuildOptimizerService.OptimizationReport report) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return ExportResult.error("Export failed: player not found.");

        Instant now = Instant.now();
        String timestamp = DISPLAY_FORMAT.format(now);
        String fileStamp = FILE_FORMAT.format(now);
        String html = renderHtml(mc.player.getName().getString(), timestamp, report);

        Path downloadDir = downloadDirectory();
        Path output = downloadDir.resolve("minmax-realms-build-" + fileStamp + ".html");
        Path latest = downloadDir.resolve("minmax-realms-build-latest.html");
        Path legacyExportDir = legacyExportDirectory();
        Path legacyLatest = legacyExportDir.resolve("build-latest.html");

        try {
            Files.createDirectories(downloadDir);
            Files.createDirectories(legacyExportDir);
            Files.writeString(output, html);
            Files.writeString(latest, html);
            Files.writeString(legacyLatest, html);
            return ExportResult.success(output);
        } catch (IOException e) {
            return ExportResult.error("HTML export error: " + e.getMessage());
        }
    }

    private static String renderHtml(String playerName, String timestamp, DrBuildOptimizerService.OptimizationReport report) {
        DrBuildOptimizerService.PlayerSnapshot snapshot = report.playerSnapshot();
        List<DrBuildOptimizerService.StatTotal> attributeStats = selectAttributeStats(report.statTotals());
        List<DrBuildOptimizerService.StatTotal> offenseStats = selectFixedStats(report.statTotals(), "Offense", OFFENSE_PRIORITY);
        List<DrBuildOptimizerService.StatTotal> defenseStats = selectFixedStats(report.statTotals(), "Defense", DEFENSE_PRIORITY);
        List<DrBuildOptimizerService.StatTotal> utilityStats = selectFixedStats(report.statTotals(), "Utility", UTILITY_PRIORITY);
        List<DrBuildOptimizerService.ItemCheck> items = report.itemChecks();
        DrBuildOptimizerService.ItemCheck weakestItem = findWeakestItem(items);
        DrBuildOptimizerService.ItemCheck strongestItem = findStrongestItem(items);
        double buildQuality = averageBuildRoll(items);
        String currentClass = blankOr(report.currentClass().classProfile(), "Unknown");
        String bestClass = blankOr(report.bestClass().classProfile(), "Unknown");
        String hudClass = blankOr(snapshot.hudClass(), currentClass);
        String topFocus = buildTopFocus(report.statTotals());

        StringBuilder gearCards = new StringBuilder();
        for (DrBuildOptimizerService.ItemCheck item : items) {
            gearCards.append(renderGearCard(item, weakestItem, strongestItem));
        }

        StringBuilder sidebarSimRows = new StringBuilder();
        for (DpsMeterFeature.BuildScore score : report.classRanking()) {
            boolean isCurrent = score.classProfile().equalsIgnoreCase(report.currentClass().classProfile());
            boolean isBest = score.classProfile().equalsIgnoreCase(report.bestClass().classProfile());
            sidebarSimRows.append("<div class=\"sidebar-sim\">")
                .append("<div class=\"sidebar-sim-name\">").append(esc(score.classProfile())).append("</div>")
                .append("<div class=\"sidebar-sim-meta\">")
                .append(isBest ? "<span class=\"tiny-badge best\">Best</span>" : isCurrent ? "<span class=\"tiny-badge\">Current</span>" : "")
                .append("</div>")
                .append("<div class=\"sidebar-sim-score\">").append(format(score.dps())).append(" DPS</div>")
                .append("</div>");
        }

        StringBuilder attributeSidebarStats = new StringBuilder();
        appendSidebarStatTiles(attributeSidebarStats, attributeStats);
        StringBuilder offenseSidebarStats = new StringBuilder();
        appendSidebarStatTiles(offenseSidebarStats, offenseStats);
        StringBuilder defenseSidebarStats = new StringBuilder();
        appendSidebarStatTiles(defenseSidebarStats, defenseStats);
        StringBuilder utilitySidebarStats = new StringBuilder();
        appendSidebarStatTiles(utilitySidebarStats, utilityStats);

        String html = """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8" />
              <meta name="viewport" content="width=device-width, initial-scale=1" />
              <title>MinMax Realms - The Arcanist's Ledger</title>
              <link rel="preconnect" href="https://fonts.googleapis.com">
              <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin>
              <link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;600;700&family=Noto+Serif:wght@400;700&family=Work+Sans:wght@500;700&display=swap" rel="stylesheet">
              <style>
                :root{
                  --sidebar-width:324px;
                  --background:#131313;
                  --surface:#131313;
                  --surface-low:#1c1b1b;
                  --surface-high:#2a2a2a;
                  --surface-highest:#353535;
                  --surface-lowest:#0e0e0e;
                  --surface-bright:#393939;
                  --primary:#e9c176;
                  --primary-dim:#c5a059;
                  --text:#e5e2e1;
                  --text-soft:#d1c5b4;
                  --text-dim:#9a8f80;
                  --ghost:rgba(154,143,128,.18);
                  --common:#ffffff;
                  --uncommon:#31e731;
                  --rare:#5385ff;
                  --epic:#bd42ff;
                  --legendary:#ff9d00;
                  --mythic:#ff5ebc;
                  --good:#8fda9d;
                  --bad:#ff8b7f;
                }
                *{box-sizing:border-box}
                html{scroll-behavior:smooth}
                body{
                  margin:0;
                  background:
                    radial-gradient(circle at top right, rgba(233,193,118,.08), transparent 28%),
                    radial-gradient(circle at top left, rgba(197,160,89,.06), transparent 22%),
                    var(--background);
                  color:var(--text);
                  font-family:"Inter", system-ui, sans-serif;
                }
                a{color:inherit;text-decoration:none}
                .app-shell{min-height:100vh;display:grid;grid-template-columns:var(--sidebar-width) 10px minmax(0,1fr)}
                .sidebar{
                  position:sticky;top:0;align-self:start;height:100vh;overflow:auto;z-index:40;
                  background:var(--surface-low);border-right:1px solid var(--ghost);
                  padding:24px 20px 28px;display:flex;flex-direction:column;gap:14px;
                  scrollbar-width:none;-ms-overflow-style:none;
                }
                .sidebar::-webkit-scrollbar{width:0;height:0;display:none}
                .sidebar-resizer{
                  position:sticky;top:0;height:100vh;cursor:col-resize;background:transparent;z-index:45;
                  display:flex;align-items:stretch;justify-content:center;
                }
                .sidebar-resizer::before{
                  content:"";width:2px;height:100%;background:rgba(154,143,128,.12);transition:background .15s ease, width .15s ease;
                }
                .sidebar-resizer:hover::before,
                .sidebar-resizer.is-dragging::before{
                  width:3px;background:rgba(233,193,118,.5);
                }
                .profile{display:flex;align-items:center;gap:12px}
                .avatar{
                  width:56px;height:56px;display:grid;place-items:center;background:linear-gradient(180deg,var(--surface-highest),var(--surface-lowest));
                  color:var(--primary);font-family:"Noto Serif", serif;font-size:22px;font-weight:700;
                }
                .profile-name{font-family:"Noto Serif", serif;font-size:16px;color:var(--primary)}
                .profile-meta{margin-top:3px;font-size:11.5px;color:var(--text-soft);text-transform:uppercase;letter-spacing:.12em;font-family:"Work Sans", sans-serif;display:flex;flex-direction:column;gap:2px}
                .sidebar-summary{background:var(--surface-lowest);padding:12px;display:flex;flex-direction:column;gap:10px}
                .sidebar-section{display:flex;flex-direction:column;gap:6px}
                .sidebar-card-title{margin:0 0 8px;font-family:"Noto Serif", serif;font-size:18px;color:var(--primary);text-transform:uppercase;letter-spacing:.12em;line-height:1.1}
                .sidebar-inline-stat{display:grid;grid-template-columns:auto 1fr;align-items:center;gap:12px}
                .sidebar-inline-stat strong{font-family:"Noto Serif", serif;font-size:28px;color:var(--text);line-height:1}
                .sidebar-inline-stat span{font-size:10px;color:var(--text-soft);line-height:1.35}
                .sidebar-stat-groups{display:flex;flex-direction:column;gap:10px}
                .sidebar-stat-group{display:flex;flex-direction:column;gap:6px}
                .sidebar-stat-title{font-family:"Work Sans", sans-serif;font-size:9px;text-transform:uppercase;letter-spacing:.14em;color:var(--text-dim)}
                .sidebar-stat-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:6px}
                .sidebar-stat-item{display:flex;justify-content:space-between;align-items:baseline;gap:8px;padding:6px 8px;background:rgba(255,255,255,.03)}
                .sidebar-stat-item span{font-size:9px;color:var(--text-soft);text-transform:uppercase;letter-spacing:.08em}
                .sidebar-stat-item strong{font-size:12px;color:var(--text)}
                .sidebar-sim-list{display:flex;flex-direction:column;gap:6px}
                .sidebar-sim{
                  display:grid;grid-template-columns:minmax(0,1fr) auto;grid-template-areas:"name meta" "score score";column-gap:10px;row-gap:6px;align-items:start;padding:10px;background:rgba(255,255,255,.03)
                }
                .sidebar-sim-name{grid-area:name;font-family:"Noto Serif", serif;font-size:13px;line-height:1.2}
                .sidebar-sim-meta{grid-area:meta;margin:0;font-size:9px;color:var(--text-dim);text-transform:uppercase;letter-spacing:.12em;text-align:right;white-space:nowrap}
                .sidebar-sim-score{grid-area:score;font-size:11px;color:var(--text-soft);text-align:left;padding-top:2px;border-top:1px solid rgba(154,143,128,.1)}
                .content{margin-left:0;padding:24px 24px 32px}
                .main-grid{display:grid;grid-template-columns:minmax(320px,420px) minmax(0,1fr);gap:24px;align-items:start}
                .panel{background:var(--surface-low);padding:24px}
                .section-title{margin:0 0 18px;font-family:"Noto Serif", serif;font-size:24px;color:var(--primary);text-transform:uppercase;letter-spacing:.12em}
                .micro-label{font-family:"Work Sans", sans-serif;font-size:10px;text-transform:uppercase;letter-spacing:.16em;color:var(--text-dim);font-weight:700}
                .left-stack,.center-stack{display:flex;flex-direction:column;gap:24px}
                .core-list{display:flex;flex-direction:column;gap:10px}
                .core-row{display:flex;align-items:flex-end;justify-content:space-between;padding-bottom:8px;border-bottom:1px solid rgba(154,143,128,.12)}
                .core-value{font-size:18px;color:var(--text)}
                .empty-note{color:var(--text-dim);font-size:13px;line-height:1.5}
                .armory-head{display:flex;justify-content:space-between;align-items:flex-start;gap:16px;padding:0 8px}
                .manifest-title{margin:0;font-family:"Noto Serif", serif;font-size:32px;letter-spacing:.02em}
                .manifest-sub{margin-top:8px;color:var(--text-soft);font-size:14px;line-height:1.5}
                .rarity-legend{display:flex;gap:8px;flex-wrap:wrap;justify-content:flex-end}
                .legend-chip{width:14px;height:14px;background:var(--surface-highest)}
                .legend-common{background:var(--common)}
                .legend-uncommon{background:var(--uncommon)}
                .legend-rare{background:var(--rare)}
                .legend-epic{background:var(--epic)}
                .legend-legendary{background:var(--legendary)}
                .gear-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:16px}
                .gear-card{background:var(--surface-low);padding:20px 18px 18px;border-left:3px solid var(--surface-highest);transition:background .2s ease}
                .gear-card:hover{background:var(--surface-bright)}
                .rarity-common{border-left-color:var(--common)}
                .rarity-uncommon{border-left-color:var(--uncommon)}
                .rarity-rare{border-left-color:var(--rare)}
                .rarity-epic{border-left-color:var(--epic)}
                .rarity-legendary{border-left-color:var(--legendary)}
                .rarity-mythic{border-left-color:var(--mythic)}
                .gear-card.empty{
                  background:var(--surface-lowest);border:2px dashed rgba(154,143,128,.25);
                  min-height:248px;display:flex;flex-direction:column;align-items:center;justify-content:center;text-align:center;
                }
                .gear-top{display:flex;justify-content:space-between;gap:14px;margin-bottom:14px}
                .gear-kicker{font-family:"Work Sans", sans-serif;font-size:10px;text-transform:uppercase;letter-spacing:.16em;font-weight:700}
                .gear-name{margin:6px 0 0;font-family:"Noto Serif", serif;font-size:22px;line-height:1.15}
                .icon-box{width:54px;height:54px;background:var(--surface-lowest);display:grid;place-items:center;color:var(--primary);font-family:"Noto Serif", serif;font-size:18px;font-weight:700;flex:0 0 auto;overflow:hidden}
                .icon-art{width:40px;height:40px;object-fit:contain;image-rendering:pixelated;display:block}
                .icon-svg{width:34px;height:34px;display:block}
                .gear-meta{display:flex;flex-wrap:wrap;gap:8px;margin-bottom:12px}
                .meta-chip{display:inline-block;padding:5px 8px;background:var(--surface-highest);font-size:10px;text-transform:uppercase;letter-spacing:.12em;font-family:"Work Sans", sans-serif;color:var(--text-soft)}
                .gear-stats{display:flex;flex-direction:column;gap:8px}
                .gear-stat{display:flex;justify-content:space-between;gap:16px;font-size:12px;padding-bottom:8px;border-bottom:1px solid rgba(154,143,128,.08)}
                .gear-stat:last-child{border-bottom:none;padding-bottom:0}
                .gear-stat-name{color:var(--text-soft)}
                .gear-stat-value{color:var(--text);text-align:right}
                .gear-footer{
                  margin-top:16px;padding-top:14px;border-top:1px solid rgba(154,143,128,.12);display:flex;justify-content:space-between;gap:12px;flex-wrap:wrap;
                  font-size:11px;text-transform:uppercase;letter-spacing:.14em;color:var(--text-dim);font-family:"Work Sans", sans-serif;
                }
                .quality-good{color:var(--good)}
                .quality-bad{color:var(--bad)}
                .quality-mid{color:var(--primary)}
                .hero-metrics{display:grid;grid-template-columns:2fr 1fr;gap:16px}
                .hero-card{background:var(--surface-low);padding:22px;position:relative;overflow:hidden}
                .hero-card::after{
                  content:"";position:absolute;right:-60px;bottom:-60px;width:200px;height:200px;
                  background:radial-gradient(circle, rgba(233,193,118,.14), transparent 60%);
                }
                .hero-stat{position:relative;z-index:1}
                .hero-score{font-family:"Noto Serif", serif;font-size:42px;color:var(--text);margin-top:6px}
                .bar-track{height:6px;background:var(--surface-lowest);margin-top:14px}
                .bar-fill{height:100%;background:linear-gradient(90deg,var(--primary-dim),var(--legendary))}
                .compact-card{background:var(--surface-low);padding:22px;display:flex;flex-direction:column;justify-content:center;align-items:flex-start}
                .compact-value{margin-top:6px;font-size:34px;font-weight:700}
                .metric-panel .metric-list{display:flex;flex-direction:column;gap:18px}
                .metric-row{display:flex;flex-direction:column;gap:8px}
                .metric-top{display:flex;justify-content:space-between;gap:12px}
                .metric-name{font-family:"Work Sans", sans-serif;font-size:10px;text-transform:uppercase;letter-spacing:.15em;color:var(--text-dim);font-weight:700}
                .metric-value{font-size:17px;color:var(--text)}
                .sim-panel-inner{background:var(--surface-lowest);padding:16px}
                .sim-intro{font-size:12px;color:var(--text-soft);line-height:1.6;margin:0 0 18px}
                .sim-list{display:flex;flex-direction:column;gap:14px}
                .sim-row{display:flex;flex-direction:column;gap:8px}
                .sim-top{display:flex;justify-content:space-between;gap:16px}
                .sim-class{font-family:"Noto Serif", serif;font-size:18px}
                .sim-meta{font-size:11px;color:var(--text-dim);text-transform:uppercase;letter-spacing:.12em}
                .sim-score{text-align:right;font-size:14px}
                .tiny-badge{display:inline-block;margin-left:6px;padding:2px 6px;background:var(--surface-highest);font-size:9px;text-transform:uppercase;letter-spacing:.14em;color:var(--text-soft)}
                .tiny-badge.best{color:var(--primary)}
                .metric-tile-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:8px}
                .metric-tile{background:var(--surface-highest);padding:10px}
                .metric-tile .tile-value{margin-top:4px;font-size:16px;font-weight:700}
                .totals-panel .totals-group{margin-top:18px}
                .totals-panel .totals-group:first-of-type{margin-top:0}
                .group-title{margin:0 0 10px;font-family:"Work Sans", sans-serif;font-size:10px;text-transform:uppercase;letter-spacing:.16em;color:var(--text-dim)}
                .merged-stats{display:flex;flex-direction:column;gap:18px}
                .muted{color:var(--text-dim)}
                @media (max-width: 1400px){
                  .main-grid{grid-template-columns:1fr}
                }
                @media (max-width: 1080px){
                  .app-shell{display:block}
                  .sidebar{position:static;width:auto;height:auto;overflow:visible;border-right:none;border-bottom:1px solid var(--ghost)}
                  .sidebar-resizer{display:none}
                  .content{margin-left:0;padding-top:24px}
                  .gear-grid,.hero-metrics,.mini-grid,.metric-tile-grid{grid-template-columns:1fr}
                }
              </style>
            </head>
            <body>
              <div class="app-shell">
                <aside class="sidebar">
                  <div class="profile">
                    <div class="avatar">__PLAYER_INITIAL__</div>
                    <div>
                      <div class="profile-name">__PLAYER__</div>
                      <div class="profile-meta"><span>Level __LEVEL__</span><span>XP __XP__</span><span>__HUD_CLASS__</span></div>
                    </div>
                  </div>
                  <div class="sidebar-summary">
                    <div class="sidebar-section">
                      <div class="sidebar-card-title">Build Quality</div>
                      <div class="sidebar-inline-stat">
                        <strong>__BUILD_QUALITY__</strong>
                        <span>Average recognized roll quality across equipped items.</span>
                      </div>
                    </div>
                    <div class="sidebar-section">
                      <div class="sidebar-card-title">Stats</div>
                      <div class="sidebar-stat-groups">
                        <div class="sidebar-stat-group">
                          <div class="sidebar-stat-title">Attributes</div>
                          <div class="sidebar-stat-grid">__SIDEBAR_ATTRIBUTES__</div>
                        </div>
                        <div class="sidebar-stat-group">
                          <div class="sidebar-stat-title">Offense</div>
                          <div class="sidebar-stat-grid">__SIDEBAR_OFFENSE__</div>
                        </div>
                        <div class="sidebar-stat-group">
                          <div class="sidebar-stat-title">Defense</div>
                          <div class="sidebar-stat-grid">__SIDEBAR_DEFENSE__</div>
                        </div>
                        <div class="sidebar-stat-group">
                          <div class="sidebar-stat-title">Utility</div>
                          <div class="sidebar-stat-grid">__SIDEBAR_UTILITY__</div>
                        </div>
                      </div>
                    </div>
                    <div class="sidebar-section">
                      <div class="sidebar-card-title">Simulations</div>
                      <div style="font-size:10px;line-height:1.45;color:var(--text-soft);">Optimizer favors __BEST_CLASS__ over __CURRENT_CLASS__. Focus: __TOP_FOCUS__.</div>
                      <div class="sidebar-sim-list">__SIDEBAR_SIM_ROWS__</div>
                    </div>
                  </div>
                </aside>
                <div class="sidebar-resizer" id="sidebarResizer" aria-hidden="true"></div>

                <main class="content">
                  <div class="main-grid">
                    <section class="center-stack">
                      <section id="armory">
                        <div class="armory-head">
                          <div>
                            <h2 class="manifest-title">Player Build</h2>
                          </div>
                          <div class="rarity-legend">
                            <span class="legend-chip legend-common"></span>
                            <span class="legend-chip legend-uncommon"></span>
                            <span class="legend-chip legend-rare"></span>
                            <span class="legend-chip legend-epic"></span>
                            <span class="legend-chip legend-legendary"></span>
                          </div>
                        </div>
                      </section>

                      <section class="gear-grid">__GEAR_CARDS__</section>

                    </section>
                  </div>
                </main>
              </div>
              <script>
                (() => {
                  const root = document.documentElement;
                  const resizer = document.getElementById('sidebarResizer');
                  if (!resizer) return;

                  const minWidth = 280;
                  const maxWidth = 520;
                  let dragging = false;

                  const setWidth = (width) => {
                    const clamped = Math.max(minWidth, Math.min(maxWidth, width));
                    root.style.setProperty('--sidebar-width', clamped + 'px');
                  };

                  resizer.addEventListener('mousedown', (event) => {
                    dragging = true;
                    resizer.classList.add('is-dragging');
                    document.body.style.userSelect = 'none';
                    setWidth(event.clientX);
                  });

                  window.addEventListener('mousemove', (event) => {
                    if (!dragging) return;
                    setWidth(event.clientX);
                  });

                  window.addEventListener('mouseup', () => {
                    if (!dragging) return;
                    dragging = false;
                    resizer.classList.remove('is-dragging');
                    document.body.style.userSelect = '';
                  });
                })();
              </script>
            </body>
            </html>
            """;

        return html
            .replace("__PLAYER_INITIAL__", esc(playerInitial(playerName)))
            .replace("__PLAYER__", esc(playerName))
            .replace("__LEVEL__", Integer.toString(snapshot.level()))
            .replace("__XP__", format(snapshot.xpPercent()) + "%")
            .replace("__HUD_CLASS__", esc(hudClass))
            .replace("__TOP_FOCUS__", esc(topFocus))
            .replace("__CURRENT_CLASS__", esc(currentClass))
            .replace("__BEST_CLASS__", esc(bestClass))
            .replace("__BUILD_QUALITY__", format(buildQuality) + "%")
            .replace("__GEAR_CARDS__", gearCards.toString())
            .replace("__SIDEBAR_ATTRIBUTES__", sidebarTileGridOrEmpty(attributeSidebarStats, "No attribute stats"))
            .replace("__SIDEBAR_OFFENSE__", sidebarTileGridOrEmpty(offenseSidebarStats, "No offensive stats"))
            .replace("__SIDEBAR_DEFENSE__", sidebarTileGridOrEmpty(defenseSidebarStats, "No defensive stats"))
            .replace("__SIDEBAR_UTILITY__", sidebarTileGridOrEmpty(utilitySidebarStats, "No utility stats"))
            .replace("__SIDEBAR_SIM_ROWS__", sidebarSimRows.toString())
            .replace("__WEAKEST_SLOT__", esc(weakestItem == null ? "Unknown" : weakestItem.itemName()))
            .replace("__STRONGEST_SLOT__", esc(strongestItem == null ? "Unknown" : strongestItem.itemName()));
    }

    private static Path downloadDirectory() {
        Path home = Paths.get(System.getProperty("user.home", "."));
        Path downloads = home.resolve("Downloads");
        return Files.isDirectory(downloads) || !Files.exists(downloads) ? downloads : home;
    }

    private static Path legacyExportDirectory() {
        return FabricLoader.getInstance().getConfigDir().resolve("dr-standalone").resolve("exports");
    }

    private static String renderGearCard(DrBuildOptimizerService.ItemCheck item,
                                         DrBuildOptimizerService.ItemCheck weakestItem,
                                         DrBuildOptimizerService.ItemCheck strongestItem) {
        if ("(empty)".equals(item.itemName())) {
            return """
                <section class="gear-card empty">
                  <div class="icon-box">+</div>
                  <div class="gear-name" style="margin-top:14px;">Empty Slot</div>
                  <div class="muted" style="margin-top:8px;">No item equipped in %s.</div>
                </section>
                """.formatted(esc(item.slotLabel()));
        }

        String rarity = blankOr(item.rarity(), "Unknown");
        String rarityClass = "rarity-" + rarity.toLowerCase(Locale.ROOT);
        boolean isWeakest = weakestItem != null && weakestItem.itemName().equals(item.itemName()) && weakestItem.slotLabel().equals(item.slotLabel());
        boolean isStrongest = strongestItem != null && strongestItem.itemName().equals(item.itemName()) && strongestItem.slotLabel().equals(item.slotLabel());

        String slotLabel = blankOr(item.slotLabel(), "");
        String itemType = blankOr(item.itemType(), "");
        String materialLabel = blankOr(item.materialLabel(), "");

        StringBuilder meta = new StringBuilder();
        if (!slotLabel.isBlank()) meta.append(chip(slotLabel));
        if (shouldDisplayItemType(slotLabel, itemType)) meta.append(chip(itemType));
        if (shouldDisplayMaterialLabel(slotLabel, itemType, materialLabel)) meta.append(chip(materialLabel));
        if (item.tier() > 0) meta.append(chip("T" + item.tier()));
        if (item.level() > 0) meta.append(chip("Lvl " + item.level()));
        if (isWeakest) meta.append(chip("Weakest"));
        if (isStrongest) meta.append(chip("Strongest"));

        StringBuilder stats = new StringBuilder();
        for (DrBuildOptimizerService.StatLine stat : item.stats()) {
            stats.append("<div class=\"gear-stat\">")
                .append("<span class=\"gear-stat-name\">").append(esc(stat.label())).append("</span>")
                .append("<span class=\"gear-stat-value\">").append(esc(stat.valueText()))
                .append(stat.percent() >= 0 ? " <span class=\"muted\">(" + format(stat.percent()) + "%)</span>" : "")
                .append(stat.rangeText() != null && !stat.rangeText().isBlank() ? " <span class=\"muted\">[" + esc(stat.rangeText()) + "]</span>" : "")
                .append(stat.overcap() ? " <span class=\"muted\">[overcap]</span>" : "")
                .append("</span></div>");
        }
        if (stats.isEmpty()) stats.append("<div class=\"empty-note\">No parsed stat lines available.</div>");

        return """
            <section class="gear-card %s">
              <div class="gear-top">
                <div>
                  <div class="gear-kicker" style="color:%s;">%s</div>
                  <h3 class="gear-name">%s</h3>
                </div>
                <div class="icon-box">%s</div>
              </div>
              <div class="gear-meta">%s</div>
              <div class="gear-stats">%s</div>
              <div class="gear-footer">
                <span class="%s">Roll %s</span>
                <span>Matched %d stats</span>
              </div>
            </section>
            """.formatted(
            rarityClass,
            rarityColor(rarity),
            esc(buildItemKicker(item)),
            esc(item.itemName()),
            renderItemIcon(item),
            meta,
            stats,
            qualityClass(item.averageRollPercent()),
            esc(qualityLabel(item.averageRollPercent())),
            item.matchedStats()
        );
    }

    private static String renderRecommendationCard(String recommendation) {
        String[] parts = splitRecommendation(recommendation);
        return """
            <div class="recommendation-item">
              <div class="recommendation-mark">*</div>
              <div>
                <div class="recommendation-title">%s</div>
                <div class="recommendation-detail">%s</div>
              </div>
            </div>
            """.formatted(esc(parts[0]), esc(parts[1]));
    }

    private static void appendMetricTiles(StringBuilder out, List<DrBuildOptimizerService.StatTotal> stats) {
        for (DrBuildOptimizerService.StatTotal total : stats) {
            out.append("<div class=\"metric-tile\">")
                .append("<div class=\"micro-label\">").append(esc(total.label())).append("</div>")
                .append("<div class=\"tile-value\">").append(formatCompact(total.value())).append(valueSuffix(total.label())).append("</div>")
                .append("</div>");
        }
    }

    private static void appendSidebarStatTiles(StringBuilder out, List<DrBuildOptimizerService.StatTotal> stats) {
        for (DrBuildOptimizerService.StatTotal total : stats) {
            out.append("<div class=\"sidebar-stat-item\">")
                .append("<span>").append(esc(total.label())).append("</span>")
                .append("<strong>").append(formatCompact(total.value())).append(valueSuffix(total.label())).append("</strong>")
                .append("</div>");
        }
    }

    private static String tileGridOrEmpty(StringBuilder builder, String fallback) {
        return builder.isEmpty() ? "<div class=\"empty-note\">" + esc(fallback) + "</div>" : builder.toString();
    }

    private static String sidebarTileGridOrEmpty(StringBuilder builder, String fallback) {
        return builder.isEmpty()
            ? "<div class=\"sidebar-stat-item\"><span>" + esc(fallback) + "</span><strong>-</strong></div>"
            : builder.toString();
    }

    private static List<DrBuildOptimizerService.StatTotal> selectAttributeStats(List<DrBuildOptimizerService.StatTotal> totals) {
        return selectFixedStats(totals, "Attributes", ATTRIBUTE_PRIORITY);
    }

    private static List<DrBuildOptimizerService.StatTotal> selectFixedStats(List<DrBuildOptimizerService.StatTotal> totals,
                                                                            String category,
                                                                            List<String> labels) {
        List<DrBuildOptimizerService.StatTotal> selected = new ArrayList<>(labels.size());
        for (String label : labels) {
            DrBuildOptimizerService.StatTotal found = totals.stream()
                .filter(total -> total.label().equalsIgnoreCase(label))
                .findFirst()
                .orElse(new DrBuildOptimizerService.StatTotal(label, 0, category, 0));
            selected.add(found);
        }
        return selected;
    }

    private static List<DrBuildOptimizerService.StatTotal> selectCoreStats(List<DrBuildOptimizerService.StatTotal> totals) {
        List<DrBuildOptimizerService.StatTotal> selected = new ArrayList<>();
        for (String label : CORE_STAT_PRIORITY) {
            totals.stream().filter(total -> total.label().equalsIgnoreCase(label)).findFirst().ifPresent(selected::add);
            if (selected.size() >= 5) break;
        }
        if (selected.isEmpty()) selected.addAll(totals.stream().limit(5).toList());
        return selected;
    }

    private static List<DrBuildOptimizerService.StatTotal> selectResistances(List<DrBuildOptimizerService.StatTotal> totals) {
        List<DrBuildOptimizerService.StatTotal> selected = new ArrayList<>();
        for (String label : RESISTANCE_PRIORITY) {
            totals.stream().filter(total -> total.label().equalsIgnoreCase(label)).findFirst().ifPresent(selected::add);
        }
        return selected;
    }

    private static List<DrBuildOptimizerService.StatTotal> selectCategoryStats(List<DrBuildOptimizerService.StatTotal> totals, String category, int limit) {
        return totals.stream()
            .filter(total -> total.category().equalsIgnoreCase(category))
            .sorted(Comparator.comparingDouble(DrBuildOptimizerService.StatTotal::value).reversed())
            .limit(limit)
            .toList();
    }

    private static DrBuildOptimizerService.ItemCheck findWeakestItem(List<DrBuildOptimizerService.ItemCheck> items) {
        return items.stream()
            .filter(item -> !"(empty)".equals(item.itemName()))
            .filter(item -> item.averageRollPercent() >= 0)
            .min(Comparator.comparingDouble(DrBuildOptimizerService.ItemCheck::averageRollPercent))
            .orElse(null);
    }

    private static DrBuildOptimizerService.ItemCheck findStrongestItem(List<DrBuildOptimizerService.ItemCheck> items) {
        return items.stream()
            .filter(item -> !"(empty)".equals(item.itemName()))
            .filter(item -> item.averageRollPercent() >= 0)
            .max(Comparator.comparingDouble(DrBuildOptimizerService.ItemCheck::averageRollPercent))
            .orElse(null);
    }

    private static double averageBuildRoll(List<DrBuildOptimizerService.ItemCheck> items) {
        double total = 0;
        int count = 0;
        for (DrBuildOptimizerService.ItemCheck item : items) {
            if (item.averageRollPercent() < 0 || "(empty)".equals(item.itemName())) continue;
            total += item.averageRollPercent();
            count++;
        }
        return count == 0 ? 0 : total / count;
    }

    private static String buildTopFocus(List<DrBuildOptimizerService.StatTotal> totals) {
        List<String> top = totals.stream()
            .filter(total -> "Attributes".equalsIgnoreCase(total.category()) || "Offense".equalsIgnoreCase(total.category()))
            .sorted(Comparator.comparingDouble(DrBuildOptimizerService.StatTotal::value).reversed())
            .map(DrBuildOptimizerService.StatTotal::label)
            .distinct()
            .limit(3)
            .toList();
        return top.isEmpty() ? "Balanced throughput" : String.join(", ", top);
    }

    private static String buildItemKicker(DrBuildOptimizerService.ItemCheck item) {
        String rarity = blankOr(item.rarity(), "Unknown");
        String archetype = switch (item.slotLabel().toLowerCase(Locale.ROOT)) {
            case "weapon" -> "Armament";
            case "offhand" -> "Ward";
            case "helmet", "chestplate", "leggings", "boots" -> "Guard";
            default -> "Relic";
        };
        return rarity + " " + archetype;
    }

    private static String rarityColor(String rarity) {
        return switch (rarity.toLowerCase(Locale.ROOT)) {
            case "common" -> "var(--common)";
            case "uncommon" -> "var(--uncommon)";
            case "rare" -> "var(--rare)";
            case "epic" -> "var(--epic)";
            case "legendary" -> "var(--legendary)";
            case "mythic" -> "var(--mythic)";
            default -> "var(--primary)";
        };
    }

    private static String itemIcon(DrBuildOptimizerService.ItemCheck item) {
        return switch (item.slotLabel().toLowerCase(Locale.ROOT)) {
            case "weapon" -> "W";
            case "offhand" -> "O";
            case "helmet" -> "H";
            case "chestplate" -> "C";
            case "leggings" -> "L";
            case "boots" -> "B";
            default -> item.itemType().isBlank() ? "?" : item.itemType().substring(0, 1).toUpperCase(Locale.ROOT);
        };
    }

    private static String renderItemIcon(DrBuildOptimizerService.ItemCheck item) {
        String textureDataUrl = loadItemTextureDataUrl(item.itemId());
        if (textureDataUrl != null) {
            return "<img class=\"icon-art\" src=\"" + textureDataUrl + "\" alt=\"" + esc(item.itemName()) + "\" />";
        }
        if ("shield".equalsIgnoreCase(item.itemType()) || "offhand".equalsIgnoreCase(item.slotLabel())) {
            return """
                <svg class="icon-svg" viewBox="0 0 64 64" aria-hidden="true">
                  <rect x="22" y="6" width="20" height="4" fill="#5a3d22"/>
                  <rect x="18" y="10" width="28" height="4" fill="#6c4928"/>
                  <rect x="14" y="14" width="36" height="4" fill="#8a5c33"/>
                  <rect x="12" y="18" width="40" height="4" fill="#9c6a3d"/>
                  <rect x="10" y="22" width="44" height="4" fill="#b07a47"/>
                  <rect x="10" y="26" width="44" height="4" fill="#c48a50"/>
                  <rect x="10" y="30" width="44" height="4" fill="#cf9557"/>
                  <rect x="10" y="34" width="44" height="4" fill="#b07a47"/>
                  <rect x="12" y="38" width="40" height="4" fill="#9c6a3d"/>
                  <rect x="14" y="42" width="36" height="4" fill="#8a5c33"/>
                  <rect x="18" y="46" width="28" height="4" fill="#6c4928"/>
                  <rect x="22" y="50" width="20" height="4" fill="#5a3d22"/>

                  <rect x="26" y="16" width="12" height="32" fill="#d9dde3"/>
                  <rect x="28" y="18" width="8" height="28" fill="#f4f6f8"/>
                  <rect x="20" y="26" width="24" height="8" fill="#d9dde3"/>
                  <rect x="22" y="28" width="20" height="4" fill="#f4f6f8"/>

                  <rect x="18" y="10" width="28" height="2" fill="#2a1a0e" opacity=".65"/>
                  <rect x="14" y="54" width="36" height="2" fill="#2a1a0e" opacity=".65"/>
                </svg>
                """;
        }
        return esc(itemIcon(item));
    }

    private static String loadItemTextureDataUrl(String itemId) {
        if (itemId == null || itemId.isBlank()) return null;
        String[] parts = itemId.split(":", 2);
        String namespace = parts.length == 2 ? parts[0] : "minecraft";
        String path = parts.length == 2 ? parts[1] : parts[0];
        Identifier textureId = Identifier.of(namespace, "textures/item/" + path + ".png");
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc == null) return null;
            try (InputStream input = mc.getResourceManager().open(textureId)) {
                byte[] bytes = input.readAllBytes();
                return "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes);
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean shouldDisplayItemType(String slotLabel, String itemType) {
        if (itemType == null || itemType.isBlank()) return false;
        if (slotLabel == null || slotLabel.isBlank()) return true;

        String normalizedSlot = normalizeChipLabel(slotLabel);
        String normalizedType = normalizeChipLabel(itemType);
        if (normalizedSlot.equals(normalizedType)) return false;

        return switch (normalizedSlot) {
            case "helmet", "chestplate", "leggings", "boots" -> false;
            default -> true;
        };
    }

    private static boolean shouldDisplayMaterialLabel(String slotLabel, String itemType, String materialLabel) {
        if (materialLabel == null || materialLabel.isBlank()) return false;
        String normalizedMaterial = normalizeChipLabel(materialLabel);
        return !normalizedMaterial.equals(normalizeChipLabel(slotLabel))
            && !normalizedMaterial.equals(normalizeChipLabel(itemType));
    }

    private static String normalizeChipLabel(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
    }

    private static String chip(String value) {
        return "<span class=\"meta-chip\">" + esc(value) + "</span>";
    }

    private static String qualityClass(double averageRoll) {
        if (averageRoll < 0) return "quality-mid";
        if (averageRoll >= 80) return "quality-good";
        if (averageRoll >= 50) return "quality-mid";
        return "quality-bad";
    }

    private static String qualityLabel(double averageRoll) {
        return averageRoll < 0 ? "N/A" : format(averageRoll) + "%";
    }

    private static String[] splitRecommendation(String recommendation) {
        String text = blankOr(recommendation, "Optimizer insight");
        int idx = text.indexOf(':');
        if (idx > 0 && idx < text.length() - 1) {
            return new String[]{text.substring(0, idx).trim(), text.substring(idx + 1).trim()};
        }
        return new String[]{"Optimizer insight", text};
    }

    private static String playerInitial(String playerName) {
        String value = blankOr(playerName, "?").trim();
        return value.isEmpty() ? "?" : value.substring(0, 1).toUpperCase(Locale.ROOT);
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String signedFormat(double value) {
        return (value > 0 ? "+" : "") + format(value);
    }

    private static String valueSuffix(String label) {
        String upper = label.toUpperCase(Locale.ROOT);
        if (upper.contains("REGEN")) return "/s";
        if (upper.contains("RESIST") || upper.contains("FIND") || upper.contains("ACCURACY") || upper.contains("EXECUTE")
            || upper.contains("BLOCK") || upper.contains("THORNS") || upper.contains("REFLECT") || upper.contains("DODGE")
            || upper.contains("MOVE SPEED") || upper.contains("CRITICAL HIT") || upper.contains("RECOVERY") || upper.contains("VS. ")
            || upper.contains("BLEEDING") || upper.contains("SHATTER") || upper.contains("CRUSHING") || upper.contains("CLEAVE")
            || upper.contains("LIFE STEAL") || upper.contains("GLOWING") || upper.contains("BLINDING") || upper.contains("SLOWNESS")) {
            return "%";
        }
        return "";
    }

    private static String blankOr(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String format(double value) {
        if (!Double.isFinite(value)) return "inf";
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static String formatCompact(double value) {
        if (!Double.isFinite(value)) return "inf";
        if (Math.abs(value) >= 1_000_000d) return String.format(Locale.ROOT, "%.1fM", value / 1_000_000d);
        if (Math.abs(value) >= 1_000d) return String.format(Locale.ROOT, "%.1fK", value / 1_000d);
        if (Math.abs(value - Math.rint(value)) < 0.0001d) return Integer.toString((int) Math.rint(value));
        return format(value);
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
            return new ExportResult(true, "HTML downloaded to: " + path.toAbsolutePath(), path);
        }

        public static ExportResult error(String message) {
            return new ExportResult(false, message, null);
        }
    }
}


