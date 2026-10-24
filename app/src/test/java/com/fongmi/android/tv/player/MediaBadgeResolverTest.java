package com.fongmi.android.tv.player;

import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;

import com.fongmi.android.tv.player.engine.PlayerEngine;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

public class MediaBadgeResolverTest {

    @Test
    public void fourKHevcHdrKeepsTheDisplayOrder() {
        Format video = video(3840, 2160, "video/hevc", 23.976f, 15_000_000, C.COLOR_TRANSFER_ST2084);
        List<MediaBadgeResolver.MediaBadge> badges = MediaBadgeResolver.resolve(video, null, null, null);

        assertEquals(List.of(MediaBadgeResolver.Icon.RESOLUTION, MediaBadgeResolver.Icon.DYNAMIC_RANGE,
                        MediaBadgeResolver.Icon.VIDEO_CODEC, MediaBadgeResolver.Icon.FRAME_RATE,
                        MediaBadgeResolver.Icon.BITRATE),
                icons(badges));
        assertEquals(Arrays.asList(MediaBadgeResolver.PLATE_RES_4K, MediaBadgeResolver.PLATE_HDR10,
                        MediaBadgeResolver.PLATE_CODEC_HEVC, null, null),
                plates(badges));
        assertEquals(List.of("23.976FPS", "15MBPS"), texts(badges));
    }

    /**
     * 牌自己已经写明了事实时，右侧不再重复同一段文字，
     * 否则屏幕上会出现 "4K 4K" 这种一行两个同名徽标。
     */
    @Test
    public void plateBadgesDoNotRepeatTheirOwnText() {
        Format video = video(3840, 2160, "video/hevc", 0f, 0, C.COLOR_TRANSFER_SDR);

        assertEquals(List.of(), texts(MediaBadgeResolver.resolve(video, null, null, null)));
    }

    @Test
    public void dolbyVisionReplacesTheHdrBadge() {
        PlayerEngine.VideoPlaybackDetails details = new PlayerEngine.VideoPlaybackDetails(
                "dvhe.07.06", 8, 6, "dvhe.08.06", "c2.qti.hevc.decoder", "", null, false);
        Format video = video(3840, 2160, "video/dolby-vision", 0f, 0, C.COLOR_TRANSFER_ST2084);
        List<MediaBadgeResolver.MediaBadge> badges = MediaBadgeResolver.resolve(video, details, null, null);

        assertEquals(Arrays.asList(MediaBadgeResolver.PLATE_RES_4K, MediaBadgeResolver.PLATE_DV,
                        MediaBadgeResolver.PLATE_CODEC_HEVC),
                plates(badges));
        // DV 牌只写 "Dolby Vision"，Profile 号仍然作为文本保留，信息不丢。
        assertEquals(List.of("DV.P8"), texts(badges));
        assertFalse(plates(badges).contains(MediaBadgeResolver.PLATE_HDR10));
    }

    @Test
    public void fullHdH264KeepsIntegerFrameRate() {
        Format video = video(1920, 1080, "video/avc", 60f, 0, C.COLOR_TRANSFER_SDR);
        List<MediaBadgeResolver.MediaBadge> badges = MediaBadgeResolver.resolve(video, null, null, null);

        assertEquals(Arrays.asList(MediaBadgeResolver.PLATE_RES_1080P, MediaBadgeResolver.PLATE_CODEC_AVC, null),
                plates(badges));
        assertEquals(List.of("60FPS"), texts(badges));
    }

    @Test
    public void hlgIsNamedAndSdrIsOmitted() {
        Format hlg = video(1920, 1080, "video/hevc", 0f, 0, C.COLOR_TRANSFER_HLG);
        Format sdr = video(1920, 1080, "video/hevc", 0f, 0, C.COLOR_TRANSFER_SDR);

        assertEquals(Arrays.asList(MediaBadgeResolver.PLATE_RES_1080P, MediaBadgeResolver.PLATE_HLG,
                        MediaBadgeResolver.PLATE_CODEC_HEVC),
                plates(MediaBadgeResolver.resolve(hlg, null, null, null)));
        assertEquals(Arrays.asList(MediaBadgeResolver.PLATE_RES_1080P, MediaBadgeResolver.PLATE_CODEC_HEVC),
                plates(MediaBadgeResolver.resolve(sdr, null, null, null)));
    }

