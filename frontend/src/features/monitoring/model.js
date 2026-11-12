export const channels = [
  {
    key: 'temperatureC',
    label: 'Temperature',
    unit: '°C',
    sensor: 'sht30',
    model: 'SHT30',
    color: '#c77c48',
    icon: '◌',
  },
  {
    key: 'humidityPct',
    label: 'Relative humidity',
    unit: '%RH',
    sensor: 'sht30',
    model: 'SHT30',
    color: '#548eb1',
    icon: '◈',
  },
  {
    key: 'gasRelative',
    label: 'Relative gas indicator',
    unit: 'ratio',
    sensor: 'mq135',
    model: 'MQ-135',
    color: '#8e79b0',
    icon: '≋',
  },
  {
    key: 'dustAdcMv',
    label: 'Dust sensor response',
    unit: 'mV',
    sensor: 'dust',
    model: 'GP2Y1010AU0F',
    color: '#658a60',
    icon: '⁙',
  },
  {
    key: 'dustEstimateUgM3',
    label: 'Experimental dust estimate',
    unit: 'µg/m³',
    sensor: 'dust',
    model: 'GP2Y1010AU0F',
    color: '#658a60',
    icon: '⁙',
  },
];
export function reading(telemetry, key) {
  const channel = channels.find((c) => c.key === key);
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
