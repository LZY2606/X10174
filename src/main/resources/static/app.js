"use strict";

const state = {
    stations: [],
    models: [],
    interps: [],
    current: null,
    segments: {},
    timelines: {},
    compare: null,
    diffs: null,
    viewStart: null,
    viewWindow: 22
};

async function api(path, options) {
    const response = await fetch("/api" + path, {
        headers: {"Content-Type": "application/json"},
        ...options
    });
    const text = await response.text();
    const body = text ? JSON.parse(text) : {};
    if (!response.ok) {
        throw new Error(body.error || ("HTTP " + response.status));
    }
    return body;
}

function toast(message, kind) {
    const el = document.getElementById("toast");
    el.textContent = message;
    el.className = kind || "";
    el.style.display = "block";
    clearTimeout(toast.timer);
    toast.timer = setTimeout(() => { el.style.display = "none"; }, 5000);
}

function fmt(value, digits) {
    if (value === null || value === undefined || !Number.isFinite(value)) {
        return "-";
    }
    return Number(value).toFixed(digits === undefined ? 3 : digits);
}

async function loadBase() {
    [state.stations, state.models, state.interps] = await Promise.all([
        api("/stations"), api("/velocity-models"), api("/interpretations")
    ]);
    renderMeta();
    const select = document.getElementById("interpSelect");
    const selected = state.current ? state.current.interpretation.code : select.value;
    select.innerHTML = "";
    for (const interp of state.interps) {
        const option = document.createElement("option");
        option.value = interp.code;
        option.textContent = interp.code + (interp.frozen ? " 🔒" : "") + " - " + interp.name;
        select.appendChild(option);
    }
    if (selected) {
        select.value = selected;
    }
    if (!select.value && state.interps.length > 0) {
        select.value = state.interps[0].code;
    }
}

async function selectInterpretation(code) {
    if (!code) {
        state.current = null;
        document.getElementById("waveformPanel").innerHTML = "";
        return;
    }
    state.current = await api("/interpretations/" + encodeURIComponent(code));
    await loadWaveforms();
    renderPicks();
    renderForms();
    renderEvents();
    renderCompare();
    drawWaveforms();
    drawResiduals();
    if (state.viewStart === null) {
        const times = state.current.picks.map(p => p.time);
        state.viewStart = times.length ? Math.floor(Math.min(...times)) - 2 : 1000000;
    }
    document.getElementById("viewStart").value = state.viewStart;
    document.getElementById("viewWindow").value = state.viewWindow;
}

async function loadWaveforms() {
    state.segments = {};
    state.timelines = {};
    const tracks = await api("/waveforms/tracks");
    for (const track of tracks) {
        const key = track.stationCode + "|" + track.channel;
        const [segments, timeline] = await Promise.all([
            api(`/waveforms/tracks/${track.stationCode}/${track.channel}/segments`),
            api(`/waveforms/tracks/${track.stationCode}/${track.channel}/timeline`)
        ]);
        state.segments[key] = segments;
        state.timelines[key] = timeline;
    }
}

function renderMeta() {
    let html = "<table><tr><th>台站</th><th>纬度</th><th>经度</th><th>海拔</th><th>版本</th></tr>";
    for (const s of state.stations) {
        html += `<tr><td>${s.code} ${s.name}</td><td>${fmt(s.lat, 3)}</td><td>${fmt(s.lon, 3)}</td><td>${fmt(s.elevationM, 0)}</td><td>v${s.version}</td></tr>`;
    }
    html += "</table>";
    html += "<table style='margin-top:8px'><tr><th>模型</th><th>层</th><th>版本</th></tr>";
    for (const m of state.models) {
        const layers = m.layers.map(l => `${fmt(l.topDepthKm, 0)}km Vp${l.vpKms}/Vs${l.vsKms}`).join("<br>");
        html += `<tr><td>${m.code}<br><span class='muted'>${m.name}</span></td><td>${layers}</td><td>v${m.version}</td></tr>`;
    }
    html += "</table>";
    document.getElementById("metaPanel").innerHTML = html;
}

function pickChannel(phase) {
    return phase === "S" ? "BHN" : "BHZ";
}

