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
package org.spongepowered.common.mixin.core.server;

import com.flowpowered.math.vector.Vector3d;
import com.mojang.authlib.GameProfile;
import io.netty.buffer.Unpooled;
import net.minecraft.entity.Entity;
import net.minecraft.entity.SharedMonsterAttributes;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.network.NetHandlerPlayServer;
import net.minecraft.network.NetworkManager;
import net.minecraft.network.Packet;
import net.minecraft.network.PacketBuffer;
import net.minecraft.network.play.server.SPacketChangeGameState;
import net.minecraft.network.play.server.SPacketCustomPayload;
import net.minecraft.network.play.server.SPacketDisconnect;
import net.minecraft.network.play.server.SPacketEntityEffect;
import net.minecraft.network.play.server.SPacketHeldItemChange;
import net.minecraft.network.play.server.SPacketJoinGame;
import net.minecraft.network.play.server.SPacketPlayerAbilities;
import net.minecraft.network.play.server.SPacketPlayerListItem;
import net.minecraft.network.play.server.SPacketRespawn;
import net.minecraft.network.play.server.SPacketServerDifficulty;
import net.minecraft.network.play.server.SPacketSetExperience;
import net.minecraft.network.play.server.SPacketSpawnPosition;
import net.minecraft.network.play.server.SPacketUpdateHealth;
import net.minecraft.potion.PotionEffect;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.PlayerList;
import net.minecraft.server.management.PlayerProfileCache;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.ITextComponent;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.WorldServer;
import net.minecraft.world.border.WorldBorder;
import net.minecraft.world.chunk.storage.AnvilChunkLoader;
import net.minecraft.world.storage.IPlayerFileData;
import net.minecraft.world.storage.WorldInfo;
import org.apache.logging.log4j.Logger;
import org.spongepowered.api.Server;
import org.spongepowered.api.data.value.mutable.MutableBoundedValue;
import org.spongepowered.api.entity.Transform;
import org.spongepowered.api.entity.living.player.Player;
import org.spongepowered.api.event.SpongeEventFactory;
import org.spongepowered.api.event.cause.Cause;
import org.spongepowered.api.event.cause.NamedCause;
import org.spongepowered.api.event.entity.living.humanoid.player.RespawnPlayerEvent;
import org.spongepowered.api.event.message.MessageEvent;
import org.spongepowered.api.event.network.ClientConnectionEvent;
import org.spongepowered.api.network.RemoteConnection;
import org.spongepowered.api.resourcepack.ResourcePack;
import org.spongepowered.api.text.Text;
import org.spongepowered.api.text.channel.MessageChannel;
import org.spongepowered.api.util.annotation.NonnullByDefault;
import org.spongepowered.api.world.Dimension;
import org.spongepowered.api.world.Location;
import org.spongepowered.api.world.World;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.common.SpongeImpl;
import org.spongepowered.common.SpongeImplHooks;
import org.spongepowered.common.entity.player.SpongeUser;
import org.spongepowered.common.interfaces.IMixinEntityPlayerMP;
import org.spongepowered.common.interfaces.entity.player.IMixinEntityPlayer;
import org.spongepowered.common.interfaces.world.IMixinWorldProvider;
import org.spongepowered.common.text.SpongeTexts;
import org.spongepowered.common.util.VecHelper;
import org.spongepowered.common.world.DimensionManager;
import org.spongepowered.common.world.storage.SpongePlayerDataHandler;

import java.net.SocketAddress;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.annotation.Nullable;

@NonnullByDefault
@Mixin(PlayerList.class)
public abstract class MixinPlayerList {

