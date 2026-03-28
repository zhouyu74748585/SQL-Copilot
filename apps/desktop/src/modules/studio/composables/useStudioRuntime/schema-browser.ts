import {
  CheckCircleOutlined,
  ClockCircleOutlined,
  CloseCircleOutlined,
  LoadingOutlined,
  MinusCircleOutlined,
} from '@ant-design/icons-vue';
import type {Ref} from 'vue';
import type {ConnectionVO} from '@sqlcopilot/shared-contracts';
import {message} from 'ant-design-vue';
import {getApi, postApi} from '../../../../api/client';
import type {
  ConnectionDbTypeVO,
  KvObjectDetailVO,
  KvOverviewVO,
  KvRedisBrowserPageVO,
  RagVectorizeEnqueueVO,
  RagVectorizeInterruptVO,
  RagVectorizeOverviewVO,
  RagVectorizeTableVO,
  RagDatabaseVectorizeStatusVO,
  SavedQueryVO,
  SchemaDatabaseVO,
  SchemaNamespaceVO,
  SchemaObjectDefinitionVO,
  SchemaOverviewVO,
  SchemaTableStatsVO,
  TableDetailVO,
} from '../../../../types';
import {namespaceCacheKey, objectCacheKey, queryTableDetailCacheKey, tableCacheKey, vectorizeStatusCacheKey} from './cache';
import {buildSchemaContext, parseConfiguredDatabaseName, supportsSchemaLayer} from './utils';
import type {ObjectRow} from './types';

interface SchemaBrowserHelperContext {
  activeDatabaseMap: Ref<Record<number, string>>;
  connections: Ref<ConnectionVO[]>;
  databaseListCache: Ref<Record<number, string[]>>;
  databaseVectorizeStatusMap: Ref<Record<string, RagDatabaseVectorizeStatusVO>>;
  getVectorizeStatusPollTimer: () => number | null;
  getVectorizeStatusPollIntervalMs: () => number;
  getWorkflowConnectionId: () => number;
  isDatabaseContextVisibleForConnection: (connection: ConnectionVO, databaseName: string) => boolean;
  isKvConnectionId: (connectionId: number) => boolean;
  namespaceListCache: Ref<Record<string, string[]>>;
  namespaceListLoadedCache: Ref<Record<string, boolean>>;
  requiresDatabaseLayer: (connection: ConnectionVO) => boolean;
  setConnectionRuntimeStatus: (connectionId: number, status: 'idle' | 'connected' | 'failed') => void;
  setVectorizeStatusPollTimer: (timer: number | null) => void;
  visibleDatabasesForConnection: (connection: ConnectionVO) => string[];
}

export function createSchemaBrowserHelpers(ctx: SchemaBrowserHelperContext) {
  async function prepareConnectionTreeData(connectionId: number, options?: { force?: boolean }) {
    const connection = ctx.connections.value.find((item) => item.id === connectionId);
    if (!connection) {
      return;
    }
    if (ctx.requiresDatabaseLayer(connection)) {
      await loadDatabaseListForConnection(connectionId, options);
    } else {
      const configuredDb = parseConfiguredDatabaseName(connection);
      if (configuredDb) {
        ctx.activeDatabaseMap.value = {
          ...ctx.activeDatabaseMap.value,
          [connectionId]: configuredDb,
        };
      }
    }
  }

  async function loadDatabaseListForConnection(connectionId: number, options?: { force?: boolean }) {
    const connection = ctx.connections.value.find((item) => item.id === connectionId);
    if (!options?.force && ctx.databaseListCache.value[connectionId]?.length) {
      ctx.setConnectionRuntimeStatus(connectionId, 'connected');
      return;
    }
    const endpoint = ctx.isKvConnectionId(connectionId)
      ? `/api/kv/databases?connectionId=${connectionId}`
      : `/api/schema/databases?connectionId=${connectionId}`;
    let list: SchemaDatabaseVO[];
    try {
      list = await getApi<SchemaDatabaseVO[]>(endpoint);
    } catch (error) {
      ctx.setConnectionRuntimeStatus(connectionId, 'failed');
      throw error;
    }
    ctx.setConnectionRuntimeStatus(connectionId, 'connected');
    const databaseNames = list.map((item) => item.databaseName).filter((item) => !!item);
    ctx.databaseListCache.value = {
      ...ctx.databaseListCache.value,
      [connectionId]: databaseNames,
    };
    if (connection) {
      const visibleNames = ctx.visibleDatabasesForConnection(connection);
      const current = (ctx.activeDatabaseMap.value[connectionId] || '').trim();
      if (!current && connection.dbType === 'REDIS') {
        const configuredDatabaseName = parseConfiguredDatabaseName(connection);
        const preferredDatabaseName = (configuredDatabaseName && databaseNames.includes(configuredDatabaseName))
          ? configuredDatabaseName
          : (databaseNames[0] || configuredDatabaseName || '0');
        if (preferredDatabaseName) {
          ctx.activeDatabaseMap.value = {
            ...ctx.activeDatabaseMap.value,
            [connectionId]: preferredDatabaseName,
          };
        }
      }
      if (current && visibleNames.length && !ctx.isDatabaseContextVisibleForConnection(connection, current)) {
        ctx.activeDatabaseMap.value = {
          ...ctx.activeDatabaseMap.value,
          [connectionId]: '',
        };
      }
    }
    const next = {...ctx.databaseVectorizeStatusMap.value};
    const prefix = `${connectionId}|`;
    Object.keys(next).forEach((key) => {
      if (key.startsWith(prefix)) {
        delete next[key];
      }
    });
    list.forEach((item) => {
      if (!item.databaseName) {
        return;
      }
      const key = vectorizeStatusCacheKey(connectionId, item.databaseName);
      next[key] = {
        databaseName: item.databaseName,
        status: item.vectorizeStatus,
        message: item.vectorizeMessage,
        updatedAt: item.vectorizeUpdatedAt,
      };
    });
    ctx.databaseVectorizeStatusMap.value = next;
  }

  async function loadNamespaceList(connectionId: number, databaseName: string, options?: { force?: boolean }) {
    if (!connectionId || !databaseName) {
      return [];
    }
    const connection = ctx.connections.value.find((item) => item.id === connectionId);
    if (!connection || !supportsSchemaLayer(connection.dbType)) {
      return [];
    }
    const cacheKey = namespaceCacheKey(connectionId, databaseName);
    if (!options?.force && ctx.namespaceListLoadedCache.value[cacheKey]) {
      return ctx.namespaceListCache.value[cacheKey] ?? [];
    }
    const list = await getApi<SchemaNamespaceVO[]>(
      `/api/schema/namespaces?connectionId=${connectionId}&databaseName=${encodeURIComponent(databaseName)}`,
    );
    const namespaceNames = list.map((item) => (item.namespaceName || '').trim()).filter((item) => !!item);
    ctx.namespaceListCache.value = {
      ...ctx.namespaceListCache.value,
      [cacheKey]: namespaceNames,
    };
    ctx.namespaceListLoadedCache.value = {
      ...ctx.namespaceListLoadedCache.value,
      [cacheKey]: true,
    };
    return namespaceNames;
  }

  async function refreshVectorizeStatusForConnection(connectionId: number) {
    const list = await getApi<RagDatabaseVectorizeStatusVO[]>(
      `/api/rag/vectorize/status/list?connectionId=${connectionId}`,
    );
    const next = {...ctx.databaseVectorizeStatusMap.value};
    const prefix = `${connectionId}|`;
    Object.keys(next).forEach((key) => {
      if (key.startsWith(prefix)) {
        delete next[key];
      }
    });
    list.forEach((item) => {
      if (!item.databaseName) {
        return;
      }
      const key = vectorizeStatusCacheKey(connectionId, item.databaseName);
      next[key] = item;
    });
    ctx.databaseVectorizeStatusMap.value = next;
  }

  async function refreshAllVectorizeStatuses(targetConnectionIds?: number[]) {
    const ids = targetConnectionIds ?? ctx.connections.value.map((item) => item.id);
    if (!ids.length) {
      ctx.databaseVectorizeStatusMap.value = {};
      return;
    }
    await Promise.all(ids.map(async (connectionId) => {
      try {
        await refreshVectorizeStatusForConnection(connectionId);
      } catch {
        // 关键操作：状态轮询失败不阻断主流程，等待下一次轮询重试。
      }
    }));
  }

  function pruneVectorizeStatusMap(validConnectionIds: number[]) {
    const valid = new Set(validConnectionIds.map((item) => `${item}|`));
    const next: Record<string, RagDatabaseVectorizeStatusVO> = {};
    Object.entries(ctx.databaseVectorizeStatusMap.value).forEach(([key, value]) => {
      if (Array.from(valid).some((prefix) => key.startsWith(prefix))) {
        next[key] = value;
      }
    });
    ctx.databaseVectorizeStatusMap.value = next;
  }

  function startVectorizeStatusPolling() {
    stopVectorizeStatusPolling();
    ctx.setVectorizeStatusPollTimer(window.setInterval(() => {
      const cachedConnectionIds = Object.keys(ctx.databaseListCache.value)
        .map((item) => Number(item))
        .filter((item) => Number.isFinite(item) && item > 0);
      const workflowConnectionId = ctx.getWorkflowConnectionId();
      const ids = cachedConnectionIds.length ? cachedConnectionIds : (workflowConnectionId ? [workflowConnectionId] : []);
      if (!ids.length) {
        return;
      }
      void refreshAllVectorizeStatuses(ids);
    }, ctx.getVectorizeStatusPollIntervalMs()));
  }

  function stopVectorizeStatusPolling() {
    const timer = ctx.getVectorizeStatusPollTimer();
    if (timer !== null) {
      window.clearInterval(timer);
      ctx.setVectorizeStatusPollTimer(null);
    }
  }

  return {
    loadDatabaseListForConnection,
    loadNamespaceList,
    prepareConnectionTreeData,
    pruneVectorizeStatusMap,
    refreshAllVectorizeStatuses,
    refreshVectorizeStatusForConnection,
    startVectorizeStatusPolling,
    stopVectorizeStatusPolling,
  };
}

interface SchemaOverviewRuntimeHelperContext {
  activeConnectionIsRedis: () => boolean;
  connections: Ref<ConnectionVO[]>;
  ensureConnection: () => void;
  expandConnectionNode: (connectionId: number) => void;
  expandedTreeKeys: Ref<(string | number)[]>;
  fetchRedisBrowserPage: (
    connectionId: number,
    databaseName: string,
    parentPath: string,
    keyword: string,
    cursor?: string,
    options?: { force?: boolean; pageSize?: number },
  ) => Promise<KvRedisBrowserPageVO>;
  getActiveDatabaseName: (connectionId: number) => string;
  getTableStatsMinRequestIntervalMs: () => number;
  getTableStatsPollIntervalMs: () => number;
  invalidateRedisBrowserCache: (connectionId: number, databaseName?: string) => void;
  isKvConnectionId: (connectionId: number) => boolean;
  kvOverview: Ref<KvOverviewVO | null>;
  loadRedisBrowserRows: (options?: { force?: boolean }) => Promise<void>;
  objectNameCache: Ref<Record<string, string[]>>;
  pendingTableDetailLoads: Map<string, Promise<TableDetailVO | null>>;
  pendingTableNameLoads: Map<string, Promise<string[]>>;
  queryTableDetailCache: Ref<Record<string, TableDetailVO>>;
  runSafely: (runner: () => Promise<void>) => Promise<void>;
  schemaOverview: Ref<SchemaOverviewVO | null>;
  setConnectionRuntimeStatus: (connectionId: number, status: 'idle' | 'connected' | 'failed') => void;
  tableNameCache: Ref<Record<string, string[]>>;
  tableNameLoadedCache: Ref<Record<string, boolean>>;
  tableStatsCache: Ref<Record<string, Record<string, { rowEstimate: number; tableSizeBytes: number }>>>;
  tableStatsLastRequestAt: Ref<Record<string, number>>;
  tableStatsLoadingState: Ref<Record<string, boolean>>;
  tableStatsPollingTimers: Map<string, number>;
  workflow: { connectionId: number };
}

