package org.thornex.musicparty.dto;

import com.fasterxml.jackson.databind.JsonNode;

/** 字段可单独更新。clearPassword=true 取消密码；summonCooldownHours=null 恢复站点默认。 */
public record RoomUpdateRequest(String name, String password, Boolean clearPassword, String visibility,
                                JsonNode summonCooldownHours) {
}
