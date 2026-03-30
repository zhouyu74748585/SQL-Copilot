import type {Ref} from 'vue';
import type {ConnectionVO} from '@sqlcopilot/shared-contracts';
import {message} from 'ant-design-vue';
import {getApi, postApi} from '../../../../api/client';
import {normalizeRagProviderByPackage, ragLocalOnnxEnabled} from '../../../../config/packageVariant';
import type {
  AiConfigSaveReq,
  AiConfigVO,
  AiModelOption,
  ConnectionCreateReq,
  ConnectionDatabasePreviewReq,
  ConnectionDatabasePreviewVO,
  ConnectionDbTypeVO,
  ConnectionGroupCreateReq,
  ConnectionGroupMoveReq,
  ConnectionGroupRemoveReq,
  ConnectionGroupRenameReq,
  ConnectionGroupVO,
  ErGraphSnapshotSummaryVO,
  KvOverviewVO,
  QueryHistorySessionVO,
  RagConfigSaveReq,
  RagConfigVO,
  SchemaOverviewVO,
} from '../../../../types';
import {resultExportDirectoryStorageKey} from './constants';
import type {
  ConnectionFormErrorMap,
  DesktopBridge,
  ErWorkspaceTab,
  ObjectDefinitionEditorTab,
  ObjectRow,
  QueryWorkspaceTab,
  TableDataWorkspaceTab,
  TableEditorWorkspaceTab,
} from './types';
import {normalizeSelectedDatabases, parseConfiguredDatabaseName, rootDatabaseNameForContext} from './utils';

const FALLBACK_SUPPORTED_DB_TYPES: ConnectionDbTypeVO[] = [
  {
    dbType: 'MYSQL',
    displayName: 'MySQL',
    defaultPort: 3306,
    storageKind: 'RELATIONAL',
    primaryObjectLabel: '表',
    queryEditorMode: 'sql',
    supportsSelectedDatabases: true,
    namespaceLabel: '',
    supportsNamespaceCreate: false,
    supportsNamespaceRename: false,
    supportsNamespaceDrop: false,
    supportsTableCreate: true,
    supportsTableDrop: true,
    supportsViewCreate: true,
    supportsViewDrop: true,
    supportsFunctionCreate: false,
    supportsFunctionDrop: false,
    supportsGenerateQuery: true,
    supportsExplainQuery: true,
    supportsAnalyzeQuery: true,
    supportsGenerateChart: true,
    requiresHost: true,
    requiresPort: true,
    supportsDatabaseName: false,
    supportsDatabasePreview: true,
    databaseNameLabel: '数据库名',
    supportsUsername: true,
    supportsPassword: true,
  },
  {
    dbType: 'POSTGRESQL',
    displayName: 'PostgreSQL',
    defaultPort: 5432,
    storageKind: 'RELATIONAL',
    primaryObjectLabel: '表',
    queryEditorMode: 'sql',
    supportsSelectedDatabases: true,
    namespaceLabel: '数据库',
    supportsNamespaceCreate: true,
    supportsNamespaceRename: true,
    supportsNamespaceDrop: true,
    supportsTableCreate: true,
    supportsTableDrop: true,
    supportsViewCreate: true,
    supportsViewDrop: true,
    supportsFunctionCreate: true,
    supportsFunctionDrop: true,
    supportsGenerateQuery: true,
    supportsExplainQuery: true,
    supportsAnalyzeQuery: true,
    supportsGenerateChart: true,
    requiresHost: true,
    requiresPort: true,
    supportsDatabaseName: false,
    supportsDatabasePreview: true,
    databaseNameLabel: '数据库',
    supportsUsername: true,
    supportsPassword: true,
  },
  {
    dbType: 'SQLSERVER',
    displayName: 'SQL Server',
    defaultPort: 1433,
    storageKind: 'RELATIONAL',
    primaryObjectLabel: '表',
    queryEditorMode: 'sql',
    supportsSelectedDatabases: true,
    namespaceLabel: '架构',
    supportsNamespaceCreate: true,
    supportsNamespaceRename: true,
    supportsNamespaceDrop: true,
    supportsTableCreate: true,
    supportsTableDrop: true,
    supportsViewCreate: true,
    supportsViewDrop: true,
    supportsFunctionCreate: true,
    supportsFunctionDrop: true,
    supportsGenerateQuery: true,
    supportsExplainQuery: true,
    supportsAnalyzeQuery: true,
    supportsGenerateChart: true,
    requiresHost: true,
    requiresPort: true,
    supportsDatabaseName: false,
    supportsDatabasePreview: true,
    databaseNameLabel: '数据库',
    supportsUsername: true,
    supportsPassword: true,
  },
  {
    dbType: 'ORACLE',
    displayName: 'Oracle',
    defaultPort: 1521,
    storageKind: 'RELATIONAL',
    primaryObjectLabel: '表',
    queryEditorMode: 'sql',
    supportsSelectedDatabases: false,
    namespaceLabel: 'Schema',
    supportsNamespaceCreate: true,
    supportsNamespaceRename: false,
    supportsNamespaceDrop: true,
    supportsTableCreate: true,
    supportsTableDrop: true,
    supportsViewCreate: true,
    supportsViewDrop: true,
    supportsFunctionCreate: true,
    supportsFunctionDrop: true,
    supportsGenerateQuery: true,
    supportsExplainQuery: true,
    supportsAnalyzeQuery: true,
    supportsGenerateChart: true,
    requiresHost: true,
    requiresPort: true,
    supportsDatabaseName: true,
    supportsDatabasePreview: false,
    databaseNameLabel: '服务名',
    supportsUsername: true,
    supportsPassword: true,
  },
  {
    dbType: 'SQLITE',
    displayName: 'SQLite',
    defaultPort: 0,
    storageKind: 'RELATIONAL',
    primaryObjectLabel: '表',
    queryEditorMode: 'sql',
    supportsSelectedDatabases: false,
    namespaceLabel: '',
    supportsNamespaceCreate: false,
    supportsNamespaceRename: false,
    supportsNamespaceDrop: false,
    supportsTableCreate: true,
    supportsTableDrop: true,
    supportsViewCreate: true,
    supportsViewDrop: true,
    supportsFunctionCreate: false,
    supportsFunctionDrop: false,
    supportsGenerateQuery: true,
    supportsExplainQuery: true,
    supportsAnalyzeQuery: true,
    supportsGenerateChart: true,
    requiresHost: false,
    requiresPort: false,
    supportsDatabaseName: true,
    supportsDatabasePreview: false,
    databaseNameLabel: '数据库文件路径',
    supportsUsername: false,
    supportsPassword: false,
  },
  {
    dbType: 'REDIS',
    displayName: 'Redis',
    defaultPort: 6379,
    storageKind: 'KV',
    primaryObjectLabel: '键',
    queryEditorMode: 'redis',
    supportsSelectedDatabases: false,
    namespaceLabel: '逻辑库',
    supportsNamespaceCreate: false,
    supportsNamespaceRename: false,
    supportsNamespaceDrop: false,
    supportsTableCreate: false,
    supportsTableDrop: false,
    supportsViewCreate: false,
    supportsViewDrop: false,
    supportsFunctionCreate: false,
    supportsFunctionDrop: false,
    supportsGenerateQuery: true,
    supportsExplainQuery: true,
    supportsAnalyzeQuery: true,
    supportsGenerateChart: false,
    requiresHost: true,
    requiresPort: true,
    supportsDatabaseName: true,
    supportsDatabasePreview: false,
    databaseNameLabel: '逻辑库',
    supportsUsername: true,
    supportsPassword: true,
  },
  {
    dbType: 'MONGODB',
    displayName: 'MongoDB',
    defaultPort: 27017,
    storageKind: 'DOCUMENT',
    primaryObjectLabel: '集合',
    queryEditorMode: 'json',
    supportsSelectedDatabases: true,
    namespaceLabel: '数据库',
    supportsNamespaceCreate: false,
    supportsNamespaceRename: false,
    supportsNamespaceDrop: false,
    supportsTableCreate: false,
    supportsTableDrop: false,
    supportsViewCreate: false,
    supportsViewDrop: false,
    supportsFunctionCreate: false,
    supportsFunctionDrop: false,
    supportsGenerateQuery: true,
    supportsExplainQuery: true,
    supportsAnalyzeQuery: true,
    supportsGenerateChart: false,
    requiresHost: true,
    requiresPort: true,
    supportsDatabaseName: false,
    supportsDatabasePreview: true,
    databaseNameLabel: '数据库',
    supportsUsername: false,
    supportsPassword: true,
  },
];