function drawWaveforms() {
    if (!state.current) {
        return;
    }
    const panel = document.getElementById("waveformPanel");
    panel.innerHTML = "";
    const start = state.viewStart;
    const end = start + Number(document.getElementById("viewWindow").value || state.viewWindow);
    const pinned = state.current.interpretation.stationVersions || {};
    for (const station of state.stations) {
        for (const channel of ["BHZ", "BHN"]) {
            const key = station.code + "|" + channel;
            const segments = state.segments[key] || [];
            const wrap = document.createElement("div");
            wrap.className = "station-wave";
            const label = document.createElement("div");
            label.className = "label";
            label.innerHTML = `<span>${station.code} ${channel}（元数据 v${pinned[station.code] || "?"}）</span>
                <span class="muted">${segments.length} 片段</span>`;
            const canvas = document.createElement("canvas");
            canvas.className = "station-canvas";
            canvas.height = 112;
            canvas.dataset.station = station.code;
            canvas.dataset.channel = channel;
            wrap.appendChild(label);
            wrap.appendChild(canvas);
            panel.appendChild(wrap);
            drawStationCanvas(canvas, segments, key, start, end);
        }
    }
}

function drawStationCanvas(canvas, segments, timelineKey, viewStart, viewEnd) {
    const width = canvas.clientWidth || 760;
    canvas.width = width;
    const ctx = canvas.getContext("2d");
    const h = canvas.height;
    ctx.fillStyle = "#0d1219";
    ctx.fillRect(0, 0, width, h);
    const xOf = t => (t - viewStart) / (viewEnd - viewStart) * width;

    ctx.strokeStyle = "#202b39";
    ctx.lineWidth = 1;
    for (let second = Math.ceil(viewStart); second <= viewEnd; second++) {
        const x = xOf(second);
        ctx.beginPath();
        ctx.moveTo(x, 0);
        ctx.lineTo(x, h);
        ctx.stroke();
    }
    ctx.strokeStyle = "#34465d";
    ctx.beginPath();
    ctx.moveTo(0, h / 2);
    ctx.lineTo(width, h / 2);
    ctx.stroke();

    for (const feature of (state.timelines[timelineKey]?.features || [])) {
        const x1 = Math.max(0, xOf(feature.start));
        const x2 = Math.min(width, xOf(feature.end));
        ctx.fillStyle = feature.kind === "GAP" ? "rgba(255,120,120,.20)" : "rgba(255,210,80,.16)";
        ctx.fillRect(x1, 0, Math.max(1, x2 - x1), h);
        ctx.fillStyle = feature.kind === "GAP" ? "#ff8c8c" : "#e8c86a";
        ctx.font = "10px sans-serif";
        ctx.fillText(feature.kind === "GAP" ? "缺口" : "重叠", Math.min(x1 + 2, width - 34), 12);
    }

    for (const segment of segments) {
        if (segment.endTime < viewStart || segment.startTime > viewEnd) {
            continue;
        }
        const values = segment.samples || [];
        ctx.strokeStyle = segment.clipped ? "#ff8b6b" : "#79d390";
        ctx.lineWidth = 1;
        ctx.beginPath();
        let started = false;
        for (let i = 0; i < values.length; i++) {
            const t = segment.startTime + i / segment.sampleRateHz;
            if (t < viewStart || t > viewEnd) {
                continue;
            }
            const x = xOf(t);
            const y = h / 2 - values[i] * h / 2.4;
            if (!started) {
                ctx.moveTo(x, y);
                started = true;
            } else {
                ctx.lineTo(x, y);
            }
        }
        ctx.stroke();
        ctx.fillStyle = "#7c8ea3";
        ctx.font = "9px sans-serif";
        ctx.fillText(`${segment.sampleRateHz}Hz ${segment.clockOffsetS ? "钟偏" + segment.clockOffsetS + "s" : ""}${segment.clipped ? " 削顶" : ""} #${segment.receivedCount}`,
            Math.max(2, xOf(segment.startTime)), h - 4);
    }

    if (!state.current) {
        return;
    }
    const station = canvas.dataset.station;
    const channel = canvas.dataset.channel;
    for (const pick of state.current.picks) {
        if (pick.stationCode !== station || pickChannel(pick.phase) !== channel) {
            continue;
        }
        const x = xOf(pick.time);
        if (x < -20 || x > width + 20) {
            continue;
        }
        const ciLeft = xOf(pick.time - pick.ciHalfWidthS);
        const ciRight = xOf(pick.time + pick.ciHalfWidthS);
        ctx.fillStyle = pick.status === "SELECTED" ? "rgba(123,226,144,.20)" : "rgba(255,210,138,.16)";
        ctx.fillRect(ciLeft, 16, Math.max(1, ciRight - ciLeft), h - 32);
        ctx.strokeStyle = pick.status === "SELECTED" ? "#7be290" : "#ffd28a";
        ctx.lineWidth = pick.status === "SELECTED" ? 2 : 1;
        ctx.beginPath();
        ctx.moveTo(x, 14);
        ctx.lineTo(x, h - 14);
        ctx.stroke();
        ctx.fillStyle = pick.status === "SELECTED" ? "#7be290" : "#ffd28a";
        ctx.font = "bold 10px sans-serif";
        ctx.fillText(`${pick.phase}#${pick.id} ${pick.polarity || ""}`, x + 3, 14);
        if (pick.status === "NOISE") {
            ctx.strokeStyle = "#ff7070";
            ctx.beginPath();
            ctx.moveTo(x - 6, 18);
            ctx.lineTo(x + 6, h - 18);
            ctx.stroke();
        }
    }
}

