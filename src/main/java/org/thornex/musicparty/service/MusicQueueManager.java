package org.thornex.musicparty.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.thornex.musicparty.config.AppProperties;
import org.thornex.musicparty.dto.Music;
import org.thornex.musicparty.dto.MusicQueueItem;
import org.thornex.musicparty.dto.UserSummary;
import org.thornex.musicparty.enums.QueueItemStatus;
import org.thornex.musicparty.enums.TopResult;
import org.thornex.musicparty.enums.Priority;

import org.thornex.musicparty.enums.PlayMode;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class MusicQueueManager {

    private final AppProperties appProperties;

    // 使用并发安全的双端队列
    private final Deque<MusicQueueItem> queue = new ConcurrentLinkedDeque<>();
    private final List<Music> playHistory = Collections.synchronizedList(new LinkedList<>());
    private final List<Music> loopList = Collections.synchronizedList(new ArrayList<>());

    // 用于实现“公平随机播放”：记录上一个播放的用户
    private final AtomicReference<String> lastPlayedUserToken = new AtomicReference<>("");

    // --- Public API for Queue Manipulation ---

    /** 去重键：平台 + 曲目 ID。Netease 与 SoundCloud 都是纯数字 ID，只比 id 会误判。 */
    public static String musicKey(Music music) {
        if (music == null) return "";
        return (music.platform() == null ? "" : music.platform()) + ":" + music.id();
    }

    public enum AddFailure { QUEUE_FULL, DUPLICATE }

    public record AddResult(MusicQueueItem item, AddFailure failure) {
        public boolean ok() { return item != null; }
    }

    /**
     * 向队列末尾添加一首歌曲。
     * 返回失败原因（队列满 / 重复）而不是静默 null —— 调用方必须据此给出反馈。
     */
    public synchronized AddResult add(Music music, UserSummary enqueuedBy, QueueItemStatus initialStatus) {
        if (queue.size() >= appProperties.getQueue().getMaxSize()) {
            return new AddResult(null, AddFailure.QUEUE_FULL);
        }
        if (isMusicInQueue(music)) {
            return new AddResult(null, AddFailure.DUPLICATE);
        }

        MusicQueueItem newItem = new MusicQueueItem(
                UUID.randomUUID().toString(),
                music,
                enqueuedBy,
                initialStatus // 存储枚举的名称
        );
        queue.addLast(newItem);
        reorderRoundRobin();
        return new AddResult(newItem, null);
    }

    /**
     * 严格轮转排序（round-robin）：GLOBAL_TOP 保持最前且相对顺序不变，其余按用户分组后交替排列。
     *
     * 研判（2026-11）：此前队列只有「每人最多 N 首」这一道闸，一个人连点 5 首就会形成 5 连排，
     * 派对里其他人只能等 20 分钟——这正是共享点歌的经典「hogging the jukebox」问题
     * （US11503373B2 开篇即以此立论，解法是按用户子队列轮转交织）。
     * 用户之间的先后由「在该队列中首次出现」决定，故先点的人先轮，仍然尊重先来后到。
     * USER_TOP 仍在其所属用户的子队列内优先，但不越过别人的轮次。
     */
    private void reorderRoundRobin() {
        if (queue.size() < 2) return;
        List<MusicQueueItem> items = new ArrayList<>(queue);

        List<MusicQueueItem> globals = items.stream()
                .filter(i -> i.priority() == Priority.GLOBAL_TOP)
                .collect(Collectors.toList());
        List<MusicQueueItem> rest = items.stream()
                .filter(i -> i.priority() != Priority.GLOBAL_TOP)
                .collect(Collectors.toList());

        Map<String, Deque<MusicQueueItem>> perUser = new LinkedHashMap<>();
        for (MusicQueueItem item : rest) {
            perUser.computeIfAbsent(item.enqueuedBy().token(), k -> new ArrayDeque<>()).addLast(item);
        }
        // 组内：个人置顶先于普通（稳定排序，不改变同组内的先后）
        perUser.replaceAll((token, deque) -> {
            List<MusicQueueItem> list = new ArrayList<>(deque);
            list.sort(Comparator.comparingInt(i -> i.priority() == Priority.USER_TOP ? 0 : 1));
            ArrayDeque<MusicQueueItem> sorted = new ArrayDeque<>(list);
            return sorted;
        });

        List<MusicQueueItem> ordered = new ArrayList<>(globals);
        boolean progressed = true;
        while (progressed) {
            progressed = false;
            for (Deque<MusicQueueItem> deque : perUser.values()) {
                MusicQueueItem next = deque.pollFirst();
                if (next != null) {
                    ordered.add(next);
                    progressed = true;
                }
            }
        }

        queue.clear();
        queue.addAll(ordered);
    }

    /**
     * 将指定歌曲置顶
     */
    public synchronized TopResult top(String queueId, PlayMode playMode) {
        Optional<MusicQueueItem> itemOpt = findByQueueId(queueId);
        if (itemOpt.isEmpty()) {
            return TopResult.NONE;
        }

        MusicQueueItem item = itemOpt.get();

        // 1. 如果已经是全局置顶 (GLOBAL_TOP)，不做操作
        if (item.priority() == Priority.GLOBAL_TOP) {
            return TopResult.NONE;
        }

        // 2. 如果是个人置顶 (USER_TOP) -> 升级为全局置顶 (GLOBAL_TOP)
        // 无论当前是否随机模式，二次置顶都视为升级
        if (item.priority() == Priority.USER_TOP) {
            if (queue.remove(item)) {
                queue.addFirst(item.withPriority(Priority.GLOBAL_TOP));
                return TopResult.GLOBAL;
            }
            return TopResult.NONE;
        }

        // 3. 如果是普通歌曲
        if (playMode == PlayMode.SHUFFLE) {
            // 随机模式下 -> 变为个人置顶 (USER_TOP)
            // 为了保持物理顺序不变（以便切换回顺序模式时不乱），我们需要原地替换
            // 由于 ConcurrentLinkedDeque 不支持原地替换，我们用重建队列的方式
            List<MusicQueueItem> snapshot = new ArrayList<>(queue);
            int index = snapshot.indexOf(item);
            if (index != -1) {
                snapshot.set(index, item.withPriority(Priority.USER_TOP));
                queue.clear();
                queue.addAll(snapshot);
                return TopResult.PERSONAL;
            }
        } else {
            // 顺序模式或单曲循环下 -> 直接变为全局置顶 (GLOBAL_TOP)
            if (queue.remove(item)) {
                queue.addFirst(item.withPriority(Priority.GLOBAL_TOP));
                return TopResult.GLOBAL;
            }
        }
        return TopResult.NONE;
    }

    /**
     * 原地换源：保留 queueId / 点歌人 / 优先级，只把曲目换成另一个源的同一首歌。
     * 用于「某源解析失败后自动换源重试」——队列位置不变，用户感知只是这一首重新进入解析。
     */
    public synchronized boolean replaceMusic(String queueId, Music music) {
        if (music == null) return false;
        Optional<MusicQueueItem> existing = findByQueueId(queueId);
        if (existing.isEmpty()) return false;
        MusicQueueItem old = existing.get();

        MusicQueueItem replaced = new MusicQueueItem(
                old.queueId(), music, old.enqueuedBy(), QueueItemStatus.PENDING, old.priority());
        List<MusicQueueItem> snapshot = new ArrayList<>(queue);
        int index = snapshot.indexOf(old);
        if (index < 0) return false;
        snapshot.set(index, replaced);
        queue.clear();
        queue.addAll(snapshot);
        reorderRoundRobin();
        return true;
    }

    /**
     * 从队列中移除指定用户的所有点歌
     * @return 移除的数量
     */
    public synchronized int removeByUser(String userToken) {
        List<MusicQueueItem> toRemove = queue.stream()
                .filter(item -> item.enqueuedBy().token().equals(userToken))
                .toList();
        
        toRemove.forEach(queue::remove);
        return toRemove.size();
    }

    /**
     * 根据 queueId 获取队列项（不移除）
     */
    public synchronized Optional<MusicQueueItem> getItem(String queueId) {
        return findByQueueId(queueId);
    }

    /**
     * 从队列中移除一首歌曲
     */
    public synchronized Optional<MusicQueueItem> remove(String queueId) {
        Optional<MusicQueueItem> itemOpt = findByQueueId(queueId);
        itemOpt.ifPresent(queue::remove);
        return itemOpt;
    }

    /**
     * 从队列中取出下一首可播放的歌曲
     * @param playMode 当前播放模式
     * @param isFairShuffle 是否启用公平随机 (轮询)
     * @param allowOfflineShuffle 是否允许随机到离线用户的歌
     * @param onlineUserTokens 在线用户的 Token 集合 (用于优先调度)
     * @return 下一首歌曲，如果队列为空则返回 null
     */
    public synchronized MusicQueueItem pollNext(PlayMode playMode, boolean isFairShuffle, boolean allowOfflineShuffle, Map<String, QueueItemStatus> statusMap, Set<String> onlineUserTokens) {
        if (queue.isEmpty()) {
            return pollFromHistory(); // 队列为空时，尝试从历史记录播放
        }

        List<MusicQueueItem> candidates = new ArrayList<>(queue);

        // 1. 优先处理全局置顶项 (GLOBAL_TOP)
        Optional<MusicQueueItem> topItem = candidates.stream()
                .filter(item -> item.priority() == Priority.GLOBAL_TOP && isReadyOrFailed(statusMap, item))
                .findFirst();

        if (topItem.isPresent()) {
            queue.remove(topItem.get());
            return topItem.get();
        }

        // 2. 移除全局置顶项，进行常规调度
        List<MusicQueueItem> availableItems = candidates.stream()
                .filter(item -> item.priority() != Priority.GLOBAL_TOP && isReadyOrFailed(statusMap, item))
                .toList();

        if (availableItems.isEmpty()) {
            return null; // 所有歌曲都在下载中
        }

        MusicQueueItem chosenItem;
        if (playMode == PlayMode.SHUFFLE) {
            if (isFairShuffle) {
                // 公平随机逻辑 (轮询 + 个人置顶优先)
                // 注意：pollNextFairShuffle 内部现在需要根据 allowOfflineShuffle 决定用户池
                chosenItem = pollNextFairShuffle(availableItems, onlineUserTokens, allowOfflineShuffle);
            } else {
                // 完全随机逻辑
                chosenItem = pollNextTotalShuffle(availableItems, onlineUserTokens, allowOfflineShuffle);
            }
        } else {
            // 顺序播放或单曲循环模式下切歌：直接取第一个
            chosenItem = availableItems.get(0);
        }

        if (chosenItem == null) {
            return null;
        }

        queue.remove(chosenItem);
        lastPlayedUserToken.set(chosenItem.enqueuedBy().token());
        return chosenItem;
    }

    /**
     * "完全"随机播放算法：在可用歌曲池中随机抽取
     */
    private MusicQueueItem pollNextTotalShuffle(List<MusicQueueItem> availableItems, Set<String> onlineUserTokens, boolean allowOffline) {
        List<MusicQueueItem> pool;
        if (allowOffline) {
            pool = availableItems;
        } else {
            pool = availableItems.stream()
                    .filter(item -> onlineUserTokens.contains(item.enqueuedBy().token()))
                    .toList();
        }

        if (pool.isEmpty()) {
            return null;
        }

        // 优先检查个人置顶 (即便完全随机，也要尊重用户的置顶意愿，或者是直接纯随机？)
        // 按照业务一致性，个人置顶在随机模式下应优先
        Optional<MusicQueueItem> userTop = pool.stream()
                .filter(i -> i.priority() == Priority.USER_TOP)
                .findAny(); // 任意一个置顶的都行

        if (userTop.isPresent()) {
            return userTop.get();
        }

        return pool.get(new Random().nextInt(pool.size()));
    }

    /**
     * "公平"随机播放算法：严格轮询 (Strict Round-Robin) + 在线优先 + 个人置顶优先
     */
    private MusicQueueItem pollNextFairShuffle(List<MusicQueueItem> availableItems, Set<String> onlineUserTokens, boolean allowOffline) {
        // 1. 按用户分组
        Map<String, List<MusicQueueItem>> userSongsMap = new HashMap<>();
        for (MusicQueueItem item : availableItems) {
            userSongsMap.computeIfAbsent(item.enqueuedBy().token(), k -> new ArrayList<>()).add(item);
        }

        List<String> allUserTokens = new ArrayList<>(userSongsMap.keySet());
        
        // 2. 筛选目标用户池
        List<String> targetUserTokens;
        if (allowOffline) {
            // 允许离线：用户池为所有人，但依然保持“在线优先”的轮询感？
            // 还是直接平权？通常公平随机下，允许离线意味着轮询不跳过离线人。
            targetUserTokens = allUserTokens;
        } else {
            // 不允许离线：只从在线用户中轮询
            targetUserTokens = allUserTokens.stream()
                    .filter(onlineUserTokens::contains)
                    .collect(Collectors.toList());
        }

        if (targetUserTokens.isEmpty()) {
            return null;
        }

        // 3. 严格轮询逻辑
        Collections.sort(targetUserTokens);

        String lastToken = lastPlayedUserToken.get();
        int nextIndex = 0;

        if (targetUserTokens.contains(lastToken)) {
            int currentIndex = targetUserTokens.indexOf(lastToken);
            nextIndex = (currentIndex + 1) % targetUserTokens.size();
        } else {
            // 寻找 Token 排序中紧随其后的用户
            nextIndex = 0;
            for (int i = 0; i < targetUserTokens.size(); i++) {
                if (targetUserTokens.get(i).compareTo(lastToken) > 0) {
                    nextIndex = i;
                    break;
                }
            }
        }

        String selectedUserToken = targetUserTokens.get(nextIndex);
        List<MusicQueueItem> userSongs = userSongsMap.get(selectedUserToken);

        // 4. 检查是否有个人置顶
        Optional<MusicQueueItem> userTop = userSongs.stream()
                .filter(i -> i.priority() == Priority.USER_TOP)
                .findFirst();

        if (userTop.isPresent()) {
            return userTop.get();
        }

        // 5. 用户内部随机
        Collections.shuffle(userSongs);
        return userSongs.get(0);
    }
    
    // ... (rest of methods)

    private Optional<MusicQueueItem> findByQueueId(String queueId) {
        return queue.stream()
                .filter(item -> item.queueId().equals(queueId))
                .findFirst();
    }

    /**
     * 将播放完的歌曲加入历史记录
     */
    public void addToHistory(Music music) {
        if (music == null) return;
        synchronized (playHistory) {
            playHistory.removeIf(m -> m.id().equals(music.id()) && m.platform().equals(music.platform()));
            playHistory.add(0, music); // 加到最前面
            if (playHistory.size() > appProperties.getQueue().getHistorySize()) {
                playHistory.removeLast();
            }
        }
    }

    /**
     * 清空队列和历史记录
     */
    public synchronized void clearAll() {
        queue.clear();
        playHistory.clear();
        loopList.clear();
        lastPlayedUserToken.set("");
    }

    public synchronized void clearPendingQueue() {
        queue.clear();
        loopList.clear();
    }

    public List<MusicQueueItem> getQueueSnapshot() {
        return new ArrayList<>(queue);
    }

    public List<Music> getHistorySnapshot() {
        synchronized (playHistory) {
            return new ArrayList<>(playHistory);
        }
    }

    /**
     * 从持久化存储恢复队列和历史记录
     */
    public synchronized void restore(List<MusicQueueItem> loadedQueue, List<Music> loadedHistory) {
        // Clear current
        queue.clear();
        playHistory.clear();
        loopList.clear();
        lastPlayedUserToken.set("");

        // Restore Queue
        if (loadedQueue != null) {
            queue.addAll(loadedQueue);
            reorderRoundRobin();
        }

        // Restore History
        if (loadedHistory != null) {
            playHistory.addAll(loadedHistory);
        }
    }

    public synchronized void addToLoopList(Music music) {
        if (music != null && loopList.stream().noneMatch(m -> musicKey(m).equals(musicKey(music)))) {
            loopList.add(music);
        }
    }

    public synchronized void removeFromLoopList(Music music) {
        if (music == null) return;
        loopList.removeIf(m -> musicKey(m).equals(musicKey(music)));
    }

    public synchronized void addToQueueEnd(Music music) {
        UserSummary systemUser = new UserSummary("SYSTEM", "SYSTEM", "AutoDJ", false);
        MusicQueueItem item = new MusicQueueItem(
                UUID.randomUUID().toString(),
                music,
                systemUser,
                QueueItemStatus.PENDING
        );
        queue.addLast(item);
    }

    public synchronized void repopulateFromLoopList() {
        if (loopList.isEmpty()) return;
        // 旧实现把整份 loopList 无条件追加：队列空一次就再加一遍，
        // LIST_LOOP 下反复重填 → 队列被同一批歌灌到数万条（并触发递归爆栈）。
        // 现在按 musicKey 去重，并受 maxSize 约束。
        int max = Math.max(1, appProperties.getQueue().getMaxSize());
        if (queue.size() >= max) return;

        UserSummary systemUser = new UserSummary("SYSTEM", "SYSTEM", "AutoDJ", false);
        Set<String> existing = queue.stream()
                .map(item -> musicKey(item.music()))
                .collect(Collectors.toSet());
        for (Music music : loopList) {
            if (queue.size() >= max) break;
            if (!existing.add(musicKey(music))) continue;
            queue.addLast(new MusicQueueItem(
                    UUID.randomUUID().toString(),
                    music,
                    systemUser,
                    QueueItemStatus.PENDING
            ));
        }
    }

    private boolean isMusicInQueue(Music music) {
        String key = musicKey(music);
        return queue.stream().anyMatch(item -> musicKey(item.music()).equals(key));
    }

    private boolean isReadyOrFailed(Map<String, QueueItemStatus> statusMap, MusicQueueItem item) {
        QueueItemStatus status = statusMap.getOrDefault(musicKey(item.music()), QueueItemStatus.PENDING);
        return status == QueueItemStatus.READY || status == QueueItemStatus.FAILED;
    }

    /**
     * 当队列为空时，从历史记录随机取一首作为 AutoDJ
     */
    private MusicQueueItem pollFromHistory() {
        if (playHistory.isEmpty()) {
            return null;
        }
        Music randomSong = playHistory.get(new Random().nextInt(playHistory.size()));

        UserSummary systemUser = new UserSummary("SYSTEM", "SYSTEM", "AutoDJ", false);

        // 注意：历史记录出来的歌需要重新判断状态
        return new MusicQueueItem(
                UUID.randomUUID().toString(),
                randomSong,
                systemUser,
                QueueItemStatus.PENDING
        );
    }
}