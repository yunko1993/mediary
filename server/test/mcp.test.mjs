import test from 'node:test';
import assert from 'node:assert/strict';
import { once } from 'node:events';
import { mkdtempSync, rmSync, readFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { createHash } from 'node:crypto';
import { Store } from '../src/store.mjs';
import { createApp } from '../src/server.mjs';

const owner = 'fake-owner-token-for-tests-only-123456';
const resource = 'https://mediary.example.invalid/mediary/mcp';
const sourceText = '测试药甲，每次1片，每日三次，饭后服用，连续7天。';
const medication = { name: '测试药甲', dose: '每次1片', frequency: '每日三次', instructions: '饭后服用', duration: '连续7天', sourceQuote: sourceText, missing: [] };
const draft = { requestId: 'test-request-0001', sourceText, medication };
const headers = { 'Content-Type': 'application/json', Accept: 'application/json, text/event-stream' };
const formHeaders = { 'Content-Type': 'application/x-www-form-urlencoded' };
const rpc = (url, token, method, params = {}, extra = {}) => fetch(`${url}/mcp`, { method: 'POST', headers: { ...headers, Authorization: `Bearer ${token}`, ...extra }, body: JSON.stringify({ jsonrpc: '2.0', id: 1, method, params }) });

async function needsOAuth(response, error = 'invalid_token', scope = 'drafts:read') {
  assert.equal(response.status, 200);
  const { result } = await response.json();
  assert.equal(result.isError, true);
  assert.equal(result.structuredContent, undefined);
  const challenge = result._meta['mcp/www_authenticate'][0];
  assert.ok(challenge.includes(`error="${error}"`));
  assert.ok(challenge.includes(`scope="${scope}"`));
  assert.ok(challenge.includes('error_description="'));
  assert.ok(challenge.includes('resource_metadata="https://mediary.example.invalid/mediary/.well-known/oauth-protected-resource"'));
}

async function service(callback) {
  const directory = mkdtempSync(join(tmpdir(), 'mediary-mcp-'));
  const store = new Store(directory);
  const config = { token: owner, publicBaseUrl: resource.slice(0, -4), dataDir: directory, apiKey: '' };
  const app = createApp(config, { store, fetchImpl: () => { throw new Error('MCP must never call model APIs'); } });
  app.listen(0, '127.0.0.1'); await once(app, 'listening');
  try { await callback(`http://127.0.0.1:${app.address().port}`, store, config); }
  finally { app.closeAllConnections(); await new Promise(resolve => app.close(resolve)); rmSync(directory, { recursive: true }); }
}

async function authorize(url, scope = 'drafts:read drafts:write offline_access') {
  const registration = await fetch(`${url}/oauth/register`, { method: 'POST', headers, body: JSON.stringify({ client_name: 'ChatGPT', redirect_uris: ['https://chatgpt.com/connector_platform_oauth_redirect'], token_endpoint_auth_method: 'none' }) });
  assert.equal(registration.status, 201);
  const client = await registration.json();
  const verifier = 'v'.repeat(43);
  const challenge = createHash('sha256').update(verifier).digest('base64url');
  const query = new URLSearchParams({ client_id: client.client_id, redirect_uri: client.redirect_uris[0], state: 'csrf-state', scope, resource, response_type: 'code', code_challenge_method: 'S256', code_challenge: challenge });
  const page = await fetch(`${url}/oauth/authorize?${query}`);
  assert.equal(page.status, 200);
  assert.match(page.headers.get('content-security-policy'), /frame-ancestors 'none'/u);
  assert.equal(page.headers.get('referrer-policy'), 'same-origin');
  const html = await page.text();
  const ticket = /name="ticket" value="([^"]+)"/u.exec(html)[1];
  const csrf = /name="csrf" value="([^"]+)"/u.exec(html)[1];
  const cookie = page.headers.get('set-cookie').split(';')[0];
  // 浏览器表单必须保留同源 Origin；空来源和外站即使拿到表单字段，也不能越过来源校验。
  for (const origin of ['null', 'https://attacker.example']) {
    const blocked = await fetch(`${url}/oauth/authorize`, { method: 'POST', redirect: 'manual',
      headers: { ...formHeaders, Cookie: cookie, Origin: origin }, body: new URLSearchParams({ ticket, csrf, password: owner }) });
    assert.equal(blocked.status, 403);
  }
  const missingCsrf = await fetch(`${url}/oauth/authorize`, { method: 'POST', redirect: 'manual', headers: formHeaders, body: new URLSearchParams({ ticket, csrf, password: owner }) });
  assert.equal(missingCsrf.status, 400);
  const response = await fetch(`${url}/oauth/authorize`, { method: 'POST', redirect: 'manual', headers: { ...formHeaders, Cookie: cookie, Origin: new URL(resource).origin }, body: new URLSearchParams({ ticket, csrf, password: owner }) });
  assert.equal(response.status, 303);
  assert.equal(response.headers.get('referrer-policy'), 'no-referrer');
  const redirect = new URL(response.headers.get('location'));
  assert.equal(redirect.searchParams.get('state'), 'csrf-state');
  assert.equal(redirect.searchParams.get('iss'), resource.slice(0, -4));
  const tokenBody = new URLSearchParams({ grant_type: 'authorization_code', client_id: client.client_id, redirect_uri: client.redirect_uris[0], resource, code_verifier: verifier, code: redirect.searchParams.get('code') });
  for (const [key, value] of [['code_verifier', 'w'.repeat(43)], ['resource', 'https://wrong.example/mcp'], ['redirect_uri', 'https://attacker.example/']]) {
    const wrong = new URLSearchParams(tokenBody); wrong.set(key, value);
    assert.equal((await fetch(`${url}/oauth/token`, { method: 'POST', headers: formHeaders, body: wrong })).status, 400);
  }
  const responseToken = await fetch(`${url}/oauth/token`, { method: 'POST', headers: formHeaders, body: tokenBody });
  assert.equal(responseToken.status, 200);
  return { ...(await responseToken.json()), tokenBody, client, page: { ticket, csrf, cookie } };
}