    private static final String WRITE_PLAYER_DATA =
            "Lnet/minecraft/world/storage/IPlayerFileData;writePlayerData(Lnet/minecraft/entity/player/EntityPlayer;)V";
    private static final String
            SERVER_SEND_PACKET_TO_ALL_PLAYERS =
            "Lnet/minecraft/server/management/PlayerList;sendPacketToAllPlayers(Lnet/minecraft/network/Packet;)V";
    private static final String NET_HANDLER_SEND_PACKET = "Lnet/minecraft/network/NetHandlerPlayServer;sendPacket(Lnet/minecraft/network/Packet;)V";
    @Shadow @Final private static Logger logger;
    @Shadow @Final private MinecraftServer mcServer;
    @Shadow @Final public Map<UUID, EntityPlayerMP> uuidToPlayerMap;
    @Shadow @Final public List<EntityPlayerMP> playerEntityList;
    @Shadow private IPlayerFileData playerNBTManagerObj;
    @Shadow public abstract NBTTagCompound readPlayerDataFromFile(EntityPlayerMP playerIn);
    @Shadow public abstract void setPlayerGameTypeBasedOnOther(EntityPlayerMP playerIn, @Nullable EntityPlayerMP other, net.minecraft.world.World worldIn);
    @Shadow public abstract MinecraftServer getServerInstance();
    @Shadow public abstract int getMaxPlayers();
    @Shadow public abstract void sendChatMsg(ITextComponent component);
    @Shadow public abstract void sendPacketToAllPlayers(Packet<?> packetIn);
    @Shadow public abstract void preparePlayer(EntityPlayerMP playerIn, @Nullable WorldServer worldIn);
    @Shadow public abstract void playerLoggedIn(EntityPlayerMP playerIn);
    @Shadow public abstract void updateTimeAndWeatherForPlayer(EntityPlayerMP playerIn, WorldServer worldIn);
    @Shadow public abstract void updatePermissionLevel(EntityPlayerMP p_187243_1_);
    @Nullable @Shadow public abstract String allowUserToConnect(SocketAddress address, GameProfile profile);

    /**
     * Bridge methods to proxy modified method in Vanilla, nothing in Forge
     */
    public void func_72355_a(NetworkManager netManager, EntityPlayerMP playerIn) {
        initializeConnectionToPlayer(netManager, playerIn, null);
    }

    /**
     * Bridge methods to proxy modified method in Vanilla, nothing in Forge
     */
    public void initializeConnectionToPlayer(NetworkManager netManager, EntityPlayerMP playerIn) {
        initializeConnectionToPlayer(netManager, playerIn, null);
    }

    private void disconnectClient(NetworkManager netManager, Optional<Text> disconnectMessage, @Nullable GameProfile profile) {
        ITextComponent reason;
        if (disconnectMessage.isPresent()) {
            reason = SpongeTexts.toComponent(disconnectMessage.get());
        } else {
            reason = new TextComponentTranslation("disconnect.disconnected");
        }

        try {
            logger.info("Disconnecting " + (profile != null ? profile.toString() + " (" + netManager.getRemoteAddress().toString() + ")" : String.valueOf(netManager.getRemoteAddress() + ": " + reason.getUnformattedText())));
            netManager.sendPacket(new SPacketDisconnect(reason));
            netManager.closeChannel(reason);
        } catch (Exception exception) {
            logger.error("Error whilst disconnecting player", exception);
        }
    }

