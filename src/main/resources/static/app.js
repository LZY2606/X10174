'use strict';

const state = {
  detail: null,
  waveforms: [],
  compare: null,
  selected: new Set(),
  window: { center: 1000000, span: 20000 },
  selectedCandForPick: null,
};

const $ = (id) => document.getElementById(id);

function toast(message, isError) {
  const el = $('toast');
  el.textContent = message;
  el.style.display = 'block';
  el.className = isError ? 'error' : '';
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => { el.style.display = 'none'; }, 5000);
}

async function api(path, options) {
  const response = await fetch(path, {
    headers: { 'Content-Type': 'application/json' },
    ...options,
  });
  const text = await response.text();
  const body = text ? JSON.parse(text) : {};
  if (!response.ok) {
    const detail = body.minimumConflictStations
      ? '\n最小冲突台站集: ' + body.minimumConflictStations.join(', ')
        + '\n冲突对: ' + (body.conflicts || []).map(c => c.pair.nearStation + ' > ' + c.pair.farStation).join('; ')
      : '';
    throw new Error(body.error || response.status + detail);
  }
  return body;
}

function fmt(ms) {
  if (ms === null || ms === undefined || !isFinite(ms)) return '—';
  return new Date(ms).toISOString().substring(11, 23);
}

async function loadInterps(selectCode) {
  const list = await api('/api/interpretations');
  const sel = $('interpSel');
  sel.innerHTML = '';
  list.forEach((interp) => {
    const opt = document.createElement('option');
    opt.value = interp.interpCode;
    opt.textContent = interp.interpCode + (interp.frozenMs ? ' 🔒' : '');
    sel.appendChild(opt);
  });
  if (selectCode) sel.value = selectCode;
  renderInterpList(list);
  if (sel.value) await loadDetail(sel.value);
}

function renderInterpList(list) {
  $('interpList').innerHTML = '';
  const table = document.createElement('table');
  table.innerHTML = '<tr><th>解释</th><th>事件</th><th>模型</th><th>状态</th><th>父分支</th></tr>';
  list.forEach((i) => {
    const tr = document.createElement('tr');
    tr.innerHTML = `<td>${i.interpCode}</td><td>${i.eventCode}</td><td>#${i.velocityModelId}</td>
      <td>${i.frozenMs ? '<span class="tag frozen">已冻结</span>' : '<span class="tag open">开放</span>'}</td>
      <td>${i.parentInterpId ?? ''}</td>`;
    table.appendChild(tr);
  });
  $('interpList').appendChild(table);
}

async function loadWaveforms() {
  state.waveforms = await api('/api/waveforms');
}

async function loadDetail(code) {
  state.detail = await api('/api/interpretations/' + encodeURIComponent(code));
  const interp = state.detail.interpretation;
  $('interpMeta').textContent =
    `事件 ${interp.eventCode} · 模型 ${state.detail.velocityModel.code} v${state.detail.velocityModel.version} · `
    + (interp.frozenMs ? `已冻结 @${interp.frozenMs}，台站钉版 #${interp.stationPinVersion}` : '开放编辑')
    + ` · headSeq=${state.detail.headSeq}`;
  renderClockStations();
  renderCandidates();
  renderEventLog();
  drawWaveforms();
}

function renderClockStations() {
  const sel = $('clockStation');
  const current = sel.value;
  sel.innerHTML = '';
  state.detail.predictions.forEach((p) => {
    const opt = document.createElement('option');
    opt.value = p.stationCode;
    const correction = state.detail.clockCorrectionsMs[p.stationCode] ?? 0;
    opt.textContent = `${p.stationCode} (当前修正 ${correction} ms)`;
    sel.appendChild(opt);
  });
  if (current) sel.value = current;
}

function candidates() {
  return state.detail.candidates;
}

