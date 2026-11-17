// Browser regression: PLAYWRIGHT_MODULE may point to an existing shared Playwright installation.
// Run: node tests/search-modal-layout.mjs
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import { mkdir } from 'node:fs/promises';
import { createServer } from 'vite';

const { chromium } = await import(process.env.PLAYWRIGHT_MODULE || 'playwright');
const server = await createServer({
    root: fileURLToPath(new URL('..', import.meta.url)),
    server: { host: '127.0.0.1', port: 0, open: false }
});
let browser;
const output = process.env.LAYOUT_SCREENSHOTS;
const longName = '很长的专辑名称 AlbumTitleWithoutAnySpaces'.repeat(12);
const song = { id: 'song', platform: 'netease', name: longName, artists: [longName], duration: 1000 };
try {
    if (output) await mkdir(output, { recursive: true });
    await server.listen();
    const origin = `http://127.0.0.1:${server.httpServer.address().port}`;
    browser = await chromium.launch({
        headless: true,
        ...(process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {}),
        args: ['--no-sandbox']
    });
    for (const theme of ['light', 'dark']) {
        for (const width of [320, 390, 767, 768, 900, 1200, 1440]) {
            const page = await browser.newPage({ viewport: { width, height: 850 } });
            const errors = [];
            page.on('pageerror', e => errors.push(e.message));
            await page.route(`${origin}/api/**`, route => {
                const path = new URL(route.request().url()).pathname;
                const data = path.includes('/search/albums')
                    ? [{ id: 'album', name: longName, artist: longName, trackCount: 1 }, { id: 'short', name: '短专辑', artist: '歌手', trackCount: 1 }]
                    : path.includes('/album/songs') || path.includes('/playlist/songs') || path === '/api/search' ? [song]
                        : path.includes('/user/playlists') ? [{ id: 'playlist', name: `测试歌单 ${longName}`, trackCount: 1, platform: 'netease' }] : [];
                return route.fulfill({ json: data });
            });
            await page.goto(`${origin}/tests/fixtures/search-modal.html`);
            await page.evaluate(async mode => {
                const { getThemeRuntime } = await import('/src/logic/themeRuntime.js');
                const runtime = getThemeRuntime();
                runtime.save({ ...runtime.getSnapshot().committed, mode });
            }, theme);
            await page.getByRole('tab', { name: '网易云', exact: true }).click();
            await page.getByRole('button', { name: '搜索专辑', exact: true }).click();
            const input = page.getByRole('textbox', { name: '搜索音乐', exact: true });
            await input.fill('test');
            await input.press('Enter');
            const pane = page.getByTestId('search-results-pane');
            const album = pane.getByRole('button').filter({ hasText: longName }).first();
            await album.waitFor({ state: 'visible' });
            const checkBounds = async state => {
                const bounds = await pane.evaluate(el => {
                    const dialog = el.closest('[role=dialog]');
                    const r = el.getBoundingClientRect(), d = dialog.getBoundingClientRect();
                    return {
                        paneLeft: r.left, paneRight: r.right, dialogLeft: d.left, dialogRight: d.right,
                        dialogWidth: d.width, paneWidth: r.width,
                        overflows: [...el.querySelectorAll('div,button')].filter(node => {
                            const rect = node.getBoundingClientRect();
                            return rect.width > 0 && (rect.right > r.right + 1 || rect.left < r.left - 1);
                        }).map(node => node.className)
                    };
                });
                assert.ok(bounds.paneLeft >= bounds.dialogLeft - 1 && bounds.paneRight <= bounds.dialogRight + 1,
                    `${theme}/${width}/${state}: pane escapes dialog: ${JSON.stringify(bounds)}`);
                assert.deepEqual(bounds.overflows, [], `${theme}/${width}/${state}: child escapes pane`);
                const toolbar = await input.evaluate(el => {
                    const row = el.parentElement.getBoundingClientRect();
                    return [...el.parentElement.children].every(child => {
                        const r = child.getBoundingClientRect();
                        return r.left >= row.left - 1 && r.right <= row.right + 1;
                    });
                });
                assert.ok(toolbar, `${theme}/${width}/${state}: search toolbar overflows`);
                if (width >= 768) assert.ok(Math.abs(bounds.paneWidth / bounds.dialogWidth - 2 / 3) < 0.01,
                    `${theme}/${width}/${state}: desktop sidebar/results ratio changed`);
                const buttons = pane.getByRole('button');
                for (const button of await buttons.all()) {
                    if (!await button.isVisible()) continue;
                    const box = await button.boundingBox();
                    assert.ok(box.x + box.width <= bounds.dialogRight + 1, `${state}: action clipped`);
                }
                if (output) await page.screenshot({ path: `${output}/${theme}-${width}-${state}.png` });
            };
            await checkBounds('albums');
            assert.equal(await album.locator('svg').last().evaluate(el => el.getBoundingClientRect().width), 16);
            await album.click();
            await pane.getByText('1 LOADED', { exact: true }).waitFor();
            await checkBounds('tracks');
            await pane.getByRole('button', { name: '返回专辑列表', exact: true }).click();
            await checkBounds('back-to-albums');
            await page.getByRole('button', { name: '搜索专辑', exact: true }).click();
            await input.press('Enter');
            await pane.getByText('NETEASE', { exact: true }).waitFor();
            await checkBounds('songs');
            if (width < 768) await page.getByRole('button', { name: '返回歌单', exact: true }).click();
            await page.getByRole('button').filter({ hasText: '测试歌单' }).click();
            await pane.getByText('1 LOADED', { exact: true }).waitFor();
            await checkBounds('playlist');
            assert.deepEqual(errors, []);
            console.log(`PASS ${theme} ${width}px: albums, tracks, back, songs, playlist`);
            await page.close();
        }
    }
} finally {
    await browser?.close();
    await server.close();
}
