// Real App fixture with mocked API/socket only. Uses existing Playwright installation.
import assert from 'node:assert/strict';
import { fileURLToPath } from 'node:url';
import { mkdir } from 'node:fs/promises';
import { createServer } from 'vite';
const { chromium } = await import(process.env.PLAYWRIGHT_MODULE || 'playwright');
const server = await createServer({ root: fileURLToPath(new URL('..', import.meta.url)), server: { host: '127.0.0.1', port: 0, open: false } });
let browser;
const output = process.env.THEME_SCREENSHOTS;
try {
    if (output) await mkdir(output, { recursive: true });
    await server.listen();
    const origin = `http://127.0.0.1:${server.httpServer.address().port}`;
    browser = await chromium.launch({ headless: true, ...(process.env.CHROMIUM_PATH ? { executablePath: process.env.CHROMIUM_PATH } : {}), args: ['--no-sandbox'] });
    const context = await browser.newContext({ viewport: { width: 1200, height: 900 }, colorScheme: 'light' });
    await context.route(`${origin}/api/**`, route => {
        const path = new URL(route.request().url()).pathname;
        const room = { id: 'main', name: '主题测试房间', visibility: 'public', hasPassword: false, onlineCount: 0 };
        const data = path.includes('/lyric/') ? '' : path === '/api/config' ? { authorName: 'ThorNex', backWords: 'MUSIC PARTY' }
            : path.includes('/rooms') ? [room]
                : path.includes('/search/albums') ? [{ id: 'album', name: '一张专辑', artist: '歌手', trackCount: 1 }]
                    : path === '/api/search' ? [{ id: 'song', name: 'Test Song', artists: ['Artist'], platform: 'netease', duration: 1000 }] : [];
        return route.fulfill({ json: data });
    });
    await context.route('https://fonts.googleapis.com/**', route => route.abort());
    await context.route('https://fonts.gstatic.com/**', route => route.abort());
    await context.addInitScript(() => localStorage.setItem('mp_tutorial_done_v1', 'true'));
    await context.route(`${origin}/cover-blue.svg`, route => route.fulfill({ contentType: 'image/svg+xml', body: '<svg xmlns="http://www.w3.org/2000/svg" width="64" height="64"><rect width="64" height="64" fill="#205be0"/></svg>' }));
    await context.route(`${origin}/cover-red.svg`, route => route.fulfill({ contentType: 'image/svg+xml', body: '<svg xmlns="http://www.w3.org/2000/svg" width="64" height="64"><rect width="64" height="64" fill="#e02535"/></svg>' }));
    await context.route(`${origin}/broken-cover`, route => route.fulfill({ status: 404, body: '' }));
    const page = await context.newPage();
    const errors = [];
    page.on('pageerror', e => errors.push(e.message));
    const url = `${origin}/tests/fixtures/appearance.html`;
    const accent = () => page.evaluate(() => document.documentElement.style.getPropertyValue('--accent'));
    const pref = () => page.evaluate(() => JSON.parse(localStorage.getItem('mp_appearance_v1') || 'null'));
    await page.goto(url);
    await page.getByRole('button', { name: '自定义主题', exact: true }).click();
    const dialog = page.getByRole('dialog', { name: '外观与主题' });
    await dialog.waitFor();
    await dialog.getByRole('button', { name: '海洋蓝', exact: true }).click();
    assert.equal(await accent(), '37 99 235'); assert.equal(await pref(), null, 'preview does not persist');
    await page.keyboard.press('Escape');
    await dialog.waitFor({ state: 'hidden' }); assert.equal(await accent(), '249 115 22');
    assert.equal(await page.getByRole('button', { name: '自定义主题', exact: true }).evaluate(el => document.activeElement === el), true, 'focus returns to launcher');
    await page.getByRole('button', { name: '自定义主题', exact: true }).click();
    await dialog.getByLabel('自定义强调色', { exact: true }).fill('#1122zz');
    await dialog.getByRole('button', { name: '保存主题', exact: true }).click();
    assert.ok(await dialog.isVisible()); assert.equal(await pref(), null);
    await dialog.getByRole('button', { name: '鸢尾紫', exact: true }).click();
    await dialog.getByRole('button', { name: '深色', exact: true }).click();
    await dialog.getByRole('button', { name: '保存主题', exact: true }).click();
    assert.equal((await pref()).accent, '#8b5cf6');
    await page.reload(); await page.waitForFunction(() => window.appearanceFixture);
    assert.equal(await accent(), '139 92 246');
    assert.equal(await page.locator('html').getAttribute('data-theme'), 'dark');
    // Cross-tab sync including an open, unsaved draft.
    const other = await context.newPage(); await other.goto(url);
    await page.getByRole('button', { name: '自定义主题', exact: true }).click();
    await dialog.getByRole('button', { name: '玫瑰红', exact: true }).click();
    await other.getByRole('button', { name: '自定义主题', exact: true }).click();
    await other.getByRole('dialog', { name: '外观与主题' }).getByRole('button', { name: '海洋蓝', exact: true }).click();
    await other.getByRole('button', { name: '保存主题', exact: true }).click();
    await page.waitForFunction(() => document.documentElement.style.getPropertyValue('--accent') === '37 99 235');
    assert.ok(await dialog.getByText('另一个标签页更新了主题，已载入最新设置。', { exact: true }).isVisible());
    await dialog.getByRole('button', { name: '取消', exact: true }).click(); await other.close();
    // Import rejects bad JSON, previews valid schemas, and exports exactly that schema.
    await page.getByRole('button', { name: '自定义主题', exact: true }).click();
    await dialog.locator('summary').click();
    await dialog.getByLabel('主题 JSON', { exact: true }).fill('{bad');
    await dialog.getByRole('button', { name: '预览导入', exact: true }).click();
    assert.ok(await dialog.getByText('无法读取 JSON 主题文件', { exact: true }).isVisible());
    const imported = { ...(await pref()), mode: 'light', source: 'manual', accent: '#059669' };
    await dialog.getByLabel('主题 JSON', { exact: true }).fill(JSON.stringify(imported));
    await dialog.getByRole('button', { name: '预览导入', exact: true }).click();
    assert.equal(await accent(), '5 150 105');
    const download = page.waitForEvent('download');
    await dialog.getByRole('button', { name: '导出 JSON', exact: true }).click();
    assert.equal((await download).suggestedFilename(), 'music-party-theme.json');
    assert.equal(JSON.parse(await dialog.getByLabel('主题 JSON', { exact: true }).inputValue()).accent, '#059669');
    await dialog.getByRole('button', { name: '保存主题', exact: true }).click();
    // System live mode; Monet is an independent source, not a brightness mode.
    await page.getByRole('button', { name: '自定义主题', exact: true }).click();
    await dialog.getByRole('button', { name: '系统', exact: true }).click();
    await page.emulateMedia({ colorScheme: 'dark' });
    await page.waitForFunction(() => document.documentElement.dataset.theme === 'dark');
    await dialog.getByRole('button', { name: 'Monet · 跟随封面', exact: true }).click();
    await page.evaluate(() => { window.appearanceFixture.player.nowPlaying = { music: { id: 'cover', name: '封面测试', artists: ['Artist'], coverUrl: '/cover-blue.svg' }, likedUserIds: [] }; });
    await page.waitForFunction(() => window.appearanceFixture.appearance.snapshot.monetAccent);
    const blue = await accent();
    // Simulate like / state sync: new nowPlaying object reference with same coverUrl must not jump or flicker
    let themeChangeCount = 0;
    await page.exposeFunction('onThemeChangedInTest', () => themeChangeCount++);
    await page.evaluate(() => window.addEventListener('music-party:theme-change', window.onThemeChangedInTest));
    await page.evaluate(() => {
        window.appearanceFixture.player.nowPlaying = {
            music: { id: 'cover', name: '封面测试', artists: ['Artist'], coverUrl: '/cover-blue.svg' },
            likedUserIds: ['self']
        };
    });
    await page.waitForTimeout(100);
    assert.equal(await accent(), blue, 'like update with same coverUrl must keep active theme');
    assert.equal(themeChangeCount, 0, 'like update with same coverUrl must not re-dispatch theme changes');
    await page.evaluate(() => { window.appearanceFixture.player.nowPlaying.music.coverUrl = '/cover-red.svg'; });
    await page.waitForFunction(previous => document.documentElement.style.getPropertyValue('--accent') !== previous && window.appearanceFixture.appearance.snapshot.monetAccent, blue);
    await page.evaluate(() => { window.appearanceFixture.player.nowPlaying.music.coverUrl = '/broken-cover'; });
    await page.waitForFunction(() => window.appearanceFixture.appearance.snapshot.monetAccent === null);
    await dialog.getByRole('button', { name: '保存主题', exact: true }).click();
    await page.evaluate(() => { const f = window.appearanceFixture; f.room.info = { id: 'main', name: '主题测试房间' }; f.room.entered = true; });
    await page.waitForFunction(() => document.querySelector('header button[aria-label="自定义主题"]'));
    // Responsive screen checks cover real landing, room and the native settings dialog.
    for (const width of [320, 390, 768, 1200]) {
        await page.setViewportSize({ width, height: 850 });
        await page.waitForFunction(width => {
            const mobileLayout = document.querySelector('.max-w-\\[min\\(60vw\\,14rem\\)\\]');
            return width >= 768 || !!mobileLayout;
        }, width);
        await page.waitForFunction(() => window.appearanceFixture && document.querySelector('header'));
        if (width < 768) {
            await page.getByRole('button', { name: '打开菜单', exact: true }).click();
            await page.getByRole('button', { name: '关闭菜单', exact: true }).first().waitFor();
        }
        if (output) await page.screenshot({ path: `${output}/before-settings-${width}.png` });
        await page.getByRole('button', { name: '自定义主题', exact: true }).click();
        await dialog.getByRole('button', { name: '手动配色', exact: true }).click();
        await dialog.getByRole('button', { name: '湖水青', exact: true }).click();
        const boxes = await dialog.evaluate(el => {
            const rect = el.getBoundingClientRect();
            return { left: rect.left, right: rect.right, top: rect.top, bottom: rect.bottom, sw: el.scrollWidth, cw: el.clientWidth,
                footer: el.querySelector('footer').getBoundingClientRect().toJSON(), buttons: [...el.querySelectorAll('button')].filter(b => b.getBoundingClientRect().right > rect.right + 1).length };
        });
        assert.ok(boxes.left >= 0 && boxes.right <= width && boxes.top >= 0 && boxes.bottom <= 850, JSON.stringify(boxes));
        assert.ok(boxes.sw <= boxes.cw + 1); assert.equal(boxes.buttons, 0);
        assert.ok(boxes.footer.bottom <= 850 && boxes.footer.height >= 44, 'footer actions remain accessible');
        for (let i = 0; i < 25; i++) { await page.keyboard.press('Tab'); assert.ok(await dialog.evaluate(el => el.contains(document.activeElement))); }
        if (output) await page.screenshot({ path: `${output}/settings-${width}.png` });
        await dialog.getByRole('button', { name: '保存主题', exact: true }).click();
        if (width < 768) {
            await page.getByRole('button', { name: '关闭菜单', exact: true }).last().click();
            await page.locator('#mobile-menu').waitFor({ state: 'hidden' });
        }
        if (output) await page.screenshot({ path: `${output}/room-${width}.png` });
        assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false);
    }
    // Lite mode still has a discoverable appearance launcher.
    await page.evaluate(() => { window.appearanceFixture.ui.isLiteMode = true; });
    await page.getByRole('button', { name: '自定义主题', exact: true }).click();
    await dialog.getByRole('button', { name: '恢复默认', exact: true }).click();
    await dialog.getByRole('button', { name: '取消', exact: true }).click();
    assert.equal((await pref()).accent, '#0891b2', 'cancelled reset must retain last saved theme');
    await page.evaluate(() => { window.appearanceFixture.ui.isLiteMode = false; });
    await page.setViewportSize({ width: 1200, height: 850 });
    // All modal surfaces read the same tokens, including admin and search.
    await page.getByRole('button', { name: '添加歌曲', exact: true }).click();
    const searchDialog = page.getByRole('dialog', { name: '添加歌曲', exact: true });
    await searchDialog.getByRole('tab', { name: '网易云', exact: true }).click();
    await searchDialog.getByRole('textbox', { name: '搜索音乐', exact: true }).fill('test');
    await searchDialog.getByRole('textbox', { name: '搜索音乐', exact: true }).press('Enter');
    await searchDialog.getByText('Test Song', { exact: true }).waitFor();
    if (output) await page.screenshot({ path: `${output}/search-1200.png` });
    await searchDialog.getByRole('button', { name: '关闭搜索', exact: true }).click();
    await page.evaluate(() => { window.appearanceFixture.admin.showDashboard = true; });
    await page.getByRole('dialog', { name: '管理终端', exact: true }).waitFor();
    await page.getByRole('dialog', { name: '管理终端', exact: true }).evaluate(async el => {
        await Promise.all(el.parentElement.getAnimations().map(animation => animation.finished.catch(() => {})));
    });
    await page.waitForFunction(() => {
        const el = document.querySelector('#admin-dashboard-title')?.closest('[role="dialog"]')?.parentElement;
        return el && Number(getComputedStyle(el).opacity) === 1;
    });
    if (output) await page.screenshot({ path: `${output}/admin-1200.png` });
    await page.evaluate(() => { const f = window.appearanceFixture; f.admin.showDashboard = false; f.chat.isOpen = true; });
    await page.getByRole('dialog', { name: '管理终端', exact: true }).waitFor({ state: 'hidden' });
    if (output) await page.screenshot({ path: `${output}/chat-1200.png` });
    await page.evaluate(() => { const f = window.appearanceFixture; f.chat.isOpen = false; f.room.entered = false; });
    await page.getByRole('button', { name: '自定义主题', exact: true }).click();
    await dialog.getByRole('button', { name: '浅色', exact: true }).click();
    await dialog.getByRole('button', { name: '保存主题', exact: true }).click();
    for (const width of [390, 1200]) {
        await page.setViewportSize({ width, height: 850 });
        if (output) await page.screenshot({ path: `${output}/landing-light-${width}.png` });
    }
    // The synchronous head cache restores the saved palette before app/bootstrap modules execute.
    const cachedAccent = await accent();
    await page.route('**/src/main.js', route => route.abort());
    await page.route('**/src/themeBootstrap.js', route => route.abort());
    await page.goto(origin);
    assert.equal(await accent(), cachedAccent);
    assert.deepEqual(errors, []);
    console.log('PASS appearance: preview/cancel/save/reload, cross-tab, import/export, system, Monet, keyboard, 4 widths, room/lite');
    await context.close();
} finally { await browser?.close(); await server.close(); }
