package top.jessi.videoplayer.ijk;

import android.content.Context;

import top.jessi.videoplayer.player.PlayerFactory;

public class IjkPlayerFactory extends PlayerFactory<IjkPlayer> {

    public static IjkPlayerFactory create() {
        return new IjkPlayerFactory();
    }

    @Override
    public IjkPlayer createPlayer(Context context) {
        return new IjkPlayer(context);
    }
}
