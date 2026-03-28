import {message} from 'ant-design-vue';
import {nextTick} from 'vue';
import {getApi, postApi} from '../../../../api/client';
import type {ChartCacheReadVO, ChartCacheSaveReq, ChartCacheSaveVO,} from '../../../../types';
import type {ChartConfigVO, ChartType, SortDirection} from '../../../../types';
import {chartExportPixelRatioCandidates} from './constants';
import type {
  DesktopBridge,
  QueryChatMessage,
  QueryExecutionPreview,
  QueryResultTableColumn,
  QueryResultTableRow,
  QueryResultViewMode,
  QueryStatementResult,
  QueryWorkspaceTab,
} from './types';

export function clampGridColumnWidth(width: number) {
  const resolved = Number(width || 0);
  if (!Number.isFinite(resolved)) {
    return 180;
  }
  return Math.min(640, Math.max(80, Math.round(resolved)));
}

export function buildColumnWidthMapFromColumns(columns: QueryResultTableColumn[]) {
  return columns.reduce<Record<string, number>>((acc, column) => {
    acc[column.key] = clampGridColumnWidth(column.width);
    return acc;
  }, {});
}

export function buildResultTableCache(
  rows: Array<{cells: Array<{columnName: string; cellValue: string | null}>}>,
  columnWidthMap: Record<string, number> = {},
) {
  if (!rows.length) {
    return {
      rows: [] as QueryResultTableRow[],
      columns: [] as QueryResultTableColumn[],
    };
  }
  const columns = rows[0].cells.map((cell) => ({
    title: cell.columnName,
    dataIndex: cell.columnName,
    key: cell.columnName,
    width: clampGridColumnWidth(columnWidthMap[cell.columnName] ?? 180),
    ellipsis: true,
  }));
  const normalizedRows = rows.map((row, index) => {
    const result: QueryResultTableRow = {__rowKey: `${index}`, __rowState: 'clean'};
    row.cells.forEach((cell) => {
      result[cell.columnName] = cell.cellValue;
    });
    return result;
  });
  return {rows: normalizedRows, columns};
}

export function supportsGroupedSeriesChart(chartType?: string) {
  return ['LINE', 'BAR', 'TREND'].includes((chartType || '').toUpperCase());
}

export function normalizeManualChartConfig(config: ChartConfigVO): ChartConfigVO {
  const chartType = ((config.chartType || 'LINE').toUpperCase() || 'LINE') as ChartType;
  config.chartType = chartType;
  config.xField = config.xField || '';
  config.seriesField = config.seriesField || '';
  config.categoryField = config.categoryField || '';
  config.valueField = config.valueField || '';
  config.sortField = config.sortField || '';
  config.sortDirection = (config.sortDirection || 'NONE') as SortDirection;
  config.title = config.title || '';
  config.description = config.description || '';
  config.yFields = [...new Set((config.yFields || []).map((item) => (item || '').trim()).filter((item) => !!item))];
  if (!supportsGroupedSeriesChart(chartType)) {
    config.seriesField = '';
  }
  if (config.seriesField && config.seriesField === config.xField) {
    config.seriesField = '';
  }
  if (chartType === 'SCATTER' || (supportsGroupedSeriesChart(chartType) && !!config.seriesField)) {
    config.yFields = config.yFields.slice(0, 1);
  }
  return config;
}

export function emptyManualChartConfig(): ChartConfigVO {
  return {
    chartType: 'LINE',
    xField: '',
    yFields: [],
    seriesField: '',
    categoryField: '',
    valueField: '',
    sortField: '',
    sortDirection: 'NONE',
    title: '',
    description: '',
  };
}

export function cloneChartConfig(config: ChartConfigVO | null | undefined): ChartConfigVO {
  if (!config) {
    return emptyManualChartConfig();
  }
  return normalizeManualChartConfig({
    chartType: (config.chartType || 'LINE') as ChartType,
    xField: config.xField || '',
    yFields: [...(config.yFields || [])],
    seriesField: config.seriesField || '',
    categoryField: config.categoryField || '',
    valueField: config.valueField || '',
    sortField: config.sortField || '',
    sortDirection: (config.sortDirection || 'NONE') as SortDirection,
    title: config.title || '',
    description: config.description || '',
  });
}

export function isNumericField(rows: Array<Record<string, string | null>>, field: string) {
  const values = rows
    .map((row) => row[field])
    .filter((value): value is string => value != null && String(value).trim() !== '')
    .slice(0, 120);
  if (!values.length) {
    return false;
  }
  return values.every((value) => Number.isFinite(Number(value)));
}

