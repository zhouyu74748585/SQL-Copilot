import {
  ApartmentOutlined,
  AppstoreOutlined,
  AreaChartOutlined,
  ArrowLeftOutlined,
  BulbFilled,
  BulbOutlined,
  CheckCircleOutlined,
  ClockCircleOutlined,
  CloseCircleOutlined,
  CloseOutlined,
  CodeOutlined,
  CopyOutlined,
  DatabaseOutlined,
  DeleteOutlined,
  DownloadOutlined,
  EditOutlined,
  ExperimentOutlined,
  EyeOutlined,
  FolderOpenOutlined,
  HddOutlined,
  HistoryOutlined,
  LinkOutlined,
  LoadingOutlined,
  MessageOutlined,
  MinusCircleOutlined,
  PlayCircleOutlined,
  PlusOutlined,
  ReadOutlined,
  ReloadOutlined,
  SearchOutlined,
  SendOutlined,
  SettingOutlined,
  StopOutlined,
  SyncOutlined,
  TableOutlined,
  ToolOutlined,
  UnorderedListOutlined,
} from '@ant-design/icons-vue';
import {Editor as MonacoEditor} from '@guolao/vue-monaco-editor';
import type * as MonacoApi from 'monaco-editor';
import type {IDisposable} from 'monaco-editor';
import type {ConnectionVO} from '@sqlcopilot/shared-contracts';
import {message, Modal, theme as antdTheme} from 'ant-design-vue';
import {computed, h, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch} from 'vue';
import {getApi, postApi, postSseApi} from '../../../api/client';
import QueryChartPanel from '../../../components/QueryChartPanel.vue';
import ErDiagramPanel from '../../../components/ErDiagramPanel.vue';
import TableEditor from '../../../components/TableEditor.vue';
import mysqlIcon from '../../../assets/db/mysql.svg';
import oracleIcon from '../../../assets/db/oracle.svg';
import postgresqlIcon from '../../../assets/db/postgresql.svg';
import sqliteIcon from '../../../assets/db/sqlite.svg';
import sqlserverIcon from '../../../assets/db/sqlserver.svg';
import {
  minimalPackage,
  normalizeRagProviderByPackage,
  ragProviderOptions,
  sqlCopilotPackageVariant,
} from '../../../config/packageVariant';
import type {
  AiAutoQueryVO,
  AiConfigSaveReq,
  AiConfigVO,
  AiGenerateChartVO,
  AiGenerateSqlVO,
  AiIntentType,
  AiModelOption,
  AiTraceStageVO,
  AiTraceVO,
  AiRepairVO,
  AiStreamEventVO,
  AiTextResponseVO,
  ChartCacheReadVO,
  ChartCacheSaveReq,
  ChartCacheSaveVO,
  ChartConfigVO,
  ChartType,
  ConnectionCreateReq,
  ConnectionDatabasePreviewReq,
  ConnectionDatabasePreviewVO,
  ErGraphReq,
  ErGraphSnapshotSummaryVO,
  ErGraphVO,
  ErLayoutMode,
  ErRelationVO,
  ExplainVO,
  QueryHistorySessionPageVO,
  QueryHistorySessionVO,
  QueryHistoryVO,
  RagConfigSaveReq,
  RagConfigVO,
  RagDatabaseVectorizeStatusVO,
  RagVectorizeEnqueueVO,
  RagVectorizeInterruptVO,
  RagVectorizeOverviewVO,
  RagVectorizeTableVO,
  RiskEvaluateVO,
  SavedQuerySaveReq,
  SavedQueryVO,
  SchemaDatabaseVO,
  SchemaOverviewVO,
  SchemaTableStatsVO,
  SortDirection,
  SqlExecuteVO,
  TableDetailVO,
} from '../../../types';

export function useStudioRuntime() {
const packageVariant = sqlCopilotPackageVariant;
const ragLocalOnnxEnabled = !minimalPackage;
const ragProviderTypeOptions = ragProviderOptions;
interface DesktopDialogFilter {
  name: string;
  extensions: string[];
}

interface DesktopPickFileOptions {
  title?: string;
  defaultPath?: string;
  filters?: DesktopDialogFilter[];
}

interface DesktopBridge {
  pickFile: (options?: DesktopPickFileOptions) => Promise<string>;
  pickDirectory: (options?: Omit<DesktopPickFileOptions, 'filters'>) => Promise<string>;
  saveChartCache?: (payload: ChartCacheSaveReq) => Promise<{ filePath: string; width: number; height: number }>;
  readChartCache?: (filePath: string) => Promise<string>;
}

interface ObjectRow {
  objectName: string;
  objectType: 'tables' | 'views' | 'functions' | 'events' | 'queries' | 'backups';
  rowEstimate: number;
  tableSize: string;
  description: string;
  vectorizeStatus: string;
  vectorizeMessage?: string;
  vectorizeUpdatedAt?: number;
  sqlText?: string;
  updatedAt?: number;
}

type QueryActionType =
  | 'generate'
  | 'explain'
  | 'analyze'
  | 'auto_generate'
  | 'auto_explain'
  | 'auto_analyze'
  | 'auto_chart_auto_plan'
  | 'repair'
  | 'chart_auto_plan'
  | 'chart_manual_render'
  | 'chart_auto_render';

type AiActionType = 'generate' | 'explain' | 'analyze';

type UiTheme = 'light' | 'dark';

type QueryResultViewMode = 'table' | 'chart';

type RetryActionKind = 'ai_action' | 'auto' | 'chart_plan';

type RequestAbortReason = 'manual' | 'timeout';

interface RetryRequestMeta {
  kind: RetryActionKind;
  actionType?: AiActionType;
  promptText: string;
  finalPrompt: string;
  actionSqlSnippet?: string;
}

interface QueryExecutionPreview {
  affectedRows: number;
  executionMs: number;
  columns: string[];
  rows: Array<Record<string, string | null>>;
  truncated: boolean;
}

interface QueryChatMessage {
  id: string;
  role: 'user' | 'assistant';
  content: string;
  pending?: boolean;
  streaming?: boolean;
  finalized?: boolean;
  thinkingContent?: string;
  liveOutput?: string;
  aborted?: boolean;
  sqlText?: string;
  actionType: QueryActionType;
  chartConfig?: ChartConfigVO;
  chartConfigSummary?: string;
  chartImageCacheKey?: string;
  chartImageDataUrl?: string;
  executionPreview?: QueryExecutionPreview;
  retryable?: boolean;
  retryLoading?: boolean;
  retryMeta?: RetryRequestMeta;
  historySaved?: boolean;
  trace?: AiTraceVO;
  traceExpanded?: boolean;
  createdAt: number;
}

interface QueryWorkspaceTab {
  key: string;
  title: string;
  connectionId: number;
  databaseName: string;
  sessionId: string;
  prompt: string;
  sqlText: string;
  riskAckToken: string;
  riskInfo: RiskEvaluateVO | null;
  executeResult: SqlExecuteVO | null;
  explainResult: ExplainVO | null;
  selectedAiModel: string;
  autoMode: boolean;
  autoExecute: boolean;
  aiGenerating: boolean;
  sqlExecuting: boolean;
  selectedSqlText: string;
  chatMessages: QueryChatMessage[];
  lastExecuteFailed: boolean;
  lastExecuteErrorMessage: string;
  lastFailedSqlText: string;
  resultViewMode: QueryResultViewMode;
  manualChartConfig: ChartConfigVO;
  activeChartConfig: ChartConfigVO | null;
  chartImageDataUrl: string;
  chartImageCacheKey: string;
  chartReadonly: boolean;
  createdAt: number;
  updatedAt: number;
  memoryEnabled: boolean;
  detailOutputOverride: boolean | null;
  lastTokenEstimate: number;
}

interface QueryContextUsage {
  enabled: boolean;
  usedTokens: number;
  totalTokens: number;
  ratio: number;
  cappedRatio: number;
  percent: number;
  tone: 'idle' | 'normal' | 'warning' | 'danger';
}

interface SqlEditorContext {
  connectionId: number;
  databaseName: string;
}

interface SqlEditorMountOptions {
  getContext?: () => SqlEditorContext | null;
  enableSelectionActions?: boolean;
}

interface ErWorkspaceTab {
  key: string;
  title: string;
  snapshotId?: number;
  connectionId: number;
  databaseName: string;
  selectedTableNames: string[];
  selectedAiModel: string;
  layoutMode: ErLayoutMode;
  lineType: ErLineType;
  showCardComments: boolean;
  aiConfidenceThreshold: number;
  includeAiInference: boolean;
  loading: boolean;
  graph: ErGraphVO | null;
  errorMessage: string;
  createdAt: number;
  updatedAt: number;
}

interface TableEditorColumnDraft {
  uuid: string;
  columnName: string;
  dataType: string;
  columnSize: number | null;
  decimalDigits: number | null;
  defaultValue: string;
  autoIncrement: boolean;
  nullable: boolean;
  columnComment: string;
  primaryKey: boolean;
  indexed: boolean;
  defaultCurrentTimestamp: boolean;
  onUpdateCurrentTimestamp: boolean;
}

interface TableEditorIndexDraft {
  uuid: string;
  indexName: string;
  unique: boolean;
  columns: string[];
}

interface TableEditorDraft {
  tableName: string;
  tableComment: string;
  columns: TableEditorColumnDraft[];
  indexes: TableEditorIndexDraft[];
}

interface TableEditorWorkspaceTab {
  key: string;
  title: string;
  connectionId: number;
  databaseName: string;
  tableName: string;
  dbType: string;
  mode: 'create' | 'edit';
  tableDetail: TableDetailVO | null;
  draft: TableEditorDraft | null;
  baselineDraft: TableEditorDraft | null;
  previewSql: string;
  canSave: boolean;
  dirty: boolean;
  loading: boolean;
  saved: boolean;
  createdAt: number;
  updatedAt: number;
}

type TableDataFilterOperator =
  | 'EQ'
  | 'NE'
  | 'GT'
  | 'GTE'
  | 'LT'
  | 'LTE'
  | 'LIKE'
  | 'IS_NULL'
  | 'IS_NOT_NULL';

interface TableDataFilterDraft {
  key: string;
  columnName: string;
  operator: TableDataFilterOperator;
  value: string;
}

interface TableDataSortDraft {
  key: string;
  columnName: string;
  direction: 'ASC' | 'DESC';
}

interface TableDataRowDraft {
  rowKey: string;
  values: Record<string, string | null>;
  originalValues: Record<string, string | null>;
  rowState: 'clean' | 'new' | 'updated';
}

interface TableDataDeleteDraft {
  rowKey: string;
  values: Record<string, string | null>;
}

interface TableDataWorkspaceTab {
  key: string;
  title: string;
  connectionId: number;
  databaseName: string;
  tableName: string;
  dbType: string;
  loading: boolean;
  submitting: boolean;
  editable: boolean;
  readOnlyReason: string;
  columns: Array<{
    columnName: string;
    columnType?: string;
    columnComment?: string;
    nullable?: boolean;
    primaryKey?: boolean;
  }>;
  primaryKeyColumns: string[];
  rows: TableDataRowDraft[];
  deletedRows: TableDataDeleteDraft[];
  selectedRowKey: string;
  editingCellKey: string;
  detailCollapsed: boolean;
  filterPanelVisible: boolean;
  filters: TableDataFilterDraft[];
  sorts: TableDataSortDraft[];
  pageNo: number;
  pageSize: number;
  hasNextPage: boolean;
  rowDataVersion: number;
  schemaVersion: number;
  displayRowsCacheVersion: number;
  displayRowsCache: Array<Record<string, string | null> & { __rowKey: string; __rowState: string }>;
  displayColumnsCacheVersion: number;
  displayColumnsCache: Array<{
    title: string;
    dataIndex: string;
    key: string;
    width: number;
    ellipsis: boolean;
    columnType?: string;
    columnComment?: string;
  }>;
  errorMessage: string;
  dirty: boolean;
  createdAt: number;
  updatedAt: number;
}

interface KnowledgeWorkspaceTab {
  key: string;
  node: 'example-sql' | 'terms';
  title: string;
  createdAt: number;
  updatedAt: number;
}

type ErLineType = 'POLYLINE' | 'STRAIGHT';

const browserTabKey = 'browser';

const uiThemeStorageKey = 'sqlcopilot.ui-theme.v1';

const {defaultAlgorithm, darkAlgorithm} = antdTheme;

const isMacOS = typeof navigator !== 'undefined' && /mac/i.test(navigator.platform);

const isWindows = typeof navigator !== 'undefined' && /win/i.test(navigator.platform);

const isLinux = typeof navigator !== 'undefined' && /linux/i.test(navigator.platform);

let vectorizeStatusPollTimer: number | null = null;

const vectorizeStatusPollIntervalMs = 30000;

const tableStatsMinRequestIntervalMs = 30000;

const tableStatsPollIntervalMs = 1500;

const connections = ref<ConnectionVO[]>([]);

const schemaOverview = ref<SchemaOverviewVO | null>(null);

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

const objectNameCache = ref<Record<string, string[]>>({});

const savedQueryCache = ref<Record<string, SavedQueryVO[]>>({});

const saveQueryModalOpen = ref(false);

const saveQuerySubmitting = ref(false);

const saveQueryTitle = ref('');

const tableStatsCache = ref<Record<string, Record<string, { rowEstimate: number; tableSizeBytes: number }>>>({});

const tableStatsLoadingState = ref<Record<string, boolean>>({});

const tableStatsLastRequestAt = ref<Record<string, number>>({});

const databaseListCache = ref<Record<number, string[]>>({});

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

const aiConfigModalOpen = ref(false);

const aiConfigActiveTab = ref<'model' | 'embedding'>('model');

const uiTheme = ref<UiTheme>('light');

const selectedAiModel = ref('');

const activeWorkbenchTab = ref(browserTabKey);

const queryTabs = ref<QueryWorkspaceTab[]>([]);

const erTabs = ref<ErWorkspaceTab[]>([]);

const tableEditorTabs = ref<TableEditorWorkspaceTab[]>([]);

const tableDataTabs = ref<TableDataWorkspaceTab[]>([]);

const knowledgeTabs = ref<KnowledgeWorkspaceTab[]>([]);

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

const contextMenu = reactive({
  visible: false,
  x: 0,
  y: 0,
  targetType: 'none' as 'none' | 'connection' | 'database' | 'object',
  connectionId: 0,
  databaseName: '',
  objectType: '' as '' | ObjectRow['objectType'],
  objectName: '',
});

const connectionForm = reactive<ConnectionCreateReq>(defaultConnectionForm());

const connectionPreviewDbOptions = ref<string[]>([]);

const connectionPreviewLoading = ref(false);

const connectionPreviewError = ref('');

const aiConfigForm = reactive<AiConfigSaveReq>(defaultAiConfigForm());

const ragConfigForm = reactive<RagConfigSaveReq>(defaultRagConfigForm());

const pickingRagModelDir = ref(false);

const pickingRagRerankModelDir = ref(false);

const workflow = reactive({
  connectionId: 0,
  prompt: '',
  sqlText: '',
});

const dbTypeOptions = [
  { label: 'MySQL', value: 'MYSQL' },
  { label: 'PostgreSQL', value: 'POSTGRESQL' },
  { label: 'SQLite', value: 'SQLITE' },
  { label: 'SQL Server', value: 'SQLSERVER' },
  { label: 'Oracle', value: 'ORACLE' },
];

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

const sqlKeywords = [
  'SELECT', 'FROM', 'WHERE', 'GROUP BY', 'ORDER BY', 'HAVING', 'LIMIT', 'OFFSET',
  'DISTINCT', 'AS', 'AND', 'OR', 'NOT', 'IN', 'EXISTS', 'BETWEEN', 'LIKE', 'IS NULL', 'IS NOT NULL',
  'INSERT INTO', 'VALUES', 'UPDATE', 'SET', 'DELETE', 'TRUNCATE', 'MERGE',
  'CREATE TABLE', 'ALTER TABLE', 'DROP TABLE', 'CREATE INDEX', 'DROP INDEX', 'CREATE VIEW', 'DROP VIEW',
  'JOIN', 'INNER JOIN', 'LEFT JOIN', 'RIGHT JOIN', 'FULL JOIN', 'ON', 'UNION', 'UNION ALL',
  'CASE', 'WHEN', 'THEN', 'ELSE', 'END',
  'COUNT', 'SUM', 'AVG', 'MIN', 'MAX',
  'WITH', 'CTE', 'DESC', 'ASC', 'TOP',
];

let sqlCompletionProviderDisposable: IDisposable | null = null;

let sqlEditorTypeDisposable: IDisposable | null = null;

let sqlEditorSelectionDisposable: IDisposable | null = null;

let sqlEditorScrollDisposable: IDisposable | null = null;

let sqlEditorMouseDownDisposable: IDisposable | null = null;

let sqlEditorMouseUpDisposable: IDisposable | null = null;

let sqlAutoSuggestTimer: number | null = null;

let activeSqlEditorInstance: MonacoApi.editor.IStandaloneCodeEditor | null = null;

const pendingTableNameLoads = new Map<string, Promise<string[]>>();

const sqlEditorContextResolverMap = new Map<string, () => SqlEditorContext | null>();

const tableStatsPollingTimers = new Map<string, number>();

const sessionTitleOverridesStorageKey = 'sqlcopilot.session-title-overrides.v1';

const sqlExecutionAbortControllerMap = new Map<string, AbortController>();

const sqlExecutionAbortReasonMap = new Map<string, RequestAbortReason>();

const aiRequestAbortControllerMap = new Map<string, AbortController>();

const aiRequestAbortReasonMap = new Map<string, RequestAbortReason>();

const selectedConnection = computed(() =>
  connections.value.find((item) => item.id === workflow.connectionId),
);

const activeQueryTab = computed(() =>
  queryTabs.value.find((item) => item.key === activeWorkbenchTab.value) ?? null,
);

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

const activeKnowledgeTab = computed(() =>
  knowledgeTabs.value.find((item) => item.key === activeWorkbenchTab.value) ?? null,
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
  };
});

const activeErForeignKeyRelations = computed(() =>
  activeErDisplayGraph.value?.foreignKeyRelations ?? [],
);

const activeErAiRelations = computed(() =>
  [...(activeErDisplayGraph.value?.aiRelations ?? [])]
    .sort((a, b) => normalizeErRelationConfidence(b.confidence) - normalizeErRelationConfidence(a.confidence)),
);

const canOpenHistory = computed(() => {
  return connections.value.length > 0;
});

const canOpenErSnapshot = computed(() => {
  return connections.value.length > 0;
});

const isDarkTheme = computed(() => uiTheme.value === 'dark');

const monacoTheme = computed(() => (isDarkTheme.value ? 'vs-dark' : 'vs'));

