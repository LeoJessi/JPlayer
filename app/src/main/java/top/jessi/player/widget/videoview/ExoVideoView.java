package top.jessi.player.widget.videoview;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.exoplayer2.source.MediaSource;

import java.util.Map;

import top.jessi.player.widget.player.CustomExoMediaPlayer;
import top.jessi.videoplayer.exo.ExoMediaSourceHelper;
import top.jessi.videoplayer.player.BaseVideoView;
import top.jessi.videoplayer.player.PlayerFactory;

public class ExoVideoView extends BaseVideoView<CustomExoMediaPlayer> {

    private MediaSource mMediaSource;

    private boolean mIsCacheEnabled;

    private final ExoMediaSourceHelper mHelper;

    public ExoVideoView(Context context) {
        super(context);
    }

    public ExoVideoView(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public ExoVideoView(@NonNull Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    {
        //由于传递了泛型，必须将CustomExoMediaPlayer设置进来，否者报错
        setPlayerFactory(new PlayerFactory<CustomExoMediaPlayer>() {
            @Override
            public CustomExoMediaPlayer createPlayer(Context context) {
                return new CustomExoMediaPlayer(context);
            }
        });
        mHelper = ExoMediaSourceHelper.getInstance(getContext());
    }

    @Override
    protected void setInitOptions() {
        super.setInitOptions();
    }

    @Override
    protected boolean prepareDataSource() {
        if (mMediaSource != null) {
            mMediaPlayer.setDataSource(mMediaSource);
            return true;
        }
        return false;
    }

    /**
     * 设置ExoPlayer的MediaSource
     */
    public void setMediaSource(MediaSource mediaSource) {
        mMediaSource = mediaSource;
    }

    @Override
    public void setUrl(String url, Map<String, String> headers) {
        mMediaSource = mHelper.getMediaSource(url, headers, mIsCacheEnabled);
    }

    /**
     * 是否打开缓存
     */
    public void setCacheEnabled(boolean isEnabled) {
        mIsCacheEnabled = isEnabled;
    }
}
