// 場邊教練 v0.1 — UI, camera, MediaPipe, scoring and speech. Coaching logic lives in engine.js.
import { calibrate, project, pickNearPlayer, Coach, TABLE, placementAdvice } from './engine.js';

const MP = 'https://cdn.jsdelivr.net/npm/@mediapipe/tasks-vision@1.0.1';
const MODELS = {
  lite: 'https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_lite/float16/1/pose_landmarker_lite.task',
  full: 'https://storage.googleapis.com/mediapipe-models/pose_landmarker/pose_landmarker_full/float16/1/pose_landmarker_full.task',
};
const $ = id => document.getElementById(id);

// ---------- settings (localStorage is only a convenience) ----------
const store = {
  get(k, d) { try { const v = localStorage.getItem('tt.' + k); return v == null ? d : JSON.parse(v); } catch { return d; } },
  set(k, v) { try { localStorage.setItem('tt.' + k, JSON.stringify(v)); } catch { /* private mode */ } },
};
const S = { mode: store.get('mode', 'match'), hand: store.get('hand', 'R'), voice: store.get('voice', 'off'), model: store.get('model', 'lite') };
const TIPS = {
  match: ['手機<b>橫放</b>，放在選手這側<b>端線後方</b>的觀眾區，2–5 公尺都可以。',
    '盡量<b>放高</b>（1.5–2 公尺往下拍），球的背景才會是球桌和地板，比較好追。',
    '畫面要拍到<b>選手全身含腳</b>、<b>這側兩個桌角</b>和<b>球網兩端</b>。放好後就別再動手機。'],
  practice: ['手機<b>橫放</b>，放在選手<b>持拍手那側的後方斜 45 度</b>，離選手 2–3 公尺。',
    '高度約<b>胸口</b>，選手全身至少佔畫面一半高。',
    '畫面要拍到<b>這側兩個桌角</b>和<b>球網兩端</b>，校正後 App 會告訴你位置對不對。'],
};
function paintTips() { $('tips').innerHTML = TIPS[S.mode].map(t => `<li>${t}</li>`).join(''); }
function paintSegs() {
  document.querySelectorAll('.seg').forEach(seg => seg.querySelectorAll('button').forEach(b => b.classList.toggle('sel', b.dataset.v === S[seg.dataset.k])));
}
document.querySelectorAll('.seg').forEach(seg => seg.addEventListener('click', e => {
  const b = e.target.closest('button'); if (!b) return; const k = seg.dataset.k;
  S[k] = b.dataset.v; store.set(k, S[k]);
  if (k === 'mode') { S.voice = S.mode === 'practice' ? 'on' : 'off'; store.set('voice', S.voice); paintTips(); }
  paintSegs();
}));
paintSegs(); paintTips();
const status = t => { $('status').innerHTML = t; };

// ---------- speech ----------
let zhVoice = null;
function pickVoice() {
  if (!('speechSynthesis' in window)) return;
  const vs = speechSynthesis.getVoices();
  zhVoice = vs.find(v => /zh[-_]TW/i.test(v.lang)) || vs.find(v => /zh[-_](HK|Hant)/i.test(v.lang)) || vs.find(v => /^zh/i.test(v.lang)) || null;
}
if ('speechSynthesis' in window) { pickVoice(); speechSynthesis.onvoiceschanged = pickVoice; }
function say(text, force) {
  if ((!force && S.voice !== 'on') || !('speechSynthesis' in window) || !text) return;
  try {
    const u = new SpeechSynthesisUtterance(text);
    u.lang = 'zh-TW'; if (zhVoice) u.voice = zhVoice; u.rate = 1.05; if (force && S.voice !== 'on') u.volume = 0;
    speechSynthesis.cancel(); speechSynthesis.speak(u);
  } catch { /* ignore */ }
}

// ---------- wake lock ----------
let wakeLock = null;
async function keepAwake() { try { wakeLock = await navigator.wakeLock?.request('screen'); } catch { /* not critical */ } }
document.addEventListener('visibilitychange', () => { if (document.visibilityState === 'visible' && phase === 'coach') keepAwake(); });

