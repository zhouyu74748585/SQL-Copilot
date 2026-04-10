import {Modal, message} from 'ant-design-vue';
import {postApi} from '../../../api/client';
import {translateText, useAppI18n} from '../../../i18n';
import type {
  TableDataCommitReq,
  TableDataCommitVO,
  TableDataFilterOperator,
  TableDataPageReq,
  TableDataPageVO,
  TableDataSortDirection,
} from '../../../types';
import type {StudioRuntime} from './useStudioRuntime';

type ObjectRow = StudioRuntime['objectRows']['value'][number];
type TableDataTab = StudioRuntime['tableDataTabs']['value'][number];
type TableDataRow = TableDataTab['rows'][number];

const VALUE_REQUIRED_OPERATORS: TableDataFilterOperator[] = ['EQ', 'NE', 'GT', 'GTE', 'LT', 'LTE', 'LIKE'];
const TABLE_DATA_TEXT_EDITOR_TYPES = new Set(['VARCHAR', 'CHAR', 'TEXT', 'JSON', 'UUID']);
const tableDataRowsCache = new Map<string, { version: number; rows: Array<Record<string, string | null> & { __rowKey: string; __rowState: string }> }>();
const tableDataColumnsCache = new Map<string, {
  version: number;
  columns: Array<{ title: string; dataIndex: string; key: string; width: number; ellipsis: boolean; columnType?: string; columnComment?: string }>;
}>();
const tableDataPageRequestSeq = new Map<string, number>();
const tableDataPageAbortController = new Map<string, AbortController>();
const TABLE_DATA_PAGE_TIMEOUT_MS = 20000;
const TABLE_DATA_MIN_COLUMN_WIDTH = 80;
const TABLE_DATA_MAX_COLUMN_WIDTH = 640;

type TableDataEditorType = 'text' | 'date' | 'datetime' | 'time';
type TableDataQuickSortDirection = TableDataSortDirection | 'NONE';

export interface TableDataModule {
  tableDataFilterOperatorOptions: Array<{ label: string; value: TableDataFilterOperator }>;
  tableDataSortDirectionOptions: Array<{ label: string; value: TableDataSortDirection }>;
  closeTableDataTab: (tabKey: string) => void;
  openTableDataTabByObject: (
    record: ObjectRow,
    options?: { connectionId?: number; databaseName?: string },
  ) => Promise<void>;
  reloadTableDataForTab: (tab: TableDataTab) => Promise<void>;
  toggleTableDataFilterPanel: (tab: TableDataTab) => void;
  toggleTableDataDetailCollapsed: (tab: TableDataTab) => void;
  addTableDataFilter: (tab: TableDataTab) => void;
  addTableDataFilterForColumn: (tab: TableDataTab, columnName: string) => void;
  removeTableDataFilter: (tab: TableDataTab, filterKey: string) => void;
  addTableDataSort: (tab: TableDataTab) => void;
  removeTableDataSort: (tab: TableDataTab, sortKey: string) => void;
  tableDataSortDirectionForColumn: (tab: TableDataTab, columnName: string) => TableDataSortDirection | '';
  applyTableDataQuickSort: (tab: TableDataTab, columnName: string, direction: TableDataQuickSortDirection) => Promise<void>;
  openTableDataSearchPanel: (tab: TableDataTab, showReplace?: boolean) => void;
  closeTableDataSearchPanel: (tab: TableDataTab) => void;
  toggleTableDataReplacePanel: (tab: TableDataTab) => void;
  updateTableDataSearchKeyword: (tab: TableDataTab, keyword: string) => void;
  updateTableDataReplaceKeyword: (tab: TableDataTab, keyword: string) => void;
  focusNextTableDataSearchMatch: (tab: TableDataTab) => void;
  focusPrevTableDataSearchMatch: (tab: TableDataTab) => void;
  replaceCurrentTableDataSearchMatch: (tab: TableDataTab) => void;
  replaceAllTableDataSearchMatches: (tab: TableDataTab) => void;
  applyTableDataFilters: (tab: TableDataTab) => Promise<void>;
  prevTableDataPage: (tab: TableDataTab) => Promise<void>;
  nextTableDataPage: (tab: TableDataTab) => Promise<void>;
  updateTableDataPageSize: (tab: TableDataTab, pageSize: number) => Promise<void>;
  selectTableDataRow: (tab: TableDataTab, rowKey: string) => void;
  toggleTableDataRowChecked: (tab: TableDataTab, rowKey: string, checked: boolean) => void;
  toggleAllTableDataRowsChecked: (tab: TableDataTab, checked: boolean) => void;
  startTableDataCellEdit: (tab: TableDataTab, rowKey: string, columnName: string) => void;
  stopTableDataCellEdit: (tab: TableDataTab) => void;
  isTableDataCellEditing: (tab: TableDataTab, rowKey: string, columnName: string) => boolean;
  updateTableDataCell: (tab: TableDataTab, rowKey: string, columnName: string, value: string | null) => void;
  tableDataColumnEditorType: (tab: TableDataTab, columnName: string) => TableDataEditorType;
  selectedTableDataRow: (tab: TableDataTab | null) => TableDataRow | null;
  addTableDataRow: (tab: TableDataTab) => void;
  deleteSelectedTableDataRow: (tab: TableDataTab) => void;
  submitTableDataChanges: (tab: TableDataTab) => Promise<void>;
  discardTableDataChanges: (tab: TableDataTab) => Promise<void>;
  tableDataDisplayRows: (tab: TableDataTab) => Array<Record<string, string | null> & { __rowKey: string; __rowState: string }>;
  tableDataDisplayColumns: (
    tab: TableDataTab,
  ) => Array<{ title: string; dataIndex: string; key: string; width: number; ellipsis: boolean; columnType?: string; columnComment?: string }>;
  updateTableDataColumnWidth: (tab: TableDataTab, columnName: string, width: number) => void;
  tableDataScrollX: (tab: TableDataTab) => number;
  isTableDataPrimaryKeyColumn: (tab: TableDataTab, columnName: string) => boolean;
  handleTableDataConnectionSelectorChange: (tab: TableDataTab, connectionId: number) => Promise<void>;
  handleTableDataDatabaseSelectorChange: (tab: TableDataTab, databaseName: string) => Promise<void>;
}

