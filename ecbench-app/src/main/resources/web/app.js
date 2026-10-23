'use strict';

/* E-CommerceBench telemetry console.
   只读消费 serve 的 /api/* JSON；图表为自绘 SVG，无任何外部依赖。 */

const PALETTE = ['#b0761a', '#26808f', '#3d8b50', '#bd4136', '#6b4fa1', '#9a7d12', '#3d6fa8', '#a44f7d'];
const POINT_RING = '#ffffff';

/* 角色与术语译名（spec §5.3）：论文/产物原始名保留英文，中文注解紧随其后。 */
const ROLE_ZH = {
  assistant: '智能体',
  tool: '供应商回复 · 工具结果',
  user: '系统',
  system: '系统',
};

const FRAUD_ZH = {
  fake_urgency: '虚假紧迫',
  future_discount: '未来折扣',
  qty_bait: '数量诱饵',
  quality_downgrade: '次品交付',
  vip_fee: 'VIP 会员费',
};

const BAND_ZH = {
  waiting: '等待', shipping: '发货', listing: '上架', withdraw: '提现',
  bargaining: '议价', state_polling: '状态查询', memory: '记忆', remainder: '其余',
};

const LAYER_ZH = {
  exp_return_rate_natural: '自然退货',
  exp_return_rate_from_pricing: '定价过高',
  exp_return_rate_from_ship_speed: '配送过慢',
  exp_return_rate_from_defective_fraud: '欺诈次品',
};

/* analysis.json 七个面板的中文注解（键名保留英文原文，便于与产物对照）。 */
const PANEL_ZH = {
  reward: 'reward（奖励与终止）',
  profitability: 'profitability（盈利与偿付）',
  negotiation_quality: 'negotiation_quality（谈判质量）',
  fulfilment_quality: 'fulfilment_quality（履约质量）',
  return_management: 'return_management（退货管理）',
  fraud_identification: 'fraud_identification（欺诈识别）',
  supplier_engagement: 'supplier_engagement（供应商触达）',
  operational_efficiency: 'operational_efficiency（运营效率）',
};

const roleZh = (role) => ROLE_ZH[role] || role || '未知';

/* 指标矩阵的维度分组（spec §5.5，29 个 label 的归属固定，不得临场判断）。 */
const METRIC_GROUPS = [
  ['谈判质量', ['SE+', 'CSE+', '%Oracle', 'AGR+', 'avg_rounds', 'money_saved']],
  ['欺诈规避', ['bad_order_share', 'spend_on_bad', 'spend_bad_share', 'vip_fee_paid']],
  ['现金流与偿付', ['final_balance', 'initial_balance', 'peak_total_assets', 'peak_drawdown',
    'bankrupt', 'final_day', 'stores / reopens']],
  ['运营效率', ['tool_calls', 'profit_per_tool_call', 'context_evictions', 'memory_calls']],
  ['运营执行', ['on_time_ship', 'return_rate', 'controllable_return_rate']],
  ['长周期学习', ['se_half_lift', 'agr_half_lift', 'fraud_avoid_lift', 'time_to_zero_bad',
    'rounds_improvement']],
];

/* 雷达轴标签的中文注解（轴序与后端 ProfileAxis 一致）。 */
const AXIS_ZH = [
  '主评分', '谈判质量', '欺诈规避', '现金流与偿付', '运营效率', '运营执行', '学习（AnchorRatio）',
];

const state = { sessions: [], selected: new Set(), drilled: null, balance: new Map(), jobs: [] };

/* 对比请求的代次：每次 loadCompare 递增，响应回来时若代次已旧则丢弃。
   否则「全选 → 立即清空」这类连续操作下，较慢的旧响应会覆盖较新的空态渲染。 */
let compareGeneration = 0;

const $ = (id) => document.getElementById(id);

async function api(path) {
  const res = await fetch(path);
  if (!res.ok) throw new Error(`${res.status} ${res.statusText}`);
  return res.json();
}

/* 写侧请求助手：与只读的 api() 分开，因为提交失败时必须拿到服务端的 error 文案，
   而 api() 只抛 `${status} ${statusText}`，会把「缺 DASHSCOPE_API_KEY」这类具体原因丢掉。 */