function renderCandidates() {
  const table = $('candTable');
  table.innerHTML = '<tr><th></th><th>ID</th><th>台站</th><th>通道</th><th>相</th><th>到时(ms)</th>'
    + '<th>极性</th><th>CI 低</th><th>CI 高</th><th>权重</th><th>来源</th><th>状态</th></tr>';
  candidates().forEach((c) => {
    const tr = document.createElement('tr');
    const checked = state.selected.has(c.id) ? 'checked' : '';
    tr.innerHTML = `<td><input type="checkbox" data-id="${c.id}" ${checked} ${c.status === 'ACTIVE' ? '' : 'disabled'}></td>
      <td>${c.id}</td><td>${c.stationCode}</td><td>${c.channel ?? ''}</td><td>${c.phase}</td>
      <td>${c.arrivalMs}</td><td>${c.polarity}</td>
      <td>${c.confidenceLowMs ?? ''}</td><td>${c.confidenceHighMs ?? ''}</td>
      <td>${c.weight}</td><td>${c.source}</td><td>${c.status}${c.mergedInto ? '→' + c.mergedInto : ''}</td>`;
    table.appendChild(tr);
    tr.querySelector('input[type=checkbox]')?.addEventListener('change', (e) => {
      const id = Number(e.target.dataset.id);
      e.target.checked ? state.selected.add(id) : state.selected.delete(id);
    });
    tr.addEventListener('dblclick', () => editCandidate(c));
  });
}

async function editCandidate(c) {
  const arrivalMs = prompt('新的到时 (ms)', c.arrivalMs);
  if (arrivalMs === null) return;
  const polarity = prompt('极性 (UP/DOWN/NONE)', c.polarity);
  if (polarity === null) return;
  const ciLow = prompt('置信区间下限 (ms，留空清除)', c.confidenceLowMs ?? '');
  if (ciLow === null) return;
  const ciHigh = prompt('置信区间上限 (ms，留空清除)', c.confidenceHighMs ?? '');
  if (ciHigh === null) return;
  const weight = prompt('权重', c.weight);
  if (weight === null) return;
  await api(`/api/interpretations/${state.detail.interpretation.interpCode}/actions`, {
    method: 'POST',
    body: JSON.stringify({
      type: 'UPDATE', targetId: c.id, arrivalMs: Number(arrivalMs), polarity,
      confidenceLowMs: ciLow === '' ? null : Number(ciLow),
      confidenceHighMs: ciHigh === '' ? null : Number(ciHigh),
      weight: Number(weight),
    }),
  });
  await refresh();
}

async function refresh() {
  await loadWaveforms();
  await loadDetail($('interpSel').value);
  if (state.compare) await runCompare(false);
}

function renderEventLog() {
  const el = $('eventLog');
  el.innerHTML = '';
  const table = document.createElement('table');
  table.innerHTML = '<tr><th>seq</th><th>类型</th><th>撤销</th><th>逆于</th></tr>';
  state.detail.events.forEach((e) => {
    const tr = document.createElement('tr');
    tr.innerHTML = `<td>${e.seq}</td><td>${e.eventType}</td>
      <td>${e.undoable ? '是' : ''}</td><td>${e.undoOfSeq ?? ''}</td>`;
    table.appendChild(tr);
  });
  el.appendChild(table);
}

function parseSamples(segment) {
  try {
    const parsed = JSON.parse(segment.samples);
    return parsed.csv.split(',').map(Number);
  } catch (e) {
    return [];
  }
}

