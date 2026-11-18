package org.thornex.musicparty.service;

/**
 * Published when a fetch fails because YouTube demanded a login check. Lets the
 * health panel reflect reality immediately instead of waiting for the next probe.
 */
public record YoutubeAuthExpiredEvent(String reason) {}
