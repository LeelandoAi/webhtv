package com.fongmi.android.tv.player;

import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;

import com.fongmi.android.tv.player.engine.PlayerEngine;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MediaBadgeResolverTest {

    @Test
    public void fourKHevcHdrKeepsTheDisplayOrder() {
        Format video = video(3840, 2160, "video/hevc", 23.976f, 15_000_000, C.COLOR_TRANSFER_ST2084);

        assertEquals(List.of("4K", "HDR10", "HEVC", "23.976FPS", "15MBPS"),
                texts(MediaBadgeResolver.resolve(video, null, null, null)));
        assertEquals(List.of(MediaBadgeResolver.Icon.RESOLUTION, MediaBadgeResolver.Icon.DYNAMIC_RANGE,
                        MediaBadgeResolver.Icon.VIDEO_CODEC, MediaBadgeResolver.Icon.FRAME_RATE,
                        MediaBadgeResolver.Icon.BITRATE),
                icons(MediaBadgeResolver.resolve(video, null, null, null)));
    }

    @Test
    public void dolbyVisionReplacesTheHdrBadge() {
        PlayerEngine.VideoPlaybackDetails details = new PlayerEngine.VideoPlaybackDetails(
                "dvhe.07.06", 8, 6, "dvhe.08.06", "c2.qti.hevc.decoder", "", null, false);
        Format video = video(3840, 2160, "video/dolby-vision", 0f, 0, C.COLOR_TRANSFER_ST2084);

        List<String> badges = texts(MediaBadgeResolver.resolve(video, details, null, null));

        assertEquals(List.of("4K", "DV.P8", "HEVC"), badges);
        assertFalse(badges.contains("HDR10"));
    }

    @Test
    public void fullHdH264KeepsIntegerFrameRate() {
        Format video = video(1920, 1080, "video/avc", 60f, 0, C.COLOR_TRANSFER_SDR);

        assertEquals(List.of("1080P", "H.264", "60FPS"),
                texts(MediaBadgeResolver.resolve(video, null, null, null)));
    }

    @Test
    public void hlgIsNamedAndSdrIsOmitted() {
        Format hlg = video(1920, 1080, "video/hevc", 0f, 0, C.COLOR_TRANSFER_HLG);
        Format sdr = video(1920, 1080, "video/hevc", 0f, 0, C.COLOR_TRANSFER_SDR);

        assertTrue(texts(MediaBadgeResolver.resolve(hlg, null, null, null)).contains("HLG"));
        assertEquals(List.of("1080P", "HEVC"),
                texts(MediaBadgeResolver.resolve(sdr, null, null, null)));
    }

    @Test
    public void audioBadgeCarriesCodecLayoutAndRates() {
        AudioPlaybackDiagnostics.Track audio = new AudioPlaybackDiagnostics.Track("E-AC-3", 6, 48000, "en", 640000);

        List<MediaBadgeResolver.MediaBadge> badges = MediaBadgeResolver.resolve(null, null, audio, null);

        assertEquals(List.of("E-AC-3 5.1 48KHZ 640KBPS"), texts(badges));
        assertEquals(List.of(MediaBadgeResolver.Icon.AUDIO), icons(badges));
    }

    @Test
    public void audioBadgeOmitsFactsThatAreMissing() {
        AudioPlaybackDiagnostics.Track audio = new AudioPlaybackDiagnostics.Track("AAC", 2, 0, "", 0);

        assertEquals(List.of("AAC 2.0"), texts(MediaBadgeResolver.resolve(null, null, audio, null)));
    }

    /**
     * media3 对 MP4 里的 AAC 上报的是 RFC 6381 编解码串 {@code mp4a.40.2}，
     * 徽标必须归一成品牌名，而不是把 MIME 子类型原样丢到屏幕上。
     */
    @Test
    public void mp4aCodecIsShownAsAac() {
        AudioPlaybackDiagnostics.Track audio = new AudioPlaybackDiagnostics.Track("mp4a.40.2", 2, 44100, "", 64000);

        assertEquals(List.of("AAC 2.0 44.1KHZ 64KBPS"),
                texts(MediaBadgeResolver.resolve(null, null, audio, null)));
    }

    @Test
    public void containerComesFromTheMimeType() {
        Format video = new Format.Builder().setContainerMimeType("video/x-matroska").build();

        List<MediaBadgeResolver.MediaBadge> badges = MediaBadgeResolver.resolve(video, null, null, null);

        assertEquals(List.of("MKV"), texts(badges));
        assertEquals(List.of(MediaBadgeResolver.Icon.CONTAINER), icons(badges));
    }

    @Test
    public void streamingManifestNeverProducesAContainerBadge() {
        Format video = new Format.Builder().setContainerMimeType("application/x-mpegURL").build();

        assertEquals(List.of(), texts(MediaBadgeResolver.resolve(video, null, null, "https://example.com/list/index.m3u8")));
        assertEquals(List.of(), texts(MediaBadgeResolver.resolve(null, null, null, "https://example.com/list/index.mpd")));
    }

    @Test
    public void missingMimeTypeFallsBackToTheFileExtension() {
        assertEquals(List.of("MKV"), texts(MediaBadgeResolver.resolve(null, null, null, "https://example.com/movie.mkv")));
        assertEquals(List.of(), texts(MediaBadgeResolver.resolve(null, null, null, "https://example.com/play/123456")));
        assertEquals(List.of("MKV"), texts(MediaBadgeResolver.resolve(null, null, null, "https://example.com/movie.mkv?token=abc.ts")));
    }

    @Test
    public void emptyFactsProduceNoBadges() {
        assertEquals(List.of(), texts(MediaBadgeResolver.resolve(null, null, null, null)));
        assertEquals(List.of(), texts(MediaBadgeResolver.resolve(new Format.Builder().build(),
                PlayerEngine.VideoPlaybackDetails.empty(), AudioPlaybackDiagnostics.Track.empty(), "")));
    }

    @Test
    public void duplicateFactsAreCollapsed() {
        Format video = video(3840, 2160, "dvhe.07.06", 0f, 0, C.COLOR_TRANSFER_SDR);
        PlayerEngine.VideoPlaybackDetails details = new PlayerEngine.VideoPlaybackDetails(
                "dvhe.07.06", 7, 6, "dvhe.07.06", "c2.qti.hevc.decoder", "", null, false);

        assertEquals(List.of("4K", "DV.P7", "HEVC"), texts(MediaBadgeResolver.resolve(video, details, null, null)));
    }

    private static List<String> texts(List<MediaBadgeResolver.MediaBadge> badges) {
        List<String> values = new ArrayList<>();
        for (MediaBadgeResolver.MediaBadge badge : badges) values.add(badge.text());
        return values;
    }

    private static List<MediaBadgeResolver.Icon> icons(List<MediaBadgeResolver.MediaBadge> badges) {
        List<MediaBadgeResolver.Icon> values = new ArrayList<>();
        for (MediaBadgeResolver.MediaBadge badge : badges) values.add(badge.icon());
        return values;
    }

    private static Format video(int width, int height, String codecs, float frameRate, int bitrate, int colorTransfer) {
        Format.Builder builder = new Format.Builder()
                .setWidth(width)
                .setHeight(height)
                .setCodecs(codecs)
                .setColorInfo(new ColorInfo.Builder().setColorTransfer(colorTransfer).build());
        // media3 的 setFrameRate 只接受正数或 NO_VALUE；0 表示测试里没有帧率事实。
        if (frameRate > 0) builder.setFrameRate(frameRate);
        if (bitrate > 0) builder.setAverageBitrate(bitrate);
        return builder.build();
    }
}