export function createSchemaOverviewRuntimeHelpers(ctx: SchemaOverviewRuntimeHelperContext) {
  function clearTableStatsPollingTimer(cacheKey: string) {
    const timer = ctx.tableStatsPollingTimers.get(cacheKey);
    if (timer !== undefined) {
      window.clearTimeout(timer);
      ctx.tableStatsPollingTimers.delete(cacheKey);
    }
  }

  function clearAllTableStatsPollingTimers() {
    ctx.tableStatsPollingTimers.forEach((timer) => {
      window.clearTimeout(timer);
    });
    ctx.tableStatsPollingTimers.clear();
  }

  function applyTableStatsSnapshot(connectionId: number, databaseName: string, payload: SchemaTableStatsVO) {
    const cacheKey = tableCacheKey(connectionId, databaseName);
    const next: Record<string, { rowEstimate: number; tableSizeBytes: number }> = {};
    (payload.tableStats ?? []).forEach((item) => {
      const tableName = (item.tableName || '').trim();
      if (!tableName) {
        return;
      }
      next[tableName] = {
        rowEstimate: Math.max(0, Number(item.rowEstimate ?? 0)),
        tableSizeBytes: Math.max(0, Number(item.tableSizeBytes ?? 0)),
      };
    });
    ctx.tableStatsCache.value = {
      ...ctx.tableStatsCache.value,
      [cacheKey]: next,
    };
  }

  function isDatabaseNodeExpanded(connectionId: number, databaseName: string) {
    const nodeKey = buildDatabaseNodeKey(connectionId, databaseName);
    return ctx.expandedTreeKeys.value.some((item) => item === nodeKey || String(item).startsWith(`${nodeKey}-`));
  }

  function collectExpandedDatabaseTargets(keys: string[]) {
    const map = new Map<string, { connectionId: number; databaseName: string }>();
    keys.forEach((key) => {
      let match = key.match(/^conn-(\d+)-db-(.+?)-category-[a-z]+$/);
      if (!match) {
        match = key.match(/^conn-(\d+)-db-(.+?)-obj-[a-z]+-.+$/);
      }
      if (!match) {
        match = key.match(/^conn-(\d+)-db-(.+)$/);
      }
      if (!match) {
        return;
      }
      const connectionId = Number(match[1]);
      const databaseName = decodeURIComponent(match[2] || '').trim();
      if (!connectionId || !databaseName || databaseName === '未发现数据库') {
        return;
      }
      map.set(`${connectionId}|${databaseName}`, {connectionId, databaseName});
    });
    return Array.from(map.values());
  }

  async function fetchTableStatsForDatabase(
    connectionId: number,
    databaseName: string,
    options?: { force?: boolean; polling?: boolean },
  ) {
    if (ctx.isKvConnectionId(connectionId)) {
      clearTableStatsPollingTimer(tableCacheKey(connectionId, databaseName));
      return;
    }
    if (!connectionId || !databaseName || databaseName === '未发现数据库') {
      return;
    }
    if (!isDatabaseNodeExpanded(connectionId, databaseName)) {
      clearTableStatsPollingTimer(tableCacheKey(connectionId, databaseName));
      return;
    }
    const cacheKey = tableCacheKey(connectionId, databaseName);
    if (ctx.tableStatsLoadingState.value[cacheKey]) {
      return;
    }
    const now = Date.now();
    const lastRequestAt = ctx.tableStatsLastRequestAt.value[cacheKey] ?? 0;
    if (!options?.force && !options?.polling && now - lastRequestAt < ctx.getTableStatsMinRequestIntervalMs()) {
      return;
    }

    ctx.tableStatsLastRequestAt.value = {
      ...ctx.tableStatsLastRequestAt.value,
      [cacheKey]: now,
    };
    ctx.tableStatsLoadingState.value = {
      ...ctx.tableStatsLoadingState.value,
      [cacheKey]: true,
    };
    try {
      const result = await getApi<SchemaTableStatsVO>(
        `/api/schema/tableStats?connectionId=${connectionId}&databaseName=${encodeURIComponent(databaseName)}`,
      );
      applyTableStatsSnapshot(connectionId, databaseName, result);
      if (result.refreshing && isDatabaseNodeExpanded(connectionId, databaseName)) {
        clearTableStatsPollingTimer(cacheKey);
        const timer = window.setTimeout(() => {
          void fetchTableStatsForDatabase(connectionId, databaseName, {polling: true});
        }, ctx.getTableStatsPollIntervalMs());
        ctx.tableStatsPollingTimers.set(cacheKey, timer);
      } else {
        clearTableStatsPollingTimer(cacheKey);
      }
    } catch {
      clearTableStatsPollingTimer(cacheKey);
    } finally {
      ctx.tableStatsLoadingState.value = {
        ...ctx.tableStatsLoadingState.value,
        [cacheKey]: false,
      };
    }
  }

  function scheduleTableStatsForExpandedDatabases(keys: string[]) {
    const targets = collectExpandedDatabaseTargets(keys);
    targets.forEach((item) => {
      void fetchTableStatsForDatabase(item.connectionId, item.databaseName);
    });
    Array.from(ctx.tableStatsPollingTimers.keys()).forEach((cacheKey) => {
      const [connectionIdText, ...dbParts] = cacheKey.split('|');
      const connectionId = Number(connectionIdText);
      const databaseName = dbParts.join('|');
      if (!connectionId || !databaseName) {
        return;
      }
      if (!isDatabaseNodeExpanded(connectionId, databaseName)) {
        clearTableStatsPollingTimer(cacheKey);
      }
    });
  }

  async function loadTableNamesByConnection(connectionId: number, databaseName: string) {
    if (!connectionId || !databaseName) {
      return [];
    }
    if (ctx.isKvConnectionId(connectionId)) {
      const connection = ctx.connections.value.find((item) => item.id === connectionId);
      if (connection?.dbType === 'REDIS') {
        const page = await ctx.fetchRedisBrowserPage(connectionId, databaseName, '', '', '0');
        const names = (page.items ?? [])
          .filter((item) => item.nodeType === 'KEY' && item.objectName)
          .map((item) => item.objectName as string);
        const cacheKey = tableCacheKey(connectionId, databaseName);
        ctx.tableNameCache.value = {
          ...ctx.tableNameCache.value,
          [cacheKey]: names,
        };
        ctx.tableNameLoadedCache.value = {
          ...ctx.tableNameLoadedCache.value,
          [cacheKey]: true,
        };
        ctx.objectNameCache.value = {
          ...ctx.objectNameCache.value,
          [objectCacheKey(connectionId, databaseName, 'tables')]: names,
        };
        return names;
      }
      const query = `/api/kv/overview?connectionId=${connectionId}&databaseName=${encodeURIComponent(databaseName)}`;
      const overview = await getApi<KvOverviewVO>(query);
      const names = (overview.objects ?? []).map((item) => item.objectName);
      const cacheKey = tableCacheKey(connectionId, databaseName);
      ctx.tableNameCache.value = {
        ...ctx.tableNameCache.value,
        [cacheKey]: names,
      };
      ctx.tableNameLoadedCache.value = {
        ...ctx.tableNameLoadedCache.value,
        [cacheKey]: true,
      };
      ctx.objectNameCache.value = {
        ...ctx.objectNameCache.value,
        [objectCacheKey(connectionId, databaseName, 'tables')]: names,
      };
      return names;
    }
    const query = `/api/schema/overview?connectionId=${connectionId}&databaseName=${encodeURIComponent(databaseName)}`;
    const overview = await getApi<SchemaOverviewVO>(query);
    const tableNames = (overview.tableSummaries ?? []).map((item) => item.tableName);
    const cacheKey = tableCacheKey(connectionId, databaseName);
    ctx.tableNameCache.value = {
      ...ctx.tableNameCache.value,
      [cacheKey]: tableNames,
    };
    ctx.tableNameLoadedCache.value = {
      ...ctx.tableNameLoadedCache.value,
      [cacheKey]: true,
    };
    ctx.objectNameCache.value = {
      ...ctx.objectNameCache.value,
      [objectCacheKey(connectionId, databaseName, 'tables')]: tableNames,
    };
    return tableNames;
  }

  async function ensureTableNamesLoaded(connectionId: number, databaseName: string) {
    if (!connectionId || !databaseName || databaseName === '未发现数据库') {
      return [];
    }
    const cacheKey = tableCacheKey(connectionId, databaseName);
    const loaded = !!ctx.tableNameLoadedCache.value[cacheKey];
    if (loaded) {
      return ctx.tableNameCache.value[cacheKey] ?? [];
    }
    const pending = ctx.pendingTableNameLoads.get(cacheKey);
    if (pending) {
      return pending;
    }
    const task = loadTableNamesByConnection(connectionId, databaseName)
      .finally(() => {
        ctx.pendingTableNameLoads.delete(cacheKey);
      });
    ctx.pendingTableNameLoads.set(cacheKey, task);
    return task;
  }

  async function loadQueryTableDetail(connectionId: number, databaseName: string, tableName: string) {
    if (!connectionId || !tableName) {
      return null;
    }
    const query = databaseName
      ? `/api/schema/tableDetail?connectionId=${connectionId}&databaseName=${encodeURIComponent(databaseName)}&tableName=${encodeURIComponent(tableName)}`
      : `/api/schema/tableDetail?connectionId=${connectionId}&tableName=${encodeURIComponent(tableName)}`;
    const detail = await getApi<TableDetailVO>(query);
    ctx.queryTableDetailCache.value = {
      ...ctx.queryTableDetailCache.value,
      [queryTableDetailCacheKey(connectionId, databaseName, tableName)]: detail,
    };
    return detail;
  }

  async function ensureQueryTableDetailLoaded(connectionId: number, databaseName: string, tableName: string) {
    if (!connectionId || !tableName) {
      return null;
    }
    const cacheKey = queryTableDetailCacheKey(connectionId, databaseName, tableName);
    const cached = ctx.queryTableDetailCache.value[cacheKey];
    if (cached) {
      return cached;
    }
    const pending = ctx.pendingTableDetailLoads.get(cacheKey);
    if (pending) {
      return pending;
    }
    const task = loadQueryTableDetail(connectionId, databaseName, tableName)
      .catch(() => null)
      .finally(() => {
        ctx.pendingTableDetailLoads.delete(cacheKey);
      });
    ctx.pendingTableDetailLoads.set(cacheKey, task);
    return task;
  }

  async function loadOverview(options?: { forceTableStats?: boolean; syncTreeCaches?: boolean }) {
    ctx.ensureConnection();
    await ctx.runSafely(async () => {
      const connectionId = ctx.workflow.connectionId;
      const databaseName = ctx.getActiveDatabaseName(connectionId);
      if (ctx.isKvConnectionId(connectionId)) {
        ctx.schemaOverview.value = null;
        if (ctx.activeConnectionIsRedis()) {
          ctx.kvOverview.value = null;
          if (options?.forceTableStats) {
            ctx.invalidateRedisBrowserCache(connectionId, databaseName);
          }
          await ctx.loadRedisBrowserRows({force: options?.forceTableStats});
        } else {
          const query = databaseName
            ? `/api/kv/overview?connectionId=${connectionId}&databaseName=${encodeURIComponent(databaseName)}`
            : `/api/kv/overview?connectionId=${connectionId}`;
          const overview = await getApi<KvOverviewVO>(query);
          ctx.setConnectionRuntimeStatus(connectionId, 'connected');
          ctx.kvOverview.value = overview;
          if (options?.syncTreeCaches !== false) {
            const cacheKey = tableCacheKey(connectionId, databaseName);
            const names = (overview.objects ?? []).map((item) => item.objectName);
            ctx.tableNameCache.value = {
              ...ctx.tableNameCache.value,
              [cacheKey]: names,
            };
            ctx.tableNameLoadedCache.value = {
              ...ctx.tableNameLoadedCache.value,
              [cacheKey]: true,
            };
            ctx.objectNameCache.value = {
              ...ctx.objectNameCache.value,
              [objectCacheKey(connectionId, databaseName, 'tables')]: names,
            };
          }
        }
        ctx.schemaOverview.value = null;
        ctx.expandConnectionNode(connectionId);
        return;
      }
      const query = databaseName
        ? `/api/schema/overview?connectionId=${connectionId}&databaseName=${encodeURIComponent(databaseName)}`
        : `/api/schema/overview?connectionId=${connectionId}`;
      const overview = await getApi<SchemaOverviewVO>(query);
      ctx.setConnectionRuntimeStatus(connectionId, 'connected');
      ctx.schemaOverview.value = overview;
      ctx.kvOverview.value = null;
      if (options?.syncTreeCaches !== false) {
        const cacheKey = tableCacheKey(connectionId, databaseName);
        const tableNames = (overview.tableSummaries ?? []).map((item) => item.tableName);
        ctx.tableNameCache.value = {
          ...ctx.tableNameCache.value,
          [cacheKey]: tableNames,
        };
        ctx.tableNameLoadedCache.value = {
          ...ctx.tableNameLoadedCache.value,
          [cacheKey]: true,
        };
        ctx.objectNameCache.value = {
          ...ctx.objectNameCache.value,
          [objectCacheKey(connectionId, databaseName, 'tables')]: tableNames,
        };
      }
      ctx.expandConnectionNode(connectionId);
      if (databaseName && databaseName !== '未发现数据库' && isDatabaseNodeExpanded(connectionId, databaseName)) {
        void fetchTableStatsForDatabase(connectionId, databaseName, {
          force: options?.forceTableStats,
        });
      }
    });
  }

  return {
    applyTableStatsSnapshot,
    clearAllTableStatsPollingTimers,
    clearTableStatsPollingTimer,
    collectExpandedDatabaseTargets,
    ensureQueryTableDetailLoaded,
    ensureTableNamesLoaded,
    fetchTableStatsForDatabase,
    isDatabaseNodeExpanded,
    loadOverview,
    loadTableNamesByConnection,
    scheduleTableStatsForExpandedDatabases,
  };
}

