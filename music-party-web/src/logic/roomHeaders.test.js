import test from 'node:test';
import assert from 'node:assert/strict';

import { headerValue } from './roomHeaders.js';

// XHR.setRequestHeader 只接受 ISO-8859-1；线上有用户用中文昵称打开 landing 时
// 直接抛 "String contains non ISO-8859-1 code point."，房间列表整个加载不出来。

const isHeaderSafe = (value) => /^[\x20-\x7e]*$/.test(value);

test('encodes non-ASCII names into header-safe ASCII', () => {
  const encoded = headerValue('小明🎵');

  assert.ok(isHeaderSafe(encoded), `header 不安全: ${encoded}`);
  assert.equal(decodeURIComponent(encoded), '小明🎵');
});

test('keeps ASCII names readable but still encodable', () => {
  const encoded = headerValue('Alice_01');

  assert.ok(isHeaderSafe(encoded));
  assert.equal(decodeURIComponent(encoded), 'Alice_01');
});

test('encodes characters that would otherwise break the header syntax', () => {
  for (const name of ['a+b', '100%', 'x y', '"quoted"', 'a\\b']) {
    const encoded = headerValue(name);
    assert.ok(isHeaderSafe(encoded), `${name} -> ${encoded}`);
    assert.equal(decodeURIComponent(encoded), name);
  }
});

test('null/undefined become an empty header instead of "null"', () => {
  assert.equal(headerValue(null), '');
  assert.equal(headerValue(undefined), '');
});

test('never throws on malformed unicode', () => {
  // 孤立代理项会让 encodeURIComponent 抛 URIError
  assert.doesNotThrow(() => headerValue('\uD800'));
  assert.equal(headerValue('\uD800'), '');
});