// ---------- pose model ----------
let landmarker = null, landmarkerKey = '', loading = null;
function loadPose() {
  if (landmarker && landmarkerKey === S.model) return Promise.resolve(landmarker);
  if (loading && loading.key === S.model) return loading.p;
  const p = createPose(); loading = { key: S.model, p };
  p.catch(() => { loading = null; });
  return p;
}
async function createPose() {
  const { FilesetResolver, PoseLandmarker } = await import(MP + '/vision_bundle.mjs');
  const files = await FilesetResolver.forVisionTasks(MP + '/wasm');
  const make = delegate => PoseLandmarker.createFromOptions(files, {
    baseOptions: { modelAssetPath: MODELS[S.model], delegate },
    runningMode: 'VIDEO', numPoses: 2,
    minPoseDetectionConfidence: 0.5, minPosePresenceConfidence: 0.5, minTrackingConfidence: 0.5,
  });
  let lm;
  const want = new URLSearchParams(location.search).get('delegate');   // ?delegate=CPU for testing
  try { lm = await make(want === 'CPU' ? 'CPU' : 'GPU'); } catch (e) { console.warn('GPU delegate failed, using CPU', e); lm = await make('CPU'); }
  landmarker = lm; landmarkerKey = S.model;
  return lm;
}

// ---------- stage / canvas ----------
// coaching clock: video time for test files (so slow devices still see real motion), wall time for the camera
const clock = () => source === 'file' ? video.currentTime : performance.now() / 1000;
const video = $('video'), view = $('view'), ctx = view.getContext('2d');
let W = 1280, H = 720, source = 'camera', phase = 'home', running = false;
let pts = [], cal = null, dragIdx = -1, coach = null;

function show(id) { document.querySelectorAll('.screen').forEach(s => s.classList.toggle('on', s.id === id)); }
function fit() {
  const s = Math.min(innerWidth / W, innerHeight / H);
  Object.assign(view.style, { width: W * s + 'px', height: H * s + 'px', left: (innerWidth - W * s) / 2 + 'px', top: (innerHeight - H * s) / 2 + 'px' });
  $('rotate').classList.toggle('need', innerHeight > innerWidth && source === 'camera' && phase !== 'home');
}
addEventListener('resize', fit);

async function startSource(kind, file) {
  source = kind;
  try {
    if (kind === 'camera') {
      if (!navigator.mediaDevices?.getUserMedia) throw new Error('這個瀏覽器不能開相機（網址要是 https）');
      const stream = await navigator.mediaDevices.getUserMedia({ audio: false, video: { facingMode: { ideal: 'environment' }, width: { ideal: 1280 }, height: { ideal: 720 } } });
      video.srcObject = stream;
    } else {
      video.srcObject = null; video.src = URL.createObjectURL(file);
    }
    video.loop = false;
    await new Promise((ok, bad) => { video.onloadedmetadata = ok; video.onerror = () => bad(new Error('影片無法播放，請換 mp4 檔')); });
    if (kind === 'camera') await video.play();
    else { video.currentTime = Math.min(1, (video.duration || 2) / 2); await new Promise(r => { video.onseeked = r; }); }
  } catch (e) {
    const m = e.name === 'NotAllowedError' ? '相機權限被拒絕，請到瀏覽器設定允許相機' : (e.message || e.name);
    status('無法開始：' + m); return false;
  }
  W = video.videoWidth; H = video.videoHeight; view.width = W; view.height = H;
  return true;
}

