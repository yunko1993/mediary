import { mkdirSync, readFileSync, openSync, writeFileSync, fsyncSync, closeSync, renameSync, statSync } from 'node:fs';
import { join } from 'node:path';
import { randomUUID, createHash } from 'node:crypto';
import { ApiError, validateExtraction } from './extraction.mjs';

export const digest = text => createHash('sha256').update(text).digest('hex');

/** 单进程个人收件箱；落盘成功后才发布内存状态，重启不会丢失已返回成功的草稿。 */
export class Store {
  constructor(directory) {
    this.file = directory ? join(directory, 'mediary.json') : null;
    this.state = { version: 1, drafts: [], clients: [], grants: [], lastDeviceSyncAt: null };
    if (directory) {
      mkdirSync(directory, { recursive: true, mode: 0o700 });
      try {
        if (statSync(this.file).size > 32 * 1024 * 1024) throw new Error('Store too large');
        this.state = JSON.parse(readFileSync(this.file, 'utf8'));
        if (this.state.version !== 1 || !['drafts', 'clients', 'grants'].every(key => Array.isArray(this.state[key]))) throw new Error('Invalid store');
      } catch (error) { if (error.code !== 'ENOENT') throw new Error('Mediary data cannot be loaded; preserve the data volume and restore a valid backup'); }
    }
  }

  change(action) {
    const next = structuredClone(this.state);
    const result = action(next);
    if (this.file) {
      const text = JSON.stringify(next);
      if (Buffer.byteLength(text) > 32 * 1024 * 1024) throw new ApiError(507, '收件箱已满，请备份整理后重试');
      const temporary = `${this.file}.tmp`;
      const descriptor = openSync(temporary, 'w', 0o600);
      try { writeFileSync(descriptor, text); fsyncSync(descriptor); } finally { closeSync(descriptor); }
      renameSync(temporary, this.file);
      // Linux 上同步目录项，避免突然断电后已确认的原子替换丢失。
      if (process.platform !== 'win32') {
        const parent = openSync(join(this.file, '..'), 'r');
        try { fsyncSync(parent); } finally { closeSync(parent); }
      }
    }
    this.state = next;
    return structuredClone(result ?? null);
  }

  createDraft(args) {
    if (!args || Object.keys(args).some(key => !['requestId', 'sourceText', 'medication'].includes(key)) ||
        typeof args.requestId !== 'string' || !/^[A-Za-z0-9_-]{8,100}$/u.test(args.requestId) ||
        typeof args.sourceText !== 'string' || !args.sourceText.trim() || args.sourceText.length > 12_000) throw new ApiError(400, '请提供原文、单条药物草稿和 8–100 位 requestId');
    let medication;
    try { medication = validateExtraction({ medications: [args.medication] }, args.sourceText).medications[0]; }
    catch { throw new ApiError(400, '草稿未通过原文证据校验：请逐字提取，未知字段留空，不能推算剂量或时间'); }
    const fingerprint = digest(JSON.stringify([args.sourceText, medication]));
    const previous = this.state.drafts.find(item => item.requestId === args.requestId);
    if (previous) {
      if (previous.fingerprint !== fingerprint) throw new ApiError(409, 'requestId 已用于不同内容，请核对之前的草稿');
      return this.publicDraft(previous);
    }
    return this.change(state => {
      if (state.drafts.length >= 1000) throw new ApiError(507, '收件箱已满，请备份整理后重试');
      const item = { id: randomUUID(), sequence: (state.drafts.at(-1)?.sequence ?? 0) + 1, requestId: args.requestId, fingerprint,
        sourceText: args.sourceText, medication, createdAt: new Date().toISOString(), receivedAt: null, reviewedAt: null, planId: null };
      state.drafts.push(item);
      return this.publicDraft(item);
    });
  }

  publicDraft(item) {
    const { requestId, fingerprint, ...draft } = item;
    return { ...draft, status: item.reviewedAt ? 'reviewed_on_device' : item.receivedAt ? 'received_on_device' : 'pending',
      requiresPhoneReview: !item.reviewedAt, reminderStatus: 'unknown_to_server' };
  }

  list(after = 0, limit = 20) {
    if (!Number.isSafeInteger(after) || after < 0 || !Number.isSafeInteger(limit) || limit < 1 || limit > 50) throw new ApiError(400, '分页参数无效');
    const items = this.state.drafts.filter(item => item.sequence > after).slice(0, limit);
    const last = items.at(-1)?.sequence ?? after;
    return { drafts: items.map(item => this.publicDraft(item)), nextCursor: this.state.drafts.some(item => item.sequence > last) ? last : null };
  }

  get(id) {
    const item = this.state.drafts.find(draft => draft.id === id);
    if (!item) throw new ApiError(404, '草稿不存在');
    return this.publicDraft(item);
  }

  /** 只接受手机凭据回执；MCP OAuth 令牌没有此权限，也不代表通知一定送达。 */
  receive(receipts) {
    if (!Array.isArray(receipts) || receipts.length > 50 || receipts.some(item => !item ||
        Object.keys(item).some(key => !['id', 'planId'].includes(key)) || typeof item.id !== 'string' ||
        (item.planId !== undefined && (typeof item.planId !== 'string' || !/^[A-Za-z0-9_-]{1,100}$/u.test(item.planId))))) throw new ApiError(400, '手机回执格式无效');
    return this.change(state => {
      const now = new Date().toISOString();
      for (const receipt of receipts) {
        const item = state.drafts.find(draft => draft.id === receipt.id);
        if (!item) throw new ApiError(404, '草稿不存在');
        if (receipt.planId && item.planId && receipt.planId !== item.planId) throw new ApiError(409, '草稿已关联另一条手机计划，请核对');
        item.receivedAt ??= now;
        if (receipt.planId) { item.reviewedAt ??= now; item.planId = receipt.planId; }
      }
      state.lastDeviceSyncAt = now;
      return { received: receipts.length, lastDeviceSyncAt: now };
    });
  }

  status() {
    return { lastDeviceSyncAt: this.state.lastDeviceSyncAt, pending: this.state.drafts.filter(item => !item.reviewedAt).length,
      reviewed: this.state.drafts.filter(item => item.reviewedAt).length,
      notice: '草稿必须在手机核对保存；回执只说明手机已保存计划，不保证权限就绪或通知送达。本接口不能启用提醒，也不读取服药记录。' };
  }
}
