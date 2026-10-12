'use strict';

/* E-CommerceBench telemetry console.
   只读消费 serve 的 /api/* JSON；图表为自绘 SVG，无任何外部依赖。 */

const PALETTE = ['#f0a63c', '#57c6d6', '#82c98f', '#e2685c', '#b48ce0', '#e8d06a', '#6fa8dc', '#d98cb3'];
const state = { sessions: [], selected: new Set(), drilled: null, balance: new Map() };

const $ = (id) => document.getElementById(id);

async function api(path) {
  const res = await fetch(path);
  if (!res.ok) throw new Error(`${res.status} ${res.statusText}`);
  return res.json();
}

function esc(text) {
  return String(text ?? '').replace(/[&<>"']/g, (c) => ({
    '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;',
  }[c]));
}

function fmtNum(value) {
  if (value === null || value === undefined || Number.isNaN(value)) return 'n/a';
  const abs = Math.abs(value);
  if (abs >= 1000) return value.toLocaleString('en-US', { maximumFractionDigits: 0 });
  if (abs >= 1) return value.toFixed(2);
  return value.toFixed(3);
}

const fmtMoney = (v) => (v === null || v === undefined ? 'n/a' : '¥' + fmtNum(v));
const setStatus = (msg, tone) => {
  $('status').textContent = msg;
  const dot = $('status-dot');
  dot.className = 'dot' + (tone ? ' ' + tone : '');
};
const shortName = (name) => (name.length > 26 ? '…' + name.slice(-25) : name);

/* ---------------------------------------------------------------- boot */

async function boot() {
  try {
    state.sessions = await api('/api/sessions');
  } catch (error) {
    setStatus('无法读取会话列表：' + error.message, 'bad');
    $('session-list').innerHTML = '<p class="hint">加载失败</p>';
    return;
  }
  if (!state.sessions.length) {
    setStatus('日志目录下没有会话', 'bad');
    $('session-list').innerHTML = '<p class="hint">未发现会话。请用 --log-dir 指向含 run 产物的目录。</p>';
    return;
  }
  // 默认选中信息最全的会话（run 最多，其次有余额产物）
  const best = [...state.sessions].sort((a, b) => (b.runs - a.runs) || (b.hasBalance - a.hasBalance))[0];
  state.selected.add(best.name);

  $('select-all').onclick = () => {
    state.sessions.forEach((s) => state.selected.add(s.name));
    renderSessions();
    loadCompare();
  };
  $('clear').onclick = () => {
    state.selected.clear();
    renderSessions();
    loadCompare();
  };
  $('metric-filter').oninput = () => filterMetricRows();

  renderSessions();
  observeReveals();
  await loadCompare();
}

function renderSessions() {
  const box = $('session-list');
  box.innerHTML = '';
  state.sessions.forEach((session) => {
    const on = state.selected.has(session.name);
    const label = document.createElement('label');
    label.className = 'session' + (on ? ' on' : '');
    const checkbox = document.createElement('input');
    checkbox.type = 'checkbox';
    checkbox.checked = on;
    checkbox.onchange = () => {
      if (checkbox.checked) state.selected.add(session.name);
      else state.selected.delete(session.name);
      renderSessions();
      loadCompare();
    };
    const name = document.createElement('span');
    name.className = 'name';
    name.textContent = session.name;
    const meta = document.createElement('span');
    meta.className = 'meta';
    meta.textContent = `r${session.runs}${session.hasBalance ? ' ·bal' : ''}`;
    label.append(checkbox, name, meta);
    box.append(label);
  });
}

/* ------------------------------------------------------------ compare */

