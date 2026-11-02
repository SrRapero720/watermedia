package org.watermedia.api.player;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;
import org.watermedia.api.util.SampleType;
import org.watermedia.test.support.Fixtures;
import org.watermedia.test.support.MediaBootstrap;
import org.watermedia.test.support.OpenALContext;
import org.watermedia.test.support.PlayerWait;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Verifies the {@link ALEngine.Sink} model: one decode feeding aligned positional sinks; every case skips without an OpenAL output device. */
@DisplayName("ALEngine sinks")
@TestInstance(TestInstance.Lifecycle.PER_CLASS) // NON-STATIC @BeforeAll/@AfterAll HOLD THE OPENAL CONTEXT
public class ALSinkTest {
    private static final int RATE_HZ = 48_000;
    private static final int CHUNK_FRAMES = 2_400; // 50 MS PER CHUNK

    private OpenALContext al;

    @BeforeAll
    void openContext() {
        MediaBootstrap.client();
        this.al = OpenALContext.open();
    }

    @AfterAll
    void closeContext() {
        if (this.al != null) this.al.close();
    }

    @Nested
    @DisplayName("Sink set")
    class SinkSet {

        @Test
        @DisplayName("A plain engine plays one dry listener-relative source and offers no sinks")
        void plainEngineHasNoSinks() {
            final ALEngine engine = PlayerAPI.alEngine();
            try {
                assertTrue(engine.sinks().isEmpty());
                assertNull(engine.addSink(ALEngine.Mix.MONO));
                // A MONO DECODE ON A PLAIN ENGINE MUST NOT BE PLACED AT THE WORLD ORIGIN
                assertEquals(AL10.AL_TRUE, AL10.alGetSourcei(engine.source(), AL10.AL_SOURCE_RELATIVE));
                assertEquals(0, AL10.alGetSourcef(engine.source(), AL10.AL_ROLLOFF_FACTOR));
                assertEquals(AL10.AL_NO_ERROR, AL10.alGetError());
            } finally {
                engine.release();
            }
        }

        @Test
        @DisplayName("The primary sink is the engine source, plays MONO and lives as long as the engine")
        void primarySinkIsTheEngineSource() {
            final ALEngine engine = PlayerAPI.alEngine(true);
            try {
                assertEquals(1, engine.sinks().size());
                final ALEngine.Sink primary = engine.sinks().get(0);
                assertTrue(primary.primary());
                assertEquals(engine.source(), primary.source());
                assertSame(ALEngine.Mix.MONO, primary.mix());
                assertFalse(primary.release(), "the primary cannot be released alone");
                assertEquals(engine.source(), primary.source());
                assertThrows(IllegalArgumentException.class, () -> engine.addSink(null));
            } finally {
                engine.release();
            }
            assertTrue(engine.sinks().isEmpty());
        }

        @Test
        @DisplayName("An added sink takes the engine's gain and pitch, and its release deletes only its own source")
        void addedSinkFollowsTheEngineAndReleasesAlone() {
            final ALEngine engine = PlayerAPI.alEngine(true);
            try {
                engine.volume(0.5f);
                assertTrue(engine.speed(1.5f));
                final ALEngine.Sink left = engine.addSink(ALEngine.Mix.LEFT);
                assertNotEquals(engine.source(), left.source());
                assertFalse(left.primary());
                assertEquals(0.5f, AL10.alGetSourcef(left.source(), AL10.AL_GAIN), 1e-6f);
                assertEquals(1.5f, AL10.alGetSourcef(left.source(), AL10.AL_PITCH), 1e-6f);
                assertEquals(AL10.AL_TRUE, AL10.alGetSourcei(left.source(), AL10.AL_SOURCE_RELATIVE), "a new sink starts dry");
                assertEquals(List.of(engine.sinks().get(0), left), engine.sinks());

                final int source = left.source();
                assertTrue(left.release());
                assertFalse(AL10.alIsSource(source));
                assertTrue(AL10.alIsSource(engine.source()));
                assertTrue(left.released());
                assertEquals(0, left.source());
                assertEquals(1, engine.sinks().size());
                assertFalse(left.release(), "a second release is a no-op");
                assertFalse(left.mix(ALEngine.Mix.RIGHT));
                assertFalse(left.spatialAudio(new SFXEngine.SpatialAudio(1, 2, 3, 1, 32, 1, false, null)));
                assertEquals(AL10.AL_NO_ERROR, AL10.alGetError());
            } finally {
                engine.release();
            }
        }

