package com.shopmod.security;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Отслеживает JOIN-рукопожатия и верифицирует seed-хэши игроков.
 *
 * Расширенная схема:
 *   1. При входе игрок отправляет [SHOPMOD-JOIN] с полем seedhash=<SHA-256(seed)>.
 *   2. Получатель проверяет HMAC, затем передаёт seedhash в SeedRegistry.checkJoin():
 *        MATCH        → TRUSTED  (всё штатно)
 *        NEW          → TRUSTED  (первый контакт, хэш сохранён)
 *        SEED_CHANGED → TAINTED  (seed сменился — переустановка!)
 *        NO_HASH      → TRUSTED с предупреждением (старая версия мода)
 *   3. [SHOPMOD-SEED] от кнопки "Поделиться seed" обрабатывается через SeedRegistry.
 */
public final class TrustManager {

    public static final String JOIN_PREFIX  = "[SHOPMOD-JOIN]";
    public static final String TAINT_PREFIX = "[SHOPMOD-TAINT]";

    private static final long JOIN_TIMEOUT_MS = 30_000L;

    private enum TrustState { PENDING, TRUSTED, TAINTED }
    private record PlayerEntry(TrustState state, long joinedAt, String seedHash) {}

    private static final Map<String, PlayerEntry> players = new ConcurrentHashMap<>();

    private TrustManager() {}

    // ── Публичный API ─────────────────────────────────────────────────────────

    public static void onPlayerJoin(String playerName) {
        if (isSelf(playerName)) return;
        players.put(playerName, new PlayerEntry(TrustState.PENDING, System.currentTimeMillis(), null));
    }

    public static void onPlayerLeave(String playerName) {
        players.remove(playerName);
    }

    /**
     * Обрабатывает входящее системное сообщение ShopMod.
     * @return true если сообщение системное (можно не показывать в чат)
     */
    public static boolean onIncomingMessage(String playerName, String message) {
        if (message == null) return false;

        // ── [SHOPMOD-SEED] — обмен seed-хэшем через кнопку ──────────────────
        if (message.startsWith(SeedRegistry.SEED_MSG_PREFIX)) {
            return SeedRegistry.onSeedMessage(playerName, message);
        }

        // ── [SHOPMOD-JOIN] — рукопожатие при входе ──────────────────────────
        if (message.startsWith(JOIN_PREFIX)) {
            if (HmacUtil.verify(message)) {
                // Извлекаем player= из сообщения (надёжнее чем полагаться на пакетный sender)
                String nick = SeedRegistry.extractField(message, "player");
                if (nick == null || nick.isBlank()) nick = playerName;

                // Извлекаем seedhash=
                String seedHash = SeedRegistry.extractField(message, "seedhash");

                // Проверяем в реестре
                SeedRegistry.SeedCheckResult result = SeedRegistry.checkJoin(nick, seedHash);
                handleSeedCheckResult(nick, result, seedHash);
            }
            return true;
        }

        // ── [SHOPMOD-TAINT] ──────────────────────────────────────────────────
        if (message.startsWith(TAINT_PREFIX)) {
            if (HmacUtil.verify(message)) {
                String nick = SeedRegistry.extractField(message, "player");
                if (nick == null || nick.isBlank()) nick = playerName;
                markTainted(nick);
                notifyTaint(nick, message);
            }
            return true;
        }

        return false;
    }

    public static boolean canTransactWith(String playerName) {
        if (isSelf(playerName)) return true;
        PlayerEntry entry = players.get(playerName);
        if (entry == null) return false;
        return switch (entry.state()) {
            case TRUSTED -> true;
            case TAINTED -> false;
            case PENDING -> {
                long elapsed = System.currentTimeMillis() - entry.joinedAt();
                yield elapsed < JOIN_TIMEOUT_MS;
            }
        };
    }

    public static void tickCheck() {
        long now = System.currentTimeMillis();
        for (Map.Entry<String, PlayerEntry> kv : players.entrySet()) {
            PlayerEntry e = kv.getValue();
            if (e.state() == TrustState.PENDING && (now - e.joinedAt()) >= JOIN_TIMEOUT_MS) {
                notifyNoJoin(kv.getKey());
            }
        }
    }

    public static String trustStateString(String playerName) {
        PlayerEntry e = players.get(playerName);
        if (e == null) return "UNKNOWN";
        return switch (e.state()) {
            case TRUSTED -> "TRUSTED" + (e.seedHash() != null ? " [" + e.seedHash().substring(0, 8) + "]" : "");
            case TAINTED -> "TAINTED";
            case PENDING -> {
                long elapsed = System.currentTimeMillis() - e.joinedAt();
                yield elapsed < JOIN_TIMEOUT_MS
                    ? "PENDING (" + (JOIN_TIMEOUT_MS - elapsed) / 1000 + "s)"
                    : "NO-JOIN";
            }
        };
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private static void handleSeedCheckResult(String nick, SeedRegistry.SeedCheckResult result, String seedHash) {
        switch (result) {
            case MATCH -> {
                markTrusted(nick, seedHash);
                // Тихо — всё нормально
            }
            case NEW -> {
                markTrusted(nick, seedHash);
                sendLocalMsg("§a[ShopMod] ✔ §e" + nick
                    + "§a — первый контакт, seed-хэш сохранён §7[" + seedHash.substring(0, 8) + "...]");
            }
            case SEED_CHANGED -> {
                // Это главное — seed сменился → помечаем как TAINTED
                markTainted(nick);
                sendLocalMsg("§c[ShopMod] ⚠ §e" + nick
                    + "§c переустановил мод! Seed-хэш изменился.");
                sendLocalMsg("§c  Новый хэш: §f" + seedHash.substring(0, 16) + "..."
                    + "§c — транзакции с этим игроком заблокированы.");
            }
            case NO_HASH -> {
                markTrusted(nick, null);
                sendLocalMsg("§e[ShopMod] ⚠ §e" + nick
                    + "§e не прислал seed-хэш (старая версия мода?).");
            }
        }
    }

    private static void markTrusted(String name, String seedHash) {
        players.compute(name, (k, old) ->
            new PlayerEntry(TrustState.TRUSTED,
                old != null ? old.joinedAt() : System.currentTimeMillis(),
                seedHash));
    }

    private static void markTainted(String name) {
        players.compute(name, (k, old) ->
            new PlayerEntry(TrustState.TAINTED,
                old != null ? old.joinedAt() : System.currentTimeMillis(),
                old != null ? old.seedHash() : null));
    }

    private static boolean isSelf(String name) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return false;
        return mc.player.getGameProfile().getName().equalsIgnoreCase(name);
    }

    private static void notifyTaint(String name, String msg) {
        sendLocalMsg("§c[ShopMod] ⚠ TAINT от §e" + name + "§c: "
            + msg.replace(TAINT_PREFIX, "").replace("|", " | "));
    }

    private static void notifyNoJoin(String name) {
        sendLocalMsg("§e[ShopMod] ⚠ §f" + name
            + "§e не прислал JOIN за 30 сек — транзакции заблокированы.");
    }

    private static void sendLocalMsg(String text) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null)
            mc.execute(() -> mc.player.sendMessage(Text.literal(text), false));
    }
}
