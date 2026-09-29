/* The history page of one source (?source=…): its card with the current
   readings, then a chart per pollutant over a chosen range. The controls'
   state lives in the URL too, so a view can be shared and reloaded as it is. */

import {
  $, DAY_MS, HOUR_MS, REFRESH_INTERVAL_MS, addDays, api, clear, dayFormat, dayTimeFormat, el, formatDateTime,
  formatLocalDay, formatValue, hideError, loadPollutants, parseLocalDay,
  pollutantLabel, pollutantThreshold, pollutantUnit, showError, sourceCard, timeFormat, updateRelativeTimes,
} from './common.js';

/* what the status line calls each averaging */
const BUCKET_LABELS = {PT10M: '10-minute averages', PT1H: 'hourly averages', P1D: 'daily averages'};
const DEFAULT_RANGE = '24';
const CUSTOM_RANGE = 'custom';

const source = new URLSearchParams(location.search).get('source');

const charts = []; // Chart.js instances of the shown history, destroyed on re-render
let lastRange = null; // the [from, to) last loaded, to prefill the custom days from
let historyLimits = {defaultHistoryHours: 24, maxHistoryDays: 30}; // overwritten from /api/limits
let sourceRequest = 0; // grows per request; a response of an older request is never rendered
let historyRequest = 0;
let lastSourceJson = null; // the card last rendered, to skip rebuilding it unchanged

async function loadLimits() {
  historyLimits = await api('/api/limits');
}

/* ---- the source's card ------------------------------------------------- */

async function loadSource() {
  const requestId = ++sourceRequest;
  try {
    const sources = await api('/api/sources');
    if (requestId !== sourceRequest) return; // a newer refresh is underway; let it render
    const status = sources.find(s => s.source === source) ?? null;
    const json = JSON.stringify(status);
    if (json !== lastSourceJson) {
      lastSourceJson = json;
      renderSource(status);
    } else {
      updateRelativeTimes(); // nothing changed; only the "x min ago" text moves
    }
    hideError();
  } catch (e) {
    if (requestId !== sourceRequest) return;
    showError(`Could not load the sensor: ${e.message}`);
  }
}

function renderSource(status) {
  const slot = $('source');
  clear(slot);
  if (status === null) {
    slot.append(el('p', {className: 'muted'}, `Nothing is stored for ${source}.`));
    return;
  }
  slot.append(sourceCard(status, {link: false}));
  document.title = `${status.sensor} — Breezi`;
}

/* ---- the controls' state, kept in the URL ------------------------------ */

function hasOption(select, value) {
  return value !== null && [...select.options].some(o => o.value === value);
}

/**
 * Sets the controls from the URL — range=24|48|168|720|8760|custom, from/to=<custom days> —
 * with a default for whatever is missing or unusable.
 */
function restoreState() {
  const params = new URLSearchParams(location.search);
  const range = params.get('range');
  $('range').value = hasOption($('range'), range) ? range : DEFAULT_RANGE;
  const custom = $('range').value === CUSTOM_RANGE;
  $('custom-range').hidden = !custom;
  if (custom) {
    $('from').value = params.get('from') ?? ''; // a date input drops a value that is not a day
    $('to').value = params.get('to') ?? '';
    if (parseLocalDay($('from').value) === null || parseLocalDay($('to').value) === null) prefillCustomDays();
  }
}

/** Writes the controls into the URL, replacing the entry so Back still returns to the overview. */
function saveState() {
  const params = new URLSearchParams({source});
  const range = $('range').value;
  params.set('range', range);
  if (range === CUSTOM_RANGE) {
    if ($('from').value) params.set('from', $('from').value);
    if ($('to').value) params.set('to', $('to').value);
  }
  history.replaceState(null, '', `${location.pathname}?${params}`);
}

/* ---- the range --------------------------------------------------------- */

/** The [from, to) range the controls describe, as epoch milliseconds, or {error} when the custom days are not usable. */
function selectedRange() {
  const range = $('range').value;
  if (range !== CUSTOM_RANGE) {
    const to = Date.now();
    return {from: to - Number(range) * HOUR_MS, to};
  }
  const fromDay = parseLocalDay($('from').value);
  const toDay = parseLocalDay($('to').value);
  if (fromDay === null || toDay === null) return {error: 'choose both days'};
  if (fromDay > toDay) return {error: 'from is after to'};
  let to = addDays(toDay, 1); // the "to" day counts in full
  if (Math.round((to - fromDay) / DAY_MS) > historyLimits.maxHistoryDays) {
    return {error: `at most ${historyLimits.maxHistoryDays} days at once`};
  }
  // a DST change can make the allowed number of days an hour longer than the
  // service's limit; trim the excess rather than be refused
  to = Math.min(to, fromDay + historyLimits.maxHistoryDays * DAY_MS);
  return {from: fromDay, to};
}