async function loadCompare() {
  const chosen = [...state.selected];
  if (!chosen.length) {
    setStatus('未选择会话');
    ['ranking', 'metric-table', 'radar-legend'].forEach((id) => { $(id).innerHTML = ''; });
    $('radar').innerHTML = '';
    $('balance-chart').innerHTML = '';
    $('radar-note').textContent = '';
    $('balance-note').textContent = '';
    renderKpis(null);
    return;
  }
  const query = chosen.map((n) => 'sessions=' + encodeURIComponent(n)).join('&');
  try {
    const data = await api('/api/compare?' + query);
    setStatus(`已对比 ${data.sessions.length} 个会话`, 'live');
    renderKpis(data);
    renderRadar(data.radar, data.sessions);
    renderRanking(data.ranking);
    renderMetricTable(data.sessions);
    renderRunPicker(data.sessions);
    await loadBalance(chosen);
  } catch (error) {
    setStatus('对比加载失败：' + error.message, 'bad');
  }
}

function renderKpis(data) {
  if (!data || !data.sessions.length) {
    ['kpi-score', 'kpi-sessions', 'kpi-axes', 'kpi-calls'].forEach((id) => { $(id).textContent = '—'; });
    ['kpi-score-who', 'kpi-runs', 'kpi-calls-note'].forEach((id) => { $(id).textContent = ''; });
    return;
  }
  const top = data.ranking.find((r) => r.score !== null) || null;
  $('kpi-score').textContent = top ? fmtMoney(top.score) : 'n/a';
  $('kpi-score-who').textContent = top ? shortName(top.name) : '无有效主分';

  const runs = data.sessions.reduce((sum, s) => sum + s.runCount, 0);
  $('kpi-sessions').textContent = String(data.sessions.length);
  $('kpi-runs').textContent = `${runs} run(s)`;

  const withData = data.radar.axes.filter((_, i) => data.radar.series.some((s) => s.values[i] !== null)).length;
  $('kpi-axes').textContent = `${withData} / 7`;

  const calls = data.sessions
    .map((s) => (s.metrics.find((m) => m.label === 'tool_calls') || {}).mean)
    .filter((v) => typeof v === 'number');
  $('kpi-calls').textContent = calls.length ? fmtNum(calls.reduce((a, b) => a + b, 0) / calls.length) : 'n/a';
  $('kpi-calls-note').textContent = calls.length ? `${calls.length} session(s) 有数据` : '无工具调用统计';
}

/* -------------------------------------------------------------- radar */

