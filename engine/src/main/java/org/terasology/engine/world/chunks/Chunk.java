// Copyright 2022 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0
package org.terasology.engine.world.chunks;

import org.joml.Vector3f;
import org.joml.Vector3i;
import org.joml.Vector3ic;
import org.terasology.engine.world.block.Block;
import org.terasology.engine.world.block.BlockRegionc;
import org.terasology.context.annotation.API;
import org.terasology.protobuf.EntityData;

/**
 * Chunks are a box-shaped logical grouping of Terasology's blocks, for performance reasons.
 *
 * <p>
 * For example the renderer renders a single mesh for all opaque blocks in a chunk rather
 * than rendering each block as a separate mesh.
 * <p>
 * For details on dimensions and other chunks characteristics see {@link Chunks}.
 */
@API
public interface Chunk extends RenderableChunk {

    /**
     * @return Position of the chunk in world, where units of distance from origin are chunks
     */
    Vector3ic getPosition();

    /**
     * Position of the chunk in world, where units of distance from origin are chunks
     *
     * @param dest will hold the result
     * @return dest
     * @deprecated use {@link #getPosition()}
     */
    @Deprecated
    default Vector3i getPosition(Vector3i dest) {
        return dest.set(getPosition());
    }

    /**
     * Returns block at given position relative to the chunk.
     *
     * @param pos Position of the block relative to corner of the chunk
     * @return Block at given position
     */
    default Block getBlock(Vector3ic pos) {
        return getBlock(pos.x(), pos.y(), pos.z());
    }

    /**
     * Returns block at given position relative to the chunk.
     *
     * @param x X offset from the corner of the chunk
     * @param y Y offset from the corner of the chunk
     * @param z Z offset from the corner of the chunk
     * @return Block at given position
     */
    Block getBlock(int x, int y, int z);

    /**
     * Sets type of block at given position relative to the chunk.
     *
     * @param x     X offset from the corner of the chunk
     * @param y     Y offset from the corner of the chunk
     * @param z     Z offset from the corner of the chunk
     * @param block Block to set block at given position to
     * @return Old Block at given position
     */
    Block setBlock(int x, int y, int z, Block block);

    /**
     * Sets type of block at given position relative to the chunk.
     *
     * @param pos   Position of the block relative to corner of the chunk
     * @param block Block to set block at given position to
     * @return Old Block at given position
     */
    default Block setBlock(Vector3ic pos, Block block) {
        return setBlock(pos.x(), pos.y(), pos.z(), block);
    }

    /**
     * Sets one of the per-block custom data values at a given position relative to the chunk.
     * The given value is downcast from int to the appropriate type for the array. It is not
     * checked for overflow.
     *
     * @param index Index of the extra data array
     * @param x     X offset from the corner of the chunk
     * @param y     Y offset from the corner of the chunk
     * @param z     Z offset from the corner of the chunk
     * @param value New value to set the block to
     */
    void setExtraData(int index, int x, int y, int z, int value);

    /**
     * Sets one of the per-block custom data values at a given position relative to the chunk.
     * The given value is downcast from int to the appropriate type for the array. It is not
     * checked for overflow.
     *
     * @param index Index of the extra data array
     * @param pos   Position of the block relative to the corner of the chunk
     * @param value New value to set the block to
     */
    default void setExtraData(int index, Vector3ic pos, int value) {
        setExtraData(index, pos.x(), pos.y(), pos.z(), value);
    }

    /**
     * Returns one of the per-block custom data values at a given position relative to the chunk.
     *
     * @param index Index of the extra data array
     * @param x     X offset from the corner of the chunk
     * @param y     Y offset from the corner of the chunk
     * @param z     Z offset from the corner of the chunk
     * @return Selected extra data value at the given location
     */
    int getExtraData(int index, int x, int y, int z);

    /**
     * Returns one of the per-block custom data values at a given position relative to the chunk.
     *
     * @param index Index of the extra data array
     * @param pos   Position of the block relative to the corner of the chunk
     * @return Selected extra data value at the given location
     */
    default int getExtraData(int index, Vector3ic pos) {
        return getExtraData(index, pos.x(), pos.y(), pos.z());
    }


    /**
     * Returns offset of this chunk to the world center (0:0:0), with one unit being one block.
     *
     * @return Offset of this chunk from world center in blocks
     */
    Vector3i getChunkWorldOffset(Vector3i pos);

    default Vector3f getRenderPosition() {
        return new Vector3f(getChunkWorldOffsetX(), getChunkWorldOffsetY(), getChunkWorldOffsetZ());
    }


