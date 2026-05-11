package top.jessi.videoplayer.ijk;

import android.content.Context;

import top.jessi.videoplayer.player.PlayerFactory;

public class IjkPlayerFactory extends PlayerFactory<IjkPlayer> {

    private IjkPlayer.DecodeMode mDecodeMode;

    public static IjkPlayerFactory create() {
        return new IjkPlayerFactory();
    }

    /**
     * 设置解码模式
     *
     * @param decodeMode 解码模式，传 null 则使用 IjkPlayer 的全局默认值
     */
    public IjkPlayerFactory setDecodeMode(IjkPlayer.DecodeMode decodeMode) {
        mDecodeMode = decodeMode;
        return this;
    }

    @Override
    public IjkPlayer createPlayer(Context context) {
        IjkPlayer player = new IjkPlayer(context);
        if (mDecodeMode != null) {
            player.setDecodeMode(mDecodeMode);
        }
        return player;
    }
}
