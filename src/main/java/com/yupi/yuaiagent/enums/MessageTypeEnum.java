package com.yupi.yuaiagent.enums;

import lombok.Getter;
/**
 * 消息角色类型枚举，区分 system / user / assistant 三种角色
 */
@Getter
public enum MessageTypeEnum {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant");
    private final String value;
    MessageTypeEnum(String value) { this.value = value; }

    public static MessageTypeEnum fromString(String str) {
        for (MessageTypeEnum type : MessageTypeEnum.values()) {
            if (type.value.equalsIgnoreCase(str)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown message role: " + str);
    }
}