async function apiSend(method, path, body) {
  const init = { method, headers: {} };
  if (body !== undefined) {
    init.headers['Content-Type'] = 'application/json';
    init.body = JSON.stringify(body);
  }
  const res = await fetch(path, init);
  let payload = null;
  try { payload = await res.json(); } catch (ignored) { payload = null; }
  if (!res.ok) {
    const detail = payload && payload.error ? payload.error : `${res.status} ${res.statusText}`;
    throw new Error(detail);
  }
  return payload;
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

/* ------------------------------------------------------------ run form */

function showRunError(text) {
  const box = $('run-error');
  if (!text) { box.hidden = true; box.textContent = ''; return; }
  box.hidden = false;
  box.textContent = text;
}

function fillModelSelect(catalog) {
  const select = $('run-model');
  select.innerHTML = '';
  const ready = catalog.models.filter((m) => m.keyReady);
  if (!ready.length) {
    const option = document.createElement('option');
    option.value = '';
    option.textContent = '无可用模型（密钥缺失）';
    select.append(option);
    select.disabled = true;
    showRunError('未检测到任何可用的 LLM 密钥。请在启动服务前 export 对应变量，或在仓库根放置 .env。');
    return;
  }
  catalog.models.forEach((model) => {
    const option = document.createElement('option');
    option.value = model.key;
    option.textContent = model.keyReady ? model.key : `${model.key}（缺 ${model.missingEnv}）`;
    option.disabled = !model.keyReady;
    select.append(option);
  });
  select.value = ready[0].key;
  select.disabled = false;

  /* NPC 模型独立告警：即便主模型就绪，缺 OPENAI_API_KEY 一样跑不起来。 */
  const warning = $('npc-warning');
  if (catalog.npc && !catalog.npc.keyReady) {
    warning.hidden = false;
    warning.textContent = `供应商模型 ${catalog.npc.modelName} 缺 ${catalog.npc.missingEnv}，任何运行都会失败。`;
  } else {
    warning.hidden = true;
    warning.textContent = '';
  }
}

/* 收集表单：留空的数字字段不发送，让服务端套用与 CLI 同源的缺省值。 */
function collectRunRequest() {
  const payload = { model: $('run-model').value };
  const numeric = [['run-days', 'maxDays'], ['run-turns', 'maxTurns'], ['run-runs', 'runs'], ['run-seed', 'seed']];
  numeric.forEach(([id, field]) => {
    const raw = $(id).value.trim();
    if (raw !== '') payload[field] = Number(raw);
  });
  return payload;
}

async function initRunForm() {
  try {
    fillModelSelect(await api('/api/models'));
  } catch (error) {
    showRunError('无法读取模型列表：' + error.message);
    $('run-model').disabled = true;
    return;
  }
  $('run-form').onsubmit = async (event) => {
    event.preventDefault();
    showRunError('');
    const button = $('run-submit');
    button.disabled = true;
    button.textContent = '提交中…';
    try {
      await apiSend('POST', '/api/jobs', collectRunRequest());
      await refreshJobs();
    } catch (error) {
      showRunError(error.message);
    } finally {
      button.disabled = false;
      button.textContent = '提交运行';
    }
  };
}

/* --------------------------------------------------------------- jobs */

const STATE_ZH = {
  QUEUED: '排队中', RUNNING: '运行中', SUCCEEDED: '已完成', FAILED: '失败', CANCELLED: '已取消',
  /* 派生态：编排正常结束但 run 未跑满营业日（破产 / 轮数用尽 / LLM 错误等折叠为 env_terminated）。
     不单独标出来会把「假密钥 401、day 0 就终止」显示成绿色的已完成，误导用户以为跑成了。 */
  TERMINATED: '已结束·未跑满',
};

const POLL_MS = 2500;

let jobGeneration = 0;
let jobTimer = null;
const finishedSeen = new Set();

async function refreshJobs() {
  const generation = ++jobGeneration;
  let payload;
  try {
    payload = await api('/api/jobs');
  } catch (error) {
    $('jobs').innerHTML = `<p class="hint">读取任务失败：${esc(error.message)}</p>`;
    return;
  }
  if (generation !== jobGeneration) return;   // 丢弃陈旧响应，与 loadCompare 同构
  state.jobs = payload.jobs || [];
  renderJobs();
  const grew = notifyNewlyFinished();
  scheduleJobPolling();
  if (grew) reloadSessions();
}

/* 记录新完成的会话；返回是否有新完成项（有才重扫会话列表）。 */
function notifyNewlyFinished() {
  const fresh = state.jobs.filter((job) => job.state === 'SUCCEEDED' && !finishedSeen.has(job.id));
  fresh.forEach((job) => finishedSeen.add(job.id));
  return fresh.length > 0;
}

async function reloadSessions() {
  try {
    state.sessions = await api('/api/sessions');
  } catch (ignored) {
    return;
  }
  renderSessions();
}

/* 仅在存在非终态 job 时轮询；全部终态后停掉，避免服务开着就永久空转。 */
function scheduleJobPolling() {
  const active = state.jobs.some((job) => job.state === 'QUEUED' || job.state === 'RUNNING');
  if (active && jobTimer === null) {
    jobTimer = setTimeout(() => { jobTimer = null; refreshJobs(); }, POLL_MS);
  }
  if (!active && jobTimer !== null) {
    clearTimeout(jobTimer);
    jobTimer = null;
  }
}

function renderJobs() {
  const box = $('jobs');
  box.innerHTML = '';
  if (!state.jobs.length) {
    box.innerHTML = '<p class="hint">暂无任务。用左侧「新建运行」提交第一个。</p>';
    return;
  }
  const table = document.createElement('table');
  table.innerHTML =
    '<thead><tr><th>模型</th><th>状态</th><th>进度</th><th>会话</th><th>提交时间</th>'
    + '<th></th><th>详情</th></tr></thead>';
  const tbody = document.createElement('tbody');
  state.jobs.forEach((job) => tbody.append(jobRow(job)));
  table.append(tbody);
  box.append(table);
}

function jobRow(job) {
  const row = document.createElement('tr');
  row.append(textCell(job.model));
  row.append(badgeCell(effectiveState(job)));
  row.append(progressCell(job.progress));
  row.append(sessionCell(job));
  row.append(textCell(String(job.submittedAt || '').replace('T', ' ').slice(0, 19)));

  const action = document.createElement('td');
  if (job.state === 'QUEUED' || job.state === 'RUNNING') {
    const button = document.createElement('button');
    button.type = 'button';
    button.textContent = '取消';
    button.title = '尽力而为：可能需等到下一次模型调用才真正停下';
    button.onclick = async () => {
      button.disabled = true;
      try {
        await apiSend('DELETE', `/api/jobs/${encodeURIComponent(job.id)}`);
      } catch (error) {
        showRunError(error.message);
      }
      await refreshJobs();
    };
    action.append(button);
  }
  row.append(action);
  row.append(textCell(job.state === 'FAILED' ? (job.error || '') : outcomeText(job)));
  return row;
}

function outcomeText(job) {
  if (!job.outcomes || !job.outcomes.length) return '';
  return job.outcomes
    .map((o) => `run${o.index}: ${o.succeeded ? `${o.canonicalReason}（day ${o.finalDay}）` : '失败'}`)
    .join(' · ');
}

function textCell(text) {
  const td = document.createElement('td');
  td.textContent = text ?? '';
  return td;
}

function badgeCell(jobState) {
  const td = document.createElement('td');
  const span = document.createElement('span');
  span.className = `badge badge-${String(jobState).toLowerCase()}`;
  span.textContent = STATE_ZH[jobState] || jobState;
  td.append(span);
  return td;
}

/* 展示用状态：编排 SUCCEEDED 但所有 run 都不是 env_completed 时，降级为 TERMINATED。 */
function effectiveState(job) {
  if (job.state !== 'SUCCEEDED') return job.state;
  const outcomes = job.outcomes || [];
  const terminated = outcomes.length
    && outcomes.every((o) => o.canonicalReason && o.canonicalReason !== 'env_completed');
  return terminated ? 'TERMINATED' : job.state;
}

function progressCell(progress) {
  const td = document.createElement('td');
  if (!progress || !progress.maxDays) { td.textContent = '—'; return td; }
  const ratio = Math.max(0, Math.min(1, progress.days / progress.maxDays));
  const bar = document.createElement('div');
  bar.className = 'bar';
  const fill = document.createElement('i');
  fill.style.width = `${(ratio * 100).toFixed(1)}%`;
  bar.append(fill);
  const label = document.createElement('span');
  label.className = 'bar-label';
  label.textContent = `${progress.days}/${progress.maxDays} 天 · run ${progress.runsDone}/${progress.runsTotal}`;
  td.append(bar, label);
  return td;
}

/* 会话名可点击：跳到会话列表并选中，衔接既有下钻面板。 */
function sessionCell(job) {
  const td = document.createElement('td');
  if (!job.sessionName) { td.textContent = '—'; return td; }
  const link = document.createElement('button');
  link.type = 'button';
  link.className = 'link';
  link.textContent = shortName(job.sessionName);
  link.title = job.sessionName;
  link.onclick = () => {
    state.selected.clear();
    state.selected.add(job.sessionName);
    renderSessions();
    loadCompare();
    $('session-list').scrollIntoView({ behavior: 'smooth', block: 'start' });
  };
  td.append(link);
  return td;
}

/* ---------------------------------------------------------------- boot */

async function boot() {
  /* 写侧面板与事件绑定必须先于「空会话早退」：新语义下用户很可能先开服务、
     再在页面上跑第一个 run，此时 log/ 为空，旧写法会在早退处直接 return 而让表单永不出现。 */
  await initRunForm();
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
  observeReveals();
  refreshJobs();

  try {
    state.sessions = await api('/api/sessions');
  } catch (error) {
    setStatus('无法读取会话列表：' + error.message, 'bad');
    $('session-list').innerHTML = '<p class="hint">加载失败</p>';
    return;
  }
  if (!state.sessions.length) {
    setStatus('日志目录下没有会话');
    $('session-list').innerHTML =
      '<p class="hint">未发现会话。在上方「新建运行」提交第一个任务，或用 --log-dir 指向含 run 产物的目录。</p>';
    return;
  }
  // 默认选中信息最全的会话（run 最多，其次有余额产物）
  const best = [...state.sessions].sort((a, b) => (b.runs - a.runs) || (b.hasBalance - a.hasBalance))[0];
  state.selected.add(best.name);

  renderSessions();
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
    meta.textContent = `${session.runs} 个 run${session.hasBalance ? ' · 有余额' : ''}`;
    label.append(checkbox, name, meta);
    box.append(label);
  });
}

