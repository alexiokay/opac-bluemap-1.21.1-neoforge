package io.github.gaming32.opacbluemapintegration;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.logging.LogUtils;
import de.bluecolored.bluemap.api.BlueMapAPI;
import de.bluecolored.bluemap.api.BlueMapWorld;
import de.bluecolored.bluemap.api.markers.ExtrudeMarker;
import de.bluecolored.bluemap.api.markers.Marker;
import de.bluecolored.bluemap.api.markers.MarkerSet;
import de.bluecolored.bluemap.api.markers.ShapeMarker;
import de.bluecolored.bluemap.api.math.Color;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppedEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.fml.loading.FMLPaths;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.arguments.TimeArgument;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import xaero.pac.common.claims.player.api.IPlayerClaimPosListAPI;
import xaero.pac.common.server.api.OpenPACServerAPI;
import xaero.pac.common.server.claims.api.IServerClaimsManagerAPI;
import xaero.pac.common.claims.tracker.api.IClaimsManagerTrackerAPI;
import xaero.pac.common.claims.tracker.api.IClaimsManagerListenerAPI;
import xaero.pac.common.claims.player.api.IPlayerChunkClaimAPI;

import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.Objects;

import static net.minecraft.commands.Commands.argument;
import static net.minecraft.commands.Commands.literal;

@Mod("opac_bluemap_integration")
public class OpacBluemapIntegration implements IClaimsManagerListenerAPI {
    public static final Logger LOGGER = LogUtils.getLogger();

    private static final String MARKER_SET_KEY = "opac-bluemap-integration";
    private static final Path CONFIG_FILE = FMLPaths.CONFIGDIR.get().resolve("opac-bluemap.json");

    public static final OpacBluemapConfig CONFIG = new OpacBluemapConfig();

    private static MinecraftServer minecraftServer;
    private static IClaimsManagerTrackerAPI claimsTracker;

    // For incremental updates - track which players need marker refresh  
    private static final Set<UUID> playersNeedingUpdate = Collections.synchronizedSet(new HashSet<>());
    private static final Map<ChunkPos, UUID> chunkToPlayerCache = new ConcurrentHashMap<>();

    private static int updateIn;

    public OpacBluemapIntegration() {
        loadConfig();
        BlueMapAPI.onEnable(OpacBluemapIntegration::updateClaims);
        
        NeoForge.EVENT_BUS.register(this);
    }
    
    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        minecraftServer = event.getServer();
        
