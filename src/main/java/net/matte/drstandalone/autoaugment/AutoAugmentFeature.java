package net.matte.drstandalone.autoaugment;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.matte.drstandalone.DrRarityHelper;
import net.matte.drstandalone.DrStandaloneConfig;
import net.matte.drstandalone.DrStandaloneMod;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class AutoAugmentFeature {
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    private static final int AUGMENT_SLOT = 22;
    private static final int TARGET_ITEM_SLOT = 13;
    private static final int MIN_ATTEMPTS = 1;
    private static final int MAX_ATTEMPTS = 50;

    private static boolean active;
    private static State state = State.Idle;
    private static int actionDelayLeft;
    private static boolean smithScreenOpen;
    private static int currentSyncId = -1;
    private static int configuredRounds = 10;
    private static int remainingRounds = 10;
    private static boolean roundsCompleted;
    private static boolean snapshotDirty;
    private static @Nullable DrEnchantSnapshotParser.Snapshot lastSnapshot;
    private static String lastSnapshotSummary = "no snapshot";
    private static boolean waitingResultSnapshot;
    private static int resultSnapshotDelayLeft;

    private static String configuredItemName = "no item";
    private static StopMode stopMode = StopMode.Any;
    private static RunMode runMode = RunMode.Attempts;
    private static PauseRule pauseRule = PauseRule.None;
    private static boolean pausedByRule;
    private static final List<String> scannedLabels = new ArrayList<>();
    private static final Map<String, DrRarityHelper.TooltipTheme> minRarityByLabel = new LinkedHashMap<>();
    private static final LinkedList<LogEntry> recentLogEntries = new LinkedList<>();
    private static final String[] PREVIEW_LABELS = {
        "HP REGEN",
        "ENERGY REGEN",
        "DODGE",
        "LIFE STEAL",
        "THORNS",
        "REFLECTION"
    };

    private AutoAugmentFeature() {
    }

    public static void init() {
        ClientTickEvents.END_CLIENT_TICK.register(client -> onTick());
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> onReceiveMessage(message.getString()));
        hydrateConfig();
    }

    private static void hydrateConfig() {
        DrStandaloneConfig config = DrStandaloneMod.config();
        active = config.autoAugmentEnabled;
        configuredRounds = Math.max(MIN_ATTEMPTS, Math.min(MAX_ATTEMPTS, config.autoAugmentConfiguredAttempts));
        remainingRounds = configuredRounds;
        runMode = parseRunMode(config.autoAugmentRunMode);
        stopMode = parseStopMode(config.autoAugmentStopMode);
        pauseRule = parsePauseRule(config.autoAugmentPauseRule);
    }

    private static void persistConfig() {
        DrStandaloneConfig config = DrStandaloneMod.config();
        config.autoAugmentEnabled = active;
        config.autoAugmentConfiguredAttempts = configuredRounds;
        config.autoAugmentRunMode = runMode.name();
        config.autoAugmentStopMode = stopMode.name();
        config.autoAugmentPauseRule = pauseRule.name();
        config.save();
    }

    private static void onTick() {
        if (mc.player == null || mc.interactionManager == null) return;

        if (!active) {
            if (waitingResultSnapshot || snapshotDirty) {
                syncCurrentSmithScreen();
                if (waitingResultSnapshot && resultSnapshotDelayLeft > 0) resultSnapshotDelayLeft--;
                captureSnapshotIfReady();
            }
            return;
        }

        syncCurrentSmithScreen();
        if (waitingResultSnapshot && resultSnapshotDelayLeft > 0) resultSnapshotDelayLeft--;
        captureSnapshotIfReady();
        if (pausedByRule) return;
        if (actionDelayLeft > 0) {
            actionDelayLeft--;
            return;
        }

        switch (state) {
            case Idle, PausedByRule, WaitingForResult -> {
            }
            case ReadyToClick, WaitingForScreenReturn -> tryClickAugment();
        }
    }

    private static void onReceiveMessage(String message) {
        if (message == null || message.isBlank()) return;
        if (!active && !waitingResultSnapshot) return;

        String lower = message.toLowerCase(Locale.ROOT);
        if (lower.contains("transaction successful")) {
            if (active && runMode == RunMode.Attempts && remainingRounds > 0) remainingRounds--;
            snapshotDirty = true;
            waitingResultSnapshot = true;
            resultSnapshotDelayLeft = DrStandaloneMod.config().autoAugmentResultSnapshotDelayTicks;

            if (active && runMode == RunMode.Attempts && remainingRounds == 0) {
                roundsCompleted = true;
                state = State.Idle;
                feedback("Attempts complete.");
                return;
            }

            state = active ? State.WaitingForScreenReturn : State.Idle;
            actionDelayLeft = nextAugmentDelay();
            feedback("Transaction successful.");
            return;
        }

        if (lower.contains("cancel") || lower.contains("not enough") || lower.contains("unable") || lower.contains("failed")) {
            state = State.Idle;
            feedback("Stopped on server message.");
        }
    }

    private static void tryClickAugment() {
        if (!tryClickAugmentInternal()) return;
        state = State.WaitingForScreenReturn;
        actionDelayLeft = nextAugmentDelay();
    }

    private static boolean tryClickAugmentInternal() {
        if (!smithScreenOpen) return false;
        if (waitingResultSnapshot) return false;
        if (!(mc.currentScreen instanceof HandledScreen<?> handled)) return false;
        if (!isAllowedSmith(handled.getTitle().getString())) return false;

        if (DrStandaloneMod.config().autoAugmentRequireAugmentSlot) {
            if (handled.getScreenHandler().slots.size() <= AUGMENT_SLOT) return false;
            ItemStack stack = handled.getScreenHandler().getSlot(AUGMENT_SLOT).getStack();
            String name = stack.getName().getString().toLowerCase(Locale.ROOT);
            if (stack.isEmpty() || !name.contains("augment")) return false;
        }

        currentSyncId = handled.getScreenHandler().syncId;
        mc.interactionManager.clickSlot(currentSyncId, AUGMENT_SLOT, 1, SlotActionType.QUICK_MOVE, mc.player);
        return true;
    }

    private static void syncCurrentSmithScreen() {
        if (!(mc.currentScreen instanceof HandledScreen<?> handled)) {
            smithScreenOpen = false;
            currentSyncId = -1;
            if (state != State.WaitingForResult && state != State.WaitingForScreenReturn) state = State.Idle;
            return;
        }

        if (!isAllowedSmith(handled.getTitle().getString())) {
            smithScreenOpen = false;
            currentSyncId = -1;
            if (state != State.WaitingForResult && state != State.WaitingForScreenReturn) state = State.Idle;
            return;
        }

        smithScreenOpen = true;
        if (roundsCompleted || (runMode == RunMode.Attempts && remainingRounds <= 0)) return;

        int syncId = handled.getScreenHandler().syncId;
        if (currentSyncId != syncId) {
            currentSyncId = syncId;
            snapshotDirty = true;
            if (state == State.Idle || state == State.WaitingForScreenReturn || state == State.WaitingForResult) {
                state = State.ReadyToClick;
                actionDelayLeft = nextAugmentDelay();
            }
        } else if (state == State.Idle) {
            state = State.ReadyToClick;
            actionDelayLeft = nextAugmentDelay();
        }
    }

    private static void captureSnapshotIfReady() {
        if (!snapshotDirty) return;
        if (waitingResultSnapshot && resultSnapshotDelayLeft > 0) return;
        if (!(mc.currentScreen instanceof HandledScreen<?> handled)) return;

        Integer itemSlot = findTargetItemSlot(handled);
        if (itemSlot == null) return;

        ItemStack stack = handled.getScreenHandler().getSlot(itemSlot).getStack();
        if (!isValidTargetItem(stack)) return;

        DrEnchantSnapshotParser.Snapshot snapshot = DrEnchantSnapshotParser.inspect(mc, stack);
        if (snapshot == null) return;

        snapshotDirty = false;
        lastSnapshotSummary = DrEnchantSnapshotParser.shortSummary(snapshot);
        refreshOverlayLog(snapshot);
        waitingResultSnapshot = false;
        resultSnapshotDelayLeft = 0;
        lastSnapshot = snapshot;
        if (!active) return;

        if (shouldPauseOnRules(snapshot)) {
            pausedByRule = true;
            state = State.PausedByRule;
            feedback("Paused by rule.");
            return;
        }

        if (shouldStopOnConfiguredTargets(snapshot)) {
            roundsCompleted = true;
            remainingRounds = 0;
            state = State.Idle;
            feedback("Target matched.");
        }
    }

    private static boolean shouldPauseOnRules(DrEnchantSnapshotParser.Snapshot snapshot) {
        if (pauseRule == PauseRule.None) return false;

        int epicOrBetter = 0;
        int improvedStats = 0;
        for (DrEnchantSnapshotParser.ParsedStat stat : snapshot.statsByLabel().values()) {
            if (Math.abs(stat.enchantBonus()) >= 0.0001 || stat.enchantRarity() != null) improvedStats++;
            if (rarityAtLeast(stat.enchantRarity(), DrRarityHelper.TooltipTheme.Epic)) epicOrBetter++;
        }

        return switch (pauseRule) {
            case None -> false;
            case TwoEpics -> epicOrBetter >= 2;
            case ThreeImproved -> improvedStats >= 3;
            case Either -> epicOrBetter >= 2 || improvedStats >= 3;
        };
    }

    private static boolean shouldStopOnConfiguredTargets(DrEnchantSnapshotParser.Snapshot snapshot) {
        boolean anyConfigured = false;
        boolean anyMatched = false;
        boolean allMatched = true;

        for (String label : scannedLabels) {
            DrRarityHelper.TooltipTheme target = minRarityByLabel.get(label);
            if (target == null) continue;

            anyConfigured = true;
            DrEnchantSnapshotParser.ParsedStat stat = snapshot.statsByLabel().get(label);
            boolean matched = stat != null && rarityAtLeast(stat.enchantRarity(), target);
            anyMatched |= matched;
            allMatched &= matched;
        }

        if (!anyConfigured) return false;
        return stopMode == StopMode.Any ? anyMatched : allMatched;
    }

    private static boolean rarityAtLeast(@Nullable DrRarityHelper.TooltipTheme actual, @Nullable DrRarityHelper.TooltipTheme target) {
        if (actual == null || target == null) return false;
        return rarityIndex(actual) >= rarityIndex(target);
    }

    private static int rarityIndex(DrRarityHelper.TooltipTheme theme) {
        return switch (theme) {
            case Common -> 0;
            case Uncommon -> 1;
            case Rare -> 2;
            case Epic -> 3;
            case Legendary -> 4;
            case Mythic -> 5;
        };
    }

    private static Integer findTargetItemSlot(HandledScreen<?> handled) {
        if (handled.getScreenHandler().slots.size() > TARGET_ITEM_SLOT) {
            Slot preferred = handled.getScreenHandler().getSlot(TARGET_ITEM_SLOT);
            if (isValidTargetItem(preferred.getStack())) return TARGET_ITEM_SLOT;
        }
        return findSmithCandidateSlot(handled);
    }

    private static @Nullable Integer findSmithCandidateSlot(HandledScreen<?> handled) {
        int total = handled.getScreenHandler().slots.size();
        int topSlots = Math.max(0, total - 36);
        for (int i = 0; i < topSlots; i++) {
            if (i == AUGMENT_SLOT) continue;
            ItemStack stack = handled.getScreenHandler().getSlot(i).getStack();
            if (!isValidTargetItem(stack)) continue;
            DrEnchantSnapshotParser.Snapshot snapshot = DrEnchantSnapshotParser.inspect(mc, stack);
            if (snapshot == null || snapshot.statsByLabel().isEmpty()) continue;
            return i;
        }
        return null;
    }

    private static boolean isValidTargetItem(ItemStack stack) {
        if (stack.isEmpty()) return false;
        if (stack.isOf(Items.ARMOR_STAND) || stack.isOf(Items.BARRIER)) return false;
        String name = stack.getName().getString().toLowerCase(Locale.ROOT);
        return !name.contains("augment item");
    }

    private static int nextAugmentDelay() {
        return Math.max(0, DrStandaloneMod.config().autoAugmentShiftRightDelayTicks);
    }

    private static boolean isPreviewTitle(String title) {
        if (title == null) return false;
        return title.toLowerCase(Locale.ROOT).contains("armorsmith preview");
    }

    private static boolean isPreviewScreenOpen() {
        if (!(mc.currentScreen instanceof HandledScreen<?> handled)) return false;
        return isPreviewTitle(handled.getTitle().getString());
    }

    private static void ensurePreviewConfig() {
        if (!isPreviewScreenOpen()) return;
        if (!scannedLabels.isEmpty() && !"no item".equalsIgnoreCase(configuredItemName)) return;

        configuredItemName = "Preview Platemail Boots";
        scannedLabels.clear();
        Collections.addAll(scannedLabels, PREVIEW_LABELS);
        if (!minRarityByLabel.containsKey(PREVIEW_LABELS[0])) minRarityByLabel.put(PREVIEW_LABELS[0], DrRarityHelper.TooltipTheme.Rare);
        if (!minRarityByLabel.containsKey(PREVIEW_LABELS[1])) minRarityByLabel.put(PREVIEW_LABELS[1], DrRarityHelper.TooltipTheme.Epic);
        if (!minRarityByLabel.containsKey(PREVIEW_LABELS[2])) minRarityByLabel.put(PREVIEW_LABELS[2], DrRarityHelper.TooltipTheme.Legendary);
    }

    private static boolean isAllowedSmith(String title) {
        if (title == null) return false;
        String lower = title.toLowerCase(Locale.ROOT);
        DrStandaloneConfig config = DrStandaloneMod.config();
        return (config.autoAugmentWeaponsmith && lower.contains("weaponsmith"))
            || (config.autoAugmentArmorsmith && lower.contains("armorsmith"));
    }

    private static void feedback(String message) {
        DrStandaloneMod.LOG.info("[AutoAugment] {}", message);
    }

    private static void refreshOverlayLog(@Nullable DrEnchantSnapshotParser.Snapshot snapshot) {
        recentLogEntries.clear();
        if (snapshot == null || snapshot.statsByLabel().isEmpty()) {
            recentLogEntries.add(new LogEntry("No enchant lines", 0xFF9AA0AA));
            return;
        }

        int shown = 0;
        for (DrEnchantSnapshotParser.ParsedStat stat : snapshot.statsByLabel().values()) {
            if (shown >= 8) break;

            double bonus = stat.enchantBonus();
            DrRarityHelper.TooltipTheme theme = stat.enchantRarity();
            if (Math.abs(bonus) < 0.0001 && theme == null) continue;

            String line = String.format(Locale.ROOT, "%s %+.1f", stat.canonicalLabel(), bonus);
            recentLogEntries.add(new LogEntry(line, colorForRarity(theme)));
            shown++;
        }

        if (recentLogEntries.isEmpty()) recentLogEntries.add(new LogEntry("No enchant lines", 0xFF9AA0AA));
    }

    private static int colorForRarity(@Nullable DrRarityHelper.TooltipTheme theme) {
        if (theme == null) return 0xFFD7D7D7;
        return switch (theme) {
            case Mythic -> 0xFFFF5A5A;
            case Legendary -> 0xFFFFC357;
            case Epic -> 0xFFE396FF;
            case Rare -> 0xFF66D5FF;
            case Uncommon -> 0xFF8CFB8C;
            case Common -> 0xFFD7D7D7;
        };
    }

    public static boolean shouldAddScreenButton(String title) {
        return isAllowedSmith(title) || isPreviewTitle(title);
    }

    public static String getScreenButtonText() {
        return active ? "Auto Augment: ON" : "Auto Augment: OFF";
    }

    public static String getRoundsButtonText() {
        int value = active && runMode == RunMode.Attempts ? remainingRounds : configuredRounds;
        return "Attempts: " + value;
    }

    public static int getUiAttemptsCount() {
        return configuredRounds;
    }

    public static double getUiAttemptsSliderValue() {
        return (configuredRounds - MIN_ATTEMPTS) / (double) (MAX_ATTEMPTS - MIN_ATTEMPTS);
    }

    public static void setUiAttemptsSliderValue(double value) {
        double clamped = Math.max(0d, Math.min(1d, value));
        int mapped = MIN_ATTEMPTS + (int) Math.round(clamped * (MAX_ATTEMPTS - MIN_ATTEMPTS));
        configuredRounds = Math.max(MIN_ATTEMPTS, Math.min(MAX_ATTEMPTS, mapped));
        if (runMode == RunMode.Attempts) {
            if (!active) remainingRounds = configuredRounds;
            else remainingRounds = Math.max(remainingRounds, 1);
        }
        persistConfig();
    }

    public static String getScreenStatusText() {
        String stateText = switch (state) {
            case PausedByRule -> "paused";
            case WaitingForResult -> "waiting result";
            case WaitingForScreenReturn -> "waiting gui";
            case ReadyToClick -> "ready";
            default -> "idle";
        };
        return stateText + " | " + lastSnapshotSummary + " | bksp stop";
    }

    public static double getUiSpeedSliderValue() {
        int delay = Math.max(0, Math.min(60, DrStandaloneMod.config().autoAugmentShiftRightDelayTicks));
        return 1d - (delay / 60d);
    }

    public static int getUiSpeedPercent() {
        return (int) Math.round(getUiSpeedSliderValue() * 100d);
    }

    public static void setUiSpeedSliderValue(double value) {
        double clamped = Math.max(0d, Math.min(1d, value));
        int delay = (int) Math.round((1d - clamped) * 60d);
        DrStandaloneConfig config = DrStandaloneMod.config();
        config.autoAugmentShiftRightDelayTicks = Math.max(0, Math.min(60, delay));
        config.save();
    }

    public static boolean isActiveFromScreen() {
        return active;
    }

    public static void stopNowFromScreen() {
        if (!active) return;
        active = false;
        state = State.Idle;
        persistConfig();
        addLogEntry("Stopped manually.", 0xFFFF7A7A);
    }

    public static void stopNowSilentlyFromScreen() {
        if (!active) return;
        active = false;
        state = State.Idle;
        persistConfig();
    }

    public static void augmentOnceFromScreen() {
        if (mc.player == null || mc.interactionManager == null) return;
        if (isPreviewScreenOpen()) {
            addLogEntry("Preview mode: augment disabled.", 0xFF9AA0AA);
            return;
        }
        if (waitingResultSnapshot || actionDelayLeft > 0) return;
        syncCurrentSmithScreen();
        if (!smithScreenOpen) return;
        if (!tryClickAugmentInternal()) return;

        snapshotDirty = true;
        waitingResultSnapshot = true;
        resultSnapshotDelayLeft = DrStandaloneMod.config().autoAugmentResultSnapshotDelayTicks;
        actionDelayLeft = nextAugmentDelay();
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

    public static String getConfigItemButtonText() {
        return shorten("Item: " + configuredItemName, 26);
    }

    public static String getConfigRescanButtonText() {
        return "Rescan";
    }

    public static void rescanConfigFromScreen() {
        scanCurrentItemConfig();
    }

    public static String getStopModeButtonText() {
        return "Stop: " + stopMode.name();
    }

    public static void cycleStopModeFromScreen() {
        stopMode = stopMode == StopMode.Any ? StopMode.All : StopMode.Any;
        persistConfig();
    }

    public static String getRunModeButtonText() {
        return "Mode: " + (runMode == RunMode.Attempts ? "Attempts" : "Until setting");
    }

    public static void cycleRunModeFromScreen() {
        runMode = runMode == RunMode.Attempts ? RunMode.UntilSetting : RunMode.Attempts;
        remainingRounds = configuredRounds;
        roundsCompleted = false;
        persistConfig();
    }

    public static String getPauseRuleButtonText() {
        return "Pause: " + switch (pauseRule) {
            case None -> "None";
            case TwoEpics -> "2 Epics";
            case ThreeImproved -> "3 Improved";
            case Either -> "Either";
        };
    }

    public static void cyclePauseRuleFromScreen() {
        pauseRule = switch (pauseRule) {
            case None -> PauseRule.TwoEpics;
            case TwoEpics -> PauseRule.ThreeImproved;
            case ThreeImproved -> PauseRule.Either;
            case Either -> PauseRule.None;
        };
        persistConfig();
    }

    public static boolean shouldShowAttemptsButton() {
        return runMode == RunMode.Attempts;
    }

    public static int getConfigRuleButtonCount() {
        ensurePreviewConfig();
        return Math.min(8, scannedLabels.size());
    }

    public static String getConfigRuleButtonText(int index) {
        ensurePreviewConfig();
        if (index < 0 || index >= scannedLabels.size()) return "-";

        String label = scannedLabels.get(index);
        DrRarityHelper.TooltipTheme theme = minRarityByLabel.get(label);
        String target = theme == null ? "Ignore" : theme.name();
        return shorten(label + ": " + target, 26);
    }

    public static void cycleConfigRuleFromScreen(int index) {
        ensurePreviewConfig();
        if (index < 0 || index >= scannedLabels.size()) return;

        String label = scannedLabels.get(index);
        DrRarityHelper.TooltipTheme current = minRarityByLabel.get(label);
        DrRarityHelper.TooltipTheme next = switch (current) {
            case null -> DrRarityHelper.TooltipTheme.Common;
            case Common -> DrRarityHelper.TooltipTheme.Uncommon;
            case Uncommon -> DrRarityHelper.TooltipTheme.Rare;
            case Rare -> DrRarityHelper.TooltipTheme.Epic;
            case Epic -> DrRarityHelper.TooltipTheme.Legendary;
            case Legendary -> DrRarityHelper.TooltipTheme.Mythic;
            case Mythic -> null;
        };

        if (next == null) minRarityByLabel.remove(label);
        else minRarityByLabel.put(label, next);
    }

    public static void toggleFromScreenButton() {
        if (isPreviewScreenOpen()) {
            active = !active;
            state = State.Idle;
            pausedByRule = false;
            roundsCompleted = false;
            remainingRounds = configuredRounds;
            snapshotDirty = true;
            waitingResultSnapshot = false;
            resultSnapshotDelayLeft = 0;
            persistConfig();
            addLogEntry(active ? "Preview mode: auto augment armed." : "Preview mode: auto augment off.", 0xFF9AA0AA);
            return;
        }

        active = !active;
        if (active) {
            state = State.Idle;
            pausedByRule = false;
            roundsCompleted = false;
            remainingRounds = configuredRounds;
            snapshotDirty = true;
            waitingResultSnapshot = false;
            resultSnapshotDelayLeft = 0;
            syncCurrentSmithScreen();
            if (scannedLabels.isEmpty()) scanCurrentItemConfig();
        } else {
            state = State.Idle;
        }
        persistConfig();
    }

    public static void cycleRoundsFromScreenButton() {
        // Deprecated by slider-based attempts control.
    }

    private static boolean scanCurrentItemConfig() {
        if (isPreviewScreenOpen()) {
            ensurePreviewConfig();
            lastSnapshotSummary = "preview snapshot";
            addLogEntry("Preview item scanned.", 0xFF9AA0AA);
            return true;
        }

        if (!(mc.currentScreen instanceof HandledScreen<?> handled)) {
            addLogEntry("Scan failed: no smith screen.", 0xFFFF7A7A);
            return false;
        }

        Integer itemSlot = findTargetItemSlot(handled);
        if (itemSlot == null) {
            addLogEntry("Scan failed: place item in smith.", 0xFFFF7A7A);
            return false;
        }

        ItemStack stack = handled.getScreenHandler().getSlot(itemSlot).getStack();
        DrEnchantSnapshotParser.Snapshot snapshot = DrEnchantSnapshotParser.inspect(mc, stack);
        if (snapshot == null || snapshot.statsByLabel().isEmpty()) {
            addLogEntry("Scan failed: no augmentable stats.", 0xFFFF7A7A);
            return false;
        }

        configuredItemName = DrEnchantSnapshotParser.baseItemName(snapshot.rawItemName());
        scannedLabels.clear();
        scannedLabels.addAll(snapshot.statsByLabel().keySet());
        minRarityByLabel.keySet().removeIf(label -> !snapshot.statsByLabel().containsKey(label));

        lastSnapshot = snapshot;
        lastSnapshotSummary = DrEnchantSnapshotParser.shortSummary(snapshot);
        refreshOverlayLog(snapshot);
        addLogEntry("Scanned " + configuredItemName + ".", 0xFF9AA0AA);
        return true;
    }

    private static String shorten(String text, int max) {
        if (text == null || text.length() <= max) return text;
        return text.substring(0, Math.max(0, max - 3)) + "...";
    }

    private static void addLogEntry(String message, int color) {
        recentLogEntries.addFirst(new LogEntry(message, color));
        while (recentLogEntries.size() > 8) recentLogEntries.removeLast();
    }

    private static RunMode parseRunMode(String value) {
        try {
            return RunMode.valueOf(value == null ? "" : value);
        } catch (IllegalArgumentException ignored) {
            return RunMode.Attempts;
        }
    }

    private static StopMode parseStopMode(String value) {
        try {
            return StopMode.valueOf(value == null ? "" : value);
        } catch (IllegalArgumentException ignored) {
            return StopMode.Any;
        }
    }

    private static PauseRule parsePauseRule(String value) {
        try {
            return PauseRule.valueOf(value == null ? "" : value);
        } catch (IllegalArgumentException ignored) {
            return PauseRule.None;
        }
    }

    private enum State {
        Idle,
        ReadyToClick,
        PausedByRule,
        WaitingForResult,
        WaitingForScreenReturn
    }

    private enum StopMode {
        Any,
        All
    }

    private enum RunMode {
        Attempts,
        UntilSetting
    }

    private enum PauseRule {
        None,
        TwoEpics,
        ThreeImproved,
        Either
    }

    private record LogEntry(String text, int color) {
    }
}
