# 多房间架构

## 概念

| 概念 | 说明 |
|---|---|
| 设备凭证 | 浏览器 localStorage 里的 `mp_user_token`（UUID）。**不是硬件指纹**：清空存储 = 换设备，房主身份随之丢失。 |
| 房间码 | 8 位小写字母数字（去掉易混淆字符），`?room=xxxxxxxx`，房间码即邀请凭证。 |
| 名字 | `mp_username`。**创建房间必须已有名字**；只加入房间可以先当游客（首次点歌时再起名）。 |
| 可选密码 | 每个房间独立，PBKDF2-HMAC-SHA256（120k 次）加盐哈希，落盘不含明文。 |

## 运行时结构

```
RoomRegistry（单例）
 ├── rooms: Map<roomId, Room>            元数据，持久化
 └── runtimes: Map<roomId, RoomRuntime>  懒加载，空闲 30 分钟卸载
      └── RoomRuntime
           ├── MusicPlayerService   每房一份（状态机、投票、锁）
           ├── MusicQueueManager    每房一份（队列/历史/轮播）
           ├── UserService          每房一份（在线列表、断线宽限）
           └── ChatService          每房一份（历史、限流、命令）
```

共享单例：音乐源 API、下载缓存（`LocalCacheService`）、运行时配置、`SimpMessagingTemplate`、直播服务（仅默认房）。
**刻意不使用 Spring prototype/custom scope**：容器不管理 prototype 的销毁，`@Scheduled`/`@EventListener` 语义不可靠；这里由 `RoomFactory` 显式创建、`RoomRuntime.shutdown()` 显式关闭。

## 调度与事件

- `RoomTicker`：**一个** 1 秒循环遍历所有房间调用 `player.tick()`；绝不为每房开线程。
- `RoomTicker` 另外负责 60 秒落盘、10 分钟清理（空闲播放器/过期用户/卸载空房）。
- `RoomScopedPublisher`：房间服务发布事件时自动包一层 `RoomEvent(roomId, payload)`；全局事件（下载状态、直播状态、运行时配置）保持原样。
- `RoomEventBridge`：按 roomId 发到 `/topic/r/{roomId}/...`；全局下载事件分发给所有房间（各房自己判断是否相关）；直播状态只给默认房。

## WebSocket 契约

CONNECT headers：

```
room-id: <房间码>            # 缺省 main（兼容旧客户端）
device-id: <UUID>            # 缺省回退到 user-token
user-name: <名字>
room-password: <密码或空>
```

失败时 ERROR 帧 `message:INVALID_ROOM_PASSWORD` 或 `ROOM_NOT_FOUND`，前端回到落地页并提示，不会无限重连。

订阅频道：

```
/topic/r/{roomId}/player/state
/topic/r/{roomId}/player/queue
/topic/r/{roomId}/player/seek
/topic/r/{roomId}/player/events
/topic/r/{roomId}/users/online
/topic/r/{roomId}/chat
```

`/user/...` 个人频道不变（它们本来就按 session 隔离）。

## REST

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/rooms/mine` | 当前设备拥有的房间 + 默认房。需 `X-Device-Id`。 |
| GET | `/api/rooms/{id}` | 公开摘要：名称、是否有密码、在线数、是否房主。 |
| POST | `/api/rooms` | 创建。需 `X-Device-Id` + `X-User-Name`（游客名会被拒绝）。body `{name?, password?}`。 |
| PATCH | `/api/rooms/{id}` | 房主改名 / 改密码 / 取消密码。 |
| DELETE | `/api/rooms/{id}` | 房主关闭房间（默认房不可删）。 |
| POST | `/api/rooms/{id}/verify` | 加入前密码预校验。 |

## 落盘

```
data/rooms/index.json        # 房间元数据（含密码哈希）
data/rooms/{roomId}.json     # 队列、历史、聊天快照（tmp + rename 原子写，0600）
```

默认房 `main` 首次启动时会回退读取旧版 `data/queue-data.json`，升级不丢队列；之后写入 `data/rooms/main.json`。

## 已知边界

- 房间码是唯一凭证，未做请求限流/枚举保护（单机派对场景可接受；公网大规模使用前需要补）。
- 只有一个默认 `main` 房能使用 `/radio/stream`（直播功能已搁置）。
- 没有分房管理端：管理员面板仍作用于默认房。
- 房主设置页（改名/改密/关闭）只有 API，尚未做 UI。
- 旧标签页仍订阅旧的全局 topic，升级后需要刷新一次。