/** Prefills the custom day inputs with the last shown range, so they are never empty. */
function prefillCustomDays() {
  const to = lastRange ? lastRange.to : Date.now();
  const from = lastRange ? lastRange.from : to - Number(historyLimits.defaultHistoryHours) * HOUR_MS;
  $('from').value = formatLocalDay(from);
  $('to').value = formatLocalDay(to - 1); // "to" is exclusive; the day of its last moment
}

/* the averaging follows the range's length — 10 minutes up to two days, an hour
   up to a month, a day beyond — so a chart has a few hundred points whatever the range */
function bucketFor(rangeMs) {
  if (rangeMs <= 2 * DAY_MS) return 'PT10M';
  if (rangeMs <= 31 * DAY_MS) return 'PT1H';
  return 'P1D';
}

/* ---- the history ------------------------------------------------------- */

async function loadHistory() {
  if (!source) return;
  saveState();
  const range = selectedRange();
  if (range.error) {
    $('history-status').textContent = range.error;
    return;
  }
  const bucket = bucketFor(range.to - range.from);
  const params = new URLSearchParams({
    source,
    from: new Date(range.from).toISOString(),
    to: new Date(range.to).toISOString(),
    bucket,
  });
  const requestId = ++historyRequest;
  $('history-status').textContent = 'loading…';
  $('charts').classList.add('loading');
  try {
    const data = await api(`/api/history?${params}`);
    if (requestId !== historyRequest) return; // a newer request is underway; let it render
    renderHistory(data);
    hideError();
    lastRange = {from: Date.parse(data.from), to: Date.parse(data.to)};
    $('history-status').textContent = `${data.points.length} points, ${BUCKET_LABELS[bucket]}`
        + ` · ${formatDateTime(lastRange.from)} – ${formatDateTime(lastRange.to)}`;
  } catch (e) {
    if (requestId !== historyRequest) return;
    $('history-status').textContent = '';
    showError(`Could not load the history: ${e.message}`);
  } finally {
    if (requestId === historyRequest) $('charts').classList.remove('loading');
  }
}

function destroyCharts() {
  for (const chart of charts) chart.destroy();
  charts.length = 0;
}

function renderHistory(data) {
  destroyCharts();
  const container = $('charts');
  clear(container);

  const fromMs = Date.parse(data.from);
  const toMs = Date.parse(data.to);
  const names = [...new Set(data.points.map(p => p.pollutant))];
  if (names.length === 0) {
    /* an empty chart of the range rather than nothing, so the page always shows a graph */
    const canvas = el('canvas');
    container.append(el('div', {className: 'chart-card'}, [
      el('div', {className: 'chart-title'}, el('h3', {className: 'muted'}, 'no readings in this range')),
      el('div', {className: 'chart-box'}, canvas),
    ]));
    charts.push(makeChart(canvas, [], fromMs, toMs, ''));
    return;
  }

  for (const name of names) {
    const points = data.points.filter(p => p.pollutant === name);
    const summary = data.summary.find(s => s.pollutant === name);
    const threshold = pollutantThreshold(name, data.bucket); // of averages this long, which the chart shows
    const canvas = el('canvas');
    const card = el('div', {className: 'chart-card'}, [
      el('div', {className: 'chart-title'}, [
        el('span', {className: 'swatch'}),
        el('h3', {}, [pollutantLabel(name), ' ', el('span', {className: 'unit'}, pollutantUnit(name))]),
      ]),
      summaryLine(summary),
      el('div', {className: 'chart-box'}, canvas),
    ]);
    container.append(card);
    charts.push(makeChart(canvas, points, fromMs, toMs, pollutantUnit(name), threshold));
  }
}

function summaryLine(summary) {
  if (!summary) return el('p', {className: 'summary'}, 'no readings in this range');
  return el('p', {className: 'summary'}, [
    'average ', el('strong', {}, formatValue(summary.mean)),
    ' · min ', el('strong', {}, formatValue(summary.min)),
    ' · max ', el('strong', {}, formatValue(summary.max)),
    ` · ${summary.count} readings`,
  ]);
}

