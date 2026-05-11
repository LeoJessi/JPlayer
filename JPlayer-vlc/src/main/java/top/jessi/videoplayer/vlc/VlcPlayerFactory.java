package top.jessi.videoplayer.vlc;

import android.content.Context;

import top.jessi.videoplayer.player.PlayerFactory;

public class VlcPlayerFactory extends PlayerFactory<VlcPlayer> {

    private VlcPlayer.HWAccel mHWAccel;

    public static VlcPlayerFactory create() {
        return new VlcPlayerFactory();
    }

    /**
     * 设置硬件加速模式
     * <p>
     * 传 null 则使用 VlcPlayer 的全局默认值（{@link VlcPlayer.HWAccel#AUTOMATIC}）
     *
     * @param accel 硬件加速模式
     */
    public VlcPlayerFactory setHWAccel(VlcPlayer.HWAccel accel) {
        mHWAccel = accel;
        return this;
    }

    @Override
    public VlcPlayer createPlayer(Context context) {
        VlcPlayer player = new VlcPlayer(context);
        if (mHWAccel != null) {
            player.setHWAccel(mHWAccel);
        }
        return player;
    }
}