test('MCP-only health and discovery work without an AI key; medical data requires OAuth', async () => service(async url => {
  const health = await fetch(`${url}/health`); assert.equal(health.status, 200); assert.equal((await health.json()).gptConfigured, false);
  const blocked = await fetch(`${url}/mcp`); assert.equal(blocked.status, 401);
  assert.match(blocked.headers.get('www-authenticate'), /oauth-protected-resource/u);
  await needsOAuth(await rpc(url, owner, 'tools/call', { name: 'get_sync_status' }));
  const metadata = await (await fetch(`${url}/.well-known/oauth-authorization-server`)).json();
  assert.deepEqual(metadata.code_challenge_methods_supported, ['S256']); assert.ok(metadata.scopes_supported.includes('offline_access'));
  assert.equal((await (await fetch(`${url}/.well-known/oauth-protected-resource`)).json()).resource, resource);
}));

test('anonymous clients discover OAuth tools and receive login challenges without reading or creating data', async () => service(async (url, store) => {
  const anonymous = (method, params = {}) => fetch(`${url}/mcp`, { method: 'POST', headers,
    body: JSON.stringify({ jsonrpc: '2.0', id: 1, method, params }) });
  const init = await anonymous('initialize', { protocolVersion: '2025-06-18' });
  assert.equal(init.status, 200); assert.equal((await init.json()).result.serverInfo.name, 'mediary');
  const listed = (await (await anonymous('tools/list')).json()).result.tools;
  assert.equal(listed.length, 4);
  for (const tool of listed) {
    assert.equal(tool.securitySchemes[0].type, 'oauth2');
    assert.deepEqual(tool._meta.securitySchemes, tool.securitySchemes);
    const args = tool.name === 'create_medication_draft' ? draft : tool.name === 'get_medication_draft' ? { id: '00000000-0000-4000-8000-000000000000' } : {};
    await needsOAuth(await anonymous('tools/call', { name: tool.name, arguments: args }), 'invalid_token',
      tool.name === 'create_medication_draft' ? 'drafts:write' : 'drafts:read');
  }
  assert.equal(store.state.drafts.length, 0);
  assert.equal(store.state.lastDeviceSyncAt, null);
}));

