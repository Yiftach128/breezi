/* Shared by both pages of the dashboard: DOM and formatting helpers, the
   service's JSON API, the pollutant catalogue and the card of one source. */

export const REFRESH_INTERVAL_MS = 60_000;
export const HOUR_MS = 3_600_000;
export const DAY_MS = 24 * HOUR_MS;

export const pollutants = new Map(); // name -> {displayName, unit, thresholds}, in the service's order

export const $ = id => document.getElementById(id);

/* ---- DOM helpers ------------------------------------------------------- */

export function el(tag, attributes = {}, children = []) {
  const node = document.createElement(tag);
  for (const [name, value] of Object.entries(attributes)) {
    if (name === 'className') node.className = value;
    else if (name === 'hidden') node.hidden = value;
    else node.setAttribute(name, value);
  }
  for (const child of [].concat(children)) {
    node.append(child instanceof Node ? child : document.createTextNode(String(child)));
  }
  return node;
}

export function clear(node) {
  while (node.firstChild) node.removeChild(node.firstChild);
}

const SVG_NS = 'http://www.w3.org/2000/svg';

/** A small line-chart glyph (axes and a rising line), drawn in the current text colour. */
function chartIcon() {
  const svg = document.createElementNS(SVG_NS, 'svg');
  svg.setAttribute('viewBox', '0 0 24 24');
  svg.setAttribute('aria-hidden', 'true');
  svg.classList.add('icon');
  const path = document.createElementNS(SVG_NS, 'path');
  path.setAttribute('d', 'M3 3v18h18M7 15l4-5 4 3 5-7');
  svg.append(path);
  return svg;
}

export function showError(message) {
  const banner = $('error');
  banner.textContent = message;
  banner.hidden = false;
}

export function hideError() {
  $('error').hidden = true;
}

/* ---- formatting -------------------------------------------------------- */

/* the page is in English, so its dates are too, whatever the browser's language:
   en-GB — day before month, 24-hour clock */
const LOCALE = 'en-GB';

export const dateTimeFormat = new Intl.DateTimeFormat(LOCALE, {
  year: 'numeric', month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false,
});
export const timeFormat = new Intl.DateTimeFormat(LOCALE, {hour: '2-digit', minute: '2-digit', hour12: false});
export const dayTimeFormat = new Intl.DateTimeFormat(LOCALE, {
  month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit', hour12: false,
});
export const dayFormat = new Intl.DateTimeFormat(LOCALE, {month: 'short', day: 'numeric'});

export function formatDateTime(ms) {
  return dateTimeFormat.format(new Date(ms));
}

export function formatValue(value) {
  return value.toFixed(1);
}

export function relativeTime(ms) {
  const ago = Date.now() - ms;
  const minutes = Math.round(ago / 60_000);
  if (minutes < 1) return 'just now';
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.round(ago / HOUR_MS);
  if (hours < 48) return `${hours} h ago`;
  return `${Math.round(ago / DAY_MS)} days ago`;
}

/** "2026-08-27" (a date input's value) as epoch ms at local midnight, or null. */
export function parseLocalDay(value) {
  if (!value) return null;
  const [year, month, day] = value.split('-').map(Number);
  return new Date(year, month - 1, day).getTime();
}

/** The local day of an epoch ms as a date input's value, "2026-08-27". */
export function formatLocalDay(ms) {
  const date = new Date(ms);
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
}

/** Local midnight some days after the given moment's local midnight (through Date, so DST days work). */
export function addDays(ms, days) {
  const date = new Date(ms);
  return new Date(date.getFullYear(), date.getMonth(), date.getDate() + days).getTime();
}

/* ---- pollutants -------------------------------------------------------- */

export function pollutantLabel(name) {
  return pollutants.get(name)?.displayName ?? name;
}

export function pollutantUnit(name) {
  return pollutants.get(name)?.unit ?? '';
}

