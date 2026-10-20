// 仅探测本容器配置与 HTTP 可用性，不调用上游模型，也不消耗 API 额度。
try {
  const response = await fetch(`http://127.0.0.1:${process.env.PORT || 8787}/health`, { signal: AbortSignal.timeout(4000) });
  process.exitCode = response.ok ? 0 : 1;
} catch { process.exitCode = 1; }