// ---------- drawing ----------
const BONES = [[11, 12], [11, 13], [13, 15], [12, 14], [14, 16], [11, 23], [12, 24], [23, 24], [23, 25], [25, 27], [27, 29], [29, 31], [27, 31], [24, 26], [26, 28], [28, 30], [30, 32], [28, 32], [0, 11], [0, 12]];
const lw = () => Math.max(2, W / 450);
function poly(P, color, width, close) {
  ctx.beginPath(); P.forEach((p, i) => i ? ctx.lineTo(p[0], p[1]) : ctx.moveTo(p[0], p[1])); if (close) ctx.closePath();
  ctx.strokeStyle = color; ctx.lineWidth = width; ctx.stroke();
}
function drawTable(cam, color, width) {
  const { halfW: w, halfL: l, h } = TABLE, P = (x, y, z = h) => project(cam, [x, y, z]);
  poly([P(-w, -l), P(w, -l), P(w, l), P(-w, l)], color, width, true);
  poly([P(0, -l), P(0, l)], color, width * 0.5);
  poly([P(-w - 0.15, 0, h), P(-w - 0.15, 0, h + 0.1525), P(w + 0.15, 0, h + 0.1525), P(w + 0.15, 0, h)], color, width * 0.7);
}
function drawCal() {
  if (cal?.ok) drawTable(cal.cam, '#34d399', lw() * 1.3);
  const r = Math.max(10, W / 90);
  if (pts.length > 1) poly(pts, 'rgba(255,255,255,.6)', lw() * 0.6, pts.length === 4);
  pts.forEach((p, i) => {
    ctx.beginPath(); ctx.arc(p[0], p[1], r, 0, 7); ctx.fillStyle = i === dragIdx ? '#fbbf24' : 'rgba(96,165,250,.85)'; ctx.fill();
    ctx.lineWidth = 2; ctx.strokeStyle = '#fff'; ctx.stroke();
    ctx.fillStyle = '#fff'; ctx.font = `bold ${r * 1.2}px sans-serif`; ctx.textAlign = 'center'; ctx.textBaseline = 'middle'; ctx.fillText(String(i + 1), p[0], p[1] - r * 2);
  });
}
function drawPose(lm, color, width) {
  ctx.strokeStyle = color; ctx.lineWidth = width; ctx.lineCap = 'round';
  for (const [a, b] of BONES) { ctx.beginPath(); ctx.moveTo(lm[a].x, lm[a].y); ctx.lineTo(lm[b].x, lm[b].y); ctx.stroke(); }
}
function drawCoach(poses, pick) {
  drawTable(cal.cam, 'rgba(52,211,153,.35)', lw());
  for (const lm of poses) if (!pick || lm !== pick.lm) drawPose(lm, 'rgba(255,255,255,.25)', lw() * 0.8);
  if (pick) {
    drawPose(pick.lm, '#34d399', lw() * 1.4);
    const g = project(cal.cam, [pick.m.G[0], pick.m.G[1], 0]);
    ctx.beginPath(); ctx.ellipse(g[0], g[1], W / 40, W / 120, 0, 0, 7); ctx.strokeStyle = '#fbbf24'; ctx.lineWidth = lw(); ctx.stroke();
  }
}