/**
 * The threshold of a measurement of the pollutant, in its unit: 'raw' for a
 * single reading, else the ISO length of the average (PT10M, PT1H, PT24H);
 * undefined when the service has none for it.
 */
export function pollutantThreshold(name, measurement) {
  return pollutants.get(name)?.thresholds?.[measurement];
}

/* ---- API --------------------------------------------------------------- */

export async function api(path) {
  const response = await fetch(path, {headers: {Accept: 'application/json'}});
  let body = null;
  try {
    body = await response.json();
  } catch (e) {
    /* no JSON body; fall through to the status text */
  }
  if (!response.ok) {
    throw new Error(body && body.error ? body.error : `${response.status} ${response.statusText}`);
  }
  return body;
}

export async function loadPollutants() {
  for (const p of await api('/api/pollutants')) {
    pollutants.set(p.name, {displayName: p.displayName, unit: p.unit, thresholds: p.thresholds ?? {}});
  }
}

/* ---- the source card --------------------------------------------------- */

/** The history page of a source. */
export function historyUrl(source) {
  return `/history?${new URLSearchParams({source})}`;
}

/**
 * The card of one source (a SourceStatus), in the order it is read: what it
 * reads right now, who it is and when it last reported. A link to its history
 * unless told otherwise.
 */
export function sourceCard(status, {link = true} = {}) {
  const stale = status.currentReadings.length === 0;
  const card = el(link ? 'a' : 'article', {
    className: `source-card${stale ? ' stale' : ''}`,
    ...(link ? {href: historyUrl(status.source)} : {}),
  });

  if (stale) {
    card.append(el('p', {className: 'no-readings'}, 'no current reading'));
  } else {
    const tiles = el('div', {className: 'tiles'});
    for (const reading of status.currentReadings) {
      const threshold = pollutantThreshold(reading.pollutant, 'PT1H'); // the hour's, not the spike threshold alerts use
      const exceeding = threshold !== undefined && reading.value > threshold;
      tiles.append(el('div', {
        className: `tile${exceeding ? ' exceeding' : ''}`,
        title: `at ${formatDateTime(Date.parse(reading.timestamp))}`
            + (exceeding ? ` — above the 1-hour threshold of ${formatValue(threshold)}` : ''),
      }, [
        el('span', {className: 'tile-name'}, pollutantLabel(reading.pollutant)),
        el('span', {className: 'tile-value'}, [
          formatValue(reading.value), el('span', {className: 'unit'}, pollutantUnit(reading.pollutant)),
        ]),
      ]));
    }
    card.append(tiles);
  }

  /* each value is one row, cut with an ellipsis when too long; the title carries it whole */
  const identity = el('dl', {className: 'card-id'});
  identity.append(el('dt', {}, 'sensor'), el('dd', {className: 'sensor', title: status.sensor}, status.sensor));
  if (status.provider !== null) {
    identity.append(el('dt', {}, 'provider'), el('dd', {className: 'provider', title: status.provider}, status.provider));
  }
  identity.append(el('dt', {}, 'city'), el('dd', {className: 'city', title: status.city}, status.city));
  card.append(identity);

  const lastMs = Date.parse(status.lastReportedAt);
  const reported = el('span', {className: 'reported', title: Number.isNaN(lastMs) ? '' : formatDateTime(lastMs)}, [
    el('span', {className: 'dot'}),
    stale ? 'last reported ' : 'updated ',
    Number.isNaN(lastMs) ? 'unknown' : el('span', {'data-relative-ms': lastMs}, relativeTime(lastMs)),
  ]);
  const foot = el('div', {className: 'card-foot'}, reported);
  if (link) foot.append(el('span', {className: 'card-cta'}, ['History', chartIcon()]));
  card.append(foot);
  return card;
}

/** Freshens every "x min ago" text in place, without rebuilding the cards around it. */
export function updateRelativeTimes() {
  for (const span of document.querySelectorAll('[data-relative-ms]')) {
    span.textContent = relativeTime(Number(span.dataset.relativeMs));
  }
}
