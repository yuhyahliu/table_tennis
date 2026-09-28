// 場邊教練 v0 — coaching engine (no DOM). Works in the browser and in Node for tests.
// World frame: origin = table centre on the floor, X = right as seen from the camera,
// Y = toward the far end, Z = up (metres). Table top z = 0.76, end lines at Y = ±1.37.

export const TABLE = { halfW: 0.7625, halfL: 1.37, h: 0.76 };

// ---------- small linear algebra ----------
function solve(A, b) { // Gaussian elimination, A n×n
  const n = b.length, M = A.map((r, i) => [...r, b[i]]);
  for (let c = 0; c < n; c++) {
    let p = c; for (let r = c + 1; r < n; r++) if (Math.abs(M[r][c]) > Math.abs(M[p][c])) p = r;
    [M[c], M[p]] = [M[p], M[c]];
    if (Math.abs(M[c][c]) < 1e-12) return null;
    for (let r = 0; r < n; r++) if (r !== c) { const f = M[r][c] / M[c][c]; for (let k = c; k <= n; k++) M[r][k] -= f * M[c][k]; }
  }
  return M.map((r, i) => r[n] / r[i]);
}
const dot = (a, b) => a[0] * b[0] + a[1] * b[1] + a[2] * b[2];
const cross = (a, b) => [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]];
const norm = a => Math.hypot(a[0], a[1], a[2]);
const scale = (a, s) => [a[0] * s, a[1] * s, a[2] * s];
const sub = (a, b) => [a[0] - b[0], a[1] - b[1], a[2] - b[2]];
const add = (a, b) => [a[0] + b[0], a[1] + b[1], a[2] + b[2]];

// Homography world-plane (X,Y) -> image (u,v) from 4 points
function homography(world, img) {
  const A = [], b = [];
  for (let i = 0; i < 4; i++) {
    const [X, Y] = world[i], [u, v] = img[i];
    A.push([X, Y, 1, 0, 0, 0, -u * X, -u * Y]); b.push(u);
    A.push([0, 0, 0, X, Y, 1, -v * X, -v * Y]); b.push(v);
  }
  const h = solve(A, b); if (!h) return null;
  return [[h[0], h[1], h[2]], [h[3], h[4], h[5]], [h[6], h[7], 1]];
}

/**
 * Calibrate from 4 taps on the table top:
 *   [near-left corner, near-right corner, right sideline at the net, left sideline at the net]
 * (the far half is often hidden behind the net for a low camera, so we use the net line).
 * Returns a camera {f, cx, cy, R (world->cam rows), C (camera centre, world)} or {ok:false,reason}.
 */