interface SchemaBrowserActionHelperContext {
  connections: Ref<ConnectionVO[]>;
  databaseVectorizeStatusMap: Ref<Record<string, RagDatabaseVectorizeStatusVO>>;
  getActiveDatabaseName: (connectionId: number) => string;
  getWorkflowConnectionId: () => number;
  loadConnections: () => Promise<void>;
  loadOverview: (options?: { forceTableStats?: boolean; syncTreeCaches?: boolean }) => Promise<void>;
  moveConnectionGroup: (connectionId: number, targetGroupId: number) => Promise<void>;
  refreshVectorizeStatusForConnection: (connectionId: number) => Promise<void>;
  runSafely: (runner: () => Promise<void>) => Promise<void>;
  vectorizeOverviewData: Ref<RagVectorizeOverviewVO | null>;
  vectorizeOverviewLoading: Ref<boolean>;
  vectorizeOverviewModalOpen: Ref<boolean>;
}

export function createSchemaBrowserActionHelpers(ctx: SchemaBrowserActionHelperContext) {
  async function handleConnectionTreeDrop(info: { dragNode?: { key?: string | number }; node?: { key?: string | number } }) {
    const dragKey = String(info.dragNode?.key || '');
    const targetKey = String(info.node?.key || '');
    const dragMatch = dragKey.match(/^conn-(\d+)$/);
    if (!dragMatch) {
      return;
    }
    const connectionId = Number(dragMatch[1]);
    let targetGroupId = 0;
    const groupMatch = targetKey.match(/^group-(\d+)$/);
    if (groupMatch) {
      targetGroupId = Number(groupMatch[1]);
    } else {
      const emptyGroupMatch = targetKey.match(/^group-(\d+)-empty$/);
      if (emptyGroupMatch) {
        targetGroupId = Number(emptyGroupMatch[1]);
      } else {
        const targetConnMatch = targetKey.match(/^conn-(\d+)$/);
        if (targetConnMatch) {
          const targetConnectionId = Number(targetConnMatch[1]);
          targetGroupId = ctx.connections.value.find((item) => item.id === targetConnectionId)?.groupId ?? 0;
        }
      }
    }
    if (!targetGroupId) {
      return;
    }
    const currentGroupId = ctx.connections.value.find((item) => item.id === connectionId)?.groupId ?? 0;
    if (currentGroupId === targetGroupId) {
      return;
    }
    await ctx.runSafely(async () => {
      await ctx.moveConnectionGroup(connectionId, targetGroupId);
      message.success('连接分组已更新');
      await ctx.loadConnections();
    });
  }

  async function openVectorizeOverview(connectionId: number, databaseName: string) {
    ctx.vectorizeOverviewModalOpen.value = true;
    ctx.vectorizeOverviewLoading.value = true;
    ctx.vectorizeOverviewData.value = null;
    try {
      ctx.vectorizeOverviewData.value = await getApi<RagVectorizeOverviewVO>(
        `/api/rag/vectorize/overview?connectionId=${connectionId}&databaseName=${encodeURIComponent(databaseName)}`,
      );
    } catch (error) {
      const msg = error instanceof Error ? error.message : String(error);
      message.error(msg);
      ctx.vectorizeOverviewModalOpen.value = false;
    } finally {
      ctx.vectorizeOverviewLoading.value = false;
    }
  }

  async function enqueueDatabaseRevectorize(connectionId: number, databaseName: string) {
    await ctx.runSafely(async () => {
      const result = await postApi<RagVectorizeEnqueueVO>('/api/rag/vectorize/enqueue', {
        connectionId,
        databaseName,
      });
      if (result.enqueued) {
        const key = vectorizeStatusCacheKey(connectionId, databaseName);
        ctx.databaseVectorizeStatusMap.value = {
          ...ctx.databaseVectorizeStatusMap.value,
          [key]: {
            databaseName,
            status: 'PENDING',
            message: result.message,
            updatedAt: Date.now(),
          },
        };
      }
      await ctx.refreshVectorizeStatusForConnection(connectionId);
      if (result.enqueued) {
        message.success(`${result.message}（队列数: ${result.queueSize}）`);
        return;
      }
      message.info(`${result.message}（队列数: ${result.queueSize}）`);
    });
  }

  async function vectorizeSingleTable(connectionId: number, databaseName: string, tableName: string) {
    await ctx.runSafely(async () => {
      const result = await postApi<RagVectorizeTableVO>('/api/rag/vectorize/table/manual', {
        connectionId,
        databaseName,
        tableName,
      });
      if (ctx.getWorkflowConnectionId() === connectionId && ctx.getActiveDatabaseName(connectionId) === databaseName) {
        await ctx.loadOverview({forceTableStats: false});
      }
      await ctx.refreshVectorizeStatusForConnection(connectionId);
      message.success(result.message || `表 ${tableName} 向量化完成`);
    });
  }

  async function interruptDatabaseVectorize(connectionId: number, databaseName: string) {
    await ctx.runSafely(async () => {
      const result = await postApi<RagVectorizeInterruptVO>('/api/rag/vectorize/interrupt', {
        connectionId,
        databaseName,
      });
      const key = vectorizeStatusCacheKey(connectionId, databaseName);
      ctx.databaseVectorizeStatusMap.value = {
        ...ctx.databaseVectorizeStatusMap.value,
        [key]: {
          databaseName,
          status: result.status,
          message: result.message,
          updatedAt: result.updatedAt ?? Date.now(),
        },
      };
      await ctx.refreshVectorizeStatusForConnection(connectionId);
      if (result.interrupted) {
        message.success(result.message);
        return;
      }
      message.info(result.message);
    });
  }

  return {
    enqueueDatabaseRevectorize,
    handleConnectionTreeDrop,
    interruptDatabaseVectorize,
    openVectorizeOverview,
    vectorizeSingleTable,
  };
}

interface ConnectionBrowserStateHelperContext {
  activeDatabaseMap: Ref<Record<number, string>>;
  clearBrowserObjectCollections: () => void;
  clearObjectDetail: () => void;
  clearTableStatsPollingTimer: (cacheKey: string) => void;
  connectionRuntimeStatusMap: Ref<Record<number, 'idle' | 'connected' | 'failed'>>;
  currentObjectType: Ref<'tables' | 'views' | 'functions' | 'events' | 'queries' | 'backups'>;
  databaseListCache: Ref<Record<number, string[]>>;
  databaseVectorizeStatusMap: Ref<Record<string, RagDatabaseVectorizeStatusVO>>;
  expandedTreeKeys: Ref<(string | number)[]>;
  getWorkflowConnectionId: () => number;
  kvOverviewCleared: () => void;
  namespaceListCache: Ref<Record<string, string[]>>;
  namespaceListLoadedCache: Ref<Record<string, boolean>>;
  objectNameCache: Ref<Record<string, string[]>>;
  savedQueryCache: Ref<Record<string, unknown[]>>;
  pendingTableDetailLoads: Map<string, Promise<unknown>>;
  pendingTableNameLoads: Map<string, Promise<unknown>>;
  queryTableDetailCache: Ref<Record<string, unknown>>;
  redisBrowseExpandedRowKeys: Ref<string[]>;
  redisBrowserChildLoadingMap: Ref<Record<string, boolean>>;
  redisBrowserContextKey: Ref<string>;
  redisBrowserPageCache: Ref<Record<string, unknown>>;
  redisBrowserRows: Ref<unknown[]>;
  redisExpandedRowKeys: Ref<string[]>;
  redisHierarchyPath: Ref<string>;
  redisSelectedRowKey: Ref<string>;
  schemaOverviewCleared: () => void;
  selectedObjectName: Ref<string>;
  selectedTreeKeys: Ref<(string | number)[]>;
  tableNameCache: Ref<Record<string, string[]>>;
  tableNameLoadedCache: Ref<Record<string, boolean>>;
  tableStatsCache: Ref<Record<string, unknown>>;
  tableStatsLastRequestAt: Ref<Record<string, number>>;
  tableStatsLoadingState: Ref<Record<string, boolean>>;
  tableStatsPollingTimers: Map<string, number>;
}

