# YouTube / YT Music 取流：需要登录 cookies

YouTube 会对机房 IP 返回：

```
Sign in to confirm you're not a bot. Use --cookies-from-browser or --cookies
```

2026-11-18 在生产容器内实测：`android`、`web_safari,android`、`tv` 三种
player client 全部被拒。这不是队列或解析超时问题，匿名取流在该 IP 上不可行。
元数据与搜索可以成功，但 `--format bestaudio` 取流会被拒。

## 启用方式

两种方式任选其一，效果相同。

### 管理终端（推荐，无需重启）

管理后台 →「YouTube 取流」：粘贴导出的 cookies.txt 内容，保存后立即生效；
「验证取流」会用固定测试视频跑一次 `--simulate`，直接告诉你当前是否还会被校验拦截。
读取接口只返回是否已配置与文件大小，永远不会返回内容。

### 放到数据目录

1. 在已登录 YouTube 的浏览器里导出 Netscape 格式 cookies（例如
   "Get cookies.txt LOCALLY" 扩展）。
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