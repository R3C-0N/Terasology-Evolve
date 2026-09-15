// Copyright 2021 The Terasology Foundation
// SPDX-License-Identifier: Apache-2.0

package org.terasology.engine.rendering.assets.mesh.resource;

import org.lwjgl.BufferUtils;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.util.function.Consumer;

/**
 * buffered resource is resource for graphics resource.
 *
 * Access to a DirectBuffer is close to equivelent to accessing an array so managing it in this
 * form is more optimal when transferring to the hardware.
 *
 * Used for managing data for vertex and index data
 */
public abstract class BufferedResource {

    /**
     * Frees a direct buffer's native memory on the spot, or null where this JVM offers no way to.
     * <p>
     * On its own the JDK frees that memory only once a collection finds the buffer unreachable. When the direct memory
     * limit ({@code -XX:MaxDirectMemorySize}) is reached before that, the next allocation runs a full, stop-the-world
     * {@code System.gc()} - one per buffer that does not fit, from whichever thread is building a mesh.
     */
    private static final MethodHandle INVOKE_CLEANER = findCleaner();

    protected int inSize = 0;
    protected ByteBuffer buffer = BufferUtils.createByteBuffer(0);
    private short version;

    /**
     * increase version flag for change
     */
    public void mark() {
        this.version++;
    }

    /**
     * the version of the buffer is used to determine if the contents has changed. this should notify the end user of the buffer to sync the
     * data back to the driver
     *
     * @return the version flag
     */
    public int getVersion() {
        return this.version;
    }

    ByteBuffer buffer() {
        return this.buffer;
    }

    /**
     * the size of the buffer allocated but the capacity can be larger to account for growth.
     *
     * @return the size of the active buffer
     */
    public int inSize() {
        return inSize;
    }

    /**
     * determines if the buffer is empty
     *
     * @return an empty buffer
     */
    public abstract boolean isEmpty();

    /**
     * append the buffer to the current BufferedResource
     * <p>
     * make sure the data is structured in a way that the buffer expects.
     *
     * All {@link BufferedResource} are all in native order. data will
     * be garbage if the order of the buffer is different from the endianness system.
     *
     * @param copyBuffer the buffer to replace the contents with
     */
    public void put(ByteBuffer copyBuffer) {
        // ensure the buffer has the correct capacity
        reserve(this.inSize + copyBuffer.limit());

        // rewind buffer
        copyBuffer.rewind();

        buffer.position(this.inSize);
        buffer.put(copyBuffer);

        this.inSize += copyBuffer.limit();
        mark();
    }

    /**
     * append the buffer to the current BufferedResource
     *
     * <p>
     * make sure the data is structured in a way that the buffer expects.
     *
     * @param resource the resource to append the contents with
     */
    public void put(BufferedResource resource) {
        // ensure the buffer has the correct capacity
        reserve(this.inSize + resource.inSize);

        ByteBuffer copyBuffer = resource.buffer;
        copyBuffer.limit(resource.inSize);
        copyBuffer.rewind();

        buffer.position(this.inSize);
        buffer.put(copyBuffer);
        this.inSize += resource.inSize;
        mark();
    }


    /**
     * replace this resource directory with the contents from another buffer
     * <p>
     * make sure the data is structured in a way that the buffer expects.
     *
     * All {@link BufferedResource} are all in native order. data will
     * be garbage if the order of the buffer is different from the endianness system.
     *
     * @param copyBuffer the buffer to replace the contents with
     */
    public void replace(ByteBuffer copyBuffer) {
        reserve(copyBuffer.limit());

        buffer.rewind();
        buffer.put(copyBuffer);

        this.inSize = copyBuffer.limit();
        mark();
    }

