package top.jessi.videoplayer.util;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import io.github.anilbeesetti.nextlib.mediainfo.AudioStream;
import io.github.anilbeesetti.nextlib.mediainfo.MediaInfo;
import io.github.anilbeesetti.nextlib.mediainfo.MediaInfoBuilder;
import io.github.anilbeesetti.nextlib.mediainfo.SubtitleStream;

/**
 * 基于 nextlib-mediainfo 的媒体信息获取工具类。
 * <p>
 * 通过 FFmpeg 获取媒体文件的详细信息，包括：
 * • 视频流信息（编码格式、分辨率、帧率、比特率）
 * • 音频流信息（编码格式、语言、声道数、采样率、比特率）
 * • 字幕流信息（编码格式、语言）
 * <p>
 * 用于补充 ExoPlayer 自带的简单轨道名称，提供更详细的轨道信息。
 * <p>
 * 使用方式：
 * 1. 在播放开始时调用 preloadMediaInfo() 在后台预加载数据
 * 2. 在需要显示轨道信息时调用 getMediaInfo() 获取缓存的数据
 */
public class MediaInfoHelper {

    private static final String TAG = "MediaInfoHelper";

    /**
     * 后台线程池，用于异步获取媒体信息
     */
    private static final ExecutorService sExecutor = Executors.newSingleThreadExecutor();

    /**
     * 主线程 Handler，用于回调通知
     */
    private static final Handler sMainHandler = new Handler(Looper.getMainLooper());

    /**
     * 缓存媒体信息，避免重复解析
     */
    private static final Map<String, MediaInfo> sCache = new HashMap<>();

    /**
     * 正在加载中的 URI 集合，防止重复加载
     */
    private static final Map<String, Boolean> sLoading = new HashMap<>();

    /**
     * 媒体信息加载回调接口
     */
    public interface OnMediaInfoLoadedListener {
        void onMediaInfoLoaded(@Nullable MediaInfo mediaInfo);
    }

    /**
     * ISO 639-2/3 三字母语言代码 → 可读语言名称映射表
     */
    private static final Map<String, String> LANGUAGE_MAP = new HashMap<>();