export function createConnectionBrowserStateHelpers(ctx: ConnectionBrowserStateHelperContext) {
  function setConnectionRuntimeStatus(connectionId: number, status: 'idle' | 'connected' | 'failed') {
    if (!connectionId) {
      return;
    }
    ctx.connectionRuntimeStatusMap.value = {
      ...ctx.connectionRuntimeStatusMap.value,
      [connectionId]: status,
    };
  }

  function invalidateConnectionMetadataCaches(connectionId: number) {
    if (!connectionId) {
      return;
    }
    const nextDatabaseListCache = {...ctx.databaseListCache.value};
    delete nextDatabaseListCache[connectionId];
    ctx.databaseListCache.value = nextDatabaseListCache;

    ctx.namespaceListCache.value = Object.fromEntries(
      Object.entries(ctx.namespaceListCache.value).filter(([key]) => !key.startsWith(`${connectionId}|`)),
    );
    ctx.namespaceListLoadedCache.value = Object.fromEntries(
      Object.entries(ctx.namespaceListLoadedCache.value).filter(([key]) => !key.startsWith(`${connectionId}|`)),
    );

    const nextActiveDatabaseMap = {...ctx.activeDatabaseMap.value};
    delete nextActiveDatabaseMap[connectionId];
    ctx.activeDatabaseMap.value = nextActiveDatabaseMap;

    ctx.tableNameCache.value = Object.fromEntries(
      Object.entries(ctx.tableNameCache.value).filter(([key]) => !key.startsWith(`${connectionId}|`)),
    );
    ctx.tableNameLoadedCache.value = Object.fromEntries(
      Object.entries(ctx.tableNameLoadedCache.value).filter(([key]) => !key.startsWith(`${connectionId}|`)),
    );
    ctx.objectNameCache.value = Object.fromEntries(
      Object.entries(ctx.objectNameCache.value).filter(([key]) => !key.startsWith(`${connectionId}|`)),
    );
    ctx.savedQueryCache.value = Object.fromEntries(
      Object.entries(ctx.savedQueryCache.value).filter(([key]) => !key.startsWith(`${connectionId}|`)),
    );
    ctx.queryTableDetailCache.value = Object.fromEntries(
      Object.entries(ctx.queryTableDetailCache.value).filter(([key]) => !key.startsWith(`${connectionId}|`)),
    );
    ctx.redisBrowserPageCache.value = Object.fromEntries(
      Object.entries(ctx.redisBrowserPageCache.value).filter(([key]) => !key.startsWith(`${connectionId}|`)),
    );
    ctx.redisBrowserChildLoadingMap.value = {};
    ctx.redisBrowserRows.value = [];
    ctx.redisExpandedRowKeys.value = [];
    ctx.redisBrowseExpandedRowKeys.value = [];
    ctx.redisSelectedRowKey.value = '';
    if (ctx.getWorkflowConnectionId() === connectionId) {
      ctx.redisBrowserContextKey.value = '';
    }
    ctx.tableStatsCache.value = Object.fromEntries(
      Object.entries(ctx.tableStatsCache.value).filter(([key]) => !key.startsWith(`${connectionId}|`)),
    );
    ctx.tableStatsLoadingState.value = Object.fromEntries(
      Object.entries(ctx.tableStatsLoadingState.value).filter(([key]) => !key.startsWith(`${connectionId}|`)),
    );
    ctx.tableStatsLastRequestAt.value = Object.fromEntries(
      Object.entries(ctx.tableStatsLastRequestAt.value).filter(([key]) => !key.startsWith(`${connectionId}|`)),
    );
    ctx.databaseVectorizeStatusMap.value = Object.fromEntries(
      Object.entries(ctx.databaseVectorizeStatusMap.value).filter(([key]) => !key.startsWith(`${connectionId}|`)),
    );

    Array.from(ctx.tableStatsPollingTimers.keys()).forEach((key) => {
      if (key.startsWith(`${connectionId}|`)) {
        ctx.clearTableStatsPollingTimer(key);
      }
    });
    Array.from(ctx.pendingTableNameLoads.keys()).forEach((key) => {
      if (key.startsWith(`${connectionId}|`)) {
        ctx.pendingTableNameLoads.delete(key);
      }
    });
    Array.from(ctx.pendingTableDetailLoads.keys()).forEach((key) => {
      if (key.startsWith(`${connectionId}|`)) {
        ctx.pendingTableDetailLoads.delete(key);
      }
    });

    if (ctx.getWorkflowConnectionId() === connectionId) {
      ctx.schemaOverviewCleared();
      ctx.kvOverviewCleared();
      ctx.selectedObjectName.value = '';
      ctx.clearBrowserObjectCollections();
      ctx.clearObjectDetail();
    }
  }

  function collapseConnectionNode(connectionId: number) {
    const prefix = `conn-${connectionId}`;
    ctx.expandedTreeKeys.value = ctx.expandedTreeKeys.value.filter((key) => {
      const normalizedKey = String(key);
      return normalizedKey !== prefix && !normalizedKey.startsWith(`${prefix}-`);
    });
  }

  function resetConnectionRuntimeState(connectionId: number) {
    if (!connectionId) {
      return;
    }
    setConnectionRuntimeStatus(connectionId, 'idle');
    invalidateConnectionMetadataCaches(connectionId);
    collapseConnectionNode(connectionId);
    const childPrefix = `conn-${connectionId}-`;
    if (ctx.selectedTreeKeys.value.some((key) => String(key).startsWith(childPrefix))) {
      ctx.selectedTreeKeys.value = [`conn-${connectionId}`];
    }
    if (ctx.getWorkflowConnectionId() === connectionId) {
      ctx.currentObjectType.value = 'tables';
      ctx.selectedObjectName.value = '';
      ctx.redisHierarchyPath.value = '';
      ctx.clearBrowserObjectCollections();
      ctx.clearObjectDetail();
    }
  }

  return {
    collapseConnectionNode,
    invalidateConnectionMetadataCaches,
    resetConnectionRuntimeState,
    setConnectionRuntimeStatus,
  };
}

interface SchemaBrowserStatusHelperContext {
  activeDatabaseMap: Ref<Record<number, string>>;
  browserObjectNameList: Ref<string[]>;
  browserSavedQueryList: Ref<SavedQueryVO[]>;
  clearTableStatsPollingTimer: (cacheKey: string) => void;
  connections: Ref<ConnectionVO[]>;
  databaseListCache: Ref<Record<number, string[]>>;
  databaseVectorizeStatusMap: Ref<Record<string, RagDatabaseVectorizeStatusVO>>;
  getWorkflowConnectionId: () => number;
  invalidateConnectionMetadataCaches: (connectionId: number) => void;
  isDatabaseContextVisibleForConnection: (connection: ConnectionVO, databaseName: string) => boolean;
  namespaceListCache: Ref<Record<string, string[]>>;
  namespaceListLoadedCache: Ref<Record<string, boolean>>;
  objectNameCache: Ref<Record<string, string[]>>;
  pendingTableDetailLoads: Map<string, Promise<unknown>>;
  pendingTableNameLoads: Map<string, Promise<unknown>>;
  queryTableDetailCache: Ref<Record<string, unknown>>;
  redisBrowserRows: Ref<unknown[]>;
  redisExpandedRowKeys: Ref<string[]>;
  redisSelectedRowKey: Ref<string>;
  schemaOverviewCleared: () => void;
  tableNameCache: Ref<Record<string, string[]>>;
  tableNameLoadedCache: Ref<Record<string, boolean>>;
  tableStatsCache: Ref<Record<string, unknown>>;
  tableStatsLastRequestAt: Ref<Record<string, number>>;
  tableStatsLoadingState: Ref<Record<string, boolean>>;
  visibleDatabasesForConnection: (connection: ConnectionVO) => string[];
}

export function createSchemaBrowserStatusHelpers(ctx: SchemaBrowserStatusHelperContext) {
  function clearBrowserObjectCollections() {
    ctx.browserObjectNameList.value = [];
    ctx.browserSavedQueryList.value = [];
    ctx.redisBrowserRows.value = [];
    ctx.redisExpandedRowKeys.value = [];
    ctx.redisSelectedRowKey.value = '';
  }

  function getActiveDatabaseName(connectionId: number) {
    const selected = (ctx.activeDatabaseMap.value[connectionId] ?? '').trim();
    const connection = ctx.connections.value.find((item) => item.id === connectionId);
    if (!connection) {
      return selected;
    }
    const visibleDatabases = ctx.visibleDatabasesForConnection(connection);
    if (selected && (!visibleDatabases.length || ctx.isDatabaseContextVisibleForConnection(connection, selected))) {
      return selected;
    }
    if (selected && visibleDatabases.length && !ctx.isDatabaseContextVisibleForConnection(connection, selected)) {
      return '';
    }
    const configured = parseConfiguredDatabaseName(connection);
    if (configured && (!visibleDatabases.length || ctx.isDatabaseContextVisibleForConnection(connection, configured))) {
      return configured;
    }
    return '';
  }

  function invalidateDatabaseMetadataCaches(connectionId: number, databaseName: string) {
    if (!connectionId) {
      return;
    }
    const normalizedDatabaseName = (databaseName || '').trim();
    if (!normalizedDatabaseName) {
      ctx.invalidateConnectionMetadataCaches(connectionId);
      return;
    }
    const tableKey = tableCacheKey(connectionId, normalizedDatabaseName);
    const tablePrefix = `${connectionId}|${normalizedDatabaseName}|`;
    const objectPrefix = `${connectionId}|${normalizedDatabaseName}|`;
    const namespaceKey = namespaceCacheKey(connectionId, normalizedDatabaseName);

    const nextTableNameCache = {...ctx.tableNameCache.value};
    delete nextTableNameCache[tableKey];
    ctx.tableNameCache.value = nextTableNameCache;

    const nextTableNameLoadedCache = {...ctx.tableNameLoadedCache.value};
    delete nextTableNameLoadedCache[tableKey];
    ctx.tableNameLoadedCache.value = nextTableNameLoadedCache;

    ctx.objectNameCache.value = Object.fromEntries(
      Object.entries(ctx.objectNameCache.value).filter(([key]) => !key.startsWith(objectPrefix)),
    );
    ctx.queryTableDetailCache.value = Object.fromEntries(
      Object.entries(ctx.queryTableDetailCache.value).filter(([key]) => !key.startsWith(tablePrefix)),
    );

    const nextTableStatsCache = {...ctx.tableStatsCache.value};
    delete nextTableStatsCache[tableKey];
    ctx.tableStatsCache.value = nextTableStatsCache;

    const nextTableStatsLoadingState = {...ctx.tableStatsLoadingState.value};
    delete nextTableStatsLoadingState[tableKey];
    ctx.tableStatsLoadingState.value = nextTableStatsLoadingState;

    const nextTableStatsLastRequestAt = {...ctx.tableStatsLastRequestAt.value};
    delete nextTableStatsLastRequestAt[tableKey];
    ctx.tableStatsLastRequestAt.value = nextTableStatsLastRequestAt;

    const nextDatabaseVectorizeStatusMap = {...ctx.databaseVectorizeStatusMap.value};
    delete nextDatabaseVectorizeStatusMap[vectorizeStatusCacheKey(connectionId, normalizedDatabaseName)];
    ctx.databaseVectorizeStatusMap.value = nextDatabaseVectorizeStatusMap;

    const nextNamespaceListCache = {...ctx.namespaceListCache.value};
    delete nextNamespaceListCache[namespaceKey];
    ctx.namespaceListCache.value = nextNamespaceListCache;

    const nextNamespaceListLoadedCache = {...ctx.namespaceListLoadedCache.value};
    delete nextNamespaceListLoadedCache[namespaceKey];
    ctx.namespaceListLoadedCache.value = nextNamespaceListLoadedCache;

    ctx.clearTableStatsPollingTimer(tableKey);
    ctx.pendingTableNameLoads.delete(tableKey);
    Array.from(ctx.pendingTableDetailLoads.keys()).forEach((key) => {
      if (key.startsWith(tablePrefix)) {
        ctx.pendingTableDetailLoads.delete(key);
      }
    });

    if (ctx.getWorkflowConnectionId() === connectionId && getActiveDatabaseName(connectionId) === normalizedDatabaseName) {
      ctx.schemaOverviewCleared();
    }
  }

  function clearDatabaseTableStatsCache(connectionId: number, databaseName: string) {
    if (!connectionId) {
      return;
    }
    const normalizedDatabaseName = (databaseName || '').trim();
    if (!normalizedDatabaseName) {
      return;
    }
    const cacheKey = tableCacheKey(connectionId, normalizedDatabaseName);

    const nextTableStatsCache = {...ctx.tableStatsCache.value};
    delete nextTableStatsCache[cacheKey];
    ctx.tableStatsCache.value = nextTableStatsCache;

    const nextTableStatsLoadingState = {...ctx.tableStatsLoadingState.value};
    delete nextTableStatsLoadingState[cacheKey];
    ctx.tableStatsLoadingState.value = nextTableStatsLoadingState;

    const nextTableStatsLastRequestAt = {...ctx.tableStatsLastRequestAt.value};
    delete nextTableStatsLastRequestAt[cacheKey];
    ctx.tableStatsLastRequestAt.value = nextTableStatsLastRequestAt;

    ctx.clearTableStatsPollingTimer(cacheKey);
  }

  function invalidateDatabaseListCache(connectionId: number) {
    if (!connectionId) {
      return;
    }
    const nextDatabaseListCache = {...ctx.databaseListCache.value};
    delete nextDatabaseListCache[connectionId];
    ctx.databaseListCache.value = nextDatabaseListCache;
    ctx.namespaceListCache.value = Object.fromEntries(
      Object.entries(ctx.namespaceListCache.value).filter(([key]) => !key.startsWith(`${connectionId}|`)),
    );
    ctx.namespaceListLoadedCache.value = Object.fromEntries(
      Object.entries(ctx.namespaceListLoadedCache.value).filter(([key]) => !key.startsWith(`${connectionId}|`)),
    );
    ctx.databaseVectorizeStatusMap.value = Object.fromEntries(
      Object.entries(ctx.databaseVectorizeStatusMap.value).filter(([key]) => !key.startsWith(`${connectionId}|`)),
    );
  }

  function handleDatabaseRenamedLocally(connectionId: number, sourceDatabaseName: string, targetDatabaseName: string) {
    invalidateDatabaseMetadataCaches(connectionId, sourceDatabaseName);
    invalidateDatabaseMetadataCaches(connectionId, targetDatabaseName);
    invalidateDatabaseListCache(connectionId);
    if ((ctx.activeDatabaseMap.value[connectionId] || '').trim() === (sourceDatabaseName || '').trim()) {
      ctx.activeDatabaseMap.value = {
        ...ctx.activeDatabaseMap.value,
        [connectionId]: (targetDatabaseName || '').trim(),
      };
    }
  }

  function getDatabaseVectorizeStatus(connectionId: number, databaseName: string) {
    if (!databaseName || databaseName === '未发现数据库') {
      return 'NOT_VECTORIZED';
    }
    const key = vectorizeStatusCacheKey(connectionId, databaseName);
    return ctx.databaseVectorizeStatusMap.value[key]?.status || 'NOT_VECTORIZED';
  }

  function getDatabaseVectorizeStatusRecord(connectionId: number, databaseName: string) {
    if (!databaseName || databaseName === '未发现数据库') {
      return null;
    }
    const key = vectorizeStatusCacheKey(connectionId, databaseName);
    return ctx.databaseVectorizeStatusMap.value[key] ?? null;
  }

  function canUseErFeature(connectionId: number, databaseName: string) {
    return getDatabaseVectorizeStatus(connectionId, databaseName) === 'SUCCESS';
  }

  function resolveErUnavailableReason(connectionId: number, databaseName: string) {
    if (!connectionId) {
      return '请先选择连接';
    }
    const normalizedDatabaseName = (databaseName || '').trim();
    if (!normalizedDatabaseName || normalizedDatabaseName === '未发现数据库') {
      return '请先选择当前数据库';
    }
    if (canUseErFeature(connectionId, normalizedDatabaseName)) {
      return '';
    }
    const status = getDatabaseVectorizeStatus(connectionId, normalizedDatabaseName);
    if (status === 'PENDING' || status === 'RUNNING') {
      return `当前库 ${normalizedDatabaseName} 正在向量化，暂不可使用智能ER图`;
    }
    if (status === 'FAILED') {
      return `当前库 ${normalizedDatabaseName} 向量化失败，请先重新向量化后再使用智能ER图`;
    }
    return `当前库 ${normalizedDatabaseName} 未向量化，智能ER图不可用，请先执行“重新向量化”`;
  }

  function isDatabaseVectorizing(connectionId: number, databaseName: string) {
    const status = getDatabaseVectorizeStatus(connectionId, databaseName);
    return status === 'PENDING' || status === 'RUNNING';
  }

  function databaseStatusLabel(status: string) {
    if (status === 'PENDING') {
      return '排队中';
    }
    if (status === 'RUNNING') {
      return '向量化中';
    }
    if (status === 'SUCCESS') {
      return '已向量化';
    }
    if (status === 'FAILED') {
      return '失败';
    }
    return '未向量化';
  }

  function databaseStatusClass(status: string) {
    if (status === 'PENDING') {
      return 'is-pending';
    }
    if (status === 'RUNNING') {
      return 'is-running';
    }
    if (status === 'SUCCESS') {
      return 'is-success';
    }
    if (status === 'FAILED') {
      return 'is-failed';
    }
    return 'is-none';
  }

  function databaseStatusIcon(status: string) {
    if (status === 'PENDING') {
      return ClockCircleOutlined;
    }
    if (status === 'RUNNING') {
      return LoadingOutlined;
    }
    if (status === 'SUCCESS') {
      return CheckCircleOutlined;
    }
    if (status === 'FAILED') {
      return CloseCircleOutlined;
    }
    return MinusCircleOutlined;
  }

  return {
    clearBrowserObjectCollections,
    invalidateDatabaseMetadataCaches,
    clearDatabaseTableStatsCache,
    invalidateDatabaseListCache,
    handleDatabaseRenamedLocally,
    getDatabaseVectorizeStatus,
    getDatabaseVectorizeStatusRecord,
    canUseErFeature,
    resolveErUnavailableReason,
    isDatabaseVectorizing,
    databaseStatusLabel,
    databaseStatusClass,
    databaseStatusIcon,
    getActiveDatabaseName,
  };
}