export function calibrate(taps, W, H) {
  const { halfW, halfL, h } = TABLE;
  const world = [[-halfW, -halfL], [halfW, -halfL], [halfW, 0], [-halfW, 0]];
  const Hm = homography(world, taps);
  if (!Hm) return { ok: false, reason: '四個點排列不正確' };
  const cx = W / 2, cy = H / 2;
  const col = j => [Hm[0][j] - cx * Hm[2][j], Hm[1][j] - cy * Hm[2][j], Hm[2][j]];
  const h1 = col(0), h2 = col(1), h3 = col(2);
  // focal length from the two rotation-column constraints
  const cands = [];
  const d1 = h1[2] * h2[2]; if (Math.abs(d1) > 1e-12) cands.push(-(h1[0] * h2[0] + h1[1] * h2[1]) / d1);
  const d2 = h1[2] ** 2 - h2[2] ** 2; if (Math.abs(d2) > 1e-12) cands.push(-(h1[0] ** 2 + h1[1] ** 2 - h2[0] ** 2 - h2[1] ** 2) / d2);
  const good = cands.filter(f2 => f2 > (0.2 * W) ** 2 && f2 < (4 * W) ** 2);
  let f = good.length ? Math.sqrt(good.reduce((a, b) => a + b) / good.length) : W / 2 / Math.tan(35 * Math.PI / 180);
  const Kinv = v => [v[0] / f, v[1] / f, v[2]];
  let r1 = Kinv(h1), r2 = Kinv(h2), t = Kinv(h3);
  const lam = 2 / (norm(r1) + norm(r2));
  r1 = scale(r1, lam); r2 = scale(r2, lam); t = scale(t, lam);
  if (t[2] < 0) { r1 = scale(r1, -1); r2 = scale(r2, -1); t = scale(t, -1); } // table in front of the camera
  // orthonormalise [r1 r2]
  const r1n = scale(r1, 1 / norm(r1)); let r2o = sub(r2, scale(r1n, dot(r1n, r2))); r2o = scale(r2o, 1 / norm(r2o));
  const r3 = cross(r1n, r2o);
  // plane coords (X,Y,Z') with Z' = z - 0.76.  cam = R*[X,Y,Z'] + t   (R columns r1,r2,r3)
  const Rcols = [r1n, r2o, r3];
  const R = [0, 1, 2].map(i => [Rcols[0][i], Rcols[1][i], Rcols[2][i]]); // rows
  // camera centre in plane coords: C' = -R^T t
  const Cp = scale([dot(Rcols[0], t), dot(Rcols[1], t), dot(Rcols[2], t)], -1);
  let C = [Cp[0], Cp[1], Cp[2] + h];
  let Rf = R;
  if (C[2] < h) { // mirror solution (camera below table plane) -> flip Z axis
    const r3f = scale(r3, -1); const Rc = [r1n, r2o, r3f];
    Rf = [0, 1, 2].map(i => [Rc[0][i], Rc[1][i], Rc[2][i]]);
    const Cp2 = scale([dot(Rc[0], t), dot(Rc[1], t), dot(Rc[2], t)], -1);
    C = [Cp2[0], Cp2[1], Cp2[2] + h];
  }
  const cam = { f, cx, cy, R: Rf, C, W, H };
  // reprojection error of the taps
  const err = world.reduce((s, [X, Y], i) => { const p = project(cam, [X, Y, h]); return s + Math.hypot(p[0] - taps[i][0], p[1] - taps[i][1]); }, 0) / 4;
  const hfov = 2 * Math.atan(W / 2 / f) * 180 / Math.PI;
  const ok = C[2] > 0.3 && C[2] < 4 && C[1] < -1.5 && hfov > 30 && hfov < 130;
  return { ok, reason: ok ? '' : '算出的手機位置不合理，請重新點選', cam, err, hfov };
}

export function project(cam, P) {
  const d = sub(P, cam.C);
  const c = [dot(cam.R[0], d), dot(cam.R[1], d), dot(cam.R[2], d)];
  return [cam.f * c[0] / c[2] + cam.cx, cam.f * c[1] / c[2] + cam.cy];
}
function ray(cam, u, v) {
  const dc = [(u - cam.cx) / cam.f, (v - cam.cy) / cam.f, 1];
  // world dir = R^T dc
  const d = [cam.R[0][0] * dc[0] + cam.R[1][0] * dc[1] + cam.R[2][0] * dc[2],
             cam.R[0][1] * dc[0] + cam.R[1][1] * dc[1] + cam.R[2][1] * dc[2],
             cam.R[0][2] * dc[0] + cam.R[1][2] * dc[1] + cam.R[2][2] * dc[2]];
  return scale(d, 1 / norm(d));
}
export function toFloor(cam, u, v, z = 0.03) {
  const d = ray(cam, u, v); if (d[2] > -1e-6) return null;
  return add(cam.C, scale(d, (z - cam.C[2]) / d[2]));
}
function heightAt(cam, u, v, G) {
  const d = ray(cam, u, v); let n = sub(G, cam.C); n[2] = 0; n = scale(n, 1 / norm(n));
  const s = dot(sub(G, cam.C), n) / dot(d, n); return cam.C[2] + s * d[2];
}