    static {
        // A
        LANGUAGE_MAP.put("aar", "Afar");
        LANGUAGE_MAP.put("abk", "Abkhazian");
        LANGUAGE_MAP.put("afr", "Afrikaans");
        LANGUAGE_MAP.put("aka", "Akan");
        LANGUAGE_MAP.put("amh", "Amharic");
        LANGUAGE_MAP.put("ara", "Arabic");
        LANGUAGE_MAP.put("arg", "Aragonese");
        LANGUAGE_MAP.put("asm", "Assamese");
        LANGUAGE_MAP.put("ava", "Avaric");
        LANGUAGE_MAP.put("ave", "Avestan");
        LANGUAGE_MAP.put("aym", "Aymara");
        LANGUAGE_MAP.put("aze", "Azerbaijani");
        // B
        LANGUAGE_MAP.put("bak", "Bashkir");
        LANGUAGE_MAP.put("bam", "Bambara");
        LANGUAGE_MAP.put("bel", "Belarusian");
        LANGUAGE_MAP.put("ben", "Bengali");
        LANGUAGE_MAP.put("bih", "Bihari");
        LANGUAGE_MAP.put("bis", "Bislama");
        LANGUAGE_MAP.put("bos", "Bosnian");
        LANGUAGE_MAP.put("bre", "Breton");
        LANGUAGE_MAP.put("bul", "Bulgarian");
        // C
        LANGUAGE_MAP.put("cat", "Catalan");
        LANGUAGE_MAP.put("cha", "Chamorro");
        LANGUAGE_MAP.put("che", "Chechen");
        LANGUAGE_MAP.put("chu", "Church Slavic");
        LANGUAGE_MAP.put("chv", "Chuvash");
        LANGUAGE_MAP.put("cor", "Cornish");
        LANGUAGE_MAP.put("cos", "Corsican");
        LANGUAGE_MAP.put("cre", "Cree");
        LANGUAGE_MAP.put("cze", "Czech");
        // D
        LANGUAGE_MAP.put("dan", "Danish");
        LANGUAGE_MAP.put("deu", "German");
        LANGUAGE_MAP.put("div", "Divehi");
        LANGUAGE_MAP.put("dzo", "Dzongkha");
        // E
        LANGUAGE_MAP.put("eng", "English");
        LANGUAGE_MAP.put("epo", "Esperanto");
        LANGUAGE_MAP.put("est", "Estonian");
        LANGUAGE_MAP.put("ell", "Greek");
        LANGUAGE_MAP.put("eus", "Basque");
        LANGUAGE_MAP.put("ewe", "Ewe");
        // F
        LANGUAGE_MAP.put("fao", "Faroese");
        LANGUAGE_MAP.put("fas", "Persian");
        LANGUAGE_MAP.put("fil", "Filipino");
        LANGUAGE_MAP.put("fij", "Fijian");
        LANGUAGE_MAP.put("fin", "Finnish");
        LANGUAGE_MAP.put("fra", "French");
        LANGUAGE_MAP.put("fry", "Western Frisian");
        LANGUAGE_MAP.put("ful", "Fulah");
        // G
        LANGUAGE_MAP.put("gla", "Gaelic");
        LANGUAGE_MAP.put("gle", "Irish");
        LANGUAGE_MAP.put("glg", "Galician");
        LANGUAGE_MAP.put("glv", "Manx");
        LANGUAGE_MAP.put("grn", "Guarani");
        LANGUAGE_MAP.put("guj", "Gujarati");
        // H
        LANGUAGE_MAP.put("hat", "Haitian");
        LANGUAGE_MAP.put("hau", "Hausa");
        LANGUAGE_MAP.put("heb", "Hebrew");
        LANGUAGE_MAP.put("her", "Herero");
        LANGUAGE_MAP.put("hin", "Hindi");
        LANGUAGE_MAP.put("hmo", "Hiri Motu");
        LANGUAGE_MAP.put("hrv", "Croatian");
        LANGUAGE_MAP.put("hun", "Hungarian");
        LANGUAGE_MAP.put("hye", "Armenian");
        // I
        LANGUAGE_MAP.put("ibo", "Igbo");
        LANGUAGE_MAP.put("ido", "Ido");
        LANGUAGE_MAP.put("iii", "Sichuan Yi");
        LANGUAGE_MAP.put("iku", "Inuktitut");
        LANGUAGE_MAP.put("ile", "Interlingue");
        LANGUAGE_MAP.put("ina", "Interlingua");
        LANGUAGE_MAP.put("ind", "Indonesian");
        LANGUAGE_MAP.put("ipk", "Inupiaq");
        LANGUAGE_MAP.put("isl", "Icelandic");
        LANGUAGE_MAP.put("ita", "Italian");
        // J
        LANGUAGE_MAP.put("jav", "Javanese");
        LANGUAGE_MAP.put("jpn", "Japanese");
        // K
        LANGUAGE_MAP.put("kal", "Kalaallisut");
        LANGUAGE_MAP.put("kan", "Kannada");
        LANGUAGE_MAP.put("kas", "Kashmiri");
        LANGUAGE_MAP.put("kau", "Kanuri");
        LANGUAGE_MAP.put("kaz", "Kazakh");
        LANGUAGE_MAP.put("khm", "Central Khmer");
        LANGUAGE_MAP.put("kik", "Kikuyu");
        LANGUAGE_MAP.put("kin", "Kinyarwanda");
        LANGUAGE_MAP.put("kir", "Kirghiz");
        LANGUAGE_MAP.put("kom", "Komi");
        LANGUAGE_MAP.put("kon", "Kongo");
        LANGUAGE_MAP.put("kor", "Korean");
        LANGUAGE_MAP.put("kua", "Kuanyama");
        LANGUAGE_MAP.put("kur", "Kurdish");
        // L
        LANGUAGE_MAP.put("lao", "Lao");
        LANGUAGE_MAP.put("lat", "Latin");
        LANGUAGE_MAP.put("lav", "Latvian");
        LANGUAGE_MAP.put("lim", "Limburgan");
        LANGUAGE_MAP.put("lin", "Lingala");
        LANGUAGE_MAP.put("lit", "Lithuanian");
        LANGUAGE_MAP.put("ltz", "Luxembourgish");
        LANGUAGE_MAP.put("lub", "Luba-Katanga");
        LANGUAGE_MAP.put("lug", "Ganda");
        // M
        LANGUAGE_MAP.put("mah", "Marshallese");
        LANGUAGE_MAP.put("mal", "Malayalam");
        LANGUAGE_MAP.put("mao", "Maori");
        LANGUAGE_MAP.put("mar", "Marathi");
        LANGUAGE_MAP.put("mkd", "Macedonian");
        LANGUAGE_MAP.put("mlg", "Malagasy");
        LANGUAGE_MAP.put("mlt", "Maltese");
        LANGUAGE_MAP.put("mon", "Mongolian");
        LANGUAGE_MAP.put("msa", "Malay");
        LANGUAGE_MAP.put("mya", "Burmese");
        // N
        LANGUAGE_MAP.put("nau", "Nauru");
        LANGUAGE_MAP.put("nav", "Navajo");
        LANGUAGE_MAP.put("nbl", "Ndebele, South");
        LANGUAGE_MAP.put("nde", "Ndebele, North");
        LANGUAGE_MAP.put("ndo", "Ndonga");
        LANGUAGE_MAP.put("nep", "Nepali");
        LANGUAGE_MAP.put("nld", "Dutch");
        LANGUAGE_MAP.put("nno", "Norwegian Nynorsk");
        LANGUAGE_MAP.put("nob", "Bokmål, Norwegian");
        LANGUAGE_MAP.put("nor", "Norwegian");
        LANGUAGE_MAP.put("nya", "Chichewa");
        // O
        LANGUAGE_MAP.put("oci", "Occitan");
        LANGUAGE_MAP.put("oji", "Ojibwa");
        LANGUAGE_MAP.put("ori", "Oriya");
        LANGUAGE_MAP.put("orm", "Oromo");
        // P
        LANGUAGE_MAP.put("pan", "Panjabi");
        LANGUAGE_MAP.put("pli", "Pali");
        LANGUAGE_MAP.put("pol", "Polish");
        LANGUAGE_MAP.put("por", "Portuguese");
        LANGUAGE_MAP.put("pus", "Pushto");
        // Q
        LANGUAGE_MAP.put("que", "Quechua");
        // R
        LANGUAGE_MAP.put("roh", "Romansh");
        LANGUAGE_MAP.put("ron", "Romanian");
        LANGUAGE_MAP.put("run", "Rundi");
        LANGUAGE_MAP.put("rus", "Russian");
        // S
        LANGUAGE_MAP.put("sag", "Sango");
        LANGUAGE_MAP.put("san", "Sanskrit");
        LANGUAGE_MAP.put("sin", "Sinhala");
        LANGUAGE_MAP.put("slk", "Slovak");
        LANGUAGE_MAP.put("slv", "Slovenian");
        LANGUAGE_MAP.put("sme", "Northern Sami");
        LANGUAGE_MAP.put("smo", "Samoan");
        LANGUAGE_MAP.put("sna", "Shona");
        LANGUAGE_MAP.put("snd", "Sindhi");
        LANGUAGE_MAP.put("som", "Somali");
        LANGUAGE_MAP.put("sot", "Sotho, Southern");
        LANGUAGE_MAP.put("spa", "Spanish");
        LANGUAGE_MAP.put("sqi", "Albanian");
        LANGUAGE_MAP.put("srd", "Sardinian");
        LANGUAGE_MAP.put("srp", "Serbian");
        LANGUAGE_MAP.put("ssw", "Swati");
        LANGUAGE_MAP.put("sun", "Sundanese");
        LANGUAGE_MAP.put("swa", "Swahili");
        LANGUAGE_MAP.put("swe", "Swedish");
        // T
        LANGUAGE_MAP.put("tah", "Tahitian");
        LANGUAGE_MAP.put("tam", "Tamil");
        LANGUAGE_MAP.put("tat", "Tatar");
        LANGUAGE_MAP.put("tel", "Telugu");
        LANGUAGE_MAP.put("tgk", "Tajik");
        LANGUAGE_MAP.put("tag", "Tagalog");
        LANGUAGE_MAP.put("tha", "Thai");
        LANGUAGE_MAP.put("bod", "Tibetan");
        LANGUAGE_MAP.put("tir", "Tigrinya");
        LANGUAGE_MAP.put("ton", "Tonga");
        LANGUAGE_MAP.put("tsn", "Tswana");
        LANGUAGE_MAP.put("tso", "Tsonga");
        LANGUAGE_MAP.put("tuk", "Turkmen");
        LANGUAGE_MAP.put("tur", "Turkish");
        LANGUAGE_MAP.put("twi", "Twi");
        // U
        LANGUAGE_MAP.put("uig", "Uighur");
        LANGUAGE_MAP.put("ukr", "Ukrainian");
        LANGUAGE_MAP.put("urd", "Urdu");
        LANGUAGE_MAP.put("uzb", "Uzbek");
        // V
        LANGUAGE_MAP.put("ven", "Venda");
        LANGUAGE_MAP.put("vie", "Vietnamese");
        // W
        LANGUAGE_MAP.put("cym", "Welsh");
        LANGUAGE_MAP.put("wln", "Walloon");
        LANGUAGE_MAP.put("wol", "Wolof");
        // X
        LANGUAGE_MAP.put("xho", "Xhosa");
        // Y
        LANGUAGE_MAP.put("yid", "Yiddish");
        LANGUAGE_MAP.put("yor", "Yoruba");
        // Z
        LANGUAGE_MAP.put("zha", "Zhuang");
        LANGUAGE_MAP.put("zho", "Chinese");
        LANGUAGE_MAP.put("zul", "Zulu");

        // ========== 书目代码别名（ISO 639-2/B） ==========
        // 部分媒体文件使用旧的书目代码而非术语代码，此处添加别名确保兼容
        LANGUAGE_MAP.put("alb", "Albanian");      // sqi 的别名
        LANGUAGE_MAP.put("arm", "Armenian");      // hye 的别名
        LANGUAGE_MAP.put("baq", "Basque");        // eus 的别名
        LANGUAGE_MAP.put("bur", "Burmese");       // mya 的别名
        LANGUAGE_MAP.put("chi", "Chinese");       // zho 的别名
        LANGUAGE_MAP.put("dut", "Dutch");         // nld 的别名
        LANGUAGE_MAP.put("fre", "French");        // fra 的别名
        LANGUAGE_MAP.put("ger", "German");        // deu 的别名
        LANGUAGE_MAP.put("gre", "Greek");         // ell 的别名
        LANGUAGE_MAP.put("ice", "Icelandic");     // isl 的别名
        LANGUAGE_MAP.put("mac", "Macedonian");    // mkd 的别名
        LANGUAGE_MAP.put("per", "Persian");       // fas 的别名
        LANGUAGE_MAP.put("rum", "Romanian");      // ron 的别名
        LANGUAGE_MAP.put("slo", "Slovak");        // slk 的别名
        LANGUAGE_MAP.put("tib", "Tibetan");       // bod 的别名
        LANGUAGE_MAP.put("wel", "Welsh");         // cym 的别名
    }