function drawWaveforms() {
  const canvas = $('waveCanvas');
  const dpr = window.devicePixelRatio || 1;
  const width = canvas.clientWidth;
  const stationCodes = state.detail.predictions.map((p) => p.stationCode);
  const rowH = Math.max(70, Math.floor(380 / Math.max(1, stationCodes.length)));
  canvas.width = width * dpr;
  canvas.height = rowH * stationCodes.length * dpr;
  const ctx = canvas.getContext('2d');
  ctx.scale(dpr, dpr);
  ctx.clearRect(0, 0, width, rowH * stationCodes.length);
  ctx.font = '11px sans-serif';

  const { center, span } = state.window;
  const xFor = (ms) => ((ms - (center - span / 2)) / span) * width;

  const grouped = {};
  state.waveforms.forEach((w) => {
    (grouped[w.stationCode] ||= []).push(w);
  });
  const candsByStation = {};
  candidates().forEach((c) => (candsByStation[c.stationCode] ||= []).push(c));

  stationCodes.forEach((code, row) => {
    const top = row * rowH;
    ctx.strokeStyle = '#2a3550';
    ctx.beginPath();
    ctx.moveTo(0, top + rowH - 0.5);
    ctx.lineTo(width, top + rowH - 0.5);
    ctx.stroke();
    ctx.fillStyle = '#8da0bd';
    ctx.fillText(code, 8, top + 14);

    const segs = grouped[code] || [];
    let min = -1, max = 1;
    segs.forEach((seg) => parseSamples(seg).forEach((v) => { min = Math.min(min, v); max = Math.max(max, v); }));
    const scale = (rowH - 30) / Math.max(1e-9, max - min);
    const yFor = (v) => top + rowH / 2 + 10 - (v - (min + max) / 2) * scale;

    segs.forEach((seg) => {
      const values = parseSamples(seg);
      const stepMs = 1000 / seg.sampleRateHz;
      ctx.strokeStyle = seg.clipped ? '#5a6b8c' : '#8fa6cc';
      ctx.lineWidth = 1;
      ctx.beginPath();
      values.forEach((v, i) => {
        const ms = seg.startMs + i * stepMs;
        const x = xFor(ms);
        const y = yFor(v);
        i === 0 ? ctx.moveTo(x, y) : ctx.lineTo(x, y);
      });
      ctx.stroke();
      if (seg.clipped) {
        ctx.fillStyle = '#ff6b6b';
        values.forEach((v, i) => {
          if (Math.abs(v) >= 0.98 * max) {
            const ms = seg.startMs + i * stepMs;
            ctx.fillRect(xFor(ms) - 1, yFor(v) - 1, 2, 2);
          }
        });
      }
      // gap markers between segments are drawn as hatched zones below
    });

    const prediction = state.detail.predictions.find((p) => p.stationCode === code);
    drawTheory(ctx, xFor(prediction.pMs), top, rowH, '#ff9d5c', 'P理论');
    drawTheory(ctx, xFor(prediction.sMs), top, rowH, '#7ee787', 'S理论');

    (candsByStation[code] || []).forEach((c) => {
      const color = c.phase === 'S' ? '#7ee787' : '#ff9d5c';
      const correction = state.detail.clockCorrectionsMs[code] ?? 0;
      const x = xFor(c.arrivalMs + correction);
      if (c.status !== 'ACTIVE') {
        ctx.globalAlpha = 0.3;
      }
      if (c.confidenceLowMs != null && c.confidenceHighMs != null) {
        ctx.fillStyle = c.phase === 'S' ? 'rgba(126,231,135,0.16)' : 'rgba(255,157,92,0.16)';
        ctx.fillRect(xFor(c.confidenceLowMs + correction), top + 18,
          Math.max(1, xFor(c.confidenceHighMs + correction) - xFor(c.confidenceLowMs + correction)), rowH - 28);
      }
      ctx.strokeStyle = color;
      ctx.beginPath();
      ctx.moveTo(x, top + 18);
      ctx.lineTo(x, top + rowH - 6);
      ctx.stroke();
      ctx.fillStyle = color;
      ctx.fillText(`${c.phase}#${c.id} ${c.polarity}`, x + 3, top + 14);
      ctx.globalAlpha = 1;
    });
  });

  function drawTheory(ctx, x, top, rowH, color, label) {
    if (!isFinite(x) || x < -20 || x > width + 20) return;
    ctx.strokeStyle = color;
    ctx.setLineDash([5, 4]);
    ctx.beginPath();
    ctx.moveTo(x, top + 18);
    ctx.lineTo(x, top + rowH - 6);
    ctx.stroke();
    ctx.setLineDash([]);
    ctx.fillStyle = color;
    ctx.fillText(label, x + 3, top + rowH - 8);
  }
}

