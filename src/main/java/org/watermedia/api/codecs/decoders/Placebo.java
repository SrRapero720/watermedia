package org.watermedia.api.codecs.decoders;

import org.bytedeco.ffmpeg.avfilter.AVBufferSrcParameters;
import org.bytedeco.ffmpeg.avfilter.AVFilterContext;
import org.bytedeco.ffmpeg.avfilter.AVFilterGraph;
import org.bytedeco.ffmpeg.avutil.AVBufferRef;
import org.bytedeco.ffmpeg.avutil.AVFrame;
import org.bytedeco.ffmpeg.avutil.AVRational;
import org.bytedeco.ffmpeg.global.avfilter;
import org.watermedia.tools.FFTool;

import static org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_VULKAN;
import static org.bytedeco.ffmpeg.global.avutil.av_buffer_ref;
import static org.bytedeco.ffmpeg.global.avutil.av_free;
import static org.bytedeco.ffmpeg.global.avutil.av_q2d;

/**
 * Runs decoded Vulkan frames through a {@code buffer → libplacebo → buffersink} graph over one decoder pool: they leave
 * cropped as RGBA in SDR BT.709 (HDR tone-mapped, other primaries gamut-mapped), for the engine to scale. One thread at a time.
 */
public final class Placebo {
    // NO deinterlace: WITH IT, av_buffersink_get_frame NEVER RETURNS ONCE AN INTERLACED FRAME ENTERS (FFMPEG 9.0.2, LIBPLACEBO 7.387)
    private static final String OPTIONS = "format=rgba:color_primaries=bt709:color_trc=bt709";

    private final AVFilterGraph graph;
    private final AVFilterContext source;
    private final AVFilterContext sink;
    final long pool;       // THE DECODER POOL THE GRAPH TAKES FRAMES FROM
    final double timeBase; // SECONDS PER pts TICK OF THE FRAMES IT SENDS (LIBPLACEBO MAY REFINE THE INPUT'S)

    private Placebo(final AVFilterGraph graph, final AVFilterContext source, final AVFilterContext sink, final long pool) {
        this.graph = graph;
        this.source = source;
        this.sink = sink;
        this.pool = pool;
        this.timeBase = av_q2d(avfilter.av_buffersink_get_time_base(sink));
    }

    /** Builds the graph for the pool of {@code frame}, timed in {@code timeBase} and run on {@code device}; throws an {@code IllegalStateException} naming the step FFmpeg refused. */
    public static Placebo open(final AVFrame frame, final AVBufferRef device, final AVRational timeBase) {
        final AVFilterGraph graph = avfilter.avfilter_graph_alloc();
        final AVFilterContext source = avfilter.avfilter_graph_alloc_filter(graph, avfilter.avfilter_get_by_name("buffer"), "in");
        final AVFilterContext placebo = avfilter.avfilter_graph_alloc_filter(graph, avfilter.avfilter_get_by_name("libplacebo"), "placebo");
        final AVFilterContext sink = avfilter.avfilter_graph_alloc_filter(graph, avfilter.avfilter_get_by_name("buffersink"), "out");
        placebo.hw_device_ctx(av_buffer_ref(device)); // THE FILTER OWNS THIS REFERENCE AND FREES IT WITH THE GRAPH
        // THE PARAMETERS TAKE THEIR OWN REFERENCE TO THE POOL
        final AVBufferSrcParameters parameters = avfilter.av_buffersrc_parameters_alloc();
        parameters.format(AV_PIX_FMT_VULKAN).width(frame.width()).height(frame.height()).time_base(timeBase)
                .sample_aspect_ratio(frame.sample_aspect_ratio()).hw_frames_ctx(frame.hw_frames_ctx())
                .color_space(frame.colorspace()).color_range(frame.color_range());
        int result = avfilter.av_buffersrc_parameters_set(source, parameters);
        av_free(parameters);
        String step = "buffer";
        if (result >= 0) result = avfilter.avfilter_init_str(source, (String) null);
        if (result >= 0) {
            step = "libplacebo";
            result = avfilter.avfilter_init_str(placebo, OPTIONS);
        }
        if (result >= 0) {
            step = "buffersink";
            result = avfilter.avfilter_init_str(sink, (String) null);
        }
        if (result >= 0) result = avfilter.avfilter_link(source, 0, placebo, 0);
        if (result >= 0) result = avfilter.avfilter_link(placebo, 0, sink, 0);
        if (result >= 0) {
            step = "graph";
            result = avfilter.avfilter_graph_config(graph, null);
        }
        if (result >= 0) return new Placebo(graph, source, sink, frame.hw_frames_ctx().data().address());
        avfilter.avfilter_graph_free(graph);
        throw new IllegalStateException("The libplacebo " + step + " failed: " + FFTool.error(result));
    }

    /** Hands a decoded frame to the graph, taking its reference (the frame stays untouched on failure); null flushes it. */
    public int push(final AVFrame frame) {
        return avfilter.av_buffersrc_add_frame_flags(this.source, frame, 0);
    }

    /** Pulls the next RGBA frame, or returns EAGAIN while the graph waits for input and EOF once flushed. */
    public int pull(final AVFrame into) {
        return avfilter.av_buffersink_get_frame(this.sink, into);
    }

    /** Frees the graph and its references. */
    public void close() {
        avfilter.avfilter_graph_free(this.graph);
    }
}