export function createStatementResult(index: number, sqlText: string): QueryStatementResult {
  return {
    key: `statement-${Date.now()}-${index}-${Math.round(Math.random() * 1000)}`,
    index,
    sqlText,
    status: 'running',
    executeResult: null,
    resultTableRows: [],
    resultTableColumns: [],
    columnWidthMap: {},
    lastExecuteFailed: false,
    lastExecuteErrorMessage: '',
    lastFailedSqlText: '',
    resultViewMode: 'table',
    manualChartConfig: emptyManualChartConfig(),
    activeChartConfig: null,
    chartImageDataUrl: '',
    chartImageCacheKey: '',
    chartReadonly: false,
  };
}

export function getActiveStatementResultForTab(tab: QueryWorkspaceTab | null | undefined) {
  if (!tab) {
    return null as QueryStatementResult | null;
  }
  return tab.statementResults.find((item) => item.key === tab.activeStatementResultKey)
    ?? tab.statementResults[0]
    ?? null;
}

export function syncTabPresentationFromStatementResult(tab: QueryWorkspaceTab, result: QueryStatementResult | null) {
  if (!result) {
    return;
  }
  tab.executeResult = result.executeResult;
  tab.explainResult = null;
  tab.resultTableRows = result.resultTableRows;
  tab.resultTableColumns = result.resultTableColumns;
  tab.lastExecuteFailed = result.lastExecuteFailed;
  tab.lastExecuteErrorMessage = result.lastExecuteErrorMessage;
  tab.lastFailedSqlText = result.lastFailedSqlText;
  tab.resultViewMode = result.resultViewMode;
  tab.manualChartConfig = cloneChartConfig(result.manualChartConfig);
  tab.activeChartConfig = result.activeChartConfig ? cloneChartConfig(result.activeChartConfig) : null;
  tab.chartImageDataUrl = result.chartImageDataUrl;
  tab.chartImageCacheKey = result.chartImageCacheKey;
  tab.chartReadonly = result.chartReadonly;
}

export function syncActiveStatementResultFromTab(tab: QueryWorkspaceTab) {
  const result = getActiveStatementResultForTab(tab);
  if (!result) {
    return;
  }
  result.executeResult = tab.executeResult;
  result.resultTableRows = tab.resultTableRows;
  result.resultTableColumns = tab.resultTableColumns;
  result.columnWidthMap = buildColumnWidthMapFromColumns(tab.resultTableColumns);
  result.lastExecuteFailed = tab.lastExecuteFailed;
  result.lastExecuteErrorMessage = tab.lastExecuteErrorMessage;
  result.lastFailedSqlText = tab.lastFailedSqlText;
  result.resultViewMode = tab.resultViewMode;
  result.manualChartConfig = cloneChartConfig(tab.manualChartConfig);
  result.activeChartConfig = tab.activeChartConfig ? cloneChartConfig(tab.activeChartConfig) : null;
  result.chartImageDataUrl = tab.chartImageDataUrl;
  result.chartImageCacheKey = tab.chartImageCacheKey;
  result.chartReadonly = tab.chartReadonly;
}

export function clearTabExecutionPresentation(tab: QueryWorkspaceTab) {
  tab.executeResult = null;
  tab.explainResult = null;
  tab.resultTableRows = [];
  tab.resultTableColumns = [];
  tab.lastExecuteFailed = false;
  tab.lastExecuteErrorMessage = '';
  tab.lastFailedSqlText = '';
  tab.resultViewMode = 'table';
  tab.manualChartConfig = emptyManualChartConfig();
  tab.activeChartConfig = null;
  tab.chartImageDataUrl = '';
  tab.chartImageCacheKey = '';
  tab.chartReadonly = false;
}

export function clearStatementResults(tab: QueryWorkspaceTab) {
  tab.statementResults = [];
  tab.activeStatementResultKey = '';
  tab.executingStatementIndex = null;
  clearTabExecutionPresentation(tab);
}

export function setActiveStatementResult(tab: QueryWorkspaceTab, statementKey: string) {
  syncActiveStatementResultFromTab(tab);
  tab.activeStatementResultKey = statementKey;
  syncTabPresentationFromStatementResult(tab, getActiveStatementResultForTab(tab));
}

export function resultTabTitle(result: QueryStatementResult) {
  const firstKeyword = (result.sqlText.match(/^\s*([a-zA-Z]+)/)?.[1] || '').toUpperCase();
  return firstKeyword ? `结果 ${result.index} · ${firstKeyword}` : `结果 ${result.index}`;
}

