import type {
    AiAutoQueryVO,
    AiGenerateChartVO,
    AiIntentType,
    ChartCacheReadVO,
    ChartCacheSaveVO,
    ChartConfigVO,
    ChartType,
    QueryRowVO,
    RiskEvaluateVO,
    SortDirection,
    SqlExecuteVO,
} from '@sqlcopilot/shared-contracts';

export type {
  AiAutoQueryVO,
  AiGenerateChartVO,
  AiIntentType,
  ChartCacheReadVO,
  ChartCacheSaveVO,
  ChartConfigVO,
  ChartType,
  QueryRowVO,
  RiskEvaluateVO,
  SortDirection,
  SqlExecuteVO,
};

export interface ConnectionCreateReq {
  name: string;
  dbType: string;
  host?: string;
  port?: number;
  databaseName?: string;
  selectedDatabases?: string[];
  username?: string;
  password?: string;
  authType?: string;
  env: string;
  readOnly: boolean;
  sshEnabled: boolean;
  sshHost?: string;
  sshPort?: number;
  sshUser?: string;
  sshAuthType?: 'SSH_PASSWORD' | 'SSH_KEY_PATH' | 'SSH_KEY_TEXT';
  sshPassword?: string;
  sshPrivateKeyPath?: string;
  sshPrivateKeyText?: string;
  sshPrivateKeyPassphrase?: string;
}

export interface ConnectionDatabasePreviewReq {
  dbType: string;
  host?: string;
  port?: number;
  databaseName?: string;
  username?: string;
  password?: string;
  sshEnabled?: boolean;
  sshHost?: string;
  sshPort?: number;
  sshUser?: string;
  sshAuthType?: 'SSH_PASSWORD' | 'SSH_KEY_PATH' | 'SSH_KEY_TEXT';
  sshPassword?: string;
  sshPrivateKeyPath?: string;
  sshPrivateKeyText?: string;
  sshPrivateKeyPassphrase?: string;
}

export interface ConnectionDatabasePreviewVO {
  databaseNames: string[];
}

export interface SchemaOverviewVO {
  connectionId: number;
  databaseName?: string;
  tableCount: number;
  columnCount: number;
  tableSummaries: Array<{
    tableName: string;
    tableComment?: string;
    rowEstimate?: number;
    tableSizeBytes?: number;
  }>;
}

export interface SchemaDatabaseVO {
  databaseName: string;
  vectorizeStatus: 'NOT_VECTORIZED' | 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED';
  vectorizeMessage?: string;
  vectorizeUpdatedAt?: number;
}

export interface SchemaTableStatsVO {
  connectionId: number;
  databaseName?: string;
  refreshing: boolean;
  updatedAt?: number;
  tableStats: Array<{
    tableName: string;
    rowEstimate?: number;
    tableSizeBytes?: number;
  }>;
}

