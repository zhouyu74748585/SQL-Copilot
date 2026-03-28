import {nextTick, type ComputedRef, type Ref} from 'vue';
import type {ConnectionVO} from '@sqlcopilot/shared-contracts';
import {message, Modal} from 'ant-design-vue';
import {getApi, postApi} from '../../../../api/client';
import type {
  KvObjectDetailVO,
  KvRedisBrowserNodeVO,
  KvRedisBrowserPageVO,
  KvRedisKeyDeleteReq,
  KvRedisKeyDeleteVO,
  KvRedisKeyEntryVO,
  KvRedisKeySaveReq,
  KvRedisKeySaveVO,
} from '../../../../types';
import type {ObjectRow} from './types';
import {buildRedisBrowserCacheKey} from './cache';

export {buildRedisBrowserCacheKey};

export function flattenRedisBrowserRows(rows: ObjectRow[]) {
  const result: ObjectRow[] = [];
  rows.forEach((item) => {
    result.push(item);
    if (item.children?.length) {
      result.push(...flattenRedisBrowserRows(item.children));
    }
  });
  return result;
}

export function buildRedisBrowserRow(item: KvRedisBrowserNodeVO): ObjectRow {
  return {
    nodeKey: item.nodeKey,
    nodeName: item.nodeName,
    fullPath: item.fullPath,
    redisNodeType: item.nodeType,
    hasChildren: item.hasChildren,
    ttlSeconds: item.ttlSeconds,
    objectName: item.objectName || item.fullPath,
    objectType: 'tables',
    rowEstimate: 0,
    tableSize: item.valueType || '-',
    description: item.description || '',
    vectorizeStatus: 'NOT_VECTORIZED',
    vectorizeMessage: 'KV 类型不进行向量化',
    vectorizeUpdatedAt: undefined,
    children: item.nodeType === 'PATH' && item.hasChildren ? [] : undefined,
  };
}

export function buildRedisBrowserLoadMoreRow(parentPath: string, nextCursor: string): ObjectRow {
  return {
    nodeKey: `load-more:${parentPath || '__root__'}:${nextCursor}`,
    nodeName: '加载更多',
    fullPath: parentPath,
    redisNodeType: 'LOAD_MORE',
    hasChildren: false,
    nextCursor,
    objectName: `__redis_load_more__:${parentPath}:${nextCursor}`,
    objectType: 'tables',
    rowEstimate: 0,
    tableSize: '-',
    description: '继续加载当前层更多节点',
    vectorizeStatus: 'NOT_VECTORIZED',
    vectorizeMessage: 'KV 类型不进行向量化',
    vectorizeUpdatedAt: undefined,
  };
}

export function normalizeRedisBrowserRows(items: KvRedisBrowserNodeVO[], parentPath: string, nextCursor?: string, finished?: boolean) {
  const rows = items.map(buildRedisBrowserRow);
  if (!finished && nextCursor && nextCursor !== '0') {
    rows.push(buildRedisBrowserLoadMoreRow(parentPath, nextCursor));
  }
  return rows;
}

export function redisParentPathOfRow(record: {redisNodeType?: string; fullPath?: string; objectName?: string}) {
  const fullPath = (record.redisNodeType === 'KEY' ? record.objectName : record.fullPath) || '';
  const segments = fullPath.split(':').filter((item) => !!item);
  segments.pop();
  return segments.join(':');
}

export function sortRedisTreeRows(rows: ObjectRow[]): ObjectRow[] {
  return [...rows]
    .sort((left, right) => {
      const leftTypeWeight = left.redisNodeType === 'PATH' ? 0 : left.redisNodeType === 'KEY' ? 1 : 2;
      const rightTypeWeight = right.redisNodeType === 'PATH' ? 0 : right.redisNodeType === 'KEY' ? 1 : 2;
      if (leftTypeWeight !== rightTypeWeight) {
        return leftTypeWeight - rightTypeWeight;
      }
      return String(left.nodeName || left.objectName).localeCompare(String(right.nodeName || right.objectName), 'zh-CN');
    })
    .map((item) => ({
      ...item,
      children: item.children?.length ? sortRedisTreeRows(item.children) : item.children,
    }));
}