interface SchemaTreeNodeHelperContext {
  connections: Ref<ConnectionVO[]>;
  getActiveDatabaseName: (connectionId: number) => string;
  getDatabaseVectorizeStatus: (connectionId: number, databaseName: string) => string;
  isKvDbType: (dbType: string) => boolean;
  namespaceListCache: Ref<Record<string, string[]>>;
  objectNameCache: Ref<Record<string, string[]>>;
  primaryObjectLabelByDbType: (dbType: string) => string;
  requiresDatabaseLayer: (connection: ConnectionVO) => boolean;
  savedQueriesByDatabase: (connectionId: number, databaseName?: string) => SavedQueryVO[];
  tableNameCache: Ref<Record<string, string[]>>;
  visibleDatabasesForConnection: (connection: ConnectionVO) => string[];
}

export function createSchemaTreeNodeHelpers(ctx: SchemaTreeNodeHelperContext) {
  function buildConnectionNodeTitle(conn: ConnectionVO) {
    return conn.name?.trim() || '未命名连接';
  }

  function getCategoryChildren(connectionId: number, databaseName: string, category: string) {
    const connection = ctx.connections.value.find((item) => item.id === connectionId);
    const dbType = connection?.dbType || '';
    if (category === 'queries') {
      return ctx.savedQueriesByDatabase(connectionId, databaseName).map((item) => ({
        key: buildObjectNodeKey(connectionId, databaseName, category, item.title),
        title: item.title,
        nodeType: category,
        connectionId,
        databaseName,
        objectType: category,
        objectName: item.title,
      }));
    }
    if (category === 'tables' && ctx.isKvDbType(dbType)) {
      return [];
    }
    const names = category === 'tables'
      ? ctx.tableNameCache.value[tableCacheKey(connectionId, databaseName)] ?? []
      : ctx.objectNameCache.value[objectCacheKey(connectionId, databaseName, category)] ?? [];
    return names.map((name) => ({
      key: buildObjectNodeKey(connectionId, databaseName, category, name),
      title: name,
      nodeType: category,
      connectionId,
      databaseName,
      objectType: category,
      objectName: name,
    }));
  }

  function buildCategoryChildren(connectionId: number, databaseName: string) {
    const connection = ctx.connections.value.find((item) => item.id === connectionId);
    const dbType = connection?.dbType || '';
    const primaryLabel = ctx.primaryObjectLabelByDbType(dbType);
    const categoryNodes = ctx.isKvDbType(dbType)
      ? [
        {suffix: 'tables', title: primaryLabel, nodeType: 'tables'},
        {suffix: 'queries', title: '查询', nodeType: 'queries'},
      ]
      : [
        {suffix: 'tables', title: '表', nodeType: 'tables'},
        {suffix: 'views', title: '视图', nodeType: 'views'},
        {suffix: 'functions', title: '函数', nodeType: 'functions'},
        {suffix: 'queries', title: '查询', nodeType: 'queries'},
      ];
    return categoryNodes.map((category) => ({
      key: buildCategoryNodeKey(connectionId, databaseName, category.suffix),
      title: category.title,
      nodeType: category.nodeType,
      connectionId,
      databaseName,
      selectable: true,
      children: getCategoryChildren(connectionId, databaseName, category.suffix),
    }));
  }

  function buildConnectionNode(conn: ConnectionVO) {
    const title = buildConnectionNodeTitle(conn);
    if (ctx.requiresDatabaseLayer(conn)) {
      const databases = ctx.visibleDatabasesForConnection(conn);
      const databaseNodes = (databases.length ? databases : ['未发现数据库']).map((databaseName) => {
        if (supportsSchemaLayer(conn.dbType) && databaseName !== '未发现数据库') {
          const namespaces = ctx.namespaceListCache.value[namespaceCacheKey(conn.id, databaseName)] ?? [];
          return {
            key: buildDatabaseRootNodeKey(conn.id, databaseName),
            title: databaseName,
            nodeType: 'database-root',
            connectionId: conn.id,
            databaseName,
            vectorizeStatus: ctx.getDatabaseVectorizeStatus(conn.id, databaseName),
            selectable: true,
            isLeaf: false,
            children: namespaces.map((namespaceName) => {
              const schemaContext = buildSchemaContext(databaseName, namespaceName);
              return {
                key: buildDatabaseNodeKey(conn.id, schemaContext),
                title: namespaceName,
                nodeType: 'database',
                connectionId: conn.id,
                databaseName: schemaContext,
                namespaceName,
                vectorizeStatus: ctx.getDatabaseVectorizeStatus(conn.id, databaseName),
                selectable: true,
                children: buildCategoryChildren(conn.id, schemaContext),
              };
            }),
          };
        }
        return {
          key: buildDatabaseNodeKey(conn.id, databaseName),
          title: databaseName,
          nodeType: 'database',
          connectionId: conn.id,
          databaseName,
          vectorizeStatus: ctx.getDatabaseVectorizeStatus(conn.id, databaseName),
          selectable: databaseName !== '未发现数据库',
          children: buildCategoryChildren(conn.id, databaseName),
        };
      });
      return {
        key: `conn-${conn.id}`,
        title,
        nodeType: 'connection',
        connectionId: conn.id,
        groupId: conn.groupId ?? 0,
        env: conn.env,
        dbType: conn.dbType,
        connectionName: conn.name,
        children: databaseNodes,
      };
    }

    const configuredDbName = ctx.getActiveDatabaseName(conn.id);
    return {
      key: `conn-${conn.id}`,
      title,
      nodeType: 'connection',
      connectionId: conn.id,
      groupId: conn.groupId ?? 0,
      env: conn.env,
      dbType: conn.dbType,
      connectionName: conn.name,
      children: buildCategoryChildren(conn.id, configuredDbName),
    };
  }

  return {
    buildConnectionNode,
    buildCategoryChildren,
    getCategoryChildren,
  };
}

interface SchemaObjectBrowserHelperContext {
  browserNavMode: Ref<string>;
  browserObjectNameList: Ref<string[]>;
  browserSavedQueryList: Ref<SavedQueryVO[]>;
  clearBrowserObjectCollections: () => void;
  clearDatabaseTableStatsCache: (connectionId: number, databaseName: string) => void;
  currentObjectType: Ref<'tables' | 'views' | 'functions' | 'events' | 'queries' | 'backups'>;
  ensureConnection: () => void;
  getActiveDatabaseName: (connectionId: number) => string;
  getWorkflowConnectionId: () => number;
  invalidateConnectionMetadataCaches: (connectionId: number) => void;
  invalidateDatabaseListCache: (connectionId: number) => void;
  invalidateDatabaseMetadataCaches: (connectionId: number, databaseName: string) => void;
  invalidateRedisBrowserCache: (connectionId: number, databaseName?: string) => void;
  isActiveConnectionRedis: () => boolean;
  loadObjectDetail: (
    connectionId: number,
    databaseName: string,
    objectType: 'tables' | 'views' | 'functions' | 'events' | 'queries' | 'backups',
    objectName: string,
  ) => Promise<void>;
  loadOverview: (options?: { forceTableStats?: boolean; syncTreeCaches?: boolean }) => Promise<void>;
  loadSavedQueries: (connectionId: number, databaseName: string, options?: { syncCache?: boolean }) => Promise<SavedQueryVO[]>;
  objectNameCache: Ref<Record<string, string[]>>;
  prepareConnectionTreeData: (connectionId: number, options?: { force?: boolean }) => Promise<void>;
  runSafely: (runner: () => Promise<void>) => Promise<void>;
  selectedObjectName: Ref<string>;
  toObjectType: (value: string) => 'tables' | 'views' | 'functions' | 'events' | 'queries' | 'backups';
  expandCategoryNode: (
    connectionId: number,
    databaseName: string,
    category: 'tables' | 'views' | 'functions' | 'events' | 'queries' | 'backups',
  ) => void;
}

