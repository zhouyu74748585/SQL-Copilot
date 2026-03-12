import {message} from 'ant-design-vue';
import {onBeforeUnmount} from 'vue';
import {getApi, postApi} from '../../../api/client';
import type {TableCopyMode, TableCopyReq, TableCopyTaskVO, TableCopyVO} from '../../../types';
import type {StudioRuntime} from './useStudioRuntime';

type CopyableTableSelection = {
  connectionId: number;
  databaseName: string;
  tableName: string;
  dbType: string;
};

type PasteTargetSelection = {
  connectionId: number;
  databaseName: string;
  dbType: string;
};

export interface TableCopyModule {
  copyTableWithinCurrentDatabase: (mode: TableCopyMode, source?: CopyableTableSelection | null) => Promise<void>;
  handleBrowserClipboardKeydown: (event: KeyboardEvent) => void;
  confirmTablePaste: () => Promise<void>;
  closeTablePasteModal: () => void;
  closeTableCopyTaskModal: () => void;
}

export function useTableCopyModule(runtime: StudioRuntime): TableCopyModule {
  let taskPollingTimer: number | null = null;
  let taskCompletionHandled = false;

  function closeTablePasteModal() {
    runtime.tablePasteModalOpen.value = false;
    runtime.tablePasteSubmitting.value = false;
  }

  function closeTableCopyTaskModal() {
    const status = runtime.tableCopyTaskInfo.value?.status || '';
    if (status === 'PENDING' || status === 'RUNNING') {
      return;
    }
    runtime.tableCopyTaskModalOpen.value = false;
  }

  function stopTaskPolling() {
    if (taskPollingTimer !== null) {
      window.clearInterval(taskPollingTimer);
      taskPollingTimer = null;
    }
  }

  function normalizeDatabaseName(databaseName: string) {
    return databaseName.trim().toLowerCase();
  }

  function resolveConnectionDbType(connectionId: number) {
    return (runtime.connections.value.find((item) => item.id === connectionId)?.dbType || '').trim().toUpperCase();
  }

  function resolveSelectedTableForCopy(): CopyableTableSelection | null {
    const treeDetail = runtime.selectedTreeDetail.value;
    if (treeDetail?.kind === 'object' && treeDetail.objectType === 'tables') {
      return {
        connectionId: treeDetail.connectionId,
        databaseName: treeDetail.databaseName,
        tableName: treeDetail.objectName,
        dbType: resolveConnectionDbType(treeDetail.connectionId),
      };
    }
    const connectionId = runtime.workflow.connectionId;
    const databaseName = runtime.getActiveDatabaseName(connectionId);
    const object = runtime.selectedObjectRecord.value;
    if (!connectionId || !databaseName || object?.objectType !== 'tables') {
      return null;
    }
    return {
      connectionId,
      databaseName,
      tableName: object.objectName,
      dbType: resolveConnectionDbType(connectionId),
    };
  }

  function resolveContextMenuTable(): CopyableTableSelection | null {
    if (runtime.contextMenu.targetType !== 'object'
      || runtime.contextMenu.objectType !== 'tables'
      || !runtime.contextMenu.connectionId
      || !runtime.contextMenu.databaseName
      || !runtime.contextMenu.objectName) {
      return null;
    }
    return {
      connectionId: runtime.contextMenu.connectionId,
      databaseName: runtime.contextMenu.databaseName,
      tableName: runtime.contextMenu.objectName,
      dbType: resolveConnectionDbType(runtime.contextMenu.connectionId),
    };
  }

  function resolvePasteTarget(): PasteTargetSelection | null {
    const treeDetail = runtime.selectedTreeDetail.value;
    if (treeDetail?.kind === 'database' || treeDetail?.kind === 'category' || treeDetail?.kind === 'object') {
      const dbType = resolveConnectionDbType(treeDetail.connectionId);
      return {
        connectionId: treeDetail.connectionId,
        databaseName: treeDetail.databaseName,
        dbType,
      };
    }
    const connectionId = runtime.workflow.connectionId;
    const databaseName = runtime.getActiveDatabaseName(connectionId);
    if (!connectionId || !databaseName) {
      return null;
    }
    return {
      connectionId,
      databaseName,
      dbType: resolveConnectionDbType(connectionId),
    };
  }

  function buildCopiedTableName(sourceTableName: string) {
    return `${sourceTableName}_copy`;
  }

  function shouldHandleBrowserShortcut(event: KeyboardEvent) {
    if (runtime.activeWorkbenchTab.value !== runtime.browserTabKey) {
      return false;
    }
    if (!(event.metaKey || event.ctrlKey) || event.altKey) {
      return false;
    }
    const target = event.target as HTMLElement | null;
    if (!target) {
      return true;
    }
    if (target.isContentEditable) {
      return false;
    }
    const tagName = target.tagName.toUpperCase();
    if (tagName === 'INPUT' || tagName === 'TEXTAREA' || tagName === 'SELECT') {
      return false;
    }
    return !target.closest('.monaco-editor');
  }

  function stashTableClipboard(selection: CopyableTableSelection, preferredCopyMode: TableCopyMode) {
    runtime.tableCopyClipboard.value = {
      sourceConnectionId: selection.connectionId,
      sourceDatabaseName: selection.databaseName,
      sourceTableName: selection.tableName,
      sourceDbType: selection.dbType,
      preferredCopyMode,
      copiedAt: Date.now(),
    };
    message.success(
      preferredCopyMode === 'STRUCTURE_ONLY'
        ? `已记录表 ${selection.tableName}（仅复制结构）`
        : `已记录表 ${selection.tableName}（复制结构和数据）`,
    );
  }

  async function refreshAndFocusCopiedTable(connectionId: number, databaseName: string, tableName: string) {
    runtime.activeWorkbenchTab.value = runtime.browserTabKey;
    runtime.workflow.connectionId = connectionId;
    runtime.activeDatabaseMap.value = {
      ...runtime.activeDatabaseMap.value,
      [connectionId]: databaseName,
    };
    runtime.currentObjectType.value = 'tables';
    runtime.expandConnectionNode(connectionId);
    runtime.expandCategoryNode(connectionId, databaseName, 'tables');
    await runtime.prepareConnectionTreeData(connectionId);
    await runtime.loadOverview();
    runtime.selectedTreeKeys.value = [runtime.buildObjectNodeKey(connectionId, databaseName, 'tables', tableName)];
    await runtime.selectObject(connectionId, databaseName, 'tables', tableName);
  }

  function initTaskModal(copyResult: TableCopyVO) {
    runtime.tableCopyTaskModalOpen.value = true;
    runtime.tableCopyTaskInfo.value = {
      taskId: copyResult.taskId || '',
      status: 'PENDING',
      stage: 'PENDING',
      message: copyResult.message || '等待执行',
      progressPercent: 0,
      copiedRows: 0,
      totalRows: 0,
      sourceConnectionId: runtime.tablePasteForm.sourceConnectionId,
      sourceDatabaseName: runtime.tablePasteForm.sourceDatabaseName,
      sourceTableName: runtime.tablePasteForm.sourceTableName,
      targetConnectionId: copyResult.targetConnectionId,
      targetDatabaseName: copyResult.targetDatabaseName,
      targetTableName: copyResult.targetTableName,
      copyMode: copyResult.copyMode,
      updatedAt: Date.now(),
    };
  }

  async function handleTaskCompletion(taskInfo: TableCopyTaskVO) {
    if (taskCompletionHandled) {
      return;
    }
    taskCompletionHandled = true;
    if (taskInfo.status === 'SUCCESS') {
      message.success(taskInfo.message || '表复制完成');
      await refreshAndFocusCopiedTable(
        taskInfo.targetConnectionId,
        taskInfo.targetDatabaseName || '',
        taskInfo.targetTableName,
      );
      return;
    }
    message.error(taskInfo.message || '表复制失败');
  }

  async function pollTask(taskId: string) {
    try {
      const taskInfo = await getApi<TableCopyTaskVO>(`/api/schema/table/copy/task?taskId=${encodeURIComponent(taskId)}`);
      runtime.tableCopyTaskInfo.value = taskInfo;
      if (taskInfo.status === 'SUCCESS' || taskInfo.status === 'FAILED') {
        stopTaskPolling();
        await handleTaskCompletion(taskInfo);
      }
    } catch (error) {
      const msg = error instanceof Error ? error.message : String(error);
      runtime.tableCopyTaskInfo.value = {
        ...(runtime.tableCopyTaskInfo.value as TableCopyTaskVO),
        status: 'FAILED',
        stage: 'FAILED',
        message: `任务轮询失败: ${msg}`,
        updatedAt: Date.now(),
      };
      stopTaskPolling();
      await handleTaskCompletion(runtime.tableCopyTaskInfo.value);
    }
  }

  function startTaskPolling(copyResult: TableCopyVO) {
    const taskId = (copyResult.taskId || '').trim();
    if (!taskId) {
      message.error('复制任务缺少 taskId');
      return;
    }
    stopTaskPolling();
    taskCompletionHandled = false;
    initTaskModal(copyResult);
    void pollTask(taskId);
    taskPollingTimer = window.setInterval(() => {
      void pollTask(taskId);
    }, 1000);
  }

  async function executeCopyRequest(req: TableCopyReq, options?: { fromPasteModal?: boolean }) {
    try {
      if (options?.fromPasteModal) {
        runtime.tablePasteSubmitting.value = true;
      }
      const result = await postApi<TableCopyVO>('/api/schema/table/copy', req);
      if (result.async) {
        closeTablePasteModal();
        startTaskPolling(result);
        message.success(result.message || '复制任务已创建');
        return;
      }
      closeTablePasteModal();
      await refreshAndFocusCopiedTable(
        result.targetConnectionId,
        result.targetDatabaseName || req.targetDatabaseName || '',
        result.targetTableName,
      );
      message.success(
        req.copyMode === 'STRUCTURE_ONLY' ? '表结构复制成功' : '表结构和数据复制成功',
      );
    } catch (error) {
      const msg = error instanceof Error ? error.message : String(error);
      message.error(msg);
    } finally {
      runtime.tablePasteSubmitting.value = false;
    }
  }

  async function copyTableWithinCurrentDatabase(mode: TableCopyMode, source?: CopyableTableSelection | null) {
    const tableSelection = source ?? resolveContextMenuTable() ?? resolveSelectedTableForCopy();
    if (!tableSelection) {
      message.warning('请先选择表');
      return;
    }
    await executeCopyRequest({
      sourceConnectionId: tableSelection.connectionId,
      sourceDatabaseName: tableSelection.databaseName,
      sourceTableName: tableSelection.tableName,
      targetConnectionId: tableSelection.connectionId,
      targetDatabaseName: tableSelection.databaseName,
      targetTableName: buildCopiedTableName(tableSelection.tableName),
      copyMode: mode,
    });
  }

  function openCrossScopePasteModal(target: PasteTargetSelection) {
    const clipboard = runtime.tableCopyClipboard.value;
    if (!clipboard) {
      return;
    }
    runtime.tablePasteForm.sourceConnectionId = clipboard.sourceConnectionId;
    runtime.tablePasteForm.sourceDatabaseName = clipboard.sourceDatabaseName;
    runtime.tablePasteForm.sourceTableName = clipboard.sourceTableName;
    runtime.tablePasteForm.sourceDbType = clipboard.sourceDbType;
    runtime.tablePasteForm.targetConnectionId = target.connectionId;
    runtime.tablePasteForm.targetDatabaseName = target.databaseName;
    runtime.tablePasteForm.targetTableName = buildCopiedTableName(clipboard.sourceTableName);
    runtime.tablePasteForm.preferredCopyMode = clipboard.preferredCopyMode;
    runtime.tablePasteForm.copyData = clipboard.preferredCopyMode === 'STRUCTURE_AND_DATA';
    runtime.tablePasteModalOpen.value = true;
  }

  async function pasteClipboardToTarget(target: PasteTargetSelection) {
    const clipboard = runtime.tableCopyClipboard.value;
    if (!clipboard) {
      message.info('请先复制源表');
      return;
    }
    if (clipboard.sourceDbType !== target.dbType) {
      message.error('跨库复制要求源连接与目标连接为相同数据库类型');
      return;
    }
    const sameScope = clipboard.sourceConnectionId === target.connectionId
      && normalizeDatabaseName(clipboard.sourceDatabaseName) === normalizeDatabaseName(target.databaseName);
    if (sameScope) {
      await executeCopyRequest({
        sourceConnectionId: clipboard.sourceConnectionId,
        sourceDatabaseName: clipboard.sourceDatabaseName,
        sourceTableName: clipboard.sourceTableName,
        targetConnectionId: target.connectionId,
        targetDatabaseName: target.databaseName,
        targetTableName: buildCopiedTableName(clipboard.sourceTableName),
        copyMode: clipboard.preferredCopyMode,
      });
      return;
    }
    openCrossScopePasteModal(target);
  }

  async function confirmTablePaste() {
    if (!runtime.tablePasteForm.targetTableName.trim()) {
      message.warning('请输入目标表名');
      return;
    }
    const copyMode = runtime.tablePasteForm.preferredCopyMode === 'STRUCTURE_ONLY'
      ? 'STRUCTURE_ONLY'
      : (runtime.tablePasteForm.copyData ? 'STRUCTURE_AND_DATA' : 'STRUCTURE_ONLY');
    await executeCopyRequest({
      sourceConnectionId: runtime.tablePasteForm.sourceConnectionId,
      sourceDatabaseName: runtime.tablePasteForm.sourceDatabaseName,
      sourceTableName: runtime.tablePasteForm.sourceTableName,
      targetConnectionId: runtime.tablePasteForm.targetConnectionId,
      targetDatabaseName: runtime.tablePasteForm.targetDatabaseName,
      targetTableName: runtime.tablePasteForm.targetTableName.trim(),
      copyMode,
    }, {
      fromPasteModal: true,
    });
  }

  function handleBrowserClipboardKeydown(event: KeyboardEvent) {
    if (!shouldHandleBrowserShortcut(event)) {
      return;
    }
    const key = event.key.toLowerCase();
    if (key === 'c' && !event.shiftKey) {
      const selection = resolveSelectedTableForCopy();
      if (!selection) {
        return;
      }
      event.preventDefault();
      stashTableClipboard(selection, 'STRUCTURE_AND_DATA');
      return;
    }
    if (key === 'v' && !event.shiftKey) {
      const target = resolvePasteTarget();
      if (!target) {
        message.info('请先选择目标数据库');
        return;
      }
      event.preventDefault();
      void pasteClipboardToTarget(target);
    }
  }

  onBeforeUnmount(() => {
    stopTaskPolling();
  });

  return {
    copyTableWithinCurrentDatabase,
    handleBrowserClipboardKeydown,
    confirmTablePaste,
    closeTablePasteModal,
    closeTableCopyTaskModal,
  };
}
