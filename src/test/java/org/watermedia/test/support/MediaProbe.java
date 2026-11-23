package org.watermedia.test.support;

import org.bytedeco.ffmpeg.avcodec.AVCodecParameters;
import org.bytedeco.ffmpeg.avcodec.AVPacket;
import org.bytedeco.ffmpeg.avcodec.AVPacketSideData;
import org.bytedeco.ffmpeg.avformat.AVFormatContext;
import org.bytedeco.ffmpeg.avutil.AVDictionaryEntry;
import org.bytedeco.ffmpeg.avutil.AVRational;
import org.bytedeco.ffmpeg.global.avcodec;
import org.bytedeco.ffmpeg.global.avformat;
import org.bytedeco.ffmpeg.global.avutil;
import org.bytedeco.javacpp.IntPointer;
import org.bytedeco.javacpp.PointerPointer;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** What a written media file holds, read back with libavformat: its length, its title tag and every stream; needs the FFmpeg natives. */
public record MediaProbe(long durationUs, String title, List<Track> tracks) {

    /** Reads the streams, their packets, where each one ends (last timestamp plus duration) and its longest packet. */
    public static MediaProbe read(final Path file) throws IOException {
        final AVFormatContext context = new AVFormatContext(null);
        final int opened = avformat.avformat_open_input(context, file.toAbsolutePath().toString(), null, null);
        if (opened < 0) throw new IOException("avformat_open_input failed: " + opened);
        final AVPacket packet = avcodec.av_packet_alloc();
        try {
            if (avformat.avformat_find_stream_info(context, (PointerPointer<?>) null) < 0) throw new IOException("avformat_find_stream_info failed");
            final int streams = context.nb_streams();
            final int[] packets = new int[streams];
            final long[] ends = new long[streams];
            final long[] longest = new long[streams];
            while (avformat.av_read_frame(context, packet) >= 0) {
                final int index = packet.stream_index();
                final AVRational base = context.streams(index).time_base();
                packets[index]++;
                ends[index] = Math.max(ends[index], (packet.pts() + packet.duration()) * 1_000_000L * base.num() / base.den());
                longest[index] = Math.max(longest[index], packet.duration() * 1_000_000L * base.num() / base.den());
                avcodec.av_packet_unref(packet);
            }
            final List<Track> tracks = new ArrayList<>();
            for (int i = 0; i < streams; i++) {
                final AVCodecParameters parameters = context.streams(i).codecpar();
                final AVPacketSideData matrix = parameters.nb_coded_side_data() <= 0 ? null
                        : avcodec.av_packet_side_data_get(parameters.coded_side_data(), parameters.nb_coded_side_data(), avcodec.AV_PKT_DATA_DISPLAYMATRIX);
                tracks.add(new Track(parameters.codec_type(), parameters.codec_id(), parameters.width(), parameters.height(),
                        parameters.sample_rate(), parameters.ch_layout().nb_channels(), packets[i], ends[i], longest[i],
                        matrix == null || matrix.isNull() ? 0 : avutil.av_display_rotation_get(new IntPointer(matrix.data()))));
            }
            final AVDictionaryEntry title = avutil.av_dict_get(context.metadata(), "title", null, 0);
            return new MediaProbe(context.duration(), title == null ? null : title.value().getString(), tracks);
        } finally {
            avcodec.av_packet_free(packet);
            avformat.avformat_close_input(context);
        }
    }

    /** Returns the first stream of an {@code AVMEDIA_TYPE_*}, or null when the file has none. */
    public Track track(final int type) {
        for (final Track track: this.tracks) if (track.type() == type) return track;
        return null;
    }

    /** One stream: its media type and codec id, its picture or sound layout, its packets, the end and longest packet in microseconds and the degrees its display matrix turns it. */
    public record Track(int type, int codec, int width, int height, int sampleRate, int channels, int packets, long endUs, long frameUs, double rotation) {}
}
