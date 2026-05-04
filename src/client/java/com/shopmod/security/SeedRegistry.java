package com.shopmod.security;

import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Распределённый реестр seed-хэшей игроков группы.
 *
 * Каждый игрок хранит у себя MAP { ник → SHA-256(seed) } для всех,
 * с кем обменялся через кнопку "Поделиться seed".
 *
 * Когда приходит [SHOPMOD-JOIN] с полем seedhash=..., мы проверяем:
 *   - Есть ли этот игрок в реестре?
 *   - Совпадает ли seedhash с сохранённым?
 *   → НЕТ  → игрок переустановил мод (seed сменился) → SEED_CHANGED
 *   → ДА   → всё штатно → TRUSTED
 *   → нет в реестре → первый контакт, сохраняем → NEW
 *
 * Реестр персистируется в peer_seeds.json (отдельно от shopmod.json,
 * чтобы случайная очистка конфига не уничтожила историю доверия).
 */
public final class SeedRegistry {

    public static final String SEED_MSG_PREFIX = "[SHOPMOD-SEED]";

    /**
     * Флаг-разрешение для SeedBlockMixin.
     * Выставляется в true только на время вызова sendAll(),
     * чтобы миксин пропустил системные сообщения от кнопки GUI.
     * volatile — отправка идёт из render-потока, читается из сетевого.
     */
    public static volatile boolean SYSTEM_SEND_ALLOWED = false;

    /**
     * Флаг-разрешение для ALLOW_COMMAND в ShopModClient.
     * Выставляется в true только на время отправки команды самим магазином
     * (покупка/продажа/банкомат), чтобы /give и /clear не помечались как TAINT.
     * volatile — всё происходит в render-потоке Minecraft, поэтому гонок нет.
     */
    public static volatile boolean SHOP_COMMAND_ALLOWED = false;

    /** Результат проверки JOIN-сообщения */
    public enum SeedCheckResult {
        /** Первый контакт — сохранили хэш, доверяем */
        NEW,
        /** Хэш совпадает с сохранённым — всё хорошо */
        MATCH,
        /** Хэш изменился — игрок переустановил мод */
        SEED_CHANGED,
        /** JOIN не содержал seedhash — старая версия мода */
        NO_HASH
    }

    // nick → sha256hex
    private static final Map<String, String> peerHashes = new ConcurrentHashMap<>();
    private static final Path REGISTRY_PATH = FabricLoader.getInstance()
            .getConfigDir().resolve("shopmod_peers.json");

    private SeedRegistry() {}

    // ── Инициализация ─────────────────────────────────────────────────────────

    public static void load() {
        if (!Files.exists(REGISTRY_PATH)) return;
        try (Reader r = Files.newBufferedReader(REGISTRY_PATH)) {
            JsonObject obj = JsonParser.parseReader(r).getAsJsonObject();
            peerHashes.clear();
            for (Map.Entry<String, JsonElement> e : obj.entrySet()) {
                String hash = e.getValue().getAsString();
                if (isValidHash(hash)) peerHashes.put(e.getKey(), hash);
            }
            LoggerFactory.getLogger("shopmod").info(
                "ShopMod: реестр seed-хэшей загружен ({} игроков)", peerHashes.size());
        } catch (Exception e) {
            LoggerFactory.getLogger("shopmod").warn("ShopMod: не удалось загрузить peer registry", e);
        }
    }

    public static void save() {
        JsonObject obj = new JsonObject();
        peerHashes.forEach(obj::addProperty);
        try (Writer w = Files.newBufferedWriter(REGISTRY_PATH, StandardCharsets.UTF_8)) {
            new GsonBuilder().setPrettyPrinting().create().toJson(obj, w);
        } catch (IOException e) {
            LoggerFactory.getLogger("shopmod").error("ShopMod: не удалось сохранить peer registry", e);
        }
    }

    // ── Публичный API ─────────────────────────────────────────────────────────

    /**
     * Проверить seedhash из входящего JOIN.
     * Вызывается из TrustManager при обработке [SHOPMOD-JOIN].
     *
     * @param playerName ник отправителя
     * @param seedHash   значение поля seedhash= из JOIN (может быть null)
     */
    public static SeedCheckResult checkJoin(String playerName, String seedHash) {
        if (seedHash == null || seedHash.isBlank() || !isValidHash(seedHash)) {
            return SeedCheckResult.NO_HASH;
        }
        String known = peerHashes.get(playerName);
        if (known == null) {
            // Первый контакт — сохраняем
            peerHashes.put(playerName, seedHash);
            save();
            return SeedCheckResult.NEW;
        }
        return known.equalsIgnoreCase(seedHash) ? SeedCheckResult.MATCH : SeedCheckResult.SEED_CHANGED;
    }

