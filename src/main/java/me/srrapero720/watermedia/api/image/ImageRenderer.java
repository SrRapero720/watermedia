package me.srrapero720.watermedia.api.image;

import me.srrapero720.watermedia.api.image.decoders.GifDecoder;
import me.srrapero720.watermedia.api.math.MathAPI;
import me.srrapero720.watermedia.api.rendering.RenderAPI;
import org.lwjgl.opengl.GL11;

import java.awt.*;
import java.awt.image.BufferedImage;

public class ImageRenderer {
    public final int width;
    public final int height;
    public final int[] textures;
    public final long[] delay;
    public final long duration;
    private BufferedImage image;
    private GifDecoder decoder;

    public int remaining;

    /**
     * creates a new instance of an ImageRenderer
     * @param image picture to use
     * method is going to begin package-protected
     */
    ImageRenderer(BufferedImage image) {
        this.image = image;
        if (image == null) throw new NullPointerException();
        this.width = image.getWidth();
        this.height = image.getHeight();
        this.textures = new int[] { -1 };
        this.delay = new long[] { 0 };
        this.duration = 1;
        this.decoder = null;
    }

    /**
     * creates a new instance of an ImageRenderer
     * @param decoder picture to use
     * method is going to begin package-protected
     */
    ImageRenderer(GifDecoder decoder) {
        this.decoder = decoder;
        if (decoder == null) throw new NullPointerException();

        Dimension frameSize = decoder.getFrameSize();
        width = (int) frameSize.getWidth();
        height = (int) frameSize.getHeight();
        textures = new int[decoder.getFrameCount()];
        delay = new long[decoder.getFrameCount()];

        this.image = null;
        this.remaining = decoder.getFrameCount();
        long time = 0;
        for (int i = 0; i < decoder.getFrameCount(); i++) {
            textures[i] = -1;
            delay[i] = time;
            time += decoder.getDelay(i);
        }

        duration = time;
    }

    /**
     * gets texture id based on time in millis
     * use API to calculate time
     * @param time time in millis
     * @return texture id usable on OpenGL
     * @see ImageRenderer#texture(int, long, boolean) too
     */
    public int texture(long time) {
        if (textures == null) return -1;
        if (textures.length == 1) return texture(0);
        int last = texture(0);
        for (int i = 1; i < delay.length; i++) {
            if (delay[i] > time) break;
            last = texture(i);
        }
        return last;
    }

    /**
     * gets texture id from a texture array
     * @param index image index
     * @return texture id usable on OpenGL
     */
    public int texture(int index) {
        if (this.textures[index] == -1) {
            if (decoder != null) {
                this.textures[index] = RenderAPI.applyBuffer(this.decoder.getFrame(index), width, height);
                if (--this.remaining <= 0) decoder = null; // uploaded in VRAM
            } else if (image != null) {
                this.textures[index] = RenderAPI.applyBuffer(this.image, width, height);
                image = null; // uploaded in VRAM
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

    /**
     * This method just drains buffers but not releases OpenGL texture
     */
    public void flush() {
        if (image != null) image.flush();
        if (decoder != null) {
            for (int i = 0; i < decoder.getFrameCount(); i++) decoder.getFrame(i).flush();
            decoder = null;
        }
    }
    /**
     * This method drain buffers and release OpenGL textures
     */
    public void release() {
        for (int i: textures) if (i != -1) GL11.glDeleteTextures(i);
        flush();
    }
}
