package com.shopmod.security;

import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * Генерирует и хранит случайный 256-битный seed, привязанный к установке мода.
 *
 * Основной файл:   config/.shopmod_seed    (открытый hex — источник правды)
 * Резервная копия: config/minecraft_1.21.1 (зашифрованный бинарник с мусором)
 *
 * При старте мод сверяет оба файла. Если копия не совпадает или отсутствует —
 * игрок удалял/восстанавливал конфиг → автоматический TAINT.
 *
 * Шифрование копии: AES-256-GCM, ключ = SHA-256(BACKUP_PEPPER + seed).
 * Формат: IV(12) + мусор(64) + AES-GCM(seed)(48) + мусор(64) = 188 байт.
 * Выглядит как случайный бинарник — игрок не знает что это и где это.
 */
public final class InstallationSeed {

    private static final String SEED_FILENAME   = ".shopmod_seed";
    private static final String BACKUP_FILENAME = "minecraft_1.21.1";
    private static final int    SEED_BYTES      = 32;
    private static final int    GCM_IV_LEN      = 12;
    private static final int    GCM_TAG_BITS    = 128;
    private static final int    JUNK_BYTES      = 64;

    private static final byte[] BACKUP_PEPPER =
            "shopmod-backup-pepper-v1".getBytes(StandardCharsets.UTF_8);

    private static byte[] cachedSeed = null;

    private InstallationSeed() {}

    // =========================================================================
    // ПУБЛИЧНЫЙ API
    // =========================================================================

    /**
     * Возвращает seed. Если файл ещё не создан (до визарда) — возвращает нули.
     */
    public static byte[] get() {
        if (cachedSeed != null) return cachedSeed;
        Path seedPath = configDir().resolve(SEED_FILENAME);
        if (Files.exists(seedPath)) {
            try {
                String hex = Files.readString(seedPath, StandardCharsets.UTF_8).trim();
                byte[] loaded = HexFormat.of().parseHex(hex);
                if (loaded.length == SEED_BYTES) {
                    cachedSeed = loaded;
                    return cachedSeed;
                }
                log().warn("ShopMod: seed file corrupted, regenerating.");
            } catch (Exception e) {
                log().warn("ShopMod: cannot read seed file, regenerating.", e);
            }
            return regenerate();
        }
        log().info("ShopMod: seed not yet created (wizard pending).");
        return new byte[SEED_BYTES]; // нули, не кэшируем
    }

    /**
     * Проверяет совпадение основного seed и резервной копии.
     * Вызывается при JOIN перед отправкой [SHOPMOD-JOIN].
     * @return true = всё ок, false = копия отсутствует или не совпадает → TAINT
     */
    public static boolean verifyBackup() {
        byte[] seed = get();
        if (isZero(seed)) return true; // визард ещё не пройден
        Path backupPath = configDir().resolve(BACKUP_FILENAME);
        if (!Files.exists(backupPath)) {
            log().warn("ShopMod: backup file missing — possible config restore attack!");
            return false;
        }
        try {
            byte[] decrypted = decryptBackup(backupPath, seed);
            if (decrypted == null || !Arrays.equals(seed, decrypted)) {
                log().warn("ShopMod: backup seed MISMATCH — possible config restore attack!");
                return false;
            }
            return true;
        } catch (Exception e) {
            log().warn("ShopMod: backup verify error.", e);
            return false;
        }
    }

    /**
     * Создаёт seed при первом выборе режима в визарде.
     * Записывает оба файла. Если оба уже есть — ничего не делает.
     */
    public static void bakeWithMode(String modeName) {
        Path seedPath   = configDir().resolve(SEED_FILENAME);
        Path backupPath = configDir().resolve(BACKUP_FILENAME);
        if (Files.exists(seedPath) && Files.exists(backupPath)) {
            get();
            return;
        }
        byte[] fresh = new byte[SEED_BYTES];
        new SecureRandom().nextBytes(fresh);
        writeSeed(seedPath, fresh);
        writeBackup(backupPath, fresh);
        cachedSeed = fresh;
        log().info("ShopMod: seed baked with mode={}.", modeName);
    }

