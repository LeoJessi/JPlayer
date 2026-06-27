package top.jessi.videoplayer.exo;

import android.annotation.SuppressLint;
import android.content.res.Resources;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.util.Assertions;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.common.util.Util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 将 {@link Format} 转换为人类可读的轨道名称。
 *
 * <p>该类根据轨道类型（视频、音频、文本等）构建包含分辨率、码率、语言、声道数等信息的
 * 格式化字符串，用于 UI 层展示轨道选择列表。
 *
 * <p><b>线程安全性：</b>该类是线程安全的，除 {@link #resources} 外无其他可变状态。
 *
 * <p><b>注意：</b>该类标记为 {@link UnstableApi}，意味着其 API 可能在后续版本中发生变更。
 */
@UnstableApi
public class ExoTrackNameProvider {

    /** 比特到百万比特的转换系数，用于将 bps 转换为 Mbps。 */
    private static final int BITS_PER_MILLION = 1_000_000;

    private final Resources resources;

    /**
     * @param resources Resources from which to obtain strings.
     */
    public ExoTrackNameProvider(Resources resources) {
        this.resources = Assertions.checkNotNull(resources);
    }

    /**
     * 根据 {@link Format} 构建人类可读的轨道名称。
     *
     * @param format 媒体格式信息
     * @return 轨道名称字符串；若无法构建有效名称，返回 "Unknown"
     */
    public String getTrackName(Format format) {
        int trackType = inferPrimaryTrackType(format);
        String trackName;
        if (trackType == C.TRACK_TYPE_VIDEO) {
            trackName = buildVideoTrackName(format);
        } else if (trackType == C.TRACK_TYPE_AUDIO) {
            trackName = buildAudioTrackName(format);
        } else {
            trackName = buildLanguageRoleOrLabelString(format);
        }
        return TextUtils.isEmpty(trackName) ? resources.getString(R.string.exo_track_unknown) : trackName;
    }

    /**
     * 构建视频轨道名称，包含角色标签、分辨率和码率信息。
     */
    private String buildVideoTrackName(Format format) {
        return joinWithSeparator(
                buildRoleString(format),
                buildResolutionString(format),
                buildBitrateString(format));
    }

    /**
     * 构建音频轨道名称，包含语言/角色/标签、声道数和码率信息。
     * 例如： English, Stereo, 0.11 Mbps
     * 现在暂时只要语言
     */
    private String buildAudioTrackName(Format format) {
        // return joinWithSeparator(
        //         buildLanguageRoleOrLabelString(format),
        //         buildAudioChannelString(format),
        //         buildBitrateString(format));
        return joinWithSeparator(buildLanguageRoleOrLabelString(format));
    }

    /**
     * 推断轨道的主要类型。
     *
     * <p>推断优先级：
     * <ol>
     *   <li>sampleMimeType（若非空）</li>
     *   <li>codecs 字段</li>
     *   <li>width/height（视频特征）</li>
     *   <li>channelCount/sampleRate（音频特征）</li>
     * </ol>
     *
     * @param format 媒体格式信息
     * @return 轨道类型常量，如 {@link C#TRACK_TYPE_VIDEO}、{@link C#TRACK_TYPE_AUDIO} 或
     *         {@link C#TRACK_TYPE_UNKNOWN}
     */
    private static int inferPrimaryTrackType(Format format) {
        // 优先根据 sampleMimeType 判断（需先判空，避免 null 穿透到后续分支）
        @Nullable String sampleMimeType = format.sampleMimeType;
        if (!TextUtils.isEmpty(sampleMimeType)) {
            int trackType = MimeTypes.getTrackType(sampleMimeType);
            if (trackType != C.TRACK_TYPE_UNKNOWN) {
                return trackType;
            }
        }
        // 根据 codecs 判断
        if (MimeTypes.getVideoMediaMimeType(format.codecs) != null) {
            return C.TRACK_TYPE_VIDEO;
        }
        if (MimeTypes.getAudioMediaMimeType(format.codecs) != null) {
            return C.TRACK_TYPE_AUDIO;
        }
        // 根据格式特征判断
        if (format.width != Format.NO_VALUE || format.height != Format.NO_VALUE) {
            return C.TRACK_TYPE_VIDEO;
        }
        if (format.channelCount != Format.NO_VALUE || format.sampleRate != Format.NO_VALUE) {
            return C.TRACK_TYPE_AUDIO;
        }
        return C.TRACK_TYPE_UNKNOWN;
    }

    /**
     * 构建分辨率字符串。
     *
     * <p>与 {@link #buildBitrateString} 结构对称，均先检查 {@link Format#NO_VALUE}，
     * 再决定是否格式化输出。
     */
    @SuppressLint("StringFormatInvalid")
    private String buildResolutionString(Format format) {
        int width = format.width;
        int height = format.height;
        return width == Format.NO_VALUE || height == Format.NO_VALUE
                ? ""
                : resources.getString(R.string.exo_track_resolution, width, height);
    }

    /**
     * 构建码率字符串，单位为 Mbps。
     */
    @SuppressLint("StringFormatInvalid")
    private String buildBitrateString(Format format) {
        int bitrate = format.bitrate;
        return bitrate == Format.NO_VALUE
                ? ""
                : resources.getString(R.string.exo_track_bitrate, bitrate / (float) BITS_PER_MILLION);
    }

    /**
     * 构建音频声道数描述字符串。
     *
     * <p>支持常见的声道配置：单声道、立体声、5.1、7.1。
     * 对于其他声道数（如 3、4、5 等），统一使用"环绕声"描述。
     */
    private String buildAudioChannelString(Format format) {
        int channelCount = format.channelCount;
        if (channelCount == Format.NO_VALUE || channelCount < 1) {
            return "";
        }
        switch (channelCount) {
            case 1:
                return resources.getString(R.string.exo_track_mono);
            case 2:
                return resources.getString(R.string.exo_track_stereo);
            case 6:
            case 7:
                return resources.getString(R.string.exo_track_surround_5_point_1);
            case 8:
                return resources.getString(R.string.exo_track_surround_7_point_1);
            default:
                // 对于 3、4、5 或其他声道数，使用通用环绕声描述
                return resources.getString(R.string.exo_track_surround);
        }
    }

    /**
     * 构建角色标签字符串，将多个角色标签用分隔符连接。
     *
     * <p>使用 {@link List} 收集非空角色文本，避免中间字符串重复创建。
     */
    private String buildRoleString(Format format) {
        List<String> roles = new ArrayList<>();
        if ((format.roleFlags & C.ROLE_FLAG_ALTERNATE) != 0) {
            roles.add(resources.getString(R.string.exo_track_role_alternate));
        }
        if ((format.roleFlags & C.ROLE_FLAG_SUPPLEMENTARY) != 0) {
            roles.add(resources.getString(R.string.exo_track_role_supplementary));
        }
        if ((format.roleFlags & C.ROLE_FLAG_COMMENTARY) != 0) {
            roles.add(resources.getString(R.string.exo_track_role_commentary));
        }
        if ((format.roleFlags & (C.ROLE_FLAG_CAPTION | C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND)) != 0) {
            roles.add(resources.getString(R.string.exo_track_role_closed_captions));
        }
        return joinWithSeparator(roles.toArray(new String[0]));
    }

    /**
     * 构建语言、角色和标签的组合字符串。
     *
     * <p>优先返回"语言 + 角色"的组合；若为空，则 fallback 到 {@link Format#label}。
     */
    private String buildLanguageRoleOrLabelString(Format format) {
        String languageRole = joinWithSeparator(buildLanguageString(format), buildRoleString(format));
        if (!TextUtils.isEmpty(languageRole)) {
            return languageRole;
        }
        // fallback 到 label
        return TextUtils.isEmpty(format.label) ? "Track " : format.label;
    }

    /**
     * 构建语言显示名称字符串。
     *
     * <p>将语言代码转换为本地化显示名称，并将首字母大写。
     * 参考: <a href="https://github.com/google/ExoPlayer/issues/9452">ExoPlayer#9452</a>。
     */
    private String buildLanguageString(Format format) {
        @Nullable String language = format.language;
        if (TextUtils.isEmpty(language) || C.LANGUAGE_UNDETERMINED.equals(language)) {
            return "";
        }
        Locale languageLocale =
                Util.SDK_INT >= 21 ? Locale.forLanguageTag(language) : new Locale(language);
        Locale displayLocale =
                Util.SDK_INT >= 24 ? Locale.getDefault(Locale.Category.DISPLAY) : Locale.getDefault();
        String languageName = languageLocale.getDisplayName(displayLocale);
        if (TextUtils.isEmpty(languageName)) {
            return "";
        }
        return capitalizeFirstLetter(languageName, displayLocale);
    }

    /**
     * 将字符串首字母大写。
     *
     * <p>使用 codepoint 方式处理 Unicode 补充字符，避免 {@link String#substring} 拆分 surrogate pair。
     *
     * @param str 待处理的字符串
     * @param locale 用于大小写转换的 Locale
     * @return 首字母大写后的字符串；若发生异常则返回原字符串
     */
    private String capitalizeFirstLetter(String str, Locale locale) {
        if (TextUtils.isEmpty(str)) {
            return str;
        }
        try {
            int firstCodePointLength = str.offsetByCodePoints(0, 1);
            return str.substring(0, firstCodePointLength).toUpperCase(locale)
                    + str.substring(firstCodePointLength);
        } catch (IndexOutOfBoundsException e) {
            // 理论上不会发生，但为安全起见返回原字符串
            return str;
        }
    }

    /**
     * 使用本地化格式将多个字符串用分隔符连接。
     *
     * <p>该方法通过 {@code R.string.exo_item_list} 资源进行格式化，以支持不同语言的列表格式
     * （如 RTL 语言）。先过滤空字符串，若仅剩一个非空项则直接返回，避免不必要的资源调用。
     *
     * @param items 待连接的字符串数组
     * @return 连接后的字符串；若全部为空则返回空字符串
     */
    @SuppressLint("StringFormatInvalid")
    private String joinWithSeparator(String... items) {
        // 收集非空 items，减少循环中的重复判断
        List<String> nonEmptyItems = new ArrayList<>();
        for (String item : items) {
            if (item.length() > 0) {
                nonEmptyItems.add(item);
            }
        }
        if (nonEmptyItems.isEmpty()) {
            return "";
        }
        if (nonEmptyItems.size() == 1) {
            return nonEmptyItems.get(0);
        }
        // 保留递归格式化以支持本地化列表格式
        String result = nonEmptyItems.get(0);
        for (int i = 1; i < nonEmptyItems.size(); i++) {
            result = resources.getString(R.string.exo_item_list, result, nonEmptyItems.get(i));
        }
        return result;
    }
}