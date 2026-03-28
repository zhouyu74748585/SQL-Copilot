import {
  CheckCircleOutlined,
  ClockCircleOutlined,
  CloseCircleOutlined,
  LoadingOutlined,
  MinusCircleOutlined,
} from '@ant-design/icons-vue';
import type * as MonacoApi from 'monaco-editor';
import type {IDisposable} from 'monaco-editor';
import type {ConnectionVO} from '@sqlcopilot/shared-contracts';
import {message} from 'ant-design-vue';
import {computed, h, reactive, ref} from 'vue';
import {postApi, postSseApi} from '../../../../api/client';
import QueryChartPanel from '../../../../components/QueryChartPanel.vue';
import ErDiagramPanel from '../../../../components/ErDiagramPanel.vue';
import {translateText, useAppI18n} from '../../../../i18n';
import {
  getRagProviderOptions,
  ragLocalOnnxEnabled,
} from '../../../../config/packageVariant';
import type {
  AiConfigSaveReq,
  AiTextResponseVO,
  ChartType,
  ConnectionCreateReq,
  ConnectionGroupVO,
  ConnectionDbTypeVO,
  ErGraphSnapshotSummaryVO,
  ErGraphVO,
  ErLayoutMode,
  QueryHistorySessionVO,
  KvObjectDetailVO,
  KvOverviewVO,
  KvRedisBrowserPageVO,
  RagConfigSaveReq,
  RagDatabaseVectorizeStatusVO,
  RagVectorizeOverviewVO,
  SchemaObjectDefinitionVO,
  SavedQueryVO,
  SchemaOverviewVO,
  SchemaTableStatsVO,
  SortDirection,
  TableCopyMode,
  TableCopyTaskVO,
  TableDetailVO,
} from '../../../../types';
import {
  AI_REQUEST_ABORTED,
  RISK_EXECUTION_CANCELLED,
  SQL_EXECUTION_ABORTED,
  aiRequestTimeoutMs,
  browserTabKey,
  chartExportPixelRatioCandidates,
  darkAlgorithm,
  defaultAlgorithm,
  erDiagramExportPixelRatioCandidates,
  isLinux,
  isMacOS,
  isWindows,
  uiThemeStorageKey,
} from './constants';
import {
  namespaceCacheKey,
  objectCacheKey,
  tableCacheKey,
  vectorizeStatusCacheKey,
} from './cache';
import {
  createConnectionCrudHelpers,
  createConnectionLoadingHelpers,
  createConnectionStatusHelpers,
  createConnectionUiHelpers,
  createConnectionRuntimeHelpers,
  createConnectionSettingsHelpers,
  defaultAiConfigForm,
  defaultConnectionForm,
  defaultRagConfigForm,
  getDesktopBridge,
  loadLastResultExportDirectory,
  saveLastResultExportDirectory,
} from './connections';
import {
  buildExecutionPreview,
  chatExecutionColumns,
  buildResultTableCache,
  canExportActiveQueryResult,
  clampGridColumnWidth,
  clearStatementResults,
  cloneChartConfig,
  createChartRuntimeHelpers,
  createQueryResultViewHelpers,
  emptyManualChartConfig,
  getActiveStatementResultForTab,
  isChartCacheRetryableError,
  isLikelyLocalFilePath,
  isNumericField,
  normalizeChartCacheErrorMessage,
  normalizeDownloadFileNamePart,
  normalizeManualChartConfig,
  resultTabTitle,
  setActiveStatementResult,
  syncActiveStatementResultFromTab,
} from './charts';
import {
  createRedisBrowserRuntimeHelpers,
  createRedisKeyModalHelpers,
  findRedisBrowserRowByNodeKey,
  findRedisSelectedObjectRow,
  flattenRedisBrowserRows,
} from './redis-browser';
import {
  createSqlEditorInteractionHelpers,
  createSqlEditorRuntimeHelpers,
  extractSqlTableReferences,
  parseQualifiedColumnContext,
  resolveSelectedSqlSnippet,
  resolveQualifiedTableReference,
  sqlKeywords,
} from './sql-editor';
import {
  buildCategoryNodeKey,
  buildDatabaseNodeKey,
  buildDatabaseRootNodeKey,
  buildObjectNodeKey,
  createConnectionBrowserStateHelpers,
  createSchemaBrowserActionHelpers,
  createSchemaBrowserHelpers,
  createSchemaBrowserStatusHelpers,
  createSchemaContextMenuHelpers,
  createSchemaObjectDetailHelpers,
  createSchemaObjectBrowserHelpers,
  createSchemaOverviewRuntimeHelpers,
  createSchemaTreeNodeHelpers,
  createSchemaTreeSelectionHelpers,
  createSchemaTreeHelpers,
} from './schema-browser';
import {
  browserObjectIconSrc,
  buildColumnSqlDefinition,
  buildCreateTableSql,
  buildObjectQuerySql,
  createObjectPresentationHelpers,
  containsDatabaseInHostInput,
  copyTextContent,
  dbIconUrl,
  escapeHtml,
  quoteSqlIdentifier,
  envTagClass,
  envTagIcon,
  envTagText,
  formatVectorizeProvider,
  getDatabaseNamePlaceholder,
  highlightSqlForDisplay,
  nodeIconComponent,
  normalizeEnv,
  normalizeSelectedDatabases,
  parseConfiguredDatabaseName,
  parseSchemaContext,
  sanitizeDatabaseName,
  supportsSchemaLayer,
  treeNodeIconUrl,
  treeTitleIconSrc,
} from './utils';
import {
  buildChartPrompt,
  chartTypeLabel,
  chartSummaryText,
  createAiInteractionHelpers,
  dedupeChartMessageContent,
  enrichPromptWithSchemaReferences,
  isChartConfigRenderable,
} from './ai-interaction';
import {createAiRequestHelpers} from './ai-request';
import {createQueryChatHelpers} from './query-chat';
import {
  createQueryExecutionHelpers,
  normalizeRiskLevel,
  resolveSqlForAction,
  riskColor,
  splitSqlStatements,
} from './query-execution';
import {createHistoryRuntimeHelpers} from './history';
import {createQueryWorkbenchHelpers} from './query-workbench';
import {
  buildErRelationKey,
  createErDiagramRuntimeHelpers,
  normalizeErRelationConfidence,
  touchErTab,
} from './er-diagram';
import type {
  AiActionType,
  ConnectionFormErrorMap,
  ErLineType,
  ErWorkspaceTab,
  KnowledgeWorkspaceTab,
  MemoryWorkspaceTab,
  ObjectDefinitionEditorTab,
  ObjectRow,
  QueryActionType,
  QueryChatMessage,
  QueryContextUsage,
  QueryExecutionPreview,
  QueryResultTableColumn,
  QueryResultTableRow,
  QueryResultViewMode,
  QueryStatementResult,
  QueryWorkspaceTab,
  RequestAbortReason,
  RetryRequestMeta,
  SqlEditorContext,
  SqlEditorMountOptions,
  TableCopyClipboard,
  TableDataFilterOperator,
  TableDataWorkspaceTab,
  TableEditorWorkspaceTab,
  UiTheme,
} from './types';

