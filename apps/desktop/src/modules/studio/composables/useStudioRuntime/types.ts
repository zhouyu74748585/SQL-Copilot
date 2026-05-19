import type {
  AiTraceVO,
  ChartCacheSaveReq,
  ChartConfigVO,
  ErGraphVO,
  ErLayoutMode,
  ExplainVO,
  PromptBudgetVO,
  RiskEvaluateVO,
  SchemaObjectType,
  SqlExecuteVO,
  TableCopyMode,
  TableDetailVO,
} from '../../../../types';
export interface DesktopDialogFilter {
  name: string;
  extensions: string[];
}

export interface DesktopPickFileOptions {
  title?: string;
  defaultPath?: string;
  filters?: DesktopDialogFilter[];
}

export interface DesktopBridge {
  pickFile: (options?: DesktopPickFileOptions) => Promise<string>;
  pickDirectory: (options?: Omit<DesktopPickFileOptions, 'filters'>) => Promise<string>;
  openExternal?: (url?: string) => Promise<boolean>;
  openPrivacyPolicy?: () => Promise<boolean>;
  setUiTheme?: (theme?: 'light' | 'dark' | string) => Promise<boolean>;
  saveChartCache?: (payload: ChartCacheSaveReq) => Promise<{ filePath: string; width: number; height: number }>;
  readChartCache?: (filePath: string) => Promise<string>;
}