    public void initializeConnectionToPlayer(NetworkManager netManager, EntityPlayerMP playerIn, @Nullable NetHandlerPlayServer handler) {
        GameProfile gameprofile = playerIn.getGameProfile();
        PlayerProfileCache playerprofilecache = this.mcServer.getPlayerProfileCache();
        GameProfile gameprofile1 = playerprofilecache.getProfileByUUID(gameprofile.getId());
        String s = gameprofile1 == null ? gameprofile.getName() : gameprofile1.getName();
        playerprofilecache.addEntry(gameprofile);

        // Sponge start - save changes to offline User before reading player data
        SpongeUser user = (SpongeUser) ((IMixinEntityPlayerMP) playerIn).getUserObject();
        if (SpongeUser.dirtyUsers.contains(user)) {
            user.save();
        }
        // Sponge end

        NBTTagCompound nbttagcompound = this.readPlayerDataFromFile(playerIn);
        WorldServer worldserver = DimensionManager.getWorldFromDimId(playerIn.dimension);

        if (worldserver == null) {
            SpongeImpl.getLogger().warn("Player [{}] has attempted to login to unloaded dimension [{}]. This is not safe so we have moved them to "
                    + "the default world's spawn point.", playerIn.getName(), playerIn.dimension);
            playerIn.dimension = 0;
            worldserver = this.mcServer.worldServerForDimension(0);
            BlockPos spawnPoint = ((IMixinWorldProvider) worldserver.provider).getRandomizedSpawnPoint();
            playerIn.setPosition(spawnPoint.getX(), spawnPoint.getY(), spawnPoint.getZ());
        }

        // Sponge start - fire login event
        @Nullable String kickReason = allowUserToConnect(netManager.getRemoteAddress(), gameprofile);
        Text disconnectMessage;
        if (kickReason != null) {
            disconnectMessage = SpongeTexts.fromLegacy(kickReason);
        } else {
            disconnectMessage = Text.of("You are not allowed to log in to this server.");
        }

        Player player = (Player) playerIn;
        Transform<World> fromTransform = player.getTransform().setExtent((World) worldserver);

        ClientConnectionEvent.Login loginEvent = SpongeEventFactory.createClientConnectionEventLogin(
                Cause.of(NamedCause.source(player)), fromTransform, fromTransform, (RemoteConnection) netManager,
                new MessageEvent.MessageFormatter(disconnectMessage), (org.spongepowered.api.profile.GameProfile) gameprofile, player, false
        );

        if (kickReason != null) {
            loginEvent.setCancelled(true);
        }

        SpongeImpl.postEvent(loginEvent);
        if (loginEvent.isCancelled()) {
            disconnectClient(netManager, loginEvent.isMessageCancelled() ? Optional.empty() : Optional.of(loginEvent.getMessage()), gameprofile);
            return;
        }

        // Sponge end

        // Join data
        Optional<Instant> firstJoined = SpongePlayerDataHandler.getFirstJoined(playerIn.getUniqueID());
        Instant lastJoined = Instant.now();
        SpongePlayerDataHandler.setPlayerInfo(playerIn.getUniqueID(), firstJoined.orElse(lastJoined), lastJoined);

        double x = loginEvent.getToTransform().getPosition().getX();
        double y = loginEvent.getToTransform().getPosition().getY();
        double z = loginEvent.getToTransform().getPosition().getZ();
        float pitch = (float) loginEvent.getToTransform().getPitch();
        float yaw = (float) loginEvent.getToTransform().getYaw();
        if (worldserver != loginEvent.getToTransform().getExtent()) {
            worldserver = (net.minecraft.world.WorldServer) loginEvent.getToTransform().getExtent();
        }

        playerIn.setPositionAndRotation(x, y, z, yaw, pitch);
        playerIn.dimension = ((IMixinWorldProvider) worldserver.provider).getDimensionId();
        // Sponge end

        playerIn.setWorld(worldserver);
        playerIn.interactionManager.setWorld((WorldServer) playerIn.worldObj);
        String s1 = "local";

        if (netManager.getRemoteAddress() != null) {
            s1 = netManager.getRemoteAddress().toString();
        }

        // Sponge start - add world name to message
        logger.info(playerIn.getName() + "[" + s1 + "] logged in with entity id " + playerIn.getEntityId() + " in "
                + worldserver.getWorldInfo().getWorldName() + "(" + ((IMixinWorldProvider) worldserver.provider).getDimensionId()
                + ") at (" + playerIn.posX + ", " + playerIn.posY + ", " + playerIn.posZ + ")");
        // Sponge end

        WorldInfo worldinfo = worldserver.getWorldInfo();
        BlockPos blockpos = worldserver.getSpawnPoint();
        this.setPlayerGameTypeBasedOnOther(playerIn, null, worldserver);

        // Sponge start
        if (handler == null) {
            // Create the handler here (so the player's gets set)
            handler = new NetHandlerPlayServer(this.mcServer, netManager, playerIn);
        }
        playerIn.playerNetServerHandler = handler;
        // Sponge end

        // Support vanilla clients logging into custom dimensions
        int dimension = DimensionManager.getClientDimensionToSend(((IMixinWorldProvider) worldserver.provider).getDimensionId(), worldserver,
                playerIn);
        if (((IMixinEntityPlayerMP) playerIn).usesCustomClient()) {
            DimensionManager.sendDimensionRegistration(worldserver, playerIn, dimension);
        }

        handler.sendPacket(new SPacketJoinGame(playerIn.getEntityId(), playerIn.interactionManager.getGameType(), worldinfo
                .isHardcoreModeEnabled(), dimension, worldserver.getDifficulty(), this.getMaxPlayers(), worldinfo
                .getTerrainType(), worldserver.getGameRules().getBoolean("reducedDebugInfo")));
        handler.sendPacket(new SPacketCustomPayload("MC|Brand", (new PacketBuffer(Unpooled.buffer())).writeString(this
                .getServerInstance().getServerModName())));
        handler.sendPacket(new SPacketServerDifficulty(worldinfo.getDifficulty(), worldinfo.isDifficultyLocked()));
        handler.sendPacket(new SPacketSpawnPosition(blockpos));
        handler.sendPacket(new SPacketPlayerAbilities(playerIn.capabilities));
        handler.sendPacket(new SPacketHeldItemChange(playerIn.inventory.currentItem));
        this.updatePermissionLevel(playerIn);
        playerIn.getStatFile().func_150877_d();
        playerIn.getStatFile().sendAchievements(playerIn);
        this.mcServer.refreshStatusNextTick();

        this.playerLoggedIn(playerIn);
        handler.setPlayerLocation(playerIn.posX, playerIn.posY, playerIn.posZ, playerIn.rotationYaw, playerIn.rotationPitch);
        this.updateTimeAndWeatherForPlayer(playerIn, worldserver);

        // Sponge Start - Use the server's ResourcePack object
        Optional<ResourcePack> pack = ((Server)this.mcServer).getDefaultResourcePack();
        if (pack.isPresent()) {
            ((Player)playerIn).sendResourcePack(pack.get());
        }
        // Sponge End

        // Sponge Start

        // Move logic for creating join message up here
        //
        // This sends the objective/score creation packets
        // to the player, without attempting to remove them from their
        // previous scoreboard (which is set in a field initializer).
        // This allows #getWorldScoreboard to function
        // as normal, without causing issues when it is initialized on the client.

        ((IMixinEntityPlayerMP) playerIn).initScoreboard();

        TextComponentTranslation chatcomponenttranslation;

        if (!playerIn.getName().equalsIgnoreCase(s))
        {
            chatcomponenttranslation = new TextComponentTranslation("multiplayer.player.joined.renamed", playerIn.getDisplayName(), s);
        }
        else
        {
            chatcomponenttranslation = new TextComponentTranslation("multiplayer.player.joined", playerIn.getDisplayName());
        }

        chatcomponenttranslation.getStyle().setColor(TextFormatting.YELLOW);

        for (PotionEffect potioneffect : playerIn.getActivePotionEffects()) {
            handler.sendPacket(new SPacketEntityEffect(playerIn.getEntityId(), potioneffect));
        }

        // Fire PlayerJoinEvent
        Text originalMessage = SpongeTexts.toText(chatcomponenttranslation);
        MessageChannel originalChannel = player.getMessageChannel();
        final ClientConnectionEvent.Join event = SpongeImplHooks.createClientConnectionEventJoin(
                Cause.of(NamedCause.source(player)), originalChannel, Optional.of(originalChannel),
                new MessageEvent.MessageFormatter(originalMessage), player, false
        );
        SpongeImpl.postEvent(event);
        // Send to the channel
        if (!event.isMessageCancelled()) {
            event.getChannel().ifPresent(channel -> channel.send(player, event.getMessage()));
        }
        // Sponge end

        if (nbttagcompound != null) {
            if (nbttagcompound.hasKey("RootVehicle", 10)) {
                NBTTagCompound nbttagcompound1 = nbttagcompound.getCompoundTag("RootVehicle");
                Entity entity2 = AnvilChunkLoader.readWorldEntity(nbttagcompound1.getCompoundTag("Entity"), worldserver, true);

                if (entity2 != null) {
                    UUID uuid = nbttagcompound1.getUniqueId("Attach");

                    if (entity2.getUniqueID().equals(uuid)) {
                        playerIn.startRiding(entity2, true);
                    } else {
                        for (Entity entity : entity2.getRecursivePassengers()) {
                            if (entity.getUniqueID().equals(uuid)) {
                                playerIn.startRiding(entity, true);
                                break;
                            }
                        }
                    }

                    if (!playerIn.isRiding()) {
                        logger.warn("Couldn\'t reattach entity to player");
                        worldserver.removePlayerEntityDangerously(entity2);

                        for (Entity entity3 : entity2.getRecursivePassengers()) {
                            worldserver.removePlayerEntityDangerously(entity3);
                        }
                    }
                }
            } else if (nbttagcompound.hasKey("Riding", 10)) {
                Entity entity1 = AnvilChunkLoader.readWorldEntity(nbttagcompound.getCompoundTag("Riding"), worldserver, true);

                if (entity1 != null) {
                    playerIn.startRiding(entity1, true);
                }
            }
        }

        playerIn.addSelfToInternalCraftingInventory();

    }

