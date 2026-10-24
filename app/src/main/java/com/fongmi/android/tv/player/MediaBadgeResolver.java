package com.fongmi.android.tv.player;

import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;

import com.fongmi.android.tv.player.engine.PlayerEngine;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * 把播放器的运行时事实转成屏显媒体信息徽标（chip）。
 * <p>
 * 只输出可证事实：分辨率、动态范围、视频编码、帧率、视频码率、音频规格、容器。
 * 发布标签（WEB-DL / REMUX 等）与来源类型（HLS / DASH / 网盘）不在此输出，
 * 因为本项目的源以爬虫直链、HLS、网盘为主，无法从运行时事实证明这些标签。
 * <p>
 * 每条徽标由三部分组成：类别图标、可选品牌牌（{@code plate}，主流播放器同类徽标的成品图形）、
 * 以及事实文本。牌已经写明了事实时（例如 4K 牌与 "4K" 文本），文本会被剥掉，避免同一行里重复。
 * 纯逻辑、无 Android 框架依赖，可脱离设备做 JVM 单测。
 */
public final class MediaBadgeResolver {

    private static final String[] EXTENSIONS = {"mkv", "mp4", "m4v", "webm", "mov", "avi", "flv", "ts", "m2ts", "wmv", "mpg", "mpeg", "3gp", "ogv"};

    /** 徽标类别，决定徽标颜色与缺牌时要画的通用图标。 */
    public enum Icon {
        RESOLUTION, DYNAMIC_RANGE, VIDEO_CODEC, FRAME_RATE, BITRATE, AUDIO, CONTAINER
    }

    /**
     * 徽标内容。
     *
     * @param text  事实文本；当 {@code plate} 已经完整写明该事实时可能为空串
     * @param icon  类别，决定颜色与缺牌时的通用图标
     * @param plate 成品牌的资源键（见 PLATE_* 常量）；没有对应牌时为 null
     */
    public record MediaBadge(String text, Icon icon, String plate) {
    }

    /** 分辨率牌。 */
    public static final String PLATE_RES_4K = "RES_4K";
    public static final String PLATE_RES_1440P = "RES_1440P";
    public static final String PLATE_RES_1080P = "RES_1080P";
    public static final String PLATE_RES_720P = "RES_720P";
    /** 动态范围牌。 */
    public static final String PLATE_DV = "DV";
    public static final String PLATE_HDR10 = "HDR10";
    public static final String PLATE_HDR = "HDR";
    public static final String PLATE_HLG = "HLG";
    /** 视频编码牌。 */
    public static final String PLATE_CODEC_HEVC = "CODEC_HEVC";
    public static final String PLATE_CODEC_AVC = "CODEC_AVC";
    public static final String PLATE_CODEC_AV1 = "CODEC_AV1";
    /** 音频编码牌。 */
    public static final String PLATE_AUDIO_AAC = "AUDIO_AAC";
    public static final String PLATE_AUDIO_FLAC = "AUDIO_FLAC";
    public static final String PLATE_AUDIO_OPUS = "AUDIO_OPUS";
    public static final String PLATE_AUDIO_DD = "AUDIO_DD";
    public static final String PLATE_AUDIO_DDP = "AUDIO_DDP";
    public static final String PLATE_AUDIO_TRUEHD = "AUDIO_TRUEHD";
    public static final String PLATE_AUDIO_DTS = "AUDIO_DTS";

    /** 牌：资源键 + 它已经写明的字样（含同义写法），用于剥掉重复文本。 */
    private record Plate(String key, List<String> labels) {
    }

    private MediaBadgeResolver() {
    }

