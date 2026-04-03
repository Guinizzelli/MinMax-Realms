package net.matte.drstandalone;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public class DrCodexWikiScreen extends Screen {
    private static final int ENTRIES_PER_PAGE = 8;

    private final Screen parent;
    private final List<DrStatsDatabase.CodexEntry> codexEntries;
    private final List<DrStatsDatabase.CodexEntry> filteredEntries = new ArrayList<>();

    private View selectedView;
    private int codexPage;
    private int wikiPage;

    private TextFieldWidget searchField;
    private List<String> slotOptions = List.of("All");
    private List<String> rarityOptions = List.of("All");
    private int slotFilterIndex;
    private int rarityFilterIndex;

    public DrCodexWikiScreen(Screen parent) {
        this(parent, View.Codex, 0, 0);
    }

    public DrCodexWikiScreen(Screen parent, View initialView, int codexPage, int wikiPage) {
        super(Text.literal("Codex DR Stats"));
        this.parent = parent;
        this.selectedView = initialView;
        this.codexPage = Math.max(0, codexPage);
        this.wikiPage = Math.max(0, wikiPage);
        this.codexEntries = DrStatsDatabase.get().getCodexEntries();
        buildFilterOptions();
        refreshFilteredEntries();
    }

    @Override
    protected void init() {
        clearChildren();

        int panelLeft = this.width / 2 - 190;
        int panelTop = 14;
        int tabY = panelTop + 22;

        addDrawableChild(tabButton(panelLeft + 12, tabY, 170, "Codex Items", View.Codex));
        addDrawableChild(tabButton(panelLeft + 198, tabY, 170, "Wiki Stats", View.Wiki));

        int contentTop = panelTop + 54;
        if (selectedView == View.Codex) {
            buildCodexControls(panelLeft, contentTop);
        } else {
            buildWikiControls(panelLeft, contentTop);
        }

        addDrawableChild(ButtonWidget.builder(Text.literal("Back"), button -> close())
            .dimensions(this.width / 2 - 80, this.height - 38, 160, 20)
            .build());
    }

    private void buildCodexControls(int panelLeft, int contentTop) {
        searchField = new TextFieldWidget(this.textRenderer, panelLeft + 12, contentTop - 2, 178, 18, Text.literal("Search"));
        searchField.setPlaceholder(Text.literal("Item name..."));
        searchField.setChangedListener(value -> {
            codexPage = 0;
            refreshFilteredEntries();
        });
        addDrawableChild(searchField);
        setInitialFocus(searchField);

        ButtonWidget slotFilter = ButtonWidget.builder(Text.literal("Slot: " + currentSlotFilter()), button -> {
            slotFilterIndex = (slotFilterIndex + 1) % slotOptions.size();
            codexPage = 0;
            refreshFilteredEntries();
            button.setMessage(Text.literal("Slot: " + currentSlotFilter()));
        }).dimensions(panelLeft + 196, contentTop - 2, 84, 18).build();
        addDrawableChild(slotFilter);

        ButtonWidget rarityFilter = ButtonWidget.builder(Text.literal("Rarity: " + currentRarityFilter()), button -> {
            rarityFilterIndex = (rarityFilterIndex + 1) % rarityOptions.size();
            codexPage = 0;
            refreshFilteredEntries();
            button.setMessage(Text.literal("Rarity: " + currentRarityFilter()));
        }).dimensions(panelLeft + 284, contentTop - 2, 84, 18).build();
        addDrawableChild(rarityFilter);

        addDrawableChild(ButtonWidget.builder(Text.literal("<"), button -> codexPage = Math.max(0, codexPage - 1))
            .dimensions(panelLeft + 12, contentTop + 194, 30, 20)
            .build());

        addDrawableChild(ButtonWidget.builder(Text.literal(">"), button -> codexPage = Math.min(maxCodexPage(), codexPage + 1))
            .dimensions(panelLeft + 338, contentTop + 194, 30, 20)
            .build());
    }

    private void buildWikiControls(int panelLeft, int contentTop) {
        int maxPage = WIKI_PAGES.size() - 1;
        wikiPage = Math.min(wikiPage, maxPage);

        addDrawableChild(ButtonWidget.builder(Text.literal("<"), button -> wikiPage = Math.max(0, wikiPage - 1))
            .dimensions(panelLeft + 12, contentTop + 194, 30, 20)
            .build());

        addDrawableChild(ButtonWidget.builder(Text.literal(">"), button -> wikiPage = Math.min(maxPage, wikiPage + 1))
            .dimensions(panelLeft + 338, contentTop + 194, 30, 20)
            .build());
    }

    private ButtonWidget tabButton(int x, int y, int width, String label, View view) {
        boolean active = selectedView == view;
        Text text = Text.literal((active ? "> " : "") + label);
        return ButtonWidget.builder(text, button -> {
            selectedView = view;
            clearAndInit();
        }).dimensions(x, y, width, 22).build();
    }

    private void buildFilterOptions() {
        Set<String> slots = new LinkedHashSet<>();
        Set<String> rarities = new LinkedHashSet<>();

        slots.add("All");
        rarities.add("All");

        for (DrStatsDatabase.CodexEntry entry : codexEntries) {
            if (entry.slot != null && !entry.slot.isBlank()) slots.add(pretty(entry.slot));
            if (entry.rarity != null && !entry.rarity.isBlank()) rarities.add(entry.rarity);
        }

        slotOptions = List.copyOf(slots);
        rarityOptions = List.copyOf(rarities);
        slotFilterIndex = 0;
        rarityFilterIndex = 0;
    }

    private void refreshFilteredEntries() {
        filteredEntries.clear();

        String search = searchField == null ? "" : searchField.getText().trim().toLowerCase(Locale.ROOT);
        String slotFilter = currentSlotFilter();
        String rarityFilter = currentRarityFilter();

        for (DrStatsDatabase.CodexEntry entry : codexEntries) {
            boolean matchesSearch = search.isEmpty() || entry.name.toLowerCase(Locale.ROOT).contains(search);
            boolean matchesSlot = "All".equals(slotFilter) || pretty(entry.slot).equalsIgnoreCase(slotFilter);
            boolean matchesRarity = "All".equals(rarityFilter) || rarityFilter.equalsIgnoreCase(entry.rarity);

            if (matchesSearch && matchesSlot && matchesRarity) filteredEntries.add(entry);
        }

        codexPage = Math.min(codexPage, maxCodexPage());
    }

    private String currentSlotFilter() {
        return slotOptions.get(Math.max(0, Math.min(slotFilterIndex, slotOptions.size() - 1)));
    }

    private String currentRarityFilter() {
        return rarityOptions.get(Math.max(0, Math.min(rarityFilterIndex, rarityOptions.size() - 1)));
    }

    private int maxCodexPage() {
        return Math.max(0, (filteredEntries.size() - 1) / ENTRIES_PER_PAGE);
    }

    @Override
    public void tick() {
        super.tick();
        if (searchField != null) searchField.tick();
    }

    @Override
    public boolean charTyped(char chr, int modifiers) {
        if (selectedView == View.Codex && searchField != null && searchField.charTyped(chr, modifiers)) return true;
        return super.charTyped(chr, modifiers);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (selectedView == View.Codex && searchField != null && searchField.keyPressed(keyCode, scanCode, modifiers)) return true;
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
        int contentLeft = left + 14;
        int contentTop = top + 58;

        context.fill(left, top, left + panelWidth, top + panelHeight, 0xD0151A22);
        context.drawBorder(left, top, panelWidth, panelHeight, 0xFF4A4F59);
        context.fill(left + 1, top + 1, left + panelWidth - 1, top + 26, 0xAA1F2631);
        context.drawTextWithShadow(this.textRenderer, Text.literal("MinMax Realms - Codex"), left + 12, top + 8, 0xFFF1E6B8);

        if (selectedView == View.Codex) {
            renderCodex(context, contentLeft, contentTop);
        } else {
            renderWiki(context, contentLeft, contentTop);
        }

        super.render(context, mouseX, mouseY, delta);
    }

    private void renderCodex(DrawContext context, int left, int top) {
        if (filteredEntries.isEmpty()) {
            context.drawTextWithShadow(this.textRenderer, Text.literal("No entries match current filters."), left, top + 24, 0xFFB8BDC8);
            return;
        }

        int maxPage = maxCodexPage();
        codexPage = Math.min(codexPage, maxPage);
        int from = codexPage * ENTRIES_PER_PAGE;
        int to = Math.min(filteredEntries.size(), from + ENTRIES_PER_PAGE);

        context.drawTextWithShadow(this.textRenderer, Text.literal("Results: " + filteredEntries.size() + " / " + codexEntries.size()), left, top + 20, 0xFFD8DEEA);
        context.drawTextWithShadow(this.textRenderer, Text.literal("Page " + (codexPage + 1) + " / " + (maxPage + 1)), left + 260, top + 20, 0xFF97A7BA);

        int y = top + 36;
        for (int i = from; i < to; i++) {
            DrStatsDatabase.CodexEntry entry = filteredEntries.get(i);
            String line1 = "• " + entry.name + " [" + pretty(entry.slot) + "]";
            String line2 = "  " + formatMeta(entry);

            context.drawTextWithShadow(this.textRenderer, Text.literal(line1), left, y, 0xFFF1E6B8);
            y += 11;
            context.drawTextWithShadow(this.textRenderer, Text.literal(line2), left, y, 0xFF9FB0C2);
            y += 13;
        }
    }

    private void renderWiki(DrawContext context, int left, int top) {
        List<String> page = WIKI_PAGES.get(Math.min(wikiPage, WIKI_PAGES.size() - 1));
        context.drawTextWithShadow(this.textRenderer, Text.literal("Stats guide"), left, top, 0xFFD8DEEA);
        context.drawTextWithShadow(this.textRenderer, Text.literal("Page " + (wikiPage + 1) + " / " + WIKI_PAGES.size()), left + 266, top, 0xFF97A7BA);

        int y = top + 16;
        for (String line : page) {
            int color = line.startsWith("#") ? 0xFFF1E6B8 : 0xFFB6C1D0;
            String clean = line.startsWith("#") ? line.substring(1) : line;
            context.drawTextWithShadow(this.textRenderer, Text.literal(clean), left, y, color);
            y += 11;
        }
    }

    private static String formatMeta(DrStatsDatabase.CodexEntry entry) {
        String rarity = entry.rarity == null || entry.rarity.isBlank() ? "N/A" : entry.rarity;
        String type = entry.itemType == null || entry.itemType.isBlank() ? "N/A" : entry.itemType;
        return String.format(Locale.ROOT, "Type: %s | Rarity: %s | T%s L%s | Stats: %d%s",
            type,
            rarity,
            entry.tier,
            entry.level,
            entry.statCount,
            entry.mythic ? " | Mythic" : "");
    }

    private static String pretty(String value) {
        if (value == null || value.isBlank()) return "Unknown";
        String lower = value.toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    public enum View {
        Codex,
        Wiki
    }

    private static final List<List<String>> WIKI_PAGES = List.of(
        List.of(
            "#How rolls are read",
            "Each stat is evaluated between a min and max.",
            "Displayed % = (value - min) / (max - min).",
            "100% means the max roll in that range.",
            "",
            "#Quick colors",
            "Green = high roll, orange = medium, red = low.",
            "Details depend on selected style (Percent/Range).",
            "",
            "#Global average",
            "Average uses all detected stats.",
            "Higher average means a better item."
        ),
        List.of(
            "#Offensive stats",
            "DMG: weapon base damage.",
            "CRITICAL HIT: critical hit chance.",
            "EXECUTE, BLEEDING, PIERCING, SHATTER: special bonuses.",
            "PURE/FIRE/ICE/POISON DMG: elemental damage.",
            "LIFE STEAL: healing from damage dealt.",
            "",
            "#Utility stats",
            "VS. MONSTERS / VS. PLAYERS: situational damage.",
            "ACCURACY: hit reliability.",
            "CLEAVE/CRUSHING: melee-focused bonuses."
        ),
        List.of(
            "#Defensive stats",
            "HP, ARMOR: raw survivability.",
            "HP REGEN / ENERGY REGEN: sustain.",
            "STR, DEX, VIT, INT: primary attributes.",
            "THORNS / REFLECT: reflected damage.",
            "BLOCK / DODGE: conditional mitigation.",
            "FIRE/ICE/POISON/PURE RESIST: resistances.",
            "",
            "#Economy stats",
            "GEM FIND: gem bonus.",
            "ITEM FIND: loot bonus.",
            "MOVE SPEED (boots): mobility."
        )
    );
}