    @Test
    public void audioBadgeCarriesCodecLayoutAndRates() {
        AudioPlaybackDiagnostics.Track audio = new AudioPlaybackDiagnostics.Track("E-AC-3", 6, 48000, "en", 640000);
        List<MediaBadgeResolver.MediaBadge> badges = MediaBadgeResolver.resolve(null, null, audio, null);

        assertEquals(List.of(MediaBadgeResolver.Icon.AUDIO), icons(badges));
        assertEquals(List.of(MediaBadgeResolver.PLATE_AUDIO_DDP), plates(badges));
        assertEquals(List.of("5.1 48KHZ 640KBPS"), texts(badges));
    }

    @Test
    public void audioBadgeOmitsFactsThatAreMissing() {
        AudioPlaybackDiagnostics.Track audio = new AudioPlaybackDiagnostics.Track("AAC", 2, 0, "", 0);
        List<MediaBadgeResolver.MediaBadge> badges = MediaBadgeResolver.resolve(null, null, audio, null);

        assertEquals(List.of(MediaBadgeResolver.PLATE_AUDIO_AAC), plates(badges));
        assertEquals(List.of("2.0"), texts(badges));
    }

    @Test
    public void audioBadgeWithoutAPlateKeepsTheCodecAsText() {
        AudioPlaybackDiagnostics.Track audio = new AudioPlaybackDiagnostics.Track("Vorbis", 2, 0, "", 0);
        List<MediaBadgeResolver.MediaBadge> badges = MediaBadgeResolver.resolve(null, null, audio, null);

        assertEquals(Arrays.asList((String) null), plates(badges));
        assertEquals(List.of("Vorbis 2.0"), texts(badges));
    }

    /**
     * media3 对 MP4 里的 AAC 上报的是 RFC 6381 编解码串 {@code mp4a.40.2}，
     * 徽标必须归一成品牌名，而不是把 MIME 子类型原样丢到屏幕上。
     */
    @Test
    public void mp4aCodecIsShownAsAac() {
        AudioPlaybackDiagnostics.Track audio = new AudioPlaybackDiagnostics.Track("mp4a.40.2", 2, 44100, "", 64000);
        List<MediaBadgeResolver.MediaBadge> badges = MediaBadgeResolver.resolve(null, null, audio, null);

        assertEquals(List.of(MediaBadgeResolver.PLATE_AUDIO_AAC), plates(badges));
        assertEquals(List.of("2.0 44.1KHZ 64KBPS"), texts(badges));
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
        assertEquals(List.of(), MediaBadgeResolver.resolve(null, null, null, null));
        assertEquals(List.of(), MediaBadgeResolver.resolve(new Format.Builder().build(),
                PlayerEngine.VideoPlaybackDetails.empty(), AudioPlaybackDiagnostics.Track.empty(), ""));
    }

    @Test
    public void duplicateFactsAreCollapsed() {
        Format video = video(3840, 2160, "dvhe.07.06", 0f, 0, C.COLOR_TRANSFER_SDR);
        PlayerEngine.VideoPlaybackDetails details = new PlayerEngine.VideoPlaybackDetails(
                "dvhe.07.06", 7, 6, "dvhe.07.06", "c2.qti.hevc.decoder", "", null, false);
        List<MediaBadgeResolver.MediaBadge> badges = MediaBadgeResolver.resolve(video, details, null, null);

        assertEquals(Arrays.asList(MediaBadgeResolver.PLATE_RES_4K, MediaBadgeResolver.PLATE_DV,
                MediaBadgeResolver.PLATE_CODEC_HEVC), plates(badges));
        assertEquals(List.of("DV.P7"), texts(badges));
    }

    /** 牌已写明的空文本不参与断言。 */
    private static List<String> texts(List<MediaBadgeResolver.MediaBadge> badges) {
        List<String> values = new ArrayList<>();
        for (MediaBadgeResolver.MediaBadge badge : badges) {
            if (badge.text() == null || badge.text().isEmpty()) continue;
            values.add(badge.text());
        }
        return values;
    }

    private static List<String> plates(List<MediaBadgeResolver.MediaBadge> badges) {
        List<String> values = new ArrayList<>();
        for (MediaBadgeResolver.MediaBadge badge : badges) values.add(badge.plate());
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
