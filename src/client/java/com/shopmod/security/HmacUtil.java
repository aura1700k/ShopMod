package com.shopmod.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * Утилита HMAC-SHA256 для подписи системных сообщений.
 *
 * Ключ = SHA-256(pepper || installationSeed).
 * Это значит:
 *   - Знания одного pepper (из декомпиляции) недостаточно — нужен ещё seed.
 *   - При переустановке мода без сохранения .shopmod_seed подписи меняются.
 *   - Ручной ввод [SHOPMOD-JOIN] / [SHOPMOD-TAINT] без знания seed невозможен.
 *
 * Replay-защита:
 *   - Пакет считается валидным только если его ts= не старше REPLAY_WINDOW_MS.
 *   - Каждый принятый ts= кладётся в replayCache (ограниченный по размеру Map).
 *   - Повторный пакет с тем же ts= от того же sender блокируется.
 *   - Ключ кэша = "prefix:ts" — разные типы пакетов не конфликтуют.
 */
public final class HmacUtil {

    // Базовый pepper — публично известен после декомпиляции, но один он не даёт ключ
    private static final byte[] PEPPER = "shopmod-hmac-pepper-v1".getBytes(StandardCharsets.UTF_8);

    /** Максимальный возраст пакета: 60 секунд. */
    private static final long REPLAY_WINDOW_MS = 60_000L;

    /**
     * Кэш виденных ts= значений.
     * Ключ: "<первые 14 символов payload (тип пакета)>:<ts>".
     * Значение: время получения пакета (для очистки старых записей).
     *
     * LinkedHashMap с removeEldestEntry — не растёт бесконечно.
     * Размер 512 достаточен: при 60-сек окне и 1 пакете/сек это 60 записей
     * с большим запасом на burst.
     */
    private static final java.util.Map<String, Long> replayCache =
        java.util.Collections.synchronizedMap(
            new java.util.LinkedHashMap<String, Long>(128, 0.75f, false) {
                @Override
                protected boolean removeEldestEntry(java.util.Map.Entry<String, Long> eldest) {
                    // Удаляем если запись старше окна ИЛИ кэш переполнен
                    return size() > 512
                        || (System.currentTimeMillis() - eldest.getValue()) > REPLAY_WINDOW_MS * 2;
                }
            }
        );

    // Итоговый ключ: вычисляется один раз из pepper + seed установки
    private static volatile byte[] DERIVED_KEY = null;

    private HmacUtil() {}

    // ── Ключ ─────────────────────────────────────────────────────────────────

    private static byte[] key() {
        if (DERIVED_KEY != null) return DERIVED_KEY;
        synchronized (HmacUtil.class) {
            if (DERIVED_KEY != null) return DERIVED_KEY;
            byte[] seed = InstallationSeed.get();
            byte[] combined = Arrays.copyOf(PEPPER, PEPPER.length + seed.length);
            System.arraycopy(seed, 0, combined, PEPPER.length, seed.length);
            try {
                java.security.MessageDigest sha = java.security.MessageDigest.getInstance("SHA-256");
                DERIVED_KEY = sha.digest(combined);
            } catch (Exception e) {
                DERIVED_KEY = combined;
            }
        }
        return DERIVED_KEY;
    }

    // ── Публичный API ─────────────────────────────────────────────────────────

    /**
     * HMAC-SHA256 от payload с производным ключом (pepper + seed установки).
     * Возвращает первые 8 hex-символов (32 бит — достаточно против ручного ввода).
     */
    public static String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key(), "HmacSHA256"));
            byte[] raw = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(raw).substring(0, 8);
        } catch (Exception e) {
            return "00000000";
        }
    }

    /**
     * Проверяет HMAC-токен и защищает от replay-атак.
     *
     * Формат: "... ts=<unix_ms> ...||hmac:<8hex>"
     *
     * Проверки по порядку:
     *   1. HMAC совпадает
     *   2. ts= присутствует и парсится
     *   3. ts не старше REPLAY_WINDOW_MS (60 сек)
     *   4. ts не из будущего (допуск 5 сек на рассинхрон часов)
     *   5. пакет с этим ts ещё не встречался (защита от повторной отправки)
     */
    public static boolean verify(String message) {
        int idx = message.lastIndexOf("||hmac:");
        if (idx < 0) return false;

        String payload  = message.substring(0, idx);
        String received = message.substring(idx + 7);

        // 1. HMAC
        if (!sign(payload).equalsIgnoreCase(received)) return false;

        // 2. ts=
        Long ts = extractTs(payload);
        if (ts == null) {
            org.slf4j.LoggerFactory.getLogger("shopmod")
                .warn("ShopMod: пакет без ts= отклонён: [{}...]",
                    message.substring(0, Math.min(message.length(), 40)));
            return false;
        }

        long now = System.currentTimeMillis();

        // 3. Не старше 60 сек
        if (now - ts > REPLAY_WINDOW_MS) {
            org.slf4j.LoggerFactory.getLogger("shopmod")
                .warn("ShopMod: устаревший пакет отклонён (возраст {}ms): [{}...]",
                    now - ts, message.substring(0, Math.min(message.length(), 40)));
            return false;
        }

        // 4. Не из будущего (допуск 5 сек)
        if (ts - now > 5_000L) {
            org.slf4j.LoggerFactory.getLogger("shopmod")
                .warn("ShopMod: пакет из будущего отклонён (ts={}, now={})", ts, now);
            return false;
        }

        // 5. Replay: ключ = первые 20 символов payload (содержат тип [SHOPMOD-XXX]) + ts
        String cacheKey = payload.substring(0, Math.min(payload.length(), 20)) + ":" + ts;
        Long seenAt = replayCache.put(cacheKey, now);
        if (seenAt != null) {
            org.slf4j.LoggerFactory.getLogger("shopmod")
                .warn("ShopMod: replay! пакет с ts={} уже получен {}ms назад: [{}...]",
                    ts, now - seenAt, message.substring(0, Math.min(message.length(), 40)));
            return false;
        }

        return true;
    }

    /**
     * Извлекает значение поля ts= из payload.
     * ts= всегда последнее поле перед ||hmac:, поэтому ищем с конца.
     */
    private static Long extractTs(String payload) {
        String marker = " ts=";
        int idx = payload.lastIndexOf(marker);
        if (idx < 0) return null;
        String raw = payload.substring(idx + marker.length()).trim();
        // На случай если ts= вплотную к следующему полю или ||hmac:
        int space = raw.indexOf(' ');
        int hmac  = raw.indexOf("||hmac:");
        int end   = -1;
        if (space >= 0) end = space;
        if (hmac >= 0 && (end < 0 || hmac < end)) end = hmac;
        if (end >= 0) raw = raw.substring(0, end);
        try {
            return Long.parseLong(raw);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Добавляет токен к payload: "payload||hmac:XXXXXXXX" */
    public static String attach(String payload) {
        return payload + "||hmac:" + sign(payload);
    }
}