export function buildRedisSearchTreeRows(items: KvRedisBrowserNodeVO[], nextCursor?: string, finished?: boolean) {
  const rowMap = new Map<string, ObjectRow>();
  items.forEach((item) => {
    rowMap.set(item.nodeKey, {
      ...buildRedisBrowserRow(item),
      children: [],
    });
  });
  const roots: ObjectRow[] = [];
  rowMap.forEach((row) => {
    const parentPath = redisParentPathOfRow(row);
    const parentKey = parentPath ? `path:${parentPath}` : '';
    const parent = parentKey ? rowMap.get(parentKey) : null;
    if (parent) {
      parent.children = parent.children ?? [];
      parent.children.push(row);
      return;
    }
    roots.push(row);
  });
  const sortedRoots = sortRedisTreeRows(roots);
  if (!finished && nextCursor && nextCursor !== '0') {
    sortedRoots.push(buildRedisBrowserLoadMoreRow('', nextCursor));
  }
  return sortedRoots;
}

export function mergeRedisSearchTreeRows(currentRows: ObjectRow[], nextRows: ObjectRow[]) {
  const mergedMap = new Map<string, ObjectRow>();
  const appendRow = (row: ObjectRow) => {
    const existing = row.nodeKey ? mergedMap.get(row.nodeKey) : null;
    if (!existing || !row.nodeKey) {
      mergedMap.set(row.nodeKey || `${row.objectName}-${mergedMap.size}`, {
        ...row,
        children: row.children ? mergeRedisSearchTreeRows([], row.children) : row.children,
      });
      return;
    }
    existing.children = mergeRedisSearchTreeRows(existing.children ?? [], row.children ?? []);
    existing.tableSize = row.tableSize || existing.tableSize;
    existing.description = row.description || existing.description;
    existing.ttlSeconds = row.ttlSeconds ?? existing.ttlSeconds;
  };
  [...currentRows.filter((item) => item.redisNodeType !== 'LOAD_MORE'), ...nextRows.filter((item) => item.redisNodeType !== 'LOAD_MORE')]
    .forEach(appendRow);
  const mergedRoots = sortRedisTreeRows(Array.from(mergedMap.values()));
  const loadMoreRow = [...nextRows, ...currentRows].find((item) => item.redisNodeType === 'LOAD_MORE');
  if (loadMoreRow) {
    mergedRoots.push(loadMoreRow);
  }
  return mergedRoots;
}

export function collectRedisPathNodeKeys(rows: ObjectRow[]) {
  const keys: string[] = [];
  rows.forEach((item) => {
    if (item.redisNodeType === 'PATH' && item.nodeKey) {
      keys.push(item.nodeKey);
    }
    if (item.children?.length) {
      keys.push(...collectRedisPathNodeKeys(item.children));
    }
  });
  return keys;
}

export function replaceRedisBranchRows(rows: ObjectRow[], parentPath: string, nextRows: ObjectRow[]): ObjectRow[] {
  return rows.map((item) => {
    if (item.redisNodeType === 'PATH' && item.fullPath === parentPath) {
      return {
        ...item,
        children: nextRows,
      };
    }
    if (!item.children?.length) {
      return item;
    }
    return {
      ...item,
      children: replaceRedisBranchRows(item.children, parentPath, nextRows),
    };
  });
}

export function appendRedisBranchRows(rows: ObjectRow[], parentPath: string, nextRows: ObjectRow[]): ObjectRow[] {
  return rows.map((item) => {
    if (item.redisNodeType === 'PATH' && item.fullPath === parentPath) {
      const merged = [...(item.children ?? []).filter((child) => child.redisNodeType !== 'LOAD_MORE'), ...nextRows];
      return {
        ...item,
        children: merged,
      };
    }
    if (!item.children?.length) {
      return item;
    }
    return {
      ...item,
      children: appendRedisBranchRows(item.children, parentPath, nextRows),
    };
  });
}