export function canExportStatementResult(result: QueryStatementResult | null | undefined) {
  return !!result && result.status === 'success' && !!result.sqlText.trim();
}

export function canExportActiveQueryResult(tab: QueryWorkspaceTab) {
  if (tab.resultViewMode === 'chart') {
    return !!tab.activeChartConfig || !!tab.chartImageDataUrl || !!tab.chartImageCacheKey;
  }
  return canExportStatementResult(getActiveStatementResultForTab(tab));
}

export function latestSuccessfulStatementResult(tab: QueryWorkspaceTab) {
  return [...tab.statementResults].reverse().find((item) => item.executeResult?.success) ?? null;
}

export function buildExecutionPreview(
  result: {affectedRows?: number; executionMs?: number; rows?: Array<{cells: Array<{columnName: string; cellValue: string | null}>}>},
  maxRows = 20,
  maxColumns = 12,
): QueryExecutionPreview {
  const rows = result.rows ?? [];
  const previewRows = rows.slice(0, maxRows).map((row) => {
    const preview: Record<string, string | null> = {};
    row.cells.slice(0, maxColumns).forEach((cell) => {
      preview[cell.columnName] = cell.cellValue;
    });
    return preview;
  });
  const columns = rows[0]?.cells.slice(0, maxColumns).map((cell) => cell.columnName) ?? [];
  return {
    affectedRows: Number(result.affectedRows || 0),
    executionMs: Number(result.executionMs || 0),
    columns,
    rows: previewRows,
    truncated: rows.length > maxRows || (rows[0]?.cells.length ?? 0) > maxColumns,
  };
}

export function chatExecutionColumns(preview: QueryExecutionPreview) {
  return preview.columns.map((columnName) => ({
    title: columnName,
    dataIndex: columnName,
    key: columnName,
    ellipsis: true,
    width: 140,
  }));
}

