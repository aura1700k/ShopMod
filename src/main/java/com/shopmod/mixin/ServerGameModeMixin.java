package com.shopmod.mixin;

import net.minecraft.server.network.ServerPlayerInteractionManager;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.world.GameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Серверный Mixin — блокирует смену геймода на creative/spectator.
 * Работает и в одиночке и на мультиплеере, независимо от OP-прав.
 * method_14261 = setGameMode(GameMode, GameMode)V — из intermediary-1.21.1.jar.
 */
@Mixin(ServerPlayerInteractionManager.class)
public class ServerGameModeMixin {

    @Shadow public ServerPlayerEntity player;

    @Inject(
        method = "method_14261",
        at = @At("HEAD"),
        cancellable = true,
        remap = false
    )
    private void shopmod$blockServerSetGameMode(GameMode gameMode, GameMode previousGameMode, CallbackInfo ci) {
        if (gameMode != GameMode.CREATIVE && gameMode != GameMode.SPECTATOR) return;

        ci.cancel();

        try {
            if (player != null) {
                player.sendMessage(
                    Text.literal("§c[ShopMod] ✗ Смена режима на §e" + gameMode.getName() + "§c заблокирована."),
                    false
                );
            }
        } catch (Exception ignored) {
            // Игрок ещё не полностью инициализирован (например, при входе в мир)
        }
    }
}
