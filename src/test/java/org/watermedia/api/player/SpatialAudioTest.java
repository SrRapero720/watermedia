package org.watermedia.api.player;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.io.TempDir;
import org.lwjgl.openal.AL;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.EXTEfx;
import org.lwjgl.system.MemoryUtil;
import org.watermedia.api.util.SampleType;
import org.watermedia.test.support.Fixtures;
import org.watermedia.test.support.MediaBootstrap;
import org.watermedia.test.support.OpenALContext;
import org.watermedia.test.support.PlayerWait;

import java.lang.reflect.Field;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Verifies {@link SFXEngine.SpatialAudio} validation and the spatial {@link ALEngine}; the OpenAL cases skip without an output device. */
@DisplayName("Spatial audio")
public class SpatialAudioTest {

    // AN ENVIRONMENT ADAPTER WHOSE DETACH FAILS, AS A BROKEN HOST EFFECT MOD WOULD
    private static final SFXEngine.SpatialAudio.Environment RESET_FAILURE = new SFXEngine.SpatialAudio.Environment() {
        @Override
        public void process(final int source, final SFXEngine.SpatialAudio audio) {}

        @Override
        public void reset(final int source) { throw new IllegalStateException("reset failed"); }
    };

    @BeforeAll
    static void boot() {
        MediaBootstrap.client();
    }