export function defaultConnectionForm(): ConnectionCreateReq {
  return {
    name: '新建连接',
    dbType: 'MYSQL',
    groupId: undefined,
    host: '',
    port: 0,
    databaseName: '',
    selectedDatabases: [],
    customParams: '',
    username: '',
    password: '',
    authType: 'PASSWORD',
    env: 'DEV',
    readOnly: false,
    sshEnabled: false,
    sshHost: '',
    sshPort: 22,
    sshUser: '',
    sshAuthType: 'SSH_PASSWORD',
    sshPassword: '',
    sshPrivateKeyPath: '',
    sshPrivateKeyText: '',
    sshPrivateKeyPassphrase: '',
  };
}

export function mergeSupportedDbTypes(
  supportedDbTypes: ConnectionDbTypeVO[] | undefined,
  hiddenDbTypes?: Set<string>,
) {
  const merged = new Map<string, ConnectionDbTypeVO>();
  FALLBACK_SUPPORTED_DB_TYPES.forEach((item) => {
    if (hiddenDbTypes?.has(item.dbType)) {
      return;
    }
    merged.set(item.dbType, {...item});
  });
  (supportedDbTypes ?? []).forEach((item) => {
    const dbType = (item.dbType || '').trim().toUpperCase();
    if (!dbType || hiddenDbTypes?.has(dbType)) {
      return;
    }
    merged.set(dbType, {
      ...merged.get(dbType),
      ...item,
      dbType,
    });
  });
  return Array.from(merged.values());
}

export function defaultAiConfigForm(): AiConfigSaveReq {
  const defaultOption: AiModelOption = {
    id: 'openai-1',
    name: 'OpenAI gpt-4.1-mini',
    providerType: 'OPENAI',
    openaiBaseUrl: 'https://api.openai.com/v1',
    openaiApiKey: '',
    openaiModel: 'gpt-4.1-mini',
    cliCommand: '',
    cliWorkingDir: '',
    contextWindowTokens: 32000,
    completionReserveTokens: 2048,
    tokenizerType: 'GENERIC_HEURISTIC',
  };
  return {
    providerType: 'OPENAI',
    openaiBaseUrl: defaultOption.openaiBaseUrl,
    openaiApiKey: defaultOption.openaiApiKey,
    openaiModel: defaultOption.openaiModel,
    cliCommand: defaultOption.cliCommand,
    cliWorkingDir: defaultOption.cliWorkingDir,
    modelOptions: [defaultOption],
    conversationMemoryEnabled: true,
    conversationMemoryWindowSize: 12,
    conversationMemoryWindowTokens: 6000,
    conversationAutoCompressRatio: 0.75,
    detailOutputEnabled: false,
  };
}

export function defaultRagConfigForm(): RagConfigSaveReq {
  const defaultProvider = normalizeRagProviderType(undefined);
  return {
    ragEmbeddingProviderType: defaultProvider,
    ragEmbeddingModelDir: '',
    ragEmbeddingOnlineBaseUrl: 'https://api.openai.com/v1',
    ragEmbeddingOnlineApiKey: '',
    ragEmbeddingOnlineModel: '',
    ragRerankEnabled: false,
    ragRerankProviderType: defaultProvider,
    ragRerankModelDir: '',
    ragRerankOnlineBaseUrl: 'https://api.openai.com/v1',
    ragRerankOnlineApiKey: '',
    ragRerankOnlineModel: '',
  };
}

export function normalizeRagProviderType(value?: string): 'LOCAL_ONNX' | 'ONLINE_OPENAI_COMPAT' {
  return normalizeRagProviderByPackage(value);
}

export function normalizeModelOptions(options: AiModelOption[] | undefined) {
  const list = (options ?? [])
    .map((item, index) => {
      const providerType: 'OPENAI' | 'LOCAL_CLI' = item.providerType === 'LOCAL_CLI' ? 'LOCAL_CLI' : 'OPENAI';
      return {
        id: (item.id || '').trim() || `${providerType === 'LOCAL_CLI' ? 'cli' : 'openai'}-${index + 1}`,
        name: (item.name || '').trim() || (providerType === 'LOCAL_CLI' ? `CLI-${index + 1}` : `OpenAI-${index + 1}`),
        providerType,
        openaiBaseUrl: (item.openaiBaseUrl || '').trim(),
        openaiApiKey: (item.openaiApiKey || '').trim(),
        openaiModel: (item.openaiModel || '').trim(),
        cliCommand: (item.cliCommand || '').trim(),
        cliWorkingDir: (item.cliWorkingDir || '').trim(),
        contextWindowTokens: Number(item.contextWindowTokens || 32000),
        completionReserveTokens: Number(item.completionReserveTokens || 2048),
        tokenizerType: (item.tokenizerType || 'GENERIC_HEURISTIC').trim() || 'GENERIC_HEURISTIC',
      } satisfies AiModelOption;
    })
    .filter((item) => !!item.id);
  if (list.length) {
    return list;
  }
  return defaultAiConfigForm().modelOptions ?? [];
}

export function findSupportedDbTypeInList(supportedDbTypes: ConnectionDbTypeVO[], dbType: string) {
  const normalizedDbType = (dbType || '').trim().toUpperCase();
  return mergeSupportedDbTypes(supportedDbTypes).find((item) => item.dbType === normalizedDbType) ?? null;
}

export function isMultiDatabaseTypeInList(supportedDbTypes: ConnectionDbTypeVO[], dbType: string) {
  return !!findSupportedDbTypeInList(supportedDbTypes, dbType)?.supportsSelectedDatabases;
}

export function isDatabaseContextVisible(
  connection: ConnectionVO,
  databaseName: string,
  visibleDatabases: string[],
) {
  const normalizedDatabaseName = (databaseName || '').trim();
  if (!normalizedDatabaseName) {
    return false;
  }
  if (!visibleDatabases.length) {
    return true;
  }
  const rootDatabaseName = rootDatabaseNameForContext(connection.dbType, normalizedDatabaseName);
  return !!rootDatabaseName && visibleDatabases.includes(rootDatabaseName);
}

interface ConnectionRuntimeHelperContext {
  connections: Ref<ConnectionVO[]>;
  databaseListCache: Ref<Record<number, string[]>>;
  hiddenDbTypes: Set<string>;
  supportedDbTypes: Ref<ConnectionDbTypeVO[]>;
}

interface ConnectionStatusHelperContext {
  connectionRuntimeStatusMap: Ref<Record<number, 'idle' | 'connected' | 'failed'>>;
}

