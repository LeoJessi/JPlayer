package top.jessi.videoplayer.vlc;

import android.content.Context;

import top.jessi.videoplayer.player.PlayerFactory;

public class VlcPlayerFactory extends PlayerFactory<VlcPlayer> {

    private VlcPlayer.DecodeMode mDecodeMode;

    public static VlcPlayerFactory create() {
        return new VlcPlayerFactory();
    }

    /**
     * 设置解码模式
     *
     * @param decodeMode 解码模式，传 null 则使用 VlcPlayer 的全局默认值
     */
    public VlcPlayerFactory setDecodeMode(VlcPlayer.DecodeMode decodeMode) {
        mDecodeMode = decodeMode;
        return this;
    }

    @Override
    public VlcPlayer createPlayer(Context context) {
        VlcPlayer player = new VlcPlayer(context);
        if (mDecodeMode != null) {
            player.setDecodeMode(mDecodeMode);
        }
        return player;
    }
}
