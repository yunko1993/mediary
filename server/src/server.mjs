import { createServer } from 'node:http';
import { createHash, timingSafeEqual } from 'node:crypto';
import { pathToFileURL } from 'node:url';
import { ApiError, extract, providerRequest, ZHIPU_BASE_URL, ZHIPU_MODEL } from './extraction.mjs';
import { Store } from './store.mjs';
import { mcpRoutes } from './mcp.mjs';

export function configuration(env = process.env) {
  // 显式配置优先；未指定提供方的旧 OpenAI 环境继续使用原接口，避免升级后误切换密钥。
  const provider = env.AI_PROVIDER || (env.OPENAI_API_KEY || env.OPENAI_BASE_URL || env.OPENAI_MODEL ? 'openai' : 'zhipu');
  if (!['zhipu', 'openai'].includes(provider)) throw new ApiError(503, 'AI_PROVIDER 仅支持 zhipu 或 openai');
  return {
    provider,
    apiKey: (provider === 'zhipu' ? env.ZHIPU_API_KEY : env.OPENAI_API_KEY) ?? '',
    model: provider === 'zhipu' ? ZHIPU_MODEL : env.OPENAI_MODEL || 'gpt-4.1-mini',
    baseUrl: provider === 'zhipu' ? ZHIPU_BASE_URL : env.OPENAI_BASE_URL || 'https://api.openai.com/v1',
    token: env.MEDIARY_ACCESS_TOKEN ?? '',
    publicBaseUrl: (env.PUBLIC_BASE_URL ?? '').replace(/\/$/u, ''),
    dataDir: env.DATA_DIR ?? '',
    mcpDiagnostics: env.MCP_DIAGNOSTICS === '1',
    host: env.HOST || '127.0.0.1', port: Number(env.PORT || 8787),
  };
}

const hash = value => createHash('sha256').update(value).digest();
const authorized = (header, token) => token.length >= 24 && typeof header === 'string' && timingSafeEqual(hash(header), hash(`Bearer ${token}`));

async function readBody(request, form = false) {
  if (!(request.headers['content-type'] ?? '').toLowerCase().startsWith(form ? 'application/x-www-form-urlencoded' : 'application/json')) throw new ApiError(415, '请求内容类型不匹配');
  let size = 0;
  const chunks = [];
  for await (const chunk of request) {
    size += chunk.length;
    if (size > 65_536) throw new ApiError(413, '医嘱文本过长，请分段导入');
    chunks.push(chunk);
  }
  const text = Buffer.concat(chunks).toString('utf8');
  if (form) return new URLSearchParams(text);
  try { return JSON.parse(text); }
  catch { throw new ApiError(400, '请求格式应为 JSON'); }
}

/** 个人使用服务：默认只监听本机，解析需要独立访问口令，不记录医嘱和密钥。 */
export function createApp(config = configuration(), options = {}) {
  let mcp;
  if (config.publicBaseUrl) {
    const base = new URL(config.publicBaseUrl);
    if (base.protocol !== 'https:' || base.username || base.password || base.search || base.hash || config.token.length < 24 || (!config.dataDir && !options.store)) throw new Error('MCP requires HTTPS PUBLIC_BASE_URL, DATA_DIR and a strong MEDIARY_ACCESS_TOKEN');
    const diagnosticSink = options.diagnosticSink ?? (config.mcpDiagnostics ? event => console.log(JSON.stringify(event)) : undefined);
    mcp = mcpRoutes(config, options.store ?? new Store(config.dataDir), readBody, diagnosticSink);
  }
  let requests = [];
  let active = 0;
  return createServer({ requestTimeout: 70_000, headersTimeout: 15_000 }, async (request, response) => {
    const send = (status, body) => {
      response.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' });
      response.end(JSON.stringify(body));
    };
    let acquired = false;
    try {
      if (request.method === 'GET' && request.url === '/health') {
        let ready = config.token.length >= 24;
        if (!mcp) { try { providerRequest('', config); } catch { ready = false; } }
        send(ready ? 200 : 503, { status: ready ? 'ok' : 'not_configured', mcpConfigured: Boolean(mcp), gptConfigured: Boolean(config.apiKey?.trim()), accessConfigured: config.token.length >= 24 }); return;
      }
      if (mcp && await mcp(request, response, new URL(request.url, 'http://localhost'), send)) return;
      if (request.method !== 'POST' || request.url !== '/v1/instructions/parse') throw new ApiError(404, '接口不存在');
      if (!authorized(request.headers.authorization, config.token)) throw new ApiError(401, '服务访问口令无效或尚未配置');
      if (!(request.headers['content-type'] ?? '').toLowerCase().startsWith('application/json')) throw new ApiError(415, '请使用 application/json');
      const body = await readBody(request);
      if (!body || Object.keys(body).some(key => key !== 'text') || typeof body.text !== 'string' || !body.text.trim() || body.text.length > 12_000) {
        throw new ApiError(400, '请求仅接受 1–12000 字的 text 医嘱文本，模型与密钥由服务端配置');
      }
      requests = requests.filter(timestamp => Date.now() - timestamp < 60_000);
      if (requests.length >= 10 || active >= 2) throw new ApiError(429, '请求较多，请稍后再试');
      requests.push(Date.now()); active++; acquired = true;
      send(200, await extract(body.text.trim(), config, options.fetchImpl));
    } catch (error) {
      send(error instanceof ApiError ? error.status : 500, { error: error instanceof ApiError ? error.message : '服务暂时不可用，请稍后重试' });
    } finally { if (acquired) active--; }
  });
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const config = configuration();
  const app = createApp(config);
  app.listen(config.port, config.host, () => {
    console.log(`Mediary service: http://${config.host}:${config.port}`);
    console.log(`AI provider: ${config.provider}; key configured: ${Boolean(config.apiKey)}; access token configured: ${config.token.length >= 24}`);
  });
  // Docker 停止时先结束在途整理请求；不记录请求正文，退出也不生成或补发手机提醒。
  let stopping = false;
  for (const signal of ['SIGTERM', 'SIGINT']) process.on(signal, () => {
    if (stopping) return;
    stopping = true;
    app.close();
    setTimeout(() => app.closeAllConnections(), 65_000).unref();
  });
}