export function findRedisBrowserRowByNodeKey(rows: ObjectRow[], nodeKey: string): ObjectRow | null {
  for (const item of rows) {
    if (item.nodeKey === nodeKey) {
      return item;
    }
    if (item.children?.length) {
      const matched = findRedisBrowserRowByNodeKey(item.children, nodeKey);
      if (matched) {
        return matched;
      }
    }
  }
  return null;
}

export function findRedisSelectedObjectRow(rows: ObjectRow[], objectName: string): ObjectRow | null {
  for (const item of rows) {
    if (item.redisNodeType === 'KEY' && item.objectName === objectName) {
      return item;
    }
    if (item.children?.length) {
      const matched = findRedisSelectedObjectRow(item.children, objectName);
      if (matched) {
        return matched;
      }
    }
  }
  return null;
}

interface RedisBrowserRuntimeHelperContext {
  getActiveDatabaseName: (connectionId: number) => string;
  redisBrowseExpandedRowKeys: Ref<string[]>;
  redisBrowserChildLoadingMap: Ref<Record<string, boolean>>;
  redisBrowserContextKey: Ref<string>;
  redisBrowserLoading: Ref<boolean>;
  redisBrowserPageCache: Ref<Record<string, KvRedisBrowserPageVO>>;
  redisBrowserRows: Ref<ObjectRow[]>;
  redisExpandedRowKeys: Ref<string[]>;
  setConnectionRuntimeStatus: (connectionId: number, status: 'idle' | 'connected' | 'failed') => void;
  tableKeyword: Ref<string>;
  workflow: { connectionId: number };
}

function normalizeRedisBrowserPath(path: string) {
  return path.replace(/^:+/, '').replace(/:+$/, '').trim();
}

function buildRedisBrowserQueryUrl(
  connectionId: number,
  databaseName: string,
  parentPath: string,
  keyword: string,
  cursor = '0',
  pageSize = 100,
) {
  const params = new URLSearchParams({
    connectionId: String(connectionId),
    databaseName,
    parentPath,
    keyword,
    cursor,
    pageSize: String(pageSize),
  });
  return `/api/kv/redis/browser?${params.toString()}`;
}

