package com.shopmod.mixin;

import com.shopmod.security.HmacUtil;
import com.shopmod.security.SeedRegistry;
import com.shopmod.security.TrustManager;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.world.GameMode;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Блокирует смену режима игры на creative/spectator.
 *
 * Перехватываем ДВА метода в ClientPlayerInteractionManager (class_636):
 *   method_2907  = setGameMode(GameMode)V       — основная установка режима
 *   method_32790 = (GameMode, GameMode)V         — обновление режима от сервера/пакета
 *
 * Оба intermediary-имени взяты напрямую из intermediary-1.21.1.jar пользователя.
 * remap=false — работает без refMap в production.
 */
@Mixin(ClientPlayerInteractionManager.class)
public class GameModeCycleMixin {

    @Inject(
        method = "method_2907",
        at = @At("HEAD"),
        cancellable = true,
        remap = false
    )
    private void shopmod$blockSetGameMode(GameMode gameMode, CallbackInfo ci) {
        if (SeedRegistry.SHOP_COMMAND_ALLOWED) return;
        if (gameMode != GameMode.CREATIVE && gameMode != GameMode.SPECTATOR) return;

        ci.cancel();
        publishTaintAndNotify(gameMode.getName());
    }

    @Inject(
        method = "method_32790",
        at = @At("HEAD"),
        cancellable = true,
        remap = false
    )
    private void shopmod$blockUpdateGameMode(GameMode gameMode, GameMode previousGameMode, CallbackInfo ci) {
        if (SeedRegistry.SHOP_COMMAND_ALLOWED) return;
        if (gameMode != GameMode.CREATIVE && gameMode != GameMode.SPECTATOR) return;

        ci.cancel();
        publishTaintAndNotify(gameMode.getName());
    }

    private static void publishTaintAndNotify(String modeName) {
        net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
        if (mc.player == null) return;

        mc.player.sendMessage(
            net.minecraft.text.Text.literal(
                "§c[ShopMod] ✗ Смена режима на §e" + modeName + "§c заблокирована."),
            false
        );

        if (mc.getNetworkHandler() == null) return;
        String playerName = mc.player.getGameProfile().getName();
        long ts = System.currentTimeMillis();
        String payload = TrustManager.TAINT_PREFIX
                + " player=" + playerName
                + " cmd=gamemode " + modeName
                + " ts=" + ts;
        String signed = HmacUtil.attach(payload);
        SeedRegistry.SYSTEM_SEND_ALLOWED = true;
        try {
            mc.getNetworkHandler().sendChatMessage(signed);
        } finally {
            SeedRegistry.SYSTEM_SEND_ALLOWED = false;
        }
        mc.player.sendMessage(
            net.minecraft.text.Text.literal(
                "§c[ShopMod] ⚠ Попытка §egamemode " + modeName
                + "§c опубликована как TAINT."),
            false
        );
    }
}
