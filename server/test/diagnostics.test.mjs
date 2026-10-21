import test from 'node:test';
import assert from 'node:assert/strict';
import { EventEmitter } from 'node:events';
import { once } from 'node:events';
import { configuration, createApp } from '../src/server.mjs';
import { Store } from '../src/store.mjs';
import { observeMcpRequest } from '../src/diagnostics.mjs';

test('diagnostics require explicit opt-in and the default service emits no request logs', async t => {
  assert.equal(configuration({}).mcpDiagnostics, false);
  for (const value of ['', '0', 'true', 'unexpected']) assert.equal(configuration({ MCP_DIAGNOSTICS: value }).mcpDiagnostics, false);
  assert.equal(configuration({ MCP_DIAGNOSTICS: '1' }).mcpDiagnostics, true);
  const logs = t.mock.method(console, 'log', () => {});
  for (const enabled of [false, true]) {
    const app = createApp({ publicBaseUrl: 'https://mediary.example.invalid', token: 'test-owner-token-for-diagnostics-only', mcpDiagnostics: enabled }, { store: new Store() });
    app.listen(0, '127.0.0.1'); await once(app, 'listening');
    try {
      const response = await fetch(`http://127.0.0.1:${app.address().port}/.well-known/oauth-protected-resource?private=test-secret`);
      assert.equal(response.status, 200); await response.json();
      assert.equal(logs.mock.callCount(), enabled ? 1 : 0);
      if (enabled) {
        const event = JSON.parse(logs.mock.calls[0].arguments[0]);
        assert.equal(event.route, 'resource_metadata'); assert.equal(event.status, 200);
        assert.equal(JSON.stringify(event).includes('test-secret'), false);
      }
    } finally { app.closeAllConnections(); await new Promise(resolve => app.close(resolve)); }
  }
});

test('diagnostics allowlist stages and never serialize untrusted method names, headers, queries or results', () => {
  const events = [];
  const secret = 'SENSITIVE_SENTINEL_DO_NOT_LOG';
  const request = { method: secret, url: `/mcp?code=${secret}`, headers: { authorization: secret }, body: secret };
  const response = new EventEmitter(); response.statusCode = 200;
  const diagnostic = observeMcpRequest(request, response, '/mcp', event => events.push(event));
  diagnostic.rpc(`tools/${secret}`, secret);
  diagnostic.result({ isError: true, content: [{ type: 'text', text: secret }], _meta: { 'mcp/www_authenticate': [secret] } });
  response.emit('finish'); response.emit('finish');
  assert.equal(events.length, 1);
  assert.deepEqual(Object.keys(events[0]).sort(), ['at', 'event', 'method', 'operation', 'outcome', 'route', 'status']);
  assert.equal(events[0].method, 'other'); assert.equal(events[0].operation, 'rpc_other');
  assert.equal(events[0].outcome, 'auth_required'); assert.equal(JSON.stringify(events).includes(secret), false);
  observeMcpRequest(request, response, `/private/${secret}`, event => events.push(event));
  response.emit('finish'); assert.equal(events.length, 1);
  const faulty = new EventEmitter(); faulty.statusCode = 200;
  observeMcpRequest({ method: 'POST' }, faulty, '/oauth/token', () => { throw new Error(secret); });
  assert.doesNotThrow(() => faulty.emit('finish'));
});