export function createRedisBrowserRuntimeHelpers(ctx: RedisBrowserRuntimeHelperContext) {
  function invalidateRedisBrowserCache(connectionId: number, databaseName?: string) {
    const targetDatabaseName = (databaseName || '').trim();
    ctx.redisBrowserPageCache.value = Object.fromEntries(
      Object.entries(ctx.redisBrowserPageCache.value).filter(([key]) => {
        if (!targetDatabaseName) {
          return !key.startsWith(`${connectionId}|`);
        }
        return !key.startsWith(`${connectionId}|${targetDatabaseName}|`);
      }),
    );
    ctx.redisBrowserChildLoadingMap.value = {};
    ctx.redisBrowserRows.value = [];
    ctx.redisExpandedRowKeys.value = [];
    ctx.redisBrowseExpandedRowKeys.value = [];
  }

  async function fetchRedisBrowserPage(
    connectionId: number,
    databaseName: string,
    parentPath: string,
    keyword: string,
    cursor = '0',
    options?: { force?: boolean; pageSize?: number },
  ) {
    const normalizedParentPath = normalizeRedisBrowserPath(parentPath);
    const normalizedKeyword = keyword.trim();
    const normalizedCursor = (cursor || '').trim() || '0';
    const cacheKey = buildRedisBrowserCacheKey(connectionId, databaseName, normalizedParentPath, normalizedKeyword, normalizedCursor);
    if (!options?.force && ctx.redisBrowserPageCache.value[cacheKey]) {
      return ctx.redisBrowserPageCache.value[cacheKey];
    }
    const page = await getApi<KvRedisBrowserPageVO>(
      buildRedisBrowserQueryUrl(
        connectionId,
        databaseName,
        normalizedParentPath,
        normalizedKeyword,
        normalizedCursor,
        options?.pageSize ?? 100,
      ),
    );
    ctx.redisBrowserPageCache.value = {
      ...ctx.redisBrowserPageCache.value,
      [cacheKey]: page,
    };
    return page;
  }

  async function loadRedisBrowserChildren(parentPath: string, options?: { force?: boolean; cursor?: string; append?: boolean }) {
    if (ctx.tableKeyword.value.trim()) {
      return;
    }
    const connectionId = ctx.workflow.connectionId;
    const databaseName = ctx.getActiveDatabaseName(connectionId);
    if (!connectionId || !databaseName) {
      return;
    }
    const normalizedParentPath = normalizeRedisBrowserPath(parentPath);
    const loadingKey = normalizedParentPath || '__root__';
    if (ctx.redisBrowserChildLoadingMap.value[loadingKey]) {
      return;
    }
    ctx.redisBrowserChildLoadingMap.value = {
      ...ctx.redisBrowserChildLoadingMap.value,
      [loadingKey]: true,
    };
    try {
      const page = await fetchRedisBrowserPage(
        connectionId,
        databaseName,
        normalizedParentPath,
        '',
        options?.cursor || '0',
        {force: options?.force},
      );
      const nextRows = normalizeRedisBrowserRows(
        page.items ?? [],
        normalizedParentPath,
        page.nextCursor,
        page.finished,
      );
      ctx.redisBrowserRows.value = options?.append
        ? appendRedisBranchRows(ctx.redisBrowserRows.value, normalizedParentPath, nextRows)
        : replaceRedisBranchRows(ctx.redisBrowserRows.value, normalizedParentPath, nextRows);
      ctx.setConnectionRuntimeStatus(connectionId, 'connected');
    } catch (error) {
      ctx.setConnectionRuntimeStatus(connectionId, 'failed');
      throw error;
    } finally {
      const nextLoadingMap = {...ctx.redisBrowserChildLoadingMap.value};
      delete nextLoadingMap[loadingKey];
      ctx.redisBrowserChildLoadingMap.value = nextLoadingMap;
    }
  }

  async function loadRedisBrowserRows(options?: { force?: boolean }) {
    const connectionId = ctx.workflow.connectionId;
    const databaseName = ctx.getActiveDatabaseName(connectionId);
    if (!connectionId || !databaseName) {
      ctx.redisBrowserRows.value = [];
      return;
    }
    const contextKey = `${connectionId}|${databaseName}`;
    if (ctx.redisBrowserContextKey.value !== contextKey) {
      ctx.redisBrowserContextKey.value = contextKey;
      ctx.redisExpandedRowKeys.value = [];
      ctx.redisBrowseExpandedRowKeys.value = [];
    }
    ctx.redisBrowserLoading.value = true;
    try {
      const keyword = ctx.tableKeyword.value.trim();
      const page = await fetchRedisBrowserPage(
        connectionId,
        databaseName,
        '',
        keyword,
        '0',
        {force: options?.force},
      );
      if (keyword) {
        ctx.redisBrowserRows.value = buildRedisSearchTreeRows(page.items ?? [], page.nextCursor, page.finished);
        ctx.redisExpandedRowKeys.value = Array.from(new Set(collectRedisPathNodeKeys(ctx.redisBrowserRows.value)));
      } else {
        const previousExpandedKeys = [...ctx.redisBrowseExpandedRowKeys.value];
        ctx.redisBrowserRows.value = normalizeRedisBrowserRows(page.items ?? [], '', page.nextCursor, page.finished);
        ctx.redisExpandedRowKeys.value = [];
        for (const nodeKey of previousExpandedKeys) {
          const path = nodeKey.startsWith('path:') ? nodeKey.slice('path:'.length) : '';
          if (!path) {
            continue;
          }
          await loadRedisBrowserChildren(path);
          if (!ctx.redisExpandedRowKeys.value.includes(nodeKey)) {
            ctx.redisExpandedRowKeys.value = [...ctx.redisExpandedRowKeys.value, nodeKey];
          }
        }
        ctx.redisBrowseExpandedRowKeys.value = [...ctx.redisExpandedRowKeys.value];
      }
      ctx.setConnectionRuntimeStatus(connectionId, 'connected');
    } catch (error) {
      ctx.setConnectionRuntimeStatus(connectionId, 'failed');
      throw error;
    } finally {
      ctx.redisBrowserLoading.value = false;
    }
  }

  async function handleRedisBrowserExpand(expanded: boolean, record: ObjectRow) {
    if (record.redisNodeType !== 'PATH' || !record.nodeKey || !record.fullPath) {
      return;
    }
    if (!expanded) {
      ctx.redisExpandedRowKeys.value = ctx.redisExpandedRowKeys.value.filter((item) => item !== record.nodeKey);
      if (!ctx.tableKeyword.value.trim()) {
        ctx.redisBrowseExpandedRowKeys.value = [...ctx.redisExpandedRowKeys.value];
      }
      return;
    }
    if (ctx.tableKeyword.value.trim()) {
      if (!ctx.redisExpandedRowKeys.value.includes(record.nodeKey)) {
        ctx.redisExpandedRowKeys.value = [...ctx.redisExpandedRowKeys.value, record.nodeKey];
      }
      return;
    }
    const hasLoadedChildren = !!record.children?.some((item) => item.redisNodeType !== 'LOAD_MORE');
    if (!hasLoadedChildren) {
      await loadRedisBrowserChildren(record.fullPath);
      await nextTick();
    }
    if (!ctx.redisExpandedRowKeys.value.includes(record.nodeKey)) {
      ctx.redisExpandedRowKeys.value = [...ctx.redisExpandedRowKeys.value, record.nodeKey];
    }
    ctx.redisBrowseExpandedRowKeys.value = [...ctx.redisExpandedRowKeys.value];
  }

  async function toggleRedisBrowserPath(record: ObjectRow) {
    if (record.redisNodeType !== 'PATH' || !record.nodeKey) {
      return;
    }
    const expanded = ctx.redisExpandedRowKeys.value.includes(record.nodeKey);
    await handleRedisBrowserExpand(!expanded, record);
  }

  async function loadMoreRedisBrowserRows(record: ObjectRow) {
    if (record.redisNodeType !== 'LOAD_MORE' || !record.nextCursor) {
      return;
    }
    const connectionId = ctx.workflow.connectionId;
    const databaseName = ctx.getActiveDatabaseName(connectionId);
    if (!databaseName) {
      return;
    }
    const keyword = ctx.tableKeyword.value.trim();
    if (keyword) {
      const page = await fetchRedisBrowserPage(
        connectionId,
        databaseName,
        '',
        keyword,
        record.nextCursor,
      );
      const nextRows = buildRedisSearchTreeRows(page.items ?? [], page.nextCursor, page.finished);
      ctx.redisBrowserRows.value = mergeRedisSearchTreeRows(ctx.redisBrowserRows.value, nextRows);
      ctx.redisExpandedRowKeys.value = Array.from(new Set(collectRedisPathNodeKeys(ctx.redisBrowserRows.value)));
      return;
    }
    const parentPath = normalizeRedisBrowserPath(record.fullPath || '');
    if (!parentPath) {
      const page = await fetchRedisBrowserPage(
        connectionId,
        databaseName,
        '',
        '',
        record.nextCursor,
      );
      const nextRows = normalizeRedisBrowserRows(
        page.items ?? [],
        '',
        page.nextCursor,
        page.finished,
      );
      ctx.redisBrowserRows.value = [
        ...ctx.redisBrowserRows.value.filter((item) => item.redisNodeType !== 'LOAD_MORE'),
        ...nextRows,
      ];
      return;
    }
    await loadRedisBrowserChildren(parentPath, {
      cursor: record.nextCursor,
      append: true,
    });
  }

  return {
    fetchRedisBrowserPage,
    handleRedisBrowserExpand,
    invalidateRedisBrowserCache,
    loadMoreRedisBrowserRows,
    loadRedisBrowserChildren,
    loadRedisBrowserRows,
    toggleRedisBrowserPath,
  };
}