function renderRadar(radar, sessions) {
  const svg = $('radar');
  const cx = 210;
  const cy = 186;
  const radius = 126;
  const axes = radar.axes;
  const count = axes.length;
  const step = (Math.PI * 2) / count;
  const pointAt = (index, value) => {
    const angle = -Math.PI / 2 + index * step;
    return [cx + radius * value * Math.cos(angle), cy + radius * value * Math.sin(angle)];
  };
  const liveAxes = new Set();
  radar.series.forEach((s) => s.values.forEach((v, i) => { if (v !== null) liveAxes.add(i); }));

  let markup = '';
  [0.25, 0.5, 0.75, 1].forEach((ring, i) => {
    const pts = axes.map((_, idx) => pointAt(idx, ring).map((n) => n.toFixed(1)).join(',')).join(' ');
    markup += `<polygon class="ring${i === 3 ? ' outer' : ''}" points="${pts}" />`;
  });
  axes.forEach((_, idx) => {
    const [x, y] = pointAt(idx, 1);
    markup += `<line class="spoke" x1="${cx}" y1="${cy}" x2="${x.toFixed(1)}" y2="${y.toFixed(1)}" />`;
  });
  [0.5, 1].forEach((ring) => {
    const [, y] = pointAt(0, ring);
    markup += `<text class="axis-tick" x="${cx + 5}" y="${y.toFixed(1)}">${ring.toFixed(2)}</text>`;
  });
  axes.forEach((label, idx) => {
    const [x, y] = pointAt(idx, 1.2);
    const anchor = Math.abs(x - cx) < 12 ? 'middle' : (x > cx ? 'start' : 'end');
    const cls = liveAxes.has(idx) ? 'axis-label' : 'axis-label muted';
    const text = label.length > 22 ? label.slice(0, 21) + '…' : label;
    markup += `<text class="${cls}" x="${x.toFixed(1)}" y="${y.toFixed(1)}" text-anchor="${anchor}">${esc(text)}</text>`;
  });

  const skipped = [];
  radar.series.forEach((series, index) => {
    const color = PALETTE[index % PALETTE.length];
    const usable = series.values.map((v, i) => ({ i, v })).filter((p) => p.v !== null);
    if (!usable.length) {
      skipped.push(`${shortName(series.name)}：七轴全缺样本`);
      return;
    }
    if (usable.length < 3) {
      usable.forEach((p) => {
        const [x, y] = pointAt(p.i, p.v);
        markup += `<circle class="series-dot" cx="${x.toFixed(1)}" cy="${y.toFixed(1)}" r="3" fill="${color}" />`;
      });
      skipped.push(`${shortName(series.name)}：仅 ${usable.length} 轴有值（不足以成面）`);
      return;
    }
    const pts = usable.map((p) => pointAt(p.i, p.v).map((n) => n.toFixed(1)).join(','));
    const closed = usable.length === count;
    markup += `<polygon class="series-poly" points="${pts.join(' ')}" fill="${color}" stroke="${color}"`
      + `${closed ? '' : ' stroke-dasharray="5 3"'} />`;
    usable.forEach((p) => {
      const [x, y] = pointAt(p.i, p.v);
      markup += `<circle class="series-dot" cx="${x.toFixed(1)}" cy="${y.toFixed(1)}" r="2.6" fill="${color}">`
        + `<title>${esc(series.name)} · ${esc(axes[p.i])} = ${p.v.toFixed(3)}</title></circle>`;
    });
  });

  const medianUsable = radar.median.map((v, i) => ({ i, v })).filter((p) => p.v !== null);
  if (medianUsable.length >= 3) {
    const pts = medianUsable.map((p) => pointAt(p.i, p.v).map((n) => n.toFixed(1)).join(','));
    markup += `<polygon class="median-poly" points="${pts.join(' ')}" />`;
  }
  svg.innerHTML = markup;

  $('radar-legend').innerHTML = radar.series
    .map((s, i) => `<span><i style="background:${PALETTE[i % PALETTE.length]}"></i>${esc(shortName(s.name))}</span>`)
    .join('') + '<span><i style="background:transparent;border-style:dashed"></i>median</span>';

  const notes = [];
  if (skipped.length) notes.push('未成面：' + skipped.join('；'));
  if (liveAxes.size < count) notes.push(`${count - liveAxes.size} 个轴全部会话缺样本（灰色标注）`);
  if (sessions.length === 1) notes.push('单会话对比时各轴归一化退化为 1.0，仅反映“有/无数据”');
  $('radar-note').textContent = notes.join(' · ');
}

/* ------------------------------------------------------------ ranking */

function renderRanking(ranking) {
  if (!ranking.length) {
    $('ranking').innerHTML = '<p class="hint">无排名数据</p>';
    return;
  }
  const rows = ranking.map((r) => `
    <tr class="${r.rank === 1 ? 'rank-1' : ''}">
      <td><span class="badge">${r.rank}</span> ${esc(shortName(r.name))}</td>
      <td class="${r.score === null ? 'na' : ''}">${r.score === null ? 'n/a' : fmtMoney(r.score)}</td>
    </tr>`).join('');
  $('ranking').innerHTML =
    '<table><thead><tr><th>session</th><th>primary score</th></tr></thead><tbody>' + rows + '</tbody></table>';
}

/* -------------------------------------------------------- metric grid */

let metricRows = [];

