package com.shopmod.config;

import com.google.gson.*;
import net.fabricmc.loader.api.FabricLoader;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.*;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;

public class ShopConfig {

    // ── Флаг первого запуска ─────────────────────────────────────────────────
    public static boolean FIRST_RUN = true;

    // ── Режим магазина (зашивается в сид при первом выборе, неизменяем) ─────
    public enum ShopMode { EASY, NORMAL, HARDCORE }
    public static ShopMode SHOP_MODE = null; // null = ещё не выбран

    // ── Дневные лимиты продаж (только для HARDCORE) ──────────────────────────
    // Ключ: itemId, значение: сколько продано сегодня
    public static final Map<String, Integer> DAILY_SELL_COUNT = new HashMap<>();
    // Игровой день когда последний раз сбрасывался счётчик (ticks / 24000)
    public static long LAST_SELL_DAY = -1L;
    // Лимит продаж одного ресурса в игровой день
    public static final int HARDCORE_DAILY_LIMIT = 64;

    // ── Основные настройки ───────────────────────────────────────────────────
    public static boolean DYNAMIC_PRICES       = true;
    public static double  DYNAMIC_PRICE_FACTOR = 0.05;
    public static int     DYNAMIC_SAMPLE_SIZE  = 10;
    public static long    VIRTUAL_BALANCE      = 0L;

    public static boolean SPEND_PHYSICAL   = true;
    public static boolean RECEIVE_PHYSICAL = true;

    // ── Комиссия ─────────────────────────────────────────────────────────────
    public static boolean COMMISSION_ENABLED = false;
    public static double  COMMISSION_PERCENT = 5.0;

    // ── Язык: "ru" или "en" ──────────────────────────────────────────────────
    public static String LANGUAGE = "ru";

    // ── Логи в чат ───────────────────────────────────────────────────────────
    public static boolean CHAT_SPAM_ENABLED = true;

    // ── Данные предметов ─────────────────────────────────────────────────────
    // Цены только из хардкода — конфиг не может их изменить (Вариант А)
    private static final Map<String, Long>    ITEM_PRICES       = new LinkedHashMap<>();
    private static final Map<String, Double>  SELL_MULTIPLIERS  = new LinkedHashMap<>();
    public  static final Map<String, Integer> TRANSACTION_COUNT = new HashMap<>();

    // ── Статистика популярности (для сортировки) ─────────────────────────────
    public static final Map<String, Long> SELL_STATS = new LinkedHashMap<>();

    // ── История банкомата (последние 20 операций) ─────────────────────────────
    public static final java.util.ArrayDeque<String> ATM_HISTORY = new java.util.ArrayDeque<>();
    private static final int ATM_HISTORY_MAX = 20;

    public static void addAtmHistory(String entry) {
        ATM_HISTORY.addFirst(entry);
        while (ATM_HISTORY.size() > ATM_HISTORY_MAX) ATM_HISTORY.removeLast();
        save();
    }

    public static void recordSale(String itemId, int amount) {
        SELL_STATS.merge(itemId, (long) amount, Long::sum);
        save();
    }

    private static final Path CONFIG_PATH = FabricLoader.getInstance()
            .getConfigDir().resolve("shopmod.json");

    // ── I18n helper ──────────────────────────────────────────────────────────
    public static String t(String ru, String en) {
        return "en".equals(LANGUAGE) ? en : ru;
    }

    // ── Шифрование виртуального баланса (AES-256-GCM) ───────────────────────
    //
    // Ключ = installation seed (32 байта = AES-256).
    // Без seed файла расшифровать баланс невозможно — правка JSON бессмысленна.
    // Формат поля: Base64(IV[12] || ciphertext || GCM-tag[16])
    //
    private static final int GCM_IV_LEN  = 12;
    private static final int GCM_TAG_LEN = 128; // бит

