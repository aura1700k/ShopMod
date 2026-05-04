package com.shopmod;

import com.shopmod.chat.ChatCommandHandler;
import com.shopmod.config.ShopConfig;
import com.shopmod.security.HmacUtil;
import com.shopmod.security.TrustManager;
import com.shopmod.security.SeedRegistry;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.ResourcePackActivationType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.block.CraftingTableBlock;
import net.minecraft.item.Items;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Identifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ShopModClient implements ClientModInitializer {

    public static final String MOD_ID = "shopmod";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    @Override
    public void onInitializeClient() {
        LOGGER.info("ShopMod initializing (client-only)...");
        ShopConfig.load();
        SeedRegistry.load();

        // Регистрируем встроенный ресурспак с текстурами и названиями монет
        FabricLoader.getInstance().getModContainer(MOD_ID).ifPresent(container -> {
            ResourceManagerHelper.registerBuiltinResourcePack(
                Identifier.of(MOD_ID, "shopmod_coins"),
                container,
                ResourcePackActivationType.ALWAYS_ENABLED
            );
            LOGGER.info("ShopMod: встроенный ресурспак монет зарегистрирован");
        });

        // Открыть магазин: ПКМ по верстаку с изумрудом в руке
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (!world.isClient) return ActionResult.PASS;
            if (player.getStackInHand(hand).getItem() != Items.EMERALD) return ActionResult.PASS;
            var block = world.getBlockState(hitResult.getBlockPos()).getBlock();
            if (!(block instanceof CraftingTableBlock)) return ActionResult.PASS;
            net.minecraft.client.MinecraftClient.getInstance()
                    .setScreen(new com.shopmod.screen.ShopScreen());
            return ActionResult.SUCCESS;
        });

        // Перехват чат-команд + блокировка ручной отправки системных сообщений
        // Заменяет HmacLeakBlockMixin и SeedBlockMixin — Fabric API не требует refMap.
        ClientSendMessageEvents.ALLOW_CHAT.register(message -> {
            // Пропускаем системные сообщения самого мода (JOIN, TAINT, SEED)
            if (SeedRegistry.SYSTEM_SEND_ALLOWED) return true;
            if (ChatCommandHandler.handle(message)) return false;
            // Блокируем ручной ввод системных префиксов и HMAC-токена
            if (isSystemMessage(message)) {
                notifyBlocked(message);
                return false;
            }
            return true;
        });

        // ── Безопасность: перехват читерских команд ──────────────────────────
        // Заменяет CommandInterceptMixin — Fabric API не требует refMap.
        ClientSendMessageEvents.ALLOW_COMMAND.register(command -> {
            if (command == null) return true;
            // Если команду отправляет сам магазин — пропускаем без TAINT
            if (SeedRegistry.SHOP_COMMAND_ALLOWED) return true;
            String lower = command.trim().toLowerCase();
            if (isCheatCommand(lower)) {
                publishTaint(command.trim());
                corruptHistory(command.trim());
            }
            return true; // команду не блокируем — сервер сам откажет если нет прав
        });

        // ── Безопасность: входящие системные сообщения ─────────────────────
        // Заменяет IncomingChatMixin — Fabric API не требует refMap.
        // GAME — системные сообщения без атрибуции (/say, объявления сервера и т.д.)
        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            String text = message.getString();
            dispatchIncoming("__system__", text);
        });
        // CHAT — сообщения игроков с подписью (содержат sender ProfileEntry)
        ClientReceiveMessageEvents.CHAT.register((message, signedMessage, sender, params, receptionTimestamp) -> {
            String senderName = sender != null ? sender.getName() : "__chat__";
            // Пробуем unsignedContent, иначе берём подписанный текст
            net.minecraft.text.Text unsigned = signedMessage.unsignedContent();
            String text = unsigned != null ? unsigned.getString() : signedMessage.getContent().getString();
            dispatchIncoming(senderName, text);
        });

        // ── Безопасность: JOIN-рукопожатие ───────────────────────────────────
        // Отправляем [JOIN] при подключении к серверу
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            client.execute(() -> {
                if (client.player == null) return;
                String playerName = client.player.getGameProfile().getName();
                long timestamp = System.currentTimeMillis();
                String seedHash = com.shopmod.security.InstallationSeed.seedHash();
                String mode = ShopConfig.SHOP_MODE != null ? ShopConfig.SHOP_MODE.name() : "UNSET";
                String payload = TrustManager.JOIN_PREFIX
                        + " player=" + playerName
                        + " seedhash=" + seedHash
                        + " mode=" + mode
                        + " ts=" + timestamp;
                String signed = HmacUtil.attach(payload);
                // Небольшая задержка 2 сек чтобы сервер принял подключение
                Thread t = new Thread(() -> {
                    try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                    client.execute(() -> {
                        if (client.player != null && client.getNetworkHandler() != null) {
                            if (!com.shopmod.security.InstallationSeed.verifyBackup()) {
                                LOGGER.warn("ShopMod: backup seed mismatch for {} — sending TAINT", playerName);
                                publishTaint("config_restore_detected");
                                return;
                            }
                            SeedRegistry.SYSTEM_SEND_ALLOWED = true;
                            try {
                                client.getNetworkHandler().sendChatMessage(signed);
                            } finally {
                                SeedRegistry.SYSTEM_SEND_ALLOWED = false;
                            }
                            LOGGER.info("ShopMod: JOIN отправлен для {}", playerName);
                        }
                    });
                }, "shopmod-join-sender");
                t.setDaemon(true);
                t.start();
            });
        });

        // Tick-проверка тайм-аутов JOIN (раз в ~1 секунду)
        int[] tickCounter = {0};
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (++tickCounter[0] >= 20) {
                tickCounter[0] = 0;
                TrustManager.tickCheck();
            }
        });

        LOGGER.info("ShopMod ready! Монеты: echo_shard=1000, heart_of_the_sea=500, disc_fragment_5=100, honeycomb=50, clay_ball=10, rabbit_hide=5, brick=1");
    }

    // ── Вспомогательные методы (бывший CommandInterceptMixin) ────────────────

    private static final String[] CHEAT_GAMEMODES = {"creative", "1", "spectator", "3"};

    private static boolean isCheatCommand(String lower) {
        if (lower.startsWith("give ") || lower.startsWith("give\t")) return true;
        if (lower.startsWith("op ")   || lower.startsWith("op\t"))   return true;
        if (lower.startsWith("gamemode ") || lower.startsWith("gamemode\t") ||
            lower.startsWith("gm ")       || lower.startsWith("gm\t")) {
            String rest = lower.replaceFirst("gamemode|gm", "").trim();
            for (String cm : CHEAT_GAMEMODES) if (rest.startsWith(cm)) return true;
            return false;
        }
        if (lower.equals("gmc")  || lower.startsWith("gmc "))  return true;
        if (lower.equals("gmsp") || lower.startsWith("gmsp ")) return true;
        return false;
    }

    private static void publishTaint(String command) {
        net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
        if (mc.player == null || mc.getNetworkHandler() == null) return;
        String playerName = mc.player.getGameProfile().getName();
        long   timestamp  = System.currentTimeMillis();
        String payload = TrustManager.TAINT_PREFIX
                + " player=" + playerName
                + " cmd="    + sanitize(command)
                + " ts="     + timestamp;
        String signed = HmacUtil.attach(payload);
        SeedRegistry.SYSTEM_SEND_ALLOWED = true;
        try {
            mc.getNetworkHandler().sendChatMessage(signed);
        } finally {
            SeedRegistry.SYSTEM_SEND_ALLOWED = false;
        }
        mc.player.sendMessage(
            net.minecraft.text.Text.literal("§c[ShopMod] ⚠ Команда §e/" + sanitize(command)
                + "§c опубликована как TAINT для всех игроков."), false);
    }

    private static void corruptHistory(String command) {
        net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
        String playerName = mc.player != null ? mc.player.getGameProfile().getName() : "unknown";
        long ts = System.currentTimeMillis();
        String marker = "§4[CHEAT] §e" + playerName
                + "§4 использовал §c/" + sanitize(command)
                + " §7@ " + ts;
        ShopConfig.addAtmHistory(marker);
        String checkEntry = "§8[integrity:" + HmacUtil.sign("cheat:" + playerName + ":" + ts) + "]";
        ShopConfig.addAtmHistory(checkEntry);
    }

    private static String sanitize(String s) {
        return s.replaceAll("[^a-zA-Z0-9_ @]", "?").substring(0, Math.min(s.length(), 64));
    }

    // ── Диспетчер входящих сообщений (бывший IncomingChatMixin) ─────────────

    private static void dispatchIncoming(String sender, String text) {
        if (text == null || text.isBlank()) return;

        if (text.startsWith(SeedRegistry.SEED_MSG_PREFIX)) {
            SeedRegistry.onSeedMessage(sender, text);
            return;
        }

        if (text.startsWith(TrustManager.JOIN_PREFIX)
                || text.startsWith(TrustManager.TAINT_PREFIX)) {
            TrustManager.onIncomingMessage(sender, text);
        }
    }

    // ── Блокировка ручной отправки системных сообщений (бывшие HmacLeakBlockMixin/SeedBlockMixin) ──

    private static final String HMAC_TOKEN   = "||hmac:";

    private static boolean isSystemMessage(String msg) {
        if (msg == null) return false;
        return msg.contains(HMAC_TOKEN)
            || msg.startsWith(TrustManager.JOIN_PREFIX)
            || msg.startsWith(TrustManager.TAINT_PREFIX)
            || msg.startsWith(SeedRegistry.SEED_MSG_PREFIX);
    }

    private static void notifyBlocked(String msg) {
        net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
        if (mc.player != null) {
            mc.player.sendMessage(
                net.minecraft.text.Text.literal("§c[ShopMod] ✗ Отправка системных сообщений ShopMod запрещена."),
                false
            );
        }
        LOGGER.warn("ShopMod: заблокирована попытка отправить системное сообщение: [{}...]",
            msg.substring(0, Math.min(msg.length(), 40)));
    }
}