function renderMetricTable(sessions) {
  if (!sessions.length) {
    $('metric-table').innerHTML = '<p class="hint">无数据</p>';
    metricRows = [];
    return;
  }
  const labels = sessions[0].metrics.map((m) => m.label);
  metricRows = labels.map((label) => {
    const cells = sessions.map((s) => {
      const stat = s.metrics.find((m) => m.label === label) || {};
      if (stat.mean === null || stat.mean === undefined) return { text: stat.fallback ?? '-', na: true };
      const spread = stat.std ? ` ±${fmtNum(stat.std)}` : '';
      return { text: fmtNum(stat.mean) + spread, na: false };
    });
    return { label, cells };
  });
  paintMetricRows();
}

function paintMetricRows() {
  const head = state.sessions.filter((s) => state.selected.has(s.name)).map((s) => `<th>${esc(shortName(s.name))}</th>`);
  const body = metricRows.map((row) => `
    <tr data-label="${esc(row.label.toLowerCase())}">
      <td>${esc(row.label)}</td>
      ${row.cells.map((c) => `<td class="${c.na ? 'na' : ''}">${esc(c.text)}</td>`).join('')}
    </tr>`).join('');
  $('metric-table').innerHTML =
    `<table><thead><tr><th>metric</th>${head.join('')}</tr></thead><tbody>${body}</tbody></table>`;
  filterMetricRows();
}

function filterMetricRows() {
  const needle = ($('metric-filter').value || '').trim().toLowerCase();
  document.querySelectorAll('#metric-table tbody tr').forEach((tr) => {
    tr.hidden = Boolean(needle) && !tr.dataset.label.includes(needle);
  });
}

/* ------------------------------------------------------------ balance */

async function loadBalance(chosen) {
  state.balance.clear();
  for (const name of chosen) {
    try {
      const payload = await api(`/api/sessions/${encodeURIComponent(name)}/balance?run=0`);
      if (payload.series && payload.series.length) state.balance.set(name, payload.series);
    } catch (error) {
      /* 该会话无余额产物：跳过，不影响其它曲线 */
    }
  }
  drawBalance();
}