    /**
     * Обработать входящее [SHOPMOD-SEED] сообщение (кнопка "Поделиться seed").
     * Сохраняет хэш если HMAC валиден.
     *
     * @param playerName ник отправителя (из пакета чата)
     * @param message    полное сообщение
     * @return true если сообщение системное и обработано
     */
    public static boolean onSeedMessage(String playerName, String message) {
        if (!message.startsWith(SEED_MSG_PREFIX)) return false;
        if (!HmacUtil.verify(message)) {
            LoggerFactory.getLogger("shopmod").warn(
                "ShopMod: [SHOPMOD-SEED] от {} с невалидным HMAC — игнорируем", playerName);
            return true; // всё равно системное, не показываем в чат
        }

        String hash = extractField(message, "hash");
        String nick = extractField(message, "player"); // из payload, не из пакета
        if (nick == null || nick.isBlank()) nick = playerName;

        if (!isValidHash(hash)) {
            LoggerFactory.getLogger("shopmod").warn(
                "ShopMod: [SHOPMOD-SEED] от {} с невалидным hash — игнорируем", playerName);
            return true;
        }

        String existing = peerHashes.get(nick);
        if (existing != null && !existing.equalsIgnoreCase(hash)) {
            // Хэш уже есть, но другой — seed сменился между сессиями (без JOIN)
            notifyHashChanged(nick, existing, hash);
        }
        peerHashes.put(nick, hash);
        save();
        LoggerFactory.getLogger("shopmod").info(
            "ShopMod: seed-хэш сохранён для {} ({}...)", nick, hash.substring(0, 8));
        return true;
    }

    /**
     * Формирует сообщение [SHOPMOD-SEED] с HMAC для отправки в чат.
     * Вызывается из GUI кнопки "Поделиться seed".
     */
    public static String buildSeedMessage(String playerName) {
        String hash = InstallationSeed.seedHash();
        long ts = System.currentTimeMillis();
        String payload = SEED_MSG_PREFIX
                + " player=" + playerName
                + " hash="   + hash
                + " ts="     + ts;
        return HmacUtil.attach(payload);
    }

    /**
     * Gossip-отправка: шлёт [SHOPMOD-SEED] за себя + за каждого известного пира.
     * Получатель обрабатывает каждое сообщение независимо через onSeedMessage().
     *
     * Алгоритм:
     *   1. Выставляем SYSTEM_SEND_ALLOWED = true — SeedBlockMixin пропустит сообщения.
     *   2. Отправляем своё сообщение (player=<selfName>, hash=<наш seedHash>).
     *   3. Для каждого пира в реестре отправляем его сохранённый хэш от своего имени
     *      (player=<nick>, hash=<известный нам хэш пира>).
     *   4. Сбрасываем флаг.
     *
     * @param selfName ник текущего игрока
     */
    public static void sendAll(String selfName) {
        net.minecraft.client.network.ClientPlayNetworkHandler handler =
            net.minecraft.client.MinecraftClient.getInstance().getNetworkHandler();
        if (handler == null) return;

        SYSTEM_SEND_ALLOWED = true;
        try {
            // Своё сообщение
            handler.sendChatMessage(buildSeedMessage(selfName));

            // Сообщения за каждого известного пира
            long ts = System.currentTimeMillis();
            for (Map.Entry<String, String> entry : peerHashes.entrySet()) {
                String nick = entry.getKey();
                String hash = entry.getValue();
                String payload = SEED_MSG_PREFIX
                        + " player=" + nick
                        + " hash="   + hash
                        + " ts="     + ts;
                handler.sendChatMessage(HmacUtil.attach(payload));
            }
        } finally {
            SYSTEM_SEND_ALLOWED = false;
        }
    }

    /** Есть ли сохранённый хэш для игрока */
    public static boolean hasHash(String playerName) {
        return peerHashes.containsKey(playerName);
    }

    /** Короткий хэш (первые 8 символов) для отображения в GUI */
    public static String shortHash(String playerName) {
        String h = peerHashes.get(playerName);
        return h != null ? h.substring(0, 8) : "——";
    }

    /** Количество игроков в реестре */
    public static int size() { return peerHashes.size(); }

    /** Снапшот реестра для GUI (ник → короткий хэш) */
    public static Map<String, String> snapshotShort() {
        Map<String, String> out = new LinkedHashMap<>();
        peerHashes.forEach((k, v) -> out.put(k, v.substring(0, 8)));
        return Collections.unmodifiableMap(out);
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    /** Извлечь значение поля "key=value" из сообщения */
    static String extractField(String message, String key) {
        String marker = " " + key + "=";
        int idx = message.indexOf(marker);
        if (idx < 0) return null;
        int start = idx + marker.length();
        int end = message.indexOf(' ', start);
        // Стоп также на ||hmac:
        int hmacIdx = message.indexOf("||hmac:", start);
        if (hmacIdx >= 0 && (end < 0 || hmacIdx < end)) end = hmacIdx;
        return end < 0 ? message.substring(start) : message.substring(start, end);
    }

    /** SHA-256 hex = ровно 64 hex-символа */
    private static boolean isValidHash(String h) {
        return h != null && h.length() == 64 && h.matches("[0-9a-fA-F]+");
    }

    private static void notifyHashChanged(String nick, String oldHash, String newHash) {
        net.minecraft.client.MinecraftClient mc = net.minecraft.client.MinecraftClient.getInstance();
        if (mc.player != null) {
            mc.execute(() -> mc.player.sendMessage(
                net.minecraft.text.Text.literal(
                    "§c[ShopMod] ⚠ Seed-хэш §e" + nick + "§c изменился!\n"
                    + "§7  Было: §f" + oldHash.substring(0, 16) + "...\n"
                    + "§7  Стало: §f" + newHash.substring(0, 16) + "...\n"
                    + "§c  Возможно, игрок переустановил мод."), false));
        }
    }
}
