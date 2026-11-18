import test from 'node:test';
import assert from 'node:assert/strict';
import { isInWallpaperBand, normalizeWallpaperRoom, normalizeWallpaperSettings } from './wallpaper.js';

test('dead zone offsets the reaction band and does not add to its width', () => {
    const nearTop = distance => isInWallpaperBand(distance, 800, 48, 15);
    assert.equal(nearTop(0), false);
    assert.equal(nearTop(47), false);
    assert.equal(nearTop(48), true);
    assert.equal(nearTop(62), true);
    assert.equal(nearTop(63), false);
});

test('opposite reaction bands stay separate on a small viewport', () => {
    assert.equal(isInWallpaperBand(89, 180, 240, 240), true);
    assert.equal(isInWallpaperBand(90, 180, 240, 240), false);
});

test('dead zones and reaction distances link independently and survive saved settings', () => {
    const settings = normalizeWallpaperSettings({
        deadZone: { linked: false, edges: { top: 48, right: -5, bottom: 250 } },
        reaction: { linked: true, edges: { top: 15, right: 90 } }
    });
    assert.deepEqual(settings.deadZone.edges, { top: 48, right: 0, bottom: 240, left: 0 });
    assert.deepEqual(settings.reaction.edges, { top: 15, right: 15, bottom: 15, left: 15 });
    assert.deepEqual(normalizeWallpaperSettings(JSON.parse(JSON.stringify(settings))), settings);
    assert.equal(normalizeWallpaperSettings().reaction.edges.bottom, 24);
});

test('wallpaper launch remembers the selected code separately from whether the room was entered', () => {
    assert.deepEqual(normalizeWallpaperRoom({ roomId: ' MAIN ', entered: true }), { roomId: 'main', entered: true });
    assert.deepEqual(normalizeWallpaperRoom({ roomId: 'main', entered: false }), { roomId: 'main', entered: false });
    assert.deepEqual(normalizeWallpaperRoom({ roomId: ' ', entered: true }), { roomId: '', entered: false });
    assert.deepEqual(normalizeWallpaperRoom(), { roomId: '', entered: false });
});
