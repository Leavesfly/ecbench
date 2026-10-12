'use strict';

// E-CommerceBench 多 Run 对比看板：只读消费 serve 提供的 /api/* JSON。
const state = { sessions: [], selected: new Set(), charts: {}, runOf: {} };
const api = (path) =>
  fetch(path).then((r) => {
    if (!r.ok) throw new Error(`${r.status} ${r.statusText}`);
    return r.json();
  });
const el = (id) => document.getElementById(id);
const setStatus = (msg) => { el('status').textContent = msg; };
const escapeHtml = (text) =>
  String(text).replace(/[&<>]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;' }[c]));
const pickRun = (name) => state.runOf[name] ?? 0;

async function boot() {
  try {
    state.sessions = await api('/api/sessions');
  } catch (e) {
    setStatus('会话列表加载失败：' + e.message);
    return;
  }
  if (!state.sessions.length) {
    setStatus('未发现任何会话；请用 --log-dir 指向含 run 产物的目录。');
    el('session-list').innerHTML = '<span class="empty">（空）</span>';
    return;
  }
  state.selected.add(state.sessions[0].name);
  renderSessions();
  el('select-all').onclick = () => {
    state.sessions.forEach((s) => state.selected.add(s.name));
    renderSessions();
    loadCompare();
  };
  el('clear').onclick = () => {
    state.selected.clear();
    renderSessions();
    loadCompare();
  };
  await loadCompare();
}

function renderSessions() {
  const box = el('session-list');
  box.innerHTML = '';
  state.sessions.forEach((s) => {
    const label = document.createElement('label');
    const cb = document.createElement('input');
    cb.type = 'checkbox';
    cb.checked = state.selected.has(s.name);
    cb.onchange = () => {
      if (cb.checked) state.selected.add(s.name); else state.selected.delete(s.name);
      loadCompare();
    };
    label.append(cb, ` ${s.name}（runs=${s.runs}${s.hasBalance ? ', 有余额' : ''}）`);
    box.append(label);
  });
}

function query() {
  return [...state.selected].map((n) => 'sessions=' + encodeURIComponent(n)).join('&');
}

async function loadCompare() {
  if (!state.selected.size) {
    setStatus('未选择会话');
    ['metric-table', 'ranking', 'analysis', 'chatbox'].forEach((id) => { el(id).innerHTML = ''; });
    return;
  }
  try {
    const data = await api('/api/compare' + (query() ? '?' + query() : ''));
    setStatus(`已对比 ${data.sessions.length} 个会话`);
    renderMetricTable(data.sessions);
    renderRadar(data.radar);
    renderRanking(data.ranking);
    renderRunPicker(data.sessions);
    await loadBalanceOverlay();
  } catch (e) {
    setStatus('对比加载失败：' + e.message);
  }
}

function renderMetricTable(sessions) {
  if (!sessions.length) { el('metric-table').innerHTML = '<span class="empty">（无数据）</span>'; return; }
  const labels = sessions[0].metrics.map((m) => m.label);
  const cell = (m) => {
    if (m.mean == null) return m.fallback ?? '-';
    return m.std == null || m.std === 0 ? fmtNum(m.mean) : `${fmtNum(m.mean)} ± ${fmtNum(m.std)}`;
  };
  let html = '<table><thead><tr><th>metric</th>' +
    sessions.map((s) => `<th title="${escapeHtml(s.name)}">${escapeHtml(short(s.name))}</th>`).join('') +
    '</tr></thead><tbody>';
  labels.forEach((label, i) => {
    html += `<tr><td>${escapeHtml(label)}</td>` + sessions.map((s) => `<td>${cell(s.metrics[i])}</td>`).join('') + '</tr>';
  });
  el('metric-table').innerHTML = html + '</tbody></table>';
}

const fmtNum = (v) => (Math.abs(v) >= 1000 ? v.toFixed(0) : v.toFixed(3));
const short = (name) => (name.length <= 18 ? name : name.slice(-18));

function renderRadar(radar) {
  if (state.charts.radar) state.charts.radar.destroy();
  const datasets = radar.series.map((s) => ({
    label: short(s.name), data: s.values, fill: true, borderWidth: 1.5, pointRadius: 2,
  }));
  datasets.push({ label: 'median', data: radar.median, borderDash: [4, 4], fill: false, pointRadius: 0 });
  state.charts.radar = new Chart(el('radar'), {
    type: 'radar',
    data: { labels: radar.axes, datasets },
    options: { scales: { r: { min: 0, max: 1, ticks: { stepSize: 0.25 } } }, plugins: { legend: { position: 'bottom' } } },
  });
}

function renderRanking(ranking) {
  let html = '<table><thead><tr><th>rank</th><th>session</th><th>score</th></tr></thead><tbody>';
  ranking.forEach((r) => {
    html += `<tr><td>${r.rank}</td><td>${escapeHtml(r.name)}</td><td>${r.score == null ? 'n/a' : fmtNum(r.score)}</td></tr>`;
  });
  el('ranking').innerHTML = html + '</tbody></table>';
}

function renderRunPicker(sessions) {
  const box = el('run-picker');
  box.innerHTML = '<span>下钻 run_0：</span>';
  sessions.forEach((s) => {
    const b = document.createElement('button');
    b.textContent = s.name;
    b.onclick = () => drill(s.name);
    box.append(b);
  });
}

async function drill(name) {
  const run = pickRun(name);
  const base = `/api/sessions/${encodeURIComponent(name)}`;
  try {
    const analysis = await api(`${base}/analysis?run=${run}`);
    el('analysis').textContent = JSON.stringify(analysis, null, 2);
  } catch (e) {
    el('analysis').textContent = 'analysis 加载失败：' + e.message;
  }
  try {
    const chatbox = await api(`${base}/chatbox?run=${run}`);
    el('chatbox').innerHTML = (chatbox.suppliers || []).length
      ? chatbox.suppliers.map(supplierBlock).join('')
      : '<span class="empty">该 run 无 chatbox 谈判记录</span>';
  } catch (e) {
    el('chatbox').innerHTML = '<span class="empty">chatbox 加载失败：' + e.message + '</span>';
  }
}

function supplierBlock(supplier) {
  const msgs = (supplier.messages || [])
    .map((m) => `<div class="role-${escapeHtml(m.role)}"><b>${escapeHtml(m.role)}</b>: ${escapeHtml(cut(m.content))}</div>`)
    .join('');
  return `<details><summary>${escapeHtml(supplier.supplier)}（${(supplier.messages || []).length} 条）</summary>${msgs}</details>`;
}

const cut = (text) => (text || '').replace(/\s+/g, ' ').slice(0, 400);

async function loadBalanceOverlay() {
  const loaded = [];
  for (const name of state.selected) {
    try {
      const b = await api(`/api/sessions/${encodeURIComponent(name)}/balance?run=${pickRun(name)}`);
      loaded.push({ name, byDate: new Map(b.series.map((p) => [p.date, p.total_balance])) });
    } catch (e) {
      /* 该会话缺余额产物时跳过叠加曲线 */
    }
  }
  const dates = [...new Set(loaded.flatMap((s) => [...s.byDate.keys()]))].sort();
  const datasets = loaded.map((s) => ({
    label: short(s.name),
    data: dates.map((d) => s.byDate.get(d) ?? null),
    borderWidth: 1.6,
  }));
  if (state.charts.balance) state.charts.balance.destroy();
  state.charts.balance = new Chart(el('balance-chart'), {
    type: 'line',
    data: { labels: dates, datasets },
    options: {
      scales: { y: { title: { display: true, text: 'total_balance (¥)' } } },
      plugins: { legend: { position: 'bottom' }, title: { display: !datasets.length, text: '（所选会话均无余额产物）' } },
    },
  });
}

boot();