        @Test
        @DisplayName("Each sink drives its own environment, and every reset runs on its release or the engine's")
        void environmentsFollowTheirSinks() {
            final ALEngine engine = PlayerAPI.alEngine(true);
            final List<Integer> processed = new ArrayList<>();
            final List<Integer> resets = new ArrayList<>();
            final SFXEngine.SpatialAudio.Environment recorder = new SFXEngine.SpatialAudio.Environment() {
                @Override
                public void process(final int source, final SFXEngine.SpatialAudio audio) { processed.add(source); }

                @Override
                public void reset(final int source) { resets.add(source); }
            };
            final ALEngine.Sink speaker = engine.addSink(ALEngine.Mix.MONO);
            final ALEngine.Sink right = engine.addSink(ALEngine.Mix.RIGHT);
            final int speakerSource = speaker.source();
            final int rightSource = right.source();
            try {
                assertTrue(speaker.spatialAudio(new SFXEngine.SpatialAudio(4, 5, 6, 1, 32, 1, false, recorder)));
                assertTrue(right.spatialAudio(new SFXEngine.SpatialAudio(7, 8, 9, 1, 32, 1, false, recorder)));
                assertEquals(List.of(speakerSource, rightSource), processed);
                final float[] position = new float[3];
                AL10.alGetSourcefv(speakerSource, AL10.AL_POSITION, position);
                assertEquals(4, position[0]);
                assertTrue(speaker.release());
                assertEquals(List.of(speakerSource), resets);
            } finally {
                engine.release();
            }
            assertEquals(List.of(speakerSource, rightSource), resets, "the engine release resets the remaining sink");
            assertTrue(right.released());
            assertFalse(AL10.alIsSource(rightSource));
            assertNull(engine.addSink(ALEngine.Mix.LEFT), "a released engine adds no sinks");
            assertEquals(AL10.AL_NO_ERROR, AL10.alGetError());
        }
    }

    @Nested
    @DisplayName("Players")
    class Players {

        @Test
        @DisplayName("A player reaches the sinks of a spatial OpenAL engine and gets none from any other engine or player")
        void playersDelegateToTheirEngine() {
            final MRL mrl = PlayerAPI.mrl(Fixtures.fileUri(Fixtures.MP4_H264));
            assertTrue(mrl.await(3_000));
            final ALEngine spatial = PlayerAPI.alEngine(true);
            final FFMediaPlayer player = new FFMediaPlayer(mrl, 0, null, spatial);
            final FFMediaPlayer plain = new FFMediaPlayer(mrl, 0, null, PlayerAPI.alEngine());
            final ServerMediaPlayer server = PlayerAPI.serverPlayer();
            try {
                assertEquals(spatial.sinks(), player.sinks());
                assertEquals(player.audioSource(), player.sinks().get(0).source());
                final ALEngine.Sink right = player.addSink(ALEngine.Mix.RIGHT);
                assertEquals(List.of(spatial.sinks().get(0), right), player.sinks());
                assertThrows(IllegalArgumentException.class, () -> player.addSink(null));

                assertTrue(plain.sinks().isEmpty());
                assertNull(plain.addSink(ALEngine.Mix.MONO));
                assertTrue(server.sinks().isEmpty());
                assertNull(server.addSink(ALEngine.Mix.MONO));
            } finally {
                player.release();
                plain.release();
                server.release();
            }
            assertTrue(player.sinks().isEmpty(), "a released player has no sinks");
            assertNull(player.addSink(ALEngine.Mix.LEFT));
        }
    }

    @Nested
    @DisplayName("Mixing")
    class Mixing {