function drawBalance() {
  const svg = $('balance-chart');
  const legend = $('balance-legend');
  const note = $('balance-note');
  if (!state.balance.size) {
    svg.innerHTML = '';
    legend.innerHTML = '';
    note.textContent = '所选会话均无余额产物（balance/run_0_daily_balance.csv）';
    return;
  }
  const dates = [...new Set([...state.balance.values()].flatMap((s) => s.map((p) => p.date)))].sort();
  const values = [...state.balance.values()].flatMap((s) => s.map((p) => p.totalBalance ?? p.total_balance));
  const lo = Math.min(...values);
  const hi = Math.max(...values);
  const pad = (hi - lo) * 0.12 || Math.max(1, hi * 0.02);
  const yMin = lo - pad;
  const yMax = hi + pad;

  const left = 74;
  const right = 18;
  const top = 16;
  const bottom = 34;
  const width = 960 - left - right;
  const height = 300 - top - bottom;
  const xAt = (i) => left + (dates.length === 1 ? width / 2 : (width * i) / (dates.length - 1));
  const yAt = (v) => top + height - ((v - yMin) / (yMax - yMin)) * height;

  let markup = '';
  for (let t = 0; t <= 4; t++) {
    const value = yMin + ((yMax - yMin) * t) / 4;
    const y = yAt(value);
    markup += `<line class="grid-line" x1="${left}" y1="${y.toFixed(1)}" x2="${left + width}" y2="${y.toFixed(1)}" />`;
    markup += `<text class="axis-text" x="${left - 9}" y="${(y + 3).toFixed(1)}" text-anchor="end">${fmtNum(value)}</text>`;
  }
  const every = Math.ceil(dates.length / 9);
  dates.forEach((date, i) => {
    if (i % every && i !== dates.length - 1) return;
    markup += `<text class="axis-text" x="${xAt(i).toFixed(1)}" y="${top + height + 18}" text-anchor="middle">${esc(date)}</text>`;
  });

  [...state.balance.entries()].forEach(([name, series], index) => {
    const color = PALETTE[index % PALETTE.length];
    const byDate = new Map(series.map((p) => [p.date, p.totalBalance ?? p.total_balance]));
    const pts = dates.map((d, i) => (byDate.has(d) ? [xAt(i), yAt(byDate.get(d))] : null));
    const segments = [];
    let current = [];
    pts.forEach((p) => {
      if (p) current.push(p);
      else if (current.length) { segments.push(current); current = []; }
    });
    if (current.length) segments.push(current);
    segments.forEach((segment) => {
      const line = segment.map(([x, y], k) => `${k ? 'L' : 'M'}${x.toFixed(1)},${y.toFixed(1)}`).join('');
      if (segment.length > 1) {
        const area = `${line}L${segment[segment.length - 1][0].toFixed(1)},${(top + height).toFixed(1)}`
          + `L${segment[0][0].toFixed(1)},${(top + height).toFixed(1)}Z`;
        markup += `<path class="area-path" d="${area}" fill="${color}" />`;
      }
      markup += `<path class="line-path" d="${line}" stroke="${color}" />`;
      segment.forEach(([x, y]) => {
        markup += `<circle cx="${x.toFixed(1)}" cy="${y.toFixed(1)}" r="2.6" fill="${color}" stroke="#11151b" stroke-width="1" />`;
      });
    });
  });
  markup += `<line id="crosshair" class="crosshair" y1="${top}" y2="${top + height}" />`;
  svg.innerHTML = markup;

  legend.innerHTML = [...state.balance.keys()]
    .map((name, i) => `<span><i style="background:${PALETTE[i % PALETTE.length]}"></i>${esc(shortName(name))}</span>`)
    .join('');
  note.textContent = `${state.balance.size} 条曲线 · ${dates.length} 个交易日`;

  const crosshair = $('crosshair');
  const tooltip = ensureTooltip();
  svg.onmousemove = (event) => {
    const box = svg.getBoundingClientRect();
    const x = ((event.clientX - box.left) / box.width) * 960;
    let nearest = 0;
    let bestDistance = Infinity;
    dates.forEach((_, i) => {
      const distance = Math.abs(xAt(i) - x);
      if (distance < bestDistance) { bestDistance = distance; nearest = i; }
    });
    crosshair.setAttribute('x1', xAt(nearest).toFixed(1));
    crosshair.setAttribute('x2', xAt(nearest).toFixed(1));
    crosshair.classList.add('on');
    const lines = [...state.balance.entries()].map(([name, series]) => {
      const point = series.find((p) => p.date === dates[nearest]);
      const value = point ? fmtMoney(point.totalBalance ?? point.total_balance) : 'n/a';
      return `${esc(shortName(name))}: <b>${value}</b>`;
    });
    tooltip.innerHTML = `<div>${esc(dates[nearest])}</div>${lines.join('<br>')}`;
    tooltip.style.left = Math.min(event.clientX + 14, window.innerWidth - 250) + 'px';
    tooltip.style.top = (event.clientY + 12) + 'px';
    tooltip.classList.add('on');
  };
  svg.onmouseleave = () => {
    crosshair.classList.remove('on');
    tooltip.classList.remove('on');
  };
}

function ensureTooltip() {
  let tip = document.querySelector('.tooltip');
  if (!tip) {
    tip = document.createElement('div');
    tip.className = 'tooltip';
    document.body.append(tip);
  }
  return tip;
}

/* ---------------------------------------------------------- drilldown */

function renderRunPicker(sessions) {
  const box = $('run-picker');
  box.innerHTML = '';
  if (!sessions.length) {
    box.innerHTML = '<p class="hint">选择会话后出现</p>';
    return;
  }
  sessions.forEach((session) => {
    const button = document.createElement('button');
    button.type = 'button';
    button.textContent = shortName(session.name);
    button.onclick = () => drill(session.name);
    box.append(button);
  });
}