    private static String encryptBalance(long balance) {
        try {
            byte[] key  = com.shopmod.security.InstallationSeed.get(); // 32 байта
            byte[] iv   = new byte[GCM_IV_LEN];
            new SecureRandom().nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(GCM_TAG_LEN, iv));
            byte[] plain  = Long.toString(balance).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            byte[] enc    = cipher.doFinal(plain);
            // Упаковываем IV + ciphertext в один Base64-блок
            byte[] out = new byte[iv.length + enc.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(enc, 0, out, iv.length, enc.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            LoggerFactory.getLogger("shopmod").error("encryptBalance failed", e);
            return "ERROR";
        }
    }

    private static long decryptBalance(String blob) {
        try {
            byte[] raw = Base64.getDecoder().decode(blob);
            if (raw.length <= GCM_IV_LEN) return 0L;
            byte[] key = com.shopmod.security.InstallationSeed.get();
            byte[] iv  = java.util.Arrays.copyOf(raw, GCM_IV_LEN);
            byte[] enc = java.util.Arrays.copyOfRange(raw, GCM_IV_LEN, raw.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(GCM_TAG_LEN, iv));
            byte[] plain = cipher.doFinal(enc);
            return Long.parseLong(new String(plain, java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            // Неверный seed (переустановка без файла) или tamper — сбрасываем в 0
            LoggerFactory.getLogger("shopmod").warn(
                "ShopMod: virtual_balance не расшифрован (seed изменился или tamper). Сброс в 0.");
            return 0L;
        }
    }

    // ── Загрузка ─────────────────────────────────────────────────────────────
    public static void load() {
        // Вариант А: цены только в хардкоде — всегда инициализируем из createDefault()
        if (ITEM_PRICES.isEmpty()) createDefault();
        if (!Files.exists(CONFIG_PATH)) { createDefault(); return; }
        try (Reader reader = Files.newBufferedReader(CONFIG_PATH)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            FIRST_RUN            = !root.has("first_run")            || root.get("first_run").getAsBoolean();
            // Режим магазина — читаем, но не даём изменить если уже выбран
            if (root.has("shop_mode")) {
                try { SHOP_MODE = ShopMode.valueOf(root.get("shop_mode").getAsString()); }
                catch (IllegalArgumentException ignored) { SHOP_MODE = null; }
            } else {
                SHOP_MODE = null;
            }
            // Дневные лимиты продаж (хардкор)
            DAILY_SELL_COUNT.clear();
            if (root.has("daily_sell_count")) {
                for (Map.Entry<String, JsonElement> e : root.get("daily_sell_count").getAsJsonObject().entrySet())
                    DAILY_SELL_COUNT.put(e.getKey(), e.getValue().getAsInt());
            }
            LAST_SELL_DAY = root.has("last_sell_day") ? root.get("last_sell_day").getAsLong() : -1L;
            DYNAMIC_PRICES       = !root.has("dynamic_prices")       || root.get("dynamic_prices").getAsBoolean();
            DYNAMIC_PRICE_FACTOR = root.has("dynamic_price_factor")  ? root.get("dynamic_price_factor").getAsDouble() : 0.05;
            DYNAMIC_SAMPLE_SIZE  = root.has("dynamic_sample_size")   ? root.get("dynamic_sample_size").getAsInt()     : 10;
            // Баланс хранится в зашифрованном виде (AES-256-GCM + installation seed)
            VIRTUAL_BALANCE = root.has("virtual_balance")
                    ? decryptBalance(root.get("virtual_balance").getAsString())
                    : 0L;
            SPEND_PHYSICAL       = !root.has("spend_physical")       || root.get("spend_physical").getAsBoolean();
            RECEIVE_PHYSICAL     = !root.has("receive_physical")     || root.get("receive_physical").getAsBoolean();
            COMMISSION_ENABLED   = root.has("commission_enabled")    && root.get("commission_enabled").getAsBoolean();
            COMMISSION_PERCENT   = root.has("commission_percent")    ? root.get("commission_percent").getAsDouble()   : 5.0;
            LANGUAGE             = root.has("language")              ? root.get("language").getAsString()             : "ru";
            CHAT_SPAM_ENABLED    = !root.has("chat_spam_enabled")    || root.get("chat_spam_enabled").getAsBoolean();

            // Принудительно восстанавливаем параметры режима — конфиг не может их переопределить.
            // Игрок не может обойти ограничения режима правкой shopmod.json вручную.
            enforceModeConstraints();

            // Вариант А: цены не читаются из конфига — только хардкод из createDefault()
            // Секция "items" в shopmod.json игнорируется полностью
            SELL_STATS.clear();
            if (root.has("sell_stats")) {
                for (Map.Entry<String, JsonElement> e : root.get("sell_stats").getAsJsonObject().entrySet())
                    SELL_STATS.put(e.getKey(), e.getValue().getAsLong());
            }
            ATM_HISTORY.clear();
            if (root.has("atm_history")) {
                for (JsonElement e : root.get("atm_history").getAsJsonArray())
                    ATM_HISTORY.addLast(e.getAsString());
            }
            if (root.has("transactions")) {
                for (Map.Entry<String, JsonElement> e : root.get("transactions").getAsJsonObject().entrySet())
                    TRANSACTION_COUNT.put(e.getKey(), e.getValue().getAsInt());
            }
        } catch (Exception e) {
            LoggerFactory.getLogger("shopmod").error("Failed to load config", e);
            createDefault();
        }
    }

    // ── Сохранение ───────────────────────────────────────────────────────────
    public static void save() {
        JsonObject root = new JsonObject();
        root.addProperty("first_run",            FIRST_RUN);
        if (SHOP_MODE != null) root.addProperty("shop_mode", SHOP_MODE.name());
        root.addProperty("dynamic_prices",       DYNAMIC_PRICES);
        root.addProperty("dynamic_price_factor", DYNAMIC_PRICE_FACTOR);
        root.addProperty("dynamic_sample_size",  DYNAMIC_SAMPLE_SIZE);
        // Сохраняем баланс зашифрованным — без seed файла не читается
        root.addProperty("virtual_balance", encryptBalance(VIRTUAL_BALANCE));
        root.addProperty("spend_physical",       SPEND_PHYSICAL);
        root.addProperty("receive_physical",     RECEIVE_PHYSICAL);
        root.addProperty("commission_enabled",   COMMISSION_ENABLED);
        root.addProperty("commission_percent",   COMMISSION_PERCENT);
        root.addProperty("language",             LANGUAGE);
        root.addProperty("chat_spam_enabled",    CHAT_SPAM_ENABLED);
        // Вариант А: секция "items" не сохраняется — цены только в коде
        JsonObject txObj = new JsonObject();
        TRANSACTION_COUNT.forEach(txObj::addProperty);
        root.add("transactions", txObj);
        JsonObject dailyObj = new JsonObject();
        DAILY_SELL_COUNT.forEach(dailyObj::addProperty);
        root.add("daily_sell_count", dailyObj);
        root.addProperty("last_sell_day", LAST_SELL_DAY);
        JsonObject statsObj = new JsonObject();
        SELL_STATS.forEach(statsObj::addProperty);
        root.add("sell_stats", statsObj);
        com.google.gson.JsonArray histArr = new com.google.gson.JsonArray();
        ATM_HISTORY.forEach(histArr::add);
        root.add("atm_history", histArr);
        try (Writer writer = Files.newBufferedWriter(CONFIG_PATH)) {
            new GsonBuilder().setPrettyPrinting().create().toJson(root, writer);
        } catch (IOException e) {
            LoggerFactory.getLogger("shopmod").error("Failed to save config", e);
        }
    }

    // ── Цены ─────────────────────────────────────────────────────────────────
    public static long getBuyPrice(String itemId) {
        long raw = getBuyPriceRaw(itemId);
        if (!COMMISSION_ENABLED) return raw;
        return Math.max(1, Math.round(raw * (1.0 + COMMISSION_PERCENT / 100.0)));
    }

    public static long getBuyPriceRaw(String itemId) {
        long base = ITEM_PRICES.getOrDefault(itemId, 10L);
        // Динамические цены только в хардкоре или если явно включены
        boolean useDynamic = DYNAMIC_PRICES && (SHOP_MODE == ShopMode.HARDCORE || SHOP_MODE == null);
        if (!useDynamic) return base;
        int tx = TRANSACTION_COUNT.getOrDefault(itemId, 0);
        double factor = 1.0 + (tx / (double) DYNAMIC_SAMPLE_SIZE) * DYNAMIC_PRICE_FACTOR;
        factor = Math.max(0.5, Math.min(3.0, factor));
        return Math.max(1, Math.round(base * factor));
    }

    public static long getSellPrice(String itemId) {
        long buyRaw = getBuyPriceRaw(itemId);
        if (SHOP_MODE == ShopMode.EASY) {
            // Лёгкий: разница ровно 20% от цены покупки, минимум 1, округление вверх
            long diff = (long) Math.ceil(buyRaw * 0.20);
            diff = Math.max(1, diff);
            return Math.max(1, buyRaw - diff);
        }
        // NORMAL и HARDCORE: стандартный множитель ~50%
        return Math.max(1, Math.round(buyRaw * SELL_MULTIPLIERS.getOrDefault(itemId, 0.5)));
    }

    /**
     * Проверяет дневной лимит продаж (только HARDCORE).
     * Сбрасывает счётчик если прошёл новый игровой день.
     * @param itemId предмет
     * @param amount сколько хотим продать
     * @param currentDayTime текущее время мира (world.getTimeOfDay())
     * @return сколько можно продать (0 если лимит исчерпан)
     */
    public static int getHardcoreAllowedSellAmount(String itemId, int amount, long currentDayTime) {
        if (SHOP_MODE != ShopMode.HARDCORE) return amount;
        long currentDay = currentDayTime / 24000L;
        if (currentDay != LAST_SELL_DAY) {
            // Новый игровой день — сбрасываем счётчики
            DAILY_SELL_COUNT.clear();
            LAST_SELL_DAY = currentDay;
        }
        int soldToday = DAILY_SELL_COUNT.getOrDefault(itemId, 0);
        int remaining = Math.max(0, HARDCORE_DAILY_LIMIT - soldToday);
        return Math.min(amount, remaining);
    }

    /** Записывает продажу в дневной счётчик (только HARDCORE) */
    public static void recordDailySell(String itemId, int amount) {
        if (SHOP_MODE != ShopMode.HARDCORE) return;
        DAILY_SELL_COUNT.merge(itemId, amount, Integer::sum);
        save();
    }

    /**
     * Применяет выбранный режим магазина.
     * Вызывается один раз из визарда. После этого SHOP_MODE нельзя изменить.
     */
    public static void applyShopMode(ShopMode mode) {
        if (SHOP_MODE != null) return; // уже выбран — игнорируем
        SHOP_MODE = mode;
        // Генерируем seed прямо сейчас, вшивая режим — до этого момента seed не существовал
        com.shopmod.security.InstallationSeed.bakeWithMode(mode.name());
        switch (mode) {
            case EASY -> {
                DYNAMIC_PRICES = false;
                COMMISSION_ENABLED = false;
            }
            case NORMAL -> {
                DYNAMIC_PRICES = false;
                COMMISSION_ENABLED = false;
            }
            case HARDCORE -> {
                DYNAMIC_PRICES = true;
                COMMISSION_ENABLED = false;
            }
        }
        save();
    }

    /**
     * Принудительно применяет ограничения выбранного режима.
     * Вызывается после load() — гарантирует что ручная правка конфига
     * не может изменить dynamic_prices или commission_enabled.
     */
    private static void enforceModeConstraints() {
        if (SHOP_MODE == null) return;
        switch (SHOP_MODE) {
            case EASY -> {
                DYNAMIC_PRICES     = false;
                COMMISSION_ENABLED = false;
            }
            case NORMAL -> {
                DYNAMIC_PRICES     = false;
                COMMISSION_ENABLED = false;
            }
            case HARDCORE -> {
                // В хардкоре dynamic_prices управляется кнопкой в GUI — не трогаем
                COMMISSION_ENABLED = false;
            }
        }
    }

    public static void recordTransaction(String itemId) {
        TRANSACTION_COUNT.merge(itemId, 1, Integer::sum);
        int cur = TRANSACTION_COUNT.get(itemId);
        if (cur > DYNAMIC_SAMPLE_SIZE * 4) TRANSACTION_COUNT.put(itemId, DYNAMIC_SAMPLE_SIZE * 2);
        save();
    }

    public static void decrementTransaction(String itemId) {
        int cur = TRANSACTION_COUNT.getOrDefault(itemId, 0);
        if (cur > 0) { TRANSACTION_COUNT.put(itemId, cur - 1); save(); }
    }

    public static Set<String> getAllItems()           { return Collections.unmodifiableSet(ITEM_PRICES.keySet()); }
    public static boolean     hasItem(String id)      { return ITEM_PRICES.containsKey(id); }
    public static long        getBasePrice(String id) { return ITEM_PRICES.getOrDefault(id, 10L); }

    // ── Разбор специальных ID ─────────────────────────────────────────────────
    /** Тип предмета по ID */
    public enum ItemKind { NORMAL, POTION, SPLASH_POTION, LINGERING_POTION, TIPPED_ARROW, ENCHANTED_BOOK }

    public static ItemKind getItemKind(String id) {
        if (id.startsWith("enchanted_book:"))   return ItemKind.ENCHANTED_BOOK;
        if (id.startsWith("tipped_arrow:"))      return ItemKind.TIPPED_ARROW;
        if (id.startsWith("lingering_potion:"))  return ItemKind.LINGERING_POTION;
        if (id.startsWith("splash_potion:"))     return ItemKind.SPLASH_POTION;
        if (id.startsWith("potion:"))            return ItemKind.POTION;
        return ItemKind.NORMAL;
    }

    /** Извлечь вариант из ID (часть после первого ':' для спец-предметов) */
    public static String getItemVariant(String id) {
        int idx = id.indexOf(':');
        return idx >= 0 && getItemKind(id) != ItemKind.NORMAL ? id.substring(idx + 1) : "";
    }

    /** Minecraft команда give для спец-предмета.
     *  Формат: give @s minecraft:potion[potion_contents={custom_effects:[...]}] 1
     *  или для зачарованных книг: give @s minecraft:enchanted_book[stored_enchantments={...}] 1
     */
    public static String buildGiveCommand(String id, int amount) {
        ItemKind kind = getItemKind(id);
        if (kind == ItemKind.NORMAL) {
            return "give @s " + id + " " + amount;
        }
        String variant = getItemVariant(id);
        switch (kind) {
            case POTION:
                return "give @s minecraft:potion[potion_contents={potion:\"minecraft:" + variant + "\"},max_stack_size=64] " + amount;
            case SPLASH_POTION:
                return "give @s minecraft:splash_potion[potion_contents={potion:\"minecraft:" + variant + "\"},max_stack_size=64] " + amount;
            case LINGERING_POTION:
                return "give @s minecraft:lingering_potion[potion_contents={potion:\"minecraft:" + variant + "\"},max_stack_size=64] " + amount;
            case TIPPED_ARROW:
                return "give @s minecraft:tipped_arrow[potion_contents={potion:\"minecraft:" + variant + "\"},max_stack_size=64] " + amount;
            case ENCHANTED_BOOK:
                // variant может быть "sharpness_5" -> enchantment "sharpness" level 5
                return buildEnchantedBookCommand(variant, amount);
            default:
                return "give @s " + id + " " + amount;
        }
    }

    private static String buildEnchantedBookCommand(String variant, int amount) {
        // Разбираем "sharpness_5" -> enchant="sharpness", level=5
        // или "silk_touch" -> enchant="silk_touch", level=1
        // или "protection_4" -> enchant="protection", level=4
        int level = 1;
        String enchant = variant;
        // Ищем суффикс _N где N — цифра
        int lastUs = variant.lastIndexOf('_');
        if (lastUs >= 0) {
            String suffix = variant.substring(lastUs + 1);
            try {
                level = Integer.parseInt(suffix);
                enchant = variant.substring(0, lastUs);
            } catch (NumberFormatException e) {
                // суффикс не число — значит весь variant это название
                enchant = variant;
                level = 1;
            }
        }
        return "give @s minecraft:enchanted_book[stored_enchantments={levels:{\"minecraft:" + enchant + "\":" + level + "}},max_stack_size=64] " + amount;
    }

    /** Команда clear для спец-предмета (просто clear minecraft:potion/etc без NBT, т.к. /clear не поддерживает компоненты) */
    public static String buildClearCommand(String id, int amount) {
        ItemKind kind = getItemKind(id);
        switch (kind) {
            case POTION:          return "clear @s minecraft:potion " + amount;
            case SPLASH_POTION:   return "clear @s minecraft:splash_potion " + amount;
            case LINGERING_POTION:return "clear @s minecraft:lingering_potion " + amount;
            case TIPPED_ARROW:    return "clear @s minecraft:tipped_arrow " + amount;
            case ENCHANTED_BOOK:  return "clear @s minecraft:enchanted_book " + amount;
            default:              return "clear @s " + id + " " + amount;
        }
    }

    /** Minecraft Item для иконки в GUI (базовый тип без варианта) */
    public static String getBaseItemId(String id) {
        ItemKind kind = getItemKind(id);
        switch (kind) {
            case POTION:           return "minecraft:potion";
            case SPLASH_POTION:    return "minecraft:splash_potion";
            case LINGERING_POTION: return "minecraft:lingering_potion";
            case TIPPED_ARROW:     return "minecraft:tipped_arrow";
            case ENCHANTED_BOOK:   return "minecraft:enchanted_book";
            default:               return id;
        }
    }

    // ── Таблица переводов эффектов/зачарований ──────────────────────────────
    /**
     * Полные названия зелий по variant — идентично ванильным lang-файлам Minecraft.
     * Ключ: вариант зелья (поле после двоеточия в itemId, напр. "night_vision",
     *       "long_night_vision", "strong_leaping").
     * Значение: [ru, en] — точные строки как в item.minecraft.potion.effect.X.
     */
    private static final Map<String, String[]> POTION_NAMES = new HashMap<>();

    /**
     * Переопределения суффиксов для стрел, где название отличается от зелья.
     * Например, зелье "ночного зрения" -> стрела "ночного видения".
     * Ключ: variant. Значение: [ru_suffix, en_suffix] — часть после "Стрела "/"Arrow of ".
     */
    private static final Map<String, String[]> ARROW_SUFFIX_OVERRIDES = new HashMap<>();

    /**
     * Полные названия зачарований по variant — [ru, en].
     * Ключ: название зачарования без уровня (напр. "sharpness").
     * Уровень II/III добавляется отдельно через toRoman().
     */
    private static final Map<String, String[]> ENCHANT_NAMES = new HashMap<>();
    static {
        // ── Зелья: полные названия как в ванильном ru_RU / en_US ──────────────
        // Нейтральные
        POTION_NAMES.put("water",                new String[]{"Бутылка воды",                    "Water Bottle"});
        POTION_NAMES.put("mundane",              new String[]{"Обычное зелье",                   "Mundane Potion"});
        POTION_NAMES.put("thick",                new String[]{"Густое зелье",                    "Thick Potion"});
        POTION_NAMES.put("awkward",              new String[]{"Неловкое зелье",                  "Awkward Potion"});
        // Ночное зрение
        POTION_NAMES.put("night_vision",         new String[]{"Зелье ночного зрения",            "Potion of Night Vision"});
        POTION_NAMES.put("long_night_vision",    new String[]{"Зелье ночного зрения +",          "Potion of Night Vision +"});
        // Невидимость
        POTION_NAMES.put("invisibility",         new String[]{"Зелье невидимости",               "Potion of Invisibility"});
        POTION_NAMES.put("long_invisibility",    new String[]{"Зелье невидимости +",             "Potion of Invisibility +"});
        // Прыгучесть
        POTION_NAMES.put("leaping",              new String[]{"Зелье прыгучести",                "Potion of Leaping"});
        POTION_NAMES.put("long_leaping",         new String[]{"Зелье прыгучести +",              "Potion of Leaping +"});
        POTION_NAMES.put("strong_leaping",       new String[]{"Зелье прыгучести II",             "Potion of Leaping II"});
        // Огнестойкость
        POTION_NAMES.put("fire_resistance",      new String[]{"Зелье огнестойкости",             "Potion of Fire Resistance"});
        POTION_NAMES.put("long_fire_resistance", new String[]{"Зелье огнестойкости +",           "Potion of Fire Resistance +"});
        // Скорость
        POTION_NAMES.put("swiftness",            new String[]{"Зелье стремительности",            "Potion of Swiftness"});
        POTION_NAMES.put("long_swiftness",       new String[]{"Зелье стремительности +",          "Potion of Swiftness +"});
        POTION_NAMES.put("strong_swiftness",     new String[]{"Зелье стремительности II",         "Potion of Swiftness II"});
        // Замедление
        POTION_NAMES.put("slowness",             new String[]{"Зелье замедления",                "Potion of Slowness"});
        POTION_NAMES.put("long_slowness",        new String[]{"Зелье замедления +",              "Potion of Slowness +"});
        POTION_NAMES.put("strong_slowness",      new String[]{"Зелье замедления IV",             "Potion of Slowness IV"});
        // Водное дыхание
        POTION_NAMES.put("water_breathing",      new String[]{"Зелье подводного дыхания",        "Potion of Water Breathing"});
        POTION_NAMES.put("long_water_breathing", new String[]{"Зелье подводного дыхания +",      "Potion of Water Breathing +"});
        // Лечение
        POTION_NAMES.put("healing",              new String[]{"Зелье исцеления",                  "Potion of Healing"});
        POTION_NAMES.put("strong_healing",       new String[]{"Зелье исцеления II",               "Potion of Healing II"});
        // Вред
        POTION_NAMES.put("harming",              new String[]{"Зелье вреда",                     "Potion of Harming"});
        POTION_NAMES.put("strong_harming",       new String[]{"Зелье вреда II",                  "Potion of Harming II"});
        // Яд
        POTION_NAMES.put("poison",               new String[]{"Зелье отравления",                "Potion of Poison"});
        POTION_NAMES.put("long_poison",          new String[]{"Зелье отравления +",              "Potion of Poison +"});
        POTION_NAMES.put("strong_poison",        new String[]{"Зелье отравления II",             "Potion of Poison II"});
        // Регенерация
        POTION_NAMES.put("regeneration",         new String[]{"Зелье регенерации",               "Potion of Regeneration"});
        POTION_NAMES.put("long_regeneration",    new String[]{"Зелье регенерации +",             "Potion of Regeneration +"});
        POTION_NAMES.put("strong_regeneration",  new String[]{"Зелье регенерации II",            "Potion of Regeneration II"});
        // Сила
        POTION_NAMES.put("strength",             new String[]{"Зелье силы",                      "Potion of Strength"});
        POTION_NAMES.put("long_strength",        new String[]{"Зелье силы +",                    "Potion of Strength +"});
        POTION_NAMES.put("strong_strength",      new String[]{"Зелье силы II",                   "Potion of Strength II"});
        // Слабость
        POTION_NAMES.put("weakness",             new String[]{"Зелье слабости",                  "Potion of Weakness"});
        POTION_NAMES.put("long_weakness",        new String[]{"Зелье слабости +",                "Potion of Weakness +"});
        // Удача
        POTION_NAMES.put("luck",                 new String[]{"Зелье удачи",                     "Potion of Luck"});
        // Хозяин черепах
        POTION_NAMES.put("turtle_master",        new String[]{"Зелье черепашьей мощи",           "Potion of the Turtle Master"});
        POTION_NAMES.put("long_turtle_master",   new String[]{"Зелье черепашьей мощи +",         "Potion of the Turtle Master +"});
        POTION_NAMES.put("strong_turtle_master", new String[]{"Зелье черепашьей мощи II",        "Potion of the Turtle Master II"});
        // Медленное падение
        POTION_NAMES.put("slow_falling",         new String[]{"Зелье плавного падения",          "Potion of Slow Falling"});
        POTION_NAMES.put("long_slow_falling",    new String[]{"Зелье плавного падения +",        "Potion of Slow Falling +"});
        // 1.21+
        POTION_NAMES.put("wind_charged",         new String[]{"Зелье ветрового заряда",          "Potion of Wind Charging"});
        POTION_NAMES.put("weaving",              new String[]{"Зелье плетения",                  "Potion of Weaving"});
        POTION_NAMES.put("oozing",               new String[]{"Зелье слизистости",               "Potion of Oozing"});
        POTION_NAMES.put("infested",             new String[]{"Зелье заражённости",              "Potion of Infestation"});

        // ── Стрелы: суффиксы, отличающиеся от зелий ─────────────────────────────
        // "ночного видения" у стрелы (не "ночного зрения" как у зелья)
        ARROW_SUFFIX_OVERRIDES.put("night_vision",         new String[]{"ночного видения",          "Night Vision"});
        ARROW_SUFFIX_OVERRIDES.put("long_night_vision",    new String[]{"ночного видения +",         "Night Vision +"});
        // "черепашьей мощи" - у стрелы так же как у зелья (уже исправлено выше)

        // Зачарования
        ENCHANT_NAMES.put("sharpness",              new String[]{"Острота", "Sharpness"});
        ENCHANT_NAMES.put("smite",                  new String[]{"Небесная кара", "Smite"});
        ENCHANT_NAMES.put("bane_of_arthropods",     new String[]{"Бич членистоногих", "Bane of Arthropods"});
        ENCHANT_NAMES.put("knockback",              new String[]{"Отдача", "Knockback"});
        ENCHANT_NAMES.put("fire_aspect",            new String[]{"Заговор огня", "Fire Aspect"});
        ENCHANT_NAMES.put("looting",                new String[]{"Добыча", "Looting"});
        ENCHANT_NAMES.put("sweeping_edge",          new String[]{"Разящий клинок", "Sweeping Edge"});
        ENCHANT_NAMES.put("unbreaking",             new String[]{"Прочность", "Unbreaking"});
        ENCHANT_NAMES.put("mending",                new String[]{"Починка", "Mending"});
        ENCHANT_NAMES.put("fortune",                new String[]{"Удача", "Fortune"});
        ENCHANT_NAMES.put("silk_touch",             new String[]{"Шёлковое касание", "Silk Touch"});
        ENCHANT_NAMES.put("efficiency",             new String[]{"Эффективность", "Efficiency"});
        ENCHANT_NAMES.put("power",                  new String[]{"Сила", "Power"});
        ENCHANT_NAMES.put("punch",                  new String[]{"Отбрасывание", "Punch"});
        ENCHANT_NAMES.put("flame",                  new String[]{"Воспламенение", "Flame"});
        ENCHANT_NAMES.put("infinity",               new String[]{"Бесконечность", "Infinity"});
        ENCHANT_NAMES.put("protection",             new String[]{"Защита", "Protection"});
        ENCHANT_NAMES.put("blast_protection",       new String[]{"Взрывоустойчивость", "Blast Protection"});
        ENCHANT_NAMES.put("fire_protection",        new String[]{"Огнеупорность", "Fire Protection"});
        ENCHANT_NAMES.put("projectile_protection",  new String[]{"Защита от снарядов", "Projectile Protection"});
        ENCHANT_NAMES.put("feather_falling",        new String[]{"Невесомость", "Feather Falling"});
        ENCHANT_NAMES.put("thorns",                 new String[]{"Шипы", "Thorns"});
        ENCHANT_NAMES.put("respiration",            new String[]{"Подводное дыхание", "Respiration"});
        ENCHANT_NAMES.put("aqua_affinity",          new String[]{"Подводник", "Aqua Affinity"});
        ENCHANT_NAMES.put("depth_strider",          new String[]{"Подводная ходьба", "Depth Strider"});
        ENCHANT_NAMES.put("frost_walker",           new String[]{"Ледоход", "Frost Walker"});
        ENCHANT_NAMES.put("soul_speed",             new String[]{"Скорость душ", "Soul Speed"});
        ENCHANT_NAMES.put("swift_sneak",            new String[]{"Проворство", "Swift Sneak"});
        ENCHANT_NAMES.put("lure",                   new String[]{"Приманка", "Lure"});
        ENCHANT_NAMES.put("luck_of_the_sea",        new String[]{"Удача морей", "Luck of the Sea"});
        ENCHANT_NAMES.put("loyalty",                new String[]{"Верность", "Loyalty"});
        ENCHANT_NAMES.put("riptide",                new String[]{"Тягун", "Riptide"});
        ENCHANT_NAMES.put("impaling",               new String[]{"Пронзание", "Impaling"});
        ENCHANT_NAMES.put("channeling",             new String[]{"Громовержец", "Channeling"});
        ENCHANT_NAMES.put("multishot",              new String[]{"Тройной выстрел", "Multishot"});
        ENCHANT_NAMES.put("quick_charge",           new String[]{"Быстрая перезарядка", "Quick Charge"});
        ENCHANT_NAMES.put("piercing",               new String[]{"Пронзающая стрела", "Piercing"});
        ENCHANT_NAMES.put("curse_of_binding",       new String[]{"Проклятие несъёмности", "Curse of Binding"});
        ENCHANT_NAMES.put("curse_of_vanishing",     new String[]{"Проклятие утраты", "Curse of Vanishing"});
        ENCHANT_NAMES.put("wind_burst",             new String[]{"Порыв ветра", "Wind Burst"});
        ENCHANT_NAMES.put("density",                new String[]{"Плотность", "Density"});
        ENCHANT_NAMES.put("breach",                 new String[]{"Пробитие", "Breach"});
    }

    /** Цвет зелья/стрелы по варианту эффекта (как в Minecraft) */
    public static int getPotionColor(String variant) {
        // Снимаем long_/strong_
        String eff = variant;
        if (eff.startsWith("long_"))   eff = eff.substring(5);
        if (eff.startsWith("strong_")) eff = eff.substring(7);
        return switch (eff) {
            case "night_vision"    -> 0xFF1F1FA1;
            case "invisibility"    -> 0xFF7F8392;
            case "leaping"         -> 0xFF22FF4C;
            case "fire_resistance" -> 0xFFE49A3A;
            case "swiftness"       -> 0xFF7CAFC6;
            case "slowness"        -> 0xFF5A6C81;
            case "water_breathing" -> 0xFF2E5299;
            case "healing"         -> 0xFFF82423;
            case "harming"         -> 0xFF430A09;
            case "poison"          -> 0xFF4E9331;
            case "regeneration"    -> 0xFFCD5CAB;
            case "strength"        -> 0xFF932423;
            case "weakness"        -> 0xFF484D48;
            case "luck"            -> 0xFF339900;
            case "turtle_master"   -> 0xFF069A80;
            case "slow_falling"    -> 0xFFACCCCC;
            case "wind_charged"    -> 0xFF99D5D5;
            case "weaving"         -> 0xFF7D4C7D;
            case "oozing"          -> 0xFF60A060;
            case "infested"        -> 0xFFA0A030;
            case "water"           -> 0xFF3F76E4;
            case "awkward"         -> 0xFF000000;
            case "mundane"         -> 0xFF000000;
            case "thick"           -> 0xFF000000;
            default                -> 0xFF385DC6;
        };
    }

    /** Читаемое название зачарования/эффекта из variant */
    /**
     * Возвращает отображаемое название варианта зелья или зачарования.
     * Для зелий/стрел — полная строка из POTION_NAMES (идентично ванильному Minecraft).
     * Для зачарований — название + уровень римскими цифрами.
     */
    public static String getVariantDisplayName(String variant) {
        if (variant.isEmpty()) return "";
        boolean ru = "ru".equals(LANGUAGE);

        // Зелья/стрелы: прямой lookup по полному variant (включает long_/strong_)
        if (POTION_NAMES.containsKey(variant)) {
            return POTION_NAMES.get(variant)[ru ? 0 : 1];
        }

        // Зачарования: отрезаем числовой суффикс уровня (_2, _3 и т.д.)
        String name = variant;
        String lvlSuffix = "";
        int lastUs = name.lastIndexOf('_');
        if (lastUs >= 0) {
            String sfx = name.substring(lastUs + 1);
            try {
                int lvl = Integer.parseInt(sfx);
                lvlSuffix = " " + toRoman(lvl);
                name = name.substring(0, lastUs);
            } catch (NumberFormatException ignored) {}
        }
        if (ENCHANT_NAMES.containsKey(name)) {
            return ENCHANT_NAMES.get(name)[ru ? 0 : 1] + lvlSuffix;
        }

        // Фоллбэк — форматируем из id
        name = name.replace('_', ' ');
        name = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        return name + lvlSuffix;
    }

    /**
     * Возвращает полное ванильное название для зелий, бросаемых/стойких зелий и стрел.
     * Идентично item.minecraft.potion.effect.X / splash_potion.effect.X и т.д.
     * Для зачарованных книг делегирует в getVariantDisplayName.
     */
    public static String getItemDisplayName(String variant, ItemKind kind) {
        if (variant.isEmpty()) return "";
        boolean ru = "ru".equals(LANGUAGE);

        // Для зачарованных книг — стандартная логика (не зелье)
        if (kind == ItemKind.ENCHANTED_BOOK) {
            return getVariantDisplayName(variant);
        }

        // Берём базовое название зелья из POTION_NAMES
        String[] potionName = POTION_NAMES.get(variant);
        if (potionName == null) {
            // Фоллбэк для неизвестных вариантов
            return getVariantDisplayName(variant);
        }
        String base = potionName[ru ? 0 : 1];

        return switch (kind) {
            case POTION -> base;  // "Зелье X" уже в POTION_NAMES
            case SPLASH_POTION -> {
                // ваниль: "Взрывное зелье X" / "Splash Potion of X"
                if (ru) {
                    if (base.startsWith("Зелье "))
                        yield "Взрывное зелье " + base.substring(6);
                    else if (base.endsWith(" зелье")) // "Мирское зелье", "Густое зелье" и т.п.
                        yield "Взрывное " + Character.toLowerCase(base.charAt(0)) + base.substring(1);
                    else
                        yield "Взрывное " + Character.toLowerCase(base.charAt(0)) + base.substring(1);
                } else if (base.contains("Potion of "))
                    yield base.replace("Potion of ", "Splash Potion of ");
                else // "Mundane Potion", "Thick Potion", "Water Bottle"
                    yield "Splash " + base;
            }
            case LINGERING_POTION -> {
                // ваниль: "Туманное зелье X" / "Lingering Potion of X"
                if (ru) {
                    if (base.startsWith("Зелье "))
                        yield "Туманное зелье " + base.substring(6);
                    else if (base.endsWith(" зелье"))
                        yield "Туманное " + Character.toLowerCase(base.charAt(0)) + base.substring(1);
                    else
                        yield "Туманное " + Character.toLowerCase(base.charAt(0)) + base.substring(1);
                } else if (base.contains("Potion of "))
                    yield base.replace("Potion of ", "Lingering Potion of ");
                else
                    yield "Lingering " + base;
            }
            case TIPPED_ARROW -> {
                // ваниль: "Стрела X" / "Arrow of X"
                if (ru) {
                    // Сначала проверяем переопределения (напр. "ночного видения" вместо "ночного зрения")
                    if (ARROW_SUFFIX_OVERRIDES.containsKey(variant))
                        yield "Стрела " + ARROW_SUFFIX_OVERRIDES.get(variant)[0];
                    else if (base.startsWith("Зелье "))
                        yield "Стрела " + base.substring(6);
                    else if (base.equals("Бутылка воды"))
                        yield "Стрела воды";
                    else
                        yield "Стрела"; // неизвестный вариант
                } else if (ARROW_SUFFIX_OVERRIDES.containsKey(variant))
                    yield "Arrow of " + ARROW_SUFFIX_OVERRIDES.get(variant)[1];
                else if (base.contains("Potion of "))
                    yield base.replace("Potion of ", "Arrow of ");
                else
                    yield base; // water bottle arrow edge case
            }
            default -> base;
        };
    }

    private static String toRoman(int n) {
        return switch (n) { case 1 -> "I"; case 2 -> "II"; case 3 -> "III"; case 4 -> "IV"; case 5 -> "V"; default -> String.valueOf(n); };
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Дефолтные цены (сбалансированные)
    //  мусор/грунт: 1-2  |  камень: 2-3  |  дерево: 4-5
    //  уголь: 6  |  медь: 8  |  железо: 25  |  золото: 55
    //  редстоун: 12  |  лазурит: 20  |  кварц: 18
    //  алмаз: 650  |  изумруд: 200  |  незерит: 2500-3200
    //  легендарки (элитра, тотем, маяк): 3500-5000
    // ─────────────────────────────────────────────────────────────────────────
    private static void createDefault() {
        Object[][] d = {
            // ── Мусор / грунт
            {"minecraft:dirt",1,"0.3"},{"minecraft:coarse_dirt",1,"0.3"},{"minecraft:rooted_dirt",1,"0.3"},
            {"minecraft:gravel",1,"0.3"},{"minecraft:sand",1,"0.3"},{"minecraft:red_sand",1,"0.3"},
            {"minecraft:clay",2,"0.35"},{"minecraft:ice",1,"0.3"},{"minecraft:snow",1,"0.3"},
            {"minecraft:mycelium",2,"0.3"},{"minecraft:podzol",1,"0.3"},{"minecraft:grass_block",1,"0.3"},
            {"minecraft:mud",1,"0.3"},{"minecraft:rotten_flesh",2,"0.3"},
            // ── Камень и производные
            {"minecraft:stone",2,"0.4"},{"minecraft:cobblestone",2,"0.4"},{"minecraft:andesite",2,"0.4"},
            {"minecraft:diorite",2,"0.4"},{"minecraft:granite",2,"0.4"},{"minecraft:deepslate",2,"0.4"},
            {"minecraft:tuff",2,"0.4"},{"minecraft:basalt",2,"0.4"},{"minecraft:blackstone",2,"0.4"},
            {"minecraft:calcite",2,"0.4"},{"minecraft:dripstone_block",2,"0.4"},{"minecraft:pointed_dripstone",2,"0.4"},
            {"minecraft:stone_slab",2,"0.4"},{"minecraft:stone_stairs",2,"0.4"},{"minecraft:stone_bricks",3,"0.4"},
            {"minecraft:stone_brick_slab",3,"0.4"},{"minecraft:stone_brick_stairs",3,"0.4"},{"minecraft:stone_brick_wall",3,"0.4"},
            {"minecraft:cobblestone_slab",2,"0.4"},{"minecraft:cobblestone_stairs",2,"0.4"},{"minecraft:cobblestone_wall",2,"0.4"},
            {"minecraft:mossy_cobblestone",3,"0.4"},{"minecraft:mossy_cobblestone_slab",3,"0.4"},
            {"minecraft:mossy_cobblestone_stairs",3,"0.4"},{"minecraft:mossy_cobblestone_wall",3,"0.4"},
            {"minecraft:mossy_stone_bricks",3,"0.4"},{"minecraft:mossy_stone_brick_slab",3,"0.4"},
            {"minecraft:mossy_stone_brick_stairs",3,"0.4"},{"minecraft:mossy_stone_brick_wall",3,"0.4"},
            {"minecraft:cracked_stone_bricks",3,"0.4"},{"minecraft:chiseled_stone_bricks",3,"0.4"},
            {"minecraft:smooth_stone",2,"0.4"},{"minecraft:smooth_stone_slab",2,"0.4"},
            {"minecraft:polished_andesite",2,"0.4"},{"minecraft:polished_andesite_slab",2,"0.4"},{"minecraft:polished_andesite_stairs",2,"0.4"},
            {"minecraft:polished_diorite",2,"0.4"},{"minecraft:polished_diorite_slab",2,"0.4"},{"minecraft:polished_diorite_stairs",2,"0.4"},
            {"minecraft:polished_granite",2,"0.4"},{"minecraft:polished_granite_slab",2,"0.4"},{"minecraft:polished_granite_stairs",2,"0.4"},
            {"minecraft:polished_basalt",2,"0.4"},{"minecraft:smooth_basalt",2,"0.4"},
            {"minecraft:polished_blackstone",2,"0.4"},{"minecraft:polished_blackstone_bricks",3,"0.4"},
            {"minecraft:polished_blackstone_brick_slab",3,"0.4"},{"minecraft:polished_blackstone_brick_stairs",3,"0.4"},
            {"minecraft:polished_blackstone_brick_wall",3,"0.4"},{"minecraft:polished_blackstone_button",2,"0.4"},
            {"minecraft:polished_blackstone_pressure_plate",2,"0.4"},{"minecraft:polished_blackstone_slab",2,"0.4"},
            {"minecraft:polished_blackstone_stairs",2,"0.4"},{"minecraft:polished_blackstone_wall",2,"0.4"},
            {"minecraft:chiseled_polished_blackstone",3,"0.4"},{"minecraft:cracked_polished_blackstone_bricks",3,"0.4"},
            {"minecraft:gilded_blackstone",5,"0.4"},
            {"minecraft:deepslate_bricks",3,"0.4"},{"minecraft:deepslate_brick_slab",3,"0.4"},
            {"minecraft:deepslate_brick_stairs",3,"0.4"},{"minecraft:deepslate_brick_wall",3,"0.4"},
            {"minecraft:deepslate_tiles",3,"0.4"},{"minecraft:deepslate_tile_slab",3,"0.4"},
            {"minecraft:deepslate_tile_stairs",3,"0.4"},{"minecraft:deepslate_tile_wall",3,"0.4"},
            {"minecraft:chiseled_deepslate",3,"0.4"},{"minecraft:cracked_deepslate_bricks",3,"0.4"},
            {"minecraft:cracked_deepslate_tiles",3,"0.4"},{"minecraft:cobbled_deepslate",2,"0.4"},
            {"minecraft:cobbled_deepslate_slab",2,"0.4"},{"minecraft:cobbled_deepslate_stairs",2,"0.4"},
            {"minecraft:cobbled_deepslate_wall",2,"0.4"},{"minecraft:polished_deepslate",2,"0.4"},
            {"minecraft:polished_deepslate_slab",2,"0.4"},{"minecraft:polished_deepslate_stairs",2,"0.4"},
            {"minecraft:polished_deepslate_wall",2,"0.4"},
            {"minecraft:tuff_bricks",3,"0.4"},{"minecraft:tuff_brick_slab",3,"0.4"},{"minecraft:tuff_brick_stairs",3,"0.4"},
            {"minecraft:tuff_brick_wall",3,"0.4"},{"minecraft:tuff_slab",2,"0.4"},{"minecraft:tuff_stairs",2,"0.4"},
            {"minecraft:tuff_wall",2,"0.4"},{"minecraft:chiseled_tuff",3,"0.4"},{"minecraft:chiseled_tuff_bricks",3,"0.4"},
            {"minecraft:polished_tuff",2,"0.4"},{"minecraft:polished_tuff_slab",2,"0.4"},
            {"minecraft:polished_tuff_stairs",2,"0.4"},{"minecraft:polished_tuff_wall",2,"0.4"},
            {"minecraft:stonecutter",3,"0.4"},{"minecraft:stone_button",2,"0.4"},{"minecraft:stone_pressure_plate",2,"0.4"},
            {"minecraft:infested_stone",2,"0.3"},{"minecraft:infested_cobblestone",2,"0.3"},
            {"minecraft:infested_stone_bricks",2,"0.3"},{"minecraft:infested_cracked_stone_bricks",2,"0.3"},
            {"minecraft:infested_mossy_stone_bricks",2,"0.3"},{"minecraft:infested_chiseled_stone_bricks",2,"0.3"},
            {"minecraft:infested_deepslate",2,"0.3"},
            // ── Дерево
            {"minecraft:oak_log",4,"0.4"},{"minecraft:spruce_log",4,"0.4"},{"minecraft:birch_log",4,"0.4"},
            {"minecraft:jungle_log",4,"0.4"},{"minecraft:acacia_log",4,"0.4"},{"minecraft:dark_oak_log",4,"0.4"},
            {"minecraft:cherry_log",5,"0.4"},{"minecraft:mangrove_log",4,"0.4"},
            {"minecraft:oak_wood",4,"0.4"},{"minecraft:spruce_wood",4,"0.4"},{"minecraft:birch_wood",4,"0.4"},
            {"minecraft:jungle_wood",4,"0.4"},{"minecraft:acacia_wood",4,"0.4"},{"minecraft:dark_oak_wood",4,"0.4"},
            {"minecraft:cherry_wood",5,"0.4"},{"minecraft:mangrove_wood",4,"0.4"},
            {"minecraft:stripped_oak_log",4,"0.4"},{"minecraft:stripped_spruce_log",4,"0.4"},
            {"minecraft:stripped_birch_log",4,"0.4"},{"minecraft:stripped_jungle_log",4,"0.4"},
            {"minecraft:stripped_acacia_log",4,"0.4"},{"minecraft:stripped_dark_oak_log",4,"0.4"},
            {"minecraft:stripped_cherry_log",5,"0.4"},{"minecraft:stripped_mangrove_log",4,"0.4"},
            {"minecraft:stripped_oak_wood",4,"0.4"},{"minecraft:stripped_spruce_wood",4,"0.4"},
            {"minecraft:stripped_birch_wood",4,"0.4"},{"minecraft:stripped_jungle_wood",4,"0.4"},
            {"minecraft:stripped_acacia_wood",4,"0.4"},{"minecraft:stripped_dark_oak_wood",4,"0.4"},
            {"minecraft:stripped_cherry_wood",5,"0.4"},{"minecraft:stripped_mangrove_wood",4,"0.4"},
            {"minecraft:oak_planks",3,"0.4"},{"minecraft:spruce_planks",3,"0.4"},{"minecraft:birch_planks",3,"0.4"},
            {"minecraft:jungle_planks",3,"0.4"},{"minecraft:acacia_planks",3,"0.4"},{"minecraft:dark_oak_planks",3,"0.4"},
            {"minecraft:cherry_planks",4,"0.4"},{"minecraft:mangrove_planks",3,"0.4"},
            {"minecraft:oak_slab",2,"0.4"},{"minecraft:oak_stairs",3,"0.4"},{"minecraft:oak_fence",3,"0.4"},
            {"minecraft:oak_fence_gate",4,"0.4"},{"minecraft:oak_door",4,"0.4"},{"minecraft:oak_trapdoor",4,"0.4"},
            {"minecraft:oak_button",2,"0.4"},{"minecraft:oak_pressure_plate",3,"0.4"},{"minecraft:oak_sign",3,"0.4"},
            {"minecraft:oak_hanging_sign",4,"0.4"},{"minecraft:oak_sapling",5,"0.5"},{"minecraft:oak_leaves",2,"0.3"},
            {"minecraft:spruce_slab",2,"0.4"},{"minecraft:spruce_stairs",3,"0.4"},{"minecraft:spruce_fence",3,"0.4"},
            {"minecraft:spruce_fence_gate",4,"0.4"},{"minecraft:spruce_door",4,"0.4"},{"minecraft:spruce_trapdoor",4,"0.4"},
            {"minecraft:spruce_button",2,"0.4"},{"minecraft:spruce_pressure_plate",3,"0.4"},{"minecraft:spruce_sign",3,"0.4"},
            {"minecraft:spruce_hanging_sign",4,"0.4"},{"minecraft:spruce_sapling",5,"0.5"},{"minecraft:spruce_leaves",2,"0.3"},
            {"minecraft:birch_slab",2,"0.4"},{"minecraft:birch_stairs",3,"0.4"},{"minecraft:birch_fence",3,"0.4"},
            {"minecraft:birch_fence_gate",4,"0.4"},{"minecraft:birch_door",4,"0.4"},{"minecraft:birch_trapdoor",4,"0.4"},
            {"minecraft:birch_button",2,"0.4"},{"minecraft:birch_pressure_plate",3,"0.4"},{"minecraft:birch_sign",3,"0.4"},
            {"minecraft:birch_hanging_sign",4,"0.4"},{"minecraft:birch_sapling",5,"0.5"},{"minecraft:birch_leaves",2,"0.3"},
            {"minecraft:jungle_slab",2,"0.4"},{"minecraft:jungle_stairs",3,"0.4"},{"minecraft:jungle_fence",3,"0.4"},
            {"minecraft:jungle_fence_gate",4,"0.4"},{"minecraft:jungle_door",4,"0.4"},{"minecraft:jungle_trapdoor",4,"0.4"},
            {"minecraft:jungle_button",2,"0.4"},{"minecraft:jungle_pressure_plate",3,"0.4"},{"minecraft:jungle_sign",3,"0.4"},
            {"minecraft:jungle_hanging_sign",4,"0.4"},{"minecraft:jungle_sapling",5,"0.5"},{"minecraft:jungle_leaves",2,"0.3"},
            {"minecraft:acacia_slab",2,"0.4"},{"minecraft:acacia_stairs",3,"0.4"},{"minecraft:acacia_fence",3,"0.4"},
            {"minecraft:acacia_fence_gate",4,"0.4"},{"minecraft:acacia_door",4,"0.4"},{"minecraft:acacia_trapdoor",4,"0.4"},
            {"minecraft:acacia_button",2,"0.4"},{"minecraft:acacia_pressure_plate",3,"0.4"},{"minecraft:acacia_sign",3,"0.4"},
            {"minecraft:acacia_hanging_sign",4,"0.4"},{"minecraft:acacia_sapling",5,"0.5"},{"minecraft:acacia_leaves",2,"0.3"},
            {"minecraft:dark_oak_slab",2,"0.4"},{"minecraft:dark_oak_stairs",3,"0.4"},{"minecraft:dark_oak_fence",3,"0.4"},
            {"minecraft:dark_oak_fence_gate",4,"0.4"},{"minecraft:dark_oak_door",4,"0.4"},{"minecraft:dark_oak_trapdoor",4,"0.4"},
            {"minecraft:dark_oak_button",2,"0.4"},{"minecraft:dark_oak_pressure_plate",3,"0.4"},{"minecraft:dark_oak_sign",3,"0.4"},
            {"minecraft:dark_oak_hanging_sign",4,"0.4"},{"minecraft:dark_oak_sapling",5,"0.5"},{"minecraft:dark_oak_leaves",2,"0.3"},
            {"minecraft:cherry_slab",3,"0.4"},{"minecraft:cherry_stairs",4,"0.4"},{"minecraft:cherry_fence",4,"0.4"},
            {"minecraft:cherry_fence_gate",5,"0.4"},{"minecraft:cherry_door",5,"0.4"},{"minecraft:cherry_trapdoor",5,"0.4"},
            {"minecraft:cherry_button",3,"0.4"},{"minecraft:cherry_pressure_plate",4,"0.4"},{"minecraft:cherry_sign",4,"0.4"},
            {"minecraft:cherry_hanging_sign",5,"0.4"},{"minecraft:cherry_sapling",8,"0.5"},{"minecraft:cherry_leaves",3,"0.3"},
            {"minecraft:mangrove_slab",2,"0.4"},{"minecraft:mangrove_stairs",3,"0.4"},{"minecraft:mangrove_fence",3,"0.4"},
            {"minecraft:mangrove_fence_gate",4,"0.4"},{"minecraft:mangrove_door",4,"0.4"},{"minecraft:mangrove_trapdoor",4,"0.4"},
            {"minecraft:mangrove_button",2,"0.4"},{"minecraft:mangrove_pressure_plate",3,"0.4"},{"minecraft:mangrove_sign",3,"0.4"},
            {"minecraft:mangrove_hanging_sign",4,"0.4"},{"minecraft:mangrove_propagule",5,"0.5"},{"minecraft:mangrove_leaves",2,"0.3"},
            {"minecraft:mangrove_roots",2,"0.3"},{"minecraft:muddy_mangrove_roots",2,"0.3"},
            {"minecraft:bamboo",3,"0.4"},{"minecraft:bamboo_block",4,"0.4"},{"minecraft:stripped_bamboo_block",4,"0.4"},
            {"minecraft:bamboo_planks",3,"0.4"},{"minecraft:bamboo_mosaic",3,"0.4"},{"minecraft:bamboo_mosaic_slab",2,"0.4"},
            {"minecraft:bamboo_mosaic_stairs",3,"0.4"},{"minecraft:bamboo_slab",2,"0.4"},{"minecraft:bamboo_stairs",3,"0.4"},
            {"minecraft:bamboo_fence",3,"0.4"},{"minecraft:bamboo_fence_gate",4,"0.4"},{"minecraft:bamboo_door",4,"0.4"},
            {"minecraft:bamboo_trapdoor",4,"0.4"},{"minecraft:bamboo_button",2,"0.4"},{"minecraft:bamboo_pressure_plate",3,"0.4"},
            {"minecraft:bamboo_sign",3,"0.4"},{"minecraft:bamboo_hanging_sign",4,"0.4"},{"minecraft:bamboo_raft",18,"0.4"},
            {"minecraft:bamboo_chest_raft",40,"0.4"},
            {"minecraft:crimson_stem",4,"0.4"},{"minecraft:warped_stem",4,"0.4"},
            {"minecraft:crimson_hyphae",4,"0.4"},{"minecraft:warped_hyphae",4,"0.4"},
            {"minecraft:stripped_crimson_stem",4,"0.4"},{"minecraft:stripped_warped_stem",4,"0.4"},
            {"minecraft:stripped_crimson_hyphae",4,"0.4"},{"minecraft:stripped_warped_hyphae",4,"0.4"},
            {"minecraft:crimson_planks",3,"0.4"},{"minecraft:warped_planks",3,"0.4"},
            {"minecraft:crimson_slab",2,"0.4"},{"minecraft:warped_slab",2,"0.4"},
            {"minecraft:crimson_stairs",3,"0.4"},{"minecraft:warped_stairs",3,"0.4"},
            {"minecraft:crimson_fence",3,"0.4"},{"minecraft:warped_fence",3,"0.4"},
            {"minecraft:crimson_fence_gate",4,"0.4"},{"minecraft:warped_fence_gate",4,"0.4"},
            {"minecraft:crimson_door",4,"0.4"},{"minecraft:warped_door",4,"0.4"},
            {"minecraft:crimson_trapdoor",4,"0.4"},{"minecraft:warped_trapdoor",4,"0.4"},
            {"minecraft:crimson_button",2,"0.4"},{"minecraft:warped_button",2,"0.4"},
            {"minecraft:crimson_pressure_plate",3,"0.4"},{"minecraft:warped_pressure_plate",3,"0.4"},
            {"minecraft:crimson_sign",3,"0.4"},{"minecraft:warped_sign",3,"0.4"},
            {"minecraft:crimson_hanging_sign",4,"0.4"},{"minecraft:warped_hanging_sign",4,"0.4"},
            {"minecraft:crimson_fungus",5,"0.4"},{"minecraft:warped_fungus",5,"0.4"},
            {"minecraft:crimson_roots",2,"0.3"},{"minecraft:warped_roots",2,"0.3"},
            {"minecraft:crimson_nylium",3,"0.4"},{"minecraft:warped_nylium",3,"0.4"},
            {"minecraft:warped_wart_block",5,"0.4"},{"minecraft:nether_sprouts",3,"0.3"},
            {"minecraft:twisting_vines",3,"0.3"},
            // ── Стекло, кирпич, песчаник
            {"minecraft:glass",4,"0.4"},{"minecraft:glass_pane",3,"0.4"},{"minecraft:tinted_glass",6,"0.4"},
            {"minecraft:sandstone",3,"0.4"},{"minecraft:sandstone_slab",2,"0.4"},{"minecraft:sandstone_stairs",3,"0.4"},
            {"minecraft:sandstone_wall",2,"0.4"},{"minecraft:smooth_sandstone",3,"0.4"},
            {"minecraft:smooth_sandstone_slab",2,"0.4"},{"minecraft:smooth_sandstone_stairs",3,"0.4"},
            {"minecraft:chiseled_sandstone",3,"0.4"},{"minecraft:cut_sandstone",3,"0.4"},{"minecraft:cut_sandstone_slab",2,"0.4"},
            {"minecraft:red_sandstone",3,"0.4"},{"minecraft:red_sandstone_slab",2,"0.4"},{"minecraft:red_sandstone_stairs",3,"0.4"},
            {"minecraft:red_sandstone_wall",2,"0.4"},{"minecraft:smooth_red_sandstone",3,"0.4"},
            {"minecraft:smooth_red_sandstone_slab",2,"0.4"},{"minecraft:smooth_red_sandstone_stairs",3,"0.4"},
            {"minecraft:chiseled_red_sandstone",3,"0.4"},{"minecraft:cut_red_sandstone",3,"0.4"},{"minecraft:cut_red_sandstone_slab",2,"0.4"},
            {"minecraft:bricks",5,"0.4"},{"minecraft:brick_slab",3,"0.4"},{"minecraft:brick_stairs",4,"0.4"},{"minecraft:brick_wall",3,"0.4"},
            {"minecraft:mud_bricks",4,"0.4"},{"minecraft:mud_brick_slab",3,"0.4"},{"minecraft:mud_brick_stairs",4,"0.4"},{"minecraft:mud_brick_wall",3,"0.4"},
            {"minecraft:packed_mud",3,"0.4"},
            {"minecraft:nether_bricks",14,"0.4"},{"minecraft:nether_brick",3,"0.4"},
            {"minecraft:nether_brick_slab",3,"0.4"},{"minecraft:nether_brick_stairs",4,"0.4"},
            {"minecraft:nether_brick_wall",3,"0.4"},{"minecraft:nether_brick_fence",3,"0.4"},
            {"minecraft:chiseled_nether_bricks",5,"0.4"},{"minecraft:cracked_nether_bricks",4,"0.4"},
            {"minecraft:red_nether_bricks",5,"0.4"},{"minecraft:red_nether_brick_slab",3,"0.4"},
            {"minecraft:red_nether_brick_stairs",4,"0.4"},{"minecraft:red_nether_brick_wall",3,"0.4"},
            // ── Кварц
            {"minecraft:quartz",18,"0.5"},{"minecraft:quartz_block",70,"0.5"},{"minecraft:quartz_bricks",75,"0.5"},
            {"minecraft:quartz_pillar",70,"0.5"},{"minecraft:quartz_slab",35,"0.5"},{"minecraft:quartz_stairs",70,"0.5"},
            {"minecraft:smooth_quartz",70,"0.5"},{"minecraft:smooth_quartz_slab",35,"0.5"},{"minecraft:smooth_quartz_stairs",70,"0.5"},
            {"minecraft:chiseled_quartz_block",75,"0.5"},{"minecraft:nether_quartz_ore",70,"0.5"},
            // ── Уголь
            {"minecraft:coal",6,"0.5"},{"minecraft:charcoal",6,"0.5"},{"minecraft:coal_block",54,"0.5"},
            {"minecraft:coal_ore",7,"0.5"},{"minecraft:deepslate_coal_ore",8,"0.5"},
            {"minecraft:torch",1,"0.4"},{"minecraft:soul_torch",1,"0.4"},
            // ── Железо (25 за слиток)
            {"minecraft:iron_ingot",25,"0.5"},{"minecraft:raw_iron",22,"0.5"},
            {"minecraft:iron_block",300,"0.5"},{"minecraft:raw_iron_block",200,"0.5"},
            {"minecraft:iron_ore",28,"0.5"},{"minecraft:deepslate_iron_ore",30,"0.5"},{"minecraft:iron_nugget",2,"0.4"},
            {"minecraft:iron_sword",65,"0.35"},{"minecraft:iron_pickaxe",95,"0.4"},{"minecraft:iron_axe",90,"0.4"},
            {"minecraft:iron_shovel",35,"0.35"},{"minecraft:iron_hoe",65,"0.4"},
            {"minecraft:iron_helmet",200,"0.4"},{"minecraft:iron_chestplate",340,"0.4"},
            {"minecraft:iron_leggings",280,"0.4"},{"minecraft:iron_boots",200,"0.4"},
            {"minecraft:iron_horse_armor",200,"0.4"},
            {"minecraft:iron_bars",10,"0.4"},{"minecraft:iron_door",80,"0.4"},{"minecraft:iron_trapdoor",100,"0.4"},
            {"minecraft:bucket",60,"0.5"},{"minecraft:lava_bucket",60,"0.5"},{"minecraft:milk_bucket",70,"0.5"},
            {"minecraft:minecart",80,"0.4"},{"minecraft:chest_minecart",120,"0.4"},
            {"minecraft:furnace_minecart",100,"0.4"},{"minecraft:hopper_minecart",200,"0.4"},
            {"minecraft:tnt_minecart",120,"0.4"},{"minecraft:cauldron",120,"0.4"},{"minecraft:chain",20,"0.4"},
            // ── Золото (55 за слиток)
            {"minecraft:gold_ingot",55,"0.5"},{"minecraft:raw_gold",50,"0.5"},
            {"minecraft:gold_block",495,"0.5"},{"minecraft:raw_gold_block",450,"0.5"},
            {"minecraft:gold_ore",60,"0.5"},{"minecraft:deepslate_gold_ore",65,"0.5"},
            {"minecraft:nether_gold_ore",60,"0.5"},{"minecraft:gold_nugget",6,"0.4"},
            {"minecraft:golden_sword",140,"0.4"},{"minecraft:golden_pickaxe",200,"0.4"},{"minecraft:golden_axe",195,"0.4"},
            {"minecraft:golden_shovel",70,"0.4"},{"minecraft:golden_hoe",140,"0.4"},
            {"minecraft:golden_helmet",180,"0.4"},{"minecraft:golden_chestplate",300,"0.4"},
            {"minecraft:golden_leggings",250,"0.4"},{"minecraft:golden_boots",180,"0.4"},
            {"minecraft:golden_horse_armor",220,"0.4"},
            {"minecraft:golden_apple",120,"0.5"},{"minecraft:golden_carrot",50,"0.5"},
            // ── Медь (8 за слиток)
            {"minecraft:copper_ingot",2,"0.5"},{"minecraft:raw_copper",7,"0.5"},
            {"minecraft:copper_block",72,"0.5"},{"minecraft:raw_copper_block",55,"0.5"},
            {"minecraft:copper_ore",9,"0.5"},{"minecraft:deepslate_copper_ore",10,"0.5"},
            {"minecraft:copper_door",8,"0.4"},{"minecraft:copper_trapdoor",8,"0.4"},
            {"minecraft:copper_grate",6,"0.4"},{"minecraft:copper_bulb",8,"0.4"},
            {"minecraft:cut_copper",6,"0.4"},{"minecraft:cut_copper_slab",4,"0.4"},{"minecraft:cut_copper_stairs",6,"0.4"},
            {"minecraft:exposed_copper",5,"0.4"},{"minecraft:exposed_copper_door",6,"0.4"},
            {"minecraft:exposed_copper_trapdoor",6,"0.4"},{"minecraft:exposed_copper_grate",5,"0.4"},
            {"minecraft:exposed_copper_bulb",6,"0.4"},{"minecraft:exposed_cut_copper",5,"0.4"},
            {"minecraft:exposed_cut_copper_slab",3,"0.4"},{"minecraft:exposed_cut_copper_stairs",5,"0.4"},
            {"minecraft:oxidized_copper",4,"0.3"},{"minecraft:oxidized_copper_door",5,"0.3"},
            {"minecraft:oxidized_copper_trapdoor",5,"0.3"},{"minecraft:oxidized_copper_grate",4,"0.3"},
            {"minecraft:oxidized_copper_bulb",5,"0.3"},{"minecraft:oxidized_cut_copper",4,"0.3"},
            {"minecraft:oxidized_cut_copper_slab",3,"0.3"},{"minecraft:oxidized_cut_copper_stairs",4,"0.3"},
            {"minecraft:lightning_rod",18,"0.4"},
            // ── Редстоун (12 за порошок)
            {"minecraft:redstone",12,"0.5"},{"minecraft:redstone_block",108,"0.5"},
            {"minecraft:redstone_ore",14,"0.5"},{"minecraft:deepslate_redstone_ore",16,"0.5"},
            {"minecraft:redstone_torch",3,"0.4"},{"minecraft:redstone_lamp",5,"0.4"},
            {"minecraft:comparator",30,"0.4"},{"minecraft:repeater",20,"0.4"},
            {"minecraft:observer",50,"0.4"},{"minecraft:piston",50,"0.4"},{"minecraft:sticky_piston",70,"0.4"},
            {"minecraft:dispenser",70,"0.4"},{"minecraft:dropper",25,"0.4"},{"minecraft:hopper",130,"0.4"},
            {"minecraft:rail",10,"0.4"},{"minecraft:powered_rail",60,"0.4"},
            {"minecraft:detector_rail",6,"0.4"},{"minecraft:activator_rail",6,"0.4"},
            {"minecraft:lever",4,"0.4"},{"minecraft:tripwire_hook",5,"0.4"},
            {"minecraft:daylight_detector",8,"0.4"},{"minecraft:note_block",6,"0.4"},
            {"minecraft:jukebox",35,"0.4"},
            // ── Лазурит (20 за кристалл)
            {"minecraft:lapis_lazuli",20,"0.5"},{"minecraft:lapis_block",180,"0.5"},
            {"minecraft:lapis_ore",22,"0.5"},{"minecraft:deepslate_lapis_ore",24,"0.5"},
            // ── Алмаз (650 за алмаз)
            {"minecraft:diamond",650,"0.6"},{"minecraft:diamond_block",5850,"0.6"},
            {"minecraft:diamond_ore",700,"0.6"},{"minecraft:deepslate_diamond_ore",750,"0.6"},
            {"minecraft:diamond_sword",1600,"0.6"},{"minecraft:diamond_pickaxe",2400,"0.6"},
            {"minecraft:diamond_axe",2300,"0.6"},{"minecraft:diamond_shovel",800,"0.55"},{"minecraft:diamond_hoe",1600,"0.6"},
            {"minecraft:diamond_helmet",2000,"0.6"},{"minecraft:diamond_chestplate",3400,"0.6"},
            {"minecraft:diamond_leggings",2800,"0.6"},{"minecraft:diamond_boots",2000,"0.6"},
            {"minecraft:diamond_horse_armor",2500,"0.6"},
            // ── Изумруд (200)
            {"minecraft:emerald",200,"0.6"},{"minecraft:emerald_block",1800,"0.6"},
            {"minecraft:emerald_ore",220,"0.6"},{"minecraft:deepslate_emerald_ore",240,"0.6"},
            // ── Незерит
            {"minecraft:ancient_debris",800,"0.65"},{"minecraft:netherite_scrap",2400,"0.7"},
            {"minecraft:netherite_ingot",3200,"0.7"},{"minecraft:netherite_block",28800,"0.7"},
            {"minecraft:netherite_upgrade_smithing_template",800,"0.6"},
            {"minecraft:netherite_sword",5000,"0.65"},{"minecraft:netherite_pickaxe",6000,"0.65"},
            {"minecraft:netherite_axe",5800,"0.65"},{"minecraft:netherite_shovel",4200,"0.65"},{"minecraft:netherite_hoe",5000,"0.65"},
            {"minecraft:netherite_helmet",6300,"0.65"},{"minecraft:netherite_chestplate",7800,"0.65"},
            {"minecraft:netherite_leggings",7200,"0.65"},{"minecraft:netherite_boots",6300,"0.65"},
            // ── Деревянные инструменты
            {"minecraft:wooden_sword",10,"0.4"},{"minecraft:wooden_pickaxe",14,"0.4"},
            {"minecraft:wooden_axe",14,"0.4"},{"minecraft:wooden_shovel",7,"0.4"},{"minecraft:wooden_hoe",10,"0.4"},
            // ── Каменные инструменты
            {"minecraft:stone_sword",8,"0.35"},{"minecraft:stone_pickaxe",10,"0.4"},
            {"minecraft:stone_axe",10,"0.4"},{"minecraft:stone_shovel",6,"0.4"},{"minecraft:stone_hoe",8,"0.4"},
            // ── Прочее оружие
            {"minecraft:bow",45,"0.4"},{"minecraft:crossbow",60,"0.4"},{"minecraft:arrow",2,"0.5"},
            {"minecraft:spectral_arrow",3,"0.5"},{"minecraft:shield",50,"0.4"},
            {"minecraft:fishing_rod",40,"0.4"},{"minecraft:flint_and_steel",30,"0.4"},
            {"minecraft:compass",100,"0.4"},{"minecraft:clock",150,"0.4"},
            {"minecraft:spyglass",60,"0.4"},{"minecraft:brush",40,"0.4"},
            {"minecraft:carrot_on_a_stick",8,"0.4"},
            // ── Кожа / броня
            {"minecraft:leather",5,"0.4"},
            {"minecraft:leather_helmet",30,"0.4"},{"minecraft:leather_chestplate",50,"0.4"},
            {"minecraft:leather_leggings",40,"0.4"},{"minecraft:leather_boots",30,"0.4"},
            {"minecraft:leather_horse_armor",35,"0.4"},
            {"minecraft:chainmail_helmet",80,"0.4"},{"minecraft:chainmail_chestplate",130,"0.4"},
            {"minecraft:chainmail_leggings",110,"0.4"},{"minecraft:chainmail_boots",80,"0.4"},
            {"minecraft:turtle_helmet",800,"0.5"},{"minecraft:turtle_scute",150,"0.5"},
            {"minecraft:wolf_armor",60,"0.5"},
            // ── Еда
            {"minecraft:apple",15,"0.5"},{"minecraft:bread",12,"0.5"},{"minecraft:cookie",6,"0.5"},
            {"minecraft:cake",25,"0.5"},{"minecraft:pumpkin_pie",10,"0.5"},
            {"minecraft:wheat",3,"0.5"},{"minecraft:wheat_seeds",2,"0.5"},
            {"minecraft:carrot",5,"0.5"},{"minecraft:potato",4,"0.5"},{"minecraft:baked_potato",6,"0.5"},
            {"minecraft:beetroot",4,"0.5"},{"minecraft:beetroot_seeds",2,"0.5"},{"minecraft:beetroot_soup",10,"0.5"},
            {"minecraft:melon_seeds",2,"0.5"},{"minecraft:pumpkin_seeds",2,"0.5"},
            {"minecraft:melon_slice",4,"0.5"},{"minecraft:melon",25,"0.5"},
            {"minecraft:beef",8,"0.5"},{"minecraft:cooked_beef",18,"0.5"},
            {"minecraft:chicken",7,"0.5"},{"minecraft:cooked_chicken",14,"0.5"},
            {"minecraft:porkchop",8,"0.5"},{"minecraft:cooked_porkchop",18,"0.5"},
            {"minecraft:mutton",7,"0.5"},{"minecraft:cooked_mutton",14,"0.5"},
            {"minecraft:rabbit",7,"0.5"},{"minecraft:cooked_rabbit",14,"0.5"},{"minecraft:rabbit_stew",18,"0.5"},
            {"minecraft:cod",7,"0.5"},{"minecraft:cooked_cod",12,"0.5"},
            {"minecraft:salmon",7,"0.5"},{"minecraft:cooked_salmon",12,"0.5"},
            {"minecraft:tropical_fish",5,"0.5"},{"minecraft:pufferfish",5,"0.5"},
            {"minecraft:mushroom_stew",14,"0.5"},{"minecraft:suspicious_stew",15,"0.5"},
            {"minecraft:honey_bottle",15,"0.5"},{"minecraft:honey_block",60,"0.5"},
            {"minecraft:honeycomb",12,"0.5"},{"minecraft:honeycomb_block",48,"0.5"},
            {"minecraft:sweet_berries",4,"0.5"},{"minecraft:glow_berries",4,"0.5"},
            {"minecraft:dried_kelp",3,"0.5"},{"minecraft:dried_kelp_block",25,"0.5"},
            {"minecraft:sugar",3,"0.5"},{"minecraft:sugar_cane",3,"0.5"},
            {"minecraft:cocoa_beans",3,"0.5"},{"minecraft:kelp",3,"0.5"},
            {"minecraft:nether_wart",10,"0.5"},{"minecraft:nether_wart_block",12,"0.5"},
            // ── Яйца спавна
            {"minecraft:pig_spawn_egg",750,"0.5"},{"minecraft:cow_spawn_egg",750,"0.5"},
            {"minecraft:sheep_spawn_egg",750,"0.5"},{"minecraft:chicken_spawn_egg",750,"0.5"},
            {"minecraft:horse_spawn_egg",750,"0.5"},{"minecraft:donkey_spawn_egg",750,"0.5"},
            {"minecraft:mule_spawn_egg",750,"0.5"},{"minecraft:wolf_spawn_egg",750,"0.5"},
            {"minecraft:cat_spawn_egg",750,"0.5"},{"minecraft:fox_spawn_egg",750,"0.5"},
            {"minecraft:panda_spawn_egg",750,"0.5"},{"minecraft:ocelot_spawn_egg",750,"0.5"},
            {"minecraft:axolotl_spawn_egg",750,"0.5"},{"minecraft:frog_spawn_egg",750,"0.5"},
            {"minecraft:camel_spawn_egg",1000,"0.5"},{"minecraft:bee_spawn_egg",750,"0.5"},
            {"minecraft:bat_spawn_egg",400,"0.5"},{"minecraft:parrot_spawn_egg",1000,"0.5"},
            {"minecraft:goat_spawn_egg",750,"0.5"},{"minecraft:rabbit_spawn_egg",750,"0.5"},
            {"minecraft:turtle_spawn_egg",1000,"0.5"},{"minecraft:tadpole_spawn_egg",750,"0.5"},
            {"minecraft:zombie_spawn_egg",750,"0.5"},{"minecraft:skeleton_spawn_egg",750,"0.5"},
            {"minecraft:creeper_spawn_egg",1000,"0.5"},{"minecraft:spider_spawn_egg",750,"0.5"},
            {"minecraft:cave_spider_spawn_egg",1000,"0.5"},{"minecraft:enderman_spawn_egg",1000,"0.5"},
            {"minecraft:endermite_spawn_egg",750,"0.5"},{"minecraft:drowned_spawn_egg",750,"0.5"},
            {"minecraft:husk_spawn_egg",750,"0.5"},{"minecraft:stray_spawn_egg",750,"0.5"},
            {"minecraft:zombie_villager_spawn_egg",1000,"0.5"},{"minecraft:witch_spawn_egg",1000,"0.5"},
            {"minecraft:pillager_spawn_egg",1000,"0.5"},{"minecraft:vindicator_spawn_egg",1000,"0.5"},
            {"minecraft:ravager_spawn_egg",1500,"0.5"},{"minecraft:vex_spawn_egg",1000,"0.5"},
            {"minecraft:phantom_spawn_egg",1000,"0.5"},{"minecraft:silverfish_spawn_egg",750,"0.5"},
            {"minecraft:slime_spawn_egg",1000,"0.5"},{"minecraft:blaze_spawn_egg",1000,"0.5"},
            {"minecraft:ghast_spawn_egg",1500,"0.5"},{"minecraft:zombified_piglin_spawn_egg",1000,"0.5"},
            {"minecraft:piglin_spawn_egg",1000,"0.5"},{"minecraft:piglin_brute_spawn_egg",1250,"0.5"},
            {"minecraft:guardian_spawn_egg",1250,"0.5"},{"minecraft:elder_guardian_spawn_egg",2500,"0.5"},
            {"minecraft:shulker_spawn_egg",1500,"0.5"},{"minecraft:breeze_spawn_egg",1500,"0.5"},
            {"minecraft:squid_spawn_egg",750,"0.5"},{"minecraft:dolphin_spawn_egg",1000,"0.5"},
            {"minecraft:cod_spawn_egg",400,"0.5"},{"minecraft:salmon_spawn_egg",400,"0.5"},
            {"minecraft:pufferfish_spawn_egg",600,"0.5"},{"minecraft:tropical_fish_spawn_egg",600,"0.5"},
            {"minecraft:skeleton_horse_spawn_egg",1500,"0.5"},{"minecraft:zombie_horse_spawn_egg",1500,"0.5"},
            {"minecraft:villager_spawn_egg",1500,"0.5"},{"minecraft:iron_golem_spawn_egg",2500,"0.5"},
            {"minecraft:wither_skeleton_spawn_egg",1750,"0.5"},
            // ── Легендарные
            {"minecraft:elytra",4000,"0.7"},{"minecraft:totem_of_undying",3500,"0.7"},
            {"minecraft:trident",3000,"0.7"},
            {"minecraft:mace",2500,"0.6"},
            {"minecraft:beacon",5000,"0.7"},{"minecraft:end_crystal",600,"0.6"},
            {"minecraft:dragon_breath",2500,"0.7"},{"minecraft:dragon_egg",8000,"0.8"},
            {"minecraft:conduit",4000,"0.7"},
            {"minecraft:heavy_core",3000,"0.7"},
            // ── Ценные материалы
            {"minecraft:ender_pearl",80,"0.5"},{"minecraft:ender_eye",150,"0.5"},
            {"minecraft:blaze_rod",100,"0.5"},{"minecraft:blaze_powder",50,"0.5"},
            {"minecraft:ghast_tear",120,"0.5"},{"minecraft:magma_cream",55,"0.5"},
            {"minecraft:slime_ball",12,"0.5"},{"minecraft:slime_block",108,"0.5"},
            {"minecraft:spider_eye",8,"0.5"},{"minecraft:fermented_spider_eye",15,"0.5"},
            {"minecraft:gunpowder",6,"0.5"},{"minecraft:string",4,"0.5"},
            {"minecraft:feather",4,"0.5"},{"minecraft:bone",5,"0.5"},
            {"minecraft:bone_meal",5,"0.5"},{"minecraft:bone_block",40,"0.5"},
            {"minecraft:ink_sac",8,"0.5"},{"minecraft:glow_ink_sac",12,"0.5"},
            {"minecraft:prismarine_shard",80,"0.5"},{"minecraft:prismarine_crystals",80,"0.5"},
            {"minecraft:shulker_shell",180,"0.5"},{"minecraft:shulker_box",400,"0.5"},
            {"minecraft:phantom_membrane",120,"0.5"},{"minecraft:rabbit_foot",8,"0.5"},{"minecraft:rabbit_hide",6,"0.5"},
            {"minecraft:name_tag",30,"0.5"},{"minecraft:saddle",120,"0.5"},
            {"minecraft:lead",35,"0.5"},{"minecraft:nautilus_shell",120,"0.5"},
            {"minecraft:goat_horn",120,"0.5"},{"minecraft:amethyst_shard",12,"0.5"},
            {"minecraft:amethyst_block",50,"0.5"},{"minecraft:amethyst_cluster",12,"0.5"},
            {"minecraft:large_amethyst_bud",8,"0.5"},{"minecraft:medium_amethyst_bud",6,"0.5"},
            {"minecraft:small_amethyst_bud",4,"0.5"},{"minecraft:budding_amethyst",8,"0.5"},
            {"minecraft:echo_shard",800,"0.6"},{"minecraft:disc_fragment_5",80,"0.6"},
            {"minecraft:breeze_rod",80,"0.6"},
            // ── Шалкер коробки (все цвета)
            {"minecraft:black_shulker_box",400,"0.5"},{"minecraft:blue_shulker_box",400,"0.5"},
            {"minecraft:brown_shulker_box",400,"0.5"},{"minecraft:cyan_shulker_box",400,"0.5"},
            {"minecraft:gray_shulker_box",400,"0.5"},{"minecraft:green_shulker_box",400,"0.5"},
            {"minecraft:light_blue_shulker_box",400,"0.5"},{"minecraft:light_gray_shulker_box",400,"0.5"},
            {"minecraft:lime_shulker_box",400,"0.5"},{"minecraft:magenta_shulker_box",400,"0.5"},
            {"minecraft:orange_shulker_box",400,"0.5"},{"minecraft:pink_shulker_box",400,"0.5"},
            {"minecraft:purple_shulker_box",400,"0.5"},{"minecraft:red_shulker_box",400,"0.5"},
            {"minecraft:white_shulker_box",400,"0.5"},{"minecraft:yellow_shulker_box",400,"0.5"},
            // ── Мебель / верстаки
            {"minecraft:crafting_table",25,"0.4"},{"minecraft:furnace",20,"0.4"},
            {"minecraft:blast_furnace",35,"0.4"},{"minecraft:smoker",25,"0.4"},
            {"minecraft:anvil",500,"0.4"},{"minecraft:damaged_anvil",40,"0.3"},
            {"minecraft:chest",25,"0.4"},{"minecraft:barrel",25,"0.4"},
            {"minecraft:ender_chest",500,"0.5"},{"minecraft:trapped_chest",30,"0.4"},
            {"minecraft:enchanting_table",900,"0.5"},{"minecraft:bookshelf",80,"0.4"},
            {"minecraft:chiseled_bookshelf",8,"0.4"},{"minecraft:lectern",30,"0.4"},
            {"minecraft:grindstone",15,"0.4"},{"minecraft:smithing_table",25,"0.4"},
            {"minecraft:fletching_table",25,"0.4"},{"minecraft:cartography_table",25,"0.4"},
            {"minecraft:loom",25,"0.4"},{"minecraft:composter",8,"0.4"},
            {"minecraft:flower_pot",4,"0.4"},{"minecraft:painting",35,"0.5"},
            {"minecraft:item_frame",35,"0.5"},{"minecraft:glow_item_frame",50,"0.5"},
            {"minecraft:armor_stand",35,"0.4"},{"minecraft:campfire",8,"0.5"},
            {"minecraft:soul_campfire",8,"0.5"},{"minecraft:lantern",12,"0.5"},
            {"minecraft:soul_lantern",12,"0.5"},{"minecraft:sea_lantern",400,"0.5"},
            {"minecraft:ladder",4,"0.4"},{"minecraft:scaffolding",3,"0.4"},
            // ── Цветные блоки
            {"minecraft:white_wool",7,"0.4"},{"minecraft:orange_wool",7,"0.4"},{"minecraft:magenta_wool",7,"0.4"},
            {"minecraft:light_blue_wool",7,"0.4"},{"minecraft:yellow_wool",7,"0.4"},{"minecraft:lime_wool",7,"0.4"},
            {"minecraft:pink_wool",7,"0.4"},{"minecraft:gray_wool",7,"0.4"},{"minecraft:light_gray_wool",7,"0.4"},
            {"minecraft:cyan_wool",7,"0.4"},{"minecraft:purple_wool",7,"0.4"},{"minecraft:blue_wool",7,"0.4"},
            {"minecraft:brown_wool",7,"0.4"},{"minecraft:green_wool",7,"0.4"},{"minecraft:red_wool",7,"0.4"},
            {"minecraft:black_wool",7,"0.4"},{"minecraft:wool",7,"0.4"},
            {"minecraft:white_concrete",5,"0.4"},{"minecraft:orange_concrete",5,"0.4"},{"minecraft:magenta_concrete",5,"0.4"},
            {"minecraft:light_blue_concrete",5,"0.4"},{"minecraft:yellow_concrete",5,"0.4"},{"minecraft:lime_concrete",5,"0.4"},
            {"minecraft:pink_concrete",5,"0.4"},{"minecraft:gray_concrete",5,"0.4"},{"minecraft:light_gray_concrete",5,"0.4"},
            {"minecraft:cyan_concrete",5,"0.4"},{"minecraft:purple_concrete",5,"0.4"},{"minecraft:blue_concrete",5,"0.4"},
            {"minecraft:brown_concrete",5,"0.4"},{"minecraft:green_concrete",5,"0.4"},{"minecraft:red_concrete",5,"0.4"},
            {"minecraft:black_concrete",5,"0.4"},
            {"minecraft:white_concrete_powder",3,"0.4"},{"minecraft:orange_concrete_powder",3,"0.4"},
            {"minecraft:magenta_concrete_powder",3,"0.4"},{"minecraft:light_blue_concrete_powder",3,"0.4"},
            {"minecraft:yellow_concrete_powder",3,"0.4"},{"minecraft:lime_concrete_powder",3,"0.4"},
            {"minecraft:pink_concrete_powder",3,"0.4"},{"minecraft:gray_concrete_powder",3,"0.4"},
            {"minecraft:light_gray_concrete_powder",3,"0.4"},{"minecraft:cyan_concrete_powder",3,"0.4"},
            {"minecraft:purple_concrete_powder",3,"0.4"},{"minecraft:blue_concrete_powder",3,"0.4"},
            {"minecraft:brown_concrete_powder",3,"0.4"},{"minecraft:green_concrete_powder",3,"0.4"},
            {"minecraft:red_concrete_powder",3,"0.4"},{"minecraft:black_concrete_powder",3,"0.4"},
            {"minecraft:terracotta",4,"0.4"},
            {"minecraft:white_terracotta",4,"0.4"},{"minecraft:orange_terracotta",4,"0.4"},{"minecraft:magenta_terracotta",4,"0.4"},
            {"minecraft:light_blue_terracotta",4,"0.4"},{"minecraft:yellow_terracotta",4,"0.4"},{"minecraft:lime_terracotta",4,"0.4"},
            {"minecraft:pink_terracotta",4,"0.4"},{"minecraft:gray_terracotta",4,"0.4"},{"minecraft:light_gray_terracotta",4,"0.4"},
            {"minecraft:cyan_terracotta",4,"0.4"},{"minecraft:purple_terracotta",4,"0.4"},{"minecraft:blue_terracotta",4,"0.4"},
            {"minecraft:brown_terracotta",4,"0.4"},{"minecraft:green_terracotta",4,"0.4"},{"minecraft:red_terracotta",4,"0.4"},
            {"minecraft:black_terracotta",4,"0.4"},
            {"minecraft:white_glazed_terracotta",5,"0.4"},{"minecraft:orange_glazed_terracotta",5,"0.4"},
            {"minecraft:magenta_glazed_terracotta",5,"0.4"},{"minecraft:light_blue_glazed_terracotta",5,"0.4"},
            {"minecraft:yellow_glazed_terracotta",5,"0.4"},{"minecraft:lime_glazed_terracotta",5,"0.4"},
            {"minecraft:pink_glazed_terracotta",5,"0.4"},{"minecraft:gray_glazed_terracotta",5,"0.4"},
            {"minecraft:light_gray_glazed_terracotta",5,"0.4"},{"minecraft:cyan_glazed_terracotta",5,"0.4"},
            {"minecraft:purple_glazed_terracotta",5,"0.4"},{"minecraft:blue_glazed_terracotta",5,"0.4"},
            {"minecraft:brown_glazed_terracotta",5,"0.4"},{"minecraft:green_glazed_terracotta",5,"0.4"},
            {"minecraft:red_glazed_terracotta",5,"0.4"},{"minecraft:black_glazed_terracotta",5,"0.4"},
            {"minecraft:white_dye",4,"0.5"},{"minecraft:orange_dye",4,"0.5"},{"minecraft:magenta_dye",4,"0.5"},
            {"minecraft:light_blue_dye",4,"0.5"},{"minecraft:yellow_dye",4,"0.5"},{"minecraft:lime_dye",4,"0.5"},
            {"minecraft:pink_dye",4,"0.5"},{"minecraft:gray_dye",4,"0.5"},{"minecraft:light_gray_dye",4,"0.5"},
            {"minecraft:cyan_dye",4,"0.5"},{"minecraft:purple_dye",4,"0.5"},{"minecraft:blue_dye",4,"0.5"},
            {"minecraft:brown_dye",4,"0.5"},{"minecraft:green_dye",4,"0.5"},{"minecraft:red_dye",4,"0.5"},
            {"minecraft:black_dye",4,"0.5"},
            {"minecraft:white_stained_glass",5,"0.4"},{"minecraft:orange_stained_glass",5,"0.4"},{"minecraft:magenta_stained_glass",5,"0.4"},
            {"minecraft:light_blue_stained_glass",5,"0.4"},{"minecraft:yellow_stained_glass",5,"0.4"},{"minecraft:lime_stained_glass",5,"0.4"},
            {"minecraft:pink_stained_glass",5,"0.4"},{"minecraft:gray_stained_glass",5,"0.4"},{"minecraft:light_gray_stained_glass",5,"0.4"},
            {"minecraft:cyan_stained_glass",5,"0.4"},{"minecraft:purple_stained_glass",5,"0.4"},{"minecraft:blue_stained_glass",5,"0.4"},
            {"minecraft:brown_stained_glass",5,"0.4"},{"minecraft:green_stained_glass",5,"0.4"},{"minecraft:red_stained_glass",5,"0.4"},
            {"minecraft:black_stained_glass",5,"0.4"},
            {"minecraft:white_stained_glass_pane",4,"0.4"},{"minecraft:orange_stained_glass_pane",4,"0.4"},
            {"minecraft:magenta_stained_glass_pane",4,"0.4"},{"minecraft:light_blue_stained_glass_pane",4,"0.4"},
            {"minecraft:yellow_stained_glass_pane",4,"0.4"},{"minecraft:lime_stained_glass_pane",4,"0.4"},
            {"minecraft:pink_stained_glass_pane",4,"0.4"},{"minecraft:gray_stained_glass_pane",4,"0.4"},
            {"minecraft:light_gray_stained_glass_pane",4,"0.4"},{"minecraft:cyan_stained_glass_pane",4,"0.4"},
            {"minecraft:purple_stained_glass_pane",4,"0.4"},{"minecraft:blue_stained_glass_pane",4,"0.4"},
            {"minecraft:brown_stained_glass_pane",4,"0.4"},{"minecraft:green_stained_glass_pane",4,"0.4"},
            {"minecraft:red_stained_glass_pane",4,"0.4"},{"minecraft:black_stained_glass_pane",4,"0.4"},
            {"minecraft:white_bed",15,"0.5"},{"minecraft:orange_bed",15,"0.5"},{"minecraft:magenta_bed",15,"0.5"},
            {"minecraft:light_blue_bed",15,"0.5"},{"minecraft:yellow_bed",15,"0.5"},{"minecraft:lime_bed",15,"0.5"},
            {"minecraft:pink_bed",15,"0.5"},{"minecraft:gray_bed",15,"0.5"},{"minecraft:light_gray_bed",15,"0.5"},
            {"minecraft:cyan_bed",15,"0.5"},{"minecraft:purple_bed",15,"0.5"},{"minecraft:blue_bed",15,"0.5"},
            {"minecraft:brown_bed",15,"0.5"},{"minecraft:green_bed",15,"0.5"},{"minecraft:red_bed",15,"0.5"},
            {"minecraft:black_bed",15,"0.5"},
            {"minecraft:white_banner",10,"0.5"},{"minecraft:orange_banner",10,"0.5"},{"minecraft:magenta_banner",10,"0.5"},
            {"minecraft:light_blue_banner",10,"0.5"},{"minecraft:yellow_banner",10,"0.5"},{"minecraft:lime_banner",10,"0.5"},
            {"minecraft:pink_banner",10,"0.5"},{"minecraft:gray_banner",10,"0.5"},{"minecraft:light_gray_banner",10,"0.5"},
            {"minecraft:cyan_banner",10,"0.5"},{"minecraft:purple_banner",10,"0.5"},{"minecraft:blue_banner",10,"0.5"},
            {"minecraft:brown_banner",10,"0.5"},{"minecraft:green_banner",10,"0.5"},{"minecraft:red_banner",10,"0.5"},
            {"minecraft:black_banner",10,"0.5"},
            {"minecraft:candle",8,"0.5"},{"minecraft:white_candle",8,"0.5"},{"minecraft:orange_candle",8,"0.5"},
            {"minecraft:magenta_candle",8,"0.5"},{"minecraft:light_blue_candle",8,"0.5"},{"minecraft:yellow_candle",8,"0.5"},
            {"minecraft:lime_candle",8,"0.5"},{"minecraft:pink_candle",8,"0.5"},{"minecraft:gray_candle",8,"0.5"},
            {"minecraft:light_gray_candle",8,"0.5"},{"minecraft:cyan_candle",8,"0.5"},{"minecraft:purple_candle",8,"0.5"},
            {"minecraft:blue_candle",8,"0.5"},{"minecraft:brown_candle",8,"0.5"},{"minecraft:green_candle",8,"0.5"},
            {"minecraft:red_candle",8,"0.5"},{"minecraft:black_candle",8,"0.5"},
            // ── Нижний мир / Конец
            {"minecraft:netherrack",4,"0.4"},{"minecraft:soul_sand",8,"0.5"},{"minecraft:soul_soil",8,"0.5"},
            {"minecraft:glowstone",5,"0.4"},{"minecraft:glowstone_dust",3,"0.4"},
            {"minecraft:magma_block",8,"0.5"},{"minecraft:obsidian",45,"0.5"},
            {"minecraft:crying_obsidian",55,"0.5"},{"minecraft:respawn_anchor",280,"0.5"},
            {"minecraft:end_stone",3,"0.4"},{"minecraft:end_stone_bricks",4,"0.4"},
            {"minecraft:end_stone_brick_slab",3,"0.4"},{"minecraft:end_stone_brick_stairs",4,"0.4"},
            {"minecraft:end_stone_brick_wall",3,"0.4"},
            {"minecraft:purpur_block",20,"0.5"},{"minecraft:purpur_pillar",20,"0.5"},
            {"minecraft:purpur_slab",12,"0.5"},{"minecraft:purpur_stairs",18,"0.5"},
            {"minecraft:chorus_flower",18,"0.5"},{"minecraft:chorus_fruit",18,"0.5"},
            {"minecraft:chorus_plant",5,"0.5"},{"minecraft:popped_chorus_fruit",18,"0.5"},
            {"minecraft:end_rod",15,"0.5"},// ── Скалк
            {"minecraft:sculk",15,"0.5"},{"minecraft:sculk_catalyst",30,"0.5"},
            {"minecraft:sculk_sensor",25,"0.5"},{"minecraft:sculk_shrieker",30,"0.5"},{"minecraft:sculk_vein",12,"0.5"},
            // ── Природные
            {"minecraft:cactus",3,"0.5"},{"minecraft:fern",3,"0.3"},{"minecraft:vine",3,"0.5"},
            {"minecraft:lily_pad",4,"0.5"},{"minecraft:seagrass",3,"0.5"},
            {"minecraft:moss_block",5,"0.4"},{"minecraft:azalea",5,"0.5"},{"minecraft:flowering_azalea",6,"0.5"},
            {"minecraft:azalea_leaves",3,"0.3"},{"minecraft:dead_bush",2,"0.3"},
            {"minecraft:dandelion",3,"0.4"},{"minecraft:poppy",3,"0.4"},{"minecraft:blue_orchid",4,"0.4"},
            {"minecraft:allium",3,"0.4"},{"minecraft:azure_bluet",3,"0.4"},{"minecraft:red_tulip",3,"0.4"},
            {"minecraft:orange_tulip",3,"0.4"},{"minecraft:white_tulip",3,"0.4"},{"minecraft:pink_tulip",3,"0.4"},
            {"minecraft:oxeye_daisy",3,"0.4"},{"minecraft:cornflower",3,"0.4"},
            {"minecraft:lily_of_the_valley",3,"0.4"},{"minecraft:wither_rose",6,"0.5"},
            {"minecraft:sunflower",5,"0.4"},{"minecraft:rose_bush",4,"0.4"},{"minecraft:peony",4,"0.4"},
            {"minecraft:pink_petals",4,"0.5"},{"minecraft:torchflower",6,"0.5"},{"minecraft:torchflower_seeds",4,"0.5"},
            {"minecraft:pitcher_plant",6,"0.5"},{"minecraft:pitcher_pod",4,"0.5"},
            {"minecraft:spore_blossom",12,"0.5"},{"minecraft:small_dripleaf",4,"0.4"},{"minecraft:glow_lichen",5,"0.4"},
            {"minecraft:red_mushroom",4,"0.5"},{"minecraft:brown_mushroom",4,"0.5"},
            {"minecraft:red_mushroom_block",5,"0.4"},{"minecraft:brown_mushroom_block",5,"0.4"},
            // ── Коралл / море
            {"minecraft:prismarine",160,"0.5"},{"minecraft:prismarine_bricks",180,"0.5"},
            {"minecraft:dark_prismarine",180,"0.5"},{"minecraft:prismarine_slab",80,"0.5"},
            {"minecraft:prismarine_stairs",160,"0.5"},{"minecraft:prismarine_wall",80,"0.5"},
            {"minecraft:prismarine_brick_slab",90,"0.5"},{"minecraft:prismarine_brick_stairs",180,"0.5"},
            {"minecraft:sponge",20,"0.5"},{"minecraft:wet_sponge",20,"0.5"},{"minecraft:sea_pickle",4,"0.5"},
            {"minecraft:tube_coral",6,"0.4"},{"minecraft:brain_coral",6,"0.4"},{"minecraft:bubble_coral",6,"0.4"},
            {"minecraft:fire_coral",6,"0.4"},{"minecraft:horn_coral",6,"0.4"},
            {"minecraft:tube_coral_block",6,"0.4"},{"minecraft:brain_coral_block",6,"0.4"},
            {"minecraft:bubble_coral_block",6,"0.4"},{"minecraft:fire_coral_block",6,"0.4"},{"minecraft:horn_coral_block",6,"0.4"},
            {"minecraft:tube_coral_fan",5,"0.4"},{"minecraft:brain_coral_fan",5,"0.4"},
            {"minecraft:bubble_coral_fan",5,"0.4"},{"minecraft:fire_coral_fan",5,"0.4"},{"minecraft:horn_coral_fan",5,"0.4"},
            {"minecraft:dead_tube_coral",3,"0.3"},{"minecraft:dead_brain_coral",3,"0.3"},
            {"minecraft:dead_bubble_coral",3,"0.3"},{"minecraft:dead_fire_coral",3,"0.3"},{"minecraft:dead_horn_coral",3,"0.3"},
            {"minecraft:dead_tube_coral_block",3,"0.3"},{"minecraft:dead_brain_coral_block",3,"0.3"},
            {"minecraft:dead_bubble_coral_block",3,"0.3"},{"minecraft:dead_fire_coral_block",3,"0.3"},
            {"minecraft:dead_horn_coral_block",3,"0.3"},
            {"minecraft:axolotl_bucket",8,"0.5"},{"minecraft:cod_bucket",8,"0.5"},
            {"minecraft:salmon_bucket",8,"0.5"},{"minecraft:tropical_fish_bucket",10,"0.5"},
            {"minecraft:pufferfish_bucket",10,"0.5"},{"minecraft:tadpole_bucket",8,"0.5"},
            {"minecraft:ochre_froglight",6,"0.4"},{"minecraft:verdant_froglight",6,"0.4"},{"minecraft:pearlescent_froglight",6,"0.4"},
            {"minecraft:packed_ice",5,"0.4"},{"minecraft:blue_ice",8,"0.4"},{"minecraft:snow_block",3,"0.4"},
            // ── Утилиты / книги
            {"minecraft:paper",3,"0.5"},{"minecraft:book",12,"0.5"},
            {"minecraft:writable_book",12,"0.5"},{"minecraft:written_book",12,"0.5"},
            {"minecraft:enchanted_book",200,"0.5"},{"minecraft:map",15,"0.5"},{"minecraft:filled_map",20,"0.5"},
            {"minecraft:firework_rocket",10,"0.5"},{"minecraft:firework_star",10,"0.5"},
            {"minecraft:egg",20,"0.5"},{"minecraft:snowball",3,"0.4"},{"minecraft:bowl",3,"0.5"},
            {"minecraft:glass_bottle",6,"0.5"},{"minecraft:fire_charge",10,"0.5"},
            {"minecraft:tnt",30,"0.5"},{"minecraft:flint",4,"0.5"},
            {"minecraft:stick",2,"0.5"},{"minecraft:hay_block",25,"0.5"},
            {"minecraft:pumpkin",5,"0.5"},{"minecraft:jack_o_lantern",12,"0.5"},
            {"minecraft:bee_nest",5,"0.5"},{"minecraft:beehive",6,"0.5"},
            {"minecraft:spawner",10000,"1.0"},{"minecraft:player_head",5,"0.5"},{"minecraft:creeper_head",300,"0.5"},
            {"minecraft:zombie_head",300,"0.5"},{"minecraft:skeleton_skull",8,"0.5"},
            {"minecraft:wither_skeleton_skull",4000,"0.6"},{"minecraft:dragon_head",2000,"0.6"},
            {"minecraft:piglin_head",6,"0.5"},
            {"minecraft:oak_boat",18,"0.4"},{"minecraft:spruce_boat",18,"0.4"},{"minecraft:birch_boat",18,"0.4"},
            {"minecraft:jungle_boat",18,"0.4"},{"minecraft:acacia_boat",18,"0.4"},{"minecraft:dark_oak_boat",18,"0.4"},
            {"minecraft:cherry_boat",20,"0.4"},{"minecraft:mangrove_boat",18,"0.4"},
            {"minecraft:oak_chest_boat",40,"0.4"},{"minecraft:spruce_chest_boat",40,"0.4"},
            {"minecraft:birch_chest_boat",40,"0.4"},{"minecraft:jungle_chest_boat",40,"0.4"},
            {"minecraft:acacia_chest_boat",40,"0.4"},{"minecraft:dark_oak_chest_boat",40,"0.4"},
            {"minecraft:cherry_chest_boat",45,"0.4"},{"minecraft:mangrove_chest_boat",40,"0.4"},
            // ── Черепки, трафареты
            {"minecraft:angler_pottery_sherd",30,"0.6"},{"minecraft:archer_pottery_sherd",30,"0.6"},
            {"minecraft:arms_up_pottery_sherd",30,"0.6"},{"minecraft:blade_pottery_sherd",30,"0.6"},
            {"minecraft:heartbreak_pottery_sherd",30,"0.6"},{"minecraft:howl_pottery_sherd",30,"0.6"},
            {"minecraft:miner_pottery_sherd",30,"0.6"},{"minecraft:mourner_pottery_sherd",30,"0.6"},
            {"minecraft:prize_pottery_sherd",30,"0.6"},{"minecraft:sheaf_pottery_sherd",30,"0.6"},
            {"minecraft:shelter_pottery_sherd",30,"0.6"},{"minecraft:skull_pottery_sherd",30,"0.6"},
            {"minecraft:snort_pottery_sherd",30,"0.6"},{"minecraft:placing_pottery_sherd",30,"0.6"},
            {"minecraft:heart_pottery_sherd",30,"0.6"},
            {"minecraft:sentry_armor_trim_smithing_template",100,"0.6"},
            {"minecraft:vex_armor_trim_smithing_template",100,"0.6"},
            {"minecraft:wild_armor_trim_smithing_template",100,"0.6"},
            {"minecraft:dune_armor_trim_smithing_template",100,"0.6"},
            {"minecraft:wayfinder_armor_trim_smithing_template",100,"0.6"},
            {"minecraft:ward_armor_trim_smithing_template",100,"0.6"},
            {"minecraft:tide_armor_trim_smithing_template",100,"0.6"},
            {"minecraft:spire_armor_trim_smithing_template",100,"0.6"},
            {"minecraft:plains_armor_trim_smithing_template",100,"0.6"},
            {"minecraft:trail_armor_trim_smithing_template",100,"0.6"},
            {"minecraft:silence_armor_trim_smithing_template",300,"0.6"},
            {"minecraft:bolt_armor_trim_smithing_template",150,"0.6"},
            {"minecraft:flow_armor_trim_smithing_template",150,"0.6"},
            {"minecraft:creeper_banner_pattern",10,"0.5"},{"minecraft:skull_banner_pattern",10,"0.5"},
            {"minecraft:flower_banner_pattern",10,"0.5"},{"minecraft:mojang_banner_pattern",10,"0.5"},
            {"minecraft:globe_banner_pattern",10,"0.5"},{"minecraft:piglin_banner_pattern",10,"0.5"},
            // ── Пластинки
            {"minecraft:music_disc_13",600,"0.6"},{"minecraft:music_disc_cat",600,"0.6"},
            {"minecraft:music_disc_blocks",600,"0.6"},{"minecraft:music_disc_chirp",600,"0.6"},
            {"minecraft:music_disc_far",600,"0.6"},{"minecraft:music_disc_mall",600,"0.6"},
            {"minecraft:music_disc_mellohi",600,"0.6"},{"minecraft:music_disc_stal",600,"0.6"},
            {"minecraft:music_disc_strad",600,"0.6"},{"minecraft:music_disc_ward",600,"0.6"},
            {"minecraft:music_disc_11",600,"0.6"},{"minecraft:music_disc_wait",600,"0.6"},
            {"minecraft:music_disc_otherside",2000,"0.7"},{"minecraft:music_disc_5",2500,"0.7"},
            {"minecraft:music_disc_pigstep",2000,"0.7"},{"minecraft:music_disc_relic",2000,"0.7"},
            {"minecraft:music_disc_precipice",2000,"0.7"},{"minecraft:music_disc_creator",2000,"0.7"},
            {"minecraft:music_disc_creator_music_box",600,"0.6"},
            // ── Зелья обычные (potion:effect)
            {"potion:water",5,"0.3"},{"potion:mundane",5,"0.3"},{"potion:thick",5,"0.3"},{"potion:awkward",10,"0.3"},
            {"potion:night_vision",80,"0.5"},{"potion:long_night_vision",120,"0.5"},
            {"potion:invisibility",80,"0.5"},{"potion:long_invisibility",120,"0.5"},
            {"potion:leaping",60,"0.5"},{"potion:long_leaping",90,"0.5"},{"potion:strong_leaping",100,"0.5"},
            {"potion:fire_resistance",80,"0.5"},{"potion:long_fire_resistance",120,"0.5"},
            {"potion:swiftness",60,"0.5"},{"potion:long_swiftness",90,"0.5"},{"potion:strong_swiftness",100,"0.5"},
            {"potion:slowness",50,"0.5"},{"potion:long_slowness",80,"0.5"},{"potion:strong_slowness",90,"0.5"},
            {"potion:water_breathing",80,"0.5"},{"potion:long_water_breathing",120,"0.5"},
            {"potion:healing",100,"0.5"},{"potion:strong_healing",180,"0.5"},
            {"potion:harming",80,"0.5"},{"potion:strong_harming",140,"0.5"},
            {"potion:poison",80,"0.5"},{"potion:long_poison",120,"0.5"},{"potion:strong_poison",150,"0.5"},
            {"potion:regeneration",120,"0.5"},{"potion:long_regeneration",180,"0.5"},{"potion:strong_regeneration",220,"0.5"},
            {"potion:strength",100,"0.5"},{"potion:long_strength",150,"0.5"},{"potion:strong_strength",200,"0.5"},
            {"potion:weakness",50,"0.5"},{"potion:long_weakness",80,"0.5"},
            {"potion:luck",200,"0.5"},{"potion:turtle_master",150,"0.5"},{"potion:long_turtle_master",200,"0.5"},{"potion:strong_turtle_master",250,"0.5"},
            {"potion:slow_falling",80,"0.5"},{"potion:long_slow_falling",120,"0.5"},
            {"potion:wind_charged",150,"0.5"},{"potion:weaving",150,"0.5"},{"potion:oozing",150,"0.5"},{"potion:infested",150,"0.5"},
            // ── Бросаемые зелья (splash_potion:effect)
            {"splash_potion:water",5,"0.3"},{"splash_potion:mundane",5,"0.3"},{"splash_potion:thick",5,"0.3"},{"splash_potion:awkward",10,"0.3"},
            {"splash_potion:night_vision",70,"0.5"},{"splash_potion:long_night_vision",100,"0.5"},
            {"splash_potion:invisibility",70,"0.5"},{"splash_potion:long_invisibility",100,"0.5"},
            {"splash_potion:leaping",50,"0.5"},{"splash_potion:long_leaping",80,"0.5"},{"splash_potion:strong_leaping",90,"0.5"},
            {"splash_potion:fire_resistance",70,"0.5"},{"splash_potion:long_fire_resistance",100,"0.5"},
            {"splash_potion:swiftness",50,"0.5"},{"splash_potion:long_swiftness",80,"0.5"},{"splash_potion:strong_swiftness",90,"0.5"},
            {"splash_potion:slowness",50,"0.5"},{"splash_potion:long_slowness",70,"0.5"},{"splash_potion:strong_slowness",80,"0.5"},
            {"splash_potion:water_breathing",70,"0.5"},{"splash_potion:long_water_breathing",100,"0.5"},
            {"splash_potion:healing",90,"0.5"},{"splash_potion:strong_healing",160,"0.5"},
            {"splash_potion:harming",70,"0.5"},{"splash_potion:strong_harming",130,"0.5"},
            {"splash_potion:poison",70,"0.5"},{"splash_potion:long_poison",100,"0.5"},{"splash_potion:strong_poison",130,"0.5"},
            {"splash_potion:regeneration",100,"0.5"},{"splash_potion:long_regeneration",150,"0.5"},{"splash_potion:strong_regeneration",200,"0.5"},
            {"splash_potion:strength",90,"0.5"},{"splash_potion:long_strength",130,"0.5"},{"splash_potion:strong_strength",180,"0.5"},
            {"splash_potion:weakness",50,"0.5"},{"splash_potion:long_weakness",70,"0.5"},
            {"splash_potion:turtle_master",130,"0.5"},{"splash_potion:long_turtle_master",180,"0.5"},{"splash_potion:slow_falling",70,"0.5"},{"splash_potion:long_slow_falling",100,"0.5"},
            {"splash_potion:luck",180,"0.5"},
            {"splash_potion:wind_charged",130,"0.5"},{"splash_potion:weaving",130,"0.5"},{"splash_potion:oozing",130,"0.5"},{"splash_potion:infested",130,"0.5"},
            // ── Стойкие зелья (lingering_potion:effect)
            {"lingering_potion:water",5,"0.3"},{"lingering_potion:mundane",5,"0.3"},{"lingering_potion:thick",5,"0.3"},{"lingering_potion:awkward",10,"0.3"},
            {"lingering_potion:night_vision",100,"0.5"},{"lingering_potion:long_night_vision",140,"0.5"},
            {"lingering_potion:invisibility",100,"0.5"},{"lingering_potion:long_invisibility",140,"0.5"},
            {"lingering_potion:leaping",80,"0.5"},{"lingering_potion:long_leaping",110,"0.5"},{"lingering_potion:strong_leaping",130,"0.5"},
            {"lingering_potion:fire_resistance",100,"0.5"},{"lingering_potion:long_fire_resistance",140,"0.5"},
            {"lingering_potion:swiftness",80,"0.5"},{"lingering_potion:long_swiftness",110,"0.5"},{"lingering_potion:strong_swiftness",130,"0.5"},
            {"lingering_potion:slowness",70,"0.5"},{"lingering_potion:long_slowness",100,"0.5"},{"lingering_potion:strong_slowness",120,"0.5"},
            {"lingering_potion:water_breathing",100,"0.5"},{"lingering_potion:long_water_breathing",140,"0.5"},
            {"lingering_potion:healing",120,"0.5"},{"lingering_potion:strong_healing",200,"0.5"},
            {"lingering_potion:harming",90,"0.5"},{"lingering_potion:strong_harming",160,"0.5"},
            {"lingering_potion:poison",100,"0.5"},{"lingering_potion:long_poison",140,"0.5"},{"lingering_potion:strong_poison",180,"0.5"},
            {"lingering_potion:regeneration",150,"0.5"},{"lingering_potion:long_regeneration",200,"0.5"},{"lingering_potion:strong_regeneration",250,"0.5"},
            {"lingering_potion:strength",120,"0.5"},{"lingering_potion:long_strength",160,"0.5"},{"lingering_potion:strong_strength",220,"0.5"},
            {"lingering_potion:weakness",70,"0.5"},{"lingering_potion:long_weakness",100,"0.5"},
            {"lingering_potion:turtle_master",170,"0.5"},{"lingering_potion:long_turtle_master",220,"0.5"},
            {"lingering_potion:slow_falling",100,"0.5"},{"lingering_potion:long_slow_falling",140,"0.5"},
            {"lingering_potion:luck",200,"0.5"},
            {"lingering_potion:wind_charged",160,"0.5"},{"lingering_potion:weaving",160,"0.5"},{"lingering_potion:oozing",160,"0.5"},{"lingering_potion:infested",160,"0.5"},
            // ── Стрелы с зельями (tipped_arrow:effect)
            {"tipped_arrow:night_vision",5,"0.5"},{"tipped_arrow:long_night_vision",8,"0.5"},
            {"tipped_arrow:invisibility",5,"0.5"},{"tipped_arrow:long_invisibility",8,"0.5"},
            {"tipped_arrow:leaping",4,"0.5"},{"tipped_arrow:long_leaping",6,"0.5"},{"tipped_arrow:strong_leaping",7,"0.5"},
            {"tipped_arrow:fire_resistance",5,"0.5"},{"tipped_arrow:long_fire_resistance",8,"0.5"},
            {"tipped_arrow:swiftness",4,"0.5"},{"tipped_arrow:long_swiftness",6,"0.5"},{"tipped_arrow:strong_swiftness",7,"0.5"},
            {"tipped_arrow:slowness",4,"0.5"},{"tipped_arrow:long_slowness",6,"0.5"},{"tipped_arrow:strong_slowness",7,"0.5"},
            {"tipped_arrow:water_breathing",5,"0.5"},{"tipped_arrow:long_water_breathing",8,"0.5"},
            {"tipped_arrow:healing",8,"0.5"},{"tipped_arrow:strong_healing",14,"0.5"},
            {"tipped_arrow:harming",6,"0.5"},{"tipped_arrow:strong_harming",10,"0.5"},
            {"tipped_arrow:poison",5,"0.5"},{"tipped_arrow:long_poison",8,"0.5"},{"tipped_arrow:strong_poison",10,"0.5"},
            {"tipped_arrow:regeneration",8,"0.5"},{"tipped_arrow:long_regeneration",12,"0.5"},{"tipped_arrow:strong_regeneration",15,"0.5"},
            {"tipped_arrow:strength",7,"0.5"},{"tipped_arrow:long_strength",10,"0.5"},{"tipped_arrow:strong_strength",14,"0.5"},
            {"tipped_arrow:weakness",4,"0.5"},{"tipped_arrow:long_weakness",6,"0.5"},
            {"tipped_arrow:turtle_master",10,"0.5"},{"tipped_arrow:long_turtle_master",14,"0.5"},{"tipped_arrow:slow_falling",5,"0.5"},{"tipped_arrow:long_slow_falling",8,"0.5"},
            {"tipped_arrow:luck",15,"0.5"},{"tipped_arrow:long_luck",20,"0.5"},{"tipped_arrow:wind_charged",12,"0.5"},{"tipped_arrow:weaving",12,"0.5"},
            {"tipped_arrow:oozing",12,"0.5"},{"tipped_arrow:infested",12,"0.5"},
            // ── Зачарованные книги (enchanted_book:enchantment_level)
            // Защита брони
            {"enchanted_book:protection",300,"0.5"},{"enchanted_book:protection_2",600,"0.5"},{"enchanted_book:protection_3",900,"0.5"},{"enchanted_book:protection_4",1800,"0.6"},
            {"enchanted_book:fire_protection",200,"0.5"},{"enchanted_book:fire_protection_2",400,"0.5"},{"enchanted_book:fire_protection_3",600,"0.5"},{"enchanted_book:fire_protection_4",1200,"0.6"},
            {"enchanted_book:blast_protection",200,"0.5"},{"enchanted_book:blast_protection_2",400,"0.5"},{"enchanted_book:blast_protection_3",600,"0.5"},{"enchanted_book:blast_protection_4",1200,"0.6"},
            {"enchanted_book:projectile_protection",200,"0.5"},{"enchanted_book:projectile_protection_2",400,"0.5"},{"enchanted_book:projectile_protection_3",600,"0.5"},{"enchanted_book:projectile_protection_4",1200,"0.6"},
            {"enchanted_book:feather_falling",200,"0.5"},{"enchanted_book:feather_falling_2",400,"0.5"},{"enchanted_book:feather_falling_3",600,"0.5"},{"enchanted_book:feather_falling_4",1200,"0.6"},
            {"enchanted_book:thorns",300,"0.5"},{"enchanted_book:thorns_2",600,"0.5"},{"enchanted_book:thorns_3",1200,"0.6"},
            {"enchanted_book:respiration",400,"0.5"},{"enchanted_book:respiration_2",800,"0.5"},{"enchanted_book:respiration_3",1500,"0.6"},
            {"enchanted_book:aqua_affinity",400,"0.6"},
            {"enchanted_book:depth_strider",400,"0.5"},{"enchanted_book:depth_strider_2",800,"0.5"},{"enchanted_book:depth_strider_3",1500,"0.6"},
            {"enchanted_book:frost_walker",600,"0.5"},{"enchanted_book:frost_walker_2",1200,"0.6"},
            {"enchanted_book:soul_speed",600,"0.5"},{"enchanted_book:soul_speed_2",1200,"0.5"},{"enchanted_book:soul_speed_3",2000,"0.6"},
            {"enchanted_book:swift_sneak",600,"0.5"},{"enchanted_book:swift_sneak_2",1200,"0.5"},{"enchanted_book:swift_sneak_3",2000,"0.6"},
            // Оружие
            {"enchanted_book:sharpness",300,"0.5"},{"enchanted_book:sharpness_2",600,"0.5"},{"enchanted_book:sharpness_3",900,"0.5"},{"enchanted_book:sharpness_4",1400,"0.5"},{"enchanted_book:sharpness_5",2500,"0.7"},
            {"enchanted_book:smite",250,"0.5"},{"enchanted_book:smite_2",500,"0.5"},{"enchanted_book:smite_3",750,"0.5"},{"enchanted_book:smite_4",1100,"0.5"},{"enchanted_book:smite_5",2000,"0.6"},
            {"enchanted_book:bane_of_arthropods",250,"0.5"},{"enchanted_book:bane_of_arthropods_2",500,"0.5"},{"enchanted_book:bane_of_arthropods_3",750,"0.5"},{"enchanted_book:bane_of_arthropods_4",1100,"0.5"},{"enchanted_book:bane_of_arthropods_5",2000,"0.6"},
            {"enchanted_book:knockback",300,"0.5"},{"enchanted_book:knockback_2",700,"0.5"},
            {"enchanted_book:fire_aspect",500,"0.5"},{"enchanted_book:fire_aspect_2",1000,"0.6"},
            {"enchanted_book:looting",600,"0.5"},{"enchanted_book:looting_2",1200,"0.5"},{"enchanted_book:looting_3",2200,"0.7"},
            {"enchanted_book:sweeping_edge",400,"0.5"},{"enchanted_book:sweeping_edge_2",800,"0.5"},{"enchanted_book:sweeping_edge_3",1400,"0.6"},
            {"enchanted_book:density",300,"0.5"},{"enchanted_book:density_2",600,"0.5"},{"enchanted_book:density_3",900,"0.5"},{"enchanted_book:density_4",1400,"0.5"},{"enchanted_book:density_5",2500,"0.7"},
            {"enchanted_book:breach",400,"0.5"},{"enchanted_book:breach_2",800,"0.5"},{"enchanted_book:breach_3",1400,"0.6"},{"enchanted_book:breach_4",2500,"0.7"},
            {"enchanted_book:wind_burst",600,"0.5"},{"enchanted_book:wind_burst_2",1200,"0.5"},{"enchanted_book:wind_burst_3",2000,"0.6"},
            // Инструменты
            {"enchanted_book:efficiency",300,"0.5"},{"enchanted_book:efficiency_2",600,"0.5"},{"enchanted_book:efficiency_3",900,"0.5"},{"enchanted_book:efficiency_4",1500,"0.5"},{"enchanted_book:efficiency_5",2500,"0.7"},
            {"enchanted_book:silk_touch",2000,"0.7"},
            {"enchanted_book:fortune",800,"0.6"},{"enchanted_book:fortune_2",1600,"0.6"},{"enchanted_book:fortune_3",3000,"0.7"},
            {"enchanted_book:unbreaking",500,"0.5"},{"enchanted_book:unbreaking_2",1000,"0.5"},{"enchanted_book:unbreaking_3",2000,"0.6"},
            {"enchanted_book:mending",3000,"0.7"},
            {"enchanted_book:curse_of_vanishing",100,"0.3"},{"enchanted_book:curse_of_binding",100,"0.3"},
            // Лук и арбалет
            {"enchanted_book:power",300,"0.5"},{"enchanted_book:power_2",600,"0.5"},{"enchanted_book:power_3",900,"0.5"},{"enchanted_book:power_4",1400,"0.5"},{"enchanted_book:power_5",2500,"0.7"},
            {"enchanted_book:punch",400,"0.5"},{"enchanted_book:punch_2",800,"0.5"},
            {"enchanted_book:flame",600,"0.6"},
            {"enchanted_book:infinity",2500,"0.7"},
            {"enchanted_book:multishot",1000,"0.6"},
            {"enchanted_book:quick_charge",400,"0.5"},{"enchanted_book:quick_charge_2",800,"0.5"},{"enchanted_book:quick_charge_3",1400,"0.6"},
            {"enchanted_book:piercing",400,"0.5"},{"enchanted_book:piercing_2",800,"0.5"},{"enchanted_book:piercing_3",1200,"0.5"},{"enchanted_book:piercing_4",2000,"0.6"},
            // Трезубец
            {"enchanted_book:loyalty",400,"0.5"},{"enchanted_book:loyalty_2",800,"0.5"},{"enchanted_book:loyalty_3",1400,"0.6"},
            {"enchanted_book:impaling",400,"0.5"},{"enchanted_book:impaling_2",800,"0.5"},{"enchanted_book:impaling_3",1200,"0.5"},{"enchanted_book:impaling_4",1800,"0.6"},{"enchanted_book:impaling_5",2800,"0.7"},
            {"enchanted_book:riptide",800,"0.6"},{"enchanted_book:riptide_2",1600,"0.6"},{"enchanted_book:riptide_3",2800,"0.7"},
            {"enchanted_book:channeling",1200,"0.6"},

            // ── Каменные производные (andesite/diorite/granite/blackstone плиты/ступени/стены)
            {"minecraft:andesite_slab",2,"0.4"},{"minecraft:andesite_stairs",2,"0.4"},{"minecraft:andesite_wall",2,"0.4"},
            {"minecraft:diorite_slab",2,"0.4"},{"minecraft:diorite_stairs",2,"0.4"},{"minecraft:diorite_wall",2,"0.4"},
            {"minecraft:granite_slab",2,"0.4"},{"minecraft:granite_stairs",2,"0.4"},{"minecraft:granite_wall",2,"0.4"},
            {"minecraft:blackstone_slab",2,"0.4"},{"minecraft:blackstone_stairs",2,"0.4"},{"minecraft:blackstone_wall",2,"0.4"},
            // ── Медь (новые варианты 1.21)
            {"minecraft:chiseled_copper",8,"0.4"},{"minecraft:exposed_chiseled_copper",6,"0.4"},
            {"minecraft:weathered_chiseled_copper",5,"0.4"},{"minecraft:oxidized_chiseled_copper",4,"0.3"},
            {"minecraft:waxed_chiseled_copper",9,"0.4"},{"minecraft:waxed_exposed_chiseled_copper",7,"0.4"},
            {"minecraft:waxed_weathered_chiseled_copper",6,"0.4"},{"minecraft:waxed_oxidized_chiseled_copper",5,"0.4"},
            // ── Кирпич (необожжённый) и кусок глины
            {"minecraft:brick",2,"0.4"},{"minecraft:clay_ball",1,"0.35"},
            // ── Природные блоки
            {"minecraft:hanging_roots",2,"0.3"},{"minecraft:flowering_azalea_leaves",4,"0.3"},
            {"minecraft:suspicious_sand",3,"0.4"},{"minecraft:suspicious_gravel",3,"0.4"},
            {"minecraft:turtle_egg",50,"0.5"},{"minecraft:sniffer_egg",200,"0.6"},
            // ── Мёртвые веера кораллов
            {"minecraft:dead_tube_coral_fan",2,"0.3"},{"minecraft:dead_brain_coral_fan",2,"0.3"},
            {"minecraft:dead_bubble_coral_fan",2,"0.3"},{"minecraft:dead_fire_coral_fan",2,"0.3"},
            {"minecraft:dead_horn_coral_fan",2,"0.3"},
            // ── Яйца спавна (пропущенные)
            {"minecraft:llama_spawn_egg",750,"0.5"},{"minecraft:trader_llama_spawn_egg",1000,"0.5"},
            {"minecraft:mooshroom_spawn_egg",1000,"0.5"},{"minecraft:snow_golem_spawn_egg",1000,"0.5"},
            {"minecraft:wandering_trader_spawn_egg",1500,"0.5"},{"minecraft:allay_spawn_egg",1500,"0.5"},
            {"minecraft:armadillo_spawn_egg",750,"0.5"},{"minecraft:bogged_spawn_egg",1000,"0.5"},
            {"minecraft:creaking_spawn_egg",1500,"0.6"},{"minecraft:glow_squid_spawn_egg",750,"0.5"},
            {"minecraft:strider_spawn_egg",1000,"0.5"},{"minecraft:hoglin_spawn_egg",1000,"0.5"},
            {"minecraft:zoglin_spawn_egg",1000,"0.5"},{"minecraft:piglin_brute_spawn_egg",1250,"0.5"},
            // ── Инструменты / утилиты (пропущенные)
            {"minecraft:recovery_compass",400,"0.5"},{"minecraft:water_bucket",12,"0.5"},
            {"minecraft:wind_charge",15,"0.5"},
            // ── Трафарет брони (пропущенный village)
            {"minecraft:raiser_armor_trim_smithing_template",100,"0.6"},
            {"minecraft:shaper_armor_trim_smithing_template",100,"0.6"},
            {"minecraft:host_armor_trim_smithing_template",100,"0.6"},
            {"minecraft:eye_armor_trim_smithing_template",100,"0.6"},
            {"minecraft:coast_armor_trim_smithing_template",100,"0.6"},
            {"minecraft:rib_armor_trim_smithing_template",100,"0.6"},
            {"minecraft:snout_armor_trim_smithing_template",100,"0.6"},
            {"minecraft:iron_golem_spawn_egg",2500,"0.5"},
            // ── Недостающие зелья
            {"splash_potion:strong_turtle_master",200,"0.5"},{"splash_potion:luck",180,"0.5"},
            {"lingering_potion:strong_turtle_master",230,"0.5"},
            {"tipped_arrow:strong_turtle_master",14,"0.5"},
            {"tipped_arrow:water",2,"0.3"},{"tipped_arrow:mundane",2,"0.3"},
            {"tipped_arrow:thick",2,"0.3"},{"tipped_arrow:awkward",3,"0.3"},
            // ── Зачарования удочки
            {"enchanted_book:luck_of_the_sea",600,"0.5"},{"enchanted_book:luck_of_the_sea_2",1200,"0.5"},{"enchanted_book:luck_of_the_sea_3",2200,"0.7"},
            {"enchanted_book:lure",600,"0.5"},{"enchanted_book:lure_2",1200,"0.5"},{"enchanted_book:lure_3",2200,"0.7"},

            // ── Недостающие обычные предметы ──────────────────────────────────────
            {"minecraft:armadillo_scute",80,"0.5"},
            {"minecraft:bell",300,"0.5"},
            {"minecraft:bundle",120,"0.5"},
            {"minecraft:enchanted_golden_apple",3000,"0.7"},
            {"minecraft:heart_of_the_sea",1500,"0.6"},
            {"minecraft:nether_star",4000,"0.7"},

            // ── Навощённая медь (waxed) ───────────────────────────────────────────
            {"minecraft:waxed_copper_block",72,"0.5"},
            {"minecraft:waxed_copper_bulb",8,"0.4"},
            {"minecraft:waxed_copper_door",8,"0.4"},
            {"minecraft:waxed_copper_grate",6,"0.4"},
            {"minecraft:waxed_copper_trapdoor",8,"0.4"},
            {"minecraft:waxed_cut_copper",6,"0.4"},
            {"minecraft:waxed_cut_copper_slab",4,"0.4"},
            {"minecraft:waxed_cut_copper_stairs",6,"0.4"},
            {"minecraft:waxed_exposed_copper",5,"0.4"},
            {"minecraft:waxed_exposed_copper_bulb",6,"0.4"},
            {"minecraft:waxed_exposed_copper_door",6,"0.4"},
            {"minecraft:waxed_exposed_copper_grate",5,"0.4"},
            {"minecraft:waxed_exposed_copper_trapdoor",6,"0.4"},
            {"minecraft:waxed_exposed_cut_copper",5,"0.4"},
            {"minecraft:waxed_exposed_cut_copper_slab",3,"0.4"},
            {"minecraft:waxed_exposed_cut_copper_stairs",5,"0.4"},
            {"minecraft:waxed_weathered_copper",4,"0.4"},
            {"minecraft:waxed_weathered_copper_bulb",5,"0.4"},
            {"minecraft:waxed_weathered_copper_door",5,"0.4"},
            {"minecraft:waxed_weathered_copper_grate",4,"0.4"},
            {"minecraft:waxed_weathered_copper_trapdoor",5,"0.4"},
            {"minecraft:waxed_weathered_cut_copper",4,"0.4"},
            {"minecraft:waxed_weathered_cut_copper_slab",3,"0.4"},
            {"minecraft:waxed_weathered_cut_copper_stairs",4,"0.4"},
            {"minecraft:waxed_oxidized_copper",4,"0.3"},
            {"minecraft:waxed_oxidized_copper_bulb",5,"0.3"},
            {"minecraft:waxed_oxidized_copper_door",5,"0.3"},
            {"minecraft:waxed_oxidized_copper_grate",4,"0.3"},
            {"minecraft:waxed_oxidized_copper_trapdoor",5,"0.3"},
            {"minecraft:waxed_oxidized_cut_copper",4,"0.3"},
            {"minecraft:waxed_oxidized_cut_copper_slab",3,"0.3"},
            {"minecraft:waxed_oxidized_cut_copper_stairs",4,"0.3"},

            // ── Окисленная медь (weathered) ───────────────────────────────────────
            {"minecraft:weathered_copper",4,"0.3"},
            {"minecraft:weathered_copper_bulb",5,"0.3"},
            {"minecraft:weathered_copper_door",5,"0.3"},
            {"minecraft:weathered_copper_grate",4,"0.3"},
            {"minecraft:weathered_copper_trapdoor",5,"0.3"},
            {"minecraft:weathered_cut_copper",4,"0.3"},
            {"minecraft:weathered_cut_copper_slab",3,"0.3"},
            {"minecraft:weathered_cut_copper_stairs",4,"0.3"}

        };

        for (Object[] row : d) {
            String id    = (String) row[0];
            long   price = row[1] instanceof Integer ? (long)(int)row[1] : (long)row[1];
            double mul   = Double.parseDouble((String) row[2]);
            ITEM_PRICES.put(id, price);  // хардкод — цены неизменяемы
            SELL_MULTIPLIERS.put(id, mul);
        }
        save();
    }
}