export function createConnectionRuntimeHelpers(ctx: ConnectionRuntimeHelperContext) {
  function findSupportedDbType(dbType: string) {
    return findSupportedDbTypeInList(ctx.supportedDbTypes.value, dbType);
  }

  function isMultiDatabaseType(dbType: string) {
    return isMultiDatabaseTypeInList(ctx.supportedDbTypes.value, dbType);
  }

  function requiresDatabaseLayer(connection: ConnectionVO) {
    if (isMultiDatabaseType(connection.dbType)) {
      return true;
    }
    return !parseConfiguredDatabaseName(connection).trim();
  }

  function isFrontendVisibleDbType(dbType: string) {
    return !ctx.hiddenDbTypes.has((dbType || '').trim().toUpperCase());
  }

  function storageKindByDbType(dbType: string) {
    return findSupportedDbType(dbType)?.storageKind || 'RELATIONAL';
  }

  function queryEditorModeByDbType(dbType: string) {
    return findSupportedDbType(dbType)?.queryEditorMode || 'sql';
  }

  function primaryObjectLabelByDbType(dbType: string) {
    return findSupportedDbType(dbType)?.primaryObjectLabel || '表';
  }

  function supportsGenerateChartByDbType(dbType: string) {
    return findSupportedDbType(dbType)?.supportsGenerateChart !== false;
  }

  function isKvDbType(dbType: string) {
    const kind = storageKindByDbType(dbType);
    return kind === 'DOCUMENT' || kind === 'KV';
  }

  function isKvConnectionId(connectionId: number) {
    const dbType = ctx.connections.value.find((item) => item.id === connectionId)?.dbType || '';
    return isKvDbType(dbType);
  }

  function defaultPortForDbType(dbType: string) {
    const configuredPort = findSupportedDbType(dbType)?.defaultPort;
    if (typeof configuredPort === 'number') {
      return configuredPort;
    }
    if (dbType === 'MYSQL') {
      return 3306;
    }
    if (dbType === 'POSTGRESQL') {
      return 5432;
    }
    if (dbType === 'SQLSERVER') {
      return 1433;
    }
    if (dbType === 'ORACLE') {
      return 1521;
    }
    return 0;
  }

  function visibleDatabasesForConnection(connection: ConnectionVO) {
    const allDatabases = ctx.databaseListCache.value[connection.id] ?? [];
    if (!isMultiDatabaseType(connection.dbType)) {
      return allDatabases;
    }
    const selected = normalizeSelectedDatabases(connection.selectedDatabases);
    if (!selected.length) {
      return allDatabases;
    }
    const selectedSet = new Set(selected.map((item) => item.toLowerCase()));
    return allDatabases.filter((item) => selectedSet.has(item.toLowerCase()));
  }

  function isDatabaseContextVisibleForConnection(connection: ConnectionVO, databaseName: string) {
    return isDatabaseContextVisible(connection, databaseName, visibleDatabasesForConnection(connection));
  }

  return {
    defaultPortForDbType,
    findSupportedDbType,
    isDatabaseContextVisibleForConnection,
    isFrontendVisibleDbType,
    isKvConnectionId,
    isKvDbType,
    isMultiDatabaseType,
    primaryObjectLabelByDbType,
    queryEditorModeByDbType,
    requiresDatabaseLayer,
    storageKindByDbType,
    supportsGenerateChartByDbType,
    visibleDatabasesForConnection,
  };
}

export function connectionStatusClassByState(status?: string) {
  if (status === 'connected') {
    return 'is-success';
  }
  if (status === 'failed') {
    return 'is-failed';
  }
  return 'is-unknown';
}

export function connectionStatusTextByState(status?: string) {
  if (status === 'connected') {
    return '已连接';
  }
  if (status === 'failed') {
    return '连接失败';
  }
  return '未连接';
}

export function createConnectionStatusHelpers(ctx: ConnectionStatusHelperContext) {
  function connectionStatusClass(connectionId?: number) {
    if (!connectionId) {
      return connectionStatusClassByState(undefined);
    }
    return connectionStatusClassByState(ctx.connectionRuntimeStatusMap.value[connectionId] || 'idle');
  }

  function connectionStatusText(connectionId?: number) {
    if (!connectionId) {
      return connectionStatusTextByState(undefined);
    }
    return connectionStatusTextByState(ctx.connectionRuntimeStatusMap.value[connectionId] || 'idle');
  }

  return {
    connectionStatusClass,
    connectionStatusText,
  };
}

interface ConnectionSettingsHelperContext {
  aiConfigForm: AiConfigSaveReq;
  connectionForm: ConnectionCreateReq;
  connectionFormSubmitted: Ref<boolean>;
  connectionGroups: Ref<ConnectionGroupVO[]>;
  connectionPreviewDbOptions: Ref<string[]>;
  connectionPreviewError: Ref<string>;
  editingConnectionId: Ref<number | null>;
  ensureConnectionFormDbType: () => void;
  isEditMode: Ref<boolean>;
  ragConfigForm: RagConfigSaveReq;
  selectedAiModel: Ref<string>;
}

export function createConnectionSettingsHelpers(ctx: ConnectionSettingsHelperContext) {
  function resetConnectionForm() {
    Object.assign(ctx.connectionForm, defaultConnectionForm());
    ctx.connectionFormSubmitted.value = false;
    if (ctx.connectionGroups.value.length) {
      ctx.connectionForm.groupId = ctx.connectionGroups.value[0].id;
    }
    ctx.ensureConnectionFormDbType();
  }

  function fillConnectionForm(connection: ConnectionVO) {
    ctx.connectionFormSubmitted.value = false;
    Object.assign(ctx.connectionForm, {
      name: connection.name,
      dbType: connection.dbType,
      groupId: connection.groupId,
      host: connection.host ?? '',
      port: connection.port ?? 0,
      databaseName: connection.databaseName ?? '',
      selectedDatabases: normalizeSelectedDatabases(connection.selectedDatabases),
      customParams: connection.customParams ?? '',
      username: connection.username ?? '',
      password: '',
      authType: 'PASSWORD',
      env: connection.env,
      readOnly: connection.readOnly,
      sshEnabled: connection.sshEnabled,
      sshHost: connection.sshHost ?? '',
      sshPort: connection.sshPort ?? 22,
      sshUser: connection.sshUser ?? '',
      sshAuthType: (connection.sshAuthType as ConnectionCreateReq['sshAuthType']) || 'SSH_PASSWORD',
      sshPassword: '',
      sshPrivateKeyPath: connection.sshPrivateKeyPath ?? '',
      sshPrivateKeyText: '',
      sshPrivateKeyPassphrase: '',
    } satisfies ConnectionCreateReq);
  }

  function nextModelOptionId(prefix: 'openai' | 'cli') {
    const existing = new Set((ctx.aiConfigForm.modelOptions ?? []).map((item) => item.id));
    let index = 1;
    while (existing.has(`${prefix}-${index}`)) {
      index++;
    }
    return `${prefix}-${index}`;
  }

  function addOpenAiModelOption() {
    const id = nextModelOptionId('openai');
    ctx.aiConfigForm.modelOptions = [
      ...(ctx.aiConfigForm.modelOptions ?? []),
      {
        id,
        name: `OpenAI ${id}`,
        providerType: 'OPENAI',
        openaiBaseUrl: 'https://api.openai.com/v1',
        openaiApiKey: '',
        openaiModel: 'gpt-4.1-mini',
        cliCommand: '',
        cliWorkingDir: '',
        contextWindowTokens: 32000,
        completionReserveTokens: 2048,
        tokenizerType: 'GENERIC_HEURISTIC',
      },
    ];
  }

  function addCliModelOption() {
    const id = nextModelOptionId('cli');
    ctx.aiConfigForm.modelOptions = [
      ...(ctx.aiConfigForm.modelOptions ?? []),
      {
        id,
        name: `CLI ${id}`,
        providerType: 'LOCAL_CLI',
        openaiBaseUrl: '',
        openaiApiKey: '',
        openaiModel: '',
        cliCommand: '',
        cliWorkingDir: '',
        contextWindowTokens: 32000,
        completionReserveTokens: 2048,
        tokenizerType: 'GENERIC_HEURISTIC',
      },
    ];
  }

  function removeModelOption(index: number) {
    const list = [...(ctx.aiConfigForm.modelOptions ?? [])];
    list.splice(index, 1);
    ctx.aiConfigForm.modelOptions = list.length ? list : (defaultAiConfigForm().modelOptions ?? []);
  }

  function fillAiConfigForm(config: AiConfigVO) {
    const options = normalizeModelOptions(config.modelOptions);
    const first = options[0];
    Object.assign(ctx.aiConfigForm, {
      providerType: first.providerType,
      openaiBaseUrl: first.openaiBaseUrl || 'https://api.openai.com/v1',
      openaiApiKey: first.openaiApiKey || '',
      openaiModel: first.openaiModel || 'gpt-4.1-mini',
      cliCommand: first.cliCommand || '',
      cliWorkingDir: first.cliWorkingDir || '',
      modelOptions: options,
      conversationMemoryEnabled: config.conversationMemoryEnabled !== false,
      conversationMemoryWindowSize: config.conversationMemoryWindowSize || 12,
      conversationMemoryWindowTokens: config.conversationMemoryWindowTokens || 6000,
      conversationAutoCompressRatio: config.conversationAutoCompressRatio || 0.75,
      detailOutputEnabled: config.detailOutputEnabled === true,
    } satisfies AiConfigSaveReq);
    const models = options.map((item) => item.id).filter((item) => !!item);
    if (!models.includes(ctx.selectedAiModel.value)) {
      ctx.selectedAiModel.value = models[0] || '';
    }
  }

  function fillRagConfigForm(config: RagConfigVO) {
    const embeddingModelDir = (config.ragEmbeddingModelDir || '').trim();
    const rerankModelDir = (config.ragRerankModelDir || '').trim();
    const embeddingProviderType = normalizeRagProviderType(config.ragEmbeddingProviderType);
    const rerankProviderType = normalizeRagProviderType(config.ragRerankProviderType);
    Object.assign(ctx.ragConfigForm, {
      ragEmbeddingProviderType: ragLocalOnnxEnabled
        && (embeddingProviderType === 'LOCAL_ONNX' || !!embeddingModelDir)
        ? 'LOCAL_ONNX'
        : embeddingProviderType,
      ragEmbeddingModelDir: embeddingModelDir,
      ragEmbeddingOnlineBaseUrl: config.ragEmbeddingOnlineBaseUrl || 'https://api.openai.com/v1',
      ragEmbeddingOnlineApiKey: config.ragEmbeddingOnlineApiKey || '',
      ragEmbeddingOnlineModel: config.ragEmbeddingOnlineModel || '',
      ragRerankEnabled: config.ragRerankEnabled === true,
      ragRerankProviderType: ragLocalOnnxEnabled
        && (rerankProviderType === 'LOCAL_ONNX' || !!rerankModelDir)
        ? 'LOCAL_ONNX'
        : rerankProviderType,
      ragRerankModelDir: rerankModelDir,
      ragRerankOnlineBaseUrl: config.ragRerankOnlineBaseUrl || 'https://api.openai.com/v1',
      ragRerankOnlineApiKey: config.ragRerankOnlineApiKey || '',
      ragRerankOnlineModel: config.ragRerankOnlineModel || '',
    } satisfies RagConfigSaveReq);
    if (!ragLocalOnnxEnabled) {
      ctx.ragConfigForm.ragEmbeddingProviderType = 'ONLINE_OPENAI_COMPAT';
      ctx.ragConfigForm.ragEmbeddingModelDir = '';
      ctx.ragConfigForm.ragRerankProviderType = 'ONLINE_OPENAI_COMPAT';
      ctx.ragConfigForm.ragRerankModelDir = '';
    }
  }

  function resetConnectionModalState() {
    ctx.isEditMode.value = false;
    ctx.editingConnectionId.value = null;
    resetConnectionForm();
    ctx.connectionPreviewDbOptions.value = [];
    ctx.connectionPreviewError.value = '';
  }

  return {
    addCliModelOption,
    addOpenAiModelOption,
    fillAiConfigForm,
    fillConnectionForm,
    fillRagConfigForm,
    nextModelOptionId,
    normalizeModelOptions,
    removeModelOption,
    resetConnectionForm,
    resetConnectionModalState,
  };
}

