import client from './client';
import { STORAGE_KEYS } from '../constants/keys';
import { headerValue } from '../logic/roomHeaders.js';

const deviceHeaders = () => ({
    'X-Device-Id': localStorage.getItem(STORAGE_KEYS.TOKEN) || '',
    'X-User-Name': headerValue(localStorage.getItem(STORAGE_KEYS.USERNAME))
});

const mailPath = (id) => `/api/rooms/${encodeURIComponent(id)}/mail`;

export const roomMailApi = {
    status: (id) => client.get(mailPath(id)),
    subscribe: (id, email, password) => client.post(`${mailPath(id)}/subscribe`, { email, password }, { timeout: 20000 }),
    unsubscribeByEmail: (id, email, password) => client.delete(`${mailPath(id)}/subscription`, { data: { email, password } }),
    summon: (id, password) => client.post(`${mailPath(id)}/summon`, { password }),
    confirm: (id, token) => client.post(`${mailPath(id)}/confirm`, { token }),
    unsubscribe: (id, token) => client.post(`${mailPath(id)}/unsubscribe`, { token })
};

export const roomsApi = {
    mine: () => client.get('/api/rooms/mine', { headers: deviceHeaders() }),
    list: () => client.get('/api/rooms', { headers: deviceHeaders() }),
    info: (id) => client.get(`/api/rooms/${encodeURIComponent(id)}`, { headers: deviceHeaders() }),
    create: (payload) => client.post('/api/rooms', payload, { headers: deviceHeaders() }),
    update: (id, payload) => client.patch(`/api/rooms/${encodeURIComponent(id)}`, payload, { headers: deviceHeaders() }),
    remove: (id) => client.delete(`/api/rooms/${encodeURIComponent(id)}`, { headers: deviceHeaders() }),
    verify: (id, password) => client.post(`/api/rooms/${encodeURIComponent(id)}/verify`, { password })
};