export function createStudioRuntimeState() {
const {currentLocale} = useAppI18n();
const ragProviderTypeOptions = computed(() => getRagProviderOptions(currentLocale.value));
const tt = (text: string) => {
  void currentLocale.value;
  return translateText(text);
};let vectorizeStatusPollTimer: number | null = null;

const vectorizeStatusPollIntervalMs = 30000;

const tableStatsMinRequestIntervalMs = 30000;

const tableStatsPollIntervalMs = 1500;

const FRONTEND_HIDDEN_DB_TYPES = new Set(['MONGODB']);

const connections = ref<ConnectionVO[]>([]);

const connectionGroups = ref<ConnectionGroupVO[]>([]);

const connectionRuntimeStatusMap = ref<Record<number, 'idle' | 'connected' | 'failed'>>({});

const schemaOverview = ref<SchemaOverviewVO | null>(null);

const kvOverview = ref<KvOverviewVO | null>(null);

const selectedObjectName = ref('');

const createModalOpen = ref(false);

const isEditMode = ref(false);

const editingConnectionId = ref<number | null>(null);

const connectionRefreshing = ref(false);

const connectionKeyword = ref('');

const tableKeyword = ref('');

const browserNavMode = ref<'connections' | 'knowledge'>('connections');

const selectedTreeKeys = ref<string[]>([]);

const expandedTreeKeys = ref<string[]>([]);

const tableNameCache = ref<Record<string, string[]>>({});

const tableNameLoadedCache = ref<Record<string, boolean>>({});

const queryTableDetailCache = ref<Record<string, TableDetailVO>>({});

const objectNameCache = ref<Record<string, string[]>>({});

const savedQueryCache = ref<Record<string, SavedQueryVO[]>>({});

const browserObjectNameList = ref<string[]>([]);

const browserSavedQueryList = ref<SavedQueryVO[]>([]);

const saveQueryModalOpen = ref(false);

const saveQuerySubmitting = ref(false);

const saveQueryTitle = ref('');

const tableStatsCache = ref<Record<string, Record<string, { rowEstimate: number; tableSizeBytes: number }>>>({});

const tableStatsLoadingState = ref<Record<string, boolean>>({});

const tableStatsLastRequestAt = ref<Record<string, number>>({});

const databaseListCache = ref<Record<number, string[]>>({});

const namespaceListCache = ref<Record<string, string[]>>({});

const namespaceListLoadedCache = ref<Record<string, boolean>>({});

const activeDatabaseMap = ref<Record<number, string>>({});

const databaseVectorizeStatusMap = ref<Record<string, RagDatabaseVectorizeStatusVO>>({});

const currentObjectType = ref<'tables' | 'views' | 'functions' | 'events' | 'queries' | 'backups'>('tables');

const objectViewMode = ref<'row' | 'grid'>('row');

const viewportHeight = ref(typeof window === 'undefined' ? 900 : window.innerHeight);

const vectorizeOverviewModalOpen = ref(false);

const vectorizeOverviewLoading = ref(false);

const vectorizeOverviewData = ref<RagVectorizeOverviewVO | null>(null);

// 删除/清空表相关状态
const truncateTableModalOpen = ref(false);

const truncateTableName = ref('');

const dropTableModalOpen = ref(false);

const dropTableName = ref('');

const groupModalOpen = ref(false);

const groupModalSubmitting = ref(false);

const groupForm = reactive({
  mode: 'create' as 'create' | 'rename',
  groupId: 0,
  name: '',
});

const renameTableModalOpen = ref(false);

const renameTableSubmitting = ref(false);

const namespaceModalOpen = ref(false);

const namespaceModalSubmitting = ref(false);

const namespaceForm = reactive({
  mode: 'create' as 'create' | 'rename',
  scope: 'namespace' as 'namespace' | 'database' | 'schema',
  connectionId: 0,
  databaseName: '',
  namespaceLabel: '命名空间',
  sourceNamespaceName: '',
  targetNamespaceName: '',
});

const renameTableForm = reactive({
  connectionId: 0,
  databaseName: '',
  sourceTableName: '',
  targetTableName: '',
});

const browserDetailCollapsed = ref(false);

const tableCopyClipboard = ref<TableCopyClipboard | null>(null);

const tablePasteModalOpen = ref(false);

const tablePasteSubmitting = ref(false);

const tablePasteForm = reactive({
  sourceConnectionId: 0,
  sourceDatabaseName: '',
  sourceTableName: '',
  sourceDbType: '',
  targetConnectionId: 0,
  targetDatabaseName: '',
  targetTableName: '',
  preferredCopyMode: 'STRUCTURE_AND_DATA' as TableCopyMode,
  copyData: true,
});

const tableCopyTaskModalOpen = ref(false);

const tableCopyTaskInfo = ref<TableCopyTaskVO | null>(null);

const aiConfigModalOpen = ref(false);

const aiConfigActiveTab = ref<'general' | 'model' | 'embedding'>('model');

const uiTheme = ref<UiTheme>('light');

const selectedAiModel = ref('');

const activeWorkbenchTab = ref(browserTabKey);

const queryTabs = ref<QueryWorkspaceTab[]>([]);

const erTabs = ref<ErWorkspaceTab[]>([]);

const tableEditorTabs = ref<TableEditorWorkspaceTab[]>([]);

const tableDataTabs = ref<TableDataWorkspaceTab[]>([]);

const objectDefinitionEditorTabs = ref<ObjectDefinitionEditorTab[]>([]);

const knowledgeTabs = ref<KnowledgeWorkspaceTab[]>([]);

const memoryTabs = ref<MemoryWorkspaceTab[]>([]);

const erTableSelectModalOpen = ref(false);

const erTableSelectSubmitting = ref(false);

const erSelectConnectionId = ref(0);

const erSelectDatabaseName = ref('');

const erSelectTargetTabKey = ref('');

const erSelectTableKeyword = ref('');

const erSelectTableOptions = ref<string[]>([]);

const erSelectTableValues = ref<string[]>([]);

const erSelectModelName = ref('');

const historyReloading = ref(false);

const historyLoadingMore = ref(false);

const historySessionLoadingKey = ref('');

const historyKeywordInput = ref('');

const historyKeyword = ref('');

const historySessionItems = ref<QueryHistorySessionVO[]>([]);

const historySessionPageNo = ref(1);

const historySessionPageSize = 20;

const historySessionHasMore = ref(true);

const historySessionConnectionId = ref(0);

const erSnapshotReloading = ref(false);

const erSnapshotLoadingMore = ref(false);

const erSnapshotLoadingId = ref<number | null>(null);

const erSnapshotActionLoadingId = ref<number | null>(null);

const erSnapshotKeywordInput = ref('');

const erSnapshotKeyword = ref('');

const erSnapshotItems = ref<ErGraphSnapshotSummaryVO[]>([]);

const erSnapshotPageNo = ref(1);

const erSnapshotPageSize = 20;

const erSnapshotHasMore = ref(true);

const erSnapshotConnectionId = ref(0);

const erSnapshotSaveModalOpen = ref(false);

const erSnapshotSaveSubmitting = ref(false);

const erSnapshotSaveName = ref('');

const erSnapshotSaveTabKey = ref('');

const editingErSnapshotId = ref<number | null>(null);

const editingErSnapshotTitle = ref('');

const editingHistoryTabKey = ref('');

const editingHistoryTitle = ref('');

const sessionTitleOverrides = ref<Record<string, string>>({});

const tableDetail = ref<TableDetailVO | null>(null);

const tableDetailLoading = ref(false);

const kvObjectDetail = ref<KvObjectDetailVO | null>(null);

const kvObjectDetailLoading = ref(false);

const redisHierarchyPath = ref('');

const redisBrowserRows = ref<ObjectRow[]>([]);

const redisBrowserLoading = ref(false);

const redisExpandedRowKeys = ref<string[]>([]);

const redisBrowseExpandedRowKeys = ref<string[]>([]);

const redisSelectedRowKey = ref('');

const redisBrowserContextKey = ref('');

const redisBrowserPageCache = ref<Record<string, KvRedisBrowserPageVO>>({});

const redisBrowserChildLoadingMap = ref<Record<string, boolean>>({});

const redisKeyModalOpen = ref(false);

const redisKeyModalSubmitting = ref(false);

const redisKeyModalMode = ref<'create' | 'edit'>('create');

const redisKeyForm = reactive<{
  keyName: string;
  valueType: 'string' | 'hash' | 'list' | 'set' | 'zset';
  ttlSeconds: number;
  editorPayload: string;
}>({
  keyName: '',
  valueType: 'string',
  ttlSeconds: -1,
  editorPayload: '',
});

const objectDefinitionDetail = ref<SchemaObjectDefinitionVO | null>(null);

const objectDefinitionDetailLoading = ref(false);

const queryEditorPaneRef = ref<HTMLElement | null>(null);

const sqlEditorContainerRef = ref<HTMLElement | null>(null);

const queryChatScrollRef = ref<HTMLElement | null>(null);

const queryChatMessageElementMap = new Map<string, HTMLElement>();

const queryChartPanelRef = ref<InstanceType<typeof QueryChartPanel> | null>(null);

const erDiagramPanelRef = ref<InstanceType<typeof ErDiagramPanel> | null>(null);

const sqlSelectionPopover = reactive({
  visible: false,
  left: 0,
  top: 0,
});

const viewportWidth = ref(typeof window === 'undefined' ? 1440 : window.innerWidth);

function syncViewportSize() {
  if (typeof window === 'undefined') {
    return;
  }
  viewportHeight.value = window.innerHeight;
  viewportWidth.value = window.innerWidth;
}

const leftPaneWidth = ref(270);

const leftPaneResizeState = reactive({
  resizing: false,
  startX: 0,
  startWidth: 270,
});

const browserRightPaneWidth = ref(390);

const browserPaneResizeState = reactive({
  resizing: false,
  startX: 0,
  startWidth: 390,
});

const erRightPaneWidth = ref(400);

const erPaneResizeState = reactive({
  resizing: false,
  startX: 0,
  startWidth: 400,
});

const queryRightPaneWidth = ref(420);

const queryPaneResizeState = reactive({
  resizing: false,
  startX: 0,
  startWidth: 420,
});

const queryEditorSectionHeight = ref(260);

const queryEditorSectionResizeState = reactive({
  resizing: false,
  startY: 0,
  startHeight: 260,
  paneHeight: 0,
});

const contextMenu = reactive({
  visible: false,
  x: 0,
  y: 0,
  targetType: 'none' as 'none' | 'group' | 'connection' | 'databaseRoot' | 'database' | 'category' | 'object',
  groupId: 0,
  connectionId: 0,
  databaseName: '',
  namespaceName: '',
  category: '' as '' | 'tables' | 'views' | 'functions' | 'queries',
  objectType: '' as '' | ObjectRow['objectType'],
  objectName: '',
  redisNodeType: '' as '' | 'PATH' | 'KEY' | 'LOAD_MORE',
});

const connectionForm = reactive<ConnectionCreateReq>(defaultConnectionForm());
const connectionFormSubmitted = ref(false);

const supportedDbTypes = ref<ConnectionDbTypeVO[]>([]);

const connectionPreviewDbOptions = ref<string[]>([]);

const connectionPreviewLoading = ref(false);

const connectionPreviewError = ref('');

const {
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
} = createConnectionRuntimeHelpers({
  connections,
  databaseListCache,
  hiddenDbTypes: FRONTEND_HIDDEN_DB_TYPES,
  supportedDbTypes,
});

const {toObjectType, objectTypeLabel} = createObjectPresentationHelpers({
  getPrimaryObjectLabel: () => {
    const dbType = connections.value.find((item) => item.id === workflow.connectionId)?.dbType || '';
    return primaryObjectLabelByDbType(dbType);
  },
});

const {connectionStatusClass, connectionStatusText} = createConnectionStatusHelpers({
  connectionRuntimeStatusMap,
});

const aiConfigForm = reactive<AiConfigSaveReq>(defaultAiConfigForm());

const ragConfigForm = reactive<RagConfigSaveReq>(defaultRagConfigForm());

const {
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
} = createConnectionSettingsHelpers({
  aiConfigForm,
  connectionForm,
  connectionFormSubmitted,
  connectionGroups,
  connectionPreviewDbOptions,
  connectionPreviewError,
  editingConnectionId,
  ensureConnectionFormDbType,
  isEditMode,
  ragConfigForm,
  selectedAiModel,
});

const pickingRagModelDir = ref(false);

const pickingRagRerankModelDir = ref(false);

let schemaBrowserStatusHelpers: ReturnType<typeof createSchemaBrowserStatusHelpers> | null = null;

function getActiveDatabaseName(connectionId: number) {
  return schemaBrowserStatusHelpers?.getActiveDatabaseName(connectionId)
    ?? (activeDatabaseMap.value[connectionId] ?? '').trim();
}

const {
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
} = createConnectionUiHelpers({
  aiConfigForm,
  aiConfigModalOpen,
  clearStatementResults,
  connections,
  fillAiConfigForm,
  fillRagConfigForm,
  getActiveDatabaseName,
  pickingRagModelDir,
  pickingRagRerankModelDir,
  prepareConnectionTreeData: async (connectionId) => prepareConnectionTreeData(connectionId),
  ragConfigForm,
  runSafely,
  touchQueryTab,
  visibleDatabasesForConnection,
  warmupTableSuggestions: async (tab) => warmupTableSuggestions(tab),
});

const workflow = reactive({
  connectionId: 0,
  prompt: '',
  sqlText: '',
});

const dbTypeOptions = computed(() =>
  supportedDbTypes.value.map((item) => ({
    label: item.displayName || item.dbType,
    value: item.dbType,
  })),
);

const envOptions = [
  { label: '开发 DEV', value: 'DEV' },
  { label: '测试 TEST', value: 'TEST' },
  { label: '生产 PROD', value: 'PROD' },
];

const sshAuthTypeOptions = [
  { label: '密码', value: 'SSH_PASSWORD' },
  { label: '私钥路径', value: 'SSH_KEY_PATH' },
  { label: '私钥文本', value: 'SSH_KEY_TEXT' },
];

const sqlEditorOptions = {
  automaticLayout: true,
  minimap: { enabled: false },
  fontSize: 12,
  lineHeight: 18,
  fontFamily: "'Consolas', 'Cascadia Mono', 'JetBrains Mono', 'Menlo', 'Courier New', monospace",
  fontLigatures: false,
  disableMonospaceOptimizations: true,
  wordWrap: 'on',
  quickSuggestions: {
    comments: false,
    strings: false,
    other: false,
  },
  quickSuggestionsDelay: 80,
  suggestOnTriggerCharacters: false,
  scrollBeyondLastLine: false,
  tabSize: 2,
  insertSpaces: true,
};

let sqlCompletionProviderDisposable: IDisposable | null = null;

let sqlEditorTypeDisposable: IDisposable | null = null;

let sqlEditorSelectionDisposable: IDisposable | null = null;

let sqlEditorScrollDisposable: IDisposable | null = null;

let sqlEditorMouseDownDisposable: IDisposable | null = null;

let sqlEditorMouseUpDisposable: IDisposable | null = null;

let sqlAutoSuggestTimer: number | null = null;

let redisBrowserSearchTimer: number | null = null;

let activeSqlEditorInstance: MonacoApi.editor.IStandaloneCodeEditor | null = null;

const pendingTableNameLoads = new Map<string, Promise<string[]>>();

const pendingTableDetailLoads = new Map<string, Promise<TableDetailVO | null>>();

const sqlEditorContextResolverMap = new Map<string, () => SqlEditorContext | null>();

const tableStatsPollingTimers = new Map<string, number>();

const sessionTitleOverridesStorageKey = 'sqlcopilot.session-title-overrides.v1';

const sqlExecutionAbortControllerMap = new Map<string, AbortController>();

const sqlExecutionAbortReasonMap = new Map<string, RequestAbortReason>();

const aiRequestAbortControllerMap = new Map<string, AbortController>();

const aiRequestAbortReasonMap = new Map<string, RequestAbortReason>();

const {
  clearObjectDetail,
  loadObjectDetail,
  selectObject,
} = createSchemaObjectDetailHelpers({
  buildObjectQuerySql,
  connections,
  isKvConnectionId,
  kvObjectDetail,
  kvObjectDetailLoading,
  objectDefinitionDetail,
  objectDefinitionDetailLoading,
  openSavedQueryTabByTitle: (...args) => openSavedQueryTabByTitle(...args),
  queryDbTypeByConnectionId,
  redisSelectedRowKey,
  selectedObjectName,
  tableDetail,
  tableDetailLoading,
  workflow,
});

const {
  collapseConnectionNode,
  invalidateConnectionMetadataCaches,
  resetConnectionRuntimeState,
  setConnectionRuntimeStatus,
} = createConnectionBrowserStateHelpers({
  activeDatabaseMap,
  clearBrowserObjectCollections: () => clearBrowserObjectCollections(),
  clearObjectDetail,
  clearTableStatsPollingTimer,
  connectionRuntimeStatusMap,
  currentObjectType,
  databaseListCache,
  databaseVectorizeStatusMap,
  expandedTreeKeys,
  getWorkflowConnectionId: () => workflow.connectionId,
  kvOverviewCleared: () => {
    kvOverview.value = null;
  },
  namespaceListCache,
  namespaceListLoadedCache,
  objectNameCache,
  pendingTableDetailLoads,
  pendingTableNameLoads,
  queryTableDetailCache,
  redisBrowseExpandedRowKeys,
  redisBrowserChildLoadingMap,
  redisBrowserContextKey,
  redisBrowserPageCache,
  redisBrowserRows,
  redisExpandedRowKeys,
  redisHierarchyPath,
  redisSelectedRowKey,
  schemaOverviewCleared: () => {
    schemaOverview.value = null;
  },
  selectedObjectName,
  selectedTreeKeys,
  savedQueryCache,
  tableNameCache,
  tableNameLoadedCache,
  tableStatsCache,
  tableStatsLastRequestAt,
  tableStatsLoadingState,
  tableStatsPollingTimers,
});

schemaBrowserStatusHelpers = createSchemaBrowserStatusHelpers({
  activeDatabaseMap,
  browserObjectNameList,
  browserSavedQueryList,
  clearTableStatsPollingTimer,
  connections,
  databaseListCache,
  databaseVectorizeStatusMap,
  getWorkflowConnectionId: () => workflow.connectionId,
  invalidateConnectionMetadataCaches: (connectionId) => invalidateConnectionMetadataCaches(connectionId),
  isDatabaseContextVisibleForConnection,
  namespaceListCache,
  namespaceListLoadedCache,
  objectNameCache,
  pendingTableDetailLoads,
  pendingTableNameLoads,
  queryTableDetailCache,
  redisBrowserRows,
  redisExpandedRowKeys,
  redisSelectedRowKey,
  schemaOverviewCleared: () => {
    schemaOverview.value = null;
  },
  tableNameCache,
  tableNameLoadedCache,
  tableStatsCache,
  tableStatsLastRequestAt,
  tableStatsLoadingState,
  visibleDatabasesForConnection,
});

const {
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
} = schemaBrowserStatusHelpers;

const {
  cacheChartImageWithRetry,
  downloadActiveChart,
  downloadImage,
  downloadMessageChart,
  exportChartPngDataUrl,
  hydrateHistoryChartImages,
  loadChartImageDataUrl,
  saveChartImageCache,
} = createChartRuntimeHelpers({
  exportChartDataUrl: async (pixelRatio = 2) => (await queryChartPanelRef.value?.exportPngDataUrl?.({pixelRatio})) || '',
  getDesktopBridge,
});

const {closeContextMenu, handleTreeRightClick} = createSchemaContextMenuHelpers({
  activeDatabaseMap,
  connections,
  contextMenu,
  parseSchemaContextByDbType: (dbType, databaseName) => parseSchemaContext(dbType, databaseName),
  refreshVectorizeStatusForConnection: (connectionId) => refreshVectorizeStatusForConnection(connectionId),
  runSafely,
  selectObject: (connectionId, databaseName, objectType, objectName) =>
    selectObject(connectionId, databaseName, objectType, objectName),
  selectedTreeKeys,
  toObjectType: (value) => toObjectType(value),
  workflow,
});

const {
  confirmErTableSelection,
  downloadActiveErDiagram,
  exportErDiagramPngDataUrl,
  openErTableSelectModal,
  refreshErGraphForTab,
} = createErDiagramRuntimeHelpers({
  activeWorkbenchTab,
  closeContextMenu,
  downloadImage,
  ensureTableNamesLoaded,
  erDiagramPanelExport: async (pixelRatio = 2) => (await erDiagramPanelRef.value?.exportPngDataUrl?.({pixelRatio})) || '',
  erSelectConnectionId,
  erSelectDatabaseName,
  erSelectModelName,
  erSelectTableKeyword,
  erSelectTableOptions,
  erSelectTableValues,
  erSelectTargetTabKey,
  erTableSelectModalOpen,
  erTableSelectSubmitting,
  erTabs,
  getActiveDatabaseName,
  getAiModelOptionValues: () => aiModelOptions.value.map((item) => String(item.value)).filter((item) => !!item),
  getSelectedAiModel: () => selectedAiModel.value,
  getWorkflowConnectionId: () => workflow.connectionId,
  resolveErUnavailableReason,
  runSafely,
});

const {
  applySessionTitle,
  assistantActionLabel,
  applyResponseTokenSnapshot,
  buildStructuredContextForTab,
  buildSessionDefaultTitle,
  buildNewQueryPlaceholderTitle,
  cancelHistoryTitleEdit,
  findQueryTabBySession,
  firstPromptForTitle,
  historyItemKey,
  loadSessionTitleOverrides,
  normalizeTitleSource,
  normalizeHistoryActionType,
  persistSessionTitleOverrides,
  queryTabConnectionNameById,
  saveConversationHistory,
  saveConversationHistoryOnce,
  sessionTitleOverrideKey,
  userBubbleClass,
} = createHistoryRuntimeHelpers({
  conversationMemoryEnabledForTab,
  connections,
  editingHistoryTabKey,
  editingHistoryTitle,
  estimateTextTokens,
  getActiveDatabaseName,
  queryTabs,
  sessionTitleOverrides,
});

const {
  clearUserRetryState,
  getErrorMessage,
  isAbortError,
  isAiRequestAbortedMessage,
  isTimeoutErrorMessage,
  looksLikeExecutableQueryText,
  looksLikeSqlText,
  markUserMessageRetryable,
  mergePromptWithSqlSnippet,
  postAiApiWithTimeout,
  postAiStreamWithTimeout,
  timeoutRetryErrorMessage,
} = createAiRequestHelpers({
  aiRequestAbortControllerMap,
  aiRequestAbortReasonMap,
  queryEditorModeByDbType,
  touchQueryTab,
});

const {
  appendAssistantSqlMessage,
  appendAssistantTextMessage,
  appendAssistantThinkingMessage,
  appendUserChatMessage,
  applyStreamTraceSnapshot,
  bindQueryChatMessageRef,
  ensureAssistantStreamingState,
  extractThinkingContentFromTrace,
  flushStreamingQueryTab,
  materializeAssistantErrorMessage,
  prepareAssistantMessage,
  removeQueryChatMessage,
  scrollToQueryChatMessage,
  toggleMessageTraceExpanded,
  upsertStreamingTraceLlmDelta,
  upsertStreamingTraceStage,
} = createQueryChatHelpers({
  applySessionTitle,
  getActiveQueryTab: () => activeQueryTab.value,
  queryChatMessageElementMap,
  queryChatScrollRef,
  touchQueryTab,
});

const {
  queryResultExportTooltip,
  setQueryResultViewMode,
  rebuildQueryResultTableCache,
  resizeActiveQueryResultColumn,
  setupManualChartConfigByResult,
  handleManualChartTypeChange,
  handleManualChartXAxisChange,
  handleManualChartYFieldsChange,
  handleManualChartSingleYFieldChange,
  handleManualChartSeriesFieldChange,
} = createQueryResultViewHelpers({
  touchQueryTab: (tab) => touchQueryTab(tab),
});

const {
  connectionEnvLabel,
  ensureRiskConfirmedBeforeExecute,
  executeSqlForTab,
  explainSqlForTab,
  exportActiveQueryResult,
  exportCsvForTab,
  exportResultTab,
} = createQueryExecutionHelpers({
  connections,
  downloadActiveChart,
  getDesktopBridge,
  isAbortError,
  isKvDbType,
  loadLastResultExportDirectory,
  queryTabDbType,
  rebuildQueryResultTableCache,
  resolveQueryDatabaseName,
  runSafely,
  saveLastResultExportDirectory,
  setupManualChartConfigByResult,
  sqlExecutionAbortControllerMap,
  sqlExecutionAbortReasonMap,
  touchQueryTab,
});

const {
  autoActionTypeByIntent,
  editChartFromHistory,
  generateChartFromMessage,
  generateChartPlanForTab,
  generateManualChartForTab,
  generateSqlForTab,
  repairSqlForTab,
  sendAutoForTab,
} = createAiInteractionHelpers({
  appendAssistantSqlMessage,
  appendAssistantTextMessage,
  appendAssistantThinkingMessage,
  appendUserChatMessage,
  applyResponseTokenSnapshot,
  applyStreamTraceSnapshot,
  cacheChartImageWithRetry,
  clearUserRetryState,
  conversationMemoryEnabledForTab,
  detailOutputEnabledForTab,
  enrichPromptWithSchemaReferences,
  ensureAssistantStreamingState,
  executeSqlForTab,
  flushStreamingQueryTab,
  getErrorMessage,
  getActiveChartRows: () => activeChartRows.value,
  isAiRequestAbortedMessage,
  isTimeoutErrorMessage,
  looksLikeExecutableQueryText,
  markUserMessageRetryable,
  materializeAssistantErrorMessage,
  mergePromptWithSqlSnippet,
  normalizeChartCacheErrorMessage,
  postAiStreamWithTimeout,
  queryTabDbType,
  removeQueryChatMessage,
  resolveSelectedSqlSnippet,
  saveConversationHistory,
  saveConversationHistoryOnce,
  setupManualChartConfigByResult,
  timeoutRetryErrorMessage,
  touchQueryTab,
  upsertStreamingTraceLlmDelta,
  upsertStreamingTraceStage,
});

const {
  loadDatabaseListForConnection,
  loadNamespaceList,
  prepareConnectionTreeData,
  pruneVectorizeStatusMap,
  refreshAllVectorizeStatuses,
  refreshVectorizeStatusForConnection,
  startVectorizeStatusPolling,
  stopVectorizeStatusPolling,
} = createSchemaBrowserHelpers({
  activeDatabaseMap,
  connections,
  databaseListCache,
  databaseVectorizeStatusMap,
  getVectorizeStatusPollIntervalMs: () => vectorizeStatusPollIntervalMs,
  getVectorizeStatusPollTimer: () => vectorizeStatusPollTimer,
  getWorkflowConnectionId: () => workflow.connectionId,
  isDatabaseContextVisibleForConnection,
  isKvConnectionId,
  namespaceListCache,
  namespaceListLoadedCache,
  requiresDatabaseLayer,
  setConnectionRuntimeStatus,
  setVectorizeStatusPollTimer: (timer) => {
    vectorizeStatusPollTimer = timer;
  },
  visibleDatabasesForConnection,
});

const schemaOverviewRuntime = createSchemaOverviewRuntimeHelpers({
  activeConnectionIsRedis: () =>
    connections.value.find((item) => item.id === workflow.connectionId)?.dbType === 'REDIS',
  connections,
  ensureConnection,
  expandConnectionNode: (connectionId) => expandConnectionNode(connectionId),
  expandedTreeKeys,
  fetchRedisBrowserPage: (connectionId, databaseName, parentPath, keyword, cursor, options) =>
    fetchRedisBrowserPage(connectionId, databaseName, parentPath, keyword, cursor, options),
  getActiveDatabaseName,
  getTableStatsMinRequestIntervalMs: () => tableStatsMinRequestIntervalMs,
  getTableStatsPollIntervalMs: () => tableStatsPollIntervalMs,
  invalidateRedisBrowserCache: (connectionId, databaseName) => invalidateRedisBrowserCache(connectionId, databaseName),
  isKvConnectionId,
  kvOverview,
  loadRedisBrowserRows: (options) => loadRedisBrowserRows(options),
  objectNameCache,
  pendingTableDetailLoads,
  pendingTableNameLoads,
  queryTableDetailCache,
  runSafely,
  schemaOverview,
  setConnectionRuntimeStatus,
  tableNameCache,
  tableNameLoadedCache,
  tableStatsCache,
  tableStatsLastRequestAt,
  tableStatsLoadingState,
  tableStatsPollingTimers,
  workflow,
});

function loadOverview(options?: { forceTableStats?: boolean; syncTreeCaches?: boolean }) {
  return schemaOverviewRuntime.loadOverview(options);
}

function clearTableStatsPollingTimer(cacheKey: string) {
  return schemaOverviewRuntime.clearTableStatsPollingTimer(cacheKey);
}

function clearAllTableStatsPollingTimers() {
  return schemaOverviewRuntime.clearAllTableStatsPollingTimers();
}

function applyTableStatsSnapshot(connectionId: number, databaseName: string, payload: SchemaTableStatsVO) {
  return schemaOverviewRuntime.applyTableStatsSnapshot(connectionId, databaseName, payload);
}

function isDatabaseNodeExpanded(connectionId: number, databaseName: string) {
  return schemaOverviewRuntime.isDatabaseNodeExpanded(connectionId, databaseName);
}

function collectExpandedDatabaseTargets(keys: string[]) {
  return schemaOverviewRuntime.collectExpandedDatabaseTargets(keys);
}

function fetchTableStatsForDatabase(
  connectionId: number,
  databaseName: string,
  options?: { force?: boolean; polling?: boolean },
) {
  return schemaOverviewRuntime.fetchTableStatsForDatabase(connectionId, databaseName, options);
}

function scheduleTableStatsForExpandedDatabases(keys: string[]) {
  return schemaOverviewRuntime.scheduleTableStatsForExpandedDatabases(keys);
}

function loadTableNamesByConnection(connectionId: number, databaseName: string) {
  return schemaOverviewRuntime.loadTableNamesByConnection(connectionId, databaseName);
}

function ensureTableNamesLoaded(connectionId: number, databaseName: string) {
  return schemaOverviewRuntime.ensureTableNamesLoaded(connectionId, databaseName);
}

function ensureQueryTableDetailLoaded(connectionId: number, databaseName: string, tableName: string) {
  return schemaOverviewRuntime.ensureQueryTableDetailLoaded(connectionId, databaseName, tableName);
}

const {
  loadCategoryObjects,
  loadObjectNames,
  refreshCurrentObjects,
  refreshCurrentPageObjects,
} = createSchemaObjectBrowserHelpers({
  browserNavMode,
  browserObjectNameList,
  browserSavedQueryList,
  clearBrowserObjectCollections,
  clearDatabaseTableStatsCache: (connectionId, databaseName) => clearDatabaseTableStatsCache(connectionId, databaseName),
  currentObjectType,
  ensureConnection: () => ensureConnection(),
  expandCategoryNode: (connectionId, databaseName, category) => expandCategoryNode(connectionId, databaseName, category),
  getActiveDatabaseName,
  getWorkflowConnectionId: () => workflow.connectionId,
  invalidateConnectionMetadataCaches: (connectionId) => invalidateConnectionMetadataCaches(connectionId),
  invalidateDatabaseListCache: (connectionId) => invalidateDatabaseListCache(connectionId),
  invalidateDatabaseMetadataCaches: (connectionId, databaseName) => invalidateDatabaseMetadataCaches(connectionId, databaseName),
  invalidateRedisBrowserCache: (connectionId, databaseName) => invalidateRedisBrowserCache(connectionId, databaseName),
  isActiveConnectionRedis: () => activeConnectionIsRedis.value,
  loadObjectDetail: (connectionId, databaseName, objectType, objectName) =>
    loadObjectDetail(connectionId, databaseName, objectType, objectName),
  loadOverview: (options) => loadOverview(options),
  loadSavedQueries: (connectionId, databaseName, options) => loadSavedQueries(connectionId, databaseName, options),
  objectNameCache,
  prepareConnectionTreeData: (connectionId, options) => prepareConnectionTreeData(connectionId, options),
  runSafely,
  selectedObjectName,
  toObjectType: (value) => toObjectType(value),
});

const {
  ensureConnectionTreeExpanded,
  expandCategoryNode,
  expandConnectionNode,
  handleTreeExpand,
  loadTreeChildrenByKey,
} = createSchemaTreeHelpers({
  buildGetErrorMessage: (error) => getErrorMessage(error),
  collapseConnectionNode: (connectionId) => collapseConnectionNode(connectionId),
  connections,
  currentObjectType,
  ensureTableNamesLoaded: (connectionId, databaseName) => ensureTableNamesLoaded(connectionId, databaseName),
  expandedTreeKeys,
  getActiveDatabaseName,
  isKvConnectionId,
  loadNamespaceList: (connectionId, databaseName, options) => loadNamespaceList(connectionId, databaseName, options),
  loadObjectNames: (connectionId, databaseName, objectType, options) => loadObjectNames(connectionId, databaseName, objectType, options),
  loadSavedQueries: (connectionId, databaseName, options) => loadSavedQueries(connectionId, databaseName, options),
  parseSchemaContextByDbType: (dbType, databaseName) => parseSchemaContext(dbType, databaseName),
  prepareConnectionTreeData: (connectionId, options) => prepareConnectionTreeData(connectionId, options),
  requiresDatabaseLayer,
  scheduleTableStatsForExpandedDatabases: (keys) => scheduleTableStatsForExpandedDatabases(keys),
  supportsSchemaLayerByDbType: (dbType) => supportsSchemaLayer(dbType),
  toObjectType: (value) => toObjectType(value),
});

const {handleTreeSelect} = createSchemaTreeSelectionHelpers({
  activeDatabaseMap,
  activeWorkbenchTab,
  browserNavMode,
  browserObjectNameList,
  browserSavedQueryList,
  browserTabKey,
  clearBrowserObjectCollections,
  clearObjectDetail,
  closeContextMenu,
  connections,
  currentObjectType,
  ensureConnectionTreeExpanded: (connectionId, options) => ensureConnectionTreeExpanded(connectionId, options),
  expandConnectionNode: (connectionId) => expandConnectionNode(connectionId),
  getActiveDatabaseName,
  kvOverviewCleared: () => {
    kvOverview.value = null;
  },
  loadCategoryObjects: (connectionId, databaseName, category) => loadCategoryObjects(connectionId, databaseName, category),
  loadNamespaceList: (connectionId, databaseName, options) => loadNamespaceList(connectionId, databaseName, options),
  loadObjectNames: (connectionId, databaseName, objectType, options) => loadObjectNames(connectionId, databaseName, objectType, options),
  loadOverview: (options) => loadOverview(options),
  loadSavedQueries: (connectionId, databaseName, options) => loadSavedQueries(connectionId, databaseName, options),
  loadTreeChildrenByKey: (nodeKey) => loadTreeChildrenByKey(nodeKey),
  objectNameCache,
  parseSchemaContextByDbType: (dbType, databaseName) => parseSchemaContext(dbType, databaseName),
  requiresDatabaseLayer,
  runSafely,
  savedQueriesByDatabase: (connectionId, databaseName) => savedQueriesByDatabase(connectionId, databaseName),
  schemaOverviewCleared: () => {
    schemaOverview.value = null;
  },
  selectObject: (connectionId, databaseName, objectType, objectName) =>
    selectObject(connectionId, databaseName, objectType, objectName),
  selectedObjectName,
  selectedTreeKeys,
  supportsSchemaLayerByDbType: (dbType) => supportsSchemaLayer(dbType),
  toObjectType: (value) => toObjectType(value),
  workflow,
});

const selectedConnection = computed(() =>
  connections.value.find((item) => item.id === workflow.connectionId),
);

const activeQueryTab = computed(() =>
  queryTabs.value.find((item) => item.key === activeWorkbenchTab.value) ?? null,
);

const activeStatementResult = computed(() => {
  const tab = activeQueryTab.value;
  if (!tab) {
    return null as QueryStatementResult | null;
  }
  return tab.statementResults.find((item) => item.key === tab.activeStatementResultKey)
    ?? tab.statementResults[0]
    ?? null;
});

const activeQueryContextUsage = computed<QueryContextUsage>(() =>
  buildQueryContextUsage(activeQueryTab.value),
);

const activeErTab = computed(() =>
  erTabs.value.find((item) => item.key === activeWorkbenchTab.value) ?? null,
);

const activeTableEditorTab = computed(() =>
  tableEditorTabs.value.find((item) => item.key === activeWorkbenchTab.value) ?? null,
);

const activeTableDataTab = computed(() =>
  tableDataTabs.value.find((item) => item.key === activeWorkbenchTab.value) ?? null,
);

const activeObjectDefinitionEditorTab = computed(() =>
  objectDefinitionEditorTabs.value.find((item) => item.key === activeWorkbenchTab.value) ?? null,
);

const {
  analyzeActionLabelByDbType,
  canGenerateChartForTab,
  explainActionLabelByDbType,
  formatObjectDefinitionSql,
  formatSqlForTab,
  formatSqlText,
  generateActionLabelByDbType,
  queryEditorLanguageByDbType,
  queryUnitLabelByDbType,
} = createSqlEditorRuntimeHelpers({
  activeObjectDefinitionEditorTab,
  activeQueryTab,
  getActiveSqlEditor: () => activeSqlEditorInstance,
  getErrorMessage,
  hideSqlSelectionPopover: () => hideSqlSelectionPopover(),
  isKvDbType,
  queryEditorModeByDbType,
  queryTabDbType,
  supportsGenerateChartByDbType,
  touchQueryTab,
});

const {
  tableNameSuggestions,
  sqlKeywordSuggestions,
  hasKeywordSuggestion,
  hasTableSuggestion,
  shouldAutoTriggerSuggest,
  registerSqlCompletionProvider,
  registerSqlAutoSuggest,
  readSelectedSql,
  hideSqlSelectionPopover,
  updateSqlSelectionPopoverPosition,
  syncSelectedSqlForActiveTab,
  registerSqlSelectionTracker,
  registerSqlSelectionPopoverTrigger,
  registerSqlScrollTracker,
  warmupTableSuggestions,
  handleSqlEditorMount,
} = createSqlEditorInteractionHelpers({
  activeObjectDefinitionEditorTab,
  activeQueryTab,
  activeWorkbenchTab,
  ensureTableNamesLoaded,
  ensureQueryTableDetailLoaded,
  formatObjectDefinitionSql,
  formatSqlForTab,
  getActiveSqlEditor: () => activeSqlEditorInstance,
  getSqlAutoSuggestTimer: () => sqlAutoSuggestTimer,
  getSqlCompletionProviderDisposable: () => sqlCompletionProviderDisposable,
  getSqlEditorMouseDownDisposable: () => sqlEditorMouseDownDisposable,
  getSqlEditorMouseUpDisposable: () => sqlEditorMouseUpDisposable,
  getSqlEditorScrollDisposable: () => sqlEditorScrollDisposable,
  getSqlEditorSelectionDisposable: () => sqlEditorSelectionDisposable,
  getSqlEditorTypeDisposable: () => sqlEditorTypeDisposable,
  queryEditorContextResolverMap: sqlEditorContextResolverMap,
  queryEditorModeByDbType,
  queryTabDbType,
  queryTabRefByContext: (context) =>
    context ? queryTabs.value.find((item) => item.connectionId === context.connectionId && item.databaseName === context.databaseName) ?? null : null,
  resolveQueryDatabaseName,
  setActiveSqlEditor: (editor) => {
    activeSqlEditorInstance = editor;
  },
  setSqlAutoSuggestTimer: (timer) => {
    sqlAutoSuggestTimer = timer;
  },
  setSqlCompletionProviderDisposable: (disposable) => {
    sqlCompletionProviderDisposable = disposable;
  },
  setSqlEditorMouseDownDisposable: (disposable) => {
    sqlEditorMouseDownDisposable = disposable;
  },
  setSqlEditorMouseUpDisposable: (disposable) => {
    sqlEditorMouseUpDisposable = disposable;
  },
  setSqlEditorScrollDisposable: (disposable) => {
    sqlEditorScrollDisposable = disposable;
  },
  setSqlEditorSelectionDisposable: (disposable) => {
    sqlEditorSelectionDisposable = disposable;
  },
  setSqlEditorTypeDisposable: (disposable) => {
    sqlEditorTypeDisposable = disposable;
  },
  sqlEditorContainerRef,
  sqlSelectionPopover,
  tableCacheKey,
  tableNameCache,
  tableNameLoadedCache,
});

const {
  openAiQueryTab,
  openQueryTabByObject,
  savedQueriesByDatabase,
  loadSavedQueries,
  openSaveQueryModal,
  saveCurrentQuery,
  openSavedQueryTab,
  openSavedQueryTabByTitle,
  closeQueryTab,
  requestSqlExecutionInterrupt,
  hasWorkbenchTab,
  ensureActiveWorkbenchTab,
} = createQueryWorkbenchHelpers({
  activeDatabaseMap,
  activeWorkbenchTab,
  aiRequestAbortControllerMap,
  aiRequestAbortReasonMap,
  applySessionTitle,
  browserNavMode,
  ensureConnection: () => ensureConnection(),
  executeSqlForTab: (tab, sqlOverride, options) => executeSqlForTab(tab, sqlOverride, options),
  erTabs,
  getActiveDatabaseName,
  getConversationMemoryEnabled: () => aiConfigForm.conversationMemoryEnabled !== false,
  getKvQueryTemplate: () => kvObjectDetail.value?.queryTemplate || '',
  getModelValues: () => aiModelOptions.value.map((item) => String(item.value)).filter((item) => !!item),
  isKvConnectionId,
  knowledgeTabs,
  memoryTabs,
  objectDefinitionEditorTabs,
  persistSessionTitleOverrides,
  prepareConnectionTreeData: (connectionId) => prepareConnectionTreeData(connectionId),
  queryDbTypeByConnectionId,
  queryTabs,
  runSafely,
  saveQueryModalOpen,
  saveQuerySubmitting,
  saveQueryTitle,
  savedQueryCache,
  sessionTitleOverrideKey,
  sessionTitleOverrides,
  sqlExecutionAbortControllerMap,
  sqlExecutionAbortReasonMap,
  tableDataTabs,
  tableEditorTabs,
  warmupTableSuggestions: (tab) => warmupTableSuggestions(tab),
  workflow,
});

const {
  buildConnectionNode,
  buildCategoryChildren,
  getCategoryChildren,
} = createSchemaTreeNodeHelpers({
  connections,
  getActiveDatabaseName: (connectionId) => getActiveDatabaseName(connectionId),
  getDatabaseVectorizeStatus: (connectionId, databaseName) => getDatabaseVectorizeStatus(connectionId, databaseName),
  isKvDbType,
  namespaceListCache,
  objectNameCache,
  primaryObjectLabelByDbType,
  requiresDatabaseLayer,
  savedQueriesByDatabase: (connectionId, databaseName) => savedQueriesByDatabase(connectionId, databaseName),
  tableNameCache,
  visibleDatabasesForConnection,
});

const activeKnowledgeTab = computed(() =>
  knowledgeTabs.value.find((item) => item.key === activeWorkbenchTab.value) ?? null,
);

const activeMemoryTab = computed(() =>
  memoryTabs.value.find((item) => item.key === activeWorkbenchTab.value) ?? null,
);

const activeErConfidenceThreshold = computed(() => {
  const threshold = Number(activeErTab.value?.aiConfidenceThreshold ?? 0.6);
  if (!Number.isFinite(threshold)) {
    return 0.6;
  }
  return Math.max(0, Math.min(1, threshold));
});

const activeErAiRelationTotal = computed(() =>
  activeErTab.value?.graph?.aiRelations?.length ?? 0,
);

const activeErDisplayGraph = computed<ErGraphVO | null>(() => {
  const graph = activeErTab.value?.graph;
  if (!graph) {
    return null;
  }
  return {
    ...graph,
    aiRelations: (graph.aiRelations ?? [])
      .filter((relation) => normalizeErRelationConfidence(relation.confidence) >= activeErConfidenceThreshold.value),
    manualRelations: graph.manualRelations ?? [],
  };
});

const activeErForeignKeyRelations = computed(() =>
  activeErDisplayGraph.value?.foreignKeyRelations ?? [],
);

const activeErAiRelations = computed(() =>
  [...(activeErDisplayGraph.value?.aiRelations ?? [])]
    .sort((a, b) => normalizeErRelationConfidence(b.confidence) - normalizeErRelationConfidence(a.confidence)),
);

const activeErManualRelations = computed(() =>
  [...(activeErDisplayGraph.value?.manualRelations ?? [])]
    .sort((a, b) => buildErRelationKey(a).localeCompare(buildErRelationKey(b))),
);

const canOpenHistory = computed(() => {
  return connections.value.length > 0;
});

const canOpenErSnapshot = computed(() => {
  return connections.value.length > 0;
});

const isDarkTheme = computed(() => uiTheme.value === 'dark');

const monacoTheme = computed(() => (isDarkTheme.value ? 'vs-dark' : 'vs'));

const antdFont =
  "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, 'Helvetica Neue', 'PingFang SC', 'Microsoft YaHei', sans-serif";

const antdThemeConfig = computed(() => ({
  algorithm: isDarkTheme.value ? darkAlgorithm : defaultAlgorithm,
  token: {
    colorPrimary: isDarkTheme.value ? '#569cd6' : '#4078c0',
    colorInfo: isDarkTheme.value ? '#569cd6' : '#4078c0',
    colorSuccess: isDarkTheme.value ? '#3cbf9a' : '#2d8f6b',
    colorWarning: isDarkTheme.value ? '#d4a520' : '#b8860b',
    colorError: isDarkTheme.value ? '#e0788f' : '#c44c6a',
    colorBgContainer: isDarkTheme.value ? '#252526' : '#f7f7f7',
    colorBorderSecondary: isDarkTheme.value ? 'rgba(255,255,255,0.09)' : 'rgba(0,0,0,0.1)',
    borderRadius: 2,
    wireframe: false,
    fontFamily: antdFont,
  },
  components: {
    Button: {
      controlHeightSM: 28,
    },
    Input: {
      controlHeightSM: 28,
    },
    Select: {
      controlHeightSM: 28,
    },
    Tree: {
      titleHeight: 22,
    },
  },
}));

const currentHistoryConnectionId = computed(() => {
  if (activeQueryTab.value?.connectionId) {
    return activeQueryTab.value.connectionId;
  }
  if (workflow.connectionId) {
    return workflow.connectionId;
  }
  return connections.value[0]?.id ?? 0;
});

const currentErSnapshotConnectionId = computed(() => {
  if (activeErTab.value?.connectionId) {
    return activeErTab.value.connectionId;
  }
  if (activeQueryTab.value?.connectionId) {
    return activeQueryTab.value.connectionId;
  }
  if (workflow.connectionId) {
    return workflow.connectionId;
  }
  return connections.value[0]?.id ?? 0;
});

const sessionHistoryTabs = computed(() => historySessionItems.value);

const filteredErSelectTableOptions = computed(() => {
  const keyword = erSelectTableKeyword.value.trim().toLowerCase();
  if (!keyword) {
    return erSelectTableOptions.value;
  }
  return erSelectTableOptions.value.filter((item) => item.toLowerCase().includes(keyword));
});

const isContextDatabaseVectorizing = computed(() =>
  contextMenu.targetType === 'database'
  && isDatabaseVectorizing(contextMenu.connectionId, contextMenu.databaseName),
);

const canViewContextVectorizedData = computed(() =>
  contextMenu.targetType === 'database'
  && !!contextMenu.databaseName
  && !isDatabaseVectorizing(contextMenu.connectionId, contextMenu.databaseName),
);

const canInterruptContextVectorize = computed(() =>
  contextMenu.targetType === 'database'
  && !!contextMenu.databaseName
  && isDatabaseVectorizing(contextMenu.connectionId, contextMenu.databaseName),
);

const canOpenBrowserErFeature = computed(() => {
  const connectionId = workflow.connectionId;
  if (!connectionId) {
    return false;
  }
  const databaseName = getActiveDatabaseName(connectionId);
  return !resolveErUnavailableReason(connectionId, databaseName);
});

const browserErEntryTooltip = computed(() => {
  const connectionId = workflow.connectionId;
  if (!connectionId) {
    return '请先选择连接';
  }
  const databaseName = getActiveDatabaseName(connectionId);
  return resolveErUnavailableReason(connectionId, databaseName) || '智能ER图';
});

const canCreateTable = computed(() => {
  const connectionId = workflow.connectionId;
  const databaseName = getActiveDatabaseName(connectionId);
  const connection = connections.value.find((item) => item.id === connectionId) ?? null;
  const spec = connection ? findSupportedDbType(connection.dbType) : null;
  return !!(connectionId && databaseName && spec?.supportsTableCreate !== false);
});

const canCreateView = computed(() => {
  const connectionId = workflow.connectionId;
  const databaseName = getActiveDatabaseName(connectionId);
  const connection = connections.value.find((item) => item.id === connectionId) ?? null;
  const spec = connection ? findSupportedDbType(connection.dbType) : null;
  return !!(connectionId && databaseName && spec?.supportsViewCreate);
});

const canCreateFunction = computed(() => {
  const connectionId = workflow.connectionId;
  const databaseName = getActiveDatabaseName(connectionId);
  const connection = connections.value.find((item) => item.id === connectionId) ?? null;
  const spec = connection ? findSupportedDbType(connection.dbType) : null;
  return !!(connectionId && databaseName && spec?.supportsFunctionCreate);
});

const connectionSelectOptions = computed(() =>
  connections.value.map((item) => ({ label: `${item.name} (${item.env})`, value: item.id })),
);

const connectionGroupOptions = computed(() =>
  connectionGroups.value.map((item) => ({ label: item.name, value: item.id })),
);

const connectionFormDbTypeSpec = computed(() => findSupportedDbType(connectionForm.dbType));

const isMultiDatabaseFormType = computed(() => isMultiDatabaseType(connectionForm.dbType));

const connectionPreviewSelectOptions = computed(() => {
  const selected = connectionForm.selectedDatabases ?? [];
  const merged = Array.from(new Set([
    ...connectionPreviewDbOptions.value,
    ...selected.filter((item) => !!item),
  ]));
  return merged.map((item) => ({ label: item, value: item }));
});

const canPreviewDatabases = computed(() => {
  if (!connectionFormDbTypeSpec.value?.supportsDatabasePreview || !isMultiDatabaseFormType.value) {
    return false;
  }
  if (connectionFormDbTypeSpec.value.requiresHost !== false && !connectionForm.host?.trim()) {
    return false;
  }
  if (connectionFormDbTypeSpec.value.requiresPort !== false && (!connectionForm.port || connectionForm.port <= 0)) {
    return false;
  }
  if (connectionFormDbTypeSpec.value.supportsUsername !== false && connectionForm.dbType !== 'MONGODB' && !connectionForm.username?.trim()) {
    return false;
  }
  if (connectionForm.sshEnabled) {
    if (!connectionForm.sshHost?.trim() || !connectionForm.sshUser?.trim()) {
      return false;
    }
    if (!connectionForm.sshPort || connectionForm.sshPort <= 0) {
      return false;
    }
    const mode = connectionForm.sshAuthType || 'SSH_PASSWORD';
    if (mode === 'SSH_PASSWORD') {
      return !!connectionForm.sshPassword?.trim();
    }
    if (mode === 'SSH_KEY_PATH') {
      return !!connectionForm.sshPrivateKeyPath?.trim();
    }
    if (mode === 'SSH_KEY_TEXT') {
      return !!connectionForm.sshPrivateKeyText?.trim();
    }
    return false;
  }
  return true;
});


const connectionFormErrors = computed<ConnectionFormErrorMap>(() => {
  const errors: ConnectionFormErrorMap = {};
  const dbType = (connectionForm.dbType || '').trim().toUpperCase();
  const requiresUsername = connectionFormDbTypeSpec.value?.supportsUsername !== false
    && dbType !== 'MONGODB'
    && dbType !== 'REDIS';

  if (!connectionForm.name?.trim()) {
    errors.name = '请输入连接名称';
  }
  if (!dbType) {
    errors.dbType = '请选择数据库类型';
  }
  if (!connectionForm.groupId || connectionForm.groupId <= 0) {
    errors.groupId = '请选择连接分组';
  }
  if (!connectionForm.env?.trim()) {
    errors.env = '请选择环境';
  }
  if (connectionFormDbTypeSpec.value?.requiresHost !== false && !connectionForm.host?.trim()) {
    errors.host = '请输入数据库主机';
  }
  if (connectionFormDbTypeSpec.value?.requiresPort !== false
    && (!connectionForm.port || connectionForm.port <= 0 || connectionForm.port > 65535)
  ) {
    errors.port = '请输入有效端口';
  }
  if (dbType === 'SQLITE' && !connectionForm.databaseName?.trim()) {
    errors.databaseName = '请输入 SQLite 数据库文件路径';
  }
  if (dbType === 'ORACLE' && !connectionForm.databaseName?.trim() && !containsDatabaseInHostInput(connectionForm.host)) {
    errors.databaseName = '请输入 Oracle 服务名';
  }
  if (requiresUsername && !connectionForm.username?.trim()) {
    errors.username = '请输入数据库用户';
  }
  if (connectionForm.sshEnabled) {
    if (!connectionForm.sshHost?.trim()) {
      errors.sshHost = '请输入 SSH 主机';
    }
    if (!connectionForm.sshPort || connectionForm.sshPort <= 0 || connectionForm.sshPort > 65535) {
      errors.sshPort = '请输入有效 SSH 端口';
    }
    if (!connectionForm.sshUser?.trim()) {
      errors.sshUser = '请输入 SSH 用户';
    }
    const authType = connectionForm.sshAuthType || 'SSH_PASSWORD';
    if (authType === 'SSH_PASSWORD' && !connectionForm.sshPassword?.trim()) {
      errors.sshPassword = '请输入 SSH 密码';
    }
    if (authType === 'SSH_KEY_PATH' && !connectionForm.sshPrivateKeyPath?.trim()) {
      errors.sshPrivateKeyPath = '请输入 SSH 私钥路径';
    }
    if (authType === 'SSH_KEY_TEXT' && !connectionForm.sshPrivateKeyText?.trim()) {
      errors.sshPrivateKeyText = '请输入 SSH 私钥内容';
    }
  }
  return errors;
});

const hasConnectionFormErrors = computed(() => Object.keys(connectionFormErrors.value).length > 0);

const {
  loadConnectionGroups,
  loadConnections,
  loadSupportedDbTypes,
  refreshConnections,
} = createConnectionLoadingHelpers({
  activeWorkbenchTab,
  browserTabKey,
  clearAllTableStatsPollingTimers,
  clearObjectDetail,
  connectionForm,
  connectionGroups,
  connectionRefreshing,
  connectionRuntimeStatusMap,
  connections,
  currentObjectType,
  ensureActiveWorkbenchTab,
  ensureConnectionFormDbType,
  ensureConnectionTreeExpanded,
  erSnapshotActionLoadingId,
  erSnapshotConnectionId,
  erSnapshotHasMore,
  erSnapshotItems,
  erSnapshotKeyword,
  erSnapshotKeywordInput,
  erSnapshotLoadingId,
  erSnapshotPageNo,
  erTabs,
  expandedTreeKeys,
  getActiveDatabaseName,
  historyKeyword,
  historyKeywordInput,
  historySessionConnectionId,
  historySessionHasMore,
  historySessionItems,
  historySessionPageNo,
  isDatabaseContextVisibleForConnection,
  isFrontendVisibleDbType,
  isMultiDatabaseType,
  kvOverview,
  loadOverview,
  objectDefinitionEditorTabs,
  pruneVectorizeStatusMap,
  queryTabs,
  refreshAllVectorizeStatuses,
  requiresDatabaseLayer,
  resetErSnapshotTitleEditState,
  runSafely,
  schemaOverview,
  selectedObjectName,
  selectedTreeKeys,
  setConnectionRuntimeStatus,
  supportedDbTypes,
  tableDataTabs,
  tableEditorTabs,
  tableStatsCache,
  tableStatsLastRequestAt,
  tableStatsLoadingState,
  visibleDatabasesForConnection,
  workflow,
});

const {
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
} = createConnectionCrudHelpers({
  canPreviewDatabases: () => canPreviewDatabases.value,
  clearObjectDetail,
  connectionForm,
  connectionFormErrors: () => connectionFormErrors.value,
  connectionFormSubmitted,
  connectionGroups,
  connectionPreviewDbOptions,
  connectionPreviewError,
  connectionPreviewLoading,
  connections,
  createModalOpen,
  currentObjectType,
  editingConnectionId,
  ensureActiveWorkbenchTab,
  ensureConnection,
  erSnapshotActionLoadingId,
  erSnapshotConnectionId,
  erSnapshotHasMore,
  erSnapshotItems,
  erSnapshotKeyword,
  erSnapshotKeywordInput,
  erSnapshotLoadingId,
  erSnapshotPageNo,
  erTabs,
  getActiveDatabaseName,
  groupForm,
  groupModalOpen,
  groupModalSubmitting,
  hasConnectionFormErrors: () => hasConnectionFormErrors.value,
  invalidateConnectionMetadataCaches,
  isEditMode,
  isMultiDatabaseType,
  loadConnectionGroups,
  loadConnections,
  loadOverview,
  objectDefinitionEditorTabs,
  queryTabs,
  resetConnectionModalState,
  resetConnectionRuntimeState,
  resetErSnapshotTitleEditState,
  runSafely,
  schemaOverview,
  selectedTreeKeys,
  tableDataTabs,
  tableEditorTabs,
  workflow,
});

const {
  enqueueDatabaseRevectorize,
  handleConnectionTreeDrop,
  interruptDatabaseVectorize,
  openVectorizeOverview,
  vectorizeSingleTable,
} = createSchemaBrowserActionHelpers({
  connections,
  databaseVectorizeStatusMap,
  getActiveDatabaseName,
  getWorkflowConnectionId: () => workflow.connectionId,
  loadConnections,
  loadOverview: (options) => loadOverview(options),
  moveConnectionGroup,
  refreshVectorizeStatusForConnection: (connectionId) => refreshVectorizeStatusForConnection(connectionId),
  runSafely,
  vectorizeOverviewData,
  vectorizeOverviewLoading,
  vectorizeOverviewModalOpen,
});

const connectionTreeData = computed(() => {
  const keyword = connectionKeyword.value.trim().toLowerCase();
  const groupOrder = connectionGroups.value.length
    ? connectionGroups.value
    : [{ id: 0, name: '未分组', defaultGroup: true } satisfies ConnectionGroupVO];
  return groupOrder
    .map((group) => {
      const items = connections.value.filter((conn) => {
        const belongs = (conn.groupId ?? 0) === group.id || (!conn.groupId && group.defaultGroup);
        if (!belongs) {
          return false;
        }
        if (!keyword) {
          return true;
        }
        const searchText = [
          conn.name,
          conn.dbType,
          conn.databaseName,
          conn.groupName,
        ].map((item) => String(item || '').toLowerCase()).join(' ');
        return searchText.includes(keyword);
      });
      if (!items.length) {
        return {
          key: `group-${group.id}`,
          title: group.name,
          nodeType: 'group',
          groupId: group.id,
          selectable: true,
          isLeaf: false,
          children: [
            {
              key: `group-${group.id}-empty`,
              title: '暂无连接',
              nodeType: 'group-empty',
              selectable: false,
              disabled: true,
              isLeaf: true,
            },
          ],
        };
      }
      return {
        key: `group-${group.id}`,
        title: group.name,
        nodeType: 'group',
        groupId: group.id,
        selectable: true,
        isLeaf: false,
        children: items.map((conn) => buildConnectionNode(conn)),
      };
    })
    .filter((item) => item.children?.length || !keyword);
});

const objectRows = computed<ObjectRow[]>(() => {
  const activeDbType = connections.value.find((item) => item.id === workflow.connectionId)?.dbType || '';
  const kvContext = isKvDbType(activeDbType);
  const databaseName = getActiveDatabaseName(workflow.connectionId);
  const unsupportedObjectVectorizeMessage = 'Object type is not vectorized';

  if (currentObjectType.value === 'tables') {
    if (kvContext) {
      if (activeDbType === 'REDIS') {
        return flattenRedisBrowserRows(redisBrowserRows.value);
      }
      return (kvOverview.value?.objects ?? []).map((item) => ({
        objectName: item.objectName,
        objectType: 'tables',
        rowEstimate: Number(item.itemCount ?? 0),
        tableSize: item.valueType || '-',
        description: item.description ?? '',
        vectorizeStatus: 'NOT_VECTORIZED',
        vectorizeMessage: 'KV 类型不进行向量化',
        vectorizeUpdatedAt: undefined,
      }));
    }
  const statsByTable = tableStatsCache.value[tableCacheKey(workflow.connectionId, databaseName)] ?? {};
  return (schemaOverview.value?.tableSummaries ?? []).map((item) => ({
      objectName: item.tableName,
      objectType: 'tables',
      rowEstimate: statsByTable[item.tableName]?.rowEstimate ?? item.rowEstimate ?? 0,
      tableSize: formatSize(statsByTable[item.tableName]?.tableSizeBytes ?? item.tableSizeBytes ?? 0),
      description: item.tableComment ?? '',
      vectorizeStatus: item.vectorizeStatus || 'NOT_VECTORIZED',
      vectorizeMessage: item.vectorizeMessage || '该表暂无向量化数据',
      vectorizeUpdatedAt: item.vectorizeUpdatedAt,
    }));
  }

  if (currentObjectType.value === 'views') {
    const names = browserObjectNameList.value;
    return names.map((name) => ({
      objectName: name,
      objectType: 'views',
      rowEstimate: 0,
      tableSize: '-',
      description: objectTypeLabel(currentObjectType.value),
      vectorizeStatus: 'NOT_VECTORIZED',
      vectorizeMessage: '视图不参与向量化',
      vectorizeUpdatedAt: undefined,
    }));
  }

  if (currentObjectType.value === 'queries') {
    return browserSavedQueryList.value.map((item) => ({
      objectName: item.title,
      objectType: 'queries',
      rowEstimate: 0,
      tableSize: formatTime(item.updatedAt) || '-',
      description: item.sqlText.replace(/\s+/g, ' ').trim().slice(0, 120) || '已保存查询',
      vectorizeStatus: 'SUCCESS',
      vectorizeMessage: '点击后恢复为新查询页签',
      vectorizeUpdatedAt: item.updatedAt,
      sqlText: item.sqlText,
      updatedAt: item.updatedAt,
    }));
  }

  const names = browserObjectNameList.value;
  return names.map((name) => ({
    objectName: name,
    objectType: currentObjectType.value,
    rowEstimate: 0,
    tableSize: '-',
    description: objectTypeLabel(currentObjectType.value),
    vectorizeStatus: 'NOT_VECTORIZED',
    vectorizeMessage: unsupportedObjectVectorizeMessage,
    vectorizeUpdatedAt: undefined,
  }));
});

const activeConnectionIsRedis = computed(() =>
  (connections.value.find((item) => item.id === workflow.connectionId)?.dbType || '') === 'REDIS',
);


const redisVisibleObjectRows = computed(() => redisBrowserRows.value);

const redisHierarchyTreeData = computed(() => []);

const selectedObjectRecord = computed(() => {
  if (activeConnectionIsRedis.value) {
    const browserRow = findRedisSelectedObjectRow(redisBrowserRows.value, selectedObjectName.value);
    if (browserRow) {
      return browserRow;
    }
    if (kvObjectDetail.value?.objectName === selectedObjectName.value && selectedObjectName.value) {
      return {
        nodeKey: `key:${selectedObjectName.value}`,
        nodeName: selectedObjectName.value.split(':').filter((item) => !!item).pop() || selectedObjectName.value,
        fullPath: selectedObjectName.value,
        redisNodeType: 'KEY',
        hasChildren: false,
        ttlSeconds: Number(kvObjectDetail.value.ttlSeconds ?? -1),
        objectName: selectedObjectName.value,
        objectType: 'tables',
        rowEstimate: 0,
        tableSize: kvObjectDetail.value.valueType || '-',
        description: kvObjectDetail.value.description || '',
        vectorizeStatus: 'NOT_VECTORIZED',
        vectorizeMessage: 'KV 类型不进行向量化',
        vectorizeUpdatedAt: undefined,
      } satisfies ObjectRow;
    }
    return null;
  }
  return objectRows.value.find((item) => item.objectName === selectedObjectName.value) ?? null;
});

const selectedTreeDetail = computed(() => {
  const key = selectedTreeKeys.value[0];
  if (!key) {
    return null;
  }
  const keyValue = String(key);
  const groupMatch = keyValue.match(/^group-(\d+)$/);
  if (groupMatch) {
    return {
      kind: 'group' as const,
      groupId: Number(groupMatch[1]),
    };
  }
  const objectMatch = keyValue.match(/^conn-(\d+)-db-(.+?)-obj-([a-z]+)-(.+)$/);
  if (objectMatch) {
    return {
      kind: 'object' as const,
      connectionId: Number(objectMatch[1]),
      databaseName: decodeURIComponent(objectMatch[2]),
      objectType: toObjectType(objectMatch[3]),
      objectName: decodeURIComponent(objectMatch[4]),
    };
  }
  const categoryMatch = keyValue.match(/^conn-(\d+)-db-(.+?)-category-([a-z]+)$/);
  if (categoryMatch) {
    return {
      kind: 'category' as const,
      connectionId: Number(categoryMatch[1]),
      databaseName: decodeURIComponent(categoryMatch[2]),
      category: categoryMatch[3],
    };
  }
  const databaseRootMatch = keyValue.match(/^conn-(\d+)-dbroot-(.+)$/);
  if (databaseRootMatch) {
    return {
      kind: 'databaseRoot' as const,
      connectionId: Number(databaseRootMatch[1]),
      databaseName: decodeURIComponent(databaseRootMatch[2]),
    };
  }
  const databaseMatch = keyValue.match(/^conn-(\d+)-db-(.+)$/);
  if (databaseMatch) {
    return {
      kind: 'database' as const,
      connectionId: Number(databaseMatch[1]),
      databaseName: decodeURIComponent(databaseMatch[2]),
    };
  }
  const connectionMatch = keyValue.match(/^conn-(\d+)$/);
  if (connectionMatch) {
    return {
      kind: 'connection' as const,
      connectionId: Number(connectionMatch[1]),
    };
  }
  return null;
});

const selectedTreeConnection = computed(() => {
  const connectionId = selectedTreeDetail.value?.connectionId ?? workflow.connectionId;
  return connections.value.find((item) => item.id === connectionId) ?? null;
});

const selectedTreeGroup = computed(() => {
  const groupId = selectedTreeDetail.value?.kind === 'group'
    ? selectedTreeDetail.value.groupId
    : (selectedTreeConnection.value?.groupId ?? 0);
  return connectionGroups.value.find((item) => item.id === groupId) ?? null;
});

const selectedTreeDatabaseStatusLabel = computed(() => {
  const detail = selectedTreeDetail.value;
  if (!detail || (detail.kind !== 'database' && detail.kind !== 'category' && detail.kind !== 'databaseRoot')) {
    return '-';
  }
  return databaseStatusLabel(getDatabaseVectorizeStatus(detail.connectionId, detail.databaseName));
});

const selectedTreeDatabaseTableCount = computed(() => {
  const detail = selectedTreeDetail.value;
  if (!detail || (detail.kind !== 'database' && detail.kind !== 'category' && detail.kind !== 'databaseRoot')) {
    return '-';
  }
  if (detail.kind === 'databaseRoot') {
    return '-';
  }
  if (isKvConnectionId(detail.connectionId)) {
    if (kvOverview.value && kvOverview.value.databaseName === detail.databaseName) {
      return `${kvOverview.value.objectCount ?? 0}`;
    }
    const tableNames = tableNameCache.value[tableCacheKey(detail.connectionId, detail.databaseName)] ?? [];
    return `${tableNames.length}`;
  }
  if (schemaOverview.value && schemaOverview.value.databaseName === detail.databaseName) {
    return `${schemaOverview.value.tableCount ?? 0}`;
  }
  const tableNames = tableNameCache.value[tableCacheKey(detail.connectionId, detail.databaseName)] ?? [];
  return `${tableNames.length}`;
});

const selectedTreeDatabaseColumnCount = computed(() => {
  const detail = selectedTreeDetail.value;
  if (!detail || (detail.kind !== 'database' && detail.kind !== 'category' && detail.kind !== 'databaseRoot')) {
    return '-';
  }
  if (detail.kind === 'databaseRoot') {
    return '-';
  }
  if (isKvConnectionId(detail.connectionId)) {
    return '-';
  }
  if (schemaOverview.value && schemaOverview.value.databaseName === detail.databaseName) {
    return `${schemaOverview.value.columnCount ?? 0}`;
  }
  return '-';
});

const createTableSqlText = computed(() => {
  if (!selectedObjectRecord.value || selectedObjectRecord.value.objectType !== 'tables') {
    return '-- 当前未选中表';
  }
  const tableName = selectedObjectRecord.value.objectName;
  const dbType = selectedConnection.value?.dbType ?? selectedTreeConnection.value?.dbType ?? 'MYSQL';
  const columns = tableDetail.value?.columns ?? [];
  if (!columns.length) {
    return `-- 未读取到表 ${tableName} 的字段元数据`;
  }
  return buildCreateTableSql(tableName, columns, dbType);
});

const createTableSqlHighlighted = computed(() => highlightSqlForDisplay(createTableSqlText.value));

const objectDefinitionSqlText = computed(() => {
  if (!selectedObjectRecord.value || (selectedObjectRecord.value.objectType !== 'views' && selectedObjectRecord.value.objectType !== 'functions')) {
    return '-- 当前对象不支持定义 SQL 展示';
  }
  const sqlText = String(objectDefinitionDetail.value?.definitionSql || '').trim();
  if (sqlText) {
    try {
      const dbType = selectedConnection.value?.dbType ?? selectedTreeConnection.value?.dbType ?? 'MYSQL';
      return formatSqlText(sqlText, dbType);
    } catch {
      return sqlText;
    }
  }
  const objectLabel = selectedObjectRecord.value.objectType === 'views' ? '视图' : '函数';
  return `-- 未读取到${objectLabel} ${selectedObjectRecord.value.objectName} 的定义 SQL`;
});

const objectDefinitionSqlHighlighted = computed(() => highlightSqlForDisplay(objectDefinitionSqlText.value));

const tableEditorSqlHighlighted = computed(() => {
  const sql = activeTableEditorTab.value?.previewSql || '-- 在右侧预览区展示结构变更 SQL';
  return highlightSqlForDisplay(sql);
});

const filteredObjectRows = computed(() => {
  if (activeConnectionIsRedis.value && currentObjectType.value === 'tables') {
    return objectRows.value;
  }
  const keyword = tableKeyword.value.trim().toLowerCase();
  if (!keyword) {
    return objectRows.value;
  }
  return objectRows.value.filter((item) => item.objectName.toLowerCase().includes(keyword));
});

const objectColumns = computed(() => {
  const activeDbType = connections.value.find((item) => item.id === workflow.connectionId)?.dbType || '';
  if (currentObjectType.value === 'tables') {
    if (isKvDbType(activeDbType)) {
      if (activeDbType === 'REDIS') {
        return [
          { title: tt('键名称'), dataIndex: 'nodeName', key: 'nodeName', width: 320, ellipsis: true },
          { title: tt('节点类型'), dataIndex: 'redisNodeType', key: 'redisNodeType', width: 120 },
          { title: tt('值类型'), dataIndex: 'tableSize', key: 'tableSize', width: 140 },
          { title: 'TTL', dataIndex: 'ttlSeconds', key: 'ttlSeconds', width: 120 },
          { title: tt('说明'), dataIndex: 'description', key: 'description', width: 320, ellipsis: true },
        ];
      }
      return [
        { title: primaryObjectLabelByDbType(activeDbType), dataIndex: 'objectName', key: 'objectName', width: 280, ellipsis: true },
        { title: tt('值类型'), dataIndex: 'tableSize', key: 'tableSize', width: 160 },
        { title: tt('数量'), dataIndex: 'rowEstimate', key: 'rowEstimate', width: 120 },
        { title: tt('说明'), dataIndex: 'description', key: 'description', width: 320, ellipsis: true },
      ];
    }
    return [
      { title: tt('对象'), dataIndex: 'objectName', key: 'objectName', width: 250, ellipsis: true },
      { title: tt('行数'), dataIndex: 'rowEstimate', key: 'rowEstimate', width: 120 },
      { title: tt('数据大小'), dataIndex: 'tableSize', key: 'tableSize', width: 120 },
      { title: tt('向量状态'), dataIndex: 'vectorizeStatus', key: 'vectorizeStatus', width: 150 },
      { title: tt('说明'), dataIndex: 'description', key: 'description', width: 300, ellipsis: true },
    ];
  }
  if (currentObjectType.value === 'queries') {
    return [
      { title: tt('名称'), dataIndex: 'objectName', key: 'objectName', width: 260, ellipsis: true },
      { title: tt('最近更新'), dataIndex: 'tableSize', key: 'tableSize', width: 160 },
      { title: tt('SQL 摘要'), dataIndex: 'description', key: 'description', width: 420, ellipsis: true },
    ];
  }
  return [
    { title: tt('对象'), dataIndex: 'objectName', key: 'objectName', width: 320, ellipsis: true },
    { title: tt('说明'), dataIndex: 'description', key: 'description', width: 180, ellipsis: true },
    { title: tt('向量状态'), dataIndex: 'vectorizeStatus', key: 'vectorizeStatus', width: 150 },
  ];
});

const tableScrollY = computed(() => Math.max(260, viewportHeight.value - 240));

const queryResultScrollY = computed(() => {
  const paneHeight = queryEditorPaneRef.value?.clientHeight ?? Math.max(520, viewportHeight.value - 190);
  const titleHeight = 40;
  const splitterHeight = 8;
  const resultHeaderHeight = 34;
  const resultFooterHeight = 28;
  const resultErrorHeight = activeQueryTab.value?.lastExecuteFailed ? 44 : 0;
  const available = paneHeight
    - titleHeight
    - queryEditorSectionHeight.value
    - splitterHeight
    - resultHeaderHeight
    - resultFooterHeight
    - resultErrorHeight
    - 8;
  return Math.max(180, available);
});

const aiModelOptions = computed(() =>
  (aiConfigForm.modelOptions ?? []).map((item) => ({
    label: `${item.name || item.id || '-'} · ${item.providerType === 'LOCAL_CLI' ? 'CLI' : (item.openaiModel || 'OPENAI')}`,
    value: item.id,
  })),
);

const workbenchStyle = computed(() => {
  if (viewportWidth.value < 1200) {
    return {};
  }
  if (activeWorkbenchTab.value === browserTabKey || activeKnowledgeTab.value || activeMemoryTab.value) {
    return {
      gridTemplateColumns: `${leftPaneWidth.value}px 1px minmax(460px, 1fr) 1px ${browserRightPaneWidth.value}px`,
    };
  }
  if (activeErTab.value) {
    return {
      gridTemplateColumns: `${leftPaneWidth.value}px 1px minmax(560px, 1fr) 1px ${erRightPaneWidth.value}px`,
    };
  }
  return {
    gridTemplateColumns: `${leftPaneWidth.value}px 1px minmax(520px, 1fr) 1px ${queryRightPaneWidth.value}px`,
  };
});

const activeResultRows = computed(() => {
  if (!activeQueryTab.value) {
    return [] as QueryResultTableRow[];
  }
  return activeStatementResult.value?.resultTableRows ?? activeQueryTab.value.resultTableRows;
});

const activeResultColumns = computed(() => {
  if (!activeQueryTab.value) {
    return [];
  }
  return activeStatementResult.value?.resultTableColumns ?? activeQueryTab.value.resultTableColumns;
});

const queryResultScrollX = computed(() =>
  Math.max(
    activeResultColumns.value.reduce((total, column) => total + clampGridColumnWidth(column.width), 0),
    960,
  ),
);

const chartTypeOptions = computed(() => [
  { label: tt('折线图'), value: 'LINE' as ChartType },
  { label: tt('柱状图'), value: 'BAR' as ChartType },
  { label: tt('饼图'), value: 'PIE' as ChartType },
  { label: tt('散点图'), value: 'SCATTER' as ChartType },
  { label: tt('趋势图'), value: 'TREND' as ChartType },
]);

const chartSortDirectionOptions = [
  { label: '不排序', value: 'NONE' as SortDirection },
  { label: '升序', value: 'ASC' as SortDirection },
  { label: '降序', value: 'DESC' as SortDirection },
];

const erLayoutModeOptions = computed(() => [
  { label: tt('网格布局'), value: 'GRID' as ErLayoutMode },
  { label: tt('环形布局'), value: 'CIRCLE' as ErLayoutMode },
  { label: tt('分层布局'), value: 'HIERARCHICAL' as ErLayoutMode },
]);

const erLineTypeOptions = computed(() => [
  { label: tt('折线'), value: 'POLYLINE' as ErLineType },
  { label: tt('直线'), value: 'STRAIGHT' as ErLineType },
]);

const activeChartRows = computed(() => activeResultRows.value.map((row) => {
  const normalized: Record<string, string | null> = {};
  Object.keys(row).forEach((key) => {
    if (key === '__rowKey') {
      return;
    }
    normalized[key] = row[key] ?? null;
  });
  return normalized;
}));

const activeChartFieldOptions = computed(() => activeResultColumns.value.map((column) => ({
  label: String(column.title || column.key),
  value: String(column.dataIndex),
})));

const activeNumericFieldOptions = computed(() => {
  const rows = activeChartRows.value;
  const fields = activeChartFieldOptions.value.map((item) => String(item.value));
  const numericFields = fields.filter((field) => isNumericField(rows, field));
  return numericFields.map((field) => ({
    label: field,
    value: field,
  }));
});

const activeSeriesFieldOptions = computed(() => {
  const currentXField = activeQueryTab.value?.manualChartConfig.xField || '';
  return activeChartFieldOptions.value.filter((item) => {
    const value = String(item.value);
    return value !== currentXField;
  });
});

const tableEditorSaving = ref(false);

function sessionRefKey(connectionId: number, sessionId: string) {
  return `${connectionId}::${sessionId}`;
}

function modelLabelById(modelId: string) {
  const model = aiConfigForm.modelOptions?.find((item) => item.id === modelId);
  if (!model) {
    return modelId || '-';
  }
  return model.name || model.id || '-';
}

function detailOutputEnabledForTab(tab: QueryWorkspaceTab | null | undefined) {
  if (!tab) {
    return aiConfigForm.detailOutputEnabled === true;
  }
  if (tab.detailOutputOverride == null) {
    return aiConfigForm.detailOutputEnabled === true;
  }
  return tab.detailOutputOverride === true;
}

function conversationMemoryEnabledForTab(tab: QueryWorkspaceTab | null | undefined) {
  if (!tab) {
    return aiConfigForm.conversationMemoryEnabled !== false;
  }
  return tab.conversationMemoryEnabled !== false;
}

function buildQueryContextUsage(tab: QueryWorkspaceTab | null | undefined): QueryContextUsage {
  const memoryWindowBudget = Math.min(32000, Math.max(512, Number(aiConfigForm.conversationMemoryWindowTokens || 6000)));
  const maxTurns = Math.min(50, Math.max(1, Number(aiConfigForm.conversationMemoryWindowSize || 12)));
  const enabled = conversationMemoryEnabledForTab(tab);
  if (!tab || !tab.chatMessages.length) {
    return {
      enabled,
      windowUsedTokens: 0,
      windowTotalTokens: memoryWindowBudget,
      windowRatio: 0,
      windowCappedRatio: 0,
      windowPercent: 0,
      promptUsedTokens: tab?.lastPromptBudget?.promptTokens ?? 0,
      promptTotalTokens: tab?.lastPromptBudget?.promptBudgetTokens ?? 0,
      promptRatio: 0,
      promptCappedRatio: 0,
      promptPercent: 0,
      tone: enabled ? 'idle' : 'normal',
    };
  }
  const selected: QueryChatMessage[] = [];
  let usedTokens = 0;
  let turnCount = 0;
  for (let index = tab.chatMessages.length - 1; index >= 0; index -= 1) {
    const item = tab.chatMessages[index];
    const itemTokens = estimateQueryChatMessageTokens(item);
    const nextTurnCount = turnCount + (item.role === 'user' ? 1 : 0);
    if (selected.length > 0 && (usedTokens + itemTokens > memoryWindowBudget || nextTurnCount > maxTurns)) {
      break;
    }
    selected.unshift(item);
    usedTokens += itemTokens;
    if (item.role === 'user') {
      turnCount = nextTurnCount;
    }
  }
  const budgetSnapshot = tab.lastPromptBudget;
  const promptUsedTokens = Math.max(0, Number(budgetSnapshot?.promptTokens ?? 0));
  const promptTotalTokens = Math.max(0, Number(budgetSnapshot?.promptBudgetTokens ?? 0));
  const windowTotalTokens = Math.max(0, Number(budgetSnapshot?.memoryWindowBudgetTokens ?? memoryWindowBudget));
  const windowUsedTokens = Math.max(0, Number(budgetSnapshot?.memoryWindowUsedTokens ?? usedTokens));
  const windowRatio = windowTotalTokens > 0 ? windowUsedTokens / windowTotalTokens : 0;
  const promptRatio = promptTotalTokens > 0 ? promptUsedTokens / promptTotalTokens : 0;
  const tone = resolveUsageTone(Math.max(windowRatio, promptRatio), enabled);
  return {
    enabled,
    windowUsedTokens,
    windowTotalTokens,
    windowRatio,
    windowCappedRatio: Math.max(0, Math.min(windowRatio, 1)),
    windowPercent: Math.round(windowRatio * 100),
    promptUsedTokens,
    promptTotalTokens,
    promptRatio,
    promptCappedRatio: Math.max(0, Math.min(promptRatio, 1)),
    promptPercent: Math.round(promptRatio * 100),
    tone,
  };
}

function resolveUsageTone(ratio: number, enabled: boolean): QueryContextUsage['tone'] {
  if (!enabled) {
    return 'normal';
  }
  if (ratio >= 1) {
    return 'danger';
  }
  if (ratio >= 0.85) {
    return 'warning';
  }
  if (ratio <= 0.05) {
    return 'idle';
  }
  return 'normal';
}

function estimateQueryChatMessageTokens(message: QueryChatMessage) {
  if (!message) {
    return 0;
  }
  return estimateTextTokens([
    message.content || '',
    message.sqlText || '',
    message.chartConfigSummary || '',
  ].filter((item) => !!item).join('\n'));
}

function estimateTextTokens(text: string) {
  const length = (text || '').trim().length;
  if (length <= 0) {
    return 0;
  }
  return Math.max(1, Math.ceil(length / 4));
}

function lastPromptText(tab: QueryWorkspaceTab) {
  const latestPrompt = [...tab.chatMessages].reverse().find((item) => item.role === 'user');
  return latestPrompt?.content || '暂无自然语言对话';
}

function touchQueryTab(tab: QueryWorkspaceTab) {
  syncActiveStatementResultFromTab(tab);
  tab.updatedAt = Date.now();
}

async function explainSelectedSqlInChat(tab: QueryWorkspaceTab) {
  await runAiTextActionWithSelectedSql(tab, 'explain');
}

async function analyzeSelectedSqlInChat(tab: QueryWorkspaceTab) {
  await runAiTextActionWithSelectedSql(tab, 'analyze');
}

async function explainMessageSqlInChat(tab: QueryWorkspaceTab, sqlText: string) {
  await runAiTextActionWithSql(tab, 'explain', sqlText);
}

async function analyzeMessageSqlInChat(tab: QueryWorkspaceTab, sqlText: string) {
  await runAiTextActionWithSql(tab, 'analyze', sqlText);
}

async function runAiTextActionWithSelectedSql(tab: QueryWorkspaceTab, actionType: 'explain' | 'analyze') {
  const selectedSqlText = tab.selectedSqlText.trim();
  if (!selectedSqlText) {
    message.info('请先选择一段 SQL');
    return;
  }
  hideSqlSelectionPopover();
  await runAiTextActionWithSql(tab, actionType, selectedSqlText);
}

async function runAiTextActionWithSql(
  tab: QueryWorkspaceTab,
  actionType: 'explain' | 'analyze',
  sqlText: string,
) {
  if (tab.aiGenerating) {
    return;
  }
  const normalizedSqlText = sqlText.trim();
  if (!normalizedSqlText) {
    message.info('当前消息不包含 SQL');
    return;
  }

  const promptText = actionType === 'explain' ? '请解释这段 SQL 的含义' : '请分析这段 SQL 的合理性';
  const userMessage = appendUserChatMessage(tab, `${promptText}\n\n${normalizedSqlText}`, actionType);
  const thinkingMessage = appendAssistantThinkingMessage(tab, actionType);
  tab.aiGenerating = true;

  try {
    const endpoint = actionType === 'explain' ? '/api/ai/query/explain/stream' : '/api/ai/query/analyze/stream';
    const streamState = {result: null as AiTextResponseVO | null};
    await postAiStreamWithTimeout(tab, endpoint, {
      connectionId: tab.connectionId,
      sessionId: tab.sessionId,
      prompt: mergePromptWithSqlSnippet(promptText, normalizedSqlText),
      databaseName: tab.databaseName || undefined,
      modelId: tab.selectedAiModel || undefined,
      memoryEnabled: conversationMemoryEnabledForTab(tab),
      detailOutputEnabled: detailOutputEnabledForTab(tab),
    }, (event) => {
      if (event.eventType === 'stage.updated' && event.stage) {
        ensureAssistantStreamingState(tab, thinkingMessage, actionType);
        upsertStreamingTraceStage(thinkingMessage, event.stage);
        return flushStreamingQueryTab(tab);
      }
      if (event.eventType === 'llm.thinking.delta') {
        ensureAssistantStreamingState(tab, thinkingMessage, actionType);
        thinkingMessage.thinkingContent = event.delta?.accumulatedText || thinkingMessage.thinkingContent || '';
        upsertStreamingTraceLlmDelta(thinkingMessage, actionType, 'thinking', thinkingMessage.thinkingContent || '');
        return flushStreamingQueryTab(tab);
      }
      if (event.eventType === 'llm.output.delta') {
        ensureAssistantStreamingState(tab, thinkingMessage, actionType);
        thinkingMessage.liveOutput = event.delta?.accumulatedText || thinkingMessage.liveOutput || '';
        thinkingMessage.content = thinkingMessage.liveOutput || thinkingMessage.content;
        upsertStreamingTraceLlmDelta(thinkingMessage, actionType, 'output', thinkingMessage.liveOutput || '');
        return flushStreamingQueryTab(tab);
      }
      if (event.eventType === 'trace.snapshot' && event.trace) {
        applyStreamTraceSnapshot(thinkingMessage, event.trace);
        return flushStreamingQueryTab(tab);
      }
      if (event.eventType === 'result.final') {
        streamState.result = event.finalResult?.textResponse || null;
        return;
      }
      if (event.eventType === 'error') {
        throw new Error(event.error?.message || 'AI 流式请求失败');
      }
    });
    const result = streamState.result;
    if (!result) {
      throw new Error('流式响应未返回最终结果');
    }
    applyResponseTokenSnapshot(tab, result);
    const content = result.content || '未返回内容';
    appendAssistantTextMessage(tab, content, actionType, result.trace, thinkingMessage);
    await saveConversationHistoryOnce(tab, userMessage, `${promptText}\n\n${normalizedSqlText}`, normalizedSqlText, {
      actionType,
      assistantContent: content,
      databaseName: tab.databaseName,
      trace: result.trace,
    });
    if (result.reasoning) {
      message.info(result.reasoning);
    }
    message.success(actionType === 'explain' ? 'SQL 含义解释已生成' : 'SQL 合理性分析已生成');
  } catch (error) {
    const msg = error instanceof Error ? error.message : String(error);
    if (isAiRequestAbortedMessage(msg)) {
      thinkingMessage.pending = false;
      thinkingMessage.streaming = false;
      thinkingMessage.aborted = true;
      if (!thinkingMessage.content && !thinkingMessage.liveOutput && !thinkingMessage.thinkingContent) {
        removeQueryChatMessage(tab, thinkingMessage);
      }
      message.info('已终止对话执行');
      return;
    }
    materializeAssistantErrorMessage(tab, thinkingMessage, actionType, msg);
    message.error(msg);
  } finally {
    tab.aiGenerating = false;
    touchQueryTab(tab);
  }
}

function resetErSnapshotTitleEditState() {
  editingErSnapshotId.value = null;
  editingErSnapshotTitle.value = '';
}

function ensureConnectionFormDbType() {
  if (!supportedDbTypes.value.length) {
    return;
  }
  if (findSupportedDbType(connectionForm.dbType)) {
    return;
  }
  const mysqlType = supportedDbTypes.value.find((item) => item.dbType === 'MYSQL');
  connectionForm.dbType = mysqlType?.dbType ?? supportedDbTypes.value[0].dbType;
}

function resolveQueryDatabaseName(tab: QueryWorkspaceTab | null) {
  if (!tab) {
    return '';
  }
  return (tab.databaseName || getActiveDatabaseName(tab.connectionId)).trim();
}


function columnSuggestions(
  monaco: typeof MonacoApi,
  columns: TableDetailVO['columns'],
  range: MonacoApi.IRange,
  prefix: string,
  sourceLabel: string,
) {
  const keyword = prefix.trim().toLowerCase();
  const seen = new Set<string>();
  const matchedColumns = columns.filter((item) => {
    const columnName = String(item.columnName || '').trim();
    if (!columnName) {
      return false;
    }
    const key = columnName.toLowerCase();
    if (seen.has(key)) {
      return false;
    }
    seen.add(key);
    if (!keyword) {
      return true;
    }
    return key.includes(keyword);
  });
  return matchedColumns.slice(0, 300).map((item) => {
    const columnName = item.columnName;
    const lowerColumnName = columnName.toLowerCase();
    const startsWithPrefix = keyword && lowerColumnName.startsWith(keyword);
    const comment = String(item.columnComment || '').trim();
    const dataType = String(item.dataType || '').trim();
    return {
      label: columnName,
      kind: monaco.languages.CompletionItemKind.Field,
      insertText: columnName,
      range,
      detail: `字段 · ${sourceLabel}`,
      documentation: comment || dataType || undefined,
      sortText: `${startsWithPrefix ? '0' : '1'}_column_${sourceLabel}_${columnName}`,
    };
  });
}

async function handleRedisHierarchySelect(keys: (string | number)[]) {
  const key = String(keys[0] || '');
  if (!key) {
    redisSelectedRowKey.value = '';
    return;
  }
  if (key.startsWith('redis-path-')) {
    const record = findRedisBrowserRowByNodeKey(redisBrowserRows.value, `path:${key.slice('redis-path-'.length)}`);
    if (record) {
      await toggleRedisBrowserPath(record);
    }
    return;
  }
  if (key.startsWith('redis-key-')) {
    const objectName = key.slice('redis-key-'.length);
    await selectObject(workflow.connectionId, getActiveDatabaseName(workflow.connectionId), 'tables', objectName);
  }
}

const activeQueryEditorLanguage = computed(() =>
  queryEditorLanguageByDbType(activeQueryTab.value ? queryTabDbType(activeQueryTab.value) : 'MYSQL'),
);

const activeConnectionIsKv = computed(() => isKvConnectionId(workflow.connectionId));

const {
  fetchRedisBrowserPage,
  handleRedisBrowserExpand,
  invalidateRedisBrowserCache,
  loadMoreRedisBrowserRows,
  loadRedisBrowserChildren,
  loadRedisBrowserRows,
  toggleRedisBrowserPath,
} = createRedisBrowserRuntimeHelpers({
  getActiveDatabaseName,
  redisBrowseExpandedRowKeys,
  redisBrowserChildLoadingMap,
  redisBrowserContextKey,
  redisBrowserLoading,
  redisBrowserPageCache,
  redisBrowserRows,
  redisExpandedRowKeys,
  setConnectionRuntimeStatus,
  tableKeyword,
  workflow,
});

const {
  closeRedisKeyModal,
  confirmRedisKeyModal,
  deleteRedisKey,
  openCreateRedisKeyModal,
  openEditRedisKeyModal,
} = createRedisKeyModalHelpers({
  activeConnectionIsKv,
  clearObjectDetail,
  getActiveDatabaseName,
  invalidateRedisBrowserCache: (connectionId, databaseName) => invalidateRedisBrowserCache(connectionId, databaseName),
  kvObjectDetail,
  loadObjectDetail: (connectionId, databaseName, objectType, objectName) =>
    loadObjectDetail(connectionId, databaseName, objectType, objectName),
  loadOverview: (options) => loadOverview(options),
  redisKeyForm,
  redisKeyModalMode,
  redisKeyModalOpen,
  redisKeyModalSubmitting,
  runSafely,
  selectedConnection,
  selectedObjectName,
  selectedObjectRecord,
  workflow,
});

function ensureConnection() {
  if (!workflow.connectionId) {
    throw new Error('请先选择连接');
  }
}

async function runSafely(task: () => Promise<void>) {
  try {
    await task();
  } catch (error) {
    const msg = error instanceof Error ? error.message : String(error);
    message.error(msg);
  }
}

function formatSize(sizeBytes: number) {
  if (sizeBytes >= 1024 * 1024) {
    return `${(sizeBytes / (1024 * 1024)).toFixed(1)} MB`;
  }
  if (sizeBytes >= 1024) {
    return `${(sizeBytes / 1024).toFixed(1)} KB`;
  }
  return `${sizeBytes} B`;
}

function formatCompactCount(count?: number) {
  const value = count ?? 0;
  if (value <= 0) {
    return '0';
  }
  if (value < 1000) {
    return `${value}`;
  }
  return new Intl.NumberFormat('zh-CN', {
    notation: 'compact',
    maximumFractionDigits: 1,
  }).format(value);
}

function formatTime(ts?: number) {
  if (!ts) {
    return '-';
  }
  const date = new Date(ts);
  const y = date.getFullYear();
  const m = String(date.getMonth() + 1).padStart(2, '0');
  const d = String(date.getDate()).padStart(2, '0');
  const hh = String(date.getHours()).padStart(2, '0');
  const mm = String(date.getMinutes()).padStart(2, '0');
  const ss = String(date.getSeconds()).padStart(2, '0');
  return `${y}-${m}-${d} ${hh}:${mm}:${ss}`;
}

function formatDurationMs(durationMs?: number) {
  if (durationMs == null || durationMs < 0) {
    return '-';
  }
  if (durationMs < 1000) {
    return `${durationMs} ms`;
  }
  if (durationMs < 60_000) {
    return `${(durationMs / 1000).toFixed(1)} s`;
  }

  const totalSeconds = Math.floor(durationMs / 1000);
  const hours = Math.floor(totalSeconds / 3600);
  const minutes = Math.floor((totalSeconds % 3600) / 60);
  const seconds = totalSeconds % 60;
  if (hours > 0) {
    return `${hours}h ${minutes}m ${seconds}s`;
  }
  return `${minutes}m ${seconds}s`;
}

async function copyCreateTableSql() {
  await copyTextContent(createTableSqlText.value, '建表语句已复制');
}

async function copyTableEditorSql() {
  const sql = activeTableEditorTab.value?.previewSql || '';
  await copyTextContent(sql, 'SQL 已复制');
}

  return {
    connectionGroups,
    ragLocalOnnxEnabled,
    ragProviderTypeOptions,
    browserTabKey,
    uiThemeStorageKey,
    defaultAlgorithm,
    darkAlgorithm,
    isMacOS,
    isWindows,
    isLinux,
    vectorizeStatusPollTimer,
    vectorizeStatusPollIntervalMs,
    tableStatsMinRequestIntervalMs,
    tableStatsPollIntervalMs,
    connections,
    schemaOverview,
    kvOverview,
    selectedObjectName,
    createModalOpen,
    isEditMode,
    editingConnectionId,
    connectionRefreshing,
    connectionKeyword,
    tableKeyword,
    browserNavMode,
    selectedTreeKeys,
    expandedTreeKeys,
    tableNameCache,
    tableNameLoadedCache,
    queryTableDetailCache,
    objectNameCache,
    savedQueryCache,
    tableStatsCache,
    tableStatsLoadingState,
    tableStatsLastRequestAt,
    databaseListCache,
    activeDatabaseMap,
    databaseVectorizeStatusMap,
    currentObjectType,
    objectViewMode,
    viewportHeight,
    vectorizeOverviewModalOpen,
    vectorizeOverviewLoading,
    vectorizeOverviewData,
    groupModalOpen,
    groupModalSubmitting,
    groupForm,
    saveQueryModalOpen,
    saveQuerySubmitting,
    saveQueryTitle,
    truncateTableModalOpen,
    truncateTableName,
    dropTableModalOpen,
    dropTableName,
    renameTableModalOpen,
    renameTableSubmitting,
    renameTableForm,
    namespaceModalOpen,
    namespaceModalSubmitting,
    namespaceForm,
    browserDetailCollapsed,
    tableCopyClipboard,
    tablePasteModalOpen,
    tablePasteSubmitting,
    tablePasteForm,
    tableCopyTaskModalOpen,
    tableCopyTaskInfo,
    aiConfigModalOpen,
    aiConfigActiveTab,
    uiTheme,
    selectedAiModel,
    activeWorkbenchTab,
    queryTabs,
    erTabs,
    tableEditorTabs,
    tableDataTabs,
    objectDefinitionEditorTabs,
    knowledgeTabs,
    memoryTabs,
    erTableSelectModalOpen,
    erTableSelectSubmitting,
    erSelectConnectionId,
    erSelectDatabaseName,
    erSelectTargetTabKey,
    erSelectTableKeyword,
    erSelectTableOptions,
    erSelectTableValues,
    erSelectModelName,
    historyReloading,
    historyLoadingMore,
    historySessionLoadingKey,
    historyKeywordInput,
    historyKeyword,
    historySessionItems,
    historySessionPageNo,
    historySessionPageSize,
    historySessionHasMore,
    historySessionConnectionId,
    erSnapshotReloading,
    erSnapshotLoadingMore,
    erSnapshotLoadingId,
    erSnapshotActionLoadingId,
    erSnapshotKeywordInput,
    erSnapshotKeyword,
    erSnapshotItems,
    erSnapshotPageNo,
    erSnapshotPageSize,
    erSnapshotHasMore,
    erSnapshotConnectionId,
    erSnapshotSaveModalOpen,
    erSnapshotSaveSubmitting,
    erSnapshotSaveName,
    erSnapshotSaveTabKey,
    editingErSnapshotId,
    editingErSnapshotTitle,
    editingHistoryTabKey,
    editingHistoryTitle,
    sessionTitleOverrides,
    tableDetail,
    tableDetailLoading,
    kvObjectDetail,
    kvObjectDetailLoading,
    redisHierarchyPath,
    redisBrowserRows,
    redisBrowserLoading,
    redisExpandedRowKeys,
    redisSelectedRowKey,
    redisKeyModalOpen,
    redisKeyModalSubmitting,
    redisKeyModalMode,
    redisKeyForm,
    objectDefinitionDetail,
    objectDefinitionDetailLoading,
    queryEditorPaneRef,
    sqlEditorContainerRef,
    queryChatScrollRef,
    queryChatMessageElementMap,
    queryChartPanelRef,
    erDiagramPanelRef,
    sqlSelectionPopover,
    viewportWidth,
    leftPaneWidth,
    leftPaneResizeState,
    browserRightPaneWidth,
    browserPaneResizeState,
    erRightPaneWidth,
    erPaneResizeState,
    queryRightPaneWidth,
    queryPaneResizeState,
    queryEditorSectionHeight,
    queryEditorSectionResizeState,
    contextMenu,
    connectionForm,
    connectionFormSubmitted,
    connectionFormErrors,
    connectionPreviewDbOptions,
    connectionPreviewLoading,
    connectionPreviewError,
    aiConfigForm,
    ragConfigForm,
    pickingRagModelDir,
    pickingRagRerankModelDir,
    workflow,
    supportedDbTypes,
    dbTypeOptions,
    envOptions,
    sshAuthTypeOptions,
    sqlEditorOptions,
    sqlKeywords,
    sqlCompletionProviderDisposable,
    sqlEditorTypeDisposable,
    sqlEditorSelectionDisposable,
    sqlEditorScrollDisposable,
    sqlEditorMouseDownDisposable,
    sqlEditorMouseUpDisposable,
    sqlAutoSuggestTimer,
    activeSqlEditorInstance,
    pendingTableNameLoads,
    tableStatsPollingTimers,
    sessionTitleOverridesStorageKey,
    sqlExecutionAbortControllerMap,
    sqlExecutionAbortReasonMap,
    aiRequestAbortControllerMap,
    aiRequestAbortReasonMap,
    selectedConnection,
    activeQueryTab,
    activeQueryEditorLanguage,
    activeConnectionIsKv,
    activeQueryContextUsage,
    activeErTab,
    activeTableEditorTab,
    activeTableDataTab,
    activeObjectDefinitionEditorTab,
    activeKnowledgeTab,
    activeMemoryTab,
    activeErConfidenceThreshold,
    activeErAiRelationTotal,
    activeErDisplayGraph,
    activeErForeignKeyRelations,
    activeErAiRelations,
    activeErManualRelations,
    canOpenHistory,
    canOpenErSnapshot,
    isDarkTheme,
    monacoTheme,
    antdThemeConfig,
    currentHistoryConnectionId,
    currentErSnapshotConnectionId,
    sessionHistoryTabs,
    filteredErSelectTableOptions,
    isContextDatabaseVectorizing,
    canViewContextVectorizedData,
    canInterruptContextVectorize,
    canOpenBrowserErFeature,
    browserErEntryTooltip,
    canCreateTable,
    canCreateView,
    canCreateFunction,
    connectionSelectOptions,
    connectionGroupOptions,
    connectionFormDbTypeSpec,
    isMultiDatabaseFormType,
    connectionPreviewSelectOptions,
    canPreviewDatabases,
    connectionTreeData,
    objectRows,
    activeConnectionIsRedis,
    redisHierarchyTreeData,
    redisVisibleObjectRows,
    selectedObjectRecord,
    selectedTreeDetail,
    selectedTreeConnection,
    selectedTreeGroup,
    selectedTreeDatabaseStatusLabel,
    selectedTreeDatabaseTableCount,
    selectedTreeDatabaseColumnCount,
    createTableSqlText,
    createTableSqlHighlighted,
    objectDefinitionSqlText,
    objectDefinitionSqlHighlighted,
    tableEditorSqlHighlighted,
    filteredObjectRows,
    objectColumns,
    tableScrollY,
    queryResultScrollY,
    aiModelOptions,
    workbenchStyle,
    activeStatementResult,
    activeResultRows,
    activeResultColumns,
    queryResultScrollX,
    chartTypeOptions,
    chartSortDirectionOptions,
    erLayoutModeOptions,
    erLineTypeOptions,
    activeChartRows,
    activeChartFieldOptions,
    activeNumericFieldOptions,
    activeSeriesFieldOptions,
    emptyManualChartConfig,
    cloneChartConfig,
    isNumericField,
    handleManualChartTypeChange,
    handleManualChartXAxisChange,
    handleManualChartYFieldsChange,
    handleManualChartSingleYFieldChange,
    handleManualChartSeriesFieldChange,
    setupManualChartConfigByResult,
    setActiveStatementResult,
    setQueryResultViewMode,
    resizeActiveQueryResultColumn,
    canExportActiveQueryResult,
    queryResultExportTooltip,
    resultTabTitle,
    buildConnectionNode,
    buildCategoryChildren,
    getCategoryChildren,
    findSupportedDbType,
    requiresDatabaseLayer,
    supportsSchemaLayer,
    isMultiDatabaseType,
    isKvConnectionId,
    getDatabaseNamePlaceholder,
    normalizeSelectedDatabases,
    visibleDatabasesForConnection,
    parseConfiguredDatabaseName,
    sanitizeDatabaseName,
    tableCacheKey,
    objectCacheKey,
    vectorizeStatusCacheKey,
    invalidateConnectionMetadataCaches,
    invalidateDatabaseMetadataCaches,
    invalidateDatabaseListCache,
    collapseConnectionNode,
    resetConnectionRuntimeState,
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
    openAiQueryTab,
    closeQueryTab,
    requestSqlExecutionInterrupt,
    tableEditorSaving,
    hasWorkbenchTab,
    ensureActiveWorkbenchTab,
    openErTableSelectModal,
    refreshErGraphForTab,
    confirmErTableSelection,
    sessionRefKey,
    sessionTitleOverrideKey,
    historyItemKey,
    findQueryTabBySession,
    queryTabConnectionNameById,
    normalizeTitleSource,
    buildSessionDefaultTitle,
    firstPromptForTitle,
    buildNewQueryPlaceholderTitle,
    applySessionTitle,
    loadSessionTitleOverrides,
    persistSessionTitleOverrides,
    cancelHistoryTitleEdit,
    modelLabelById,
    detailOutputEnabledForTab,
    toggleMessageTraceExpanded,
    lastPromptText,
    assistantActionLabel,
    normalizeHistoryActionType,
    userBubbleClass,
    touchQueryTab,
    bindQueryChatMessageRef,
    scrollToQueryChatMessage,
    appendUserChatMessage,
    appendAssistantThinkingMessage,
    removeQueryChatMessage,
    prepareAssistantMessage,
    appendAssistantSqlMessage,
    appendAssistantTextMessage,
    explainSelectedSqlInChat,
    analyzeSelectedSqlInChat,
    explainMessageSqlInChat,
    analyzeMessageSqlInChat,
    runAiTextActionWithSelectedSql,
    runAiTextActionWithSql,
    prepareConnectionTreeData,
    loadDatabaseListForConnection,
    refreshVectorizeStatusForConnection,
    refreshAllVectorizeStatuses,
    pruneVectorizeStatusMap,
    startVectorizeStatusPolling,
    stopVectorizeStatusPolling,
    loadConnections,
    refreshConnections,
    openCreateGroupModal,
    openRenameGroupModal,
    closeGroupModal,
    confirmGroupModal,
    removeConnectionGroup,
    saveConnection,
    previewConnectionDatabases,
    testConnection,
    disconnectConnection,
    removeConnection,
    syncSchema,
    loadOverview,
    clearTableStatsPollingTimer,
    clearAllTableStatsPollingTimers,
    applyTableStatsSnapshot,
    isDatabaseNodeExpanded,
    collectExpandedDatabaseTargets,
    fetchTableStatsForDatabase,
    scheduleTableStatsForExpandedDatabases,
    loadTableNamesByConnection,
    resolveQueryDatabaseName,
    ensureTableNamesLoaded,
    ensureQueryTableDetailLoaded,
    tableNameSuggestions,
    sqlKeywordSuggestions,
    hasKeywordSuggestion,
    hasTableSuggestion,
    shouldAutoTriggerSuggest,
    registerSqlCompletionProvider,
    registerSqlAutoSuggest,
    readSelectedSql,
    hideSqlSelectionPopover,
    updateSqlSelectionPopoverPosition,
    syncSelectedSqlForActiveTab,
    registerSqlSelectionTracker,
    registerSqlSelectionPopoverTrigger,
    registerSqlScrollTracker,
    warmupTableSuggestions,
    handleSqlEditorMount,
    loadObjectNames,
    refreshCurrentObjects,
    refreshCurrentPageObjects,
    loadCategoryObjects,
    loadTreeChildrenByKey,
    handleTreeSelect,
    handleTreeExpand,
    handleConnectionTreeDrop,
    handleTreeRightClick,
    closeContextMenu,
    loadSavedQueries,
    openVectorizeOverview,
    enqueueDatabaseRevectorize,
    vectorizeSingleTable,
    interruptDatabaseVectorize,
    selectObject,
    loadObjectDetail,
    clearObjectDetail,
    openCreateRedisKeyModal,
    openEditRedisKeyModal,
    closeRedisKeyModal,
    confirmRedisKeyModal,
    deleteRedisKey,
    handleRedisBrowserExpand,
    toggleRedisBrowserPath,
    loadMoreRedisBrowserRows,
    handleRedisHierarchySelect,
    openQueryTabByObject,
    openSaveQueryModal,
    saveCurrentQuery,
    openSavedQueryTab,
    openSavedQueryTabByTitle,
    getDesktopBridge,
    pickRagEmbeddingModelDir,
    pickRagRerankModelDir,
    openAiConfigModal,
    saveAiConfig,
    databaseOptionsForTab,
    databaseOptionsForTableEditorTab,
    databaseOptionsForTableDataTab,
    queryTabConnectionName,
    handleQueryConnectionChange,
    handleQueryDatabaseChange,
    handleTableEditorConnectionChange,
    handleTableEditorDatabaseChange,
    queryTabDbType,
    resolveSqlForAction,
    resolveSelectedSqlSnippet,
    queryEditorLanguageByDbType,
    queryUnitLabelByDbType,
    generateActionLabelByDbType,
    explainActionLabelByDbType,
    analyzeActionLabelByDbType,
    canGenerateChartForTab,
    formatSqlForTab,
    formatObjectDefinitionSql,
    extractThinkingContentFromTrace,
    saveConversationHistory,
    buildStructuredContextForTab,
    saveConversationHistoryOnce,
    timeoutRetryErrorMessage,
    isTimeoutErrorMessage,
    isAbortError,
    getErrorMessage,
    clearUserRetryState,
    markUserMessageRetryable,
    mergePromptWithSqlSnippet,
    aiRequestTimeoutMs,
    AI_REQUEST_ABORTED,
    isAiRequestAbortedMessage,
    postAiApiWithTimeout,
    postAiStreamWithTimeout,
    looksLikeSqlText,
    looksLikeExecutableQueryText,
    generateSqlForTab,
    autoActionTypeByIntent,
    sendAutoForTab,
    buildChartPrompt,
    chartTypeLabel,
    chartSummaryText,
    dedupeChartMessageContent,
    isChartConfigRenderable,
    buildExecutionPreview,
    chatExecutionColumns,
    chartExportPixelRatioCandidates,
    erDiagramExportPixelRatioCandidates,
    exportChartPngDataUrl,
    exportErDiagramPngDataUrl,
    normalizeDownloadFileNamePart,
    isChartCacheRetryableError,
    normalizeChartCacheErrorMessage,
    isLikelyLocalFilePath,
    saveChartImageCache,
    loadChartImageDataUrl,
    cacheChartImageWithRetry,
    downloadImage,
    generateChartPlanForTab,
    generateChartFromMessage,
    generateManualChartForTab,
    downloadActiveChart,
    exportResultTab,
    downloadMessageChart,
    downloadActiveErDiagram,
    hydrateHistoryChartImages,
    editChartFromHistory,
    explainSqlForTab,
    RISK_EXECUTION_CANCELLED,
    SQL_EXECUTION_ABORTED,
    connectionEnvLabel,
    ensureRiskConfirmedBeforeExecute,
    executeSqlForTab,
    repairSqlForTab,
    exportActiveQueryResult,
    riskColor,
    normalizeRiskLevel,
    ensureConnection,
    runSafely,
    formatSize,
    formatCompactCount,
    formatTime,
    formatDurationMs,
    formatVectorizeProvider,
    expandConnectionNode,
    ensureConnectionTreeExpanded,
    buildDatabaseRootNodeKey,
    buildDatabaseNodeKey,
    buildCategoryNodeKey,
    buildObjectNodeKey,
    expandCategoryNode,
    toObjectType,
    objectTypeLabel,
    normalizeEnv,
    envTagText,
    envTagClass,
    envTagIcon,
    connectionStatusClass,
    connectionStatusText,
    nodeIconComponent,
    quoteSqlIdentifier,
    buildColumnSqlDefinition,
    buildCreateTableSql,
    escapeHtml,
    highlightSqlForDisplay,
    copyTextContent,
    copyCreateTableSql,
    copyTableEditorSql,
    dbIconUrl,
    treeNodeIconUrl,
    browserObjectIconSrc,
    treeTitleIconSrc,
    normalizeModelOptions,
    nextModelOptionId,
    addOpenAiModelOption,
    addCliModelOption,
    removeModelOption,
    defaultConnectionForm,
    resetConnectionForm,
    fillConnectionForm,
    defaultAiConfigForm,
    fillAiConfigForm,
    defaultRagConfigForm,
    fillRagConfigForm,
    resetConnectionModalState,
  };
}

export type StudioRuntimeState = ReturnType<typeof createStudioRuntimeState>;
