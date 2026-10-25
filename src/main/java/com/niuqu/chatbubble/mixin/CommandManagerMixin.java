package com.niuqu.chatbubble.mixin;

import com.niuqu.chatbubble.ChatBubbleMod;
import com.mojang.brigadier.ParseResults;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

// Fabric API has no command-execution event (Forge has CommandEvent), so private
// message quotes (/msg /tell /w /whisper) would never be consumed server-side.
// Inject vanilla CommandManager.execute before dispatch — same semantics as Forge.
@Mixin(Commands.class)
public class CommandManagerMixin {

    @Inject(method = "performCommand(Lcom/mojang/brigadier/ParseResults;Ljava/lang/String;)V",
        at = @At("HEAD"))
    private void onCommandExecuted(ParseResults<CommandSourceStack> parseResults, String command, CallbackInfo ci) {
        ChatBubbleMod.consumePrivateMessageQuote(parseResults, command);
    }
}