function makeChart(canvas, points, fromMs, toMs, unit, threshold) {
  const style = getComputedStyle(document.documentElement);
  const inkMuted = style.getPropertyValue('--muted').trim();
  const grid = style.getPropertyValue('--grid').trim();
  const color = style.getPropertyValue('--reading').trim(); // the same for every pollutant
  const exceed = style.getPropertyValue('--exceed').trim();
  const above = value => threshold !== undefined && value > threshold;
  const dense = points.length > 400;
  const toXY = key => points.map(p => ({x: Date.parse(p.time), y: p[key]}));
  const datasets = [{
    label: 'average',
    data: toXY('mean'),
    borderColor: color,
    backgroundColor: color,
    borderWidth: 2,
    pointRadius: dense ? 0 : 2.5,
    pointHoverRadius: 5,
    /* a mean above the threshold: its point and the segments touching it turn warm */
    pointBackgroundColor: ctx => above(ctx.raw.y) ? exceed : color,
    pointBorderColor: ctx => above(ctx.raw.y) ? exceed : color,
    segment: {borderColor: ctx => above(ctx.p0.parsed.y) || above(ctx.p1.parsed.y) ? exceed : undefined},
    tension: 0,
  }];
  /* the band between the lowest and highest reading of each bucket */
  datasets.push(
      {label: 'max', data: toXY('max'), borderWidth: 0, pointRadius: 0, pointHoverRadius: 0,
        backgroundColor: color + '33', fill: '+1'},
      {label: 'min', data: toXY('min'), borderWidth: 0, pointRadius: 0, pointHoverRadius: 0},
  );
  if (threshold !== undefined) {
    /* the threshold of averages this long, dashed and level across the range; at a width
       of 1 it falls between two rows of pixels on some charts and comes out pale */
    datasets.push({label: 'threshold', data: [{x: fromMs, y: threshold}, {x: toMs, y: threshold}],
      borderColor: exceed, borderDash: [6, 4], borderWidth: 1.5, pointRadius: 0, pointHoverRadius: 0});
  }
  const spanMs = toMs - fromMs;
  const tickFormat = spanMs <= 2 * DAY_MS ? timeFormat : spanMs <= 8 * DAY_MS ? dayTimeFormat : dayFormat;
  return new Chart(canvas, {
    type: 'line',
    data: {datasets},
    options: {
      animation: false,
      parsing: false,
      normalized: true,
      responsive: true,
      maintainAspectRatio: false,
      interaction: {mode: 'nearest', axis: 'x', intersect: false},
      plugins: {
        legend: {display: false},
        tooltip: {
          filter: item => item.datasetIndex === 0,
          callbacks: {
            title: items => items.length ? formatDateTime(items[0].parsed.x) : '',
            label: item => {
              const p = points[item.dataIndex];
              if (!p) return '';
              return `average ${formatValue(p.mean)} ${unit} (min ${formatValue(p.min)}, max ${formatValue(p.max)}, ${p.count} readings)`
                  + (above(p.mean) ? ` — above the ${formatValue(threshold)} threshold` : '');
            },
          },
        },
      },
      scales: {
        x: {
          type: 'linear',
          min: fromMs,
          max: toMs,
          ticks: {maxTicksLimit: 8, color: inkMuted, callback: value => tickFormat.format(new Date(value))},
          grid: {color: grid},
          border: {color: grid},
        },
        y: {
          beginAtZero: true,
          ticks: {color: inkMuted, display: points.length > 0}, // nothing to read off an empty chart
          grid: {color: grid},
          border: {display: false},
        },
      },
    },
  });
}

/* ---- wiring ------------------------------------------------------------ */

$('range').addEventListener('change', () => {
  const custom = $('range').value === CUSTOM_RANGE;
  $('custom-range').hidden = !custom;
  if (custom) prefillCustomDays();
  loadHistory();
});
for (const id of ['from', 'to']) {
  $(id).addEventListener('change', loadHistory);
}

(async function start() {
  if (!source) {
    showError('No sensor given — open one from the overview.');
    return;
  }
  $('history').hidden = false;
  try {
    await loadPollutants();
  } catch (e) {
    showError(`Could not load the pollutants: ${e.message}`);
  }
  try {
    await loadLimits();
  } catch (e) {
    /* the built-in defaults stand in; the service still enforces its own limits */
  }
  restoreState();
  loadSource();
  setInterval(loadSource, REFRESH_INTERVAL_MS);
  loadHistory();
})();
