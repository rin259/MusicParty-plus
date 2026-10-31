package org.thornex.musicparty.dto;

/** 单个音乐源的可观测状态：是否启用、是否可达、探测时延与样本量。 */
public record SourceHealth(
        String platform,
        boolean enabled,
        boolean ok,
        long latencyMs,
        int sampleCount,
        String error,
        long checkedAt
) {}