export function createSchemaObjectBrowserHelpers(ctx: SchemaObjectBrowserHelperContext) {
  async function loadObjectNames(
    connectionId: number,
    databaseName: string,
    objectType: string,
    options?: { syncCache?: boolean },
  ) {
    const query = `/api/schema/objectNames?connectionId=${connectionId}&databaseName=${encodeURIComponent(databaseName)}&objectType=${encodeURIComponent(objectType)}`;
    const names = await getApi<string[]>(query);
    if (options?.syncCache !== false) {
      ctx.objectNameCache.value = {
        ...ctx.objectNameCache.value,
        [objectCacheKey(connectionId, databaseName, objectType)]: names,
      };
    }
    return names;
  }

  async function refreshCurrentObjects(options?: { force?: boolean }) {
    ctx.ensureConnection();
    await ctx.runSafely(async () => {
      const connectionId = ctx.getWorkflowConnectionId();
      const databaseName = ctx.getActiveDatabaseName(connectionId);
      if (options?.force) {
        ctx.invalidateDatabaseListCache(connectionId);
        if (databaseName && databaseName !== '未发现数据库') {
          ctx.invalidateDatabaseMetadataCaches(connectionId, databaseName);
          if (ctx.isActiveConnectionRedis()) {
            ctx.invalidateRedisBrowserCache(connectionId, databaseName);
          }
        } else {
          ctx.invalidateConnectionMetadataCaches(connectionId);
        }
        await ctx.prepareConnectionTreeData(connectionId, {force: true});
      }
      if (ctx.currentObjectType.value === 'tables') {
        ctx.clearBrowserObjectCollections();
        await ctx.loadOverview({forceTableStats: options?.force});
      } else if (ctx.currentObjectType.value === 'queries') {
        ctx.browserSavedQueryList.value = await ctx.loadSavedQueries(connectionId, databaseName);
        ctx.browserObjectNameList.value = [];
      } else {
        ctx.browserObjectNameList.value = await loadObjectNames(connectionId, databaseName, ctx.currentObjectType.value);
        ctx.browserSavedQueryList.value = [];
      }
      if (ctx.selectedObjectName.value && ctx.currentObjectType.value !== 'queries') {
        await ctx.loadObjectDetail(connectionId, databaseName, ctx.currentObjectType.value, ctx.selectedObjectName.value);
      }
    });
  }

  async function refreshCurrentPageObjects(options?: { force?: boolean }) {
    ctx.ensureConnection();
    await ctx.runSafely(async () => {
      const connectionId = ctx.getWorkflowConnectionId();
      const databaseName = ctx.getActiveDatabaseName(connectionId);
      if (ctx.currentObjectType.value === 'tables') {
        ctx.clearBrowserObjectCollections();
        if (options?.force) {
          ctx.clearDatabaseTableStatsCache(connectionId, databaseName);
          if (ctx.isActiveConnectionRedis()) {
            ctx.invalidateRedisBrowserCache(connectionId, databaseName);
          }
        }
        await ctx.loadOverview({forceTableStats: !!options?.force, syncTreeCaches: false});
      } else if (ctx.currentObjectType.value === 'queries') {
        ctx.browserSavedQueryList.value = await ctx.loadSavedQueries(connectionId, databaseName, {syncCache: false});
        ctx.browserObjectNameList.value = [];
      } else {
        ctx.browserObjectNameList.value = await loadObjectNames(connectionId, databaseName, ctx.currentObjectType.value, {syncCache: false});
        ctx.browserSavedQueryList.value = [];
      }
      if (ctx.selectedObjectName.value && ctx.currentObjectType.value !== 'queries') {
        await ctx.loadObjectDetail(connectionId, databaseName, ctx.currentObjectType.value, ctx.selectedObjectName.value);
      }
    });
  }

  async function loadCategoryObjects(connectionId: number, databaseName: string, category: string) {
    ctx.currentObjectType.value = ctx.toObjectType(category);
    ctx.browserNavMode.value = 'connections';
    if (ctx.currentObjectType.value === 'tables') {
      ctx.clearBrowserObjectCollections();
      await ctx.loadOverview();
      return;
    }
    if (ctx.currentObjectType.value === 'queries') {
      ctx.browserSavedQueryList.value = await ctx.loadSavedQueries(connectionId, databaseName);
      ctx.browserObjectNameList.value = [];
      ctx.expandCategoryNode(connectionId, databaseName, ctx.currentObjectType.value);
      return;
    }
    await ctx.runSafely(async () => {
      ctx.browserObjectNameList.value = await loadObjectNames(connectionId, databaseName, ctx.currentObjectType.value);
      ctx.browserSavedQueryList.value = [];
      ctx.expandCategoryNode(connectionId, databaseName, ctx.currentObjectType.value);
    });
  }

  return {
    loadCategoryObjects,
    loadObjectNames,
    refreshCurrentObjects,
    refreshCurrentPageObjects,
  };
}

interface SchemaObjectDetailHelperContext {
  buildObjectQuerySql: (objectName: string, dbType: string) => string;
  connections: Ref<ConnectionVO[]>;
  isKvConnectionId: (connectionId: number) => boolean;
  kvObjectDetail: Ref<KvObjectDetailVO | null>;
  kvObjectDetailLoading: Ref<boolean>;
  objectDefinitionDetail: Ref<SchemaObjectDefinitionVO | null>;
  objectDefinitionDetailLoading: Ref<boolean>;
  openSavedQueryTabByTitle: (connectionId: number, databaseName: string, title: string) => Promise<unknown>;
  queryDbTypeByConnectionId: (connectionId: number) => string;
  redisSelectedRowKey: Ref<string>;
  selectedObjectName: Ref<string>;
  tableDetail: Ref<TableDetailVO | null>;
  tableDetailLoading: Ref<boolean>;
  workflow: {
    prompt: string;
    sqlText: string;
  };
}

export function createSchemaObjectDetailHelpers(ctx: SchemaObjectDetailHelperContext) {
  function clearObjectDetail() {
    ctx.tableDetail.value = null;
    ctx.tableDetailLoading.value = false;
    ctx.kvObjectDetail.value = null;
    ctx.kvObjectDetailLoading.value = false;
    ctx.objectDefinitionDetail.value = null;
    ctx.objectDefinitionDetailLoading.value = false;
  }

  async function loadObjectDetail(
    connectionId: number,
    databaseName: string,
    objectType: ObjectRow['objectType'],
    objectName: string,
  ) {
    clearObjectDetail();

    if (ctx.isKvConnectionId(connectionId) && objectType === 'tables') {
      ctx.kvObjectDetailLoading.value = true;
      try {
        const query = databaseName
          ? `/api/kv/object/detail?connectionId=${connectionId}&databaseName=${encodeURIComponent(databaseName)}&objectName=${encodeURIComponent(objectName)}`
          : `/api/kv/object/detail?connectionId=${connectionId}&objectName=${encodeURIComponent(objectName)}`;
        ctx.kvObjectDetail.value = await getApi<KvObjectDetailVO>(query);
      } finally {
        ctx.kvObjectDetailLoading.value = false;
      }
      return;
    }

    if (objectType === 'tables') {
      ctx.tableDetailLoading.value = true;
      try {
        const query = databaseName
          ? `/api/schema/tableDetail?connectionId=${connectionId}&databaseName=${encodeURIComponent(databaseName)}&tableName=${encodeURIComponent(objectName)}`
          : `/api/schema/tableDetail?connectionId=${connectionId}&tableName=${encodeURIComponent(objectName)}`;
        ctx.tableDetail.value = await getApi<TableDetailVO>(query);
      } finally {
        ctx.tableDetailLoading.value = false;
      }
      return;
    }

    if (objectType === 'views' || objectType === 'functions') {
      ctx.objectDefinitionDetailLoading.value = true;
      try {
        ctx.objectDefinitionDetail.value = await getApi<SchemaObjectDefinitionVO>(
          `/api/schema/object/definition?connectionId=${connectionId}&databaseName=${encodeURIComponent(databaseName)}&objectType=${encodeURIComponent(objectType)}&objectName=${encodeURIComponent(objectName)}`,
        );
      } finally {
        ctx.objectDefinitionDetailLoading.value = false;
      }
    }
  }

  async function selectObject(
    connectionId: number,
    databaseName: string,
    objectType: ObjectRow['objectType'],
    objectName: string,
  ) {
    ctx.selectedObjectName.value = objectName;
    if (ctx.isKvConnectionId(connectionId) && ctx.connections.value.find((item) => item.id === connectionId)?.dbType === 'REDIS') {
      ctx.redisSelectedRowKey.value = `key:${objectName}`;
    }
    if (objectType === 'queries') {
      await ctx.openSavedQueryTabByTitle(connectionId, databaseName, objectName);
      return;
    }
    ctx.workflow.prompt = ctx.isKvConnectionId(connectionId) ? `查询 ${objectName}` : `查询 ${objectName} 最近数据`;
    if ((objectType === 'tables' || objectType === 'views') && !ctx.isKvConnectionId(connectionId)) {
      ctx.workflow.sqlText = ctx.buildObjectQuerySql(objectName, ctx.queryDbTypeByConnectionId(connectionId));
    }
    await loadObjectDetail(connectionId, databaseName, objectType, objectName);
    if (ctx.isKvConnectionId(connectionId) && objectType === 'tables') {
      ctx.workflow.sqlText = ctx.kvObjectDetail.value?.queryTemplate || '';
    }
  }

  return {
    clearObjectDetail,
    loadObjectDetail,
    selectObject,
  };
}

export function buildDatabaseNodeKey(connectionId: number, databaseName: string) {
  return `conn-${connectionId}-db-${encodeURIComponent(databaseName)}`;
}

export function buildDatabaseRootNodeKey(connectionId: number, databaseName: string) {
  return `conn-${connectionId}-dbroot-${encodeURIComponent(databaseName)}`;
}

export function buildCategoryNodeKey(connectionId: number, databaseName: string, category: string) {
  return `conn-${connectionId}-db-${encodeURIComponent(databaseName)}-category-${category}`;
}

export function buildObjectNodeKey(connectionId: number, databaseName: string, objectType: string, objectName: string) {
  return `conn-${connectionId}-db-${encodeURIComponent(databaseName)}-obj-${objectType}-${encodeURIComponent(objectName)}`;
}

interface SchemaTreeHelperContext {
  buildGetErrorMessage: (error: unknown) => string;
  connections: Ref<ConnectionVO[]>;
  currentObjectType: Ref<'tables' | 'views' | 'functions' | 'events' | 'queries' | 'backups'>;
  ensureTableNamesLoaded: (connectionId: number, databaseName: string) => Promise<string[]>;
  expandedTreeKeys: Ref<(string | number)[]>;
  getActiveDatabaseName: (connectionId: number) => string;
  isKvConnectionId: (connectionId: number) => boolean;
  loadNamespaceList: (connectionId: number, databaseName: string, options?: { force?: boolean }) => Promise<string[]>;
  loadObjectNames: (connectionId: number, databaseName: string, objectType: string, options?: { syncCache?: boolean }) => Promise<string[]>;
  loadSavedQueries: (connectionId: number, databaseName: string, options?: { syncCache?: boolean }) => Promise<SavedQueryVO[]>;
  prepareConnectionTreeData: (connectionId: number, options?: { force?: boolean }) => Promise<void>;
  requiresDatabaseLayer: (connection: ConnectionVO) => boolean;
  scheduleTableStatsForExpandedDatabases: (keys: string[]) => void;
  supportsSchemaLayerByDbType: (dbType: string) => boolean;
  parseSchemaContextByDbType: (dbType: string, databaseName: string) => { databaseName: string; namespaceName: string };
  toObjectType: (value: string) => 'tables' | 'views' | 'functions' | 'events' | 'queries' | 'backups';
  collapseConnectionNode: (connectionId: number) => void;
}