/* ------------------------------------------------------------ compare */

async function loadCompare() {
  const chosen = [...state.selected];
  const generation = ++compareGeneration;
  if (!chosen.length) {
    setStatus('未选择会话');
    ['ranking', 'metric-table', 'radar-legend', 'primary-cards', 'dimensions', 'crossings']
      .forEach((id) => { $(id).innerHTML = ''; });
    $('radar').innerHTML = '';
    $('balance-chart').innerHTML = '';
    $('balance-legend').innerHTML = '';
    state.balance.clear();
    $('radar-note').textContent = '';
    $('balance-note').textContent = '';
    // 下钻入口随选择一同复位，避免残留上一次会话的按钮（已渲染的下钻内容保留供阅读）。
    state.drilled = null;
    $('run-picker').innerHTML = '<p class="hint">选择会话后出现</p>';
    renderKpis(null);
    return;
  }
  const query = chosen.map((n) => 'sessions=' + encodeURIComponent(n)).join('&');
  try {
    const data = await api('/api/compare?' + query);
    if (generation !== compareGeneration) return;
    setStatus(`已对比 ${data.sessions.length} 个会话`, 'live');
    renderKpis(data);
    renderPrimary(data);
    renderDimensions(data);
    renderCrossings(data);
    renderRadar(data.radar, data.sessions);
    renderRanking(data.ranking);
    renderMetricTable(data.sessions);
    renderRunPicker(data.sessions);
    await loadBalance(chosen, generation);
  } catch (error) {
    if (generation !== compareGeneration) return;
    setStatus('对比加载失败：' + error.message, 'bad');
  }
}

