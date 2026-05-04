package com.shopmod.registry;

/**
 * Кастомные предметы-монеты удалены.
 * Теперь используются ванильные предметы из EconomyManager:
 *   COIN_1000 = Items.ECHO_SHARD
 *   COIN_100  = Items.DISC_FRAGMENT_5
 *   COIN_10   = Items.CLAY_BALL
 *   COIN_1    = Items.BRICK
 *
 * Регистрация в реестре не нужна — мод полностью клиентский.
 */
public class ModItems {
    public static void register() {
        // Ничего не регистрируем — используем ванильные предметы
    }
}