    // A temporary variable to transfer the 'isBedSpawn' variable between
    // getPlayerRespawnLocation and recreatePlayerEntity
    private boolean tempIsBedSpawn = false;

    @SuppressWarnings("unchecked")
    @Overwrite
    public EntityPlayerMP recreatePlayerEntity(EntityPlayerMP playerIn, int targetDimension, boolean conqueredEnd) {

        // Sponge start

        // ### PHASE 1 ### Get the location to spawn

        // Vanilla will always use overworld, set to the world the player was in
        // UNLESS comming back from the end.
        if (!conqueredEnd && targetDimension == 0) {
            targetDimension = playerIn.dimension;
        }

        Player player = (Player) playerIn;
        Transform<World> fromTransform = player.getTransform();
        Transform<World> toTransform = new Transform<>(this.getPlayerRespawnLocation(playerIn, targetDimension), Vector3d.ZERO, Vector3d.ZERO);
        Location<World> location = toTransform.getLocation();

        // Keep players out of blocks
        Vector3d tempPos = player.getLocation().getPosition();
        playerIn.setPosition(location.getX(), location.getY(), location.getZ());
        while (!((WorldServer) location.getExtent()).getCollisionBoxes(playerIn, playerIn.getEntityBoundingBox()).isEmpty()) {
            playerIn.setPosition(playerIn.posX, playerIn.posY + 1.0D, playerIn.posZ);
            location = location.add(0, 1, 0);
        }
        playerIn.setPosition(tempPos.getX(), tempPos.getY(), tempPos.getZ());

        // Sponge end

        // ### PHASE 2 ### Remove player from current dimension
        playerIn.getServerWorld().getEntityTracker().removePlayerFromTrackers(playerIn);
        playerIn.getServerWorld().getEntityTracker().untrackEntity(playerIn);
        playerIn.getServerWorld().getPlayerChunkMap().removePlayer(playerIn);
        this.playerEntityList.remove(playerIn);
        this.mcServer.worldServerForDimension(playerIn.dimension).removePlayerEntityDangerously(playerIn);

        // Sponge start

        // ### PHASE 3 ### Reset player (if applicable)
        playerIn.playerConqueredTheEnd = false;
        if (!conqueredEnd) { // don't reset player if returning from end
            ((IMixinEntityPlayerMP) playerIn).reset();
        }
        playerIn.setSneaking(false);
        // update to safe location
        toTransform = toTransform.setLocation(location);

        ((IMixinEntityPlayerMP) playerIn).resetAttributeMap();

        // ### PHASE 4 ### Fire event and set new location on the player
        final RespawnPlayerEvent event =
                SpongeImplHooks.createRespawnPlayerEvent(Cause.of(NamedCause.source(playerIn)), fromTransform, toTransform,
                    (Player) playerIn, this.tempIsBedSpawn);
        this.tempIsBedSpawn = false;
        SpongeImpl.postEvent(event);
        player.setTransform(event.getToTransform());
        location = event.getToTransform().getLocation();

        if (!(location.getExtent() instanceof WorldServer)) {
            SpongeImpl.getLogger().warn("Location set in PlayerRespawnEvent was invalid, using original location instead");
            location = event.getFromTransform().getLocation();
        }
        final WorldServer targetWorld = (WorldServer) location.getExtent();

        playerIn.dimension = ((IMixinWorldProvider) targetWorld.provider).getDimensionId();
        playerIn.setWorld(targetWorld);
        playerIn.interactionManager.setWorld(targetWorld);

        targetWorld.getChunkProvider().provideChunk((int) location.getX() >> 4, (int) location.getZ() >> 4);

        // ### PHASE 5 ### Respawn player in new world

        // Support vanilla clients logging into custom dimensions
        int dimension = DimensionManager.getClientDimensionToSend(((IMixinWorldProvider) targetWorld.provider).getDimensionId(), targetWorld, playerIn);
        if (((IMixinEntityPlayerMP) playerIn).usesCustomClient()) {
            DimensionManager.sendDimensionRegistration(targetWorld, playerIn, dimension);
        }

        playerIn.playerNetServerHandler.sendPacket(new SPacketRespawn(dimension, targetWorld.getDifficulty(), targetWorld
                .getWorldInfo().getTerrainType(), playerIn.interactionManager.getGameType()));
        playerIn.isDead = false;
        playerIn.playerNetServerHandler.setPlayerLocation(location.getX(), location.getY(), location.getZ(),
                playerIn.rotationYaw, playerIn.rotationPitch);

        final BlockPos spawnLocation = targetWorld.getSpawnPoint();
        playerIn.playerNetServerHandler.sendPacket(new SPacketSpawnPosition(spawnLocation));
        playerIn.playerNetServerHandler.sendPacket(new SPacketSetExperience(playerIn.experience, playerIn.experienceTotal,
                playerIn.experienceLevel));
        this.updateTimeAndWeatherForPlayer(playerIn, targetWorld);
        this.updatePermissionLevel(playerIn);
        targetWorld.getPlayerChunkMap().addPlayer(playerIn);
        targetWorld.spawnEntityInWorld(playerIn);
        this.playerEntityList.add(playerIn);
        this.uuidToPlayerMap.put(playerIn.getUniqueID(), playerIn);
        playerIn.addSelfToInternalCraftingInventory();

        // Reset the health.
        final MutableBoundedValue<Double> maxHealth = ((Player) playerIn).maxHealth();
        final MutableBoundedValue<Integer> food = ((Player) playerIn).foodLevel();
        final MutableBoundedValue<Double> saturation = ((Player) playerIn).saturation();

        playerIn.getEntityAttribute(SharedMonsterAttributes.MAX_HEALTH).setBaseValue(1.0F);
        playerIn.getEntityAttribute(SharedMonsterAttributes.MAX_HEALTH).setBaseValue(maxHealth.get().floatValue());
        playerIn.playerNetServerHandler.sendPacket(new SPacketUpdateHealth(maxHealth.get().floatValue(), food.get(), saturation.get().floatValue()));

        return playerIn;
    }