export function useTableDataModule(runtime: StudioRuntime): TableDataModule {
  const {currentLocale} = useAppI18n();
  const tt = (text: string) => {
    void currentLocale.value;
    return translateText(text);
  };
  const tableDataFilterOperatorOptions: Array<{ label: string; value: TableDataFilterOperator }> = [
    {label: '等于', value: 'EQ'},
    {label: '不等于', value: 'NE'},
    {label: '大于', value: 'GT'},
    {label: '大于等于', value: 'GTE'},
    {label: '小于', value: 'LT'},
    {label: '小于等于', value: 'LTE'},
    {label: '包含', value: 'LIKE'},
    {label: '为空', value: 'IS_NULL'},
    {label: '非空', value: 'IS_NOT_NULL'},
  ];
  const tableDataSortDirectionOptions: Array<{ label: string; value: TableDataSortDirection }> = [
    {label: '升序', value: 'ASC'},
    {label: '降序', value: 'DESC'},
  ];

  function closeTableDataTab(tabKey: string) {
    const index = runtime.tableDataTabs.value.findIndex((item) => item.key === tabKey);
    if (index < 0) {
      return;
    }
    const tabs = [...runtime.tableDataTabs.value];
    tabs.splice(index, 1);
    runtime.tableDataTabs.value = tabs;
    if (runtime.activeWorkbenchTab.value === tabKey) {
      runtime.activeWorkbenchTab.value = tabs[index]?.key || tabs[index - 1]?.key || runtime.browserTabKey;
      runtime.ensureActiveWorkbenchTab();
    }
    const controller = tableDataPageAbortController.get(tabKey);
    if (controller) {
      controller.abort();
      tableDataPageAbortController.delete(tabKey);
    }
    tableDataPageRequestSeq.delete(tabKey);
    tableDataRowsCache.delete(tabKey);
    tableDataColumnsCache.delete(tabKey);
  }

  async function openTableDataTabByObject(
    record: ObjectRow,
    options?: { connectionId?: number; databaseName?: string },
  ) {
    if (record.objectType !== 'tables' && record.objectType !== 'views') {
      return;
    }
    const connectionId = options?.connectionId ?? runtime.workflow.connectionId;
    const databaseName = (options?.databaseName ?? runtime.getActiveDatabaseName(connectionId)).trim();
    if (!connectionId || !databaseName) {
      message.warning('请先选择连接和数据库');
      return;
    }
    const existing = runtime.tableDataTabs.value.find(
      (item) => item.connectionId === connectionId
        && item.databaseName === databaseName
        && item.tableName === record.objectName
        && item.objectType === record.objectType,
    );
    if (existing) {
      runtime.activeWorkbenchTab.value = existing.key;
      return;
    }

    const now = Date.now();
    const dbType = runtime.connections.value.find((item) => item.id === connectionId)?.dbType ?? 'MYSQL';
    const tab: TableDataTab = {
      key: `table-data-${now}-${Math.round(Math.random() * 1000)}`,
      title: `数据 · ${record.objectName}`,
      connectionId,
      databaseName,
      tableName: record.objectName,
      objectType: record.objectType,
      dbType,
      loading: false,
      submitting: false,
      editable: false,
      readOnlyReason: '',
      columns: [],
      primaryKeyColumns: [],
      rows: [],
      deletedRows: [],
      selectedRowKey: '',
      checkedRowKeys: [],
      editingCellKey: '',
      detailCollapsed: false,
      filterPanelVisible: false,
      filters: [],
      sorts: [],
      searchPanelVisible: false,
      searchReplaceVisible: false,
      searchKeyword: '',
      replaceKeyword: '',
      searchMatches: [],
      activeSearchMatchIndex: -1,
      columnWidthMap: {},
      pageNo: 1,
      pageSize: 1000,
      hasNextPage: false,
      rowDataVersion: 0,
      schemaVersion: 0,
      displayRowsCacheVersion: -1,
      displayRowsCache: [],
      displayColumnsCacheVersion: -1,
      displayColumnsCache: [],
      errorMessage: '',
      dirty: false,
      createdAt: now,
      updatedAt: now,
    };
    runtime.tableDataTabs.value = [...runtime.tableDataTabs.value, tab];
    const reactiveTab = runtime.tableDataTabs.value.find((item) => item.key === tab.key) ?? tab;
    runtime.activeWorkbenchTab.value = tab.key;
    await loadTableDataPage(reactiveTab);
  }

  async function reloadTableDataForTab(tab: TableDataTab) {
    if (!ensureCanSwitchPageOrFilter(tab)) {
      return;
    }
    await loadTableDataPage(tab);
  }

  function toggleTableDataFilterPanel(tab: TableDataTab) {
    tab.filterPanelVisible = !tab.filterPanelVisible;
    touchTableDataTab(tab);
  }

  function toggleTableDataDetailCollapsed(tab: TableDataTab) {
    tab.detailCollapsed = !tab.detailCollapsed;
    touchTableDataTab(tab);
  }

  function addTableDataFilter(tab: TableDataTab) {
    if (!tab.columns.length) {
      message.warning('当前表暂无可过滤字段');
      return;
    }
    tab.filters = [
      ...tab.filters,
      {
        key: `filter-${Date.now()}-${Math.round(Math.random() * 1000)}`,
        columnName: tab.columns[0].columnName,
        operator: 'EQ',
        value: '',
      },
    ];
    touchTableDataTab(tab);
  }

  function addTableDataFilterForColumn(tab: TableDataTab, columnName: string) {
    const normalizedColumnName = (columnName || '').trim();
    if (!normalizedColumnName) {
      return;
    }
    const matchedColumn = tab.columns.find((item) => item.columnName === normalizedColumnName);
    if (!matchedColumn) {
      message.warning('当前列不可用于筛选');
      return;
    }
    tab.filterPanelVisible = true;
    tab.filters = [
      ...tab.filters,
      {
        key: `filter-${Date.now()}-${Math.round(Math.random() * 1000)}`,
        columnName: matchedColumn.columnName,
        operator: 'EQ',
        value: '',
      },
    ];
    touchTableDataTab(tab);
  }

  function removeTableDataFilter(tab: TableDataTab, filterKey: string) {
    tab.filters = tab.filters.filter((item) => item.key !== filterKey);
    touchTableDataTab(tab);
  }

  function addTableDataSort(tab: TableDataTab) {
    if (!tab.columns.length) {
      message.warning('当前表暂无可排序字段');
      return;
    }
    tab.sorts = [
      ...tab.sorts,
      {
        key: `sort-${Date.now()}-${Math.round(Math.random() * 1000)}`,
        columnName: tab.columns[0].columnName,
        direction: 'ASC',
      },
    ];
    touchTableDataTab(tab);
  }

  function removeTableDataSort(tab: TableDataTab, sortKey: string) {
    tab.sorts = tab.sorts.filter((item) => item.key !== sortKey);
    touchTableDataTab(tab);
  }

  function tableDataSortDirectionForColumn(tab: TableDataTab, columnName: string) {
    return tab.sorts.find((item) => item.columnName === columnName)?.direction || '';
  }

  async function applyTableDataQuickSort(tab: TableDataTab, columnName: string, direction: TableDataQuickSortDirection) {
    if (!ensureCanSwitchPageOrFilter(tab)) {
      return;
    }
    const normalizedColumnName = (columnName || '').trim();
    if (!normalizedColumnName) {
      return;
    }
    const nextSorts: typeof tab.sorts = [];
    let updated = false;
    tab.sorts.forEach((item) => {
      if (item.columnName !== normalizedColumnName) {
        nextSorts.push(item);
        return;
      }
      if (direction === 'NONE' || updated) {
        return;
      }
      nextSorts.push({
        ...item,
        direction,
      });
      updated = true;
    });
    if (direction !== 'NONE' && !updated) {
      nextSorts.push({
        key: `sort-${Date.now()}-${Math.round(Math.random() * 1000)}`,
        columnName: normalizedColumnName,
        direction,
      });
    }
    tab.sorts = nextSorts;
    tab.pageNo = 1;
    touchTableDataTab(tab);
    await loadTableDataPage(tab);
  }

  function openTableDataSearchPanel(tab: TableDataTab, showReplace = false) {
    tab.searchPanelVisible = true;
    if (showReplace) {
      tab.searchReplaceVisible = true;
    }
    recomputeTableDataSearchMatches(tab);
    touchTableDataTab(tab);
  }

  function closeTableDataSearchPanel(tab: TableDataTab) {
    if (!tab.searchPanelVisible && !tab.searchReplaceVisible) {
      return;
    }
    tab.searchPanelVisible = false;
    tab.searchReplaceVisible = false;
    tab.searchKeyword = '';
    tab.replaceKeyword = '';
    tab.searchMatches = [];
    tab.activeSearchMatchIndex = -1;
    touchTableDataTab(tab);
  }

  function toggleTableDataReplacePanel(tab: TableDataTab) {
    if (!tab.searchPanelVisible) {
      tab.searchPanelVisible = true;
    }
    tab.searchReplaceVisible = !tab.searchReplaceVisible;
    touchTableDataTab(tab);
  }

  function updateTableDataSearchKeyword(tab: TableDataTab, keyword: string) {
    tab.searchKeyword = String(keyword || '');
    recomputeTableDataSearchMatches(tab);
    touchTableDataTab(tab);
  }

  function updateTableDataReplaceKeyword(tab: TableDataTab, keyword: string) {
    tab.replaceKeyword = String(keyword || '');
    touchTableDataTab(tab);
  }

  function focusNextTableDataSearchMatch(tab: TableDataTab) {
    moveTableDataSearchMatchFocus(tab, 1);
  }

  function focusPrevTableDataSearchMatch(tab: TableDataTab) {
    moveTableDataSearchMatchFocus(tab, -1);
  }

  function replaceCurrentTableDataSearchMatch(tab: TableDataTab) {
    if (!tab.editable) {
      message.warning(tab.readOnlyReason || tt('当前表不可编辑'));
      return;
    }
    const match = currentTableDataSearchMatch(tab);
    if (!match) {
      return;
    }
    if (isTableDataPrimaryKeyColumn(tab, match.columnName)) {
      message.warning(tt('主键列不支持替换'));
      return;
    }
    const row = tab.rows.find((item) => item.rowKey === match.rowKey);
    if (!row) {
      recomputeTableDataSearchMatches(tab);
      touchTableDataTab(tab);
      return;
    }
    const currentValue = row.values[match.columnName];
    const nextValue = replaceKeywordInCellValue(currentValue, tab.searchKeyword, tab.replaceKeyword);
    if (nextValue === currentValue) {
      moveTableDataSearchMatchFocus(tab, 1);
      return;
    }
    updateTableDataCell(tab, match.rowKey, match.columnName, nextValue);
    recomputeTableDataSearchMatches(tab, {
      rowKey: match.rowKey,
      columnName: match.columnName,
      moveDirectionIfMissing: 1,
    });
    touchTableDataTab(tab);
  }

  function replaceAllTableDataSearchMatches(tab: TableDataTab) {
    if (!tab.editable) {
      message.warning(tab.readOnlyReason || tt('当前表不可编辑'));
      return;
    }
    const keyword = tab.searchKeyword.trim();
    if (!keyword) {
      return;
    }
    const editableMatches = tab.searchMatches.filter((item) => !isTableDataPrimaryKeyColumn(tab, item.columnName));
    if (!editableMatches.length) {
      message.info(tt('当前页无可替换匹配项'));
      return;
    }
    let replacedCount = 0;
    const replacedCellKeySet = new Set<string>();
    editableMatches.forEach((match) => {
      const cellKey = `${match.rowKey}::${match.columnName}`;
      if (replacedCellKeySet.has(cellKey)) {
        return;
      }
      replacedCellKeySet.add(cellKey);
      const row = tab.rows.find((item) => item.rowKey === match.rowKey);
      if (!row) {
        return;
      }
      const currentValue = row.values[match.columnName];
      const nextValue = replaceKeywordInCellValue(currentValue, tab.searchKeyword, tab.replaceKeyword);
      if (nextValue === currentValue) {
        return;
      }
      updateTableDataCell(tab, match.rowKey, match.columnName, nextValue);
      replacedCount += 1;
    });
    recomputeTableDataSearchMatches(tab);
    touchTableDataTab(tab);
    if (replacedCount > 0) {
      message.success(tt(`已替换 ${replacedCount} 个单元格`));
    }
  }

  async function applyTableDataFilters(tab: TableDataTab) {
    if (!ensureCanSwitchPageOrFilter(tab)) {
      return;
    }
    tab.pageNo = 1;
    await loadTableDataPage(tab);
  }

  async function prevTableDataPage(tab: TableDataTab) {
    if (!ensureCanSwitchPageOrFilter(tab)) {
      return;
    }
    if (tab.pageNo <= 1) {
      return;
    }
    tab.pageNo -= 1;
    await loadTableDataPage(tab);
  }

  async function nextTableDataPage(tab: TableDataTab) {
    if (!ensureCanSwitchPageOrFilter(tab)) {
      return;
    }
    if (!tab.hasNextPage) {
      return;
    }
    tab.pageNo += 1;
    await loadTableDataPage(tab);
  }

  async function updateTableDataPageSize(tab: TableDataTab, pageSize: number) {
    if (!ensureCanSwitchPageOrFilter(tab)) {
      return;
    }
    const normalized = Number.isFinite(pageSize) ? Math.max(1, Math.floor(pageSize)) : 1000;
    tab.pageSize = normalized;
    tab.pageNo = 1;
    await loadTableDataPage(tab);
  }

  function selectTableDataRow(tab: TableDataTab, rowKey: string) {
    tab.selectedRowKey = rowKey;
    if (tab.editingCellKey && !tab.editingCellKey.startsWith(`${rowKey}::`)) {
      tab.editingCellKey = '';
    }
    touchTableDataTab(tab);
  }

  function toggleTableDataRowChecked(tab: TableDataTab, rowKey: string, checked: boolean) {
    if (!tab.rows.some((item) => item.rowKey === rowKey)) {
      return;
    }
    const checkedKeySet = new Set(tab.checkedRowKeys);
    if (checked) {
      checkedKeySet.add(rowKey);
    } else {
      checkedKeySet.delete(rowKey);
    }
    tab.checkedRowKeys = tab.rows
      .map((item) => item.rowKey)
      .filter((item) => checkedKeySet.has(item));
    tab.selectedRowKey = rowKey;
    if (tab.editingCellKey && !tab.editingCellKey.startsWith(`${rowKey}::`)) {
      tab.editingCellKey = '';
    }
    touchTableDataTab(tab);
  }

  function toggleAllTableDataRowsChecked(tab: TableDataTab, checked: boolean) {
    tab.checkedRowKeys = checked ? tab.rows.map((item) => item.rowKey) : [];
    touchTableDataTab(tab);
  }

  function startTableDataCellEdit(tab: TableDataTab, rowKey: string, columnName: string) {
    if (!tab.editable) {
      return;
    }
    if (isTableDataPrimaryKeyColumn(tab, columnName)) {
      return;
    }
    tab.selectedRowKey = rowKey;
    tab.editingCellKey = `${rowKey}::${columnName}`;
    touchTableDataTab(tab);
  }

  function stopTableDataCellEdit(tab: TableDataTab) {
    if (!tab.editingCellKey) {
      return;
    }
    tab.editingCellKey = '';
    touchTableDataTab(tab);
  }

  function isTableDataCellEditing(tab: TableDataTab, rowKey: string, columnName: string) {
    return tab.editingCellKey === `${rowKey}::${columnName}`;
  }

  function updateTableDataCell(tab: TableDataTab, rowKey: string, columnName: string, value: string | null) {
    if (!tab.editable) {
      return;
    }
    if (isTableDataPrimaryKeyColumn(tab, columnName)) {
      return;
    }
    const row = tab.rows.find((item) => item.rowKey === rowKey);
    if (!row) {
      return;
    }
    row.values[columnName] = value;
    if (row.rowState === 'clean') {
      if (!isRowEqual(row.values, row.originalValues)) {
        row.rowState = 'updated';
      }
    } else if (row.rowState === 'updated') {
      if (isRowEqual(row.values, row.originalValues)) {
        row.rowState = 'clean';
      }
    }
    refreshDirtyState(tab);
    tab.rowDataVersion += 1;
    tab.displayRowsCacheVersion = -1;
    recomputeTableDataSearchMatches(tab, {
      rowKey,
      columnName,
    });
    touchTableDataTab(tab);
  }

  function tableDataColumnEditorType(tab: TableDataTab, columnName: string): TableDataEditorType {
    const columnTypeRaw = (tab.columns.find((item) => item.columnName === columnName)?.columnType || '').trim().toUpperCase();
    if (!columnTypeRaw) {
      return 'text';
    }
    if (columnTypeRaw.includes('TIMESTAMP') || columnTypeRaw.includes('DATETIME')) {
      return 'datetime';
    }
    if (columnTypeRaw.includes('DATE')) {
      return 'date';
    }
    if (columnTypeRaw.includes('TIME')) {
      return 'time';
    }
    const normalized = columnTypeRaw.replace(/\(.+$/, '').trim();
    if (TABLE_DATA_TEXT_EDITOR_TYPES.has(normalized)) {
      return 'text';
    }
    return 'text';
  }

  function selectedTableDataRow(tab: TableDataTab | null): TableDataRow | null {
    if (!tab || !tab.selectedRowKey) {
      return null;
    }
    return tab.rows.find((item) => item.rowKey === tab.selectedRowKey) ?? null;
  }

  function addTableDataRow(tab: TableDataTab) {
    if (!tab.editable) {
      message.warning(tab.readOnlyReason || tt('当前表不可编辑'));
      return;
    }
    const values: Record<string, string | null> = {};
    tab.columns.forEach((column) => {
      values[column.columnName] = null;
    });
    const row: TableDataRow = {
      rowKey: `new-${Date.now()}-${Math.round(Math.random() * 1000)}`,
      values,
      originalValues: {...values},
      rowState: 'new',
    };
    tab.rows = [row, ...tab.rows];
    tab.selectedRowKey = row.rowKey;
    tab.editingCellKey = '';
    refreshDirtyState(tab);
    tab.rowDataVersion += 1;
    tab.displayRowsCacheVersion = -1;
    recomputeTableDataSearchMatches(tab);
    touchTableDataTab(tab);
  }

  function deleteSelectedTableDataRow(tab: TableDataTab) {
    if (!tab.editable) {
      message.warning(tab.readOnlyReason || tt('当前表不可编辑'));
      return;
    }
    const targetRowKeys = tab.checkedRowKeys.length ? [...tab.checkedRowKeys] : (tab.selectedRowKey ? [tab.selectedRowKey] : []);
    if (!targetRowKeys.length) {
      message.info(tt('请先选择要删除的数据行'));
      return;
    }
    const targetKeySet = new Set(targetRowKeys);
    const targetRows = tab.rows.filter((item) => targetKeySet.has(item.rowKey));
    if (!targetRows.length) {
      message.info(tt('未找到选中行'));
      return;
    }
    const deletedRows = [...tab.deletedRows];
    targetRows.forEach((target) => {
      if (target.rowState === 'new') {
        return;
      }
      deletedRows.push({
        rowKey: target.rowKey,
        values: {...target.values},
      });
    });
    tab.deletedRows = deletedRows;
    tab.rows = tab.rows.filter((item) => !targetKeySet.has(item.rowKey));
    tab.checkedRowKeys = tab.checkedRowKeys.filter((item) => !targetKeySet.has(item));
    tab.selectedRowKey = tab.rows.find((item) => item.rowKey === tab.selectedRowKey)?.rowKey || tab.rows[0]?.rowKey || '';
    tab.editingCellKey = '';
    refreshDirtyState(tab);
    tab.rowDataVersion += 1;
    tab.displayRowsCacheVersion = -1;
    recomputeTableDataSearchMatches(tab);
    touchTableDataTab(tab);
  }

  async function submitTableDataChanges(tab: TableDataTab) {
    if (!tab.editable) {
      message.warning(tab.readOnlyReason || tt('当前表不可编辑'));
      return;
    }
    if (!tab.dirty) {
      message.info(tt('当前无待提交变更'));
      return;
    }

    const payload = buildCommitPayload(tab);
    if (!payload.inserts.length && !payload.updates.length && !payload.deletes.length) {
      tab.dirty = false;
      touchTableDataTab(tab);
      message.info(tt('未检测到有效变更'));
      return;
    }

    tab.errorMessage = '';
    tab.submitting = true;
    touchTableDataTab(tab);
    try {
      const result = await postApi<TableDataCommitVO>('/api/schema/table/data/commit', payload);
      message.success(`提交成功：新增 ${result.insertedCount}，更新 ${result.updatedCount}，删除 ${result.deletedCount}`);
      await loadTableDataPage(tab);
    } catch (error) {
      const msg = error instanceof Error ? error.message : String(error);
      tab.errorMessage = msg || tt('未知错误');
      Modal.error({
        title: tt('提交数据变更失败'),
        content: `${tt('本次提交未成功，变更仍保留在当前页面，可修正后重试。')}\n${tab.errorMessage}`,
        okText: tt('关闭'),
      });
    } finally {
      tab.submitting = false;
      touchTableDataTab(tab);
    }
  }

  async function discardTableDataChanges(tab: TableDataTab) {
    if (!tab.dirty) {
      return;
    }
    await loadTableDataPage(tab);
  }

  function tableDataDisplayRows(tab: TableDataTab) {
    const cache = tableDataRowsCache.get(tab.key);
    if (cache && cache.version === tab.rowDataVersion) {
      return cache.rows;
    }
    const rows = tab.rows.map((row) => {
      const item: Record<string, string | null> & { __rowKey: string; __rowState: string } = {
        __rowKey: row.rowKey,
        __rowState: row.rowState,
      };
      tab.columns.forEach((column) => {
        item[column.columnName] = row.values[column.columnName] ?? null;
      });
      return item;
    });
    tableDataRowsCache.set(tab.key, {version: tab.rowDataVersion, rows});
    return rows;
  }

  function tableDataDisplayColumns(tab: TableDataTab) {
    const cache = tableDataColumnsCache.get(tab.key);
    if (cache && cache.version === tab.schemaVersion) {
      return cache.columns;
    }
    const columns = tab.columns.map((column) => ({
      title: column.primaryKey ? `${column.columnName} (PK)` : column.columnName,
      dataIndex: column.columnName,
      key: column.columnName,
      width: clampTableDataColumnWidth(tab.columnWidthMap[column.columnName] ?? 132),
      ellipsis: true,
      columnType: column.columnType,
      columnComment: column.columnComment,
    }));
    tableDataColumnsCache.set(tab.key, {version: tab.schemaVersion, columns});
    return columns;
  }

  function updateTableDataColumnWidth(tab: TableDataTab, columnName: string, width: number) {
    const normalizedColumnName = (columnName || '').trim();
    if (!normalizedColumnName) {
      return;
    }
    tab.columnWidthMap = {
      ...tab.columnWidthMap,
      [normalizedColumnName]: clampTableDataColumnWidth(width),
    };
    tab.schemaVersion += 1;
    tab.displayColumnsCacheVersion = -1;
    touchTableDataTab(tab);
  }

  function tableDataScrollX(tab: TableDataTab) {
    const totalWidth = tableDataDisplayColumns(tab).reduce((sum, column) => sum + clampTableDataColumnWidth(column.width), 0);
    return Math.max(totalWidth, 960);
  }

  function isTableDataPrimaryKeyColumn(tab: TableDataTab, columnName: string) {
    return tab.primaryKeyColumns.includes(columnName);
  }

  async function handleTableDataConnectionSelectorChange(tab: TableDataTab, connectionId: number) {
    if (tab.dirty) {
      message.warning('存在未提交改动，请先提交或撤销');
      return;
    }
    tab.connectionId = connectionId;
    await runtime.runSafely(async () => {
      await runtime.prepareConnectionTreeData(connectionId);
      tab.databaseName = runtime.getActiveDatabaseName(connectionId);
      tab.dbType = runtime.connections.value.find((item) => item.id === connectionId)?.dbType ?? 'MYSQL';
      tab.pageNo = 1;
      await loadTableDataPage(tab);
    });
  }

  async function handleTableDataDatabaseSelectorChange(tab: TableDataTab, databaseName: string) {
    if (tab.dirty) {
      message.warning('存在未提交改动，请先提交或撤销');
      return;
    }
    tab.databaseName = (databaseName || '').trim();
    tab.pageNo = 1;
    await loadTableDataPage(tab);
  }

  async function loadTableDataPage(tab: TableDataTab) {
    const requestSeq = (tableDataPageRequestSeq.get(tab.key) || 0) + 1;
    tableDataPageRequestSeq.set(tab.key, requestSeq);

    const previousController = tableDataPageAbortController.get(tab.key);
    if (previousController) {
      previousController.abort();
    }
    const controller = new AbortController();
    tableDataPageAbortController.set(tab.key, controller);

    if (!tab.databaseName) {
      tab.rows = [];
      tab.columns = [];
      tab.primaryKeyColumns = [];
      tab.hasNextPage = false;
      tab.errorMessage = tt('未选择数据库');
      tab.readOnlyReason = tt('未选择数据库');
      tab.editable = false;
      tab.deletedRows = [];
      tab.selectedRowKey = '';
      tab.checkedRowKeys = [];
      tab.editingCellKey = '';
      tab.sorts = [];
      tab.columnWidthMap = {};
      tab.dirty = false;
      tab.rowDataVersion += 1;
      tab.schemaVersion += 1;
      tab.displayRowsCacheVersion = -1;
      tab.displayColumnsCacheVersion = -1;
      if (tableDataPageRequestSeq.get(tab.key) === requestSeq) {
        tab.loading = false;
        tableDataPageAbortController.delete(tab.key);
      }
      touchTableDataTab(tab);
      return;
    }

    const filters = buildPageFilters(tab);
    tab.loading = true;
    tab.errorMessage = '';
    tab.rows = [];
    tab.deletedRows = [];
    tab.selectedRowKey = '';
    tab.checkedRowKeys = [];
    tab.editingCellKey = '';
    tab.dirty = false;
    tab.hasNextPage = false;
    tab.rowDataVersion += 1;
    tab.displayRowsCacheVersion = -1;
    touchTableDataTab(tab);
    const timeoutId = setTimeout(() => {
      if (tableDataPageRequestSeq.get(tab.key) === requestSeq) {
        controller.abort();
      }
    }, TABLE_DATA_PAGE_TIMEOUT_MS);
    try {
      const payload: TableDataPageReq = {
        connectionId: tab.connectionId,
        databaseName: tab.databaseName,
        tableName: tab.tableName,
        objectType: tab.objectType,
        pageNo: tab.pageNo,
        pageSize: tab.pageSize,
        filters,
        sorts: buildPageSorts(tab),
      };
      const page = await postApi<TableDataPageVO>('/api/schema/table/data/page', payload, {signal: controller.signal});
      if (tableDataPageRequestSeq.get(tab.key) !== requestSeq) {
        return;
      }
      tab.columns = page.columns || [];
      tab.primaryKeyColumns = page.primaryKeyColumns || [];
      pruneInvalidColumnWidthState(tab);
      tab.editable = page.editable;
      tab.readOnlyReason = page.readOnlyReason || '';
      tab.hasNextPage = page.hasNext === true;
      tab.pageNo = Number(page.pageNo || tab.pageNo || 1);
      tab.pageSize = Number(page.pageSize || tab.pageSize || 1000);
      tab.rows = (page.rows || []).map((row, index) => {
        const values: Record<string, string | null> = {};
        tab.columns.forEach((column) => {
          values[column.columnName] = null;
        });
        (row.cells || []).forEach((cell) => {
          values[cell.columnName] = cell.cellValue;
        });
        return {
          rowKey: row.rowKey || `row-${tab.pageNo}-${index}`,
          values,
          originalValues: {...values},
          rowState: 'clean' as const,
        };
      });
      tab.deletedRows = [];
      tab.selectedRowKey = tab.rows[0]?.rowKey || '';
      tab.checkedRowKeys = [];
      tab.editingCellKey = '';
      tab.dirty = false;
      tab.rowDataVersion += 1;
      tab.schemaVersion += 1;
      tab.displayRowsCacheVersion = -1;
      tab.displayColumnsCacheVersion = -1;
      recomputeTableDataSearchMatches(tab);
    } catch (error) {
      if (tableDataPageRequestSeq.get(tab.key) !== requestSeq) {
        return;
      }
      if (error instanceof Error && (error.name === 'AbortError' || /abort/i.test(error.message))) {
        return;
      }
      const msg = error instanceof Error ? error.message : String(error);
      tab.errorMessage = msg;
      tab.rows = [];
      tab.deletedRows = [];
      tab.selectedRowKey = '';
      tab.checkedRowKeys = [];
      tab.editingCellKey = '';
      tab.hasNextPage = false;
      tab.dirty = false;
      tab.rowDataVersion += 1;
      tab.displayRowsCacheVersion = -1;
      recomputeTableDataSearchMatches(tab);
      message.error(`加载表数据失败: ${msg}`);
    } finally {
      clearTimeout(timeoutId);
      if (tableDataPageRequestSeq.get(tab.key) === requestSeq) {
        tab.loading = false;
        tableDataPageAbortController.delete(tab.key);
        touchTableDataTab(tab);
      }
    }
  }

  function buildPageFilters(tab: TableDataTab): TableDataPageReq['filters'] {
    const filters: TableDataPageReq['filters'] = [];
    tab.filters.forEach((item) => {
      const columnName = (item.columnName || '').trim();
      if (!columnName) {
        return;
      }
      if (VALUE_REQUIRED_OPERATORS.includes(item.operator) && !item.value.trim()) {
        return;
      }
      filters.push({
        columnName,
        operator: item.operator,
        value: item.value,
      });
    });
    return filters;
  }

  function moveTableDataSearchMatchFocus(tab: TableDataTab, delta: 1 | -1) {
    if (!tab.searchMatches.length) {
      tab.activeSearchMatchIndex = -1;
      touchTableDataTab(tab);
      return;
    }
    const currentIndex = tab.activeSearchMatchIndex >= 0 ? tab.activeSearchMatchIndex : (delta > 0 ? -1 : 0);
    const nextIndex = (currentIndex + delta + tab.searchMatches.length) % tab.searchMatches.length;
    focusTableDataSearchMatchAt(tab, nextIndex);
  }

  function focusTableDataSearchMatchAt(tab: TableDataTab, matchIndex: number) {
    if (!tab.searchMatches.length) {
      tab.activeSearchMatchIndex = -1;
      return;
    }
    const normalizedIndex = Math.min(tab.searchMatches.length - 1, Math.max(0, matchIndex));
    tab.activeSearchMatchIndex = normalizedIndex;
    const match = tab.searchMatches[normalizedIndex];
    tab.selectedRowKey = match.rowKey;
    if (tab.editingCellKey && !tab.editingCellKey.startsWith(`${match.rowKey}::`)) {
      tab.editingCellKey = '';
    }
  }

  function currentTableDataSearchMatch(tab: TableDataTab) {
    if (tab.activeSearchMatchIndex < 0 || tab.activeSearchMatchIndex >= tab.searchMatches.length) {
      return null;
    }
    return tab.searchMatches[tab.activeSearchMatchIndex] || null;
  }

  function recomputeTableDataSearchMatches(
    tab: TableDataTab,
    preferred?: { rowKey?: string; columnName?: string; moveDirectionIfMissing?: 1 | -1 },
  ) {
    const keyword = tab.searchKeyword.trim().toLowerCase();
    if (!keyword) {
      tab.searchMatches = [];
      tab.activeSearchMatchIndex = -1;
      return;
    }
    const matches: TableDataTab['searchMatches'] = [];
    tab.rows.forEach((row) => {
      tab.columns.forEach((column) => {
        const value = String(row.values[column.columnName] ?? '');
        if (!value) {
          return;
        }
        if (value.toLowerCase().includes(keyword)) {
          matches.push({
            rowKey: row.rowKey,
            columnName: column.columnName,
          });
        }
      });
    });
    tab.searchMatches = matches;
    if (!matches.length) {
      tab.activeSearchMatchIndex = -1;
      return;
    }
    if (preferred?.rowKey && preferred?.columnName) {
      const preferredIndex = matches.findIndex((item) => item.rowKey === preferred.rowKey && item.columnName === preferred.columnName);
      if (preferredIndex >= 0) {
        focusTableDataSearchMatchAt(tab, preferredIndex);
        return;
      }
      if (preferred.moveDirectionIfMissing) {
        const fallbackIndex = preferred.moveDirectionIfMissing > 0 ? 0 : matches.length - 1;
        focusTableDataSearchMatchAt(tab, fallbackIndex);
        return;
      }
    }
    if (tab.activeSearchMatchIndex >= 0 && tab.activeSearchMatchIndex < matches.length) {
      focusTableDataSearchMatchAt(tab, tab.activeSearchMatchIndex);
      return;
    }
    focusTableDataSearchMatchAt(tab, 0);
  }

  function replaceKeywordInCellValue(value: string | null, searchKeyword: string, replaceKeyword: string) {
    const source = String(value ?? '');
    const keyword = searchKeyword.trim();
    if (!keyword) {
      return value;
    }
    const escaped = keyword.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
    const nextValue = source.replace(new RegExp(escaped, 'gi'), replaceKeyword);
    return nextValue === '' ? null : nextValue;
  }

  function buildPageSorts(tab: TableDataTab): TableDataPageReq['sorts'] {
    const sorts: TableDataPageReq['sorts'] = [];
    tab.sorts.forEach((item) => {
      const columnName = (item.columnName || '').trim();
      if (!columnName) {
        return;
      }
      const direction = item.direction === 'DESC' ? 'DESC' : 'ASC';
      sorts.push({
        columnName,
        direction,
      });
    });
    return sorts;
  }

  function ensureCanSwitchPageOrFilter(tab: TableDataTab) {
    if (!tab.dirty) {
      return true;
    }
    message.warning('存在未提交改动，请先提交或撤销');
    return false;
  }

  function refreshDirtyState(tab: TableDataTab) {
    tab.dirty = tab.rows.some((item) => item.rowState !== 'clean') || tab.deletedRows.length > 0;
  }

  function clampTableDataColumnWidth(width: number) {
    const resolved = Number(width || 0);
    if (!Number.isFinite(resolved)) {
      return 132;
    }
    return Math.min(TABLE_DATA_MAX_COLUMN_WIDTH, Math.max(TABLE_DATA_MIN_COLUMN_WIDTH, Math.round(resolved)));
  }

  function pruneInvalidColumnWidthState(tab: TableDataTab) {
    const validColumnNames = new Set(tab.columns.map((item) => item.columnName));
    const nextWidthMap = Object.entries(tab.columnWidthMap).reduce<Record<string, number>>((acc, [key, value]) => {
      if (validColumnNames.has(key)) {
        acc[key] = clampTableDataColumnWidth(value);
      }
      return acc;
    }, {});
    const currentKeys = Object.keys(tab.columnWidthMap);
    const nextKeys = Object.keys(nextWidthMap);
    if (currentKeys.length === nextKeys.length && currentKeys.every((key) => nextWidthMap[key] === tab.columnWidthMap[key])) {
      return;
    }
    tab.columnWidthMap = nextWidthMap;
  }

  function touchTableDataTab(tab: TableDataTab) {
    const now = Date.now();
    tab.updatedAt = now;
    const index = runtime.tableDataTabs.value.findIndex((item) => item.key === tab.key);
    if (index < 0) {
      return;
    }
    const tabs = [...runtime.tableDataTabs.value];
    // `loadTableDataPage` 在异步过程中会持续修改传入的 tab。
    // 这里必须以当前 tab 为准回写，否则会把数组中的旧快照重新覆盖回去，
    // 导致 loading/rows 等状态停留在请求前的值。
    tabs[index] = {
      ...tab,
      updatedAt: now,
    };
    runtime.tableDataTabs.value = tabs;
  }

  function isRowEqual(a: Record<string, string | null>, b: Record<string, string | null>) {
    const keys = new Set<string>([...Object.keys(a), ...Object.keys(b)]);
    for (const key of keys) {
      if ((a[key] ?? null) !== (b[key] ?? null)) {
        return false;
      }
    }
    return true;
  }

  function buildCommitPayload(tab: TableDataTab): TableDataCommitReq {
    const inserts: TableDataCommitReq['inserts'] = tab.rows
      .filter((item) => item.rowState === 'new')
      .map((item) => ({
        cells: tab.columns.map((column) => ({
          columnName: column.columnName,
          cellValue: item.values[column.columnName] ?? null,
        })),
      }));

    const updates: TableDataCommitReq['updates'] = tab.rows
      .filter((item) => item.rowState === 'updated')
      .map((item) => {
        const changedCells = tab.columns
          .filter((column) => !isTableDataPrimaryKeyColumn(tab, column.columnName))
          .filter((column) => (item.values[column.columnName] ?? null) !== (item.originalValues[column.columnName] ?? null))
          .map((column) => ({
            columnName: column.columnName,
            cellValue: item.values[column.columnName] ?? null,
          }));

        return {
          primaryKeyValues: tab.primaryKeyColumns.map((columnName) => ({
            columnName,
            cellValue: item.originalValues[columnName] ?? null,
          })),
          cells: changedCells,
        };
      })
      .filter((item) => item.cells.length > 0);

    const deletes: TableDataCommitReq['deletes'] = tab.deletedRows.map((item) => ({
      primaryKeyValues: tab.primaryKeyColumns.map((columnName) => ({
        columnName,
        cellValue: item.values[columnName] ?? null,
      })),
    }));

    return {
      connectionId: tab.connectionId,
      databaseName: tab.databaseName,
      tableName: tab.tableName,
      objectType: tab.objectType,
      inserts,
      updates,
      deletes,
    };
  }

  return {
    tableDataFilterOperatorOptions,
    tableDataSortDirectionOptions,
    closeTableDataTab,
    openTableDataTabByObject,
    reloadTableDataForTab,
    toggleTableDataFilterPanel,
    toggleTableDataDetailCollapsed,
    addTableDataFilter,
    addTableDataFilterForColumn,
    removeTableDataFilter,
    addTableDataSort,
    removeTableDataSort,
    tableDataSortDirectionForColumn,
    applyTableDataQuickSort,
    openTableDataSearchPanel,
    closeTableDataSearchPanel,
    toggleTableDataReplacePanel,
    updateTableDataSearchKeyword,
    updateTableDataReplaceKeyword,
    focusNextTableDataSearchMatch,
    focusPrevTableDataSearchMatch,
    replaceCurrentTableDataSearchMatch,
    replaceAllTableDataSearchMatches,
    applyTableDataFilters,
    prevTableDataPage,
    nextTableDataPage,
    updateTableDataPageSize,
    selectTableDataRow,
    toggleTableDataRowChecked,
    toggleAllTableDataRowsChecked,
    startTableDataCellEdit,
    stopTableDataCellEdit,
    isTableDataCellEditing,
    updateTableDataCell,
    tableDataColumnEditorType,
    selectedTableDataRow,
    addTableDataRow,
    deleteSelectedTableDataRow,
    submitTableDataChanges,
    discardTableDataChanges,
    tableDataDisplayRows,
    tableDataDisplayColumns,
    updateTableDataColumnWidth,
    tableDataScrollX,
    isTableDataPrimaryKeyColumn,
    handleTableDataConnectionSelectorChange,
    handleTableDataDatabaseSelectorChange,
  };
}