type RedisKeyModalForm = {
  keyName: string;
  valueType: 'string' | 'hash' | 'list' | 'set' | 'zset';
  ttlSeconds: number;
  editorPayload: string;
};

interface RedisKeyModalHelperContext {
  activeConnectionIsKv: ComputedRef<boolean>;
  clearObjectDetail: () => void;
  getActiveDatabaseName: (connectionId: number) => string;
  invalidateRedisBrowserCache: (connectionId: number, databaseName: string) => void;
  kvObjectDetail: Ref<KvObjectDetailVO | null>;
  loadObjectDetail: (
    connectionId: number,
    databaseName: string,
    objectType: ObjectRow['objectType'],
    objectName: string,
  ) => Promise<void>;
  loadOverview: (options?: { forceTableStats?: boolean; syncTreeCaches?: boolean }) => Promise<void>;
  redisKeyForm: RedisKeyModalForm;
  redisKeyModalMode: Ref<'create' | 'edit'>;
  redisKeyModalOpen: Ref<boolean>;
  redisKeyModalSubmitting: Ref<boolean>;
  runSafely: (runner: () => Promise<void>) => Promise<void>;
  selectedConnection: ComputedRef<ConnectionVO | undefined>;
  selectedObjectName: Ref<string>;
  selectedObjectRecord: ComputedRef<ObjectRow | null>;
  workflow: { connectionId: number };
}

