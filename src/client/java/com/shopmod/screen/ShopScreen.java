package com.shopmod.screen;

import com.shopmod.config.ShopConfig;
import com.shopmod.economy.EconomyManager;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.PotionContentsComponent;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.potion.Potion;
import net.minecraft.registry.Registries;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

import java.util.*;
import java.util.stream.Collectors;

@Environment(EnvType.CLIENT)
public class ShopScreen extends Screen {

    // ── Русский поиск ────────────────────────────────────────────────────────
    private static final Map<String, String> RU_SEARCH = new HashMap<>();
    static {
        RU_SEARCH.put("алмаз", "diamond"); RU_SEARCH.put("алмазный", "diamond"); RU_SEARCH.put("алмазная", "diamond"); RU_SEARCH.put("алмазное", "diamond");
        RU_SEARCH.put("железо", "iron"); RU_SEARCH.put("железный", "iron"); RU_SEARCH.put("железная", "iron");
        RU_SEARCH.put("золото", "gold"); RU_SEARCH.put("золотой", "gold"); RU_SEARCH.put("золотая", "gold"); RU_SEARCH.put("золотое", "gold");
        RU_SEARCH.put("изумруд", "emerald"); RU_SEARCH.put("незерит", "netherite"); RU_SEARCH.put("незеритовый", "netherite"); RU_SEARCH.put("незеритовая", "netherite");
        RU_SEARCH.put("уголь", "coal"); RU_SEARCH.put("редстоун", "redstone"); RU_SEARCH.put("лазурит", "lapis");
        RU_SEARCH.put("меч", "sword"); RU_SEARCH.put("кирка", "pickaxe"); RU_SEARCH.put("топор", "axe"); RU_SEARCH.put("лопата", "shovel"); RU_SEARCH.put("мотыга", "hoe");
        RU_SEARCH.put("шлем", "helmet"); RU_SEARCH.put("нагрудник", "chestplate"); RU_SEARCH.put("поножи", "leggings"); RU_SEARCH.put("ботинки", "boots");
        RU_SEARCH.put("лук", "bow"); RU_SEARCH.put("арбалет", "crossbow"); RU_SEARCH.put("щит", "shield"); RU_SEARCH.put("стрела", "arrow"); RU_SEARCH.put("трезубец", "trident");
        RU_SEARCH.put("древесина", "log"); RU_SEARCH.put("доска", "planks"); RU_SEARCH.put("дерево", "oak"); RU_SEARCH.put("берёза", "birch"); RU_SEARCH.put("берёзовый", "birch");
        RU_SEARCH.put("ель", "spruce"); RU_SEARCH.put("еловый", "spruce"); RU_SEARCH.put("джунгли", "jungle"); RU_SEARCH.put("акация", "acacia");
        RU_SEARCH.put("тёмный дуб", "dark_oak"); RU_SEARCH.put("вишня", "cherry"); RU_SEARCH.put("мангровое", "mangrove"); RU_SEARCH.put("бамбук", "bamboo");
        RU_SEARCH.put("пурпур", "crimson"); RU_SEARCH.put("искажённый", "warped");
        RU_SEARCH.put("камень", "stone"); RU_SEARCH.put("булыжник", "cobblestone"); RU_SEARCH.put("гранит", "granite"); RU_SEARCH.put("диорит", "diorite");
        RU_SEARCH.put("андезит", "andesite"); RU_SEARCH.put("кварц", "quartz"); RU_SEARCH.put("обсидиан", "obsidian");
        RU_SEARCH.put("глубинный сланец", "deepslate"); RU_SEARCH.put("туф", "tuff"); RU_SEARCH.put("базальт", "basalt"); RU_SEARCH.put("чёрный камень", "blackstone");
        RU_SEARCH.put("кирпич", "brick"); RU_SEARCH.put("стекло", "glass"); RU_SEARCH.put("песок", "sand"); RU_SEARCH.put("гравий", "gravel");
        RU_SEARCH.put("земля", "dirt"); RU_SEARCH.put("глина", "clay"); RU_SEARCH.put("снег", "snow"); RU_SEARCH.put("лёд", "ice");
        RU_SEARCH.put("хлеб", "bread"); RU_SEARCH.put("яблоко", "apple"); RU_SEARCH.put("морковь", "carrot"); RU_SEARCH.put("картофель", "potato");
        RU_SEARCH.put("пшеница", "wheat"); RU_SEARCH.put("говядина", "beef"); RU_SEARCH.put("свинина", "porkchop"); RU_SEARCH.put("курица", "chicken");
        RU_SEARCH.put("баранина", "mutton"); RU_SEARCH.put("кролик", "rabbit"); RU_SEARCH.put("треска", "cod"); RU_SEARCH.put("лосось", "salmon");
        RU_SEARCH.put("печёный", "cooked"); RU_SEARCH.put("суп", "soup"); RU_SEARCH.put("рагу", "stew");
        RU_SEARCH.put("факел", "torch"); RU_SEARCH.put("сундук", "chest"); RU_SEARCH.put("верстак", "crafting_table"); RU_SEARCH.put("печь", "furnace");
        RU_SEARCH.put("наковальня", "anvil"); RU_SEARCH.put("эндер", "ender"); RU_SEARCH.put("жемчуг эндера", "ender_pearl");
        RU_SEARCH.put("порох", "gunpowder"); RU_SEARCH.put("кость", "bone"); RU_SEARCH.put("перо", "feather"); RU_SEARCH.put("нить", "string");
        RU_SEARCH.put("кожа", "leather"); RU_SEARCH.put("слизь", "slime"); RU_SEARCH.put("глаз", "eye"); RU_SEARCH.put("звезда", "star");
        RU_SEARCH.put("яйцо спавна", "spawn_egg"); RU_SEARCH.put("волк", "wolf"); RU_SEARCH.put("кошка", "cat"); RU_SEARCH.put("лиса", "fox");
        RU_SEARCH.put("аксолотль", "axolotl"); RU_SEARCH.put("крипер", "creeper"); RU_SEARCH.put("зомби", "zombie"); RU_SEARCH.put("скелет", "skeleton");
        RU_SEARCH.put("паук", "spider"); RU_SEARCH.put("эндермен", "enderman"); RU_SEARCH.put("ведьма", "witch"); RU_SEARCH.put("страж", "guardian");
        RU_SEARCH.put("призрак", "phantom"); RU_SEARCH.put("дракон", "dragon"); RU_SEARCH.put("иссушитель", "wither");
        RU_SEARCH.put("коралл", "coral"); RU_SEARCH.put("призма", "prismarine"); RU_SEARCH.put("море", "sea"); RU_SEARCH.put("морской фонарь", "sea_lantern");
        RU_SEARCH.put("душа", "soul"); RU_SEARCH.put("ад", "nether"); RU_SEARCH.put("незерак", "netherrack");
        RU_SEARCH.put("конец", "end"); RU_SEARCH.put("финальный камень", "end_stone"); RU_SEARCH.put("хорус", "chorus");
        RU_SEARCH.put("аметист", "amethyst"); RU_SEARCH.put("чернила", "ink"); RU_SEARCH.put("светящийся", "glow");
        RU_SEARCH.put("пчела", "bee"); RU_SEARCH.put("мёд", "honey"); RU_SEARCH.put("восковой", "honeycomb");
        RU_SEARCH.put("пластинка", "music_disc"); RU_SEARCH.put("диск", "music_disc"); RU_SEARCH.put("осколок пластинки", "disc_fragment");
        RU_SEARCH.put("осколок эха", "echo_shard"); RU_SEARCH.put("черепок", "clay_ball"); RU_SEARCH.put("скульк", "sculk");
        RU_SEARCH.put("элитра", "elytra"); RU_SEARCH.put("тотем", "totem"); RU_SEARCH.put("сердце моря", "heart_of_the_sea");
        RU_SEARCH.put("маяк", "beacon"); RU_SEARCH.put("рамка портала", "end_portal_frame");
        RU_SEARCH.put("булава", "mace"); RU_SEARCH.put("навершие", "heavy_core"); RU_SEARCH.put("стержень бриза", "breeze_rod");
    }

    private static String translateQuery(String query) {
        if (query == null || query.isEmpty()) return query;
        String lower = query.toLowerCase().trim();
        if (RU_SEARCH.containsKey(lower)) return RU_SEARCH.get(lower);
        String best = null; int bestLen = 0;
        for (Map.Entry<String, String> e : RU_SEARCH.entrySet()) {
            if (lower.contains(e.getKey()) && e.getKey().length() > bestLen) {
                best = e.getValue(); bestLen = e.getKey().length();
            }
        }
        return best != null ? best : lower;
    }

