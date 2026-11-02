/*
 * Netease 解析自愈补丁（容器内，uid=node；无需 root、不改 /etc/hosts、不改镜像）。
 *
 * 复发史与根因（2026-10-31 第 2 次）：
 *   v1 修法「指定国内 DNS」假设 DNS 会给出可连 IP；
 *   v2 修法「extra_hosts 钉 IP」假设钉的 IP 永远有效，且被库的 dns.resolve4（c-ares）绕过。
 *   本机出口 IP 变为境外后，CDN 边缘（118.x / 111.124.x）直接 RST，两个假设同时失效。
 *   更隐蔽的是：ncm-api 启动时要先注册 xeapi 公钥，该请求若失败（ECONNREFUSED），
 *   xeapi 配置即缺失，此后 /song/url/v1 永远 404 —— 所以**首轮探活必须在应用启动前完成**。
 *
 * 本版做法：
 *   0) 启动时**同步**完成首轮解析与探活（阻塞 require，随后才加载 app.js）；
 *   1) 候选 = 网易自有 IDC 种子 ∪ 当前系统解析结果（getent）；
 *   2) 每个候选 TCP:443 探活，只保留能连的；
 *   3) 覆盖 dns.lookup / resolve4 / resolve6 / promises.* / Resolver.prototype，
 *      使「遵循 /etc/hosts」与「c-ares 直查」两条路径都被接管；
 *   4) 每 5 分钟异步复检，IP 轮换后自动跟随；全部不可连则回退系统解析（如实失败）。
 *
 * 生效：docker-compose 里 NODE_OPTIONS="--require /opt/netfix.js"。
 */
const dns = require('dns');
const net = require('net');
const { execFileSync } = require('child_process');

const HOSTS = [
  'music.163.com',
  'interface.music.163.com',
  'interface3.music.163.com',
  'interfacepc.music.163.com',
  'clientlog.music.163.com',
  'clientlog3.music.163.com'
];

// 种子：网易自有 IDC 段（仅候选，仍须探活通过才会使用）
const SEED = ['59.111.181.60', '59.111.181.38', '59.111.181.35'];

const REFRESH_MS = 5 * 60 * 1000;
const RESOLVED = new Map(); // host -> [ip]

const originalLookup = dns.lookup.bind(dns);
const originalResolve4 = dns.resolve4.bind(dns);
const originalResolve6 = dns.resolve6.bind(dns);
const originalPromisesLookup = dns.promises ? dns.promises.lookup.bind(dns.promises) : null;
const originalPromisesResolve4 = dns.promises ? dns.promises.resolve4.bind(dns.promises) : null;

/** 同步探活：nc -z（已在本镜像验证：可连 rc=0，拒连 rc=1）。 */
const probeSync = (ip) => {
  try {
    execFileSync('nc', ['-z', '-w', '2', ip, '443'], { stdio: 'ignore', timeout: 3000 });
    return true;
  } catch {
    return false;
  }
};

const systemResolveSync = (host) => {
  try {
    const output = execFileSync('getent', ['ahostsv4', host], { encoding: 'utf8', timeout: 3000 });
    return output.split('\n')
      .map((line) => line.trim().split(/\s+/)[0])
      .filter((ip) => /^\d+\.\d+\.\d+\.\d+$/.test(ip));
  } catch {
    return [];
  }
};

const probeAsync = (ip) => new Promise((resolve) => {
  const socket = net.connect({ host: ip, port: 443, timeout: 2500 });
  let settled = false;
  const finish = (ok) => {
    if (settled) return;
    settled = true;
    socket.destroy();
    resolve(ok);
  };
  socket.once('connect', () => finish(true));
  socket.once('timeout', () => finish(false));
  socket.once('error', () => finish(false));
});

