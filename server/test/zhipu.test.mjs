import test from 'node:test';
import assert from 'node:assert/strict';
import { once } from 'node:events';
import { configuration, createApp } from '../src/server.mjs';
import { extract, ZHIPU_BASE_URL, ZHIPU_MODEL } from '../src/extraction.mjs';

const source = '测试药甲，每次1片，每日三次，饭后服用，连续7天。';
const medication = { name: '测试药甲', dose: '每次1片', frequency: '每日三次', instructions: '饭后服用', duration: '连续7天', sourceQuote: source, missing: [] };
const payload = { medications: [medication] };
const config = configuration({ AI_PROVIDER: 'zhipu', ZHIPU_API_KEY: 'test-zhipu-key', MEDIARY_ACCESS_TOKEN: 'test-service-token-1234567890' });
const completion = (overrides = {}) => ({ model: ZHIPU_MODEL, choices: [{ finish_reason: 'stop', message: { content: JSON.stringify(payload) } }], ...overrides });
const jsonResponse = value => new Response(JSON.stringify(value));

test('new configuration pins Zhipu origin and model, ignoring alternative model variables', () => {
  const fresh = configuration({ ZHIPU_API_KEY: 'test-key', ZHIPU_MODEL: 'glm-4.7', ZHIPU_BASE_URL: 'https://wrong.example' });
  assert.equal(fresh.provider, 'zhipu');
  assert.equal(fresh.baseUrl, ZHIPU_BASE_URL);
  assert.equal(fresh.model, ZHIPU_MODEL);
  assert.equal(fresh.apiKey, 'test-key');
  assert.equal(fresh.host, '127.0.0.1');
});

test('legacy OpenAI environment retains its provider; explicit Zhipu uses a separate key', () => {
  assert.equal(configuration({ OPENAI_API_KEY: 'old-key' }).provider, 'openai');
  assert.equal(configuration({ OPENAI_MODEL: 'old-model' }).model, 'old-model');
  const selected = configuration({ AI_PROVIDER: 'zhipu', OPENAI_API_KEY: 'old-key', ZHIPU_API_KEY: 'new-key' });
  assert.equal(selected.apiKey, 'new-key');
  assert.equal(selected.model, ZHIPU_MODEL);
  assert.equal(configuration({ AI_PROVIDER: 'zhipu', OPENAI_API_KEY: 'old-key' }).apiKey, '');
  assert.throws(() => configuration({ AI_PROVIDER: 'unknown' }), /AI_PROVIDER/);
});

test('Zhipu sends the fixed model, JSON schema and final text through Chat Completions', async () => {
  let count = 0;
  const result = await extract(source, config, async (url, options) => {
    count++;
    assert.equal(url.href, `${ZHIPU_BASE_URL}/chat/completions`);
    assert.equal(options.headers.Authorization, `Bearer ${config.apiKey}`);
    assert.equal(options.redirect, 'error');
    assert.ok(options.signal instanceof AbortSignal);
    const request = JSON.parse(options.body);
    assert.equal(request.model, ZHIPU_MODEL);
    assert.equal(request.stream, false);
    assert.equal(request.max_tokens, 5000);
    assert.equal(request.response_format.type, 'json_object');
    assert.match(request.messages[0].content, /sourceQuote/);
    assert.equal(request.messages[1].content, source);
    return jsonResponse(completion());
  });
  assert.equal(count, 1);
  assert.equal(result.medications[0].frequency, '每日三次');
  assert.ok(result.medications[0].missing.includes('具体提醒时间需人工核对'));
});

test('changed origin, model or missing key fails before any network request', async () => {
  let calls = 0;
  const fetchImpl = async () => { calls++; throw new Error('must not run'); };
  for (const patch of [{ baseUrl: 'https://wrong.example/v4' }, { model: 'glm-4.7' }, { apiKey: '' }]) {
    await assert.rejects(extract(source, { ...config, ...patch }, fetchImpl), error => error.status === 503);
  }
  assert.equal(calls, 0);
});

for (const model of ['glm-4.7', 'glm-4.7-flashx', undefined]) {
  test(`rejects returned model ${model} without retry or fallback`, async () => {
    let calls = 0;
    await assert.rejects(extract(source, config, async () => { calls++; return jsonResponse(completion({ model })); }), /模型标识/);
    assert.equal(calls, 1);
  });
}