export function createSchemaTreeHelpers(ctx: SchemaTreeHelperContext) {
  function expandCategoryNode(connectionId: number, databaseName: string, category: string) {
    const keys = new Set(ctx.expandedTreeKeys.value);
    keys.add(buildCategoryNodeKey(connectionId, databaseName, category));
    ctx.expandedTreeKeys.value = Array.from(keys);
  }

  function expandConnectionNode(connectionId: number) {
    const connection = ctx.connections.value.find((item) => item.id === connectionId);
    if (!connection) {
      return;
    }
    const keys = new Set(ctx.expandedTreeKeys.value);
    keys.add(`conn-${connectionId}`);
    const activeDb = ctx.getActiveDatabaseName(connectionId);
    const activeCategory = ctx.currentObjectType.value || 'tables';
    if (ctx.requiresDatabaseLayer(connection)) {
      if (activeDb) {
        if (ctx.supportsSchemaLayerByDbType(connection.dbType)) {
          const schemaContext = ctx.parseSchemaContextByDbType(connection.dbType, activeDb);
          if (schemaContext.databaseName) {
            keys.add(buildDatabaseRootNodeKey(connectionId, schemaContext.databaseName));
          }
          if (schemaContext.namespaceName) {
            keys.add(buildDatabaseNodeKey(connectionId, activeDb));
            keys.add(buildCategoryNodeKey(connectionId, activeDb, activeCategory));
          }
        } else {
          keys.add(buildDatabaseNodeKey(connectionId, activeDb));
          keys.add(buildCategoryNodeKey(connectionId, activeDb, activeCategory));
        }
      }
    } else {
      keys.add(buildCategoryNodeKey(connectionId, activeDb, activeCategory));
    }
    ctx.expandedTreeKeys.value = Array.from(keys);
  }

  async function ensureConnectionTreeExpanded(connectionId: number, options?: { showError?: boolean }) {
    try {
      await ctx.prepareConnectionTreeData(connectionId);
      expandConnectionNode(connectionId);
      ctx.scheduleTableStatsForExpandedDatabases(ctx.expandedTreeKeys.value.map((item) => String(item)));
      return true;
    } catch (error) {
      ctx.collapseConnectionNode(connectionId);
      if (options?.showError !== false) {
        message.error(ctx.buildGetErrorMessage(error));
      }
      return false;
    }
  }

  async function loadTreeChildrenByKey(nodeKey: string) {
    const groupMatch = nodeKey.match(/^group-(\d+)$/);
    if (groupMatch) {
      return;
    }
    const connectionMatch = nodeKey.match(/^conn-(\d+)$/);
    if (connectionMatch) {
      await ctx.prepareConnectionTreeData(Number(connectionMatch[1]));
      return;
    }

    const databaseRootMatch = nodeKey.match(/^conn-(\d+)-dbroot-(.+)$/);
    if (databaseRootMatch) {
      const connectionId = Number(databaseRootMatch[1]);
      const databaseName = decodeURIComponent(databaseRootMatch[2] || '').trim();
      if (!connectionId || !databaseName || databaseName === '未发现数据库') {
        return;
      }
      await ctx.loadNamespaceList(connectionId, databaseName);
      return;
    }

    const databaseMatch = nodeKey.match(/^conn-(\d+)-db-(.+)$/);
    if (databaseMatch && !nodeKey.includes('-category-') && !nodeKey.includes('-obj-')) {
      const connectionId = Number(databaseMatch[1]);
      const databaseName = decodeURIComponent(databaseMatch[2] || '').trim();
      if (!connectionId || !databaseName || databaseName === '未发现数据库') {
        return;
      }
      if (!ctx.isKvConnectionId(connectionId)) {
        await ctx.ensureTableNamesLoaded(connectionId, databaseName);
      }
      return;
    }

    const categoryMatch = nodeKey.match(/^conn-(\d+)-db-(.+?)-category-([a-z]+)$/);
    if (!categoryMatch) {
      return;
    }
    const connectionId = Number(categoryMatch[1]);
    const databaseName = decodeURIComponent(categoryMatch[2] || '').trim();
    const category = ctx.toObjectType(categoryMatch[3] || '');
    if (!connectionId || !databaseName || databaseName === '未发现数据库') {
      return;
    }
    if (category === 'tables') {
      await ctx.ensureTableNamesLoaded(connectionId, databaseName);
      return;
    }
    if (category === 'queries') {
      await ctx.loadSavedQueries(connectionId, databaseName);
      return;
    }
    await ctx.loadObjectNames(connectionId, databaseName, category);
  }

  async function handleTreeExpand(keys: (string | number)[]) {
    const previousExpanded = new Set(ctx.expandedTreeKeys.value.map((item) => String(item)));
    const normalizedKeys = keys.map((item) => String(item));
    const newExpandedKeys = normalizedKeys.filter((item) => !previousExpanded.has(item));
    const nextExpandedKeys = normalizedKeys.filter((item) => previousExpanded.has(item));
    await Promise.all(newExpandedKeys.map(async (nodeKey) => {
      try {
        await loadTreeChildrenByKey(nodeKey);
        nextExpandedKeys.push(nodeKey);
      } catch (error) {
        if (nodeKey.startsWith('conn-')) {
          message.error(ctx.buildGetErrorMessage(error));
        }
      }
    }));
    ctx.expandedTreeKeys.value = nextExpandedKeys;
    ctx.scheduleTableStatsForExpandedDatabases(nextExpandedKeys);
  }

  return {
    ensureConnectionTreeExpanded,
    expandCategoryNode,
    expandConnectionNode,
    handleTreeExpand,
    loadTreeChildrenByKey,
  };
}

interface SchemaTreeSelectionHelperContext {
  activeWorkbenchTab: Ref<string>;
  browserNavMode: Ref<string>;
  browserSavedQueryList: Ref<SavedQueryVO[]>;
  browserObjectNameList: Ref<string[]>;
  browserTabKey: string;
  clearBrowserObjectCollections: () => void;
  clearObjectDetail: () => void;
  closeContextMenu: () => void;
  connections: Ref<ConnectionVO[]>;
  currentObjectType: Ref<'tables' | 'views' | 'functions' | 'events' | 'queries' | 'backups'>;
  ensureConnectionTreeExpanded: (connectionId: number, options?: { showError?: boolean }) => Promise<boolean>;
  expandConnectionNode: (connectionId: number) => void;
  getActiveDatabaseName: (connectionId: number) => string;
  loadCategoryObjects: (connectionId: number, databaseName: string, category: string) => Promise<void>;
  loadNamespaceList: (connectionId: number, databaseName: string, options?: { force?: boolean }) => Promise<string[]>;
  loadObjectNames: (connectionId: number, databaseName: string, objectType: string, options?: { syncCache?: boolean }) => Promise<string[]>;
  loadOverview: (options?: { forceTableStats?: boolean; syncTreeCaches?: boolean }) => Promise<void>;
  loadSavedQueries: (connectionId: number, databaseName: string, options?: { syncCache?: boolean }) => Promise<SavedQueryVO[]>;
  loadTreeChildrenByKey: (nodeKey: string) => Promise<void>;
  objectNameCache: Ref<Record<string, string[]>>;
  parseSchemaContextByDbType: (dbType: string, databaseName: string) => { databaseName: string; namespaceName: string };
  requiresDatabaseLayer: (connection: ConnectionVO) => boolean;
  runSafely: (runner: () => Promise<void>) => Promise<void>;
  savedQueriesByDatabase: (connectionId: number, databaseName: string) => SavedQueryVO[];
  schemaOverviewCleared: () => void;
  kvOverviewCleared: () => void;
  selectObject: (
    connectionId: number,
    databaseName: string,
    objectType: 'tables' | 'views' | 'functions' | 'events' | 'queries' | 'backups',
    objectName: string,
  ) => Promise<void>;
  selectedTreeKeys: Ref<(string | number)[]>;
  selectedObjectName: Ref<string>;
  supportsSchemaLayerByDbType: (dbType: string) => boolean;
  toObjectType: (value: string) => 'tables' | 'views' | 'functions' | 'events' | 'queries' | 'backups';
  workflow: { connectionId: number };
  activeDatabaseMap: Ref<Record<number, string>>;
}

export function createSchemaTreeSelectionHelpers(ctx: SchemaTreeSelectionHelperContext) {
  async function handleTreeSelect(keys: (string | number)[]) {
    if (!keys.length) {
      return;
    }
    ctx.closeContextMenu();
    ctx.browserNavMode.value = 'connections';
    const value = String(keys[0]);
    ctx.selectedTreeKeys.value = [value];
    ctx.activeWorkbenchTab.value = ctx.browserTabKey;
    try {
      await ctx.loadTreeChildrenByKey(value);
    } catch {
      // 点击节点时若预加载失败，继续走原有选择逻辑，由后续请求兜底。
    }

    const connectionMatch = value.match(/^conn-(\d+)$/);
    const groupMatch = value.match(/^group-(\d+)$/);
    if (groupMatch) {
      ctx.selectedObjectName.value = '';
      ctx.clearBrowserObjectCollections();
      ctx.clearObjectDetail();
      return;
    }
    if (connectionMatch) {
      const connectionId = Number(connectionMatch[1]);
      ctx.workflow.connectionId = connectionId;
      ctx.currentObjectType.value = 'tables';
      ctx.selectedObjectName.value = '';
      ctx.clearBrowserObjectCollections();
      ctx.clearObjectDetail();
      const expanded = await ctx.ensureConnectionTreeExpanded(connectionId);
      if (!expanded) {
        ctx.schemaOverviewCleared();
        ctx.kvOverviewCleared();
        return;
      }
      const current = ctx.connections.value.find((item) => item.id === connectionId);
      const activeDatabaseName = ctx.getActiveDatabaseName(connectionId);
      const activeSchemaContext = current ? ctx.parseSchemaContextByDbType(current.dbType, activeDatabaseName) : null;
      if (current && (!ctx.requiresDatabaseLayer(current) || (
        activeDatabaseName && (!ctx.supportsSchemaLayerByDbType(current.dbType) || activeSchemaContext?.namespaceName)
      ))) {
        await ctx.loadOverview();
      } else {
        ctx.schemaOverviewCleared();
      }
      return;
    }

    const databaseRootMatch = value.match(/^conn-(\d+)-dbroot-(.+)$/);
    if (databaseRootMatch) {
      const connectionId = Number(databaseRootMatch[1]);
      const databaseName = decodeURIComponent(databaseRootMatch[2]);
      ctx.workflow.connectionId = connectionId;
      ctx.activeDatabaseMap.value = {
        ...ctx.activeDatabaseMap.value,
        [connectionId]: databaseName,
      };
      ctx.currentObjectType.value = 'tables';
      ctx.selectedObjectName.value = '';
      ctx.clearBrowserObjectCollections();
      ctx.clearObjectDetail();
      ctx.expandConnectionNode(connectionId);
      await ctx.loadNamespaceList(connectionId, databaseName);
      return;
    }

    const objectMatch = value.match(/^conn-(\d+)-db-(.+?)-obj-([a-z]+)-(.+)$/);
    if (objectMatch) {
      const connectionId = Number(objectMatch[1]);
      const databaseName = decodeURIComponent(objectMatch[2]);
      const objectType = ctx.toObjectType(objectMatch[3]);
      const objectName = decodeURIComponent(objectMatch[4]);
      ctx.workflow.connectionId = connectionId;
      ctx.activeDatabaseMap.value = {
        ...ctx.activeDatabaseMap.value,
        [connectionId]: databaseName,
      };
      ctx.currentObjectType.value = objectType;
      if (objectType === 'tables') {
        ctx.clearBrowserObjectCollections();
      } else if (objectType === 'queries') {
        ctx.browserSavedQueryList.value = ctx.savedQueriesByDatabase(connectionId, databaseName).length
          ? ctx.savedQueriesByDatabase(connectionId, databaseName)
          : await ctx.loadSavedQueries(connectionId, databaseName);
        ctx.browserObjectNameList.value = [];
      } else {
        const cachedNames = ctx.objectNameCache.value[objectCacheKey(connectionId, databaseName, objectType)] ?? [];
        ctx.browserObjectNameList.value = cachedNames.length ? cachedNames : await ctx.loadObjectNames(connectionId, databaseName, objectType);
        ctx.browserSavedQueryList.value = [];
      }
      ctx.expandConnectionNode(connectionId);
      await ctx.runSafely(async () => {
        await ctx.selectObject(connectionId, databaseName, objectType, objectName);
      });
      return;
    }

    const categoryMatch = value.match(/^conn-(\d+)-db-(.+?)-category-([a-z]+)$/);
    if (categoryMatch) {
      const connectionId = Number(categoryMatch[1]);
      const databaseName = decodeURIComponent(categoryMatch[2]);
      const category = categoryMatch[3];
      ctx.workflow.connectionId = connectionId;
      ctx.activeDatabaseMap.value = {
        ...ctx.activeDatabaseMap.value,
        [connectionId]: databaseName,
      };
      ctx.selectedObjectName.value = '';
      ctx.clearBrowserObjectCollections();
      ctx.clearObjectDetail();
      await ctx.loadCategoryObjects(connectionId, databaseName, category);
      return;
    }

    const databaseMatch = value.match(/^conn-(\d+)-db-(.+)$/);
    if (databaseMatch) {
      const connectionId = Number(databaseMatch[1]);
      const databaseName = decodeURIComponent(databaseMatch[2]);
      ctx.workflow.connectionId = connectionId;
      ctx.activeDatabaseMap.value = {
        ...ctx.activeDatabaseMap.value,
        [connectionId]: databaseName,
      };
      ctx.currentObjectType.value = 'tables';
      ctx.selectedObjectName.value = '';
      ctx.clearBrowserObjectCollections();
      ctx.clearObjectDetail();
      ctx.expandConnectionNode(connectionId);
      await ctx.loadOverview();
    }
  }

  return {
    handleTreeSelect,
  };
}

