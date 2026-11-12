import latest from '../../../contracts/fixtures/api/Latest.json';
import history from '../../../contracts/fixtures/api/MeasurementPage.json';
import statistics from '../../../contracts/fixtures/api/Statistics.json';
import threshold from '../../../contracts/fixtures/api/Threshold.json';
import warmup from '../../../contracts/fixtures/valid/telemetry-warming-up.json';
import uncalibrated from '../../../contracts/fixtures/valid/telemetry-uncalibrated.json';
import fault from '../../../contracts/fixtures/valid/telemetry-sensor-fault.json';
import dust from '../../../contracts/fixtures/valid/telemetry-dust-estimate.json';
import { reading, channels } from '../features/monitoring/model.js';

export const fixtureTime = Date.parse(latest.measurement.telemetry.measuredAt);
export const scenarios = [
  'Normal',
  'Warming up',
  'Uncalibrated',
  'Sensor fault',
  'Dust estimate',
  'Stale',
  'Offline',
  'No readings',
  'Warning',
  'Alert',
];
export function fixtureLatest(scenario) {
  const result = structuredClone(latest);
  const telemetry = {
    'Warming up': warmup,
    Uncalibrated: uncalibrated,
    'Sensor fault': fault,
    'Dust estimate': dust,
  }[scenario];
  if (telemetry) result.measurement.telemetry = structuredClone(telemetry);
  if (scenario === 'No readings') {
    result.measurement = null;
    result.condition = 'UNKNOWN';
    result.device.freshness = 'UNKNOWN';
  }
  if (scenario === 'Stale' || scenario === 'Offline') {
    result.device.freshness = 'STALE';
    result.condition = 'UNKNOWN';
  }
  if (scenario === 'Offline') result.device.online = false;
  if (['Sensor fault', 'Warming up', 'Uncalibrated'].includes(scenario))
    result.condition = 'UNKNOWN';
  if (['Warning', 'Alert'].includes(scenario)) {
    result.condition = scenario.toUpperCase();
    result.measurement.telemetry.values.gasRelative = scenario === 'Alert' ? 1.35 : 1.22;
    result.measurement.telemetry.raw.gasAdcMv = scenario === 'Alert' ? 1350 : 1220;
    result.reasons = [
      `Relative gas indicator crossed the synthetic ${scenario.toLowerCase()} boundary at ${scenario === 'Alert' ? 1.3 : 1.2} ratio in D9-405.`,
    ];
    result.recommendations = [
      {
        id: 'fixture-gas-advice',
        status: 'ACTIVE',
        message:
          'Investigate nearby sources and check ventilation. MQ-135 does not establish CO₂ concentration or identify a gas.',
        measurementIds: [result.measurement.telemetry.messageId],
      },
    ];
  }
  return result;
}
export const fixtureThresholds = [threshold];
export function fixtureHistory(from, to) {
  const items = structuredClone(history.items).filter(
    (m) =>
      Date.parse(m.telemetry.measuredAt) >= Date.parse(from) &&
      Date.parse(m.telemetry.measuredAt) < Date.parse(to),
  );
  const stats = structuredClone(statistics);
  stats.from = from;
  stats.to = to;
  for (const channel of channels) {
    const values = items.map((m) => reading(m.telemetry, channel.key)).filter((v) => v != null);
    stats.parameters[channel.key] = {
      ...stats.parameters[channel.key],
      min: values.length ? Math.min(...values) : null,
      max: values.length ? Math.max(...values) : null,
      mean: values.length ? values.reduce((a, b) => a + b, 0) / values.length : null,
      validCount: values.length,
      calibrationId: values.length ? stats.parameters[channel.key].calibrationId : null,
    };
  }
  return { items, stats };
}