function renderKpis(data) {
  if (!data || !data.sessions.length) {
    ['kpi-score', 'kpi-sessions', 'kpi-axes', 'kpi-calls'].forEach((id) => { $(id).textContent = '—'; });
    ['kpi-score-who', 'kpi-runs', 'kpi-calls-note'].forEach((id) => { $(id).textContent = ''; });
    return;
  }
  const rows = (data.primaryScore && data.primaryScore.rows) || [];
  const best = rows.find((row) => row.rank === 1) || null;
  $('kpi-score').textContent = best && best.value !== null ? best.value.toFixed(2) + ' ×' : '不适用';
  $('kpi-score-who').textContent = best ? shortName(best.name) : '无有效主评分';

  const runs = data.sessions.reduce((sum, s) => sum + s.runCount, 0);
  $('kpi-sessions').textContent = String(data.sessions.length);
  $('kpi-runs').textContent = `${runs} 个 run`;

  const withData = data.radar.axes.filter((_, i) => data.radar.series.some((s) => s.values[i] !== null)).length;
  $('kpi-axes').textContent = `${withData} / 7`;

  const calls = data.sessions
    .map((s) => (s.metrics.find((m) => m.label === 'tool_calls') || {}).mean)
    .filter((v) => typeof v === 'number');
  $('kpi-calls').textContent = calls.length ? fmtNum(calls.reduce((a, b) => a + b, 0) / calls.length) : 'n/a';
  $('kpi-calls-note').textContent = calls.length ? `${calls.length} 个会话有数据` : '无工具调用统计';
}

/* ------------------------------------------------- paper views (§3.7/§4.3) */

function renderPrimary(data) {
  const box = $('primary-cards');
  const rows = (data.primaryScore && data.primaryScore.rows) || [];
  if (!rows.length) {
    box.innerHTML = '<p class="hint">无主评分数据（analysis.json 缺 reward.final_score）</p>';
    return;
  }
  box.innerHTML = rows.map((row) => `
    <div class="primary-card">
      <div class="who">${esc(shortName(row.name))}</div>
      <div class="value">${row.value === null ? '不适用' : row.value.toFixed(2) + ' ×'}</div>
      <div class="rank">排名第 ${row.rank === null ? '—' : row.rank} · 年末总资产 ${fmtMoney(row.finalBalance)}</div>
      <div class="rank">破产 run ${row.bankruptRuns} 个 · 终止原因 ${esc(row.termination || '未知')}${row.terminationDetail ? '（' + esc(row.terminationDetail) + '）' : ''}</div>
    </div>`).join('');
}

