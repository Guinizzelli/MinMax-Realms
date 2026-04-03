package net.matte.drstandalone.mixin;

import net.matte.drstandalone.DrHudSnapshot;
import net.matte.drstandalone.DrRarityHelper;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.tooltip.TooltipData;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyVariable;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;
import java.util.Optional;

@Mixin(DrawContext.class)
public abstract class DrawContextMixin {
    @ModifyVariable(method = "drawTooltip(Lnet/minecraft/client/font/TextRenderer;Ljava/util/List;Ljava/util/Optional;IILnet/minecraft/util/Identifier;)V", at = @At("HEAD"), argsOnly = true, ordinal = 0)
    private @Nullable Identifier minmaxrealms$applyQueuedRarityTexture(@Nullable Identifier texture) {
        return DrRarityHelper.apply(texture);
    }

    @Inject(method = "drawTooltip(Lnet/minecraft/client/font/TextRenderer;Ljava/util/List;Ljava/util/Optional;IILnet/minecraft/util/Identifier;)V", at = @At("TAIL"))
    private void minmaxrealms$clearQueuedRarityTexture(TextRenderer textRenderer, List<Text> text, Optional<TooltipData> data, int x, int y, @Nullable Identifier texture, CallbackInfo ci) {
        DrRarityHelper.clear();
    }

    @Inject(method = "drawTextWithShadow(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/Text;III)I", at = @At("HEAD"))
    private void minmaxrealms$captureHudText(TextRenderer textRenderer, Text text, int x, int y, int color, CallbackInfoReturnable<Integer> cir) {
        if (text != null) DrHudSnapshot.capture(text.getString());
    }

    @Inject(method = "drawTextWithShadow(Lnet/minecraft/client/font/TextRenderer;Ljava/lang/String;III)I", at = @At("HEAD"))
    private void minmaxrealms$captureHudString(TextRenderer textRenderer, String text, int x, int y, int color, CallbackInfoReturnable<Integer> cir) {
        DrHudSnapshot.capture(text);
    }

    @Inject(method = "drawTextWithShadow(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/OrderedText;III)I", at = @At("HEAD"))
    private void minmaxrealms$captureHudOrderedText(TextRenderer textRenderer, OrderedText text, int x, int y, int color, CallbackInfoReturnable<Integer> cir) {
        if (text != null) DrHudSnapshot.capture(text.toString());
    }
}
