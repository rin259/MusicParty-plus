import { QuantizerCelebi, Score, argbFromRgb, hexFromArgb } from '@material/material-color-utilities';

export function seedFromPixels(pixels) {
    const colors = [];
    for (let i = 0; i + 3 < pixels.length; i += 4) {
        if (pixels[i + 3] < 200) continue;
        colors.push(argbFromRgb(pixels[i], pixels[i + 1], pixels[i + 2]));
    }
    if (!colors.length) return null;
    const ranked = Score.score(QuantizerCelebi.quantize(colors, 32), { desired: 1, filter: true, fallbackColorARGB: colors[0] });
    return ranked.length ? hexFromArgb(ranked[0]) : null;
}

// Bounded sampling, anonymous CORS, no proxy or uploads. Failure is a normal fallback.
export function extractCoverColor(url, { ImageCtor = globalThis.Image, documentRef = globalThis.document, signal, timeoutMs = 4000 } = {}) {
    if (!url || !ImageCtor || !documentRef?.createElement || signal?.aborted) return Promise.resolve(null);
    return new Promise(resolve => {
        const image = new ImageCtor();
        let settled = false;
        const finish = value => {
            if (settled) return;
            settled = true;
            clearTimeout(timer);
            signal?.removeEventListener('abort', abort);
            image.onload = image.onerror = null;
            // Cancel old fetch/decode when leaving the room or switching tracks.
            if (!value) image.removeAttribute?.('src');
            resolve(value);
        };
        const abort = () => finish(null);
        const timer = setTimeout(abort, timeoutMs);
        signal?.addEventListener('abort', abort, { once: true });
        image.crossOrigin = 'anonymous';
        image.referrerPolicy = 'no-referrer';
        image.onload = () => {
            try {
                const canvas = documentRef.createElement('canvas');
                canvas.width = canvas.height = 32;
                const ctx = canvas.getContext('2d', { willReadFrequently: true });
                ctx.drawImage(image, 0, 0, 32, 32);
                finish(seedFromPixels(ctx.getImageData(0, 0, 32, 32).data));
            } catch { finish(null); }
        };
        image.onerror = abort;
        image.src = url;
    });
}