function theoreticalRows() {
    // Deterministically request residual rows for the pinned model via the compare endpoint
    // when needed. The first compare result is drawn on the waveforms.
    if (!state.compare) {
        return null;
    }
    const model = state.compare.models.find(m => m.modelId === state.current.interpretation.modelId)
        || state.compare.models[0];
    return model ? model.rows : null;
}

function drawTheoreticalOnWaveforms(rows) {
    if (!rows) {
        return;
    }
    for (const row of rows) {
        const canvas = document.querySelector(
            `canvas.station-canvas[data-station="${row.stationCode}"][data-channel="${pickChannel(row.phase)}"]`);
        if (!canvas) {
            continue;
        }
        const width = canvas.width;
        const h = canvas.height;
        const start = state.viewStart;
        const end = start + Number(document.getElementById("viewWindow").value || state.viewWindow);
        const x = (row.predictedTime - start) / (end - start) * width;
        if (x < 0 || x > width) {
            continue;
        }
        const ctx = canvas.getContext("2d");
        ctx.strokeStyle = row.phase === "P" ? "#7db7ff" : "#c39bff";
        ctx.setLineDash([5, 4]);
        ctx.lineWidth = 1.5;
        ctx.beginPath();
        ctx.moveTo(x, 4);
        ctx.lineTo(x, h - 4);
        ctx.stroke();
        ctx.setLineDash([]);
        ctx.fillStyle = row.phase === "P" ? "#7db7ff" : "#c39bff";
        ctx.font = "bold 9px sans-serif";
        ctx.fillText("理论" + row.phase, x + 3, h - 18);
    }
}

