package org.thornex.musicparty.dto;

/** 粘贴链接解析结果。kind: track | playlist | album。 */
public record ResolvedLink(String platform, String kind, String id, String title) {}