export function getDesktopBridge(): DesktopBridge | null {
  if (typeof window === 'undefined') {
    return null;
  }
  const bridge = (window as Window & { sqlCopilotDesktop?: DesktopBridge }).sqlCopilotDesktop;
  if (!bridge || typeof bridge.pickFile !== 'function') {
    return null;
  }
  return bridge;
}

export function loadLastResultExportDirectory() {
  if (typeof window === 'undefined') {
    return '';
  }
  try {
    return window.localStorage.getItem(resultExportDirectoryStorageKey) || '';
  } catch {
    return '';
  }
}

export function saveLastResultExportDirectory(directory: string) {
  if (typeof window === 'undefined') {
    return;
  }
  try {
    if (!directory) {
      window.localStorage.removeItem(resultExportDirectoryStorageKey);
      return;
    }
    window.localStorage.setItem(resultExportDirectoryStorageKey, directory);
  } catch {
    // 忽略存储失败，避免影响导出主流程。
  }
}

interface DatabaseOptionTarget {
  connectionId: number;
  databaseName: string;
}

function databaseOptionsForTarget(
  target: DatabaseOptionTarget,
  connections: ConnectionVO[],
  visibleDatabasesForConnection: (connection: ConnectionVO) => string[],
  getActiveDatabaseName: (connectionId: number) => string,
) {
  const connection = connections.find((item) => item.id === target.connectionId);
  const cached = connection ? visibleDatabasesForConnection(connection) : [];
  const options = cached.map((item) => ({label: item, value: item}));
  const fallback = target.databaseName || getActiveDatabaseName(target.connectionId);
  if (!fallback) {
    return options;
  }
  if (options.some((item) => item.value === fallback)) {
    return options;
  }
  return [...options, {label: fallback, value: fallback}];
}

interface ConnectionUiHelperContext {
  aiConfigForm: AiConfigSaveReq;
  aiConfigModalOpen: Ref<boolean>;
  clearStatementResults: (tab: QueryWorkspaceTab) => void;
  connections: Ref<ConnectionVO[]>;
  fillAiConfigForm: (config: AiConfigVO) => void;
  fillRagConfigForm: (config: RagConfigVO) => void;
  getActiveDatabaseName: (connectionId: number) => string;
  prepareConnectionTreeData: (connectionId: number) => Promise<void>;
  pickingRagModelDir: Ref<boolean>;
  pickingRagRerankModelDir: Ref<boolean>;
  ragConfigForm: RagConfigSaveReq;
  runSafely: (runner: () => Promise<void>) => Promise<void>;
  touchQueryTab: (tab: QueryWorkspaceTab) => void;
  visibleDatabasesForConnection: (connection: ConnectionVO) => string[];
  warmupTableSuggestions: (tab: QueryWorkspaceTab) => Promise<void>;
}