    // Internal. Note: Has side-effects
    private Location<World> getPlayerRespawnLocation(EntityPlayerMP playerIn, int targetDimension) {
        Location<World> location = ((World) playerIn.worldObj).getSpawnLocation();
        this.tempIsBedSpawn = false;
        WorldServer targetWorld = this.mcServer.worldServerForDimension(targetDimension);
        if (targetWorld == null) { // Target world doesn't exist? Use global
            return location;
        }

        Dimension targetDim = (Dimension) targetWorld.provider;
        // Cannot respawn in requested world, use the fallback dimension for
        // that world. (Usually overworld unless a mod says otherwise).
        if (!targetDim.allowsPlayerRespawns()) {
            targetDimension = ((IMixinWorldProvider) targetDim).getRespawnDimension(playerIn);
            targetWorld = this.mcServer.worldServerForDimension(targetDimension);
            targetDim = (Dimension) targetWorld.provider;
        }
        Vector3d spawnPos = VecHelper.toVector3d(targetWorld.getSpawnPoint());
        BlockPos bedLoc = ((IMixinEntityPlayer) playerIn).getBedLocation(targetDimension);
        if (bedLoc != null) { // Player has a bed
            boolean forceBedSpawn = ((IMixinEntityPlayer) playerIn).isSpawnForced(targetDimension);
            BlockPos bedSpawnLoc = EntityPlayer.getBedSpawnLocation(this.mcServer.worldServerForDimension(targetDimension), bedLoc, forceBedSpawn);
            if (bedSpawnLoc != null) { // The bed exists and is not obstructed
                this.tempIsBedSpawn = true;
                playerIn.setLocationAndAngles(bedSpawnLoc.getX() + 0.5D, bedSpawnLoc.getY() + 0.1D, bedSpawnLoc.getZ() + 0.5D, 0.0F, 0.0F);
                spawnPos = new Vector3d(bedSpawnLoc.getX() + 0.5D, bedSpawnLoc.getY() + 0.1D, bedSpawnLoc.getZ() + 0.5D);
            } else { // Bed invalid
                playerIn.playerNetServerHandler.sendPacket(new SPacketChangeGameState(0, 0.0F));
                // Vanilla behaviour - Delete the known bed location if invalid
                bedLoc = null; // null = remove location
            }
            // Set the new bed location for the new dimension
            int prevDim = playerIn.dimension; // Temporarily for setSpawnPoint
            playerIn.dimension = targetDimension;
            playerIn.setSpawnPoint(bedLoc, forceBedSpawn);
            playerIn.dimension = prevDim;
        }
        return new Location<>((World) targetWorld, spawnPos);
    }

