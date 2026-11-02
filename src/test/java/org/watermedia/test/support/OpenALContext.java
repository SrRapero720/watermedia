package org.watermedia.test.support;

import org.junit.jupiter.api.Assumptions;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALCCapabilities;
import org.lwjgl.openal.EXTEfx;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

/** The default OpenAL device with a context current on the opening thread, as a host sound system provides it. */
public record OpenALContext(long device, long context) implements AutoCloseable {

    /** Opens the device and makes its context current, aborting the calling tests when OpenAL has no natives or no output. */
    public static OpenALContext open() {
        final long device;
        final ALCCapabilities capabilities;
        try {
            device = ALC10.alcOpenDevice((ByteBuffer) null);
            capabilities = device == 0L ? null : ALC.createCapabilities(device);
        } catch (final Throwable unavailable) {
            // MISSING OR BROKEN NATIVES SURFACE AS LINKAGE ERRORS FROM THE FIRST ALC CALL
            return Assumptions.abort("OpenAL natives unavailable: " + unavailable);
        }
        Assumptions.assumeTrue(device != 0L, "no OpenAL output device");
        // EFX HOSTS (SOUND PHYSICS) REQUEST AUXILIARY SENDS, SO THE CONTEXT ASKS FOR SOME WHEN THE DEVICE OFFERS EFX
        final long context = capabilities.ALC_EXT_EFX
                ? ALC10.alcCreateContext(device, new int[] { EXTEfx.ALC_MAX_AUXILIARY_SENDS, 4, 0 })
                : ALC10.alcCreateContext(device, (IntBuffer) null);
        if (context == 0L || !ALC10.alcMakeContextCurrent(context)) {
            if (context != 0L) ALC10.alcDestroyContext(context);
            ALC10.alcCloseDevice(device);
            return Assumptions.abort("no current OpenAL context");
        }
        AL.createCapabilities(capabilities);
        return new OpenALContext(device, context);
    }

    @Override
    public void close() {
        ALC10.alcMakeContextCurrent(0L);
        ALC10.alcDestroyContext(this.context);
        ALC10.alcCloseDevice(this.device);
    }
}
