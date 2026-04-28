package top.jessi.videoplayer.player;

import android.content.Context;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;

import java.lang.ref.WeakReference;

/**
 * 音频焦点改变监听
 */
final class AudioFocusHelper implements AudioManager.OnAudioFocusChangeListener {

    private Handler mHandler = new Handler(Looper.getMainLooper());

    private WeakReference<BaseVideoView> mWeakVideoView;

    private AudioManager mAudioManager;

    private boolean mStartRequested = false;
    private boolean mPausedForLoss = false;
    private int mCurrentFocus = 0;

    AudioFocusHelper(@NonNull BaseVideoView baseVideoView) {
        mWeakVideoView = new WeakReference<>(baseVideoView);
        mAudioManager = (AudioManager) baseVideoView.getContext().getApplicationContext().getSystemService(Context.AUDIO_SERVICE);
    }

    @Override
    public void onAudioFocusChange(final int focusChange) {
        if (mCurrentFocus == focusChange) {
            return;
        }

        //由于onAudioFocusChange有可能在子线程调用，
        //故通过此方式切换到主线程去执行
        mHandler.post(new Runnable() {
            @Override
            public void run() {
                handleAudioFocusChange(focusChange);
            }
        });

        mCurrentFocus = focusChange;
    }

    private void handleAudioFocusChange(int focusChange) {
        final BaseVideoView baseVideoView = mWeakVideoView.get();
        if (baseVideoView == null) {
            return;
        }
        switch (focusChange) {
            case AudioManager.AUDIOFOCUS_GAIN://获得焦点
            case AudioManager.AUDIOFOCUS_GAIN_TRANSIENT://暂时获得焦点
                if (mStartRequested || mPausedForLoss) {
                    baseVideoView.start();
                    mStartRequested = false;
                    mPausedForLoss = false;
                }
                if (!baseVideoView.isMute())//恢复音量
                    baseVideoView.setVolume(1.0f, 1.0f);
                break;
            case AudioManager.AUDIOFOCUS_LOSS://焦点丢失
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT://焦点暂时丢失
                if (baseVideoView.isPlaying()) {
                    mPausedForLoss = true;
                    baseVideoView.pause();
                }
                break;
            case AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK://此时需降低音量
                if (baseVideoView.isPlaying() && !baseVideoView.isMute()) {
                    baseVideoView.setVolume(0.1f, 0.1f);
                }
                break;
        }
    }

    /**
     * Requests to obtain the audio focus
     */
    void requestFocus() {
        if (mCurrentFocus == AudioManager.AUDIOFOCUS_GAIN) {
            return;
        }

        if (mAudioManager == null) {
            return;
        }

        int status = mAudioManager.requestAudioFocus(this, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN);
        if (AudioManager.AUDIOFOCUS_REQUEST_GRANTED == status) {
            mCurrentFocus = AudioManager.AUDIOFOCUS_GAIN;
            return;
        }

        mStartRequested = true;
    }

    /**
     * Requests the system to drop the audio focus
     */
    void abandonFocus() {

        if (mAudioManager == null) {
            return;
        }

        mStartRequested = false;
        mAudioManager.abandonAudioFocus(this);
    }
}