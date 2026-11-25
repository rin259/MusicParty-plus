import { test } from 'node:test';
import assert from 'node:assert/strict';
import { sourceUrlFor, hasDirectUrl } from './sourceUrl.js';

test('a real permalink is used as-is', () => {
  const song = { id: '123', platform: 'soundcloud', url: 'https://soundcloud.com/a/b' };
  assert.equal(hasDirectUrl(song), true);
  assert.equal(sourceUrlFor(song), 'https://soundcloud.com/a/b');
});

test('platforms with a stable path get a template', () => {
  assert.equal(sourceUrlFor({ id: '42', platform: 'netease' }), 'https://music.163.com/#/song?id=42');
  assert.equal(sourceUrlFor({ id: 'BV1x', platform: 'bilibili' }), 'https://www.bilibili.com/video/BV1x');
  assert.equal(sourceUrlFor({ id: 'yt1', platform: 'ytmusic' }), 'https://music.youtube.com/watch?v=yt1');
});

test('soundcloud without a permalink falls back to the server resolver', () => {
  // A numeric SoundCloud id has no page path of its own.
  assert.equal(sourceUrlFor({ id: '999', platform: 'soundcloud' }), '/api/track/url?platform=soundcloud&id=999');
});

test('an unusable url falls through to the template instead of being used', () => {
  const song = { id: '42', platform: 'netease', url: 'not-a-url' };
  assert.equal(hasDirectUrl(song), false);
  assert.equal(sourceUrlFor(song), 'https://music.163.com/#/song?id=42');
});

test('ids are escaped so odd characters cannot break the link', () => {
  assert.equal(sourceUrlFor({ id: 'a b&c', platform: 'bilibili' }), 'https://www.bilibili.com/video/a%20b%26c');
});

test('nothing to link to returns null rather than a bare hash', () => {
  assert.equal(sourceUrlFor(null), null);
  assert.equal(sourceUrlFor({ platform: 'unknown' }), null);
  assert.equal(sourceUrlFor({ platform: 'soundcloud' }), null);
});
