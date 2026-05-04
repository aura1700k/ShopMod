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
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.*;

/**
 * Оптовая скупка.
 *
 * Каждый слот инвентаря — независимая галочка.
 * Клик по слоту переключает только этот слот, а не все слоты с тем же предметом.
 */
@Environment(EnvType.CLIENT)
public class BulkSellScreen extends Screen {

    // ── layout ───────────────────────────────────────────────────────────────
    private static final int GW   = 460, GH = 324;
    private static final int SLOT = 18, GAP = 2;
    private static final int INV_COLS = 9, INV_ROWS = 3;
    private int GL, GT;

    // ── colors ───────────────────────────────────────────────────────────────
    private static final int C_BG      = 0xEF0d0d1a;
    private static final int C_HEADER  = 0xFF1a1a3a;
    private static final int C_GOLD    = 0xFFFFD700;
    private static final int C_WHITE   = 0xFFFFFFFF;
    private static final int C_GRAY    = 0xFFAAAAAA;
    private static final int C_SLOT_E  = 0x33FFFFFF;
    private static final int C_SLOT_F  = 0x55FFFFFF;
    private static final int C_SLOT_H  = 0x66AACCFF;
    private static final int C_SLOT_A  = 0x44FF8800;  // броня
    private static final int C_CHECKED = 0xAA55FF55;   // отмечен
    private static final int C_PANEL   = 0x88000020;
    private static final int C_LINE    = 0x44FFD700;
    private static final int C_LINE2   = 0x22FFFFFF;

    // ── Слот инвентаря ────────────────────────────────────────────────────────
    /** Тип источника слота */
    private enum SlotSource { MAIN, HOTBAR, ARMOR, OFFHAND }

    private static class InvSlot {
        final SlotSource source;
        final int        index;   // индекс в своём массиве
        final ItemStack  stack;
        final String     itemId;
        boolean          checked;

        InvSlot(SlotSource src, int idx, ItemStack st, String id, boolean chk) {
            source = src; index = idx; stack = st; itemId = id; checked = chk;
        }
    }

    /** Все слоты инвентаря (заполненные и продаваемые — все, чтобы рендерить) */
    private final List<InvSlot> invSlots = new ArrayList<>();

    private long totalEarnings = 0L;
    private boolean confirming = false;

    // Прокрутка правой панели
    private int rightScroll = 0;
    private static final int RIGHT_VISIBLE = 12;

    // ── message ───────────────────────────────────────────────────────────────
    private String  lastMsg  = "";
    private long    msgUntil = 0;
    private boolean msgOk    = true;

    public BulkSellScreen() {
        super(Text.literal("BulkSell"));
        scanInventory();
    }

