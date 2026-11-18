# YouTube / YT Music 取流：需要登录 cookies

YouTube 会对机房 IP 返回：

```
Sign in to confirm you're not a bot. Use --cookies-from-browser or --cookies
```

2026-11-18 在生产容器内实测：`android`、`web_safari,android`、`tv` 三种
player client 全部被拒。这不是队列或解析超时问题，匿名取流在该 IP 上不可行。
元数据与搜索可以成功，但 `--format bestaudio` 取流会被拒。

## 为什么 cookies 会「过一阵就失效」（2026-11 社群共识）

yt-dlp 官方 wiki 与 issue #8227 都指向同一件事：**YouTube 会持续轮换登录会话的
cookies**（浏览器会调用 `accounts.youtube.com/RotateCookiesPage` 刷新它们）。

- 只要**同一个账号还在浏览器里登录着**，浏览器就会轮换这组 token，
  服务器手中的静态导出随即过期。issue #8227 的原话是：使用者一直在浏览器里
  用同一个账号，导致 cookies 很快失效；清除该浏览器的 YouTube 缓存后问题消失。
- 机房 IP 会被 YouTube 判定为不可信，**加速**这种失效（维护者原话：
  "rapidly invalidating cookies for IP addresses that have been flagged
  (e.g. data center IPs)"）。
- 并发的多个 yt-dlp 进程各拿到不同的轮换值，token 链无法对齐，也会被判为盗用。
  （本项目已改为 YouTube 会话串行执行：`YT_DLP_YOUTUBE_CONCURRENCY=1`。）

因此正确的导出方式不是「随便登录一个窗口导出」，而是：

1. 打开一个**全新隐私窗口/无痕窗口**，在其中登录 YouTube。
2. 另开一个标签页，**关掉 YouTube 标签页**。
3. 从该隐私窗口导出 youtube.com 的 cookies（Netscape 格式）。
4. **立刻关闭整个隐私窗口**，此后永不在此窗口访问 YouTube。

这样这组 cookies 只会被服务器轮换，不会被浏览器抢先改掉，寿命可从数分钟延长到数周。

其它已验证的边界：

- **PO Token（bgutil 等）不能解决 IP 级机器人校验**，官方文档明说
  "a PO token ... won't get past IP-based restrictions"。本项目当前用 m4a/HLS，
  尚未触发需要 PO Token 的客户端。
- 换用**住宅 IP / 家宽出口**是从根上消除该错误的方式；机房 IP 只能靠 cookies 续命。
- 同一个账号被用于服务器取流有封号风险，建议专备一个小号，不要在其它地方使用。

## 另一种失败：`HTTP Error 403: Forbidden`（与 bot check 不同）

若日志是 `unable to download video data: HTTP Error 403: Forbidden`，
那**不是**登录问题，cookies 可以是完全有效的。原因是 GVS（Google Video Server）
要求 PO Token：默认的 `web` / `mweb` / `web_embedded` 客户端从机房 IP 取媒体流会被拒。

本项目默认改用 `web_safari` 客户端（`YT_DLP_YOUTUBE_PLAYER_CLIENT`）：
它走 HLS（m3u8），目前不需要 GVS PO Token，实测可正常下载。

代价是 `web_safari` 只提供**带画面**的合并流，所以下载后会再抽成纯音轨：

```sh
--extract-audio --audio-format m4a    # 7MB 的 360p 流 → 约 1MB 的 m4a
```

若以后连 HLS 也开始强制 PO Token，需要额外部署 PO Token provider
（如 `brainicism/bgutil-ytdlp-pot-provider` 容器 + yt-dlp 插件），
届时才能回到 `web` 客户端的纯音频流。

**验证口径**：管理台的「验证取流」会真的下载 5 秒媒体并抽成 m4a 再删除，
不用 `--simulate`——后者只解析格式，在 403 的情况下也会报成功。

## 启用方式

两种方式任选其一，效果相同。

### 管理终端（推荐，无需重启）

管理后台 →「YouTube 取流」：粘贴导出的 cookies.txt 内容，保存后立即生效；
「验证取流」会用固定测试视频跑一次 `--simulate`，直接告诉你当前是否还会被校验拦截。
读取接口只返回是否已配置与文件大小，永远不会返回内容。

### 放到数据目录

1. 按上节「隐私窗口」流程导出 Netscape 格式 cookies（例如
   "Get cookies.txt LOCALLY" 扩展），确保文件中包含 `SID`、`SAPISID`、`LOGIN_INFO`
   这三条——它们由 Google 标记为 HttpOnly，用 `document.cookie` 一类方式导出会漏掉，
   存进去也永远取不到流（服务端保存时会直接拒绝这种文件）。
2. 放到宿主机的 `./music_party/data/yt-cookies.txt`（容器内为
   `/app/data/yt-cookies.txt`，权限建议 `chmod 600`）。
3. 重启应用容器使配置生效：

```sh
docker compose -f docker-compose.prod.yml up -d --no-cache-deps music-party
```

也可以用 `YT_DLP_COOKIES_FILE` 指定其他容器内路径。

## 验证

应用启动日志会打印是否找到 cookies：

```sh
docker logs music-party-app 2>&1 | grep 'youtube cookies'
```

为 `none (YouTube may reject anonymous streaming)` 表示未配置；为具体路径表示已加载。
随后在房间内点一首 YouTube 歌曲，`GET /api/admin/preparation` 的 `recentTasks`
应显示该任务从 `resolve-source` 走到 `ready`。

## 还需要 JavaScript 运行时

YouTube 会给媒体地址签名，并用人机挑战保护可用格式。缺少 JS 运行时时
yt-dlp 只能看到 storyboard 图片，表现为：

```
Requested format is not available
```

镜像内置 `bun`，并且只对 YouTube/YT Music 的调用追加
`--js-runtimes bun:/usr/local/bin/bun`（可用 `YT_DLP_JS_RUNTIME` 覆盖）。

音频优先选择 `bestaudio[ext=m4a]/bestaudio`：m4a 能被 Safari 直接解码，
Opus/WebM 在部分浏览器上无法播放。

## 行为说明

- cookies 只会附加到 YouTube / YT Music 的 yt-dlp 调用，SoundCloud 与 B 站路径不受影响。
- 机器人校验、私有视频、DRM 被视为**永久失败**：不再重试两次，也不进入重试退避，
  队列会在首次失败后直接标记 FAILED，并把原因写进系统消息与管理诊断。
- cookies 属于登录凭据：不要提交到仓库，也不要放进镜像。