    // ── State ────────────────────────────────────────────────────────────────
    private List<ShopEntry> allEntries      = new ArrayList<>();
    private List<ShopEntry> filteredEntries = new ArrayList<>();
    private int scrollOffset  = 0;
    private int selectedIndex = -1;
    private String selectedItemId = null;
    private int buyAmount    = 1;
    private String currentTab = "ALL";

    private TextFieldWidget searchField;
    private String lastMessage        = "";
    private long   messageShowUntilMs = 0;
    private boolean messageSuccess    = true;

    // Подтверждение MAX операций (первый клик — запрос, второй — выполнение)
    private boolean pendingMaxBuy  = false;
    private boolean pendingMaxSell = false;

    // ── Сортировка ────────────────────────────────────────────────────────────
    // sortMode: 0=по алфавиту, 1=по популярности, 2=по цене↑, 3=по цене↓
    private int  sortMode   = 0;
    private boolean sortByBuyPrice = true; // true=по цене покупки, false=по цене продажи

    // ── First-run wizard step (0=lang, 1=features, 2=done) ──────────────────
    private int wizardStep = 0;

    // ── Layout ───────────────────────────────────────────────────────────────
    private static final int GW = 400, GH = 450;
    private static final int LIST_X = 8, LIST_Y = 86;  // 86 = 30 (tabs row1) + 14 (tabs row2) + 14 (searchbar) + 28
    private static final int LIST_W = 220, ROW_H = 22;
    private static final int VISIBLE = 8;
    private static final int DETAIL_X_OFF = LIST_X + LIST_W + 16;
    private static final int DETAIL_W = GW - DETAIL_X_OFF - 6;
    private int GL, GT;

    // ── Colors ───────────────────────────────────────────────────────────────
    private static final int C_BG       = 0xEF0d0d1a;
    private static final int C_HEADER   = 0xFF1a1a3a;
    private static final int C_ROW_EVEN = 0x22FFFFFF;
    private static final int C_ROW_ODD  = 0x11FFFFFF;
    private static final int C_SELECTED = 0x66FFD700;
    private static final int C_HOVERED  = 0x44AACCFF;
    private static final int C_PANEL    = 0x88000020;
    private static final int C_GOLD     = 0xFFFFD700;
    private static final int C_WHITE    = 0xFFFFFFFF;
    private static final int C_GRAY     = 0xFFAAAAAA;
    private static final int C_GREEN    = 0xFF55FF55;
    private static final int C_RED      = 0xFFFF5555;
    private static final int C_AQUA     = 0xFF55FFFF;

    public ShopScreen() { super(Text.literal("Shop")); }

