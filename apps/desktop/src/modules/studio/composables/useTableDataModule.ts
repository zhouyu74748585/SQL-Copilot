import {message} from 'ant-design-vue';
import {postApi} from '../../../api/client';
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

type TableDataEditorType = 'text' | 'date' | 'datetime' | 'time';

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
  removeTableDataFilter: (tab: TableDataTab, filterKey: string) => void;
  addTableDataSort: (tab: TableDataTab) => void;
  removeTableDataSort: (tab: TableDataTab, sortKey: string) => void;
  applyTableDataFilters: (tab: TableDataTab) => Promise<void>;
  prevTableDataPage: (tab: TableDataTab) => Promise<void>;
  nextTableDataPage: (tab: TableDataTab) => Promise<void>;
  updateTableDataPageSize: (tab: TableDataTab, pageSize: number) => Promise<void>;
  selectTableDataRow: (tab: TableDataTab, rowKey: string) => void;
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
  tableDataScrollX: (tab: TableDataTab) => number;
  isTableDataPrimaryKeyColumn: (tab: TableDataTab, columnName: string) => boolean;
  handleTableDataConnectionSelectorChange: (tab: TableDataTab, connectionId: number) => Promise<void>;
  handleTableDataDatabaseSelectorChange: (tab: TableDataTab, databaseName: string) => Promise<void>;
}

export function useTableDataModule(runtime: StudioRuntime): TableDataModule {
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
    if (record.objectType !== 'tables') {
      return;
    }
    const connectionId = options?.connectionId ?? runtime.workflow.connectionId;
    const databaseName = (options?.databaseName ?? runtime.getActiveDatabaseName(connectionId)).trim();
    if (!connectionId || !databaseName) {
      message.warning('请先选择连接和数据库');
      return;
    }
    const existing = runtime.tableDataTabs.value.find(
      (item) => item.connectionId === connectionId && item.databaseName === databaseName && item.tableName === record.objectName,
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
      editingCellKey: '',
      detailCollapsed: false,
      filterPanelVisible: false,
      filters: [],
      sorts: [],
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
      message.warning(tab.readOnlyReason || '当前表不可编辑');
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
    touchTableDataTab(tab);
  }

  function deleteSelectedTableDataRow(tab: TableDataTab) {
    if (!tab.editable) {
      message.warning(tab.readOnlyReason || '当前表不可编辑');
      return;
    }
    if (!tab.selectedRowKey) {
      message.info('请先选择要删除的数据行');
      return;
    }
    const index = tab.rows.findIndex((item) => item.rowKey === tab.selectedRowKey);
    if (index < 0) {
      message.info('未找到选中行');
      return;
    }
    const target = tab.rows[index];
    if (target.rowState !== 'new') {
      tab.deletedRows = [
        ...tab.deletedRows,
        {
          rowKey: target.rowKey,
          values: {...target.values},
        },
      ];
    }
    tab.rows = tab.rows.filter((item) => item.rowKey !== target.rowKey);
    tab.selectedRowKey = tab.rows[0]?.rowKey || '';
    tab.editingCellKey = '';
    refreshDirtyState(tab);
    tab.rowDataVersion += 1;
    tab.displayRowsCacheVersion = -1;
    touchTableDataTab(tab);
  }

  async function submitTableDataChanges(tab: TableDataTab) {
    if (!tab.editable) {
      message.warning(tab.readOnlyReason || '当前表不可编辑');
      return;
    }
    if (!tab.dirty) {
      message.info('当前无待提交变更');
      return;
    }

    const payload = buildCommitPayload(tab);
    if (!payload.inserts.length && !payload.updates.length && !payload.deletes.length) {
      tab.dirty = false;
      touchTableDataTab(tab);
      message.info('未检测到有效变更');
      return;
    }

    tab.submitting = true;
    touchTableDataTab(tab);
    try {
      const result = await postApi<TableDataCommitVO>('/api/schema/table/data/commit', payload);
      message.success(`提交成功：新增 ${result.insertedCount}，更新 ${result.updatedCount}，删除 ${result.deletedCount}`);
      await loadTableDataPage(tab);
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
      width: 132,
      ellipsis: true,
      columnType: column.columnType,
      columnComment: column.columnComment,
    }));
    tableDataColumnsCache.set(tab.key, {version: tab.schemaVersion, columns});
    return columns;
  }

  function tableDataScrollX(tab: TableDataTab) {
    return Math.max(tab.columns.length * 132, 960);
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
      tab.errorMessage = '未选择数据库';
      tab.readOnlyReason = '未选择数据库';
      tab.editable = false;
      tab.deletedRows = [];
      tab.selectedRowKey = '';
      tab.editingCellKey = '';
      tab.sorts = [];
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
      tab.editingCellKey = '';
      tab.dirty = false;
      tab.rowDataVersion += 1;
      tab.schemaVersion += 1;
      tab.displayRowsCacheVersion = -1;
      tab.displayColumnsCacheVersion = -1;
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
      tab.editingCellKey = '';
      tab.hasNextPage = false;
      tab.dirty = false;
      tab.rowDataVersion += 1;
      tab.displayRowsCacheVersion = -1;
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

  function touchTableDataTab(tab: TableDataTab) {
    tab.updatedAt = Date.now();
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
    removeTableDataFilter,
    addTableDataSort,
    removeTableDataSort,
    applyTableDataFilters,
    prevTableDataPage,
    nextTableDataPage,
    updateTableDataPageSize,
    selectTableDataRow,
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
    tableDataScrollX,
    isTableDataPrimaryKeyColumn,
    handleTableDataConnectionSelectorChange,
    handleTableDataDatabaseSelectorChange,
  };
}
