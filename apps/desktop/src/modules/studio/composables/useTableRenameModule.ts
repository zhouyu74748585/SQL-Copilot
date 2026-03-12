import {message} from 'ant-design-vue';
import {postApi} from '../../../api/client';
import type {TableRenameReq, TableRenameVO} from '../../../types';
import type {StudioRuntime} from './useStudioRuntime';

export interface TableRenameModule {
  openRenameTableModal: (source?: {
    connectionId: number;
    databaseName: string;
    tableName: string;
  } | null) => void;
  closeRenameTableModal: () => void;
  confirmRenameTable: () => Promise<void>;
}

export function useTableRenameModule(runtime: StudioRuntime): TableRenameModule {
  function resetRenameTableForm() {
    runtime.renameTableForm.connectionId = 0;
    runtime.renameTableForm.databaseName = '';
    runtime.renameTableForm.sourceTableName = '';
    runtime.renameTableForm.targetTableName = '';
  }

  function resolveRenameSource() {
    const sourceConnectionId = runtime.contextMenu.connectionId || runtime.workflow.connectionId;
    const sourceDatabaseName = runtime.contextMenu.databaseName || runtime.getActiveDatabaseName(sourceConnectionId);
    const sourceTableName = runtime.contextMenu.objectName || runtime.selectedObjectName.value;
    if (!sourceConnectionId || !sourceDatabaseName || !sourceTableName) {
      return null;
    }
    return {
      connectionId: sourceConnectionId,
      databaseName: sourceDatabaseName,
      tableName: sourceTableName,
    };
  }

  function openRenameTableModal(source?: {
    connectionId: number;
    databaseName: string;
    tableName: string;
  } | null) {
    const resolved = source ?? resolveRenameSource();
    if (!resolved) {
      message.warning('请先选择需要重命名的表');
      return;
    }
    runtime.renameTableForm.connectionId = resolved.connectionId;
    runtime.renameTableForm.databaseName = resolved.databaseName;
    runtime.renameTableForm.sourceTableName = resolved.tableName;
    runtime.renameTableForm.targetTableName = resolved.tableName;
    runtime.renameTableModalOpen.value = true;
  }

  function closeRenameTableModal() {
    runtime.renameTableModalOpen.value = false;
    runtime.renameTableSubmitting.value = false;
    resetRenameTableForm();
  }

  function applyRenamedTableState(payload: TableRenameVO, req: TableRenameReq) {
    const connectionId = req.connectionId;
    const databaseName = (payload.databaseName || req.databaseName || '').trim();
    const sourceTableName = payload.sourceTableName;
    const targetTableName = payload.targetTableName;

    runtime.tableEditorTabs.value.forEach((tab) => {
      if (tab.connectionId !== connectionId || tab.databaseName !== databaseName || tab.tableName !== sourceTableName) {
        return;
      }
      tab.tableName = targetTableName;
      tab.title = targetTableName;
      if (tab.tableDetail) {
        tab.tableDetail = {
          ...tab.tableDetail,
          tableName: targetTableName,
        };
      }
      if (tab.draft) {
        tab.draft = {
          ...tab.draft,
          tableName: targetTableName,
        };
      }
      if (tab.baselineDraft) {
        tab.baselineDraft = {
          ...tab.baselineDraft,
          tableName: targetTableName,
        };
      }
      tab.updatedAt = Date.now();
    });

    runtime.tableDataTabs.value.forEach((tab) => {
      if (tab.connectionId !== connectionId || tab.databaseName !== databaseName || tab.tableName !== sourceTableName) {
        return;
      }
      tab.tableName = targetTableName;
      tab.title = `数据 · ${targetTableName}`;
      tab.updatedAt = Date.now();
    });

    const clipboard = runtime.tableCopyClipboard.value;
    if (clipboard
      && clipboard.sourceConnectionId === connectionId
      && clipboard.sourceDatabaseName === databaseName
      && clipboard.sourceTableName === sourceTableName) {
      runtime.tableCopyClipboard.value = {
        ...clipboard,
        sourceTableName: targetTableName,
        copiedAt: Date.now(),
      };
    }

    if (runtime.contextMenu.targetType === 'object'
      && runtime.contextMenu.connectionId === connectionId
      && runtime.contextMenu.databaseName === databaseName
      && runtime.contextMenu.objectType === 'tables'
      && runtime.contextMenu.objectName === sourceTableName) {
      runtime.contextMenu.objectName = targetTableName;
    }
  }

  async function confirmRenameTable() {
    const req: TableRenameReq = {
      connectionId: runtime.renameTableForm.connectionId,
      databaseName: runtime.renameTableForm.databaseName.trim(),
      sourceTableName: runtime.renameTableForm.sourceTableName.trim(),
      targetTableName: runtime.renameTableForm.targetTableName.trim(),
    };
    if (!req.connectionId || !req.databaseName || !req.sourceTableName || !req.targetTableName) {
      message.warning('请完整填写表重命名信息');
      return;
    }
    if (req.sourceTableName === req.targetTableName) {
      message.warning('新表名不能与原表名相同');
      return;
    }

    runtime.renameTableSubmitting.value = true;
    try {
      const result = await postApi<TableRenameVO>('/api/schema/table/rename', req);
      applyRenamedTableState(result, req);
      runtime.invalidateDatabaseMetadataCaches(req.connectionId, req.databaseName);
      await runtime.prepareConnectionTreeData(req.connectionId);
      if (runtime.workflow.connectionId === req.connectionId
        && runtime.getActiveDatabaseName(req.connectionId) === req.databaseName
        && runtime.currentObjectType.value === 'tables') {
        await runtime.refreshCurrentObjects();
      }
      const shouldRefreshSelectedTable =
        runtime.workflow.connectionId === req.connectionId
        && runtime.getActiveDatabaseName(req.connectionId) === req.databaseName
        && runtime.selectedObjectName.value === req.sourceTableName;
      if (runtime.selectedTreeDetail.value?.kind === 'object'
        && runtime.selectedTreeDetail.value.connectionId === req.connectionId
        && runtime.selectedTreeDetail.value.databaseName === req.databaseName
        && runtime.selectedTreeDetail.value.objectType === 'tables'
        && runtime.selectedTreeDetail.value.objectName === req.sourceTableName) {
        runtime.selectedTreeKeys.value = [
          runtime.buildObjectNodeKey(req.connectionId, req.databaseName, 'tables', req.targetTableName),
        ];
      }
      if (shouldRefreshSelectedTable) {
        await runtime.selectObject(req.connectionId, req.databaseName, 'tables', req.targetTableName);
      }
      message.success(result.message || '表重命名成功');
      closeRenameTableModal();
    } catch (error) {
      runtime.renameTableSubmitting.value = false;
      const msg = error instanceof Error ? error.message : String(error);
      message.error(`表重命名失败: ${msg}`);
    }
  }

  return {
    openRenameTableModal,
    closeRenameTableModal,
    confirmRenameTable,
  };
}
