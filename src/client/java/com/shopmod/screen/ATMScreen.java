package com.shopmod.screen;

import com.shopmod.config.ShopConfig;
import com.shopmod.economy.EconomyManager;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;

/**
 * Банкомат — обмен монет, пополнение/снятие виртуального баланса, история.
 */
@Environment(EnvType.CLIENT)
public class ATMScreen extends Screen {

    private static final int GW = 460, GH = 300;
    private static final int C_BG     = 0xEF0d0d1a;
    private static final int C_HEADER = 0xFF1a1a3a;
    private static final int C_GOLD   = 0xFFFFD700;
    private static final int C_WHITE  = 0xFFFFFFFF;
    private static final int C_GRAY   = 0xFFAAAAAA;
    private static final int C_PANEL  = 0x88000030;
    private static final int C_GREEN  = 0xFF55FF55;
    private static final int C_RED    = 0xFFFF5555;
    private static final int C_TEAL   = 0xFF55FFFF;
    private static final int C_SLOT   = 0x44FFFFFF;
    private static final int C_SLOT_H = 0x66AACCFF;

    private int GL, GT;

    // Секция: 0=обмен монет, 1=виртуал, 2=история
    private int section = 0;

    // Для обмена монет
    private Item selectedCoin  = null;
    private int  exchangeCount = 1;
    private boolean modeUp = true; // true=объединить вверх, false=разменять вниз

    private String  lastMsg   = "";
    private long    msgUntil  = 0;
    private boolean msgOk     = true;

    // Для снятия/пополнения виртуала
    private long depositAmount = 100;

    public ATMScreen() { super(Text.literal("ATM")); }

    @Override
    protected void init() {
        GL = (width - GW) / 2;
        GT = (height - GH) / 2;
        clearChildren();

        // Кнопка назад
        addDrawableChild(ButtonWidget.builder(
                        Text.literal("§7← " + ShopConfig.t("Магазин", "Shop")),
                        b -> MinecraftClient.getInstance().setScreen(new ShopScreen()))
                .dimensions(GL + 4, GT + 2, 60, 12).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("✕"), b -> close())
                .dimensions(GL + GW - 14, GT + 2, 12, 12).build());

