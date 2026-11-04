package org.thornex.musicparty.dto;

/** 创建房间：name 可留空（服务端用「<名字> 的房间」兜底），password 可空。 */
public record RoomCreateRequest(String name, String password) {
}
