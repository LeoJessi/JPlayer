# VLC 3.7.0 HLS 兼容性解决方案

## 问题背景

某些 HLS 流的服务器对 TS 片段使用 `.html` 后缀（而非标准的 `.ts`），并返回 `Content-Type: text/html` 响应头。VLC 3.7.0 的原生 adaptive 解复用器在处理此类流时会卡在缓冲状态，无法播放。

## 根因分析

VLC 3.7.0 的 `FakeESOut::applyTimestampContinuity()` 函数存在 bug：

```cpp
// modules/demux/adaptive/plumbing/FakeESOut.cpp
ts += continuityoffset;
assert(ts >= VLC_TICK_INVALID);  // VLC_TICK_INVALID = 0
```

当 TS 流中 PCR（Program Clock Reference）时间戳不连续时，`continuityoffset` 的值会非常大，导致 `ts += continuityoffset` 溢出为负值，触发 assertion 失败，播放卡住。

该 bug 在 VLC master/v4 中通过 commit `7a103f0` 修复，添加了 PCR 状态机来验证 PCR 有效性。但 VLC 3.7.0（Maven Central 上最新的 3.x 版本）没有此修复，且无法从 Java 层修补 native 代码。

## 解决方案

**核心思路：强制 VLC 使用 avformat（FFmpeg）解复用器代替原生的 adaptive 解复用器。**

avformat 使用 FFmpeg 的 HLS 实现，不经过 FakeESOut，从根本上避开了这个 bug。同时配合本地 HTTP 代理来修正 Content-Type。

### 架构流程

```
VLC (avformat 解复用器)
  │
  │  ① 请求 m3u8（经过代理）
  ▼
HlsProxy（本地 HTTP 代理 127.0.0.1:PORT）
  │
  │  ② 从原始服务器获取 m3u8
  │  ③ 重写片段 URL → 代理 URL（保持 .html 后缀）
  │  ④ 返回改写后的 m3u8
  ▼
VLC 解析 m3u8，请求片段 URL（经过代理）
  │
  ▼
HlsProxy
  │
  │  ⑤ 从原始服务器获取 TS 数据
  │  ⑥ 修正 Content-Type: text/html → video/MP2T
  │  ⑦ 流式转发给 VLC
  ▼
VLC 正常解码播放
```

## 文件说明

### HlsProxy.java — 本地 HTTP 代理

| 方法 | 说明 |
|------|------|
| `start()` | 在 `127.0.0.1:0` 启动代理服务器，端口随机分配 |
| `stop()` | 关闭代理，释放资源 |
| `getProxyUrl()` | 将原始 URL 转换为代理 URL |
| `rewriteM3u8()` | 重写 m3u8，将片段 URL 替换为代理 URL |
| `proxyRequest()` | 核心代理逻辑，区分 m3u8/TS 片段/其他请求 |

**关键设计决策：**
- 使用 `127.0.0.1` 本地地址，不经过外部网络
- 端口设为 `0`，由系统自动分配可用端口
- TS 片段流式转发（8KB 缓冲），不缓存整个文件
- 客户端断开连接时（如退出播放），`SocketException` 静默处理，不打印堆栈
- HTTPS 信任所有证书（某些服务器使用自签名证书）

### VlcPlayer.java — VLC 播放器

**`buildDefaultOptions()` — VLC 启动参数：**

| 参数 | 说明 |
|------|------|
| `--avcodec-skiploopfilter` | 根据 CPU 自动选择环路滤波跳过级别（0-4） |
| `--avcodec-skip-frame` | 跳帧策略，低端设备跳过非关键帧 |
| `--avcodec-skip-idct` | 跳过 IDCT，降低 CPU 开销 |
| `--aout` | 蓝牙场景自动切换 audiotrack |
| `--audio-time-stretch` | 倍速播放时保持音调不变 |
| `--audio-resampler` | 多核用 soxr（高质量），少核用 ugly（低开销） |
| `--network-caching=3000` | 网络缓存 3 秒 |
| `--rtsp-tcp` | RTSP 强制使用 TCP |
| `--http-reconnect` | HTTP 自动重连 |
| `--clock-jitter=50` | 时钟抖动容限 50ms |
| `--clock-synchro=0` | 禁用时钟同步直通 |
| `--ts-seek-percent` | TS 流按百分比 seek |
| `--stats` | 启用统计信息 |

**`setDataSource()` — 数据源设置流程：**

1. 调用 `applyHlsProxyIfNeeded()`，对 `.m3u8` 地址启动代理
2. 创建 Media 对象
3. 设置硬件解码模式
4. 增大网络缓存（`:network-caching=5000`，`:file-caching=3000`）
5. 若代理激活，添加 `:demux=avformat` 强制使用 FFmpeg 解复用器
6. 设置用户自定义 headers 和循环播放选项

**`applyHlsProxyIfNeeded()`：**

- 仅对 `.m3u8` / `.m3u` 地址启用代理
- 非 HLS 流直接返回原始 URL
- 代理启动失败时降级为原始 URL

**生命周期管理：**

- `reset()` — 停止代理，下次 `setDataSource()` 重新创建
- `release()` — 停止代理并释放所有资源

## 使用方式

### 基本使用

```java
VlcPlayer player = new VlcPlayer(context);
player.initPlayer();
player.setDataSource("https://example.com/stream/index.m3u8", null);
player.prepareAsync();
player.start();
```

HLS 代理默认自动启用，无需额外配置。

### 手动控制

```java
// 禁用 HLS 代理（全局设置）
VlcPlayer.setHlsProxyEnabled(false);

// 设置硬件加速模式
player.setHLCPlayer.setHWAccel(VlcPlayer.HWAccel.DISABLED);
```

## 已知限制

1. **仅针对 HLS 流**：代理只对 `.m3u8` / `.m3u` 地址生效，普通流不受影响
2. **本地代理开销**：每个 HLS 会话会有一个本地 HTTP 代理，占用一个端口和若干线程
3. **HTTPS 信任所有证书**：为兼容自签名证书的服务器，HTTPS 连接不验证证书链
