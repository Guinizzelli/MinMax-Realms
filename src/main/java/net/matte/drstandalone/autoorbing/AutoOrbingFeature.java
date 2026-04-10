package net.matte.drstandalone.autoorbing;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.matte.drstandalone.DrItemRollsFeature;
import net.matte.drstandalone.DrStatsDatabase;
import net.matte.drstandalone.DrStandaloneConfig;
import net.matte.drstandalone.DrStandaloneMod;
import net.matte.drstandalone.autoaugment.DrEnchantSnapshotParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AutoOrbingFeature {
    private static final MinecraftClient mc = MinecraftClient.getInstance();
    private static final Pattern FIRST_NUMBER = Pattern.compile("([+-]?\\d+(?:\\.\\d+)?)");

    private static final int TARGET_PREVIEW_SLOT = 22;
    private static final int[] BLUE_ORB_SLOTS = {11, 12, 13, 14, 15, 16, 17};
    private static final int[] RED_ORB_SLOTS = {18, 19, 20, 21};
    private static final int[] PURPLE_ORB_SLOTS = {23, 24, 25, 26};
    private static final int[][] ORB_GROUPS = {BLUE_ORB_SLOTS, RED_ORB_SLOTS, PURPLE_ORB_SLOTS};
    private static final int[] ALL_MAPPED_SLOTS = {11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 21, 22, 23, 24, 25, 26};
    private static final int[] GUIDE_COLORS = {0xB34FA7FF, 0xB3FF5C5C, 0xB3AE71FF};
    private static final String[] ORB_GROUP_NAMES = {"Orb of Alteration", "Orb of Augmentation", "Orb of Nullification"};
    private static final int MAX_RULES = 16;
    private static final String[] WEAPON_RULE_LABELS = {"PIERCING", "SHATTER", "CRUSHING", "CLEAVE", "EXECUTE", "LIFE STEAL", "CRITICAL HIT", "PURE DMG", "STR", "DEX", "VIT", "INT"};
    private static final String[] ARMOR_RULE_LABELS = {"DODGE", "REFLECTION", "THORNS", "ITEM FIND", "GEM FIND", "HP REGEN", "ENERGY REGEN", "PURE RESIST", "STR", "DEX", "VIT", "INT"};
    private static final String[] SHIELD_RULE_LABELS = {"BLOCK", "REFLECTION", "THORNS", "ITEM FIND", "GEM FIND", "HP REGEN", "PURE RESIST", "DODGE", "STR", "DEX", "VIT", "INT"};

    private static boolean enabled;
    private static boolean placementGuideEnabled;
    private static boolean running;
    private static boolean singleRun;
    private static State state = State.Idle;
    private static int actionDelayLeft;
    private static int resultDelayLeft;
    private static int pendingOrbSlotId = -1;
    private static int currentGroupIndex;
    private static int currentSlotInGroupIndex;
    private static @Nullable DrEnchantSnapshotParser.Snapshot lastSnapshot;
    private static String configuredItemName = "no item";
    private static String lastSummary = "no snapshot";
    private static String lastRuleStatus = "no rules";
    private static StopMode stopMode = StopMode.Any;
    private static int minStatsRequired = 1;
    private static boolean minStatsRuleEnabled = true;
    private static final Map<String, Integer> minRollPercentByLabel = new LinkedHashMap<>();
    private static final Map<String, Integer> currentRollPercentByLabel = new LinkedHashMap<>();
    private static final Map<String, Double> currentVisibleValueByLabel = new LinkedHashMap<>();
    private static final LinkedList<LogEntry> recentLogEntries = new LinkedList<>();

    private AutoOrbingFeature() {
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> onTick());
        hydrateConfig();
    }

    public static void reloadFromConfig() {
        hydrateConfig();
        if (!enabled) {
            running = false;
            singleRun = false;
            state = State.Idle;
            pendingOrbSlotId = -1;
            actionDelayLeft = 0;
            resultDelayLeft = 0;
        }
    }

    private static void hydrateConfig() {
        DrStandaloneConfig config = DrStandaloneMod.config();
        enabled = config.autoOrbingEnabled;
        placementGuideEnabled = config.autoOrbingPlacementGuideEnabled;
        minStatsRequired = Math.max(1, Math.min(8, config.autoOrbingMinStatsRequired));
        minStatsRuleEnabled = config.autoOrbingMinStatsRuleEnabled;
        stopMode = parseStopMode(config.autoOrbingStopMode);
        minRollPercentByLabel.clear();
        if (config.autoOrbingMinRollPercentByLabel != null) {
            for (Map.Entry<String, Integer> entry : config.autoOrbingMinRollPercentByLabel.entrySet()) {
                Integer value = entry.getValue();
                if (entry.getKey() != null && value != null) {
                    minRollPercentByLabel.put(entry.getKey(), Math.max(0, Math.min(100, value)));
                }
            }
        }
    }

    private static void persistConfig() {
        DrStandaloneConfig config = DrStandaloneMod.config();
        config.autoOrbingEnabled = enabled;
        config.autoOrbingPlacementGuideEnabled = placementGuideEnabled;
        config.autoOrbingStopMode = stopMode.name();
        config.autoOrbingMinStatsRequired = minStatsRequired;
        config.autoOrbingMinStatsRuleEnabled = minStatsRuleEnabled;
        config.autoOrbingMinRollPercentByLabel.clear();
        for (Map.Entry<String, Integer> entry : minRollPercentByLabel.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                config.autoOrbingMinRollPercentByLabel.put(entry.getKey(), Math.max(0, Math.min(100, entry.getValue())));
            }
        }
        config.save();
    }

    private static void onTick() {
        if (mc.player == null || mc.interactionManager == null) return;

        if (!running) return;
        if (!(mc.currentScreen instanceof HandledScreen<?> handled) || !isCompatibleScreen(mc.currentScreen, handled.getTitle().getString())) {
            stopLoop(State.StoppedInvalidLayout, "Stopped: invalid orbing screen.");
            return;
        }

        if (actionDelayLeft > 0) {
            actionDelayLeft--;
            return;
        }

        if (resultDelayLeft > 0) {
            resultDelayLeft--;
            if (resultDelayLeft == 0) captureSnapshotAfterOrb(handled);
            return;
        }

        switch (state) {
            case Idle, Ready -> beginNextOrb(handled);
            case ApplyingOrb -> applyHeldOrbToTarget(handled);
            case ReturningCursor -> returnCursorToOrigin(handled);
            case WaitingForResult, StoppedMatched, StoppedNoMoreOrbs, StoppedInvalidLayout -> {
            }
        }
    }

    private static void beginNextOrb(HandledScreen<?> handled) {
        if (!enabled) {
            stopLoop(State.Idle, "Enable Auto-Orbing first.");
            return;
        }
        if (isPreviewScreen(handled.getTitle().getString())) {
            stopLoop(State.Idle, "Preview mode: run disabled.");
            return;
        }

        Integer targetSlotId = resolveTargetSlotId(handled);
        if (targetSlotId == null || !isValidTargetItem(handled.getScreenHandler().getSlot(targetSlotId).getStack())) {
            stopLoop(State.StoppedInvalidLayout, "Stopped: target slot invalid.");
            return;
        }

        Integer nextOrbSlotId = findNextOrbSlotId(handled);
        if (nextOrbSlotId == null) {
            stopLoop(State.StoppedNoMoreOrbs, "Stopped: no more mapped orbs.");
            return;
        }

        pendingOrbSlotId = nextOrbSlotId;
        addLogEntry("Using " + getCurrentOrbGroupName() + ".", colorForOrbGroup(currentGroupIndex));
        handled.getScreenHandler().syncState();
        mc.interactionManager.clickSlot(handled.getScreenHandler().syncId, pendingOrbSlotId, 0, SlotActionType.PICKUP, mc.player);
        state = State.ApplyingOrb;
        actionDelayLeft = nextActionDelay();
    }

    private static void applyHeldOrbToTarget(HandledScreen<?> handled) {
        Integer targetSlotId = resolveTargetSlotId(handled);
        if (targetSlotId == null) {
            stopLoop(State.StoppedInvalidLayout, "Stopped: target slot missing.");
            return;
        }

        mc.interactionManager.clickSlot(handled.getScreenHandler().syncId, targetSlotId, 1, SlotActionType.PICKUP, mc.player);
        state = State.ReturningCursor;
        actionDelayLeft = nextActionDelay();
    }

    private static void returnCursorToOrigin(HandledScreen<?> handled) {
        if (pendingOrbSlotId < 0) {
            stopLoop(State.StoppedInvalidLayout, "Stopped: lost orb slot state.");
            return;
        }

        if (!handled.getScreenHandler().getCursorStack().isEmpty()) {
            mc.interactionManager.clickSlot(handled.getScreenHandler().syncId, pendingOrbSlotId, 0, SlotActionType.PICKUP, mc.player);
        }

        Slot originSlot = handled.getScreenHandler().getSlot(pendingOrbSlotId);
        boolean originStillHasOrbs = originSlot.hasStack() && isOrbStack(originSlot.getStack());
        pendingOrbSlotId = -1;
        if (!originStillHasOrbs) {
            advanceOrbPointer();
        }
        state = State.WaitingForResult;
        resultDelayLeft = Math.max(1, DrStandaloneMod.config().autoOrbingResultSnapshotDelayTicks);
    }

    private static void captureSnapshotAfterOrb(HandledScreen<?> handled) {
        Integer targetSlotId = resolveTargetSlotId(handled);
        if (targetSlotId == null) {
            stopLoop(State.StoppedInvalidLayout, "Stopped: target item lost.");
            return;
        }

        ItemStack stack = handled.getScreenHandler().getSlot(targetSlotId).getStack();
        if (!isValidTargetItem(stack)) {
            stopLoop(State.StoppedInvalidLayout, "Stopped: target item invalid.");
            return;
        }

        DrEnchantSnapshotParser.Snapshot snapshot = inspectOrbSnapshot(stack);
        if (snapshot == null) {
            state = State.Ready;
            lastSummary = "waiting parse";
            addLogEntry("Waiting for parsed item...", 0xFFB8BDC8);
            return;
        }

        configuredItemName = DrEnchantSnapshotParser.baseItemName(snapshot.rawItemName());
        currentRollPercentByLabel.clear();
        currentVisibleValueByLabel.clear();
        currentRollPercentByLabel.putAll(readRollPercents(stack));
        lastSummary = DrEnchantSnapshotParser.shortSummary(snapshot);
        refreshOverlayLog(snapshot);
        lastSnapshot = snapshot;

        if (shouldStopOnConfiguredTargets(snapshot)) {
            stopLoop(State.StoppedMatched, "Stopped: target matched.");
            return;
        }

        if (singleRun) {
            stopLoop(State.Idle, "Single orb complete.");
            return;
        }

        state = State.Ready;
    }

    private static Integer findNextOrbSlotId(HandledScreen<?> handled) {
        ResolvedMapping mapping = resolveMapping(handled);
        if (mapping == null) return null;

        for (int group = currentGroupIndex; group < mapping.groups().size(); group++) {
            List<Integer> slots = mapping.groups().get(group);
            int start = group == currentGroupIndex ? currentSlotInGroupIndex : 0;
            for (int i = start; i < slots.size(); i++) {
                int slotId = slots.get(i);
                Slot slot = handled.getScreenHandler().getSlot(slotId);
                if (slot.hasStack() && isOrbStack(slot.getStack())) {
                    currentGroupIndex = group;
                    currentSlotInGroupIndex = i;
                    return slotId;
                }
            }
        }

        return null;
    }

    private static void advanceOrbPointer() {
        currentSlotInGroupIndex++;
    }

    private static int nextActionDelay() {
        return Math.max(0, DrStandaloneMod.config().autoOrbingActionDelayTicks);
    }

    private static void refreshOverlayLog(@Nullable DrEnchantSnapshotParser.Snapshot snapshot) {
        recentLogEntries.clear();
        addOrbLegendEntries();
        if (snapshot == null || snapshot.statsByLabel().isEmpty()) {
            recentLogEntries.add(new LogEntry("No orb stats parsed", 0xFF9AA0AA));
            return;
        }

        List<DrEnchantSnapshotParser.ParsedStat> orderedStats = new ArrayList<>(snapshot.statsByLabel().values());
        orderedStats.sort(Comparator
            .comparingInt((DrEnchantSnapshotParser.ParsedStat stat) -> statPriority(stat.canonicalLabel()))
            .thenComparing(DrEnchantSnapshotParser.ParsedStat::canonicalLabel));

        int shown = 0;
        for (DrEnchantSnapshotParser.ParsedStat stat : orderedStats) {
            if (shown >= 5) break;
            double displayValue = currentVisibleValueByLabel.getOrDefault(normalizeStatLabel(stat.canonicalLabel()), stat.total());
            Integer rollPercent = currentRollPercentByLabel.get(normalizeStatLabel(stat.canonicalLabel()));
            String rollText = rollPercent == null ? "Present only" : rollPercent + "%";
            recentLogEntries.add(new LogEntry(
                String.format(Locale.ROOT, "%s %+.1f (%s)", stat.canonicalLabel(), displayValue, rollText),
                colorForStat(stat.canonicalLabel())
            ));
            shown++;
        }
    }

    private static void addOrbLegendEntries() {
        recentLogEntries.add(new LogEntry("Blue: Orb of Alteration", colorForOrbGroup(0)));
        recentLogEntries.add(new LogEntry("Red: Orb of Augmentation", colorForOrbGroup(1)));
        recentLogEntries.add(new LogEntry("Purple: Orb of Nullification", colorForOrbGroup(2)));
    }

    private static int statPriority(String label) {
        String normalized = label == null ? "" : label.toUpperCase(Locale.ROOT);
        boolean isMobDamage = normalized.contains("DMG") && (normalized.contains("MOB") || normalized.contains("MONSTER"));
        boolean isPlayerDamage = normalized.contains("DMG") && normalized.contains("PLAYER");
        if (isMobDamage || isPlayerDamage) return 0;
        return 1;
    }

    private static String getCurrentOrbGroupName() {
        int index = Math.max(0, Math.min(currentGroupIndex, ORB_GROUP_NAMES.length - 1));
        return ORB_GROUP_NAMES[index];
    }

    private static int colorForOrbGroup(int groupIndex) {
        return switch (groupIndex) {
            case 0 -> 0xFF66D5FF;
            case 1 -> 0xFFFF7A7A;
            case 2 -> 0xFFC38BFF;
            default -> 0xFFB8BDC8;
        };
    }

    private static int colorForStat(String label) {
        String normalized = normalizeStatLabel(label);
        if (isMobOrPlayerDamageStat(normalized)) return 0xFF66D5FF;
        return currentRollPercentByLabel.containsKey(normalized) ? 0xFFF0D08A : 0xFFD7D7D7;
    }

    private static boolean shouldStopOnConfiguredTargets(DrEnchantSnapshotParser.Snapshot snapshot) {
        boolean anyConfigured = false;
        boolean anyMatched = false;
        boolean allMatched = true;
        List<String> failures = new ArrayList<>();

        for (String label : getActiveRuleLabels()) {
            Integer targetPercent = minRollPercentByLabel.get(label);
            if (targetPercent == null) continue;
            anyConfigured = true;

            DrEnchantSnapshotParser.ParsedStat stat = snapshot.statsByLabel().get(label);
            boolean present = stat != null;
            Integer actualPercent = currentRollPercentByLabel.get(label);
            boolean matched = present && (targetPercent <= 0 || (actualPercent != null && actualPercent >= targetPercent));
            anyMatched |= matched;
            allMatched &= matched;
            if (!matched) {
                if (!present) failures.add(label + " absent");
                else if (targetPercent > 0 && actualPercent == null) failures.add(label + " no %");
                else failures.add(label + " " + (actualPercent == null ? 0 : actualPercent) + "% < " + targetPercent + "%");
            }
        }

        boolean rulesMatched = anyConfigured && (stopMode == StopMode.Any ? anyMatched : allMatched);
        boolean minStatsMatched = snapshot.statsByLabel().size() >= minStatsRequired;
        if (minStatsRuleEnabled && !minStatsMatched) {
            lastRuleStatus = "min stats " + snapshot.statsByLabel().size() + "/" + minStatsRequired;
            return false;
        }
        if (!anyConfigured) {
            lastRuleStatus = "no stat rules";
            return false;
        }
        if (!rulesMatched) {
            lastRuleStatus = failures.isEmpty() ? "rules not met" : shorten(String.join(" | ", failures), 40);
            return false;
        }
        lastRuleStatus = minStatsRuleEnabled ? "rules + min stats matched" : "rules matched";
        return true;
    }

    private static void stopLoop(State nextState, String message) {
        running = false;
        singleRun = false;
        state = nextState;
        actionDelayLeft = 0;
        resultDelayLeft = 0;
        pendingOrbSlotId = -1;
        addLogEntry(message, nextState == State.StoppedMatched ? 0xFF8CFB8C : 0xFFFFB36B);
    }

    private static boolean isOrbStack(ItemStack stack) {
        if (stack.isEmpty()) return false;
        return !stack.isOf(Items.DIAMOND_SWORD)
            && !stack.isOf(Items.DIAMOND_AXE)
            && !stack.isOf(Items.IRON_SWORD)
            && !stack.isOf(Items.IRON_AXE)
            && !stack.getName().getString().toLowerCase(Locale.ROOT).contains("sword");
    }

    private static boolean isValidTargetItem(ItemStack stack) {
        if (stack.isEmpty()) return false;
        String name = stack.getName().getString().toLowerCase(Locale.ROOT);
        return !name.contains("orb");
    }

    private static boolean isPreviewScreen(String title) {
        return title != null && title.toLowerCase(Locale.ROOT).contains("auto-orbing preview");
    }

    private static boolean isCompatibleScreen(@Nullable Screen screen, String title) {
        return screen instanceof InventoryScreen || isPreviewScreen(title);
    }

    public static boolean shouldRenderOverlay(@Nullable Screen screen, String title) {
        return isPreviewScreen(title) || (enabled && isCompatibleScreen(screen, title));
    }

    public static boolean shouldRenderPlacementGuide(@Nullable Screen screen, String title) {
        return placementGuideEnabled && isCompatibleScreen(screen, title);
    }

    public static @Nullable Integer getGuideColorForSlot(HandledScreen<?> handled, int slotId) {
        ResolvedMapping mapping = resolveMapping(handled);
        if (mapping == null) return null;
        for (int i = 0; i < mapping.groups().size(); i++) {
            if (mapping.groups().get(i).contains(slotId)) return GUIDE_COLORS[i];
        }
        return null;
    }

    public static boolean isTargetGuideSlot(HandledScreen<?> handled, int slotId) {
        ResolvedMapping mapping = resolveMapping(handled);
        return mapping != null && mapping.targetSlotId() == slotId;
    }

    private static @Nullable Integer resolveTargetSlotId(HandledScreen<?> handled) {
        ResolvedMapping mapping = resolveMapping(handled);
        return mapping == null ? null : mapping.targetSlotId();
    }

    private static @Nullable ResolvedMapping resolveMapping(HandledScreen<?> handled) {
        if (mc.player == null) return null;

        if (isPreviewScreen(handled.getTitle().getString())) {
            return new ResolvedMapping(
                TARGET_PREVIEW_SLOT,
                List.of(toList(BLUE_ORB_SLOTS), toList(RED_ORB_SLOTS), toList(PURPLE_ORB_SLOTS))
            );
        }

        if (!(handled instanceof InventoryScreen)) return null;

        Integer targetSlotId = resolvePlayerInventorySlotId(handled, 22);
        if (targetSlotId == null) return null;

        List<Integer> blue = resolvePlayerInventorySlots(handled, BLUE_ORB_SLOTS);
        List<Integer> red = resolvePlayerInventorySlots(handled, RED_ORB_SLOTS);
        List<Integer> purple = resolvePlayerInventorySlots(handled, PURPLE_ORB_SLOTS);
        if (blue.isEmpty() || red.isEmpty() || purple.isEmpty()) return null;
        return new ResolvedMapping(targetSlotId, List.of(blue, red, purple));
    }

    private static List<Integer> resolvePlayerInventorySlots(HandledScreen<?> handled, int[] logicalIndices) {
        List<Integer> result = new ArrayList<>();
        for (int logicalIndex : logicalIndices) {
            Integer slotId = resolvePlayerInventorySlotId(handled, logicalIndex);
            if (slotId != null) result.add(slotId);
        }
        return result;
    }

    private static @Nullable Integer resolvePlayerInventorySlotId(HandledScreen<?> handled, int inventoryIndex) {
        if (mc.player == null) return null;
        ScreenHandler handler = handled.getScreenHandler();
        for (int i = 0; i < handler.slots.size(); i++) {
            Slot slot = handler.getSlot(i);
            if (slot.inventory == mc.player.getInventory() && slot.getIndex() == inventoryIndex) {
                return i;
            }
        }
        return null;
    }

    private static List<Integer> toList(int[] values) {
        List<Integer> out = new ArrayList<>(values.length);
        for (int value : values) out.add(value);
        return out;
    }

    private static void ensurePreviewConfig() {
        if (!(mc.currentScreen instanceof HandledScreen<?> handled) || !isPreviewScreen(handled.getTitle().getString())) return;
        if (!"no item".equalsIgnoreCase(configuredItemName)) return;

        configuredItemName = "Preview Dragon Sword";
        minRollPercentByLabel.putIfAbsent("PIERCING", 0);
        minRollPercentByLabel.putIfAbsent("SHATTER", 75);
        currentRollPercentByLabel.putIfAbsent("PIERCING", 68);
        currentRollPercentByLabel.putIfAbsent("SHATTER", 81);
        lastRuleStatus = "preview rules";
    }

    public static String getModuleButtonText() {
        return enabled ? "Auto Orbing: ON" : "Auto Orbing: OFF";
    }

    public static String getGuideButtonText() {
        return placementGuideEnabled ? "Placement: ON" : "Placement: OFF";
    }

    public static String getStartButtonText() {
        return "Orb x1";
    }

    public static String getAutoButtonText() {
        return running && !singleRun ? "Stop Auto" : "Auto Orbing";
    }

    public static String getStatusText() {
        String stateText = switch (state) {
            case ApplyingOrb -> "applying";
            case WaitingForResult -> "waiting result";
            case Ready -> "ready";
            case StoppedMatched -> "matched";
            case StoppedNoMoreOrbs -> "no more orbs";
            case StoppedInvalidLayout -> "invalid layout";
            default -> "idle";
        };
        return stateText + " | " + shorten(lastRuleStatus + " | " + lastSummary, 34);
    }

    public static String getStopModeButtonText() {
        return "Stop: " + stopMode.name();
    }

    public static String getMinStatsButtonText() {
        return "Min Stats: " + minStatsRequired;
    }

    public static String getMinStatsRuleButtonText() {
        return minStatsRuleEnabled ? "Min Stats Rule: ON" : "Min Stats Rule: OFF";
    }

    public static String getRuleFamilyLabel() {
        return "Rules: " + getCurrentItemCategory().label;
    }

    public static void toggleEnabledFromScreen() {
        enabled = !enabled;
        if (!enabled) {
            running = false;
            state = State.Idle;
        }
        persistConfig();
    }

    public static void togglePlacementGuideFromScreen() {
        placementGuideEnabled = !placementGuideEnabled;
        persistConfig();
    }

    public static void startStopFromScreen() {
        if (running) {
            stopLoop(State.Idle, "Run stopped.");
            return;
        }

        if (!enabled) {
            addLogEntry("Enable Auto-Orbing first.", 0xFFFFB36B);
            return;
        }

        if (isPreviewCurrentScreen()) {
            addLogEntry("Preview mode: run disabled.", 0xFF9AA0AA);
            return;
        }

        armRun(false, "Auto-Orbing armed.");
    }

    public static void startOnceFromScreen() {
        if (running) {
            stopLoop(State.Idle, "Run stopped.");
            return;
        }

        if (!enabled) {
            addLogEntry("Enable Auto-Orbing first.", 0xFFFFB36B);
            return;
        }

        if (isPreviewCurrentScreen()) {
            addLogEntry("Preview mode: x1 disabled.", 0xFF9AA0AA);
            return;
        }

        armRun(true, "Single orb armed.");
    }

    private static void armRun(boolean oneShot, String logMessage) {
        running = true;
        singleRun = oneShot;
        state = State.Ready;
        currentGroupIndex = 0;
        currentSlotInGroupIndex = 0;
        actionDelayLeft = 0;
        resultDelayLeft = 0;
        addLogEntry(logMessage, 0xFF9AA0AA);
    }

    public static void stopNowFromScreen() {
        stopLoop(State.Idle, "Stopped manually.");
    }

    public static void scanFromScreen() {
        if (isPreviewCurrentScreen()) {
            ensurePreviewConfig();
            lastSummary = "preview orb stats";
            lastRuleStatus = "preview scanned";
            addLogEntry("Preview mapping scanned.", 0xFF9AA0AA);
            return;
        }

        if (!(mc.currentScreen instanceof HandledScreen<?> handled)) {
            addLogEntry("Scan failed: no inventory screen.", 0xFFFF7A7A);
            return;
        }

        Integer targetSlotId = resolveTargetSlotId(handled);
        if (targetSlotId == null) {
            addLogEntry("Scan failed: no yellow target slot.", 0xFFFF7A7A);
            return;
        }

        ItemStack stack = handled.getScreenHandler().getSlot(targetSlotId).getStack();
        DrEnchantSnapshotParser.Snapshot snapshot = inspectOrbSnapshot(stack);
        if (snapshot == null || snapshot.statsByLabel().isEmpty()) {
            addLogEntry("Scan failed: no orb stats parsed.", 0xFFFF7A7A);
            return;
        }

        configuredItemName = DrEnchantSnapshotParser.baseItemName(snapshot.rawItemName());
        lastSnapshot = snapshot;
        currentRollPercentByLabel.clear();
        currentVisibleValueByLabel.clear();
        currentRollPercentByLabel.putAll(readRollPercents(stack));
        lastSummary = DrEnchantSnapshotParser.shortSummary(snapshot);
        lastRuleStatus = "scan ready";
        refreshOverlayLog(snapshot);
        addLogEntry("Scanned " + configuredItemName + ".", 0xFF9AA0AA);
    }

    public static void cycleStopModeFromScreen() {
        stopMode = stopMode == StopMode.Any ? StopMode.All : StopMode.Any;
        persistConfig();
    }

    public static void cycleMinStatsFromScreen() {
        minStatsRequired = minStatsRequired >= 8 ? 1 : minStatsRequired + 1;
        persistConfig();
    }

    public static void toggleMinStatsRuleFromScreen() {
        minStatsRuleEnabled = !minStatsRuleEnabled;
        persistConfig();
    }

    public static int getConfigRuleButtonCount() {
        ensurePreviewConfig();
        return Math.min(MAX_RULES, getActiveRuleLabels().size());
    }

    public static String getConfigRuleButtonText(int index) {
        ensurePreviewConfig();
        List<String> labels = getActiveRuleLabels();
        if (index < 0 || index >= labels.size()) return "-";
        String label = labels.get(index);
        boolean required = minRollPercentByLabel.containsKey(label);
        return shorten(label + ": " + (required ? "Present" : "Ignore"), 26);
    }

    public static void cycleConfigRuleFromScreen(int index) {
        ensurePreviewConfig();
        List<String> labels = getActiveRuleLabels();
        if (index < 0 || index >= labels.size()) return;
        String label = labels.get(index);
        if (minRollPercentByLabel.containsKey(label)) minRollPercentByLabel.remove(label);
        else minRollPercentByLabel.put(label, 0);
        persistConfig();
    }

    public static boolean isRuleSliderVisible(int index) {
        List<String> labels = getActiveRuleLabels();
        if (index < 0 || index >= labels.size()) return false;
        return minRollPercentByLabel.containsKey(labels.get(index));
    }

    public static double getRuleSliderValue(int index) {
        List<String> labels = getActiveRuleLabels();
        if (index < 0 || index >= labels.size()) return 0d;
        return Math.max(0d, Math.min(1d, minRollPercentByLabel.getOrDefault(labels.get(index), 0) / 100d));
    }

    public static void setRuleSliderValue(int index, double value) {
        List<String> labels = getActiveRuleLabels();
        if (index < 0 || index >= labels.size()) return;
        String label = labels.get(index);
        if (!minRollPercentByLabel.containsKey(label)) return;
        minRollPercentByLabel.put(label, Math.max(0, Math.min(100, (int) Math.round(value * 100d))));
        persistConfig();
    }

    public static String getRuleSliderText(int index) {
        List<String> labels = getActiveRuleLabels();
        if (index < 0 || index >= labels.size()) return "Roll %";
        String label = labels.get(index);
        int value = minRollPercentByLabel.getOrDefault(label, 0);
        return value <= 0 ? "Roll %: Present" : "Roll %: " + value + "%";
    }

    public static String getConfigItemText() {
        return shorten("Item: " + configuredItemName, 28);
    }

    public static List<String> getRecentLogLines() {
        List<String> lines = new ArrayList<>();
        for (LogEntry entry : recentLogEntries) lines.add(entry.text());
        return Collections.unmodifiableList(lines);
    }

    public static int getRecentLogColor(int index) {
        if (index < 0 || index >= recentLogEntries.size()) return 0xFFB8BDC8;
        return recentLogEntries.get(index).color();
    }

    public static boolean isRunning() {
        return running;
    }

    public static boolean isSingleRun() {
        return running && singleRun;
    }

    private static boolean isPreviewCurrentScreen() {
        return mc.currentScreen instanceof HandledScreen<?> handled && isPreviewScreen(handled.getTitle().getString());
    }

    private static Map<String, Integer> readRollPercents(ItemStack stack) {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (mc.player == null || stack.isEmpty()) return result;

        List<Text> tooltip = stack.getTooltip(Item.TooltipContext.DEFAULT, mc.player, TooltipType.BASIC);
        DrItemRollsFeature.TooltipSnapshot tooltipSnapshot = DrItemRollsFeature.inspectTooltip(stack, tooltip, DrStandaloneMod.config());
        DrStatsDatabase.TooltipAnalysis analysis = tooltipSnapshot.analysis();
        if (analysis == null || analysis.matches == null) return result;

        for (DrStatsDatabase.StatMatch match : analysis.matches) {
            String label = normalizeStatLabel(match.label);
            result.put(label, Math.max(0, Math.min(100, (int) Math.round(match.percent))));
            currentVisibleValueByLabel.put(label, extractFirstNumber(match.valueText));
        }
        return result;
    }

    private static @Nullable DrEnchantSnapshotParser.Snapshot inspectOrbSnapshot(ItemStack stack) {
        if (stack.isEmpty() || mc.player == null) return null;

        DrEnchantSnapshotParser.Snapshot direct = DrEnchantSnapshotParser.inspect(mc, stack);
        if (direct != null && !direct.statsByLabel().isEmpty()) {
            Map<String, Integer> rollPercents = readRollPercents(stack);
            Map<String, DrEnchantSnapshotParser.ParsedStat> merged = new LinkedHashMap<>();
            for (Map.Entry<String, DrEnchantSnapshotParser.ParsedStat> entry : direct.statsByLabel().entrySet()) {
                String label = normalizeStatLabel(entry.getKey());
                DrEnchantSnapshotParser.ParsedStat stat = entry.getValue();
                double visibleValue = currentVisibleValueByLabel.getOrDefault(label, stat.total());
                merged.put(entry.getKey(), new DrEnchantSnapshotParser.ParsedStat(
                    stat.rawLabel(),
                    stat.canonicalLabel(),
                    visibleValue,
                    0d,
                    null,
                    0d,
                    visibleValue
                ));
                if (!rollPercents.containsKey(label)) {
                    currentVisibleValueByLabel.putIfAbsent(label, visibleValue);
                }
            }
            return new DrEnchantSnapshotParser.Snapshot(
                direct.rawItemName(),
                direct.upgradeLevel(),
                direct.rarity(),
                direct.transmuted(),
                merged
            );
        }

        List<Text> tooltip = stack.getTooltip(Item.TooltipContext.DEFAULT, mc.player, TooltipType.BASIC);
        DrItemRollsFeature.TooltipSnapshot tooltipSnapshot = DrItemRollsFeature.inspectTooltip(stack, tooltip, DrStandaloneMod.config());
        DrStatsDatabase.TooltipAnalysis analysis = tooltipSnapshot.analysis();
        if (analysis != null && analysis.matches != null && !analysis.matches.isEmpty()) {
            Map<String, DrEnchantSnapshotParser.ParsedStat> rollStats = new LinkedHashMap<>();
            for (DrStatsDatabase.StatMatch match : analysis.matches) {
                String canonical = normalizeStatLabel(match.label);
                double numericValue = extractFirstNumber(match.valueText);
                rollStats.put(canonical, new DrEnchantSnapshotParser.ParsedStat(
                    match.label,
                    canonical,
                    numericValue,
                    0d,
                    null,
                    0d,
                    numericValue
                ));
            }
            if (!rollStats.isEmpty()) {
                return new DrEnchantSnapshotParser.Snapshot(
                    stack.getName().getString(),
                    0,
                    tooltipSnapshot.rarity(),
                    tooltipSnapshot.transmuted(),
                    rollStats
                );
            }
        }
        return direct;
    }

    private static double extractFirstNumber(String text) {
        Matcher matcher = FIRST_NUMBER.matcher(text == null ? "" : text);
        if (!matcher.find()) return 0d;
        try {
            return Double.parseDouble(matcher.group(1));
        } catch (NumberFormatException ignored) {
            return 0d;
        }
    }

    private static void addLogEntry(String message, int color) {
        recentLogEntries.addFirst(new LogEntry(message, color));
        while (recentLogEntries.size() > 8) recentLogEntries.removeLast();
        if (DrStandaloneMod.config().autoOrbingLogToChat && mc.player != null) {
            mc.player.sendMessage(Text.literal("[Auto-Orbing] ").styled(style -> style.withColor(0xF0D08A))
                .append(Text.literal(message).styled(style -> style.withColor(color & 0xFFFFFF))), false);
        }
    }

    private static String shorten(String text, int max) {
        if (text == null || text.length() <= max) return text;
        return text.substring(0, Math.max(0, max - 3)) + "...";
    }

    private static StopMode parseStopMode(String value) {
        try {
            return StopMode.valueOf(value == null ? "" : value);
        } catch (IllegalArgumentException ignored) {
            return StopMode.Any;
        }
    }

    private static boolean isMobOrPlayerDamageStat(String label) {
        String normalized = normalizeStatLabel(label);
        return "VS. MONSTERS".equals(normalized) || "VS. PLAYERS".equals(normalized)
            || normalized.contains("MOB") || normalized.contains("PLAYER");
    }

    private static String normalizeStatLabel(String label) {
        String normalized = label == null ? "" : label.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "STRENGTH" -> "STR";
            case "DEXTERITY" -> "DEX";
            case "VITALITY" -> "VIT";
            case "INTELLECT", "INTELLIGENCE" -> "INT";
            case "HEALTH" -> "HP";
            case "HEALTH REGEN", "HEALTH/S", "HP/S", "HP REGEN/S", "HP RECOVERY" -> "HP REGEN";
            case "ENERGY/S", "ENERGY REGEN/S" -> "ENERGY REGEN";
            case "DMG REDUCTION" -> "ARMOR";
            case "FIRE RESISTANCE" -> "FIRE RESIST";
            case "ICE RESISTANCE" -> "ICE RESIST";
            case "POISON RESISTANCE" -> "POISON RESIST";
            case "PURE RESISTANCE" -> "PURE RESIST";
            case "ELEMENTAL RESISTANCE" -> "ELEMENTAL RESIST";
            case "MOVEMENT SPEED" -> "MOVE SPEED";
            case "REFLECT" -> "REFLECTION";
            case "VS MONSTERS", "VS. MONSTER", "VS MONSTER", "DMG VS MOB", "DMG VS MONSTERS" -> "VS. MONSTERS";
            case "VS PLAYERS", "DMG VS PLAYER", "DMG VS PLAYERS" -> "VS. PLAYERS";
            default -> normalized;
        };
    }

    private static List<String> getActiveRuleLabels() {
        return switch (getCurrentItemCategory()) {
            case Armor -> List.of(ARMOR_RULE_LABELS);
            case Shield -> List.of(SHIELD_RULE_LABELS);
            case Weapon -> List.of(WEAPON_RULE_LABELS);
        };
    }

    private static ItemCategory getCurrentItemCategory() {
        if (lastSnapshot != null) return categorizeByName(lastSnapshot.rawItemName());
        if (mc.currentScreen instanceof HandledScreen<?> handled) {
            Integer targetSlotId = resolveTargetSlotId(handled);
            if (targetSlotId != null) {
                ItemStack stack = handled.getScreenHandler().getSlot(targetSlotId).getStack();
                if (!stack.isEmpty()) return categorizeByName(stack.getName().getString());
            }
            if (isPreviewScreen(handled.getTitle().getString())) return ItemCategory.Weapon;
        }
        return ItemCategory.Weapon;
    }

    private static ItemCategory categorizeByName(String rawName) {
        String lower = rawName == null ? "" : rawName.toLowerCase(Locale.ROOT);
        if (lower.contains("shield")) return ItemCategory.Shield;
        if (lower.contains("helmet") || lower.contains("chestplate") || lower.contains("platemail") || lower.contains("leggings") || lower.contains("boots")) {
            return ItemCategory.Armor;
        }
        return ItemCategory.Weapon;
    }

    private enum State {
        Idle,
        Ready,
        ApplyingOrb,
        ReturningCursor,
        WaitingForResult,
        StoppedMatched,
        StoppedNoMoreOrbs,
        StoppedInvalidLayout
    }

    private enum StopMode {
        Any,
        All
    }

    private enum ItemCategory {
        Weapon("Weapon"),
        Armor("Armor"),
        Shield("Shield");

        private final String label;

        ItemCategory(String label) {
            this.label = label;
        }
    }

    private record LogEntry(String text, int color) {
    }

    private record ResolvedMapping(int targetSlotId, List<List<Integer>> groups) {
    }
}