// ---------- calibration + placement guidance ----------
const STEP_TEXT = ['點 ① 選手這側的<b>左</b>桌角', '點 ② 選手這側的<b>右</b>桌角', '點 ③ <b>右</b>邊線和球網的交點', '點 ④ <b>左</b>邊線和球網的交點'];
function calHelp(html) { const el = $('calHelp'); el.innerHTML = html; el.classList.remove('hidden'); }
function recompute() {
  cal = pts.length === 4 ? calibrate(pts, W, H) : null;
  if (pts.length < 4) calHelp(STEP_TEXT[pts.length] + '<small>放大鏡幫你對準；點錯可以直接拖動圓點</small>');
  else if (!cal.ok) calHelp('⚠️ ' + cal.reason + '<small>確認順序：① 左角 ② 右角 ③ 右邊網 ④ 左邊網</small>');
  else {
    const tips = placementAdvice(cal.cam, S.mode, S.hand);
    calHelp('綠框有貼齊球桌就按「開始」<small>沒貼齊就拖動圓點</small><ul>' + tips.map(t => `<li>${t.good ? '✅' : '⚠️'} ${t.text}</li>`).join('') + '</ul>');
  }
  $('cGo').disabled = !(cal && cal.ok);
}
function enterCal() {
  phase = 'cal';
  ['hud', 'score'].forEach(id => $(id).classList.add('hidden')); ['log', 'notes', 'cue'].forEach(id => $(id).classList.remove('on'));
  $('calBar').classList.remove('hidden');
  const saved = store.get('calib', null);
  pts = saved && Math.abs(saved.W / saved.H - W / H) < 0.01 ? saved.pts.map(([u, v]) => [u * W, v * H]) : [];
  recompute(); fit();
}
function toVid(e) { const r = view.getBoundingClientRect(); return [(e.clientX - r.left) / r.width * W, (e.clientY - r.top) / r.height * H]; }
function loupe(e, p) {
  const L = $('loupe'), lc = L.getContext('2d'), sz = 130 / 3 * (W / view.getBoundingClientRect().width);
  lc.drawImage(view, p[0] - sz / 2, p[1] - sz / 2, sz, sz, 0, 0, 130, 130);
  lc.strokeStyle = '#fbbf24'; lc.lineWidth = 1.5; lc.beginPath(); lc.moveTo(65, 45); lc.lineTo(65, 85); lc.moveTo(45, 65); lc.lineTo(85, 65); lc.stroke();
  Object.assign(L.style, { display: 'block', left: Math.min(innerWidth - 140, Math.max(10, e.clientX - 65)) + 'px', top: Math.max(10, e.clientY - 180) + 'px' });
}
view.addEventListener('pointerdown', e => {
  if (phase !== 'cal') return;
  const p = toVid(e), hit = W / view.getBoundingClientRect().width * 30;
  // while placing, every tap adds a point (corners can be only a few px apart on a phone); once all 4 exist, drag the nearest
  if (pts.length < 4) { pts.push(p); dragIdx = pts.length - 1; }
  else { let best = -1, bd = hit; pts.forEach((q, i) => { const d = Math.hypot(q[0] - p[0], q[1] - p[1]); if (d < bd) { bd = d; best = i; } }); dragIdx = best; }
  if (dragIdx >= 0) { try { view.setPointerCapture(e.pointerId); } catch { } loupe(e, p); recompute(); }
});
view.addEventListener('pointermove', e => { if (phase !== 'cal' || dragIdx < 0) return; const p = toVid(e); pts[dragIdx] = p; loupe(e, p); if (pts.length === 4) recompute(); });
const endDrag = () => { if (dragIdx < 0) return; dragIdx = -1; $('loupe').style.display = 'none'; recompute(); };
view.addEventListener('pointerup', endDrag); view.addEventListener('pointercancel', endDrag);
$('cReset').onclick = () => { pts = []; recompute(); };
$('cBack').onclick = () => goHome();
$('cGo').onclick = () => startCoach();

// ---------- scoring ----------
const score = { me: 0, opp: 0, gMe: 0, gOpp: 0, hist: [] };
function paintScore() { $('sMe').textContent = score.me; $('sOpp').textContent = score.opp; $('sGames').textContent = `局 ${score.gMe}:${score.gOpp}`; }
function point(meWon) {
  score.hist.push({ me: score.me, opp: score.opp, gMe: score.gMe, gOpp: score.gOpp, game: coach.game });
  meWon ? score.me++ : score.opp++;
  const r = coach.markPoint(meWon, clock());
  addLog(`${meWon ? '<span class="w">得分</span>' : '<span class="l">失分</span>'} ${score.me}:${score.opp}${r ? '' : '（這分沒抓到回合）'}`);
  const a = score.me, b = score.opp;
  if ((a >= 11 || b >= 11) && Math.abs(a - b) >= 2) {
    const game = coach.game; a > b ? score.gMe++ : score.gOpp++;
    showNotes(`第 ${game + 1} 局結束 ${a}:${b} · 局間重點`, coach.notes(game));
    addLog('📋 ' + coach.summary(game));
    coach.newGame(); score.me = 0; score.opp = 0;
  }
  paintScore(); if (navigator.vibrate) navigator.vibrate(30);
}
function undo() {
  const h = score.hist.pop(); if (!h) return;
  Object.assign(score, { me: h.me, opp: h.opp, gMe: h.gMe, gOpp: h.gOpp });
  coach.game = h.game; coach.undoPoint(); paintScore(); addLog('↩︎ 收回上一分'); if (navigator.vibrate) navigator.vibrate([20, 40, 20]);
}
function pressable(el, onTap, onLong) {
  let tm = 0, long = false;
  el.addEventListener('pointerdown', () => { long = false; tm = setTimeout(() => { long = true; onLong(); }, 650); });
  el.addEventListener('pointerup', () => { clearTimeout(tm); if (!long) onTap(); });
  el.addEventListener('pointerleave', () => clearTimeout(tm));
  el.addEventListener('contextmenu', e => e.preventDefault());
}
pressable($('bMe'), () => point(true), undo);
pressable($('bOpp'), () => point(false), undo);

