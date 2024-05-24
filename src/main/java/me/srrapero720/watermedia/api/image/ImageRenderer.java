package me.srrapero720.watermedia.api.image;

import me.srrapero720.watermedia.api.image.decoders.GifDecoder;
import me.srrapero720.watermedia.api.math.MathAPI;
import me.srrapero720.watermedia.api.rendering.RenderAPI;
import me.srrapero720.watermedia.tools.ByteTool;

import java.awt.image.BufferedImage;
import java.nio.ByteBuffer;
import java.util.Arrays;

public class ImageRenderer {
    public final int width;
    public final int height;
    public final int[] textures;
    public final long[] delay;
    public final long duration;
    private ByteBuffer[] images;

    public boolean flushed;
    public int remaining;

    /**
     * creates a new instance of an ImageRenderer
     * @param image picture to use
     * method is going to begin package-protected
     */
    ImageRenderer(BufferedImage image) {
        if (image == null) throw new NullPointerException();
        this.images = new ByteBuffer[] { RenderAPI.getRawImageBuffer(image) };
        this.width = image.getWidth();
        this.height = image.getHeight();
        this.textures = new int[] { -1 };
        this.delay = new long[1];
        this.duration = 1;
        this.remaining = this.images.length;
    }

    /**
     * creates a new instance of an ImageRenderer
     * @param decoder picture to use
     * method is going to begin package-protected
     */
    ImageRenderer(GifDecoder decoder) {
        if (decoder == null) throw new NullPointerException();
        this.images = RenderAPI.getRawImageBuffer(decoder.getFrames());
        this.width = decoder.getWidth();
        this.height = decoder.getHeight();
        this.textures = new int[decoder.getFrameCount()];
        this.delay = decoder.getDelayFrames();
        this.duration = decoder.getDuration();
        this.remaining = this.images.length;
        Arrays.fill(textures, -1);
    }

    /**
     * gets texture id based on time in millis
     * use API to calculate time
     * @param time time in millis
     * @return texture id usable on OpenGL
     * @see ImageRenderer#texture(int, long, boolean) too
     */
    public int texture(long time) {
        if (textures == null) return 0;
        if (textures.length == 1) return texture(0);

        for (int i = 0; i < delay.length; i++) {
            time -= delay[i];
            if (time <= 0)
                return texture(i);
        }
        return texture(images.length - 1);
    }

    /**
     * gets texture id from a texture array
     * @param index image index
     * @return texture id usable on OpenGL
     */
    public int texture(int index) {
        if (this.textures[index] == -1) {
            this.textures[index] = RenderAPI.uploadBufferTexture(this.images[index], width, height);
            this.remaining -= 1;
            if (this.remaining == 0) {
                this.flush();
            }
        }
        return textures[index];
    }

    /**
     * Calculate texture based on tick time (1s/20t) plus deltaTime (missing ms on ticks)
     * make tick count by yourself
     * @param tick Tick count
     * @param deltaTime extra ms to add
     * @param loop enable looping if tick count overflows duration
     * @return OpenGL texture ID
     */
    public int texture(long tick, long deltaTime, boolean loop) {
        long time = (tick * 50L) + deltaTime;
        if (duration > 0 && time > duration && loop) time %= duration;
        return texture(time);
    }

    /**
     * Calculate texture based on tick time (1s/20t) plus deltaTime (missing ms on ticks)
     * make tick count by yourself
     * @param tick Tick count
     * @param deltaTime extra ms to add
     * @param loop enable looping if tick count overflows duration
     * @deprecated <p>Tick type was changed from int to long following the time counting standard.</p>
     * Use instead {@link ImageRenderer#texture(long, long, boolean)}
     * @return OpenGL texture ID
     */
    @Deprecated
    public int texture(int tick, long deltaTime, boolean loop) {
        long time = (tick * 50L) + deltaTime;
        if (duration > 0 && time > duration && loop) time %= duration;
        return texture(time);
    }

    /**
     * Calculate texture based on tick time (1s/20t) plus partialTicks (fraction of a tick)
     * make tick count by yourself
     * @param tick Tick count
     * @param partialTicks fraction of tick time
     * @param loop enable looping if tick count overflows duration
     * @return OpenGL texture ID
     */
    public int texture(long tick, float partialTicks, boolean loop) {
        return texture(tick, MathAPI.tickToMs(partialTicks), loop);
    }

    public boolean isFlushed() {
        return flushed;
    }

    /**
     * This method just drains buffers but not releases OpenGL texture
     */
    protected void flush() {
        if (flushed) throw new IllegalStateException("Buffers are already flushed");
        for (ByteBuffer buffer: this.images) {
            RenderAPI.freeByteBuffer(buffer);
        }
        this.images = new ByteBuffer[this.images.length];
        this.flushed = true;
    }

    /**
     * Moves the image data to RAM (from VRAM) and resets the state of the ImageRender as never requested
     */
    public void reset() {
        if (!flushed) throw new IllegalStateException("Buffers are not flushed");
        this.remaining = this.images.length;
        for (int i = 0; i < this.images.length; i++) {
            this.images[i] = RenderAPI.getTextureBuffer(this.textures[i], width, height);
            RenderAPI.deleteTexture(this.textures);
            Arrays.fill(this.textures, -1);
        }
    }

    /**
     * This method drain buffers and release OpenGL textures
     */
    public void release() {
        if (flushed) {
            RenderAPI.deleteTexture(this.textures);
            Arrays.fill(this.textures, -1);
        } else {
            this.flush();
            RenderAPI.deleteTexture(ByteTool.filterValue(this.textures, -1));
            Arrays.fill(this.textures, -1);
        }
    }

    static class Absolute extends ImageRenderer {

        Absolute(BufferedImage image) {
            super(image);
        }

        Absolute(GifDecoder decoder) {
            super(decoder);
        }

        @Override public void release() {}
    }
}
