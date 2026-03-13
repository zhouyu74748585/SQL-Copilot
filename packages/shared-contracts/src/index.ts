export interface ApiResponse<T> {
  code: number;
  message: string;
  data: T;
}

export interface ConnectionVO {
  id: number;
  name: string;
  dbType: string;
  host?: string;
  port?: number;
  databaseName?: string;
  selectedDatabases?: string[];
  username?: string;
  env: string;
  readOnly: boolean;
  sshEnabled: boolean;
  sshHost?: string;
  sshPort?: number;
  sshUser?: string;
  sshAuthType?: string;
  sshPrivateKeyPath?: string;
  sshPasswordConfigured?: boolean;
  sshPrivateKeyTextConfigured?: boolean;
  sshPrivateKeyPassphraseConfigured?: boolean;
  lastTestStatus?: string;
  lastTestMessage?: string;
  riskPolicySummary?: string;
}

export interface RiskItemVO {
  ruleCode: string;
  description: string;
  level: 'LOW' | 'MEDIUM' | 'HIGH';
}

export interface RiskEvaluateVO {
  riskLevel: 'LOW' | 'MEDIUM' | 'HIGH';
  riskItems: RiskItemVO[];
  confirmRequired: boolean;
  confirmReason?: string;
  riskAckToken?: string;
}

export interface QueryCellVO {
  columnName: string;
  cellValue: string | null;
}

export interface QueryRowVO {
  cells: QueryCellVO[];
}

export interface SqlExecuteVO {
  success: boolean;
  affectedRows: number;
  executionMs: number;
  rows: QueryRowVO[];
  message: string;
}

export type ChartType = 'LINE' | 'BAR' | 'PIE' | 'SCATTER' | 'TREND';

export type SortDirection = 'NONE' | 'ASC' | 'DESC';

export interface ChartConfigVO {
  chartType: ChartType;
  xField?: string;
  yFields?: string[];
  seriesField?: string;
  categoryField?: string;
  valueField?: string;
  sortField?: string;
  sortDirection?: SortDirection;
  title?: string;
  description?: string;
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

export interface AiGenerateChartVO {
  sqlText: string;
  chartConfig?: ChartConfigVO;
  configSummary: string;
  reasoning: string;
  fallbackUsed: boolean;
  promptTokens?: number;
  completionTokens?: number;
  totalTokens?: number;
  trace?: AiTraceVO;
}

export type AiIntentType = 'GENERATE_SQL' | 'EXPLAIN_SQL' | 'ANALYZE_SQL' | 'GENERATE_CHART';

export interface AiAutoQueryVO {
  intentType: AiIntentType;
  intentLabel: string;
  intentConfidence: number;
  reasoning: string;
  fallbackUsed: boolean;
  totalTokens?: number;
  sqlText?: string;
  content?: string;
  chartConfig?: ChartConfigVO;
  configSummary?: string;
  trace?: AiTraceVO;
}

export interface ChartCacheSaveVO {
  cacheKey: string;
  filePath: string;
  width: number;
  height: number;
}

export interface ChartCacheReadVO {
  cacheKey: string;
  dataUrl: string;
}