export function normalizeDownloadFileNamePart(text: string) {
  const normalized = text.trim().replace(/[\\/:*?"<>|]+/g, '_').replace(/\s+/g, '_');
  return normalized || 'er';
}

export function isChartCacheRetryableError(rawMessage: string) {
  const normalized = rawMessage.trim().toLowerCase();
  return normalized.includes('超过大小限制')
    || normalized.includes('too large')
    || normalized.includes('http 413');
}

export function normalizeChartCacheErrorMessage(rawMessage: string) {
  const normalized = rawMessage.trim();
  if (!normalized || /^http \d+$/i.test(normalized)) {
    return 'Chart generated, but image cache failed. Available for this session only.';
  }
  return `图表已生成，但图片缓存失败：${normalized}`;
}

export function isLikelyLocalFilePath(rawPath: string) {
  const normalized = rawPath.trim();
  if (!normalized) {
    return false;
  }
  if (normalized.startsWith('/')) {
    return true;
  }
  return /^[a-zA-Z]:[\\/]/.test(normalized);
}

export interface CacheChartImageResult {
  imageDataUrl: string;
  cacheKey: string;
  cacheErrorMessage?: string;
}

interface ChartRuntimeHelperContext {
  exportChartDataUrl: (pixelRatio?: number) => Promise<string>;
  getDesktopBridge: () => DesktopBridge | null;
}

interface QueryResultViewHelperContext {
  touchQueryTab: (tab: QueryWorkspaceTab) => void;
}

function getUnknownErrorMessage(error: unknown) {
  return error instanceof Error ? error.message : String(error);
}

export function createChartRuntimeHelpers(ctx: ChartRuntimeHelperContext) {
  async function exportChartPngDataUrl(pixelRatio = 2) {
    for (let attempt = 0; attempt < 6; attempt += 1) {
      await nextTick();
      await new Promise((resolve) => setTimeout(resolve, attempt === 0 ? 40 : 90));
      const dataUrl = (await ctx.exportChartDataUrl(pixelRatio)) || '';
      if (dataUrl) {
        return dataUrl;
      }
    }
    return '';
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
    const bridge = ctx.getDesktopBridge();
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
    const bridge = ctx.getDesktopBridge();
    if (bridge && typeof bridge.readChartCache === 'function' && isLikelyLocalFilePath(normalized)) {
      return (await bridge.readChartCache(normalized)) || '';
    }
    const loaded = await getApi<ChartCacheReadVO>(
      `/api/editor/chart/cache/read?cacheKey=${encodeURIComponent(normalized)}`,
    );
    return loaded.dataUrl || '';
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
        lastErrorMessage = getUnknownErrorMessage(error);
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

  return {
    exportChartPngDataUrl,
    saveChartImageCache,
    loadChartImageDataUrl,
    cacheChartImageWithRetry,
    downloadImage,
    downloadActiveChart,
    downloadMessageChart,
    hydrateHistoryChartImages,
  };
}

export function createQueryResultViewHelpers(ctx: QueryResultViewHelperContext) {
  function queryResultExportTooltip(tab: QueryWorkspaceTab) {
    return tab.resultViewMode === 'chart' ? 'Download chart PNG' : 'Export result (CSV)';
  }

  function setQueryResultViewMode(tab: QueryWorkspaceTab, mode: QueryResultViewMode) {
    tab.resultViewMode = mode;
    ctx.touchQueryTab(tab);
  }

  function rebuildQueryResultTableCache(tab: QueryWorkspaceTab) {
    const rows = tab.executeResult?.rows ?? tab.explainResult?.rows ?? [];
    const cache = buildResultTableCache(rows, getActiveStatementResultForTab(tab)?.columnWidthMap ?? {});
    tab.resultTableRows = cache.rows;
    tab.resultTableColumns = cache.columns;
    syncActiveStatementResultFromTab(tab);
  }

  function resizeActiveQueryResultColumn(tab: QueryWorkspaceTab, columnName: string, width: number) {
    const nextWidth = clampGridColumnWidth(width);
    const activeResult = getActiveStatementResultForTab(tab);
    const mapColumns = (columns: QueryResultTableColumn[]) => columns.map((column) =>
      column.key === columnName ? {...column, width: nextWidth} : column,
    );

    if (activeResult) {
      activeResult.resultTableColumns = mapColumns(activeResult.resultTableColumns);
      activeResult.columnWidthMap = {
        ...activeResult.columnWidthMap,
        [columnName]: nextWidth,
      };
      if (tab.activeStatementResultKey === activeResult.key) {
        tab.resultTableColumns = [...activeResult.resultTableColumns];
      }
    } else {
      tab.resultTableColumns = mapColumns(tab.resultTableColumns);
    }
    ctx.touchQueryTab(tab);
  }

  function setupManualChartConfigByResult(tab: QueryWorkspaceTab) {
    const rows = (tab.executeResult?.rows ?? tab.explainResult?.rows ?? []);
    if (!rows.length) {
      tab.manualChartConfig = emptyManualChartConfig();
      syncActiveStatementResultFromTab(tab);
      return;
    }
    const fields = rows[0].cells.map((cell) => cell.columnName).filter((item) => !!item);
    if (!fields.length) {
      tab.manualChartConfig = emptyManualChartConfig();
      syncActiveStatementResultFromTab(tab);
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
      seriesField: '',
      categoryField: fields[0],
      valueField: numericFields[0] || '',
      sortField: '',
      sortDirection: 'NONE',
      title: '',
      description: '',
    };
    normalizeManualChartConfig(tab.manualChartConfig);
    syncActiveStatementResultFromTab(tab);
  }

  function handleManualChartTypeChange(tab: QueryWorkspaceTab, value: string) {
    tab.manualChartConfig.chartType = (value || 'LINE') as ChartType;
    normalizeManualChartConfig(tab.manualChartConfig);
    ctx.touchQueryTab(tab);
  }

  function handleManualChartXAxisChange(tab: QueryWorkspaceTab, value: string) {
    tab.manualChartConfig.xField = value || '';
    normalizeManualChartConfig(tab.manualChartConfig);
    ctx.touchQueryTab(tab);
  }

  function handleManualChartYFieldsChange(tab: QueryWorkspaceTab, value: string[]) {
    tab.manualChartConfig.yFields = Array.isArray(value) ? value : [];
    normalizeManualChartConfig(tab.manualChartConfig);
    ctx.touchQueryTab(tab);
  }

  function handleManualChartSingleYFieldChange(tab: QueryWorkspaceTab, value: string) {
    tab.manualChartConfig.yFields = value ? [value] : [];
    normalizeManualChartConfig(tab.manualChartConfig);
    ctx.touchQueryTab(tab);
  }

  function handleManualChartSeriesFieldChange(tab: QueryWorkspaceTab, value: string) {
    tab.manualChartConfig.seriesField = value || '';
    normalizeManualChartConfig(tab.manualChartConfig);
    ctx.touchQueryTab(tab);
  }

  return {
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
  };
}
