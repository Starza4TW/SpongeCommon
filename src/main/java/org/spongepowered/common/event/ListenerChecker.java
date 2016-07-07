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
package org.spongepowered.common.event;

import com.google.common.base.CaseFormat;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableMap;
import com.google.common.reflect.TypeToken;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;

public class ListenerChecker {

    private final Class<?> clazz;
    private Map<String, Field> fields = new HashMap<>();

    private LoadingCache<Class<?>, Optional<Field>> fieldCache = CacheBuilder.newBuilder().build(new CacheLoader<Class<?>, Optional<Field>>() {

        @Override
        public Optional<Field> load(Class<?> key) throws Exception {
            return Optional.ofNullable(ListenerChecker.this.fields.get(getName(key)));
        }
    });

    private LoadingCache<Class<?>, Set<Class<?>>> subtypeMappings = CacheBuilder.newBuilder().build(new CacheLoader<Class<?>, Set<Class<?>>>() {

        @Override
        public Set<Class<?>> load(Class<?> key) throws Exception {
            return new HashSet<>();
        }
    });

    private static String getName(Class<?> clazz) {
        // Properly account for inner classes. Class#getName uses a $
        // to separate inner classes, so the last '.' is the end of the package name
        //
        String name = clazz.getName().substring(clazz.getName().lastIndexOf(".") + 1).replace("$", "");
        return CaseFormat.UPPER_CAMEL.to(CaseFormat.UPPER_UNDERSCORE, name);
    }

    public void registerListenerFor(Class<?> eventClass) {
        Set<Class<?>> types = (Set) TypeToken.of(eventClass).getTypes().rawTypes();
        for (Class<?> type: types) {
            this.subtypeMappings.getUnchecked(type).add(eventClass);
        }

        // Walk up the tree. The event class and all its supertypes have their
        // flags set to 'true', because there is a possibility of them being fired.

        // For example, if a listener is registered for SpawnEntityEvent.Custom,
        // then SpawnEntityEvent.Custom, SpawnEntityEvent, and Event (plus others)
        // will have their flags set to 'true'. When the implementation checks
        // ShouldFire.SPAWN_ENTITY_EVENT, it will be 'true', because there is a possibility
        // than something that matches the type 'SpawnEntityEvent' will have a listener.
        // The actual type might be SpawnEntityEvent.ChunkLoad, which might have no listeners.
        // However, the base flags takes into account that some of the subevent *do* have listeners.

        // We don't need to walk down the tree, because a more-specific flag can never be turned
        // from false to true by a less-specific event (and therefore flag) being registered.
        this.updateFields(types, c -> true);
    }

    public void unregisterListenerFor(Class<?> eventClass) {
        Set<Class<?>> types = (Set) TypeToken.of(eventClass).getTypes().rawTypes();
        for (Class<?> type: types) {
            this.subtypeMappings.getUnchecked(type).remove(eventClass);
        }

        // As in 'registerListenerFor', we only set flags when walking up the tree - that is,
        // only the flags for the event class and its superinterfaces can be flipped (from 'true'
        // to 'false' in this case). However, we need to look *down* the tree to see if we can
        // flip the flag. If a given class has any listeners registered for its subtypes (recorded
        // earlier in 'registerListenerFor'), we can't set the flag to false, because there's still
        // the possibility that an event which matches that type will have a listener (if it's the correct
        // subtype, which the less-specific flag cannot make assumptions about).

        // If the set is empty for a class (there are no direct listeners or subclass listeners)
        // we set it to false (opposite of isEmpty/true)
        this.updateFields(types, c -> !this.subtypeMappings.getUnchecked(c).isEmpty());
    }

    public ListenerChecker(Class<?> clazz) {
        this.clazz = clazz;
        for (Field field: this.clazz.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers()) && Modifier.isPublic(field.getModifiers())) {
                this.fields.put(field.getName(), field);
            }
        }
    }

    public void updateFields(Collection<Class<?>> classes, Predicate<Class<?>> enable) {
        for (Class<?> clazz: classes) {
            this.fieldCache.getUnchecked(clazz).ifPresent(f -> {
                try {
                    f.set(null, enable.test(clazz));
                } catch (IllegalAccessException e) {
                    e.printStackTrace();
                }
            });
        }
    }

}
