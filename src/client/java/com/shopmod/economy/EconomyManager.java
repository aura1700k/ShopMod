package com.shopmod.economy;

import com.shopmod.config.ShopConfig;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.item.ItemStack;

/**
 * Клиентская экономика.
 * Валюта: brick=1, rabbit_hide=5, clay_ball=10, honeycomb=50,
 *         disc_fragment_5=100, heart_of_the_sea=500, echo_shard=1000
 */
@Environment(EnvType.CLIENT)
public class EconomyManager {

    public static final Item COIN_10000 = Items.NETHER_STAR;
    public static final Item COIN_5000  = Items.ENCHANTED_GOLDEN_APPLE;
    public static final Item COIN_1000  = Items.ECHO_SHARD;
    public static final Item COIN_500   = Items.HEART_OF_THE_SEA;
    public static final Item COIN_100   = Items.DISC_FRAGMENT_5;
    public static final Item COIN_50    = Items.HONEYCOMB;
    public static final Item COIN_10    = Items.CLAY_BALL;
    public static final Item COIN_5     = Items.RABBIT_HIDE;
    public static final Item COIN_1     = Items.BRICK;

    private static final String ID_10000 = "minecraft:nether_star";
    private static final String ID_5000  = "minecraft:enchanted_golden_apple";
    private static final String ID_1000  = "minecraft:echo_shard";
    private static final String ID_500   = "minecraft:heart_of_the_sea";
    private static final String ID_100   = "minecraft:disc_fragment_5";
    private static final String ID_50    = "minecraft:honeycomb";
    private static final String ID_10    = "minecraft:clay_ball";
    private static final String ID_5     = "minecraft:rabbit_hide";
    private static final String ID_1     = "minecraft:brick";

    public static Item[] allCoins() {
        return new Item[]{COIN_10000, COIN_5000, COIN_1000, COIN_500, COIN_100, COIN_50, COIN_10, COIN_5, COIN_1};
    }

    public static long getBalance() {
        return getPhysicalBalance() + ShopConfig.VIRTUAL_BALANCE;
    }

    public static long getPhysicalBalance() {
        var player = MinecraftClient.getInstance().player;
        if (player == null) return 0;
        long total = 0;
        for (int i = 0; i < player.getInventory().size(); i++)
            total += getCoinValue(player.getInventory().getStack(i));
        return total;
    }

    public static long getCoinValue(ItemStack stack) {
        if (stack.isEmpty()) return 0;
        if (stack.getItem() == COIN_10000) return 10000L * stack.getCount();
        if (stack.getItem() == COIN_5000)  return  5000L * stack.getCount();
        if (stack.getItem() == COIN_1000) return 1000L * stack.getCount();
        if (stack.getItem() == COIN_500)  return  500L * stack.getCount();
        if (stack.getItem() == COIN_100)  return  100L * stack.getCount();
        if (stack.getItem() == COIN_50)   return   50L * stack.getCount();
        if (stack.getItem() == COIN_10)   return   10L * stack.getCount();
        if (stack.getItem() == COIN_5)    return    5L * stack.getCount();
        if (stack.getItem() == COIN_1)    return    1L * stack.getCount();
        return 0;
    }

    public static boolean isCoin(Item item) {
        return item == COIN_1 || item == COIN_5 || item == COIN_10
            || item == COIN_50 || item == COIN_100 || item == COIN_500
            || item == COIN_1000 || item == COIN_5000 || item == COIN_10000;
    }

    public static int coinValue(Item coin) {
        if (coin == COIN_10000) return 10000;
        if (coin == COIN_5000)  return 5000;
        if (coin == COIN_1000) return 1000;
        if (coin == COIN_500)  return 500;
        if (coin == COIN_100)  return 100;
        if (coin == COIN_50)   return 50;
        if (coin == COIN_10)   return 10;
        if (coin == COIN_5)    return 5;
        if (coin == COIN_1)    return 1;
        return 0;
    }

    public static String coinId(Item coin) {
        if (coin == COIN_10000) return ID_10000;
        if (coin == COIN_5000)  return ID_5000;
        if (coin == COIN_1000) return ID_1000;
        if (coin == COIN_500)  return ID_500;
        if (coin == COIN_100)  return ID_100;
        if (coin == COIN_50)   return ID_50;
        if (coin == COIN_10)   return ID_10;
        if (coin == COIN_5)    return ID_5;
        if (coin == COIN_1)    return ID_1;
        return null;
    }