export function createConnectionUiHelpers(ctx: ConnectionUiHelperContext) {
  async function pickRagEmbeddingModelDir() {
    if (!ragLocalOnnxEnabled) {
      message.warning('当前包型不支持本地 ONNX 目录配置。');
      return;
    }
    const bridge = getDesktopBridge();
    if (!bridge || typeof bridge.pickDirectory !== 'function') {
      message.warning('Directory picker is unavailable in this runtime. Please run in desktop app.');
      return;
    }
    if (ctx.pickingRagModelDir.value) {
      return;
    }
    ctx.pickingRagModelDir.value = true;
    try {
      const selectedPath = await bridge.pickDirectory({
        title: 'Select embedding model directory',
        defaultPath: ctx.ragConfigForm.ragEmbeddingModelDir || undefined,
      });
      if (!selectedPath) {
        return;
      }
      ctx.ragConfigForm.ragEmbeddingProviderType = 'LOCAL_ONNX';
      ctx.ragConfigForm.ragEmbeddingModelDir = selectedPath;
    } finally {
      ctx.pickingRagModelDir.value = false;
    }
  }

  async function pickRagRerankModelDir() {
    if (!ragLocalOnnxEnabled) {
      message.warning('当前包型不支持本地 ONNX 目录配置。');
      return;
    }
    const bridge = getDesktopBridge();
    if (!bridge || typeof bridge.pickDirectory !== 'function') {
      message.warning('Directory picker is unavailable in this runtime. Please run in desktop app.');
      return;
    }
    if (ctx.pickingRagRerankModelDir.value) {
      return;
    }
    ctx.pickingRagRerankModelDir.value = true;
    try {
      const selectedPath = await bridge.pickDirectory({
        title: 'Select local rerank model directory',
        defaultPath: ctx.ragConfigForm.ragRerankModelDir || undefined,
      });
      if (!selectedPath) {
        return;
      }
      ctx.ragConfigForm.ragRerankProviderType = 'LOCAL_ONNX';
      ctx.ragConfigForm.ragRerankModelDir = selectedPath;
    } finally {
      ctx.pickingRagRerankModelDir.value = false;
    }
  }

  async function openAiConfigModal() {
    ctx.aiConfigModalOpen.value = true;
    await ctx.runSafely(async () => {
      const aiConfig = await getApi<AiConfigVO>('/api/ai/config/get');
      const ragConfig = await getApi<RagConfigVO>('/api/rag/config/get');
      ctx.fillAiConfigForm(aiConfig);
      ctx.fillRagConfigForm(ragConfig);
    });
  }

  async function saveAiConfig() {
    await ctx.runSafely(async () => {
      const modelOptions = normalizeModelOptions(ctx.aiConfigForm.modelOptions);
      if (!modelOptions.length) {
        throw new Error('Please configure at least one model.');
      }
      ctx.aiConfigForm.modelOptions = modelOptions;
      ctx.aiConfigForm.providerType = modelOptions[0].providerType;
      ctx.aiConfigForm.openaiBaseUrl = modelOptions[0].openaiBaseUrl || '';
      ctx.aiConfigForm.openaiApiKey = modelOptions[0].openaiApiKey || '';
      ctx.aiConfigForm.openaiModel = modelOptions[0].openaiModel || '';
      ctx.aiConfigForm.cliCommand = modelOptions[0].cliCommand || '';
      ctx.aiConfigForm.cliWorkingDir = modelOptions[0].cliWorkingDir || '';
      ctx.aiConfigForm.conversationMemoryEnabled = ctx.aiConfigForm.conversationMemoryEnabled !== false;
      ctx.aiConfigForm.conversationMemoryWindowSize = Math.min(50, Math.max(4, Number(ctx.aiConfigForm.conversationMemoryWindowSize || 12)));
      ctx.aiConfigForm.conversationMemoryWindowTokens = Math.min(32000, Math.max(512, Number(ctx.aiConfigForm.conversationMemoryWindowTokens || 6000)));
      ctx.aiConfigForm.conversationAutoCompressRatio = Math.min(0.95, Math.max(0.3, Number(ctx.aiConfigForm.conversationAutoCompressRatio || 0.75)));
      ctx.aiConfigForm.detailOutputEnabled = ctx.aiConfigForm.detailOutputEnabled === true;
      ctx.ragConfigForm.ragEmbeddingProviderType = normalizeRagProviderType(ctx.ragConfigForm.ragEmbeddingProviderType);
      ctx.ragConfigForm.ragEmbeddingModelDir = (ctx.ragConfigForm.ragEmbeddingModelDir || '').trim();
      ctx.ragConfigForm.ragEmbeddingOnlineBaseUrl = (ctx.ragConfigForm.ragEmbeddingOnlineBaseUrl || '').trim();
      ctx.ragConfigForm.ragEmbeddingOnlineApiKey = (ctx.ragConfigForm.ragEmbeddingOnlineApiKey || '').trim();
      ctx.ragConfigForm.ragEmbeddingOnlineModel = (ctx.ragConfigForm.ragEmbeddingOnlineModel || '').trim();
      ctx.ragConfigForm.ragRerankEnabled = ctx.ragConfigForm.ragRerankEnabled === true;
      ctx.ragConfigForm.ragRerankProviderType = normalizeRagProviderType(ctx.ragConfigForm.ragRerankProviderType);
      ctx.ragConfigForm.ragRerankModelDir = (ctx.ragConfigForm.ragRerankModelDir || '').trim();
      ctx.ragConfigForm.ragRerankOnlineBaseUrl = (ctx.ragConfigForm.ragRerankOnlineBaseUrl || '').trim();
      ctx.ragConfigForm.ragRerankOnlineApiKey = (ctx.ragConfigForm.ragRerankOnlineApiKey || '').trim();
      ctx.ragConfigForm.ragRerankOnlineModel = (ctx.ragConfigForm.ragRerankOnlineModel || '').trim();
      if (!ragLocalOnnxEnabled) {
        ctx.ragConfigForm.ragEmbeddingProviderType = 'ONLINE_OPENAI_COMPAT';
        ctx.ragConfigForm.ragEmbeddingModelDir = '';
        ctx.ragConfigForm.ragRerankProviderType = 'ONLINE_OPENAI_COMPAT';
        ctx.ragConfigForm.ragRerankModelDir = '';
      } else {
        if (ctx.ragConfigForm.ragEmbeddingModelDir) {
          ctx.ragConfigForm.ragEmbeddingProviderType = 'LOCAL_ONNX';
        }
        if (ctx.ragConfigForm.ragRerankEnabled && ctx.ragConfigForm.ragRerankModelDir) {
          ctx.ragConfigForm.ragRerankProviderType = 'LOCAL_ONNX';
        }
      }
      const savedAi = await postApi<AiConfigVO>('/api/ai/config/save', ctx.aiConfigForm);
      const savedRag = await postApi<RagConfigVO>('/api/rag/config/save', ctx.ragConfigForm);
      ctx.fillAiConfigForm(savedAi);
      ctx.fillRagConfigForm(savedRag);
      ctx.aiConfigModalOpen.value = false;
      message.success('AI and RAG configuration saved.');
    });
  }

  function databaseOptionsForTab(tab: QueryWorkspaceTab) {
    return databaseOptionsForTarget(
      tab,
      ctx.connections.value,
      ctx.visibleDatabasesForConnection,
      ctx.getActiveDatabaseName,
    );
  }

  function databaseOptionsForTableEditorTab(tab: TableEditorWorkspaceTab) {
    return databaseOptionsForTarget(
      tab,
      ctx.connections.value,
      ctx.visibleDatabasesForConnection,
      ctx.getActiveDatabaseName,
    );
  }

  function databaseOptionsForTableDataTab(tab: TableDataWorkspaceTab) {
    return databaseOptionsForTarget(
      tab,
      ctx.connections.value,
      ctx.visibleDatabasesForConnection,
      ctx.getActiveDatabaseName,
    );
  }

  function queryTabConnectionName(tab: QueryWorkspaceTab) {
    return ctx.connections.value.find((item) => item.id === tab.connectionId)?.name ?? '-';
  }

  async function handleQueryConnectionChange(tab: QueryWorkspaceTab) {
    await ctx.runSafely(async () => {
      await ctx.prepareConnectionTreeData(tab.connectionId);
      tab.databaseName = ctx.getActiveDatabaseName(tab.connectionId);
      tab.riskAckToken = '';
      tab.riskInfo = null;
      tab.selectedSqlText = '';
      ctx.clearStatementResults(tab);
      ctx.touchQueryTab(tab);
      await ctx.warmupTableSuggestions(tab);
    });
  }

  function handleQueryDatabaseChange(tab: QueryWorkspaceTab) {
    tab.riskAckToken = '';
    ctx.clearStatementResults(tab);
    ctx.touchQueryTab(tab);
    void ctx.warmupTableSuggestions(tab);
  }

  async function handleTableEditorConnectionChange(tab: TableEditorWorkspaceTab) {
    if (tab.mode === 'edit') {
      return;
    }
    await ctx.runSafely(async () => {
      await ctx.prepareConnectionTreeData(tab.connectionId);
      tab.databaseName = ctx.getActiveDatabaseName(tab.connectionId);
      tab.dbType = ctx.connections.value.find((item) => item.id === tab.connectionId)?.dbType ?? 'MYSQL';
      tab.draft = null;
      tab.baselineDraft = null;
      tab.previewSql = '';
      tab.canSave = false;
      tab.dirty = false;
      tab.updatedAt = Date.now();
    });
  }

  function handleTableEditorDatabaseChange(tab: TableEditorWorkspaceTab) {
    if (tab.mode === 'edit') {
      return;
    }
    tab.updatedAt = Date.now();
  }

  function queryDbTypeByConnectionId(connectionId: number) {
    return ctx.connections.value.find((item) => item.id === connectionId)?.dbType ?? 'MYSQL';
  }

  function queryTabDbType(tab: QueryWorkspaceTab) {
    return queryDbTypeByConnectionId(tab.connectionId);
  }

  return {
    databaseOptionsForTab,
    databaseOptionsForTableDataTab,
    databaseOptionsForTableEditorTab,
    handleQueryConnectionChange,
    handleQueryDatabaseChange,
    handleTableEditorConnectionChange,
    handleTableEditorDatabaseChange,
    openAiConfigModal,
    pickRagEmbeddingModelDir,
    pickRagRerankModelDir,
    queryDbTypeByConnectionId,
    queryTabConnectionName,
    queryTabDbType,
    saveAiConfig,
  };
}