const antdThemeConfig = computed(() => ({
  algorithm: isDarkTheme.value ? darkAlgorithm : defaultAlgorithm,
  token: {
    colorPrimary: '#3b82f6',
    borderRadius: 10,
    wireframe: false,
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
  return !!(connectionId && databaseName);
});

const connectionSelectOptions = computed(() =>
  connections.value.map((item) => ({ label: `${item.name} (${item.env})`, value: item.id })),
);

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
  if (!isMultiDatabaseFormType.value) {
    return false;
  }
  if (!connectionForm.host?.trim() || !connectionForm.username?.trim()) {
    return false;
  }
  if (!connectionForm.port || connectionForm.port <= 0) {
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

const connectionTreeData = computed(() => {
  const keyword = connectionKeyword.value.trim().toLowerCase();
  const filtered = keyword
    ? connections.value.filter((item) => item.name.toLowerCase().includes(keyword))
    : connections.value;
  return filtered.map((conn) => buildConnectionNode(conn));
});

const objectRows = computed<ObjectRow[]>(() => {
  const databaseName = getActiveDatabaseName(workflow.connectionId);
  const dbVectorizeStatus = getDatabaseVectorizeStatus(workflow.connectionId, databaseName);
  const dbVectorizeRecord = getDatabaseVectorizeStatusRecord(workflow.connectionId, databaseName);
  const unsupportedObjectVectorizeMessage = 'Object type is not vectorized';

  if (currentObjectType.value === 'tables') {
    const statsByTable = tableStatsCache.value[tableCacheKey(workflow.connectionId, databaseName)] ?? {};
    return (schemaOverview.value?.tableSummaries ?? []).map((item) => ({
      objectName: item.tableName,
      objectType: 'tables',
      rowEstimate: statsByTable[item.tableName]?.rowEstimate ?? item.rowEstimate ?? 0,
      tableSize: formatSize(statsByTable[item.tableName]?.tableSizeBytes ?? item.tableSizeBytes ?? 0),
      description: item.tableComment ?? '',
      vectorizeStatus: dbVectorizeStatus,
      vectorizeMessage: dbVectorizeRecord?.message,
      vectorizeUpdatedAt: dbVectorizeRecord?.updatedAt,
    }));
  }

  if (currentObjectType.value === 'views') {
    const names = objectNameCache.value[objectCacheKey(workflow.connectionId, databaseName, currentObjectType.value)] ?? [];
    return names.map((name) => ({
      objectName: name,
      objectType: 'views',
      rowEstimate: 0,
      tableSize: '-',
      description: objectTypeLabel(currentObjectType.value),
      vectorizeStatus: dbVectorizeStatus,
      vectorizeMessage: dbVectorizeRecord?.message,
      vectorizeUpdatedAt: dbVectorizeRecord?.updatedAt,
    }));
  }

  if (currentObjectType.value === 'queries') {
    return savedQueriesByDatabase(workflow.connectionId, databaseName).map((item) => ({
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

  const names = objectNameCache.value[objectCacheKey(workflow.connectionId, databaseName, currentObjectType.value)] ?? [];
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

const selectedObjectRecord = computed(() =>
  objectRows.value.find((item) => item.objectName === selectedObjectName.value) ?? null,
);

const selectedTreeDetail = computed(() => {
  const key = selectedTreeKeys.value[0];
  if (!key) {
    return null;
  }
  const keyValue = String(key);
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

const selectedTreeDatabaseStatusLabel = computed(() => {
  const detail = selectedTreeDetail.value;
  if (!detail || (detail.kind !== 'database' && detail.kind !== 'category')) {
    return '-';
  }
  return databaseStatusLabel(getDatabaseVectorizeStatus(detail.connectionId, detail.databaseName));
});

const selectedTreeDatabaseTableCount = computed(() => {
  const detail = selectedTreeDetail.value;
  if (!detail || (detail.kind !== 'database' && detail.kind !== 'category')) {
    return '-';
  }
  if (schemaOverview.value && schemaOverview.value.databaseName === detail.databaseName) {
    return `${schemaOverview.value.tableCount ?? 0}`;
  }
  const tableNames = tableNameCache.value[tableCacheKey(detail.connectionId, detail.databaseName)] ?? [];
  return `${tableNames.length}`;
});

const selectedTreeDatabaseColumnCount = computed(() => {
  const detail = selectedTreeDetail.value;
  if (!detail || (detail.kind !== 'database' && detail.kind !== 'category')) {
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

const tableEditorSqlHighlighted = computed(() => {
  const sql = activeTableEditorTab.value?.previewSql || '-- 在右侧预览区展示结构变更 SQL';
  return highlightSqlForDisplay(sql);
});

const filteredObjectRows = computed(() => {
  const keyword = tableKeyword.value.trim().toLowerCase();
  if (!keyword) {
    return objectRows.value;
  }
  return objectRows.value.filter((item) => item.objectName.toLowerCase().includes(keyword));
});

const objectColumns = computed(() => {
  if (currentObjectType.value === 'tables') {
    return [
      { title: '对象', dataIndex: 'objectName', key: 'objectName', width: 250, ellipsis: true },
      { title: '行数', dataIndex: 'rowEstimate', key: 'rowEstimate', width: 120 },
      { title: '数据大小', dataIndex: 'tableSize', key: 'tableSize', width: 120 },
      { title: '向量状态', dataIndex: 'vectorizeStatus', key: 'vectorizeStatus', width: 150 },
      { title: '说明', dataIndex: 'description', key: 'description', width: 300, ellipsis: true },
    ];
  }
  if (currentObjectType.value === 'queries') {
    return [
      { title: '名称', dataIndex: 'objectName', key: 'objectName', width: 260, ellipsis: true },
      { title: '最近更新', dataIndex: 'tableSize', key: 'tableSize', width: 160 },
      { title: 'SQL 摘要', dataIndex: 'description', key: 'description', width: 420, ellipsis: true },
    ];
  }
  return [
    { title: '对象', dataIndex: 'objectName', key: 'objectName', width: 320, ellipsis: true },
    { title: '说明', dataIndex: 'description', key: 'description', width: 180, ellipsis: true },
    { title: '向量状态', dataIndex: 'vectorizeStatus', key: 'vectorizeStatus', width: 150 },
  ];
});

const tableScrollY = computed(() => Math.max(260, viewportHeight.value - 240));

const queryResultScrollY = computed(() => Math.max(180, viewportHeight.value - 560));

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
  if (activeWorkbenchTab.value === browserTabKey || activeKnowledgeTab.value) {
    return {
      gridTemplateColumns: `${leftPaneWidth.value}px 4px minmax(460px, 1fr) 4px ${browserRightPaneWidth.value}px`,
    };
  }
  if (activeErTab.value) {
    return {
      gridTemplateColumns: `${leftPaneWidth.value}px 4px minmax(560px, 1fr) 4px ${erRightPaneWidth.value}px`,
    };
  }
  return {
    gridTemplateColumns: `${leftPaneWidth.value}px 4px minmax(520px, 1fr) 4px ${queryRightPaneWidth.value}px`,
  };
});

const activeResultRows = computed(() => {
  if (!activeQueryTab.value) {
    return [] as Array<Record<string, string | null> & { __rowKey: string }>;
  }
  const rows = activeQueryTab.value.executeResult?.rows ?? activeQueryTab.value.explainResult?.rows ?? [];
  return rows.map((row, index) => {
    const result: Record<string, string | null> & { __rowKey: string } = { __rowKey: `${index}` };
    row.cells.forEach((cell) => {
      result[cell.columnName] = cell.cellValue;
    });
    return result;
  });
});

const activeResultColumns = computed(() => {
  if (!activeQueryTab.value) {
    return [];
  }
  const rows = activeQueryTab.value.executeResult?.rows ?? activeQueryTab.value.explainResult?.rows ?? [];
  if (!rows.length) {
    return [];
  }
  return rows[0].cells.map((cell) => ({
    title: cell.columnName,
    dataIndex: cell.columnName,
    key: cell.columnName,
    width: 180,
    ellipsis: true,
  }));
});

const queryResultScrollX = computed(() => Math.max(activeResultColumns.value.length * 180, 960));

const chartTypeOptions = [
  { label: '折线图', value: 'LINE' as ChartType },
  { label: '柱状图', value: 'BAR' as ChartType },
  { label: '饼图', value: 'PIE' as ChartType },
  { label: '散点图', value: 'SCATTER' as ChartType },
  { label: '趋势图', value: 'TREND' as ChartType },
];

const chartSortDirectionOptions = [
  { label: '不排序', value: 'NONE' as SortDirection },
  { label: '升序', value: 'ASC' as SortDirection },
  { label: '降序', value: 'DESC' as SortDirection },
];

const erLayoutModeOptions = [
  { label: '网格布局', value: 'GRID' as ErLayoutMode },
  { label: '环形布局', value: 'CIRCLE' as ErLayoutMode },
  { label: '分层布局', value: 'HIERARCHICAL' as ErLayoutMode },
];

const erLineTypeOptions = [
  { label: '折线', value: 'POLYLINE' as ErLineType },
  { label: '直线', value: 'STRAIGHT' as ErLineType },
];

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

function emptyManualChartConfig(): ChartConfigVO {
  return {
    chartType: 'LINE',
    xField: '',
    yFields: [],
    categoryField: '',
    valueField: '',
    sortField: '',
    sortDirection: 'NONE',
    title: '',
    description: '',
  };
}

function cloneChartConfig(config: ChartConfigVO | null | undefined): ChartConfigVO {
  if (!config) {
    return emptyManualChartConfig();
  }
  return {
    chartType: (config.chartType || 'LINE') as ChartType,
    xField: config.xField || '',
    yFields: [...(config.yFields || [])],
    categoryField: config.categoryField || '',
    valueField: config.valueField || '',
    sortField: config.sortField || '',
    sortDirection: (config.sortDirection || 'NONE') as SortDirection,
    title: config.title || '',
    description: config.description || '',
  };
}

function isNumericField(rows: Array<Record<string, string | null>>, field: string) {
  const values = rows
    .map((row) => row[field])
    .filter((value): value is string => value != null && String(value).trim() !== '')
    .slice(0, 120);
  if (!values.length) {
    return false;
  }
  return values.every((value) => Number.isFinite(Number(value)));
}

function setupManualChartConfigByResult(tab: QueryWorkspaceTab) {
  const rows = (tab.executeResult?.rows ?? tab.explainResult?.rows ?? []);
  if (!rows.length) {
    tab.manualChartConfig = emptyManualChartConfig();
    return;
  }
  const fields = rows[0].cells.map((cell) => cell.columnName).filter((item) => !!item);
  if (!fields.length) {
    tab.manualChartConfig = emptyManualChartConfig();
    return;
  }
  const rowObjects = rows.map((row) => {
    const result: Record<string, string | null> = {};
    row.cells.forEach((cell) => {
      result[cell.columnName] = cell.cellValue;
    });
    return result;
  });
  const numericFields = fields.filter((field) => isNumericField(rowObjects, field));
  const fallbackY = numericFields[0] || fields[1] || fields[0];
  tab.manualChartConfig = {
    chartType: 'LINE',
    xField: fields[0],
    yFields: fallbackY ? [fallbackY] : [],
    categoryField: fields[0],
    valueField: numericFields[0] || '',
    sortField: '',
    sortDirection: 'NONE',
    title: '',
    description: '',
  };
}

function buildConnectionNode(conn: ConnectionVO) {
  if (requiresDatabaseLayer(conn)) {
    const databases = visibleDatabasesForConnection(conn);
    const activeDbName = getActiveDatabaseName(conn.id);
    const databaseNodes = (databases.length ? databases : ['未发现数据库']).map((databaseName) => ({
      key: buildDatabaseNodeKey(conn.id, databaseName),
      title: databaseName,
      nodeType: 'database',
      vectorizeStatus: getDatabaseVectorizeStatus(conn.id, databaseName),
      selectable: databaseName !== '未发现数据库',
      children: buildCategoryChildren(conn.id, databaseName),
    }));
    return {
      key: `conn-${conn.id}`,
      title: conn.name,
      nodeType: 'connection',
      env: conn.env,
      dbType: conn.dbType,
      children: databaseNodes,
    };
  }

  const configuredDbName = getActiveDatabaseName(conn.id);
  return {
    key: `conn-${conn.id}`,
    title: conn.name,
    nodeType: 'connection',
    env: conn.env,
    dbType: conn.dbType,
    children: buildCategoryChildren(conn.id, configuredDbName),
  };
}

function buildCategoryChildren(connectionId: number, databaseName: string) {
  const categoryNodes = [
    { suffix: 'tables', title: '表', nodeType: 'tables' },
    { suffix: 'views', title: '视图', nodeType: 'views' },
    { suffix: 'functions', title: '函数', nodeType: 'functions' },
    { suffix: 'queries', title: '查询', nodeType: 'queries' },
  ];
  return categoryNodes.map((category) => ({
    key: buildCategoryNodeKey(connectionId, databaseName, category.suffix),
    title: category.title,
    nodeType: category.nodeType,
    selectable: true,
    children: getCategoryChildren(connectionId, databaseName, category.suffix),
  }));
}

function getCategoryChildren(connectionId: number, databaseName: string, category: string) {
  if (category === 'queries') {
    return savedQueriesByDatabase(connectionId, databaseName).map((item) => ({
      key: buildObjectNodeKey(connectionId, databaseName, category, item.title),
      title: item.title,
      nodeType: category,
      objectType: category,
      objectName: item.title,
    }));
  }
  const names = category === 'tables'
    ? tableNameCache.value[tableCacheKey(connectionId, databaseName)] ?? []
    : objectNameCache.value[objectCacheKey(connectionId, databaseName, category)] ?? [];
  return names.map((name) => ({
    key: buildObjectNodeKey(connectionId, databaseName, category, name),
    title: name,
    nodeType: category,
    objectType: category,
    objectName: name,
  }));
}

function requiresDatabaseLayer(connection: ConnectionVO) {
  if (isMultiDatabaseType(connection.dbType)) {
    return true;
  }
  return !parseConfiguredDatabaseName(connection).trim();
}

function isMultiDatabaseType(dbType: string) {
  return dbType === 'MYSQL' || dbType === 'POSTGRESQL' || dbType === 'SQLSERVER';
}

function normalizeSelectedDatabases(values: string[] | undefined) {
  if (!values?.length) {
    return [];
  }
  const set = new Set<string>();
  values.forEach((item) => {
    const value = (item || '').trim();
    if (value) {
      set.add(value);
    }
  });
  return Array.from(set);
}

function visibleDatabasesForConnection(connection: ConnectionVO) {
  const allDatabases = databaseListCache.value[connection.id] ?? [];
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

function parseConfiguredDatabaseName(connection: ConnectionVO) {
  const direct = (connection.databaseName ?? '').trim();
  if (direct) {
    return sanitizeDatabaseName(direct);
  }
  const host = (connection.host ?? '').trim();
  if (!host) {
    return '';
  }
  const stripped = host.replace(/^jdbc:[^:]+:\/\//i, '').replace(/^[a-z]+:\/\//i, '');
  const atIndex = stripped.lastIndexOf('@');
  const hostPart = atIndex >= 0 ? stripped.substring(atIndex + 1) : stripped;
  const slashIndex = hostPart.indexOf('/');
  if (slashIndex >= 0 && slashIndex < hostPart.length - 1) {
    return sanitizeDatabaseName(hostPart.substring(slashIndex + 1));
  }
  return '';
}

function sanitizeDatabaseName(raw: string) {
  const queryIndex = raw.indexOf('?');
  const semicolonIndex = raw.indexOf(';');
  let value = raw;
  if (queryIndex >= 0) {
    value = value.substring(0, queryIndex);
  }
  if (semicolonIndex >= 0) {
    value = value.substring(0, semicolonIndex);
  }
  while (value.startsWith('/')) {
    value = value.substring(1);
  }
  return value.trim();
}

function tableCacheKey(connectionId: number, databaseName: string) {
  return `${connectionId}|${databaseName || '__default__'}`;
}

function objectCacheKey(connectionId: number, databaseName: string, objectType: string) {
  return `${connectionId}|${databaseName || '__default__'}|${objectType}`;
}

function vectorizeStatusCacheKey(connectionId: number, databaseName: string) {
  return `${connectionId}|${databaseName.trim().toLowerCase()}`;
}

function getDatabaseVectorizeStatus(connectionId: number, databaseName: string) {
  if (!databaseName || databaseName === '未发现数据库') {
    return 'NOT_VECTORIZED';
  }
  const key = vectorizeStatusCacheKey(connectionId, databaseName);
  return databaseVectorizeStatusMap.value[key]?.status || 'NOT_VECTORIZED';
}

function getDatabaseVectorizeStatusRecord(connectionId: number, databaseName: string) {
  if (!databaseName || databaseName === '未发现数据库') {
    return null;
  }
  const key = vectorizeStatusCacheKey(connectionId, databaseName);
  return databaseVectorizeStatusMap.value[key] ?? null;
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

function getActiveDatabaseName(connectionId: number) {
  const selected = (activeDatabaseMap.value[connectionId] ?? '').trim();
  const connection = connections.value.find((item) => item.id === connectionId);
  if (!connection) {
    return selected;
  }
  const visibleDatabases = visibleDatabasesForConnection(connection);
  if (selected && (!visibleDatabases.length || visibleDatabases.includes(selected))) {
    return selected;
  }
  if (selected && visibleDatabases.length && !visibleDatabases.includes(selected)) {
    return '';
  }
  const configured = parseConfiguredDatabaseName(connection);
  if (configured && (!visibleDatabases.length || visibleDatabases.includes(configured))) {
    return configured;
  }
  return '';
}

function openAiQueryTab(initialPrompt = '') {
  return createQueryTab({
    prompt: initialPrompt,
    sqlText: workflow.sqlText,
  });
}

function createQueryTab(options?: {
  title?: string;
  connectionId?: number;
  databaseName?: string;
  prompt?: string;
  sqlText?: string;
  detailOutputOverride?: boolean | null;
}) {
  ensureConnection();
  const connectionId = options?.connectionId ?? workflow.connectionId;
  const now = Date.now();
  const databaseName = options?.databaseName ?? getActiveDatabaseName(connectionId);
  const models = aiModelOptions.value.map((item) => String(item.value)).filter((item) => !!item);
  const tab: QueryWorkspaceTab = {
    key: `query-${now}-${Math.round(Math.random() * 1000)}`,
    title: options?.title || '新的查询',
    connectionId,
    databaseName,
    sessionId: `session-${now}`,
    prompt: options?.prompt ?? '',
    sqlText: options?.sqlText ?? workflow.sqlText,
    riskAckToken: '',
    riskInfo: null,
    executeResult: null,
    explainResult: null,
    selectedAiModel: models[0] ?? '',
    autoMode: true,
    autoExecute: false,
    aiGenerating: false,
    sqlExecuting: false,
    selectedSqlText: '',
    chatMessages: [],
    lastExecuteFailed: false,
    lastExecuteErrorMessage: '',
    lastFailedSqlText: '',
    resultViewMode: 'table',
    manualChartConfig: emptyManualChartConfig(),
    activeChartConfig: null,
    chartImageDataUrl: '',
    chartImageCacheKey: '',
    chartReadonly: false,
    createdAt: now,
    updatedAt: now,
    memoryEnabled: true,
    detailOutputOverride: options?.detailOutputOverride ?? null,
    lastTokenEstimate: 0,
  };
  if (options?.title?.trim()) {
    sessionTitleOverrides.value = {
      ...sessionTitleOverrides.value,
      [sessionTitleOverrideKey(tab)]: options.title.trim(),
    };
    persistSessionTitleOverrides();
  }
  applySessionTitle(tab);
  queryTabs.value = [...queryTabs.value, tab];
  activeWorkbenchTab.value = tab.key;
  void runSafely(async () => {
    await prepareConnectionTreeData(tab.connectionId);
    tab.databaseName = tab.databaseName || getActiveDatabaseName(tab.connectionId);
    await warmupTableSuggestions(tab);
  });
  return tab;
}

function savedQueryCacheKey(connectionId: number, databaseName?: string) {
  return `${connectionId}|${databaseName || ''}`;
}

function savedQueriesByDatabase(connectionId: number, databaseName?: string) {
  return savedQueryCache.value[savedQueryCacheKey(connectionId, databaseName)] ?? [];
}

async function loadSavedQueries(connectionId: number, databaseName: string) {
  const normalizedDatabaseName = databaseName || '';
  const list = await getApi<SavedQueryVO[]>(
    `/api/editor/saved-query/list?connectionId=${connectionId}&databaseName=${encodeURIComponent(normalizedDatabaseName)}`,
  );
  savedQueryCache.value = {
    ...savedQueryCache.value,
    [savedQueryCacheKey(connectionId, normalizedDatabaseName)]: list,
  };
  return list;
}

function openSaveQueryModal(tab: QueryWorkspaceTab) {
  if (!tab.sqlText.trim()) {
    message.warning('请先输入要保存的 SQL');
    return;
  }
  saveQueryTitle.value = tab.title.includes('新的查询') ? '' : tab.title;
  saveQueryModalOpen.value = true;
}

async function saveCurrentQuery(tab: QueryWorkspaceTab) {
  const title = saveQueryTitle.value.trim();
  if (!title) {
    message.warning('保存查询名称不能为空');
    return null;
  }
  const sqlText = tab.sqlText.trim();
  if (!sqlText) {
    message.warning('请先输入要保存的 SQL');
    return null;
  }
  saveQuerySubmitting.value = true;
  try {
    const payload: SavedQuerySaveReq = {
      connectionId: tab.connectionId,
      databaseName: tab.databaseName || '',
      title,
      sqlText,
    };
    const saved = await postApi<SavedQueryVO>('/api/editor/saved-query/save', payload);
    sessionTitleOverrides.value = {
      ...sessionTitleOverrides.value,
      [sessionTitleOverrideKey(tab)]: title,
    };
    persistSessionTitleOverrides();
    tab.title = title;
    saveQueryModalOpen.value = false;
    await loadSavedQueries(tab.connectionId, tab.databaseName || '');
    message.success('查询已保存');
    return saved;
  } finally {
    saveQuerySubmitting.value = false;
  }
}

async function openSavedQueryTab(savedQuery: SavedQueryVO) {
  const normalizedDatabaseName = savedQuery.databaseName || '';
  workflow.connectionId = savedQuery.connectionId;
  activeDatabaseMap.value = {
    ...activeDatabaseMap.value,
    [savedQuery.connectionId]: normalizedDatabaseName,
  };
  const tab = createQueryTab({
    title: savedQuery.title,
    connectionId: savedQuery.connectionId,
    databaseName: normalizedDatabaseName,
    prompt: '',
    sqlText: savedQuery.sqlText,
  });
  await runSafely(async () => {
    await prepareConnectionTreeData(savedQuery.connectionId);
    await warmupTableSuggestions(tab);
  });
  browserNavMode.value = 'connections';
  return tab;
}

async function openSavedQueryTabByTitle(connectionId: number, databaseName: string, title: string) {
  const queries = savedQueriesByDatabase(connectionId, databaseName).length
    ? savedQueriesByDatabase(connectionId, databaseName)
    : await loadSavedQueries(connectionId, databaseName);
  const target = queries.find((item) => item.title === title);
  if (!target) {
    message.warning('未找到对应的保存查询');
    return null;
  }
  return openSavedQueryTab(target);
}

function closeQueryTab(tabKey: string) {
  const index = queryTabs.value.findIndex((item) => item.key === tabKey);
  if (index < 0) {
    return;
  }
  const sqlController = sqlExecutionAbortControllerMap.get(tabKey);
  if (sqlController) {
    sqlExecutionAbortReasonMap.set(tabKey, 'manual');
    sqlController.abort();
  }
  const aiController = aiRequestAbortControllerMap.get(tabKey);
  if (aiController) {
    aiRequestAbortReasonMap.set(tabKey, 'manual');
    aiController.abort();
  }
  const tabs = [...queryTabs.value];
  tabs.splice(index, 1);
  queryTabs.value = tabs;
  if (activeWorkbenchTab.value === tabKey) {
    activeWorkbenchTab.value = tabs[index]?.key || tabs[index - 1]?.key || browserTabKey;
    ensureActiveWorkbenchTab();
  }
}

function touchErTab(tab: ErWorkspaceTab) {
  tab.updatedAt = Date.now();
}

function normalizeErRelationConfidence(value?: number) {
  const confidence = Number(value ?? 0);
  if (!Number.isFinite(confidence)) {
    return 0;
  }
  return Math.max(0, Math.min(1, confidence));
}

const tableEditorSaving = ref(false);

function hasWorkbenchTab(tabKey: string) {
  if (tabKey === browserTabKey) {
    return true;
  }
  return queryTabs.value.some((item) => item.key === tabKey)
    || erTabs.value.some((item) => item.key === tabKey)
    || knowledgeTabs.value.some((item) => item.key === tabKey)
    || tableEditorTabs.value.some((item) => item.key === tabKey)
    || tableDataTabs.value.some((item) => item.key === tabKey);
}

function ensureActiveWorkbenchTab() {
  if (hasWorkbenchTab(activeWorkbenchTab.value)) {
    return;
  }
  activeWorkbenchTab.value = queryTabs.value[0]?.key
    ?? erTabs.value[0]?.key
    ?? knowledgeTabs.value[0]?.key
    ?? tableEditorTabs.value[0]?.key
    ?? tableDataTabs.value[0]?.key
    ?? browserTabKey;
}

async function openErTableSelectModal(tab?: ErWorkspaceTab) {
  closeContextMenu();
  const connectionId = tab?.connectionId || workflow.connectionId;
  if (!connectionId) {
    message.error('请先选择连接');
    return;
  }
  const databaseName = (tab?.databaseName || getActiveDatabaseName(connectionId)).trim();
  if (!databaseName || databaseName === '未发现数据库') {
    message.error('请先选择当前数据库');
    return;
  }
  const erUnavailableReason = resolveErUnavailableReason(connectionId, databaseName);
  if (erUnavailableReason) {
    message.warning(erUnavailableReason);
    return;
  }
  const models = aiModelOptions.value.map((item) => String(item.value)).filter((item) => !!item);
  if (!models.length) {
    message.error('请先在 AI 配置中至少新增一个模型');
    return;
  }
  const fallbackModel = models.includes(selectedAiModel.value) ? selectedAiModel.value : models[0];
  const targetModel = tab && models.includes(tab.selectedAiModel) ? tab.selectedAiModel : fallbackModel;

  erSelectConnectionId.value = connectionId;
  erSelectDatabaseName.value = databaseName;
  erSelectTargetTabKey.value = tab?.key || '';
  erSelectTableKeyword.value = '';
  erSelectTableValues.value = tab ? [...tab.selectedTableNames] : [];
  erSelectModelName.value = targetModel;
  erTableSelectModalOpen.value = true;

  await runSafely(async () => {
    const tables = await ensureTableNamesLoaded(connectionId, databaseName);
    const normalized = Array.from(new Set((tables ?? []).map((item) => (item || '').trim()).filter((item) => !!item)));
    erSelectTableOptions.value = normalized.sort((a, b) => a.localeCompare(b));
  });
}

async function refreshErGraphForTab(tab: ErWorkspaceTab, includeAiInference?: boolean) {
  if (!tab.connectionId || !tab.databaseName || !tab.selectedTableNames.length) {
    return;
  }
  const erUnavailableReason = resolveErUnavailableReason(tab.connectionId, tab.databaseName);
  if (erUnavailableReason) {
    tab.errorMessage = erUnavailableReason;
    touchErTab(tab);
    message.warning(erUnavailableReason);
    return;
  }
  tab.loading = true;
  tab.errorMessage = '';
  touchErTab(tab);
  try {
    const payload: ErGraphReq = {
      connectionId: tab.connectionId,
      databaseName: tab.databaseName,
      tableNames: [...tab.selectedTableNames],
      modelName: tab.selectedAiModel || undefined,
      includeAiInference: includeAiInference == null ? tab.includeAiInference : includeAiInference,
      aiConfidenceThreshold: tab.aiConfidenceThreshold,
    };
    const graph = await postApi<ErGraphVO>('/api/schema/er/graph', payload);
    tab.graph = graph;
    tab.includeAiInference = payload.includeAiInference !== false;
    tab.errorMessage = '';
  } catch (error) {
    const msg = error instanceof Error ? error.message : String(error);
    tab.errorMessage = msg || '加载ER图失败';
    message.error(tab.errorMessage);
  } finally {
    tab.loading = false;
    touchErTab(tab);
  }
}

async function confirmErTableSelection() {
  if (!erSelectConnectionId.value || !erSelectDatabaseName.value) {
    message.error('缺少连接或数据库信息');
    return;
  }
  const erUnavailableReason = resolveErUnavailableReason(erSelectConnectionId.value, erSelectDatabaseName.value);
  if (erUnavailableReason) {
    message.warning(erUnavailableReason);
    return;
  }
  const selected = Array.from(new Set(erSelectTableValues.value.map((item) => (item || '').trim()).filter((item) => !!item)));
  if (!selected.length) {
    message.error('请至少选择一张表');
    return;
  }
  if (selected.length > 30) {
    message.error('最多选择 30 张表');
    return;
  }

  const models = aiModelOptions.value.map((item) => String(item.value)).filter((item) => !!item);
  if (!models.length) {
    message.error('请先在 AI 配置中至少新增一个模型');
    return;
  }
  const selectedModel = erSelectModelName.value.trim();
  if (!selectedModel) {
    message.error('请选择用于 ER 关系推断的模型');
    return;
  }
  if (!models.includes(selectedModel)) {
    message.error('所选模型已不可用，请重新选择');
    erSelectModelName.value = models[0] ?? '';
    return;
  }

  erTableSelectSubmitting.value = true;
  try {
    const targetTab = erSelectTargetTabKey.value
      ? erTabs.value.find((item) => item.key === erSelectTargetTabKey.value) ?? null
      : null;
    let tab: ErWorkspaceTab;
    if (!targetTab) {
      const now = Date.now();
      const createdTab: ErWorkspaceTab = {
        key: `er-${now}-${Math.round(Math.random() * 1000)}`,
        title: `ER · ${erSelectDatabaseName.value}`,
        snapshotId: undefined,
        connectionId: erSelectConnectionId.value,
        databaseName: erSelectDatabaseName.value,
        selectedTableNames: [...selected],
        selectedAiModel: selectedModel,
        layoutMode: 'GRID',
        lineType: 'POLYLINE',
        showCardComments: false,
        aiConfidenceThreshold: 0.6,
        includeAiInference: true,
        loading: false,
        graph: null,
        errorMessage: '',
        createdAt: now,
        updatedAt: now,
      };
      erTabs.value = [...erTabs.value, createdTab];
      // Ensure later async graph updates operate on the reactive tab instance.
      tab = erTabs.value.find((item) => item.key === createdTab.key) ?? createdTab;
    } else {
      tab = targetTab;
      tab.snapshotId = undefined;
      tab.connectionId = erSelectConnectionId.value;
      tab.databaseName = erSelectDatabaseName.value;
      tab.selectedTableNames = [...selected];
      tab.selectedAiModel = selectedModel;
      if (!tab.layoutMode) {
        tab.layoutMode = 'GRID';
      }
      if (tab.lineType !== 'STRAIGHT' && tab.lineType !== 'POLYLINE') {
        tab.lineType = 'POLYLINE';
      }
      if (tab.showCardComments == null) {
        tab.showCardComments = false;
      }
      tab.title = `ER · ${erSelectDatabaseName.value}`;
      touchErTab(tab);
    }
    activeWorkbenchTab.value = tab.key;
    erTableSelectModalOpen.value = false;
    await nextTick();
    void refreshErGraphForTab(tab, true);
  } finally {
    erTableSelectSubmitting.value = false;
  }
}

function sessionRefKey(connectionId: number, sessionId: string) {
  return `${connectionId}::${sessionId}`;
}

function sessionTitleOverrideKey(sessionRef: { connectionId: number; sessionId: string }) {
  return sessionRefKey(sessionRef.connectionId, sessionRef.sessionId);
}

function historyItemKey(item: QueryHistorySessionVO) {
  return sessionRefKey(item.connectionId, item.sessionId);
}

function findQueryTabBySession(connectionId: number, sessionId: string) {
  return queryTabs.value.find((tab) => tab.connectionId === connectionId && tab.sessionId === sessionId) ?? null;
}

function queryTabConnectionNameById(connectionId?: number) {
  if (!connectionId) {
    return '';
  }
  return connections.value.find((item) => item.id === connectionId)?.name ?? '';
}

function normalizeTitleSource(text: string) {
  return text.replace(/\s+/g, ' ').trim();
}

function buildSessionDefaultTitle(text: string) {
  const normalized = normalizeTitleSource(text);
  if (!normalized) {
    return '未命名会话';
  }
  const splitIndex = normalized.search(/[。！？\n]/);
  const firstSentence = (splitIndex >= 0 ? normalized.slice(0, splitIndex) : normalized).trim() || normalized;
  return firstSentence.length > 20 ? `${firstSentence.slice(0, 20)}...` : firstSentence;
}

function firstPromptForTitle(tab: QueryWorkspaceTab) {
  const firstUser = tab.chatMessages.find((item) => item.role === 'user' && item.content.trim());
  if (firstUser) {
    return firstUser.content;
  }
  return tab.prompt;
}

function buildNewQueryPlaceholderTitle(tab: QueryWorkspaceTab) {
  const connectionName = (queryTabConnectionNameById(tab.connectionId) || '').trim() || '未命名连接';
  const databaseName = (tab.databaseName || getActiveDatabaseName(tab.connectionId) || '').trim() || '未指定库';
  return `${connectionName} / ${databaseName} · 新的查询`;
}

function applySessionTitle(tab: QueryWorkspaceTab) {
  const custom = (sessionTitleOverrides.value[sessionTitleOverrideKey(tab)] ?? '').trim();
  if (custom) {
    tab.title = custom;
    return;
  }
  const firstPrompt = firstPromptForTitle(tab).trim();
  if (firstPrompt) {
    tab.title = buildSessionDefaultTitle(firstPrompt);
    return;
  }
  tab.title = buildNewQueryPlaceholderTitle(tab);
}

function loadSessionTitleOverrides() {
  if (typeof window === 'undefined') {
    return;
  }
  try {
    const raw = window.localStorage.getItem(sessionTitleOverridesStorageKey);
    if (!raw) {
      return;
    }
    const parsed = JSON.parse(raw) as Record<string, string>;
    if (!parsed || typeof parsed !== 'object') {
      return;
    }
    const next: Record<string, string> = {};
    Object.entries(parsed).forEach(([key, value]) => {
      if (typeof value !== 'string') {
        return;
      }
      const normalized = value.trim();
      if (normalized) {
        next[key] = normalized;
      }
    });
    sessionTitleOverrides.value = next;
  } catch {
    sessionTitleOverrides.value = {};
  }
}

function persistSessionTitleOverrides() {
  if (typeof window === 'undefined') {
    return;
  }
  try {
    window.localStorage.setItem(sessionTitleOverridesStorageKey, JSON.stringify(sessionTitleOverrides.value));
  } catch {
    // 忽略本地存储异常，避免阻塞主流程。
  }
}

function cancelHistoryTitleEdit() {
  editingHistoryTabKey.value = '';
  editingHistoryTitle.value = '';
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

function buildQueryContextUsage(tab: QueryWorkspaceTab | null | undefined): QueryContextUsage {
  const totalTokens = Math.min(32000, Math.max(512, Number(aiConfigForm.conversationMemoryWindowTokens || 6000)));
  const maxTurns = Math.min(50, Math.max(1, Number(aiConfigForm.conversationMemoryWindowSize || 12)));
  const enabled = tab?.memoryEnabled ?? (aiConfigForm.conversationMemoryEnabled !== false);
  if (!tab || !tab.chatMessages.length) {
    return {
      enabled,
      usedTokens: 0,
      totalTokens,
      ratio: 0,
      cappedRatio: 0,
      percent: 0,
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
    if (selected.length > 0 && (usedTokens + itemTokens > totalTokens || nextTurnCount > maxTurns)) {
      break;
    }
    selected.unshift(item);
    usedTokens += itemTokens;
    if (item.role === 'user') {
      turnCount = nextTurnCount;
    }
  }
  const ratio = totalTokens > 0 ? usedTokens / totalTokens : 0;
  const cappedRatio = Math.max(0, Math.min(ratio, 1));
  let tone: QueryContextUsage['tone'] = 'normal';
  if (ratio >= 1) {
    tone = 'danger';
  } else if (ratio >= 0.85) {
    tone = 'warning';
  } else if (ratio <= 0.05) {
    tone = 'idle';
  }
  return {
    enabled,
    usedTokens,
    totalTokens,
    ratio,
    cappedRatio,
    percent: Math.round(ratio * 100),
    tone,
  };
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

function toggleMessageTraceExpanded(tab: QueryWorkspaceTab, messageId: string) {
  const target = tab.chatMessages.find((item) => item.id === messageId);
  if (!target || !target.trace) {
    return;
  }
  target.traceExpanded = target.traceExpanded !== true;
  touchQueryTab(tab);
}

function lastPromptText(tab: QueryWorkspaceTab) {
  const latestPrompt = [...tab.chatMessages].reverse().find((item) => item.role === 'user');
  return latestPrompt?.content || '暂无自然语言对话';
}

function assistantActionLabel(actionType: QueryChatMessage['actionType']) {
  if (actionType === 'auto_generate') {
    return 'Auto · 生成 SQL';
  }
  if (actionType === 'auto_explain') {
    return 'Auto · 解释 SQL';
  }
  if (actionType === 'auto_analyze') {
    return 'Auto · 分析 SQL';
  }
  if (actionType === 'auto_chart_auto_plan') {
    return 'Auto · 图表方案';
  }
  if (actionType === 'explain') {
    return '解释 SQL';
  }
  if (actionType === 'analyze') {
    return '分析 SQL';
  }
  if (actionType === 'repair') {
    return '修复 SQL';
  }
  if (actionType === 'chart_auto_plan') {
    return '图表方案';
  }
  if (actionType === 'chart_manual_render') {
    return '手动制图';
  }
  if (actionType === 'chart_auto_render') {
    return '自动制图';
  }
  return '生成 SQL';
}

function normalizeHistoryActionType(actionType?: string): QueryActionType {
  const normalized = (actionType || '').trim().toLowerCase();
  if (normalized === 'auto_generate') {
    return 'auto_generate';
  }
  if (normalized === 'auto_explain') {
    return 'auto_explain';
  }
  if (normalized === 'auto_analyze') {
    return 'auto_analyze';
  }
  if (normalized === 'auto_chart_auto_plan') {
    return 'auto_chart_auto_plan';
  }
  if (normalized === 'explain') {
    return 'explain';
  }
  if (normalized === 'analyze') {
    return 'analyze';
  }
  if (normalized === 'repair') {
    return 'repair';
  }
  if (normalized === 'chart_auto_plan') {
    return 'chart_auto_plan';
  }
  if (normalized === 'chart_manual_render') {
    return 'chart_manual_render';
  }
  if (normalized === 'chart_auto_render') {
    return 'chart_auto_render';
  }
  return 'generate';
}

function userBubbleClass(actionType: QueryActionType) {
  if (actionType === 'auto_explain') {
    return 'is-explain';
  }
  if (actionType === 'auto_analyze') {
    return 'is-analyze';
  }
  if (actionType === 'explain') {
    return 'is-explain';
  }
  if (actionType === 'analyze') {
    return 'is-analyze';
  }
  if (actionType === 'repair') {
    return 'is-repair';
  }
  return 'is-generate';
}

function touchQueryTab(tab: QueryWorkspaceTab) {
  tab.updatedAt = Date.now();
}

function isQueryChatMessage(value: unknown): value is QueryChatMessage {
  if (!value || typeof value !== 'object') {
    return false;
  }
  const candidate = value as Partial<QueryChatMessage>;
  return (candidate.role === 'user' || candidate.role === 'assistant') && typeof candidate.id === 'string';
}

function bindQueryChatMessageRef(messageId: string, element: unknown) {
  if (element instanceof HTMLElement) {
    queryChatMessageElementMap.set(messageId, element);
    return;
  }
  queryChatMessageElementMap.delete(messageId);
}

function scrollToQueryChatMessage(tab: QueryWorkspaceTab, messageId: string) {
  const tabKey = tab.key;
  void nextTick().then(() => {
    if (!activeQueryTab.value || activeQueryTab.value.key !== tabKey) {
      return;
    }
    const container = queryChatScrollRef.value;
    if (!container) {
      return;
    }
    const target = queryChatMessageElementMap.get(messageId);
    if (!target) {
      container.scrollTop = container.scrollHeight;
      return;
    }
    target.scrollIntoView({ block: 'end' });
  });
}

function appendUserChatMessage(tab: QueryWorkspaceTab, promptText: string, actionType: QueryChatMessage['actionType']) {
  const now = Date.now();
  const messageItem: QueryChatMessage = {
    id: `chat-user-${now}-${Math.random().toString(16).slice(2, 8)}`,
    role: 'user',
    content: promptText,
    actionType,
    retryable: false,
    retryLoading: false,
    historySaved: false,
    createdAt: now,
  };
  tab.chatMessages.push(messageItem);
  applySessionTitle(tab);
  touchQueryTab(tab);
  scrollToQueryChatMessage(tab, messageItem.id);
  return messageItem;
}

function appendAssistantThinkingMessage(tab: QueryWorkspaceTab, actionType: QueryChatMessage['actionType']) {
  const now = Date.now();
  const messageItem: QueryChatMessage = {
    id: `chat-assistant-thinking-${now}-${Math.random().toString(16).slice(2, 8)}`,
    role: 'assistant',
    content: '思考中...',
    pending: true,
    streaming: true,
    finalized: false,
    thinkingContent: '',
    liveOutput: '',
    actionType,
    createdAt: now,
  };
  tab.chatMessages.push(messageItem);
  touchQueryTab(tab);
  scrollToQueryChatMessage(tab, messageItem.id);
  return messageItem;
}

function removeQueryChatMessage(tab: QueryWorkspaceTab, targetMessage: QueryChatMessage | undefined) {
  if (!targetMessage) {
    return;
  }
  const index = tab.chatMessages.findIndex((item) => item.id === targetMessage.id);
  if (index < 0) {
    return;
  }
  tab.chatMessages.splice(index, 1);
  touchQueryTab(tab);
}

function prepareAssistantMessage(
  messageItem: QueryChatMessage,
  actionType: QueryChatMessage['actionType'],
  createdAt: number,
) {
  messageItem.role = 'assistant';
  messageItem.actionType = actionType;
  messageItem.pending = false;
  messageItem.streaming = false;
  messageItem.finalized = true;
  messageItem.thinkingContent = extractThinkingContentFromTrace(messageItem.trace) || messageItem.thinkingContent || '';
  messageItem.liveOutput = '';
  messageItem.aborted = false;
  messageItem.content = '';
  messageItem.sqlText = undefined;
  messageItem.chartConfig = undefined;
  messageItem.chartConfigSummary = undefined;
  messageItem.chartImageCacheKey = undefined;
  messageItem.chartImageDataUrl = undefined;
  messageItem.executionPreview = undefined;
  messageItem.retryable = undefined;
  messageItem.retryLoading = undefined;
  messageItem.retryMeta = undefined;
  messageItem.historySaved = undefined;
  messageItem.trace = undefined;
  messageItem.traceExpanded = undefined;
  messageItem.createdAt = createdAt;
}

function extractThinkingContentFromTrace(trace?: AiTraceVO | null) {
  if (!trace?.stages?.length) {
    return '';
  }
  const values = trace.stages
    .map((stage) => stage.llmCall?.thinkingContent || '')
    .filter((item) => !!item.trim());
  return values.length ? values[values.length - 1] : '';
}

function ensureAssistantStreamingState(
  tab: QueryWorkspaceTab,
  messageItem: QueryChatMessage,
  actionType?: QueryChatMessage['actionType'],
) {
  messageItem.role = 'assistant';
  if (actionType) {
    messageItem.actionType = actionType;
  }
  messageItem.pending = false;
  messageItem.streaming = true;
  messageItem.finalized = false;
  if (typeof messageItem.thinkingContent !== 'string') {
    messageItem.thinkingContent = '';
  }
  if (typeof messageItem.liveOutput !== 'string') {
    messageItem.liveOutput = '';
  }
  touchQueryTab(tab);
  scrollToQueryChatMessage(tab, messageItem.id);
}

function upsertStreamingTraceStage(messageItem: QueryChatMessage, stage: AiTraceStageVO) {
  const trace = messageItem.trace || {stageCount: 0, totalDurationMs: 0, stages: []};
  const stages = [...(trace.stages || [])];
  const index = stages.findIndex((item) => item.stageCode === stage.stageCode);
  if (index >= 0) {
    stages[index] = stage;
  } else {
    stages.push(stage);
  }
  messageItem.trace = {
    ...trace,
    stages,
    stageCount: stages.length,
  };
  if (messageItem.traceExpanded == null) {
    messageItem.traceExpanded = false;
  }
}

function applyStreamTraceSnapshot(messageItem: QueryChatMessage, trace?: AiTraceVO) {
  if (!trace) {
    return;
  }
  messageItem.trace = trace;
  messageItem.traceExpanded = messageItem.traceExpanded === true;
  const thinkingContent = extractThinkingContentFromTrace(trace);
  if (thinkingContent) {
    messageItem.thinkingContent = thinkingContent;
  }
}

function finalizeStreamingMessage(messageItem: QueryChatMessage) {
  messageItem.pending = false;
  messageItem.streaming = false;
  messageItem.finalized = true;
  messageItem.liveOutput = '';
}

function appendAssistantSqlMessage(
  tab: QueryWorkspaceTab,
  sqlText: string,
  actionType: QueryChatMessage['actionType'],
  content = '',
  chartConfig?: ChartConfigVO | null,
  chartConfigSummary?: string,
  chartImageCacheKey?: string,
  traceOrTargetMessage?: AiTraceVO | QueryChatMessage,
  targetMessage?: QueryChatMessage,
) {
  const now = Date.now();
  const resolvedTrace = isQueryChatMessage(traceOrTargetMessage) ? undefined : traceOrTargetMessage;
  const resolvedTargetMessage = isQueryChatMessage(traceOrTargetMessage) ? traceOrTargetMessage : targetMessage;
  const messageItem: QueryChatMessage = resolvedTargetMessage ?? {
    id: `chat-assistant-${now}-${Math.random().toString(16).slice(2, 8)}`,
    role: 'assistant',
    content: '',
    actionType,
    createdAt: now,
  };
  prepareAssistantMessage(messageItem, actionType, now);
  messageItem.content = content.trim();
  messageItem.sqlText = sqlText;
  messageItem.chartConfig = chartConfig ? cloneChartConfig(chartConfig) : undefined;
  messageItem.chartConfigSummary = (chartConfigSummary || '').trim() || undefined;
  messageItem.chartImageCacheKey = (chartImageCacheKey || '').trim() || undefined;
  messageItem.trace = resolvedTrace;
  messageItem.traceExpanded = false;
  if (!resolvedTargetMessage) {
    tab.chatMessages.push(messageItem);
  }
  touchQueryTab(tab);
  scrollToQueryChatMessage(tab, messageItem.id);
  return messageItem;
}

function appendAssistantTextMessage(
  tab: QueryWorkspaceTab,
  content: string,
  actionType: QueryChatMessage['actionType'],
  traceOrTargetMessage?: AiTraceVO | QueryChatMessage,
  targetMessage?: QueryChatMessage,
) {
  const now = Date.now();
  const resolvedTrace = isQueryChatMessage(traceOrTargetMessage) ? undefined : traceOrTargetMessage;
  const resolvedTargetMessage = isQueryChatMessage(traceOrTargetMessage) ? traceOrTargetMessage : targetMessage;
  const messageItem: QueryChatMessage = resolvedTargetMessage ?? {
    id: `chat-assistant-${now}-${Math.random().toString(16).slice(2, 8)}`,
    role: 'assistant',
    content: '',
    actionType,
    createdAt: now,
  };
  prepareAssistantMessage(messageItem, actionType, now);
  messageItem.content = content.trim();
  messageItem.trace = resolvedTrace;
  messageItem.traceExpanded = false;
  if (!resolvedTargetMessage) {
    tab.chatMessages.push(messageItem);
  }
  touchQueryTab(tab);
  scrollToQueryChatMessage(tab, messageItem.id);
  return messageItem;
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

async function runAiTextActionWithSql(tab: QueryWorkspaceTab, actionType: 'explain' | 'analyze', sqlText: string) {
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
      memoryEnabled: tab.memoryEnabled,
      detailOutputEnabled: detailOutputEnabledForTab(tab),
    }, (event) => {
      if (event.eventType === 'stage.updated' && event.stage) {
        ensureAssistantStreamingState(tab, thinkingMessage, actionType);
        upsertStreamingTraceStage(thinkingMessage, event.stage);
        return;
      }
      if (event.eventType === 'llm.thinking.delta') {
        ensureAssistantStreamingState(tab, thinkingMessage, actionType);
        thinkingMessage.thinkingContent = event.delta?.accumulatedText || thinkingMessage.thinkingContent || '';
        touchQueryTab(tab);
        return;
      }
      if (event.eventType === 'llm.output.delta') {
        ensureAssistantStreamingState(tab, thinkingMessage, actionType);
        thinkingMessage.liveOutput = event.delta?.accumulatedText || thinkingMessage.liveOutput || '';
        thinkingMessage.content = thinkingMessage.liveOutput || thinkingMessage.content;
        touchQueryTab(tab);
        return;
      }
      if (event.eventType === 'trace.snapshot' && event.trace) {
        applyStreamTraceSnapshot(thinkingMessage, event.trace);
        touchQueryTab(tab);
        return;
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
    tab.lastTokenEstimate = Number(result.totalTokens || 0);
    const content = result.content || '未返回内容';
    appendAssistantTextMessage(tab, content, actionType, result.trace, thinkingMessage);
    await saveConversationHistoryOnce(tab, userMessage, `${promptText}\n\n${normalizedSqlText}`, normalizedSqlText, {
      actionType,
      assistantContent: content,
      databaseName: tab.databaseName,
      trace: result.trace,
      tokenEstimate: tab.lastTokenEstimate,
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
    removeQueryChatMessage(tab, thinkingMessage);
    message.error(msg);
  } finally {
    tab.aiGenerating = false;
    touchQueryTab(tab);
  }
}

async function prepareConnectionTreeData(connectionId: number) {
  const connection = connections.value.find((item) => item.id === connectionId);
  if (!connection) {
    return;
  }
  if (requiresDatabaseLayer(connection)) {
    await loadDatabaseListForConnection(connectionId);
  } else {
    const configuredDb = parseConfiguredDatabaseName(connection);
    if (configuredDb) {
      activeDatabaseMap.value = {
        ...activeDatabaseMap.value,
        [connectionId]: configuredDb,
      };
    }
  }
}

async function loadDatabaseListForConnection(connectionId: number) {
  if (databaseListCache.value[connectionId]?.length) {
    return;
  }
  const list = await getApi<SchemaDatabaseVO[]>(`/api/schema/databases?connectionId=${connectionId}`);
  const databaseNames = list.map((item) => item.databaseName).filter((item) => !!item);
  databaseListCache.value = {
    ...databaseListCache.value,
    [connectionId]: databaseNames,
  };
  const connection = connections.value.find((item) => item.id === connectionId);
  if (connection) {
    const visibleNames = visibleDatabasesForConnection(connection);
    const current = (activeDatabaseMap.value[connectionId] || '').trim();
    if (current && visibleNames.length && !visibleNames.includes(current)) {
      activeDatabaseMap.value = {
        ...activeDatabaseMap.value,
        [connectionId]: '',
      };
    }
  }
  const next = {...databaseVectorizeStatusMap.value};
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
  databaseVectorizeStatusMap.value = next;
}

async function refreshVectorizeStatusForConnection(connectionId: number) {
  const list = await getApi<RagDatabaseVectorizeStatusVO[]>(
    `/api/rag/vectorize/status/list?connectionId=${connectionId}`,
  );
  const next = {...databaseVectorizeStatusMap.value};
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
  databaseVectorizeStatusMap.value = next;
}

async function refreshAllVectorizeStatuses(targetConnectionIds?: number[]) {
  const ids = targetConnectionIds ?? connections.value.map((item) => item.id);
  if (!ids.length) {
    databaseVectorizeStatusMap.value = {};
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
  Object.entries(databaseVectorizeStatusMap.value).forEach(([key, value]) => {
    if (Array.from(valid).some((prefix) => key.startsWith(prefix))) {
      next[key] = value;
    }
  });
  databaseVectorizeStatusMap.value = next;
}

function startVectorizeStatusPolling() {
  stopVectorizeStatusPolling();
  vectorizeStatusPollTimer = window.setInterval(() => {
    const cachedConnectionIds = Object.keys(databaseListCache.value)
      .map((item) => Number(item))
      .filter((item) => Number.isFinite(item) && item > 0);
    const ids = cachedConnectionIds.length ? cachedConnectionIds : (workflow.connectionId ? [workflow.connectionId] : []);
    if (!ids.length) {
      return;
    }
    void refreshAllVectorizeStatuses(ids);
  }, vectorizeStatusPollIntervalMs);
}

function stopVectorizeStatusPolling() {
  if (vectorizeStatusPollTimer !== null) {
    window.clearInterval(vectorizeStatusPollTimer);
    vectorizeStatusPollTimer = null;
  }
}

function resetErSnapshotTitleEditState() {
  editingErSnapshotId.value = null;
  editingErSnapshotTitle.value = '';
}

async function loadConnections() {
  connectionRefreshing.value = true;
  try {
    const list = await getApi<ConnectionVO[]>('/api/connection/list');
    connections.value = list;
    queryTabs.value.forEach((tab) => {
      const connection = list.find((item) => item.id === tab.connectionId);
      if (!connection || !isMultiDatabaseType(connection.dbType)) {
        return;
      }
      const visibleNames = visibleDatabasesForConnection(connection);
      if (tab.databaseName && visibleNames.length && !visibleNames.includes(tab.databaseName)) {
        tab.databaseName = '';
      }
    });
    erTabs.value.forEach((tab) => {
      const connection = list.find((item) => item.id === tab.connectionId);
      if (!connection || !isMultiDatabaseType(connection.dbType)) {
        return;
      }
      const visibleNames = visibleDatabasesForConnection(connection);
      if (tab.databaseName && visibleNames.length && !visibleNames.includes(tab.databaseName)) {
        tab.databaseName = '';
      }
    });
    tableDataTabs.value.forEach((tab) => {
      const connection = list.find((item) => item.id === tab.connectionId);
      if (!connection || !isMultiDatabaseType(connection.dbType)) {
        return;
      }
      const visibleNames = visibleDatabasesForConnection(connection);
      if (tab.databaseName && visibleNames.length && !visibleNames.includes(tab.databaseName)) {
        tab.databaseName = '';
      }
    });
    pruneVectorizeStatusMap(list.map((item) => item.id));
    if (!list.length) {
      workflow.connectionId = 0;
      selectedTreeKeys.value = [];
      expandedTreeKeys.value = [];
      clearAllTableStatsPollingTimers();
      tableStatsCache.value = {};
      tableStatsLoadingState.value = {};
      tableStatsLastRequestAt.value = {};
      schemaOverview.value = null;
      queryTabs.value = [];
      erTabs.value = [];
      tableDataTabs.value = [];
      activeWorkbenchTab.value = browserTabKey;
      historySessionConnectionId.value = 0;
      historySessionItems.value = [];
      historySessionPageNo.value = 1;
      historySessionHasMore.value = true;
      historyKeywordInput.value = '';
      historyKeyword.value = '';
      erSnapshotConnectionId.value = 0;
      erSnapshotItems.value = [];
      erSnapshotPageNo.value = 1;
      erSnapshotHasMore.value = true;
      erSnapshotKeywordInput.value = '';
      erSnapshotKeyword.value = '';
      resetErSnapshotTitleEditState();
      erSnapshotLoadingId.value = null;
      erSnapshotActionLoadingId.value = null;
      return;
    }
    await refreshAllVectorizeStatuses(list.map((item) => item.id));
    if (!workflow.connectionId || !list.some((item) => item.id === workflow.connectionId)) {
      workflow.connectionId = list[0].id;
    }
    if (historySessionConnectionId.value && !list.some((item) => item.id === historySessionConnectionId.value)) {
      historySessionConnectionId.value = 0;
      historySessionItems.value = [];
      historySessionPageNo.value = 1;
      historySessionHasMore.value = true;
      historyKeywordInput.value = '';
      historyKeyword.value = '';
    }
    if (erSnapshotConnectionId.value && !list.some((item) => item.id === erSnapshotConnectionId.value)) {
      erSnapshotConnectionId.value = 0;
      erSnapshotItems.value = [];
      erSnapshotPageNo.value = 1;
      erSnapshotHasMore.value = true;
      erSnapshotKeywordInput.value = '';
      erSnapshotKeyword.value = '';
      resetErSnapshotTitleEditState();
      erSnapshotLoadingId.value = null;
      erSnapshotActionLoadingId.value = null;
    }
    currentObjectType.value = 'tables';
    selectedObjectName.value = '';
    clearObjectDetail();
    await prepareConnectionTreeData(workflow.connectionId);
    selectedTreeKeys.value = [`conn-${workflow.connectionId}`];
    expandConnectionNode(workflow.connectionId);
    const current = connections.value.find((item) => item.id === workflow.connectionId);
    if (current && (!requiresDatabaseLayer(current) || getActiveDatabaseName(workflow.connectionId))) {
      await loadOverview();
    } else {
      schemaOverview.value = null;
    }
    ensureActiveWorkbenchTab();
  } finally {
    connectionRefreshing.value = false;
  }
}

async function refreshConnections() {
  await runSafely(async () => {
    await loadConnections();
  });
}

async function saveConnection() {
  await runSafely(async () => {
    const editing = isEditMode.value;
    const normalizedSelectedDatabases = isMultiDatabaseType(connectionForm.dbType)
      ? normalizeSelectedDatabases(connectionForm.selectedDatabases)
      : [];
    const sshAuthType = connectionForm.sshEnabled ? (connectionForm.sshAuthType || 'SSH_PASSWORD') : undefined;
    const payload: ConnectionCreateReq & { id?: number } = {
      ...connectionForm,
      selectedDatabases: normalizedSelectedDatabases,
      sshAuthType,
      sshPassword: connectionForm.sshEnabled && sshAuthType === 'SSH_PASSWORD'
        ? (connectionForm.sshPassword || '').trim()
        : '',
      sshPrivateKeyPath: connectionForm.sshEnabled && sshAuthType === 'SSH_KEY_PATH'
        ? (connectionForm.sshPrivateKeyPath || '').trim()
        : '',
      sshPrivateKeyText: connectionForm.sshEnabled && sshAuthType === 'SSH_KEY_TEXT'
        ? (connectionForm.sshPrivateKeyText || '').trim()
        : '',
      sshPrivateKeyPassphrase: connectionForm.sshEnabled
        && (sshAuthType === 'SSH_KEY_PATH' || sshAuthType === 'SSH_KEY_TEXT')
        ? (connectionForm.sshPrivateKeyPassphrase || '').trim()
        : '',
    };
    if (!isMultiDatabaseType(payload.dbType)) {
      payload.selectedDatabases = [];
    }
    const endpoint = editing ? '/api/connection/update' : '/api/connection/create';
    if (editing) {
      payload.id = editingConnectionId.value ?? undefined;
      if (!payload.id) {
        throw new Error('缺少待编辑连接 ID');
      }
    }
    const saved = await postApi<ConnectionVO>(endpoint, payload);
    createModalOpen.value = false;
    resetConnectionModalState();
    workflow.connectionId = saved.id;
    selectedTreeKeys.value = [`conn-${saved.id}`];
    message.success(editing ? '连接已更新' : '连接已创建');
    await loadConnections();
  });
}

async function previewConnectionDatabases() {
  if (!canPreviewDatabases.value) {
    return;
  }
  connectionPreviewLoading.value = true;
  connectionPreviewError.value = '';
  try {
    const payload: ConnectionDatabasePreviewReq = {
      dbType: connectionForm.dbType,
      host: (connectionForm.host || '').trim(),
      port: connectionForm.port,
      databaseName: (connectionForm.databaseName || '').trim(),
      username: (connectionForm.username || '').trim(),
      password: (connectionForm.password || '').trim(),
      sshEnabled: connectionForm.sshEnabled,
      sshHost: (connectionForm.sshHost || '').trim(),
      sshPort: connectionForm.sshPort,
      sshUser: (connectionForm.sshUser || '').trim(),
      sshAuthType: connectionForm.sshEnabled ? (connectionForm.sshAuthType || 'SSH_PASSWORD') : undefined,
      sshPassword: connectionForm.sshEnabled && (connectionForm.sshAuthType || 'SSH_PASSWORD') === 'SSH_PASSWORD'
        ? (connectionForm.sshPassword || '').trim()
        : '',
      sshPrivateKeyPath: connectionForm.sshEnabled && connectionForm.sshAuthType === 'SSH_KEY_PATH'
        ? (connectionForm.sshPrivateKeyPath || '').trim()
        : '',
      sshPrivateKeyText: connectionForm.sshEnabled && connectionForm.sshAuthType === 'SSH_KEY_TEXT'
        ? (connectionForm.sshPrivateKeyText || '').trim()
        : '',
      sshPrivateKeyPassphrase: connectionForm.sshEnabled
        && (connectionForm.sshAuthType === 'SSH_KEY_PATH' || connectionForm.sshAuthType === 'SSH_KEY_TEXT')
        ? (connectionForm.sshPrivateKeyPassphrase || '').trim()
        : '',
    };
    const result = await postApi<ConnectionDatabasePreviewVO>('/api/connection/databases/preview', payload);
    connectionPreviewDbOptions.value = Array.from(
      new Set((result.databaseNames ?? []).map((item) => (item || '').trim()).filter((item) => !!item)),
    );
  } catch (error) {
    const msg = error instanceof Error ? error.message : String(error);
    connectionPreviewError.value = msg || '获取数据库失败';
  } finally {
    connectionPreviewLoading.value = false;
  }
}

async function testConnection(id: number) {
  await runSafely(async () => {
    const result = await postApi<{ success: boolean; message: string }>('/api/connection/test', { connectionId: id });
    message.info(result.message);
    await loadConnections();
  });
}

async function removeConnection(id: number) {
  await runSafely(async () => {
    await postApi<boolean>('/api/connection/remove', { id });
    message.success('连接已删除');
    if (workflow.connectionId === id) {
      workflow.connectionId = 0;
      schemaOverview.value = null;
      clearObjectDetail();
    }
    queryTabs.value = queryTabs.value.filter((item) => item.connectionId !== id);
    erTabs.value = erTabs.value.filter((item) => item.connectionId !== id);
    tableEditorTabs.value = tableEditorTabs.value.filter((item) => item.connectionId !== id);
    tableDataTabs.value = tableDataTabs.value.filter((item) => item.connectionId !== id);
    if (erSnapshotConnectionId.value === id) {
      erSnapshotConnectionId.value = 0;
      erSnapshotItems.value = [];
      erSnapshotPageNo.value = 1;
      erSnapshotHasMore.value = true;
      erSnapshotKeywordInput.value = '';
      erSnapshotKeyword.value = '';
      resetErSnapshotTitleEditState();
      erSnapshotLoadingId.value = null;
      erSnapshotActionLoadingId.value = null;
    }
    ensureActiveWorkbenchTab();
    await loadConnections();
  });
}

async function syncSchema(targetConnectionId?: number) {
  if (targetConnectionId) {
    workflow.connectionId = targetConnectionId;
    selectedTreeKeys.value = [`conn-${targetConnectionId}`];
  }
  ensureConnection();
  await runSafely(async () => {
    const databaseName = getActiveDatabaseName(workflow.connectionId);
    const result = await postApi<{ success: boolean; tableCount: number; columnCount: number; message: string }>(
      '/api/schema/sync',
      { connectionId: workflow.connectionId, databaseName },
    );
    message.success(`${result.message}，表 ${result.tableCount}，字段 ${result.columnCount}`);
    currentObjectType.value = 'tables';
    await loadOverview();
  });
}

async function loadOverview() {
  ensureConnection();
  await runSafely(async () => {
    const databaseName = getActiveDatabaseName(workflow.connectionId);
    const query = databaseName
      ? `/api/schema/overview?connectionId=${workflow.connectionId}&databaseName=${encodeURIComponent(databaseName)}`
      : `/api/schema/overview?connectionId=${workflow.connectionId}`;
    const overview = await getApi<SchemaOverviewVO>(query);
    schemaOverview.value = overview;
    const cacheKey = tableCacheKey(workflow.connectionId, databaseName);
    const tableNames = (overview.tableSummaries ?? []).map((item) => item.tableName);
    tableNameCache.value = {
      ...tableNameCache.value,
      [cacheKey]: tableNames,
    };
    tableNameLoadedCache.value = {
      ...tableNameLoadedCache.value,
      [cacheKey]: true,
    };
    objectNameCache.value = {
      ...objectNameCache.value,
      [objectCacheKey(workflow.connectionId, databaseName, 'tables')]: tableNames,
    };
    expandConnectionNode(workflow.connectionId);
    if (databaseName && databaseName !== '未发现数据库' && isDatabaseNodeExpanded(workflow.connectionId, databaseName)) {
      void fetchTableStatsForDatabase(workflow.connectionId, databaseName);
    }
  });
}

function clearTableStatsPollingTimer(cacheKey: string) {
  const timer = tableStatsPollingTimers.get(cacheKey);
  if (timer !== undefined) {
    window.clearTimeout(timer);
    tableStatsPollingTimers.delete(cacheKey);
  }
}

function clearAllTableStatsPollingTimers() {
  tableStatsPollingTimers.forEach((timer) => {
    window.clearTimeout(timer);
  });
  tableStatsPollingTimers.clear();
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
  tableStatsCache.value = {
    ...tableStatsCache.value,
    [cacheKey]: next,
  };
}

function isDatabaseNodeExpanded(connectionId: number, databaseName: string) {
  const nodeKey = buildDatabaseNodeKey(connectionId, databaseName);
  return expandedTreeKeys.value.some((item) => item === nodeKey || item.startsWith(`${nodeKey}-`));
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
    map.set(`${connectionId}|${databaseName}`, { connectionId, databaseName });
  });
  return Array.from(map.values());
}

async function fetchTableStatsForDatabase(
  connectionId: number,
  databaseName: string,
  options?: { force?: boolean; polling?: boolean },
) {
  if (!connectionId || !databaseName || databaseName === '未发现数据库') {
    return;
  }
  if (!isDatabaseNodeExpanded(connectionId, databaseName)) {
    clearTableStatsPollingTimer(tableCacheKey(connectionId, databaseName));
    return;
  }
  const cacheKey = tableCacheKey(connectionId, databaseName);
  if (tableStatsLoadingState.value[cacheKey]) {
    return;
  }
  const now = Date.now();
  const lastRequestAt = tableStatsLastRequestAt.value[cacheKey] ?? 0;
  if (!options?.force && !options?.polling && now - lastRequestAt < tableStatsMinRequestIntervalMs) {
    return;
  }

  tableStatsLastRequestAt.value = {
    ...tableStatsLastRequestAt.value,
    [cacheKey]: now,
  };
  tableStatsLoadingState.value = {
    ...tableStatsLoadingState.value,
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
        void fetchTableStatsForDatabase(connectionId, databaseName, { polling: true });
      }, tableStatsPollIntervalMs);
      tableStatsPollingTimers.set(cacheKey, timer);
    } else {
      clearTableStatsPollingTimer(cacheKey);
    }
  } catch {
    clearTableStatsPollingTimer(cacheKey);
  } finally {
    tableStatsLoadingState.value = {
      ...tableStatsLoadingState.value,
      [cacheKey]: false,
    };
  }
}

function scheduleTableStatsForExpandedDatabases(keys: string[]) {
  const targets = collectExpandedDatabaseTargets(keys);
  targets.forEach((item) => {
    void fetchTableStatsForDatabase(item.connectionId, item.databaseName);
  });
  Array.from(tableStatsPollingTimers.keys()).forEach((cacheKey) => {
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
  const query = `/api/schema/overview?connectionId=${connectionId}&databaseName=${encodeURIComponent(databaseName)}`;
  const overview = await getApi<SchemaOverviewVO>(query);
  const tableNames = (overview.tableSummaries ?? []).map((item) => item.tableName);
  const cacheKey = tableCacheKey(connectionId, databaseName);
  tableNameCache.value = {
    ...tableNameCache.value,
    [cacheKey]: tableNames,
  };
  tableNameLoadedCache.value = {
    ...tableNameLoadedCache.value,
    [cacheKey]: true,
  };
  objectNameCache.value = {
    ...objectNameCache.value,
    [objectCacheKey(connectionId, databaseName, 'tables')]: tableNames,
  };
  return tableNames;
}

function resolveQueryDatabaseName(tab: QueryWorkspaceTab | null) {
  if (!tab) {
    return '';
  }
  return (tab.databaseName || getActiveDatabaseName(tab.connectionId)).trim();
}

async function ensureTableNamesLoaded(connectionId: number, databaseName: string) {
  if (!connectionId || !databaseName || databaseName === '未发现数据库') {
    return [];
  }
  const cacheKey = tableCacheKey(connectionId, databaseName);
  const loaded = !!tableNameLoadedCache.value[cacheKey];
  if (loaded) {
    return tableNameCache.value[cacheKey] ?? [];
  }
  const pending = pendingTableNameLoads.get(cacheKey);
  if (pending) {
    return pending;
  }
  const task = loadTableNamesByConnection(connectionId, databaseName)
    .catch(() => {
      tableNameLoadedCache.value = {
        ...tableNameLoadedCache.value,
        [cacheKey]: true,
      };
      return [];
    })
    .finally(() => {
      pendingTableNameLoads.delete(cacheKey);
    });
  pendingTableNameLoads.set(cacheKey, task);
  return task;
}

function tableNameSuggestions(
  monaco: typeof MonacoApi,
  names: string[],
  range: MonacoApi.IRange,
  prefix: string,
  databaseName: string,
) {
  const keyword = prefix.trim().toLowerCase();
  if (!keyword) {
    return [];
  }
  const uniqueNames = Array.from(new Set(names.filter((item) => !!item)));
  const matched = keyword
    ? uniqueNames.filter((name) => name.toLowerCase().includes(keyword))
    : uniqueNames;
  return matched.slice(0, 300).map((name) => {
    const startsWithPrefix = keyword && name.toLowerCase().startsWith(keyword);
    return {
      label: name,
      kind: monaco.languages.CompletionItemKind.Struct,
      insertText: name,
      range,
      detail: `表 · ${databaseName}`,
      sortText: `${startsWithPrefix ? '0' : '1'}_${name}`,
    };
  });
}

function sqlKeywordSuggestions(
  monaco: typeof MonacoApi,
  range: MonacoApi.IRange,
  prefix: string,
) {
  const keyword = prefix.trim().toUpperCase();
  if (!keyword) {
    return [];
  }
  const matched = keyword
    ? sqlKeywords.filter((item) => item.includes(keyword))
    : sqlKeywords;
  return matched.map((item) => {
    const startsWithPrefix = keyword && item.startsWith(keyword);
    return {
      label: item,
      kind: monaco.languages.CompletionItemKind.Keyword,
      insertText: item,
      range,
      detail: 'SQL keyword',
      sortText: `${startsWithPrefix ? '0' : '1'}_keyword_${item}`,
    };
  });
}

function hasKeywordSuggestion(prefix: string) {
  const keyword = prefix.trim().toUpperCase();
  if (!keyword) {
    return false;
  }
  return sqlKeywords.some((item) => item.includes(keyword));
}

function hasTableSuggestion(names: string[], prefix: string) {
  const keyword = prefix.trim().toLowerCase();
  if (!keyword) {
    return false;
  }
  return names.some((name) => name.toLowerCase().includes(keyword));
}

function resolveSqlEditorContextForModel(model: MonacoApi.editor.ITextModel | null) {
  if (!model) {
    return null;
  }
  return sqlEditorContextResolverMap.get(model.uri.toString())?.() ?? null;
}

function registerSqlEditorContext(
  editor: MonacoApi.editor.IStandaloneCodeEditor,
  getContext: () => SqlEditorContext | null,
) {
  let currentModel: MonacoApi.editor.ITextModel | null = null;

  const syncModelContext = () => {
    if (currentModel) {
      sqlEditorContextResolverMap.delete(currentModel.uri.toString());
    }
    currentModel = editor.getModel();
    if (currentModel) {
      sqlEditorContextResolverMap.set(currentModel.uri.toString(), getContext);
    }
  };

  syncModelContext();
  const changeDisposable = editor.onDidChangeModel(() => {
    syncModelContext();
  });
  editor.onDidDispose(() => {
    changeDisposable.dispose();
    if (currentModel) {
      sqlEditorContextResolverMap.delete(currentModel.uri.toString());
      currentModel = null;
    }
  });
}

function shouldAutoTriggerSuggestByContext(context: SqlEditorContext | null, prefix: string) {
  if (hasKeywordSuggestion(prefix)) {
    return true;
  }
  if (!context?.connectionId) {
    return false;
  }
  const tab = context as QueryWorkspaceTab;
  const databaseName = resolveQueryDatabaseName(tab);
  if (!databaseName || databaseName === '未发现数据库') {
    return false;
  }
  const cacheKey = tableCacheKey(tab.connectionId, databaseName);
  const loaded = !!tableNameLoadedCache.value[cacheKey];
  if (!loaded) {
    void ensureTableNamesLoaded(tab.connectionId, databaseName);
    return false;
  }
  const tableNames = tableNameCache.value[cacheKey] ?? [];
  return hasTableSuggestion(tableNames, prefix);
}

function shouldAutoTriggerSuggest(tab: QueryWorkspaceTab, prefix: string) {
  return shouldAutoTriggerSuggestByContext({
    connectionId: tab.connectionId,
    databaseName: resolveQueryDatabaseName(tab),
  }, prefix);
}

function registerSqlCompletionProvider(monaco: typeof MonacoApi) {
  if (sqlCompletionProviderDisposable) {
    return;
  }
  sqlCompletionProviderDisposable = monaco.languages.registerCompletionItemProvider('sql', {
    triggerCharacters: ['.', '`'],
    provideCompletionItems: async (model, position) => {
      const word = model.getWordUntilPosition(position);
      const wordPrefix = (word.word || '').trim();
      if (!wordPrefix) {
        return undefined;
      }
      const context = resolveSqlEditorContextForModel(model)
        ?? (() => {
          const tab = activeQueryTab.value;
          if (!tab) {
            return null;
          }
          return {
            connectionId: tab.connectionId,
            databaseName: resolveQueryDatabaseName(tab),
          };
        })();
      const range = {
        startLineNumber: position.lineNumber,
        endLineNumber: position.lineNumber,
        startColumn: word.startColumn,
        endColumn: word.endColumn,
      };
      const keywordSuggestions = sqlKeywordSuggestions(monaco, range, wordPrefix);
      if (!context?.connectionId) {
        return keywordSuggestions.length ? { suggestions: keywordSuggestions } : undefined;
      }
      const tab = context as QueryWorkspaceTab;
      const databaseName = resolveQueryDatabaseName(tab);
      if (!databaseName || databaseName === '未发现数据库') {
        return keywordSuggestions.length ? { suggestions: keywordSuggestions } : undefined;
      }
      const tableNames = await ensureTableNamesLoaded(tab.connectionId, databaseName);
      const tableSuggestions = tableNameSuggestions(monaco, tableNames, range, wordPrefix, databaseName);
      const suggestions = [...tableSuggestions, ...keywordSuggestions];
      if (!suggestions.length) {
        return undefined;
      }
      return {
        suggestions,
      };
    },
  });
}

function registerSqlAutoSuggest(
  editor: MonacoApi.editor.IStandaloneCodeEditor,
  getContext: () => SqlEditorContext | null,
) {
  sqlEditorTypeDisposable?.dispose();
  sqlEditorTypeDisposable = editor.onDidChangeModelContent((event) => {
    if (event.isFlush || !event.changes.length) {
      return;
    }
    const latestChange = event.changes[event.changes.length - 1];
    const typedText = latestChange.text ?? '';
    if (!typedText || typedText.length > 2 || /\s/.test(typedText)) {
      return;
    }
    if (!/[\w.`]/.test(typedText)) {
      return;
    }
    const model = editor.getModel();
    const position = editor.getPosition();
    if (!model || !position) {
      return;
    }
    const currentWord = model.getWordUntilPosition(position).word.trim();
    if (!currentWord) {
      return;
    }
    if (!shouldAutoTriggerSuggestByContext(getContext(), currentWord)) {
      return;
    }
    if (sqlAutoSuggestTimer !== null) {
      window.clearTimeout(sqlAutoSuggestTimer);
    }
    sqlAutoSuggestTimer = window.setTimeout(() => {
      editor.trigger('sql-auto-suggest', 'editor.action.triggerSuggest', {});
    }, 60);
    syncSelectedSqlForActiveTab(false);
  });
}

function readSelectedSql(editor: MonacoApi.editor.IStandaloneCodeEditor) {
  const model = editor.getModel();
  const selection = editor.getSelection();
  if (!model || !selection || selection.isEmpty()) {
    return '';
  }
  return model.getValueInRange(selection).trim();
}

function hideSqlSelectionPopover() {
  sqlSelectionPopover.visible = false;
}

function updateSqlSelectionPopoverPosition(editor: MonacoApi.editor.IStandaloneCodeEditor) {
  const selection = editor.getSelection();
  const container = sqlEditorContainerRef.value;
  const editorNode = editor.getDomNode();
  if (!selection || selection.isEmpty() || !container || !editorNode) {
    hideSqlSelectionPopover();
    return;
  }
  const visiblePosition = editor.getScrolledVisiblePosition(selection.getEndPosition());
  if (!visiblePosition) {
    hideSqlSelectionPopover();
    return;
  }
  const containerRect = container.getBoundingClientRect();
  const editorRect = editorNode.getBoundingClientRect();
  const popoverWidth = 340;
  const estimatedHeight = 36;
  const baseLeft = editorRect.left - containerRect.left + visiblePosition.left;
  const baseTop = editorRect.top - containerRect.top + visiblePosition.top;
  const maxLeft = Math.max(8, container.clientWidth - popoverWidth - 8);
  const left = Math.min(Math.max(8, baseLeft), maxLeft);
  const top = Math.max(8, baseTop - estimatedHeight - 6);
  sqlSelectionPopover.left = left;
  sqlSelectionPopover.top = top;
  sqlSelectionPopover.visible = true;
}

function syncSelectedSqlForActiveTab(showPopover = true) {
  if (!activeSqlEditorInstance || !activeQueryTab.value) {
    hideSqlSelectionPopover();
    return;
  }
  activeQueryTab.value.selectedSqlText = readSelectedSql(activeSqlEditorInstance);
  if (!activeQueryTab.value.selectedSqlText) {
    hideSqlSelectionPopover();
    return;
  }
  if (!showPopover) {
    hideSqlSelectionPopover();
    return;
  }
  updateSqlSelectionPopoverPosition(activeSqlEditorInstance);
}

function registerSqlSelectionTracker(editor: MonacoApi.editor.IStandaloneCodeEditor) {
  sqlEditorSelectionDisposable?.dispose();
  sqlEditorSelectionDisposable = editor.onDidChangeCursorSelection(() => {
    syncSelectedSqlForActiveTab(false);
  });
}

function registerSqlSelectionPopoverTrigger(editor: MonacoApi.editor.IStandaloneCodeEditor) {
  sqlEditorMouseDownDisposable?.dispose();
  sqlEditorMouseDownDisposable = editor.onMouseDown(() => {
    hideSqlSelectionPopover();
  });
  sqlEditorMouseUpDisposable?.dispose();
  sqlEditorMouseUpDisposable = editor.onMouseUp(() => {
    syncSelectedSqlForActiveTab(true);
  });
}

function registerSqlScrollTracker(editor: MonacoApi.editor.IStandaloneCodeEditor) {
  sqlEditorScrollDisposable?.dispose();
  sqlEditorScrollDisposable = editor.onDidScrollChange(() => {
    if (!activeQueryTab.value?.selectedSqlText) {
      hideSqlSelectionPopover();
      return;
    }
    updateSqlSelectionPopoverPosition(editor);
  });
}

async function warmupTableSuggestions(tab: QueryWorkspaceTab | null) {
  if (!tab) {
    return;
  }
  const databaseName = resolveQueryDatabaseName(tab);
  if (!databaseName || databaseName === '未发现数据库') {
    return;
  }
  await ensureTableNamesLoaded(tab.connectionId, databaseName);
}

async function warmupTableSuggestionsForContext(context: SqlEditorContext | null) {
  if (!context?.connectionId) {
    return;
  }
  const databaseName = (context.databaseName || '').trim();
  if (!databaseName || databaseName === '鏈彂鐜版暟鎹簱') {
    return;
  }
  await ensureTableNamesLoaded(context.connectionId, databaseName);
}

function handleSqlEditorMount(
  editor: MonacoApi.editor.IStandaloneCodeEditor,
  monaco: typeof MonacoApi,
  options?: SqlEditorMountOptions,
) {
  const getContext = options?.getContext ?? (() => {
    const tab = activeQueryTab.value;
    if (!tab) {
      return null;
    }
    return {
      connectionId: tab.connectionId,
      databaseName: resolveQueryDatabaseName(tab),
    };
  });
  const enableSelectionActions = options?.enableSelectionActions !== false;

  activeSqlEditorInstance = editor;
  monaco.editor.remeasureFonts();
  editor.layout();
  window.requestAnimationFrame(() => {
    monaco.editor.remeasureFonts();
    editor.layout();
  });
  registerSqlCompletionProvider(monaco);
  registerSqlEditorContext(editor, getContext);
  registerSqlAutoSuggest(editor, getContext);
  if (enableSelectionActions) {
    registerSqlSelectionTracker(editor);
    registerSqlSelectionPopoverTrigger(editor);
    registerSqlScrollTracker(editor);
    syncSelectedSqlForActiveTab(false);
  } else {
    sqlEditorSelectionDisposable?.dispose();
    sqlEditorSelectionDisposable = null;
    sqlEditorScrollDisposable?.dispose();
    sqlEditorScrollDisposable = null;
    sqlEditorMouseDownDisposable?.dispose();
    sqlEditorMouseDownDisposable = null;
    sqlEditorMouseUpDisposable?.dispose();
    sqlEditorMouseUpDisposable = null;
    hideSqlSelectionPopover();
  }
  void warmupTableSuggestionsForContext(getContext());
}

async function loadObjectNames(connectionId: number, databaseName: string, objectType: string) {
  const query = `/api/schema/objectNames?connectionId=${connectionId}&databaseName=${encodeURIComponent(databaseName)}&objectType=${encodeURIComponent(objectType)}`;
  const names = await getApi<string[]>(query);
  objectNameCache.value = {
    ...objectNameCache.value,
    [objectCacheKey(connectionId, databaseName, objectType)]: names,
  };
}

async function refreshCurrentObjects() {
  ensureConnection();
  await runSafely(async () => {
    const databaseName = getActiveDatabaseName(workflow.connectionId);
    if (currentObjectType.value === 'tables') {
      await loadOverview();
      return;
    }
    if (currentObjectType.value === 'queries') {
      await loadSavedQueries(workflow.connectionId, databaseName);
      return;
    }
    await loadObjectNames(workflow.connectionId, databaseName, currentObjectType.value);
  });
}

async function loadCategoryObjects(connectionId: number, databaseName: string, category: string) {
  currentObjectType.value = toObjectType(category);
  browserNavMode.value = 'connections';
  if (currentObjectType.value === 'tables') {
    await loadOverview();
    return;
  }
  if (currentObjectType.value === 'queries') {
    await loadSavedQueries(connectionId, databaseName);
    expandCategoryNode(connectionId, databaseName, currentObjectType.value);
    return;
  }
  await runSafely(async () => {
    await loadObjectNames(connectionId, databaseName, currentObjectType.value);
    expandCategoryNode(connectionId, databaseName, currentObjectType.value);
  });
}

async function loadTreeChildrenByKey(nodeKey: string) {
  const connectionMatch = nodeKey.match(/^conn-(\d+)$/);
  if (connectionMatch) {
    await prepareConnectionTreeData(Number(connectionMatch[1]));
    return;
  }

  const databaseMatch = nodeKey.match(/^conn-(\d+)-db-(.+)$/);
  if (databaseMatch && !nodeKey.includes('-category-') && !nodeKey.includes('-obj-')) {
    const connectionId = Number(databaseMatch[1]);
    const databaseName = decodeURIComponent(databaseMatch[2] || '').trim();
    if (!connectionId || !databaseName || databaseName === '未发现数据库') {
      return;
    }
    await ensureTableNamesLoaded(connectionId, databaseName);
    return;
  }

  const categoryMatch = nodeKey.match(/^conn-(\d+)-db-(.+?)-category-([a-z]+)$/);
  if (!categoryMatch) {
    return;
  }
  const connectionId = Number(categoryMatch[1]);
  const databaseName = decodeURIComponent(categoryMatch[2] || '').trim();
  const category = toObjectType(categoryMatch[3] || '');
  if (!connectionId || !databaseName || databaseName === '未发现数据库') {
    return;
  }
  if (category === 'tables') {
    await ensureTableNamesLoaded(connectionId, databaseName);
    return;
  }
  if (category === 'queries') {
    await loadSavedQueries(connectionId, databaseName);
    return;
  }
  await loadObjectNames(connectionId, databaseName, category);
}

async function handleTreeSelect(keys: (string | number)[]) {
  if (!keys.length) {
    return;
  }
  closeContextMenu();
  browserNavMode.value = 'connections';
  const value = String(keys[0]);
  selectedTreeKeys.value = [value];
  activeWorkbenchTab.value = browserTabKey;
  try {
    await loadTreeChildrenByKey(value);
  } catch {
    // 点击节点时若预加载失败，继续走原有选择逻辑，由后续请求兜底。
  }

  const connectionMatch = value.match(/^conn-(\d+)$/);
  if (connectionMatch) {
    const connectionId = Number(connectionMatch[1]);
    workflow.connectionId = connectionId;
    currentObjectType.value = 'tables';
    selectedObjectName.value = '';
    clearObjectDetail();
    await prepareConnectionTreeData(connectionId);
    expandConnectionNode(connectionId);
    const current = connections.value.find((item) => item.id === connectionId);
    if (current && (!requiresDatabaseLayer(current) || getActiveDatabaseName(connectionId))) {
      await loadOverview();
    } else {
      schemaOverview.value = null;
    }
    return;
  }

  const objectMatch = value.match(/^conn-(\d+)-db-(.+?)-obj-([a-z]+)-(.+)$/);
  if (objectMatch) {
    const connectionId = Number(objectMatch[1]);
    const databaseName = decodeURIComponent(objectMatch[2]);
    const objectType = toObjectType(objectMatch[3]);
    const objectName = decodeURIComponent(objectMatch[4]);
    workflow.connectionId = connectionId;
    activeDatabaseMap.value = {
      ...activeDatabaseMap.value,
      [connectionId]: databaseName,
    };
    currentObjectType.value = objectType;
    expandConnectionNode(connectionId);
    await runSafely(async () => {
      await selectObject(connectionId, databaseName, objectType, objectName);
    });
    return;
  }

  const categoryMatch = value.match(/^conn-(\d+)-db-(.+?)-category-([a-z]+)$/);
  if (categoryMatch) {
    const connectionId = Number(categoryMatch[1]);
    const databaseName = decodeURIComponent(categoryMatch[2]);
    const category = categoryMatch[3];
    workflow.connectionId = connectionId;
    activeDatabaseMap.value = {
      ...activeDatabaseMap.value,
      [connectionId]: databaseName,
    };
    selectedObjectName.value = '';
    clearObjectDetail();
    await loadCategoryObjects(connectionId, databaseName, category);
    return;
  }

  const databaseMatch = value.match(/^conn-(\d+)-db-(.+)$/);
  if (databaseMatch) {
    const connectionId = Number(databaseMatch[1]);
    const databaseName = decodeURIComponent(databaseMatch[2]);
    workflow.connectionId = connectionId;
    activeDatabaseMap.value = {
      ...activeDatabaseMap.value,
      [connectionId]: databaseName,
    };
    currentObjectType.value = 'tables';
    selectedObjectName.value = '';
    clearObjectDetail();
    expandConnectionNode(connectionId);
    await loadOverview();
  }
}

async function handleTreeExpand(keys: (string | number)[]) {
  const previousExpanded = new Set(expandedTreeKeys.value);
  const normalizedKeys = keys.map((item) => String(item));
  expandedTreeKeys.value = normalizedKeys;
  const newExpandedKeys = normalizedKeys.filter((item) => !previousExpanded.has(item));
  await Promise.all(newExpandedKeys.map(async (nodeKey) => {
    try {
      await loadTreeChildrenByKey(nodeKey);
    } catch {
      // 展开时的懒加载失败不阻塞其余节点展开。
    }
  }));
  scheduleTableStatsForExpandedDatabases(expandedTreeKeys.value);
}

async function handleTreeRightClick(event: { event: MouseEvent; node: { key?: string | number } }) {
  event.event.preventDefault();
  event.event.stopPropagation();
  const keyValue = String(event.node?.key ?? '');
  const objectMatch = keyValue.match(/^conn-(\d+)-db-(.+?)-obj-([a-z]+)-(.+)$/);
  if (objectMatch) {
    const connectionId = Number(objectMatch[1]);
    const databaseName = decodeURIComponent(objectMatch[2]);
    const objectType = toObjectType(objectMatch[3]);
    const objectName = decodeURIComponent(objectMatch[4]);
    workflow.connectionId = connectionId;
    activeDatabaseMap.value = {
      ...activeDatabaseMap.value,
      [connectionId]: databaseName,
    };
    selectedTreeKeys.value = [keyValue];
    contextMenu.visible = true;
    contextMenu.x = Math.min(event.event.clientX, window.innerWidth - 220);
    contextMenu.y = Math.min(event.event.clientY, window.innerHeight - 180);
    contextMenu.targetType = 'object';
    contextMenu.connectionId = connectionId;
    contextMenu.databaseName = databaseName;
    contextMenu.objectType = objectType;
    contextMenu.objectName = objectName;
    void runSafely(async () => {
      await selectObject(connectionId, databaseName, objectType, objectName);
    });
    return;
  }
  const categoryMatch = keyValue.match(/^conn-(\d+)-db-(.+?)-category-([a-z]+)$/);
  if (categoryMatch) {
    return;
  }

  const connectionMatch = keyValue.match(/^conn-(\d+)$/);
  const databaseMatch = keyValue.match(/^conn-(\d+)-db-(.+)$/);
  if (!connectionMatch && !databaseMatch) {
    return;
  }

  if (connectionMatch) {
    const connectionId = Number(connectionMatch[1]);
    workflow.connectionId = connectionId;
    selectedTreeKeys.value = [keyValue];
    contextMenu.visible = true;
    contextMenu.x = Math.min(event.event.clientX, window.innerWidth - 220);
    contextMenu.y = Math.min(event.event.clientY, window.innerHeight - 180);
    contextMenu.targetType = 'connection';
    contextMenu.connectionId = connectionId;
    contextMenu.databaseName = '';
    contextMenu.objectType = '';
    contextMenu.objectName = '';
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
  workflow.connectionId = connectionId;
  activeDatabaseMap.value = {
    ...activeDatabaseMap.value,
    [connectionId]: databaseName,
  };
  try {
    await refreshVectorizeStatusForConnection(connectionId);
  } catch {
    // 关键操作：右键菜单唤起时尝试刷新状态，失败则沿用本地缓存状态。
  }
  selectedTreeKeys.value = [keyValue];
  contextMenu.visible = true;
  contextMenu.x = Math.min(event.event.clientX, window.innerWidth - 220);
  contextMenu.y = Math.min(event.event.clientY, window.innerHeight - 180);
  contextMenu.targetType = 'database';
  contextMenu.connectionId = connectionId;
  contextMenu.databaseName = databaseName;
  contextMenu.objectType = '';
  contextMenu.objectName = '';
}

function closeContextMenu() {
  contextMenu.visible = false;
  contextMenu.targetType = 'none';
  contextMenu.databaseName = '';
  contextMenu.objectType = '';
  contextMenu.objectName = '';
}

async function openVectorizeOverview(connectionId: number, databaseName: string) {
  vectorizeOverviewModalOpen.value = true;
  vectorizeOverviewLoading.value = true;
  vectorizeOverviewData.value = null;
  try {
    vectorizeOverviewData.value = await getApi<RagVectorizeOverviewVO>(
      `/api/rag/vectorize/overview?connectionId=${connectionId}&databaseName=${encodeURIComponent(databaseName)}`,
    );
    if ((vectorizeOverviewData.value.totalVectorCount ?? 0) <= 0) {
      message.info('该数据库暂无向量化数据');
      vectorizeOverviewModalOpen.value = false;
    }
  } catch (error) {
    const msg = error instanceof Error ? error.message : String(error);
    message.error(msg);
    vectorizeOverviewModalOpen.value = false;
  } finally {
    vectorizeOverviewLoading.value = false;
  }
}

async function enqueueDatabaseRevectorize(connectionId: number, databaseName: string) {
  await runSafely(async () => {
    const result = await postApi<RagVectorizeEnqueueVO>('/api/rag/vectorize/enqueue', {
      connectionId,
      databaseName,
    });
    if (result.enqueued) {
      const key = vectorizeStatusCacheKey(connectionId, databaseName);
      databaseVectorizeStatusMap.value = {
        ...databaseVectorizeStatusMap.value,
        [key]: {
          databaseName,
          status: 'PENDING',
          message: result.message,
          updatedAt: Date.now(),
        },
      };
    }
    await refreshVectorizeStatusForConnection(connectionId);
    if (result.enqueued) {
      message.success(`${result.message}（队列数: ${result.queueSize}）`);
      return;
    }
    message.info(`${result.message}（队列数: ${result.queueSize}）`);
  });
}

async function vectorizeSingleTable(connectionId: number, databaseName: string, tableName: string) {
  await runSafely(async () => {
    const result = await postApi<RagVectorizeTableVO>('/api/rag/vectorize/table/manual', {
      connectionId,
      databaseName,
      tableName,
    });
    const key = vectorizeStatusCacheKey(connectionId, databaseName);
    databaseVectorizeStatusMap.value = {
      ...databaseVectorizeStatusMap.value,
      [key]: {
        databaseName,
        status: 'SUCCESS',
        message: result.message || `表 ${tableName} 向量化完成`,
        updatedAt: result.updatedAt ?? Date.now(),
      },
    };
    await refreshVectorizeStatusForConnection(connectionId);
    message.success(result.message || `表 ${tableName} 向量化完成`);
  });
}

async function interruptDatabaseVectorize(connectionId: number, databaseName: string) {
  await runSafely(async () => {
    const result = await postApi<RagVectorizeInterruptVO>('/api/rag/vectorize/interrupt', {
      connectionId,
      databaseName,
    });
    const key = vectorizeStatusCacheKey(connectionId, databaseName);
    databaseVectorizeStatusMap.value = {
      ...databaseVectorizeStatusMap.value,
      [key]: {
        databaseName,
        status: result.status,
        message: result.message,
        updatedAt: result.updatedAt ?? Date.now(),
      },
    };
    await refreshVectorizeStatusForConnection(connectionId);
    if (result.interrupted) {
      message.success(result.message);
      return;
    }
    message.info(result.message);
  });
}

async function selectObject(connectionId: number, databaseName: string, objectType: ObjectRow['objectType'], objectName: string) {
  selectedObjectName.value = objectName;
  if (objectType === 'queries') {
    await openSavedQueryTabByTitle(connectionId, databaseName, objectName);
    return;
  }
  workflow.prompt = `查询 ${objectName} 最近数据`;
  if (objectType === 'tables' || objectType === 'views') {
    workflow.sqlText = `SELECT * FROM ${objectName} LIMIT 100`;
  }
  await loadObjectDetail(connectionId, databaseName, objectType, objectName);
}

async function loadObjectDetail(
  connectionId: number,
  databaseName: string,
  objectType: ObjectRow['objectType'],
  objectName: string,
) {
  tableDetail.value = null;

  if (objectType !== 'tables') {
    return;
  }

  tableDetailLoading.value = true;
  try {
    const query = databaseName
      ? `/api/schema/tableDetail?connectionId=${connectionId}&databaseName=${encodeURIComponent(databaseName)}&tableName=${encodeURIComponent(objectName)}`
      : `/api/schema/tableDetail?connectionId=${connectionId}&tableName=${encodeURIComponent(objectName)}`;
    tableDetail.value = await getApi<TableDetailVO>(query);
  } finally {
    tableDetailLoading.value = false;
  }
}

function clearObjectDetail() {
  tableDetail.value = null;
}

function openQueryTabByObject(record: ObjectRow, autoExecute = false) {
  if (record.objectType !== 'tables' && record.objectType !== 'views') {
    return;
  }
  const sql = `SELECT * FROM ${record.objectName} LIMIT 100`;
  const prompt = `查询 ${record.objectName} 最近数据`;
  workflow.sqlText = sql;
  workflow.prompt = prompt;
  const tab = openAiQueryTab(prompt);
  if (!tab) {
    return;
  }
  tab.sqlText = sql;
  tab.prompt = '';
  tab.databaseName = getActiveDatabaseName(tab.connectionId);
  touchQueryTab(tab);
  if (autoExecute) {
    void runSafely(async () => {
      await executeSqlForTab(tab, sql);
    });
  }
}

function getDesktopBridge(): DesktopBridge | null {
  if (typeof window === 'undefined') {
    return null;
  }
  const bridge = (window as Window & { sqlCopilotDesktop?: DesktopBridge }).sqlCopilotDesktop;
  if (!bridge || typeof bridge.pickFile !== 'function') {
    return null;
  }
  return bridge;
}

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
  if (pickingRagModelDir.value) {
    return;
  }
  pickingRagModelDir.value = true;
  try {
    const selectedPath = await bridge.pickDirectory({
      title: 'Select embedding model directory',
      defaultPath: ragConfigForm.ragEmbeddingModelDir || undefined,
    });
    if (!selectedPath) {
      return;
    }
    ragConfigForm.ragEmbeddingModelDir = selectedPath;
  } finally {
    pickingRagModelDir.value = false;
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
  if (pickingRagRerankModelDir.value) {
    return;
  }
  pickingRagRerankModelDir.value = true;
  try {
    const selectedPath = await bridge.pickDirectory({
      title: 'Select local rerank model directory',
      defaultPath: ragConfigForm.ragRerankModelDir || undefined,
    });
    if (!selectedPath) {
      return;
    }
    ragConfigForm.ragRerankModelDir = selectedPath;
  } finally {
    pickingRagRerankModelDir.value = false;
  }
}

async function openAiConfigModal() {
  aiConfigModalOpen.value = true;
  await runSafely(async () => {
    const aiConfig = await getApi<AiConfigVO>('/api/ai/config/get');
    const ragConfig = await getApi<RagConfigVO>('/api/rag/config/get');
    fillAiConfigForm(aiConfig);
    fillRagConfigForm(ragConfig);
  });
}

async function saveAiConfig() {
  await runSafely(async () => {
    const modelOptions = normalizeModelOptions(aiConfigForm.modelOptions);
    if (!modelOptions.length) {
      throw new Error('Please configure at least one model.');
    }
    aiConfigForm.modelOptions = modelOptions;
    aiConfigForm.providerType = modelOptions[0].providerType;
    aiConfigForm.openaiBaseUrl = modelOptions[0].openaiBaseUrl || '';
    aiConfigForm.openaiApiKey = modelOptions[0].openaiApiKey || '';
    aiConfigForm.openaiModel = modelOptions[0].openaiModel || '';
    aiConfigForm.cliCommand = modelOptions[0].cliCommand || '';
    aiConfigForm.cliWorkingDir = modelOptions[0].cliWorkingDir || '';
    aiConfigForm.conversationMemoryEnabled = aiConfigForm.conversationMemoryEnabled !== false;
    aiConfigForm.conversationMemoryWindowSize = Math.min(50, Math.max(4, Number(aiConfigForm.conversationMemoryWindowSize || 12)));
    aiConfigForm.conversationMemoryWindowTokens = Math.min(32000, Math.max(512, Number(aiConfigForm.conversationMemoryWindowTokens || 6000)));
    aiConfigForm.conversationAutoCompressRatio = Math.min(0.95, Math.max(0.3, Number(aiConfigForm.conversationAutoCompressRatio || 0.75)));
    aiConfigForm.detailOutputEnabled = aiConfigForm.detailOutputEnabled === true;
    ragConfigForm.ragEmbeddingProviderType = normalizeRagProviderType(ragConfigForm.ragEmbeddingProviderType);
    ragConfigForm.ragEmbeddingModelDir = (ragConfigForm.ragEmbeddingModelDir || '').trim();
    ragConfigForm.ragEmbeddingOnlineBaseUrl = (ragConfigForm.ragEmbeddingOnlineBaseUrl || '').trim();
    ragConfigForm.ragEmbeddingOnlineApiKey = (ragConfigForm.ragEmbeddingOnlineApiKey || '').trim();
    ragConfigForm.ragEmbeddingOnlineModel = (ragConfigForm.ragEmbeddingOnlineModel || '').trim();
    ragConfigForm.ragRerankEnabled = ragConfigForm.ragRerankEnabled === true;
    ragConfigForm.ragRerankProviderType = normalizeRagProviderType(ragConfigForm.ragRerankProviderType);
    ragConfigForm.ragRerankModelDir = (ragConfigForm.ragRerankModelDir || '').trim();
    ragConfigForm.ragRerankOnlineBaseUrl = (ragConfigForm.ragRerankOnlineBaseUrl || '').trim();
    ragConfigForm.ragRerankOnlineApiKey = (ragConfigForm.ragRerankOnlineApiKey || '').trim();
    ragConfigForm.ragRerankOnlineModel = (ragConfigForm.ragRerankOnlineModel || '').trim();
    if (!ragLocalOnnxEnabled) {
      ragConfigForm.ragEmbeddingProviderType = 'ONLINE_OPENAI_COMPAT';
      ragConfigForm.ragEmbeddingModelDir = '';
      ragConfigForm.ragRerankProviderType = 'ONLINE_OPENAI_COMPAT';
      ragConfigForm.ragRerankModelDir = '';
    }
    const savedAi = await postApi<AiConfigVO>('/api/ai/config/save', aiConfigForm);
    const savedRag = await postApi<RagConfigVO>('/api/rag/config/save', ragConfigForm);
    fillAiConfigForm(savedAi);
    fillRagConfigForm(savedRag);
    aiConfigModalOpen.value = false;
    message.success('AI and RAG configuration saved.');
  });
}

function databaseOptionsForTab(tab: QueryWorkspaceTab) {
  const connection = connections.value.find((item) => item.id === tab.connectionId);
  const cached = connection ? visibleDatabasesForConnection(connection) : [];
  if (cached.length) {
    return cached.map((item) => ({ label: item, value: item }));
  }
  const fallback = tab.databaseName || getActiveDatabaseName(tab.connectionId);
  if (!fallback) {
    return [];
  }
  return [{ label: fallback, value: fallback }];
}

function databaseOptionsForTableEditorTab(tab: TableEditorWorkspaceTab) {
  const connection = connections.value.find((item) => item.id === tab.connectionId);
  const cached = connection ? visibleDatabasesForConnection(connection) : [];
  if (cached.length) {
    return cached.map((item) => ({ label: item, value: item }));
  }
  const fallback = tab.databaseName || getActiveDatabaseName(tab.connectionId);
  if (!fallback) {
    return [];
  }
  return [{ label: fallback, value: fallback }];
}

function databaseOptionsForTableDataTab(tab: TableDataWorkspaceTab) {
  const connection = connections.value.find((item) => item.id === tab.connectionId);
  const cached = connection ? visibleDatabasesForConnection(connection) : [];
  if (cached.length) {
    return cached.map((item) => ({ label: item, value: item }));
  }
  const fallback = tab.databaseName || getActiveDatabaseName(tab.connectionId);
  if (!fallback) {
    return [];
  }
  return [{ label: fallback, value: fallback }];
}

function queryTabConnectionName(tab: QueryWorkspaceTab) {
  return connections.value.find((item) => item.id === tab.connectionId)?.name ?? '-';
}

async function handleQueryConnectionChange(tab: QueryWorkspaceTab) {
  await runSafely(async () => {
    await prepareConnectionTreeData(tab.connectionId);
    tab.databaseName = getActiveDatabaseName(tab.connectionId);
    tab.riskAckToken = '';
    tab.riskInfo = null;
    tab.executeResult = null;
    tab.explainResult = null;
    tab.lastExecuteFailed = false;
    tab.lastExecuteErrorMessage = '';
    tab.lastFailedSqlText = '';
    tab.selectedSqlText = '';
    tab.resultViewMode = 'table';
    tab.manualChartConfig = emptyManualChartConfig();
    tab.activeChartConfig = null;
    tab.chartImageDataUrl = '';
    tab.chartImageCacheKey = '';
    tab.chartReadonly = false;
    touchQueryTab(tab);
    await warmupTableSuggestions(tab);
  });
}

function handleQueryDatabaseChange(tab: QueryWorkspaceTab) {
  tab.riskAckToken = '';
  tab.lastExecuteFailed = false;
  tab.lastExecuteErrorMessage = '';
  tab.lastFailedSqlText = '';
  tab.resultViewMode = 'table';
  tab.manualChartConfig = emptyManualChartConfig();
  tab.activeChartConfig = null;
  tab.chartImageDataUrl = '';
  tab.chartImageCacheKey = '';
  tab.chartReadonly = false;
  touchQueryTab(tab);
  void warmupTableSuggestions(tab);
}

async function handleTableEditorConnectionChange(tab: TableEditorWorkspaceTab) {
  if (tab.mode === 'edit') {
    return;
  }
  await runSafely(async () => {
    await prepareConnectionTreeData(tab.connectionId);
    tab.databaseName = getActiveDatabaseName(tab.connectionId);
    tab.dbType = connections.value.find((item) => item.id === tab.connectionId)?.dbType ?? 'MYSQL';
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

function resolveSqlForAction(tab: QueryWorkspaceTab, sqlOverride?: string) {
  const override = (sqlOverride ?? '').trim();
  if (override) {
    return override;
  }
  const selected = tab.selectedSqlText.trim();
  if (selected) {
    return selected;
  }
  return tab.sqlText.trim();
}

function resolveSelectedSqlSnippet(tab: QueryWorkspaceTab, sqlOverride?: string) {
  const override = (sqlOverride ?? '').trim();
  if (override) {
    return override;
  }
  return tab.selectedSqlText.trim();
}

interface SaveConversationHistoryOptions {
  actionType?: QueryActionType;
  assistantContent?: string;
  databaseName?: string;
  chartConfig?: ChartConfigVO | null;
  chartImageCacheKey?: string;
  historyType?: 'CHAT' | 'EXECUTE';
  executionMs?: number;
  success?: boolean;
  structuredContextJson?: string;
  trace?: AiTraceVO;
  traceJson?: string;
  tokenEstimate?: number;
  memoryEnabled?: boolean;
}

async function saveConversationHistory(
  tab: QueryWorkspaceTab,
  promptText: string,
  sqlText: string,
  options?: SaveConversationHistoryOptions,
) {
  try {
    await postApi<boolean>('/api/editor/history/save', {
      connectionId: tab.connectionId,
      sessionId: tab.sessionId,
      promptText,
      sqlText,
      historyType: options?.historyType || 'CHAT',
      actionType: options?.actionType || 'generate',
      assistantContent: options?.assistantContent || '',
      databaseName: options?.databaseName || tab.databaseName || '',
      chartConfigJson: options?.chartConfig ? JSON.stringify(options.chartConfig) : '',
      chartImageCacheKey: options?.chartImageCacheKey || '',
      structuredContextJson: options?.structuredContextJson || '',
      traceJson: options?.traceJson || (options?.trace ? JSON.stringify(options.trace) : ''),
      trace: options?.trace,
      tokenEstimate: options?.tokenEstimate,
      memoryEnabled: options?.memoryEnabled ?? tab.memoryEnabled,
      executionMs: options?.executionMs,
      success: options?.success ?? true,
    });
  } catch {
    // 关键操作：会话历史持久化失败不阻塞主流程。
  }
}

function buildStructuredContextForTab(tab: QueryWorkspaceTab, windowSize = 12) {
  const start = Math.max(0, tab.chatMessages.length - windowSize);
  const rows = tab.chatMessages.slice(start).map((item) => ({
    role: item.role,
    actionType: item.actionType,
    content: (item.content || '').slice(0, 500),
    sqlText: (item.sqlText || '').slice(0, 500),
    createdAt: item.createdAt,
  }));
  return JSON.stringify(rows);
}

async function saveConversationHistoryOnce(
  tab: QueryWorkspaceTab,
  userMessage: QueryChatMessage,
  promptText: string,
  sqlText: string,
  options?: SaveConversationHistoryOptions,
) {
  if (userMessage.historySaved) {
    return;
  }
  const mergedOptions: SaveConversationHistoryOptions = {
    ...options,
    tokenEstimate: options?.tokenEstimate ?? (tab.lastTokenEstimate || Math.max(1, Math.ceil(((promptText || "").length + (sqlText || "").length) / 4))),
    memoryEnabled: options?.memoryEnabled ?? tab.memoryEnabled,
    structuredContextJson: options?.structuredContextJson ?? buildStructuredContextForTab(tab),
    traceJson: options?.traceJson ?? (options?.trace ? JSON.stringify(options.trace) : ''),
  };
  await saveConversationHistory(tab, promptText, sqlText, mergedOptions);
  userMessage.historySaved = true;
}

function timeoutRetryErrorMessage(rawMessage: string) {
  const normalized = rawMessage.trim();
  if (!normalized) {
    return '请求超时，请点击重试';
  }
  return normalized;
}

function isTimeoutErrorMessage(rawMessage: string) {
  const normalized = rawMessage.trim().toLowerCase();
  return normalized.includes('timeout')
    || normalized.includes('timed out')
    || normalized.includes('超时')
    || normalized.includes('http 504')
    || normalized.includes('http 408');
}

function isAbortError(error: unknown) {
  if (error instanceof DOMException && error.name === 'AbortError') {
    return true;
  }
  const normalized = getErrorMessage(error).trim().toLowerCase();
  return normalized.includes('abort');
}

function getErrorMessage(error: unknown) {
  return error instanceof Error ? error.message : String(error);
}

function clearUserRetryState(userMessage: QueryChatMessage) {
  userMessage.retryable = false;
  userMessage.retryLoading = false;
  userMessage.retryMeta = undefined;
}

function markUserMessageRetryable(
  tab: QueryWorkspaceTab,
  userMessage: QueryChatMessage,
  retryMeta: RetryRequestMeta,
) {
  userMessage.retryable = true;
  userMessage.retryLoading = false;
  userMessage.retryMeta = retryMeta;
  touchQueryTab(tab);
}

function mergePromptWithSqlSnippet(promptText: string, selectedSqlText?: string) {
  const basePrompt = promptText.trim();
  const snippet = (selectedSqlText ?? '').trim();
  if (!snippet) {
    return basePrompt;
  }
  if (!basePrompt) {
    return snippet;
  }
  return [
    basePrompt,
    '',
    snippet,
  ].join('\n');
}

const aiRequestTimeoutMs = 120000;

const AI_REQUEST_ABORTED = 'AI_REQUEST_ABORTED';

function isAiRequestAbortedMessage(rawMessage: string) {
  return rawMessage.trim() === AI_REQUEST_ABORTED;
}

async function postAiApiWithTimeout<T>(
  tab: QueryWorkspaceTab,
  path: string,
  payload: unknown,
  timeoutMs = aiRequestTimeoutMs,
) {
  const controller = new AbortController();
  aiRequestAbortControllerMap.set(tab.key, controller);
  aiRequestAbortReasonMap.delete(tab.key);
  const timeoutHandle = window.setTimeout(() => {
    aiRequestAbortReasonMap.set(tab.key, 'timeout');
    controller.abort();
  }, timeoutMs);
  try {
    return await postApi<T>(path, payload, {
      signal: controller.signal,
    });
  } catch (error) {
    if (isAbortError(error)) {
      const reason = aiRequestAbortReasonMap.get(tab.key);
      if (reason === 'timeout') {
        throw new Error(`请求超时（${Math.floor(timeoutMs / 1000)}s）`);
      }
      throw new Error(AI_REQUEST_ABORTED);
    }
    throw error;
  } finally {
    window.clearTimeout(timeoutHandle);
    if (aiRequestAbortControllerMap.get(tab.key) === controller) {
      aiRequestAbortControllerMap.delete(tab.key);
    }
    aiRequestAbortReasonMap.delete(tab.key);
  }
}

async function postAiStreamWithTimeout(
  tab: QueryWorkspaceTab,
  path: string,
  payload: unknown,
  onEvent: (event: AiStreamEventVO) => void,
  timeoutMs = aiRequestTimeoutMs,
) {
  const controller = new AbortController();
  aiRequestAbortControllerMap.set(tab.key, controller);
  aiRequestAbortReasonMap.delete(tab.key);
  const timeoutHandle = window.setTimeout(() => {
    aiRequestAbortReasonMap.set(tab.key, 'timeout');
    controller.abort();
  }, timeoutMs);
  try {
    await postSseApi<AiStreamEventVO>(path, payload, {
      signal: controller.signal,
      onEvent: ({data}) => onEvent(data),
    });
  } catch (error) {
    if (isAbortError(error)) {
      const reason = aiRequestAbortReasonMap.get(tab.key);
      if (reason === 'timeout') {
        throw new Error(`请求超时（${Math.floor(timeoutMs / 1000)}s）`);
      }
      throw new Error(AI_REQUEST_ABORTED);
    }
    throw error;
  } finally {
    window.clearTimeout(timeoutHandle);
    if (aiRequestAbortControllerMap.get(tab.key) === controller) {
      aiRequestAbortControllerMap.delete(tab.key);
    }
    aiRequestAbortReasonMap.delete(tab.key);
  }
}

function looksLikeSqlText(text: string) {
  const normalized = text.trim().toLowerCase();
  return /^(select|with|insert|update|delete|replace|create|alter|drop|truncate|merge|show|explain)\b/.test(normalized);
}

interface RetryInvokeOptions {
  userMessage: QueryChatMessage;
  promptText: string;
  finalPrompt: string;
  actionSqlSnippet?: string;
}

async function generateSqlForTab(
  tab: QueryWorkspaceTab,
  actionType: AiActionType = 'generate',
  retryOptions?: RetryInvokeOptions,
) {
  if (tab.aiGenerating) {
    return;
  }
  const rawPrompt = retryOptions?.promptText ?? tab.prompt.trim();
  const actionSqlSnippet = retryOptions?.actionSqlSnippet ?? (actionType === 'generate'
    ? ''
    : resolveSelectedSqlSnippet(tab));
  if (actionType === 'generate' && !rawPrompt.trim()) {
    message.info('Please enter a natural language request first.');
    return;
  }
  if (actionType !== 'generate' && !rawPrompt.trim() && !actionSqlSnippet) {
    message.info('请先输入说明，或在右侧编辑器中选择 SQL');
    return;
  }
  const promptText = rawPrompt.trim() || (actionType === 'explain'
    ? 'Please explain this SQL.'
    : 'Please analyze whether this SQL is reasonable.');
  const userMessage = retryOptions?.userMessage ?? appendUserChatMessage(tab, promptText, actionType);
  const thinkingMessage = appendAssistantThinkingMessage(tab, actionType);
  if (!retryOptions) {
    tab.prompt = '';
  }
  const finalPrompt = retryOptions?.finalPrompt ?? (actionType === 'generate'
    ? promptText
    : mergePromptWithSqlSnippet(promptText, actionSqlSnippet));
  const retryMeta: RetryRequestMeta = {
    kind: 'ai_action',
    actionType,
    promptText,
    finalPrompt,
    actionSqlSnippet,
  };
  tab.aiGenerating = true;
  try {
    if (actionType === 'generate') {
      const streamState = {generated: null as AiGenerateSqlVO | null};
      await postAiStreamWithTimeout(tab, '/api/ai/query/generate/stream', {
        connectionId: tab.connectionId,
        sessionId: tab.sessionId,
        prompt: finalPrompt,
        databaseName: tab.databaseName || undefined,
        modelId: tab.selectedAiModel || undefined,
        memoryEnabled: tab.memoryEnabled,
        detailOutputEnabled: detailOutputEnabledForTab(tab),
      }, (event) => {
        if (event.eventType === 'stage.updated' && event.stage) {
          ensureAssistantStreamingState(tab, thinkingMessage, actionType);
          upsertStreamingTraceStage(thinkingMessage, event.stage);
          return;
        }
        if (event.eventType === 'llm.thinking.delta') {
          ensureAssistantStreamingState(tab, thinkingMessage, actionType);
          thinkingMessage.thinkingContent = event.delta?.accumulatedText || thinkingMessage.thinkingContent || '';
          touchQueryTab(tab);
          return;
        }
        if (event.eventType === 'llm.output.delta') {
          ensureAssistantStreamingState(tab, thinkingMessage, actionType);
          thinkingMessage.liveOutput = event.delta?.accumulatedText || thinkingMessage.liveOutput || '';
          thinkingMessage.content = thinkingMessage.liveOutput || thinkingMessage.content;
          touchQueryTab(tab);
          return;
        }
        if (event.eventType === 'trace.snapshot' && event.trace) {
          applyStreamTraceSnapshot(thinkingMessage, event.trace);
          touchQueryTab(tab);
          return;
        }
        if (event.eventType === 'result.final') {
          streamState.generated = event.finalResult?.generateSql || null;
          return;
        }
        if (event.eventType === 'error') {
          throw new Error(event.error?.message || 'AI 流式请求失败');
        }
      });
      const generated = streamState.generated;
      if (!generated) {
        throw new Error('流式响应未返回最终结果');
      }
      tab.lastTokenEstimate = Number(generated.totalTokens || 0);
      const generatedText = (generated.sqlText || '').trim();
      if (looksLikeSqlText(generatedText)) {
        appendAssistantSqlMessage(tab, generatedText, actionType, '', undefined, undefined, undefined, generated.trace, thinkingMessage);
        await saveConversationHistoryOnce(tab, userMessage, promptText, generatedText, {
          trace: generated.trace,
          tokenEstimate: tab.lastTokenEstimate,
        });
        message.success('SQL generated.');
      } else {
        appendAssistantTextMessage(tab, generatedText || '未返回可执行 SQL', actionType, generated.trace, thinkingMessage);
        await saveConversationHistoryOnce(tab, userMessage, promptText, '', {
          actionType,
          assistantContent: generatedText || '未返回可执行 SQL',
          databaseName: tab.databaseName,
          trace: generated.trace,
          tokenEstimate: tab.lastTokenEstimate,
        });
        message.warning('未生成可执行 SQL，已返回说明内容');
      }
      if (generated.reasoning) {
        message.info(generated.reasoning);
      }
      clearUserRetryState(userMessage);
      return;
    }

    const endpoint = actionType === 'explain' ? '/api/ai/query/explain' : '/api/ai/query/analyze';
    const streamState = {result: null as AiTextResponseVO | null};
    await postAiStreamWithTimeout(tab, `${endpoint}/stream`, {
      connectionId: tab.connectionId,
      sessionId: tab.sessionId,
      prompt: finalPrompt,
      databaseName: tab.databaseName || undefined,
      modelId: tab.selectedAiModel || undefined,
      memoryEnabled: tab.memoryEnabled,
      detailOutputEnabled: detailOutputEnabledForTab(tab),
    }, (event) => {
      if (event.eventType === 'stage.updated' && event.stage) {
        ensureAssistantStreamingState(tab, thinkingMessage, actionType);
        upsertStreamingTraceStage(thinkingMessage, event.stage);
        return;
      }
      if (event.eventType === 'llm.thinking.delta') {
        ensureAssistantStreamingState(tab, thinkingMessage, actionType);
        thinkingMessage.thinkingContent = event.delta?.accumulatedText || thinkingMessage.thinkingContent || '';
        touchQueryTab(tab);
        return;
      }
      if (event.eventType === 'llm.output.delta') {
        ensureAssistantStreamingState(tab, thinkingMessage, actionType);
        thinkingMessage.liveOutput = event.delta?.accumulatedText || thinkingMessage.liveOutput || '';
        thinkingMessage.content = thinkingMessage.liveOutput || thinkingMessage.content;
        touchQueryTab(tab);
        return;
      }
      if (event.eventType === 'trace.snapshot' && event.trace) {
        applyStreamTraceSnapshot(thinkingMessage, event.trace);
        touchQueryTab(tab);
        return;
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
    tab.lastTokenEstimate = Number(result.totalTokens || 0);
    const content = result.content || 'No content returned.';
    appendAssistantTextMessage(tab, content, actionType, result.trace, thinkingMessage);
    await saveConversationHistoryOnce(tab, userMessage, promptText, actionSqlSnippet || '', {
      actionType,
      assistantContent: content,
      databaseName: tab.databaseName,
      trace: result.trace,
      tokenEstimate: tab.lastTokenEstimate,
    });
    if (result.reasoning) {
      message.info(result.reasoning);
    }
    message.success(actionType === 'explain' ? 'SQL explanation generated.' : 'SQL analysis generated.');
    clearUserRetryState(userMessage);
  } catch (error) {
    const msg = getErrorMessage(error);
    if (isAiRequestAbortedMessage(msg)) {
      thinkingMessage.pending = false;
      thinkingMessage.streaming = false;
      thinkingMessage.aborted = true;
      if (!thinkingMessage.content && !thinkingMessage.liveOutput && !thinkingMessage.thinkingContent) {
        removeQueryChatMessage(tab, thinkingMessage);
      }
      clearUserRetryState(userMessage);
      message.info('Conversation was stopped.');
      return;
    }
    removeQueryChatMessage(tab, thinkingMessage);
    if (isTimeoutErrorMessage(msg)) {
      markUserMessageRetryable(tab, userMessage, retryMeta);
      message.error(timeoutRetryErrorMessage(msg));
    } else {
      clearUserRetryState(userMessage);
      message.error(msg);
    }
  } finally {
    tab.aiGenerating = false;
    touchQueryTab(tab);
  }
}

function autoActionTypeByIntent(intentType: AiIntentType): QueryActionType {
  if (intentType === 'EXPLAIN_SQL') {
    return 'auto_explain';
  }
  if (intentType === 'ANALYZE_SQL') {
    return 'auto_analyze';
  }
  if (intentType === 'GENERATE_CHART') {
    return 'auto_chart_auto_plan';
  }
  return 'auto_generate';
}

async function sendAutoForTab(tab: QueryWorkspaceTab, retryOptions?: RetryInvokeOptions) {
  if (tab.aiGenerating) {
    return;
  }
  const rawPrompt = retryOptions?.promptText ?? tab.prompt.trim();
  if (!rawPrompt) {
    message.info('Please enter a natural language request first.');
    return;
  }
  const sqlSnippet = retryOptions?.actionSqlSnippet ?? resolveSelectedSqlSnippet(tab);
  const finalPrompt = retryOptions?.finalPrompt ?? mergePromptWithSqlSnippet(rawPrompt, sqlSnippet);
  const userMessage = retryOptions?.userMessage ?? appendUserChatMessage(tab, rawPrompt, 'auto_generate');
  const thinkingMessage = appendAssistantThinkingMessage(tab, 'auto_generate');
  if (!retryOptions) {
    tab.prompt = '';
  }
  const retryMeta: RetryRequestMeta = {
    kind: 'auto',
    promptText: rawPrompt,
    finalPrompt,
    actionSqlSnippet: sqlSnippet,
  };
  tab.aiGenerating = true;
  try {
    const streamState = {result: null as AiAutoQueryVO | null};
    await postAiStreamWithTimeout(tab, '/api/ai/query/auto/stream', {
      connectionId: tab.connectionId,
      sessionId: tab.sessionId,
      prompt: finalPrompt,
      databaseName: tab.databaseName || undefined,
      modelId: tab.selectedAiModel || undefined,
      memoryEnabled: tab.memoryEnabled,
      detailOutputEnabled: detailOutputEnabledForTab(tab),
    }, (event) => {
      if (event.eventType === 'intent.resolved' && event.intent?.intentType) {
        thinkingMessage.actionType = autoActionTypeByIntent(event.intent.intentType as AiIntentType);
        touchQueryTab(tab);
        return;
      }
      if (event.eventType === 'stage.updated' && event.stage) {
        ensureAssistantStreamingState(tab, thinkingMessage, thinkingMessage.actionType);
        upsertStreamingTraceStage(thinkingMessage, event.stage);
        return;
      }
      if (event.eventType === 'llm.thinking.delta') {
        ensureAssistantStreamingState(tab, thinkingMessage, thinkingMessage.actionType);
        thinkingMessage.thinkingContent = event.delta?.accumulatedText || thinkingMessage.thinkingContent || '';
        touchQueryTab(tab);
        return;
      }
      if (event.eventType === 'llm.output.delta') {
        ensureAssistantStreamingState(tab, thinkingMessage, thinkingMessage.actionType);
        thinkingMessage.liveOutput = event.delta?.accumulatedText || thinkingMessage.liveOutput || '';
        thinkingMessage.content = thinkingMessage.liveOutput || thinkingMessage.content;
        touchQueryTab(tab);
        return;
      }
      if (event.eventType === 'trace.snapshot' && event.trace) {
        applyStreamTraceSnapshot(thinkingMessage, event.trace);
        touchQueryTab(tab);
        return;
      }
      if (event.eventType === 'result.final' && event.finalResult?.autoQuery) {
        streamState.result = event.finalResult.autoQuery;
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
    const latestTokenEstimate = result.totalTokens;
    if (latestTokenEstimate != null) {
      tab.lastTokenEstimate = Number(latestTokenEstimate || 0);
    }
    const actionType = autoActionTypeByIntent(result.intentType);
    if (result.intentType === 'GENERATE_SQL') {
      const sqlText = (result.sqlText || '').trim();
      if (looksLikeSqlText(sqlText)) {
        const assistantMessage = appendAssistantSqlMessage(
          tab,
          sqlText,
          actionType,
          '',
          undefined,
          undefined,
          undefined,
          result.trace,
          thinkingMessage,
        );
        await saveConversationHistoryOnce(tab, userMessage, rawPrompt, sqlText, {
          actionType,
          databaseName: tab.databaseName,
          trace: result.trace,
          tokenEstimate: tab.lastTokenEstimate,
        });
        if (tab.autoExecute) {
          const executed = await executeSqlForTab(tab, sqlText, { silentSuccess: true });
          if (!executed) {
            message.warning('SQL generated, but auto execution failed.');
          } else if (tab.executeResult?.success) {
            assistantMessage.executionPreview = buildExecutionPreview(tab.executeResult);
            touchQueryTab(tab);
          }
        }
      } else {
        const contentText = sqlText || '未返回可执行 SQL';
        appendAssistantTextMessage(tab, contentText, actionType, result.trace, thinkingMessage);
        await saveConversationHistoryOnce(tab, userMessage, rawPrompt, '', {
          actionType,
          assistantContent: contentText,
          databaseName: tab.databaseName,
          trace: result.trace,
          tokenEstimate: tab.lastTokenEstimate,
        });
      }
    } else if (result.intentType === 'GENERATE_CHART') {
      const sqlText = (result.sqlText || '').trim();
      const config = result.chartConfig ? cloneChartConfig(result.chartConfig) : null;
      const summary = (result.configSummary || '').trim() || chartSummaryText(config);
      if (!sqlText) {
        appendAssistantTextMessage(tab, summary || 'No chart plan returned.', actionType, result.trace, thinkingMessage);
        await saveConversationHistoryOnce(tab, userMessage, rawPrompt, '', {
          actionType,
          assistantContent: summary || 'No chart plan returned.',
          chartConfig: config,
          databaseName: tab.databaseName,
          trace: result.trace,
          tokenEstimate: tab.lastTokenEstimate,
        });
      } else {
        const plannedMessage = appendAssistantSqlMessage(
          tab,
          sqlText,
          actionType,
          summary,
          config,
          summary,
          undefined,
          result.trace,
          thinkingMessage,
        );
        await saveConversationHistoryOnce(tab, userMessage, rawPrompt, sqlText, {
          actionType,
          assistantContent: summary,
          chartConfig: config,
          databaseName: tab.databaseName,
          trace: result.trace,
          tokenEstimate: tab.lastTokenEstimate,
        });
        const generatedChart = await generateChartFromMessage(tab, plannedMessage, {
          appendRenderMessage: false,
          silentSuccess: true,
        });
        if (!generatedChart) {
          message.warning('Chart plan generated, but auto execution failed. Please click Generate Chart manually.');
        }
      }
    } else if (result.intentType === 'EXPLAIN_SQL' || result.intentType === 'ANALYZE_SQL') {
      const content = (result.content || '').trim() || 'No content returned.';
      appendAssistantTextMessage(tab, content, actionType, result.trace, thinkingMessage);
      await saveConversationHistoryOnce(tab, userMessage, rawPrompt, sqlSnippet || '', {
        actionType,
        assistantContent: content,
        databaseName: tab.databaseName,
        trace: result.trace,
        tokenEstimate: tab.lastTokenEstimate,
      });
    } else {
      throw new Error('未识别的 Auto 意图类型');
    }

    if (result.fallbackUsed) {
      message.warning('Auto mode fell back. Please check returned content.');
    }
    clearUserRetryState(userMessage);
  } catch (error) {
    const msg = getErrorMessage(error);
    if (isAiRequestAbortedMessage(msg)) {
      thinkingMessage.pending = false;
      thinkingMessage.streaming = false;
      thinkingMessage.aborted = true;
      if (!thinkingMessage.content && !thinkingMessage.liveOutput && !thinkingMessage.thinkingContent) {
        removeQueryChatMessage(tab, thinkingMessage);
      }
      clearUserRetryState(userMessage);
      message.info('Conversation was stopped.');
      return;
    }
    removeQueryChatMessage(tab, thinkingMessage);
    if (isTimeoutErrorMessage(msg)) {
      markUserMessageRetryable(tab, userMessage, retryMeta);
      message.error(timeoutRetryErrorMessage(msg));
    } else {
      clearUserRetryState(userMessage);
      message.error(msg);
    }
  } finally {
    tab.aiGenerating = false;
    touchQueryTab(tab);
  }
}

function buildChartPrompt(promptText: string) {
  return [
    'Please generate a chart plan.',
    'Requirements:',
    '1) Return executable SQL and structured chart config.',
    '2) 配置需包含图表类型、字段映射、排序建议；',
    '3) Use only the current database context.',
    `用户需求：${promptText}`,
  ].join('\n');
}

function chartTypeLabel(chartType?: string) {
  const normalized = (chartType || '').toUpperCase();
  if (normalized === 'BAR') {
    return 'Bar';
  }
  if (normalized === 'PIE') {
    return '饼图';
  }
  if (normalized === 'SCATTER') {
    return 'Scatter';
  }
  if (normalized === 'TREND') {
    return 'Trend';
  }
  return 'Line';
}

function chartSummaryText(config?: ChartConfigVO | null) {
  if (!config) {
    return 'No usable chart config returned. Please configure manually.';
  }
  const type = chartTypeLabel(config.chartType);
  if ((config.chartType || '').toUpperCase() === 'PIE') {
    return `${type} · Category: ${config.categoryField || '-'} · Value: ${config.valueField || '-'}`;
  }
  const y = (config.yFields || []).join(', ') || '-';
  return `${type} · X: ${config.xField || '-'} · Y: ${y}`;
}

function isChartConfigRenderable(config: ChartConfigVO | null | undefined, rows: Array<Record<string, string | null>>) {
  if (!config) {
    return false;
  }
  const fields = rows.length ? Object.keys(rows[0]) : [];
  const hasField = (field?: string) => !!field && fields.includes(field);
  const chartType = (config.chartType || '').toUpperCase();
  if (chartType === 'PIE') {
    return hasField(config.categoryField) && hasField(config.valueField);
  }
  if (chartType === 'SCATTER') {
    return hasField(config.xField) && !!config.yFields?.[0] && hasField(config.yFields[0]);
  }
  return hasField(config.xField) && !!config.yFields?.length && config.yFields.every((field) => hasField(field));
}

function buildExecutionPreview(result: SqlExecuteVO, maxRows = 20, maxColumns = 12): QueryExecutionPreview {
  const sourceRows = result.rows || [];
  const firstRow = sourceRows[0];
  const allColumns = (firstRow?.cells || [])
    .map((cell) => (cell.columnName || '').trim())
    .filter((item) => !!item);
  const columns = allColumns.slice(0, maxColumns);
  const rows = sourceRows.slice(0, maxRows).map((row, index) => {
    const mapped: Record<string, string | null> = {
      __rowKey: String(index + 1),
    } as Record<string, string | null>;
    (row.cells || []).forEach((cell) => {
      const key = (cell.columnName || '').trim();
      if (!key || !columns.includes(key)) {
        return;
      }
      mapped[key] = cell.cellValue ?? null;
    });
    return mapped;
  });
  return {
    affectedRows: result.affectedRows ?? 0,
    executionMs: result.executionMs ?? 0,
    columns,
    rows,
    truncated: sourceRows.length > maxRows || allColumns.length > maxColumns,
  };
}

function chatExecutionColumns(preview: QueryExecutionPreview) {
  return preview.columns.map((columnName) => ({
    title: columnName,
    dataIndex: columnName,
    key: columnName,
    ellipsis: true,
    width: 140,
  }));
}

const chartExportPixelRatioCandidates = [2, 1.5, 1];

const erDiagramExportPixelRatioCandidates = [2, 1.5, 1];

async function exportChartPngDataUrl(pixelRatio = 2) {
  for (let attempt = 0; attempt < 6; attempt += 1) {
    await nextTick();
    await new Promise((resolve) => setTimeout(resolve, attempt === 0 ? 40 : 90));
    const dataUrl = (await queryChartPanelRef.value?.exportPngDataUrl?.({ pixelRatio })) || '';
    if (dataUrl) {
      return dataUrl;
    }
  }
  return '';
}

async function exportErDiagramPngDataUrl(pixelRatio = 2) {
  for (let attempt = 0; attempt < 6; attempt += 1) {
    await nextTick();
    await new Promise((resolve) => setTimeout(resolve, attempt === 0 ? 40 : 90));
    const dataUrl = (await erDiagramPanelRef.value?.exportPngDataUrl?.({ pixelRatio })) || '';
    if (dataUrl) {
      return dataUrl;
    }
  }
  return '';
}

function normalizeDownloadFileNamePart(text: string) {
  const normalized = text.trim().replace(/[\\/:*?"<>|]+/g, '_').replace(/\s+/g, '_');
  return normalized || 'er';
}

function isChartCacheRetryableError(rawMessage: string) {
  const normalized = rawMessage.trim().toLowerCase();
  return normalized.includes('超过大小限制')
    || normalized.includes('too large')
    || normalized.includes('http 413');
}

function normalizeChartCacheErrorMessage(rawMessage: string) {
  const normalized = rawMessage.trim();
  if (!normalized || /^http \d+$/i.test(normalized)) {
    return 'Chart generated, but image cache failed. Available for this session only.';
  }
  return `图表已生成，但图片缓存失败：${normalized}`;
}

function isLikelyLocalFilePath(rawPath: string) {
  const normalized = rawPath.trim();
  if (!normalized) {
    return false;
  }
  if (normalized.startsWith('/')) {
    return true;
  }
  return /^[a-zA-Z]:[\\/]/.test(normalized);
}

async function saveChartImageCache(
  tab: QueryWorkspaceTab,
  imageDataUrl: string,
  suggestedFileName: string,
) {
  const payload: ChartCacheSaveReq = {
    connectionId: tab.connectionId,
    sessionId: tab.sessionId,
    imageBase64Png: imageDataUrl,
    suggestedFileName,
  };
  const bridge = getDesktopBridge();
  if (bridge && typeof bridge.saveChartCache === 'function') {
    const saved = await bridge.saveChartCache(payload);
    return (saved?.filePath || '').trim();
  }
  const saved = await postApi<ChartCacheSaveVO>('/api/editor/chart/cache/save', payload);
  return (saved.filePath || saved.cacheKey || '').trim();
}

async function loadChartImageDataUrl(cachePathOrKey: string) {
  const normalized = cachePathOrKey.trim();
  if (!normalized) {
    return '';
  }
  const bridge = getDesktopBridge();
  if (bridge && typeof bridge.readChartCache === 'function' && isLikelyLocalFilePath(normalized)) {
    return (await bridge.readChartCache(normalized)) || '';
  }
  const loaded = await getApi<ChartCacheReadVO>(
    `/api/editor/chart/cache/read?cacheKey=${encodeURIComponent(normalized)}`,
  );
  return loaded.dataUrl || '';
}

interface CacheChartImageResult {
  imageDataUrl: string;
  cacheKey: string;
  cacheErrorMessage?: string;
}

async function cacheChartImageWithRetry(tab: QueryWorkspaceTab, suggestedFileName: string): Promise<CacheChartImageResult> {
  let fallbackImageDataUrl = '';
  let lastErrorMessage = '';
  for (const pixelRatio of chartExportPixelRatioCandidates) {
    const imageDataUrl = await exportChartPngDataUrl(pixelRatio);
    if (!imageDataUrl) {
      continue;
    }
    fallbackImageDataUrl = imageDataUrl;
    try {
      const cacheKey = await saveChartImageCache(tab, imageDataUrl, suggestedFileName);
      return {
        imageDataUrl,
        cacheKey,
      };
    } catch (error) {
      lastErrorMessage = getErrorMessage(error);
      if (!isChartCacheRetryableError(lastErrorMessage)) {
        break;
      }
    }
  }
  return {
    imageDataUrl: fallbackImageDataUrl,
    cacheKey: '',
    cacheErrorMessage: lastErrorMessage,
  };
}

function downloadImage(dataUrl: string, fileName: string) {
  if (!dataUrl) {
    return;
  }
  const anchor = document.createElement('a');
  anchor.href = dataUrl;
  anchor.download = fileName;
  document.body.appendChild(anchor);
  anchor.click();
  document.body.removeChild(anchor);
}

async function generateChartPlanForTab(tab: QueryWorkspaceTab, retryOptions?: RetryInvokeOptions) {
  if (tab.aiGenerating) {
    return;
  }
  const rawPrompt = retryOptions?.promptText ?? tab.prompt.trim();
  if (!rawPrompt) {
    message.info('Please enter chart requirements first.');
    return;
  }
  const finalPrompt = retryOptions?.finalPrompt ?? buildChartPrompt(rawPrompt);
  const userMessage = retryOptions?.userMessage ?? appendUserChatMessage(tab, rawPrompt, 'chart_auto_plan');
  const thinkingMessage = appendAssistantThinkingMessage(tab, 'chart_auto_plan');
  if (!retryOptions) {
    tab.prompt = '';
  }
  const retryMeta: RetryRequestMeta = {
    kind: 'chart_plan',
    promptText: rawPrompt,
    finalPrompt,
  };
  tab.aiGenerating = true;
  try {
    const streamState = {generated: null as AiGenerateChartVO | null};
    await postAiStreamWithTimeout(tab, '/api/ai/query/generate-chart/stream', {
      connectionId: tab.connectionId,
      sessionId: tab.sessionId,
      prompt: finalPrompt,
      databaseName: tab.databaseName || undefined,
      modelId: tab.selectedAiModel || undefined,
      memoryEnabled: tab.memoryEnabled,
      detailOutputEnabled: detailOutputEnabledForTab(tab),
    }, (event) => {
      if (event.eventType === 'stage.updated' && event.stage) {
        ensureAssistantStreamingState(tab, thinkingMessage, 'chart_auto_plan');
        upsertStreamingTraceStage(thinkingMessage, event.stage);
        return;
      }
      if (event.eventType === 'llm.thinking.delta') {
        ensureAssistantStreamingState(tab, thinkingMessage, 'chart_auto_plan');
        thinkingMessage.thinkingContent = event.delta?.accumulatedText || thinkingMessage.thinkingContent || '';
        touchQueryTab(tab);
        return;
      }
      if (event.eventType === 'llm.output.delta') {
        ensureAssistantStreamingState(tab, thinkingMessage, 'chart_auto_plan');
        thinkingMessage.liveOutput = event.delta?.accumulatedText || thinkingMessage.liveOutput || '';
        thinkingMessage.content = thinkingMessage.liveOutput || thinkingMessage.content;
        touchQueryTab(tab);
        return;
      }
      if (event.eventType === 'trace.snapshot' && event.trace) {
        applyStreamTraceSnapshot(thinkingMessage, event.trace);
        touchQueryTab(tab);
        return;
      }
      if (event.eventType === 'result.final') {
        streamState.generated = event.finalResult?.generateChart || null;
        return;
      }
      if (event.eventType === 'error') {
        throw new Error(event.error?.message || 'AI 流式请求失败');
      }
    });
    const generated = streamState.generated;
    if (!generated) {
      throw new Error('流式响应未返回最终结果');
    }
    tab.lastTokenEstimate = Number(generated.totalTokens || 0);
    const sqlText = (generated.sqlText || '').trim();
    const config = generated.chartConfig ? cloneChartConfig(generated.chartConfig) : null;
    const summary = (generated.configSummary || '').trim() || chartSummaryText(config);
    if (!sqlText) {
      appendAssistantTextMessage(tab, summary || 'No chart plan returned.', 'chart_auto_plan', generated.trace, thinkingMessage);
      message.warning('未生成可执行 SQL');
      return;
    }
    const plannedMessage = appendAssistantSqlMessage(
      tab,
      sqlText,
      'chart_auto_plan',
      summary,
      config,
      summary,
      undefined,
      generated.trace,
      thinkingMessage,
    );
    await saveConversationHistoryOnce(tab, userMessage, rawPrompt, sqlText, {
      actionType: 'chart_auto_plan',
      assistantContent: summary,
      chartConfig: config,
      databaseName: tab.databaseName,
      trace: generated.trace,
      tokenEstimate: tab.lastTokenEstimate,
    });
    if (generated.reasoning) {
      message.info(generated.reasoning);
    }
    const generatedChart = await generateChartFromMessage(tab, plannedMessage, {
      appendRenderMessage: false,
      silentSuccess: true,
    });
    if (!generatedChart) {
      message.warning('Chart plan generated, but auto execution failed. Please click Generate Chart manually.');
      return;
    }
    message.success('AI 图表方案已执行并生成图表');
    clearUserRetryState(userMessage);
  } catch (error) {
    const msg = getErrorMessage(error);
    if (isAiRequestAbortedMessage(msg)) {
      thinkingMessage.pending = false;
      thinkingMessage.streaming = false;
      thinkingMessage.aborted = true;
      if (!thinkingMessage.content && !thinkingMessage.liveOutput && !thinkingMessage.thinkingContent) {
        removeQueryChatMessage(tab, thinkingMessage);
      }
      clearUserRetryState(userMessage);
      message.info('Conversation was stopped.');
      return;
    }
    removeQueryChatMessage(tab, thinkingMessage);
    if (isTimeoutErrorMessage(msg)) {
      markUserMessageRetryable(tab, userMessage, retryMeta);
      message.error(timeoutRetryErrorMessage(msg));
    } else {
      clearUserRetryState(userMessage);
      message.error(msg);
    }
  } finally {
    tab.aiGenerating = false;
    touchQueryTab(tab);
  }
}

interface GenerateChartFromMessageOptions {
  appendRenderMessage?: boolean;
  silentSuccess?: boolean;
}

async function generateChartFromMessage(
  tab: QueryWorkspaceTab,
  item: QueryChatMessage,
  options?: GenerateChartFromMessageOptions,
) {
  const sqlText = (item.sqlText || '').trim();
  if (!sqlText) {
    message.warning('Current message does not contain SQL. Unable to generate chart.');
    return;
  }
  const success = await executeSqlForTab(tab, sqlText, { silentSuccess: true });
  if (!success) {
    return;
  }
  if (tab.executeResult?.success) {
    item.executionPreview = buildExecutionPreview(tab.executeResult);
  }

  const rows = activeChartRows.value;
  let config = item.chartConfig ? cloneChartConfig(item.chartConfig) : null;
  if (!isChartConfigRenderable(config, rows)) {
    setupManualChartConfigByResult(tab);
    config = cloneChartConfig(tab.manualChartConfig);
    message.warning('AI chart config does not match result fields. Switched to manual default config.');
  } else {
    tab.manualChartConfig = cloneChartConfig(config);
  }
  tab.activeChartConfig = config;
  tab.resultViewMode = 'chart';
  tab.chartReadonly = false;
  touchQueryTab(tab);

  const cached = await cacheChartImageWithRetry(tab, `chart-auto-${Date.now()}`);
  const imageDataUrl = cached.imageDataUrl;
  if (!imageDataUrl) {
    message.warning('图表渲染完成，但图片导出失败');
    return false;
  }
  tab.chartImageDataUrl = imageDataUrl;
  item.chartImageDataUrl = imageDataUrl;
  const cacheKey = cached.cacheKey || '';
  tab.chartImageCacheKey = cacheKey;
  item.chartImageCacheKey = cacheKey;
  if (cached.cacheErrorMessage) {
    message.warning(normalizeChartCacheErrorMessage(cached.cacheErrorMessage));
  }
  if (options?.appendRenderMessage !== false) {
    const renderMessage = appendAssistantSqlMessage(
      tab,
      sqlText,
      'chart_auto_render',
      'Chart generated.',
      config,
      chartSummaryText(config),
      cacheKey,
    );
    renderMessage.chartImageDataUrl = imageDataUrl;
  }
  await saveConversationHistory(tab, '生成图表', sqlText, {
    actionType: 'chart_auto_render',
    assistantContent: chartSummaryText(config),
    chartConfig: config,
    chartImageCacheKey: cacheKey,
    databaseName: tab.databaseName,
  });
  if (!options?.silentSuccess) {
    message.success('Chart generated.');
  }
  return true;
}

async function generateManualChartForTab(tab: QueryWorkspaceTab) {
  if (!tab.executeResult?.rows?.length) {
    message.info('当前没有可用于制图的查询结果');
    return;
  }
  const config = cloneChartConfig(tab.manualChartConfig);
  const rows = activeChartRows.value;
  if (!isChartConfigRenderable(config, rows)) {
    message.warning('图表字段配置不完整，请先选择有效字段');
    return;
  }
  tab.activeChartConfig = config;
  tab.resultViewMode = 'chart';
  tab.chartReadonly = false;
  touchQueryTab(tab);

  const cached = await cacheChartImageWithRetry(tab, `chart-manual-${Date.now()}`);
  const imageDataUrl = cached.imageDataUrl;
  if (!imageDataUrl) {
    message.warning('图表渲染完成，但图片导出失败');
    return;
  }
  tab.chartImageDataUrl = imageDataUrl;
  tab.chartImageCacheKey = cached.cacheKey || '';
  if (cached.cacheErrorMessage) {
    message.warning(normalizeChartCacheErrorMessage(cached.cacheErrorMessage));
  }
  message.success('Manual chart generated.');
}

async function downloadActiveChart(tab: QueryWorkspaceTab) {
  let dataUrl = tab.chartImageDataUrl;
  if (!dataUrl) {
    dataUrl = await exportChartPngDataUrl();
  }
  if (!dataUrl) {
    message.info('暂无可下载的图表图片');
    return;
  }
  downloadImage(dataUrl, `chart-${Date.now()}.png`);
}

async function downloadMessageChart(item: QueryChatMessage) {
  let dataUrl = item.chartImageDataUrl || '';
  if (!dataUrl && item.chartImageCacheKey) {
    try {
      dataUrl = await loadChartImageDataUrl(item.chartImageCacheKey);
      item.chartImageDataUrl = dataUrl;
    } catch {
      message.error('Cached chart not found. Re-run SQL and generate again.');
      return;
    }
  }
  if (!dataUrl) {
    message.info('当前消息暂无图表图片');
    return;
  }
  downloadImage(dataUrl, `chart-${Date.now()}.png`);
}

async function downloadActiveErDiagram(tab: ErWorkspaceTab) {
  if (!tab.graph || tab.loading) {
    message.info('当前 ER 图尚未准备好，无法导出');
    return;
  }
  let dataUrl = '';
  for (const pixelRatio of erDiagramExportPixelRatioCandidates) {
    dataUrl = await exportErDiagramPngDataUrl(pixelRatio);
    if (dataUrl) {
      break;
    }
  }
  if (!dataUrl) {
    message.error('ER 图导出失败，请稍后重试');
    return;
  }
  const fileNamePart = normalizeDownloadFileNamePart(tab.databaseName || tab.title || 'er');
  downloadImage(dataUrl, `er-${fileNamePart}-${Date.now()}.png`);
}

async function hydrateHistoryChartImages(tab: QueryWorkspaceTab) {
  const targets = tab.chatMessages.filter((item) => item.role === 'assistant' && !!item.chartImageCacheKey);
  for (const item of targets) {
    if (item.chartImageDataUrl || !item.chartImageCacheKey) {
      continue;
    }
    try {
      item.chartImageDataUrl = await loadChartImageDataUrl(item.chartImageCacheKey);
    } catch {
      // 历史图缺失不阻断会话加载。
    }
  }
}

async function editChartFromHistory(tab: QueryWorkspaceTab, item: QueryChatMessage) {
  await generateChartFromMessage(tab, item);
}

async function explainSqlForTab(tab: QueryWorkspaceTab, sqlOverride?: string) {
  await runSafely(async () => {
    const sqlText = resolveSqlForAction(tab, sqlOverride);
    if (!sqlText) {
      throw new Error('请先输入或选择 SQL');
    }
    tab.explainResult = await postApi<ExplainVO>('/api/sql/explain', {
      connectionId: tab.connectionId,
      sqlText,
      databaseName: tab.databaseName || undefined,
    });
    tab.executeResult = null;
    touchQueryTab(tab);
    message.success('EXPLAIN 完成');
  });
}

const RISK_EXECUTION_CANCELLED = 'RISK_EXECUTION_CANCELLED';

const SQL_EXECUTION_ABORTED = 'SQL_EXECUTION_ABORTED';

function connectionEnvLabel(connectionId: number) {
  const env = connections.value.find((item) => item.id === connectionId)?.env ?? 'DEV';
  return env.toUpperCase();
}

async function ensureRiskConfirmedBeforeExecute(tab: QueryWorkspaceTab, sqlText: string, signal?: AbortSignal) {
  const result = await postApi<RiskEvaluateVO>('/api/sql/risk/evaluate', {
    connectionId: tab.connectionId,
    sqlText,
  }, {
    signal,
  });
  if (signal?.aborted) {
    throw new Error(SQL_EXECUTION_ABORTED);
  }
  tab.riskInfo = result;
  touchQueryTab(tab);
  const riskAckToken = (result.riskAckToken ?? '').trim();
  if (!result.confirmRequired) {
    return riskAckToken;
  }
  const normalizedRiskLevel = normalizeRiskLevel(result.riskLevel);
  const confirmLevelClass = `risk-level-${normalizedRiskLevel.toLowerCase()}`;
  const riskItemsText = (result.riskItems ?? [])
    .map((item, index) => `${index + 1}. [${item.level}] ${item.description}`)
    .join('\n') || 'No risk details.';
  if (signal?.aborted) {
    throw new Error(SQL_EXECUTION_ABORTED);
  }
  const confirmed = await new Promise<boolean>((resolve) => {
    Modal.confirm({
      title: `${connectionEnvLabel(tab.connectionId)} 环境 SQL 风险确认`,
      content: h('div', { class: ['risk-confirm-content', confirmLevelClass] }, [
        h('div', { class: 'risk-confirm-level-row' }, [
          h('span', '风险级别'),
          h('span', { class: 'risk-confirm-level-badge' }, normalizedRiskLevel),
        ]),
        h('div', `确认策略: ${result.confirmReason || '-'}`),
        h('pre', { class: 'risk-confirm-pre' }, riskItemsText),
      ]),
      okText: '确认执行',
      cancelText: '取消',
      onOk: () => resolve(true),
      onCancel: () => resolve(false),
    });
  });
  if (!confirmed) {
    throw new Error(RISK_EXECUTION_CANCELLED);
  }
  if (!riskAckToken) {
    throw new Error('风险确认令牌缺失，请重新执行');
  }
  return riskAckToken;
}

async function executeSqlForTab(
  tab: QueryWorkspaceTab,
  sqlOverride?: string,
  options?: { silentSuccess?: boolean },
) {
  if (tab.sqlExecuting) {
    return false;
  }
  const sqlText = resolveSqlForAction(tab, sqlOverride);
  if (!sqlText) {
    message.error('请先输入或选择 SQL');
    return false;
  }
  tab.sqlExecuting = true;
  const controller = new AbortController();
  sqlExecutionAbortControllerMap.set(tab.key, controller);
  sqlExecutionAbortReasonMap.delete(tab.key);
  touchQueryTab(tab);
  let riskAckToken = '';
  try {
    try {
      riskAckToken = await ensureRiskConfirmedBeforeExecute(tab, sqlText, controller.signal);
    } catch (error) {
      const manualAborted = sqlExecutionAbortReasonMap.get(tab.key) === 'manual' || isAbortError(error);
      if (manualAborted) {
        message.info('Execution was stopped.');
        return false;
      }
      const errMsg = error instanceof Error ? error.message : String(error);
      if (errMsg === RISK_EXECUTION_CANCELLED) {
        message.info('Execution cancelled.');
        return false;
      }
      message.error(errMsg);
      return false;
    }
    try {
      tab.riskAckToken = riskAckToken;
      const result = await postApi<SqlExecuteVO>('/api/sql/execute', {
        connectionId: tab.connectionId,
        sessionId: tab.sessionId,
        sqlText,
        databaseName: tab.databaseName || undefined,
        memoryEnabled: tab.memoryEnabled,
        riskAckToken: riskAckToken || undefined,
        operatorName: 'desktop-user',
      }, {
        signal: controller.signal,
      });
      tab.executeResult = result;
      tab.explainResult = null;
      tab.riskAckToken = '';
      tab.lastExecuteFailed = false;
      tab.lastExecuteErrorMessage = '';
      tab.lastFailedSqlText = '';
      tab.resultViewMode = 'table';
      tab.chartReadonly = false;
      tab.chartImageDataUrl = '';
      tab.chartImageCacheKey = '';
      setupManualChartConfigByResult(tab);
      if (!options?.silentSuccess) {
        message.success(`执行成功，耗时 ${result.executionMs}ms`);
      }
      return true;
    } catch (error) {
      const manualAborted = sqlExecutionAbortReasonMap.get(tab.key) === 'manual' || isAbortError(error);
      if (manualAborted) {
        tab.riskAckToken = '';
        tab.lastExecuteFailed = false;
        tab.lastExecuteErrorMessage = '';
        tab.lastFailedSqlText = '';
        message.info('Execution was stopped.');
        return false;
      }
      const errMsg = error instanceof Error ? error.message : String(error);
      tab.executeResult = null;
      tab.explainResult = null;
      tab.riskAckToken = '';
      tab.lastExecuteFailed = true;
      tab.lastExecuteErrorMessage = errMsg;
      tab.lastFailedSqlText = sqlText;
      return false;
    }
  } finally {
    if (sqlExecutionAbortControllerMap.get(tab.key) === controller) {
      sqlExecutionAbortControllerMap.delete(tab.key);
    }
    sqlExecutionAbortReasonMap.delete(tab.key);
    tab.sqlExecuting = false;
    touchQueryTab(tab);
  }
}

async function repairSqlForTab(tab: QueryWorkspaceTab) {
  if (!tab.lastExecuteFailed) {
    message.info('最近一次 SQL 执行未失败，无需修复');
    return;
  }
  const failedSql = tab.lastFailedSqlText.trim();
  const errorMessage = tab.lastExecuteErrorMessage.trim();
  if (!failedSql || !errorMessage) {
    message.error('缺少失败 SQL 或错误信息，无法执行修复');
    return;
  }
  if (tab.aiGenerating) {
    return;
  }
  let thinkingMessage: QueryChatMessage | undefined;
  tab.aiGenerating = true;
  try {
    const promptText = `请修复以下 SQL 执行错误。\n错误信息：${errorMessage}\n\nSQL:\n${failedSql}`;
    const userMessage = appendUserChatMessage(tab, promptText, 'repair');
    thinkingMessage = appendAssistantThinkingMessage(tab, 'repair');
    const streamState = {repaired: null as AiRepairVO | null};
    await postAiStreamWithTimeout(tab, '/api/ai/query/repair/stream', {
      connectionId: tab.connectionId,
      sessionId: tab.sessionId,
      sqlText: failedSql,
      errorMessage,
      databaseName: tab.databaseName || undefined,
      modelId: tab.selectedAiModel || undefined,
      detailOutputEnabled: detailOutputEnabledForTab(tab),
    }, (event) => {
      if (!thinkingMessage) {
        return;
      }
      if (event.eventType === 'stage.updated' && event.stage) {
        ensureAssistantStreamingState(tab, thinkingMessage, 'repair');
        upsertStreamingTraceStage(thinkingMessage, event.stage);
        return;
      }
      if (event.eventType === 'llm.thinking.delta') {
        ensureAssistantStreamingState(tab, thinkingMessage, 'repair');
        thinkingMessage.thinkingContent = event.delta?.accumulatedText || thinkingMessage.thinkingContent || '';
        touchQueryTab(tab);
        return;
      }
      if (event.eventType === 'llm.output.delta') {
        ensureAssistantStreamingState(tab, thinkingMessage, 'repair');
        thinkingMessage.liveOutput = event.delta?.accumulatedText || thinkingMessage.liveOutput || '';
        thinkingMessage.content = thinkingMessage.liveOutput || thinkingMessage.content;
        touchQueryTab(tab);
        return;
      }
      if (event.eventType === 'trace.snapshot' && event.trace) {
        applyStreamTraceSnapshot(thinkingMessage, event.trace);
        touchQueryTab(tab);
        return;
      }
      if (event.eventType === 'result.final') {
        streamState.repaired = event.finalResult?.repair || null;
        return;
      }
      if (event.eventType === 'error') {
        throw new Error(event.error?.message || 'AI 流式请求失败');
      }
    });
    const repaired = streamState.repaired;
    if (!repaired) {
      throw new Error('流式响应未返回最终结果');
    }
    const repairedSql = (repaired.repairedSql || failedSql || '').trim();
    const assistantContent = (repaired.errorExplanation || repaired.repairNote || '已尝试修复 SQL').trim();
    appendAssistantSqlMessage(
      tab,
      repairedSql,
      'repair',
      assistantContent,
      undefined,
      undefined,
      undefined,
      repaired.trace,
      thinkingMessage,
    );
    await saveConversationHistoryOnce(tab, userMessage, promptText, repairedSql, {
      actionType: 'repair',
      assistantContent,
      databaseName: tab.databaseName,
      trace: repaired.trace,
    });
    tab.lastExecuteFailed = false;
    tab.lastExecuteErrorMessage = '';
    tab.lastFailedSqlText = '';
    touchQueryTab(tab);
    message.success(repaired.repairNote || 'Repair suggestion generated.');
  } catch (error) {
    const errMsg = error instanceof Error ? error.message : String(error);
    if (isAiRequestAbortedMessage(errMsg) && thinkingMessage) {
      thinkingMessage.pending = false;
      thinkingMessage.streaming = false;
      thinkingMessage.aborted = true;
      if (!thinkingMessage.content && !thinkingMessage.liveOutput && !thinkingMessage.thinkingContent) {
        removeQueryChatMessage(tab, thinkingMessage);
      }
      message.info('Conversation was stopped.');
      return;
    }
    removeQueryChatMessage(tab, thinkingMessage);
    message.error(errMsg);
  } finally {
    tab.aiGenerating = false;
  }
}

async function exportCsvForTab(tab: QueryWorkspaceTab) {
  await runSafely(async () => {
    const result = await postApi<{ filePath: string }>('/api/editor/result/export', {
      connectionId: tab.connectionId,
      sqlText: tab.sqlText,
      format: 'CSV',
      fileName: `aidb_${Date.now()}`,
    });
    message.success(`已导出 ${result.filePath}`);
  });
}

function riskColor(level: string) {
  const normalizedLevel = normalizeRiskLevel(level);
  if (normalizedLevel === 'HIGH') {
    return 'red';
  }
  if (normalizedLevel === 'MEDIUM') {
    return 'orange';
  }
  return 'green';
}

function normalizeRiskLevel(level?: string): 'LOW' | 'MEDIUM' | 'HIGH' {
  if (level === 'HIGH' || level === 'MEDIUM' || level === 'LOW') {
    return level;
  }
  return 'LOW';
}

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

function formatVectorizeProvider(provider?: string) {
  const normalized = (provider || '').trim().toUpperCase();
  if (!normalized) {
    return '-';
  }
  if (normalized === 'CORE_ML') {
    return 'Core ML';
  }
  if (normalized === 'DIRECT_ML') {
    return 'DirectML';
  }
  if (normalized === 'HASH_FALLBACK') {
    return '哈希降级';
  }
  return normalized;
}

onMounted(async () => {
  syncViewportSize();
  window.addEventListener('resize', syncViewportSize);
  loadSessionTitleOverrides();
  startVectorizeStatusPolling();
  await runSafely(async () => {
    await loadConnections();
  });
  await runSafely(async () => {
    const aiConfig = await getApi<AiConfigVO>('/api/ai/config/get');
    const ragConfig = await getApi<RagConfigVO>('/api/rag/config/get');
    fillAiConfigForm(aiConfig);
    fillRagConfigForm(ragConfig);
  });
});

onBeforeUnmount(() => {
  window.removeEventListener('resize', syncViewportSize);
  stopVectorizeStatusPolling();
  clearAllTableStatsPollingTimers();
  sqlEditorTypeDisposable?.dispose();
  sqlEditorTypeDisposable = null;
  sqlEditorSelectionDisposable?.dispose();
  sqlEditorSelectionDisposable = null;
  sqlEditorScrollDisposable?.dispose();
  sqlEditorScrollDisposable = null;
  sqlEditorMouseDownDisposable?.dispose();
  sqlEditorMouseDownDisposable = null;
  sqlEditorMouseUpDisposable?.dispose();
  sqlEditorMouseUpDisposable = null;
  sqlCompletionProviderDisposable?.dispose();
  sqlCompletionProviderDisposable = null;
  sqlEditorContextResolverMap.clear();
  activeSqlEditorInstance = null;
  queryChatMessageElementMap.clear();
  queryChatScrollRef.value = null;
  hideSqlSelectionPopover();
  if (sqlAutoSuggestTimer !== null) {
    window.clearTimeout(sqlAutoSuggestTimer);
    sqlAutoSuggestTimer = null;
  }
});

watch(
  () => [activeWorkbenchTab.value, activeQueryTab.value?.connectionId ?? 0, activeQueryTab.value?.databaseName ?? ''],
  () => {
    if (!activeQueryTab.value) {
      hideSqlSelectionPopover();
      return;
    }
    activeQueryTab.value.selectedSqlText = '';
    hideSqlSelectionPopover();
    void warmupTableSuggestions(activeQueryTab.value);
    syncSelectedSqlForActiveTab(false);
  },
  { immediate: true },
);

watch(
  () => connectionForm.dbType,
  (dbType) => {
    if (dbType === 'MYSQL' && (!connectionForm.port || connectionForm.port <= 0)) {
      connectionForm.port = 3306;
    } else if (dbType === 'POSTGRESQL' && (!connectionForm.port || connectionForm.port <= 0)) {
      connectionForm.port = 5432;
    } else if (dbType === 'SQLSERVER' && (!connectionForm.port || connectionForm.port <= 0)) {
      connectionForm.port = 1433;
    } else if (dbType === 'ORACLE' && (!connectionForm.port || connectionForm.port <= 0)) {
      connectionForm.port = 1521;
    } else if (dbType === 'SQLITE') {
      connectionForm.host = '';
      connectionForm.port = 0;
      connectionForm.username = '';
      connectionForm.password = '';
      connectionForm.selectedDatabases = [];
    }
    if (!isMultiDatabaseType(dbType)) {
      connectionForm.selectedDatabases = [];
    } else if (connectionForm.databaseName === 'sample.db') {
      connectionForm.databaseName = '';
    }
    connectionPreviewDbOptions.value = [];
    connectionPreviewError.value = '';
  },
  { immediate: true },
);

watch(
  () => connectionForm.sshEnabled,
  (enabled) => {
    if (!enabled) {
      connectionForm.sshAuthType = 'SSH_PASSWORD';
      connectionForm.sshPassword = '';
      connectionForm.sshPrivateKeyPath = '';
      connectionForm.sshPrivateKeyText = '';
      connectionForm.sshPrivateKeyPassphrase = '';
      return;
    }
    if (!connectionForm.sshPort || connectionForm.sshPort <= 0) {
      connectionForm.sshPort = 22;
    }
    if (!connectionForm.sshAuthType) {
      connectionForm.sshAuthType = 'SSH_PASSWORD';
    }
  },
  { immediate: true },
);

watch(
  () => connectionForm.sshAuthType,
  (mode) => {
    if (!connectionForm.sshEnabled) {
      return;
    }
    if (mode === 'SSH_PASSWORD') {
      connectionForm.sshPrivateKeyPath = '';
      connectionForm.sshPrivateKeyText = '';
      connectionForm.sshPrivateKeyPassphrase = '';
      return;
    }
    if (mode === 'SSH_KEY_PATH') {
      connectionForm.sshPassword = '';
      connectionForm.sshPrivateKeyText = '';
      return;
    }
    if (mode === 'SSH_KEY_TEXT') {
      connectionForm.sshPassword = '';
      connectionForm.sshPrivateKeyPath = '';
    }
  },
);

watch(
  () => JSON.stringify(aiConfigForm.modelOptions ?? []),
  () => {
    const models = aiModelOptions.value.map((item) => String(item.value)).filter((item) => !!item);
    if (!models.length) {
      selectedAiModel.value = '';
      erSelectModelName.value = '';
      queryTabs.value.forEach((tab) => {
        tab.selectedAiModel = '';
      });
      erTabs.value.forEach((tab) => {
        tab.selectedAiModel = '';
      });
      return;
    }
    if (!models.includes(selectedAiModel.value)) {
      selectedAiModel.value = models[0];
    }
    if (!models.includes(erSelectModelName.value)) {
      erSelectModelName.value = models[0];
    }
    queryTabs.value.forEach((tab) => {
      if (!models.includes(tab.selectedAiModel)) {
        tab.selectedAiModel = models[0];
      }
    });
    erTabs.value.forEach((tab) => {
      if (!models.includes(tab.selectedAiModel)) {
        tab.selectedAiModel = models[0];
      }
    });
  },
  { immediate: true },
);

function expandConnectionNode(connectionId: number) {
  const connection = connections.value.find((item) => item.id === connectionId);
  if (!connection) {
    return;
  }
  const keys = new Set(expandedTreeKeys.value);
  keys.add(`conn-${connectionId}`);
  const activeDb = getActiveDatabaseName(connectionId);
  const activeCategory = currentObjectType.value || 'tables';
  if (requiresDatabaseLayer(connection)) {
    if (activeDb) {
      keys.add(buildDatabaseNodeKey(connectionId, activeDb));
      keys.add(buildCategoryNodeKey(connectionId, activeDb, activeCategory));
    }
  } else {
    keys.add(buildCategoryNodeKey(connectionId, activeDb, activeCategory));
  }
  expandedTreeKeys.value = Array.from(keys);
}

function buildDatabaseNodeKey(connectionId: number, databaseName: string) {
  return `conn-${connectionId}-db-${encodeURIComponent(databaseName)}`;
}

function buildCategoryNodeKey(connectionId: number, databaseName: string, category: string) {
  return `conn-${connectionId}-db-${encodeURIComponent(databaseName)}-category-${category}`;
}

function buildObjectNodeKey(connectionId: number, databaseName: string, objectType: string, objectName: string) {
  return `conn-${connectionId}-db-${encodeURIComponent(databaseName)}-obj-${objectType}-${encodeURIComponent(objectName)}`;
}

function expandCategoryNode(connectionId: number, databaseName: string, category: string) {
  const keys = new Set(expandedTreeKeys.value);
  keys.add(buildCategoryNodeKey(connectionId, databaseName, category));
  expandedTreeKeys.value = Array.from(keys);
}

function toObjectType(value: string): 'tables' | 'views' | 'functions' | 'events' | 'queries' | 'backups' {
  const normalized = value.toLowerCase();
  if (normalized === 'tables' || normalized === 'views' || normalized === 'functions'
    || normalized === 'events' || normalized === 'queries' || normalized === 'backups') {
    return normalized;
  }
  return 'tables';
}

function objectTypeLabel(value: string) {
  if (value === 'tables') {
    return 'Table';
  }
  if (value === 'views') {
    return '视图';
  }
  if (value === 'functions') {
    return '函数';
  }
  if (value === 'queries') {
    return '查询';
  }
  return value;
}

function normalizeEnv(value?: string) {
  const env = (value || '').trim().toUpperCase();
  if (env === 'PROD' || env === 'TEST' || env === 'DEV') {
    return env;
  }
  return 'DEV';
}

function envTagText(value?: string) {
  const env = normalizeEnv(value);
  if (env === 'PROD') {
    return '生产';
  }
  if (env === 'TEST') {
    return '测试';
  }
  return 'Dev';
}

function envTagClass(value?: string) {
  const env = normalizeEnv(value);
  if (env === 'PROD') {
    return 'is-prod';
  }
  if (env === 'TEST') {
    return 'is-test';
  }
  return 'is-dev';
}

function envTagIcon(value?: string) {
  const env = normalizeEnv(value);
  if (env === 'PROD') {
    return CloseCircleOutlined;
  }
  if (env === 'TEST') {
    return CheckCircleOutlined;
  }
  return ToolOutlined;
}

function nodeIconComponent(dataRef: { nodeType?: string }) {
  if (dataRef.nodeType === 'database') {
    return DatabaseOutlined;
  }
  if (dataRef.nodeType === 'tables') {
    return FolderOpenOutlined;
  }
  if (dataRef.nodeType === 'views') {
    return EyeOutlined;
  }
  if (dataRef.nodeType === 'functions') {
    return CodeOutlined;
  }
  if (dataRef.nodeType === 'events') {
    return ClockCircleOutlined;
  }
  if (dataRef.nodeType === 'queries') {
    return SearchOutlined;
  }
  if (dataRef.nodeType === 'backups') {
    return HddOutlined;
  }
  return AppstoreOutlined;
}

function quoteSqlIdentifier(identifier: string, dbType: string) {
  const text = String(identifier || '').trim();
  if (!text) {
    return '';
  }
  if (dbType === 'SQLSERVER') {
    return `[${text}]`;
  }
  if (dbType === 'POSTGRESQL' || dbType === 'ORACLE') {
    return `"${text}"`;
  }
  return `\`${text}\``;
}

function buildColumnSqlDefinition(
  column: TableDetailVO['columns'][number],
  dbType: string,
) {
  const colName = quoteSqlIdentifier(column.columnName, dbType) || column.columnName;
  const baseType = (column.dataType || 'TEXT').trim();
  let typeSql = baseType;
  if (!/\(/.test(baseType) && column.columnSize && column.columnSize > 0) {
    if (column.decimalDigits && column.decimalDigits > 0) {
      typeSql = `${baseType}(${column.columnSize},${column.decimalDigits})`;
    } else if (/char|binary|var|text|int|number|decimal|numeric/i.test(baseType)) {
      typeSql = `${baseType}(${column.columnSize})`;
    }
  }
  const fragments = [`${colName} ${typeSql}`];
  if (column.nullable === false) {
    fragments.push('NOT NULL');
  }
  if (column.defaultValue != null && String(column.defaultValue).trim() !== '') {
    fragments.push(`DEFAULT ${String(column.defaultValue).trim()}`);
  }
  if (column.autoIncrement) {
    if (dbType === 'SQLSERVER') {
      fragments.push('IDENTITY(1,1)');
    } else if (dbType === 'POSTGRESQL') {
      fragments.push('GENERATED BY DEFAULT AS IDENTITY');
    } else {
      fragments.push('AUTO_INCREMENT');
    }
  }
  if (column.columnComment && dbType === 'MYSQL') {
    fragments.push(`COMMENT '${column.columnComment.replace(/'/g, "''")}'`);
  }
  return fragments.join(' ');
}

function buildCreateTableSql(tableName: string, columns: TableDetailVO['columns'], dbTypeRaw: string) {
  const dbType = (dbTypeRaw || 'MYSQL').toUpperCase();
  const lines = columns.map((column) => `  ${buildColumnSqlDefinition(column, dbType)}`);
  const primaryKeys = columns
    .filter((column) => column.primaryKey)
    .map((column) => quoteSqlIdentifier(column.columnName, dbType) || column.columnName);
  if (primaryKeys.length) {
    lines.push(`  PRIMARY KEY (${primaryKeys.join(', ')})`);
  }
  const tableQuoted = quoteSqlIdentifier(tableName, dbType) || tableName;
  return `CREATE TABLE ${tableQuoted} (\n${lines.join(',\n')}\n);`;
}

function escapeHtml(raw: string) {
  return raw
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

function highlightSqlForDisplay(sqlText: string) {
  const escaped = escapeHtml(sqlText || '');
  const keywords = [
    'CREATE',
    'ALTER',
    'TABLE',
    'PRIMARY',
    'KEY',
    'NOT',
    'NULL',
    'DEFAULT',
    'AUTO_INCREMENT',
    'COMMENT',
    'GENERATED',
    'BY',
    'AS',
    'IDENTITY',
    'ADD',
    'DROP',
    'COLUMN',
    'INDEX',
    'UNIQUE',
    'MODIFY',
    'CURRENT_TIMESTAMP',
    'ON',
    'UPDATE',
  ];
  const dataTypes = [
    'BIGINT',
    'INT',
    'INTEGER',
    'SMALLINT',
    'TINYINT',
    'MEDIUMINT',
    'SERIAL',
    'BIGSERIAL',
    'DECIMAL',
    'NUMERIC',
    'FLOAT',
    'DOUBLE',
    'REAL',
    'BOOLEAN',
    'BIT',
    'CHAR',
    'NCHAR',
    'VARCHAR',
    'NVARCHAR',
    'TEXT',
    'MEDIUMTEXT',
    'LONGTEXT',
    'DATE',
    'TIME',
    'DATETIME',
    'TIMESTAMP',
    'YEAR',
    'BLOB',
    'LONGBLOB',
    'JSON',
    'UUID',
  ];
  const keywordSet = new Set(keywords.map((item) => item.toUpperCase()));
  const typeSet = new Set(dataTypes.map((item) => item.toUpperCase()));
  const tokens = Array.from(new Set([...keywords, ...dataTypes]))
    .sort((a, b) => b.length - a.length);
  const tokenPattern = new RegExp(`\\b(${tokens.join('|')})\\b`, 'gi');
  return escaped.replace(tokenPattern, (matched) => {
    const upper = matched.toUpperCase();
    if (keywordSet.has(upper)) {
      return `<span class="sql-keyword">${matched}</span>`;
    }
    if (typeSet.has(upper)) {
      return `<span class="sql-datatype">${matched}</span>`;
    }
    return matched;
  });
}

async function copyTextContent(text: string, successText: string) {
  if (!text.trim()) {
    return;
  }
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text);
      message.success(successText);
      return;
    }
    throw new Error('clipboard unavailable');
  } catch {
    const input = document.createElement('textarea');
    input.value = text;
    input.setAttribute('readonly', 'true');
    input.style.position = 'fixed';
    input.style.opacity = '0';
    document.body.appendChild(input);
    input.select();
    document.execCommand('copy');
    document.body.removeChild(input);
    message.success(successText);
  }
}

async function copyCreateTableSql() {
  await copyTextContent(createTableSqlText.value, '建表语句已复制');
}

async function copyTableEditorSql() {
  const sql = activeTableEditorTab.value?.previewSql || '';
  await copyTextContent(sql, 'SQL 已复制');
}

function dbIconUrl(dbType: string) {
  if (dbType === 'MYSQL') {
    return mysqlIcon;
  }
  if (dbType === 'POSTGRESQL') {
    return postgresqlIcon;
  }
  if (dbType === 'SQLITE') {
    return sqliteIcon;
  }
  if (dbType === 'SQLSERVER') {
    return sqlserverIcon;
  }
  if (dbType === 'ORACLE') {
    return oracleIcon;
  }
  return sqliteIcon;
}

function normalizeModelOptions(options: AiModelOption[] | undefined) {
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
      } satisfies AiModelOption;
    })
    .filter((item) => !!item.id);
  if (list.length) {
    return list;
  }
  return defaultAiConfigForm().modelOptions ?? [];
}

function nextModelOptionId(prefix: 'openai' | 'cli') {
  const existing = new Set((aiConfigForm.modelOptions ?? []).map((item) => item.id));
  let index = 1;
  while (existing.has(`${prefix}-${index}`)) {
    index++;
  }
  return `${prefix}-${index}`;
}

function addOpenAiModelOption() {
  const id = nextModelOptionId('openai');
  aiConfigForm.modelOptions = [
    ...(aiConfigForm.modelOptions ?? []),
    {
      id,
      name: `OpenAI ${id}`,
      providerType: 'OPENAI',
      openaiBaseUrl: 'https://api.openai.com/v1',
      openaiApiKey: '',
      openaiModel: 'gpt-4.1-mini',
      cliCommand: '',
      cliWorkingDir: '',
    },
  ];
}

function addCliModelOption() {
  const id = nextModelOptionId('cli');
  aiConfigForm.modelOptions = [
    ...(aiConfigForm.modelOptions ?? []),
    {
      id,
      name: `CLI ${id}`,
      providerType: 'LOCAL_CLI',
      openaiBaseUrl: '',
      openaiApiKey: '',
      openaiModel: '',
      cliCommand: '',
      cliWorkingDir: '',
    },
  ];
}

function removeModelOption(index: number) {
  const list = [...(aiConfigForm.modelOptions ?? [])];
  list.splice(index, 1);
  aiConfigForm.modelOptions = list.length ? list : (defaultAiConfigForm().modelOptions ?? []);
}

function defaultConnectionForm(): ConnectionCreateReq {
  return {
    name: '本地 SQLite',
    dbType: 'SQLITE',
    host: '',
    port: 0,
    databaseName: 'sample.db',
    selectedDatabases: [],
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

function resetConnectionForm() {
  Object.assign(connectionForm, defaultConnectionForm());
}

function fillConnectionForm(connection: ConnectionVO) {
  Object.assign(connectionForm, {
    name: connection.name,
    dbType: connection.dbType,
    host: connection.host ?? '',
    port: connection.port ?? 0,
    databaseName: connection.databaseName ?? '',
    selectedDatabases: normalizeSelectedDatabases(connection.selectedDatabases),
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

function defaultAiConfigForm(): AiConfigSaveReq {
  const defaultOption: AiModelOption = {
    id: 'openai-1',
    name: 'OpenAI gpt-4.1-mini',
    providerType: 'OPENAI',
    openaiBaseUrl: 'https://api.openai.com/v1',
    openaiApiKey: '',
    openaiModel: 'gpt-4.1-mini',
    cliCommand: '',
    cliWorkingDir: '',
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

function fillAiConfigForm(config: AiConfigVO) {
  const options = normalizeModelOptions(config.modelOptions);
  const first = options[0];
  Object.assign(aiConfigForm, {
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
  if (!models.includes(selectedAiModel.value)) {
    selectedAiModel.value = models[0] || '';
  }
}

function defaultRagConfigForm(): RagConfigSaveReq {
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

function fillRagConfigForm(config: RagConfigVO) {
  Object.assign(ragConfigForm, {
    ragEmbeddingProviderType: normalizeRagProviderType(config.ragEmbeddingProviderType),
    ragEmbeddingModelDir: config.ragEmbeddingModelDir || '',
    ragEmbeddingOnlineBaseUrl: config.ragEmbeddingOnlineBaseUrl || 'https://api.openai.com/v1',
    ragEmbeddingOnlineApiKey: config.ragEmbeddingOnlineApiKey || '',
    ragEmbeddingOnlineModel: config.ragEmbeddingOnlineModel || '',
    ragRerankEnabled: config.ragRerankEnabled === true,
    ragRerankProviderType: normalizeRagProviderType(config.ragRerankProviderType),
    ragRerankModelDir: config.ragRerankModelDir || '',
    ragRerankOnlineBaseUrl: config.ragRerankOnlineBaseUrl || 'https://api.openai.com/v1',
    ragRerankOnlineApiKey: config.ragRerankOnlineApiKey || '',
    ragRerankOnlineModel: config.ragRerankOnlineModel || '',
  } satisfies RagConfigSaveReq);
  if (!ragLocalOnnxEnabled) {
    ragConfigForm.ragEmbeddingProviderType = 'ONLINE_OPENAI_COMPAT';
    ragConfigForm.ragEmbeddingModelDir = '';
    ragConfigForm.ragRerankProviderType = 'ONLINE_OPENAI_COMPAT';
    ragConfigForm.ragRerankModelDir = '';
  }
}

function normalizeRagProviderType(value?: string): 'LOCAL_ONNX' | 'ONLINE_OPENAI_COMPAT' {
  return normalizeRagProviderByPackage(value);
}

function resetConnectionModalState() {
  isEditMode.value = false;
  editingConnectionId.value = null;
  resetConnectionForm();
  connectionPreviewDbOptions.value = [];
  connectionPreviewError.value = '';
}

  return {
    packageVariant,
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
    saveQueryModalOpen,
    saveQuerySubmitting,
    saveQueryTitle,
    truncateTableModalOpen,
    truncateTableName,
    dropTableModalOpen,
    dropTableName,
    aiConfigModalOpen,
    aiConfigActiveTab,
    uiTheme,
    selectedAiModel,
    activeWorkbenchTab,
    queryTabs,
    erTabs,
    tableEditorTabs,
    tableDataTabs,
    knowledgeTabs,
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
    contextMenu,
    connectionForm,
    connectionPreviewDbOptions,
    connectionPreviewLoading,
    connectionPreviewError,
    aiConfigForm,
    ragConfigForm,
    pickingRagModelDir,
    pickingRagRerankModelDir,
    workflow,
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
    activeQueryContextUsage,
    activeErTab,
    activeTableEditorTab,
    activeTableDataTab,
    activeKnowledgeTab,
    activeErConfidenceThreshold,
    activeErAiRelationTotal,
    activeErDisplayGraph,
    activeErForeignKeyRelations,
    activeErAiRelations,
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
    connectionSelectOptions,
    isMultiDatabaseFormType,
    connectionPreviewSelectOptions,
    canPreviewDatabases,
    connectionTreeData,
    objectRows,
    selectedObjectRecord,
    selectedTreeDetail,
    selectedTreeConnection,
    selectedTreeDatabaseStatusLabel,
    selectedTreeDatabaseTableCount,
    selectedTreeDatabaseColumnCount,
    createTableSqlText,
    createTableSqlHighlighted,
    tableEditorSqlHighlighted,
    filteredObjectRows,
    objectColumns,
    tableScrollY,
    queryResultScrollY,
    aiModelOptions,
    workbenchStyle,
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
    emptyManualChartConfig,
    cloneChartConfig,
    isNumericField,
    setupManualChartConfigByResult,
    buildConnectionNode,
    buildCategoryChildren,
    getCategoryChildren,
    requiresDatabaseLayer,
    isMultiDatabaseType,
    normalizeSelectedDatabases,
    visibleDatabasesForConnection,
    parseConfiguredDatabaseName,
    sanitizeDatabaseName,
    tableCacheKey,
    objectCacheKey,
    vectorizeStatusCacheKey,
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
    saveConnection,
    previewConnectionDatabases,
    testConnection,
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
    loadCategoryObjects,
    loadTreeChildrenByKey,
    handleTreeSelect,
    handleTreeExpand,
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
    resolveSqlForAction,
    resolveSelectedSqlSnippet,
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
    generateSqlForTab,
    autoActionTypeByIntent,
    sendAutoForTab,
    buildChartPrompt,
    chartTypeLabel,
    chartSummaryText,
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
    exportCsvForTab,
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
    resetConnectionModalState
  };
}

export type StudioRuntime = ReturnType<typeof useStudioRuntime>;