        @Test
        @DisplayName("Each mix takes one side or the average of a stereo chunk in every sample type")
        void mixesSplitStereo() throws Exception {
            final Method render = ALEngine.class.getDeclaredMethod("render", ByteBuffer.class, ALEngine.Mix.class);
            render.setAccessible(true);
            final ALEngine engine = PlayerAPI.alEngine(true);
            try {
                for (final SampleType type: engine.supportedTypes()) {
                    assertTrue(engine.format(type, 2, RATE_HZ), type::name);
                    final ByteBuffer chunk = stereo(type, 2, 0.25, 0.75);
                    final int at = chunk.position();
                    assertEquals(0.25, sample(type, (ByteBuffer) render.invoke(engine, chunk, ALEngine.Mix.LEFT), 1), 0.01, type::name);
                    assertEquals(0.75, sample(type, (ByteBuffer) render.invoke(engine, chunk, ALEngine.Mix.RIGHT), 1), 0.01, type::name);
                    assertEquals(0.5, sample(type, (ByteBuffer) render.invoke(engine, chunk, ALEngine.Mix.MONO), 1), 0.01, type::name);
                    assertEquals(at, chunk.position(), "rendering never moves the chunk");

                    // A MONO DECODE REACHES EVERY SINK AS IT IS
                    assertTrue(engine.format(type, 1, RATE_HZ));
                    final ByteBuffer mono = ByteBuffer.allocateDirect(type.bytes());
                    assertSame(mono, render.invoke(engine, mono, ALEngine.Mix.RIGHT));
                }
            } finally {
                engine.release();
            }
        }
    }

    @Nested
    @DisplayName("Transport")
    class Transport {

        @Test
        @DisplayName("Every sink queues every chunk as mono and the slowest pool sets the backpressure")
        void everySinkQueuesEveryChunk() {
            final ALEngine engine = PlayerAPI.alEngine(2, true);
            try {
                final ALEngine.Sink right = engine.addSink(ALEngine.Mix.RIGHT);
                assertTrue(engine.format(SampleType.S16, 2, RATE_HZ));
                final ByteBuffer chunk = stereo(SampleType.S16, CHUNK_FRAMES, 0.1, 0.2);
                assertTrue(engine.upload(chunk));
                assertTrue(engine.upload(chunk));
                assertFalse(engine.upload(chunk), "two buffers per sink are taken");
                for (final ALEngine.Sink sink: engine.sinks()) {
                    assertEquals(2, AL10.alGetSourcei(sink.source(), AL10.AL_BUFFERS_QUEUED));
                    final int buffer = AL10.alGetSourcei(sink.source(), AL10.AL_BUFFER);
                    assertEquals(1, AL10.alGetBufferi(buffer, AL10.AL_CHANNELS));
                    assertEquals(CHUNK_FRAMES * 2, AL10.alGetBufferi(buffer, AL10.AL_SIZE));
                }
                assertEquals(100, engine.pendingMs());
                assertTrue(right.release());
                assertEquals(AL10.AL_NO_ERROR, AL10.alGetError());
            } finally {
                engine.release();
            }
        }

        @Test
        @DisplayName("play, pause, flush, speed and volume reach every sink")
        void controlsReachEverySink() {
            final ALEngine engine = PlayerAPI.alEngine(true);
            try {
                engine.addSink(ALEngine.Mix.LEFT);
                engine.addSink(ALEngine.Mix.RIGHT);
                fill(engine, 4);
                engine.play();
                for (final ALEngine.Sink sink: engine.sinks()) assertEquals(AL10.AL_PLAYING, state(sink));
                engine.pause();
                for (final ALEngine.Sink sink: engine.sinks()) assertEquals(AL10.AL_PAUSED, state(sink));
                assertTrue(engine.speed(1.25f));
                engine.volume(0.3f);
                for (final ALEngine.Sink sink: engine.sinks()) {
                    assertEquals(1.25f, AL10.alGetSourcef(sink.source(), AL10.AL_PITCH), 1e-6f);
                    assertEquals(0.3f, AL10.alGetSourcef(sink.source(), AL10.AL_GAIN), 1e-6f);
                }
                engine.flush();
                for (final ALEngine.Sink sink: engine.sinks()) {
                    assertEquals(AL10.AL_INITIAL, state(sink));
                    assertEquals(0, AL10.alGetSourcei(sink.source(), AL10.AL_BUFFERS_QUEUED));
                }
                assertEquals(0, engine.pendingMs());
                assertEquals(AL10.AL_NO_ERROR, AL10.alGetError());
            } finally {
                engine.release();
            }
        }

