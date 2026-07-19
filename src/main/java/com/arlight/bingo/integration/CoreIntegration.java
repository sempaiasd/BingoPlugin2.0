package com.arlight.bingo.integration;

import com.arlight.bingo.game.BingoGame;
import com.arlight.bingo.game.BingoTeam;
import com.arlight.bingo.game.GameState;
import com.arlight.core.api.ArlightCoreAPI;
import com.arlight.core.api.MinigameProvider;
import com.arlight.core.api.MinigameStatus;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.UUID;

/**
 * Integracion OPCIONAL con ArlightCore: registra el Bingo en el item selector de minijuegos
 * y otorga XP a los ganadores. Esta clase solo se carga (se referencia) desde BingoPlugin
 * DESPUES de comprobar que el plugin ArlightCore esta instalado, asi que si no lo esta,
 * el resto del plugin nunca toca las clases de ArlightCoreAPI y no hay ningun error.
 */
public final class CoreIntegration {

    private CoreIntegration() {
    }

    public static void register(BingoGame game) {
        ArlightCoreAPI.registerMinigame(new MinigameProvider() {
            @Override
            public String getId() {
                return "bingo";
            }

            @Override
            public String getDisplayName() {
                return ChatColor.GOLD + "Bingo";
            }

            @Override
            public ItemStack getIcon() {
                return new ItemStack(Material.PAPER);
            }

            @Override
            public MinigameStatus getStatus() {
                return game.getState() == GameState.WAITING ? MinigameStatus.WAITING : MinigameStatus.IN_PROGRESS;
            }

            @Override
            public void join(Player player) {
                game.addPlayer(player, null);
            }
        });

        game.setWinListener(winners -> {
            for (BingoTeam team : winners) {
                for (UUID uuid : team.getMembers()) {
                    Player p = Bukkit.getPlayer(uuid);
                    if (p != null) {
                        ArlightCoreAPI.addWinXp(p);
                    }
                }
            }
        });
    }
}
