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
package org.spongepowered.common.data.manipulator.mutable.common;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.common.base.Objects;
import com.google.common.collect.ComparisonChain;
import com.google.common.collect.Lists;
import org.spongepowered.api.data.key.Key;
import org.spongepowered.api.data.manipulator.DataManipulator;
import org.spongepowered.api.data.manipulator.ImmutableDataManipulator;
import org.spongepowered.api.data.manipulator.immutable.ImmutableListData;
import org.spongepowered.api.data.manipulator.mutable.ListData;
import org.spongepowered.api.data.value.BaseValue;
import org.spongepowered.api.data.value.mutable.ListValue;
import org.spongepowered.common.data.value.mutable.SpongeListValue;
import org.spongepowered.common.util.ReflectionUtil;

import java.lang.reflect.Modifier;
import java.util.List;

/**
 * A common implementation for {@link ListData}s provided by the API.
 *
 * @param <E> The type of element within the list
 * @param <M> The type of {@link DataManipulator}
 * @param <I> The type of {@link ImmutableDataManipulator}
 */
@SuppressWarnings("unchecked")
public abstract class AbstractListData<E, M extends ListData<E, M, I>, I extends ImmutableListData<E, I, M>>
        extends AbstractSingleData<List<E>, M, I> implements ListData<E, M, I> {
    private final Class<? extends I> immutableClass;

    protected AbstractListData(Class<M> manipulatorClass, List<E> value, Key<? extends BaseValue<List<E>>> usedKey, Class<? extends I> immutableClass) {
        super(manipulatorClass, Lists.newArrayList(value), usedKey);
        checkArgument(!Modifier.isAbstract(immutableClass.getModifiers()), "The immutable class cannot be abstract!");
        checkArgument(!Modifier.isInterface(immutableClass.getModifiers()), "The immutable class cannot be an interface!");
        this.immutableClass = immutableClass;
    }

    @Override
    protected ListValue<E> getValueGetter() {
        return new SpongeListValue<>(this.usedKey, this.getValue());
    }

    @SuppressWarnings("unchecked")
    @Override
    public M copy() {
        return (M) ReflectionUtil.createInstance(this.getClass(), getValue());
    }

    @Override
    public I asImmutable() {
        return ReflectionUtil.createInstance(this.immutableClass, getValue());
    }

    // Again, overriding for generics
    @Override
    public int compareTo(M o) {
        final List<E> thisList = getValue();
        final List<E> otherList = o.asList();
        return ComparisonChain.start()
            .compare(thisList.containsAll(otherList), otherList.containsAll(thisList))
            .result();
    }

    @Override
    protected List<E> getValue() {
        return Lists.newArrayList(super.getValue());
    }

    @Override
    protected M setValue(List<E> value) {
        return super.setValue(Lists.newArrayList(value));
    }

    @Override
    public int hashCode() {
        return 31 * super.hashCode() + Objects.hashCode(this.getValue());
    }

    @SuppressWarnings("rawtypes")
    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null || getClass() != obj.getClass()) {
            return false;
        }
        if (!super.equals(obj)) {
            return false;
        }
        final AbstractListData other = (AbstractListData) obj;
        return Objects.equal(this.getValue(), other.getValue());
    }

    @Override
    public ListValue<E> getListValue() {
        return getValueGetter();
    }

    @Override
    public List<E> asList() {
        return getValue();
    }
}
