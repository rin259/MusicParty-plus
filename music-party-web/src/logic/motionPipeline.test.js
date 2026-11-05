import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

// 動效是產品的一部分：桌面/移動/不同系統偏好都必須看到同一套動畫。
// 禁止再引入系統「減少動態」這類會讓一部分使用者「少一個版本」的偏好分支。
const ROOT = fileURLToPath(new URL('../..', import.meta.url));
const NEEDLE = ['prefers', 'reduced', 'motion'].join('-');

function sourceFiles(dir) {
    return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
        if (entry.name === 'node_modules' || entry.name === 'dist' || entry.name === '.git') return [];
        const path = join(dir, entry.name);
        if (entry.isDirectory()) return sourceFiles(path);
        return /\.(js|vue|css|html)$/.test(entry.name) ? [path] : [];
    });
}

test('all platforms share one animation pipeline', () => {
    const offenders = sourceFiles(ROOT).filter((file) =>
        readFileSync(file, 'utf8').toLowerCase().includes(NEEDLE)
    );
    assert.deepEqual(offenders, [], `animation preference branches found in:\n${offenders.join('\n')}`);
});
