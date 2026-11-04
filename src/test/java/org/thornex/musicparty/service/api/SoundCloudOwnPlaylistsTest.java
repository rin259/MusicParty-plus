package org.thornex.musicparty.service.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.thornex.musicparty.dto.Playlist;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * /me/playlists 的解析：官方 OpenAPI 里 Playlist 只有 urn（soundcloud:playlists:123），
 * 没有 id。私密歌单用 sharing=private 标记，名称后追加「 · 私密」。
 */
class SoundCloudOwnPlaylistsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static List<Playlist> parse(String json) throws Exception {
        return SoundCloudMusicApiService.parseOwnPlaylists("djtest", MAPPER.readTree(json), "soundcloud");
    }

    @Test
    void readsIdFromUrnWhenIdIsAbsent() throws Exception {
        List<Playlist> playlists = parse("""
                {"collection":[{"urn":"soundcloud:playlists:1212781357","title":"Public Set",
                  "sharing":"public","track_count":12,"artwork_url":"https://i1.sndcdn.com/a-large.jpg"}]}
                """);

        assertThat(playlists).hasSize(1);
        assertThat(playlists.get(0).id()).isEqualTo("own:djtest:1212781357");
        assertThat(playlists.get(0).name()).isEqualTo("Public Set");
        assertThat(playlists.get(0).trackCount()).isEqualTo(12);
        assertThat(playlists.get(0).coverImgUrl()).isEqualTo("https://i1.sndcdn.com/a-t500x500.jpg");
    }

    @Test
    void marksPrivatePlaylists() throws Exception {
        List<Playlist> playlists = parse("""
                {"collection":[
                  {"urn":"soundcloud:playlists:1","title":"Secret","sharing":"private","track_count":5},
                  {"urn":"soundcloud:playlists:2","title":"Open","sharing":"public","track_count":3}
                ]}
                """);

        assertThat(playlists).extracting(Playlist::name)
                .containsExactly("Secret · 私密", "Open");
        assertThat(playlists).extracting(Playlist::id)
                .containsExactly("own:djtest:1", "own:djtest:2");
    }

    @Test
    void acceptsLegacyNumericId() throws Exception {
        List<Playlist> playlists = parse("""
                {"collection":[{"id":999,"title":"Legacy","sharing":"public","track_count":1}]}
                """);
        assertThat(playlists).extracting(Playlist::id).containsExactly("own:djtest:999");
    }

    @Test
    void skipsEntriesWithoutIdentityOrTitle() throws Exception {
        List<Playlist> playlists = parse("""
                {"collection":[
                  {"urn":"","title":"No id"},
                  {"urn":"soundcloud:playlists:7","title":""},
                  {"urn":"soundcloud:playlists:8","title":"Ok","sharing":"public"}
                ]}
                """);
        assertThat(playlists).extracting(Playlist::name).containsExactly("Ok");
    }

    @Test
    void emptyResponseYieldsEmptyList() throws Exception {
        assertThat(parse("{}")).isEmpty();
        assertThat(parse("{\"collection\":[]}")).isEmpty();
    }
}
