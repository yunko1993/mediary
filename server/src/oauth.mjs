import { randomBytes, timingSafeEqual } from 'node:crypto';
import { ApiError } from './extraction.mjs';
import { digest } from './store.mjs';

const random = () => randomBytes(32).toString('base64url');
const scopes = ['drafts:read', 'drafts:write', 'offline_access'];
const escape = text => String(text).replace(/[&<>"']/gu, value => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' })[value]);
export const secretEquals = (actual, expected) => typeof actual === 'string' && typeof expected === 'string' && expected.length >= 24 && timingSafeEqual(Buffer.from(digest(actual)), Buffer.from(digest(expected)));
const allowedRedirect = value => typeof value === 'string' && value.length <= 2048 && /^https:\/\/chatgpt\.com\/(?:connector_platform_oauth_redirect|connector\/oauth\/[A-Za-z0-9_-]+)$/u.test(value);

/** 单用户授权服务器：ChatGPT 使用 PKCE 和一次性授权码，手机访问口令只在本站授权表单输入。 */
export class OAuth {
  constructor(config, store) { this.config = config; this.store = store; this.forms = new Map(); this.codes = new Map(); this.failures = []; }
  metadata() {
    const base = this.config.publicBaseUrl;
    return { issuer: base, authorization_endpoint: `${base}/oauth/authorize`, token_endpoint: `${base}/oauth/token`,
      registration_endpoint: `${base}/oauth/register`, revocation_endpoint: `${base}/oauth/revoke`,
      authorization_response_iss_parameter_supported: true, response_types_supported: ['code'], grant_types_supported: ['authorization_code', 'refresh_token'],
      token_endpoint_auth_methods_supported: ['none'], code_challenge_methods_supported: ['S256'], scopes_supported: scopes };
  }
  resource() { return `${this.config.publicBaseUrl}/mcp`; }
  protectedMetadata() { return { resource: this.resource(), authorization_servers: [this.config.publicBaseUrl], scopes_supported: scopes.slice(0, 2), resource_name: 'Mediary 草稿收件箱' }; }
  register(body) {
    if (!body || !Array.isArray(body.redirect_uris) || !body.redirect_uris.length || body.redirect_uris.length > 5 || !body.redirect_uris.every(allowedRedirect) ||
        (body.token_endpoint_auth_method && body.token_endpoint_auth_method !== 'none') ||
        (body.grant_types && (!Array.isArray(body.grant_types) || body.grant_types.some(grant => !['authorization_code', 'refresh_token'].includes(grant)))) ||
        (body.response_types && (!Array.isArray(body.response_types) || body.response_types.some(value => value !== 'code')))) throw new ApiError(400, 'OAuth 客户端参数无效；仅允许 ChatGPT HTTPS 回调');
    return this.store.change(state => {
      if (state.clients.length >= 500) throw new ApiError(429, '授权连接数量已达上限');
      const client = { client_id: random(), redirect_uris: body.redirect_uris, client_name: typeof body.client_name === 'string' ? body.client_name.slice(0, 100) : 'ChatGPT', token_endpoint_auth_method: 'none',
        grant_types: ['authorization_code', 'refresh_token'], response_types: ['code'] };
      state.clients.push(client); return client;
    });
  }
  validateAuthorization(params) {
    const client = this.store.state.clients.find(item => item.client_id === params.get('client_id'));
    if (!client || !client.redirect_uris.includes(params.get('redirect_uri'))) throw new ApiError(400, 'OAuth 客户端或回调地址不匹配');
    const requested = (params.get('scope') || 'drafts:read drafts:write offline_access').split(' ').filter(Boolean);
    if (params.get('response_type') !== 'code' || params.get('code_challenge_method') !== 'S256' || !/^[A-Za-z0-9_-]{43}$/u.test(params.get('code_challenge') ?? '') ||
        params.get('resource') !== this.resource() || requested.some(scope => !scopes.includes(scope)) || !requested.some(scope => scope.startsWith('drafts:')) ||
        !params.get('state') || params.get('state').length > 2048) throw new ApiError(400, 'OAuth 授权参数无效，需要 S256 PKCE、resource 和 state');
    return { clientId: client.client_id, clientName: client.client_name, redirectUri: params.get('redirect_uri'), challenge: params.get('code_challenge'), state: params.get('state'), scope: [...new Set(requested)].join(' ') };
  }
  form(params) {
    this.prune();
    if (this.forms.size >= 200) throw new ApiError(429, '授权请求较多，请稍后重试');
    const details = this.validateAuthorization(params);
    const ticket = random(); const csrf = random();
    this.forms.set(ticket, { ...details, csrf, expires: Date.now() + 600_000 });
    return { cookie: `mediary_oauth=${csrf}; HttpOnly; Secure; SameSite=Lax; Path=${new URL(this.config.publicBaseUrl).pathname.replace(/\/$/u, '')}/oauth; Max-Age=600`,
      html: `<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>连接 Mediary</title><body><main><h1>连接 Mediary</h1><p>连接名称：${escape(details.clientName)}</p><p>允许此连接${details.scope.includes('drafts:read') ? '读取草稿' : ''}${details.scope.includes('drafts:write') ? '、创建待核对草稿' : ''}。它不能启用提醒，也不能读取手机服药历史。</p><p>只有你自己的连接才应获准。请在此输入 Mediary 服务访问口令，不要把口令发到聊天中。</p><form method="post" action="${escape(this.config.publicBaseUrl)}/oauth/authorize"><input type="hidden" name="ticket" value="${ticket}"><input type="hidden" name="csrf" value="${csrf}"><label>服务访问口令 <input name="password" type="password" autocomplete="current-password" required maxlength="4096"></label><p><button type="submit">授权连接</button></p></form><p>完成后仍需在手机逐项核对草稿并保存，才会安排提醒。</p></main></body></html>` };
  }
  authorize(body, cookie = '') {
    this.prune();
    const pending = this.forms.get(body.get('ticket'));
    const cookieToken = /(?:^|;\s*)mediary_oauth=([^;]+)/u.exec(cookie)?.[1];
    if (!pending || !secretEquals(body.get('csrf'), pending.csrf) || !secretEquals(cookieToken, pending.csrf)) throw new ApiError(400, '授权页面已失效，请从 ChatGPT 重新连接');
    this.failures = this.failures.filter(time => Date.now() - time < 900_000);
    if (this.failures.length >= 20) throw new ApiError(429, '口令尝试过多，请 15 分钟后重试');
    if (!secretEquals(body.get('password'), this.config.token)) { this.failures.push(Date.now()); throw new ApiError(401, '服务访问口令无效'); }
    this.forms.delete(body.get('ticket'));
    const code = random();
    this.codes.set(digest(code), { ...pending, expires: Date.now() + 300_000 });
    const redirect = new URL(pending.redirectUri);
    redirect.searchParams.set('code', code); redirect.searchParams.set('state', pending.state); redirect.searchParams.set('iss', this.config.publicBaseUrl);
    return redirect.href;
  }
  token(body) {
    this.prune();
    const clientId = body.get('client_id');
    if (!this.store.state.clients.some(client => client.client_id === clientId) || body.get('resource') !== this.resource()) throw new ApiError(400, 'invalid_grant');
    let grant; let previousRefresh;
    if (body.get('grant_type') === 'authorization_code') {
      const key = digest(body.get('code') ?? '');
      grant = this.codes.get(key);
      const verifier = body.get('code_verifier') ?? '';
      if (!grant || grant.clientId !== clientId || grant.redirectUri !== body.get('redirect_uri') || !/^[A-Za-z0-9._~-]{43,128}$/u.test(verifier) ||
          Buffer.from(digest(verifier), 'hex').toString('base64url') !== grant.challenge) throw new ApiError(400, 'invalid_grant');
      this.codes.delete(key);
    } else if (body.get('grant_type') === 'refresh_token') {
      previousRefresh = digest(body.get('refresh_token') ?? '');
      grant = this.store.state.grants.find(item => item.refreshHash === previousRefresh && item.clientId === clientId && item.refreshExpires > Date.now() && item.ownerHash === digest(this.config.token));
      if (!grant) throw new ApiError(400, 'invalid_grant');
      const requested = body.get('scope');
      if (requested && requested.split(' ').some(scope => !grant.scope.split(' ').includes(scope))) throw new ApiError(400, 'invalid_scope');
    } else throw new ApiError(400, 'unsupported_grant_type');
    const access = random(); const refresh = grant.scope.split(' ').includes('offline_access') ? random() : null;
    const now = Date.now();
    this.store.change(state => {
      state.grants = state.grants.filter(item => item.refreshHash !== previousRefresh && (item.refreshExpires > now || item.accessExpires > now));
      if (state.grants.length >= 500) throw new ApiError(429, '授权数量已达上限');
      state.grants.push({ clientId, scope: grant.scope, ownerHash: digest(this.config.token), resource: this.resource(), accessHash: digest(access),
        refreshHash: refresh ? digest(refresh) : null, accessExpires: now + 3600_000, refreshExpires: refresh ? now + 30 * 86400_000 : 0 });
    });
    return { access_token: access, token_type: 'Bearer', expires_in: 3600, scope: grant.scope, ...(refresh ? { refresh_token: refresh } : {}) };
  }
  access(header, scope) {
    if (typeof header !== 'string' || !header.startsWith('Bearer ')) return false;
    return this.store.state.grants.some(item => item.accessHash === digest(header.slice(7)) && item.accessExpires > Date.now() &&
      item.resource === this.resource() && item.ownerHash === digest(this.config.token) && item.scope.split(' ').includes(scope));
  }
  revoke(body) {
    const hash = digest(body.get('token') ?? '');
    this.store.change(state => { state.grants = state.grants.filter(item => !(item.clientId === body.get('client_id') && (item.accessHash === hash || item.refreshHash === hash))); });
  }
  prune() { for (const map of [this.forms, this.codes]) for (const [key, value] of map) if (value.expires <= Date.now()) map.delete(key); }
}