interface SchemaContextMenuState {
  visible: boolean;
  x: number;
  y: number;
  targetType: 'none' | 'group' | 'connection' | 'databaseRoot' | 'database' | 'category' | 'object';
  groupId: number;
  connectionId: number;
  databaseName: string;
  namespaceName: string;
  category: '' | 'tables' | 'views' | 'functions' | 'queries';
  objectType: '' | 'tables' | 'views' | 'functions' | 'events' | 'queries' | 'backups';
  objectName: string;
  redisNodeType: '' | 'PATH' | 'KEY' | 'LOAD_MORE';
}

interface SchemaContextMenuHelperContext {
  activeDatabaseMap: Ref<Record<number, string>>;
  connections: Ref<ConnectionVO[]>;
  contextMenu: SchemaContextMenuState;
  parseSchemaContextByDbType: (dbType: string, databaseName: string) => { databaseName: string; namespaceName: string };
  refreshVectorizeStatusForConnection: (connectionId: number) => Promise<void>;
  runSafely: (runner: () => Promise<void>) => Promise<void>;
  selectObject: (
    connectionId: number,
    databaseName: string,
    objectType: 'tables' | 'views' | 'functions' | 'events' | 'queries' | 'backups',
    objectName: string,
  ) => Promise<void>;
  selectedTreeKeys: Ref<(string | number)[]>;
  toObjectType: (value: string) => 'tables' | 'views' | 'functions' | 'events' | 'queries' | 'backups';
  workflow: { connectionId: number };
}

export function createSchemaContextMenuHelpers(ctx: SchemaContextMenuHelperContext) {
  function closeContextMenu() {
    ctx.contextMenu.visible = false;
    ctx.contextMenu.targetType = 'none';
    ctx.contextMenu.groupId = 0;
    ctx.contextMenu.databaseName = '';
    ctx.contextMenu.namespaceName = '';
    ctx.contextMenu.category = '';
    ctx.contextMenu.objectType = '';
    ctx.contextMenu.objectName = '';
  }

  async function handleTreeRightClick(event: { event: MouseEvent; node: { key?: string | number } }) {
    event.event.preventDefault();
    event.event.stopPropagation();
    const keyValue = String(event.node?.key ?? '');
    const groupMatch = keyValue.match(/^group-(\d+)$/);
    if (groupMatch) {
      ctx.selectedTreeKeys.value = [keyValue];
      ctx.contextMenu.visible = true;
      ctx.contextMenu.x = Math.min(event.event.clientX, window.innerWidth - 220);
      ctx.contextMenu.y = Math.min(event.event.clientY, window.innerHeight - 180);
      ctx.contextMenu.targetType = 'group';
      ctx.contextMenu.groupId = Number(groupMatch[1]);
      ctx.contextMenu.connectionId = 0;
      ctx.contextMenu.databaseName = '';
      ctx.contextMenu.namespaceName = '';
      ctx.contextMenu.category = '';
      ctx.contextMenu.objectType = '';
      ctx.contextMenu.objectName = '';
      return;
    }
    const databaseRootMatch = keyValue.match(/^conn-(\d+)-dbroot-(.+)$/);
    if (databaseRootMatch) {
      const connectionId = Number(databaseRootMatch[1]);
      const databaseName = decodeURIComponent(databaseRootMatch[2]);
      ctx.workflow.connectionId = connectionId;
      ctx.activeDatabaseMap.value = {
        ...ctx.activeDatabaseMap.value,
        [connectionId]: databaseName,
      };
      try {
        await ctx.refreshVectorizeStatusForConnection(connectionId);
      } catch {
        // 右键菜单唤起时尝试刷新状态，失败则沿用本地缓存。
      }
      ctx.selectedTreeKeys.value = [keyValue];
      ctx.contextMenu.visible = true;
      ctx.contextMenu.x = Math.min(event.event.clientX, window.innerWidth - 220);
      ctx.contextMenu.y = Math.min(event.event.clientY, window.innerHeight - 180);
      ctx.contextMenu.targetType = 'databaseRoot';
      ctx.contextMenu.connectionId = connectionId;
      ctx.contextMenu.databaseName = databaseName;
      ctx.contextMenu.namespaceName = '';
      ctx.contextMenu.category = '';
      ctx.contextMenu.objectType = '';
      ctx.contextMenu.objectName = '';
      return;
    }
    const objectMatch = keyValue.match(/^conn-(\d+)-db-(.+?)-obj-([a-z]+)-(.+)$/);
    if (objectMatch) {
      const connectionId = Number(objectMatch[1]);
      const databaseName = decodeURIComponent(objectMatch[2]);
      const objectType = ctx.toObjectType(objectMatch[3]);
      const objectName = decodeURIComponent(objectMatch[4]);
      ctx.workflow.connectionId = connectionId;
      ctx.activeDatabaseMap.value = {
        ...ctx.activeDatabaseMap.value,
        [connectionId]: databaseName,
      };
      ctx.selectedTreeKeys.value = [keyValue];
      ctx.contextMenu.visible = true;
      ctx.contextMenu.x = Math.min(event.event.clientX, window.innerWidth - 220);
      ctx.contextMenu.y = Math.min(event.event.clientY, window.innerHeight - 180);
      ctx.contextMenu.targetType = 'object';
      ctx.contextMenu.connectionId = connectionId;
      ctx.contextMenu.databaseName = databaseName;
      ctx.contextMenu.namespaceName = '';
      ctx.contextMenu.category = '';
      ctx.contextMenu.objectType = objectType;
      ctx.contextMenu.objectName = objectName;
      void ctx.runSafely(async () => {
        await ctx.selectObject(connectionId, databaseName, objectType, objectName);
      });
      return;
    }
    const categoryMatch = keyValue.match(/^conn-(\d+)-db-(.+?)-category-([a-z]+)$/);
    if (categoryMatch) {
      const connectionId = Number(categoryMatch[1]);
      const databaseName = decodeURIComponent(categoryMatch[2]);
      const category = categoryMatch[3] as 'tables' | 'views' | 'functions' | 'queries';
      ctx.workflow.connectionId = connectionId;
      ctx.activeDatabaseMap.value = {
        ...ctx.activeDatabaseMap.value,
        [connectionId]: databaseName,
      };
      ctx.selectedTreeKeys.value = [keyValue];
      ctx.contextMenu.visible = true;
      ctx.contextMenu.x = Math.min(event.event.clientX, window.innerWidth - 220);
      ctx.contextMenu.y = Math.min(event.event.clientY, window.innerHeight - 180);
      ctx.contextMenu.targetType = 'category';
      ctx.contextMenu.connectionId = connectionId;
      ctx.contextMenu.databaseName = databaseName;
      ctx.contextMenu.namespaceName = '';
      ctx.contextMenu.category = category;
      ctx.contextMenu.objectType = '';
      ctx.contextMenu.objectName = '';
      return;
    }

    const connectionMatch = keyValue.match(/^conn-(\d+)$/);
    const databaseMatch = keyValue.match(/^conn-(\d+)-db-(.+)$/);
    if (!connectionMatch && !databaseMatch) {
      return;
    }

    if (connectionMatch) {
      const connectionId = Number(connectionMatch[1]);
      ctx.workflow.connectionId = connectionId;
      ctx.selectedTreeKeys.value = [keyValue];
      ctx.contextMenu.visible = true;
      ctx.contextMenu.x = Math.min(event.event.clientX, window.innerWidth - 220);
      ctx.contextMenu.y = Math.min(event.event.clientY, window.innerHeight - 180);
      ctx.contextMenu.targetType = 'connection';
      ctx.contextMenu.connectionId = connectionId;
      ctx.contextMenu.databaseName = '';
      ctx.contextMenu.namespaceName = '';
      ctx.contextMenu.category = '';
      ctx.contextMenu.objectType = '';
      ctx.contextMenu.objectName = '';
      return;
    }

    if (!databaseMatch) {
      return;
    }
    const connectionId = Number(databaseMatch[1]);
    const databaseName = decodeURIComponent(databaseMatch[2]);
    if (databaseName === '未发现数据库') {
      return;
    }
    ctx.workflow.connectionId = connectionId;
    ctx.activeDatabaseMap.value = {
      ...ctx.activeDatabaseMap.value,
      [connectionId]: databaseName,
    };
    try {
      await ctx.refreshVectorizeStatusForConnection(connectionId);
    } catch {
      // 右键菜单唤起时尝试刷新状态，失败则沿用本地缓存。
    }
    ctx.selectedTreeKeys.value = [keyValue];
    ctx.contextMenu.visible = true;
    ctx.contextMenu.x = Math.min(event.event.clientX, window.innerWidth - 220);
    ctx.contextMenu.y = Math.min(event.event.clientY, window.innerHeight - 180);
    ctx.contextMenu.targetType = 'database';
    ctx.contextMenu.connectionId = connectionId;
    ctx.contextMenu.databaseName = databaseName;
    ctx.contextMenu.namespaceName = ctx.parseSchemaContextByDbType(
      ctx.connections.value.find((item) => item.id === connectionId)?.dbType || '',
      databaseName,
    ).namespaceName;
    ctx.contextMenu.category = '';
    ctx.contextMenu.objectType = '';
    ctx.contextMenu.objectName = '';
  }

  return {
    closeContextMenu,
    handleTreeRightClick,
  };
}
