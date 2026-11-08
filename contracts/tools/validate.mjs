import Ajv2020 from 'ajv/dist/2020.js';
import addFormats from 'ajv-formats';
import { readFileSync } from 'node:fs';

export const root = new URL('../', import.meta.url);
export const readJson = path => JSON.parse(readFileSync(new URL(path, root), 'utf8'));
const ajv = new Ajv2020({ allErrors: true, strict: true, strictTypes: false });
addFormats(ajv);
export const validators = Object.fromEntries(
  ['telemetry', 'state', 'metadata', 'receipt', 'command', 'command-result', 'adc-profile'].map(name =>
    [name, ajv.compile(readJson(`schemas/${name}.schema.json`))]),
);
export { ajv };

// Pure contract checks, not a broker consumer or a durable-ingestion implementation.
// Registry values must come from trusted provisioning, never incoming metadata.
export function validateTelemetry(text, { topic, registry, calibrations, now }) {
  const fail = (code, message) => ({ ok: false, code, message });
  if (Buffer.byteLength(text, 'utf8') > 4096) return fail('PAYLOAD_TOO_LARGE', 'Maximum 4096 UTF-8 bytes.');
  let p;
  try { p = JSON.parse(text); } catch { return fail('INVALID_PAYLOAD', 'Malformed JSON.'); }
  if (p && typeof p === 'object' && p.schemaVersion !== undefined && p.schemaVersion !== 1) {
    return fail('UNSUPPORTED_VERSION', 'Only schemaVersion 1 is supported.');
  }
  if (!validators.telemetry(p)) return fail('INVALID_PAYLOAD', ajv.errorsText(validators.telemetry.errors));
  if (topic !== `airsense/${p.deviceId}/telemetry` || !Object.hasOwn(registry, p.deviceId)) {
    return fail('DEVICE_MISMATCH', 'Topic/device mismatch or unregistered device.');
  }
  if (p.messageId !== `${p.deviceId}:${p.bootId}:${p.sequence}`) {
    return fail('INVALID_PAYLOAD', 'messageId must equal deviceId:bootId:sequence.');
  }
  if (p.measuredAt !== null && Date.parse(p.measuredAt) > now + 120_000) {
    return fail('FUTURE_TIMESTAMP', 'Acquisition time exceeds server clock by more than 120 seconds.');
  }
  const device = registry[p.deviceId];
  // A server provisioning failure is retryable, never a terminal device rejection.
  if (!validators['adc-profile'](device) ||
      device.gasUsableMinMv >= device.gasUsableMaxMv ||
      device.dustUsableMinMv >= device.dustUsableMaxMv) {
    return { ok: false, code: 'CONFIGURATION_ERROR', retryable: true,
      message: 'A complete, valid trusted ESP32-S3 acquisition profile is required.' };
  }
  for (const [sensor, rawPrefix, calibrationKey] of [['mq135', 'gas', 'gasId'], ['dust', 'dust', 'dustId']]) {
    const count = p.raw[`${rawPrefix}AdcCounts`];
    const mv = p.raw[`${rawPrefix}AdcMv`];
    if (count !== null && count > 2 ** device.resolutionBits - 1) {
      return fail('OUT_OF_RANGE', `${rawPrefix} ADC counts exceed provisioned resolution.`);
    }
    if (p.quality[sensor] === 'VALID' &&
        (mv < device[`${rawPrefix}UsableMinMv`] || mv > device[`${rawPrefix}UsableMaxMv`] ||
         count === 0 || count === 2 ** device.resolutionBits - 1)) {
      return fail('OUT_OF_RANGE', `${rawPrefix} valid reading is outside the usable range or at an ADC rail.`);
    }
    const id = p.calibration[calibrationKey];
    if (id !== null) {
      const c = calibrations[id];
      if (!c || c.deviceId !== p.deviceId || c.sensor !== sensor) {
        return fail('INVALID_CALIBRATION', `Unknown or mismatched ${calibrationKey}.`);
      }
      if (rawPrefix === 'gas' && (!(c.baselineAdcMv > 0) || !Number.isFinite(c.baselineAdcMv))) {
        return fail('INVALID_CALIBRATION', 'Gas baseline must be finite and positive.');
      }
      if (rawPrefix === 'dust' && p.values.dustEstimateUgM3 !== null && !c.conversionDocument) {
        return fail('INVALID_CALIBRATION', 'Dust estimate requires a documented conversion.');
      }
      if (rawPrefix === 'gas' && p.quality.mq135 === 'VALID') {
        const expected = p.raw.gasAdcMv / c.baselineAdcMv;
        if (Math.abs(p.values.gasRelative - expected) > 0.001) {
          return fail('INVALID_CALIBRATION', 'gasRelative differs from ADC mV / baseline mV by more than 0.001.');
        }
      }
    }
  }
  return { ok: true, payload: p };
}

// JSON object key order is insignificant; array order and numeric values are significant.
// Check the schema first. Preserve the original received payload separately in storage.
export function canonical(value) {
  if (Array.isArray(value)) return `[${value.map(canonical).join(',')}]`;
  if (value !== null && typeof value === 'object') {
    return `{${Object.keys(value).sort().map(k => `${JSON.stringify(k)}:${canonical(value[k])}`).join(',')}}`;
  }
  return JSON.stringify(value);
}
