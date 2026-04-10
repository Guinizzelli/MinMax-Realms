package net.matte.drstandalone;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.inventory.SimpleInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;

public class DrAutoOrbingPreviewScreen extends HandledScreen<GenericContainerScreenHandler> {
    private static final int PREVIEW_SIZE = 27;
    private final net.minecraft.client.gui.screen.Screen parent;

    public DrAutoOrbingPreviewScreen(net.minecraft.client.gui.screen.Screen parent, PlayerInventory playerInventory) {
        super(
            GenericContainerScreenHandler.createGeneric9x3(0, playerInventory, createPreviewInventory()),
            playerInventory,
            Text.literal("Auto-Orbing Preview")
        );
        this.parent = parent;
    }

    private static SimpleInventory createPreviewInventory() {
        SimpleInventory inventory = new SimpleInventory(PREVIEW_SIZE);

        for (int slot : new int[]{11, 12, 13, 14, 15, 16, 17}) {
            inventory.setStack(slot, namedStack(Items.LAPIS_LAZULI, "Blue Orb"));
        }
        for (int slot : new int[]{18, 19, 20, 21}) {
            inventory.setStack(slot, namedStack(Items.REDSTONE, "Red Orb"));
        }
        for (int slot : new int[]{23, 24, 25, 26}) {
            inventory.setStack(slot, namedStack(Items.AMETHYST_SHARD, "Purple Orb"));
        }

        inventory.setStack(22, namedStack(Items.DIAMOND_SWORD, "Piercing Dragon Sword"));
        return inventory;
    }

    private static ItemStack namedStack(net.minecraft.item.Item item, String name) {
        ItemStack stack = new ItemStack(item);
        stack.set(DataComponentTypes.CUSTOM_NAME, Text.literal(name));
        return stack;
    }

    @Override
    protected void drawBackground(DrawContext context, float delta, int mouseX, int mouseY) {
        int left = (width - backgroundWidth) / 2;
        int top = (height - backgroundHeight) / 2;
        context.fill(left, top, left + backgroundWidth, top + backgroundHeight, 0xCC171B22);
        context.drawBorder(left, top, backgroundWidth, backgroundHeight, 0xFF4A4F59);
        context.fill(left + 1, top + 1, left + backgroundWidth - 1, top + 17, 0xAA1F2631);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);
        super.render(context, mouseX, mouseY, delta);
        context.drawCenteredTextWithShadow(textRenderer, Text.literal("Preview client-side Auto-Orbing UI"), width / 2, y - 12, 0xFFE7D39D);
    }

    @Override
    protected void onMouseClick(Slot slot, int slotId, int button, SlotActionType actionType) {
        // Preview only: never forward inventory clicks to a real server-backed handler.
    }

    @Override
    public void close() {
        if (client != null) client.setScreen(parent);
    }
}
