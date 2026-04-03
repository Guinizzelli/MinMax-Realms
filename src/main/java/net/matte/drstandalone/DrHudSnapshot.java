package net.matte.drstandalone;

import org.jetbrains.annotations.Nullable;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class DrHudSnapshot {
    private static final Pattern STATUS_PATTERN = Pattern.compile("LV\\s*(\\d+)\\s+([A-Z]+)\\s*-\\s*HP\\s*(\\d+(?:\\.\\d+)?)\\s*-\\s*XP\\s*(\\d+(?:\\.\\d+)?)%", Pattern.CASE_INSENSITIVE);
    private static volatile Snapshot latest;

    private DrHudSnapshot() {
    }

    public static void capture(String text) {
        if (text == null || text.isBlank()) return;
        String normalized = text.replace('\u00A0', ' ').trim();
        Matcher matcher = STATUS_PATTERN.matcher(normalized.toUpperCase(Locale.ROOT));
        if (!matcher.find()) return;

        try {
            latest = new Snapshot(
                Integer.parseInt(matcher.group(1)),
                matcher.group(2),
                Double.parseDouble(matcher.group(3).replace(',', '.')),
                Double.parseDouble(matcher.group(4).replace(',', '.')),
                System.currentTimeMillis(),
                normalized
            );
        } catch (NumberFormatException ignored) {
        }
    }

    public static @Nullable Snapshot latestFresh(long maxAgeMillis) {
        Snapshot snapshot = latest;
        if (snapshot == null) return null;
        return System.currentTimeMillis() - snapshot.capturedAtMillis() <= maxAgeMillis ? snapshot : null;
    }

    public record Snapshot(
        int level,
        String className,
        double health,
        double xpPercent,
        long capturedAtMillis,
        String rawText
    ) {
    }
}
