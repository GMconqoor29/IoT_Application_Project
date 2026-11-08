import assert from 'node:assert/strict';
import { ajv, canonical, readJson, validateTelemetry, validators } from './validate.mjs';

let passed = 0;
function check(name, action) {
  try { action(); passed++; console.log(`PASS ${name}`); }
  catch (error) { console.error(`FAIL ${name}`); throw error; }
}
for (const fixture of readJson('fixtures/manifest.json')) {
  check(fixture.file, () => {
    const validate = validators[fixture.schema];
    assert.equal(validate(readJson(`fixtures/${fixture.file}`)), fixture.valid,
      `${fixture.reason ?? ''} ${ajv.errorsText(validate.errors)}`);
  });
}
const normal = readJson('fixtures/valid/telemetry-normal.json');
const context = {
  topic: 'airsense/AIRSENSE-001/telemetry',
  now: Date.parse('2026-11-06T07:00:01Z'),
  registry: { 'AIRSENSE-001': readJson('fixtures/profiles/simulator-s3.json') },
  calibrations: {
    'gas-baseline-001': { deviceId: 'AIRSENSE-001', sensor: 'mq135', baselineAdcMv: 1000 },
    'dust-fit-001': { deviceId: 'AIRSENSE-001', sensor: 'dust', conversionDocument: 'synthetic-test-only' },
  },
};
const run = (p, overrides = {}) => validateTelemetry(JSON.stringify(p), { ...context, ...overrides });
for (const fixture of readJson('fixtures/manifest.json').filter(f => f.valid && f.schema === 'telemetry')) {
  check(`semantic ${fixture.file}`, () => assert.equal(run(readJson(`fixtures/${fixture.file}`)).ok, true));
}
check('topic mismatch', () => assert.equal(run(normal, { topic: 'airsense/OTHER/telemetry' }).code, 'DEVICE_MISMATCH'));
check('unknown device', () => assert.equal(run(normal, { registry: {} }).code, 'DEVICE_MISMATCH'));
const profile = context.registry['AIRSENSE-001'];
for (const [name, badProfile] of [
  ['missing profile', undefined], ['empty profile', {}], ['null profile', null],
  ['missing voltage bound', { ...profile, gasUsableMaxMv: undefined }],
  ['NaN voltage', { ...profile, gasUsableMaxMv: NaN }],
  ['infinite voltage', { ...profile, gasUsableMaxMv: Infinity }],
  ['wrong resolution', { ...profile, resolutionBits: 16 }],
  ['wrong chip', { ...profile, chipModel: 'ESP32' }],
  ['unsafe usable maximum', { ...profile, dustUsableMaxMv: 9000 }],
  ['inverted range', { ...profile, dustUsableMinMv: 3001 }],
  ['uncommissioned hardware', { ...profile, kind: 'HARDWARE' }],
]) {
  check(name, () => {
    const result = run(normal, { registry: { 'AIRSENSE-001': badProfile } });
    assert.equal(result.code, 'CONFIGURATION_ERROR');
    assert.equal(result.retryable, true);
  });
}
check('reviewed hardware profile', () => assert.equal(run(normal, { registry: {
  'AIRSENSE-001': { ...profile, kind: 'HARDWARE', commissioningReference: 'synthetic-test-record' },
} }).ok, true));
check('inherited registry key rejected', () => assert.equal(run(normal, {
  registry: Object.create({ 'AIRSENSE-001': profile }),
}).code, 'DEVICE_MISMATCH'));
check('valid ADC below usable voltage', () => assert.equal(run({ ...normal, raw: { ...normal.raw, dustAdcMv: 100 } }).code, 'OUT_OF_RANGE'));
for (const count of [0, 4095]) {
  check(`valid ADC at rail ${count}`, () => assert.equal(run({ ...normal, raw: { ...normal.raw, dustAdcCounts: count } }).code, 'OUT_OF_RANGE'));
  check(`invalid ADC rail diagnostics ${count} preserved`, () => assert.equal(run({ ...normal,
    quality: { ...normal.quality, dust: 'INVALID' }, raw: { ...normal.raw, dustAdcCounts: count },
  }).ok, true));
}
check('impossible counts rejected even for fault diagnostics', () => assert.equal(run({ ...normal,
  quality: { ...normal.quality, dust: 'INVALID' }, raw: { ...normal.raw, dustAdcCounts: 65535 },
}).code, 'OUT_OF_RANGE'));
check('identity mismatch', () => assert.equal(run({ ...normal, sequence: 124 }).code, 'INVALID_PAYLOAD'));
check('future clock', () => assert.equal(run({ ...normal, measuredAt: '2026-11-06T07:02:02Z' }).code, 'FUTURE_TIMESTAMP'));
check('historical replay remains admissible', () => assert.equal(run(normal, { now: context.now + 86400000 }).ok, true));
check('invalid baseline', () => assert.equal(run(normal, { calibrations: { 'gas-baseline-001': { deviceId: normal.deviceId, sensor: 'mq135', baselineAdcMv: 0 } } }).code, 'INVALID_CALIBRATION'));
check('ratio mismatch', () => assert.equal(run({ ...normal, values: { ...normal.values, gasRelative: 2 } }).code, 'INVALID_CALIBRATION'));
check('counts exceed board resolution', () => assert.equal(run({ ...normal, raw: { ...normal.raw, gasAdcCounts: 4096 } }).code, 'OUT_OF_RANGE'));
check('valid ADC exceeds usable voltage', () => assert.equal(run({ ...normal, raw: { ...normal.raw, dustAdcMv: 3100 } }).code, 'OUT_OF_RANGE'));
check('oversize payload', () => assert.equal(validateTelemetry(' '.repeat(4097), context).code, 'PAYLOAD_TOO_LARGE'));
check('malformed JSON', () => assert.equal(validateTelemetry('{', context).code, 'INVALID_PAYLOAD'));
check('infinite JSON number', () => assert.equal(validateTelemetry(JSON.stringify(normal).replace('27.1', '1e999'), context).code, 'INVALID_PAYLOAD'));
check('canonical identical duplicate', () => assert.equal(canonical(normal), canonical(Object.fromEntries(Object.entries(normal).reverse()))));
check('canonical conflicting duplicate', () => assert.notEqual(canonical(normal), canonical({ ...normal, uptimeMs: 0 })));
for (const [schema, patch] of [
  ['state', { online: true, reason: 'CONNECTION_LOST' }],
  ['receipt', { status: 'STORED', code: 'ID_CONFLICT' }],
  ['command-result', { status: 'APPLIED', code: 'EXPIRED' }],
]) {
  check(`${schema} contradictory status`, () => {
    const file = { state: 'state-online', receipt: 'receipt-stored', 'command-result': 'command-result' }[schema];
    assert.equal(validators[schema]({ ...readJson(`fixtures/valid/${file}.json`), ...patch }), false);
  });
}

