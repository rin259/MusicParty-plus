/**
 * XHR 的 header 值只能是 ISO-8859-1 的单字节字符。
 * 用户名里带中文/emoji 时直接塞进 setRequestHeader 会抛：
 *   Failed to execute 'setRequestHeader' on 'XMLHttpRequest':
 *   String contains non ISO-8859-1 code point.
 * 整个请求（例如 landing 的房间列表）会直接失败，页面就只剩报错。
 *
 * 所以凡是把用户内容放进 header，都先 percent-encode；服务端 decode 回来。
 */
export const headerValue = (value) => {
    if (value == null) return '';
    try {
        return encodeURIComponent(String(value));
    } catch {
        // 孤立代理项（"\\uD800" 之类）会让 encodeURIComponent 抛错。
        // 宁可这个 header 退化成空串，也不要把请求整个炸掉。
        return '';
    }
};