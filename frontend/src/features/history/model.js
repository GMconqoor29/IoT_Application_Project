import { reading } from '../monitoring/model.js';
export function chartRows(items, key, intervalSec) {
  const rows = [];
  for (const item of items) {
    const t = item.telemetry;
    if (t.timeQuality !== 'SYNCED' || !t.measuredAt) continue;
    const time = Date.parse(t.measuredAt);
    const previous = rows.at(-1);
    const calibration =
      key === 'gasRelative'
        ? t.calibration.gasId
        : key === 'dustEstimateUgM3'
          ? t.calibration.dustId
          : null;
    if (
      previous &&
      (time - previous.time > Math.max(intervalSec, t.reportIntervalSec) * 1500 ||
        previous.calibration !== calibration)
    ) {
      rows.push({ time: previous.time + 1, value: null, calibration: null });
    }
    rows.push({ time, value: reading(t, key), calibration });
  }
  return rows;
}
export function validateRange(from, to) {
  const start = Date.parse(from),
    end = Date.parse(to);
  if (!Number.isFinite(start) || !Number.isFinite(end) || start >= end)
    return 'Choose an end time after the start time.';
  if (end - start > 31 * 86400000) return 'Choose a range of at most 31 days.';
  return null;
}
export async function allMeasurements(getPage, signal) {
  const items = [],
    seen = new Set();
  let cursor;
  do {
    if (signal?.aborted) throw new DOMException('Aborted', 'AbortError');
    const page = await getPage(cursor);
    items.push(...page.items);
    cursor = page.nextCursor;
    if (cursor && seen.has(cursor))
      throw new Error('The server repeated a history cursor. Please retry.');
    if (cursor) seen.add(cursor);
  } while (cursor);
  return items;
}