    /**
     * replace this with the resource supplied
     *
     * @param resource the resource to replace the contents with
     */
    public void replace(BufferedResource resource) {
        reserve(resource.inSize);

        ByteBuffer copyBuffer = resource.buffer;
        copyBuffer.limit(resource.inSize);
        copyBuffer.rewind();

        buffer.rewind();
        buffer.put(copyBuffer);

        this.inSize = resource.inSize;
        mark();
    }

    /**
     * expand the capacity of the buffer without increasing {@link #inSize()}
     *
     * @param size the size of the capacity of the buffer
     */
    protected void reserve(int size) {
        if (size > buffer.capacity()) {
            int newCap = Math.max(buffer.capacity() << 1, size);
            ByteBuffer newBuffer = BufferUtils.createByteBuffer(newCap);
            buffer.limit(this.inSize);
            buffer.position(0);
            newBuffer.put(buffer);
            swapIn(newBuffer);
        }
        mark();
    }

    /**
     * allocate the buffer to match {@link #inSize()}
     *
     * @param size the size the buffer should be allocated to
     */
    protected void allocate(int size) {
        ensureCapacity(size);
        this.inSize = size;
        mark();
    }

    /**
     * ensure the buffer is large enough for the size requested. {@link #inSize()} will use the current size if the buffer is already larger
     * then the requested size else the size is set to the requested
     *
     * @param size the size of the buffer
     */
    protected void ensureCapacity(int size) {
        if (size > buffer.capacity()) {
            int newCap = Math.max(this.buffer.capacity() << 1, size);
            ByteBuffer newBuffer = BufferUtils.createByteBuffer(newCap);
            buffer.limit(this.inSize);
            buffer.position(0);
            newBuffer.put(buffer);
            swapIn(newBuffer);
        }
        if (size > this.inSize) {
            this.inSize = size;
        }
        buffer.limit(inSize);
        mark();
    }

    /**
     * write the results of the buffer to a consumer. the buffer is rewinded to the back and the limit is set to the expected {@link
     * #inSize()}
     *
     * @param consumer
     */
    public void writeBuffer(Consumer<ByteBuffer> consumer) {
        buffer.rewind();
        buffer.limit(inSize);
        consumer.accept(buffer);
    }

    /**
     * shrink the buffer capacity to match {@link #inSize()}
     */
    public void squeeze() {
        if (this.inSize != buffer.capacity()) {
            ByteBuffer newBuffer = BufferUtils.createByteBuffer(this.inSize);
            buffer.limit(this.inSize);
            buffer.position(0);
            newBuffer.put(buffer);
            swapIn(newBuffer);
        }
    }

    /**
     * Gives the buffer's memory back now and leaves the resource empty.
     * <p>
     * For data that has been copied elsewhere and will not be read again - a mesh already uploaded to the GPU - so that
     * its native memory does not wait for a garbage collection. The resource stays usable: writing to it again allocates
     * a new buffer.
     */
    public void release() {
        ByteBuffer released = buffer;
        buffer = BufferUtils.createByteBuffer(0);
        inSize = 0;
        mark();
        free(released);
    }

    /** Makes the grown or shrunk copy the buffer, and frees the one it was copied from. */
    private void swapIn(ByteBuffer newBuffer) {
        ByteBuffer old = this.buffer;
        this.buffer = newBuffer;
        free(old);
    }

    private static void free(ByteBuffer released) {
        if (INVOKE_CLEANER == null || !released.isDirect() || released.capacity() == 0) {
            return;
        }
        try {
            INVOKE_CLEANER.invokeExact(released);
        } catch (Throwable e) {
            // A slice or a duplicate does not own its memory and cannot be freed this way; the collector will.
        }
    }

    private static MethodHandle findCleaner() {
        try {
            Class<?> unsafeClass = Class.forName("sun.misc.Unsafe");
            Field theUnsafe = unsafeClass.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            return MethodHandles.lookup()
                    .findVirtual(unsafeClass, "invokeCleaner", MethodType.methodType(void.class, ByteBuffer.class))
                    .bindTo(theUnsafe.get(null));
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }
}
