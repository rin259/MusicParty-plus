import test from 'node:test';
import assert from 'node:assert/strict';
import {
    dedupeKey,
    normalizeTitle,
    mergeResults,
    looksLikeLink,
    formatDuration,
    scoreSong
} from './searchMerge.js';

const song = (id, name, artist, extra = {}) => ({
    id,
    name,
    artists: [artist],
    duration: 200000,
    coverUrl: 'x',
    ...extra
});

test('标题归一：剥掉视频噪声，但不剥 live', () => {
    assert.equal(normalizeTitle('Hello (Official Music Video)', 'Adele'), 'hello');
    assert.equal(normalizeTitle('Adele - Hello [Lyrics]', 'Adele'), 'hello');
    assert.equal(normalizeTitle('Hello (Live at the Royal Albert Hall)', 'Adele'), 'hello live at the royal albert hall');
    assert.equal(normalizeTitle('【高清】晴天 - 周杰伦', '周杰伦'), '晴天 周杰伦');
});

test('跨源去重：Adele - Hello 与 Hello 合并，来源保留两个', () => {
    const merged = mergeResults([
        { platform: 'netease', songs: [song('1', 'Hello', 'Adele')] },
        { platform: 'ytmusic', songs: [song('YQHsXMglC9A', 'Adele - Hello (Official Music Video)', 'Adele')] }
    ], 'hello');
    assert.equal(merged.length, 1);
    assert.equal(merged[0].platform, 'netease');
    assert.deepEqual(merged[0]._sources.map((s) => s.platform).sort(), ['netease', 'ytmusic']);
});

test('现场版不合并进录音室版', () => {
    const merged = mergeResults([
        { platform: 'netease', songs: [song('1', 'Hello', 'Adele')] },
        { platform: 'netease', songs: [song('2', 'Hello (Live)', 'Adele')] }
    ], 'hello');
    assert.equal(merged.length, 2);
});

test('英文大小写与标点不影响去重', () => {
    const a = dedupeKey(song('1', 'AC/DC - Back In Black', 'AC/DC'));
    const b = dedupeKey(song('2', 'Back in Black', 'AC DC'));
    assert.equal(a, b);
});

test('排序：同分时网易云优先，且精确标题优先', () => {
    const merged = mergeResults([
        { platform: 'ytmusic', songs: [song('a', 'Hello', 'Adele')] },
        { platform: 'netease', songs: [song('b', 'Hello', 'Adele')] }
    ], 'hello');
    assert.equal(merged[0].platform, 'netease');
    assert.ok(scoreSong(song('1', 'Hello', 'Adele'), 'hello', 'netease')
        > scoreSong(song('2', 'Hello Again', 'Adele'), 'hello', 'netease'));
});

test('时长为 0 的结果降权（网易云旧接口缺陷的兜底）', () => {
    const withDuration = scoreSong(song('1', 'Hello', 'Adele'), 'hello', 'netease');
    const without = scoreSong(song('2', 'Hello', 'Adele', { duration: 0 }), 'hello', 'netease');
    assert.ok(withDuration > without);
});

test('链接识别与 BV 号', () => {
    assert.equal(looksLikeLink('https://music.163.com/song?id=1'), true);
    assert.equal(looksLikeLink('BV1xvxi6LEfz'), true);
    assert.equal(looksLikeLink('hello world'), false);
});

test('时长格式', () => {
    assert.equal(formatDuration(0), '0:00');
    assert.equal(formatDuration(295502), '4:56');
    assert.equal(formatDuration(61000), '1:01');
});
