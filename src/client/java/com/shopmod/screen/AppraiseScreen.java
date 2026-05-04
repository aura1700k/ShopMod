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
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;

/**
 * Экран оценки предмета.
 *  - Инвентарь (3 строки), хотбар, броня (4 слота), левая рука
 *  - Клик по предмету → слот оценки справа
 *  - Показывает цены покупки и продажи
 *  - Кнопка «Продать» (если предмет есть в инвентаре)
 *  - Кнопка «В магазин» (купить) — переход в ShopScreen с выбором этого предмета
 */
@Environment(EnvType.CLIENT)
public class AppraiseScreen extends Screen {

    // ── layout ───────────────────────────────────────────────────────────────
    // BUG-16 fix: GH expanded from 308 to 320 so flash message at GT+GH-8 has clear space below buttons
    private static final int GW      = 430, GH = 320;
    private static final int SLOT    = 18;
    private static final int GAP     = 2;
    private static final int INV_COLS = 9;
    private static final int INV_ROWS = 3;
    private int GL, GT;

    // ── colors ───────────────────────────────────────────────────────────────
    private static final int C_BG     = 0xEF0d0d1a;
    private static final int C_HEADER = 0xFF1a1a3a;
    private static final int C_GOLD   = 0xFFFFD700;
    private static final int C_WHITE  = 0xFFFFFFFF;
    private static final int C_GRAY   = 0xFFAAAAAA;
    private static final int C_SLOT   = 0x44FFFFFF;
    private static final int C_SEL    = 0x99FFD700;
    private static final int C_HOV    = 0x55AACCFF;
    private static final int C_ARMOR  = 0x44FF8800;  // подсветка брони

    // ── appraise state ───────────────────────────────────────────────────────
    private String  appItemId   = null;
    private Item    appItem     = null;
    private int     appInvCount = 0;
    private int     appQty      = 1;

    // ── message flash ────────────────────────────────────────────────────────
    private String  lastMsg = "";
    private long    msgUntil = 0;
    private boolean msgOk   = true;

    public AppraiseScreen() { super(Text.literal("Appraise")); }

    // =========================================================================
    // INIT
    // =========================================================================
    @Override
    protected void init() {
        GL = (width - GW) / 2;
        GT = (height - GH) / 2;
        clearChildren();

        // Назад в магазин
        addDrawableChild(ButtonWidget.builder(
                        Text.literal("§7← " + ShopConfig.t("Магазин", "Shop")),
                        b -> MinecraftClient.getInstance().setScreen(new ShopScreen()))
                .dimensions(GL + 4, GT + 2, 65, 12).build());

        // Кнопки других экранов
        addDrawableChild(ButtonWidget.builder(
                        Text.literal("§6📦 " + ShopConfig.t("Опт.", "Bulk")),
                        b -> MinecraftClient.getInstance().setScreen(new BulkSellScreen()))
                .dimensions(GL + 72, GT + 2, 48, 12).build());

        addDrawableChild(ButtonWidget.builder(
                        Text.literal("§e🏧 ATM"),
                        b -> MinecraftClient.getInstance().setScreen(new ATMScreen()))
                .dimensions(GL + 124, GT + 2, 40, 12).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("✕"), b -> close())
                .dimensions(GL + GW - 14, GT + 2, 12, 12).build());