function drawResiduals() {
    const canvas = document.getElementById("residualCanvas");
    const ctx = canvas.getContext("2d");
    const w = canvas.width;
    const h = canvas.height;
    ctx.fillStyle = "#0d1219";
    ctx.fillRect(0, 0, w, h);
    const rows = theoreticalRows();
    if (!rows || rows.length === 0) {
        ctx.fillStyle = "#7c8ea3";
        ctx.font = "13px sans-serif";
        ctx.fillText("选择两个模型运行比较后显示残差图（理论到时也会绘制到波形）", 20, 40);
        return;
    }
    const pad = 56;
    const maxAbs = Math.max(state.compare.windowSeconds, ...rows.map(r => Math.abs(r.residualSeconds)));
    const scale = (w - pad * 2) / (maxAbs * 2 || 1);
    const xZero = pad + maxAbs * scale;
    ctx.strokeStyle = "#34465d";
    ctx.beginPath();
    ctx.moveTo(xZero, 20);
    ctx.lineTo(xZero, h - 30);
    ctx.stroke();
    ctx.strokeStyle = "#5a4d33";
    for (const sign of [-1, 1]) {
        const x = xZero + sign * state.compare.windowSeconds * scale;
        ctx.beginPath();
        ctx.moveTo(x, 20);
        ctx.lineTo(x, h - 30);
        ctx.stroke();
    }
    ctx.fillStyle = "#8fa6bd";
    ctx.font = "11px sans-serif";
    ctx.fillText("-" + state.compare.windowSeconds + "s", xZero - state.compare.windowSeconds * scale - 18, h - 12);
    ctx.fillText("0", xZero - 4, h - 12);
    ctx.fillText("+" + state.compare.windowSeconds + "s", xZero + state.compare.windowSeconds * scale - 4, h - 12);
    rows.forEach((row, i) => {
        const y = 24 + i * 22;
        const x = xZero + row.residualSeconds * scale;
        ctx.strokeStyle = row.withinWindow ? (row.phase === "P" ? "#7db7ff" : "#c39bff") : "#ff7070";
        ctx.lineWidth = Math.max(1, row.weight * 5);
        ctx.beginPath();
        ctx.moveTo(xZero, y);
        ctx.lineTo(x, y);
        ctx.stroke();
        ctx.fillStyle = "#e6edf3";
        ctx.font = "10px sans-serif";
        ctx.fillText(`${row.stationCode}-${row.phase} ${fmt(row.residualSeconds, 2)}s w${row.weight}`,
            Math.min(x + 4, w - 150), y - 4);
    });
}

