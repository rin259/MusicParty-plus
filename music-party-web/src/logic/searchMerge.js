/**
 * 跨源搜索结果归并：纯函数，便于单测。
 *
 * 设计要点
 *  - 去重键 = 归一化标题 + 主艺人；标题只剥「视频噪声」（official video / lyrics / 高清…），
 *    不剥 live / remix / acoustic，否则会把现场版与录音室版误并。
 *  - 排序 = 文本匹配度 + 时长/封面可信度 + 源权重。源权重不是「谁更好」，
 *    而是「中文房间里默认更容易播出」：网易云 > B站 > SoundCloud > YouTube。
 *  - 每个归并项保留全部来源，UI 才能显示「+2 源」，也便于日后切换播放源。
 */

export const PLATFORMS = ['netease', 'bilibili', 'soundcloud', 'ytmusic'];

export const PLATFORM_LABEL = {
    netease: 'NETEASE',
    bilibili: 'BILIBILI',
    soundcloud: 'SOUNDCLOUD',
    ytmusic: 'YOUTUBE'
};

const PLATFORM_WEIGHT = { netease: 30, bilibili: 20, soundcloud: 15, ytmusic: 5 };

const BRACKET_NOISE = /official|lyrics?|video|audio|\bmv\b|\bm\/v\b|\bhd\b|4k|8k|visuali[sz]er|高清|官方|完整版|字幕|纯享|无损|音源/i;
const NOISE_WORDS = /\b(official|lyrics?|video|audio|visuali[sz]er|remaster(ed)?|hq|hd|4k|8k)\b/gi;

export const primaryArtist = (song) => (song?.artists?.[0] || '').trim();

export function stripBrackets(text) {
    return String(text || '')
        .replace(/[（(【\[〈《]([^）)】\]〉》]*)[）)】\]〉》]/g, (whole, inner) =>
            BRACKET_NOISE.test(inner) ? ' ' : whole);
}

export function normalizeText(text) {
    return String(text || '')
        .normalize('NFKC')
        .toLowerCase()
        .replace(/[’'`´]/g, '')
        .replace(/[^\p{L}\p{N}]+/gu, ' ')
        .replace(/\s+/g, ' ')
        .trim();
}

export function normalizeTitle(title, artist = '') {
    let t = stripBrackets(title).normalize('NFKC').trim();
    const artistRaw = String(artist || '').trim();

    if (artistRaw) {
        // 在去标点之前剥「艺人 - 」前缀，否则连字符已消失，无法匹配。
        const prefix = new RegExp(`^\\s*${escapeRegExp(artistRaw)}\\s*[-–—:|：]\\s*`, 'i');
        t = t.replace(prefix, '');
    }

    let out = normalizeText(t).replace(NOISE_WORDS, ' ');
    out = normalizeText(out);

    // 兜底：艺人写法带标点差异（AC/DC → ac dc）时，按归一化结果再剥一次。
    const artistNorm = normalizeText(artistRaw);
    if (artistNorm && out.startsWith(`${artistNorm} `)) {
        out = out.slice(artistNorm.length + 1);
    }
    return out;
}

function escapeRegExp(value) {
    return value.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
}

export function dedupeKey(song) {
    return `${normalizeTitle(song?.name, primaryArtist(song))}|${normalizeText(primaryArtist(song))}`;
}

export function scoreSong(song, query, platform) {
    const q = normalizeText(query);
    const artist = normalizeText(primaryArtist(song));
    const title = normalizeTitle(song?.name, primaryArtist(song));
    let score = PLATFORM_WEIGHT[platform] ?? 0;

    if (q) {
        if (title === q) score += 100;
        else if (title.startsWith(q)) score += 45;
        else if (title.includes(q)) score += 25;
        if (artist.includes(q)) score += 30;
    }
    if (song?.duration > 0) score += 6;
    if (song?.duration >= 30_000) score += 3;
    if (song?.coverUrl) score += 2;
    return score;
}

/**
 * @param {Array<{platform: string, songs: Array}>} batches
 * @param {string} query
 * @param {{limit?: number}} options
 */
export function mergeResults(batches, query, { limit = 80 } = {}) {
    const groups = new Map();

    for (const batch of batches || []) {
        const platform = batch?.platform;
        for (const song of batch?.songs || []) {
            if (!song?.id || !song?.name) continue;
            const key = dedupeKey(song);
            if (!key.trim() || key.startsWith('|')) continue;
            const score = scoreSong(song, query, platform);
            const existing = groups.get(key);
            if (!existing) {
                groups.set(key, {
                    key,
                    song,
                    platform,
                    score,
                    sources: [{ platform, id: String(song.id) }]
                });
                continue;
            }
            if (!existing.sources.some((s) => s.platform === platform)) {
                existing.sources.push({ platform, id: String(song.id) });
            }
            if (score > existing.score) {
                existing.song = song;
                existing.platform = platform;
                existing.score = score;
            }
        }
    }

    return Array.from(groups.values())
        .sort((a, b) => b.score - a.score
            || PLATFORMS.indexOf(a.platform) - PLATFORMS.indexOf(b.platform)
            || a.key.localeCompare(b.key))
        .slice(0, limit)
        .map((group) => ({
            ...group.song,
            platform: group.platform,
            _key: group.key,
            _sources: group.sources,
            _score: group.score
        }));
}

/** 只有一个来源时，保留原始平台字段；多来源时以最佳来源为准。 */
export function songKey(song) {
    return `${song?.platform}:${song?.id}`;
}

export function formatDuration(ms) {
    const total = Math.max(0, Math.round(Number(ms) / 1000));
    const minutes = Math.floor(total / 60);
    const seconds = total % 60;
    return `${minutes}:${String(seconds).padStart(2, '0')}`;
}

/** 是否是「链接 / BV 号」而不是关键词。 */
export function looksLikeLink(raw) {
    const value = String(raw || '').trim();
    if (!value) return false;
    if (/^https?:\/\//i.test(value)) return true;
    return /\bBV[0-9A-Za-z]{10}\b/.test(value);
}