        @Test
        @DisplayName("A sink added mid-chunk joins on the primary's sample and keeps the media clock")
        void addedSinkJoinsOnThePrimarySample() throws Exception {
            final Field total = ALEngine.class.getDeclaredField("totalQueuedUs");
            total.setAccessible(true);
            final ALEngine engine = PlayerAPI.alEngine(true);
            try {
                fill(engine, 8);
                engine.play();
                assertTrue(PlayerWait.awaitCondition(() -> AL10.alGetSourcei(engine.source(), AL11.AL_SAMPLE_OFFSET) > CHUNK_FRAMES + 100, 2_000),
                        "the primary never played past its first chunk");
                engine.pause();
                // PENDING AUDIO WITHOUT THE DEVICE LATENCY, WHICH A HALTED SOURCE MAY OR MAY NOT REPORT
                final long pendingUs = total.getLong(engine) - (long) (AL10.alGetSourcef(engine.source(), AL11.AL_SEC_OFFSET) * 1_000_000);
                final ALEngine.Sink right = engine.addSink(ALEngine.Mix.RIGHT);

                // EVERY QUEUE WAS REBUILT FROM THE PRIMARY'S SAMPLE: SAME DEPTH, SAME START, SAME PENDING AUDIO
                final int queued = AL10.alGetSourcei(engine.source(), AL10.AL_BUFFERS_QUEUED);
                assertTrue(queued > 0 && queued < 8, () -> "the played chunks were dropped, queued=" + queued);
                assertEquals(queued, AL10.alGetSourcei(right.source(), AL10.AL_BUFFERS_QUEUED));
                assertEquals(AL10.alGetBufferi(AL10.alGetSourcei(engine.source(), AL10.AL_BUFFER), AL10.AL_SIZE),
                        AL10.alGetBufferi(AL10.alGetSourcei(right.source(), AL10.AL_BUFFER), AL10.AL_SIZE), "the head chunk is trimmed alike");
                final long rebuiltUs = total.getLong(engine);
                assertTrue(Math.abs(pendingUs - rebuiltUs) <= 1_000, () -> "the media clock moved: " + pendingUs + " -> " + rebuiltUs + " us");

                // ONE BATCH STARTS AND HALTS THEM, SO THEY STAY ON THE SAME SAMPLE
                engine.play();
                assertTrue(PlayerWait.awaitCondition(() -> AL10.alGetSourcei(right.source(), AL11.AL_SAMPLE_OFFSET) > 0, 2_000));
                engine.pause();
                assertEquals(AL10.alGetSourcei(engine.source(), AL11.AL_SAMPLE_OFFSET), AL10.alGetSourcei(right.source(), AL11.AL_SAMPLE_OFFSET));
                assertEquals(AL10.AL_NO_ERROR, AL10.alGetError());
            } finally {
                engine.release();
            }
        }

        @Test
        @DisplayName("A new mix rebuilds the queued audio on every sink")
        void mixChangeRebuildsTheQueue() {
            final ALEngine engine = PlayerAPI.alEngine(true);
            try {
                final ALEngine.Sink right = engine.addSink(ALEngine.Mix.RIGHT);
                fill(engine, 4);
                final ALEngine.Sink primary = engine.sinks().get(0);
                assertTrue(primary.mix(ALEngine.Mix.LEFT));
                assertSame(ALEngine.Mix.LEFT, primary.mix());
                for (final ALEngine.Sink sink: engine.sinks()) {
                    assertEquals(4, AL10.alGetSourcei(sink.source(), AL10.AL_BUFFERS_QUEUED));
                    assertEquals(AL10.AL_INITIAL, state(sink), "a paused engine stays paused");
                }
                assertTrue(primary.mix(ALEngine.Mix.LEFT), "the same mix is accepted without a rebuild");
                assertThrows(IllegalArgumentException.class, () -> right.mix(null));
                assertEquals(AL10.AL_NO_ERROR, AL10.alGetError());
            } finally {
                engine.release();
            }
        }

