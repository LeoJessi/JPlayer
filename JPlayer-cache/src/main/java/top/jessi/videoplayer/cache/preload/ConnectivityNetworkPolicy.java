package top.jessi.videoplayer.cache.preload;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.os.Build;

import top.jessi.videoplayer.cache.internal.Logger;

/**
 * 基于 {@link ConnectivityManager} 的网络策略。
 * <p>
 * 默认<b>任何网络下都允许预加载</b>（包含移动网络，默认不省流量），与
 * {@link NetworkPolicy#DEFAULT} 的语义一致；此外在无可用网络时不会发起预加载，
 * 避免做无用请求。
 * <p>
 * 如需节省流量，调用 {@link #setCanPreLoadOnMobile(boolean) setCanPreLoadOnMobile(false)}
 * 即可切换为"仅 WiFi / 有线网络预加载"。
 * <p>
 * 注意：需要 {@code android.permission.ACCESS_NETWORK_STATE} 权限，缺失时
 * {@link #canPreload()} 会退化为返回 {@code true}（保持默认行为）。
 */
public class ConnectivityNetworkPolicy implements NetworkPolicy {

    /**
     * 网络状态变化回调，用于触发预加载窗口重算
     */
    public interface OnNetworkChangedListener {
        void onNetworkChanged();
    }

    private final ConnectivityManager mConnectivityManager;
    private final boolean mCanPreLoadOnWifi;

    /**
     * 是否允许在移动网络下预加载，默认 {@code true}（不省流量）
     */
    private volatile boolean mCanPreLoadOnMobile;

    private volatile OnNetworkChangedListener mListener;

    private final ConnectivityManager.NetworkCallback mNetworkCallback =
            new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    notifyListener();
                }

                @Override
                public void onLost(Network network) {
                    notifyListener();
                }
            };

    public ConnectivityNetworkPolicy(Context context) {
        this(context, true, true);
    }

    public ConnectivityNetworkPolicy(Context context, boolean canPreLoadOnWifi, boolean canPreLoadOnMobile) {
        mConnectivityManager = context == null
                ? null
                : (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        mCanPreLoadOnWifi = canPreLoadOnWifi;
        mCanPreLoadOnMobile = canPreLoadOnMobile;
        register();
    }

    private void register() {
        if (mConnectivityManager == null) return;
        try {
            NetworkRequest request = new NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build();
            mConnectivityManager.registerNetworkCallback(request, mNetworkCallback);
        } catch (Exception e) {
            // 缺少权限或系统限制：退化为始终允许
            Logger.info("网络监听注册失败：" + e.getMessage());
        }
    }

    /**
     * 注销网络监听，释放资源
     */
    public void release() {
        if (mConnectivityManager == null) return;
        try {
            mConnectivityManager.unregisterNetworkCallback(mNetworkCallback);
        } catch (Exception e) {
            // 忽略：未注册或已注销
        }
    }

    /**
     * 设置是否允许在移动网络下预加载，变更后触发窗口重算
     */
    public void setCanPreLoadOnMobile(boolean canPreLoadOnMobile) {
        if (mCanPreLoadOnMobile == canPreLoadOnMobile) return;
        mCanPreLoadOnMobile = canPreLoadOnMobile;
        notifyListener();
    }

    /**
     * 是否允许在移动网络下预加载
     */
    public boolean canPreLoadOnMobile() {
        return mCanPreLoadOnMobile;
    }

    /**
     * 设置网络状态变化回调
     */
    public void setOnNetworkChangedListener(OnNetworkChangedListener listener) {
        mListener = listener;
    }

    private void notifyListener() {
        OnNetworkChangedListener listener = mListener;
        if (listener != null) {
            listener.onNetworkChanged();
        }
    }

    @Override
    public boolean canPreload() {
        if (mConnectivityManager == null) return true;
        try {
            // 无可用网络时不发起预加载，避免做无用请求
            if (!isNetworkConnected()) return false;
            // 非蜂窝网络（WiFi / 有线）与移动网络分别受对应开关控制
            return isNonCellularConnected() ? mCanPreLoadOnWifi : mCanPreLoadOnMobile;
        } catch (Exception e) {
            return true;
        }
    }

    /**
     * 是否存在可用网络
     */
    private boolean isNetworkConnected() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network network = mConnectivityManager.getActiveNetwork();
            if (network == null) return false;
            NetworkCapabilities caps = mConnectivityManager.getNetworkCapabilities(network);
            return caps != null
                    && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        }
        NetworkInfo info = mConnectivityManager.getActiveNetworkInfo();
        return info != null && info.isConnected();
    }

    /**
     * 当前是否通过<b>非蜂窝</b>网络联网（WiFi 或有线以太网）。
     * <p>不能只判断 WiFi：电视盒子 / 智能电视大多是有线网络
     * （{@link NetworkCapabilities#TRANSPORT_ETHERNET}），只判 WiFi 会把它误判成
     * "移动网络"，于是调用 {@link #setCanPreLoadOnMobile(boolean)
     * setCanPreLoadOnMobile(false)} 省流量时会把电视的预加载也一并关掉。
     */
    private boolean isNonCellularConnected() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Network network = mConnectivityManager.getActiveNetwork();
            if (network == null) return false;
            NetworkCapabilities caps = mConnectivityManager.getNetworkCapabilities(network);
            if (caps == null) return false;
            return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET);
        }
        NetworkInfo info = mConnectivityManager.getActiveNetworkInfo();
        if (info == null || !info.isConnected()) return false;
        int type = info.getType();
        return type == ConnectivityManager.TYPE_WIFI
                || type == ConnectivityManager.TYPE_ETHERNET;
    }
}
