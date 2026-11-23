#!/usr/bin/env node
// Çalışan uygulamanın (gerçek backend + yüklenmiş veri) ekran görüntülerini alır.
// Bağımlılık yok: Node 22 yerleşik fetch/WebSocket + headless Chrome (DevTools Protocol).
//
// Kullanım: node scripts/screenshots.mjs   (ortam: BASE=http://127.0.0.1:8080, CHROME=google-chrome)
// Çıktı:    docs/kanit/ekran/*.png
import { spawn } from 'node:child_process';
import { mkdirSync, mkdtempSync, writeFileSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, dirname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { randomUUID } from 'node:crypto';

const ROOT = join(dirname(fileURLToPath(import.meta.url)), '..');
const OUT = join(ROOT, 'docs/kanit/ekran');
const BASE = process.env.BASE ?? 'http://127.0.0.1:8080';
const CHROME = process.env.CHROME ?? 'google-chrome';
const PORT = 9333;
mkdirSync(OUT, { recursive: true });

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function api(method, path, token, body) {
  const res = await fetch(BASE + path, {
    method,
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}) },
    body: body ? JSON.stringify(body) : undefined,
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`${method} ${path} -> ${res.status} ${text}`);
  return text ? JSON.parse(text) : null;
}

const login = (email, password) => api('POST', '/api/auth/login', null, { email, password });

// ---------------------------------------------------------------- Chrome / CDP
const profile = mkdtempSync(join(tmpdir(), 'oyunport-chrome-'));
const chrome = spawn(CHROME, ['--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
  `--user-data-dir=${profile}`, `--remote-debugging-port=${PORT}`, '--window-size=1366,900', 'about:blank'],
  { stdio: 'ignore' });

let page;
for (let i = 0; i < 50 && !page; i++) {
  await sleep(200);
  try {
    page = (await (await fetch(`http://127.0.0.1:${PORT}/json/list`)).json()).find((t) => t.type === 'page');
  } catch { /* henüz hazır değil */ }
}
if (!page) throw new Error('Chrome başlatılamadı');

const ws = new WebSocket(page.webSocketDebuggerUrl);
await new Promise((r) => ws.addEventListener('open', r, { once: true }));
let seq = 0;
const pending = new Map();
ws.addEventListener('message', (e) => {
  const m = JSON.parse(e.data);
  if (m.id && pending.has(m.id)) {
    const { res, rej } = pending.get(m.id);
    pending.delete(m.id);
    m.error ? rej(new Error(JSON.stringify(m.error))) : res(m.result);
  }
});
const send = (method, params = {}) => new Promise((res, rej) => {
  const id = ++seq;
  pending.set(id, { res, rej });
  ws.send(JSON.stringify({ id, method, params }));
});
const evaluate = async (expression) =>
  (await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true })).result.value;

await send('Page.enable');
await send('Runtime.enable');

let width = 1366;
async function viewport(w, h = 900) {
  width = w;
  await send('Emulation.setDeviceMetricsOverride', { width: w, height: h, deviceScaleFactor: 1, mobile: w < 600 });
}

async function settle() {
  for (let i = 0; i < 80; i++) {
    const busy = await evaluate(`document.readyState !== 'complete' || /yükleniyor|alınıyor|Yükleniyor/.test(document.body.innerText)`);
    if (!busy) break;
    await sleep(150);
  }
  await sleep(500);
}

async function go(path) {
  await send('Page.navigate', { url: BASE + path });
  await sleep(300);
  await settle();
}

async function shot(name) {
  const h = await evaluate('Math.min(Math.ceil(document.documentElement.scrollHeight), 3200)');
  await send('Emulation.setDeviceMetricsOverride', { width, height: h, deviceScaleFactor: 1, mobile: width < 600 });
  const { data } = await send('Page.captureScreenshot', { format: 'png' });
  writeFileSync(join(OUT, `${name}.png`), Buffer.from(data, 'base64'));
  await viewport(width);
  console.log(`ekran: ${name}.png`);
}

async function setSession(auth, cart) {
  await go('/giris');
  await evaluate(`localStorage.clear(); localStorage.setItem('oyunport.session', ${JSON.stringify(JSON.stringify(
    { token: auth.token, expiresAt: auth.expiresAt, user: auth.user }))});
    ${cart ? `localStorage.setItem('oyunport.cart.u${auth.user.userId}', ${JSON.stringify(JSON.stringify(cart))});` : ''} true`);
}