// ---------- per-frame measurement ----------
// lm: array of 33 {x,y,visibility} in PIXELS
export function measure(cam, lm) {
  const vis = i => (lm[i].visibility ?? 1);
  if (Math.max(vis(29), vis(31)) < 0.2 || Math.max(vis(30), vis(32)) < 0.2) return null; // feet not visible
  const mid = (a, b) => [(lm[a].x + lm[b].x) / 2, (lm[a].y + lm[b].y) / 2];
  const fl = mid(29, 31), fr = mid(30, 32);
  const FL = toFloor(cam, fl[0], fl[1]), FR = toFloor(cam, fr[0], fr[1]);
  if (!FL || !FR) return null;
  const G = [(FL[0] + FR[0]) / 2, (FL[1] + FR[1]) / 2, 0];
  if (!(G[1] < -1.0 && G[1] > -7 && Math.abs(G[0]) < 2.8)) return null; // not the near player
  const nose = heightAt(cam, lm[0].x, lm[0].y, G);
  const width = Math.hypot(FL[0] - FR[0], FL[1] - FR[1]);
  const bodyPx = Math.max(40, Math.max(lm[29].y, lm[30].y) - lm[0].y);
  return { G, FL, FR, nose, width, dist: -G[1] - TABLE.halfL, wrist: [lm[16].x, lm[16].y], wristL: [lm[15].x, lm[15].y], bodyPx };
}

/** pick the pose belonging to the near player (feet on the floor behind the near end line, largest) */
export function pickNearPlayer(cam, poses) {
  let best = null;
  for (const lm of poses) {
    const m = measure(cam, lm); if (!m) continue;
    if (!best || m.bodyPx > best.m.bodyPx) best = { lm, m };
  }
  return best;
}

// ---------- coach: rally detection + cues ----------
const pct = (arr, p) => { if (!arr.length) return NaN; const s = [...arr].sort((a, b) => a - b); return s[Math.min(s.length - 1, Math.floor(p * (s.length - 1)))]; };

export const DEFAULTS = { hand: 'R', targetRatio: 0.90, sideLimit: 0.25, minWidth: 0.40, mode: 'train',
  zoneX: 0.95, zoneY: -2.35, minDist: 0.15, maxDist: 1.3, wristRef: 1.0, tau: 0.4, actOn: 0.7, hotT: 0.5, coldT: 1.2, outT: 0.5 };

