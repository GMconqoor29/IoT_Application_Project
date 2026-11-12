import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { freshness, condition, reading } from '../src/features/monitoring/model.js';
import { chartRows, validateRange, allMeasurements } from '../src/features/history/model.js';
import { thresholdPayload } from '../src/features/settings/model.js';
const fixture = (path) =>
  JSON.parse(fs.readFileSync(new URL(`../../contracts/fixtures/${path}`, import.meta.url)));
const latest = fixture('api/Latest.json');
const t = latest.measurement.telemetry;
const now = Date.parse(t.measuredAt) + 1000;

test('freshness expires without another server response and never clears to Normal', () => {
  assert.equal(freshness(latest, now), 'FRESH');
  assert.equal(freshness(latest, now + 91000), 'STALE');
  assert.equal(condition(latest, now + 91000), 'UNKNOWN');
  assert.equal(freshness({ ...latest, measurement: null }, now), 'UNKNOWN');
  assert.equal(freshness(latest, now - 2000), 'UNKNOWN');
});
test('quality is independent per channel; invalid values never become zero', () => {
  const fault = fixture('valid/telemetry-sensor-fault.json');
  assert.equal(reading(fault, 'temperatureC'), null);
  assert.equal(reading(fault, 'gasRelative'), 1.08);
  assert.equal(reading(t, 'dustAdcMv'), 820);
  assert.equal(reading(t, 'dustEstimateUgM3'), null);
  assert.equal(reading(fixture('valid/telemetry-warming-up.json'), 'gasRelative'), null);
});
test('charts retain invalid samples and break long gaps and calibration changes', () => {
  const item = (seconds, calibration = t.calibration.gasId) => ({
    telemetry: {
      ...structuredClone(t),
      measuredAt: new Date(Date.parse(t.measuredAt) + seconds * 1000).toISOString(),
      calibration: { ...t.calibration, gasId: calibration },
    },
  });
  const rows = chartRows(
    [item(0), item(30), item(150), item(180, 'new-baseline')],
    'gasRelative',
    30,
  );
  assert.equal(rows.filter((r) => r.value === null).length, 2);
  assert.equal(
    chartRows([{ telemetry: fixture('valid/telemetry-sensor-fault.json') }], 'temperatureC', 30)[0]
      .value,
    null,
  );
});
test('ranges enforce order and the contractual 31-day maximum', () => {
  assert.equal(validateRange('2026-11-06T00:00:00Z', '2026-11-07T00:00:00Z'), null);
  assert.ok(validateRange('2026-11-07', '2026-11-06'));
  assert.ok(validateRange('2026-09-01', '2026-11-01'));
});
test('threshold payload uses exact editable fields, finite values and strict ordering', () => {
  const rule = fixture('api/Threshold.json');
  const payload = thresholdPayload(rule);
  assert.equal(payload.expectedRevision, rule.revision);
  assert.equal(payload.warning.low, null);
  assert.equal('calibrationId' in payload, false);
  assert.throws(() => thresholdPayload({ ...rule, warning: { low: null, high: '' } }), /finite/);
  assert.throws(() => thresholdPayload({ ...rule, recovery: { low: null, high: 1.2 } }), /ordered/);
  assert.throws(
    () => thresholdPayload({ ...rule, alert: { low: null, high: Infinity } }),
    /finite/,
  );
  const range = {
    ...rule,
    direction: 'RANGE',
    alert: { low: 0, high: 5 },
    warning: { low: 1, high: 4 },
    recovery: { low: 2, high: 3 },
  };
  assert.equal(thresholdPayload(range).recovery.high, 3);
  assert.equal(thresholdPayload({ ...range, direction: 'LOWER' }).warning.high, null);
});
test('history traverses every cursor and rejects loops instead of showing partial totals', async () => {
  const cursors = [];
  const rows = await allMeasurements(async (cursor) => {
    cursors.push(cursor);
    return cursor ? { items: [2], nextCursor: null } : { items: [1], nextCursor: 'next' };
  });
  assert.deepEqual(cursors, [undefined, 'next']);
  assert.deepEqual(rows, [1, 2]);
  await assert.rejects(
    () => allMeasurements(async () => ({ items: [], nextCursor: 'loop' })),
    /repeated/,
  );
});