try {
  await viewport(1366);

  // -------------------------------------------------- müşteri (demo@oyunport.test)
  const demo = await login('demo@oyunport.test', 'Oyun123!');
  const lib = await api('GET', '/api/library', demo.token);
  const owned = new Set(lib.map((l) => l.productId));
  // Detay sayfası: DLC'si ve yorumu olan popüler bir ana oyun
  const popular = await api('GET', '/api/catalog/products?type=BASE_GAME&sort=rating_desc&size=100', demo.token);
  let showcase = null;
  if (process.env.SHOWCASE_ID) {
    showcase = await api('GET', `/api/catalog/products/${process.env.SHOWCASE_ID}`, demo.token);
  }
  for (const p of popular.items) {
    if (showcase) break;
    const d = await api('GET', `/api/catalog/products/${p.productId}`, demo.token);
    if (d.dlcs.length >= 1) showcase = d;
  }
  // Sepet: sahip olunmayan bir ana oyun + DLC'si (BR05: aynı sepette geçerli)
  let cartBase = null;
  for (const p of popular.items) {
    if (owned.has(p.productId)) continue;
    const d = await api('GET', `/api/catalog/products/${p.productId}`, demo.token);
    if (d.active && d.dlcs.some((x) => x.active && !x.owned)) { cartBase = d; break; }
  }
  const cart = { productIds: [cartBase.productId, cartBase.dlcs.find((x) => x.active).productId], checkoutKey: randomUUID() };

  await setSession(demo, cart);
  await go('/');                         await shot('01_magaza_katalog');
  await go(`/urun/${showcase.productId}`);     await shot('02_urun_detay_ana_oyun');
  await go(`/urun/${showcase.dlcs[0].productId}`); await shot('03_urun_detay_dlc');
  await go('/sepet');                          await shot('04_sepet_dogrulama');

  // Başarısız ödeme: T1 API ile, ödeme arayüzden (form gerçekten kullanılır)
  const t1 = await api('POST', '/api/orders', demo.token, { checkoutKey: randomUUID(), productIds: [cartBase.productId] });
  await go(`/siparisler/${t1.order.orderId}`);
  await evaluate(`(() => {
    const sel = [...document.querySelectorAll('select')].find(s => [...s.options].some(o => o.value === 'CARD_DECLINED'));
    Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'value').set.call(sel, 'CARD_DECLINED');
    sel.dispatchEvent(new Event('change', { bubbles: true }));
    [...document.querySelectorAll('button')].find(b => b.textContent.trim().startsWith('Öde')).click();
    return true; })()`);
  await sleep(800); await settle();
  await shot('05_odeme_basarisiz_failed');
  await evaluate(`(() => {
    const sel = [...document.querySelectorAll('select')].find(s => [...s.options].some(o => o.value === 'SUCCESS'));
    Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype, 'value').set.call(sel, 'SUCCESS');
    sel.dispatchEvent(new Event('change', { bubbles: true }));
    [...document.querySelectorAll('button')].find(b => b.textContent.trim().startsWith('Öde')).click();
    return true; })()`);
  await sleep(800); await settle();
  await shot('06_odeme_yeniden_deneme_basarili');

  await go('/siparisler');                     await shot('07_siparislerim');
  await go('/kutuphane');                      await shot('08_kutuphanem');
  await go('/istek-listesi');                  await shot('09_istek_listem');
  await go('/yorumlarim');                     await shot('10_yorumlarim');
  await go('/profil');                         await shot('11_profil');
  await go('/admin/raporlar');                 await shot('12_musteri_admin_yetkisiz');

  // -------------------------------------------------- admin
  const admin = await login('admin@oyunport.local', 'Admin123!');
  await setSession(admin);
  await go('/admin/katalog');                  await shot('13_admin_katalog');
  await go('/admin/raporlar?rapor=q1');        await shot('14_admin_rapor_q1');
  await go('/admin/raporlar?rapor=q5');
  await evaluate(`[...document.querySelectorAll('button')].find(b => b.textContent.includes('EXPLAIN')).click(), true`);
  await sleep(1500); await settle();
  await shot('15_admin_rapor_q5_explain');

  // -------------------------------------------------- mobil genişlik
  await viewport(375, 800);
  await setSession(demo);
  await go('/');                         await shot('16_magaza_mobil_375px');
} finally {
  ws.close();
  chrome.kill('SIGTERM');
  await sleep(300);
  rmSync(profile, { recursive: true, force: true });
}