        @Test
        @DisplayName("Sinks a race left apart are rebuilt on one sample before playing again")
        void playRealignsSinksLeftApart() {
            final ALEngine engine = PlayerAPI.alEngine(true);
            try {
                final ALEngine.Sink right = engine.addSink(ALEngine.Mix.RIGHT);
                fill(engine, 8);
                engine.play();
                // AN UNDERRUN SEEN MID MIXER UPDATE STOPS ONE SINK WHILE THE OTHER STILL PLAYS
                AL10.alSourceStop(right.source());
                engine.play();
                assertEquals(AL10.AL_PLAYING, state(right));
                engine.pause();
                assertEquals(AL10.alGetSourcei(engine.source(), AL10.AL_BUFFERS_QUEUED), AL10.alGetSourcei(right.source(), AL10.AL_BUFFERS_QUEUED));
                assertEquals(AL10.alGetSourcei(engine.source(), AL11.AL_SAMPLE_OFFSET), AL10.alGetSourcei(right.source(), AL11.AL_SAMPLE_OFFSET));
                assertEquals(AL10.AL_NO_ERROR, AL10.alGetError());
            } finally {
                engine.release();
            }
        }

        @Test
        @DisplayName("play() after an underrun never replays the audio that already played")
        void playAfterUnderrunDropsPlayedAudio() {
            for (final boolean spatial: new boolean[] { false, true }) {
                final ALEngine engine = PlayerAPI.alEngine(spatial);
                try {
                    assertTrue(engine.format(SampleType.S16, 2, RATE_HZ));
                    assertTrue(engine.upload(stereo(SampleType.S16, 480, 0.1, 0.1)));
                    engine.play();
                    assertTrue(PlayerWait.awaitCondition(() -> AL10.alGetSourcei(engine.source(), AL10.AL_SOURCE_STATE) == AL10.AL_STOPPED, 2_000));
                    engine.play();
                    assertEquals(0, AL10.alGetSourcei(engine.source(), AL10.AL_BUFFERS_QUEUED), "the played buffer was reclaimed, not replayed");
                    assertEquals(0, engine.pendingMs());
                    assertEquals(AL10.AL_NO_ERROR, AL10.alGetError());
                } finally {
                    engine.release();
                }
            }
        }
    }

    // ==========================================================================
    // UTILITY
    // ==========================================================================

    // FORMATS S16 STEREO AND QUEUES chunks CHUNKS OF CHUNK_FRAMES WITHOUT PLAYING THEM
    private static void fill(final ALEngine engine, final int chunks) {
        assertTrue(engine.format(SampleType.S16, 2, RATE_HZ));
        for (int i = 0; i < chunks; i++) assertTrue(engine.upload(stereo(SampleType.S16, CHUNK_FRAMES, 0.1, 0.2)));
    }

    private static int state(final ALEngine.Sink sink) {
        return AL10.alGetSourcei(sink.source(), AL10.AL_SOURCE_STATE);
    }

    // A NATIVE-ENDIAN STEREO CHUNK OF frames FRAMES HOLDING left AND right AS FULL-SCALE FRACTIONS
    private static ByteBuffer stereo(final SampleType type, final int frames, final double left, final double right) {
        final ByteBuffer chunk = ByteBuffer.allocateDirect(frames * 2 * type.bytes()).order(ByteOrder.nativeOrder());
        for (int i = 0; i < frames; i++) {
            for (final double value: new double[] { left, right }) {
                switch (type) {
                    case U8 -> chunk.put((byte) (128 + Math.round(value * 127)));
                    case S16 -> chunk.putShort((short) Math.round(value * Short.MAX_VALUE));
                    case FLT -> chunk.putFloat((float) value);
                    case DBL -> chunk.putDouble(value);
                    case S32 -> throw new IllegalArgumentException("OpenAL has no S32");
                }
            }
        }
        return chunk.flip();
    }

    // THE FULL-SCALE FRACTION OF A MONO SAMPLE AT index
    private static double sample(final SampleType type, final ByteBuffer mono, final int index) {
        final ByteBuffer in = mono.duplicate().order(ByteOrder.nativeOrder());
        final int at = in.position() + index * type.bytes();
        return switch (type) {
            case U8 -> ((in.get(at) & 0xFF) - 128) / 127.0;
            case S16 -> in.getShort(at) / (double) Short.MAX_VALUE;
            case FLT -> in.getFloat(at);
            case DBL -> in.getDouble(at);
            case S32 -> throw new IllegalArgumentException("OpenAL has no S32");
        };
    }
}
