package com.niuqu.chatbubble.mixin;

import net.minecraft.client.gui.components.CommandSuggestions;
import net.minecraft.client.renderer.Rect2i;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = CommandSuggestions.SuggestionsList.class)
public interface SuggestionWindowAccessor {
    @Accessor("rect")
    Rect2i getArea();
}
