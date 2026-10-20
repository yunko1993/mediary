import test from 'node:test';
import assert from 'node:assert/strict';
import { once } from 'node:events';
import { validateExtraction, readResponse } from '../src/extraction.mjs';
import { createApp } from '../src/server.mjs';

const source = '测试药甲，每次1片，每日三次，饭后服用，连续7天。';
const medication = { name: '测试药甲', dose: '每次1片', frequency: '每日三次', instructions: '饭后服用', duration: '连续7天', sourceQuote: source, missing: [] };
const payload = { medications: [medication] };
const completed = { status: 'completed', output: [{ type: 'message', content: [{ type: 'output_text', text: JSON.stringify(payload) }] }] };

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
test('refusals and incomplete output cannot become plans', () => {
  assert.throws(() => readResponse({ status: 'incomplete', output: [] }), /未完成/);
  assert.throws(() => readResponse({ status: 'completed', output: [{ content: [{ type: 'refusal' }] }] }), /未能处理/);
  assert.throws(() => readResponse({ status: 'completed', output: [{ content: [{ type: 'output_text', text: '{' }] }] }), /不完整/);
});

test('malformed Responses content returns a sanitized upstream error', () => {
  for (const content of [[null], [{ type: 'output_text', text: {} }]]) {
    assert.throws(() => readResponse({ status: 'completed', output: [{ content }] }), error => error.status === 502);
  }
});

async function withService(callback, options = {}) {
  const config = { token: 'test-service-token-1234567890', apiKey: 'test-key-never-sent', baseUrl: 'https://api.openai.com/v1', model: 'configured-model' };
  const app = createApp({ ...config, ...options.config }, { fetchImpl: options.fetchImpl ?? (async () => new Response(JSON.stringify(completed))) });
  app.listen(0, '127.0.0.1');
  await once(app, 'listening');
  try { await callback(`http://127.0.0.1:${app.address().port}`, config); }
  finally { app.closeAllConnections(); await new Promise(resolve => app.close(resolve)); }
}
const post = (url, config, body, extra = {}) => fetch(`${url}/v1/instructions/parse`, {
  method: 'POST', headers: { Authorization: `Bearer ${config.token}`, 'Content-Type': 'application/json', ...extra }, body: JSON.stringify(body),
});

test('HTTP route rejects missing auth and malformed input', async () => {
  await withService(async (url, config) => {
    assert.equal((await post(url, config, { text: source }, { Authorization: 'Bearer wrong' })).status, 401);
    assert.equal((await post(url, config, { text: '' })).status, 400);
    assert.equal((await post(url, config, { text: 'a'.repeat(12001) })).status, 400);
    assert.equal((await post(url, config, { text: source }, { 'Content-Type': 'text/plain' })).status, 415);
  });
});
test('HTTP integration uses Responses schema, store:false and configured model', async () => {
  let sent;
  await withService(async (url, config) => {
    const response = await post(url, config, { text: source });
    assert.equal(response.status, 200);
    assert.equal((await response.json()).medications[0].name, '测试药甲');
    assert.equal(sent.model, config.model);
    assert.equal(sent.store, false);
    assert.equal(sent.text.format.type, 'json_schema');
    assert.equal(sent.input, source);
    assert.equal('apiKey' in sent, false);
  }, { fetchImpl: async (url, options) => { assert.equal(url.pathname, '/v1/responses'); sent = JSON.parse(options.body); return new Response(JSON.stringify(completed)); } });
});
test('missing API key returns actionable error; no fake AI fallback', async () => {
  await withService(async (url, config) => {
    const response = await post(url, config, { text: source });
    assert.equal(response.status, 503);
    assert.match((await response.json()).error, /OPENAI_API_KEY/);
  }, { config: { apiKey: '' } });
});
test('upstream failures cannot leak request text or key', async () => {
  await withService(async (url, config) => {
    const response = await post(url, config, { text: source });
    const body = await response.text();
    assert.equal(response.status, 502);
    assert.equal(body.includes(source), false);
    assert.equal(body.includes(config.apiKey), false);
  }, { fetchImpl: async () => new Response('sensitive upstream body', { status: 401 }) });
});
