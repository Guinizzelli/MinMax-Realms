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

public class DrAutoAugmentPreviewScreen extends HandledScreen<GenericContainerScreenHandler> {
    private static final int PREVIEW_SIZE = 27;
    private final net.minecraft.client.gui.screen.Screen parent;

    public DrAutoAugmentPreviewScreen(net.minecraft.client.gui.screen.Screen parent, PlayerInventory playerInventory) {
        super(
            GenericContainerScreenHandler.createGeneric9x3(0, playerInventory, createPreviewInventory()),
            playerInventory,
            Text.literal("Armorsmith Preview")
        );
        this.parent = parent;
    }

    private static SimpleInventory createPreviewInventory() {
        SimpleInventory inventory = new SimpleInventory(PREVIEW_SIZE);

        ItemStack augment = new ItemStack(Items.NETHER_STAR);
        augment.set(DataComponentTypes.CUSTOM_NAME, Text.literal("Augment Item"));
        inventory.setStack(22, augment);

        ItemStack target = new ItemStack(Items.DIAMOND_BOOTS);
        target.set(DataComponentTypes.CUSTOM_NAME, Text.literal("Lucky Arcane Ancient Platemail Boots"));
        inventory.setStack(13, target);

        return inventory;
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
        context.drawCenteredTextWithShadow(textRenderer, Text.literal("Preview client-side AutoAugment UI"), width / 2, y - 12, 0xFFE7D39D);
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