test('truncated output, refusal, tools and multiple choices never become drafts', async () => {
  for (const choices of [
    [{ finish_reason: 'length', message: { content: JSON.stringify(payload) } }],
    [{ finish_reason: 'stop', message: { refusal: 'cannot process', content: JSON.stringify(payload) } }],
    [{ finish_reason: 'stop', message: { tool_calls: [{}], content: JSON.stringify(payload) } }],
    [{ finish_reason: 'stop', message: { tool_calls: {}, content: JSON.stringify(payload) } }],
    [], [completion().choices[0], completion().choices[0]],
  ]) {
    await assert.rejects(extract(source, config, async () => jsonResponse(completion({ choices }))));
  }
});

test('reasoning text cannot replace final JSON; final JSON still requires source evidence', async () => {
  const reply = message => jsonResponse(completion({ choices: [{ finish_reason: 'stop', message }] }));
  await assert.rejects(extract(source, config, async () => reply({ reasoning_content: JSON.stringify(payload) })), /格式异常/);
  const invented = { medications: [{ ...medication, frequency: '每8小时' }] };
  await assert.rejects(extract(source, config, async () => reply({ content: JSON.stringify(invented) })), /原文未明确/);
  const result = await extract(source, config, async () => reply({ reasoning_content: '无关内容', content: JSON.stringify(payload) }));
  assert.equal(result.medications[0].name, medication.name);
});

test('upstream HTTP errors do not leak details and do not cause provider fallback', async () => {
  for (const status of [401, 429, 500]) {
    let calls = 0;
    await assert.rejects(extract(source, config, async () => {
      calls++;
      return new Response(`${config.apiKey} ${source}`, { status });
    }), error => error.status === (status === 429 ? 429 : 502) && !error.message.includes(source) && !error.message.includes(config.apiKey));
    assert.equal(calls, 1);
  }
});

test('upstream body is bounded after decompression and malformed JSON is sanitized', async () => {
  await assert.rejects(extract(source, config, async () => new Response('x'.repeat(1_048_577))), /内容过大/);
  await assert.rejects(extract(source, config, async () => new Response(config.apiKey)), error => error.status === 502 && !error.message.includes(config.apiKey));
  await assert.rejects(extract(source, config, async () => { throw new Error(`${config.apiKey} ${source}`); }), /无法连接 AI/);
});

async function withService(callback, selected = config) {
  let calls = 0;
  const app = createApp(selected, { fetchImpl: async () => { calls++; return jsonResponse(completion()); } });
  app.listen(0, '127.0.0.1');
  await once(app, 'listening');
  const url = `http://127.0.0.1:${app.address().port}`;
  const post = body => fetch(`${url}/v1/instructions/parse`, { method: 'POST', headers: {
    Authorization: `Bearer ${selected.token}`, 'Content-Type': 'application/json',
  }, body: JSON.stringify(body) });
  try { await callback({ url, post, calls: () => calls }); }
  finally { app.closeAllConnections(); await new Promise(resolve => app.close(resolve)); }
}

test('HTTP Zhipu relay returns the existing Android draft contract without upstream secrets', async () => {
  await withService(async ({ post, calls }) => {
    const response = await post({ text: source });
    assert.equal(response.status, 200);
    const body = await response.text();
    assert.equal(JSON.parse(body).medications[0].name, medication.name);
    assert.equal(body.includes(config.apiKey), false);
    assert.equal(body.includes(config.token), false);
    assert.equal(calls(), 1);
  });
});

test('phone cannot override model, destination, provider or upstream key', async () => {
  await withService(async ({ post, calls }) => {
    for (const override of [{ model: 'glm-4.7' }, { baseUrl: 'https://wrong.example' }, { provider: 'openai' }, { apiKey: 'other-key' }]) {
      assert.equal((await post({ text: source, ...override })).status, 400);
    }
    assert.equal(calls(), 0);
  });
});

test('health checks verify local configuration without calling AI', async () => {
  for (const patch of [{}, { apiKey: '' }, { token: 'short' }, { model: 'glm-4.7' }]) {
    await withService(async ({ url, calls }) => {
      const response = await fetch(`${url}/health`);
      assert.equal(response.status, Object.keys(patch).length ? 503 : 200);
      const body = await response.text();
      assert.equal(body.includes(config.apiKey), false);
      assert.equal(body.includes(config.token), false);
      assert.equal(calls(), 0);
    }, { ...config, ...patch });
  }
});

test('personal relay limits request frequency before further upstream calls', async () => {
  await withService(async ({ post, calls }) => {
    for (let index = 0; index < 10; index++) assert.equal((await post({ text: source })).status, 200);
    assert.equal((await post({ text: source })).status, 429);
    assert.equal(calls(), 10);
  });
});
