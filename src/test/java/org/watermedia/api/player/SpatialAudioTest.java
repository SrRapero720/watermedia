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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;

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

/** Verifies {@link SFXEngine.SpatialAudio} validation and the spatial {@link ALEngine}: the world position, environment callbacks and resets, failures that leave a dry source, the context guard, a release that waits for a host callback and a spatial player's sinks; the OpenAL cases skip without an output device. */
@DisplayName("Spatial audio")
public class SpatialAudioTest {

    // AN ENVIRONMENT ADAPTER WHOSE DETACH FAILS, AS A BROKEN HOST EFFECT MOD WOULD
    private static final SFXEngine.SpatialAudio.Environment RESET_FAILURE = new SFXEngine.SpatialAudio.Environment() {
        @Override
        public void process(final int source, final SFXEngine.SpatialAudio audio) {}

        @Override
        public void reset(final int source) { throw new IllegalStateException("reset failed"); }
    };

    // ==========================================================================
    // INTERNAL API
    // ==========================================================================

    @BeforeAll
    static void boot() {
        MediaBootstrap.client();
    }

    // ==========================================================================
    // NESTED TYPES
    // ==========================================================================

    @Nested
    @DisplayName("SpatialAudio validation (no device)")
    class Validation {

        @Test
        @DisplayName("SpatialAudio rejects coordinates outside the finite float range, invalid distances and reflected-only audio without an environment")
        void rejectsInvalidCoordinatesAndDistances() {
            for (final double value: new double[] { Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY, Double.MAX_VALUE }) {
                assertThrows(IllegalArgumentException.class, () -> new SFXEngine.SpatialAudio(value, 1, 1, 1, 16, 1, false, null), "x = " + value);
                assertThrows(IllegalArgumentException.class, () -> new SFXEngine.SpatialAudio(1, value, 1, 1, 16, 1, false, null), "y = " + value);
                assertThrows(IllegalArgumentException.class, () -> new SFXEngine.SpatialAudio(1, 1, value, 1, 16, 1, false, null), "z = " + value);
            }
            for (final float value: new float[] { Float.NaN, Float.POSITIVE_INFINITY, -1, 0 }) {
                assertThrows(IllegalArgumentException.class, () -> new SFXEngine.SpatialAudio(1, 1, 1, value, 16, 1, false, null), "referenceDistance = " + value);
                assertThrows(IllegalArgumentException.class, () -> new SFXEngine.SpatialAudio(1, 1, 1, 1, value, 1, false, null), "maxDistance = " + value);
            }
            assertThrows(IllegalArgumentException.class, () -> new SFXEngine.SpatialAudio(1, 1, 1, 16, 16, 1, false, null), "maxDistance equal to referenceDistance");
            assertThrows(IllegalArgumentException.class, () -> new SFXEngine.SpatialAudio(1, 1, 1, 1, 16, Float.NaN, false, null), "rolloff = NaN");
            assertThrows(IllegalArgumentException.class, () -> new SFXEngine.SpatialAudio(1, 1, 1, 1, 16, -1, false, null), "rolloff = -1");
            assertThrows(IllegalArgumentException.class, () -> new SFXEngine.SpatialAudio(1, 1, 1, 1, 16, 1, true, null), "auxOnly without an environment");
            assertDoesNotThrow(() -> new SFXEngine.SpatialAudio(0, 0, 0, 1, 16, 0, false, null), "the origin without attenuation is valid");
        }
    }

