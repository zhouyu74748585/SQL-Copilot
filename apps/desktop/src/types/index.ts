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

export interface TableDetailVO {
  connectionId: number;
  tableName: string;
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
  }>;
}

export interface AiGenerateSqlVO {
  sqlText: string;
  reasoning: string;
  fallbackUsed: boolean;
}

export interface AiTextResponseVO {
  content: string;
  reasoning: string;
  fallbackUsed: boolean;
}

export interface AiRepairVO {
  repairedSql: string;
  repaired: boolean;
  repairNote: string;
  errorExplanation?: string;
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
  executionMs?: number;
  success?: boolean;
  createdAt?: number;
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
}

export interface QueryHistorySessionPageVO {
  pageNo: number;
  pageSize: number;
  total: number;
  hasMore: boolean;
  items: QueryHistorySessionVO[];
}

export interface AiConfigVO {
  providerType: 'OPENAI' | 'LOCAL_CLI';
  openaiBaseUrl?: string;
  openaiApiKey?: string;
  openaiModel?: string;
  cliCommand?: string;
  cliWorkingDir?: string;
  modelOptions?: AiModelOption[];
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
  ragEmbeddingModelDir?: string;
  ragEmbeddingModelFileName?: string;
  ragEmbeddingModelDataFileName?: string;
  ragEmbeddingTokenizerFileName?: string;
  ragEmbeddingTokenizerConfigFileName?: string;
  ragEmbeddingConfigFileName?: string;
  ragEmbeddingSpecialTokensFileName?: string;
  ragEmbeddingSentencepieceFileName?: string;
  ragEmbeddingModelPath?: string;
  ragEmbeddingModelDataPath?: string;
  updatedAt?: number;
}

export interface RagConfigSaveReq {
  ragEmbeddingModelDir?: string;
  ragEmbeddingModelFileName?: string;
  ragEmbeddingModelDataFileName?: string;
  ragEmbeddingTokenizerFileName?: string;
  ragEmbeddingTokenizerConfigFileName?: string;
  ragEmbeddingConfigFileName?: string;
  ragEmbeddingSpecialTokensFileName?: string;
  ragEmbeddingSentencepieceFileName?: string;
  ragEmbeddingModelPath?: string;
  ragEmbeddingModelDataPath?: string;
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
  vectorDimension?: number;
}