const api = readJson('openapi.json');
check('current-alert scope excludes historical endpoint and models', () => {
  assert.equal(api.info.version, '1.1.0');
  assert.equal(api.paths['/api/alerts'], undefined);
  assert.equal(api.components.schemas.Alert, undefined);
  assert.equal(api.components.schemas.AlertPage, undefined);
});
check('notification suppression belongs to backend configuration', () => {
  for (const name of ['Threshold', 'ThresholdUpdate']) {
    for (const field of ['enterConsecutive', 'clearConsecutive', 'cooldownSec']) {
      assert.equal(api.components.schemas[name].properties[field], undefined);
      assert.equal(api.components.schemas[name].required.includes(field), false);
    }
  }
});
check('OpenAPI references resolve', () => {
  const visit = value => {
    if (!value || typeof value !== 'object') return;
    if (value.$ref) {
      assert.ok(value.$ref.startsWith('#/'), 'Only local API references are expected.');
      let target = api;
      for (const segment of value.$ref.slice(2).split('/')) {
        target = target?.[segment.replaceAll('~1', '/').replaceAll('~0', '~')];
      }
      assert.ok(target, `Unresolved ${value.$ref}`);
    }
    Object.values(value).forEach(visit);
  };
  visit(api);
});
const apiId = 'https://airsense.example/contracts/v1/openapi.json';
// OpenAPI is not itself a JSON Schema. Extract its schema objects into $defs.
const apiDefinitions = JSON.parse(JSON.stringify(api.components.schemas)
  .replaceAll('#/components/schemas/', '#/$defs/'));
ajv.addSchema({ $id: apiId, $defs: apiDefinitions });
for (const name of Object.keys(api.components.schemas)) {
  check(`compile API ${name}`, () => {
    const validate = ajv.compile({ $ref: `${apiId}#/$defs/${name}` });
    for (const example of api.components.schemas[name].examples ?? []) {
      assert.equal(validate(example), true, ajv.errorsText(validate.errors));
      assert.deepEqual(example, readJson(`fixtures/api/${name}.json`));
    }
  });
}
for (const [apiName, file] of [['Telemetry', 'telemetry'], ['Metadata', 'metadata'], ['DeviceState', 'state']]) {
  check(`API shares ${file} schema`, () => {
    const original = readJson(`schemas/${file}.schema.json`);
    delete original.$id; delete original.$schema;
    assert.deepEqual(api.components.schemas[apiName], original);
  });
}
for (const [path, methods] of Object.entries(api.paths)) {
  for (const [method, operation] of Object.entries(methods)) {
    check(`${method.toUpperCase()} ${path} security`, () => {
      if (method === 'get' || path.endsWith('/login')) assert.deepEqual(operation.security, []);
      else assert.deepEqual(operation.security, [{ adminSession: [], csrfHeader: [] }]);
      assert.ok(operation.operationId);
      assert.ok(operation.responses);
    });
  }
}
for (const [index, fixture] of readJson('fixtures/canonical.json').entries()) {
  check(`cross-language canonical fixture ${index}`, () => assert.equal(canonical(fixture.input), fixture.expected));
}
console.log(`\n${passed} contract checks passed. These do not test a broker, server, or hardware.`);
