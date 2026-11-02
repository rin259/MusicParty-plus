import test from 'node:test';
import assert from 'node:assert/strict';
import { socketService } from './socket.js';

const fakeClient = (active) => ({
    active,
    activateCalls: 0,
    deactivateCalls: 0,
    activate() {
        this.activateCalls++;
        this.active = true;
    },
    async deactivate() {
        this.deactivateCalls++;
        this.active = false;
    }
});

const withClient = async (client, run) => {
    const previous = socketService.client;
    const previousPromise = socketService.reconnectPromise;
    socketService.client = client;
    socketService.reconnectPromise = null;
    try {
        await run();
    } finally {
        socketService.client = previous;
        socketService.reconnectPromise = previousPromise;
    }
};

test('does not rebuild an active or auto-reconnecting client', async () => {
    await withClient(fakeClient(true), async () => {
        socketService.forceReconnect();
        socketService.forceReconnect();
        socketService.forceReconnect();
        assert.equal(socketService.client.deactivateCalls, 0);
        assert.equal(socketService.client.activateCalls, 0);
    });
});

test('activates an inactive client once across repeated health checks', async () => {
    await withClient(fakeClient(false), async () => {
        const first = socketService.forceReconnect();
        const second = socketService.forceReconnect();
        const third = socketService.forceReconnect();
        await Promise.all([first, second, third]);
        assert.equal(socketService.client.deactivateCalls, 0);
        assert.equal(socketService.client.activateCalls, 1);
    });
});

test('explicit force rebuild still replaces an active connection once', async () => {
    await withClient(fakeClient(true), async () => {
        await socketService.forceReconnect(true);
        assert.equal(socketService.client.deactivateCalls, 1);
        assert.equal(socketService.client.activateCalls, 1);
    });
});

test('a stale reconnect promise cannot activate a replacement client', async () => {
    const original = fakeClient(false);
    const replacement = fakeClient(false);
    await withClient(original, async () => {
        const reconnect = socketService.forceReconnect();
        socketService.client = replacement;
        await reconnect;
        assert.equal(original.activateCalls, 0);
        assert.equal(replacement.activateCalls, 0);
    });
});