// ---------- coaching ----------
function showNotes(title, lines) {
  $('notesTitle').textContent = title;
  $('notesList').innerHTML = lines.map(l => `<li>${l}</li>`).join('');
  $('notesSub').textContent = `已記錄 ${coach.rallies.length} 分`;
  $('notes').classList.add('on'); say(lines.join('。'));
}
$('notesClose').onclick = () => $('notes').classList.remove('on');
$('tNotes').onclick = () => showNotes(`第 ${coach.game + 1} 局目前重點（暫停用）`, coach.notes());
$('tLog').onclick = () => $('log').classList.toggle('on');
$('btnLogClose').onclick = () => $('log').classList.remove('on');
$('tCal').onclick = () => enterCal();
$('tEnd').onclick = () => goHome();
$('btnDl').onclick = () => {
  const data = { app: '場邊教練 v0.1', date: new Date().toISOString(), settings: S, score, rallies: coach?.rallies || [] };
  const a = document.createElement('a'); a.href = URL.createObjectURL(new Blob([JSON.stringify(data, null, 1)], { type: 'application/json' }));
  a.download = `場邊教練_${new Date().toISOString().slice(0, 16).replace(/[:T]/g, '')}.json`; a.click();
};
function addLog(html) { const d = document.createElement('div'); d.className = 'item'; d.innerHTML = html; $('logItems').prepend(d); }

async function startCoach() {
  if (!cal?.ok) return;
  store.set('calib', { W, H, pts: pts.map(([x, y]) => [x / W, y / H]) });
  say('開始', true);                          // a tap unlocks speech on iOS even when muted now
  $('calHelp').classList.add('hidden'); $('calBar').classList.add('hidden');
  calHelp('載入辨識模型中…第一次約 5–15 秒');
  try { await loadPose(); } catch (e) { console.error(e); calHelp('⚠️ 辨識模型載入失敗，請確認網路後再按一次「開始」'); $('calBar').classList.remove('hidden'); return; }
  $('calHelp').classList.add('hidden');
  if (!coach) coach = new Coach({ hand: S.hand });
  coach.o.hand = S.hand;
  phase = 'coach'; $('hud').classList.remove('hidden'); $('score').classList.remove('hidden'); paintScore();
  keepAwake();
  if (source === 'file') { video.currentTime = 0; video.play(); video.onended = () => showNotes('影片結束 · 重點', coach.notes()); }
}
const DETAIL = r => {
  const P = v => Math.round(v * 100);
  return [isFinite(r.ratio) ? `重心 ${P(r.ratio)}%` : '重心校準中', `反手側 ${P(r.leftShare)}%`, `步寬 ${P(r.width)} 公分`, `離桌 ${r.dist.toFixed(1)} 公尺`, `${r.dur.toFixed(0)} 秒`].join(' · ');
};
let rallyNo = 0;
function onEvent(ev) {
  if (ev.event === 'start') { $('hState').textContent = '🔴 回合中'; $('cue').classList.remove('on'); return; }
  $('hState').textContent = '⏸ 分與分之間';
  if (ev.event !== 'end') return;
  rallyNo++;
  const r = ev.rally;
  addLog(`<b>第 ${rallyNo} 分</b> ${DETAIL(r)}${ev.cue ? ' → <b>' + ev.cue.text + '</b>' : ''}`);
  if (ev.cue) {
    const c = $('cue'); c.querySelector('.main').textContent = ev.cue.text; c.querySelector('.detail').textContent = DETAIL(r);
    c.classList.toggle('good', ev.cue.k === 'good'); c.classList.add('on');
    say(ev.cue.text);
  }
}

