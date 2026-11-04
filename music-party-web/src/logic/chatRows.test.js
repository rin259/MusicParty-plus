import test from 'node:test';
import assert from 'node:assert/strict';
import { buildChatRows, LIKE_MERGE_WINDOW_MS } from './chatRows.js';

const msg = (type, timestamp, content, id = String(timestamp)) => ({
    id, type, timestamp, content, userId: 'u', userName: 'U'
});

test('连续点赞合并为一行并计数', () => {
    const rows = buildChatRows([
        msg('LIKE', 1000, 'A 点赞了 歌 1'),
        msg('LIKE', 2000, 'B 点赞了 歌 1'),
        msg('LIKE', 3000, 'C 点赞了 歌 1')
    ], 'CHAT');

    assert.equal(rows.length, 1);
    assert.equal(rows[0].count, 3);
    assert.equal(rows[0].msg.content, 'C 点赞了 歌 1');
});

test('中间夹了聊天就另起一行', () => {
    const rows = buildChatRows([
        msg('LIKE', 1000, '赞 1'),
        msg('CHAT', 1500, '说话'),
        msg('LIKE', 2000, '赞 2')
    ], 'CHAT');

    assert.equal(rows.length, 3);
    assert.deepEqual(rows.map(r => r.count), [1, 1, 1]);
});

test('超出时间窗的点赞不合并', () => {
    const rows = buildChatRows([
        msg('LIKE', 1000, '赞 1'),
        msg('LIKE', 1000 + LIKE_MERGE_WINDOW_MS + 1, '赞 2')
    ], 'CHAT');

    assert.equal(rows.length, 2);
});

test('按 Tab 过滤且点赞在两个 Tab 都保留', () => {
    const input = [
        msg('CHAT', 1000, '聊天'),
        msg('SYSTEM', 2000, '系统'),
        msg('LIKE', 3000, '点赞')
    ];
    assert.deepEqual(buildChatRows(input, 'CHAT').map(r => r.msg.type), ['CHAT', 'LIKE']);
    assert.deepEqual(buildChatRows(input, 'SYSTEM INFO').map(r => r.msg.type), ['SYSTEM', 'LIKE']);
});

test('超过 3 分钟显示时间戳', () => {
    // 真实时间戳是 epoch ms；第一条总是显示时间
    const base = Date.now();
    const rows = buildChatRows([
        msg('CHAT', base, 'a'),
        msg('CHAT', base + 1, 'b'),
        msg('CHAT', base + 3 * 60 * 1000 + 10, 'c')
    ], 'CHAT');

    assert.deepEqual(rows.map(r => r.showTime), [true, false, true]);
});