export class Coach {
  constructor(opts = {}) {
    this.o = { ...DEFAULTS, ...opts };
    this.noseHist = []; this.state = 'idle'; this.hot = 0; this.cold = 0;
    this.prev = null; this.act = 0; this.out = 0; this.rally = null; this.rallies = []; this.lastCues = []; this.goodStreak = 0; this.game = 0;
  }
  get standing() { const s = pct(this.noseHist, 0.95); return isFinite(s) ? Math.min(2.1, Math.max(0.9, s)) : NaN; }
  /** feed one measurement (or null) at time t (s). Returns {event, cue?} when a rally ends.
   *  A rally = active play inside the ready zone; it ends when the player leaves the zone
   *  (walks to fetch the ball / out of frame) or goes quiet. */
  update(m, t) {
    const o = this.o; let out = null;
    const dt = this.lastT != null ? Math.min(0.25, Math.max(0, t - this.lastT)) : 0; this.lastT = t;
    const inZone = !!m && Math.abs(m.G[0]) < o.zoneX && m.G[1] > o.zoneY;
    if (m) {
      if (inZone) { if (this.noseHist.length < 3000) this.noseHist.push(m.nose); else this.noseHist[(Math.random() * 3000) | 0] = m.nose; }
      if (this.prev && t > this.prev.t && t - this.prev.t < 0.5) {
        const d = t - this.prev.t, p = this.prev.m;
        const w = Math.max(Math.hypot(m.wrist[0] - p.wrist[0], m.wrist[1] - p.wrist[1]),
                           Math.hypot(m.wristL[0] - p.wristL[0], m.wristL[1] - p.wristL[1])) / m.bodyPx / d;
        const a = Math.min(w / o.wristRef, 3);
        const k = 1 - Math.exp(-d / o.tau); this.act += (a - this.act) * k;
      }
      this.prev = { m, t };
    } else this.act *= Math.exp(-dt / o.tau);
    this.out = inZone ? 0 : this.out + dt;
    const active = inZone && this.act > o.actOn;
    if (this.state === 'idle') {
      this.hot = active ? this.hot + dt : 0;
      if (this.hot >= o.hotT) { this.state = 'rally'; this.rally = { t0: t - this.hot, nose: [], x: [], w: [], d: [] }; this.cold = 0; out = { event: 'start' }; }
    } else {
      if (inZone) { this.rally.nose.push(m.nose); this.rally.x.push(o.hand === 'R' ? m.G[0] : -m.G[0]); this.rally.w.push(m.width); this.rally.d.push(m.dist); }
      this.cold = active ? 0 : this.cold + dt;
      const dur = t - this.rally.t0;
      if (this.out >= o.outT || this.cold >= o.coldT || dur > 30) {
        this.state = 'idle'; this.hot = 0;
        const r = this.rally; r.t1 = t - Math.max(this.out, this.cold); r.dur = r.t1 - r.t0;
        if (r.dur >= 1.5 && r.nose.length > 10) { const sum = this.summarise(r); this.rallies.push(sum); out = { event: 'end', rally: sum, cue: this.cue(sum) }; }
        else out = { event: 'drop' };
      }
    }
    this.inZone = inZone;
    return out;
  }
  summarise(r) {
    const st = this.noseHist.length >= 60 ? this.standing : NaN;
    return { t0: r.t0, t1: r.t1, dur: r.dur, ratio: pct(r.nose, 0.5) / st, xMed: pct(r.x, 0.5),
      leftShare: r.x.filter(v => v < -this.o.sideLimit).length / r.x.length, width: pct(r.w, 0.5), dist: pct(r.d, 0.5), won: null, game: this.game };
  }
  cue(s) {
    const o = this.o, issues = [];
    if (s.ratio > o.targetRatio + 0.01) issues.push({ k: 'low', sev: (s.ratio - o.targetRatio) * 20,
      say: ['重心放低，膝蓋彎', '再蹲低一點', '別站直，膝蓋保持彎'] });
    if (s.leftShare > 0.7) issues.push({ k: 'side', sev: s.leftShare * 1.2,
      say: [o.hand === 'R' ? '準備位置往右一點，顧正手' : '準備位置往左一點，顧正手', '回到中間準備', '站位別太偏反手'] });
    if (s.dist > o.maxDist) issues.push({ k: 'far', sev: (s.dist - o.maxDist) * 3, say: ['離桌太遠，往前站', '別退太多，靠近球桌'] });
    if (s.dist < o.minDist) issues.push({ k: 'near', sev: (o.minDist - s.dist) * 6, say: ['離桌太近，退半步', '站遠一點，留出揮拍空間'] });
    if (s.width < o.minWidth) issues.push({ k: 'width', sev: (o.minWidth - s.width) * 8, say: ['兩腳再開一點', '步子站寬一點'] });
    issues.sort((a, b) => b.sev - a.sev);
    // don't repeat the same issue 3 times in a row: rotate to the next one
    let pick = issues[0];
    if (pick && this.lastCues.slice(-2).every(c => c === pick.k) && issues[1]) pick = issues[1];
    if (!pick && !isFinite(s.ratio)) return null; // still learning the player's standing height
    if (!pick) { this.goodStreak++; this.lastCues.push('good'); return this.goodStreak % 2 === 1 ? { k: 'good', text: '很好，保持' } : null; }
    this.goodStreak = 0;
    const n = this.lastCues.filter(c => c === pick.k).length; this.lastCues.push(pick.k);
    return { k: pick.k, text: pick.say[n % pick.say.length] };
  }
  /** the scorer pressed a button: attach the result to the rally that just ended */
  markPoint(meWon, t) {
    const r = [...this.rallies].reverse().find(r => r.won === null);
    if (r && t - r.t1 < 25) { r.won = meWon; return r; }
    return null;
  }
  undoPoint() { const r = [...this.rallies].reverse().find(r => r.won !== null); if (r) r.won = null; }
  newGame() { this.game++; }
  /** between-games notes for the coach: up to 3 short lines, most important first */
  notes(game = this.game) {
    const o = this.o, R = this.rallies.filter(r => r.game === game && isFinite(r.ratio));
    if (R.length < 2) return ['這局資料還不夠，先照原本的打法。'];
    const out = [], P = v => Math.round(v * 100);
    const ratio = pct(R.map(r => r.ratio), 0.5), hi = o.targetRatio + 0.01;
    const L = R.filter(r => r.won !== null), low = L.filter(r => r.ratio <= hi), up = L.filter(r => r.ratio > hi);
    const w = a => a.filter(r => r.won).length;
    if (low.length >= 2 && up.length >= 2 && w(low) / low.length - w(up) / up.length >= 0.2)
      out.push({ sev: 3, t: `蹲低的分贏 ${w(low)}/${low.length}，站直的分只贏 ${w(up)}/${up.length}，重心是這局關鍵` });
    else if (ratio > hi) out.push({ sev: 2 + (ratio - hi) * 20, t: `重心偏高（站直的 ${P(ratio)}%，目標 ${P(o.targetRatio)}%），先提醒蹲低` });
    const first = this.rallies.filter(r => r.game === 0 && isFinite(r.ratio));
    if (game > 0 && first.length >= 3) { const d = ratio - pct(first.map(r => r.ratio), 0.5); if (d > 0.02) out.push({ sev: 2 + d * 20, t: `比第一局站得更直（多 ${P(d)}%），可能累了` }); }
    const left = R.map(r => r.leftShare).reduce((a, b) => a + b) / R.length;
    if (left > 0.6) out.push({ sev: 1 + left, t: `${P(left)}% 時間站在反手側，正手大角容易空出來` });
    const dist = pct(R.map(r => r.dist), 0.5);
    if (dist > o.maxDist) out.push({ sev: 1.5, t: `平均離桌 ${dist.toFixed(1)} 公尺，退太遠` });
    const width = pct(R.map(r => r.width), 0.5);
    if (width < o.minWidth) out.push({ sev: 1.2, t: `步寬只有 ${Math.round(width * 100)} 公分，站太窄` });
    out.sort((a, b) => b.sev - a.sev);
    const lines = out.slice(0, 3).map(x => x.t);
    return lines.length ? lines : [`重心 ${P(ratio)}%、站位都在目標內，維持`];
  }
  /** plain-sentence version, for speech or the log */
  summary(game = this.game) { return this.notes(game).join('。') + '。'; }
}