    @Inject(method = "setPlayerManager", at = @At("HEAD"), cancellable = true)
    private void onSetPlayerManager(WorldServer[] worldServers, CallbackInfo callbackInfo) {
        if (this.playerNBTManagerObj == null) {
            this.playerNBTManagerObj = worldServers[0].getSaveHandler().getPlayerNBTManager();
            // This is already added in our world constructor
            //worldServers[0].getWorldBorder().addListener(new PlayerBorderListener(0));
        }
        callbackInfo.cancel();
    }

    @Redirect(method = "updateTimeAndWeatherForPlayer", at = @At(value = "INVOKE", target = "Lnet/minecraft/world/WorldServer;getWorldBorder()Lnet/minecraft/world/border/WorldBorder;"))
    private WorldBorder onUpdateTimeGetWorldBorder(WorldServer worldServer, EntityPlayerMP entityPlayerMP, WorldServer worldServerIn) {
        return worldServerIn.getWorldBorder();
    }

    @Inject(method = "playerLoggedOut(Lnet/minecraft/entity/player/EntityPlayerMP;)V", at = @At("HEAD"))
    private void onPlayerLogOut(EntityPlayerMP player, CallbackInfo ci) {
        // Synchronise with user object
        NBTTagCompound nbt = new NBTTagCompound();
        player.writeToNBT(nbt);
        ((SpongeUser) ((IMixinEntityPlayerMP) player).getUserObject()).readFromNbt(nbt);
    }