    /**
     * @param video        当前视频轨；可为 null
     * @param videoDetails 引擎给出的视频播放细节（Dolby Vision 等）；可为 null
     * @param audio        当前音频轨；可为 null
     * @param url          播放地址，仅在容器 MIME 缺失时用于扩展名兜底；可为 null
     * @return 按展示顺序排列的徽标，可能为空列表
     */
    public static List<MediaBadge> resolve(Format video, PlayerEngine.VideoPlaybackDetails videoDetails, AudioPlaybackDiagnostics.Track audio, String url) {
        List<MediaBadge> badges = new ArrayList<>();
        add(badges, resolutionBadge(video), Icon.RESOLUTION);
        add(badges, dynamicRangeBadge(videoDetails, video), Icon.DYNAMIC_RANGE);
        add(badges, videoCodecBadge(video, videoDetails), Icon.VIDEO_CODEC);
        add(badges, badge(frameRate(video), null), Icon.FRAME_RATE);
        add(badges, badge(videoBitrate(video), null), Icon.BITRATE);
        add(badges, audioBadge(audio), Icon.AUDIO);
        add(badges, badge(container(video, url), null), Icon.CONTAINER);
        return badges;
    }

    private static void add(List<MediaBadge> badges, MediaBadge badge, Icon icon) {
        if (badge == null) return;
        String text = badge.text() == null ? "" : badge.text().trim();
        if (text.isEmpty() && badge.plate() == null) return;
        for (MediaBadge existing : badges) {
            if (existing.text().equals(text) && Objects.equals(existing.plate(), badge.plate())) return;
        }
        badges.add(new MediaBadge(text, icon, badge.plate()));
    }

    /** 组装一条徽标：牌已经写明的字样从文本里剥掉。 */
    private static MediaBadge badge(String text, Plate plate) {
        if (text == null || text.trim().isEmpty()) return null;
        return new MediaBadge(stripPlate(text, plate), null, plate == null ? null : plate.key());
    }

    private static String stripPlate(String text, Plate plate) {
        String value = text.trim();
        if (plate == null) return value;
        for (String label : plate.labels()) {
            if (value.equalsIgnoreCase(label)) return "";
            if (value.regionMatches(true, 0, label + " ", 0, label.length() + 1)) return value.substring(label.length()).trim();
        }
        return value;
    }

    private static MediaBadge resolutionBadge(Format video) {
        if (video == null) return null;
        int width = video.width;
        int height = video.height;
        if (width <= 0 || height <= 0) return null;
        if (width >= 3840 && height >= 2000) return badge("4K", new Plate(PLATE_RES_4K, List.of("4K")));
        if (width >= 2560 && height >= 1400) return badge("1440P", new Plate(PLATE_RES_1440P, List.of("1440P", "2K")));
        if (width >= 1920 && height >= 1000) return badge("1080P", new Plate(PLATE_RES_1080P, List.of("1080P", "FHD")));
        if (width >= 1280 && height >= 700) return badge("720P", new Plate(PLATE_RES_720P, List.of("720P", "HD")));
        return badge(width + "X" + height, null);
    }

    private static MediaBadge dynamicRangeBadge(PlayerEngine.VideoPlaybackDetails details, Format video) {
        if (details != null && details.hasDolbyVisionSource()) {
            return badge(details.dolbyVisionProfile() > 0 ? "DV.P" + details.dolbyVisionProfile() : "DV", new Plate(PLATE_DV, List.of("DV")));
        }
        ColorInfo color = details != null && details.outputColorInfo() != null ? details.outputColorInfo() : (video != null ? video.colorInfo : null);
        if (color == null) return null;
        if (color.colorTransfer == C.COLOR_TRANSFER_ST2084) return badge("HDR10", new Plate(PLATE_HDR10, List.of("HDR10")));
        if (color.colorTransfer == C.COLOR_TRANSFER_HLG) return badge("HLG", new Plate(PLATE_HLG, List.of("HLG")));
        if (ColorInfo.isTransferHdr(color)) return badge("HDR", new Plate(PLATE_HDR, List.of("HDR")));
        return null;
    }

