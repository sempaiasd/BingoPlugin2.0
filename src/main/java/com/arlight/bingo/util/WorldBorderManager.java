package com.arlight.bingo.util;

import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Ajusta el borde de los mundos del Bingo mediante radios fijos.
 *
 * No utiliza locateNearestStructure(), porque esa operación puede bloquear
 * el hilo principal de Arclight con mods de generación de estructuras.
 */
public class WorldBorderManager {

    /**
     * Se genera un chunk por tick para reducir la carga en Arclight.
     */
    private static final int CHUNKS_POR_TICK = 1;

    /**
     * Radios de los bordes.
     *
     * El diámetro final será el doble del radio.
     */
    private static final int RADIO_OVERWORLD = 4096;
    private static final int RADIO_NETHER = 2048;
    private static final int RADIO_END = 4096;

    private final JavaPlugin plugin;

    public WorldBorderManager(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Aplica un borde fijo dependiendo de la dimensión y, si está habilitado,
     * pregenera una zona pequeña cerca del centro.
     */
    public CompletableFuture<Void> applyAdaptiveBorder(
            World world,
            ConfigManager cfg
    ) {
        if (!cfg.isWorldBorderEnabled()) {
            return CompletableFuture.completedFuture(null);
        }

        int configuredMinimumRadius =
                Math.max(1, cfg.getWorldBorderMinSize() / 2);

        int neededRadius;

        switch (world.getEnvironment()) {
            case NORMAL:
                neededRadius = Math.max(
                        configuredMinimumRadius,
                        RADIO_OVERWORLD
                );
                break;

            case NETHER:
                neededRadius = Math.max(
                        configuredMinimumRadius,
                        RADIO_NETHER
                );
                break;

            case THE_END:
                neededRadius = Math.max(
                        configuredMinimumRadius,
                        RADIO_END
                );
                break;

            default:
                neededRadius = configuredMinimumRadius;
                break;
        }

        int maxRadius = Math.max(
                1,
                cfg.getWorldBorderMaxSize() / 2
        );

        int finalRadius = Math.min(
                neededRadius,
                maxRadius
        );

        if (finalRadius < neededRadius) {
            plugin.getLogger().warning(
                    "El borde solicitado para "
                            + world.getName()
                            + " era de "
                            + (neededRadius * 2)
                            + " bloques de diámetro, pero fue limitado a "
                            + (finalRadius * 2)
                            + " por world-border.max-size="
                            + cfg.getWorldBorderMaxSize()
                            + "."
            );
        }

        world.getWorldBorder().setCenter(0.0, 0.0);
        world.getWorldBorder().setSize(finalRadius * 2.0);

        plugin.getLogger().info(
                "Borde de "
                        + world.getName()
                        + " establecido en "
                        + (finalRadius * 2)
                        + " bloques de diámetro."
        );

        if (!cfg.isWorldBorderPregenerate()) {
            plugin.getLogger().info(
                    "Pregeneración desactivada para "
                            + world.getName()
                            + "."
            );

            return CompletableFuture.completedFuture(null);
        }

        int configuredPregenerationRadius = Math.max(
                0,
                cfg.getWorldBorderPregenerateRadius()
        );

        int pregenerationRadius = Math.min(
                finalRadius,
                configuredPregenerationRadius
        );

        if (pregenerationRadius <= 0) {
            plugin.getLogger().info(
                    "No se pregenerarán chunks en "
                            + world.getName()
                            + " porque el radio de pregeneración es 0."
            );

            return CompletableFuture.completedFuture(null);
        }

        return pregenerateCore(
                world,
                pregenerationRadius
        );
    }

    /**
     * Pregenera los chunks progresivamente en el hilo principal.
     *
     * Arclight no implementa World#getChunkAtAsync(), por lo que se usa
     * getChunkAt(). Solo se procesa un chunk por tick para reducir la carga.
     */
    private CompletableFuture<Void> pregenerateCore(
            World world,
            int radius
    ) {
        CompletableFuture<Void> completion =
                new CompletableFuture<>();

        int chunkRadius = (int) Math.ceil(radius / 16.0);
        List<int[]> chunks = createChunkList(chunkRadius);

        int totalChunks = chunks.size();

        plugin.getLogger().info(
                "Pregenerando "
                        + totalChunks
                        + " chunks en "
                        + world.getName()
                        + " con un radio de "
                        + radius
                        + " bloques..."
        );

        new BukkitRunnable() {

            private int currentIndex = 0;

            @Override
            public void run() {
                try {
                    /*
                     * Comprobar que el plugin continúa habilitado.
                     */
                    if (!plugin.isEnabled()) {
                        completion.completeExceptionally(
                                new IllegalStateException(
                                        "El plugin fue deshabilitado durante "
                                                + "la pregeneración."
                                )
                        );

                        cancel();
                        return;
                    }

                    /*
                     * Comprobar que el mundo continúa cargado.
                     */
                    World loadedWorld = plugin.getServer().getWorld(
                            world.getName()
                    );

                    if (loadedWorld == null) {
                        completion.completeExceptionally(
                                new IllegalStateException(
                                        "El mundo "
                                                + world.getName()
                                                + " fue descargado durante "
                                                + "la pregeneración."
                                )
                        );

                        cancel();
                        return;
                    }

                    int generatedThisTick = 0;

                    while (currentIndex < totalChunks
                            && generatedThisTick < CHUNKS_POR_TICK) {

                        int[] coordinates =
                                chunks.get(currentIndex);

                        int chunkX = coordinates[0];
                        int chunkZ = coordinates[1];

                        /*
                         * Si el chunk todavía no está cargado, se genera
                         * o carga en el hilo principal.
                         */
                        if (!loadedWorld.isChunkLoaded(chunkX, chunkZ)) {
                            loadedWorld.getChunkAt(chunkX, chunkZ);
                        }

                        currentIndex++;
                        generatedThisTick++;
                    }

                    if (currentIndex >= totalChunks) {
                        plugin.getLogger().info(
                                "Pregeneración de "
                                        + world.getName()
                                        + " terminada. "
                                        + totalChunks
                                        + " chunks procesados."
                        );

                        completion.complete(null);
                        cancel();
                    }

                } catch (Throwable throwable) {
                    plugin.getLogger().severe(
                            "Error pregenerando chunks en "
                                    + world.getName()
                                    + ": "
                                    + throwable.getClass().getSimpleName()
                                    + ": "
                                    + throwable.getMessage()
                    );

                    throwable.printStackTrace();

                    completion.completeExceptionally(throwable);
                    cancel();
                }
            }
        }.runTaskTimer(plugin, 1L, 1L);

        return completion;
    }

    /**
     * Crea la lista de chunks comenzando desde el centro y avanzando hacia
     * el exterior. Esto permite que la zona inicial se prepare primero.
     */
    private List<int[]> createChunkList(int chunkRadius) {
        List<int[]> chunks = new ArrayList<>();

        for (int x = -chunkRadius; x <= chunkRadius; x++) {
            for (int z = -chunkRadius; z <= chunkRadius; z++) {
                chunks.add(new int[]{x, z});
            }
        }

        chunks.sort((first, second) -> {
            int firstDistance =
                    first[0] * first[0] + first[1] * first[1];

            int secondDistance =
                    second[0] * second[0] + second[1] * second[1];

            return Integer.compare(
                    firstDistance,
                    secondDistance
            );
        });

        return chunks;
    }
}