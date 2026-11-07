export const channels = [
  { key: 'temperatureC', label: 'Temperature', unit: '°C', sensor: 'sht30', model: 'SHT30', color: '#c77c48', icon: '◌' },
  { key: 'humidityPct', label: 'Relative humidity', unit: '%RH', sensor: 'sht30', model: 'SHT30', color: '#548eb1', icon: '◈' },
  { key: 'gasRelative', label: 'Relative gas indicator', unit: 'ratio', sensor: 'mq135', model: 'MQ-135', color: '#8e79b0', icon: '≋' },
  { key: 'dustAdcMv', label: 'Dust sensor response', unit: 'mV', sensor: 'dust', model: 'GP2Y1010AU0F', color: '#658a60', icon: '⁙' },
  { key: 'dustEstimateUgM3', label: 'Experimental dust estimate', unit: 'µg/m³', sensor: 'dust', model: 'GP2Y1010AU0F', color: '#658a60', icon: '⁙' },
];
export const label = text => (text || 'UNKNOWN').toLowerCase().replaceAll('_', ' ').replace(/^./, c => c.toUpperCase());
export const number = value => value == null || !Number.isFinite(value) ? '—' : new Intl.NumberFormat(undefined, { maximumFractionDigits: 2 }).format(value);
export function reading(telemetry, key) {
  const channel = channels.find(c => c.key === key);
  if (!telemetry || telemetry.quality[channel.sensor] !== 'VALID') return null;
  return key === 'dustAdcMv' ? telemetry.raw.dustAdcMv : telemetry.values[key];
}
export function freshness(latest, now = Date.now()) {
  if (!latest?.measurement) return 'UNKNOWN';
  const t = latest.measurement.telemetry;
  const time = Date.parse(t.measuredAt);
  if (t.timeQuality !== 'SYNCED' || !Number.isFinite(time) || time > now) return 'UNKNOWN';
  if (now - time > Math.max(90, latest.device.reportIntervalSec * 3) * 1000) return 'STALE';
  return latest.device.freshness;
}
export function condition(latest, now) {
  return freshness(latest, now) === 'FRESH' ? latest.condition : 'UNKNOWN';
}
export function chartRows(items, key, intervalSec) {
  const rows = [];
  for (const item of items) {
    const t = item.telemetry;
    if (t.timeQuality !== 'SYNCED' || !t.measuredAt) continue;
    const time = Date.parse(t.measuredAt);
    const previous = rows.at(-1);
    const calibration = key === 'gasRelative' ? t.calibration.gasId : key === 'dustEstimateUgM3' ? t.calibration.dustId : null;
    if (previous && (time - previous.time > Math.max(intervalSec, t.reportIntervalSec) * 1500 || previous.calibration !== calibration)) {
      rows.push({ time: previous.time + 1, value: null, calibration: null });
    }
    rows.push({ time, value: reading(t, key), calibration });
  }
  return rows;
}
export function validateRange(from, to) {
  const start = Date.parse(from), end = Date.parse(to);
  if (!Number.isFinite(start) || !Number.isFinite(end) || start >= end) return 'Choose an end time after the start time.';
  if (end - start > 31 * 86400000) return 'Choose a range of at most 31 days.';
  return null;
}
export function thresholdPayload(draft) {
  const payload = { expectedRevision: draft.revision, direction: draft.direction, enabled: draft.enabled };
  for (const group of ['warning', 'alert', 'recovery']) {
    payload[group] = {};
    for (const side of ['low', 'high']) {
      const active = draft.direction === 'RANGE' || (side === 'low' ? draft.direction === 'LOWER' : draft.direction === 'UPPER');
      const raw = draft[group][side];
      if (active && (raw == null || String(raw).trim() === '' || !Number.isFinite(Number(raw)))) throw new Error(`Enter a finite ${group} ${side} boundary.`);
      payload[group][side] = active ? Number(raw) : null;
    }
  }
  const { warning: w, alert: a, recovery: r } = payload;
  const sequence = draft.direction === 'UPPER' ? [r.high, w.high, a.high] : draft.direction === 'LOWER' ? [a.low, w.low, r.low] : [a.low, w.low, r.low, r.high, w.high, a.high];
  if (sequence.some((value, i) => i > 0 && value <= sequence[i - 1])) throw new Error('Boundaries must be strictly ordered: alert low < warning low < recovery low < recovery high < warning high < alert high (use the applicable side).');
  return payload;
}
export async function allMeasurements(getPage, signal) {
  const items = [], seen = new Set();
  let cursor;
  do {
    if (signal?.aborted) throw new DOMException('Aborted', 'AbortError');
    const page = await getPage(cursor);
    items.push(...page.items);
    cursor = page.nextCursor;
    if (cursor && seen.has(cursor)) throw new Error('The server repeated a history cursor. Please retry.');
    if (cursor) seen.add(cursor);
  } while (cursor);
  return items;
}