async function drill(name) {
  state.drilled = name;
  $('drilldown').hidden = false;
  $('drill-name').textContent = name + ' · run_0';
  $('analysis').innerHTML = '<p class="hint">读取中…</p>';
  $('chatbox').innerHTML = '<p class="hint">读取中…</p>';
  const base = `/api/sessions/${encodeURIComponent(name)}`;
  try {
    renderAnalysis(await api(`${base}/analysis?run=0`));
  } catch (error) {
    $('analysis').innerHTML = `<p class="hint">analysis 读取失败：${esc(error.message)}</p>`;
  }
  try {
    renderChatbox(await api(`${base}/chatbox?run=0`));
  } catch (error) {
    $('chatbox').innerHTML = `<p class="hint">chatbox 读取失败：${esc(error.message)}</p>`;
  }
  $('drilldown').scrollIntoView({ behavior: 'smooth', block: 'start' });
}

function renderAnalysis(analysis) {
  const blocks = Object.entries(analysis).map(([panel, value]) => {
    const rows = flatten(value);
    return `<details class="block"${panel === 'reward' ? ' open' : ''}>
      <summary>${esc(panel)}</summary>
      <dl class="kv">${rows}</dl>
    </details>`;
  }).join('');
  $('analysis').innerHTML = blocks || '<p class="hint">空 analysis</p>';
}

function flatten(value, depth) {
  const level = depth || 0;
  if (value === null || typeof value !== 'object') {
    return `<dt>${esc(level ? '' : 'value')}</dt><dd>${esc(display(value))}</dd>`;
  }
  return Object.entries(value).map(([key, child]) => {
    if (child !== null && typeof child === 'object' && !Array.isArray(child) && level < 2) {
      return `<div class="group">${esc(key)}</div>` + flatten(child, level + 1);
    }
    return `<dt>${esc(key)}</dt><dd>${esc(display(child))}</dd>`;
  }).join('');
}

function display(value) {
  if (value === null || value === undefined) return 'null';
  if (Array.isArray(value)) return value.length ? value.map(display).join(', ') : '[]';
  if (typeof value === 'number') return fmtNum(value);
  if (typeof value === 'object') return Object.entries(value).map(([k, v]) => `${k}=${display(v)}`).join(' ');
  return String(value);
}

function renderChatbox(payload) {
  const suppliers = payload.suppliers || [];
  if (!suppliers.length) {
    $('chatbox').innerHTML = '<p class="hint">该 run 没有 chatbox 谈判记录</p>';
    return;
  }
  $('chatbox').innerHTML = suppliers.map((supplier, index) => `
    <details class="supplier"${index === 0 ? ' open' : ''}>
      <summary><b>${esc(supplier.supplier)}</b> · ${supplier.messages.length} 条</summary>
      ${supplier.messages.map((m) => `
        <div class="msg ${esc(m.role)}">
          <span class="who">${esc(m.role || '?')}</span>
          <span class="body">${esc(collapse(m.content))}</span>
        </div>`).join('')}
    </details>`).join('');
}

const collapse = (text) => String(text ?? '').replace(/\s+/g, ' ').trim().slice(0, 700);

/* ------------------------------------------------------------- motion */

function observeReveals() {
  const cards = [...document.querySelectorAll('.reveal')];
  cards.forEach((card, index) => card.style.setProperty('--d', `${Math.min(index * 60, 420)}ms`));
  if (!('IntersectionObserver' in window)) {
    cards.forEach((card) => card.classList.add('in'));
    return;
  }
  const observer = new IntersectionObserver((entries) => {
    entries.forEach((entry) => {
      if (entry.isIntersecting) {
        entry.target.classList.add('in');
        observer.unobserve(entry.target);
      }
    });
  }, { rootMargin: '0px 0px -8% 0px' });
  cards.forEach((card) => observer.observe(card));
}

boot();
