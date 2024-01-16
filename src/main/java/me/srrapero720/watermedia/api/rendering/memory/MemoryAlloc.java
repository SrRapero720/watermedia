package me.srrapero720.watermedia.api.rendering.memory;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

public class MemoryAlloc {
    private static final MemoryUtil.MemoryAllocator ALLOCATOR;
    static {
        ALLOCATOR = MemoryUtil.getAllocator(false);
    }

    public static ByteBuffer create(int pSize) {
        long i = ALLOCATOR.malloc(pSize);
        if (i == 0L) {
            throw new OutOfMemoryError("Failed to allocate " + pSize + " bytes");
        } else {
            return MemoryUtil.memByteBuffer(i, pSize);
        }
    }

    public static ByteBuffer resize(ByteBuffer pBuffer, int pByteSize) {
        long i = ALLOCATOR.realloc(MemoryUtil.memAddress0(pBuffer), pByteSize);
        if (i == 0L) {
            throw new OutOfMemoryError("Failed to resize buffer from " + pBuffer.capacity() + " bytes to " + pByteSize + " bytes");
        } else {
            return MemoryUtil.memByteBuffer(i, pByteSize);
        }
    }
}