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
package org.spongepowered.common.interfaces;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.util.math.BlockPos;
import org.spongepowered.api.block.BlockSnapshot;
import org.spongepowered.api.entity.living.player.User;
import org.spongepowered.api.event.cause.Cause;
import org.spongepowered.api.util.Direction;
import org.spongepowered.api.world.BlockChangeFlag;
import org.spongepowered.api.world.Chunk;
import org.spongepowered.common.entity.PlayerTracker;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.annotation.Nullable;

public interface IMixinChunk {

    Map<Short, PlayerTracker> getTrackedShortPlayerPositions();

    Map<Integer, PlayerTracker> getTrackedIntPlayerPositions();

    Optional<User> getBlockOwner(BlockPos pos);

    Optional<User> getBlockNotifier(BlockPos pos);

    @Nullable
    IBlockState setBlockState(BlockPos pos, IBlockState newState, IBlockState currentState, @Nullable BlockSnapshot originalBlockSnapshot);

    @Nullable
    IBlockState setBlockState(BlockPos pos, IBlockState newState, IBlockState currentState, @Nullable BlockSnapshot originalBlockSnapshot, BlockChangeFlag flag);

    void setBlockNotifier(BlockPos pos, UUID uuid);

    void setBlockCreator(BlockPos pos, UUID uuid);

    void addTrackedBlockPosition(Block block, BlockPos pos, User user, PlayerTracker.Type trackerType);

    void setTrackedIntPlayerPositions(Map<Integer, PlayerTracker> trackedPlayerPositions);

    void setTrackedShortPlayerPositions(Map<Short, PlayerTracker> trackedPlayerPositions);

    void setNeighbor(Direction direction, Chunk neighbor);

    boolean areNeighborsLoaded();

}
