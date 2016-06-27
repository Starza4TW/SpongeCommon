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
package org.spongepowered.common.data.persistence;

import com.google.common.reflect.TypeToken;
import org.spongepowered.api.data.DataContainer;
import org.spongepowered.api.data.DataView;
import org.spongepowered.api.data.persistence.DataTranslator;
import org.spongepowered.api.data.persistence.InvalidDataException;
import org.spongepowered.api.world.schematic.Schematic;
import org.spongepowered.common.data.util.DataQueries;

public class SchematicTranslator implements DataTranslator<Schematic> {

    private static final SchematicTranslator INSTANCE = new SchematicTranslator();
    private static final TypeToken<Schematic> TYPE_TOKEN = TypeToken.of(Schematic.class);
    private static final int VERSION = 1;
    private static final int MAX_SIZE = 65535;
    
    public static SchematicTranslator get() {
        return INSTANCE;
    }
    
    private SchematicTranslator() {
        
    }
    
    @Override
    public String getId() {
        return "sponge:schematic";
    }

    @Override
    public String getName() {
        return "Sponge Schematic Translator";
    }

    @Override
    public TypeToken<Schematic> getToken() {
        return TYPE_TOKEN;
    }

    @Override
    public Schematic translate(DataView view) throws InvalidDataException {
        int version = view.getInt(DataQueries.Schematic.VERSION).get();
        // TODO version conversions
        if(version != VERSION) {
            throw new InvalidDataException(String.format("Unknown schematic version %d (current version is %d)", version, VERSION));
        }
        DataView metadata = view.getView(DataQueries.Schematic.METADATA).orElse(null);

        int width = view.getShort(DataQueries.Schematic.WIDTH).get();
        int height = view.getShort(DataQueries.Schematic.HEIGHT).get();
        int length = view.getShort(DataQueries.Schematic.LENGTH).get();
        if (width > MAX_SIZE || height > MAX_SIZE || length > MAX_SIZE) {
            throw new InvalidDataException(String.format(
                    "Schematic is larger than maximum allowable size (found: (%d, %d, %d) max: (%d, %<d, %<d)", width, height, length, MAX_SIZE));
        }
        
        // TODO
        
        return null;
    }

    @Override
    public DataContainer translate(Schematic obj) throws InvalidDataException {
        DataContainer data = new NonCloningDataContainer();
        // TODO Auto-generated method stub
        return null;
    }

}