function drawResiduals() {
  if (!state.compare) return;
  const canvas = $('residualCanvas');
  const dpr = window.devicePixelRatio || 1;
  const width = canvas.clientWidth;
  canvas.width = width * dpr;
  canvas.height = 220 * dpr;
  const ctx = canvas.getContext('2d');
  ctx.scale(dpr, dpr);
  ctx.clearRect(0, 0, width, 220);
  const margin = 60;
  const scores = state.compare.scores;
  const rows = [];
  scores.forEach((score) => score.rows.forEach((r) => rows.push({ score, r })));
  if (!rows.length) return;
  const maxAbs = Math.max(...rows.map((x) => Math.abs(x.r.residualMs)), state.compare.windowMs);
  const zeroX = margin + (width - margin * 2) / 2;
  const xFor = (ms) => zeroX + (ms / maxAbs) * (width - margin * 2) / 2;
  ctx.strokeStyle = '#2a3550';
  ctx.beginPath(); ctx.moveTo(zeroX, 10); ctx.lineTo(zeroX, 200); ctx.stroke();
  ctx.strokeStyle = '#ff6b6b';
  [xFor(state.compare.windowMs), xFor(-state.compare.windowMs)].forEach((x) => {
    ctx.beginPath(); ctx.moveTo(x, 10); ctx.lineTo(x, 200); ctx.stroke();
  });
  const colors = ['#6ea8fe', '#ffd166', '#c9a7ff', '#7ee787', '#ff9d5c'];
  const rowH = Math.max(14, Math.floor(180 / Math.max(1, rows.length)));
  ctx.font = '10px sans-serif';
  rows.forEach((item, i) => {
    const y = 16 + i * rowH;
    const modelIndex = scores.findIndex((s) => s.modelId === item.score.modelId);
    ctx.fillStyle = item.r.inWindow ? colors[modelIndex % colors.length] : '#ff6b6b';
    const x = xFor(item.r.residualMs);
    ctx.fillRect(Math.min(zeroX, x), y, Math.max(2, Math.abs(x - zeroX)), rowH - 4);
    ctx.fillStyle = '#8da0bd';
    ctx.fillText(`${item.score.key()} ${item.r.stationCode} ${item.r.phase} ${Math.round(item.r.residualMs)}ms`, 4, y + rowH - 5);
  });
}

async function runCompare(redrawOnly) {
  if (!redrawOnly) {
    const windowMs = Number($('windowMs').value);
    state.compare = await api(
      `/api/interpretations/${state.detail.interpretation.interpCode}/compare`,
      { method: 'POST', body: JSON.stringify({ windowMs }) });
  }
  const scores = state.compare.scores;
  const table = $('scoreTable');
  table.innerHTML = '<tr><th>排名</th><th>模型</th><th>台站</th><th>窗口内</th><th>窗口外</th>'
    + '<th>加权RMS(ms)</th><th>平均残差(ms)</th><th>总权重</th></tr>';
  scores.forEach((s, i) => {
    const winner = state.compare.winners.some((w) => w.modelId === s.modelId);
    const tr = document.createElement('tr');
    tr.innerHTML = `<td>${i + 1}${winner ? ' 🏆' : ''}</td><td>${s.key()}</td><td>${s.comparedStations}</td>
      <td class="good">${s.inWindowCount}</td><td class="${s.outOfWindowCount ? 'bad' : ''}">${s.outOfWindowCount}</td>
      <td>${isFinite(s.weightedRmsMs) ? s.weightedRmsMs.toFixed(2) : '∞'}</td>
      <td>${isFinite(s.meanResidualMs) ? s.meanResidualMs.toFixed(2) : '∞'}</td><td>${s.totalWeight}</td>`;
    table.appendChild(tr);
  });
  $('compareWinners').textContent = '获胜: ' + state.compare.winners.map((w) => w.key()).join(', ');

  const stationTable = $('stationCmpTable');
  const keys = scores.map((s) => s.key());
  stationTable.innerHTML = '<tr><th>台站</th><th>相</th>' + keys.map((k) =>
    `<th>${k} 残差</th><th>${k} 预测差</th><th>${k} 观测差</th><th>${k} 窗口</th><th>${k} 权重来源</th>`).join('') + '</tr>';
  state.compare.stationComparison.forEach((sc) => {
    const tr = document.createElement('tr');
    let cells = `<td>${sc.stationCode}</td><td>${sc.phase}</td>`;
    keys.forEach((k) => {
      const residual = sc.residualMs[k];
      const pdiff = sc.predictedDiffMs[k];
      const odiff = sc.observedDiffMs[k];
      const win = sc.inWindow[k];
      const weight = (sc.weightSources.find((w) => w.startsWith(k)) || '').replace(k + ' => ', '');
      cells += `<td class="${win ? 'good' : 'bad'}">${residual === undefined ? '—' : residual.toFixed(1)}</td>
        <td>${pdiff === undefined ? '—' : pdiff.toFixed(1)}</td>
        <td>${odiff === undefined ? '—' : odiff.toFixed(1)}</td>
        <td>${win ? '内' : '外'}</td><td class="small muted">${weight}</td>`;
    });
    tr.innerHTML = cells;
    stationTable.appendChild(tr);
  });
  drawResiduals();
}

