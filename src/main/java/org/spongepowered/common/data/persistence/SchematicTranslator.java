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

import com.flowpowered.math.vector.Vector3i;
import com.google.common.reflect.TypeToken;
import org.spongepowered.api.Sponge;
import org.spongepowered.api.block.BlockState;
import org.spongepowered.api.data.DataContainer;
import org.spongepowered.api.data.DataQuery;
import org.spongepowered.api.data.DataView;
import org.spongepowered.api.data.persistence.DataTranslator;
import org.spongepowered.api.data.persistence.InvalidDataException;
import org.spongepowered.api.world.extent.MutableBlockVolume;
import org.spongepowered.api.world.schematic.Palette;
import org.spongepowered.api.world.schematic.Schematic;
import org.spongepowered.common.data.util.DataQueries;
import org.spongepowered.common.util.gen.ByteArrayMutableBlockBuffer;
import org.spongepowered.common.util.gen.CharArrayMutableBlockBuffer;
import org.spongepowered.common.util.gen.IntArrayMutableBlockBuffer;
import org.spongepowered.common.world.schematic.BimapPalette;
import org.spongepowered.common.world.schematic.GlobalPalette;
import org.spongepowered.common.world.schematic.SpongeSchematic;

import java.util.Optional;
import java.util.Set;

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
        if (version != VERSION) {
            throw new InvalidDataException(String.format("Unknown schematic version %d (current version is %d)", version, VERSION));
        }
        DataView metadata = view.getView(DataQueries.Schematic.METADATA).orElse(null);

        // TODO error handling for these optionals
        int width = view.getShort(DataQueries.Schematic.WIDTH).get();
        int height = view.getShort(DataQueries.Schematic.HEIGHT).get();
        int length = view.getShort(DataQueries.Schematic.LENGTH).get();
        if (width > MAX_SIZE || height > MAX_SIZE || length > MAX_SIZE) {
            throw new InvalidDataException(String.format(
                    "Schematic is larger than maximum allowable size (found: (%d, %d, %d) max: (%d, %<d, %<d)", width, height, length, MAX_SIZE));
        }

        int[] offset = (int[]) view.get(DataQueries.Schematic.OFFSET).orElse(null);
        if (offset == null) {
            offset = new int[3];
        }
        if (offset.length < 3) {
            throw new InvalidDataException("Schematic offset was not of length 3");
        }
        Palette palette;
        Optional<DataView> paletteData = view.getView(DataQueries.Schematic.PALETTE);
        int palette_max = view.getInt(DataQueries.Schematic.PALETTE_MAX).orElse(0xFFFF);
        if (paletteData.isPresent()) {
            // If we had a default palette_max we don't want to allocate all
            // that space for nothing so we use a sensible default instead
            palette = new BimapPalette(palette_max != 0xFFFF ? palette_max : 64);
            DataView paletteMap = paletteData.get();
            Set<DataQuery> paletteKeys = paletteMap.getKeys(false);
            for (DataQuery key : paletteKeys) {
                BlockState state = Sponge.getRegistry().getType(BlockState.class, key.getParts().get(0)).get();
                ((BimapPalette) palette).assign(state, paletteMap.getInt(key).get());
            }
        } else {
            palette = GlobalPalette.instance;
        }

        MutableBlockVolume buffer;
        if (palette_max <= 0xFF) {
            buffer = new ByteArrayMutableBlockBuffer(palette, new Vector3i(-offset[0], -offset[1], -offset[2]), new Vector3i(width, height, length));
        } else if (palette_max <= 0xFF) {
            buffer = new CharArrayMutableBlockBuffer(palette, new Vector3i(-offset[0], -offset[1], -offset[2]), new Vector3i(width, height, length));
        } else {
            buffer = new IntArrayMutableBlockBuffer(palette, new Vector3i(-offset[0], -offset[1], -offset[2]), new Vector3i(width, height, length));
        }

        byte[] blockdata = (byte[]) view.get(DataQueries.Schematic.BLOCK_DATA).get();

        int index = 0;
        int i = 0;
        int value = 0;
        int varint_length = 0;
        while (i < blockdata.length) {
            value = 0;
            length = 0;

            while (true) {
                value |= (blockdata[i] & 127) << varint_length++ * 7;
                if (varint_length > 5) {
                    throw new RuntimeException("VarInt too big (probably corrupted data)");
                }
                if ((blockdata[i] & 128) != 128) {
                    break;
                }
                i++;
            }
            // index = (y * length + z) * width + x
            int y = index / (width * length);
            int z = (index % (width * length)) / width;
            int x = (index % (width * length)) % width;

            BlockState state = palette.get(value).get();
            buffer.setBlock(x - offset[0], y - offset[1], z - offset[2], state);

            index++;
        }
        // TODO tile entities and entities

        Schematic schematic = new SpongeSchematic(buffer, metadata);
        return schematic;
    }

    @Override
    public DataContainer translate(Schematic obj) throws InvalidDataException {
        DataContainer data = new NonCloningDataContainer();
        // TODO Auto-generated method stub
        return null;
    }

}
