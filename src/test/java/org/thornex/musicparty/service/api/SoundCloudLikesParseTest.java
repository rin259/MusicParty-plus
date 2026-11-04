package org.thornex.musicparty.service.api;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 「喜欢的音乐」解析：SoundCloud 的 likes 接口返回的是 like 对象，里面包着 track；
 * 有的实现直接返回 track。两种都要能读，且页面地址（permalink）必须带上。
 */
class SoundCloudLikesParseTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void unwrapsLikeObjectsInto() throws Exception {
        String json = """
                {"collection":[
                  {"track":{"id":111,"title":"Liked One","duration":200000,
                    "artwork_url":"https://i1.sndcdn.com/a-large.jpg",
                    "permalink_url":"https://soundcloud.com/artist/liked-one",
                    "user":{"username":"Artist"}}},
                  {"track":{"id":222,"title":"Liked Two","duration":180000,
                    "permalink_url":"https://soundcloud.com/artist/liked-two",
                    "user":{"username":"Other"}}}
                ]}
                """;
        JsonNode node = MAPPER.readTree(json);
        var tracks = SoundCloudSearchClient.parseLikes(node);

        assertThat(tracks).hasSize(2);
        assertThat(tracks.get(0).id()).isEqualTo("111");
        assertThat(tracks.get(0).pageUrl()).isEqualTo("https://soundcloud.com/artist/liked-one");
        assertThat(tracks.get(0).thumbnail()).isEqualTo("https://i1.sndcdn.com/a-t500x500.jpg");
        assertThat(tracks.get(1).title()).isEqualTo("Liked Two");
    }

    @Test
    void acceptsPlainTrackItems() throws Exception {
        String json = """
                {"collection":[
                  {"id":333,"title":"Plain","duration":100000,
                    "permalink_url":"https://soundcloud.com/x/plain","user":{"username":"X"}}
                ]}
                """;
        var tracks = SoundCloudSearchClient.parseLikes(MAPPER.readTree(json));
        assertThat(tracks).hasSize(1);
        assertThat(tracks.get(0).id()).isEqualTo("333");
    }

    @Test
    void dropsEntriesWithoutIdOrTitle() throws Exception {
        String json = """
                {"collection":[
                  {"track":{"id":0,"title":"no id"}},
                  {"track":{"id":444,"title":""}},
                  {"track":{"id":555,"title":"ok","permalink_url":"https://soundcloud.com/a/ok","user":{"username":"A"}}}
                ]}
                """;
        var tracks = SoundCloudSearchClient.parseLikes(MAPPER.readTree(json));
        assertThat(tracks).hasSize(1);
        assertThat(tracks.get(0).id()).isEqualTo("555");
    }

    @Test
    void missingCollectionYieldsEmpty() throws Exception {
        assertThat(SoundCloudSearchClient.parseLikes(MAPPER.readTree("{}"))).isEmpty();
    }
}
