package top.jessi.videoplayer.sys;

import android.content.Context;

import top.jessi.videoplayer.player.PlayerFactory;

/**
 * 创建{@link SystemPlayer}的工厂类，不推荐，系统的MediaPlayer兼容性较差，建议使用IjkPlayer或者ExoPlayer
 */
public class SystemPlayerFactory extends PlayerFactory<SystemPlayer> {

    public static SystemPlayerFactory create() {
        return new SystemPlayerFactory();
    }

    @Override
    public SystemPlayer createPlayer(Context context) {
        return new SystemPlayer(context);
    }
}
