package top.jessi.videoplayer.player;

import android.content.Context;

import top.jessi.videoplayer.media.SystemPlayer;
import top.jessi.videoplayer.media.SystemPlayerFactory;

/**
 * 此接口使用方法：
 * 1.继承{@link AbstractPlayer}扩展自己的播放器。
 * 2.继承此接口并实现{@link #createPlayer(Context)}，返回步骤1中的播放器。
 * 可参照{@link SystemPlayer}和{@link SystemPlayerFactory}的实现。
 */
public abstract class PlayerFactory<P extends AbstractPlayer> {

    public abstract P createPlayer(Context context);
}