function renderDimensions(data) {
  const box = $('dimensions');
  const axes = (data.dimensions || []);
  if (!axes.length) {
    box.innerHTML = '<p class="hint">无维度数据</p>';
    return;
  }
  box.innerHTML = axes.map((axis) => `
    <article class="dimension">
      <header>
        <h3>${esc(axis.labelZh)}</h3>
        <span class="ref">${esc(axis.paperRef)}</span>
      </header>
      ${dimensionHeadline(axis, data)}
      ${dimensionTable(axis)}
      ${dimensionBlocks(axis, data)}
      ${axis.key === 'learning' ? renderLearningCard(data.learning, data.sessions) : ''}
    </article>`).join('');
}

/** 卡片顶部：排名指标名（英文 + 中文注解）、方向、各会话的值与 rank。 */
function dimensionHeadline(axis, data) {
  const metric = axis.rankMetric || {};
  const direction = metric.direction === 'lower' ? '越低越好' : '越高越好';
  const rows = (axis.rows || []).map((row) => `
    <tr>
      <td>${esc(shortName(row.name))}</td>
      <td class="${row.value === null ? 'na' : ''}">${row.value === null ? '不适用' : fmtNum(row.value)}</td>
      <td class="${row.rank === null ? 'na' : ''}">${row.rank === null ? '—' : '#' + row.rank}</td>
    </tr>`).join('');
  return `
    <div class="headline">
      <b>${esc(metric.label || '')}</b>
      <span class="ref">${esc(metric.labelZh || '')} · ${direction}${metric.unit ? ' · ' + esc(metric.unit) : ''}</span>
    </div>
    <table><thead><tr><th>会话</th><th>值</th><th>维度排名</th></tr></thead><tbody>${rows}</tbody></table>`;
}

/** 诊断指标小表：行 = 诊断指标（英文 + 中文注解），列 = 会话。 */
function dimensionTable(axis) {
  const diagnostics = axis.diagnostics || [];
  if (!diagnostics.length) return '';
  const names = (axis.rows || []).map((row) => row.name);
  const head = names.map((name) => `<th>${esc(shortName(name))}</th>`).join('');
  const body = diagnostics.map((item) => `
    <tr>
      <td title="${esc(item.label)}">${esc(item.label)}<span class="ref"> ${esc(item.labelZh)}</span></td>
      ${names.map((name) => {
        const value = item.values ? item.values[name] : null;
        return `<td class="${value === null || value === undefined ? 'na' : ''}">${fmtNum(value)}</td>`;
      }).join('')}
    </tr>`).join('');
  return `<table><thead><tr><th>诊断指标</th>${head}</tr></thead><tbody>${body}</tbody></table>`;
}

/** 三类嵌套诊断块：八类活动带、五类骗局支出、退货四层 + 发货速度构成。 */
function dimensionBlocks(axis, data) {
  const blocks = axis.blocks || {};
  if (axis.key === 'efficiency' && blocks.callsByBand) {
    const entries = Object.entries(blocks.callsByBand).map(([band, value]) => [
      `${band}（${BAND_ZH[band] || band}）`, value.count, value.share,
    ]);
    return barsBlock(entries);
  }
  if (axis.key === 'fraud' && blocks.spendByFraudType) {
    const entries = Object.entries(blocks.spendByFraudType);
    const colors = ['#bd4136', '#b0761a', '#6b4fa1', '#26808f', '#a44f7d'];
    const stacked = stackedBlock(
      entries.map(([type, value]) => [`${type}（${FRAUD_ZH[type] || type}）`, value.spend]), colors);
    const detail = entries.map(([type, value]) => `
      <div class="bar-row">
        <span>${esc(type)}</span>
        <span class="num">${fmtNum(value.orders)} 单 / ${fmtNum(value.units)} 件</span>
        <span class="num">${fmtMoney(value.spend)}</span>
      </div>`).join('');
    return stacked + detail;
  }
  if (axis.key === 'execution') {
    const layers = blocks.returnLayers || {};
    const entries = Object.entries(layers).map(([key, value]) => [LAYER_ZH[key] || key, value]);
    const colors = ['#8a94a2', '#b0761a', '#26808f', '#bd4136'];
    const speed = blocks.shipSpeed
      ? `<div class="legend-inline">${Object.entries(blocks.shipSpeed)
          .map(([key, value]) => `<span>${esc(key)}：${fmtNum(value)}</span>`).join('')}</div>`
      : '';
    return stackedBlock(entries, colors) + speed;
  }
  return '';
}

/** 横向条：entries = [[标签, 计数, 占比]]，条长按占比归一化。 */
function barsBlock(entries) {
  const max = Math.max(1, ...entries.map((entry) => Number(entry[2]) || 0));
  return `<div class="bars">${entries.map(([label, count, share]) => `
    <div class="bar-row">
      <span>${esc(label)}</span>
      <span class="track"><i class="fill" style="width:${(((Number(share) || 0) / max) * 100).toFixed(1)}%"></i></span>
      <span class="num">${fmtNum(count)} 次 · ${fmtNum(share)}%</span>
    </div>`).join('')}</div>`;
}