    public static boolean buyWithPhysical(String itemId, int amount, long totalCost) {
        var mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.getNetworkHandler() == null) return false;
        if (getPhysicalBalance() < totalCost) return false;
        withFeedback(() -> {
            sendCommand(ShopConfig.buildGiveCommand(itemId, amount));
            clearCoinsCommands(totalCost);
        });
        return true;
    }

    public static boolean buyWithVirtual(String itemId, int amount, long totalCost) {
        var mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.getNetworkHandler() == null) return false;
        if (ShopConfig.VIRTUAL_BALANCE < totalCost) return false;
        ShopConfig.VIRTUAL_BALANCE -= totalCost;
        ShopConfig.save();
        withFeedback(() -> sendCommand(ShopConfig.buildGiveCommand(itemId, amount)));
        return true;
    }

    public static boolean sellForPhysical(Item item, String itemId, int amount, long earned) {
        var mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.getNetworkHandler() == null) return false;
        // Для спец-предметов (зелья, стрелы, книги) item == null — используем countItemById
        int inInv = (item != null) ? countItem(item) : countItemById(itemId, amount);
        if (inInv < amount) return false;
        withFeedback(() -> {
            sendCommand(ShopConfig.buildClearCommand(itemId, amount));
            giveCoinsCommands(earned);
        });
        return true;
    }

    public static boolean sellForVirtual(Item item, String itemId, int amount, long earned) {
        var mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.getNetworkHandler() == null) return false;
        int inInv = (item != null) ? countItem(item) : countItemById(itemId, amount);
        if (inInv < amount) return false;
        withFeedback(() -> sendCommand(ShopConfig.buildClearCommand(itemId, amount)));
        ShopConfig.VIRTUAL_BALANCE += earned;
        ShopConfig.save();
        return true;
    }

    /** Для спец-предметов (зелья и т.д.) — считаем базовый item в инвентаре */
    private static int countItemById(String itemId, int needed) {
        // Считаем реальное количество базового предмета (зелье, стрела, книга) в инвентаре
        String baseItemId = ShopConfig.getBaseItemId(itemId);
        net.minecraft.item.Item baseItem = net.minecraft.registry.Registries.ITEM
                .get(net.minecraft.util.Identifier.of(baseItemId));
        if (baseItem == null || baseItem == net.minecraft.item.Items.AIR) return 0;
        var player = MinecraftClient.getInstance().player;
        if (player == null) return 0;
        int count = 0;
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack s = player.getInventory().getStack(i);
            if (s.getItem() == baseItem) count += s.getCount();
        }
        return count;
    }

    public static boolean depositPhysicalToVirtual(long amount) {
        if (getPhysicalBalance() < amount) return false;
        clearCoinsCommands(amount);
        ShopConfig.VIRTUAL_BALANCE += amount;
        ShopConfig.save();
        ShopConfig.addAtmHistory(ShopConfig.t("Монеты → Вирт: ", "Coins→Virt: ") + amount);
        return true;
    }

    public static boolean withdrawVirtualToPhysical(long amount) {
        if (ShopConfig.VIRTUAL_BALANCE < amount) return false;
        ShopConfig.VIRTUAL_BALANCE -= amount;
        ShopConfig.save();
        giveCoinsCommands(amount);
        ShopConfig.addAtmHistory(ShopConfig.t("Вирт → Монеты: ", "Virt→Coins: ") + amount);
        return true;
    }

    /** Обмен монет: разменять count штук монеты fromCoin на мелкие */
    public static boolean exchangeDown(Item fromCoin, int count) {
        var mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.getNetworkHandler() == null) return false;
        String id = coinId(fromCoin);
        if (id == null) return false;
        int inInv = countCoin(fromCoin);
        if (inInv < count) return false;
        long total = (long) coinValue(fromCoin) * count;
        withFeedback(() -> {
            sendCommand("clear @s " + id + " " + count);
            giveCoinsSmallest(total, coinValue(fromCoin));
        });
        ShopConfig.addAtmHistory(ShopConfig.t("Размен ↓ ", "Break ↓ ") + count + "×" + coinValue(fromCoin));
        return true;
    }

    /** Обмен монет: объединить count штук fromCoin в монеты крупнее */
    public static boolean exchangeUp(Item fromCoin, int count) {
        var mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.getNetworkHandler() == null) return false;
        String id = coinId(fromCoin);
        if (id == null) return false;
        int inInv = countCoin(fromCoin);
        if (inInv < count) return false;
        int cv = coinValue(fromCoin);
        // Найти следующий крупный номинал
        Item biggerCoin = nextBigger(fromCoin);
        if (biggerCoin == null) return false;
        int bigVal = coinValue(biggerCoin);
        String bigId = coinId(biggerCoin);
        long total = (long) cv * count;
        long give = total / bigVal;
        long remainder = total % bigVal;
        if (give < 1) return false;
        withFeedback(() -> {
            // FIX: списываем ровно count монет номинала fromCoin (не /clear со всего инвентаря)
            sendCommand("clear @s " + id + " " + count);
            sendCommand("give @s " + bigId + " " + give);
            if (remainder > 0) giveCoinsCommands(remainder);
        });
        ShopConfig.addAtmHistory(ShopConfig.t("Объед ↑ ", "Merge ↑ ") + count + "×" + cv + " → " + give + "×" + bigVal);
        return true;
    }

    /**
     * Объединить ВСЕ монеты в инвентаре в самый крупный номинал.
     * Алгоритм: считаем суммарный физический баланс, списываем все монеты,
     * выдаём оптимально (с остатком мелочью).
     */
    public static boolean exchangeUpAll() {
        var mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.getNetworkHandler() == null) return false;
        long total = getPhysicalBalance();
        if (total <= 0) return false;
        withFeedback(() -> {
            // Списываем все монеты всех номиналов
            for (Item coin : allCoins()) {
                int cnt = countCoin(coin);
                if (cnt > 0) sendCommand("clear @s " + coinId(coin) + " " + cnt);
            }
            // Выдаём оптимально
            giveCoinsCommands(total);
        });
        ShopConfig.addAtmHistory(ShopConfig.t("Объед всё → ", "Merge all → ") + total);
        return true;
    }

    private static Item nextBigger(Item coin) {
        if (coin == COIN_1)    return COIN_5;
        if (coin == COIN_5)    return COIN_10;
        if (coin == COIN_10)   return COIN_50;
        if (coin == COIN_50)   return COIN_100;
        if (coin == COIN_100)  return COIN_500;
        if (coin == COIN_500)  return COIN_1000;
        if (coin == COIN_1000) return COIN_5000;
        if (coin == COIN_5000) return COIN_10000;
        return null;
    }

    private static Item nextSmaller(Item coin) {
        if (coin == COIN_10000) return COIN_5000;
        if (coin == COIN_5000)  return COIN_1000;
        if (coin == COIN_1000) return COIN_500;
        if (coin == COIN_500)  return COIN_100;
        if (coin == COIN_100)  return COIN_50;
        if (coin == COIN_50)   return COIN_10;
        if (coin == COIN_10)   return COIN_5;
        if (coin == COIN_5)    return COIN_1;
        return null;
    }

    public static int countCoin(Item coin) {
        var player = MinecraftClient.getInstance().player;
        if (player == null) return 0;
        int count = 0;
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack s = player.getInventory().getStack(i);
            if (s.getItem() == coin) count += s.getCount();
        }
        return count;
    }

    private static void clearCoinsCommands(long amount) {
        var player = MinecraftClient.getInstance().player;
        if (player == null) return;
        record CoinType(Item item, String id, long value) {}
        CoinType[] coins = {
            new CoinType(COIN_10000, ID_10000, 10000),
            new CoinType(COIN_5000,  ID_5000,  5000),
            new CoinType(COIN_1000,  ID_1000,  1000),
            new CoinType(COIN_500,   ID_500,   500),
            new CoinType(COIN_100,   ID_100,   100),
            new CoinType(COIN_50,    ID_50,    50),
            new CoinType(COIN_10,    ID_10,    10),
            new CoinType(COIN_5,     ID_5,     5),
            new CoinType(COIN_1,     ID_1,     1)
        };

        // Считаем количество каждой монеты в инвентаре один раз
        int[] inInvArr = new int[coins.length];
        for (int ci = 0; ci < coins.length; ci++) {
            for (int i = 0; i < player.getInventory().size(); i++) {
                ItemStack s = player.getInventory().getStack(i);
                if (s.getItem() == coins[ci].item()) inInvArr[ci] += s.getCount();
            }
        }

        // Жадный проход: берём целые монеты каждого номинала
        long remaining = amount;
        for (int ci = 0; ci < coins.length; ci++) {
            if (remaining <= 0) break;
            CoinType c = coins[ci];
            int canTake = (int) Math.min(inInvArr[ci], remaining / c.value());
            if (canTake > 0) {
                sendCommand("clear @s " + c.id() + " " + canTake);
                remaining -= (long) canTake * c.value();
            }
        }

        // FIX: если после жадного прохода остаток > 0, берём одну монету крупнее
        // остатка и выдаём сдачу. Это покрывает случай типа: нужно 5000,
        // а в инвентаре только монета 10000.
        if (remaining > 0) {
            for (int ci = 0; ci < coins.length; ci++) {
                CoinType c = coins[ci];
                if (inInvArr[ci] > 0 && c.value() >= remaining) {
                    sendCommand("clear @s " + c.id() + " 1");
                    long change = c.value() - remaining;
                    if (change > 0) giveCoinsCommands(change);
                    remaining = 0;
                    break;
                }
            }
        }
    }

    private static void giveCoinsCommands(long amount) {
        record CoinType(String id, long value) {}
        CoinType[] coins = {
            new CoinType(ID_10000, 10000), new CoinType(ID_5000, 5000),
            new CoinType(ID_1000, 1000),   new CoinType(ID_500, 500),
            new CoinType(ID_100, 100),     new CoinType(ID_50, 50),
            new CoinType(ID_10, 10),       new CoinType(ID_5, 5),
            new CoinType(ID_1, 1)
        };
        long rem = amount;
        for (CoinType c : coins) {
            if (rem <= 0) break;
            long count = rem / c.value();
            if (count > 0) {
                // FIX: сохраняем сумму до while-цикла, чтобы корректно обновить rem
                // (while обнуляет count, поэтому rem / c.value() после цикла уже = 0)
                long toDeduct = count * c.value();
                // Разбиваем на стаки по 64 (лимит Minecraft)
                while (count > 0) {
                    long batch = Math.min(count, 64);
                    sendCommand("give @s " + c.id() + " " + batch);
                    count -= batch;
                }
                rem -= toDeduct;
            }
        }
    }

    /** Выдать только монетами МЕНЬШЕ чем skipValue */
    private static void giveCoinsSmallest(long amount, int skipValue) {
        record CoinType(String id, long value) {}
        CoinType[] coins = {
            new CoinType(ID_5000, 5000),   new CoinType(ID_1000, 1000),
            new CoinType(ID_500, 500), new CoinType(ID_100, 100),
            new CoinType(ID_50, 50),   new CoinType(ID_10, 10),
            new CoinType(ID_5, 5),     new CoinType(ID_1, 1)
        };
        long rem = amount;
        for (CoinType c : coins) {
            if (rem <= 0) break;
            if (c.value() >= skipValue) continue;
            long count = rem / c.value();
            if (count > 0) { sendCommand("give @s " + c.id() + " " + count); rem -= count * c.value(); }
        }
        // Если осталось — выдаём единицами
        if (rem > 0) sendCommand("give @s " + ID_1 + " " + rem);
    }

    public static int countItem(Item item) {
        var player = MinecraftClient.getInstance().player;
        if (player == null) return 0;
        if (isCoin(item)) return 0;
        int count = 0;
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack s = player.getInventory().getStack(i);
            if (s.getItem() == item) count += s.getCount();
        }
        for (int i = 0; i < 4; i++) {
            ItemStack s = player.getInventory().getArmorStack(i);
            if (s.getItem() == item) count += s.getCount();
        }
        ItemStack off = player.getOffHandStack();
        if (off.getItem() == item) count += off.getCount();
        return count;
    }

    /**
     * Подсчёт предметов в инвентаре по shopId (включая спец-предметы).
     * Для зелий/стрел/книг — считаем ВСЕ предметы этого базового типа
     * (нет возможности фильтровать по варианту на клиенте без NBT API).
     */
    public static int countItemByShopId(String shopId) {
        var player = MinecraftClient.getInstance().player;
        if (player == null) return 0;
        ShopConfig.ItemKind kind = ShopConfig.getItemKind(shopId);
        if (kind == ShopConfig.ItemKind.NORMAL) {
            // Стандартный предмет — ищем по ID
            String baseId = shopId.startsWith("minecraft:") ? shopId : "minecraft:" + shopId;
            int count = 0;
            for (int i = 0; i < player.getInventory().size(); i++) {
                ItemStack s = player.getInventory().getStack(i);
                String sid = net.minecraft.registry.Registries.ITEM.getId(s.getItem()).toString();
                if (sid.equals(baseId) || sid.equals(shopId)) count += s.getCount();
            }
            for (int i = 0; i < 4; i++) {
                ItemStack s = player.getInventory().getArmorStack(i);
                String sid = net.minecraft.registry.Registries.ITEM.getId(s.getItem()).toString();
                if (sid.equals(baseId) || sid.equals(shopId)) count += s.getCount();
            }
            ItemStack off = player.getOffHandStack();
            String osid = net.minecraft.registry.Registries.ITEM.getId(off.getItem()).toString();
            if (osid.equals(baseId) || osid.equals(shopId)) count += off.getCount();
            return count;
        }
        // Спец-предмет (зелье, стрела, книга) — считаем только стеки с точным совпадением варианта
        String baseItemId = ShopConfig.getBaseItemId(shopId); // e.g. "minecraft:tipped_arrow"
        String expectedVariant = ShopConfig.getItemVariant(shopId); // e.g. "turtle_master"
        net.minecraft.item.Item baseItem = net.minecraft.registry.Registries.ITEM
                .get(net.minecraft.util.Identifier.of(baseItemId));
        if (baseItem == null || baseItem == net.minecraft.item.Items.AIR) return 0;
        int count = 0;
        for (int i = 0; i < player.getInventory().size(); i++) {
            ItemStack s = player.getInventory().getStack(i);
            if (s.getItem() == baseItem && stackMatchesVariant(s, expectedVariant)) count += s.getCount();
        }
        return count;
    }

    /**
     * Проверяет, совпадает ли реальный эффект зелья/стрелы в стеке с ожидаемым вариантом.
     * Для зачарованных книг и обычных предметов всегда возвращает true.
     */
    private static boolean stackMatchesVariant(ItemStack stack, String expectedVariant) {
        if (expectedVariant == null || expectedVariant.isEmpty()) return true;
        var comp = stack.get(net.minecraft.component.DataComponentTypes.POTION_CONTENTS);
        if (comp == null) return false;
        var potionOpt = comp.potion();
        if (potionOpt == null || potionOpt.isEmpty()) return false;
        var id = net.minecraft.registry.Registries.POTION.getId(potionOpt.get().value());
        if (id == null) return false;
        return id.getPath().equals(expectedVariant);
    }

    private static void sendCommand(String command) {
        var mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.getNetworkHandler() == null) return;
        // Выставляем флаг чтобы ALLOW_COMMAND знал: эту команду отправляет сам магазин,
        // а не игрок вручную — TAINT публиковать не нужно.
        com.shopmod.security.SeedRegistry.SHOP_COMMAND_ALLOWED = true;
        try {
            mc.getNetworkHandler().sendChatCommand(command);
        } finally {
            com.shopmod.security.SeedRegistry.SHOP_COMMAND_ALLOWED = false;
        }
    }

    public static void withFeedback(Runnable block) {
        var mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.getNetworkHandler() == null) { block.run(); return; }
        if (!ShopConfig.CHAT_SPAM_ENABLED)
            mc.getNetworkHandler().sendChatCommand("gamerule sendCommandFeedback false");
        block.run();
        if (!ShopConfig.CHAT_SPAM_ENABLED)
            mc.getNetworkHandler().sendChatCommand("gamerule sendCommandFeedback true");
    }
}