/** 首轮：同步完成，确保 app.js 加载前 RESOLVED 已就绪（xeapi 公钥那步不能失败）。 */
function bootstrap() {
  const cache = new Map(); // ip -> reachable
  const describe = [];

  for (const host of HOSTS) {
    const candidates = new Set(SEED);
    for (const ip of systemResolveSync(host)) candidates.add(ip);

    const working = [];
    for (const ip of candidates) {
      if (!cache.has(ip)) cache.set(ip, probeSync(ip));
      if (cache.get(ip)) working.push(ip);
    }

    if (working.length > 0) {
      RESOLVED.set(host, working);
      describe.push(`${host}=${working.join(',')}`);
    } else {
      describe.push(`${host}=UNREACHABLE(fallback)`);
    }
  }

  console.log(`[netfix] bootstrap ${describe.join(' | ')}`);
}

async function refresh() {
  await Promise.all(HOSTS.map(async (host) => {
    const candidates = new Set(SEED);
    for (const ip of systemResolveSync(host)) candidates.add(ip);

    const working = [];
    await Promise.all(Array.from(candidates, async (ip) => {
      if (await probeAsync(ip)) working.push(ip);
    }));

    if (working.length > 0) RESOLVED.set(host, working);
    else RESOLVED.delete(host);
  }));

  const summary = Array.from(RESOLVED, ([host, ips]) => `${host}=${ips.join(',')}`).join(' | ');
  console.log(`[netfix] refresh ${summary || '(none reachable, fallback to system dns)'}`);
}

bootstrap();
setInterval(() => refresh().catch(() => {}), REFRESH_MS).unref?.();

const normalizeLookupArgs = (options, callback) => {
  if (typeof options === 'function') return [{}, options];
  return [options || {}, callback];
};

dns.lookup = function lookup(hostname, options, callback) {
  const [opts, cb] = normalizeLookupArgs(options, callback);
  const ips = RESOLVED.get(hostname);
  if (!ips || !ips.length || opts.family === 6) return originalLookup(hostname, opts, cb);
  if (opts.all) process.nextTick(() => cb(null, ips.map((address) => ({ address, family: 4 }))));
  else process.nextTick(() => cb(null, ips[0], 4));
};

dns.resolve4 = function resolve4(hostname, options, callback) {
  const [opts, cb] = normalizeLookupArgs(options, callback);
  const ips = RESOLVED.get(hostname);
  if (!ips || !ips.length) return originalResolve4(hostname, opts, cb);
  process.nextTick(() => cb(null, ips.slice()));
};

dns.resolve6 = function resolve6(hostname, options, callback) {
  const [opts, cb] = normalizeLookupArgs(options, callback);
  const ips = RESOLVED.get(hostname);
  if (!ips || !ips.length) return originalResolve6(hostname, opts, cb);
  process.nextTick(() => cb(null, []));
};

// 有些库用 new dns.Resolver()（c-ares 实例，同样绕过 /etc/hosts）
if (dns.Resolver) {
  const originalResolverResolve4 = dns.Resolver.prototype.resolve4;
  const originalResolverResolve6 = dns.Resolver.prototype.resolve6;
  dns.Resolver.prototype.resolve4 = function resolve4(hostname, options, callback) {
    const [opts, cb] = normalizeLookupArgs(options, callback);
    const ips = RESOLVED.get(hostname);
    if (!ips || !ips.length) return originalResolverResolve4.call(this, hostname, opts, cb);
    process.nextTick(() => cb(null, ips.slice()));
  };
  dns.Resolver.prototype.resolve6 = function resolve6(hostname, options, callback) {
    const [opts, cb] = normalizeLookupArgs(options, callback);
    const ips = RESOLVED.get(hostname);
    if (!ips || !ips.length) return originalResolverResolve6.call(this, hostname, opts, cb);
    process.nextTick(() => cb(null, []));
  };
}

if (dns.promises) {
  dns.promises.lookup = async (hostname, options = {}) => {
    const ips = RESOLVED.get(hostname);
    if (!ips || !ips.length) return originalPromisesLookup(hostname, options);
    if (options.all) return ips.map((address) => ({ address, family: 4 }));
    return { address: ips[0], family: 4 };
  };
  dns.promises.resolve4 = async (hostname) => {
    const ips = RESOLVED.get(hostname);
    return ips && ips.length ? ips.slice() : originalPromisesResolve4(hostname);
  };
}