    // ── Init ─────────────────────────────────────────────────────────────────
    @Override
    protected void init() {
        GL = (width - GW) / 2;
        GT = (height - GH) / 2;

        if (ShopConfig.FIRST_RUN) { initWizard(); return; }

        allEntries.clear();
        for (String id : ShopConfig.getAllItems()) {
            ShopConfig.ItemKind kind = ShopConfig.getItemKind(id);
            if (kind == ShopConfig.ItemKind.NORMAL) {
                Identifier ident = Identifier.tryParse(id);
                if (ident == null) continue;
                Item item = Registries.ITEM.get(ident);
                if (item == null || item == Items.AIR) continue;
                if (EconomyManager.isCoin(item)) continue;
                allEntries.add(new ShopEntry(id, item));
            } else {
                // Спец-предмет: определяем базовый Item для иконки
                String baseId = ShopConfig.getBaseItemId(id);
                Identifier ident = Identifier.tryParse(baseId);
                if (ident == null) continue;
                Item item = Registries.ITEM.get(ident);
                if (item == null || item == Items.AIR) continue;
                allEntries.add(new ShopEntry(id, item));
            }
        }
        // Первичная сортировка по умолчанию (алфавит)
        allEntries.sort(Comparator.comparing(ShopEntry::displayName));
        applyFilter();
        restoreSelectedIndex();

        // Search bar
        searchField = new TextFieldWidget(textRenderer,
                GL + LIST_X, GT + 50, LIST_W, 16, Text.literal("Search"));
        searchField.setMaxLength(64);
        searchField.setPlaceholder(Text.literal(ShopConfig.t("§7Поиск...","§7Search...")));
        searchField.setChangedListener(s -> { applyFilter(); scrollOffset = 0; restoreSelectedIndex(); });
        addDrawableChild(searchField);

        // Tabs — новые вкладки (2 строки по 5)
        String[] tabKeys   = {"ALL","БРОНЯ","ОРУ","ЕДА","БЛОКИ","КНИГИ","ЗЕЛЬЯ","СТРЕЛЫ","ЦЕННЫЕ","ЯЙЦА"};
        String[] tabLabels = {
                ShopConfig.t("ВСЁ","ALL"),
                ShopConfig.t("БRON","ARMR"),
                ShopConfig.t("ОРУ","WPN"),
                ShopConfig.t("ЕДА","FOOD"),
                ShopConfig.t("БЛК","BLK"),
                ShopConfig.t("КНГ","ENC"),
                ShopConfig.t("ЗЛЬ","POT"),
                ShopConfig.t("СТР","ARW"),
                ShopConfig.t("ЦНН","VAL"),
                ShopConfig.t("ЯЙЦ","EGG")
        };
        int tw = 44; // 5 вкладок × 44 = 220 = LIST_W
        for (int i = 0; i < tabKeys.length; i++) {
            final String tabKey = tabKeys[i];
            int row = i / 5;
            int col = i % 5;
            boolean active = currentTab.equals(tabKey);
            String lbl = (active ? "§e" : "§7") + tabLabels[i];
            addDrawableChild(ButtonWidget.builder(Text.literal(lbl), b -> {
                currentTab = tabKey; applyFilter(); scrollOffset = 0;
                selectedIndex = -1; selectedItemId = null;
                restoreSelectedIndex();
                rebuildWidgets();
            }).dimensions(GL + LIST_X + col * tw, GT + 30 + row * 14, tw - 1, 13).build());
        }

        // Scroll
        addDrawableChild(ButtonWidget.builder(Text.literal("▲"), b -> scroll(-1))
                .dimensions(GL + LIST_X + LIST_W + 2, GT + LIST_Y, 12, 12).build());
        addDrawableChild(ButtonWidget.builder(Text.literal("▼"), b -> scroll(1))
                .dimensions(GL + LIST_X + LIST_W + 2, GT + LIST_Y + VISIBLE * ROW_H, 12, 12).build());

        // Amount controls — only when an item is selected
        if (selectedIndex >= 0 && selectedIndex < filteredEntries.size()) {
            int rx = GL + DETAIL_X_OFF;
            // BUG-15 fix: gap between -/+ (h=14, ends GT+178) and x4/x16 increased to 6px → GT+184
            int maxStack = filteredEntries.get(selectedIndex).item().getMaxCount();
            addDrawableChild(ButtonWidget.builder(Text.literal("-"), b -> { buyAmount = Math.max(1, buyAmount-1); }).dimensions(rx, GT+164, 18, 14).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("+"), b -> { buyAmount = Math.min(maxStack, buyAmount+1); }).dimensions(rx+40, GT+164, 18, 14).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("x4"),  b -> buyAmount = Math.min(maxStack, 4)).dimensions(rx,    GT+184, 28, 12).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("x16"), b -> buyAmount = Math.min(maxStack, 16)).dimensions(rx+30, GT+184, 28, 12).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("x64"), b -> buyAmount = Math.min(maxStack, 64)).dimensions(rx,    GT+198, 28, 12).build());
            addDrawableChild(ButtonWidget.builder(Text.literal("x1"),  b -> buyAmount = 1).dimensions(rx+30,  GT+198, 28, 12).build());

            // Buy/Sell — основные кнопки
            addDrawableChild(ButtonWidget.builder(Text.literal("§a▶ " + ShopConfig.t("КУПИТЬ","BUY")), b -> doBuy())
                    .dimensions(rx, GT+334, DETAIL_W - 62, 18).build());
            addDrawableChild(ButtonWidget.builder(
                    Text.literal("§2▶▶ " + ShopConfig.t("MAX KУП","MAX BUY")),
                    b -> { if (pendingMaxBuy) { doMaxBuy(); pendingMaxBuy = false; rebuildWidgets(); }
                           else { pendingMaxBuy = true; pendingMaxSell = false; rebuildWidgets(); } })
                    .dimensions(rx + DETAIL_W - 60, GT+334, 60, 18).build());

            addDrawableChild(ButtonWidget.builder(Text.literal("§c◀ " + ShopConfig.t("ПРОДАТЬ","SELL")), b -> doSell())
                    .dimensions(rx, GT+354, DETAIL_W - 62, 18).build());
            addDrawableChild(ButtonWidget.builder(
                    Text.literal("§4◀◀ " + ShopConfig.t("MAX ПРД","MAX SEL")),
                    b -> { if (pendingMaxSell) { doMaxSell(); pendingMaxSell = false; rebuildWidgets(); }
                           else { pendingMaxSell = true; pendingMaxBuy = false; rebuildWidgets(); } })
                    .dimensions(rx + DETAIL_W - 60, GT+354, 60, 18).build());

            // ── Переключатели баланса ────────────────────────────────────────────
            // BUG-36 fix: кнопки занимают rx+0..rx+(DETAIL_W/2-2) и rx+(DETAIL_W/2+2)..rx+DETAIL_W-4
            // Гарантируем 4px зазор от правого края панели (rx+DETAIL_W+2)
            int halfW = DETAIL_W / 2 - 3;
            addDrawableChild(ButtonWidget.builder(
                    Text.literal(ShopConfig.SPEND_PHYSICAL ? ShopConfig.t("§eТратить: §aФИЗ","§eSpend: §aPHYS") : ShopConfig.t("§eТратить: §bВИРТ","§eSpend: §bVIRT")),
                    b -> {
                        ShopConfig.SPEND_PHYSICAL = !ShopConfig.SPEND_PHYSICAL;
                        ShopConfig.save();
                        rebuildWidgets();
                    }).dimensions(rx, GT+376, halfW, 14).build());

            addDrawableChild(ButtonWidget.builder(
                    Text.literal(ShopConfig.RECEIVE_PHYSICAL ? ShopConfig.t("§eПолучать: §aФИЗ","§eRecv: §aPHYS") : ShopConfig.t("§eПолучать: §bВИРТ","§eRecv: §bVIRT")),
                    b -> {
                        ShopConfig.RECEIVE_PHYSICAL = !ShopConfig.RECEIVE_PHYSICAL;
                        ShopConfig.save();
                        rebuildWidgets();
                    }).dimensions(rx + halfW + 2, GT+376, DETAIL_W - halfW - 6, 14).build());
        }

        // ── Навигация: Оценить | Опт.скупка | Банкомат
        addDrawableChild(ButtonWidget.builder(
                Text.literal("§b🔍 " + ShopConfig.t("Оценить","Appraise")), b -> {
                    MinecraftClient.getInstance().setScreen(new AppraiseScreen());
                }).dimensions(GL + LIST_X, GT + GH - 20, 74, 14).build());

        addDrawableChild(ButtonWidget.builder(
                Text.literal("§6📦 " + ShopConfig.t("Опт.скупка","Bulk Sell")), b -> {
                    MinecraftClient.getInstance().setScreen(new BulkSellScreen());
                }).dimensions(GL + LIST_X + 76, GT + GH - 20, 76, 14).build());

        addDrawableChild(ButtonWidget.builder(
                Text.literal("§e🏧 " + ShopConfig.t("Банкомат","ATM")), b -> {
                    MinecraftClient.getInstance().setScreen(new ATMScreen());
                }).dimensions(GL + LIST_X + 154, GT + GH - 20, 66, 14).build());

        // ── Сортировка — 4 кнопки умещаются в ширину списка (4×55=220 = LIST_W)
        String[] sortLabels = {
                ShopConfig.t("A-Я","A-Z"),
                ShopConfig.t("★Топ","★Pop"),
                ShopConfig.t("↑Цена","↑Price"),
                ShopConfig.t("↓Цена","↓Price")
        };
        int sortX = GL + LIST_X;
        for (int i = 0; i < 4; i++) {
            final int idx = i;
            boolean active = sortMode == i;
            addDrawableChild(ButtonWidget.builder(
                            Text.literal(active ? "§e[" + sortLabels[i] + "]" : "§7" + sortLabels[i]),
                            b -> { sortMode = idx; applyFilter(); rebuildWidgets(); })
                    .dimensions(sortX + i * 55, GT + GH - 36, 53, 12).build());
        }
        // Переключатель «по цене покупки / продажи» — отдельная строка выше кнопок сортировки
        if (sortMode == 2 || sortMode == 3) {
            addDrawableChild(ButtonWidget.builder(
                            Text.literal(sortByBuyPrice
                                    ? ShopConfig.t("§7[по покупке]","§7[by buy]")
                                    : ShopConfig.t("§7[по продаже]","§7[by sell]")),
                            b -> { sortByBuyPrice = !sortByBuyPrice; applyFilter(); rebuildWidgets(); })
                    .dimensions(GL + LIST_X, GT + GH - 54, LIST_W, 12).build());
        }

        // ── Закрыть
        addDrawableChild(ButtonWidget.builder(Text.literal("✕"), b -> close())
                .dimensions(GL+GW-14, GT+2, 12, 12).build());

        // ── Динамические цены — только в HARDCORE, в остальных режимах кнопка скрыта
        if (ShopConfig.SHOP_MODE == ShopConfig.ShopMode.HARDCORE) {
            String dynLabel = ShopConfig.DYNAMIC_PRICES
                    ? ShopConfig.t("§aДин:ВКЛ","§aDyn:ON")
                    : ShopConfig.t("§7Дин:ВЫКЛ","§7Dyn:OFF");
            addDrawableChild(ButtonWidget.builder(Text.literal(dynLabel), b -> {
                ShopConfig.DYNAMIC_PRICES = !ShopConfig.DYNAMIC_PRICES; ShopConfig.save(); rebuildWidgets();
            }).dimensions(GL+GW-68, GT+2, 50, 12).build());
        }

        // ── Язык (RU/EN)
        String langLbl = "§a" + ShopConfig.LANGUAGE.toUpperCase();
        addDrawableChild(ButtonWidget.builder(Text.literal(langLbl), b -> {
            ShopConfig.LANGUAGE = "ru".equals(ShopConfig.LANGUAGE) ? "en" : "ru";
            ShopConfig.save(); rebuildWidgets();
        }).dimensions(GL+GW-220, GT+2, 20, 12).build());

        // ── Комиссия — скрыта, режим магазина фиксирует её при applyShopMode()
        // Показываем только как индикатор (без кнопки), чтобы игрок видел текущее состояние

        // ── Лог в чат
        String logLabel = ShopConfig.CHAT_SPAM_ENABLED
                ? ShopConfig.t("§7Лог:§aВКЛ","§7Log:§aON")
                : ShopConfig.t("§7Лог:§cВЫКЛ","§7Log:§cOFF");
        addDrawableChild(ButtonWidget.builder(Text.literal(logLabel), b -> {
            ShopConfig.CHAT_SPAM_ENABLED = !ShopConfig.CHAT_SPAM_ENABLED; ShopConfig.save(); rebuildWidgets();
        }).dimensions(GL+GW-132, GT+2, 60, 12).build());
    }

    // =========================================================================
    // FIRST-RUN WIZARD
    // =========================================================================
    private void initWizard() {
        int cx = GL + GW / 2;

        if (wizardStep == 0) {
            // Шаг 0 — выбор языка
            addDrawableChild(ButtonWidget.builder(Text.literal(
                    "ru".equals(ShopConfig.LANGUAGE) ? "§a● §fРусский" : "§7○ §fРусский"), b -> {
                ShopConfig.LANGUAGE = "ru"; ShopConfig.save(); rebuildWidgets();
            }).dimensions(cx - 105, GT + 100, 100, 22).build());

            addDrawableChild(ButtonWidget.builder(Text.literal(
                    "en".equals(ShopConfig.LANGUAGE) ? "§a● §fEnglish" : "§7○ §fEnglish"), b -> {
                ShopConfig.LANGUAGE = "en"; ShopConfig.save(); rebuildWidgets();
            }).dimensions(cx + 5, GT + 100, 100, 22).build());

            addDrawableChild(ButtonWidget.builder(Text.literal(
                    "§a➤ " + ShopConfig.t("Далее →","Next →")), b -> {
                wizardStep = 1; rebuildWidgets();
            }).dimensions(cx - 50, GT + 145, 100, 20).build());

        } else if (wizardStep == 1) {
            // Шаг 1 — выбор режима магазина (НЕОБРАТИМО)
            boolean easySelected     = ShopConfig.ShopMode.EASY     == ShopConfig.SHOP_MODE;
            boolean normalSelected   = ShopConfig.ShopMode.NORMAL   == ShopConfig.SHOP_MODE;
            boolean hardcoreSelected = ShopConfig.ShopMode.HARDCORE == ShopConfig.SHOP_MODE;

            // ЛЁГКИЙ
            String easyLbl = (easySelected ? "§a● §f" : "§7○ §7")
                    + ShopConfig.t("ЛЁГКИЙ", "EASY");
            addDrawableChild(ButtonWidget.builder(Text.literal(easyLbl), b -> {
                ShopConfig.SHOP_MODE = ShopConfig.ShopMode.EASY; rebuildWidgets();
            }).dimensions(cx - 110, GT + 80, 220, 20).build());

            // НОРМАЛЬНЫЙ
            String normalLbl = (normalSelected ? "§e● §f" : "§7○ §7")
                    + ShopConfig.t("НОРМАЛЬНЫЙ", "NORMAL");
            addDrawableChild(ButtonWidget.builder(Text.literal(normalLbl), b -> {
                ShopConfig.SHOP_MODE = ShopConfig.ShopMode.NORMAL; rebuildWidgets();
            }).dimensions(cx - 110, GT + 108, 220, 20).build());

            // ХАРДКОР
            String hardcoreLbl = (hardcoreSelected ? "§c● §f" : "§7○ §7")
                    + ShopConfig.t("ХАРДКОР", "HARDCORE");
            addDrawableChild(ButtonWidget.builder(Text.literal(hardcoreLbl), b -> {
                ShopConfig.SHOP_MODE = ShopConfig.ShopMode.HARDCORE; rebuildWidgets();
            }).dimensions(cx - 110, GT + 136, 220, 20).build());

            // Лог в чат (независим от режима)
            String logLbl = ShopConfig.CHAT_SPAM_ENABLED
                    ? ShopConfig.t("§a✔ Лог в чат: ВКЛ", "§a✔ Chat log: ON")
                    : ShopConfig.t("§7✘ Лог в чат: ВЫКЛ", "§7✘ Chat log: OFF");
            addDrawableChild(ButtonWidget.builder(Text.literal(logLbl), b -> {
                ShopConfig.CHAT_SPAM_ENABLED = !ShopConfig.CHAT_SPAM_ENABLED;
                ShopConfig.save(); rebuildWidgets();
            }).dimensions(cx - 110, GT + 168, 220, 20).build());

            // Кнопка назад
            addDrawableChild(ButtonWidget.builder(Text.literal(
                    "§7← " + ShopConfig.t("Назад","Back")), b -> {
                wizardStep = 0; rebuildWidgets();
            }).dimensions(cx - 110, GT + 210, 70, 20).build());

            // Кнопка "Начать!" — только если режим выбран
            if (ShopConfig.SHOP_MODE != null) {
                addDrawableChild(ButtonWidget.builder(Text.literal(
                        "§a✔ " + ShopConfig.t("Начать!","Start!")), b -> {
                    ShopConfig.applyShopMode(ShopConfig.SHOP_MODE);
                    ShopConfig.FIRST_RUN = false;
                    ShopConfig.save();
                    rebuildWidgets();
                }).dimensions(cx + 10, GT + 210, 100, 20).build());
            }
        }
    }

    private void renderWizard(DrawContext ctx) {
        int cx = GL + GW / 2;

        if (wizardStep == 0) {
            String title = "§6✦ ShopMod ✦";
            ctx.drawText(textRenderer, title, cx - textRenderer.getWidth(title)/2, GT + 20, 0xFFFFD700, true);

            String sub = ShopConfig.t("Добро пожаловать!", "Welcome!");
            ctx.drawText(textRenderer, "§f" + sub, cx - textRenderer.getWidth("§f"+sub)/2, GT + 36, 0xFFFFFFFF, false);

            String q = ShopConfig.t("Выберите язык / Choose language:", "Choose language:");
            ctx.drawText(textRenderer, "§7" + q, cx - textRenderer.getWidth("§7"+q)/2, GT + 76, 0xFFAAAAAA, false);

            String note = ShopConfig.t("§8(Можно изменить позже в магазине)", "§8(Can be changed later in settings)");
            ctx.drawText(textRenderer, note, cx - textRenderer.getWidth(note)/2, GT + 130, 0xFFAAAAAA, false);

        } else if (wizardStep == 1) {
            String title = ShopConfig.t("§6Выбор режима магазина", "§6Choose Shop Mode");
            ctx.drawText(textRenderer, title, cx - textRenderer.getWidth(title)/2, GT + 20, 0xFFFFD700, true);

            String warn = ShopConfig.t("§c⚠ Выбор необратим — изменить потом нельзя!", "§c⚠ Irreversible — cannot be changed later!");
            ctx.drawText(textRenderer, warn, cx - textRenderer.getWidth(warn)/2, GT + 36, 0xFFFF5555, false);

            // Описания режимов
            ctx.drawText(textRenderer,
                    ShopConfig.t("§8  Разница купля/продажа фиксированные 20%", "§8  Fixed 20% buy/sell spread"),
                    GL + 20, GT + 103, 0xFFAAAAAA, false);

            ctx.drawText(textRenderer,
                    ShopConfig.t("§8  Разница купля/продажа ~50% (стандарт)", "§8  ~50% buy/sell spread (standard)"),
                    GL + 20, GT + 131, 0xFFAAAAAA, false);

            ctx.drawText(textRenderer,
                    ShopConfig.t("§8  ~50% + динамические цены + лимит 64/день", "§8  ~50% + dynamic prices + 64/day sell limit"),
                    GL + 20, GT + 159, 0xFFAAAAAA, false);

            if (ShopConfig.SHOP_MODE == null) {
                String hint = ShopConfig.t("§7Выберите режим чтобы продолжить", "§7Select a mode to continue");
                ctx.drawText(textRenderer, hint, cx - textRenderer.getWidth(hint)/2, GT + 200, 0xFF777777, false);
            }
        }
    }

    private void rebuildWidgets() {
        String savedSearch = searchField != null ? searchField.getText() : "";
        clearChildren();
        init();
        if (!savedSearch.isEmpty() && searchField != null) {
            searchField.setText(savedSearch);
        }
    }

    // ── Render ────────────────────────────────────────────────────────────────
    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        renderBackground(ctx, mx, my, delta);

        ctx.fill(GL+3, GT+3, GL+GW+3, GT+GH+19, 0x55000000);
        ctx.fill(GL, GT, GL+GW, GT+GH+16, C_BG);
        ctx.fill(GL, GT, GL+GW, GT+26, C_HEADER);
        ctx.fill(GL, GT+25, GL+GW, GT+26, C_GOLD);

        // ── Wizard mode ──────────────────────────────────────────────────────
        if (ShopConfig.FIRST_RUN) {
            // Прогресс-бар шагов
            String stepStr = wizardStep == 0
                    ? ShopConfig.t("§7[§a1§7/2] Язык","§7[§a1§7/2] Language")
                    : ShopConfig.t("§7[§a2§7/2] Настройки","§7[§a2§7/2] Settings");
            ctx.drawText(textRenderer, stepStr, GL+8, GT+8, 0xFFFFFFFF, false);
            super.render(ctx, mx, my, delta);
            renderWizard(ctx);
            return;
        }

        long phys = EconomyManager.getPhysicalBalance();
        long virt = ShopConfig.VIRTUAL_BALANCE;
        ctx.drawText(textRenderer, ShopConfig.t("§fКошелёк: ","§fWallet: ") + formatBalance(phys+virt), GL+8, GT+8, C_WHITE, false);
        ctx.drawText(textRenderer, ShopConfig.t("§8физ: §e","§8phys: §e") + phys + ShopConfig.t(" §8| вирт: §b"," §8| virt: §b") + virt, GL+8, GT+17, C_GRAY, false);

        drawCoinBreakdown(ctx, GL+GW-180, GT+6);

        ctx.fill(GL+LIST_X-1, GT+LIST_Y-1, GL+LIST_X+LIST_W+1, GT+LIST_Y+VISIBLE*ROW_H+1, 0x66000000);

        int hoveredIdx = -1;
        for (int i = 0; i < VISIBLE && i+scrollOffset < filteredEntries.size(); i++) {
            int ry = GT+LIST_Y+i*ROW_H;
            if (mx>=GL+LIST_X && mx<GL+LIST_X+LIST_W && my>=ry && my<ry+ROW_H)
                hoveredIdx = i+scrollOffset;
        }

        for (int i = 0; i < VISIBLE && i+scrollOffset < filteredEntries.size(); i++) {
            int idx = i+scrollOffset;
            ShopEntry e = filteredEntries.get(idx);
            int ry = GT+LIST_Y+i*ROW_H;
            int bg = idx==selectedIndex ? C_SELECTED : idx==hoveredIdx ? C_HOVERED : (i%2==0) ? C_ROW_EVEN : C_ROW_ODD;
            ctx.fill(GL+LIST_X, ry, GL+LIST_X+LIST_W, ry+ROW_H-1, bg);
            // Цветная полоска для зелий и стрел
            ShopConfig.ItemKind kind = ShopConfig.getItemKind(e.itemId);
            if (kind == ShopConfig.ItemKind.POTION || kind == ShopConfig.ItemKind.SPLASH_POTION
                    || kind == ShopConfig.ItemKind.LINGERING_POTION || kind == ShopConfig.ItemKind.TIPPED_ARROW) {
                String variant = ShopConfig.getItemVariant(e.itemId);
                int potColor = ShopConfig.getPotionColor(variant);
                // Полоска 3px слева, полупрозрачная
                ctx.fill(GL+LIST_X, ry, GL+LIST_X+3, ry+ROW_H-1, (potColor & 0x00FFFFFF) | 0xCC000000);
            }
            ctx.drawItem(makeStack(e, 1), GL+LIST_X+2, ry+3);
            // BUG-27 fix: обрезаем название, чтобы не заходило на цену
            long buy = ShopConfig.getBuyPrice(e.itemId), sell = ShopConfig.getSellPrice(e.itemId);
            String prStr = "§e"+buy+" §8/ §a"+sell;
            int pw = textRenderer.getWidth(prStr.replaceAll("§.",""));
            int nameMaxW = LIST_W - 22 - pw - 8; // 22=иконка, 8=зазор между названием и ценой
            String dName = "§f"+e.displayName();
            while (textRenderer.getWidth(dName.replaceAll("§.","")) > nameMaxW && dName.length() > 3)
                dName = dName.substring(0, dName.length() - 1);
            if (dName.length() < ("§f"+e.displayName()).length()) dName += "…";
            ctx.drawText(textRenderer, dName, GL+LIST_X+22, ry+7, C_WHITE, false);
            ctx.drawText(textRenderer, prStr, GL+LIST_X+LIST_W-pw-4, ry+7, C_WHITE, false);
        }

        // BUG-21 fix: сначала рисуем панель деталей (заливает фон), затем scrollbar поверх неё
        drawDetailPanel(ctx);

        // Scrollbar — рисуется ПОСЛЕ drawDetailPanel, чтобы не быть перекрытым её фоном
        if (filteredEntries.size() > VISIBLE) {
            int trackH = VISIBLE*ROW_H-4;
            int barH = Math.max(8, trackH*VISIBLE/filteredEntries.size());
            int maxOff = filteredEntries.size()-VISIBLE;
            int barY = GT+LIST_Y+2+(maxOff>0 ? scrollOffset*(trackH-barH)/maxOff : 0);
            ctx.fill(GL+LIST_X+LIST_W+16, GT+LIST_Y+2, GL+LIST_X+LIST_W+18, GT+LIST_Y+2+trackH, 0x44FFFFFF);
            ctx.fill(GL+LIST_X+LIST_W+16, barY, GL+LIST_X+LIST_W+18, barY+barH, 0xCCFFD700);
        }
        super.render(ctx, mx, my, delta);

        long now = System.currentTimeMillis();
        if (now < messageShowUntilMs) {
            long rem = messageShowUntilMs - now;
            int alpha = (int) Math.min(255, rem / 4);
            int col = messageSuccess ? (0x00FF55|(alpha<<24)) : (0xFF5555|(alpha<<24));
            int mw = textRenderer.getWidth(lastMessage);
            ctx.drawText(textRenderer, lastMessage, GL+GW/2-mw/2, GT+GH+4, col, true);
        }

        // Подтверждение MAX операций
        if (pendingMaxBuy) {
            String confirmTxt = ShopConfig.t("§e▶▶ Ещё раз — куплю на ВСЕ деньги!", "§e▶▶ Click again — buy with ALL balance!");
            int tw2 = textRenderer.getWidth(confirmTxt.replaceAll("§.", ""));
            ctx.drawText(textRenderer, confirmTxt, GL+GW/2-tw2/2, GT+GH+14, 0xFFFFDD00, true);
        }
        if (pendingMaxSell) {
            String confirmTxt = ShopConfig.t("§c◀◀ Ещё раз — продам ВЕСЬ инвентарь!", "§c◀◀ Click again — sell ALL inventory!");
            int tw2 = textRenderer.getWidth(confirmTxt.replaceAll("§.", ""));
            ctx.drawText(textRenderer, confirmTxt, GL+GW/2-tw2/2, GT+GH+14, 0xFFFF5555, true);
        }

        if (hoveredIdx >= 0 && hoveredIdx < filteredEntries.size()) {
            ShopEntry e = filteredEntries.get(hoveredIdx);
            long buy = ShopConfig.getBuyPrice(e.itemId), sell = ShopConfig.getSellPrice(e.itemId);
            List<Text> tt = new ArrayList<>();
            tt.add(Text.literal("§e"+e.displayName()));
            tt.add(Text.literal(ShopConfig.t("§7Купить: §e","§7Buy: §e")+buy+ShopConfig.t(" монет"," coins")));
            tt.add(Text.literal(ShopConfig.t("§7Продать: §a","§7Sell: §a")+sell+ShopConfig.t(" монет"," coins")));
            if (ShopConfig.DYNAMIC_PRICES) {
                int tx = ShopConfig.TRANSACTION_COUNT.getOrDefault(e.itemId, 0);
                tt.add(Text.literal(ShopConfig.t("§7Спрос: §b","§7Demand: §b")+tx+ShopConfig.t(" сделок"," trades")));
            }
            ctx.drawTooltip(textRenderer, tt, mx, my);
        }
    }

    private void drawCoinBreakdown(DrawContext ctx, int x, int y) {
        var player = MinecraftClient.getInstance().player;
        if (player == null) return;
        // Собираем только монеты, которые есть в инвентаре
        List<net.minecraft.item.Item> present = new ArrayList<>();
        List<Integer> counts = new ArrayList<>();
        for (net.minecraft.item.Item coin : EconomyManager.allCoins()) {
            int count = 0;
            for (int i = 0; i < player.getInventory().size(); i++) {
                ItemStack s = player.getInventory().getStack(i);
                if (s.getItem() == coin) count += s.getCount();
            }
            if (count > 0) { present.add(coin); counts.add(count); }
        }
        // Рисуем в 2 ряда по 4 монеты, начиная с x
        int blockW = 30;
        int perRow = 4;
        int cx = x, cy = y;
        for (int i = 0; i < present.size(); i++) {
            if (i == perRow) { cx = x; cy = y + 20; } // второй ряд
            ctx.drawItem(new ItemStack(present.get(i)), cx, cy);
            ctx.drawText(textRenderer, "§f" + counts.get(i), cx + 16, cy + 10, C_WHITE, true);
            cx += blockW;
        }
    }

    private void drawDetailPanel(DrawContext ctx) {
        int rx = GL+DETAIL_X_OFF, ry = GT+LIST_Y;
        ctx.fill(rx-2, ry-2, rx+DETAIL_W+2, GT+GH-4, C_PANEL);

        if (selectedIndex < 0 || selectedIndex >= filteredEntries.size()) {
            ctx.drawText(textRenderer, ShopConfig.t("§7← Выберите предмет","§7← Select an item"), rx+4, ry+10, C_GRAY, false);
            ctx.drawText(textRenderer, ShopConfig.t("§8ПКМ по верстаку с §aИзумрудом","§8RMB on crafting table with §aEmerald"), rx+4, ry+22, 0x88FFFFFF, false);
            int ly = ry+42;
            ctx.drawText(textRenderer, ShopConfig.t("§eВалюта:","§eCurrency:"), rx+4, ly, C_GOLD, false);
            for (int i = 0; i < EconomyManager.allCoins().length; i++) {
                net.minecraft.item.Item coin = EconomyManager.allCoins()[i];
                int val = EconomyManager.coinValue(coin);
                String coinLbl = "§e= " + val;
                ctx.drawItem(new ItemStack(coin), rx+4, ly+12+i*18);
                ctx.drawText(textRenderer, coinLbl, rx+22, ly+17+i*18, C_WHITE, false);
            }
            int cy = ly + 12 + EconomyManager.allCoins().length * 18 + 6; // после всех монет
            ctx.drawText(textRenderer, ShopConfig.t("§eКоманды в чате:","§eChat commands:"), rx+4, cy, C_GOLD, false);
            ctx.drawText(textRenderer, ShopConfig.t("§av<сумма> §7→ монеты→вирт","§av<amount> §7→ coins→virt"), rx+4, cy+10, C_WHITE, false);
            ctx.drawText(textRenderer, ShopConfig.t("§cs<сумма> §7→ вирт→монеты","§cs<amount> §7→ virt→coins"), rx+4, cy+20, C_WHITE, false);
            ctx.drawText(textRenderer, ShopConfig.t("§fbal §7→ баланс","§fbal §7→ balance"), rx+4, cy+30, C_WHITE, false);
            return;
        }

        ShopEntry e = filteredEntries.get(selectedIndex);
        long buyPrice  = ShopConfig.getBuyPrice(e.itemId);
        long sellPrice = ShopConfig.getSellPrice(e.itemId);
        long totalBuy  = buyPrice  * buyAmount;
        long totalSell = sellPrice * buyAmount;

        // Доступный баланс в зависимости от режима
        long availableForBuy = ShopConfig.SPEND_PHYSICAL
                ? EconomyManager.getPhysicalBalance()
                : ShopConfig.VIRTUAL_BALANCE;

        ItemStack detailStack = makeStack(e, buyAmount);
        ctx.drawItem(detailStack, rx+DETAIL_W/2-8, ry+2);
        ctx.drawItemInSlot(textRenderer, detailStack, rx+DETAIL_W/2-8, ry+2);

        // Цветной акцент для зелий/стрел в панели деталей
        ShopConfig.ItemKind selKind = ShopConfig.getItemKind(e.itemId);
        if (selKind == ShopConfig.ItemKind.POTION || selKind == ShopConfig.ItemKind.SPLASH_POTION
                || selKind == ShopConfig.ItemKind.LINGERING_POTION || selKind == ShopConfig.ItemKind.TIPPED_ARROW) {
            int pc = ShopConfig.getPotionColor(ShopConfig.getItemVariant(e.itemId));
            ctx.fill(rx-2, ry-2, rx+DETAIL_W+2, ry+2, (pc & 0x00FFFFFF) | 0x99000000);
        }

        String name = e.displayName();
        int nw = textRenderer.getWidth("§f"+name);
        ctx.drawText(textRenderer, "§f"+name, rx+DETAIL_W/2-nw/2, ry+24, C_WHITE, true);
        // BUG-14 fix: separator moved to ry+36 (was ry+34), giving 4px gap after 8px-tall name text
        ctx.fill(rx+4, ry+36, rx+DETAIL_W-4, ry+37, 0x55FFD700);

        ctx.drawText(textRenderer, ShopConfig.t("§7Цена (шт): §e","§7Price(ea): §e")+buyPrice,    rx+4, ry+42, C_WHITE, false);
        ctx.drawText(textRenderer, ShopConfig.t("§7Продажа(шт): §a","§7Sell(ea): §a")+sellPrice, rx+4, ry+52, C_WHITE, false);

        if (ShopConfig.DYNAMIC_PRICES) {
            int tx = ShopConfig.TRANSACTION_COUNT.getOrDefault(e.itemId, 0);
            String demand = tx<3 ? ShopConfig.t("§aНизкий","§aLow") : tx<8 ? ShopConfig.t("§eСредний","§eMid") : tx<15 ? ShopConfig.t("§6Высокий","§6High") : ShopConfig.t("§cОчень высокий","§cV.High");
            ctx.drawText(textRenderer, ShopConfig.t("§7Спрос: ","§7Demand: ")+demand, rx+4, ry+62, C_WHITE, false);
        }

        ctx.fill(rx+4, ry+72, rx+DETAIL_W-4, ry+73, 0x33FFFFFF);
        ctx.drawText(textRenderer, ShopConfig.t("§fКол-во: §b","§fQty: §b")+buyAmount, rx+DETAIL_W/2-18, ry+77, C_WHITE, true);
        // BUG-15 fix: ry+198+12=ry+210 — конец кнопок x64/x1 (сдвинуты +4px); тексты с ry+214
        ctx.drawText(textRenderer, ShopConfig.t("§7Итого купить: §e","§7Total buy: §e")+totalBuy,    rx+4, ry+214, C_WHITE, false);
        ctx.drawText(textRenderer, ShopConfig.t("§7Итого продать: §a","§7Total sell: §a")+totalSell,  rx+4, ry+224, C_WHITE, false);

        boolean canAfford = availableForBuy >= totalBuy;
        ctx.drawText(textRenderer, canAfford ? ShopConfig.t("§a✔ Хватает","§a✔ Enough") : ShopConfig.t("§c✘ Нехватает §e","§c✘ Not enough §e")+(totalBuy-availableForBuy),
                rx+4, ry+238, C_WHITE, false);
        ctx.drawText(textRenderer, ShopConfig.t("§7В инвентаре: §f","§7In inventory: §f")+EconomyManager.countItemByShopId(e.itemId), rx+4, ry+248, C_WHITE, false);
    }

    // ── Input ─────────────────────────────────────────────────────────────────
    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        // Клик по списку — сбрасываем ожидание подтверждения MAX
        for (int i = 0; i < VISIBLE && i+scrollOffset < filteredEntries.size(); i++) {
            int ry = GT+LIST_Y+i*ROW_H;
            if (mx>=GL+LIST_X && mx<GL+LIST_X+LIST_W && my>=ry && my<ry+ROW_H) {
                pendingMaxBuy = false; pendingMaxSell = false;
                int newIdx = i+scrollOffset;
                boolean changed = selectedIndex != newIdx;
                selectedIndex = newIdx;
                selectedItemId = filteredEntries.get(selectedIndex).itemId;
                if (changed) {
                    int ms = filteredEntries.get(selectedIndex).item().getMaxCount();
                    if (buyAmount > ms) buyAmount = ms;
                    rebuildWidgets();
                }
                return true;
            }
        }
        return super.mouseClicked(mx, my, btn);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hAmt, double vAmt) {
        // BUG-39 fix: Y-зона скролла колёсиком начинается с +14, чтобы не перекрываться с кнопкой ▲ (h=12)
        if (mx>=GL+LIST_X && mx<GL+LIST_X+LIST_W+20 && my>=GT+LIST_Y+14 && my<GT+LIST_Y+VISIBLE*ROW_H) {
            scroll(vAmt > 0 ? -1 : 1); return true;
        }
        return super.mouseScrolled(mx, my, hAmt, vAmt);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (searchField == null) return super.keyPressed(keyCode, scanCode, modifiers);
        if (searchField.isFocused()) return super.keyPressed(keyCode, scanCode, modifiers);
        if (keyCode == 264 && selectedIndex < filteredEntries.size()-1) {
            selectedIndex++; selectedItemId = filteredEntries.get(selectedIndex).itemId;
            if (selectedIndex >= scrollOffset+VISIBLE) scroll(1); return true;
        }
        if (keyCode == 265 && selectedIndex > 0) {
            selectedIndex--; selectedItemId = filteredEntries.get(selectedIndex).itemId;
            if (selectedIndex < scrollOffset) scroll(-1); return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    // ── Logic ─────────────────────────────────────────────────────────────────
    private void doBuy() {
        if (selectedIndex < 0 || selectedIndex >= filteredEntries.size()) {
            showMessage(ShopConfig.t("Выберите предмет!","Select an item!"), false); return;
        }
        ShopEntry e = filteredEntries.get(selectedIndex);
        buyAmount = Math.max(1, Math.min(e.item.getMaxCount(), buyAmount));
        long totalCost = ShopConfig.getBuyPrice(e.itemId) * buyAmount;

        boolean ok;
        if (ShopConfig.SPEND_PHYSICAL) {
            if (EconomyManager.getPhysicalBalance() < totalCost) {
                showMessage(ShopConfig.t("Мало монет! Нужно ","Not enough coins! Need ") + totalCost, false); return;
            }
            ok = EconomyManager.buyWithPhysical(e.itemId, buyAmount, totalCost);
        } else {
            if (ShopConfig.VIRTUAL_BALANCE < totalCost) {
                showMessage(ShopConfig.t("Мало виртуала! Нужно ","Not enough virtual! Need ") + totalCost, false); return;
            }
            ok = EconomyManager.buyWithVirtual(e.itemId, buyAmount, totalCost);
        }

        if (!ok) { showMessage(ShopConfig.t("Ошибка транзакции!","Transaction error!"), false); return; }
        ShopConfig.recordTransaction(e.itemId);
        showMessage(ShopConfig.t("Куплено ","Bought ") + buyAmount + "x " + e.displayName() + ShopConfig.t(" за "," for ") + totalCost, true);
    }

    private long getWorldTime() {
        var mc = MinecraftClient.getInstance();
        return (mc.world != null) ? mc.world.getTimeOfDay() : 0L;
    }

    private void doSell() {
        if (selectedIndex < 0 || selectedIndex >= filteredEntries.size()) {
            showMessage(ShopConfig.t("Выберите предмет!","Select an item!"), false); return;
        }
        ShopEntry e = filteredEntries.get(selectedIndex);
        if (EconomyManager.isCoin(e.item)) { showMessage(ShopConfig.t("Нельзя продавать монеты!","Can't sell coins!"), false); return; }
        buyAmount = Math.max(1, Math.min(e.item.getMaxCount(), buyAmount));
        int inInv = EconomyManager.countItemByShopId(e.itemId);
        if (inInv < buyAmount) { showMessage(ShopConfig.t("Недостаточно ","Not enough: ") + e.displayName() + ShopConfig.t("! Есть: "," available: ") + inInv, false); return; }

        // Хардкор: проверяем дневной лимит
        int allowedAmount = ShopConfig.getHardcoreAllowedSellAmount(e.itemId, buyAmount, getWorldTime());
        if (allowedAmount <= 0) {
            showMessage(ShopConfig.t("§cЛимит продаж на сегодня исчерпан! (макс. " + ShopConfig.HARDCORE_DAILY_LIMIT + "/день)",
                    "§cDaily sell limit reached! (max " + ShopConfig.HARDCORE_DAILY_LIMIT + "/day)"), false); return;
        }
        if (allowedAmount < buyAmount) {
            showMessage(ShopConfig.t("§eХардкор: можно продать только " + allowedAmount + " (лимит дня)",
                    "§eHardcore: can only sell " + allowedAmount + " today (daily limit)"), false);
            buyAmount = allowedAmount;
        }

        long earned = ShopConfig.getSellPrice(e.itemId) * buyAmount;
        boolean ok;
        if (ShopConfig.RECEIVE_PHYSICAL) {
            ok = EconomyManager.sellForPhysical(e.item, e.itemId, buyAmount, earned);
        } else {
            ok = EconomyManager.sellForVirtual(e.item, e.itemId, buyAmount, earned);
        }

        if (!ok) { showMessage(ShopConfig.t("Не удалось продать!","Sell failed!"), false); return; }
        ShopConfig.decrementTransaction(e.itemId);
        ShopConfig.recordSale(e.itemId, buyAmount);
        ShopConfig.recordDailySell(e.itemId, buyAmount);
        showMessage(ShopConfig.t("Продано ","Sold ") + buyAmount + "x " + e.displayName() + ShopConfig.t(" за "," for ") + earned, true);
    }

    /** MAX КУПИТЬ — покупаем столько, сколько позволяет баланс */
    private void doMaxBuy() {
        if (selectedIndex < 0 || selectedIndex >= filteredEntries.size()) {
            showMessage(ShopConfig.t("Выберите предмет!","Select an item!"), false); return;
        }
        ShopEntry e = filteredEntries.get(selectedIndex);
        long unitPrice = ShopConfig.getBuyPrice(e.itemId);
        if (unitPrice <= 0) { showMessage(ShopConfig.t("Ошибка цены!","Price error!"), false); return; }
        long balance = ShopConfig.SPEND_PHYSICAL ? EconomyManager.getPhysicalBalance() : ShopConfig.VIRTUAL_BALANCE;
        int maxAmount = (int) Math.min(e.item.getMaxCount(), balance / unitPrice);
        if (maxAmount <= 0) { showMessage(ShopConfig.t("Нет монет!","No coins!"), false); return; }
        long totalCost = unitPrice * maxAmount;
        boolean ok = ShopConfig.SPEND_PHYSICAL
                ? EconomyManager.buyWithPhysical(e.itemId, maxAmount, totalCost)
                : EconomyManager.buyWithVirtual(e.itemId, maxAmount, totalCost);
        if (!ok) { showMessage(ShopConfig.t("Ошибка транзакции!","Transaction error!"), false); return; }
        ShopConfig.recordTransaction(e.itemId);
        showMessage(ShopConfig.t("Куплено ","Bought ") + maxAmount + "x " + e.displayName() + ShopConfig.t(" за "," for ") + totalCost, true);
    }

    /** MAX ПРОДАТЬ — продаём всё что есть в инвентаре */
    private void doMaxSell() {
        if (selectedIndex < 0 || selectedIndex >= filteredEntries.size()) {
            showMessage(ShopConfig.t("Выберите предмет!","Select an item!"), false); return;
        }
        ShopEntry e = filteredEntries.get(selectedIndex);
        if (EconomyManager.isCoin(e.item)) { showMessage(ShopConfig.t("Нельзя продавать монеты!","Can't sell coins!"), false); return; }
        int inInv = EconomyManager.countItemByShopId(e.itemId);
        if (inInv <= 0) { showMessage(ShopConfig.t("Нет предметов в инвентаре!","No items in inventory!"), false); return; }

        // Хардкор: ограничиваем дневным лимитом
        int sellAmount = ShopConfig.getHardcoreAllowedSellAmount(e.itemId, inInv, getWorldTime());
        if (sellAmount <= 0) {
            showMessage(ShopConfig.t("§cЛимит продаж на сегодня исчерпан! (макс. " + ShopConfig.HARDCORE_DAILY_LIMIT + "/день)",
                    "§cDaily sell limit reached! (max " + ShopConfig.HARDCORE_DAILY_LIMIT + "/day)"), false); return;
        }
        if (sellAmount < inInv) {
            showMessage(ShopConfig.t("§eХардкор: продаём только " + sellAmount + " из " + inInv + " (лимит дня)",
                    "§eHardcore: selling " + sellAmount + " of " + inInv + " (daily limit)"), false);
        }

        long unitPrice = ShopConfig.getSellPrice(e.itemId);
        long earned = unitPrice * sellAmount;
        boolean ok = ShopConfig.RECEIVE_PHYSICAL
                ? EconomyManager.sellForPhysical(e.item, e.itemId, sellAmount, earned)
                : EconomyManager.sellForVirtual(e.item, e.itemId, sellAmount, earned);
        if (!ok) { showMessage(ShopConfig.t("Не удалось продать!","Sell failed!"), false); return; }
        ShopConfig.decrementTransaction(e.itemId);
        ShopConfig.recordSale(e.itemId, sellAmount);
        ShopConfig.recordDailySell(e.itemId, sellAmount);
        showMessage(ShopConfig.t("Продано ","Sold ") + sellAmount + "x " + e.displayName() + ShopConfig.t(" за "," for ") + earned, true);
    }

    private void applyFilter() {
        String raw = searchField != null ? searchField.getText().trim() : "";
        String search = translateQuery(raw).toLowerCase();
        filteredEntries = allEntries.stream().filter(e -> {
            if (!search.isEmpty()) {
                boolean matchesTranslated = e.itemId.toLowerCase().contains(search)
                        || e.displayName().toLowerCase().contains(search);
                boolean matchesRaw = !raw.isEmpty() && !raw.equalsIgnoreCase(search)
                        && (e.itemId.toLowerCase().contains(raw.toLowerCase())
                        || e.displayName().toLowerCase().contains(raw.toLowerCase()));
                if (!matchesTranslated && !matchesRaw) return false;
            }
            return switch (currentTab) {
                case "БРОНЯ"   -> isArmor(e.itemId);
                case "ОРУ"     -> isWeaponTool(e.itemId);
                case "ЕДА"     -> isFood(e.itemId);
                case "БЛОКИ"   -> isBlock(e.itemId);
                case "КНИГИ"   -> isEnchBook(e.itemId);
                case "ЗЕЛЬЯ"   -> isPotion(e.itemId);
                case "СТРЕЛЫ"  -> isTippedArrow(e.itemId);
                case "ЦЕННЫЕ"  -> isValuable(e.itemId);
                case "ЯЙЦА"    -> isSpawnEgg(e.itemId);
                default -> true;
            };
        }).collect(Collectors.toList());

        // Сортировка
        switch (sortMode) {
            case 1 -> // По популярности (кол-во продаж убывает)
                    filteredEntries.sort((a, b) -> {
                        long sa = ShopConfig.SELL_STATS.getOrDefault(a.itemId, 0L);
                        long sb2 = ShopConfig.SELL_STATS.getOrDefault(b.itemId, 0L);
                        return Long.compare(sb2, sa);
                    });
            case 2 -> // По цене ↑
                    filteredEntries.sort(Comparator.comparingLong(e ->
                            sortByBuyPrice ? ShopConfig.getBuyPrice(e.itemId) : ShopConfig.getSellPrice(e.itemId)));
            case 3 -> // По цене ↓
                    filteredEntries.sort((a, b) -> {
                        long pa = sortByBuyPrice ? ShopConfig.getBuyPrice(a.itemId) : ShopConfig.getSellPrice(a.itemId);
                        long pb2 = sortByBuyPrice ? ShopConfig.getBuyPrice(b.itemId) : ShopConfig.getSellPrice(b.itemId);
                        return Long.compare(pb2, pa);
                    });
            default -> // По алфавиту (displayName)
                    filteredEntries.sort(Comparator.comparing(ShopEntry::displayName));
        }
    }

    private void restoreSelectedIndex() {
        if (selectedItemId == null) { selectedIndex = -1; return; }
        selectedIndex = -1;
        for (int i = 0; i < filteredEntries.size(); i++) {
            if (filteredEntries.get(i).itemId.equals(selectedItemId)) { selectedIndex = i; break; }
        }
        if (selectedIndex == -1) selectedItemId = null;
    }

    private boolean isArmor(String id)       { return id.contains("helmet")||id.contains("chestplate")||id.contains("leggings")||id.contains("boots")||id.contains("elytra")||id.contains("turtle_helmet")||id.contains("wolf_armor")||id.contains("horse_armor"); }
    private boolean isWeaponTool(String id)  { return id.contains("sword")||id.contains("bow")||id.contains("crossbow")||id.contains("trident")||id.contains("shield")||id.contains("pickaxe")||id.contains("_axe")||id.contains("shovel")||id.contains("hoe")||id.contains("wooden_")||id.contains("stone_")||id.contains("iron_pick")||id.contains("iron_axe")||id.contains("fishing_rod")||id.contains("flint_and_steel")||id.contains("spyglass")||id.contains("brush")||id.contains("compass")||id.contains("clock")||id.contains("mace"); }
    private boolean isFood(String id)        { return id.contains("bread")||id.contains("apple")||id.contains("beef")||id.contains("chicken")||id.contains("pork")||id.contains("wheat")||id.contains("carrot")||id.contains("potato")||id.contains("cooked")||id.contains("cake")||id.contains("cookie")||id.contains("melon_slice")||id.contains("stew")||id.contains("soup")||id.contains("berry")||id.contains("honey")||id.contains("mutton")||id.contains("rabbit")||id.contains("cod")||id.contains("salmon")||id.contains("tropical_fish")||id.contains("pufferfish")||id.contains("sugar")||id.contains("beetroot")||id.contains("pumpkin_pie")||id.contains("dried_kelp")||id.contains("glow_berries"); }
    private boolean isBlock(String id)       { return id.startsWith("minecraft:")&&(id.contains("_ore")||id.contains("stone")||id.contains("log")||id.contains("planks")||id.contains("dirt")||id.contains("sand")||id.contains("gravel")||id.contains("glass")||id.contains("obsidian")||id.contains("sapling")||id.contains("torch")||id.contains("brick")||id.contains("deepslate")||id.contains("tuff")||id.contains("basalt")||id.contains("blackstone")||id.contains("andesite")||id.contains("granite")||id.contains("diorite")||id.contains("cobblestone")||id.contains("slab")||id.contains("stairs")||id.contains("wall")||id.contains("_block")||id.contains("concrete")||id.contains("terracotta")||id.contains("wool")||id.contains("carpet")||id.contains("bamboo")||id.contains("coral")||id.contains("prismarine")||id.contains("end_stone")||id.contains("purpur")||id.contains("sculk")||id.contains("nylium")||id.contains("hyphae")||id.contains("stem")); }
    private boolean isEnchBook(String id)    { return id.startsWith("enchanted_book:"); }
    private boolean isPotion(String id)      { return id.startsWith("potion:")||id.startsWith("splash_potion:")||id.startsWith("lingering_potion:"); }
    private boolean isTippedArrow(String id) { return id.startsWith("tipped_arrow:"); }
    private boolean isSpawnEgg(String id)    { return id.contains("spawn_egg"); }
    private boolean isValuable(String id)    { return id.startsWith("minecraft:")&&(id.contains("diamond")||id.contains("emerald")||id.contains("netherite")||id.contains("ancient_debris")||id.contains("elytra")||id.contains("totem")||id.contains("trident")||id.contains("nether_star")||id.contains("beacon")||id.contains("end_crystal")||id.contains("dragon")||id.contains("heart_of_the_sea")||id.contains("conduit")||id.contains("echo_shard")||id.contains("enchanted_golden_apple")||id.contains("music_disc")||id.contains("wither_skeleton_skull")||id.contains("shulker_shell")||id.contains("heavy_core")||id.contains("mace")||id.contains("end_portal_frame")||id.contains("armor_trim")||id.contains("pottery_sherd")||id.contains("music_disc")); }

    // Устаревшие методы — оставляем для совместимости
    private boolean isCombat(String id) { return isArmor(id) || isWeaponTool(id); }
    private boolean isTools(String id)  { return id.contains("pickaxe")||id.contains("_axe")||id.contains("shovel")||id.contains("hoe")||id.contains("fishing_rod")||id.contains("flint_and_steel")||id.contains("compass")||id.contains("clock")||id.contains("spyglass")||id.contains("brush"); }

    private void scroll(int dir) {
        int max = Math.max(0, filteredEntries.size()-VISIBLE);
        scrollOffset = Math.max(0, Math.min(max, scrollOffset+dir));
    }

    private void showMessage(String msg, boolean success) {
        lastMessage = msg; messageSuccess = success;
        messageShowUntilMs = System.currentTimeMillis() + 4000;
    }

    private String formatBalance(long bal) {
        if (bal == 0) return "§f0";
        if (bal < 0) return "§c-" + formatBalance(-bal);
        StringBuilder sb = new StringBuilder();
        long k = bal/1000, h = (bal%1000)/100, d = (bal%100)/10, o = bal%10;
        if (k > 0) sb.append("§b").append(k).append("k");
        if (h > 0) sb.append("§d").append(h).append("h");
        if (d > 0) sb.append("§6").append(d).append("d");
        if (o > 0 || (k==0 && h==0 && d==0)) sb.append("§f").append(o);
        return sb.toString();
    }

    @Override public boolean shouldPause() { return false; }

    /** Предвыбор предмета при переходе из другого экрана */
    public void preselect(String itemId) {
        if (itemId != null) {
            selectedItemId = itemId;
        }
    }

    /**
     * Создаёт ItemStack для отображения иконки.
     * Для зелий и стрел подставляет PotionContentsComponent,
     * чтобы Minecraft рисовал цветную жидкость/наконечник.
     */
    private static ItemStack makeStack(ShopEntry e, int count) {
        ItemStack stack = new ItemStack(e.item, count);
        ShopConfig.ItemKind kind = ShopConfig.getItemKind(e.itemId);
        if (kind == ShopConfig.ItemKind.POTION
                || kind == ShopConfig.ItemKind.SPLASH_POTION
                || kind == ShopConfig.ItemKind.LINGERING_POTION
                || kind == ShopConfig.ItemKind.TIPPED_ARROW) {
            String variant = ShopConfig.getItemVariant(e.itemId);
            Identifier potionId = Identifier.of("minecraft", variant);
            RegistryEntry<Potion> potionEntry = Registries.POTION.getEntry(potionId).orElse(null);
            if (potionEntry != null) {
                stack.set(DataComponentTypes.POTION_CONTENTS,
                        new PotionContentsComponent(potionEntry));
            }
        }
        return stack;
    }

    record ShopEntry(String itemId, Item item) {
        /** Локализованное название: берём из игры (RU/EN зависит от языка Minecraft) */
        String displayName() {
            ShopConfig.ItemKind kind = ShopConfig.getItemKind(itemId);
            if (kind != ShopConfig.ItemKind.NORMAL) {
                String variant = ShopConfig.getItemVariant(itemId);
                if (kind == ShopConfig.ItemKind.ENCHANTED_BOOK) {
                    boolean ru = "ru".equals(ShopConfig.LANGUAGE);
                    String prefix = ru ? "Книга: " : "Book: ";
                    return prefix + ShopConfig.getVariantDisplayName(variant);
                }
                // Зелья и стрелы — полное ванильное название через getItemDisplayName
                return ShopConfig.getItemDisplayName(variant, kind);
            }
            String loc = new ItemStack(item).getName().getString();
            // Если игра вернула технический ключ (не переведено) — делаем CamelCase из id
            if (loc.startsWith("item.") || loc.startsWith("block.") || loc.startsWith("tile.")) {
                String[] parts = itemId.split(":");
                String raw = parts.length > 1 ? parts[1] : parts[0];
                return Arrays.stream(raw.split("_"))
                        .map(w -> w.isEmpty() ? w : Character.toUpperCase(w.charAt(0)) + w.substring(1))
                        .collect(Collectors.joining(" "));
            }
            return loc;
        }
    }
}