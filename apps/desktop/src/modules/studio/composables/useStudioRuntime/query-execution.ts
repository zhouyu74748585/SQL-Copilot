import type {Ref} from 'vue';
import {h} from 'vue';
import type {ConnectionVO} from '@sqlcopilot/shared-contracts';
import {message, Modal} from 'ant-design-vue';
import {postApi} from '../../../../api/client';
import type {ExplainVO, RiskEvaluateVO, SqlExecuteVO} from '../../../../types';
import {
  clearStatementResults,
  createStatementResult,
  getActiveStatementResultForTab,
  setActiveStatementResult,
  syncActiveStatementResultFromTab,
} from './charts';
import {QUERY_RESULT_MAX_ROWS, RISK_EXECUTION_CANCELLED, SQL_EXECUTION_ABORTED} from './constants';
import type {DesktopBridge, QueryStatementResult, QueryWorkspaceTab, RequestAbortReason} from './types';

export function resolveSqlForAction(tab: QueryWorkspaceTab, sqlOverride?: string) {
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

function normalizeSqlStatement(text: string) {
  return text.trim().replace(/;+$/g, '').trim();
}

export function splitSqlStatements(sourceSql: string) {
  const sqlText = sourceSql || '';
  const statements: string[] = [];
  let current = '';
  let inSingleQuote = false;
  let inDoubleQuote = false;
  let inBacktickQuote = false;
  let inLineComment = false;
  let inBlockComment = false;
  const pushCurrent = () => {
    const normalized = normalizeSqlStatement(current);
    if (normalized) {
      statements.push(normalized);
    }
    current = '';
  };
  for (let index = 0; index < sqlText.length; index += 1) {
    const char = sqlText[index];
    const next = sqlText[index + 1] || '';
    current += char;
    if (inLineComment) {
      if (char === '\n') {
        inLineComment = false;
      }
      continue;
    }
    if (inBlockComment) {
      if (char === '*' && next === '/') {
        current += next;
        index += 1;
        inBlockComment = false;
      }
      continue;
    }
    if (inSingleQuote) {
      if (char === '\\' && next) {
        current += next;
        index += 1;
        continue;
      }
      if (char === '\'' && next === '\'') {
        current += next;
        index += 1;
        continue;
      }
      if (char === '\'') {
        inSingleQuote = false;
      }
      continue;
    }
    if (inDoubleQuote) {
      if (char === '\\' && next) {
        current += next;
        index += 1;
        continue;
      }
      if (char === '"' && next === '"') {
        current += next;
        index += 1;
        continue;
      }
      if (char === '"') {
        inDoubleQuote = false;
      }
      continue;
    }
    if (inBacktickQuote) {
      if (char === '`') {
        inBacktickQuote = false;
      }
      continue;
    }
    if (char === '-' && next === '-') {
      current += next;
      index += 1;
      inLineComment = true;
      continue;
    }
    if (char === '#') {
      inLineComment = true;
      continue;
    }
    if (char === '/' && next === '*') {
      current += next;
      index += 1;
      inBlockComment = true;
      continue;
    }
    if (char === '\'') {
      inSingleQuote = true;
      continue;
    }
    if (char === '"') {
      inDoubleQuote = true;
      continue;
    }
    if (char === '`') {
      inBacktickQuote = true;
      continue;
    }
    if (char === ';') {
      pushCurrent();
    }
  }
  pushCurrent();
  return statements;
}

export function normalizeRiskLevel(level?: string): 'LOW' | 'MEDIUM' | 'HIGH' {
  if (level === 'HIGH' || level === 'MEDIUM' || level === 'LOW') {
    return level;
  }
  return 'LOW';
}

export function riskColor(level: string) {
  const normalizedLevel = normalizeRiskLevel(level);
  if (normalizedLevel === 'HIGH') {
    return 'red';
  }
  if (normalizedLevel === 'MEDIUM') {
    return 'orange';
  }
  return 'green';
}

interface QueryExecutionHelperContext {
  connections: Ref<ConnectionVO[]>;
  downloadActiveChart: (tab: QueryWorkspaceTab) => Promise<void>;
  getDesktopBridge: () => DesktopBridge | null;
  isAbortError: (error: unknown) => boolean;
  isKvDbType: (dbType: string) => boolean;
  loadLastResultExportDirectory: () => string;
  queryTabDbType: (tab: QueryWorkspaceTab) => string;
  rebuildQueryResultTableCache: (tab: QueryWorkspaceTab) => void;
  resolveQueryDatabaseName: (tab: QueryWorkspaceTab | null) => string;
  runSafely: (task: () => Promise<void>) => Promise<void>;
  saveLastResultExportDirectory: (directory: string) => void;
  setupManualChartConfigByResult: (tab: QueryWorkspaceTab) => void;
  sqlExecutionAbortControllerMap: Map<string, AbortController>;
  sqlExecutionAbortReasonMap: Map<string, RequestAbortReason>;
  touchQueryTab: (tab: QueryWorkspaceTab) => void;
}

export function createQueryExecutionHelpers(ctx: QueryExecutionHelperContext) {
  function connectionEnvLabel(connectionId: number) {
    const env = ctx.connections.value.find((item) => item.id === connectionId)?.env ?? 'DEV';
    return env.toUpperCase();
  }

  async function explainSqlForTab(tab: QueryWorkspaceTab, sqlOverride?: string) {
    await ctx.runSafely(async () => {
      const sqlText = resolveSqlForAction(tab, sqlOverride);
      if (!sqlText) {
        throw new Error('请先输入或选择 SQL');
      }
      clearStatementResults(tab);
      tab.explainResult = await postApi<ExplainVO>('/api/sql/explain', {
        connectionId: tab.connectionId,
        sqlText,
        databaseName: tab.databaseName || undefined,
      });
      tab.executeResult = null;
      ctx.rebuildQueryResultTableCache(tab);
      ctx.touchQueryTab(tab);
      message.success('EXPLAIN 完成');
    });
  }

  async function ensureRiskConfirmedBeforeExecute(tab: QueryWorkspaceTab, sqlText: string, signal?: AbortSignal) {
    const result = await postApi<RiskEvaluateVO>('/api/sql/risk/evaluate', {
      connectionId: tab.connectionId,
      databaseName: tab.databaseName || undefined,
      sqlText,
    }, {
      signal,
    });
    if (signal?.aborted) {
      throw new Error(SQL_EXECUTION_ABORTED);
    }
    tab.riskInfo = result;
    ctx.touchQueryTab(tab);
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
        content: h('div', {class: ['risk-confirm-content', confirmLevelClass]}, [
          h('div', {class: 'risk-confirm-level-row'}, [
            h('span', '风险级别'),
            h('span', {class: 'risk-confirm-level-badge'}, normalizedRiskLevel),
          ]),
          h('div', `确认策略: ${result.confirmReason || '-'}`),
          h('pre', {class: 'risk-confirm-pre'}, riskItemsText),
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

  async function executeSingleSqlStatementForTab(
    tab: QueryWorkspaceTab,
    sqlText: string,
    controller: AbortController,
  ) {
    if (ctx.isKvDbType(ctx.queryTabDbType(tab))) {
      try {
        const result = await postApi<SqlExecuteVO>('/api/kv/query/execute', {
          connectionId: tab.connectionId,
          sessionId: tab.sessionId,
          queryText: sqlText,
          databaseName: tab.databaseName || undefined,
          maxRows: QUERY_RESULT_MAX_ROWS,
        }, {
          signal: controller.signal,
        });
        tab.executeResult = result;
        tab.explainResult = null;
        ctx.rebuildQueryResultTableCache(tab);
        tab.riskAckToken = '';
        tab.lastExecuteFailed = false;
        tab.lastExecuteErrorMessage = '';
        tab.lastFailedSqlText = '';
        tab.resultViewMode = 'table';
        tab.chartReadonly = false;
        tab.chartImageDataUrl = '';
        tab.chartImageCacheKey = '';
        return {success: true, aborted: false, cancelled: false, errorMessage: ''};
      } catch (error) {
        const manualAborted = ctx.sqlExecutionAbortReasonMap.get(tab.key) === 'manual' || ctx.isAbortError(error);
        if (manualAborted) {
          tab.riskAckToken = '';
          tab.lastExecuteFailed = false;
          tab.lastExecuteErrorMessage = '';
          tab.lastFailedSqlText = '';
          return {success: false, aborted: true, cancelled: false, errorMessage: ''};
        }
        const errMsg = error instanceof Error ? error.message : String(error);
        tab.executeResult = null;
        tab.explainResult = null;
        ctx.rebuildQueryResultTableCache(tab);
        tab.riskAckToken = '';
        tab.lastExecuteFailed = true;
        tab.lastExecuteErrorMessage = errMsg;
        tab.lastFailedSqlText = sqlText;
        return {success: false, aborted: false, cancelled: false, errorMessage: errMsg};
      }
    }

    let riskAckToken = '';
    try {
      riskAckToken = await ensureRiskConfirmedBeforeExecute(tab, sqlText, controller.signal);
    } catch (error) {
      const manualAborted = ctx.sqlExecutionAbortReasonMap.get(tab.key) === 'manual' || ctx.isAbortError(error);
      if (manualAborted) {
        return {success: false, aborted: true, cancelled: false, errorMessage: ''};
      }
      const errMsg = error instanceof Error ? error.message : String(error);
      if (errMsg === RISK_EXECUTION_CANCELLED) {
        return {success: false, aborted: false, cancelled: true, errorMessage: errMsg};
      }
      message.error(errMsg);
      return {success: false, aborted: false, cancelled: false, errorMessage: errMsg};
    }

    try {
      tab.riskAckToken = riskAckToken;
      const result = await postApi<SqlExecuteVO>('/api/sql/execute', {
        connectionId: tab.connectionId,
        sessionId: tab.sessionId,
        sqlText,
        databaseName: tab.databaseName || undefined,
        maxRows: QUERY_RESULT_MAX_ROWS,
        memoryEnabled: tab.sqlMemoryEnabled,
        riskAckToken: riskAckToken || undefined,
        operatorName: 'desktop-user',
      }, {
        signal: controller.signal,
      });
      tab.executeResult = result;
      tab.explainResult = null;
      ctx.rebuildQueryResultTableCache(tab);
      tab.riskAckToken = '';
      tab.lastExecuteFailed = false;
      tab.lastExecuteErrorMessage = '';
      tab.lastFailedSqlText = '';
      tab.resultViewMode = 'table';
      tab.chartReadonly = false;
      tab.chartImageDataUrl = '';
      tab.chartImageCacheKey = '';
      ctx.setupManualChartConfigByResult(tab);
      return {success: true, aborted: false, cancelled: false, errorMessage: ''};
    } catch (error) {
      const manualAborted = ctx.sqlExecutionAbortReasonMap.get(tab.key) === 'manual' || ctx.isAbortError(error);
      if (manualAborted) {
        tab.riskAckToken = '';
        tab.lastExecuteFailed = false;
        tab.lastExecuteErrorMessage = '';
        tab.lastFailedSqlText = '';
        return {success: false, aborted: true, cancelled: false, errorMessage: ''};
      }
      const errMsg = error instanceof Error ? error.message : String(error);
      tab.executeResult = null;
      tab.explainResult = null;
      ctx.rebuildQueryResultTableCache(tab);
      tab.riskAckToken = '';
      tab.lastExecuteFailed = true;
      tab.lastExecuteErrorMessage = errMsg;
      tab.lastFailedSqlText = sqlText;
      return {success: false, aborted: false, cancelled: false, errorMessage: errMsg};
    }
  }

  async function executeSqlForTab(
    tab: QueryWorkspaceTab,
    sqlOverride?: string,
    options?: {silentSuccess?: boolean},
  ) {
    if (tab.sqlExecuting) {
      return false;
    }
    const sqlText = resolveSqlForAction(tab, sqlOverride);
    if (!sqlText) {
      message.error(ctx.isKvDbType(ctx.queryTabDbType(tab)) ? '请先输入查询文本' : '请先输入或选择 SQL');
      return false;
    }
    const statements = ctx.isKvDbType(ctx.queryTabDbType(tab)) ? [sqlText.trim()] : splitSqlStatements(sqlText);
    if (!statements.length) {
      message.error(ctx.isKvDbType(ctx.queryTabDbType(tab)) ? '请先输入查询文本' : '请先输入或选择 SQL');
      return false;
    }
    clearStatementResults(tab);
    tab.riskInfo = null;
    tab.sqlExecuting = true;
    const controller = new AbortController();
    ctx.sqlExecutionAbortControllerMap.set(tab.key, controller);
    ctx.sqlExecutionAbortReasonMap.delete(tab.key);
    ctx.touchQueryTab(tab);
    let successCount = 0;
    let failureCount = 0;
    let cancelled = false;
    let aborted = false;
    try {
      for (let index = 0; index < statements.length; index += 1) {
        const statementSql = statements[index];
        const statementResult = createStatementResult(index + 1, statementSql);
        tab.statementResults = [...tab.statementResults, statementResult];
        tab.executingStatementIndex = index + 1;
        setActiveStatementResult(tab, statementResult.key);
        tab.riskInfo = null;
        ctx.touchQueryTab(tab);

        const execution = await executeSingleSqlStatementForTab(tab, statementSql, controller);
        statementResult.status = execution.success ? 'success' : 'error';
        syncActiveStatementResultFromTab(tab);

        if (execution.aborted) {
          aborted = true;
          break;
        }
        if (execution.cancelled) {
          cancelled = true;
          break;
        }
        if (execution.success) {
          successCount += 1;
        } else {
          failureCount += 1;
        }
      }
    } finally {
      if (ctx.sqlExecutionAbortControllerMap.get(tab.key) === controller) {
        ctx.sqlExecutionAbortControllerMap.delete(tab.key);
      }
      ctx.sqlExecutionAbortReasonMap.delete(tab.key);
      tab.executingStatementIndex = null;
      tab.sqlExecuting = false;
      ctx.touchQueryTab(tab);
    }
    if (aborted) {
      message.info('Execution was stopped.');
      return successCount > 0;
    }
    if (cancelled) {
      message.info('Execution cancelled.');
      return successCount > 0;
    }
    if (!options?.silentSuccess) {
      if (statements.length === 1 && successCount === 1 && tab.executeResult) {
        message.success(`执行成功，耗时 ${tab.executeResult.executionMs}ms`);
      } else if (statements.length > 1) {
        if (failureCount === 0) {
          message.success(`共执行 ${successCount} 条 SQL，全部成功`);
        } else if (successCount > 0) {
          message.warning(`共执行 ${statements.length} 条 SQL，成功 ${successCount} 条，失败 ${failureCount} 条`);
        } else {
          message.error(`共执行 ${statements.length} 条 SQL，全部失败`);
        }
      }
    }
    return successCount > 0;
  }

  async function exportCsvForTab(tab: QueryWorkspaceTab, statementResult?: QueryStatementResult | null) {
    await ctx.runSafely(async () => {
      const exportSql = statementResult?.sqlText || getActiveStatementResultForTab(tab)?.sqlText || resolveSqlForAction(tab);
      if (!exportSql) {
        throw new Error('当前没有可导出的 SQL');
      }
      const bridge = ctx.getDesktopBridge();
      if (!bridge || typeof bridge.pickDirectory !== 'function') {
        throw new Error('当前环境不支持选择导出目录，请在桌面端中使用导出功能');
      }
      const exportDirectory = await bridge.pickDirectory({
        title: '选择导出目录',
        defaultPath: ctx.loadLastResultExportDirectory() || undefined,
      });
      if (!exportDirectory) {
        return;
      }
      ctx.saveLastResultExportDirectory(exportDirectory);
      const result = await postApi<{filePath: string}>('/api/editor/result/export', {
        connectionId: tab.connectionId,
        databaseName: ctx.resolveQueryDatabaseName(tab) || undefined,
        sqlText: exportSql,
        format: 'CSV',
        fileName: `aidb_${Date.now()}`,
        exportDirectory,
      });
      message.success(`已导出 ${result.filePath}`);
    });
  }

  async function exportResultTab(tab: QueryWorkspaceTab, statementResult?: QueryStatementResult | null) {
    if (tab.resultViewMode === 'chart') {
      await ctx.downloadActiveChart(tab);
      return;
    }
    await exportCsvForTab(tab, statementResult);
  }

  async function exportActiveQueryResult(tab: QueryWorkspaceTab) {
    await exportResultTab(tab, getActiveStatementResultForTab(tab));
  }

  return {
    connectionEnvLabel,
    ensureRiskConfirmedBeforeExecute,
    executeSqlForTab,
    explainSqlForTab,
    exportActiveQueryResult,
    exportCsvForTab,
    exportResultTab,
  };
}
