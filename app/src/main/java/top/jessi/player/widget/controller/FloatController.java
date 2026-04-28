package top.jessi.player.widget.controller;

import android.content.Context;
import android.util.AttributeSet;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import top.jessi.player.widget.component.PipControlView;
import top.jessi.videocontroller.component.CompleteView;
import top.jessi.videocontroller.component.ErrorView;
import top.jessi.videoplayer.controller.GestureVideoController;

/**
 * 悬浮播放控制器
 * Created by Doikki on 2017/6/1.
 */
public class FloatController extends GestureVideoController {

    public FloatController(@NonNull Context context) {
        super(context);
    }

    public FloatController(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected int getLayoutId() {
        return 0;
    }

    @Override
    protected void initView() {
        super.initView();
        addControlComponent(new CompleteView(getContext()));
        addControlComponent(new ErrorView(getContext()));
        addControlComponent(new PipControlView(getContext()));
    }
}