export interface TableDetailVO {
  connectionId: number;
  tableName: string;
  tableComment?: string;
  columns: Array<{
    columnName: string;
    dataType?: string;
    columnSize?: number;
    decimalDigits?: number;
    defaultValue?: string;
    autoIncrement?: boolean;
    nullable?: boolean;
    columnComment?: string;
    indexed?: boolean;
    primaryKey?: boolean;
    defaultCurrentTimestamp?: boolean;
    onUpdateCurrentTimestamp?: boolean;
  }>;
  indexes?: Array<{
    indexName: string;
    unique?: boolean;
    columns: string[];
  }>;
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

export type TableDataSortDirection = 'ASC' | 'DESC';

export interface TableDataPageReq {
  connectionId: number;
  databaseName: string;
  tableName: string;
  pageNo: number;
  pageSize: number;
  filters: Array<{
    columnName: string;
    operator: TableDataFilterOperator;
    value?: string;
  }>;
  sorts: Array<{
    columnName: string;
    direction: TableDataSortDirection;
  }>;
}

export interface TableDataPageVO {
  tableName: string;
  editable: boolean;
  readOnlyReason?: string;
  columns: Array<{
    columnName: string;
    columnType?: string;
    columnComment?: string;
    nullable?: boolean;
    primaryKey?: boolean;
  }>;
  primaryKeyColumns: string[];
  rows: Array<{
    rowKey: string;
    cells: Array<{
      columnName: string;
      cellValue: string | null;
    }>;
  }>;
  pageNo: number;
  pageSize: number;
  hasNext: boolean;
}

export interface TableDataCommitReq {
  connectionId: number;
  databaseName: string;
  tableName: string;
  inserts: Array<{
    cells: Array<{
      columnName: string;
      cellValue: string | null;
    }>;
  }>;
  updates: Array<{
    primaryKeyValues: Array<{
      columnName: string;
      cellValue: string | null;
    }>;
    cells: Array<{
      columnName: string;
      cellValue: string | null;
    }>;
  }>;
  deletes: Array<{
    primaryKeyValues: Array<{
      columnName: string;
      cellValue: string | null;
    }>;
  }>;
}

export interface TableDataCommitVO {
  success: boolean;
  message: string;
  insertedCount: number;
  updatedCount: number;
  deletedCount: number;
}

export interface ErGraphReq {
  connectionId: number;
  databaseName: string;
  tableNames: string[];
  modelName?: string;
  includeAiInference?: boolean;
  aiConfidenceThreshold?: number;
}

export type ErLayoutMode = 'GRID' | 'CIRCLE' | 'HIERARCHICAL';

export interface ErColumnNodeVO {
  columnName: string;
  dataType?: string;
  columnComment?: string;
  primaryKey?: boolean;
  indexed?: boolean;
  nullable?: boolean;
}

export interface ErTableNodeVO {
  tableName: string;
  tableComment?: string;
  columns: ErColumnNodeVO[];
}

export interface ErRelationVO {
  sourceTable: string;
  sourceColumn: string;
  targetTable: string;
  targetColumn: string;
  relationType: 'FK' | 'AI_INFERRED' | string;
  relationDirection?: 'SOURCE_TO_TARGET' | 'TARGET_TO_SOURCE' | 'BIDIRECTIONAL' | string;
  confidence?: number;
  reason?: string;
  routeManual?: boolean;
  routeLaneX?: number;
  routeVersion?: number;
}

export interface ErAiInferenceStatusVO {
  requested: boolean;
  success: boolean;
  message?: string;
}

export interface ErGraphVO {
  connectionId: number;
  databaseName: string;
  tables: ErTableNodeVO[];
  foreignKeyRelations: ErRelationVO[];
  aiRelations: ErRelationVO[];
  aiInference: ErAiInferenceStatusVO;
  generatedAt: number;
}

export interface AiTraceFieldVO {
  fieldCode: string;
  fieldLabel: string;
  fieldValue: string;
}

export interface AiTraceLlmCallVO {
  modelId?: string;
  providerType?: string;
  providerName?: string;
  actualModel?: string;
  systemPrompt?: string;
  userPrompt?: string;
  fullOutput?: string;
  thinkingContent?: string;
  providerRequestId?: string;
  streaming?: boolean;
  promptTokens?: number;
  completionTokens?: number;
  totalTokens?: number;
}

export interface AiTraceStageVO {
  stageCode: string;
  stageLabel: string;
  stageType: string;
  status: string;
  durationMs?: number;
  inputFields?: AiTraceFieldVO[];
  outputFields?: AiTraceFieldVO[];
  llmCall?: AiTraceLlmCallVO;
}

export interface AiTraceVO {
  stageCount?: number;
  totalDurationMs?: number;
  stages?: AiTraceStageVO[];
}

export interface AiGenerateSqlVO {
  sqlText: string;
  reasoning: string;
  fallbackUsed: boolean;
  promptTokens?: number;
  completionTokens?: number;
  totalTokens?: number;
  trace?: AiTraceVO;
}

export interface AiTextResponseVO {
  content: string;
  reasoning: string;
  fallbackUsed: boolean;
  promptTokens?: number;
  completionTokens?: number;
  totalTokens?: number;
  trace?: AiTraceVO;
}

export interface AiRepairVO {
  repairedSql: string;
  repaired: boolean;
  repairNote: string;
  errorExplanation?: string;
  trace?: AiTraceVO;
}

export interface AiStreamDeltaVO {
  channel: 'thinking' | 'output' | string;
  deltaText?: string;
  accumulatedText?: string;
}

export interface AiStreamIntentVO {
  intentType?: AiIntentType | string;
  intentLabel?: string;
  intentConfidence?: number;
  reasoning?: string;
}

export interface AiStreamErrorVO {
  code?: number;
  message?: string;
}

export interface AiStreamFinalVO {
  actionType?: string;
  generateSql?: AiGenerateSqlVO;
  autoQuery?: AiAutoQueryVO;
  textResponse?: AiTextResponseVO;
  generateChart?: AiGenerateChartVO;
  repair?: AiRepairVO;
}

export interface AiStreamEventVO {
  eventType: string;
  sessionId?: string;
  actionType?: string;
  sequence?: number;
  timestamp?: number;
  delta?: AiStreamDeltaVO;
  finalResult?: AiStreamFinalVO;
  error?: AiStreamErrorVO;
  intent?: AiStreamIntentVO;
  stage?: AiTraceStageVO;
  trace?: AiTraceVO;
}

export interface ExplainVO {
  rows: QueryRowVO[];
  summary: string;
}

export interface QueryHistoryVO {
  id: number;
  connectionId: number;
  sessionId?: string;
  promptText?: string;
  sqlText: string;
  historyType?: 'CHAT' | 'EXECUTE';
  actionType?: string;
  assistantContent?: string;
  databaseName?: string;
  chartConfig?: ChartConfigVO;
  chartImageCacheKey?: string;
  structuredContextJson?: string;
  traceJson?: string;
  trace?: AiTraceVO;
  tokenEstimate?: number;
  memoryEnabled?: boolean;
  executionMs?: number;
  success?: boolean;
  createdAt?: number;
}

export interface SavedQueryVO {
  id: number;
  connectionId: number;
  databaseName?: string;
  title: string;
  sqlText: string;
  createdAt?: number;
  updatedAt?: number;
}

export interface SavedQuerySaveReq {
  connectionId: number;
  databaseName?: string;
  title: string;
  sqlText: string;
}

export type KnowledgeScope = 'GLOBAL' | 'CONNECTION' | 'DATABASE';

export interface KnowledgeTermVO {
  id: number;
  scope: KnowledgeScope;
  connectionId?: number;
  databaseName?: string;
  term: string;
  description?: string;
  createdAt?: number;
  updatedAt?: number;
}

export interface KnowledgeTermSaveReq {
  id?: number;
  scope: KnowledgeScope;
  connectionId?: number;
  databaseName?: string;
  term: string;
  description?: string;
}

export interface KnowledgeExampleSqlVO {
  id: number;
  scope: KnowledgeScope;
  connectionId?: number;
  databaseName?: string;
  sqlText: string;
  description?: string;
  termIds: number[];
  createdAt?: number;
  updatedAt?: number;
}

export interface KnowledgeExampleSqlSaveReq {
  id?: number;
  scope: KnowledgeScope;
  connectionId?: number;
  databaseName?: string;
  sqlText: string;
  description?: string;
  termIds: number[];
}

export interface KnowledgeVectorRebuildVO {
  termCount: number;
  exampleCount: number;
  rebuiltAt?: number;
  message?: string;
}

export interface ChartCacheSaveReq {
  connectionId: number;
  sessionId: string;
  imageBase64Png: string;
  suggestedFileName?: string;
  width?: number;
  height?: number;
}

export interface QueryHistorySessionVO {
  connectionId: number;
  sessionId: string;
  title: string;
  createdAt?: number;
  updatedAt?: number;
  messageCount?: number;
  totalTokens?: number;
}

export interface QueryHistorySessionPageVO {
  pageNo: number;
  pageSize: number;
  total: number;
  hasMore: boolean;
  items: QueryHistorySessionVO[];
}

export interface ErGraphSnapshotSaveReq {
  snapshotId?: number;
  connectionId: number;
  databaseName: string;
  snapshotName: string;
  selectedTableNames: string[];
  modelName?: string;
  layoutMode?: ErLayoutMode;
  aiConfidenceThreshold?: number;
  includeAiInference?: boolean;
  graph: ErGraphVO;
}

export interface ErGraphSnapshotRenameReq {
  connectionId: number;
  snapshotId: number;
  snapshotName: string;
}

export interface ErGraphSnapshotRemoveReq {
  connectionId: number;
  snapshotId: number;
}

export interface ErGraphSnapshotSummaryVO {
  id: number;
  connectionId: number;
  databaseName: string;
  snapshotName: string;
  tableCount?: number;
  modelName?: string;
  createdAt?: number;
  updatedAt?: number;
}

export interface ErGraphSnapshotPageVO {
  pageNo: number;
  pageSize: number;
  total: number;
  hasMore: boolean;
  items: ErGraphSnapshotSummaryVO[];
}

export interface ErGraphSnapshotVO {
  id: number;
  connectionId: number;
  databaseName: string;
  snapshotName: string;
  selectedTableNames: string[];
  modelName?: string;
  layoutMode?: ErLayoutMode;
  aiConfidenceThreshold?: number;
  includeAiInference?: boolean;
  graph: ErGraphVO;
  createdAt?: number;
  updatedAt?: number;
}

export interface AiConfigVO {
  providerType: 'OPENAI' | 'LOCAL_CLI';
  openaiBaseUrl?: string;
  openaiApiKey?: string;
  openaiModel?: string;
  cliCommand?: string;
  cliWorkingDir?: string;
  modelOptions?: AiModelOption[];
  conversationMemoryEnabled?: boolean;
  conversationMemoryWindowSize?: number;
  conversationMemoryWindowTokens?: number;
  conversationAutoCompressRatio?: number;
  detailOutputEnabled?: boolean;
  updatedAt?: number;
}

export interface AiConfigSaveReq {
  providerType: 'OPENAI' | 'LOCAL_CLI';
  openaiBaseUrl?: string;
  openaiApiKey?: string;
  openaiModel?: string;
  cliCommand?: string;
  cliWorkingDir?: string;
  modelOptions?: AiModelOption[];
  conversationMemoryEnabled?: boolean;
  conversationMemoryWindowSize?: number;
  conversationMemoryWindowTokens?: number;
  conversationAutoCompressRatio?: number;
  detailOutputEnabled?: boolean;
}

export interface AiModelOption {
  id: string;
  name: string;
  providerType: 'OPENAI' | 'LOCAL_CLI';
  openaiBaseUrl?: string;
  openaiApiKey?: string;
  openaiModel?: string;
  cliCommand?: string;
  cliWorkingDir?: string;
}

export interface RagConfigVO {
  ragEmbeddingProviderType?: 'LOCAL_ONNX' | 'ONLINE_OPENAI_COMPAT';
  ragEmbeddingModelDir?: string;
  ragEmbeddingOnlineBaseUrl?: string;
  ragEmbeddingOnlineApiKey?: string;
  ragEmbeddingOnlineModel?: string;
  ragRerankEnabled?: boolean;
  ragRerankProviderType?: 'LOCAL_ONNX' | 'ONLINE_OPENAI_COMPAT';
  ragRerankModelDir?: string;
  ragRerankOnlineBaseUrl?: string;
  ragRerankOnlineApiKey?: string;
  ragRerankOnlineModel?: string;
  updatedAt?: number;
}

export interface RagConfigSaveReq {
  ragEmbeddingProviderType?: 'LOCAL_ONNX' | 'ONLINE_OPENAI_COMPAT';
  ragEmbeddingModelDir?: string;
  ragEmbeddingOnlineBaseUrl?: string;
  ragEmbeddingOnlineApiKey?: string;
  ragEmbeddingOnlineModel?: string;
  ragRerankEnabled?: boolean;
  ragRerankProviderType?: 'LOCAL_ONNX' | 'ONLINE_OPENAI_COMPAT';
  ragRerankModelDir?: string;
  ragRerankOnlineBaseUrl?: string;
  ragRerankOnlineApiKey?: string;
  ragRerankOnlineModel?: string;
}

export interface RagVectorizeEnqueueReq {
  connectionId: number;
  databaseName: string;
}

export interface RagVectorizeEnqueueVO {
  enqueued: boolean;
  queueSize: number;
  message: string;
}

export interface RagVectorizeInterruptReq {
  connectionId: number;
  databaseName: string;
}

export interface RagVectorizeInterruptVO {
  interrupted: boolean;
  status: 'NOT_VECTORIZED' | 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED';
  message: string;
  updatedAt?: number;
}

export interface RagVectorizeTableReq {
  connectionId: number;
  databaseName: string;
  tableName: string;
}

export interface RagVectorizeTableVO {
  success: boolean;
  databaseName: string;
  tableName: string;
  message: string;
  updatedAt?: number;
}

export interface RagDatabaseVectorizeStatusVO {
  databaseName: string;
  status: 'NOT_VECTORIZED' | 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED';
  message?: string;
  updatedAt?: number;
}

export interface RagVectorizeOverviewVO {
  databaseName: string;
  status: 'NOT_VECTORIZED' | 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED';
  message?: string;
  updatedAt?: number;
  totalVectorCount: number;
  schemaTableVectorCount: number;
  schemaColumnVectorCount: number;
  sqlHistoryVectorCount: number;
  sqlFragmentVectorCount: number;
  metricTermVectorCount: number;
  exampleSqlVectorCount: number;
  globalVectorCount: number;
  globalMetricTermVectorCount: number;
  globalExampleSqlVectorCount: number;
  vectorDimension?: number;
  lastFullVectorizeDurationMs?: number;
  lastFullVectorizeProvider?: string;
}