test('OAuth and Streamable HTTP support initialization, tools, notifications and method errors', async () => service(async url => {
  const auth = await authorize(url);
  const init = await (await rpc(url, auth.access_token, 'initialize', { protocolVersion: '2025-11-25' })).json();
  assert.equal(init.result.protocolVersion, '2025-06-18');
  const list = await (await rpc(url, auth.access_token, 'tools/list')).json();
  assert.equal(list.result.tools.length, 4); assert.equal(list.result.tools[0].annotations.readOnlyHint, false);
  assert.equal((await rpc(url, auth.access_token, 'tools/list', {}, { Origin: 'https://attacker.example' })).status, 403);
  assert.equal((await rpc(url, auth.access_token, 'tools/list', {}, { Accept: 'application/json' })).status, 406);
  assert.equal((await rpc(url, auth.access_token, 'tools/list', {}, { 'MCP-Protocol-Version': 'invalid' })).status, 400);
  const notify = await fetch(`${url}/mcp`, { method: 'POST', headers: { ...headers, Authorization: `Bearer ${auth.access_token}` }, body: JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' }) });
  assert.equal(notify.status, 202); assert.equal(await notify.text(), '');
  assert.equal((await fetch(`${url}/mcp`, { headers: { Authorization: `Bearer ${auth.access_token}` } })).status, 405);
  assert.equal((await (await rpc(url, auth.access_token, 'unknown')).json()).error.code, -32601);
}));

test('drafts survive restart, repeated requests are idempotent, and a conflicting retry fails', async () => service(async (url, store, config) => {
  const auth = await authorize(url);
  const call = args => rpc(url, auth.access_token, 'tools/call', { name: 'create_medication_draft', arguments: args }).then(result => result.json());
  const first = (await call(draft)).result.structuredContent;
  assert.equal(first.status, 'pending'); assert.equal(first.requiresPhoneReview, true); assert.equal(first.reminderStatus, 'unknown_to_server');
  assert.equal((await call(draft)).result.structuredContent.id, first.id);
  const conflict = await call({ ...draft, medication: { ...medication, dose: '' } }); assert.equal(conflict.result.isError, true);
  const restarted = new Store(config.dataDir); assert.equal(restarted.get(first.id).medication.frequency, '每日三次');
  const file = readFileSync(store.file, 'utf8');
  assert.equal(file.includes(auth.access_token), false); assert.equal(file.includes(auth.refresh_token), false); assert.equal(file.includes(owner), false);
}));

test('invented schedules, unknown fields and missing-field advice cannot enter a usable draft', async () => service(async url => {
  const auth = await authorize(url);
  for (const args of [{ ...draft, medication: { ...medication, frequency: '每8小时' } }, { ...draft, enabled: true }, { ...draft, medication: { ...medication, sourceQuote: '虚构原文' } }]) {
    const result = await (await rpc(url, auth.access_token, 'tools/call', { name: 'create_medication_draft', arguments: args })).json(); assert.equal(result.result.isError, true);
  }
  const result = await (await rpc(url, auth.access_token, 'tools/call', { name: 'create_medication_draft', arguments: { ...draft, medication: { ...medication, missing: ['请自行加量'] } } })).json();
  assert.equal(result.result.structuredContent.medication.missing.includes('请自行加量'), false);
}));

test('only the phone can acknowledge drafts; repeated receipts preserve review time and reject plan conflicts', async () => service(async (url, store) => {
  const auth = await authorize(url); const item = store.createDraft(draft);
  const receipts = token => fetch(`${url}/v1/drafts/receipts`, { method: 'POST', headers: { ...headers, Authorization: `Bearer ${token}` }, body: JSON.stringify({ receipts: [{ id: item.id, planId: 'phone-plan-1' }] }) });
  assert.equal((await receipts(auth.access_token)).status, 401);
  assert.equal((await receipts(owner)).status, 200); const reviewed = store.get(item.id).reviewedAt;
  assert.equal((await receipts(owner)).status, 200); assert.equal(store.get(item.id).reviewedAt, reviewed);
  assert.throws(() => store.receive([{ id: item.id, planId: 'another-plan' }]), /另一条/u);
  assert.equal(store.get(item.id).planId, 'phone-plan-1');
  const status = await (await rpc(url, auth.access_token, 'tools/call', { name: 'get_sync_status' })).json(); assert.equal(status.result.structuredContent.reviewed, 1);
  const page = await (await fetch(`${url}/v1/drafts?after=0&limit=20`, { headers: { Authorization: `Bearer ${owner}` } })).json(); assert.equal(page.drafts[0].status, 'reviewed_on_device');
}));

test('an empty phone inbox still acknowledges a successful sync without creating medical data', async () => service(async (url, store) => {
  const response = await fetch(`${url}/v1/drafts/receipts`, { method: 'POST', headers: { ...headers, Authorization: `Bearer ${owner}` }, body: JSON.stringify({ receipts: [] }) });
  assert.equal(response.status, 200);
  assert.ok(store.status().lastDeviceSyncAt);
  assert.equal(store.state.drafts.length, 0);
}));

test('read-only OAuth cannot create drafts; refresh rotates credentials; revocation and owner rotation invalidate grants', async () => service(async (url, store, config) => {
  const read = await authorize(url, 'drafts:read offline_access');
  await needsOAuth(await rpc(url, read.access_token, 'tools/call', { name: 'create_medication_draft', arguments: draft }), 'insufficient_scope', 'drafts:write');
  assert.equal(store.state.drafts.length, 0);
  const body = new URLSearchParams({ grant_type: 'refresh_token', client_id: read.client.client_id, refresh_token: read.refresh_token, resource });
  const response = await fetch(`${url}/oauth/token`, { method: 'POST', headers: formHeaders, body }); assert.equal(response.status, 200);
  const rotated = await response.json(); assert.notEqual(rotated.refresh_token, read.refresh_token);
  assert.equal((await fetch(`${url}/oauth/token`, { method: 'POST', headers: formHeaders, body })).status, 400);
  await needsOAuth(await rpc(url, read.access_token, 'tools/call', { name: 'get_sync_status' }));
  const grant = store.state.grants.find(item => item.clientId === read.client.client_id); grant.accessExpires = 0;
  await needsOAuth(await rpc(url, rotated.access_token, 'tools/call', { name: 'get_sync_status' })); grant.accessExpires = Date.now() + 60000;
  config.token = 'rotated-owner-token-for-tests-only-12345'; await needsOAuth(await rpc(url, rotated.access_token, 'tools/call', { name: 'get_sync_status' })); config.token = owner;
  await fetch(`${url}/oauth/revoke`, { method: 'POST', headers: formHeaders, body: new URLSearchParams({ token: rotated.refresh_token, client_id: read.client.client_id }) });
  await needsOAuth(await rpc(url, rotated.access_token, 'tools/call', { name: 'get_sync_status' }));
}));

test('OAuth rejects redirect substitution, missing CSRF, bad PKCE, wrong audience and reused codes', async () => service(async url => {
  const badRegistration = await fetch(`${url}/oauth/register`, { method: 'POST', headers, body: JSON.stringify({ redirect_uris: ['https://attacker.example/callback'] }) }); assert.equal(badRegistration.status, 400);
  const auth = await authorize(url);
  assert.equal((await fetch(`${url}/oauth/token`, { method: 'POST', headers: formHeaders, body: auth.tokenBody })).status, 400);
  const replayForm = await fetch(`${url}/oauth/authorize`, { method: 'POST', headers: formHeaders, body: new URLSearchParams({ ...auth.page, password: owner }) }); assert.equal(replayForm.status, 400);
  for (const replacement of [{ redirect_uri: 'https://attacker.example/' }, { code_challenge_method: 'plain' }, { resource: 'https://wrong.example/mcp' }]) {
    const query = new URLSearchParams({ response_type: 'code', client_id: auth.client.client_id, redirect_uri: auth.client.redirect_uris[0], state: 'test', code_challenge_method: 'S256', code_challenge: 'c'.repeat(43), resource, ...replacement });
    assert.equal((await fetch(`${url}/oauth/authorize?${query}`)).status, 400);
  }
}));

test('a corrupted data file fails closed instead of silently losing existing drafts', () => {
  const directory = mkdtempSync(join(tmpdir(), 'mediary-corrupt-'));
  try { writeFileSync(join(directory, 'mediary.json'), '{invalid'); assert.throws(() => new Store(directory), /cannot be loaded/u); }
  finally { rmSync(directory, { recursive: true }); }
});
