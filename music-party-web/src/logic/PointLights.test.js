import test from 'node:test';
import assert from 'node:assert/strict';
import { pointLightColor } from './PointLights.js';

test('orange and theme endpoints preserve all RGB channels', () => {
    assert.deepEqual(pointLightColor(0, [212, 218, 228]), [249, 115, 22]);
    assert.deepEqual(pointLightColor(1, [212, 218, 228]), [212, 218, 228]);
    assert.deepEqual(pointLightColor(1, [255, 255, 255]), [255, 255, 255]);
    assert.deepEqual(pointLightColor(0, [255, 255, 255], [37, 99, 235]), [37, 99, 235]);
    assert.deepEqual(pointLightColor(1, [255, 255, 255], [37, 99, 235]), [255, 255, 255]);
});
