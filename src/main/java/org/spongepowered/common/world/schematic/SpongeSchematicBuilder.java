/*
 * Copyright (c) 2015-2016 VoxelBox <http://engine.thevoxelbox.com>.
 * All Rights Reserved.
 */
package org.spongepowered.common.world.schematic;

import com.flowpowered.math.vector.Vector3i;
import org.spongepowered.api.world.extent.ArchetypeVolume;
import org.spongepowered.api.world.extent.Extent;
import org.spongepowered.api.world.schematic.Palette;
import org.spongepowered.api.world.schematic.PaletteType;
import org.spongepowered.api.world.schematic.PaletteTypes;
import org.spongepowered.api.world.schematic.Schematic;
import org.spongepowered.api.world.schematic.Schematic.Builder;

public class SpongeSchematicBuilder implements Schematic.Builder {

    private ArchetypeVolume volume;
    private Extent view;
    private Palette palette;
    private PaletteType type = PaletteTypes.LOCAL;
    private Vector3i origin;
    private boolean storeEntities;
    
    @Override
    public Builder volume(ArchetypeVolume volume) {
        this.volume = volume;
        return this;
    }

    @Override
    public Builder volume(Extent volume) {
        this.view = volume;
        return null;
    }

    @Override
    public Builder palette(Palette palette) {
        this.palette = palette;
        this.type = palette.getType();
        return this;
    }

    @Override
    public Builder paletteType(PaletteType type) {
        this.type = type;
        this.palette = null;
        return this;
    }

    @Override
    public Builder origin(Vector3i origin) {
        this.origin = origin;
        return this;
    }

    @Override
    public Builder storeEntities(boolean state) {
        this.storeEntities = state;
        return this;
    }

    @Override
    public Builder from(Schematic value) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Builder reset() {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public Schematic build() throws IllegalArgumentException {
        if(this.palette == null) {
            this.palette = this.type.create();
            if(this.type == PaletteTypes.LOCAL) {
                //TODO fill palette from area;
            }
        }
        CharArraySchematic schematic = new CharArraySchematic(this.palette, this.volume.getBlockMin(), this.volume.getBlockSize());
        // TODO
        return schematic;
    }

}
