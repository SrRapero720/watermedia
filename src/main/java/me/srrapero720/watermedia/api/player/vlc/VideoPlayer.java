package me.srrapero720.watermedia.api.player.vlc;

import uk.co.caprica.vlcj.factory.MediaPlayerFactory;
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat;
import me.srrapero720.watermedia.api.player.PlayerAPI;
import me.srrapero720.watermedia.api.player.SyncBasePlayer;
import me.srrapero720.watermedia.api.rendering.RenderAPI;
import me.srrapero720.watermedia.core.tools.annotations.Experimental;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL21;

import java.awt.*;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import static me.srrapero720.watermedia.WaterMedia.LOGGER;

/**
 * WARNING: Experimental
 * this class works same as SyncVideoPlayer but uses a PBO
 * Use it only to test + contribute to its functionality
 */
@Experimental
public class VideoPlayer extends SyncBasePlayer {
    private static final Marker IT = MarkerManager.getMarker("VideoPlayer");

    private final int texture;
    private final int pbo;
    private volatile int width = 1;
    private volatile int height = 1;
    private ByteBuffer buffer;
    private ByteBuffer pboBuffer;
    private volatile Throwable exception;
    private final BufferHelper bufferHelper;

    protected final Executor playerThreadEx;
    protected final ReentrantLock renderLock = new ReentrantLock();
    protected final AtomicBoolean updateFrame = new AtomicBoolean(false);
    protected final AtomicBoolean updateFirstFrame = new AtomicBoolean(false);
    protected final AtomicBoolean forceFirstFrame = new AtomicBoolean(false);

    /**
     * Creates a player instance
     * @param playerThreadEx executor of render thread for an async task (normally <code>Minecraft.getInstance()</code>)
     * @deprecated Future replacement is a static method inside {@link PlayerAPI}.
     * PlayerAPI (including all Players) are intended to be rewrited in 2.1.0
     */
    @Deprecated
    public VideoPlayer(Executor playerThreadEx) { this(null, playerThreadEx, DEFAULT_BUFFER_HELPER); }


    /**
     * Creates a player instance
     * @param factory custom MediaPlayerFactory instance
     * @param playerThreadEx executor of render thread for an async task (normally <code>Minecraft.getInstance()</code>)
     * @deprecated Future replacement is a static method inside {@link PlayerAPI}.
     * PlayerAPI (including all Players) are intended to be rewrited in 2.1.0
     */
    @Deprecated
    public VideoPlayer(MediaPlayerFactory factory, Executor playerThreadEx) { this(factory, playerThreadEx, DEFAULT_BUFFER_HELPER); }

    /**
     * Creates a player instance
     * @param playerThreadEx executor of render thread for an async task (normally <code>Minecraft.getInstance()</code>)
     * @param bufferHelper helper to create IntBuffers
     * @deprecated mod now integrates an own MemoryTracker with a BufferAlloc
     */
    @Deprecated
    public VideoPlayer(Executor playerThreadEx, BufferHelper bufferHelper) { this(null, playerThreadEx, bufferHelper); }