    /**
     * 将 ISO 639-2/3 三字母语言代码转换为可读的语言名称
     *
     * @param code 三字母语言代码，如 "eng"、"zho"
     * @return 可读的语言名称，如 "English"、"Chinese"；未知代码返回原值
     */
    public static String getLanguageName(String code) {
        if (code == null || code.isEmpty()) {
            return code;
        }
        String name = LANGUAGE_MAP.get(code.toLowerCase());
        return name != null ? name : code;
    }

    /**
     * 在后台线程预加载媒体信息。
     * 建议在播放开始时调用，提前解析媒体信息，避免在获取轨道列表时阻塞主线程。
     *
     * @param context  上下文
     * @param uri      媒体 URI
     * @param listener 加载完成回调（在主线程执行），可为 null
     */
    public static void preloadMediaInfo(@NonNull Context context, @NonNull String uri,
                                        @Nullable OnMediaInfoLoadedListener listener) {
        // 如果已有缓存，直接回调
        MediaInfo cached = sCache.get(uri);
        if (cached != null) {
            if (listener != null) {
                sMainHandler.post(() -> listener.onMediaInfoLoaded(cached));
            }
            return;
        }

        // 如果正在加载，不再重复发起
        if (sLoading.containsKey(uri)) {
            return;
        }

        sLoading.put(uri, true);

        sExecutor.execute(() -> {
            MediaInfo mediaInfo = getMediaInfoSync(context, uri);
            sLoading.remove(uri);
            if (listener != null) {
                sMainHandler.post(() -> listener.onMediaInfoLoaded(mediaInfo));
            }
        });
    }

