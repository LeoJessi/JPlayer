package top.jessi.player.widget.player;


import android.content.Context;

import top.jessi.videoplayer.exo.ExoMediaPlayer;
import androidx.media3.exoplayer.source.MediaSource;

/**
 * 自定义ExoMediaPlayer，目前扩展了诸如边播边存，以及可以直接设置Exo自己的MediaSource。
 */
public class CustomExoMediaPlayer extends ExoMediaPlayer {

    public CustomExoMediaPlayer(Context context) {
        super(context);
    }

    public void setDataSource(MediaSource dataSource) {
        mMediaSource = dataSource;
    }
}