/** 堆叠条：entries = [[标签, 数值]]，按总量占比分段；总量为 0 时显示空态。 */
function stackedBlock(entries, colors) {
  const total = entries.reduce((sum, entry) => sum + (Number(entry[1]) || 0), 0);
  if (!total) return '<p class="hint">无样本（全部为 0）</p>';
  const segments = entries.map((entry, index) => {
    const value = Number(entry[1]) || 0;
    const width = ((value / total) * 100).toFixed(2);
    return `<i style="width:${width}%;background:${colors[index % colors.length]}" title="${esc(entry[0])}"></i>`;
  }).join('');
  const legend = entries.map((entry, index) => `
    <span><i style="background:${colors[index % colors.length]}"></i>${esc(entry[0])} ${fmtNum(entry[1])}</span>`).join('');
  return `<div class="stacked">${segments}</div><div class="legend-inline">${legend}</div>`;
}

/** 学习维度卡：论文 §E.6 的锚定与半年比较；无产物时给出中文空态与条款出处。 */
function renderLearningCard(learning, sessions) {
  const entries = learning ? Object.entries(learning) : [];
  if (!entries.length) return '<p class="hint">无学习维度数据</p>';
  return entries.map(([name, value]) => {
    if (!value || value.episodes === 0) {
      return `<p class="hint">${esc(shortName(name))}：无重复采购记录，AnchorRatio 不适用（§E.6 过滤规则 4）；
        若该会话产物早于逐谈判记录落盘，需重跑基准才能计算。</p>`;
    }
    const rows = [
      ['AnchorRatio', '锚定比（越低越好，1.0 = 与随机排列无异）', value.anchorRatio],
      ['AnchorRegret', '锚定遗憾', value.anchorRegret],
      ['NewLow', '创新低率', value.newLow],
      ['z_anchor', '锚定 z 分数（Stouffer 合并）', value.zAnchor],
      ['h_SE', '剩余半期提升', value.seHalfLift],
      ['b_SE', '逐日盈余趋势', value.seTrend],
      ['h_fraud', '欺诈半期提升', value.fraudHalfLift],
    ];
    return `<table><thead><tr><th>学习指标</th><th>值</th></tr></thead><tbody>
      ${rows.map(([label, note, raw]) => `
        <tr><td title="${esc(note)}">${esc(label)}<span class="ref"> ${esc(note)}</span></td>
        <td class="${raw === null ? 'na' : ''}">${fmtNum(raw)}</td></tr>`).join('')}
      <tr><td>episodes / repeatPairs / reorders<span class="ref"> 计入回合数 / 重复采购对 / 再订购次数</span></td>
        <td>${value.episodes} / ${value.repeatPairs} / ${value.reorders}</td></tr>
    </tbody></table>${dualCaliberNote(name, value, sessions)}`;
  }).join('');
}

/**
 * 双口径提示（spec §5.5）：矩阵里的 se_half_lift 来自 analysis.json 的追踪器口径，本卡是 §E.6 重算值；
 * 两者都有值且差 &gt; 1e-6 时明示差异，避免误读。
 */
function dualCaliberNote(name, value, sessions) {
  const tracked = trackerHalfLift(sessions, name);
  if (tracked === null || value.seHalfLift === null || value.seHalfLift === undefined) return '';
  if (Math.abs(tracked - value.seHalfLift) <= 1e-6) return '';
  return '<p class="note">与追踪器 learning_speed.se_half_lift 存在差异（排序口径），以本卡 §E.6 重算值为准。</p>';
}

/** 从指标矩阵取追踪器口径的 se_half_lift（analysis.json 的 negotiation_quality.learning_speed）。 */
function trackerHalfLift(sessions, name) {
  const session = (sessions || []).find((item) => item.name === name);
  if (!session) return null;
  const stat = (session.metrics || []).find((metric) => metric.label === 'se_half_lift');
  return stat && typeof stat.mean === 'number' ? stat.mean : null;
}

