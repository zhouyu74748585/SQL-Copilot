import type {QueryRowVO, RiskEvaluateVO, SqlExecuteVO} from '@sqlcopilot/shared-contracts';

export type { QueryRowVO, RiskEvaluateVO, SqlExecuteVO };

export interface ConnectionCreateReq {
  name: string;
  dbType: string;
  host?: string;
  port?: number;
  databaseName?: string;
  username?: string;
  password?: string;
  authType?: string;
  env: string;
  readOnly: boolean;
  sshEnabled: boolean;
  sshHost?: string;
  sshPort?: number;
  sshUser?: string;
}

export interface SchemaOverviewVO {
  connectionId: number;
  tableCount: number;
  columnCount: number;
  tableSummaries: Array<{
    tableName: string;
    tableComment?: string;
    rowEstimate?: number;
    tableSizeBytes?: number;
  }>;
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
  executionMs?: number;
  success?: boolean;
  createdAt?: number;
}

export interface AiConfigVO {
  providerType: 'OPENAI' | 'LOCAL_CLI';
  openaiBaseUrl?: string;
  openaiApiKey?: string;
  openaiModel?: string;
  cliCommand?: string;
  cliArgs?: string;
  cliWorkingDir?: string;
  updatedAt?: number;
}

export interface AiConfigSaveReq {
  providerType: 'OPENAI' | 'LOCAL_CLI';
  openaiBaseUrl?: string;
  openaiApiKey?: string;
  openaiModel?: string;
  cliCommand?: string;
  cliArgs?: string;
  cliWorkingDir?: string;
}