let tsLast = 0, hudT = 0, nDet = 0, fpsT = 0;
function hud(pick, poses) {
  const now = performance.now(); nDet++;
  if (now - fpsT > 1000) { const f = Math.round(nDet * 1000 / (now - fpsT)); $('hFps').textContent = f < 8 ? `⚠️ ${f} fps 太慢，改「快速」辨識` : f + ' fps'; nDet = 0; fpsT = now; }
  if (now - hudT < 250) return; hudT = now;
  if (!pick) {
    const cut = poses.some(lm => (lm[27].visibility ?? 1) < 0.3 && (lm[28].visibility ?? 1) < 0.3 && lm[0].y < H * 0.8);
    $('hLow').textContent = cut ? '腳被切到了' : '找不到選手'; $('hPos').textContent = '–'; $('hDist').textContent = '–'; $('hLow').style.color = '#fbbf24'; return;
  }
  const st = coach.standing, n = coach.noseHist.length, el = $('hLow');
  if (n < 60 || !isFinite(st)) { el.textContent = '校準中'; el.style.color = ''; }
  else { const r = pick.m.nose / st; el.textContent = Math.round(r * 100) + '%'; el.style.color = r <= coach.o.targetRatio + 0.01 ? '#34d399' : '#fbbf24'; }
  const x = S.hand === 'R' ? pick.m.G[0] : -pick.m.G[0];
  $('hPos').textContent = x < -coach.o.sideLimit ? '偏反手' : x > coach.o.sideLimit ? '偏正手' : '中間';
  $('hDist').textContent = pick.m.dist.toFixed(1) + 'm';
}

function frame() {
  if (!running) return;
  if (video.readyState >= 2) {
    ctx.drawImage(video, 0, 0, W, H);
    if (phase === 'cal') drawCal();
    else if (phase === 'coach' && landmarker && !(source === 'file' && video.paused)) {
      const ts = Math.max(tsLast + 1, performance.now()); tsLast = ts;
      let res = null;
      try { res = landmarker.detectForVideo(video, ts); } catch (e) { console.warn(e); }
      window.__tt.nPoses = res?.landmarks?.length ?? -1;
      const poses = (res?.landmarks || []).map(l => l.map(p => ({ x: p.x * W, y: p.y * H, visibility: p.visibility })));
      const pick = pickNearPlayer(cal.cam, poses);
      const ev = coach.update(pick ? pick.m : null, clock());
      if (ev) onEvent(ev);
      drawCoach(poses, pick); hud(pick, poses);
    }
  }
  if (video.requestVideoFrameCallback && phase === 'coach' && !video.paused) video.requestVideoFrameCallback(frame);
  else requestAnimationFrame(frame);
}

async function begin(kind, file) {
  status('');
  if (!(await startSource(kind, file))) return;
  show('stage'); running = true; enterCal(); frame();
  loadPose().catch(e => console.warn('model preload failed', e));   // warm up while the coach taps
}
function goHome() {
  running = false; phase = 'home';
  video.pause(); video.srcObject?.getTracks().forEach(t => t.stop()); video.srcObject = null;
  try { wakeLock?.release(); } catch { }
  $('calHelp').classList.add('hidden'); $('calBar').classList.add('hidden'); $('hud').classList.add('hidden'); $('score').classList.add('hidden');
  if (coach?.rallies.length) status(`上一場：記錄 ${coach.rallies.length} 分，比分局數 ${score.gMe}:${score.gOpp}。<br>${coach.summary()}`);
  show('home');
}

$('btnCam').onclick = () => { say(' ', true); begin('camera'); };
$('btnFile').onclick = () => $('file').click();
$('file').onchange = e => { const f = e.target.files[0]; if (f) { say(' ', true); begin('file', f); } e.target.value = ''; };

if ('serviceWorker' in navigator && location.protocol === 'https:') navigator.serviceWorker.register('sw.js').catch(() => {});
window.__tt = { get landmarker() { return landmarker; }, get coach() { return coach; }, get cal() { return cal; }, get phase() { return phase; }, score }; // for debugging
