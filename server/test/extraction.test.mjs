import test from 'node:test';
import assert from 'node:assert/strict';
import { once } from 'node:events';
import { validateExtraction } from '../src/extraction.mjs';
import { configuration, createApp } from '../src/server.mjs';

const source = '测试药甲，每次1片，每日三次，饭后服用，连续7天。';
const medication = { name: '测试药甲', dose: '每次1片', frequency: '每日三次', instructions: '饭后服用', duration: '连续7天', sourceQuote: source, missing: [] };
const payload = { medications: [medication] };

test('extracts only quoted fields and requires human time review', () => {
  const result = validateExtraction(payload, source);
  assert.equal(result.medications[0].frequency, '每日三次');
  assert.ok(result.medications[0].missing.includes('具体提醒时间需人工核对'));
});
test('rejects invented doses and automatic q8h conversions', () => {
  assert.throws(() => validateExtraction({ medications: [{ ...medication, dose: '每次2片' }] }, source), /原文未明确/);
  assert.throws(() => validateExtraction({ medications: [{ ...medication, frequency: '每8小时' }] }, source), /原文未明确/);
});
test('rejects fabricated source and unrecognized fields', () => {
  assert.throws(() => validateExtraction({ medications: [{ ...medication, sourceQuote: '虚构医嘱' }] }, source), /引文/);
  assert.throws(() => validateExtraction({ medications: [{ ...medication, recommendedDose: '2片' }] }, source), /格式异常/);
});
test('marks missing information without inferring', () => {
  const result = validateExtraction({ medications: [{ ...medication, dose: '', frequency: '', duration: '' }] }, source);
  assert.ok(result.medications[0].missing.includes('每次剂量'));
  assert.equal(result.medications[0].dose, '');
});

test('missing hints cannot smuggle dose adjustment advice', () => {
  const result = validateExtraction({ medications: [{ ...medication, missing: ['自行加量', '开始日期'] }] }, source);
  assert.ok(result.medications[0].missing.includes('开始日期'));
  assert.ok(!result.medications[0].missing.includes('自行加量'));
});

test('removed extraction endpoint stays unavailable and health requires MCP', async () => {
  const config = configuration({ MEDIARY_ACCESS_TOKEN: 'test-service-token-1234567890' });
  const app = createApp(config);
  app.listen(0, '127.0.0.1');
  await once(app, 'listening');
  const url = `http://127.0.0.1:${app.address().port}`;
  try {
    const response = await fetch(`${url}/v1/instructions/parse`, { method: 'POST',
      headers: { Authorization: `Bearer ${config.token}`, 'Content-Type': 'application/json' },
      body: JSON.stringify({ text: source }) });
    assert.equal(response.status, 404);
    assert.deepEqual(await response.json(), { error: '接口不存在' });
    const health = await fetch(`${url}/health`);
    assert.equal(health.status, 503);
    assert.deepEqual(await health.json(), { status: 'not_configured', mcpConfigured: false, accessConfigured: true });
  } finally { app.closeAllConnections(); await new Promise(resolve => app.close(resolve)); }
});