    private static MediaBadge videoCodecBadge(Format video, PlayerEngine.VideoPlaybackDetails details) {
        String codecs = video != null && !isEmpty(video.codecs) ? video.codecs : details != null ? details.sourceCodecs() : null;
        if (isEmpty(codecs)) return null;
        String value = codecs.toLowerCase(Locale.US);
        // Dolby Vision 是叠加在基础编码上的，动态范围徽标已经说明 DV，
        // 这里如实给出底层编码，不再重复一个 DV 徽标。
        if (value.startsWith("dvhe") || value.startsWith("dvh1") || value.contains("dolby-vision")) return badge("HEVC", new Plate(PLATE_CODEC_HEVC, List.of("HEVC", "H.265", "H265")));
        if (value.startsWith("dav1")) return badge("AV1", new Plate(PLATE_CODEC_AV1, List.of("AV1")));
        if (value.startsWith("dvav") || value.startsWith("dva1")) return badge("H.264", new Plate(PLATE_CODEC_AVC, List.of("H.264", "H264", "AVC")));
        if (value.contains("hev1") || value.contains("hvc1") || value.contains("hevc") || value.contains("h265")) return badge("HEVC", new Plate(PLATE_CODEC_HEVC, List.of("HEVC", "H.265", "H265")));
        if (value.contains("avc") || value.contains("h264")) return badge("H.264", new Plate(PLATE_CODEC_AVC, List.of("H.264", "H264", "AVC")));
        if (value.contains("av01") || value.contains("av1")) return badge("AV1", new Plate(PLATE_CODEC_AV1, List.of("AV1")));
        if (value.contains("vp09") || value.contains("vp9")) return badge("VP9", null);
        if (value.contains("vp08") || value.contains("vp8")) return badge("VP8", null);
        if (value.contains("mp4v") || value.contains("mpeg2") || value.contains("mpeg4")) return badge("MPEG-4", null);
        int dot = value.indexOf('.');
        return badge(upper(dot > 0 ? value.substring(0, dot) : value), null);
    }

    private static String frameRate(Format video) {
        if (video == null) return null;
        float frameRate = video.frameRate;
        if (frameRate <= 0f) return null;
        String value = frameRate == Math.rint(frameRate) ? String.valueOf((int) frameRate) : trimZeros(String.format(Locale.US, "%.3f", frameRate));
        return value + "FPS";
    }

    private static String videoBitrate(Format video) {
        if (video == null) return null;
        int bitrate = Math.max(video.averageBitrate, video.bitrate);
        if (bitrate <= 0) return null;
        return bitrateText(bitrate);
    }

    private static MediaBadge audioBadge(AudioPlaybackDiagnostics.Track audio) {
        if (audio == null || !audio.available()) return null;
        String codec = isEmpty(audio.codec()) ? "" : audioCodec(audio.codec());
        StringBuilder builder = new StringBuilder();
        if (!codec.isEmpty()) builder.append(codec);
        String channels = AudioPlaybackDiagnostics.channelLabel(audio.channels());
        if (!isEmpty(channels)) append(builder, channels);
        if (audio.sampleRate() > 0) append(builder, sampleRateText(audio.sampleRate()));
        if (audio.bitrate() > 0) append(builder, bitrateText(audio.bitrate()));
        if (builder.length() == 0) return null;
        return badge(builder.toString(), audioPlate(codec));
    }

    /**
     * 复用音频诊断的编解码归一化，避免把 media3 的 MIME 子类型（mp4a.40.2）直接当徽标文本。
     * 归一化失败时退回原始 codec 的大写形式，保证信息不丢。
     */
    private static String audioCodec(String codec) {
        String label = AudioPlaybackDiagnostics.codecLabel(codec, "", "", "");
        // codecLabel 已经给出规范大写/品牌写法（AAC、E-AC-3、Dolby TrueHD Atmos），
        // 只有它认不出来时才退回原始 codec 的大写形式。
        if (label.isEmpty() || "音频".equals(label)) return codec.toUpperCase(Locale.US);
        return label;
    }