    @Inject(method = "saveAllPlayerData()V", at = @At("RETURN"))
    private void onSaveAllPlayerData(CallbackInfo ci) {
        for (SpongeUser user : SpongeUser.dirtyUsers) {
            user.save();
        }
    }

    @Inject(method = "playerLoggedIn", at = @At(value = "INVOKE", target = SERVER_SEND_PACKET_TO_ALL_PLAYERS, shift = At.Shift.BEFORE), cancellable = true)
    public void playerLoggedIn2(EntityPlayerMP player, CallbackInfo ci) {
        // Create a packet to be used for players without context data
        SPacketPlayerListItem noSpecificViewerPacket = new SPacketPlayerListItem(SPacketPlayerListItem.Action.ADD_PLAYER, player);

        for (EntityPlayerMP viewer : this.playerEntityList) {
            if (((Player) viewer).canSee((Player) player)) {
                viewer.playerNetServerHandler.sendPacket(noSpecificViewerPacket);
            }

            if (((Player) player).canSee((Player) viewer)) {
                player.playerNetServerHandler.sendPacket(new SPacketPlayerListItem(SPacketPlayerListItem.Action.ADD_PLAYER, viewer));
            }
        }

        // Spawn player into level
        WorldServer level = this.mcServer.worldServerForDimension(player.dimension);
        // TODO direct this appropriately
        level.spawnEntityInWorld(player);
        this.preparePlayer(player, null);

        // We always want to cancel.
        ci.cancel();
    }

    @Inject(method = "writePlayerData", at = @At(target = WRITE_PLAYER_DATA, value = "INVOKE"))
    private void onWritePlayerFile(EntityPlayerMP playerMP, CallbackInfo callbackInfo) {
        SpongePlayerDataHandler.savePlayer(playerMP.getUniqueID());
    }

}