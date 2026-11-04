package org.thornex.musicparty.dto;

/** 房主修改房间：name/password 二选一或都传；clearPassword=true 表示取消密码。 */
public record RoomUpdateRequest(String name, String password, Boolean clearPassword) {
}
