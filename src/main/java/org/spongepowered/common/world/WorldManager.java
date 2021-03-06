/*
 * This file is part of Sponge, licensed under the MIT License (MIT).
 *
 * Copyright (c) SpongePowered <https://www.spongepowered.org>
 * Copyright (c) contributors
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package org.spongepowered.common.world;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.common.base.Throwables;
import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.MapMaker;
import it.unimi.dsi.fastutil.ints.Int2ObjectMap;
import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.objects.ObjectIterator;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.CompressedStreamTools;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.datafix.FixTypes;
import net.minecraft.world.DimensionType;
import net.minecraft.world.GameType;
import net.minecraft.world.MinecraftException;
import net.minecraft.world.ServerWorldEventHandler;
import net.minecraft.world.WorldProvider;
import net.minecraft.world.WorldServer;
import net.minecraft.world.WorldServerMulti;
import net.minecraft.world.WorldSettings;
import net.minecraft.world.WorldType;
import net.minecraft.world.chunk.storage.AnvilSaveHandler;
import net.minecraft.world.storage.ISaveHandler;
import net.minecraft.world.storage.WorldInfo;
import org.apache.commons.io.FileUtils;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.event.SpongeEventFactory;
import org.spongepowered.api.event.cause.Cause;
import org.spongepowered.api.event.cause.NamedCause;
import org.spongepowered.api.world.DimensionTypes;
import org.spongepowered.api.world.WorldArchetype;
import org.spongepowered.api.world.storage.WorldProperties;
import org.spongepowered.common.SpongeImpl;
import org.spongepowered.common.SpongeImplHooks;
import org.spongepowered.common.config.SpongeConfig;
import org.spongepowered.common.data.util.DataUtil;
import org.spongepowered.common.data.util.NbtDataUtil;
import org.spongepowered.common.interfaces.IMixinIntegratedServer;
import org.spongepowered.common.interfaces.IMixinMinecraftServer;
import org.spongepowered.common.interfaces.world.IMixinWorldInfo;
import org.spongepowered.common.interfaces.world.IMixinWorldServer;
import org.spongepowered.common.interfaces.world.IMixinWorldSettings;
import org.spongepowered.common.scheduler.SpongeScheduler;
import org.spongepowered.common.util.SpongeHooks;
import org.spongepowered.common.world.storage.WorldServerMultiAdapterWorldInfo;

import java.io.DataInputStream;
import java.io.File;
import java.io.FileFilter;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import javax.annotation.Nullable;

public final class WorldManager {

    public static final DirectoryStream.Filter<Path> LEVEL_AND_SPONGE =
            entry -> Files.isDirectory(entry) && Files.exists(entry.resolve("level.dat")) && Files.exists(entry.resolve("level_sponge.dat"));

    private static final Int2ObjectMap<DimensionType> dimensionTypeByTypeId = new Int2ObjectOpenHashMap<>(3);
    private static final Int2ObjectMap<DimensionType> dimensionTypeByDimensionId = new Int2ObjectOpenHashMap<>(3);
    private static final IntSet unregisterableDimensions = new IntOpenHashSet(3);
    private static final Int2ObjectMap<Path> dimensionPathByDimensionId = new Int2ObjectOpenHashMap<>(3);
    private static final Int2ObjectOpenHashMap<WorldServer> worldByDimensionId = new Int2ObjectOpenHashMap<>(3);
    private static final Map<String, WorldProperties> worldPropertiesByFolderName = new HashMap<>(3);
    private static final Map<UUID, WorldProperties> worldPropertiesByWorldUuid =  new HashMap<>(3);
    private static final BiMap<String, UUID> worldUuidByFolderName =  HashBiMap.create(3);
    private static final BitSet dimensionBits = new BitSet(Long.SIZE << 4);
    private static final Map<WorldServer, WorldServer> weakWorldByWorld = new MapMaker().weakKeys().weakValues().concurrencyLevel(1).makeMap();
    private static final Queue<WorldServer> unloadQueue = new ArrayDeque<>();
    private static final Comparator<WorldServer>
            WORLD_SERVER_COMPARATOR =
            (world1, world2) -> {
                final Integer world1DimId = ((IMixinWorldServer) world1).getDimensionId();

                if (world2 == null) {
                    return world1DimId;
                }

                final Integer world2DimId = ((IMixinWorldServer) world2).getDimensionId();
                return world1DimId - world2DimId;
            };
            
    private static boolean isVanillaRegistered = false;
            
    static {
        WorldManager.registerVanillaTypesAndDimensions();
    }

    public static void registerVanillaTypesAndDimensions() {
        if (!isVanillaRegistered) {
            WorldManager.registerDimensionType(0, DimensionType.OVERWORLD);
            WorldManager.registerDimensionType(-1, DimensionType.NETHER);
            WorldManager.registerDimensionType(1, DimensionType.THE_END);

            WorldManager.registerDimension(0, DimensionType.OVERWORLD, false);
            WorldManager.registerDimension(-1, DimensionType.NETHER, false);
            WorldManager.registerDimension(1, DimensionType.THE_END, false);
        }

        isVanillaRegistered = true;
    }

    public static boolean registerDimensionType(DimensionType type) {
        checkNotNull(type);
        final Optional<Integer> optNextDimensionTypeId = getNextFreeDimensionTypeId();
        return optNextDimensionTypeId.isPresent() && registerDimensionType(optNextDimensionTypeId.get(), type);

    }

    public static boolean registerDimensionType(int dimensionTypeId, DimensionType type) {
        checkNotNull(type);
        if (dimensionTypeByTypeId.containsKey(dimensionTypeId)) {
            return false;
        }

        dimensionTypeByTypeId.put(dimensionTypeId, type);
        return true;
    }

    private static Optional<Integer> getNextFreeDimensionTypeId() {
        Integer highestDimensionTypeId = null;

        for (Integer dimensionTypeId : dimensionTypeByTypeId.keySet()) {
            if (highestDimensionTypeId == null || highestDimensionTypeId < dimensionTypeId) {
                highestDimensionTypeId = dimensionTypeId;
            }
        }

        if (highestDimensionTypeId != null && highestDimensionTypeId < 127) {
            return Optional.of(++highestDimensionTypeId);
        }
        return Optional.empty();
    }

    public static Integer getNextFreeDimensionId() {
        return dimensionBits.nextClearBit(0);
    }

    public static boolean registerDimension(int dimensionId, DimensionType type, boolean canBeUnregistered) {
        checkNotNull(type);
        if (!dimensionTypeByTypeId.containsValue(type)) {
            return false;
        }

        if (dimensionTypeByDimensionId.containsKey(dimensionId)) {
            return false;
        }
        dimensionTypeByDimensionId.put(dimensionId, type);
        if (dimensionId >= 0) {
            dimensionBits.set(dimensionId);
        }
        if (canBeUnregistered) {
            unregisterableDimensions.add(dimensionId);
        }
        return true;
    }

    public static void unregisterDimension(int dimensionId) {
        if (!dimensionTypeByDimensionId.containsKey(dimensionId))
        {
            throw new IllegalArgumentException("Failed to unregister dimension [" + dimensionId + "] as it is not registered!");
        }
        dimensionTypeByDimensionId.remove(dimensionId);
    }

    public static void registerVanillaDimensionPaths(final Path savePath) {
        WorldManager.registerDimensionPath(0, savePath);
        WorldManager.registerDimensionPath(-1, savePath.resolve("DIM-1"));
        WorldManager.registerDimensionPath(1, savePath.resolve("DIM1"));
    }

    public static void registerDimensionPath(int dimensionId, Path dimensionDataRoot) {
        checkNotNull(dimensionDataRoot);
        dimensionPathByDimensionId.put(dimensionId, dimensionDataRoot);
    }

    public static Optional<DimensionType> getDimensionType(int dimensionId) {
        return Optional.ofNullable(dimensionTypeByDimensionId.get(dimensionId));
    }

    public static Optional<DimensionType> getDimensionType(Class<? extends WorldProvider> providerClass) {
        checkNotNull(providerClass);
        for (Object rawDimensionType : dimensionTypeByTypeId.values()) {
            final DimensionType dimensionType = (DimensionType) rawDimensionType;
            if (((org.spongepowered.api.world.DimensionType) (Object) dimensionType).getDimensionClass().equals(providerClass)) {
                return Optional.of(dimensionType);
            }
        }

        return Optional.empty();
    }

    public static Collection<DimensionType> getDimensionTypes() {
        return dimensionTypeByTypeId.values();
    }

    public static Integer[] getRegisteredDimensionIdsFor(DimensionType type) {
        return (Integer[]) dimensionTypeByDimensionId.entrySet().stream().filter(entry -> entry.getValue().equals(type))
                .map(Map.Entry::getKey).collect(Collectors.toList()).toArray();
    }

    public static int[] getRegisteredDimensionIds() {
        return dimensionTypeByTypeId.keySet().toIntArray();
    }

    public static Optional<Path> getWorldFolder(int dimensionId) {
        return Optional.ofNullable(dimensionPathByDimensionId.get(dimensionId));
    }

    public static boolean isDimensionRegistered(int dimensionId) {
        return dimensionTypeByDimensionId.containsKey(dimensionId);
    }

    public static Map<Integer, DimensionType> sortedDimensionMap() {
        Int2ObjectMap<DimensionType> copy = new Int2ObjectOpenHashMap<>(dimensionTypeByDimensionId);

        HashMap<Integer, DimensionType> newMap = new LinkedHashMap<>();

        newMap.put(0, copy.remove(0));
        newMap.put(-1, copy.remove(-1));
        newMap.put(1, copy.remove(1));

        int[] ids = copy.keySet().toIntArray();
        Arrays.sort(ids);

        for (int id : ids) {
            newMap.put(id, copy.get(id));
        }

        return newMap;
    }

    public static ObjectIterator<Int2ObjectMap.Entry<WorldServer>> worldsIterator() {
        return worldByDimensionId.int2ObjectEntrySet().fastIterator();
    }

    public static Collection<WorldServer> getWorlds() {
        return worldByDimensionId.values();
    }

    public static Optional<WorldServer> getWorldByDimensionId(int dimensionId) {
        return Optional.ofNullable(worldByDimensionId.get(dimensionId));
    }

    public static int[] getLoadedWorldDimensionIds() {
        return worldByDimensionId.keySet().toIntArray();
    }

    public static Optional<WorldServer> getWorld(String worldName) {
        for (WorldServer worldServer : getWorlds()) {
            final org.spongepowered.api.world.World apiWorld = (org.spongepowered.api.world.World) worldServer;
            if (apiWorld.getName().equals(worldName)) {
                return Optional.of(worldServer);
            }
        }
        return Optional.empty();
    }

    public static void registerWorldProperties(WorldProperties properties) {
        checkNotNull(properties);
        worldPropertiesByFolderName.put(properties.getWorldName(), properties);
        worldPropertiesByWorldUuid.put(properties.getUniqueId(), properties);
        worldUuidByFolderName.put(properties.getWorldName(), properties.getUniqueId());
    }

    public static void unregisterWorldProperties(WorldProperties properties, boolean freeDimensionId) {
        checkNotNull(properties);
        worldPropertiesByFolderName.remove(properties.getWorldName());
        worldPropertiesByWorldUuid.remove(properties.getUniqueId());
        worldUuidByFolderName.remove(properties.getWorldName());
        if (((IMixinWorldInfo) properties).getDimensionId() != null && freeDimensionId) {
            dimensionBits.clear(((IMixinWorldInfo) properties).getDimensionId());
        }
    }

    public static Optional<WorldProperties> getWorldProperties(String folderName) {
        checkNotNull(folderName);
        return Optional.ofNullable(worldPropertiesByFolderName.get(folderName));
    }

    public static Collection<WorldProperties> getAllWorldProperties() {
        return Collections.unmodifiableCollection(worldPropertiesByFolderName.values());
    }

    public static Optional<WorldProperties> getWorldProperties(UUID uuid) {
        checkNotNull(uuid);
        return Optional.ofNullable(worldPropertiesByWorldUuid.get(uuid));
    }

    public static Optional<UUID> getUuidForFolder(String folderName) {
        checkNotNull(folderName);
        return Optional.ofNullable(worldUuidByFolderName.get(folderName));
    }

    public static Optional<String> getFolderForUuid(UUID uuid) {
        checkNotNull(uuid);
        return Optional.ofNullable(worldUuidByFolderName.inverse().get(uuid));
    }

    public static WorldProperties createWorldProperties(String folderName, WorldArchetype archetype) {
        checkNotNull(folderName);
        checkNotNull(archetype);
        final Optional<WorldServer> optWorldServer = getWorld(folderName);
        if (optWorldServer.isPresent()) {
            return ((org.spongepowered.api.world.World) optWorldServer.get()).getProperties();
        }

        final Optional<WorldProperties> optWorldProperties = WorldManager.getWorldProperties(folderName);

        if (optWorldProperties.isPresent()) {
            return optWorldProperties.get();
        }

        final ISaveHandler saveHandler = new AnvilSaveHandler(WorldManager.getCurrentSavesDirectory().get().toFile(), folderName, true, SpongeImpl
                .getServer().getDataFixer());
        WorldInfo worldInfo = saveHandler.loadWorldInfo();

        if (worldInfo == null) {
            worldInfo = new WorldInfo((WorldSettings) (Object) archetype, folderName);
        } else {
            ((IMixinWorldInfo) worldInfo).createWorldConfig();
            ((WorldProperties) worldInfo).setGeneratorModifiers(archetype.getGeneratorModifiers());
        }

        setUuidOnProperties(getCurrentSavesDirectory().get(), (WorldProperties) worldInfo);
        ((IMixinWorldInfo) worldInfo).setDimensionId(Integer.MIN_VALUE);
        ((IMixinWorldInfo) worldInfo).setDimensionType(archetype.getDimensionType());
        ((WorldProperties) worldInfo).setGeneratorType(archetype.getGeneratorType());
        ((IMixinWorldInfo) worldInfo).getWorldConfig().save();
        registerWorldProperties((WorldProperties) worldInfo);

        SpongeImpl.postEvent(SpongeEventFactory.createConstructWorldPropertiesEvent(Cause.of(NamedCause.source(Sponge.getServer())), archetype,
                (WorldProperties) worldInfo));

        saveHandler.saveWorldInfoWithPlayer(worldInfo, SpongeImpl.getServer().getPlayerList().getHostPlayerData());

        return (WorldProperties) worldInfo;

    }

    public static boolean saveWorldProperties(WorldProperties properties) {
        checkNotNull(properties);
        final Optional<WorldServer> optWorldServer = getWorldByDimensionId(((IMixinWorldInfo) properties).getDimensionId());
        // If the World represented in the properties is still loaded, save the properties and have the World reload its info
        if (optWorldServer.isPresent()) {
            final WorldServer worldServer = optWorldServer.get();
            worldServer.getSaveHandler().saveWorldInfo((WorldInfo) properties);
            worldServer.getSaveHandler().loadWorldInfo();
        } else {
            new AnvilSaveHandler(WorldManager.getCurrentSavesDirectory().get().toFile(), properties.getWorldName(), true, SpongeImpl.getServer()
                    .getDataFixer()).saveWorldInfo((WorldInfo) properties);
        }
        // No return values or exceptions so can only assume true.
        return true;
    }

    public static void unloadQueuedWorlds() {
        while (unloadQueue.peek() != null) {
            unloadWorld(unloadQueue.poll(), false, true, true, false);
        }

        unloadQueue.clear();
    }

    public static void queueWorldToUnload(WorldServer worldServer) {
        checkNotNull(worldServer);
        unloadQueue.add(worldServer);
    }

    // TODO Result
    public static boolean unloadWorld(WorldServer worldServer, boolean checkConfig, boolean save, boolean throwEvent, boolean force) {
        checkNotNull(worldServer);
        final MinecraftServer server = SpongeImpl.getServer();

        // Likely leaked, don't want to drop leaked world data
        if (!worldByDimensionId.containsValue(worldServer)) {
            return false;
        }

        // Force is only true if we're stopping server. Vanilla sometimes doesn't remove player entities from world first
        if (!force) {
            if (!worldServer.playerEntities.isEmpty()) {
                return false;
            }

            // We only check config if base game wants to unload world. If mods/plugins say unload, we unload
            if (checkConfig) {
                if (((IMixinWorldServer) worldServer).getActiveConfig().getConfig().getWorld().getKeepSpawnLoaded()) {
                    return false;
                }
            }
        }

        final IMixinWorldServer mixinWorldServer = (IMixinWorldServer) worldServer;
        final int dimensionId = mixinWorldServer.getDimensionId();

        try {
            // We do not perform a save when stopping server as that happens before the actual unload call
            if (save) {
                saveWorld(worldServer, true);
            }
        } catch (MinecraftException e) {
            e.printStackTrace();
        } finally {
            mixinWorldServer.getActiveConfig().save();
            worldByDimensionId.remove(dimensionId);
            weakWorldByWorld.remove(worldServer);
            ((IMixinMinecraftServer) server).getWorldTickTimes().remove(dimensionId);
            SpongeImpl.getLogger().info("Unloading world [{}] (DIM{})", worldServer.getWorldInfo().getWorldName(), dimensionId);
            reorderWorldsVanillaFirst();
        }

        if (throwEvent) {
            SpongeImpl.postEvent(SpongeEventFactory.createUnloadWorldEvent(Cause.of(NamedCause.source(server)), (org.spongepowered.api.world.World)
                    worldServer));
        }

        if (force && unregisterableDimensions.contains(dimensionId)) {
            unregisterDimension(dimensionId);
        }

        return true;
    }

    public static void saveWorld(WorldServer worldServer, boolean flush) throws MinecraftException {
        final MinecraftServer server = SpongeImpl.getServer();
        final org.spongepowered.api.world.World apiWorld = (org.spongepowered.api.world.World) worldServer;

        Sponge.getEventManager().post(SpongeEventFactory.createSaveWorldEventPre(Cause.of(NamedCause.source(server)), apiWorld));

        worldServer.saveAllChunks(true, null);
        if (flush) {
            worldServer.flush();
        }

        Sponge.getEventManager().post(SpongeEventFactory.createSaveWorldEventPost(Cause.of(NamedCause.source(server)), apiWorld));
    }

    public static Collection<WorldProperties> getUnloadedWorlds() throws IOException {
        final Optional<Path> optCurrentSavesDir = getCurrentSavesDirectory();
        checkState(optCurrentSavesDir.isPresent(), "Attempt made to get unloaded worlds too early!");

        final List<WorldProperties> worlds = new ArrayList<>();
        try (final DirectoryStream<Path> stream = Files.newDirectoryStream(optCurrentSavesDir.get(), LEVEL_AND_SPONGE)) {
            for (Path worldFolder : stream) {
                final String worldFolderName = worldFolder.getFileName().toString();
                final WorldInfo worldInfo = new AnvilSaveHandler(WorldManager.getCurrentSavesDirectory().get().toFile(), worldFolderName, true,
                        SpongeImpl.getServer().getDataFixer()).loadWorldInfo();
                if (worldInfo != null) {
                    worlds.add((WorldProperties) worldInfo);
                }
            }
        }
        return worlds;
    }

    public static Optional<WorldServer> loadWorld(UUID uuid) {
        checkNotNull(uuid);
        // If someone tries to load loaded world, return it
        Sponge.getServer().getWorld(uuid).ifPresent(Optional::of);
        // Check if we even know of this UUID's folder
        final String worldFolder = worldUuidByFolderName.inverse().get(uuid);
        // We don't know of this UUID at all. TODO Search files?
        if (worldFolder == null) {
            return Optional.empty();
        }
        return loadWorld(worldFolder, null, null);
    }

    public static Optional<WorldServer> loadWorld(String worldName) {
        checkNotNull(worldName);
        return loadWorld(worldName, null, null);
    }

    public static Optional<WorldServer> loadWorld(WorldProperties properties) {
        checkNotNull(properties);
        return loadWorld(properties.getWorldName(), null, properties);
    }

    private static Optional<WorldServer> loadWorld(String worldName, @Nullable ISaveHandler saveHandler, @Nullable WorldProperties properties) {
        checkNotNull(worldName);
        final Path currentSavesDir = WorldManager.getCurrentSavesDirectory().orElseThrow(() -> new IllegalStateException("Attempt "
                + "made to load world too early!"));
        final MinecraftServer server = SpongeImpl.getServer();
        final Optional<WorldServer> optExistingWorldServer = getWorld(worldName);
        if (optExistingWorldServer.isPresent()) {
            return optExistingWorldServer;
        }

        if (!server.getAllowNether()) {
            SpongeImpl.getLogger().error("Unable to load world [{}]. Multi-world is disabled via [allow-nether] in [server.properties].", worldName);
            return Optional.empty();
        }

        final Path worldFolder = currentSavesDir.resolve(worldName);
        if (!Files.isDirectory(worldFolder)) {
            SpongeImpl.getLogger().error("Unable to load world [{}]. We cannot find its folder under [{}].", worldFolder, currentSavesDir);
            return Optional.empty();
        }

        if (saveHandler == null) {
            saveHandler = new AnvilSaveHandler(currentSavesDir.toFile(), worldName, true, SpongeImpl.getServer()
                    .getDataFixer());
        }

        // We weren't given a properties, see if one is cached
        if (properties == null) {
            properties = (WorldProperties) saveHandler.loadWorldInfo();

            // We tried :'(
            if (properties == null) {
                SpongeImpl.getLogger().error("Unable to load world [{}]. No world properties was found!", worldName);
                return Optional.empty();
            }

            if (((IMixinWorldInfo) properties).getDimensionId() == null || ((IMixinWorldInfo) properties).getDimensionId() == Integer.MIN_VALUE) {
                ((IMixinWorldInfo) properties).setDimensionId(getNextFreeDimensionId());
            }

            registerWorldProperties(properties);

        } else {
            if (((IMixinWorldInfo) properties).getDimensionId() == null || ((IMixinWorldInfo) properties).getDimensionId() == Integer.MIN_VALUE) {
                ((IMixinWorldInfo) properties).setDimensionId(getNextFreeDimensionId());
            }
        }

        setUuidOnProperties(getCurrentSavesDirectory().get(), properties);

        final WorldInfo worldInfo = (WorldInfo) properties;
        ((IMixinWorldInfo) worldInfo).createWorldConfig();

        // check if enabled
        if (!((WorldProperties) worldInfo).isEnabled()) {
            SpongeImpl.getLogger().error("Unable to load world [{}]. It is disabled.", worldName);
            return Optional.empty();
        }

        final int dimensionId = ((IMixinWorldInfo) properties).getDimensionId();
        registerDimension(dimensionId, (DimensionType) (Object) properties.getDimensionType(), true);
        registerDimensionPath(dimensionId, worldFolder);
        SpongeImpl.getLogger().info("Loading world [{}] ({})", properties.getWorldName(), getDimensionType
                (dimensionId).get().getName());

        final WorldServer worldServer = createWorldFromProperties(dimensionId, saveHandler, (WorldInfo) properties, new WorldSettings((WorldInfo)
                        properties),
                properties.doesGenerateSpawnOnLoad());

        reorderWorldsVanillaFirst();
        return Optional.of(worldServer);
    }

    public static void loadAllWorlds(String worldName, long defaultSeed, WorldType defaultWorldType, String generatorOptions) {
        final MinecraftServer server = SpongeImpl.getServer();

        // We cannot call getCurrentSavesDirectory here as that would generate a savehandler and trigger a session lock.
        // We'll go ahead and make the directories for the save name here so that the migrator won't fail
        final Path currentSavesDir = server.anvilFile.toPath().resolve(server.getFolderName());
        try {
            // Symlink needs special handling
            if (Files.isSymbolicLink(currentSavesDir)) {
                final Path actualPathLink = Files.readSymbolicLink(currentSavesDir);
                if (Files.notExists(actualPathLink)) {
                    // TODO Need to test symlinking to see if this is even legal...
                    Files.createDirectories(actualPathLink);
                } else if (!Files.isDirectory(actualPathLink)) {
                    throw new IOException("Saves directory [" + currentSavesDir + "] symlinked to [" + actualPathLink + "] is not a directory!");
                }
            } else {
                Files.createDirectories(currentSavesDir);
            }
        } catch (IOException ioe) {
            throw new RuntimeException(ioe);
        }

        WorldManager.registerVanillaDimensionPaths(currentSavesDir);

        WorldMigrator.migrateWorldsTo(currentSavesDir);

        registerExistingSpongeDimensions(currentSavesDir);

        for (Map.Entry<Integer, DimensionType> entry: sortedDimensionMap().entrySet()) {

            final int dimensionId = entry.getKey();
            final DimensionType dimensionType = entry.getValue();

            // Skip all worlds besides dimension 0 if multi-world is disabled
            if (dimensionId != 0 && !server.getAllowNether()) {
                continue;
            }

            // Skip already loaded worlds by plugins
            if (getWorldByDimensionId(dimensionId).isPresent()) {
                continue;
            }

            // Step 1 - Grab the world's data folder
            final Optional<Path> optWorldFolder = getWorldFolder(dimensionId);
            if (!optWorldFolder.isPresent()) {
                SpongeImpl.getLogger().error("An attempt was made to load a world with dimension id [{}] that has no registered world folder!",
                        dimensionId);
                continue;
            }

            final Path worldFolder = optWorldFolder.get();
            final String worldFolderName = worldFolder.getFileName().toString();

            // Step 2 - See if we are allowed to load it
            if (dimensionId != 0) {
                final SpongeConfig<?> activeConfig = SpongeHooks.getActiveConfig(((org.spongepowered.api.world.DimensionType) (Object) dimensionType)
                        .getId(), worldFolderName);
                if (!activeConfig.getConfig().getWorld().isWorldEnabled()) {
                    SpongeImpl.getLogger().warn("World [{}] (DIM{}) is disabled. World will not be loaded...", worldFolder, dimensionId);
                    continue;
                }
            }

            // Step 3 - Get our world information from disk
            final ISaveHandler saveHandler;
            if (dimensionId == 0) {
                saveHandler = server.getActiveAnvilConverter().getSaveLoader(server.getFolderName(), true);
            } else {
                saveHandler = new AnvilSaveHandler(WorldManager.getCurrentSavesDirectory().get().toFile(), worldFolderName, true, server
                        .getDataFixer());
            }

            WorldInfo worldInfo = saveHandler.loadWorldInfo();
            WorldSettings worldSettings;

            // If this is integrated server, we need to use the WorldSettings from the client's Single Player menu to construct the worlds
            if (server instanceof IMixinIntegratedServer) {
                worldSettings = ((IMixinIntegratedServer) server).getSettings();

                // If this is overworld and a new save, the WorldInfo has already been made but we want to still fire the construct event.
                if (dimensionId == 0 && ((IMixinIntegratedServer) server).isNewSave()) {
                    SpongeImpl.postEvent(SpongeEventFactory.createConstructWorldPropertiesEvent(Cause.of(NamedCause.source(server)), (WorldArchetype)
                            (Object) worldSettings, (WorldProperties) worldInfo));
                }
            } else {
                // WorldSettings will be null here on dedicated server so we need to build one
                worldSettings = new WorldSettings(defaultSeed, server.getGameType(), server.canStructuresSpawn(), server.isHardcore(),
                        defaultWorldType);
            }

            if (worldInfo == null) {
                // Step 4 - At this point, we have either have the WorldInfo or we have none. If we have none, we'll use the settings built above to
                // create the WorldInfo
                worldInfo = createWorldInfoFromSettings(currentSavesDir, (org.spongepowered.api.world.DimensionType) (Object) dimensionType,
                        dimensionId, worldFolderName, worldSettings, generatorOptions);
            }

            final WorldProperties properties = (WorldProperties) worldInfo;

            // Safety check to ensure we'll get a unique id no matter what
            if (((WorldProperties) worldInfo).getUniqueId() == null) {
                setUuidOnProperties(dimensionId == 0 ? currentSavesDir.getParent() : currentSavesDir, (WorldProperties) worldInfo);
            }

            // Safety check to ensure the world info has the dimension id set
            if (((IMixinWorldInfo) worldInfo).getDimensionId() == null) {
                ((IMixinWorldInfo) worldInfo).setDimensionId(dimensionId);
            }

            // Step 5 - Load server resource pack from dimension 0
            if (dimensionId == 0) {
                server.setResourcePackFromWorld(worldFolderName, saveHandler);
            }

            // TODO Revise this silly configuration system
            ((IMixinWorldInfo) worldInfo).createWorldConfig();

            // Step 6 - Cache the WorldProperties we've made so we don't load from disk later.
            registerWorldProperties((WorldProperties) worldInfo);

            if (dimensionId != 0 && !((WorldProperties) worldInfo).loadOnStartup()) {
                SpongeImpl.getLogger().warn("World [{}] (DIM{}) is set to not load on startup. To load it later, enable [load-on-startup] in config "
                        + "or use a plugin", worldFolder, dimensionId);
                continue;
            }

            // Step 7 - Finally, we can create the world and tell it to load
            final WorldServer worldServer = createWorldFromProperties(dimensionId, saveHandler, worldInfo, worldSettings, false);

            SpongeImpl.getLogger().info("Loading world [{}] ({})", ((org.spongepowered.api.world.World) worldServer).getName(), getDimensionType
                    (dimensionId).get().getName());
        }

        reorderWorldsVanillaFirst();
    }
    
    public static WorldInfo createWorldInfoFromSettings(Path currentSaveRoot, org.spongepowered.api.world.DimensionType dimensionType, int
            dimensionId, String worldFolderName, WorldSettings worldSettings, String generatorOptions) {
        final MinecraftServer server = SpongeImpl.getServer();

        worldSettings.setGeneratorOptions(generatorOptions);

        ((IMixinWorldSettings) (Object) worldSettings).setDimensionType(dimensionType);

        final WorldInfo worldInfo = new WorldInfo(worldSettings, worldFolderName);
        setUuidOnProperties(dimensionId == 0 ? currentSaveRoot.getParent() : currentSaveRoot, (WorldProperties) worldInfo);
        ((IMixinWorldInfo) worldInfo).setDimensionId(dimensionId);
        SpongeImpl.postEvent(SpongeEventFactory.createConstructWorldPropertiesEvent(Cause.of(NamedCause.source(server)),
                (WorldArchetype) (Object) worldSettings, (WorldProperties) worldInfo));

        return worldInfo;

    }

    public static WorldServer createWorldFromProperties(int dimensionId, ISaveHandler saveHandler, WorldInfo worldInfo, @Nullable WorldSettings
            worldSettings, boolean prepareSpawn) {
        final MinecraftServer server = SpongeImpl.getServer();
        final WorldServer worldServer;
        if (dimensionId == 0) {
            worldServer = new WorldServer(server, saveHandler, worldInfo, dimensionId, server.theProfiler);
        } else {
            final WorldServerMultiAdapterWorldInfo info = new WorldServerMultiAdapterWorldInfo(saveHandler, worldInfo);
            worldServer = new WorldServerMulti(server, info, dimensionId, worldByDimensionId.get(0), server.theProfiler);
        }
        worldServer.init();

        // WorldSettings is only non-null here if this is a newly generated WorldInfo and therefore we need to initialize to calculate spawn.
        if (worldSettings != null) {
            worldServer.initialize(worldSettings);
        }

        worldServer.addEventListener(new ServerWorldEventHandler(server, worldServer));

        // This code changes from Mojang's to account for per-world API-set GameModes.
        if (!server.isSinglePlayer() && worldServer.getWorldInfo().getGameType().equals(GameType.NOT_SET)) {
            worldServer.getWorldInfo().setGameType(server.getGameType());
        }

        worldByDimensionId.put(dimensionId, worldServer);
        weakWorldByWorld.put(worldServer, worldServer);

        ((IMixinMinecraftServer) SpongeImpl.getServer()).getWorldTickTimes().put(dimensionId, new long[100]);

        SpongeImpl.postEvent(SpongeImplHooks.createLoadWorldEvent((org.spongepowered.api.world.World) worldServer));

        if (prepareSpawn) {
            ((IMixinMinecraftServer) server).prepareSpawnArea(worldServer);
        }

        // Ensure that config is initialized
        SpongeHooks.getActiveConfig(worldServer, true);

        return worldServer;
    }

    /**
     * Internal use only - Namely for SpongeForge.
     * @param dimensionId The world instance dimension id
     * @param worldServer The world server
     */
    public static void forceAddWorld(int dimensionId, WorldServer worldServer) {
        worldByDimensionId.put(dimensionId, worldServer);
        weakWorldByWorld.put(worldServer, worldServer);

        ((IMixinMinecraftServer) SpongeImpl.getServer()).getWorldTickTimes().put(dimensionId, new long[100]);
    }

    public static void reorderWorldsVanillaFirst() {
        final List<WorldServer> worldServers = new ArrayList<>(worldByDimensionId.values());
        final List<WorldServer> sorted = new LinkedList<>();

        int vanillaWorldsCount = 0;
        WorldServer worldServer = worldByDimensionId.get(0);

        if (worldServer != null) {
            sorted.add(worldServer);
            vanillaWorldsCount++;
        }

        worldServer = worldByDimensionId.get(-1);

        if (worldServer != null) {
            sorted.add(worldServer);
            vanillaWorldsCount++;
        }

        worldServer = worldByDimensionId.get(1);

        if (worldServer != null) {
            sorted.add(worldServer);
            vanillaWorldsCount++;
        }

        final List<WorldServer> nonVanillaWorlds = worldServers.subList(vanillaWorldsCount, worldServers.size());
        nonVanillaWorlds.sort(WORLD_SERVER_COMPARATOR);
        sorted.addAll(nonVanillaWorlds);
        SpongeImpl.getServer().worldServers = sorted.toArray(new WorldServer[sorted.size()]);
    }

    /**
     * Parses a {@link UUID} from disk from other known plugin platforms and sets it on the
     * {@link WorldProperties}. Currently only Bukkit is supported.
     */
    public static UUID setUuidOnProperties(Path savesRoot, WorldProperties properties) {
        checkNotNull(properties);

        UUID uuid;
        if (properties.getUniqueId() == null || properties.getUniqueId().equals
                (UUID.fromString("00000000-0000-0000-0000-000000000000"))) {
            // Check if Bukkit's uid.dat file is here and use it
            final Path uidPath = savesRoot.resolve(properties.getWorldName()).resolve("uid.dat");
            if (Files.notExists(uidPath)) {
                uuid = UUID.randomUUID();
            } else {
                try(final DataInputStream dis = new DataInputStream(Files.newInputStream(uidPath))) {
                    uuid = new UUID(dis.readLong(), dis.readLong());
                } catch (IOException e) {
                    SpongeImpl.getLogger().error("World folder [{}] has an existing Bukkit unique identifier for it but we encountered issues parsing "
                            + "the file. We will have to use a new unique id. Please report this to Sponge ASAP.", properties.getWorldName(), e);
                    uuid = UUID.randomUUID();
                }
            }
        } else {
            uuid = properties.getUniqueId();
        }

        ((IMixinWorldInfo) properties).setUniqueId(uuid);
        return uuid;
    }

    /**
     * Handles registering existing Sponge dimensions that are not the root dimension (known as overworld).
     */
    private static void registerExistingSpongeDimensions(Path rootPath) {
        try (final DirectoryStream<Path> stream = Files.newDirectoryStream(rootPath, LEVEL_AND_SPONGE)) {
            for (Path worldPath : stream) {
                final Path spongeLevelPath = worldPath.resolve("level_sponge.dat");
                final String worldFolderName = worldPath.getFileName().toString();

                NBTTagCompound compound;
                try {
                    compound = CompressedStreamTools.readCompressed(Files.newInputStream(spongeLevelPath));
                } catch (IOException e) {
                    SpongeImpl.getLogger().error("Failed loading Sponge data for World [{}]}. Report to Sponge ASAP.", worldFolderName, e);
                    continue;
                }

                NBTTagCompound spongeDataCompound = compound.getCompoundTag(NbtDataUtil.SPONGE_DATA);

                if (!compound.hasKey(NbtDataUtil.SPONGE_DATA)) {
                    SpongeImpl.getLogger()
                            .error("World [{}] has Sponge related data in the form of [level-sponge.dat] but the structure is not proper."
                                            + " Generally, the data is within a [{}] tag but it is not for this world. Report to Sponge ASAP.",
                                    worldFolderName, NbtDataUtil.SPONGE_DATA);
                    continue;
                }

                if (!spongeDataCompound.hasKey(NbtDataUtil.DIMENSION_ID)) {
                    SpongeImpl.getLogger().error("World [{}] has no dimension id. Report this to Sponge ASAP.", worldFolderName);
                    continue;
                }

                final int dimensionId = spongeDataCompound.getInteger(NbtDataUtil.DIMENSION_ID);

                // We do not handle Vanilla dimensions, skip them
                if (dimensionId == 0 || dimensionId == -1 || dimensionId == 1) {
                    continue;
                }

                spongeDataCompound = DataUtil.spongeDataFixer.process(FixTypes.LEVEL, spongeDataCompound);

                String dimensionTypeId = "overworld";

                if (spongeDataCompound.hasKey(NbtDataUtil.DIMENSION_TYPE)) {
                    dimensionTypeId = spongeDataCompound.getString(NbtDataUtil.DIMENSION_TYPE);
                } else {
                    SpongeImpl.getLogger().warn("World [{}] (DIM{}) has no specified dimension type. Defaulting to [{}}]...", worldFolderName,
                            dimensionId, DimensionTypes.OVERWORLD.getName());
                }

                final Optional<org.spongepowered.api.world.DimensionType> optDimensionType
                        = Sponge.getRegistry().getType(org.spongepowered.api.world.DimensionType.class, dimensionTypeId);
                if (!optDimensionType.isPresent()) {
                    SpongeImpl.getLogger().warn("World [{}] (DIM{}) has specified dimension type that is not registered. Defaulting to [{}]...",
                            worldFolderName, DimensionTypes.OVERWORLD.getName());
                }
                final DimensionType dimensionType = (DimensionType) (Object) optDimensionType.get();
                spongeDataCompound.setString(NbtDataUtil.DIMENSION_TYPE, dimensionTypeId);

                if (!spongeDataCompound.hasUniqueId(NbtDataUtil.UUID)) {
                    SpongeImpl.getLogger().error("World [{}] (DIM{}) has no valid unique identifier. This is a critical error and should be reported"
                            + " to Sponge ASAP.", worldFolderName, dimensionId);
                    continue;
                }

                if (isDimensionRegistered(dimensionId)) {
                    SpongeImpl.getLogger().error("World [{}] (DIM{}) has already been registered (likely by a mod). Going to print existing "
                            + "registration", worldFolderName, dimensionId);
                    continue;
                }

                registerDimension(dimensionId, dimensionType, true);
                registerDimensionPath(dimensionId, rootPath.resolve(worldFolderName));
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static CompletableFuture<Optional<WorldProperties>> copyWorld(WorldProperties worldProperties, String copyName) {
        checkArgument(!worldPropertiesByFolderName.containsKey(worldProperties.getWorldName()), "World properties not registered!");
        checkArgument(worldPropertiesByFolderName.containsKey(copyName), "Destination world name already is registered!");
        final WorldInfo info = (WorldInfo) worldProperties;

        final WorldServer worldServer = worldByDimensionId.get(((IMixinWorldInfo) info).getDimensionId().intValue());
        if (worldServer != null) {
            try {
                saveWorld(worldServer, true);
            } catch (MinecraftException e) {
                Throwables.propagate(e);
            }

            ((IMixinMinecraftServer) SpongeImpl.getServer()).setSaveEnabled(false);
        }

        final CompletableFuture<Optional<WorldProperties>> future = SpongeScheduler.getInstance().submitAsyncTask(new CopyWorldTask(info, copyName));
        if (worldServer != null) { // World was loaded
            future.thenRun(() -> ((IMixinMinecraftServer) SpongeImpl.getServer()).setSaveEnabled(true));
        }
        return future;
    }

    public static Optional<WorldProperties> renameWorld(WorldProperties worldProperties, String newName) {
        checkNotNull(worldProperties);
        checkNotNull(newName);
        checkState(worldByDimensionId.containsKey(((IMixinWorldInfo) worldProperties).getDimensionId()), "World is still loaded!");

        final Path oldWorldFolder = getCurrentSavesDirectory().get().resolve(worldProperties.getWorldName());
        final Path newWorldFolder = oldWorldFolder.resolveSibling(newName);
        if (Files.exists(newWorldFolder)) {
            return Optional.empty();
        }

        try {
            Files.move(oldWorldFolder, newWorldFolder);
        } catch (IOException e) {
            return Optional.empty();
        }

        unregisterWorldProperties(worldProperties, false);

        final WorldInfo info = new WorldInfo((WorldInfo) worldProperties);
        info.setWorldName(newName);
        ((IMixinWorldInfo) info).createWorldConfig();
        new AnvilSaveHandler(WorldManager.getCurrentSavesDirectory().get().toFile(), newName, true, SpongeImpl.getServer().getDataFixer())
                .saveWorldInfo(info);
        registerWorldProperties((WorldProperties) info);
        return Optional.of((WorldProperties) info);
    }

    public static CompletableFuture<Boolean> deleteWorld(WorldProperties worldProperties) {
        checkNotNull(worldProperties);
        checkArgument(worldPropertiesByWorldUuid.containsKey(worldProperties.getUniqueId()), "World properties not registered!");
        checkState(!worldByDimensionId.containsKey(((IMixinWorldInfo) worldProperties).getDimensionId()), "World not unloaded!");
        return SpongeScheduler.getInstance().submitAsyncTask(new DeleteWorldTask(worldProperties));
    }

    private static class CopyWorldTask implements Callable<Optional<WorldProperties>> {

        private final WorldInfo oldInfo;
        private final String newName;

        public CopyWorldTask(WorldInfo info, String newName) {
            this.oldInfo = info;
            this.newName = newName;
        }

        @Override
        public Optional<WorldProperties> call() throws Exception {
            Path oldWorldFolder = getCurrentSavesDirectory().get().resolve(this.oldInfo.getWorldName());
            final Path newWorldFolder = getCurrentSavesDirectory().get().resolve(this.newName);

            if (Files.exists(newWorldFolder)) {
                return Optional.empty();
            }

            FileFilter filter = null;
            if (((IMixinWorldInfo) this.oldInfo).getDimensionId() == 0) {
                oldWorldFolder = getCurrentSavesDirectory().get();
                filter = (file) -> !file.isDirectory() || !new File(file, "level.dat").exists();
            }

            FileUtils.copyDirectory(oldWorldFolder.toFile(), newWorldFolder.toFile(), filter);

            final WorldInfo info = new WorldInfo(this.oldInfo);
            info.setWorldName(this.newName);
            ((IMixinWorldInfo) info).setDimensionId(getNextFreeDimensionId());
            ((IMixinWorldInfo) info).setUniqueId(UUID.randomUUID());
            ((IMixinWorldInfo) info).createWorldConfig();
            registerWorldProperties((WorldProperties) info);
            new AnvilSaveHandler(WorldManager.getCurrentSavesDirectory().get().toFile(), newName, true, SpongeImpl.getServer().getDataFixer())
                    .saveWorldInfo(info);
            return Optional.of((WorldProperties) info);
        }
    }

    private static class DeleteWorldTask implements Callable<Boolean> {

        private final WorldProperties props;

        public DeleteWorldTask(WorldProperties props) {
            this.props = props;
        }

        @Override
        public Boolean call() throws Exception {
            final Path worldFolder = getCurrentSavesDirectory().get().resolve(props.getWorldName());
            if (!Files.exists(worldFolder)) {
                unregisterWorldProperties(this.props, true);
                return true;
            }

            try {
                FileUtils.deleteDirectory(worldFolder.toFile());
                unregisterWorldProperties(this.props, true);
                return true;
            } catch (IOException e) {
                return false;
            }
        }

    }

    public static DimensionType getClientDimensionType(DimensionType serverDimensionType) {
        switch (serverDimensionType) {
            case OVERWORLD:
            case NETHER:
            case THE_END:
                return serverDimensionType;
            default:
                return DimensionType.OVERWORLD;
        }
    }

    public static void sendDimensionRegistration(EntityPlayerMP playerMP, WorldProvider provider) {
        // Do nothing in Common
    }

    public static void loadDimensionDataMap(@Nullable NBTTagCompound compound) {
        dimensionBits.clear();
        if (compound == null) {
            dimensionTypeByDimensionId.keySet().stream().filter(dimensionId -> dimensionId >= 0).forEach(dimensionBits::set);
        } else {
            final int[] intArray = compound.getIntArray("DimensionArray");
            for (int i = 0; i < intArray.length; i++) {
                for (int j = 0; j < Integer.SIZE; j++) {
                    dimensionBits.set(i * Integer.SIZE + j, (intArray[i] & (1 << j)) != 0);
                }
            }
        }
    }

    public static NBTTagCompound saveDimensionDataMap() {
        int[] data = new int[(dimensionBits.length() + Integer.SIZE - 1 )/ Integer.SIZE];
        NBTTagCompound dimMap = new NBTTagCompound();
        for (int i = 0; i < data.length; i++) {
            int val = 0;
            for (int j = 0; j < Integer.SIZE; j++) {
                val |= dimensionBits.get(i * Integer.SIZE + j) ? (1 << j) : 0;
            }
            data[i] = val;
        }
        dimMap.setIntArray("DimensionArray", data);
        return dimMap;
    }

    public static Optional<Path> getCurrentSavesDirectory() {
        final Optional<WorldServer> optWorldServer = getWorldByDimensionId(0);

        if (optWorldServer.isPresent()) {
            return Optional.of(optWorldServer.get().getSaveHandler().getWorldDirectory().toPath());
        }

        return Optional.empty();
    }

    public static Map<WorldServer, WorldServer> getWeakWorldMap() {
        return weakWorldByWorld;
    }
}