function renderPicks() {
    const panel = document.getElementById("picksPanel");
    const interp = state.current.interpretation;
    let html = `<div class="muted">拾取版本 v${interp.pickVersion} ${interp.frozen ? "（已冻结）" : ""}</div>`;
    const picks = [...state.current.picks].sort((a, b) =>
        (a.stationCode + a.phase + a.time + a.id).localeCompare(b.stationCode + b.phase + b.time + b.id));
    for (const p of picks) {
        const cls = p.status === "SELECTED" ? "selected" : p.status.toLowerCase();
        html += `<div class="pick-card ${cls}">
            <div><b>#${p.id}</b> <span class="badge ${p.phase.toLowerCase()}">${p.phase}</span>
            ${p.stationCode} @ ${fmt(p.time, 3)}
            <span class="badge ${p.status === "SELECTED" ? "selected-badge" : p.status === "NOISE" ? "noise-badge" : ""}">${p.status}</span>
            ${p.mergedInto ? `→ #${p.mergedInto}` : ""}</div>
            <div class="muted">来源 ${p.source} · 极性 ${p.polarity || "?"} · CI ±${fmt(p.ciHalfWidth, 2)}s · 行内版本 v${p.pickVersion}</div>
            <div class="pick-row">
                <button onclick="movePick(${p.id})">移动</button>
                <button onclick="setPolarity(${p.id}, '+')">+</button>
                <button onclick="setPolarity(${p.id}, '-')">−</button>
                <button onclick="setCi(${p.id})">置信区间</button>
                <button class="good" onclick="setStatus(${p.id}, 'SELECTED')">采用</button>
                <button onclick="setStatus(${p.id}, 'CANDIDATE')">候选</button>
                <button class="danger" onclick="setStatus(${p.id}, 'NOISE')">噪声</button>
                <button onclick="mergePick(${p.id})">合并到…</button>
            </div>
        </div>`;
    }
    panel.innerHTML = html;
}

function renderEvents() {
    const interp = state.current.interpretation;
    let html = `<div>解释 ${interp.code}，模型 #${interp.modelId}，${interp.frozen ? "已冻结" : "未冻结"}</div>`;
    html += "<table><tr><th>#</th><th>事件</th><th>时间</th></tr>";
    for (const e of state.current.events) {
        html += `<tr><td>${e.seq}</td><td>${e.type}</td><td>${e.createdAt}</td></tr>`;
    }
    html += "</table>";
    const corrections = state.current.corrections || {};
    html += "<h2>校时修正</h2><table><tr><th>台站</th><th>秒</th></tr>";
    for (const [code, shift] of Object.entries(corrections)) {
        html += `<tr><td>${code}</td><td>${fmt(shift, 3)}</td></tr>`;
    }
    html += "</table>";
    if (state.current.frozenBundle) {
        html += "<div class='muted'>冻结快照已保存；可使用旧版本重放验证。</div>";
    }
    document.getElementById("eventPanel").innerHTML = html;
}

function modelOptions(skip) {
    return state.models.filter(m => !skip || m.id !== skip)
        .map(m => `<option value="${m.id}">${m.code} v${m.version}</option>`).join("");
}

function renderForms() {
    const frozen = state.current.interpretation.frozen;
    const disabled = frozen ? "disabled" : "";
    document.getElementById("formsPanel").innerHTML = `
    <form id="pickForm" class="inline-form" ${disabled}>
        <label>台站<select name="stationCode">${state.stations.map(s => `<option>${s.code}</option>`).join("")}</select></label>
        <label>震相<select name="phase"><option>P</option><option>S</option></select></label>
        <label>时间<input name="time" type="number" step="0.001" value="1000006"></label>
        <label>CI ±秒<input name="ciHalfWidth" type="number" step="0.01" value="0.2"></label>
        <label>极性<select name="polarity"><option>+</option><option>-</option><option>?</option></select></label>
        <label>来源<select name="source"><option>ANALYST</option><option>ALGORITHM</option><option>MANUAL</option></select></label>
        <label class="full"><input name="selected" type="checkbox" checked> 作为当前采用候选</label>
        <button class="full good" ${disabled}>添加候选</button>
    </form>
    <form id="correctionForm" class="inline-form" ${disabled}>
        <label>台站<select name="stationCode">${state.stations.map(s => `<option>${s.code}</option>`).join("")}</select></label>
        <label>校时秒<input name="shiftSeconds" type="number" step="0.01" value="0"></label>
        <button class="full" ${disabled}>应用校时修正</button>
    </form>
    <form id="compareForm" class="inline-form">
        <label>模型 A<select name="a">${modelOptions()}</select></label>
        <label>模型 B<select name="b">${modelOptions()}</select></label>
        <button class="full">比较并绘制理论到时/残差</button>
    </form>
    <form id="branchForm" class="inline-form">
        <label>新代码<input name="code" type="text" value="I-BRANCH-1"></label>
        <label>替换模型<select name="modelId"><option value="">保持当前</option>${modelOptions()}</select></label>
        <button class="full">复制为新分支</button>
    </form>`;

    document.getElementById("pickForm").addEventListener("submit", async event => {
        event.preventDefault();
        const data = Object.fromEntries(new FormData(event.target).entries());
        try {
            await api(`/interpretations/${currentCode()}/picks`, {
                method: "POST",
                body: JSON.stringify({
                    stationCode: data.stationCode, phase: data.phase, time: Number(data.time),
                    ciHalfWidth: Number(data.ciHalfWidth), polarity: data.polarity,
                    source: data.source, selected: event.target.elements.selected.checked
                })
            });
            toast("候选已添加", "success");
            await refreshCurrent();
        } catch (e) { toast(e.message, "error"); }
    });
    document.getElementById("correctionForm").addEventListener("submit", async event => {
        event.preventDefault();
        const data = Object.fromEntries(new FormData(event.target).entries());
        try {
            const result = await api(`/interpretations/${currentCode()}/corrections`, {
                method: "POST",
                body: JSON.stringify({stationCode: data.stationCode, shiftSeconds: Number(data.shiftSeconds)})
            });
            const order = await api(`/interpretations/${currentCode()}/ordering`);
            if (order.reversed) {
                toast(`次序将反转，冻结会被阻止。最小冲突台站集合：${order.minimumConflictStations.join(", ")}`, "error");
            } else {
                toast("校时修正已记录（可撤销）：" + JSON.stringify(result.corrections), "success");
            }
            await refreshCurrent();
        } catch (e) { toast(e.message, "error"); }
    });
    document.getElementById("compareForm").addEventListener("submit", async event => {
        event.preventDefault();
        const data = Object.fromEntries(new FormData(event.target).entries());
        const ids = [...new Set([Number(data.a), Number(data.b)])];
        try {
            state.compare = await api(`/interpretations/${currentCode()}/compare`, {
                method: "POST", body: JSON.stringify({modelIds: ids})
            });
            if (ids.length === 2) {
                state.diffs = await api(`/interpretations/${currentCode()}/station-diffs`, {
                    method: "POST", body: JSON.stringify({modelAId: ids[0], modelBId: ids[1]})
                });
            }
            drawWaveforms();
            drawTheoreticalOnWaveforms(theoreticalRows());
            drawResiduals();
            renderCompare();
            toast(`比较完成：${state.compare.winnerModelCode || "无合格模型（窗口否决）"}`, "success");
        } catch (e) { toast(e.message, "error"); }
    });
    document.getElementById("branchForm").addEventListener("submit", async event => {
        event.preventDefault();
        const data = Object.fromEntries(new FormData(event.target).entries());
        try {
            await api(`/interpretations/${currentCode()}/branch`, {
                method: "POST",
                body: JSON.stringify({code: data.code, modelId: data.modelId ? Number(data.modelId) : null})
            });
            toast("分支已创建，历史与撤销事件已复制", "success");
            await loadBase();
            document.getElementById("interpSelect").value = data.code;
            await selectInterpretation(data.code);
        } catch (e) { toast(e.message, "error"); }
    });
}

function renderCompare() {
    const panel = document.getElementById("comparePanel");
    if (!state.compare) {
        panel.innerHTML = "<div class='muted'>选择模型运行比较。超出窗口的模型不会因平均残差小而获胜。</div>";
        return;
    }
    let html = `<div class="muted">${state.compare.rule}</div>`;
    html += `<div>获胜：<b>${state.compare.winnerModelCode || "无（全部被窗口否决）"}</b></div>`;
    for (const m of state.compare.models) {
        html += `<div class="compare-model ${state.compare.winnerModelCode === m.modelCode ? "winner" : ""}">
            <b>${m.modelCode} v${m.modelVersion}</b> ${m.eligible ? "窗口合格" : "窗口否决"}
            <div>加权平均绝对残差 ${fmt(m.weightedMeanAbsResidual, 3)}s；普通均值 ${fmt(m.meanAbsResidual, 3)}s；窗口外 ${m.outsideWindowCount}</div>
            <table><tr><th>台站</th><th>震相</th><th>预测</th><th>观测</th><th>残差</th><th>权重</th></tr>`;
        for (const r of m.rows) {
            html += `<tr><td>${r.stationCode} v${r.stationVersion}</td><td>${r.phase}</td><td>${fmt(r.predictedTime, 2)}</td><td>${fmt(r.observedTime, 2)}</td><td style="color:${r.withinWindow ? "#cfe8ff" : "#ff8c8c"}">${fmt(r.residualSeconds, 2)}</td><td>${r.weight}</td></tr>`;
        }
        html += "</table></div>";
    }
    if (state.diffs) {
        html += "<h2>逐台站预测差</h2><table><tr><th>台站</th><th>相</th><th>预测差(B-A)</th><th>残差差</th></tr>";
        for (const d of state.diffs.diffs) {
            html += `<tr><td>${d.stationCode}</td><td>${d.phase}</td><td>${fmt(d.predictedDifferenceSeconds, 3)}</td><td>${fmt(d.residualDifferenceSeconds, 3)}</td></tr>`;
        }
        html += "</table>";
    }
    panel.innerHTML = html;
}

function currentCode() {
    return document.getElementById("interpSelect").value;
}

async function refreshCurrent() {
    await loadBase();
    await selectInterpretation(currentCode());
}

async function postPickAction(path, body) {
    try {
        await api(path, {method: "POST", body: JSON.stringify(body || {})});
        await refreshCurrent();
    } catch (e) {
        toast(e.message, "error");
    }
}

async function movePick(id) {
    const value = prompt("新的拾取时间（秒）");
    if (value === null) { return; }
    await postPickAction(`/interpretations/${currentCode()}/picks/${id}/move`, {time: Number(value)});
}
async function setPolarity(id, polarity) {
    await postPickAction(`/interpretations/${currentCode()}/picks/${id}/move`, {polarity});
}
async function setCi(id) {
    const value = prompt("置信区间半宽（秒）");
    if (value === null) { return; }
    await postPickAction(`/interpretations/${currentCode()}/picks/${id}/move`, {ciHalfWidth: Number(value)});
}
async function setStatus(id, status) {
    await postPickAction(`/interpretations/${currentCode()}/picks/${id}/status`, {status});
}
async function mergePick(loser) {
    const winner = Number(prompt("合并到哪个候选拾取 ID？"));
    if (!winner) { return; }
    await postPickAction(`/interpretations/${currentCode()}/picks/merge`, {winningPickId: winner, losingPickId: loser});
}

document.getElementById("interpSelect").addEventListener("change", async event => {
    state.compare = null;
    state.diffs = null;
    state.viewStart = null;
    await selectInterpretation(event.target.value);
});
document.getElementById("seedBtn").addEventListener("click", async () => {
    try {
        const result = await api("/demo/seed?force=true", {method: "POST"});
        toast("虚拟数据：" + JSON.stringify(result), "success");
        state.viewStart = null;
        await loadBase();
        if (state.interps.length) {
            document.getElementById("interpSelect").value = state.interps[0].code;
            await selectInterpretation(state.interps[0].code);
        }
    } catch (e) { toast(e.message, "error"); }
});
document.getElementById("refreshBtn").addEventListener("click", refreshCurrent);
document.getElementById("undoBtn").addEventListener("click", async () => {
    try {
        const result = await api(`/interpretations/${currentCode()}/undo`, {method: "POST"});
        toast("已撤销：" + result.undoneType, "success");
        await refreshCurrent();
    } catch (e) { toast(e.message, "error"); }
});
document.getElementById("freezeBtn").addEventListener("click", async () => {
    try {
        const result = await api(`/interpretations/${currentCode()}/freeze`, {method: "POST"});
        if (result.blocked) {
            toast(`${result.reason}\n冲突对：${result.analysis.conflictPairs.map(p => p.earlierStation + "↔" + p.laterStation).join(", ")}\n最小冲突台站集合：${result.analysis.minimumConflictStations.join(", ")}`, "error");
        } else {
            toast("解释已冻结，版本组合已钉住", "success");
        }
        await refreshCurrent();
    } catch (e) { toast(e.message, "error"); }
});
document.getElementById("replayBtn").addEventListener("click", async () => {
    try {
        const result = await api(`/interpretations/${currentCode()}/replay`, {method: "GET"});
        state.replay = result;
        renderEvents();
        toast(`重放完成，残差一致：${result.identical}；模型版本 v${result.pinnedModelVersion}→v${result.currentModelVersion}`, "success");
    } catch (e) { toast(e.message, "error"); }
});
document.getElementById("branchBtn").addEventListener("click", () => {
    document.getElementById("branchForm").scrollIntoView({behavior: "smooth"});
});
document.getElementById("applyRange").addEventListener("click", () => {
    state.viewStart = Number(document.getElementById("viewStart").value);
    state.viewWindow = Number(document.getElementById("viewWindow").value);
    drawWaveforms();
    drawTheoreticalOnWaveforms(theoreticalRows());
});
window.addEventListener("resize", () => {
    drawWaveforms();
    drawTheoreticalOnWaveforms(theoreticalRows());
});

(async function init() {
    try {
        await loadBase();
        if (state.interps.length === 0) {
            const seeded = await api("/demo/seed?force=false", {method: "POST"});
            if (seeded.seeded) {
                toast("已自动载入固定虚拟数据", "success");
                await loadBase();
            }
        }
        if (state.interps.length > 0) {
            document.getElementById("interpSelect").value = state.interps[0].code;
            await selectInterpretation(state.interps[0].code);
        }
    } catch (e) {
        toast(e.message, "error");
    }
})();