/** where should the phone go? returns [{good, text}] for the calibration screen */
export function placementAdvice(cam, mode, hand) {
  const C = cam.C, behind = -C[1] - TABLE.halfL, tips = [];
  const ok = (good, text) => tips.push({ good, text });
  if (mode === 'match') {
    ok(C[2] >= 1.4, C[2] >= 1.4 ? `高度 ${C[2].toFixed(1)} 公尺，很好` : `手機高度 ${C[2].toFixed(1)} 公尺，能再放高到 1.5 公尺以上，球會比較好追`);
    ok(behind >= 1.5 && behind <= 6, behind < 1.5 ? `離端線只有 ${behind.toFixed(1)} 公尺，往後退一點，選手才會整個入鏡` : behind > 6 ? `離端線 ${behind.toFixed(1)} 公尺，太遠了，人會太小` : `離端線 ${behind.toFixed(1)} 公尺，很好`);
    ok(Math.abs(C[0]) <= 1.5, Math.abs(C[0]) <= 1.5 ? '左右位置很好' : `手機偏向${C[0] > 0 ? '右' : '左'}邊 ${Math.abs(C[0]).toFixed(1)} 公尺，盡量對著球桌中線`);
  } else {
    const side = hand === 'R' ? 1 : -1, px = 0, py = -TABLE.halfL - 0.5; // typical ready position
    const az = Math.atan2((C[0] - px) * side, py - C[1]) * 180 / Math.PI, d = Math.hypot(C[0] - px, C[1] - py);
    const handWord = hand === 'R' ? '右' : '左';
    ok(az >= 30 && az <= 60, az < 30 ? `角度只有 ${Math.round(az)} 度，手機往選手${handWord}手邊移，斜 45 度最好` : az > 60 ? `角度 ${Math.round(az)} 度太側面了，往選手後方移一點` : `角度 ${Math.round(az)} 度，很好`);
    ok(d >= 1.8 && d <= 3.8, d < 1.8 ? `離選手只有 ${d.toFixed(1)} 公尺，往後退一點` : d > 3.8 ? `離選手 ${d.toFixed(1)} 公尺，靠近一點動作才看得清楚` : `距離 ${d.toFixed(1)} 公尺，很好`);
    ok(C[2] >= 0.9 && C[2] <= 1.7, C[2] < 0.9 ? '手機太低，放到胸口高度' : C[2] > 1.7 ? '手機太高，放到胸口高度' : `高度 ${C[2].toFixed(1)} 公尺，很好`);
  }
  return tips;
}