    @Nested
    @TestInstance(TestInstance.Lifecycle.PER_CLASS) // NON-STATIC @BeforeAll/@AfterAll HOLD THE OPENAL CONTEXT
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
                assertEquals(AL10.AL_FALSE, AL10.alGetSourcei(engine.source(), AL10.AL_SOURCE_RELATIVE), "a positioned source lives in world space");
                final float[] position = new float[3];
                AL10.alGetSourcefv(engine.source(), AL10.AL_POSITION, position);
                assertArrayEquals(new float[] { 12, 24, -36 }, position);
                assertEquals(4, AL10.alGetSourcef(engine.source(), AL10.AL_REFERENCE_DISTANCE));
                assertEquals(64, AL10.alGetSourcef(engine.source(), AL10.AL_MAX_DISTANCE));
                assertEquals(0.75f, AL10.alGetSourcef(engine.source(), AL10.AL_ROLLOFF_FACTOR));
                assertEquals(model, AL10.alGetInteger(AL10.AL_DISTANCE_MODEL), "the host distance model is untouched");
                assertTrue(engine.spatialAudio(null));
                assertEquals(AL10.AL_TRUE, AL10.alGetSourcei(engine.source(), AL10.AL_SOURCE_RELATIVE), "a cleared source is listener-relative again");
                assertEquals(0, AL10.alGetSourcef(engine.source(), AL10.AL_ROLLOFF_FACTOR), "a cleared source has no attenuation");
                assertEquals(AL10.AL_NO_ERROR, AL10.alGetError());
            } finally {
                engine.release();
            }
        }

        @Test
        @DisplayName("Environment callbacks run on the updating thread from the source's world position, and the same processor disabled leaves no effects")
        void callbacksRestoreWorldOriginAndClearEffectsWhenDisabled() {
            assumeTrue(AL.getCapabilities().ALC_EXT_EFX, "EFX unavailable");
            final ALEngine engine = PlayerAPI.alEngine(true);
            final int filter = EXTEfx.alGenFilters();
            try {
                EXTEfx.alFilteri(filter, EXTEfx.AL_FILTER_TYPE, EXTEfx.AL_FILTER_LOWPASS);
                final List<Thread> callers = new ArrayList<>();
                final boolean[] disabled = new boolean[1];
                final SFXEngine.SpatialAudio.Environment effects = (source, audio) -> {
                    callers.add(Thread.currentThread());
                    // A HOST EFFECT MOD SWITCHED OFF RETURNS WITHOUT TOUCHING AL, AND THE ENGINE STILL SEES THE SAME PROCESSOR
                    if (disabled[0]) return;
                    final float[] position = new float[3];
                    AL10.alGetSourcefv(source, AL10.AL_POSITION, position);
                    assertArrayEquals(new float[] { 10, 20, 30 }, position, "the callback starts from the source's world position");
                    AL10.alSourcei(source, EXTEfx.AL_DIRECT_FILTER, filter);
                    AL10.alSourcef(source, EXTEfx.AL_AIR_ABSORPTION_FACTOR, 5);
                    AL10.alSource3f(source, AL10.AL_POSITION, -1, -2, -3);
                };
                final SFXEngine.SpatialAudio audio = new SFXEngine.SpatialAudio(10, 20, 30, 1, 32, 1, false, effects);
                assertTrue(engine.spatialAudio(audio));
                // THE FIRST CALLBACK MOVED THE SOURCE TOWARD A REFLECTION, SO THE SECOND ONE SEES WHETHER ITS POSITION CAME BACK
                assertTrue(engine.spatialAudio(audio));
                assertEquals(List.of(Thread.currentThread(), Thread.currentThread()), callers, "every callback runs on the updating thread");
                assertEquals(5, AL10.alGetSourcef(engine.source(), EXTEfx.AL_AIR_ABSORPTION_FACTOR), "the callback applied its effects");

                disabled[0] = true;
                assertTrue(engine.spatialAudio(audio));
                // OPENAL SOFT CANNOT QUERY AL_DIRECT_FILTER, SO THE AIR ABSORPTION THE SAME RESET CLEARS STANDS FOR EVERY EFFECT
                assertEquals(0, AL10.alGetSourcef(engine.source(), EXTEfx.AL_AIR_ABSORPTION_FACTOR), "the old effects are removed");
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
                assertNull(engine.spatialAudio(), "a failed processor applies no state");
                assertEquals(AL10.AL_TRUE, AL10.alGetSourcei(engine.source(), AL10.AL_SOURCE_RELATIVE), "a failed processor leaves a listener-relative source");
                assertTrue(engine.spatialAudio(new SFXEngine.SpatialAudio(1, 2, 3, 1, 32, 1, false, RESET_FAILURE)));
                assertThrows(IllegalStateException.class, () -> engine.spatialAudio(null));
                assertNull(engine.spatialAudio(), "a failed reset still detaches the state");
                assertEquals(AL10.AL_TRUE, AL10.alGetSourcei(engine.source(), AL10.AL_SOURCE_RELATIVE), "a failed reset leaves a listener-relative source");
                assertEquals(0, AL10.alGetSourcef(engine.source(), AL10.AL_ROLLOFF_FACTOR), "a failed reset leaves no attenuation");
                assertEquals(AL10.AL_NO_ERROR, AL10.alGetError());
            } finally {
                engine.release();
            }
        }

        @Test
        @DisplayName("A reset failure during release still frees the source and leaves the engine released")
        void resetFailureOnReleaseStillReleases() {
            final ALEngine engine = PlayerAPI.alEngine(true);
            try {
                final int source = engine.source();
                assertTrue(engine.format(SampleType.S16, 1, 48_000));
                assertTrue(engine.spatialAudio(new SFXEngine.SpatialAudio(1, 2, 3, 1, 32, 1, false, RESET_FAILURE)));
                assertThrows(IllegalStateException.class, engine::release, "the host failure still reaches the caller");
                assertEquals(0, engine.source());
                assertFalse(AL10.alIsSource(source), "the source was deleted despite the failure");
                assertTrue(engine.released(), "a teardown that freed the source is not retried");
                assertFalse(engine.format(SampleType.S16, 1, 48_000), "the engine stays inert");
                assertEquals(AL10.AL_NO_ERROR, AL10.alGetError());
            } finally {
                engine.release();
            }
        }

        @Test
        @DisplayName("release() blocks while an environment callback still uses the source and frees it once the callback returns")
        void releaseWaitsForAHostCallbackUsingTheSource() throws Exception {
            final ALEngine engine = PlayerAPI.alEngine(true);
            final CountDownLatch entered = new CountDownLatch(1);
            final CountDownLatch resume = new CountDownLatch(1);
            final SFXEngine.SpatialAudio.Environment holding = (source, audio) -> {
                entered.countDown();
                try { assertTrue(resume.await(5, TimeUnit.SECONDS), "the test never resumed the callback"); }
                catch (final InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
                assertTrue(AL10.alIsSource(source), "the source outlives the callback");
            };
            final FutureTask<Boolean> update = new FutureTask<>(() -> engine.spatialAudio(new SFXEngine.SpatialAudio(1, 2, 3, 1, 32, 1, false, holding)));
            final FutureTask<Void> release = new FutureTask<>(engine::release, null);
            final Thread updater = new Thread(update, "SpatialAudioTest-update");
            final Thread releaser = new Thread(release, "SpatialAudioTest-release");
            try {
                updater.start();
                assertTrue(entered.await(2, TimeUnit.SECONDS), "the callback never ran");
                releaser.start();
                // BLOCKED IS THE WAIT FOR THE ENGINE MONITOR THE CALLBACK HOLDS; A RELEASE THAT SKIPPED IT WOULD FINISH INSTEAD
                assertTrue(PlayerWait.awaitCondition(() -> releaser.getState() == Thread.State.BLOCKED, 2_000),
                        () -> "the release did not wait for the callback: " + releaser.getState());
                resume.countDown();
                assertTrue(update.get(2, TimeUnit.SECONDS), "the update applied");
                release.get(2, TimeUnit.SECONDS);
                assertTrue(engine.released());
                assertEquals(0, engine.source(), "the release freed the source");
            } finally {
                resume.countDown();
                updater.join(5_000);
                releaser.join(5_000);
                engine.release();
            }
        }

        @Test
        @DisplayName("A wrong context throws and a released engine never touches a recycled source")
        void wrongContextAndLateUpdatesCannotTouchRecycledSource() {
            final ALEngine engine = PlayerAPI.alEngine(true);
            final SFXEngine.SpatialAudio audio = new SFXEngine.SpatialAudio(1, 2, 3, 1, 32, 1, false, null);
            try {
                // A CONFIGURED ENGINE, SO THE UPLOAD BELOW PASSES THE BASE CHECKS AND REACHES THE CONTEXT GUARD
                assertTrue(engine.format(SampleType.S16, 1, 48_000));
                final long other = ALC10.alcCreateContext(this.al.device(), (IntBuffer) null);
                try {
                    assertNotEquals(0L, other, "a second context on the same device");
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
                    ALC10.alcMakeContextCurrent(this.al.context());
                    if (other != 0L) ALC10.alcDestroyContext(other);
                }
            } finally {
                engine.release();
            }
            // THE DRIVER MAY HAND THE FREED SOURCE ID TO THE NEXT ENGINE, SO LATE CALLS ON THE RELEASED ONE MUST STAY INERT
            final ALEngine replacement = PlayerAPI.alEngine(true);
            try {
                assertEquals(0, engine.source());
                assertFalse(engine.spatialAudio(audio), "a released engine applies no update");
                assertFalse(engine.upload(ByteBuffer.allocateDirect(16)), "a released engine takes no upload");
                engine.flush();
                engine.release();
                assertTrue(AL10.alIsSource(replacement.source()), "the replacement source survives the late calls");
                assertEquals(AL10.AL_NO_ERROR, AL10.alGetError());
            } finally {
                replacement.release();
            }
        }

        @Test
        @DisplayName("A failing audio reset on release still frees the player's plane pool")
        void failingAudioResetCannotLeakPlayerPlanePool() throws Exception {
            final Field poolField = FFMediaPlayer.class.getDeclaredField("planePool");
            final Field alignmentField = FFMediaPlayer.class.getDeclaredField("planeAlign");
            poolField.setAccessible(true);
            alignmentField.setAccessible(true);
            final MRL mrl = PlayerAPI.mrl(Fixtures.fileUri(Fixtures.MP4_H264));
            assertTrue(mrl.await(3_000), "the MP4 fixture never resolved");
            final ALEngine engine = PlayerAPI.alEngine(true);
            try {
                final HeadlessGFXEngine graphics = new HeadlessGFXEngine();
                final FFMediaPlayer player = new FFMediaPlayer(mrl, 0, graphics, engine);
                final ByteBuffer[][] pool = (ByteBuffer[][]) poolField.get(player);
                try {
                    // A PLANE THE DECODE THREAD WOULD HAVE ALLOCATED, SO THE FAILING RELEASE HAS NATIVE MEMORY TO FREE
                    pool[0] = new ByteBuffer[] { MemoryUtil.memAlignedAlloc(64, 64) };
                    alignmentField.setInt(player, 64);
                    assertTrue(player.spatialAudio(new SFXEngine.SpatialAudio(1, 2, 3, 1, 32, 1, false, RESET_FAILURE)));
                    assertThrows(IllegalStateException.class, player::release);
                    for (final ByteBuffer[] planes: pool) assertNull(planes, "every plane was freed");
                    assertEquals(-1, alignmentField.getInt(player), "the plane alignment was reset");
                    assertTrue(graphics.released(), "the graphics engine was released");
                    assertEquals(0, engine.source(), "the audio source was freed");
                    assertEquals(AL10.AL_NO_ERROR, AL10.alGetError());
                } finally {
                    if (pool[0] != null && pool[0][0] != null) {
                        MemoryUtil.memAlignedFree(pool[0][0]);
                        pool[0] = null;
                    }
                    graphics.release();
                    player.release();
                }
            } finally {
                engine.release();
            }
        }

        @Test
        @DisplayName("A spatial player keeps the stereo decode and queues a mono buffer on every sink")
        void spatialPlayerQueuesMonoBuffersOnEverySink(@TempDir final Path directory) throws Exception {
            assumeTrue(MediaBootstrap.ffmpegAvailable(), "FFmpeg natives unavailable");
            // ONE SECOND OF A DIFFERENT TONE ON EACH SIDE, SO THE DECODE IS TRUE STEREO
            final int rate = 48_000;
            final ByteBuffer pcm = ByteBuffer.allocate(rate * 4).order(ByteOrder.LITTLE_ENDIAN);
            for (int i = 0; i < rate; i++) {
                pcm.putShort((short) (4_000 * Math.sin(2 * Math.PI * 440 * i / rate)));
                pcm.putShort((short) (4_000 * Math.sin(2 * Math.PI * 880 * i / rate)));
            }
            final Path wav = directory.resolve("stereo.wav");
            Fixtures.wav(wav, rate, 2, pcm.array());
            final MRL mrl = PlayerAPI.mrl(wav.toUri());
            assertTrue(mrl.await(3_000), "the WAV never resolved");
            final ALEngine engine = PlayerAPI.alEngine(true);
            try {
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
                            assertEquals(1, AL10.alGetBufferi(buffer, AL10.AL_CHANNELS), "the primary plays mono buffers");
                        }
                    }
                    assertTrue(uploaded, "the primary holds uploaded audio");
                    assertEquals(List.of(engine.sinks().get(0), right), engine.sinks());
                    assertTrue(AL10.alGetSourcei(right.source(), AL10.AL_BUFFERS_QUEUED) > 0, "the new sink joined the queued audio");
                    assertEquals(1, AL10.alGetBufferi(AL10.alGetSourcei(right.source(), AL10.AL_BUFFER), AL10.AL_CHANNELS), "the new sink plays mono buffers");
                    assertEquals(AL10.AL_NO_ERROR, AL10.alGetError());
                } finally {
                    player.release();
                }
            } finally {
                engine.release();
            }
        }
    }
}