        if (appItem != null && appInvCount > 0) {
            int bx = GL + GW - 118, by = GT + 188;  // сдвинуто вниз, ниже текста «Спрос»
            addDrawableChild(ButtonWidget.builder(Text.literal("-"), b -> {
                appQty = Math.max(1, appQty - 1); rebuildWidgets();
            }).dimensions(bx, by, 18, 14).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("+"), b -> {
                appQty = Math.min(appInvCount, appQty + 1); rebuildWidgets();
            }).dimensions(bx + 42, by, 18, 14).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("x1"), b -> { appQty = 1; rebuildWidgets(); })
                    .dimensions(bx, by + 16, 28, 12).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("x" + appInvCount), b -> { appQty = appInvCount; rebuildWidgets(); })
                    .dimensions(bx + 32, by + 16, 36, 12).build());

            int aw = 104;
            int ax = GL + GW - aw - 6;
            // BUG-34 fix: кнопки x1/xN заканчиваются на by+28=GT+216; ПРОДАТЬ сдвинута на GT+220 — зазор 4px
            addDrawableChild(ButtonWidget.builder(
                            Text.literal("§c◀ " + ShopConfig.t("ПРОДАТЬ", "SELL")), b -> doSell())
                    .dimensions(ax, GT + 220, aw, 18).build());
        }

        // Кнопка «В магазин» — всегда если предмет выбран (для покупки)
        if (appItemId != null) {
            int aw = 104;
            int ax = GL + GW - aw - 6;
            addDrawableChild(ButtonWidget.builder(
                            Text.literal("§a▶ " + ShopConfig.t("В МАГАЗИН", "TO SHOP")), b -> goToShop())
                    .dimensions(ax, GT + 244, aw, 18).build());
        }
    }

    private void rebuildWidgets() { clearChildren(); init(); }

    // =========================================================================
    // RENDER
    // =========================================================================
    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        renderBackground(ctx, mx, my, delta);

        ctx.fill(GL + 3, GT + 3, GL + GW + 3, GT + GH + 3, 0x55000000);
        ctx.fill(GL,   GT,   GL + GW,   GT + GH,   C_BG);
        ctx.fill(GL,   GT,   GL + GW,   GT + 26,   C_HEADER);
        ctx.fill(GL,   GT + 25, GL + GW, GT + 26,  C_GOLD);

        String title = "§b🔍 " + ShopConfig.t("Оценить предмет", "Item Appraisal");
        int titleW = textRenderer.getWidth(title.replaceAll("§.", ""));
        // BUG-35 fix: кнопка ATM заканчивается на GL+164; заголовок не должен начинаться левее GL+168
        int titleX = GL + (GW - titleW) / 2;
        if (titleX < GL + 168) titleX = GL + 168;
        ctx.drawText(textRenderer, title, titleX, GT + 8, C_WHITE, true);

        ctx.drawText(textRenderer,
                ShopConfig.t("§7Кликни по предмету:", "§7Click an item:"),
                GL + 8, GT + 30, C_GRAY, false);

        var player = MinecraftClient.getInstance().player;
        if (player == null) { super.render(ctx, mx, my, delta); return; }

        // ── Основной инвентарь (3 ряда, слоты 9-35) ─────────────────────────
        int invX = GL + 8;
        int invY = GT + 42;
        for (int row = 0; row < INV_ROWS; row++) {
            for (int col = 0; col < INV_COLS; col++) {
                int slotIdx = 9 + row * INV_COLS + col;
                int sx = invX + col * (SLOT + GAP);
                int sy = invY + row * (SLOT + GAP);
                drawInvSlot(ctx, player.getInventory().getStack(slotIdx), sx, sy, mx, my, false);
            }
        }

        // Разделитель хотбар
        int hotbarY = invY + INV_ROWS * (SLOT + GAP) + 14; // +14: место для метки под разделителем
        ctx.fill(invX, hotbarY - 12, invX + INV_COLS * (SLOT + GAP), hotbarY - 11, 0x44FFD700);
        ctx.drawText(textRenderer, ShopConfig.t("§8Хотбар", "§8Hotbar"), invX, hotbarY - 10, 0xFF555555, false);

        // ── Хотбар (слоты 0-8) ───────────────────────────────────────────────
        for (int col = 0; col < INV_COLS; col++) {
            int sx = invX + col * (SLOT + GAP);
            drawInvSlot(ctx, player.getInventory().getStack(col), sx, hotbarY, mx, my, false);
        }

        // ── Броня (4 слота: голова, нагрудник, поножи, ботинки) ─────────────
        int armorX = invX;
        // BUG-23 fix: armorY сдвинут +2, метка на armorY-10 → зазор 2px до слотов
        int armorY = hotbarY + SLOT + GAP + 10;
        ctx.drawText(textRenderer, ShopConfig.t("§8Броня:", "§8Armor:"), armorX, armorY - 10, 0xFF555555, false);
        String[] armorLabels = {
                ShopConfig.t("🪖", "🪖"), // голова
                ShopConfig.t("🧥", "🧥"), // нагрудник
                ShopConfig.t("👖", "👖"), // ноги
                ShopConfig.t("👟", "👟"), // ботинки
        };
        for (int i = 0; i < 4; i++) {
            int sx = armorX + i * (SLOT + GAP + 4);
            // Броня хранится в обратном порядке: 3=голова, 2=нагрудник, 1=поножи, 0=ботинки
            ItemStack armorStack = player.getInventory().getArmorStack(3 - i);
            drawInvSlot(ctx, armorStack, sx, armorY, mx, my, true);
        }

        // ── Левая рука ────────────────────────────────────────────────────────
        int offX = armorX + 4 * (SLOT + GAP + 4) + 8;
        ctx.drawText(textRenderer, ShopConfig.t("§8Лев.рука:", "§8Offhand:"), offX, armorY - 10, 0xFF555555, false);
        drawInvSlot(ctx, player.getOffHandStack(), offX, armorY, mx, my, true);

        // ── Слот оценки (справа) ─────────────────────────────────────────────
        int ox = GL + GW - 120;
        int oy = GT + 42;
        ctx.fill(ox - 4, oy - 4, GL + GW - 6, GT + GH - 14, 0x88000020);

        String slotLabel = ShopConfig.t("§eОценка", "§eAppraisal");
        // BUG-47 fix: панель ox-4..GL+GW-6 = 118px; центрируем по реальной ширине
        // BUG-24 fix: метка сдвинута на oy-14 (было oy-12), зазор 2px до фона панели на oy-4
        int panelW = (GL + GW - 6) - (ox - 4); // = 118
        ctx.drawText(textRenderer, slotLabel,
                (ox - 4) + (panelW - textRenderer.getWidth(slotLabel.replaceAll("§.", ""))) / 2, oy - 14, C_GOLD, false);

        // Большой слот предмета
        ctx.fill(ox - 2, oy - 2, ox + 22, oy + 22, appItem != null ? 0xAAFFD700 : 0x44FFFFFF);
        if (appItem != null) {
            ItemStack display = new ItemStack(appItem, appQty);
            ctx.drawItem(display, ox, oy);
            ctx.drawItemInSlot(textRenderer, display, ox, oy);
        } else {
            ctx.drawText(textRenderer, "§8?", ox + 7, oy + 6, 0xFF444444, false);
        }

        // Данные об оценке
        if (appItemId != null && appItem != null) {
            long sellPriceEa = ShopConfig.getSellPrice(appItemId);
            long buyPriceEa  = ShopConfig.getBuyPrice(appItemId);
            long totalSell   = sellPriceEa * appQty;
            long totalBuy    = buyPriceEa  * appQty;
            long available   = ShopConfig.SPEND_PHYSICAL
                    ? EconomyManager.getPhysicalBalance()
                    : ShopConfig.VIRTUAL_BALANCE;

            // BUG-17 fix: clamp iy so text never reaches button zone (GT+204 = ПРОДАТЬ button)
            final int IY_MAX = GT + 200;
            int iy = oy + 28;
            String name = new ItemStack(appItem).getName().getString();
            // Сократить если длинное
            if (textRenderer.getWidth(name) > 104) name = name.substring(0, 11) + "…";
            if (iy <= IY_MAX) ctx.drawText(textRenderer, "§f" + name, ox, iy, C_WHITE, true);
            iy += 12;

            if (iy <= IY_MAX) { ctx.fill(ox - 2, iy, ox + 108, iy + 1, 0x44FFD700); }
            iy += 4;

            if (iy <= IY_MAX) ctx.drawText(textRenderer, ShopConfig.t("§7В инв: §f", "§7In inv: §f") + appInvCount, ox, iy, C_GRAY, false);
            iy += 10;
            if (iy <= IY_MAX) ctx.drawText(textRenderer, ShopConfig.t("§7Кол-во: §b", "§7Qty: §b") + appQty, ox, iy, C_WHITE, false);
            iy += 12;

            if (iy <= IY_MAX) { ctx.fill(ox - 2, iy, ox + 108, iy + 1, 0x33FFFFFF); }
            iy += 4;

            if (iy <= IY_MAX) ctx.drawText(textRenderer, ShopConfig.t("§7Купить §8(шт): §e", "§7Buy §8(ea): §e") + buyPriceEa, ox, iy, C_WHITE, false);
            iy += 10;
            if (iy <= IY_MAX) ctx.drawText(textRenderer, ShopConfig.t("§7Итого купить: §e", "§7Total buy: §e") + totalBuy, ox, iy, C_WHITE, false);
            iy += 10;
            boolean canAfford = available >= totalBuy;
            if (iy <= IY_MAX) ctx.drawText(textRenderer,
                    canAfford ? ShopConfig.t("§a✔ Хватит", "§a✔ Enough")
                            : ShopConfig.t("§c✘ Нехватит", "§c✘ Insufficient"),
                    ox, iy, C_WHITE, false);
            iy += 12;

            if (iy <= IY_MAX) { ctx.fill(ox - 2, iy, ox + 108, iy + 1, 0x33FFFFFF); }
            iy += 4;

            if (iy <= IY_MAX) ctx.drawText(textRenderer, ShopConfig.t("§7Продать §8(шт): §a", "§7Sell §8(ea): §a") + sellPriceEa, ox, iy, C_WHITE, false);
            iy += 10;
            if (iy <= IY_MAX) ctx.drawText(textRenderer, ShopConfig.t("§7Итого продать: §a", "§7Total sell: §a") + totalSell, ox, iy, C_WHITE, false);
            iy += 12;

            if (ShopConfig.DYNAMIC_PRICES) {
                int tx = ShopConfig.TRANSACTION_COUNT.getOrDefault(appItemId, 0);
                String demand = tx < 3  ? ShopConfig.t("§aНизкий", "§aLow")
                        : tx < 8  ? ShopConfig.t("§eСредний", "§eMid")
                        : tx < 15 ? ShopConfig.t("§6Высокий", "§6High")
                        :           ShopConfig.t("§cОч.высокий", "§cV.High");
                if (iy <= IY_MAX) ctx.drawText(textRenderer, ShopConfig.t("§7Спрос: ", "§7Demand: ") + demand, ox, iy, C_WHITE, false);
                iy += 10;
            }

            // Популярность
            long sold = ShopConfig.SELL_STATS.getOrDefault(appItemId, 0L);
            if (sold > 0 && iy <= IY_MAX) {
                ctx.drawText(textRenderer, ShopConfig.t("§7Продано: §f", "§7Sold total: §f") + sold, ox, iy, C_WHITE, false);
            }
        } else {
            ctx.drawText(textRenderer, ShopConfig.t("§8← кликни предмет", "§8← click an item"),
                    ox + 2, oy + 30, 0xFF555555, false);
        }

        super.render(ctx, mx, my, delta);

        // Флеш — поверх кнопок
        long now = System.currentTimeMillis();
        if (now < msgUntil) {
            int alpha = (int) Math.min(255, (msgUntil - now) / 4);
            int col = msgOk ? (0x00FF55 | (alpha << 24)) : (0xFF5555 | (alpha << 24));
            int mw = textRenderer.getWidth(lastMsg);
            ctx.drawText(textRenderer, lastMsg, GL + GW / 2 - mw / 2, GT + GH - 8, col, true);
        }

        renderInvTooltips(ctx, player, invX, invY, hotbarY, mx, my);
    }

    private void drawInvSlot(DrawContext ctx, ItemStack stack, int sx, int sy, int mx, int my, boolean isSpecial) {
        boolean hovered  = mx >= sx && mx < sx + SLOT && my >= sy && my < sy + SLOT;
        boolean selected = !stack.isEmpty() && appItem != null && stack.getItem() == appItem;
        int bg = selected ? C_SEL : hovered ? C_HOV : isSpecial ? C_ARMOR : C_SLOT;
        ctx.fill(sx - 1, sy - 1, sx + SLOT + 1, sy + SLOT + 1, bg);
        if (!stack.isEmpty()) {
            ctx.drawItem(stack, sx, sy);
            ctx.drawItemInSlot(textRenderer, stack, sx, sy);
        }
    }

    private void renderInvTooltips(DrawContext ctx, net.minecraft.entity.player.PlayerEntity player,
                                   int invX, int invY, int hotbarY, int mx, int my) {
        for (int row = 0; row < INV_ROWS; row++) {
            for (int col = 0; col < INV_COLS; col++) {
                int sx = invX + col * (SLOT + GAP);
                int sy = invY + row * (SLOT + GAP);
                if (mx >= sx && mx < sx + SLOT && my >= sy && my < sy + SLOT) {
                    ItemStack stack = player.getInventory().getStack(9 + row * INV_COLS + col);
                    if (!stack.isEmpty()) renderStackTooltip(ctx, stack, mx, my);
                }
            }
        }
        for (int col = 0; col < INV_COLS; col++) {
            int sx = invX + col * (SLOT + GAP);
            if (mx >= sx && mx < sx + SLOT && my >= hotbarY && my < hotbarY + SLOT) {
                ItemStack stack = player.getInventory().getStack(col);
                if (!stack.isEmpty()) renderStackTooltip(ctx, stack, mx, my);
            }
        }
        // Броня
        int armorY = hotbarY + SLOT + GAP + 10; // BUG-23 fix: sync with render
        for (int i = 0; i < 4; i++) {
            int sx = invX + i * (SLOT + GAP + 4);
            if (mx >= sx && mx < sx + SLOT && my >= armorY && my < armorY + SLOT) {
                ItemStack stack = player.getInventory().getArmorStack(3 - i);
                if (!stack.isEmpty()) renderStackTooltip(ctx, stack, mx, my);
            }
        }
        // Левая рука
        int offX = invX + 4 * (SLOT + GAP + 4) + 8;
        int armorY2 = hotbarY + SLOT + GAP + 10; // BUG-23 fix: sync with render
        if (mx >= offX && mx < offX + SLOT && my >= armorY2 && my < armorY2 + SLOT) {
            ItemStack stack = player.getOffHandStack();
            if (!stack.isEmpty()) renderStackTooltip(ctx, stack, mx, my);
        }
    }

    private void renderStackTooltip(DrawContext ctx, ItemStack stack, int mx, int my) {
        String id = Registries.ITEM.getId(stack.getItem()).toString();
        List<Text> tt = new ArrayList<>();
        tt.add(Text.literal("§f" + stack.getName().getString()));
        if (ShopConfig.hasItem(id)) {
            long buy  = ShopConfig.getBuyPrice(id);
            long sell = ShopConfig.getSellPrice(id);
            tt.add(Text.literal(ShopConfig.t("§7Купить: §e", "§7Buy: §e") + buy + ShopConfig.t(" монет", " coins")));
            tt.add(Text.literal(ShopConfig.t("§7Продать: §a", "§7Sell: §a") + sell + ShopConfig.t(" монет", " coins")));
            tt.add(Text.literal(ShopConfig.t("§8Кликни для оценки", "§8Click to appraise")));
        } else {
            tt.add(Text.literal(ShopConfig.t("§8Не продаётся", "§8Not for sale")));
        }
        ctx.drawTooltip(textRenderer, tt, mx, my);
    }

    // =========================================================================
    // INPUT
    // =========================================================================
    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        var player = MinecraftClient.getInstance().player;
        if (player == null) return super.mouseClicked(mx, my, btn);

        int invX = GL + 8, invY = GT + 42;
        int hotbarY = invY + INV_ROWS * (SLOT + GAP) + 14;
        int armorY  = hotbarY + SLOT + GAP + 8;
        int offX    = invX + 4 * (SLOT + GAP + 4) + 8;

        // Основной инвентарь
        for (int row = 0; row < INV_ROWS; row++) {
            for (int col = 0; col < INV_COLS; col++) {
                int sx = invX + col * (SLOT + GAP), sy = invY + row * (SLOT + GAP);
                if (mx >= sx && mx < sx + SLOT && my >= sy && my < sy + SLOT) {
                    handleSlotClick(player.getInventory().getStack(9 + row * INV_COLS + col)); return true;
                }
            }
        }
        // Хотбар
        for (int col = 0; col < INV_COLS; col++) {
            int sx = invX + col * (SLOT + GAP);
            if (mx >= sx && mx < sx + SLOT && my >= hotbarY && my < hotbarY + SLOT) {
                handleSlotClick(player.getInventory().getStack(col)); return true;
            }
        }
        // Броня
        for (int i = 0; i < 4; i++) {
            int sx = invX + i * (SLOT + GAP + 4);
            if (mx >= sx && mx < sx + SLOT && my >= armorY && my < armorY + SLOT) {
                handleSlotClick(player.getInventory().getArmorStack(3 - i)); return true;
            }
        }
        // Левая рука
        if (mx >= offX && mx < offX + SLOT && my >= armorY && my < armorY + SLOT) {
            handleSlotClick(player.getOffHandStack()); return true;
        }
        return super.mouseClicked(mx, my, btn);
    }

    private void handleSlotClick(ItemStack stack) {
        if (stack.isEmpty()) {
            appItemId = null; appItem = null; appInvCount = 0; appQty = 1;
            rebuildWidgets(); return;
        }
        Item item = stack.getItem();
        if (EconomyManager.isCoin(item)) {
            showMsg(ShopConfig.t("Монеты нельзя оценивать", "Can't appraise coins"), false); return;
        }
        String id = Registries.ITEM.getId(item).toString();
        if (!ShopConfig.hasItem(id)) {
            showMsg(ShopConfig.t("Предмет не продаётся", "Item not in shop"), false); return;
        }
        appItemId   = id;
        appItem     = item;
        appInvCount = EconomyManager.countItem(item);
        appQty      = Math.min(Math.max(1, appQty), Math.max(1, appInvCount));
        rebuildWidgets();
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hAmt, double vAmt) {
        if (appItem != null && appInvCount > 0) {
            // BUG-48 fix: пересобираем виджеты только если appQty изменился
            int newQty = Math.max(1, Math.min(appInvCount, appQty - (int) Math.signum(vAmt)));
            if (newQty != appQty) {
                appQty = newQty;
                rebuildWidgets();
            }
            return true;
        }
        return super.mouseScrolled(mx, my, hAmt, vAmt);
    }

    // =========================================================================
    // TRADE LOGIC
    // =========================================================================
    private void doSell() {
        if (appItem == null || appInvCount < appQty) {
            showMsg(ShopConfig.t("Предмет не найден", "Item not found"), false); return;
        }
        long earned = ShopConfig.getSellPrice(appItemId) * appQty;
        boolean ok = ShopConfig.RECEIVE_PHYSICAL
                ? EconomyManager.sellForPhysical(appItem, appItemId, appQty, earned)
                : EconomyManager.sellForVirtual(appItem, appItemId, appQty, earned);
        if (!ok) { showMsg(ShopConfig.t("Ошибка продажи!", "Sell failed!"), false); return; }
        ShopConfig.decrementTransaction(appItemId);
        ShopConfig.recordSale(appItemId, appQty);
        showMsg(ShopConfig.t("Продано ", "Sold ") + appQty + "x " + ShopConfig.t(" за ", " for ") + earned, true);
        appInvCount = EconomyManager.countItem(appItem);
        if (appInvCount <= 0) { appItem = null; appItemId = null; appQty = 1; }
        else appQty = Math.min(appQty, appInvCount);
        rebuildWidgets();
    }

    /** Перейти в магазин с выбранным предметом для покупки */
    private void goToShop() {
        // Передаём выбранный предмет через статическое поле ShopScreen
        ShopScreen shop = new ShopScreen();
        shop.preselect(appItemId);
        MinecraftClient.getInstance().setScreen(shop);
    }

    // =========================================================================
    // HELPERS
    // =========================================================================
    private void showMsg(String msg, boolean ok) {
        lastMsg = msg; msgOk = ok; msgUntil = System.currentTimeMillis() + 4000;
    }

    @Override public boolean shouldPause() { return false; }
}