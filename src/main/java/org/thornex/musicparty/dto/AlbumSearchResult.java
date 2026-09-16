package org.thornex.musicparty.dto;

public record AlbumSearchResult(
    String id,
    String name,
    String artist,
    String coverUrl,
    int trackCount,
    String platform
) {}