    /**
     * SHA-256(seed + shopMode). Режим вшит — подмена mode в конфиге меняет хеш.
     */
    public static String seedHash() {
        try {
            String mode = com.shopmod.config.ShopConfig.SHOP_MODE != null
                    ? com.shopmod.config.ShopConfig.SHOP_MODE.name() : "UNSET";
            java.security.MessageDigest sha = java.security.MessageDigest.getInstance("SHA-256");
            sha.update(get());
            sha.update(mode.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(sha.digest());
        } catch (Exception e) {
            return "0".repeat(64);
        }
    }

    /** Hex первых 4 байт seed — для логов/отладки. */
    public static String shortId() {
        return HexFormat.of().formatHex(get(), 0, 4);
    }

    // =========================================================================
    // ВНУТРЕННИЕ МЕТОДЫ
    // =========================================================================

    private static byte[] regenerate() {
        byte[] fresh = new byte[SEED_BYTES];
        new SecureRandom().nextBytes(fresh);
        writeSeed(configDir().resolve(SEED_FILENAME), fresh);
        writeBackup(configDir().resolve(BACKUP_FILENAME), fresh);
        cachedSeed = fresh;
        log().info("ShopMod: seed regenerated (was corrupted).");
        return cachedSeed;
    }

    private static void writeSeed(Path path, byte[] seed) {
        try {
            Files.writeString(path, HexFormat.of().formatHex(seed), StandardCharsets.UTF_8);
        } catch (IOException e) {
            log().error("Cannot write seed file!", e);
        }
    }

    /**
     * Формат резервной копии (188 байт):
     *   IV(12) | junk(64) | AES-GCM(seed)(32+16=48) | junk(64)
     *
     * Ключ шифрования = SHA-256(BACKUP_PEPPER + seed).
     * junk — случайные байты, маскируют структуру файла.
     */
    private static void writeBackup(Path path, byte[] seed) {
        try {
            SecureRandom rng = new SecureRandom();
            byte[] iv         = new byte[GCM_IV_LEN];
            byte[] junkBefore = new byte[JUNK_BYTES];
            byte[] junkAfter  = new byte[JUNK_BYTES];
            rng.nextBytes(iv);
            rng.nextBytes(junkBefore);
            rng.nextBytes(junkAfter);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(backupKey(seed), "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(seed); // 48 байт (32 + 16 GCM-тег)

            ByteArrayOutputStream out = new ByteArrayOutputStream(
                    GCM_IV_LEN + JUNK_BYTES + encrypted.length + JUNK_BYTES);
            out.write(iv);
            out.write(junkBefore);
            out.write(encrypted);
            out.write(junkAfter);
            Files.write(path, out.toByteArray());
        } catch (Exception e) {
            log().error("Cannot write backup seed file!", e);
        }
    }

    private static byte[] decryptBackup(Path path, byte[] seed) {
        try {
            byte[] raw = Files.readAllBytes(path);
            int minLen = GCM_IV_LEN + JUNK_BYTES + SEED_BYTES + 16 + JUNK_BYTES;
            if (raw.length < minLen) return null;

            byte[] iv        = Arrays.copyOfRange(raw, 0, GCM_IV_LEN);
            byte[] encrypted = Arrays.copyOfRange(raw,
                    GCM_IV_LEN + JUNK_BYTES,
                    GCM_IV_LEN + JUNK_BYTES + SEED_BYTES + 16);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(backupKey(seed), "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            return cipher.doFinal(encrypted);
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] backupKey(byte[] seed) throws Exception {
        java.security.MessageDigest sha = java.security.MessageDigest.getInstance("SHA-256");
        sha.update(BACKUP_PEPPER);
        sha.update(seed);
        return sha.digest();
    }

    private static boolean isZero(byte[] arr) {
        for (byte b : arr) if (b != 0) return false;
        return true;
    }

    private static Path configDir() {
        return FabricLoader.getInstance().getConfigDir();
    }

    private static org.slf4j.Logger log() {
        return LoggerFactory.getLogger("shopmod");
    }
}