export interface ObjectRow {
  nodeKey?: string;
  nodeName?: string;
  fullPath?: string;
  redisNodeType?: 'PATH' | 'KEY' | 'LOAD_MORE';
  hasChildren?: boolean;
  ttlSeconds?: number;
  children?: ObjectRow[];
  nextCursor?: string;
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

export type ConnectionFormFieldKey =
  | 'name'
  | 'dbType'
  | 'groupId'
  | 'env'
  | 'host'
  | 'port'
  | 'databaseName'
  | 'username'
  | 'sshHost'
  | 'sshPort'
  | 'sshUser'
  | 'sshPassword'
  | 'sshPrivateKeyPath'
  | 'sshPrivateKeyText';

export type ConnectionFormErrorMap = Partial<Record<ConnectionFormFieldKey, string>>;

export interface TableCopyClipboard {
  sourceConnectionId: number;
  sourceDatabaseName: string;
  sourceTableName: string;
  sourceDbType: string;
  preferredCopyMode: TableCopyMode;
  copiedAt: number;
}

export type QueryActionType =
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

export type AiActionType = 'generate' | 'explain' | 'analyze';

export type UiTheme = 'light' | 'dark';

export type QueryResultViewMode = 'table' | 'chart';

export type RetryActionKind = 'ai_action' | 'auto' | 'chart_plan';

export type RequestAbortReason = 'manual' | 'timeout';

export interface RetryRequestMeta {
  kind: RetryActionKind;
  actionType?: AiActionType;
  promptText: string;
  finalPrompt: string;
  actionSqlSnippet?: string;
}

export interface QueryExecutionPreview {
  affectedRows: number;
  executionMs: number;
  columns: string[];
  rows: Array<Record<string, string | null>>;
  truncated: boolean;
}

export type QueryResultTableRow = Record<string, string | null> & { __rowKey: string; __rowState: string };

export interface QueryResultTableColumn {
  title: string;
  dataIndex: string;
  key: string;
  width: number;
  ellipsis: boolean;
}

export interface QueryStatementResult {
  key: string;
  index: number;
  sqlText: string;
  status: 'running' | 'success' | 'error';
  executeResult: SqlExecuteVO | null;
  resultTableRows: QueryResultTableRow[];
  resultTableColumns: QueryResultTableColumn[];
  columnWidthMap: Record<string, number>;
  lastExecuteFailed: boolean;
  lastExecuteErrorMessage: string;
  lastFailedSqlText: string;
  resultViewMode: QueryResultViewMode;
  manualChartConfig: ChartConfigVO;
  activeChartConfig: ChartConfigVO | null;
  chartImageDataUrl: string;
  chartImageCacheKey: string;
  chartReadonly: boolean;
}

export interface QueryChatMessage {
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
  thinkingExpanded?: boolean;
  createdAt: number;
}

export interface QueryWorkspaceTab {
  key: string;
  title: string;
  connectionId: number;
  databaseName: string;
  savedQueryId?: number;
  savedQueryEditMode: boolean;
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
  executingStatementIndex: number | null;
  selectedSqlText: string;
  chatMessages: QueryChatMessage[];
  resultTableRows: QueryResultTableRow[];
  resultTableColumns: QueryResultTableColumn[];
  lastExecuteFailed: boolean;
  lastExecuteErrorMessage: string;
  lastFailedSqlText: string;
  resultViewMode: QueryResultViewMode;
  manualChartConfig: ChartConfigVO;
  activeChartConfig: ChartConfigVO | null;
  chartImageDataUrl: string;
  chartImageCacheKey: string;
  chartReadonly: boolean;
  statementResults: QueryStatementResult[];
  activeStatementResultKey: string;
  createdAt: number;
  updatedAt: number;
  conversationMemoryEnabled: boolean;
  sqlMemoryEnabled: boolean;
  detailOutputOverride: boolean | null;
  lastTokenEstimate: number;
  lastPromptBudget: PromptBudgetVO | null;
  lastRequestPromptTokens: number;
  lastRequestCompletionTokens: number;
  lastRequestTotalTokens: number;
  lastTurnContentTokens: number;
}

export interface QueryContextUsage {
  enabled: boolean;
  windowUsedTokens: number;
  windowTotalTokens: number;
  windowRatio: number;
  windowCappedRatio: number;
  windowPercent: number;
  promptUsedTokens: number;
  promptTotalTokens: number;
  promptRatio: number;
  promptCappedRatio: number;
  promptPercent: number;
  tone: 'idle' | 'normal' | 'warning' | 'danger';
}

export interface SqlEditorContext {
  connectionId: number;
  databaseName: string;
}

export interface SqlEditorMountOptions {
  getContext?: () => SqlEditorContext | null;
  enableSelectionActions?: boolean;
}

export interface ErWorkspaceTab {
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
  detailCollapsed: boolean;
  aiConfidenceThreshold: number;
  includeAiInference: boolean;
  loading: boolean;
  graph: ErGraphVO | null;
  selectedRelationKey: string;
  errorMessage: string;
  createdAt: number;
  updatedAt: number;
}

export interface TableEditorColumnDraft {
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

export interface TableEditorIndexDraft {
  uuid: string;
  indexName: string;
  unique: boolean;
  columns: string[];
}

export interface TableEditorDraft {
  tableName: string;
  tableComment: string;
  columns: TableEditorColumnDraft[];
  indexes: TableEditorIndexDraft[];
}

export interface TableEditorWorkspaceTab {
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

export type TableDataFilterOperator =
  | 'EQ'
  | 'NE'
  | 'GT'
  | 'GTE'
  | 'LT'
  | 'LTE'
  | 'LIKE'
  | 'IS_NULL'
  | 'IS_NOT_NULL';

export interface TableDataFilterDraft {
  key: string;
  columnName: string;
  operator: TableDataFilterOperator;
  value: string;
}

export interface TableDataSortDraft {
  key: string;
  columnName: string;
  direction: 'ASC' | 'DESC';
}

export interface TableDataSearchMatch {
  rowKey: string;
  columnName: string;
}

export interface TableDataRowDraft {
  rowKey: string;
  values: Record<string, string | null>;
  originalValues: Record<string, string | null>;
  rowState: 'clean' | 'new' | 'updated';
}

export interface TableDataDeleteDraft {
  rowKey: string;
  values: Record<string, string | null>;
}

export interface TableDataWorkspaceTab {
  key: string;
  title: string;
  connectionId: number;
  databaseName: string;
  tableName: string;
  objectType: 'tables' | 'views';
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
  checkedRowKeys: string[];
  editingCellKey: string;
  detailCollapsed: boolean;
  filterPanelVisible: boolean;
  filters: TableDataFilterDraft[];
  sorts: TableDataSortDraft[];
  searchPanelVisible: boolean;
  searchReplaceVisible: boolean;
  searchKeyword: string;
  replaceKeyword: string;
  searchMatches: TableDataSearchMatch[];
  activeSearchMatchIndex: number;
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
  columnWidthMap: Record<string, number>;
  errorMessage: string;
  dirty: boolean;
  createdAt: number;
  updatedAt: number;
}

export interface ObjectDefinitionEditorTab {
  key: string;
  title: string;
  connectionId: number;
  databaseName: string;
  objectType: SchemaObjectType;
  objectName: string;
  dbType: string;
  mode: 'create' | 'edit';
  isNewObject: boolean;
  sqlText: string;
  baselineSql: string;
  loading: boolean;
  saving: boolean;
  dirty: boolean;
  errorMessage: string;
  createdAt: number;
  updatedAt: number;
}

export interface KnowledgeWorkspaceTab {
  key: string;
  node: 'example-sql' | 'terms';
  title: string;
  createdAt: number;
  updatedAt: number;
}

export interface MemoryWorkspaceTab {
  key: string;
  node: 'entries';
  title: string;
  createdAt: number;
  updatedAt: number;
}

export type ErLineType = 'POLYLINE' | 'STRAIGHT';

export interface SqlTableReference {
  tableName: string;
  databaseName: string;
  alias: string;
}

export interface SqlQualifiedColumnContext {
  qualifierParts: string[];
  prefix: string;
  replaceStartColumn: number;
}
