import test from 'node:test';
import assert from 'node:assert/strict';
import { mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { OAuth } from '../src/oauth.mjs';
import { Store, digest } from '../src/store.mjs';

const config = { publicBaseUrl: 'https://mediary.example.invalid', token: 'synthetic-review-credential-00000000' };
const registration = { redirect_uris: ['https://chatgpt.com/connector_platform_oauth_redirect'] };
const make = (store = new Store('')) => ({ store, oauth: new OAuth(config, store) });
function grant(fixture) {
  const client = fixture.oauth.register(registration);
  fixture.store.change(state => state.grants.push({ clientId: client.client_id, scope: 'drafts:read drafts:write offline_access',
    ownerHash: digest(config.token), resource: fixture.oauth.resource(), accessHash: digest('synthetic-access'),
    refreshHash: digest('synthetic-refresh'), accessExpires: Date.now() + 3600_000, refreshExpires: Date.now() + 86400_000 }));
  return new URLSearchParams({ grant_type: 'refresh_token', client_id: client.client_id, resource: fixture.oauth.resource(), refresh_token: 'synthetic-refresh' });
}

test('anonymous registration churn never displaces an approved legacy client or blocks new registration', () => {
  const fixture = make();
  const body = grant(fixture);
  fixture.store.change(state => { delete state.clients[0].registeredAt; });
  for (let index = 0; index < 550; index++) fixture.oauth.register(registration);
  assert.equal(fixture.store.state.clients.length, 500);
  assert.ok(fixture.store.state.clients.some(client => client.client_id === body.get('client_id')));
  assert.equal(typeof fixture.oauth.token(body).access_token, 'string');
});

test('pending registration expires and is reclaimed after restarting the persistent store', t => {
  const directory = mkdtempSync(join(tmpdir(), 'mediary-oauth-'));
  try {
    const now = Date.now(); t.mock.method(Date, 'now', () => now);
    const first = make(new Store(directory)); const client = first.oauth.register(registration);
    t.mock.method(Date, 'now', () => now + 3600_001);
    const restarted = make(new Store(directory));
    const params = new URLSearchParams({ client_id: client.client_id, redirect_uri: client.redirect_uris[0], resource: restarted.oauth.resource(),
      response_type: 'code', state: 'test-state', code_challenge_method: 'S256', code_challenge: 'c'.repeat(43) });
    assert.throws(() => restarted.oauth.form(params), /重新连接/u);
    restarted.oauth.register(registration);
    assert.equal(restarted.store.state.clients.length, 1);
    assert.notEqual(restarted.store.state.clients[0].client_id, client.client_id);
  } finally { rmSync(directory, { recursive: true }); }
});

test('password approval protects a client before the code is exchanged', () => {
  const fixture = make(); const client = fixture.oauth.register(registration);
  const params = new URLSearchParams({ client_id: client.client_id, redirect_uri: client.redirect_uris[0], resource: fixture.oauth.resource(),
    response_type: 'code', state: 'test-state', code_challenge_method: 'S256', code_challenge: 'c'.repeat(43) });
  const form = fixture.oauth.form(params);
  const ticket = /name="ticket" value="([^"]+)"/u.exec(form.html)[1];
  const csrf = /name="csrf" value="([^"]+)"/u.exec(form.html)[1];
  fixture.oauth.authorize(new URLSearchParams({ ticket, csrf, password: config.token }), form.cookie);
  for (let index = 0; index < 510; index++) fixture.oauth.register(registration);
  assert.ok(fixture.store.state.clients.some(item => item.client_id === client.client_id && item.authorizedAt != null));
});

test('refresh narrowing removes write permission and offline renewal when not requested', () => {
  const fixture = make(); const body = grant(fixture); body.set('scope', 'drafts:read');
  const result = fixture.oauth.token(body);
  assert.equal(result.scope, 'drafts:read'); assert.equal(result.refresh_token, undefined);
  assert.equal(fixture.oauth.access(`Bearer ${result.access_token}`, 'drafts:read'), true);
  assert.equal(fixture.oauth.access(`Bearer ${result.access_token}`, 'drafts:write'), false);
  assert.throws(() => fixture.oauth.token(body), /invalid_grant/u);
});

test('a narrowed renewable grant cannot regain write permission on the next refresh', () => {
  const fixture = make(); const body = grant(fixture); body.set('scope', 'drafts:read offline_access');
  const result = fixture.oauth.token(body);
  assert.equal(result.scope, 'drafts:read offline_access'); assert.equal(typeof result.refresh_token, 'string');
  body.set('refresh_token', result.refresh_token); body.set('scope', 'drafts:write offline_access');
  assert.throws(() => fixture.oauth.token(body), /invalid_scope/u);
  body.delete('scope');
  assert.equal(fixture.oauth.token(body).scope, 'drafts:read offline_access');
});