    /**
     * 同步获取媒体信息（带缓存）。
     * 注意：此方法可能耗时较长，建议在后台线程调用。
     *
     * @param context 上下文
     * @param uri     媒体 URI
     * @return MediaInfo 对象，解析失败返回 null
     */
    @Nullable
    public static MediaInfo getMediaInfo(@NonNull Context context, @NonNull String uri) {
        MediaInfo cached = sCache.get(uri);
        if (cached != null) {
            return cached;
        }
        return getMediaInfoSync(context, uri);
    }

    /**
     * 内部同步获取方法
     */
    @Nullable
    private static MediaInfo getMediaInfoSync(@NonNull Context context, @NonNull String uri) {
        try {
            MediaInfoBuilder builder = new MediaInfoBuilder();
            builder.from(context, Uri.parse(uri));
            MediaInfo mediaInfo = builder.build();
            if (mediaInfo != null) {
                Log.d(TAG, "getMediaInfo success: "
                        + " audioStreams=" + mediaInfo.getAudioStreams().size()
                        + " subtitleStreams=" + mediaInfo.getSubtitleStreams().size());
                sCache.put(uri, mediaInfo);
            } else {
                Log.w(TAG, "getMediaInfo: builder.build() returned null for " + uri);
            }
            return mediaInfo;
        } catch (Exception e) {
            Log.e(TAG, "getMediaInfo failed for " + uri, e);
            return null;
        }
    }