        // Register OpenPAC claim change listener with incremental updates
        try {
            IServerClaimsManagerAPI claimsManager = OpenPACServerAPI.get(minecraftServer).getServerClaimsManager();
            claimsTracker = claimsManager.getTracker();
            claimsTracker.register(this);
            LOGGER.info("Registered OpenPAC claim change listener with incremental updates");
        } catch (Exception e) {
            LOGGER.error("Failed to register OpenPAC claim change listener", e);
        }
    }
    
    @SubscribeEvent
    public void onServerStopped(ServerStoppedEvent event) {
        // Clear OpenPAC claim tracker reference
        claimsTracker = null;
        minecraftServer = null;
    }
    
    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(literal("openpac-bluemap")
            .requires(s -> s.hasPermission(2))
            .then(literal("refresh-now")
                .requires(s -> BlueMapAPI.getInstance().isPresent())
                .executes(ctx -> {
                    final BlueMapAPI api = BlueMapAPI.getInstance().orElse(null);
                    if (api == null) {
                        ctx.getSource().sendFailure(Component.literal("BlueMap not loaded").withStyle(ChatFormatting.RED));
                        return 0;
                    }
                    updateClaims(api);
                    ctx.getSource().sendSuccess(
                            () -> Component.literal("BlueMap OpenPaC claims refreshed").withStyle(ChatFormatting.GREEN),
                        true
                    );
                    return Command.SINGLE_SUCCESS;
                })
            )
            .then(literal("refresh-in")
                .executes(ctx -> {
                    ctx.getSource().sendSuccess(() -> Component.literal("OpenPaC BlueMap will refresh in ").append(
                                Component.literal((updateIn / 20) + "s").withStyle(ChatFormatting.GREEN)
                        ),
                        true
                    );
                    return Command.SINGLE_SUCCESS;
                })
                .then(argument("time", TimeArgument.time())
                    .executes(ctx -> {
                        updateIn = IntegerArgumentType.getInteger(ctx, "time");
                        ctx.getSource().sendSuccess(
                                () -> Component.literal("OpenPaC BlueMap will refresh in ").append(
                                Component.literal((updateIn / 20) + "s").withStyle(ChatFormatting.GREEN)
                            ),
                            true
                        );
                        return Command.SINGLE_SUCCESS;
                    })
                )
            )
            .then(literal("refresh-every")
                .executes(ctx -> {
                    ctx.getSource().sendSuccess(
                            () -> Component.literal("OpenPaC BlueMap auto refreshes every ").append(
                            Component.literal((CONFIG.getUpdateInterval() / 20) + "s").withStyle(ChatFormatting.GREEN)
                        ),
                        true
                    );
                    return Command.SINGLE_SUCCESS;
                })
                .then(argument("interval", TimeArgument.time())
                    .executes(ctx -> {
                        final int interval = IntegerArgumentType.getInteger(ctx, "interval");
                        CONFIG.setUpdateInterval(interval);
                        if (interval < updateIn) {
                            updateIn = interval;
                        }
                        saveConfig();
                        ctx.getSource().sendSuccess(
                                () -> Component.literal("OpenPaC BlueMap will auto refresh every ").append(
                                Component.literal((interval / 20) + "s").withStyle(ChatFormatting.GREEN)
                            ),
                            true
                        );
                        return Command.SINGLE_SUCCESS;
                    })
                )
            )
            .then(literal("reload")
                .executes(ctx -> {
                    loadConfig();
                    if (CONFIG.getUpdateInterval() < updateIn) {
                        updateIn = CONFIG.getUpdateInterval();
                    }
                    ctx.getSource().sendSuccess(
                            () -> Component.literal("Reloaded OpenPaC BlueMap config").withStyle(ChatFormatting.GREEN),
                        true
                    );
                    return Command.SINGLE_SUCCESS;
                })
            )
        );
    }
    
    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        if (updateIn <= 0) return;
        if (--updateIn <= 0) {
            BlueMapAPI.getInstance().ifPresent(OpacBluemapIntegration::updateClaims);
        }
    }

    public static void loadConfig() {
        try {
            CONFIG.loadFromFile(CONFIG_FILE);
        } catch (Exception e) {
            LOGGER.warn("Failed to read {}.", CONFIG_FILE, e);
        }
        saveConfig();
    }

    public static void saveConfig() {
        try {
            CONFIG.saveToFile(CONFIG_FILE);
            LOGGER.info("Saved OpenPaC BlueMap config");
        } catch (Exception e) {
            LOGGER.error("Failed to write {}.", CONFIG_FILE, e);
        }
    }

    public static void updateClaims(BlueMapAPI blueMap) {
        if (minecraftServer == null) {
            LOGGER.warn("updateClaims called with minecraftServer == null!");
            return;
        }
        
        // No debouncing - let updates happen immediately
        
        LOGGER.debug("Refreshing OpenPaC BlueMap markers");
        
        // Clear all existing claim markers first (handles unclaimed areas properly)
        blueMap.getWorlds().forEach(world -> {
            world.getMaps().forEach(map -> {
                map.getMarkerSets().computeIfPresent(MARKER_SET_KEY, (key, markerSet) -> {
                    markerSet.getMarkers().clear();
                    return markerSet;
                });
            });
        });
        
        // Then add current claims
        OpenPACServerAPI.get(minecraftServer)
            .getServerClaimsManager()
            .getPlayerInfoStream()
            .forEach(playerClaimInfo -> {
                // Get both UUID for marker ID and name for display
                final UUID playerId = playerClaimInfo.getPlayerId();
                String name = playerClaimInfo.getClaimsName();
                if (StringUtils.isBlank(name)) {
                    name = playerClaimInfo.getPlayerUsername();
                    if (name.length() > 2 && name.charAt(0) == '"' && name.charAt(name.length() - 1) == '"') {
                        name = name.substring(1, name.length() - 1) + " claim";
                    } else {
                        name += "'s claim";
                    }
                }
                final String displayName = name;
                playerClaimInfo.getStream().forEach(entry -> {
                    final BlueMapWorld world = blueMap.getWorld(ResourceKey.create(Registries.DIMENSION, entry.getKey())).orElse(null);                    if (world == null) return;
                    final List<ShapeHolder> shapes = createShapes(
                        entry.getValue()
                            .getStream()
                            .flatMap(IPlayerClaimPosListAPI::getStream)
                            .collect(Collectors.toSet())
                    );
                    world.getMaps().forEach(map -> {
                        final Map<String, Marker> markers = map
                            .getMarkerSets()
                            .computeIfAbsent(MARKER_SET_KEY, k ->
                                MarkerSet.builder()
                                    .toggleable(true)
                                    .label("Open Parties and Claims")
                                    .build()
                            )
                            .getMarkers();
                        final float minY = CONFIG.getMarkerMinY();
                        final float maxY = CONFIG.getMarkerMaxY();
                        //noinspection SuspiciousNameCombination
                        final boolean flatPlane = Mth.equal(minY, maxY);
                        // No need to remove individual markers since we cleared all above
                        for (int i = 0; i < shapes.size(); i++) {
                            final ShapeHolder shape = shapes.get(i);
                            markers.put(playerId + "---" + i,
                                // Yes these builders are the same. No they don't share a superclass (except for label).
                                flatPlane
                                    ? ShapeMarker.builder()
                                        .label(displayName)
                                        .fillColor(new Color(playerClaimInfo.getClaimsColor(), 102))
                                        .lineColor(new Color(playerClaimInfo.getClaimsColor(), 255))
                                        .shape(shape.baseShape(), minY)
                                        .holes(shape.holes())
                                        .depthTestEnabled(CONFIG.isDepthTest())
                                        .build()
                                    : ExtrudeMarker.builder()
                                        .label(displayName)
                                        .fillColor(new Color(playerClaimInfo.getClaimsColor(), 102))
                                        .lineColor(new Color(playerClaimInfo.getClaimsColor(), 255))
                                        .shape(shape.baseShape(), minY, maxY)
                                        .holes(shape.holes())
                                        .depthTestEnabled(CONFIG.isDepthTest())
                                        .build()
                            );
                        }
                    });
                });
            });
        LOGGER.debug("Refreshed OpenPaC BlueMap markers");
        updateIn = CONFIG.getUpdateInterval();
    }

    public static void updateSpecificPlayer(BlueMapAPI blueMap, UUID playerId) {
        if (minecraftServer == null) {
            LOGGER.warn("updateSpecificPlayer called with minecraftServer == null!");
            return;
        }
        
        LOGGER.debug("Refreshing BlueMap markers for specific player: {}", playerId);
        
        // TRUST OUR CACHE - Check cache first, it's immediately consistent with events
        Set<ChunkPos> cachedChunks = chunkToPlayerCache.entrySet()
            .stream()
            .filter(entry -> entry.getValue().equals(playerId))
            .map(Map.Entry::getKey)
            .collect(Collectors.toSet());
        
        if (cachedChunks.isEmpty()) {
            // Cache shows player has no chunks → TRUST IT, remove all markers immediately
            LOGGER.debug("Cache shows player {} has no chunks, removing all markers immediately", playerId);
            blueMap.getWorlds().forEach(world -> {
                world.getMaps().forEach(map -> {
                    final Map<String, Marker> markers = map
                        .getMarkerSets()
                        .computeIfAbsent(MARKER_SET_KEY, k ->
                            MarkerSet.builder()
                                .toggleable(true)
                                .label("Open Parties and Claims")
                                .build()
                        )
                        .getMarkers();
                    
                    // Remove all markers for this player using UUID
                    int removedCount = (int) markers.keySet().stream()
                        .filter(k -> k.startsWith(playerId + "---"))
                        .count();
                    markers.keySet().removeIf(k -> k.startsWith(playerId + "---"));
                    LOGGER.debug("Removed {} markers for player {} (cache-based)", removedCount, playerId);
                });
            });
            return; // Exit early, no need to query OpenPAC API
        }
        
        // Cache has chunks → query OpenPAC API for colors/names and rebuild
        LOGGER.debug("Cache shows player {} has {} chunks, querying OpenPAC for details", playerId, cachedChunks.size());
        OpenPACServerAPI.get(minecraftServer)
            .getServerClaimsManager()
            .getPlayerInfoStream()
            .filter(playerInfo -> {
                // Match by UUID - now we know getPlayerId() exists and returns UUID
                return Objects.equals(playerInfo.getPlayerId(), playerId);
            })
            .findFirst()
            .ifPresentOrElse(playerClaimInfo -> {
                String name = playerClaimInfo.getClaimsName();
                final String idName;
                if (StringUtils.isBlank(name)) {
                    idName = name = playerClaimInfo.getPlayerUsername();
                    if (name.length() > 2 && name.charAt(0) == '"' && name.charAt(name.length() - 1) == '"') {
                        name = name.substring(1, name.length() - 1) + " claim";
                    } else {
                        name += "'s claim";
                    }
                } else {
                    idName = name;
                }
                final String displayName = name;
                
                playerClaimInfo.getStream().forEach(entry -> {
                    final BlueMapWorld world = blueMap.getWorld(ResourceKey.create(Registries.DIMENSION, entry.getKey())).orElse(null);
                    if (world == null) return;
                    
                    final List<ShapeHolder> shapes = createShapes(
                        entry.getValue()
                            .getStream()
                            .flatMap(IPlayerClaimPosListAPI::getStream)
                            .collect(Collectors.toSet())
                    );
                    
                    world.getMaps().forEach(map -> {
                        final Map<String, Marker> markers = map
                            .getMarkerSets()
                            .computeIfAbsent(MARKER_SET_KEY, k ->
                                MarkerSet.builder()
                                    .toggleable(true)
                                    .label("Open Parties and Claims")
                                    .build()
                            )
                            .getMarkers();
                        
                        final float minY = CONFIG.getMarkerMinY();
                        final float maxY = CONFIG.getMarkerMaxY();
                        //noinspection SuspiciousNameCombination
                        final boolean flatPlane = Mth.equal(minY, maxY);
                        
                        // Remove existing markers for this specific player (using UUID)
                        markers.keySet().removeIf(k -> k.startsWith(playerId + "---"));
                        
                        // Add new markers for this player (using UUID for ID, displayName for label)
                        for (int i = 0; i < shapes.size(); i++) {
                            final ShapeHolder shape = shapes.get(i);
                            markers.put(playerId + "---" + i,
                                flatPlane
                                    ? ShapeMarker.builder()
                                        .label(displayName)
                                        .fillColor(new Color(playerClaimInfo.getClaimsColor(), 102))
                                        .lineColor(new Color(playerClaimInfo.getClaimsColor(), 255))
                                        .shape(shape.baseShape(), minY)
                                        .holes(shape.holes())
                                        .depthTestEnabled(CONFIG.isDepthTest())
                                        .build()
                                    : ExtrudeMarker.builder()
                                        .label(displayName)
                                        .fillColor(new Color(playerClaimInfo.getClaimsColor(), 102))
                                        .lineColor(new Color(playerClaimInfo.getClaimsColor(), 255))
                                        .shape(shape.baseShape(), minY, maxY)
                                        .holes(shape.holes())
                                        .depthTestEnabled(CONFIG.isDepthTest())
                                        .build()
                            );
                        }
                    });
                });
            }, () -> {
                // This shouldn't happen since we check cache first, but log if it does
                LOGGER.warn("OpenPAC API says player {} has no claims, but our cache had chunks - possible sync issue", playerId);
            });
        
        LOGGER.debug("Finished processing BlueMap markers for player: {}", playerId);
    }

    public static List<ShapeHolder> createShapes(Set<ChunkPos> chunks) {
        return createChunkGroups(chunks)
            .stream()
            .map(ShapeHolder::create)
            .toList();
    }

    public static List<Set<ChunkPos>> createChunkGroups(Set<ChunkPos> chunks) {
        final List<Set<ChunkPos>> result = new ArrayList<>();
        final Set<ChunkPos> visited = new HashSet<>();
        for (final ChunkPos chunk : chunks) {
            if (visited.contains(chunk)) continue;
            final Set<ChunkPos> neighbors = findNeighbors(chunk, chunks);
            result.add(neighbors);
            visited.addAll(neighbors);
        }
        return result;
    }

    public static Set<ChunkPos> findNeighbors(ChunkPos chunk, Set<ChunkPos> chunks) {
        if (!chunks.contains(chunk)) {
            throw new IllegalArgumentException("chunks must contain chunk to find neighbors!");
        }
        final Set<ChunkPos> visited = new HashSet<>();
        final Queue<ChunkPos> toVisit = new ArrayDeque<>();
        visited.add(chunk);
        toVisit.add(chunk);
        while (!toVisit.isEmpty()) {
            final ChunkPos visiting = toVisit.remove();
            for (final ChunkPosDirection dir : ChunkPosDirection.values()) {
                final ChunkPos offsetPos = dir.add(visiting);
                if (!chunks.contains(offsetPos) || !visited.add(offsetPos)) continue;
                toVisit.add(offsetPos);
            }
        }
        return visited;
    }

    // Implementation of IClaimsManagerListenerAPI
    @Override
    public void onWholeRegionChange(net.minecraft.resources.ResourceLocation dimension, int regionX, int regionZ) {
        // Just log the event - let onChunkChange handle the actual updates
        LOGGER.debug("OpenPAC region change event: dimension={}, regionX={}, regionZ={}", dimension, regionX, regionZ);
    }

    @Override
    public void onChunkChange(net.minecraft.resources.ResourceLocation dimension, int chunkX, int chunkZ, IPlayerChunkClaimAPI claim) {
        ChunkPos chunkPos = new ChunkPos(chunkX, chunkZ);
        
        if (claim != null) {
            // Chunk was claimed - update the cache and mark player for update
            UUID playerId = claim.getPlayerId();
            chunkToPlayerCache.put(chunkPos, playerId);
            playersNeedingUpdate.add(playerId);
            
            LOGGER.debug("Chunk claimed: {} at ({}, {}) by player {}", dimension, chunkX, chunkZ, playerId);
        } else {
            // Chunk was unclaimed - find previous owner and mark for update
            UUID previousOwner = chunkToPlayerCache.remove(chunkPos);
            if (previousOwner != null) {
                playersNeedingUpdate.add(previousOwner);
                LOGGER.debug("Chunk unclaimed: {} at ({}, {}) previously owned by {}", dimension, chunkX, chunkZ, previousOwner);
            }
        }
        
        // Trigger incremental update for affected players
        if (!playersNeedingUpdate.isEmpty()) {
            BlueMapAPI.getInstance().ifPresent(blueMap -> {
                // Process all players needing updates
                Set<UUID> toUpdate = new HashSet<>(playersNeedingUpdate);
                playersNeedingUpdate.clear();
                
                toUpdate.forEach(playerId -> updateSpecificPlayer(blueMap, playerId));
            });
        }
    }

    @Override
    public void onDimensionChange(net.minecraft.resources.ResourceLocation dimension) {
        // Just log the event - let onChunkChange handle the actual updates
        LOGGER.debug("OpenPAC dimension change event: dimension={}", dimension);
    }
}