    // =========================================================================
    // INIT
    // =========================================================================
    @Override
    protected void init() {
        GL = (width - GW) / 2;
        GT = (height - GH) / 2;
        clearChildren();

        // Навигация
        addDrawableChild(ButtonWidget.builder(
                        Text.literal("§7← " + ShopConfig.t("Магазин", "Shop")),
                        b -> MinecraftClient.getInstance().setScreen(new ShopScreen()))
                .dimensions(GL + 4, GT + 2, 60, 12).build());

        addDrawableChild(ButtonWidget.builder(
                        Text.literal("§b🔍 " + ShopConfig.t("Оценить", "Appraise")),
                        b -> MinecraftClient.getInstance().setScreen(new AppraiseScreen()))
                .dimensions(GL + 67, GT + 2, 60, 12).build());

        addDrawableChild(ButtonWidget.builder(
                        Text.literal("§e🏧 ATM"),
                        b -> MinecraftClient.getInstance().setScreen(new ATMScreen()))
                .dimensions(GL + 130, GT + 2, 40, 12).build());

        addDrawableChild(ButtonWidget.builder(Text.literal("✕"), b -> close())
                .dimensions(GL + GW - 14, GT + 2, 12, 12).build());

        // Кнопка «Обновить»
        addDrawableChild(ButtonWidget.builder(
                        Text.literal("§e↺ " + ShopConfig.t("Обновить", "Refresh")),
                        b -> { confirming = false; scanInventory(); rebuildWidgets(); })
                .dimensions(GL + GW - 92, GT + 2, 74, 12).build());

        // Выбрать/снять всё
        addDrawableChild(ButtonWidget.builder(
                        Text.literal(ShopConfig.t("§7✔ Все", "§7✔ All")),
                        b -> { invSlots.stream().filter(s -> s.itemId != null).forEach(s -> s.checked = true); recalcTotal(); rebuildWidgets(); })
                .dimensions(GL + 4, GT + GH - 40, 44, 18).build());
        addDrawableChild(ButtonWidget.builder(
                        Text.literal(ShopConfig.t("§7✘ Ни одного", "§7✘ None")),
                        b -> { invSlots.stream().filter(s -> s.itemId != null).forEach(s -> s.checked = false); recalcTotal(); rebuildWidgets(); })
                .dimensions(GL + 50, GT + GH - 40, 72, 18).build());

        if (totalEarnings > 0) {
            addDrawableChild(ButtonWidget.builder(
                            Text.literal("§a✔ " + ShopConfig.t("Продать выбранное", "Sell Selected")),
                            b -> { confirming = true; rebuildWidgets(); })
                    .dimensions(GL + GW / 2 - 80, GT + GH - 40, 160, 18).build());
        }

        // Стрелки прокрутки правой панели
        long rightRows = invSlots.stream().filter(s -> s.itemId != null).map(s -> s.itemId).distinct().count();
        if (rightRows > RIGHT_VISIBLE) {
            addDrawableChild(ButtonWidget.builder(Text.literal("▲"),
                            b -> { rightScroll = Math.max(0, rightScroll - 1); rebuildWidgets(); })
                    .dimensions(GL + GW - 14, GT + 16, 10, 10).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("▼"),
                            b -> { rightScroll = Math.min((int)(rightRows - RIGHT_VISIBLE), rightScroll + 1); rebuildWidgets(); })
                    .dimensions(GL + GW - 14, GT + GH - 58, 10, 10).build());
        }
    }

    private void rebuildWidgets() { clearChildren(); init(); }

    private int rightPanelX() { return GL + 8 + INV_COLS * (SLOT + GAP) + 10; }

    // =========================================================================
    // SCAN
    // =========================================================================
    private void scanInventory() {
        // Запомним предыдущее состояние галочек по ключу "source:index"
        Map<String, Boolean> prevChecked = new HashMap<>();
        for (InvSlot s : invSlots) {
            prevChecked.put(slotKey(s), s.checked);
        }

        invSlots.clear();
        rightScroll = 0;

        var player = MinecraftClient.getInstance().player;
        if (player == null) { totalEarnings = 0; return; }

        // Основной инвентарь (9-35)
        for (int i = 9; i < 36; i++) {
            addSlot(SlotSource.MAIN, i, player.getInventory().getStack(i), prevChecked);
        }
        // Хотбар (0-8)
        for (int i = 0; i < 9; i++) {
            addSlot(SlotSource.HOTBAR, i, player.getInventory().getStack(i), prevChecked);
        }
        // Броня
        for (int i = 0; i < 4; i++) {
            addSlot(SlotSource.ARMOR, i, player.getInventory().getArmorStack(i), prevChecked);
        }
        // Левая рука
        addSlot(SlotSource.OFFHAND, 0, player.getOffHandStack(), prevChecked);

        recalcTotal();
    }

    private String slotKey(InvSlot s) { return s.source.name() + ":" + s.index; }

    private void addSlot(SlotSource src, int idx, ItemStack stack, Map<String, Boolean> prev) {
        String key = src.name() + ":" + idx;
        String id = null;
        if (!stack.isEmpty() && !EconomyManager.isCoin(stack.getItem())) {
            String rawId = Registries.ITEM.getId(stack.getItem()).toString();
            if (ShopConfig.hasItem(rawId)) {
                id = rawId;
            } else {
                // Для зелий, стрел с эффектом, книг — нужно сравнивать не только базовый item,
                // но и реальный вариант (эффект зелья) из PotionContentsComponent.
                // Иначе все стрелы/зелья матчатся на первый попавшийся вариант в конфиге.
                String potionVariant = getPotionVariant(stack);
                for (String shopId : ShopConfig.getAllItems()) {
                    if (!ShopConfig.getBaseItemId(shopId).equals(rawId)) continue;
                    if (potionVariant != null) {
                        // Есть реальный вариант — ищем точное совпадение
                        if (shopId.endsWith(":" + potionVariant)) {
                            id = shopId;
                            break;
                        }
                    } else {
                        // Нет варианта (зачарованная книга без NBT и т.п.) — берём первый
                        id = shopId;
                        break;
                    }
                }
            }
        }
        boolean chk = prev.getOrDefault(key, Boolean.TRUE);
        invSlots.add(new InvSlot(src, idx, stack, id, chk));
    }

    /**
     * Читает реальный эффект зелья/стрелы из PotionContentsComponent.
     * Возвращает строку вида "turtle_master", "long_night_vision" и т.п.,
     * или null если компонент отсутствует.
     */
    private static String getPotionVariant(ItemStack stack) {
        var comp = stack.get(net.minecraft.component.DataComponentTypes.POTION_CONTENTS);
        if (comp == null) return null;
        var potionOpt = comp.potion();
        if (potionOpt == null || potionOpt.isEmpty()) return null;
        // getId возвращает Identifier вида "minecraft:turtle_master"
        var entry = potionOpt.get();
        var id = net.minecraft.registry.Registries.POTION.getId(entry.value());
        if (id == null) return null;
        return id.getPath(); // только "turtle_master", без namespace
    }

    private void recalcTotal() {
        totalEarnings = invSlots.stream()
                .filter(s -> s.itemId != null && s.checked)
                .mapToLong(s -> ShopConfig.getSellPrice(s.itemId) * s.stack.getCount())
                .sum();
    }

    // =========================================================================
    // RENDER
    // =========================================================================
    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        renderBackground(ctx, mx, my, delta);

        ctx.fill(GL + 3, GT + 3, GL + GW + 3, GT + GH + 3, 0x55000000);
        ctx.fill(GL, GT, GL + GW, GT + GH, C_BG);
        ctx.fill(GL, GT, GL + GW, GT + 26, C_HEADER);
        ctx.fill(GL, GT + 25, GL + GW, GT + 26, C_GOLD);

        String title = "§6📦 " + ShopConfig.t("Оптовая скупка", "Bulk Sell");
        ctx.drawText(textRenderer, title,
                GL + (GW - textRenderer.getWidth(title.replaceAll("§.", ""))) / 2,
                GT + 8, C_WHITE, true);

        renderInvArea(ctx, mx, my);
        renderRightPanel(ctx, mx, my);
        renderSummaryBar(ctx);

        super.render(ctx, mx, my, delta);

        if (confirming) renderConfirmOverlay(ctx);

        long now = System.currentTimeMillis();
        if (now < msgUntil) {
            int alpha = (int) Math.min(255, (msgUntil - now) / 4);
            int col = msgOk ? (0x00FF55 | (alpha << 24)) : (0xFF5555 | (alpha << 24));
            int mw = textRenderer.getWidth(lastMsg);
            ctx.drawText(textRenderer, lastMsg, GL + GW / 2 - mw / 2, GT + GH - 8, col, true);
        }
    }

    // ── Левая часть: инвентарь ────────────────────────────────────────────────
    private void renderInvArea(DrawContext ctx, int mx, int my) {
        int invX = GL + 8, invY = GT + 38;
        ctx.drawText(textRenderer, ShopConfig.t("§7Инвентарь:", "§7Inventory:"), invX, invY - 10, C_GRAY, false);

        // 3 ряда инвентаря (MAIN, индексы 9-35)
        List<InvSlot> main = slotsOf(SlotSource.MAIN);
        for (int row = 0; row < INV_ROWS; row++) {
            for (int col = 0; col < INV_COLS; col++) {
                int sx = invX + col * (SLOT + GAP);
                int sy = invY + row * (SLOT + GAP);
                InvSlot s = main.get(row * INV_COLS + col);
                renderSellSlot(ctx, s, sx, sy, mx, my, false);
            }
        }

        int hotbarY = invY + INV_ROWS * (SLOT + GAP) + 14;
        ctx.fill(invX, hotbarY - 12, invX + INV_COLS * (SLOT + GAP), hotbarY - 11, 0x44FFD700);
        ctx.drawText(textRenderer, ShopConfig.t("§8Хотбар", "§8Hotbar"), invX, hotbarY - 10, 0xFF555555, false);

        List<InvSlot> hotbar = slotsOf(SlotSource.HOTBAR);
        for (int col = 0; col < INV_COLS; col++) {
            int sx = invX + col * (SLOT + GAP);
            renderSellSlot(ctx, hotbar.get(col), sx, hotbarY, mx, my, false);
        }

        int armorY = hotbarY + SLOT + GAP + 12;
        ctx.drawText(textRenderer, ShopConfig.t("§8Броня:", "§8Armor:"), invX, armorY - 10, 0xFF555555, false);
        List<InvSlot> armor = slotsOf(SlotSource.ARMOR);
        for (int i = 0; i < 4; i++) {
            int sx = invX + i * (SLOT + GAP + 2);
            // отображаем 3→0 (голова→ботинки), но слот armor.get(3-i)
            renderSellSlot(ctx, armor.get(3 - i), sx, armorY, mx, my, true);
        }

        int offX = invX + 4 * (SLOT + GAP + 2) + 8;
        ctx.drawText(textRenderer, ShopConfig.t("§8Доп:", "§8Off:"), offX, armorY - 10, 0xFF555555, false);
        List<InvSlot> offhand = slotsOf(SlotSource.OFFHAND);
        renderSellSlot(ctx, offhand.get(0), offX, armorY, mx, my, true);
    }

    private List<InvSlot> slotsOf(SlotSource src) {
        List<InvSlot> out = new ArrayList<>();
        for (InvSlot s : invSlots) if (s.source == src) out.add(s);
        return out;
    }

    private void renderSellSlot(DrawContext ctx, InvSlot slot, int sx, int sy, int mx, int my, boolean isSpecial) {
        boolean hovered  = mx >= sx && mx < sx + SLOT && my >= sy && my < sy + SLOT;
        boolean sellable = slot.itemId != null;
        boolean ticked   = sellable && slot.checked;

        int bg = ticked ? C_CHECKED : hovered ? C_SLOT_H : isSpecial ? C_SLOT_A : (sellable ? C_SLOT_F : C_SLOT_E);
        ctx.fill(sx - 1, sy - 1, sx + SLOT + 1, sy + SLOT + 1, bg);

        if (!slot.stack.isEmpty()) {
            ctx.drawItem(slot.stack, sx, sy);
            ctx.drawItemInSlot(textRenderer, slot.stack, sx, sy);
        }

        if (sellable) {
            String mark = ticked ? "§a✔" : "§c✘";
            ctx.drawText(textRenderer, mark, sx + 1, sy + 1, C_WHITE, false);
        }
    }

    // ── Правая панель: таблица предметов (агрегировано по itemId + checked) ──
    private void renderRightPanel(DrawContext ctx, int mx, int my) {
        int px  = rightPanelX();
        int py  = GT + 28;
        int pw  = GW - (px - GL) - 6;
        int rowH = 13;

        ctx.fill(px - 4, py - 4, GL + GW - 4, GT + GH - 20, C_PANEL);

        ctx.drawText(textRenderer, ShopConfig.t("§eПредмет", "§eItem"), px, py, C_GOLD, false);
        ctx.drawText(textRenderer, ShopConfig.t("§eКол", "§eQty"), px + 118, py, C_GOLD, false);
        ctx.drawText(textRenderer, ShopConfig.t("§eЦена", "§ePrice"), px + 145, py, C_GOLD, false);
        ctx.drawText(textRenderer, ShopConfig.t("§eИтого", "§eTotal"), px + 198, py, C_GOLD, false);
        py += 10;
        ctx.fill(px, py, px + pw - 2, py + 1, C_LINE);
        py += 3;

        // Строим агрегированный список: уникальные itemId с суммой выбранных и полного кол-ва
        List<String> uniqueIds = new ArrayList<>();
        for (InvSlot s : invSlots) {
            if (s.itemId != null && !uniqueIds.contains(s.itemId)) uniqueIds.add(s.itemId);
        }

        if (uniqueIds.isEmpty()) {
            ctx.drawText(textRenderer, ShopConfig.t("§8Нет продаваемых предметов", "§8No sellable items"),
                    px, py + 20, C_GRAY, false);
            return;
        }

        int startIdx = Math.min(rightScroll, Math.max(0, uniqueIds.size() - RIGHT_VISIBLE));
        int endIdx   = Math.min(uniqueIds.size(), startIdx + RIGHT_VISIBLE);

        for (int i = startIdx; i < endIdx; i++) {
            String id = uniqueIds.get(i);
            // Сколько выбрано vs всего
            int checkedAmt = invSlots.stream().filter(s -> id.equals(s.itemId) && s.checked).mapToInt(s -> s.stack.getCount()).sum();
            int totalAmt   = invSlots.stream().filter(s -> id.equals(s.itemId)).mapToInt(s -> s.stack.getCount()).sum();
            long price = ShopConfig.getSellPrice(id);
            long total = price * checkedAmt;
            boolean anyChecked = checkedAmt > 0;

            if ((i - startIdx) % 2 == 0) ctx.fill(px - 2, py - 1, px + pw - 2, py + rowH, 0x11FFFFFF);

            // Галочка в таблице — показывает состояние (частично/все/ничего)
            boolean hovCheck = mx >= px - 2 && mx < px + 8 && my >= py - 1 && my < py + rowH;
            ctx.fill(px - 2, py - 1, px + 8, py + rowH - 1, hovCheck ? 0x33FFFFFF : 0);
            String checkMark = (checkedAmt == totalAmt) ? "§a✔" : (checkedAmt == 0 ? "§c✘" : "§e~");
            ctx.drawText(textRenderer, checkMark, px - 1, py + 1, C_WHITE, false);

            Identifier ident = Identifier.tryParse(id);
            if (ident != null) {
                var item = net.minecraft.registry.Registries.ITEM.get(ident);
                if (item != null) ctx.drawItem(new ItemStack(item), px + 10, py);
            }

            String name = getLocalizedName(id);
            String nameColor = anyChecked ? "§f" : "§8";
            while (textRenderer.getWidth(name) > 90 && name.length() > 4) name = name.substring(0, name.length() - 1);
            ctx.drawText(textRenderer, nameColor + name, px + 28, py + 2, C_WHITE, false);

            // Кол-во: показываем "выбрано/всего" если не все выбраны
            String amtStr = (checkedAmt == totalAmt) ? String.valueOf(totalAmt) : checkedAmt + "/" + totalAmt;
            ctx.drawText(textRenderer, "§7" + amtStr, px + 122, py + 2, C_WHITE, false);

            ctx.drawText(textRenderer, "§e" + price, px + 145, py + 2, C_WHITE, false);

            String totalStr = (anyChecked ? "§a" : "§8") + total;
            ctx.drawText(textRenderer, totalStr, px + 198, py + 2, C_WHITE, false);

            ctx.fill(px, py + rowH - 1, px + pw - 2, py + rowH, C_LINE2);
            py += rowH;
        }

        ctx.fill(px, py + 2, px + pw - 2, py + 3, C_LINE);
        py += 6;
        ctx.drawText(textRenderer,
                ShopConfig.t("§eВыбрано: §a", "§eSelected: §a") + totalEarnings + ShopConfig.t(" монет", " coins"),
                px, py, C_GOLD, true);
    }

    // ── Нижняя строка статуса ────────────────────────────────────────────────
    private void renderSummaryBar(DrawContext ctx) {
        int px = rightPanelX();
        int sy = GT + GH - 72;
        ctx.fill(GL + 8, sy, px - 6, sy + 1, 0x44FFD700);
        sy += 3;
        String modeStr = ShopConfig.RECEIVE_PHYSICAL
                ? ShopConfig.t("§a→ физ. монеты", "§a→ physical coins")
                : ShopConfig.t("§b→ вирт. баланс", "§b→ virtual balance");
        ctx.drawText(textRenderer, ShopConfig.t("§7Получить: ", "§7Receive: ") + modeStr, GL + 10, sy, C_GRAY, false);
    }

    private void renderConfirmOverlay(DrawContext ctx) {
        ctx.fill(GL, GT, GL + GW, GT + GH - 14, 0xDD000020);

        int cx = GL + GW / 2, cy = GT + GH / 2 - 30;
        String q = ShopConfig.t("§eПодтвердить продажу?", "§eConfirm sale?");
        ctx.drawText(textRenderer, q, cx - textRenderer.getWidth(q.replaceAll("§.", "")) / 2, cy, C_GOLD, true);
        long cnt = invSlots.stream().filter(s -> s.itemId != null && s.checked).map(s -> s.itemId).distinct().count();
        ctx.drawText(textRenderer, "§f" + cnt + ShopConfig.t(" видов предметов", " item types"),
                cx - 40, cy + 14, C_WHITE, false);
        ctx.drawText(textRenderer, ShopConfig.t("§7Выручка: §a", "§7Earnings: §a") + totalEarnings + ShopConfig.t(" монет", " coins"),
                cx - 50, cy + 26, C_WHITE, false);

        int btnY = cy + 46;
        int yesX = cx - 82, noX = cx + 4;
        int btnW = 78, btnH = 18;

        ctx.fill(yesX, btnY, yesX + btnW, btnY + btnH, 0xFF226622);
        ctx.fill(noX,  btnY, noX  + btnW, btnY + btnH, 0xFF662222);
        ctx.fill(yesX, btnY, yesX + btnW, btnY + 1,    0xFF55FF55);
        ctx.fill(yesX, btnY + btnH - 1, yesX + btnW, btnY + btnH, 0xFF55FF55);
        ctx.fill(yesX, btnY, yesX + 1, btnY + btnH,    0xFF55FF55);
        ctx.fill(yesX + btnW - 1, btnY, yesX + btnW, btnY + btnH, 0xFF55FF55);
        ctx.fill(noX,  btnY, noX  + btnW, btnY + 1,    0xFFFF5555);
        ctx.fill(noX,  btnY + btnH - 1, noX  + btnW, btnY + btnH, 0xFFFF5555);
        ctx.fill(noX,  btnY, noX  + 1, btnY + btnH,    0xFFFF5555);
        ctx.fill(noX  + btnW - 1, btnY, noX  + btnW, btnY + btnH, 0xFFFF5555);
        String yesLbl = ShopConfig.t("§a✔ ДА, продаём!", "§a✔ YES, sell!");
        String noLbl  = ShopConfig.t("§c✕ Отмена", "§c✕ Cancel");
        ctx.drawText(textRenderer, yesLbl, yesX + (btnW - textRenderer.getWidth(yesLbl.replaceAll("§.", ""))) / 2, btnY + 5, C_WHITE, false);
        ctx.drawText(textRenderer, noLbl,  noX  + (btnW - textRenderer.getWidth(noLbl.replaceAll("§.", "")))  / 2, btnY + 5, C_WHITE, false);
    }

    // =========================================================================
    // INPUT
    // =========================================================================
    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        if (confirming) {
            int cx = GL + GW / 2, btnY = GT + GH / 2 - 30 + 46;
            int yesX = cx - 82, noX = cx + 4, btnW = 78, btnH = 18;
            if (mx >= yesX && mx < yesX + btnW && my >= btnY && my < btnY + btnH) {
                doSellSelected(); confirming = false; rebuildWidgets(); return true;
            }
            if (mx >= noX && mx < noX + btnW && my >= btnY && my < btnY + btnH) {
                confirming = false; rebuildWidgets(); return true;
            }
            return true;
        }

        var player = MinecraftClient.getInstance().player;
        if (player != null) {
            int invX = GL + 8, invY = GT + 38;
            int hotbarY = invY + INV_ROWS * (SLOT + GAP) + 14;
            int armorY  = hotbarY + SLOT + GAP + 12;

            List<InvSlot> main   = slotsOf(SlotSource.MAIN);
            List<InvSlot> hotbar = slotsOf(SlotSource.HOTBAR);
            List<InvSlot> armor  = slotsOf(SlotSource.ARMOR);
            List<InvSlot> offhand= slotsOf(SlotSource.OFFHAND);

            // Основной инвентарь
            for (int row = 0; row < INV_ROWS; row++) {
                for (int col = 0; col < INV_COLS; col++) {
                    int sx = invX + col * (SLOT + GAP), sy = invY + row * (SLOT + GAP);
                    if (mx >= sx && mx < sx + SLOT && my >= sy && my < sy + SLOT) {
                        toggleSlot(main.get(row * INV_COLS + col)); return true;
                    }
                }
            }
            // Хотбар
            for (int col = 0; col < INV_COLS; col++) {
                int sx = invX + col * (SLOT + GAP);
                if (mx >= sx && mx < sx + SLOT && my >= hotbarY && my < hotbarY + SLOT) {
                    toggleSlot(hotbar.get(col)); return true;
                }
            }
            // Броня
            for (int i = 0; i < 4; i++) {
                int sx = invX + i * (SLOT + GAP + 2);
                if (mx >= sx && mx < sx + SLOT && my >= armorY && my < armorY + SLOT) {
                    toggleSlot(armor.get(3 - i)); return true;
                }
            }
            // Левая рука
            int offX = invX + 4 * (SLOT + GAP + 2) + 8;
            if (mx >= offX && mx < offX + SLOT && my >= armorY && my < armorY + SLOT) {
                toggleSlot(offhand.get(0)); return true;
            }
        }

        // Клик по галочке в правой таблице — переключает ВСЕ слоты с этим itemId
        int px = rightPanelX(), py = GT + 41, rowH = 13;
        List<String> uniqueIds = new ArrayList<>();
        for (InvSlot s : invSlots) if (s.itemId != null && !uniqueIds.contains(s.itemId)) uniqueIds.add(s.itemId);

        for (int i = rightScroll; i < Math.min(uniqueIds.size(), rightScroll + RIGHT_VISIBLE); i++) {
            if (mx >= px - 2 && mx < px + 8 && my >= py && my < py + rowH) {
                String id = uniqueIds.get(i);
                // Если хоть один выбран — снимаем все; иначе выбираем все
                boolean anyChecked = invSlots.stream().anyMatch(s -> id.equals(s.itemId) && s.checked);
                for (InvSlot s : invSlots) if (id.equals(s.itemId)) s.checked = !anyChecked;
                recalcTotal(); rebuildWidgets(); return true;
            }
            py += rowH;
        }

        return super.mouseClicked(mx, my, btn);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hAmt, double vAmt) {
        int px = rightPanelX();
        if (mx >= px && mx < GL + GW) {
            long total = invSlots.stream().filter(s -> s.itemId != null).map(s -> s.itemId).distinct().count();
            rightScroll = (int) Math.max(0, Math.min(Math.max(0, total - RIGHT_VISIBLE),
                    rightScroll + (vAmt > 0 ? -1 : 1)));
            rebuildWidgets(); return true;
        }
        return super.mouseScrolled(mx, my, hAmt, vAmt);
    }

    private void toggleSlot(InvSlot slot) {
        if (slot.itemId == null) return;
        slot.checked = !slot.checked;
        recalcTotal();
        rebuildWidgets();
    }

    // =========================================================================
    // SELL LOGIC
    // =========================================================================
    private void doSellSelected() {
        // Группируем выбранные слоты по itemId
        Map<String, Integer> toSell = new LinkedHashMap<>();
        for (InvSlot s : invSlots) {
            if (s.itemId != null && s.checked) {
                toSell.merge(s.itemId, s.stack.getCount(), Integer::sum);
            }
        }

        long totalActual = 0L;
        int typesOk = 0;
        for (Map.Entry<String, Integer> e : toSell.entrySet()) {
            String id = e.getKey();
            ShopConfig.ItemKind kind = ShopConfig.getItemKind(id);
            net.minecraft.item.Item item = null;
            int inInv;
            if (kind == ShopConfig.ItemKind.NORMAL) {
                Identifier ident = Identifier.tryParse(id);
                if (ident == null) continue;
                item = net.minecraft.registry.Registries.ITEM.get(ident);
                if (item == null || item == net.minecraft.item.Items.AIR) continue;
                inInv = EconomyManager.countItem(item);
            } else {
                // Спец-предмет: зелье, книга, стрела — item=null, считаем по базовому типу
                inInv = EconomyManager.countItemByShopId(id);
            }
            int sellAmt = Math.min(e.getValue(), inInv);
            if (sellAmt <= 0) continue;
            // Хардкор: ограничиваем дневным лимитом
            long worldTime = net.minecraft.client.MinecraftClient.getInstance().world != null
                    ? net.minecraft.client.MinecraftClient.getInstance().world.getTimeOfDay() : 0L;
            sellAmt = ShopConfig.getHardcoreAllowedSellAmount(id, sellAmt, worldTime);
            if (sellAmt <= 0) continue;
            long earned = ShopConfig.getSellPrice(id) * sellAmt;
            boolean ok = ShopConfig.RECEIVE_PHYSICAL
                    ? EconomyManager.sellForPhysical(item, id, sellAmt, earned)
                    : EconomyManager.sellForVirtual(item, id, sellAmt, earned);
            if (ok) {
                ShopConfig.decrementTransaction(id);
                ShopConfig.recordSale(id, sellAmt);
                ShopConfig.recordDailySell(id, sellAmt);
                totalActual += earned;
                typesOk++;
            }
        }

        if (typesOk > 0) showMsg(ShopConfig.t("Продано! Выручка: ", "Sold! Earned: ") + totalActual + ShopConfig.t(" монет", " coins"), true);
        else showMsg(ShopConfig.t("Ничего не продано", "Nothing sold"), false);
        scanInventory();
        rebuildWidgets();
    }

    // =========================================================================
    // HELPERS
    // =========================================================================
    private String getLocalizedName(String itemId) {
        // Для спец-предметов (зелья, стрелы, книги) используем ShopConfig
        ShopConfig.ItemKind kind = ShopConfig.getItemKind(itemId);
        if (kind != ShopConfig.ItemKind.NORMAL) {
            String variant = ShopConfig.getItemVariant(itemId);
            return ShopConfig.getItemDisplayName(variant, kind);
        }
        Identifier ident = Identifier.tryParse(itemId);
        if (ident == null) return itemId;
        var item = net.minecraft.registry.Registries.ITEM.get(ident);
        if (item == null) return itemId;
        String loc = new ItemStack(item).getName().getString();
        if (loc.startsWith("item.") || loc.startsWith("block.")) {
            String[] parts = itemId.split(":");
            String raw = parts.length > 1 ? parts[1] : parts[0];
            StringBuilder sb = new StringBuilder();
            for (String w : raw.split("_")) {
                if (!w.isEmpty()) {
                    if (sb.length() > 0) sb.append(' ');
                    sb.append(Character.toUpperCase(w.charAt(0))).append(w.substring(1));
                }
            }
            return sb.toString();
        }
        return loc;
    }

    private void showMsg(String msg, boolean ok) {
        lastMsg = msg; msgOk = ok; msgUntil = System.currentTimeMillis() + 5000;
    }

    @Override public boolean shouldPause() { return false; }
}