interface ConnectionCrudHelperContext {
  canPreviewDatabases: () => boolean;
  clearObjectDetail: () => void;
  connectionForm: ConnectionCreateReq;
  connectionFormErrors: () => ConnectionFormErrorMap;
  connectionFormSubmitted: Ref<boolean>;
  connectionGroups: Ref<ConnectionGroupVO[]>;
  connectionPreviewDbOptions: Ref<string[]>;
  connectionPreviewError: Ref<string>;
  connectionPreviewLoading: Ref<boolean>;
  connections: Ref<ConnectionVO[]>;
  createModalOpen: Ref<boolean>;
  currentObjectType: Ref<ObjectRow['objectType']>;
  editingConnectionId: Ref<number | null>;
  ensureActiveWorkbenchTab: () => void;
  ensureConnection: () => void;
  erSnapshotActionLoadingId: Ref<number | null>;
  erSnapshotConnectionId: Ref<number>;
  erSnapshotHasMore: Ref<boolean>;
  erSnapshotItems: Ref<ErGraphSnapshotSummaryVO[]>;
  erSnapshotKeyword: Ref<string>;
  erSnapshotKeywordInput: Ref<string>;
  erSnapshotLoadingId: Ref<number | null>;
  erSnapshotPageNo: Ref<number>;
  erTabs: Ref<ErWorkspaceTab[]>;
  getActiveDatabaseName: (connectionId: number) => string;
  groupForm: { mode: 'create' | 'rename'; groupId: number; name: string };
  groupModalOpen: Ref<boolean>;
  groupModalSubmitting: Ref<boolean>;
  hasConnectionFormErrors: () => boolean;
  invalidateConnectionMetadataCaches: (connectionId: number) => void;
  isEditMode: Ref<boolean>;
  isMultiDatabaseType: (dbType: string) => boolean;
  loadConnectionGroups: () => Promise<void>;
  loadConnections: () => Promise<void>;
  loadOverview: () => Promise<void>;
  objectDefinitionEditorTabs: Ref<ObjectDefinitionEditorTab[]>;
  queryTabs: Ref<QueryWorkspaceTab[]>;
  resetConnectionModalState: () => void;
  resetConnectionRuntimeState: (connectionId: number) => void;
  resetErSnapshotTitleEditState: () => void;
  runSafely: (runner: () => Promise<void>) => Promise<void>;
  schemaOverview: Ref<SchemaOverviewVO | null>;
  selectedTreeKeys: Ref<string[]>;
  tableDataTabs: Ref<TableDataWorkspaceTab[]>;
  tableEditorTabs: Ref<TableEditorWorkspaceTab[]>;
  workflow: { connectionId: number };
}

export function createConnectionCrudHelpers(ctx: ConnectionCrudHelperContext) {
  function openCreateGroupModal() {
    ctx.groupForm.mode = 'create';
    ctx.groupForm.groupId = 0;
    ctx.groupForm.name = '';
    ctx.groupModalOpen.value = true;
  }

  function openRenameGroupModal(groupId: number) {
    const current = ctx.connectionGroups.value.find((item) => item.id === groupId);
    if (!current) {
      message.warning('未找到连接分组');
      return;
    }
    ctx.groupForm.mode = 'rename';
    ctx.groupForm.groupId = groupId;
    ctx.groupForm.name = current.name;
    ctx.groupModalOpen.value = true;
  }

  function closeGroupModal() {
    ctx.groupModalOpen.value = false;
    ctx.groupModalSubmitting.value = false;
    ctx.groupForm.mode = 'create';
    ctx.groupForm.groupId = 0;
    ctx.groupForm.name = '';
  }

  async function confirmGroupModal() {
    await ctx.runSafely(async () => {
      const name = ctx.groupForm.name.trim();
      if (!name) {
        message.warning('请输入分组名称');
        return;
      }
      ctx.groupModalSubmitting.value = true;
      try {
        if (ctx.groupForm.mode === 'create') {
          await postApi<ConnectionGroupVO>('/api/connection/group/create', {
            name,
          } satisfies ConnectionGroupCreateReq);
          message.success('分组已创建');
        } else {
          await postApi<ConnectionGroupVO>('/api/connection/group/rename', {
            groupId: ctx.groupForm.groupId,
            name,
          } satisfies ConnectionGroupRenameReq);
          message.success('分组已更新');
        }
        closeGroupModal();
        await ctx.loadConnectionGroups();
      } finally {
        ctx.groupModalSubmitting.value = false;
      }
    });
  }

  async function removeConnectionGroup(groupId: number) {
    await ctx.runSafely(async () => {
      await postApi<boolean>('/api/connection/group/remove', {
        groupId,
      } satisfies ConnectionGroupRemoveReq);
      message.success('分组已删除');
      await ctx.loadConnections();
    });
  }

  async function moveConnectionGroup(connectionId: number, targetGroupId: number) {
    await postApi<boolean>('/api/connection/group/move', {
      connectionId,
      targetGroupId,
    } satisfies ConnectionGroupMoveReq);
  }

  async function saveConnection() {
    ctx.connectionFormSubmitted.value = true;
    if (ctx.hasConnectionFormErrors()) {
      const firstError = Object.values(ctx.connectionFormErrors()).find((item) => !!item);
      message.warning(firstError || '请完善必填项后再保存');
      return;
    }
    await ctx.runSafely(async () => {
      const editing = ctx.isEditMode.value;
      const normalizedSelectedDatabases = ctx.isMultiDatabaseType(ctx.connectionForm.dbType)
        ? normalizeSelectedDatabases(ctx.connectionForm.selectedDatabases)
        : [];
      const sshAuthType = ctx.connectionForm.sshEnabled ? (ctx.connectionForm.sshAuthType || 'SSH_PASSWORD') : undefined;
      const payload: ConnectionCreateReq & { id?: number } = {
        ...ctx.connectionForm,
        selectedDatabases: normalizedSelectedDatabases,
        customParams: (ctx.connectionForm.customParams || '').trim(),
        sshAuthType,
        sshPassword: ctx.connectionForm.sshEnabled && sshAuthType === 'SSH_PASSWORD'
          ? (ctx.connectionForm.sshPassword || '').trim()
          : '',
        sshPrivateKeyPath: ctx.connectionForm.sshEnabled && sshAuthType === 'SSH_KEY_PATH'
          ? (ctx.connectionForm.sshPrivateKeyPath || '').trim()
          : '',
        sshPrivateKeyText: ctx.connectionForm.sshEnabled && sshAuthType === 'SSH_KEY_TEXT'
          ? (ctx.connectionForm.sshPrivateKeyText || '').trim()
          : '',
        sshPrivateKeyPassphrase: ctx.connectionForm.sshEnabled
          && (sshAuthType === 'SSH_KEY_PATH' || sshAuthType === 'SSH_KEY_TEXT')
          ? (ctx.connectionForm.sshPrivateKeyPassphrase || '').trim()
          : '',
      };
      if (!ctx.isMultiDatabaseType(payload.dbType)) {
        payload.selectedDatabases = [];
      }
      const endpoint = editing ? '/api/connection/update' : '/api/connection/create';
      if (editing) {
        payload.id = ctx.editingConnectionId.value ?? undefined;
        if (!payload.id) {
          throw new Error('缺少待编辑连接 ID');
        }
      }
      const saved = await postApi<ConnectionVO>(endpoint, payload);
      ctx.createModalOpen.value = false;
      ctx.resetConnectionModalState();
      ctx.workflow.connectionId = saved.id;
      ctx.selectedTreeKeys.value = [`conn-${saved.id}`];
      message.success(editing ? '连接已更新' : '连接已创建');
      await ctx.loadConnections();
    });
  }

  async function previewConnectionDatabases() {
    if (!ctx.canPreviewDatabases()) {
      return;
    }
    ctx.connectionPreviewLoading.value = true;
    ctx.connectionPreviewError.value = '';
    try {
      const payload: ConnectionDatabasePreviewReq = {
        dbType: ctx.connectionForm.dbType,
        host: (ctx.connectionForm.host || '').trim(),
        port: ctx.connectionForm.port,
        databaseName: (ctx.connectionForm.databaseName || '').trim(),
        customParams: (ctx.connectionForm.customParams || '').trim(),
        username: (ctx.connectionForm.username || '').trim(),
        password: (ctx.connectionForm.password || '').trim(),
        sshEnabled: ctx.connectionForm.sshEnabled,
        sshHost: (ctx.connectionForm.sshHost || '').trim(),
        sshPort: ctx.connectionForm.sshPort,
        sshUser: (ctx.connectionForm.sshUser || '').trim(),
        sshAuthType: ctx.connectionForm.sshEnabled ? (ctx.connectionForm.sshAuthType || 'SSH_PASSWORD') : undefined,
        sshPassword: ctx.connectionForm.sshEnabled && (ctx.connectionForm.sshAuthType || 'SSH_PASSWORD') === 'SSH_PASSWORD'
          ? (ctx.connectionForm.sshPassword || '').trim()
          : '',
        sshPrivateKeyPath: ctx.connectionForm.sshEnabled && ctx.connectionForm.sshAuthType === 'SSH_KEY_PATH'
          ? (ctx.connectionForm.sshPrivateKeyPath || '').trim()
          : '',
        sshPrivateKeyText: ctx.connectionForm.sshEnabled && ctx.connectionForm.sshAuthType === 'SSH_KEY_TEXT'
          ? (ctx.connectionForm.sshPrivateKeyText || '').trim()
          : '',
        sshPrivateKeyPassphrase: ctx.connectionForm.sshEnabled
          && (ctx.connectionForm.sshAuthType === 'SSH_KEY_PATH' || ctx.connectionForm.sshAuthType === 'SSH_KEY_TEXT')
          ? (ctx.connectionForm.sshPrivateKeyPassphrase || '').trim()
          : '',
      };
      const result = await postApi<ConnectionDatabasePreviewVO>('/api/connection/databases/preview', payload);
      ctx.connectionPreviewDbOptions.value = Array.from(
        new Set((result.databaseNames ?? []).map((item) => (item || '').trim()).filter((item) => !!item)),
      );
    } catch (error) {
      const msg = error instanceof Error ? error.message : String(error);
      ctx.connectionPreviewError.value = msg || '获取数据库失败';
    } finally {
      ctx.connectionPreviewLoading.value = false;
    }
  }

  async function testConnection(id: number) {
    await ctx.runSafely(async () => {
      const result = await postApi<{ success: boolean; message: string }>('/api/connection/test', {connectionId: id});
      message.info(result.message);
      await ctx.loadConnections();
    });
  }

  async function disconnectConnection(id: number) {
    await ctx.runSafely(async () => {
      await postApi<boolean>('/api/connection/disconnect', {id});
      ctx.resetConnectionRuntimeState(id);
      message.success('连接已关闭');
    });
  }

  async function removeConnection(id: number) {
    await ctx.runSafely(async () => {
      await postApi<boolean>('/api/connection/remove', {id});
      message.success('连接已删除');
      ctx.invalidateConnectionMetadataCaches(id);
      if (ctx.workflow.connectionId === id) {
        ctx.workflow.connectionId = 0;
        ctx.schemaOverview.value = null;
        ctx.clearObjectDetail();
      }
      ctx.queryTabs.value = ctx.queryTabs.value.filter((item) => item.connectionId !== id);
      ctx.erTabs.value = ctx.erTabs.value.filter((item) => item.connectionId !== id);
      ctx.tableEditorTabs.value = ctx.tableEditorTabs.value.filter((item) => item.connectionId !== id);
      ctx.tableDataTabs.value = ctx.tableDataTabs.value.filter((item) => item.connectionId !== id);
      ctx.objectDefinitionEditorTabs.value = ctx.objectDefinitionEditorTabs.value.filter((item) => item.connectionId !== id);
      if (ctx.erSnapshotConnectionId.value === id) {
        ctx.erSnapshotConnectionId.value = 0;
        ctx.erSnapshotItems.value = [];
        ctx.erSnapshotPageNo.value = 1;
        ctx.erSnapshotHasMore.value = true;
        ctx.erSnapshotKeywordInput.value = '';
        ctx.erSnapshotKeyword.value = '';
        ctx.resetErSnapshotTitleEditState();
        ctx.erSnapshotLoadingId.value = null;
        ctx.erSnapshotActionLoadingId.value = null;
      }
      ctx.ensureActiveWorkbenchTab();
      await ctx.loadConnections();
    });
  }

  async function syncSchema(targetConnectionId?: number) {
    if (targetConnectionId) {
      ctx.workflow.connectionId = targetConnectionId;
      ctx.selectedTreeKeys.value = [`conn-${targetConnectionId}`];
    }
    ctx.ensureConnection();
    await ctx.runSafely(async () => {
      const databaseName = ctx.getActiveDatabaseName(ctx.workflow.connectionId);
      const result = await postApi<{ success: boolean; tableCount: number; columnCount: number; message: string }>(
        '/api/schema/sync',
        {connectionId: ctx.workflow.connectionId, databaseName},
      );
      message.success(`${result.message}，表 ${result.tableCount}，字段 ${result.columnCount}`);
      ctx.currentObjectType.value = 'tables';
      await ctx.loadOverview();
    });
  }

  return {
    closeGroupModal,
    confirmGroupModal,
    disconnectConnection,
    moveConnectionGroup,
    openCreateGroupModal,
    openRenameGroupModal,
    previewConnectionDatabases,
    removeConnection,
    removeConnectionGroup,
    saveConnection,
    syncSchema,
    testConnection,
  };
}