    /** 音频牌按归一化后的编解码名匹配；认不出的编码不留牌，只留文本。 */
    private static Plate audioPlate(String label) {
        switch (label) {
            case "AAC":
                return new Plate(PLATE_AUDIO_AAC, List.of("AAC"));
            case "FLAC":
                return new Plate(PLATE_AUDIO_FLAC, List.of("FLAC"));
            case "Opus":
                return new Plate(PLATE_AUDIO_OPUS, List.of("Opus"));
            case "AC-3":
                return new Plate(PLATE_AUDIO_DD, List.of("AC-3", "Dolby Digital"));
            case "E-AC-3":
            case "E-AC-3 JOC":
                return new Plate(PLATE_AUDIO_DDP, List.of("E-AC-3", "Dolby Digital Plus", "DD+"));
            case "Dolby TrueHD":
            case "Dolby TrueHD Atmos":
                return new Plate(PLATE_AUDIO_TRUEHD, List.of("Dolby TrueHD"));
            case "DTS":
            case "DTS-HD":
            case "DTS-HD HRA":
            case "DTS-HD MA":
            case "DTS:X":
                return new Plate(PLATE_AUDIO_DTS, List.of("DTS"));
            default:
                return null;
        }
    }

    private static void append(StringBuilder builder, String value) {
        if (builder.length() > 0) builder.append(' ');
        builder.append(value);
    }

    private static String sampleRateText(int sampleRate) {
        String value = sampleRate % 1000 == 0 ? String.valueOf(sampleRate / 1000) : trimZeros(String.format(Locale.US, "%.1f", sampleRate / 1000f));
        return value + "KHZ";
    }

    private static String bitrateText(int bitrate) {
        if (bitrate >= 1_000_000) return trimZeros(String.format(Locale.US, "%.1f", bitrate / 1_000_000f)) + "MBPS";
        return Math.max(1, bitrate / 1000) + "KBPS";
    }

    private static String container(Format video, String url) {
        String mime = video != null ? video.containerMimeType : null;
        String byMime = containerByMime(mime);
        if (byMime != null) return byMime;
        return containerByExtension(url);
    }

    private static String containerByMime(String mime) {
        if (isEmpty(mime)) return null;
        switch (mime.toLowerCase(Locale.US).trim()) {
            case "video/x-matroska":
            case "audio/x-matroska":
                return "MKV";
            case "video/mp4":
            case "audio/mp4":
            case "video/m4v":
                return "MP4";
            case "video/webm":
            case "audio/webm":
                return "WEBM";
            case "video/quicktime":
                return "MOV";
            case "video/x-msvideo":
                return "AVI";
            case "video/mp2t":
                return "TS";
            case "video/x-flv":
                return "FLV";
            case "video/3gpp":
            case "video/3gpp2":
                return "3GP";
            case "video/ogg":
                return "OGV";
            default:
                return null;
        }
    }

    private static String containerByExtension(String url) {
        String path = path(url);
        if (path.isEmpty()) return null;
        int dot = path.lastIndexOf('.');
        if (dot <= 0 || dot == path.length() - 1) return null;
        String extension = path.substring(dot + 1).toLowerCase(Locale.US);
        if (!isKnown(extension)) return null;
        if ("m4v".equals(extension)) return "MP4";
        if ("m2ts".equals(extension)) return "TS";
        return extension.toUpperCase(Locale.US);
    }

    private static boolean isKnown(String extension) {
        for (String value : EXTENSIONS) {
            if (value.equals(extension)) return true;
        }
        return false;
    }

    /** 去掉查询串与片段，避免把 "?a=b.ts" 误判成扩展名。 */
    private static String path(String url) {
        if (isEmpty(url)) return "";
        String value = url.trim();
        int end = value.length();
        int query = value.indexOf('?');
        if (query >= 0 && query < end) end = query;
        int fragment = value.indexOf('#');
        if (fragment >= 0 && fragment < end) end = fragment;
        value = value.substring(0, end);
        int slash = value.lastIndexOf('/');
        return slash >= 0 ? value.substring(slash + 1) : value;
    }

    private static String trimZeros(String value) {
        if (value.indexOf('.') < 0) return value;
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '0') end--;
        if (end > 0 && value.charAt(end - 1) == '.') end--;
        return value.substring(0, end);
    }

    private static String upper(String value) {
        return value.toUpperCase(Locale.US);
    }

    private static boolean isEmpty(String value) {
        return value == null || value.trim().isEmpty();
    }
}