    /**
     * 获取指定索引的音频流信息
     */
    @Nullable
    public static AudioStream getAudioStream(@NonNull MediaInfo mediaInfo, int index) {
        List<AudioStream> streams = mediaInfo.getAudioStreams();
        if (index >= 0 && index < streams.size()) {
            return streams.get(index);
        }
        return null;
    }

    /**
     * 获取指定索引的字幕流信息
     */
    @Nullable
    public static SubtitleStream getSubtitleStream(@NonNull MediaInfo mediaInfo, int index) {
        List<SubtitleStream> streams = mediaInfo.getSubtitleStreams();
        if (index >= 0 && index < streams.size()) {
            return streams.get(index);
        }
        return null;
    }

    /**
     * 获取音频流的详细描述名称
     * 格式：语言名称 [标题] 或 语言名称 [编码 声道 采样率 比特率]
     */
    @Nullable
    public static String getAudioTrackName(@NonNull MediaInfo mediaInfo, int index) {
        AudioStream stream = getAudioStream(mediaInfo, index);
        if (stream == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        // 优先使用 title（如 "Director's Commentary"、"Stereo Mix" 等）
        String title = stream.getTitle();
        if (title != null && !title.isEmpty()) {
            sb.append(title);
        } else {
            // 没有 title 时，使用编码信息拼接
            String codec = stream.getCodecName();
            if (!codec.isEmpty()) {
                sb.append(codec);
            }
            // 声道布局（比简单的声道数更详细，如 "5.1"、"7.1"）
            String channelLayout = stream.getChannelLayout();
            if (channelLayout != null && !channelLayout.isEmpty()) {
                sb.append(" ").append(channelLayout);
            } else {
                // 没有 channelLayout 时使用声道数
                int channels = stream.getChannels();
                if (channels > 0) {
                    sb.append(" ").append(channels).append("ch");
                }
            }
            // 采样率
            int sampleRate = stream.getSampleRate();
            if (sampleRate > 0) {
                sb.append(" ").append(sampleRate / 1000).append("kHz");
            }
            // 比特率
            long bitRate = stream.getBitRate();
            if (bitRate > 0) {
                sb.append(" ").append(bitRate / 1000).append("kbps");
            }
        }
        // 语言名称（将 ISO 639-2/3 代码转换为可读名称）
        String language = stream.getLanguage();
        if (language != null && !language.isEmpty()) {
            String languageName = getLanguageName(language);
            String titleName = sb.toString();
            if (TextUtils.isEmpty(titleName)) {
                sb.append(languageName);
            } else {
                sb.append(" - ").append("[").append(languageName).append("]");
            }
        }
        return sb.toString();
    }

    /**
     * 获取字幕流的详细描述名称
     */
    @Nullable
    public static String getSubtitleTrackName(@NonNull MediaInfo mediaInfo, int index) {
        SubtitleStream stream = getSubtitleStream(mediaInfo, index);
        if (stream == null) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        // 标题
        String title = stream.getTitle();
        if (title != null && !title.isEmpty()) {
            sb.append(title);
        }
        // 语言名称（将 ISO 639-2/3 代码转换为可读名称）
        String language = stream.getLanguage();
        if (language != null && !language.isEmpty()) {
            String languageName = getLanguageName(language);
            String titleName = sb.toString();
            if (TextUtils.isEmpty(titleName)) {
                sb.append(languageName);
            } else {
                sb.append(" - ").append("[").append(languageName).append("]");
            }
        }
        return sb.toString();
    }

    /**
     * 清除缓存
     */
    public static void clearCache() {
        sCache.clear();
    }

    /**
     * 移除指定 URI 的缓存
     */
    public static void removeCache(@NonNull String uri) {
        sCache.remove(uri);
    }

    /**
     * 释放资源，在应用退出时调用
     */
    public static void release() {
        sExecutor.shutdownNow();
        sCache.clear();
        sLoading.clear();
    }
}
