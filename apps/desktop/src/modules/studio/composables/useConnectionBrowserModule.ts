import {message} from 'ant-design-vue';
import type {StudioRuntime} from './useStudioRuntime';

type ObjectRow = StudioRuntime['objectRows']['value'][number];

type ContextAction =
  | 'edit'
  | 'test'
  | 'sync'
  | 'delete'
  | 'revectorize'
  | 'interruptVectorize'
  | 'viewVectorizedData'
  | 'queryData'
  | 'vectorizeTable'
  | 'editTable'
  | 'dropTable'
  | 'truncateTable';

export interface ConnectionBrowserModule {
  activateBrowserTab: () => void;
  openCreateModal: () => void;
  openEditModal: (targetConnectionId?: number) => void;
  closeContextMenu: () => void;
  triggerContextAction: (action: ContextAction) => Promise<void>;
  onObjectRow: (record: ObjectRow) => {
    onClick: () => void;
    onDblclick: () => void;
    onContextmenu: (event: MouseEvent) => void;
  };
}

interface ConnectionBrowserDeps {
  openEditTableEditor: (connectionId: number, databaseName: string, tableName: string) => Promise<void>;
}

export function useConnectionBrowserModule(
  runtime: StudioRuntime,
  deps: ConnectionBrowserDeps,
): ConnectionBrowserModule {
  function activateBrowserTab() {
    runtime.activeWorkbenchTab.value = runtime.browserTabKey;
  }

  function closeContextMenu() {
    runtime.contextMenu.visible = false;
    runtime.contextMenu.targetType = 'none';
    runtime.contextMenu.databaseName = '';
    runtime.contextMenu.objectType = '';
    runtime.contextMenu.objectName = '';
  }

  function openCreateModal() {
    closeContextMenu();
    runtime.resetConnectionForm();
    runtime.connectionPreviewDbOptions.value = [];
    runtime.connectionPreviewError.value = '';
    runtime.isEditMode.value = false;
    runtime.editingConnectionId.value = null;
    runtime.createModalOpen.value = true;
  }

  function openEditModal(targetConnectionId?: number) {
    closeContextMenu();
    if (targetConnectionId) {
      runtime.workflow.connectionId = targetConnectionId;
    }
    runtime.ensureConnection();
    const current = runtime.connections.value.find((item) => item.id === runtime.workflow.connectionId);
    if (!current) {
      message.warning('请先选择连接');
      return;
    }
    runtime.fillConnectionForm(current);
    runtime.connectionPreviewDbOptions.value = runtime.databaseListCache.value[current.id] ?? [];
    runtime.connectionPreviewError.value = '';
    runtime.isEditMode.value = true;
    runtime.editingConnectionId.value = current.id;
    runtime.createModalOpen.value = true;
  }

  async function triggerContextAction(action: ContextAction) {
    const id = runtime.contextMenu.connectionId;
    const databaseName = runtime.contextMenu.databaseName;
    const targetType = runtime.contextMenu.targetType;
    const objectType = runtime.contextMenu.objectType;
    const objectName = runtime.contextMenu.objectName;
    closeContextMenu();
    if (!id) {
      return;
    }
    if (action === 'edit') {
      openEditModal(id);
      return;
    }
    if (action === 'test') {
      await runtime.testConnection(id);
      return;
    }
    if (action === 'sync') {
      await runtime.syncSchema(id);
      return;
    }
    if (action === 'delete') {
      await runtime.removeConnection(id);
      return;
    }
    if (action === 'queryData') {
      if (targetType !== 'object' || !objectName || (objectType !== 'tables' && objectType !== 'views')) {
        return;
      }
      const rowVectorizeRecord = runtime.getDatabaseVectorizeStatusRecord(id, databaseName || '');
      runtime.openQueryTabByObject({
        objectName,
        objectType,
        rowEstimate: 0,
        tableSize: '-',
        description: '',
        vectorizeStatus: rowVectorizeRecord?.status || 'NOT_VECTORIZED',
        vectorizeMessage: rowVectorizeRecord?.message,
        vectorizeUpdatedAt: rowVectorizeRecord?.updatedAt,
      }, true);
      return;
    }
    if (action === 'vectorizeTable') {
      if (targetType !== 'object' || !objectName || objectType !== 'tables' || !databaseName) {
        return;
      }
      await runtime.vectorizeSingleTable(id, databaseName, objectName);
      return;
    }
    if (action === 'editTable') {
      if (targetType !== 'object' || !objectName || objectType !== 'tables' || !databaseName) {
        return;
      }
      await deps.openEditTableEditor(id, databaseName, objectName);
      return;
    }
    if (action === 'truncateTable') {
      if (targetType !== 'object' || !objectName || objectType !== 'tables' || !databaseName) {
        return;
      }
      runtime.truncateTableName.value = objectName;
      runtime.truncateTableModalOpen.value = true;
      return;
    }
    if (action === 'dropTable') {
      if (targetType !== 'object' || !objectName || objectType !== 'tables' || !databaseName) {
        return;
      }
      runtime.dropTableName.value = objectName;
      runtime.dropTableModalOpen.value = true;
      return;
    }
    if (action === 'revectorize') {
      if (targetType !== 'database' || !databaseName) {
        return;
      }
      if (runtime.isDatabaseVectorizing(id, databaseName)) {
        message.info('该数据库正在向量化，请等待当前任务完成');
        return;
      }
      await runtime.enqueueDatabaseRevectorize(id, databaseName);
      return;
    }
    if (action === 'interruptVectorize') {
      if (targetType !== 'database' || !databaseName) {
        return;
      }
      await runtime.interruptDatabaseVectorize(id, databaseName);
      return;
    }
    if (action === 'viewVectorizedData') {
      if (targetType !== 'database' || !databaseName) {
        return;
      }
      await runtime.openVectorizeOverview(id, databaseName);
    }
  }

  function onObjectRow(record: ObjectRow) {
    return {
      onClick: () => {
        closeContextMenu();
        const databaseName = runtime.getActiveDatabaseName(runtime.workflow.connectionId);
        void runtime.runSafely(async () => {
          await runtime.selectObject(runtime.workflow.connectionId, databaseName, record.objectType, record.objectName);
        });
      },
      onDblclick: () => {
        runtime.openQueryTabByObject(record);
      },
      onContextmenu: (event: MouseEvent) => {
        event.preventDefault();
        event.stopPropagation();
        closeContextMenu();
        const databaseName = runtime.getActiveDatabaseName(runtime.workflow.connectionId);
        void runtime.runSafely(async () => {
          await runtime.selectObject(runtime.workflow.connectionId, databaseName, record.objectType, record.objectName);
        });
        runtime.contextMenu.visible = true;
        runtime.contextMenu.x = Math.min(event.clientX, window.innerWidth - 220);
        runtime.contextMenu.y = Math.min(event.clientY, window.innerHeight - 180);
        runtime.contextMenu.targetType = 'object';
        runtime.contextMenu.connectionId = runtime.workflow.connectionId;
        runtime.contextMenu.databaseName = databaseName;
        runtime.contextMenu.objectType = record.objectType;
        runtime.contextMenu.objectName = record.objectName;
      },
    };
  }

  return {
    activateBrowserTab,
    openCreateModal,
    openEditModal,
    closeContextMenu,
    triggerContextAction,
    onObjectRow,
  };
}
