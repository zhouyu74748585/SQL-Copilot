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
  username?: string;
  env: string;
  readOnly: boolean;
  sshEnabled: boolean;
  sshHost?: string;
  sshPort?: number;
  sshUser?: string;
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
