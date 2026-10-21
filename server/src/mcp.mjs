import { ApiError, extractionSchema, instructions } from './extraction.mjs';
import { OAuth, secretEquals } from './oauth.mjs';
import { observeMcpRequest } from './diagnostics.mjs';

const objectSchema = properties => ({ type: 'object', additionalProperties: false, properties });
const pageSchema = objectSchema({ after: { type: 'integer', minimum: 0 }, limit: { type: 'integer', minimum: 1, maximum: 50 } });
const names = ['create_medication_draft', 'list_medication_drafts', 'get_medication_draft', 'get_sync_status'];
const tools = [
  { name: names[0], title: '保存待核对的用药草稿', description: '仅当用户要求把医嘱存入 Mediary 时调用。每次保存一种药物，所有非空字段必须逐字来自 sourceText 和 sourceQuote；未知字段留空。不能把每日三次换成每8小时，不提供补服或调药建议。requestId 为本次创建生成的唯一标识，网络重试必须复用。结果只是草稿，请让用户在安卓 Mediary 同步、核对并保存；不要声称提醒已启用。',
    inputSchema: { ...objectSchema({ requestId: { type: 'string', minLength: 8, maxLength: 100, pattern: '^[A-Za-z0-9_-]+$' }, sourceText: { type: 'string', minLength: 1, maxLength: 12000 }, medication: extractionSchema.properties.medications.items }), required: ['requestId', 'sourceText', 'medication'] } },
  { name: names[1], title: '查看用药草稿', description: '分页读取已保存的医嘱草稿及手机回执。不会读取手机服药历史。手机回执不能证明通知权限或实际提醒送达。', inputSchema: pageSchema },
  { name: names[2], title: '读取一条用药草稿', description: '根据草稿 ID 读取原文、待核对字段和手机回执，不能启用提醒。', inputSchema: { ...objectSchema({ id: { type: 'string', format: 'uuid' } }), required: ['id'] } },
  { name: names[3], title: '查看手机同步状态', description: '返回最后一次手机主动同步的服务器时间和待核对数量；离线手机上的状态未知。不把没有服药记录解释成未服用。', inputSchema: objectSchema({}) },
].map((tool, index) => {
  const securitySchemes = [{ type: 'oauth2', scopes: [index === 0 ? 'drafts:write' : 'drafts:read'] }];
  return { ...tool, annotations: { readOnlyHint: index !== 0, destructiveHint: false, idempotentHint: true, openWorldHint: false }, securitySchemes, _meta: { securitySchemes } };
});

