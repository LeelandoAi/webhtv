package com.fongmi.android.tv.player;

import androidx.media3.common.C;
import androidx.media3.common.ColorInfo;
import androidx.media3.common.Format;

import com.fongmi.android.tv.player.engine.PlayerEngine;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 把播放器的运行时事实转成屏显媒体信息徽标（chip）。
 * <p>
 * 只输出可证事实：分辨率、动态范围、视频编码、帧率、视频码率、音频规格、容器。
 * 发布标签（WEB-DL / REMUX 等）与来源类型（HLS / DASH / 网盘）不在此输出，
 * 因为本项目的源以爬虫直链、HLS、网盘为主，无法从运行时事实证明这些标签。
 * 纯逻辑、无 Android 框架依赖，可脱离设备做 JVM 单测。
 */
public final class MediaBadgeResolver {

    private static final String[] EXTENSIONS = {"mkv", "mp4", "m4v", "webm", "mov", "avi", "flv", "ts", "m2ts", "wmv", "mpg", "mpeg", "3gp", "ogv"};

    /** 徽标类别，决定徽标左侧显示哪一个图标。 */
    public enum Icon {
        RESOLUTION, DYNAMIC_RANGE, VIDEO_CODEC, FRAME_RATE, BITRATE, AUDIO, CONTAINER
    }

    /** 一条徽标：展示文本 + 类别图标。 */
    public record MediaBadge(String text, Icon icon) {
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
        add(badges, resolution(video), Icon.RESOLUTION);
        add(badges, dynamicRange(videoDetails, video), Icon.DYNAMIC_RANGE);
        add(badges, videoCodec(video, videoDetails), Icon.VIDEO_CODEC);
        add(badges, frameRate(video), Icon.FRAME_RATE);
        add(badges, videoBitrate(video), Icon.BITRATE);
        add(badges, audioText(audio), Icon.AUDIO);
        add(badges, container(video, url), Icon.CONTAINER);
        return badges;
    }

    private static void add(List<MediaBadge> badges, String text, Icon icon) {
        if (text == null) return;
        String value = text.trim();
        if (value.isEmpty()) return;
        for (MediaBadge badge : badges) if (badge.text().equals(value)) return;
        badges.add(new MediaBadge(value, icon));
    }

    private static String resolution(Format video) {
        if (video == null) return null;
        int width = video.width;
        int height = video.height;
        if (width <= 0 || height <= 0) return null;
        if (width >= 3840 && height >= 2000) return "4K";
        if (width >= 2560 && height >= 1400) return "1440P";
        if (width >= 1920 && height >= 1000) return "1080P";
        if (width >= 1280 && height >= 700) return "720P";
        return width + "X" + height;
    }

    private static String dynamicRange(PlayerEngine.VideoPlaybackDetails details, Format video) {
        if (details != null && details.hasDolbyVisionSource()) {
            return details.dolbyVisionProfile() > 0 ? "DV.P" + details.dolbyVisionProfile() : "DV";
        }
        ColorInfo color = details != null && details.outputColorInfo() != null ? details.outputColorInfo() : (video != null ? video.colorInfo : null);
        if (color == null) return null;
        if (color.colorTransfer == C.COLOR_TRANSFER_ST2084) return "HDR10";
        if (color.colorTransfer == C.COLOR_TRANSFER_HLG) return "HLG";
        if (ColorInfo.isTransferHdr(color)) return "HDR";
        return null;
    }

    private static String videoCodec(Format video, PlayerEngine.VideoPlaybackDetails details) {
        String codecs = video != null && !isEmpty(video.codecs) ? video.codecs : details != null ? details.sourceCodecs() : null;
        if (isEmpty(codecs)) return null;
        String value = codecs.toLowerCase(Locale.US);
        // Dolby Vision 是叠加在基础编码上的，动态范围徽标已经说明 DV，
        // 这里如实给出底层编码，不再重复一个 DV 徽标。
        if (value.startsWith("dvhe") || value.startsWith("dvh1") || value.contains("dolby-vision")) return "HEVC";
        if (value.startsWith("dav1")) return "AV1";
        if (value.startsWith("dvav") || value.startsWith("dva1")) return "H.264";
        if (value.contains("hev1") || value.contains("hvc1") || value.contains("hevc") || value.contains("h265")) return "HEVC";
        if (value.contains("avc") || value.contains("h264")) return "H.264";
        if (value.contains("av01") || value.contains("av1")) return "AV1";
        if (value.contains("vp09") || value.contains("vp9")) return "VP9";
        if (value.contains("vp08") || value.contains("vp8")) return "VP8";
        if (value.contains("mp4v") || value.contains("mpeg2") || value.contains("mpeg4")) return "MPEG-4";
        int dot = value.indexOf('.');
        return upper(dot > 0 ? value.substring(0, dot) : value);
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

    private static String audioText(AudioPlaybackDiagnostics.Track audio) {
        if (audio == null || !audio.available()) return null;
        StringBuilder builder = new StringBuilder();
        if (!isEmpty(audio.codec())) builder.append(audioCodec(audio.codec()));
        String channels = AudioPlaybackDiagnostics.channelLabel(audio.channels());
        if (!isEmpty(channels)) append(builder, channels);
        if (audio.sampleRate() > 0) append(builder, sampleRateText(audio.sampleRate()));
        if (audio.bitrate() > 0) append(builder, bitrateText(audio.bitrate()));
        return builder.length() == 0 ? null : builder.toString();
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