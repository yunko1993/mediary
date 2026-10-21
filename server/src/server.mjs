import { createServer } from 'node:http';
import { pathToFileURL } from 'node:url';
import { ApiError } from './extraction.mjs';
import { Store } from './store.mjs';
import { mcpRoutes } from './mcp.mjs';

export function configuration(env = process.env) {
  return {
    token: env.MEDIARY_ACCESS_TOKEN ?? '',
    publicBaseUrl: (env.PUBLIC_BASE_URL ?? '').replace(/\/$/u, ''),
    dataDir: env.DATA_DIR ?? '',
    mcpDiagnostics: env.MCP_DIAGNOSTICS === '1',
    host: env.HOST || '127.0.0.1', port: Number(env.PORT || 8787),
  };
}

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

/** 个人使用 MCP 服务：手机同步需要独立访问口令，不调用模型、不记录医嘱或凭据。 */
export function createApp(config = configuration(), options = {}) {
  let mcp;
  if (config.publicBaseUrl) {
    const base = new URL(config.publicBaseUrl);
    if (base.protocol !== 'https:' || base.username || base.password || base.search || base.hash || config.token.length < 24 || (!config.dataDir && !options.store)) throw new Error('MCP requires HTTPS PUBLIC_BASE_URL, DATA_DIR and a strong MEDIARY_ACCESS_TOKEN');
    const diagnosticSink = options.diagnosticSink ?? (config.mcpDiagnostics ? event => console.log(JSON.stringify(event)) : undefined);
    mcp = mcpRoutes(config, options.store ?? new Store(config.dataDir), readBody, diagnosticSink);
  }
  return createServer({ requestTimeout: 70_000, headersTimeout: 15_000 }, async (request, response) => {
    const send = (status, body) => {
      response.writeHead(status, { 'Content-Type': 'application/json; charset=utf-8', 'Cache-Control': 'no-store', 'X-Content-Type-Options': 'nosniff' });
      response.end(JSON.stringify(body));
    };
    try {
      if (request.method === 'GET' && request.url === '/health') {
        const ready = Boolean(mcp) && config.token.length >= 24;
        send(ready ? 200 : 503, { status: ready ? 'ok' : 'not_configured', mcpConfigured: Boolean(mcp), accessConfigured: config.token.length >= 24 }); return;
      }
      if (mcp && await mcp(request, response, new URL(request.url, 'http://localhost'), send)) return;
      throw new ApiError(404, '接口不存在');
    } catch (error) {
      send(error instanceof ApiError ? error.status : 500, { error: error instanceof ApiError ? error.message : '服务暂时不可用，请稍后重试' });
    }
  });
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  const config = configuration();
  const app = createApp(config);
  app.listen(config.port, config.host, () => {
    console.log(`Mediary service: http://${config.host}:${config.port}`);
    console.log(`MCP configured: ${Boolean(config.publicBaseUrl)}; access token configured: ${config.token.length >= 24}`);
  });
  // Docker 停止时先结束在途授权与同步；退出不生成或补发手机提醒。
  let stopping = false;
  for (const signal of ['SIGTERM', 'SIGINT']) process.on(signal, () => {
    if (stopping) return;
    stopping = true;
    app.close();
    setTimeout(() => app.closeAllConnections(), 65_000).unref();
  });
}