        // Секции
        String[] sectionLabels = {
                ShopConfig.t("Обмен монет", "Coin Exchange"),
                ShopConfig.t("Виртуал", "Virtual"),
                ShopConfig.t("История", "History")
        };
        int sw = 90;
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            boolean active = section == i;
            addDrawableChild(ButtonWidget.builder(
                            Text.literal(active ? "§e" + sectionLabels[i] : "§7" + sectionLabels[i]),
                            b -> { section = idx; rebuildWidgets(); })
                    .dimensions(GL + 10 + i * (sw + 2), GT + 30, sw, 14).build());
        }

        if (section == 0) initCoinExchange();
        else if (section == 1) initVirtual();
        // История — только рендер, без кнопок
    }

    private void initCoinExchange() {
        // Переключатель режима (вверх/вниз)
        String modeLbl = modeUp
                ? ShopConfig.t("§a↑ Объединить в крупные", "§a↑ Merge to larger")
                : ShopConfig.t("§e↓ Разменять на мелкие", "§e↓ Break to smaller");
        addDrawableChild(ButtonWidget.builder(Text.literal(modeLbl),
                        b -> { modeUp = !modeUp; rebuildWidgets(); })
                .dimensions(GL + 10, GT + 48, 180, 14).build());

        // Кнопка "Объединить всё в самое крупное"
        addDrawableChild(ButtonWidget.builder(
                Text.literal(ShopConfig.t("§6⬆ Объединить всё", "§6⬆ Merge All")),
                b -> {
                    if (EconomyManager.exchangeUpAll())
                        showMsg(ShopConfig.t("Всё объединено!", "All merged!"), true);
                    else
                        showMsg(ShopConfig.t("Нет монет!", "No coins!"), false);
                    rebuildWidgets();
                }).dimensions(GL + 10, GT + 64, 130, 14).build());

        // Если монета выбрана — кнопки кол-ва и обмена
        if (selectedCoin != null) {
            int inInv = EconomyManager.countCoin(selectedCoin);
            int bx = GL + 200, by = GT + 126;

            addDrawableChild(ButtonWidget.builder(Text.literal("-"), b -> {
                exchangeCount = Math.max(1, exchangeCount - 1); rebuildWidgets();
            }).dimensions(bx, by, 20, 14).build());

            addDrawableChild(ButtonWidget.builder(Text.literal("+"), b -> {
                exchangeCount = Math.min(inInv, exchangeCount + 1); rebuildWidgets();
            }).dimensions(bx + 46, by, 20, 14).build());

            addDrawableChild(ButtonWidget.builder(Text.literal("MAX"), b -> {
                exchangeCount = inInv; rebuildWidgets();
            }).dimensions(bx + 70, by, 32, 14).build());

            // Кнопка обмена
            String exLbl = modeUp
                    ? ShopConfig.t("§a↑ Объединить", "§a↑ Merge")
                    : ShopConfig.t("§e↓ Разменять", "§e↓ Break");
            addDrawableChild(ButtonWidget.builder(Text.literal(exLbl), b -> doExchange())
                    .dimensions(bx, by + 20, 105, 16).build());
        }
    }

    private void initVirtual() {
        int bx = GL + 20, by = GT + 136;  // BUG-20 fix: moved from GT+132 to GT+136 (hint text ends GT+126, gap now 10px)

        // Суммы быстрого ввода
        long[] amounts = {50, 100, 500, 1000, 5000};
        for (int i = 0; i < amounts.length; i++) {
            final long amt = amounts[i];
            boolean sel = depositAmount == amt;
            addDrawableChild(ButtonWidget.builder(
                            Text.literal(sel ? "§e[" + amt + "]" : "§7" + amt),
                            b -> { depositAmount = amt; rebuildWidgets(); })
                    .dimensions(bx + i * 72, by, 68, 12).build());
        }

        // Пополнить (монеты → вирт)
        addDrawableChild(ButtonWidget.builder(
                Text.literal(ShopConfig.t("§a→ Монеты в Виртуал", "§a→ Coins to Virtual")),
                b -> {
                    if (EconomyManager.depositPhysicalToVirtual(depositAmount))
                        showMsg(ShopConfig.t("Переведено: ", "Deposited: ") + depositAmount, true);
                    else showMsg(ShopConfig.t("Мало монет!", "Not enough coins!"), false);
                }).dimensions(bx, by + 20, 200, 16).build());

        // Снять (вирт → монеты)
        // BUG-42 fix: увеличен зазор между кнопками с 5px до 10px (bx+210 вместо bx+205)
        addDrawableChild(ButtonWidget.builder(
                Text.literal(ShopConfig.t("§c← Виртуал в Монеты", "§c← Virtual to Coins")),
                b -> {
                    if (EconomyManager.withdrawVirtualToPhysical(depositAmount))
                        showMsg(ShopConfig.t("Выдано: ", "Withdrawn: ") + depositAmount, true);
                    else showMsg(ShopConfig.t("Мало виртуала!", "Not enough virtual!"), false);
                }).dimensions(bx + 210, by + 20, 195, 16).build());
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        renderBackground(ctx, mx, my, delta);

        ctx.fill(GL + 3, GT + 3, GL + GW + 3, GT + GH + 3, 0x55000000);
        ctx.fill(GL, GT, GL + GW, GT + GH, C_BG);
        ctx.fill(GL, GT, GL + GW, GT + 26, C_HEADER);
        ctx.fill(GL, GT + 25, GL + GW, GT + 26, C_GOLD);

        // Заголовок
        String title = "§6🏧 " + ShopConfig.t("Банкомат", "ATM");
        ctx.drawText(textRenderer, title, GL + GW / 2 - textRenderer.getWidth(title.replaceAll("§.", "")) / 2, GT + 8, C_WHITE, true);

        // Баланс
        long phys = EconomyManager.getPhysicalBalance();
        long virt = ShopConfig.VIRTUAL_BALANCE;
        String balStr = ShopConfig.t("§fМонеты: §e", "§fCoins: §e") + phys
                + ShopConfig.t("  §fВиртуал: §b", "  §fVirtual: §b") + virt;
        ctx.drawText(textRenderer, balStr,
                GL + GW / 2 - textRenderer.getWidth(balStr.replaceAll("§.", "")) / 2, GT + 18, C_WHITE, false);

        super.render(ctx, mx, my, delta);

        if (section == 0) renderCoinExchange(ctx, mx, my);
        else if (section == 1) renderVirtual(ctx);
        else renderHistory(ctx);

        // Флеш-сообщение — поверх всего
        long now = System.currentTimeMillis();
        if (now < msgUntil) {
            int alpha = (int) Math.min(255, (msgUntil - now) / 4);
            int col = msgOk ? (0x00FF55 | (alpha << 24)) : (0xFF5555 | (alpha << 24));
            int mw = textRenderer.getWidth(lastMsg);
            ctx.drawText(textRenderer, lastMsg, GL + GW / 2 - mw / 2, GT + GH - 16, col, true);
        }
    }

    private void renderCoinExchange(DrawContext ctx, int mx, int my) {
        int startY = GT + 84;
        ctx.drawText(textRenderer,
                ShopConfig.t("§7Выбери монету (клик):", "§7Select a coin (click):"),
                GL + 10, startY, C_GRAY, false);

        Item[] coins = EconomyManager.allCoins();
        int cx = GL + 10;
        int cy = startY + 12;
        int slotSz = 22;
        int slotStep = 46; // чуть плотнее чтобы влезло 4 в ряд

        // Сначала рисуем фон панели выбранной монеты, чтобы монеты поверх него были видны
        if (selectedCoin != null) {
            int panX = GL + 210, panY = GT + 84;
            ctx.fill(panX - 4, panY - 4, GL + GW - 8, panY + 170, C_PANEL);
        }

        // Монеты рисуются в 2 ряда: первые 4 сверху, оставшиеся 3 снизу
        cx = GL + 10;
        int rowStartCy = cy;
        int coinsPerRow = 4;
        for (int i = 0; i < coins.length; i++) {
            if (i == coinsPerRow) {
                // Переход на второй ряд
                cx = GL + 10;
                cy = rowStartCy + slotSz + 20; // +20 для подписей
            }
            Item coin = coins[i];
            int inInv = EconomyManager.countCoin(coin);
            boolean sel = coin == selectedCoin;
            boolean hov = mx >= cx && mx < cx + slotSz && my >= cy && my < cy + slotSz;
            ctx.fill(cx - 1, cy - 1, cx + slotSz + 1, cy + slotSz + 1,
                    sel ? 0xAAFFD700 : hov ? C_SLOT_H : C_SLOT);
            ctx.drawItem(new ItemStack(coin), cx + 2, cy + 2);
            // Номинал
            String val = "×" + EconomyManager.coinValue(coin);
            ctx.drawText(textRenderer, "§e" + val, cx, cy + slotSz + 2, C_GOLD, false);
            // Кол-во в инвентаре
            ctx.drawText(textRenderer, "§f" + inInv, cx, cy + slotSz + 12, C_WHITE, false);
            cx += slotStep;
        }

        // Панель выбранной монеты — текст и детали (фон уже нарисован выше)
        if (selectedCoin != null) {
            int panX = GL + 210, panY = GT + 84;

            int inInv = EconomyManager.countCoin(selectedCoin);
            int val   = EconomyManager.coinValue(selectedCoin);
            ctx.drawItem(new ItemStack(selectedCoin, exchangeCount), panX, panY);
            // BUG-25 fix: текст «×val» рисуется на panY+18 (ниже иконки h=16), не внутри неё
            ctx.drawText(textRenderer,
                    "§e×" + val + ShopConfig.t(" монет", " coins"),
                    panX + 20, panY + 18, C_GOLD, true);
            ctx.drawText(textRenderer,
                    ShopConfig.t("§7В инвентаре: §f", "§7In inventory: §f") + inInv,
                    panX, panY + 30, C_GRAY, false);
            ctx.drawText(textRenderer,
                    ShopConfig.t("§7Кол-во: §b", "§7Count: §b") + exchangeCount,
                    panX + 20, panY + 98, C_WHITE, true);

            long totalVal = (long) val * exchangeCount;
            ctx.drawText(textRenderer,
                    ShopConfig.t("§7Сумма: §e", "§7Total: §e") + totalVal,
                    panX, panY + 138, C_WHITE, false);

            // Подсказка результата
            if (modeUp) {
                Item bigger = nextBigger(selectedCoin);
                if (bigger != null) {
                    int bigVal = EconomyManager.coinValue(bigger);
                    long give = totalVal / bigVal;
                    long rem  = totalVal % bigVal;
                    String hint = "→ " + give + "×" + bigVal;
                    if (rem > 0) hint += " + " + rem;
                    ctx.drawText(textRenderer, "§a" + hint, panX, panY + 150, C_GREEN, false);
                }
            } else {
                Item smaller = nextSmaller(selectedCoin);
                if (smaller != null) {
                    int smVal = EconomyManager.coinValue(smaller);
                    ctx.drawText(textRenderer,
                            "§e→ " + (totalVal / smVal) + "×" + smVal + ShopConfig.t(" и мелочь", " + rest"),
                            panX, panY + 150, 0xFFFFAA00, false);
                }
            }
        } else {
            ctx.drawText(textRenderer,
                    ShopConfig.t("§8← кликни монету слева", "§8← click a coin on the left"),
                    GL + 200, GT + 136, 0xFF555555, false);
        }
    }

    private void renderVirtual(DrawContext ctx) {
        int px = GL + 10, py = GT + 68;
        ctx.fill(px - 4, py - 4, GL + GW - 8, py + 160, C_PANEL);

        ctx.drawText(textRenderer, "§e" + ShopConfig.t("Управление виртуальным балансом", "Manage Virtual Balance"),
                px, py, C_GOLD, true);

        long phys = EconomyManager.getPhysicalBalance();
        long virt = ShopConfig.VIRTUAL_BALANCE;
        ctx.drawText(textRenderer, ShopConfig.t("§7Физические монеты: §e", "§7Physical coins: §e") + phys, px, py + 14, C_WHITE, false);
        ctx.drawText(textRenderer, ShopConfig.t("§7Виртуальный баланс: §b", "§7Virtual balance: §b") + virt, px, py + 24, C_WHITE, false);

        ctx.fill(px, py + 36, px + 430, py + 37, 0x44FFD700);

        // «Сумма:» — отдельная строка над кнопками быстрого выбора
        ctx.drawText(textRenderer, ShopConfig.t("§7Сумма: §e", "§7Amount: §e") + depositAmount, px, py + 40, C_WHITE, false);
        ctx.drawText(textRenderer, ShopConfig.t("§8Выбери сумму, затем нажми кнопку:", "§8Choose amount then use buttons below:"),
                px, py + 50, C_GRAY, false);
    }

    private void renderHistory(DrawContext ctx) {
        int px = GL + 10, py = GT + 48;
        ctx.fill(px - 4, py - 4, GL + GW - 8, GT + GH - 14, C_PANEL);

        ctx.drawText(textRenderer, "§e" + ShopConfig.t("История операций:", "Operation History:"),
                px, py, C_GOLD, true);

        if (ShopConfig.ATM_HISTORY.isEmpty()) {
            ctx.drawText(textRenderer, ShopConfig.t("§8Нет операций", "§8No operations"),
                    px, py + 16, C_GRAY, false);
        } else {
            int iy = py + 16;
            int idx = 0;
            // BUG-28 fix: брейк при iy > GT+GH-28, чтобы последняя строка (h=8) заканчивалась
            // не ниже GT+GH-20, давая 4px зазор до флеш-сообщения на GT+GH-16
            int maxLineW = GW - (px - GL) - 12;
            for (String entry : ShopConfig.ATM_HISTORY) {
                if (iy > GT + GH - 28) break;
                String num = "§8" + (++idx) + ". §7";
                String line = entry;
                // Обрезаем entry если не умещается
                while (textRenderer.getWidth(num + line) > maxLineW && line.length() > 4) {
                    line = line.substring(0, line.length() - 1);
                }
                if (line.length() < entry.length()) line += "…";
                ctx.drawText(textRenderer, num + line, px, iy, C_WHITE, false);
                iy += 12;
            }
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        if (section == 0) {
            // Клик по монете — 2 ряда: первые 4 монеты сверху, остальные снизу
            Item[] coins = EconomyManager.allCoins();
            int slotSz = 22, slotStep = 46, coinsPerRow = 4;
            int rowStartCy = GT + 96;
            int cx = GL + 10, cy = rowStartCy;
            for (int i = 0; i < coins.length; i++) {
                if (i == coinsPerRow) { cx = GL + 10; cy = rowStartCy + slotSz + 20; }
                if (mx >= cx && mx < cx + slotSz && my >= cy && my < cy + slotSz) {
                    selectedCoin = coins[i];
                    exchangeCount = 1;
                    rebuildWidgets();
                    return true;
                }
                cx += slotStep;
            }
        }
        return super.mouseClicked(mx, my, btn);
    }

    private void doExchange() {
        if (selectedCoin == null) return;
        boolean ok;
        if (modeUp) ok = EconomyManager.exchangeUp(selectedCoin, exchangeCount);
        else        ok = EconomyManager.exchangeDown(selectedCoin, exchangeCount);
        if (ok) showMsg(ShopConfig.t("Обмен выполнен!", "Exchange done!"), true);
        else    showMsg(ShopConfig.t("Не хватает монет!", "Not enough coins!"), false);
        exchangeCount = 1;
        rebuildWidgets();
    }

    private Item nextBigger(Item coin) {
        Item[] coins = EconomyManager.allCoins(); // от крупных к мелким
        for (int i = coins.length - 1; i > 0; i--) {
            if (coins[i] == coin) return coins[i - 1];
        }
        return null;
    }

    private Item nextSmaller(Item coin) {
        Item[] coins = EconomyManager.allCoins();
        for (int i = 0; i < coins.length - 1; i++) {
            if (coins[i] == coin) return coins[i + 1];
        }
        return null;
    }

    private void showMsg(String msg, boolean ok) {
        lastMsg = msg; msgOk = ok; msgUntil = System.currentTimeMillis() + 3500;
    }

    private void rebuildWidgets() { clearChildren(); init(); }

    @Override public boolean shouldPause() { return false; }
}