    /**
     * Returns X offset of this chunk to the world center (0:0:0), with one unit being one block.
     *
     * @return X offset of this chunk from world center in blocks
     */
    int getChunkWorldOffsetX();

    /**
     * Returns Y offset of this chunk to the world center (0:0:0), with one unit being one block.
     *
     * @return Y offset of this chunk from world center in blocks
     */
    int getChunkWorldOffsetY();

    /**
     * Returns Z offset of this chunk to the world center (0:0:0), with one unit being one block.
     *
     * @return Z offset of this chunk from world center in blocks
     */
    int getChunkWorldOffsetZ();


    /**
     * Returns position in this chunk transformed to world coordinates.
     *
     * @param dest Position in this chunk you want to transform
     * @return Transformed position
     */
    default Vector3i chunkToWorldPosition(Vector3ic blockPos, Vector3i dest) {
        return chunkToWorldPosition(blockPos.x(), blockPos.y(), blockPos.z(), dest);
    }


    /**
     * Returns position in this chunk transformed to world coordinates.
     *
     * @param x X position in this chunk you want to transform
     * @param y Y position in this chunk you want to transform
     * @param z Z position in this chunk you want to transform
     * @param dest will hold the result
     * @return dest
     */
    Vector3i chunkToWorldPosition(int x, int y, int z, Vector3i dest);


    /**
     * Returns X position in this chunk transformed to world coordinate.
     *
     * @param x X  position in this chunk you want to transform
     * @return Transformed position
     */
    int chunkToWorldPositionX(int x);

    /**
     * Returns Y position in this chunk transformed to world coordinate.
     *
     * @param y Y  position in this chunk you want to transform
     * @return Transformed position
     */
    int chunkToWorldPositionY(int y);

    /**
     * Returns Z position in this chunk transformed to world coordinate.
     *
     * @param z Z  position in this chunk you want to transform
     * @return Transformed position
     */
    int chunkToWorldPositionZ(int z);

    /**
     * @return Size of the chunk along the X axis.
     */
    default int getChunkSizeX() {
        return Chunks.SIZE_X;
    }

    /**
     * @return Size of the chunk along the Y axis.
     */
    default int getChunkSizeY() {
        return Chunks.SIZE_Y;
    }

    /**
     * @return Size of the chunk along the Z axis.
     */
    default int getChunkSizeZ() {
        return Chunks.SIZE_Z;
    }

    /**
     * @return Chunk's Region
     */
    BlockRegionc getRegion();

    /**
     * Returns the current amount of sunlight at given position relative to the chunk.
     *
     * @param pos Position of the block relative to corner of the chunk
     * @return Current sunlight
     */
    default byte getSunlight(Vector3ic pos) {
        return getSunlight(pos.x(), pos.y(), pos.z());
    }

    /**
     * Returns the current amount of sunlight at given position relative to the chunk.
     *
     * @param x X offset from the corner of the chunk
     * @param y Y offset from the corner of the chunk
     * @param z Z offset from the corner of the chunk
     * @return Current sunlight
     */
    byte getSunlight(int x, int y, int z);

    /**
     * Sets the amount of sunlight at given position relative to the chunk.
     *
     * @param pos    Position of the block relative to corner of the chunk
     * @param amount Amount of sunlight to set this block to
     * @return False if the amount is same as the old value, true otherwise
     */
    default boolean setSunlight(Vector3ic pos, byte amount) {
        return setSunlight(pos.x(), pos.y(), pos.z(), amount);
    }

    /**
     * Sets the amount of sunlight at given position relative to the chunk.
     *
     * @param x X offset from the corner of the chunk
     * @param y Y offset from the corner of the chunk
     * @param z Z offset from the corner of the chunk
     * @return False if the amount is same as the old value, true otherwise
     */
    boolean setSunlight(int x, int y, int z, byte amount);

    /**
     * Returns current value of sunlight regeneration for given block relative to the chunk.
     *
     * @param pos Position of the block relative to corner of the chunk
     * @return Current sunlight regeneration
     */
    default byte getSunlightRegen(Vector3ic pos) {
        return getSunlightRegen(pos.x(), pos.y(), pos.z());
    }

    /**
     * Returns current value of sunlight regeneration for given block relative to the chunk.
     *
     * @param x X offset from the corner of the chunk
     * @param y Y offset from the corner of the chunk
     * @param z Z offset from the corner of the chunk
     * @return Current sunlight regeneration
     */
    byte getSunlightRegen(int x, int y, int z);

