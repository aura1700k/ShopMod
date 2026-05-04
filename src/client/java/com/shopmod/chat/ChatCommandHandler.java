package com.shopmod.chat;

import com.shopmod.config.ShopConfig;
import com.shopmod.economy.EconomyManager;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

/**
 * Чат-команды (клиентские, перехватываются до отправки на сервер):
 *   v<amount>  — физические монеты → виртуальный баланс
 *   s<amount>  — виртуальный баланс → физические монеты (через /give)
 *   bal        — показать баланс
 */
@Environment(EnvType.CLIENT)
public class ChatCommandHandler {

    public static boolean handle(String message) {
        if (message == null) return false;
        String msg = message.trim();

        if (msg.equalsIgnoreCase("bal") || msg.equalsIgnoreCase("balance")) {
            printBalance();
            return true;
        }

        if (msg.length() > 1) {
            char prefix = Character.toLowerCase(msg.charAt(0));

            if (prefix == 'v') {
                long amount = parseLong(msg.substring(1));
                if (amount > 0) { depositToVirtual(amount); return true; }
            }

            if (prefix == 's') {
                long amount = parseLong(msg.substring(1));
                if (amount > 0) { withdrawFromVirtual(amount); return true; }
            }
        }

        return false;
    }

    private static void depositToVirtual(long amount) {
        long physBalance = EconomyManager.getPhysicalBalance();
        if (physBalance < amount) {
            sendMsg("§c✘ Недостаточно монет! Нужно §e" + amount + "§c, есть §e" + physBalance);
            return;
        }
        boolean ok = EconomyManager.depositPhysicalToVirtual(amount);
        if (!ok) { sendMsg("§c✘ Ошибка при переводе!"); return; }
        sendMsg("§a✔ §e" + amount + " §aмонет → виртуальный баланс. Вирт: §b" + ShopConfig.VIRTUAL_BALANCE
                + " §8| Физ: §e" + EconomyManager.getPhysicalBalance());
    }

    private static void withdrawFromVirtual(long amount) {
        if (ShopConfig.VIRTUAL_BALANCE < amount) {
            sendMsg("§c✘ Мало виртуальных монет! Нужно §e" + amount + "§c, есть §e" + ShopConfig.VIRTUAL_BALANCE);
            return;
        }
        boolean ok = EconomyManager.withdrawVirtualToPhysical(amount);
        if (!ok) { sendMsg("§c✘ Ошибка вывода!"); return; }
        sendMsg("§a✔ §e" + amount + " §aс виртуала → монеты. Вирт: §b" + ShopConfig.VIRTUAL_BALANCE
                + " §8| Физ: §e" + EconomyManager.getPhysicalBalance());
    }

    private static void printBalance() {
        long virt = ShopConfig.VIRTUAL_BALANCE;
        long phys = EconomyManager.getPhysicalBalance();
        sendMsg("§6═══ §eШоп Баланс §6═══");
        sendMsg("§7Виртуальный: §b" + virt + " §7монет");
        sendMsg("§7Физический:  §e" + phys + " §7монет");
        sendMsg("§7Итого:       §a" + (virt + phys) + " §7монет");
        // Разбивка по номиналам
        StringBuilder coins = new StringBuilder("§8  Монеты: ");
        for (net.minecraft.item.Item coin : EconomyManager.allCoins()) {
            int cnt = EconomyManager.countCoin(coin);
            if (cnt > 0) coins.append("§f").append(cnt).append("×").append(EconomyManager.coinValue(coin)).append(" ");
        }
        sendMsg(coins.toString());
        sendMsg("§8  v<сумма> → монеты в вирт | s<сумма> → вирт в монеты");
    }

    private static void sendMsg(String text) {
        var mc = MinecraftClient.getInstance();
        if (mc.player != null) mc.player.sendMessage(Text.literal(text), false);
    }

    private static long parseLong(String s) {
        try { return Long.parseLong(s.trim()); }
        catch (NumberFormatException e) { return -1; }
    }
}
