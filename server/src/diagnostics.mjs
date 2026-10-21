const routes = new Map([
  ['/mcp', 'mcp'], ['/oauth/authorize', 'authorize'], ['/oauth/token', 'token'],
  ['/oauth/register', 'register'], ['/oauth/revoke', 'revoke'],
  ['/.well-known/oauth-authorization-server', 'authorization_metadata'],
  ['/.well-known/oauth-protected-resource', 'resource_metadata'],
]);
const methods = new Set(['GET', 'POST', 'HEAD', 'OPTIONS']);
const rpcMethods = new Map([['initialize', 'initialize'], ['ping', 'ping'], ['tools/list', 'tools_list']]);
const toolNames = new Set(['create_medication_draft', 'list_medication_drafts', 'get_medication_draft', 'get_sync_status']);
const disabled = { rpc() {}, result() {}, rpcError() {} };

/** 只输出固定枚举和状态码；不能把请求对象、URL、错误正文或 OAuth 凭据交给日志输出端。 */
export function observeMcpRequest(request, response, path, sink) {
  const route = routes.get(path);
  if (!sink || !route) return disabled;
  const method = methods.has(request.method) ? request.method : 'other';
  let operation = 'http';
  let outcome = 'http';
  response.once('finish', () => {
    try {
      sink({ event: 'mediary_mcp_diagnostic', at: new Date().toISOString(), route, method, operation, outcome, status: response.statusCode });
    } catch { /* 诊断输出故障不能中断授权或草稿处理。 */ }
  });
  return {
    rpc(name, tool) {
      if (rpcMethods.has(name)) operation = rpcMethods.get(name);
      else if (name === 'tools/call') operation = toolNames.has(tool) ? `tool_${tool}` : 'tool_other';
      else operation = name.startsWith('notifications/') ? 'notification' : 'rpc_other';
    },
    result(value) {
      outcome = value.isError ? value._meta?.['mcp/www_authenticate'] ? 'auth_required' : 'tool_error' : 'success';
    },
    rpcError() { outcome = 'rpc_error'; },
  };
}