type ConnectionRuntimeStatus = 'idle' | 'connected' | 'failed';

interface ConnectionLoadingHelperContext {
  activeWorkbenchTab: Ref<string>;
  browserTabKey: string;
  clearAllTableStatsPollingTimers: () => void;
  clearObjectDetail: () => void;
  connectionForm: ConnectionCreateReq;
  connectionGroups: Ref<ConnectionGroupVO[]>;
  connectionRefreshing: Ref<boolean>;
  connectionRuntimeStatusMap: Ref<Record<number, ConnectionRuntimeStatus>>;
  connections: Ref<ConnectionVO[]>;
  currentObjectType: Ref<ObjectRow['objectType']>;
  ensureActiveWorkbenchTab: () => void;
  ensureConnectionFormDbType: () => void;
  ensureConnectionTreeExpanded: (connectionId: number, options?: { showError?: boolean }) => Promise<boolean>;
  erSnapshotActionLoadingId: Ref<number | null>;
  erSnapshotConnectionId: Ref<number>;
  erSnapshotHasMore: Ref<boolean>;
  erSnapshotItems: Ref<ErGraphSnapshotSummaryVO[]>;
  erSnapshotKeyword: Ref<string>;
  erSnapshotKeywordInput: Ref<string>;
  erSnapshotLoadingId: Ref<number | null>;
  erSnapshotPageNo: Ref<number>;
  erTabs: Ref<ErWorkspaceTab[]>;
  expandedTreeKeys: Ref<string[]>;
  getActiveDatabaseName: (connectionId: number) => string;
  historyKeyword: Ref<string>;
  historyKeywordInput: Ref<string>;
  historySessionConnectionId: Ref<number>;
  historySessionHasMore: Ref<boolean>;
  historySessionItems: Ref<QueryHistorySessionVO[]>;
  historySessionPageNo: Ref<number>;
  isDatabaseContextVisibleForConnection: (connection: ConnectionVO, databaseName: string) => boolean;
  isFrontendVisibleDbType: (dbType: string) => boolean;
  isMultiDatabaseType: (dbType: string) => boolean;
  kvOverview: Ref<KvOverviewVO | null>;
  loadOverview: () => Promise<void>;
  objectDefinitionEditorTabs: Ref<ObjectDefinitionEditorTab[]>;
  pruneVectorizeStatusMap: (validConnectionIds: number[]) => void;
  queryTabs: Ref<QueryWorkspaceTab[]>;
  refreshAllVectorizeStatuses: (targetConnectionIds?: number[]) => Promise<void>;
  requiresDatabaseLayer: (connection: ConnectionVO) => boolean;
  resetErSnapshotTitleEditState: () => void;
  runSafely: (runner: () => Promise<void>) => Promise<void>;
  schemaOverview: Ref<SchemaOverviewVO | null>;
  selectedObjectName: Ref<string>;
  selectedTreeKeys: Ref<string[]>;
  setConnectionRuntimeStatus: (connectionId: number, status: ConnectionRuntimeStatus) => void;
  supportedDbTypes: Ref<ConnectionDbTypeVO[]>;
  tableDataTabs: Ref<TableDataWorkspaceTab[]>;
  tableEditorTabs: Ref<TableEditorWorkspaceTab[]>;
  tableStatsCache: Ref<Record<string, unknown>>;
  tableStatsLastRequestAt: Ref<Record<string, number>>;
  tableStatsLoadingState: Ref<Record<string, boolean>>;
  visibleDatabasesForConnection: (connection: ConnectionVO) => string[];
  workflow: { connectionId: number };
}