/** rank crossings（附录 F.8）：与主评分排名不一致的格子标红。 */
function renderCrossings(data) {
  const rows = data.rankCrossings || [];
  const box = $('crossings');
  if (!rows.length) {
    box.innerHTML = '<p class="hint">无排名数据</p>';
    return;
  }
  const axes = (data.dimensions || []).map((axis) => [axis.key, axis.labelZh]);
  const head = axes.map(([, labelZh]) => `<th>${esc(labelZh)}</th>`).join('');
  const body = rows.map((row) => `
    <tr>
      <td>${esc(shortName(row.name))}</td>
      <td>${row.primary === null || row.primary === undefined ? '—' : '#' + row.primary}</td>
      ${axes.map(([key]) => {
        const rank = row.ranks ? row.ranks[key] : null;
        const crosses = rank !== null && rank !== undefined && row.primary !== null && rank !== row.primary;
        return `<td class="${rank === null || rank === undefined ? 'na' : ''}${crosses ? ' cross' : ''}">
          ${rank === null || rank === undefined ? '—' : '#' + rank}</td>`;
      }).join('')}
    </tr>`).join('');
  box.innerHTML = `<table><thead><tr><th>会话</th><th>主评分</th>${head}</tr></thead><tbody>${body}</tbody></table>`;
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
    const text = AXIS_ZH[idx] || label;
    markup += `<text class="${cls}" x="${x.toFixed(1)}" y="${y.toFixed(1)}" text-anchor="${anchor}">`
      + `<title>${esc(label)}</title>${esc(text)}</text>`;
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
    .join('') + '<span><i class="median"></i>中位数</span>';

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
    '<table><thead><tr><th>会话</th><th>主评分（年末总资产）</th></tr></thead><tbody>' + rows + '</tbody></table>';
}

/* -------------------------------------------------------- metric grid */

let metricRows = [];
/* 指标矩阵的列序：必须取自后端返回的 data.sessions 序（= 请求参数序），不能从 state.sessions 反推，
   否则「非扫描序勾选」时表头与数据列会错位，把某模型的指标值挂到另一个模型名下。 */
let metricColumns = [];

function renderMetricTable(sessions) {
  if (!sessions.length) {
    $('metric-table').innerHTML = '<p class="hint">无数据</p>';
    metricRows = [];
    metricColumns = [];
    return;
  }
  metricColumns = sessions.map((s) => s.name);
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
  const head = metricColumns.map((name) => `<th>${esc(shortName(name))}</th>`);
  const byLabel = new Map(metricRows.map((row) => [row.label, row]));
  const rowHtml = (row) => `
    <tr data-label="${esc(row.label.toLowerCase())}">
      <td>${esc(row.label)}</td>
      ${row.cells.map((c) => `<td class="${c.na ? 'na' : ''}">${esc(c.text)}</td>`).join('')}
    </tr>`;
  const grouped = new Set();
  let body = '';
  METRIC_GROUPS.forEach(([title, labels], index) => {
    const rows = labels.map((label) => byLabel.get(label)).filter(Boolean);
    if (!rows.length) return;
    rows.forEach((row) => grouped.add(row.label));
    body += `<tr class="group-head" data-group="${index}" data-label="${esc(title.toLowerCase())}">
      <td colspan="${head.length + 1}">${esc(title)}（${rows.length}）</td></tr>`;
    body += rows.map(rowHtml).join('');
  });
  // 归组之外的 label（例如后端新增指标）兜底到「其余」组，避免任何一行被静默丢弃
  const rest = metricRows.filter((row) => !grouped.has(row.label));
  if (rest.length) {
    body += `<tr class="group-head" data-label="其余"><td colspan="${head.length + 1}">其余（${rest.length}）</td></tr>`;
    body += rest.map(rowHtml).join('');
  }
  $('metric-table').innerHTML =
    `<table><thead><tr><th>指标</th>${head.join('')}</tr></thead><tbody>${body}</tbody></table>`;
  // 分组标题可点击折叠/展开（spec §5.5 要求「可折叠分组」）：只切换 class，
  // 行级可见性统一由 filterMetricRows 按 DOM 序从所属分组标题的 collapsed 态推导。
  $('metric-table').onclick = (event) => {
    const groupHead = event.target.closest('tr.group-head');
    if (!groupHead) return;
    groupHead.classList.toggle('collapsed');
    filterMetricRows();
  };
  filterMetricRows();
}

function filterMetricRows() {
  const needle = ($('metric-filter').value || '').trim().toLowerCase();
  const stale = document.querySelector('#metric-table tr.no-match');
  if (stale) stale.remove();
  const rows = [...document.querySelectorAll('#metric-table tbody tr')];
  let keepGroup = false;
  let collapsed = false;
  let visible = 0;
  rows.forEach((tr) => {
    const isGroup = tr.classList.contains('group-head');
    if (isGroup) {
      keepGroup = !needle || tr.dataset.label.includes(needle);
      collapsed = tr.classList.contains('collapsed');
      tr.hidden = !keepGroup;
      return;
    }
    // 有过滤词时搜索优先于折叠态；无过滤词时尊重各组的折叠态。
    const show = needle
      ? (tr.dataset.label.includes(needle) || keepGroup)
      : !collapsed;
    tr.hidden = !show;
    if (show) visible += 1;
  });
  // 过滤到零行时给明确提示，避免用户误以为表格坏了（旧行为是默默变成 0 行）。
  if (needle && !visible) appendNoMatch(needle, rows);
}