    @Test
    @DisplayName("SpatialAudio rejects non-finite coordinates and invalid distances")
    void rejectsInvalidCoordinatesAndDistances() {
        for (final double value: new double[] { Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.MAX_VALUE }) {
            assertThrows(IllegalArgumentException.class, () -> new SFXEngine.SpatialAudio(value, 1, 1, 1, 16, 1, false, null));
            assertThrows(IllegalArgumentException.class, () -> new SFXEngine.SpatialAudio(1, value, 1, 1, 16, 1, false, null));
            assertThrows(IllegalArgumentException.class, () -> new SFXEngine.SpatialAudio(1, 1, value, 1, 16, 1, false, null));
        }
        for (final float value: new float[] { Float.NaN, Float.POSITIVE_INFINITY, -1, 0 }) {
            assertThrows(IllegalArgumentException.class, () -> new SFXEngine.SpatialAudio(1, 1, 1, value, 16, 1, false, null));
            assertThrows(IllegalArgumentException.class, () -> new SFXEngine.SpatialAudio(1, 1, 1, 1, value, 1, false, null));
        }
        assertThrows(IllegalArgumentException.class, () -> new SFXEngine.SpatialAudio(1, 1, 1, 16, 16, 1, false, null));
        assertThrows(IllegalArgumentException.class, () -> new SFXEngine.SpatialAudio(1, 1, 1, 1, 16, Float.NaN, false, null));
        assertThrows(IllegalArgumentException.class, () -> new SFXEngine.SpatialAudio(1, 1, 1, 1, 16, -1, false, null));
        assertThrows(IllegalArgumentException.class, () -> new SFXEngine.SpatialAudio(1, 1, 1, 1, 16, 1, true, null));
        assertDoesNotThrow(() -> new SFXEngine.SpatialAudio(0, 0, 0, 1, 16, 0, false, null));
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    @DisplayName("ALEngine (OpenAL, requires a current context)")
    class OpenAL {
        private OpenALContext al;

        @BeforeAll
        void openContext() {
            this.al = OpenALContext.open();
        }

        @AfterAll
        void closeContext() {
            if (this.al != null) this.al.close();
        }

        @Test
        @DisplayName("A spatial source applies its world position and keeps the host distance model")
        void appliesWorldPositionAndPreservesHostDistanceModel() {
            final int model = AL10.alGetInteger(AL10.AL_DISTANCE_MODEL);
            final ALEngine engine = PlayerAPI.alEngine(true);
            try {
                assertTrue(engine.spatial());
                final SFXEngine.SpatialAudio audio = new SFXEngine.SpatialAudio(12, 24, -36, 4, 64, 0.75f, false, null);
                assertTrue(engine.spatialAudio(audio));
                assertSame(audio, engine.spatialAudio());
                assertEquals(AL10.AL_FALSE, AL10.alGetSourcei(engine.source(), AL10.AL_SOURCE_RELATIVE));
                final float[] position = new float[3];
                AL10.alGetSourcefv(engine.source(), AL10.AL_POSITION, position);
                assertArrayEquals(new float[] { 12, 24, -36 }, position);
                assertEquals(4, AL10.alGetSourcef(engine.source(), AL10.AL_REFERENCE_DISTANCE));
                assertEquals(64, AL10.alGetSourcef(engine.source(), AL10.AL_MAX_DISTANCE));
                assertEquals(0.75f, AL10.alGetSourcef(engine.source(), AL10.AL_ROLLOFF_FACTOR));
                assertEquals(model, AL10.alGetInteger(AL10.AL_DISTANCE_MODEL));
                engine.spatialAudio(null);
                assertEquals(AL10.AL_TRUE, AL10.alGetSourcei(engine.source(), AL10.AL_SOURCE_RELATIVE));
                assertEquals(0, AL10.alGetSourcef(engine.source(), AL10.AL_ROLLOFF_FACTOR));
                assertEquals(AL10.AL_NO_ERROR, AL10.alGetError());
            } finally {
                engine.release();
            }
        }

        @Test
        @DisplayName("Environment callbacks see the world origin and a disabled processor leaves no effects")
        void callbacksRestoreWorldOriginAndClearEffectsWhenDisabled() {
            assumeTrue(AL.getCapabilities().ALC_EXT_EFX, "EFX unavailable");
            final ALEngine engine = PlayerAPI.alEngine(true);
            final int filter = EXTEfx.alGenFilters();
            EXTEfx.alFilteri(filter, EXTEfx.AL_FILTER_TYPE, EXTEfx.AL_FILTER_LOWPASS);
            final List<Thread> callers = new ArrayList<>();
            final SFXEngine.SpatialAudio.Environment effects = (source, audio) -> {
                callers.add(Thread.currentThread());
                final float[] position = new float[3];
                AL10.alGetSourcefv(source, AL10.AL_POSITION, position);
                assertArrayEquals(new float[] { 10, 20, 30 }, position);
                AL10.alSourcei(source, EXTEfx.AL_DIRECT_FILTER, filter);
                AL10.alSourcef(source, EXTEfx.AL_AIR_ABSORPTION_FACTOR, 5);
                AL10.alSource3f(source, AL10.AL_POSITION, -1, -2, -3);
            };
            try {
                final SFXEngine.SpatialAudio audio = new SFXEngine.SpatialAudio(10, 20, 30, 1, 32, 1, false, effects);
                engine.spatialAudio(audio);
                engine.spatialAudio(audio);
                assertEquals(List.of(Thread.currentThread(), Thread.currentThread()), callers);
                // A DISABLED PROCESSOR CAN RETURN WITHOUT TOUCHING AL; OLD EFFECTS MUST NOT SURVIVE
                engine.spatialAudio(new SFXEngine.SpatialAudio(10, 20, 30, 1, 32, 1, false, (source, state) -> {}));
                assertEquals(0, AL10.alGetSourcef(engine.source(), EXTEfx.AL_AIR_ABSORPTION_FACTOR));
                assertEquals(AL10.AL_NO_ERROR, AL10.alGetError());
            } finally {
                engine.release();
                EXTEfx.alDeleteFilters(filter);
            }
        }

        @Test
        @DisplayName("Processor and reset failures leave a dry listener-relative source")
        void callbackAndResetFailuresLeaveDrySource() {
            final ALEngine engine = PlayerAPI.alEngine(true);
            try {
                final SFXEngine.SpatialAudio broken = new SFXEngine.SpatialAudio(1, 2, 3, 1, 32, 1, false, (source, audio) -> {
                    throw new IllegalStateException("processor failed");
                });
                assertThrows(IllegalStateException.class, () -> engine.spatialAudio(broken));
                assertNull(engine.spatialAudio());
                assertEquals(AL10.AL_TRUE, AL10.alGetSourcei(engine.source(), AL10.AL_SOURCE_RELATIVE));
                engine.spatialAudio(new SFXEngine.SpatialAudio(1, 2, 3, 1, 32, 1, false, RESET_FAILURE));
                assertThrows(IllegalStateException.class, () -> engine.spatialAudio(null));
                assertNull(engine.spatialAudio());
                assertEquals(AL10.AL_TRUE, AL10.alGetSourcei(engine.source(), AL10.AL_SOURCE_RELATIVE));
                assertEquals(0, AL10.alGetSourcef(engine.source(), AL10.AL_ROLLOFF_FACTOR));
                assertEquals(AL10.AL_NO_ERROR, AL10.alGetError());
            } finally {
                engine.release();
            }
        }

        @Test
        @DisplayName("A reset failure during release still frees the source and leaves the engine released")
        void resetFailureOnReleaseStillReleases() {
            final ALEngine engine = PlayerAPI.alEngine(true);
            final int source = engine.source();
            assertTrue(engine.format(SampleType.S16, 1, 48_000));
            assertTrue(engine.spatialAudio(new SFXEngine.SpatialAudio(1, 2, 3, 1, 32, 1, false, RESET_FAILURE)));
            assertThrows(IllegalStateException.class, engine::release, "the host failure still reaches the caller");
            assertEquals(0, engine.source());
            assertFalse(AL10.alIsSource(source), "the source was deleted despite the failure");
            assertTrue(engine.released(), "a teardown that freed the source is not retried");
            assertFalse(engine.format(SampleType.S16, 1, 48_000), "the engine stays inert");
            assertEquals(AL10.AL_NO_ERROR, AL10.alGetError());
        }

        @Test
        @DisplayName("A wrong context throws and a released engine never touches a recycled source")
        void wrongContextAndLateUpdatesCannotTouchRecycledSource() {
            final ALEngine engine = PlayerAPI.alEngine(true);
            final SFXEngine.SpatialAudio audio = new SFXEngine.SpatialAudio(1, 2, 3, 1, 32, 1, false, null);
            final long other = ALC10.alcCreateContext(this.al.device(), (IntBuffer) null);
            assertNotEquals(0, other);
            // A CONFIGURED ENGINE, SO THE UPLOAD BELOW PASSES THE BASE CHECKS AND REACHES THE CONTEXT GUARD
            assertTrue(engine.format(SampleType.S16, 1, 48_000));
            try {
                assertTrue(ALC10.alcMakeContextCurrent(other));
                assertThrows(IllegalStateException.class, () -> engine.spatialAudio(audio));
                assertThrows(IllegalStateException.class, engine::pause);
                assertThrows(IllegalStateException.class, engine::play);
                assertThrows(IllegalStateException.class, () -> engine.speed(2));
                assertThrows(IllegalStateException.class, () -> engine.volume(0));
                assertThrows(IllegalStateException.class, () -> engine.upload(ByteBuffer.allocateDirect(16)));
                assertThrows(IllegalStateException.class, engine::flush);
                assertThrows(IllegalStateException.class, engine::pendingMs);
                assertThrows(IllegalStateException.class, engine::release);
                assertFalse(engine.released(), "a release refused on the wrong context can be retried");
            } finally {
                assertTrue(ALC10.alcMakeContextCurrent(this.al.context()));
                ALC10.alcDestroyContext(other);
                engine.release();
            }
            final ALEngine replacement = PlayerAPI.alEngine(true);
            try {
                assertEquals(0, engine.source());
                assertFalse(engine.spatialAudio(audio));
                assertFalse(engine.upload(ByteBuffer.allocateDirect(16)));
                engine.flush();
                engine.release();
                assertTrue(AL10.alIsSource(replacement.source()));
                assertEquals(AL10.AL_NO_ERROR, AL10.alGetError());
            } finally {
                replacement.release();
            }
        }

        @Test
        @DisplayName("A failing audio reset on release still frees the player's plane pool")
        void failingAudioResetCannotLeakPlayerPlanePool() throws Exception {
            final MRL mrl = PlayerAPI.mrl(Fixtures.fileUri(Fixtures.MP4_H264));
            assertTrue(mrl.await(3_000));
            final ALEngine engine = PlayerAPI.alEngine(true);
            final HeadlessGFXEngine graphics = new HeadlessGFXEngine();
            final FFMediaPlayer player = new FFMediaPlayer(mrl, 0, graphics, engine);
            final Field poolField = FFMediaPlayer.class.getDeclaredField("planePool");
            final Field alignmentField = FFMediaPlayer.class.getDeclaredField("planeAlign");
            poolField.setAccessible(true);
            alignmentField.setAccessible(true);
            final ByteBuffer[][] pool = (ByteBuffer[][]) poolField.get(player);
            pool[0] = new ByteBuffer[] { MemoryUtil.memAlignedAlloc(64, 64) };
            alignmentField.setInt(player, 64);
            try {
                player.spatialAudio(new SFXEngine.SpatialAudio(1, 2, 3, 1, 32, 1, false, RESET_FAILURE));
                assertThrows(IllegalStateException.class, player::release);
                for (final ByteBuffer[] planes: pool) assertNull(planes);
                assertEquals(-1, alignmentField.getInt(player));
                assertTrue(graphics.released());
                assertEquals(0, engine.source());
                assertEquals(AL10.AL_NO_ERROR, AL10.alGetError());
            } finally {
                if (pool[0] != null && pool[0][0] != null) {
                    MemoryUtil.memAlignedFree(pool[0][0]);
                    pool[0] = null;
                }
                graphics.release();
                player.release();
            }
        }

        @Test
        @DisplayName("A spatial player keeps the stereo decode and queues a mono buffer on every sink")
        void spatialPlayerQueuesMonoBuffersOnEverySink(@TempDir final Path directory) throws Exception {
            assumeTrue(MediaBootstrap.ffmpegAvailable(), "FFmpeg natives unavailable");
            final int samples = 48_000;
            final ByteBuffer pcm = ByteBuffer.allocate(samples * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < samples; i++) {
                pcm.putShort((short) (4_000 * Math.sin(2 * Math.PI * 440 * i / samples)));
                pcm.putShort((short) (4_000 * Math.sin(2 * Math.PI * 880 * i / samples)));
            }
            final Path wav = directory.resolve("stereo.wav");
            Fixtures.wav(wav, samples, 2, pcm.array());
            final MRL mrl = PlayerAPI.mrl(wav.toUri());
            assertTrue(mrl.await(3_000));
            final ALEngine engine = PlayerAPI.alEngine(true);
            final FFMediaPlayer player = new FFMediaPlayer(mrl, 0, null, engine);
            try {
                assertTrue(player.spatial());
                player.mute(true);
                assertTrue(player.start());
                assertTrue(PlayerWait.awaitCondition(() -> AL10.alGetSourcei(engine.source(), AL10.AL_BUFFERS_QUEUED) > 0, 15_000),
                        () -> "Audio failed to queue: " + player.status());
                final ALEngine.Sink right = engine.addSink(ALEngine.Mix.RIGHT);
                player.pause(true);
                assertEquals(2, engine.channels(), "FFmpeg keeps the stereo decode for the sinks to split");
                boolean uploaded = false;
                for (final int buffer: engine.buffers()) {
                    if (AL10.alGetBufferi(buffer, AL10.AL_SIZE) > 0) {
                        uploaded = true;
                        assertEquals(1, AL10.alGetBufferi(buffer, AL10.AL_CHANNELS));
                    }
                }
                assertTrue(uploaded);
                assertEquals(List.of(engine.sinks().get(0), right), engine.sinks());
                assertTrue(AL10.alGetSourcei(right.source(), AL10.AL_BUFFERS_QUEUED) > 0, "the new sink joined the queued audio");
                assertEquals(1, AL10.alGetBufferi(AL10.alGetSourcei(right.source(), AL10.AL_BUFFER), AL10.AL_CHANNELS));
                assertEquals(AL10.AL_NO_ERROR, AL10.alGetError());
            } finally {
                player.release();
            }
        }
    }
}
