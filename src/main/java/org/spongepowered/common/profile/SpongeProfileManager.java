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
package org.spongepowered.common.profile;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.management.PlayerProfileCache;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.profile.GameProfile;
import org.spongepowered.api.profile.GameProfileCache;
import org.spongepowered.api.profile.GameProfileManager;
import org.spongepowered.api.profile.property.ProfileProperty;
import org.spongepowered.common.profile.query.GameProfileQuery;
import org.spongepowered.common.profile.query.NameQuery;
import org.spongepowered.common.profile.query.UniqueIdQuery;
import org.spongepowered.common.scheduler.SpongeScheduler;

import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

import javax.annotation.Nullable;

public final class SpongeProfileManager implements GameProfileManager {

    private final GameProfileCache defaultCache = (GameProfileCache) ((MinecraftServer) Sponge.getServer()).getPlayerProfileCache();
    private GameProfileCache cache = this.defaultCache;

    @Override
    public GameProfile createProfile(UUID uniqueId, @Nullable String name) {
        checkNotNull(uniqueId, "unique id");
        return (GameProfile) new com.mojang.authlib.GameProfile(uniqueId, name);
    }

    @Override
    public ProfileProperty createProfileProperty(String name, String value, @Nullable String signature) {
        return (ProfileProperty) new com.mojang.authlib.properties.Property(checkNotNull(name, "name"), checkNotNull(value, "value"), signature);
    }

    @Override
    public CompletableFuture<GameProfile> get(UUID uniqueId, final boolean useCache) {
        return this.submitTask(new UniqueIdQuery.SingleGet(this.cache, checkNotNull(uniqueId, "unique id"), useCache));
    }

    @Override
    public CompletableFuture<Collection<GameProfile>> getAllById(Iterable<UUID> uniqueIds, boolean useCache) {
        return this.submitTask(new UniqueIdQuery.MultiGet(this.cache, checkNotNull(uniqueIds, "unique ids"), useCache));
    }

    @Override
    public CompletableFuture<GameProfile> get(String name, boolean useCache) {
        return this.submitTask(new NameQuery.SingleGet(this.cache, checkNotNull(name, "name"), useCache));
    }

    @Override
    public CompletableFuture<Collection<GameProfile>> getAllByName(Iterable<String> names, boolean useCache) {
        return this.submitTask(new NameQuery.MultiGet(this.cache, checkNotNull(names, "names"), useCache));
    }

    @Override
    public CompletableFuture<GameProfile> fill(GameProfile profile, boolean signed, boolean useCache) {
        return this.submitTask(new GameProfileQuery.SingleFill(this.cache, checkNotNull(profile, "profile"), signed, useCache));
    }

    @Override
    public GameProfileCache getCache() {
        return this.cache;
    }

    @Override
    public void setCache(GameProfileCache cache) {
        this.cache = checkNotNull(cache, "cache");
    }

    @Override
    public GameProfileCache getDefaultCache() {
        return this.defaultCache;
    }

    private <T> CompletableFuture<T> submitTask(Callable<T> callable) {
        return SpongeScheduler.getInstance().submitAsyncTask(callable);
    }

}