/** MCP 与手机 REST 共用收件箱，OAuth 权限不能越界到手机确认接口。 */
export function mcpRoutes(config, store, readBody, diagnosticSink) {
  const oauth = new OAuth(config, store);
  let traffic = [];
  return async (request, response, url, send) => {
    const path = url.pathname;
    if (!['/mcp', '/oauth/authorize', '/oauth/token', '/oauth/register', '/oauth/revoke', '/.well-known/oauth-authorization-server', '/.well-known/oauth-protected-resource', '/v1/drafts', '/v1/drafts/receipts'].includes(path)) return false;
    const diagnostic = observeMcpRequest(request, response, path, diagnosticSink);
    const origin = request.headers.origin;
    if (origin && ![new URL(config.publicBaseUrl).origin, 'https://chatgpt.com'].includes(origin)) throw new ApiError(403, '请求来源不被允许');
    traffic = traffic.filter(time => Date.now() - time < 60_000);
    if (traffic.length >= 240) throw new ApiError(429, '请求较多，请稍后重试');
    traffic.push(Date.now());
    if (request.method === 'GET' && path === '/.well-known/oauth-protected-resource') { send(200, oauth.protectedMetadata()); return true; }
    if (request.method === 'GET' && path === '/.well-known/oauth-authorization-server') { send(200, oauth.metadata()); return true; }
    if (path === '/oauth/register' && request.method === 'POST') { send(201, oauth.register(await readBody(request))); return true; }
    if (path === '/oauth/authorize') {
      if (request.method === 'GET') {
        const form = oauth.form(url.searchParams);
        // no-referrer 会让浏览器表单发送 Origin:null；same-origin 保留本站来源，跨站仍不发送 Referer。
        // Chromium 还会用 form-action 检查提交后的 303；仅放行本次已验证的 ChatGPT 回调。
        response.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8', 'Set-Cookie': form.cookie, 'Cache-Control': 'no-store', 'Referrer-Policy': 'same-origin',
          'Content-Security-Policy': `default-src 'none'; form-action 'self' ${form.redirectUri}; frame-ancestors 'none'; base-uri 'none'`, 'X-Content-Type-Options': 'nosniff' });
        response.end(form.html); return true;
      }
      if (request.method === 'POST') {
        const location = oauth.authorize(await readBody(request, true), request.headers.cookie);
        response.writeHead(303, { Location: location, 'Cache-Control': 'no-store', 'Referrer-Policy': 'no-referrer' }); response.end(); return true;
      }
    }
    if (path === '/oauth/token' && request.method === 'POST') {
      try { send(200, oauth.token(await readBody(request, true))); }
      catch (error) { send(error instanceof ApiError ? error.status : 500, { error: ['invalid_grant', 'invalid_scope', 'unsupported_grant_type'].includes(error.message) ? error.message : 'invalid_request' }); }
      return true;
    }
    if (path === '/oauth/revoke' && request.method === 'POST') { oauth.revoke(await readBody(request, true)); send(200, {}); return true; }
    if (path.startsWith('/v1/drafts')) {
      if (!secretEquals(request.headers.authorization, `Bearer ${config.token}`)) throw new ApiError(401, '请在手机配置正确的服务访问口令');
      if (path === '/v1/drafts' && request.method === 'GET') { send(200, store.list(Number(url.searchParams.get('after') ?? 0), Number(url.searchParams.get('limit') ?? 20))); return true; }
      if (path === '/v1/drafts/receipts' && request.method === 'POST') {
        const body = await readBody(request);
        if (!body || Object.keys(body).some(key => key !== 'receipts')) throw new ApiError(400, '手机回执格式无效');
        send(200, store.receive(body.receipts)); return true;
      }
    }
    if (path === '/mcp') {
      const challenge = `Bearer resource_metadata="${config.publicBaseUrl}/.well-known/oauth-protected-resource", scope="drafts:read drafts:write"`;
      const version = request.headers['mcp-protocol-version'];
      if (request.method !== 'POST') {
        if (!oauth.access(request.headers.authorization, 'drafts:read') && !oauth.access(request.headers.authorization, 'drafts:write')) {
          response.setHeader('WWW-Authenticate', challenge); throw new ApiError(401, '请先通过 OAuth 授权 Mediary');
        }
        response.setHeader('Allow', 'POST'); throw new ApiError(405, '本服务使用无会话的 Streamable HTTP JSON 响应');
      }
      const accept = request.headers.accept ?? '';
      if (!accept.includes('application/json') || !accept.includes('text/event-stream')) throw new ApiError(406, 'Accept 需要包含 application/json 和 text/event-stream');
      const body = await readBody(request);
      const validId = body?.id === undefined || typeof body.id === 'string' || Number.isSafeInteger(body.id);
      if (!body || Array.isArray(body) || body.jsonrpc !== '2.0' || typeof body.method !== 'string' || !validId) {
        send(400, { jsonrpc: '2.0', id: null, error: { code: -32600, message: '无效 JSON-RPC 请求；不支持批处理' } }); return true;
      }
      diagnostic.rpc(body.method, body.params?.name);
      if (body.method !== 'initialize' && version && !['2025-03-26', '2025-06-18'].includes(version)) throw new ApiError(400, '不支持此 MCP 协议版本，请重新初始化');
      const fail = (code, message) => { diagnostic.rpcError(); send(200, { jsonrpc: '2.0', id: body.id, error: { code, message } }); };
      const result = value => { diagnostic.result(value); send(200, { jsonrpc: '2.0', id: body.id, result: value }); };
      if (body.id === undefined) {
        if (!body.method.startsWith('notifications/')) { send(400, { error: '通知方法无效' }); return true; }
        response.writeHead(202); response.end(); return true;
      }
      // 初始化与工具目录只包含公开协议资料；客户端先发现 OAuth 策略，才能在调用时引导登录。
      if (body.method === 'initialize') {
        result({ protocolVersion: ['2025-03-26', '2025-06-18'].includes(body.params?.protocolVersion) ? body.params.protocolVersion : '2025-06-18', capabilities: { tools: { listChanged: false } },
          serverInfo: { name: 'mediary', version: '0.2.0' }, instructions: `${instructions}\n使用本服务仅保存待核对草稿，不能启用提醒、查询或修改手机服药历史。` }); return true;
      }
      if (body.method === 'ping') { result({}); return true; }
      if (body.method === 'tools/list') { result({ tools }); return true; }
      if (body.method !== 'tools/call') { fail(-32601, '方法不存在'); return true; }
      const name = body.params?.name;
      if (!names.includes(name)) { fail(-32602, '工具不存在'); return true; }
      const needed = name === names[0] ? 'drafts:write' : 'drafts:read';
      if (!oauth.access(request.headers.authorization, needed)) {
        const hasGrant = oauth.access(request.headers.authorization, 'drafts:read') || oauth.access(request.headers.authorization, 'drafts:write');
        const authChallenge = `Bearer resource_metadata="${config.publicBaseUrl}/.well-known/oauth-protected-resource", scope="${needed}", error="${hasGrant ? 'insufficient_scope' : 'invalid_token'}", error_description="Connect Mediary to authorize this tool"`;
        // ChatGPT 需要工具结果中的标准挑战才能弹出连接入口；拒绝时不读取状态，也不写入草稿。
        response.setHeader('WWW-Authenticate', authChallenge);
        result({ isError: true, content: [{ type: 'text', text: '请先连接并授权 Mediary；此次没有读取或保存用药数据。' }],
          _meta: { 'mcp/www_authenticate': [authChallenge] } });
        return true;
      }
      try {
        const args = body.params.arguments ?? {};
        if (!args || typeof args !== 'object' || Array.isArray(args)) throw new ApiError(400, '工具参数格式无效');
        if (name === names[1] && Object.keys(args).some(key => !['after', 'limit'].includes(key)) || name === names[2] && (Object.keys(args).some(key => key !== 'id') || typeof args.id !== 'string') || name === names[3] && Object.keys(args).length) throw new ApiError(400, '工具参数格式无效');
        const value = name === names[0] ? store.createDraft(args) : name === names[1] ? store.list(args.after, args.limit) : name === names[2] ? store.get(args.id) : store.status();
        result({ content: [{ type: 'text', text: JSON.stringify(value) }], structuredContent: value });
      } catch (error) {
        result({ isError: true, content: [{ type: 'text', text: error instanceof ApiError ? error.message : '操作失败，请稍后重试；不要声称草稿已保存' }] });
      }
      return true;
    }
    throw new ApiError(405, '不支持此请求方法');
  };
}