    /**
     * Sets sunlight regeneration for given block relative to the chunk.
     *
     * @param pos    Position of the block relative to corner of the chunk
     * @param amount Sunlight regeneration amount
     * @return False if the amount is same as the old value, true otherwise
     */
    default boolean setSunlightRegen(Vector3ic pos, byte amount) {
        return setSunlightRegen(pos.x(), pos.y(), pos.z(), amount);
    }

    /**
     * Sets sunlight regeneration for given block relative to the chunk.
     *
     * @param x      X offset from the corner of the chunk
     * @param y      Y offset from the corner of the chunk
     * @param z      Z offset from the corner of the chunk
     * @param amount Sunlight regeneration amount
     * @return False if the amount is same as the old value, true otherwise
     */
    boolean setSunlightRegen(int x, int y, int z, byte amount);

    /**
     * Returns current amount of light for given block relative to the chunk.
     *
     * @param pos Position of the block relative to corner of the chunk
     * @return Current lightness
     */
    default byte getLight(Vector3ic pos) {
        return getLight(pos.x(), pos.y(), pos.z());
    }

    /**
     * Returns current amount of light for given block relative to the chunk.
     *
     * @param x X offset from the corner of the chunk
     * @param y Y offset from the corner of the chunk
     * @param z Z offset from the corner of the chunk
     * @return Current lightness
     */
    byte getLight(int x, int y, int z);

    /**
     * Sets lightness for given block relative to the chunk.
     *
     * @param pos    Position of the block relative to corner of the chunk
     * @param amount Lightness value
     * @return False if the amount is same as the old value, true otherwise
     */
    default boolean setLight(Vector3ic pos, byte amount) {
        return setLight(pos.x(), pos.y(), pos.z(), amount);
    }

    /**
     * Sets lightness for given block relative to the chunk.
     *
     * @param x      X offset from the corner of the chunk
     * @param y      Y offset from the corner of the chunk
     * @param z      Z offset from the corner of the chunk
     * @param amount Lightness value
     * @return False if the amount is same as the old value, true otherwise
     */
    boolean setLight(int x, int y, int z, byte amount);

    /**
     * Returns how much of the light at a block is the glow of molten rock.
     * <p>
     * A second light channel, propagated exactly as the light is and never above it, so that the mesher can tell the
     * orange of lava from the near white of a torch. Like the light, it is never stored or sent: the client rebuilds
     * it from the blocks.
     *
     * @param pos Position of the block relative to corner of the chunk
     * @return Current warmth, nought to fifteen
     */
    default byte getWarmth(Vector3ic pos) {
        return getWarmth(pos.x(), pos.y(), pos.z());
    }

    /**
     * Returns how much of the light at a block is the glow of molten rock.
     *
     * @param x X offset from the corner of the chunk
     * @param y Y offset from the corner of the chunk
     * @param z Z offset from the corner of the chunk
     * @return Current warmth, nought to fifteen
     */
    byte getWarmth(int x, int y, int z);

    /**
     * Sets the warmth for given block relative to the chunk.
     *
     * @param pos    Position of the block relative to corner of the chunk
     * @param amount Warmth value
     * @return False if the amount is same as the old value, true otherwise
     */
    default boolean setWarmth(Vector3ic pos, byte amount) {
        return setWarmth(pos.x(), pos.y(), pos.z(), amount);
    }

    /**
     * Sets the warmth for given block relative to the chunk.
     *
     * @param x      X offset from the corner of the chunk
     * @param y      Y offset from the corner of the chunk
     * @param z      Z offset from the corner of the chunk
     * @param amount Warmth value
     * @return False if the amount is same as the old value, true otherwise
     */
    boolean setWarmth(int x, int y, int z, byte amount);

    /**
     * Whether any warmth has ever been written into this chunk.
     * <p>
     * Merging a light channel between two chunks sweeps a whole face twice per side, so the warmth propagator is only
     * worth running where there is, or was, something warm. Once true it stays true.
     *
     * @return True if this chunk has ever held warmth
     */
    boolean hasWarmth();

    int getEstimatedMemoryConsumptionInBytes();

    ChunkBlockIterator getBlockIterator();

    void markReady();

    boolean isReady();

    void deflate();

    void deflateSunlight();

    void dispose();

    boolean isDisposed();

    void prepareForReactivation();

    // TODO: Expose appropriate iterators, remove this method
    EntityData.ChunkStore.Builder encode();

    boolean isDirty();

    void setDirty(boolean dirty);
}