    /**
     * Creates a player instance
     * @param factory custom MediaPlayerFactory instance
     * @param playerThreadEx executor of render thread for an async task (normally <code>Minecraft.getInstance()</code>)
     * @param bufferHelper helper to create IntBuffers
     * @deprecated mod now integrates an own MemoryTracker with a BufferAlloc
     */
    @Deprecated
    public VideoPlayer(MediaPlayerFactory factory, Executor playerThreadEx, BufferHelper bufferHelper) {
        super();
        this.playerThreadEx = playerThreadEx;
        this.texture = GL11.glGenTextures();
        this.pbo = GL15.glGenBuffers();
        this.bufferHelper = bufferHelper;
        this.init(factory, (mediaPlayer, nativeBuffers, bufferFormat) -> {
            renderLock.lock(); // we are running in a native thread!! careful
            try {
                if (buffer == null) return;
                ((Buffer) nativeBuffers[0]).rewind();
                buffer.put(nativeBuffers[0]);
                ((Buffer) buffer).rewind();
                updateFrame.set(true);
            } catch (Throwable t) {
                if (exception == null) {
                    exception = t;
                    LOGGER.fatal(IT, "Failed due process native buffers", t);
                }
            } finally {
                renderLock.unlock();
            }
        }, (sourceWidth, sourceHeight) -> {
            renderLock.lock();
            try {
                width = sourceWidth;
                height = sourceHeight;
                buffer = bufferHelper.create(sourceWidth * sourceHeight * 4);
                updateFrame.set(true);
                updateFirstFrame.set(true);
            } catch (Throwable t) {
                if (exception == null) {
                    exception = t;
                    LOGGER.fatal(IT, "Failed due create ByteBuffer", t);
                }
            } finally {
                renderLock.unlock();
            }
            return new BufferFormat("RGBA", sourceWidth, sourceHeight, new int[]{sourceWidth * 4}, new int[]{sourceHeight});
        });
        if (raw() == null) {
            GL11.glDeleteTextures(texture);
        }

        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, pbo);
        GL15.glBufferData(GL21.GL_PIXEL_UNPACK_BUFFER, this.width * this.height * 4L, GL15.GL_STREAM_DRAW);
        GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
    }

    /**
     * Forces {@link RenderAPI#applyBuffer(ByteBuffer, int, int, int, boolean)} to always use glTexImage2D instead of glTexSubImage2D
     * By default for performance purposes we use glTexSubImage2D,
     * but it may cause rendering issues on Minecraft Screens
     * @param forced force always glTexImage2D
     * @deprecated renamed to {@link #texSubMode(boolean)}
     */
    @Deprecated
    public void firstFrameMode(boolean forced) { this.forceFirstFrame.set(forced); }

    /**
     * Forces {@link RenderAPI#applyBuffer(ByteBuffer, int, int, int, boolean)} to always use glTexImage2D instead of glTexSubImage2D
     * By default for performance purposes we use glTexSubImage2D,
     * but it may cause rendering issues on Minecraft Screens
     * @param mode force always glTexImage2D
     */
    public void texSubMode(boolean mode) { this.forceFirstFrame.set(mode); }

    /**
     * Send texture buffer to OpenGL
     * always run this on render thread, and before use texture id
     * @deprecated in rendering, doesn't have sense just get texture without pre-render.
     * Now pre-render is collapsed in one method
     * Use instead {@link #getGlTexture()}, ensure you store texture ID after execute
     * @return gl texture ID
     */
    @Deprecated
    public int prepareTexture() {
        this.preRender();
        return texture;
    }

    /**
     * WARNING: use it at your own risk
     * Returns current defined buffer, may be null or may cause concurrency issues
     * @return Buffer containing current frame
     */
    public ByteBuffer getBuffer() {
        return buffer;
    }

    /**
     * WARNING: use it at your own risk
     * Returns current defined buffer, may be null
     * @return Buffer containing current frame
     */
    public ByteBuffer getBufferLock() {
        try {
            renderLock.lock();
            return buffer;
        } finally {
            renderLock.unlock();
        }
    }

    public ReentrantLock getRenderLock() {
        return renderLock;
    }

    /**
     * Send texture buffer to OpenGL
     * always run this on render thread, and before use texture id
     * @deprecated in rendering, doesn't have sense just get texture without pre-render.
     * Now pre-render is collapsed in one method
     * Use instead {@link #getGlTexture()}, ensure you store texture ID after execute
     */
    @Deprecated
    public void preRender() {
        if (raw() == null) return;
        renderLock.lock();
        try {
            // BIND PBO
            GL15.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, pbo);

            // COPY TO PBO
            pboBuffer = GL15.glMapBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, GL15.GL_WRITE_ONLY, this.width * this.height * 4L, pboBuffer);
            pboBuffer.put(buffer);
            GL15.glUnmapBuffer(GL21.GL_PIXEL_UNPACK_BUFFER);

            // PARAMS
            GL11.glBindTexture(GL11.GL_TEXTURE_2D, texture);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
            GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
            GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, width, height, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0);

            // UNBIND PBO
            GL21.glBindBuffer(GL21.GL_PIXEL_UNPACK_BUFFER, 0);
        } finally {
            renderLock.unlock();
        }
    }


    /**
     * Media Buffer width
     * @return Buffer texture width in px
     */
    public int getWidth() { return width; }

    /**
     * Media Buffer height
     * @return Buffer texture height in px
     */
    public int getHeight() {
        return height;
    }

    /**
     * Texture for OpenGL
     * @return texture id, if MediaPlayer is broken returns -1
     * @deprecated in rendering, doesn't have sense just get texture without pre-render.
     * Now pre-render is collapsed in one method
     * Use instead {@link #getGlTexture()}, ensure you store texture ID after execute
     */
    @Deprecated
    public int getTexture() {
        return texture;
    }

    /**
     * Send texture buffer to OpenGL using VideoPlayer texture id
     * always run this on render thread
     */
    public int getGlTexture() {
        this.preRender();
        return texture;
    }

    /**
     * Returns a Dimension instance with current byffer size
     * values are obtained after build IntBuffer first time
     * @return current buffer dimensions, null if raw player isn't created
     */
    public Dimension getDimensions() {
        if (raw() == null) return null;
        return new Dimension(width, height);
    }

    /**
     * Returns a Dimension instance by VLC
     * @return current buffer dimensions, null if raw player isn't created or by any internal VLC error
     */
    public Dimension getMediaDimensions() {
        if (raw() == null) return null;
        return raw().mediaPlayer().video().videoDimension();
    }

    /**
     * Releases all resources of the player
     */
    @Override
    public void release() {
        playerThreadEx.execute(() -> {
            GL11.glDeleteTextures(texture);
            GL21.glDeleteBuffers(pbo);
            if (bufferHelper == DEFAULT_BUFFER_HELPER) RenderAPI.freeByteBuffer(buffer);
        });
        super.release();
        buffer = null;
    }

    @Deprecated
    public interface BufferHelper { @Deprecated ByteBuffer create(int size); }
    public static final BufferHelper DEFAULT_BUFFER_HELPER = RenderAPI::createByteBuffer;
}