function normalizeRedisValueType(value?: string): 'string' | 'hash' | 'list' | 'set' | 'zset' {
  const normalized = String(value || '').trim().toLowerCase();
  if (normalized === 'hash' || normalized === 'list' || normalized === 'set' || normalized === 'zset') {
    return normalized;
  }
  return 'string';
}

function parseRedisEditorEntries(valueType: string, payload: string): KvRedisKeyEntryVO[] {
  const trimmed = payload.trim();
  if (!trimmed) {
    return [];
  }
  const parsed = JSON.parse(trimmed);
  if (valueType === 'hash') {
    return Object.entries(parsed as Record<string, unknown>).map(([key, value]) => ({
      key,
      value: value == null ? '' : String(value),
    }));
  }
  if (valueType === 'list' || valueType === 'set') {
    return (Array.isArray(parsed) ? parsed : []).map((value) => ({
      value: value == null ? '' : String(value),
    }));
  }
  if (valueType === 'zset') {
    return (Array.isArray(parsed) ? parsed : []).map((item) => ({
      key: String((item as { member?: unknown }).member ?? ''),
      score: Number((item as { score?: unknown }).score ?? 0),
    }));
  }
  return [];
}

export function createRedisKeyModalHelpers(ctx: RedisKeyModalHelperContext) {
  function openCreateRedisKeyModal() {
    ctx.redisKeyModalMode.value = 'create';
    ctx.redisKeyForm.keyName = '';
    ctx.redisKeyForm.valueType = 'string';
    ctx.redisKeyForm.ttlSeconds = -1;
    ctx.redisKeyForm.editorPayload = '';
    ctx.redisKeyModalOpen.value = true;
  }

  function openEditRedisKeyModal() {
    if (!ctx.selectedObjectRecord.value || !ctx.activeConnectionIsKv.value || ctx.selectedConnection.value?.dbType !== 'REDIS') {
      return;
    }
    ctx.redisKeyModalMode.value = 'edit';
    ctx.redisKeyForm.keyName = ctx.selectedObjectRecord.value.objectName;
    ctx.redisKeyForm.valueType = normalizeRedisValueType(ctx.kvObjectDetail.value?.valueType);
    ctx.redisKeyForm.ttlSeconds = Number(ctx.kvObjectDetail.value?.ttlSeconds ?? -1);
    ctx.redisKeyForm.editorPayload = ctx.kvObjectDetail.value?.editorPayload || '';
    ctx.redisKeyModalOpen.value = true;
  }

  function closeRedisKeyModal() {
    ctx.redisKeyModalOpen.value = false;
    ctx.redisKeyModalSubmitting.value = false;
  }

  async function confirmRedisKeyModal() {
    await ctx.runSafely(async () => {
      if (ctx.selectedConnection.value?.dbType !== 'REDIS') {
        return;
      }
      const connectionId = ctx.workflow.connectionId;
      const databaseName = ctx.getActiveDatabaseName(connectionId);
      const valueType = ctx.redisKeyForm.valueType;
      const payload: KvRedisKeySaveReq = {
        connectionId,
        databaseName,
        keyName: ctx.redisKeyForm.keyName.trim(),
        valueType,
        ttlSeconds: Number(ctx.redisKeyForm.ttlSeconds ?? -1),
        stringValue: valueType === 'string' ? ctx.redisKeyForm.editorPayload : undefined,
        entries: valueType === 'string' ? [] : parseRedisEditorEntries(valueType, ctx.redisKeyForm.editorPayload),
      };
      ctx.redisKeyModalSubmitting.value = true;
      try {
        const endpoint = ctx.redisKeyModalMode.value === 'create'
          ? '/api/kv/redis/key/create'
          : '/api/kv/redis/key/update';
        const result = await postApi<KvRedisKeySaveVO>(endpoint, payload);
        message.success(result.message);
        closeRedisKeyModal();
        await ctx.loadOverview({forceTableStats: false});
        await ctx.loadObjectDetail(connectionId, databaseName, 'tables', payload.keyName);
        ctx.selectedObjectName.value = payload.keyName;
      } finally {
        ctx.redisKeyModalSubmitting.value = false;
      }
    });
  }

  async function deleteRedisKey(targetValue: string, targetType: 'KEY' | 'PATH' = 'KEY') {
    await new Promise<void>((resolve) => {
      Modal.confirm({
        title: targetType === 'PATH' ? '删除路径下全部键' : '删除键',
        content: targetType === 'PATH'
          ? `将递归删除此前缀下所有 keys：${targetValue}`
          : `确定删除键：${targetValue}？`,
        okType: 'danger',
        okText: '删除',
        cancelText: '取消',
        onOk() {
          resolve();
          void ctx.runSafely(async () => {
            const connectionId = ctx.workflow.connectionId;
            const databaseName = ctx.getActiveDatabaseName(connectionId);
            const result = await postApi<KvRedisKeyDeleteVO>('/api/kv/redis/key/delete', {
              connectionId,
              databaseName,
              targetType,
              targetValue,
            } satisfies KvRedisKeyDeleteReq);
            message.success(result.message);
            if (targetType === 'KEY' && ctx.selectedObjectName.value === targetValue) {
              ctx.selectedObjectName.value = '';
              ctx.clearObjectDetail();
            }
            if (targetType === 'PATH' && ctx.selectedObjectName.value
              && (ctx.selectedObjectName.value === targetValue || ctx.selectedObjectName.value.startsWith(`${targetValue}:`))) {
              ctx.selectedObjectName.value = '';
              ctx.clearObjectDetail();
            }
            ctx.invalidateRedisBrowserCache(connectionId, databaseName);
            await ctx.loadOverview({forceTableStats: true});
          });
        },
        onCancel() {
          resolve();
        },
      });
    });
  }

  return {
    closeRedisKeyModal,
    confirmRedisKeyModal,
    deleteRedisKey,
    openCreateRedisKeyModal,
    openEditRedisKeyModal,
  };
}