function wire() {
  $('interpSel').addEventListener('change', async () => {
    state.compare = null;
    await loadDetail($('interpSel').value);
  });
  $('btnRefresh').addEventListener('click', refresh);
  $('btnWfApply').addEventListener('click', () => {
    state.window = { center: Number($('wfCenter').value), span: Number($('wfSpan').value) };
    drawWaveforms();
  });
  $('btnUndo').addEventListener('click', async () => {
    try {
      await api(`/api/interpretations/${$('interpSel').value}/undo`, { method: 'POST' });
      await refresh();
      toast('已撤销');
    } catch (e) { toast(e.message, true); }
  });
  $('btnAdd').addEventListener('click', async () => {
    const stationCode = $('clockStation').value || prompt('台站代码');
    if (!stationCode) return;
    const phase = prompt('震相 P / S', 'P');
    const arrivalMs = Number(prompt('到时 (epoch ms)', state.window.center));
    const ci = prompt('置信半宽 (ms，可留空)', '500');
    const channel = prompt('通道', 'HHZ');
    const body = { type: 'ADD', candidate: {
      stationCode, channel, phase, arrivalMs, polarity: 'NONE',
      confidenceLowMs: ci ? arrivalMs - Number(ci) : null,
      confidenceHighMs: ci ? arrivalMs + Number(ci) : null,
      weight: 1, source: 'HUMAN' } };
    try {
      await api(`/api/interpretations/${$('interpSel').value}/actions`,
        { method: 'POST', body: JSON.stringify(body) });
      await refresh();
    } catch (e) { toast(e.message, true); }
  });
  $('btnMerge').addEventListener('click', async () => {
    const ids = [...state.selected].sort((a, b) => a - b);
    if (ids.length < 2) return toast('请勾选至少两个候选', true);
    try {
      await api(`/api/interpretations/${$('interpSel').value}/actions`, {
        method: 'POST',
        body: JSON.stringify({ type: 'MERGE', survivorId: ids[0], mergedIds: ids.slice(1) }),
      });
      state.selected.clear();
      await refresh();
    } catch (e) { toast(e.message, true); }
  });
  $('btnNoise').addEventListener('click', async () => {
    for (const id of state.selected) {
      await api(`/api/interpretations/${$('interpSel').value}/candidates/${id}/noise`,
        { method: 'POST', body: JSON.stringify({ noise: true }) });
    }
    state.selected.clear();
    await refresh();
  });
  $('btnActive').addEventListener('click', async () => {
    const id = Number(prompt('要恢复的候选 ID'));
    await api(`/api/interpretations/${$('interpSel').value}/candidates/${id}/noise`,
      { method: 'POST', body: JSON.stringify({ noise: false }) });
    await refresh();
  });
  $('btnClockApply').addEventListener('click', async () => {
    try {
      await api(`/api/interpretations/${$('interpSel').value}/actions`, {
        method: 'POST',
        body: JSON.stringify({ type: 'CLOCK', stationCode: $('clockStation').value,
          afterMs: Number($('clockMs').value) }),
      });
      await refresh();
    } catch (e) { toast(e.message, true); }
  });
  $('btnClockReport').addEventListener('click', async () => {
    try {
      const report = await api(`/api/interpretations/${$('interpSel').value}/clock-report`);
      toast(report.consistent ? '校时次序一致' : JSON.stringify(report.minimumConflictStations), !report.consistent);
    } catch (e) { toast(e.message, true); }
  });
  $('btnFreeze').addEventListener('click', async () => {
    try {
      const result = await api(`/api/interpretations/${$('interpSel').value}/freeze`,
        { method: 'POST' });
      toast('已冻结: headSeq=' + result.headSeq + ' 台站钉版=' + result.stationPinVersion);
      await loadInterps($('interpSel').value);
    } catch (e) { toast(e.message, true); }
  });
  $('btnBranch').addEventListener('click', async () => {
    const newCode = prompt('新分支代码');
    if (!newCode) return;
    const model = prompt('替换速度模型代码（留空保持原模型）', '');
    try {
      await api('/api/interpretations/branch', {
        method: 'POST',
        body: JSON.stringify({ sourceCode: $('interpSel').value, newCode,
          velocityModelCode: model || null }),
      });
      await loadInterps(newCode);
      toast('分支已创建');
    } catch (e) { toast(e.message, true); }
  });
  $('btnCompare').addEventListener('click', () => runCompare(false).catch((e) => toast(e.message, true)));
}

(async function init() {
  wire();
  try {
    await loadWaveforms();
    await loadInterps();
  } catch (e) {
    toast(e.message, true);
  }
})();