export function createConnectionLoadingHelpers(ctx: ConnectionLoadingHelperContext) {
  async function loadSupportedDbTypes() {
    const list = await getApi<ConnectionDbTypeVO[]>('/api/connection/db-types');
    const hiddenFallbackDbTypes = new Set(
      FALLBACK_SUPPORTED_DB_TYPES
        .map((item) => item.dbType)
        .filter((dbType) => !ctx.isFrontendVisibleDbType(dbType)),
    );
    ctx.supportedDbTypes.value = mergeSupportedDbTypes(
      list.filter((item) => ctx.isFrontendVisibleDbType(item.dbType)),
      hiddenFallbackDbTypes,
    );
    ctx.ensureConnectionFormDbType();
  }

  async function loadConnectionGroups() {
    const list = await getApi<ConnectionGroupVO[]>('/api/connection/group/list');
    ctx.connectionGroups.value = list;
    if (!ctx.connectionForm.groupId && list.length) {
      ctx.connectionForm.groupId = list[0].id;
    }
  }

  async function loadConnections() {
    ctx.connectionRefreshing.value = true;
    try {
      await loadConnectionGroups();
      const list = (await getApi<ConnectionVO[]>('/api/connection/list'))
        .filter((item) => ctx.isFrontendVisibleDbType(item.dbType));
      ctx.connections.value = list;
      const nextRuntimeStatus: Record<number, ConnectionRuntimeStatus> = {};
      list.forEach((item) => {
        nextRuntimeStatus[item.id] = ctx.connectionRuntimeStatusMap.value[item.id] || 'idle';
      });
      ctx.connectionRuntimeStatusMap.value = nextRuntimeStatus;
      const visibleConnectionIds = new Set(list.map((item) => item.id));
      ctx.queryTabs.value = ctx.queryTabs.value.filter((item) => visibleConnectionIds.has(item.connectionId));
      ctx.erTabs.value = ctx.erTabs.value.filter((item) => visibleConnectionIds.has(item.connectionId));
      ctx.tableEditorTabs.value = ctx.tableEditorTabs.value.filter((item) => visibleConnectionIds.has(item.connectionId));
      ctx.tableDataTabs.value = ctx.tableDataTabs.value.filter((item) => visibleConnectionIds.has(item.connectionId));
      ctx.objectDefinitionEditorTabs.value = ctx.objectDefinitionEditorTabs.value.filter((item) => visibleConnectionIds.has(item.connectionId));
      ctx.queryTabs.value.forEach((tab) => {
        const connection = list.find((item) => item.id === tab.connectionId);
        if (!connection || !ctx.isMultiDatabaseType(connection.dbType)) {
          return;
        }
        const visibleNames = ctx.visibleDatabasesForConnection(connection);
        if (tab.databaseName && visibleNames.length && !ctx.isDatabaseContextVisibleForConnection(connection, tab.databaseName)) {
          tab.databaseName = '';
        }
      });
      ctx.erTabs.value.forEach((tab) => {
        const connection = list.find((item) => item.id === tab.connectionId);
        if (!connection || !ctx.isMultiDatabaseType(connection.dbType)) {
          return;
        }
        const visibleNames = ctx.visibleDatabasesForConnection(connection);
        if (tab.databaseName && visibleNames.length && !ctx.isDatabaseContextVisibleForConnection(connection, tab.databaseName)) {
          tab.databaseName = '';
        }
      });
      ctx.tableDataTabs.value.forEach((tab) => {
        const connection = list.find((item) => item.id === tab.connectionId);
        if (!connection || !ctx.isMultiDatabaseType(connection.dbType)) {
          return;
        }
        const visibleNames = ctx.visibleDatabasesForConnection(connection);
        if (tab.databaseName && visibleNames.length && !ctx.isDatabaseContextVisibleForConnection(connection, tab.databaseName)) {
          tab.databaseName = '';
        }
      });
      ctx.pruneVectorizeStatusMap(list.map((item) => item.id));
      if (!list.length) {
        ctx.workflow.connectionId = 0;
        ctx.selectedTreeKeys.value = [];
        ctx.expandedTreeKeys.value = [];
        ctx.clearAllTableStatsPollingTimers();
        ctx.tableStatsCache.value = {};
        ctx.tableStatsLoadingState.value = {};
        ctx.tableStatsLastRequestAt.value = {};
        ctx.schemaOverview.value = null;
        ctx.kvOverview.value = null;
        ctx.queryTabs.value = [];
        ctx.erTabs.value = [];
        ctx.tableDataTabs.value = [];
        ctx.activeWorkbenchTab.value = ctx.browserTabKey;
        ctx.historySessionConnectionId.value = 0;
        ctx.historySessionItems.value = [];
        ctx.historySessionPageNo.value = 1;
        ctx.historySessionHasMore.value = true;
        ctx.historyKeywordInput.value = '';
        ctx.historyKeyword.value = '';
        ctx.erSnapshotConnectionId.value = 0;
        ctx.erSnapshotItems.value = [];
        ctx.erSnapshotPageNo.value = 1;
        ctx.erSnapshotHasMore.value = true;
        ctx.erSnapshotKeywordInput.value = '';
        ctx.erSnapshotKeyword.value = '';
        ctx.resetErSnapshotTitleEditState();
        ctx.erSnapshotLoadingId.value = null;
        ctx.erSnapshotActionLoadingId.value = null;
        return;
      }
      await ctx.refreshAllVectorizeStatuses(list.map((item) => item.id));
      if (!ctx.workflow.connectionId || !list.some((item) => item.id === ctx.workflow.connectionId)) {
        ctx.workflow.connectionId = list[0].id;
      }
      if (ctx.historySessionConnectionId.value && !list.some((item) => item.id === ctx.historySessionConnectionId.value)) {
        ctx.historySessionConnectionId.value = 0;
        ctx.historySessionItems.value = [];
        ctx.historySessionPageNo.value = 1;
        ctx.historySessionHasMore.value = true;
        ctx.historyKeywordInput.value = '';
        ctx.historyKeyword.value = '';
      }
      if (ctx.erSnapshotConnectionId.value && !list.some((item) => item.id === ctx.erSnapshotConnectionId.value)) {
        ctx.erSnapshotConnectionId.value = 0;
        ctx.erSnapshotItems.value = [];
        ctx.erSnapshotPageNo.value = 1;
        ctx.erSnapshotHasMore.value = true;
        ctx.erSnapshotKeywordInput.value = '';
        ctx.erSnapshotKeyword.value = '';
        ctx.resetErSnapshotTitleEditState();
        ctx.erSnapshotLoadingId.value = null;
        ctx.erSnapshotActionLoadingId.value = null;
      }
      ctx.currentObjectType.value = 'tables';
      ctx.selectedObjectName.value = '';
      ctx.clearObjectDetail();
      const expanded = await ctx.ensureConnectionTreeExpanded(ctx.workflow.connectionId, {showError: false});
      ctx.selectedTreeKeys.value = [`conn-${ctx.workflow.connectionId}`];
      const current = ctx.connections.value.find((item) => item.id === ctx.workflow.connectionId);
      if (expanded && current && (!ctx.requiresDatabaseLayer(current) || ctx.getActiveDatabaseName(ctx.workflow.connectionId))) {
        await ctx.loadOverview();
      } else {
        ctx.schemaOverview.value = null;
      }
      ctx.ensureActiveWorkbenchTab();
    } finally {
      ctx.connectionRefreshing.value = false;
    }
  }

  async function refreshConnections() {
    await ctx.runSafely(async () => {
      await loadConnections();
    });
  }

  return {
    loadConnectionGroups,
    loadConnections,
    loadSupportedDbTypes,
    refreshConnections,
  };
}
