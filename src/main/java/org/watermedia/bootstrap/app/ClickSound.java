package org.watermedia.bootstrap.app;

import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.MarkerManager;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.stb.STBVorbis;
import org.lwjgl.system.MemoryUtil;
import org.watermedia.WaterMedia;
import org.watermedia.tools.IOTool;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

import static org.lwjgl.openal.AL10.*;

/** The tick played on menu and settings navigation, decoded once into an OpenAL buffer of the app's own device. */
public final class ClickSound {
    private static final Marker IT = MarkerManager.getMarker(ClickSound.class.getSimpleName());

    /** Whether {@link #play()} makes a sound; the app preferences set it. */
    public boolean enabled = true;
    private int source = -1;

    // ==========================================================================
    // PUBLIC API
    // ==========================================================================
    /** Opens the default OpenAL device and loads the tick; without audio the app keeps running silent. */
    public void open() {
        try {
            final long device = ALC10.alcOpenDevice((ByteBuffer) null);
            if (device == 0L) throw new IllegalStateException("Failed to open a new Audio Device");
            final long context = ALC10.alcCreateContext(device, (IntBuffer) null);
            ALC10.alcMakeContextCurrent(context);
            AL.createCapabilities(ALC.createCapabilities(device));
            this.load();
        } catch (final RuntimeException e) {
            WaterMedia.LOGGER.warn(IT, "App-side OpenAL audio unavailable; continuing without the click sound", e);
        }
    }

    /** Restarts the tick from its first sample. */
    public void play() {
        if (this.enabled && this.source > 0) {
            alSourceStop(this.source);
            alSourcePlay(this.source);
        }
    }

    // ==========================================================================
    // HELPERS
    // ==========================================================================
    private void load() {
        try (final InputStream in = IOTool.jarOpenFile("assets/watermedia/sounds/duck.ogg")) {
            final byte[] soundBytes = in.readAllBytes();

            final ByteBuffer oggBuffer = MemoryUtil.memAlloc(soundBytes.length);
            oggBuffer.put(soundBytes).flip();

            final IntBuffer channelsBuffer = MemoryUtil.memAllocInt(1);
            final IntBuffer sampleRateBuffer = MemoryUtil.memAllocInt(1);
            final ShortBuffer pcmBuffer = STBVorbis.stb_vorbis_decode_memory(oggBuffer, channelsBuffer, sampleRateBuffer);

            MemoryUtil.memFree(oggBuffer);
            if (pcmBuffer == null) {
                MemoryUtil.memFree(channelsBuffer);
                MemoryUtil.memFree(sampleRateBuffer);
                return;
            }

            final int channels = channelsBuffer.get(0);
            final int sampleRate = sampleRateBuffer.get(0);
            MemoryUtil.memFree(channelsBuffer);
            MemoryUtil.memFree(sampleRateBuffer);

            final int buffer = alGenBuffers();
            alBufferData(buffer, channels == 1 ? AL_FORMAT_MONO16 : AL_FORMAT_STEREO16, pcmBuffer, sampleRate);
            MemoryUtil.memFree(pcmBuffer);

            this.source = alGenSources();
            alSourcei(this.source, AL_BUFFER, buffer);
            alSourcef(this.source, AL_GAIN, 0.2f);
        } catch (final Exception e) {
            WaterMedia.LOGGER.warn(IT, "Failed to load sound effect", e);
        }
    }
}
