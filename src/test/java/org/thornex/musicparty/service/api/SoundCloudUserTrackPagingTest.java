package org.thornex.musicparty.service.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用户级集合（喜欢的音乐 / 我上传的 / 自己的歌单）的游标累积逻辑。
 *
 * <p>回归背景（2026-11）：线上滚动 LIKES 时，列表一直循环大约 200 首。
 * 真实原因是 {@code loadUserTracks} 每次都从第一页重新取，再把整页追加到缓存：
 * 只要缓存条数还不够 offset+limit，就会把第一页重复插一遍。而游标越过末尾时
 * 上游又会回绕到第一页（不返回空数组），所以「空数组 = 到底」也不成立。
 */
class SoundCloudUserTrackPagingTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode page(String nextHref, int fromIndex, int count) {
        ObjectNode node = MAPPER.createObjectNode();
        ArrayNode collection = node.putArray("collection");
        for (int i = 0; i < count; i++) {
            ObjectNode track = collection.addObject();
            track.put("id", 1_000_000 + fromIndex + i);
            track.put("title", "track-" + (fromIndex + i));
            track.put("duration", 180_000);
            track.putObject("user").put("username", "artist");
        }
        if (nextHref != null) node.put("next_href", nextHref);
        return node;
    }

    private static List<String> ids(SoundCloudMusicApiService.TrackPage page) {
        List<String> out = new ArrayList<>();
        page.items.forEach(track -> out.add(track.id()));
        return out;
    }

    @Test
    void continuesFromTheCachedCursorInsteadOfRefetchingTheFirstPage() {
        List<String> calls = new ArrayList<>();
        Function<String, JsonNode> api = url -> {
            calls.add(url);
            return switch (url) {
                case "start" -> page("c1", 0, 50);
                case "c1" -> page("c2", 50, 50);
                case "c2" -> page(null, 100, 50);
                default -> throw new AssertionError("不应请求 " + url);
            };
        };

        SoundCloudMusicApiService.TrackPage page = new SoundCloudMusicApiService.TrackPage();

        SoundCloudMusicApiService.drainPages(page, 60, "start", api);
        // 整页取：want=60 需要两页，所以拿到 100 条（多取的部分由调用方 slice）
        assertThat(page.items).hasSize(100);
        assertThat(calls).containsExactly("start", "c1");

        // 第二段必须从 c2 继续；如果又从 "start" 取，就会出现重复内容（线上看到的现象）
        SoundCloudMusicApiService.drainPages(page, 150, "start", api);
        assertThat(page.items).hasSize(150);
        assertThat(calls).containsExactly("start", "c1", "c2");
        assertThat(ids(page)).doesNotHaveDuplicates();
    }

    @Test
    void stopsWhenUpstreamWrapsBackToTheFirstPage() {
        // 真实行为：游标越过末尾后返回第一页，而不是空数组
        Function<String, JsonNode> api = url -> "start".equals(url) ? page("c1", 0, 50) : page("c1", 0, 50);

        SoundCloudMusicApiService.TrackPage page = new SoundCloudMusicApiService.TrackPage();
        SoundCloudMusicApiService.drainPages(page, 500, "start", api);

        assertThat(page.items).hasSize(50);
        assertThat(page.complete).as("回绕必须被识别为到底").isTrue();
        assertThat(ids(page)).doesNotHaveDuplicates();
    }

    @Test
    void keepsGoingWhenTheSameNextHrefIsReturnedButTheDataAdvances() {
        // 上游偶尔连续返回同一个 next_href，但每页数据仍在前进，不能据此提前结束
        int[] calls = {0};
        Function<String, JsonNode> api = url -> switch (calls[0]++) {
            case 0 -> page("same", 0, 50);
            case 1 -> page("same", 50, 50);
            case 2 -> page(null, 100, 50);
            default -> throw new AssertionError("取多了");
        };

        SoundCloudMusicApiService.TrackPage page = new SoundCloudMusicApiService.TrackPage();
        SoundCloudMusicApiService.drainPages(page, 150, "start", api);

        assertThat(page.items).hasSize(150);
        assertThat(page.complete).isTrue();
    }

    @Test
    void scrollingThroughEveryPageNeverRepeatsAContentBlock() {
        // 模拟前端连续翻页：offset 0/50/.../400，上游两页真实数据后回绕
        Function<String, JsonNode> api = url -> switch (url) {
            case "start" -> page("c1", 0, 200);
            case "c1" -> page("c2", 200, 200);
            case "c2" -> page("start", 0, 200);   // 回绕到第一页
            default -> throw new AssertionError("不应请求 " + url);
        };

        SoundCloudMusicApiService.TrackPage page = new SoundCloudMusicApiService.TrackPage();
        for (int offset = 0; offset <= 400; offset += 50) {
            SoundCloudMusicApiService.drainPages(page, offset + 50, "start", api);
        }

        assertThat(page.items).hasSize(400);
        assertThat(ids(page)).doesNotHaveDuplicates();
        assertThat(page.complete).isTrue();
    }
}