/** 在指标矩阵末尾插入一行「无匹配」提示；列数跟表头对齐。 */
function appendNoMatch(needle, rows) {
  const tbody = document.querySelector('#metric-table tbody');
  if (!tbody) return;
  const columns = document.querySelectorAll('#metric-table thead th').length
    || (rows[0] ? rows[0].children.length : 1);
  const tr = document.createElement('tr');
  tr.className = 'no-match';
  tr.innerHTML = `<td colspan="${columns}">无匹配「${esc(needle)}」的指标或维度</td>`;
  tbody.append(tr);
}

/* ------------------------------------------------------------ balance */

async function loadBalance(chosen, generation) {
  state.balance.clear();
  for (const name of chosen) {
    try {
      const payload = await api(`/api/sessions/${encodeURIComponent(name)}/balance?run=0`);
      if (generation !== compareGeneration) return;
      if (payload.series && payload.series.length) state.balance.set(name, payload.series);
    } catch (error) {
      /* 该会话无余额产物：跳过，不影响其它曲线 */
    }
  }
  if (generation !== compareGeneration) return;
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
        markup += `<circle cx="${x.toFixed(1)}" cy="${y.toFixed(1)}" r="2.6" fill="${color}" stroke="${POINT_RING}" stroke-width="1" />`;
      });
    });
  });
  markup += `<line id="crosshair" class="crosshair" y1="${top}" y2="${top + height}" />`;
  svg.innerHTML = markup;

  legend.innerHTML = [...state.balance.keys()]
    .map((name, i) => `<span><i style="background:${PALETTE[i % PALETTE.length]}"></i>${esc(shortName(name))}</span>`)
    .join('');
  note.textContent = `${state.balance.size} 条曲线 · ${dates.length} 个交易日 · 纵轴为年末总资产`;

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
    // 先写内容再量尺寸，四个方向都夹取：避免右侧溢出（旧代码用 250 夹但 max-width 是 260）与底部顶出屏幕。
    const tipWidth = Math.min(tooltip.offsetWidth || 260, 260);
    const tipHeight = tooltip.offsetHeight || 60;
    tooltip.style.left = Math.max(8, Math.min(event.clientX + 14, window.innerWidth - tipWidth - 8)) + 'px';
    tooltip.style.top = Math.max(8, Math.min(event.clientY + 12, window.innerHeight - tipHeight - 8)) + 'px';
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
  $('drill-name').textContent = name + ' · run_0（下钻固定首个 run）';
  $('analysis').innerHTML = '<p class="hint">读取中…</p>';
  $('chatbox').innerHTML = '<p class="hint">读取中…</p>';
  const base = `/api/sessions/${encodeURIComponent(name)}`;
  try {
    renderAnalysis(await api(`${base}/analysis?run=0`));
  } catch (error) {
    $('analysis').innerHTML = `<p class="hint">analysis.json 读取失败：${esc(error.message)}</p>`;
  }
  try {
    renderChatbox(await api(`${base}/chatbox?run=0`));
  } catch (error) {
    $('chatbox').innerHTML = `<p class="hint">谈判记录读取失败：${esc(error.message)}</p>`;
  }
  $('drilldown').scrollIntoView({ behavior: 'smooth', block: 'start' });
}

function renderAnalysis(analysis) {
  const blocks = Object.entries(analysis).map(([panel, value]) => {
    const rows = flatten(value);
    return `<details class="block"${panel === 'reward' ? ' open' : ''}>
      <summary>${esc(PANEL_ZH[panel] || panel)}</summary>
      <dl class="kv">${rows}</dl>
    </details>`;
  }).join('');
  $('analysis').innerHTML = blocks || '<p class="hint">analysis.json 为空</p>';
}

function flatten(value, depth) {
  const level = depth || 0;
  if (value === null || typeof value !== 'object') {
    return `<dt>${esc(level ? '' : '值')}</dt><dd>${esc(display(value))}</dd>`;
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
      <summary><b>${esc(supplier.supplier)}</b>
        <span class="count">${supplier.messages.length} 条</span>
        ${rosterTag(supplier)}</summary>
      ${supplier.messages.map((m) => `
        <div class="msg ${esc(m.role)}">
          <span class="who">${esc(roleZh(m.role))}</span>
          <span class="body">${esc(collapse(m.content))}</span>
        </div>`).join('')}
    </details>`).join('');
}

/** 供应商标签：欺诈者标出骗局类型，诚信者标出人格；名册未命中则不显示标签。 */
function rosterTag(supplier) {
  if (supplier.supplierType === 'bad') {
    const fraud = FRAUD_ZH[supplier.fraudType] || supplier.fraudType || '未知骗局';
    return `<span class="tag bad">欺诈 · ${esc(fraud)}</span>`;
  }
  if (supplier.supplierType === 'good') {
    const who = supplier.personality ? ` · ${esc(supplier.personality)}` : '';
    return `<span class="tag good">诚信${who}</span>`;
  }
  return '';
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
