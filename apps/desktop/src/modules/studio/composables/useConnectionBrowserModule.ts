import {Modal, message} from 'ant-design-vue';
import {computed} from 'vue';
import type {ComputedRef} from 'vue';
import {postApi} from '../../../api/client';
import type {
  ConnectionDbTypeVO,
  SavedQueryRemoveReq,
  SchemaDatabaseCreateReq,
  SchemaNamespaceCreateReq,
  SchemaNamespaceDropReq,
  SchemaNamespaceOperationVO,
  SchemaNamespaceRenameReq,
  SchemaSchemaCreateReq,
  SchemaObjectDropReq,
  SchemaObjectDropVO,
} from '../../../types';
import type {StudioRuntime} from './useStudioRuntime';

type ObjectRow = StudioRuntime['objectRows']['value'][number];

type ContextAction =
  | 'createGroup'
  | 'renameGroup'
  | 'deleteGroup'
  | 'createConnection'
  | 'editConnection'
  | 'testConnection'
  | 'disconnectConnection'
  | 'syncSchema'
  | 'deleteConnection'
  | 'createDatabase'
  | 'createSchema'
  | 'createNamespace'
  | 'renameNamespace'
  | 'dropNamespace'
  | 'createTable'
  | 'createView'
  | 'createFunction'
  | 'createQuery'
  | 'querySql'
  | 'browseData'
  | 'vectorizeTable'
  | 'editTable'
  | 'editDefinition'
  | 'dropObject'
  | 'copyTableStructure'
  | 'copyTableStructureAndData'
  | 'renameTable'
  | 'dropTable'
  | 'truncateTable'
  | 'editSavedQuery'
  | 'deleteSavedQuery'
  | 'revectorize'
  | 'interruptVectorize'
  | 'viewVectorizedData'
  | 'copyKeyName'
  | 'createKey'
  | 'editKey'
  | 'deleteKey';

type TreeNodeData = {
  nodeType?: string;
  title?: string;
  connectionId?: number;
  databaseName?: string;
  objectType?: ObjectRow['objectType'];
  objectName?: string;
};

type ContextMenuActionItem = {
  id: ContextAction;
  label: string;
  danger?: boolean;
  disabled?: boolean;
  children?: ContextMenuActionItem[];
};

export interface ConnectionBrowserModule {
  activateBrowserTab: () => void;
  toggleBrowserDetailCollapsed: () => void;
  openCreateModal: () => void;
  openEditModal: (targetConnectionId?: number) => void;
  openNamespaceCreateModal: (connectionId: number, options?: {
    namespaceLabel?: string;
    scope?: 'namespace' | 'database' | 'schema';
    databaseName?: string;
  }) => void;
  closeNamespaceModal: () => void;
  confirmNamespaceModal: () => Promise<void>;
  closeContextMenu: () => void;
  triggerContextAction: (action: ContextAction) => Promise<void>;
  contextMenuActions: ComputedRef<ContextMenuActionItem[]>;
  handleTreeNodeDblclick: (node: TreeNodeData) => Promise<void>;
  onObjectRow: (record: ObjectRow) => {
    onClick: () => void;
    onDblclick: () => void;
    onContextmenu: (event: MouseEvent) => void;
  };
}

interface ConnectionBrowserDeps {
  copyTableWithinCurrentDatabase: (mode: 'STRUCTURE_ONLY' | 'STRUCTURE_AND_DATA', source?: {
    connectionId: number;
    databaseName: string;
    tableName: string;
    dbType: string;
  } | null) => Promise<void>;
  openNewTableEditor: () => Promise<void>;
  openEditTableEditor: (connectionId: number, databaseName: string, tableName: string) => Promise<void>;
  openTableDataTabByObject: (
    record: ObjectRow,
    options?: { connectionId?: number; databaseName?: string },
  ) => Promise<void>;
  openRenameTableModal: (source?: {
    connectionId: number;
    databaseName: string;
    tableName: string;
  } | null) => void;
  openObjectDefinitionEditor: (
    connectionId: number,
    databaseName: string,
    objectType: 'views' | 'functions',
    objectName: string,
  ) => Promise<void>;
  openNewObjectDefinitionEditor: (
    connectionId: number,
    databaseName: string,
    objectType: 'views' | 'functions',
  ) => Promise<void>;
}

export function useConnectionBrowserModule(
  runtime: StudioRuntime,
  deps: ConnectionBrowserDeps,
): ConnectionBrowserModule {
  function activateBrowserTab() {
    runtime.browserNavMode.value = 'connections';
    runtime.activeWorkbenchTab.value = runtime.browserTabKey;
  }

  function toggleBrowserDetailCollapsed() {
    runtime.browserDetailCollapsed.value = !runtime.browserDetailCollapsed.value;
  }

  function closeContextMenu() {
    runtime.contextMenu.visible = false;
    runtime.contextMenu.targetType = 'none';
    runtime.contextMenu.groupId = 0;
    runtime.contextMenu.databaseName = '';
    runtime.contextMenu.namespaceName = '';
    runtime.contextMenu.category = '';
    runtime.contextMenu.objectType = '';
    runtime.contextMenu.objectName = '';
  }

  function openCreateModal(defaultGroupId?: number) {
    closeContextMenu();
    runtime.resetConnectionForm();
    if (defaultGroupId) {
      runtime.connectionForm.groupId = defaultGroupId;
    }
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

  function currentConnection() {
    return runtime.connections.value.find((item) => item.id === runtime.contextMenu.connectionId)
      ?? runtime.connections.value.find((item) => item.id === runtime.workflow.connectionId)
      ?? null;
  }

  function currentDbTypeSpec(): ConnectionDbTypeVO | null {
    const connection = currentConnection();
    if (!connection) {
      return null;
    }
    return runtime.findSupportedDbType(connection.dbType);
  }

  function namespaceLabel() {
    return currentDbTypeSpec()?.namespaceLabel?.trim() || '命名空间';
  }

  function currentDbType() {
    return currentConnection()?.dbType || '';
  }

  function parseDatabaseContext(rawDatabaseName: string) {
    const dbType = currentDbType();
    const normalized = String(rawDatabaseName || '').trim();
    if (!normalized || !runtime.supportsSchemaLayer(dbType)) {
      return {
        databaseName: normalized,
        namespaceName: '',
      };
    }
    const separatorIndex = normalized.indexOf('::');
    if (separatorIndex <= 0 || separatorIndex >= normalized.length - 2) {
      return {
        databaseName: normalized,
        namespaceName: '',
      };
    }
    return {
      databaseName: normalized.slice(0, separatorIndex).trim(),
      namespaceName: normalized.slice(separatorIndex + 2).trim(),
    };
  }

  function currentRootDatabaseName() {
    return parseDatabaseContext(runtime.contextMenu.databaseName).databaseName;
  }

  function supportsDatabaseCreateAction() {
    return ['MYSQL', 'POSTGRESQL', 'SQLSERVER'].includes(currentDbType());
  }

  function supportsSchemaCreateAction() {
    return ['POSTGRESQL', 'SQLSERVER'].includes(currentDbType());
  }

  function supportsViewCreateAction() {
    const spec = currentDbTypeSpec();
    if (typeof spec?.supportsViewCreate === 'boolean') {
      return spec.supportsViewCreate;
    }
    return ['MYSQL', 'POSTGRESQL', 'SQLSERVER', 'ORACLE', 'SQLITE'].includes(currentDbType());
  }

  function supportsFunctionCreateAction() {
    const spec = currentDbTypeSpec();
    if (typeof spec?.supportsFunctionCreate === 'boolean') {
      return spec.supportsFunctionCreate;
    }
    return ['MYSQL', 'POSTGRESQL', 'SQLSERVER', 'ORACLE'].includes(currentDbType());
  }

  function openNamespaceCreateModal(connectionId: number, options?: {
    namespaceLabel?: string;
    scope?: 'namespace' | 'database' | 'schema';
    databaseName?: string;
  }) {
    closeContextMenu();
    runtime.namespaceForm.mode = 'create';
    runtime.namespaceForm.scope = options?.scope || 'namespace';
    runtime.namespaceForm.connectionId = connectionId;
    runtime.namespaceForm.databaseName = options?.databaseName || '';
    runtime.namespaceForm.namespaceLabel = options?.namespaceLabel || namespaceLabel();
    runtime.namespaceForm.sourceNamespaceName = '';
    runtime.namespaceForm.targetNamespaceName = '';
    runtime.namespaceModalOpen.value = true;
  }

  function openNamespaceRenameModal(connectionId: number, sourceNamespaceName: string, customNamespaceLabel?: string) {
    closeContextMenu();
    runtime.namespaceForm.mode = 'rename';
    runtime.namespaceForm.scope = 'namespace';
    runtime.namespaceForm.connectionId = connectionId;
    runtime.namespaceForm.databaseName = '';
    runtime.namespaceForm.namespaceLabel = customNamespaceLabel || namespaceLabel();
    runtime.namespaceForm.sourceNamespaceName = sourceNamespaceName;
    runtime.namespaceForm.targetNamespaceName = sourceNamespaceName;
    runtime.namespaceModalOpen.value = true;
  }

  function closeNamespaceModal() {
    runtime.namespaceModalOpen.value = false;
    runtime.namespaceModalSubmitting.value = false;
    runtime.namespaceForm.mode = 'create';
    runtime.namespaceForm.scope = 'namespace';
    runtime.namespaceForm.connectionId = 0;
    runtime.namespaceForm.databaseName = '';
    runtime.namespaceForm.namespaceLabel = '命名空间';
    runtime.namespaceForm.sourceNamespaceName = '';
    runtime.namespaceForm.targetNamespaceName = '';
  }

  async function confirmNamespaceModal() {
    const mode = runtime.namespaceForm.mode;
    const scope = runtime.namespaceForm.scope;
    const connectionId = runtime.namespaceForm.connectionId;
    const databaseName = runtime.namespaceForm.databaseName.trim();
    const namespaceLabelValue = runtime.namespaceForm.namespaceLabel;
    const sourceNamespaceName = runtime.namespaceForm.sourceNamespaceName.trim();
    const targetNamespaceName = runtime.namespaceForm.targetNamespaceName.trim();
    if (!targetNamespaceName) {
      message.warning(`请输入${namespaceLabelValue}名称`);
      return;
    }
    runtime.namespaceModalSubmitting.value = true;
    try {
      let result: SchemaNamespaceOperationVO;
      if (mode === 'create' && scope === 'database') {
        result = await postApi<SchemaNamespaceOperationVO>('/api/schema/database/create', {
          connectionId,
          targetDatabaseName: targetNamespaceName,
        } satisfies SchemaDatabaseCreateReq);
      } else if (mode === 'create' && scope === 'schema') {
        result = await postApi<SchemaNamespaceOperationVO>('/api/schema/schema/create', {
          connectionId,
          databaseName,
          targetNamespaceName,
        } satisfies SchemaSchemaCreateReq);
      } else {
        result = mode === 'create'
          ? await postApi<SchemaNamespaceOperationVO>('/api/schema/namespace/create', {
            connectionId,
            targetNamespaceName,
          } satisfies SchemaNamespaceCreateReq)
          : await postApi<SchemaNamespaceOperationVO>('/api/schema/namespace/rename', {
            connectionId,
            sourceNamespaceName,
            targetNamespaceName,
          } satisfies SchemaNamespaceRenameReq);
      }
      closeNamespaceModal();
      message.success(result.message || `${namespaceLabelValue}鎿嶄綔鎴愬姛`);
      if (scope === 'schema' && databaseName) {
        runtime.invalidateDatabaseMetadataCaches(connectionId, databaseName);
      } else {
        runtime.invalidateConnectionMetadataCaches(connectionId);
      }
      await runtime.prepareConnectionTreeData(connectionId, { force: true });
      runtime.selectedTreeKeys.value = [`conn-${connectionId}`];
    } catch (error) {
      const msg = error instanceof Error ? error.message : String(error);
      message.error(msg);
    } finally {
      runtime.namespaceModalSubmitting.value = false;
    }
  }

  function openQueryCreator(connectionId: number, databaseName: string) {
    runtime.workflow.connectionId = connectionId;
    runtime.activeDatabaseMap.value = {
      ...runtime.activeDatabaseMap.value,
      [connectionId]: databaseName,
    };
    runtime.openAiQueryTab('');
  }

  async function removeNamespace(connectionId: number, sourceNamespaceName: string) {
    const label = namespaceLabel();
    await Modal.confirm({
      title: `删除${label}`,
      content: `确认删除${label} ${sourceNamespaceName} 吗？`,
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      async onOk() {
        const result = await postApi<SchemaNamespaceOperationVO>('/api/schema/namespace/drop', {
          connectionId,
          sourceNamespaceName,
        } satisfies SchemaNamespaceDropReq);
        message.success(result.message || `${label}删除成功`);
        runtime.invalidateDatabaseMetadataCaches(connectionId, sourceNamespaceName);
        runtime.invalidateDatabaseListCache(connectionId);
        await runtime.prepareConnectionTreeData(connectionId);
        runtime.selectedTreeKeys.value = [`conn-${connectionId}`];
        if (runtime.workflow.connectionId === connectionId
          && runtime.getActiveDatabaseName(connectionId) === sourceNamespaceName) {
          runtime.activeDatabaseMap.value = {
            ...runtime.activeDatabaseMap.value,
            [connectionId]: '',
          };
          runtime.selectedObjectName.value = '';
          runtime.clearObjectDetail();
        }
      },
    });
  }

  async function removeObject(connectionId: number, databaseName: string, objectType: 'views' | 'functions', objectName: string) {
    await Modal.confirm({
      title: `删除${objectType === 'views' ? '视图' : '函数'}`,
      content: `确认删除 ${objectName} 吗？`,
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      async onOk() {
        const result = await postApi<SchemaObjectDropVO>('/api/schema/object/drop', {
          connectionId,
          databaseName,
          objectType,
          objectName,
        } satisfies SchemaObjectDropReq);
        message.success(result.message || '对象删除成功');
        runtime.invalidateDatabaseMetadataCaches(connectionId, databaseName);
        await runtime.prepareConnectionTreeData(connectionId);
        if (runtime.workflow.connectionId === connectionId && runtime.getActiveDatabaseName(connectionId) === databaseName) {
          await runtime.refreshCurrentObjects();
        }
        if (runtime.selectedObjectName.value === objectName) {
          runtime.selectedObjectName.value = '';
          runtime.clearObjectDetail();
        }
      },
    });
  }

  async function removeSavedQuery(connectionId: number, savedQueryId: number, objectName: string, databaseName: string) {
    await Modal.confirm({
      title: '删除保存查询',
      content: `确认删除保存查询 ${objectName} 吗？`,
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      async onOk() {
        await postApi<boolean>('/api/editor/saved-query/remove', {
          id: savedQueryId,
          connectionId,
        } satisfies SavedQueryRemoveReq);
        const nextTabs = runtime.queryTabs.value.map((item) => {
          if (item.savedQueryId === savedQueryId) {
            return {
              ...item,
              savedQueryId: undefined,
              savedQueryEditMode: false,
            };
          }
          return item;
        });
        runtime.queryTabs.value = nextTabs;
        await runtime.loadSavedQueries(connectionId, databaseName);
        await runtime.prepareConnectionTreeData(connectionId);
        if (runtime.workflow.connectionId === connectionId && runtime.getActiveDatabaseName(connectionId) === databaseName) {
          await runtime.refreshCurrentObjects();
        }
        message.success('保存查询已删除');
      },
    });
  }

  function createChildActions(): ContextMenuActionItem[] {
    const actions: ContextMenuActionItem[] = [];
    if (currentDbTypeSpec()?.supportsTableCreate !== false) {
      actions.push({ id: 'createTable', label: '新建表' });
    }
    if (supportsViewCreateAction()) {
      actions.push({ id: 'createView', label: '新建视图' });
    }
    if (supportsFunctionCreateAction()) {
      actions.push({ id: 'createFunction', label: '新建函数' });
    }
    actions.push({ id: 'createQuery', label: '新建查询' });
    return actions;
  }

  const contextMenuActions = computed<ContextMenuActionItem[]>(() => {
    const spec = currentDbTypeSpec();
    const menu = runtime.contextMenu;
    if (!menu.connectionId && menu.targetType !== 'connection') {
      if (menu.targetType !== 'group') {
        return [];
      }
    }
    if (menu.targetType === 'group') {
      return [
        { id: 'createConnection', label: '新建连接' },
        { id: 'createGroup', label: '新建分组' },
        { id: 'renameGroup', label: '重命名分组' },
        { id: 'deleteGroup', label: '删除分组', danger: true },
      ];
    }
    if (menu.targetType === 'connection') {
      const actions: ContextMenuActionItem[] = [
        { id: 'createConnection', label: '新建连接' },
        { id: 'createGroup', label: '新建分组' },
        { id: 'editConnection', label: '编辑连接' },
        { id: 'testConnection', label: '测试连接' },
        { id: 'disconnectConnection', label: '关闭连接' },
        { id: 'syncSchema', label: '同步 Schema' },
        { id: 'deleteConnection', label: '删除连接', danger: true },
      ];
      if (supportsDatabaseCreateAction()) {
        actions.splice(1, 0, { id: 'createDatabase', label: '新建库' });
      }
      if (!supportsDatabaseCreateAction() && spec?.supportsNamespaceCreate) {
        actions.splice(1, 0, { id: 'createNamespace', label: `新建${namespaceLabel()}` });
      }
      return actions;
    }
    if (menu.targetType === 'databaseRoot' || menu.targetType === 'database') {
      const isKv = runtime.isKvConnectionId(menu.connectionId);
      const isSchemaContext = menu.targetType === 'database' && !!menu.namespaceName;
      const actions: ContextMenuActionItem[] = [];
      if (!isKv && supportsDatabaseCreateAction()) {
        actions.push({ id: 'createDatabase', label: '新建库' });
      }
      if (!isKv && supportsSchemaCreateAction()) {
        actions.push({ id: 'createSchema', label: '新建Schema' });
      }
      if (!isSchemaContext && spec?.supportsNamespaceRename && !isKv) {
        actions.push({ id: 'renameNamespace', label: `编辑${namespaceLabel()}` });
      }
      if (!supportsDatabaseCreateAction() && !supportsSchemaCreateAction() && spec?.supportsNamespaceCreate && !isKv) {
        actions.push({ id: 'createNamespace', label: `新建同级${namespaceLabel()}` });
      }
      const childActions = createChildActions();
      if (childActions.length && !isKv) {
        actions.push({ id: 'createTable', label: '新建下级', children: childActions });
      }
      if (!isKv) {
        actions.push(
          { id: 'revectorize', label: '重新向量化', disabled: runtime.isContextDatabaseVectorizing.value },
          { id: 'interruptVectorize', label: '中断向量化', disabled: !runtime.canInterruptContextVectorize.value },
          { id: 'viewVectorizedData', label: '查看向量化数据', disabled: !runtime.canViewContextVectorizedData.value },
        );
      }
      if (!isSchemaContext && spec?.supportsNamespaceDrop && !isKv) {
        actions.push({ id: 'dropNamespace', label: `删除${namespaceLabel()}`, danger: true });
      }
      return actions;
    }
    if (menu.targetType === 'category') {
      if (menu.category === 'tables') {
        return [{ id: 'createTable', label: '新建表' }];
      }
      if (menu.category === 'views' && supportsViewCreateAction()) {
        return [{ id: 'createView', label: '新建视图' }];
      }
      if (menu.category === 'functions' && supportsFunctionCreateAction()) {
        return [{ id: 'createFunction', label: '新建函数' }];
      }
      if (menu.category === 'queries') {
        return [{ id: 'createQuery', label: '新建查询' }];
      }
      return [];
    }
    if (menu.targetType !== 'object') {
      return [];
    }
    const isKvForObject = menu.objectType === 'tables' && runtime.isKvConnectionId(menu.connectionId);
    if (menu.objectType === 'tables') {
      if (isKvForObject) {
        return [
          { id: 'createKey', label: '新增键' },
          { id: 'editKey', label: '编辑键' },
          { id: 'copyKeyName', label: '复制键名' },
          { id: 'deleteKey', label: '删除键', danger: true },
        ];
      }
      return [
        { id: 'querySql', label: 'SQL查询' },
        { id: 'createTable', label: '新建表' },
        { id: 'editTable', label: '编辑表结构' },
        { id: 'browseData', label: '数据浏览' },
        { id: 'renameTable', label: '重命名表' },
        { id: 'vectorizeTable', label: '向量化' },
        {
          id: 'copyTableStructure',
          label: '复制',
          children: [
            { id: 'copyTableStructure', label: '仅复制结构' },
            { id: 'copyTableStructureAndData', label: '复制结构和数据' },
          ],
        },
        { id: 'truncateTable', label: '清空表数据', danger: true },
        { id: 'dropTable', label: '删除表', danger: true },
      ];
    }
    if (menu.objectType === 'views') {
      const actions: ContextMenuActionItem[] = [
        { id: 'editDefinition', label: '编辑视图定义' },
        { id: 'browseData', label: '数据浏览' },
      ];
      if (supportsViewCreateAction()) {
        actions.unshift(
          { id: 'querySql', label: 'SQL查询' },
          { id: 'createView', label: '新建视图' },
        );
      }
      if (spec?.supportsViewDrop) {
        actions.push({ id: 'dropObject', label: '删除视图', danger: true });
      }
      return actions;
    }
    if (menu.objectType === 'functions') {
      const actions: ContextMenuActionItem[] = [
        { id: 'editDefinition', label: '编辑函数定义' },
      ];
      if (supportsFunctionCreateAction()) {
        actions.unshift({ id: 'createFunction', label: '新建函数' });
      }
      if (spec?.supportsFunctionDrop) {
        actions.push({ id: 'dropObject', label: '删除函数', danger: true });
      }
      return actions;
    }
    if (menu.objectType === 'queries') {
      return [
        { id: 'createQuery', label: '新建查询' },
        { id: 'editSavedQuery', label: '编辑查询' },
        { id: 'deleteSavedQuery', label: '删除保存查询', danger: true },
      ];
    }
    return [];
  });

  async function triggerContextAction(action: ContextAction) {
    const id = runtime.contextMenu.connectionId;
    const databaseName = runtime.contextMenu.databaseName;
    const targetType = runtime.contextMenu.targetType;
    const objectType = runtime.contextMenu.objectType;
    const objectName = runtime.contextMenu.objectName;
    const category = runtime.contextMenu.category;
    const groupId = runtime.contextMenu.groupId;
    const connection = runtime.connections.value.find((item) => item.id === id) || null;
    const resolvedDatabaseName = databaseName || runtime.getActiveDatabaseName(id);
    const savedQuery = objectType === 'queries'
      ? runtime.savedQueryCache.value[`${id}|${resolvedDatabaseName}`]?.find((item) => item.title === objectName)
      : null;
    closeContextMenu();
    if (action === 'createGroup') {
      runtime.openCreateGroupModal();
      return;
    }
    if (action === 'renameGroup') {
      if (!groupId) {
        return;
      }
      runtime.openRenameGroupModal(groupId);
      return;
    }
    if (action === 'deleteGroup') {
      if (!groupId) {
        return;
      }
      await runtime.removeConnectionGroup(groupId);
      return;
    }
    if (action === 'createConnection') {
      openCreateModal(targetType === 'group' ? groupId : undefined);
      return;
    }
    if (!id) {
      return;
    }
    if (action === 'editConnection') {
      openEditModal(id);
      return;
    }
    if (action === 'testConnection') {
      await runtime.testConnection(id);
      return;
    }
    if (action === 'disconnectConnection') {
      await runtime.disconnectConnection(id);
      return;
    }
    if (action === 'syncSchema') {
      await runtime.syncSchema(id);
      return;
    }
    if (action === 'deleteConnection') {
      await runtime.removeConnection(id);
      return;
    }
    if (action === 'createNamespace') {
      openNamespaceCreateModal(id);
      return;
    }
    if (action === 'createDatabase') {
      openNamespaceCreateModal(id, {
        namespaceLabel: '库',
        scope: 'database',
        databaseName: currentRootDatabaseName(),
      });
      return;
    }
    if (action === 'createSchema') {
      openNamespaceCreateModal(id, {
        namespaceLabel: 'Schema',
        scope: 'schema',
        databaseName: currentRootDatabaseName(),
      });
      return;
    }
    if (action === 'renameNamespace') {
      const namespaceName = (runtime.contextMenu.namespaceName || databaseName || '').trim();
      if (targetType !== 'database' || !namespaceName) {
        return;
      }
      openNamespaceRenameModal(id, namespaceName);
      return;
    }
    if (action === 'dropNamespace') {
      const namespaceName = (runtime.contextMenu.namespaceName || databaseName || '').trim();
      if (targetType !== 'database' || !namespaceName) {
        return;
      }
      await removeNamespace(id, namespaceName);
      return;
    }
    if (action === 'createTable') {
      runtime.workflow.connectionId = id;
      if (resolvedDatabaseName) {
        runtime.activeDatabaseMap.value = {
          ...runtime.activeDatabaseMap.value,
          [id]: resolvedDatabaseName,
        };
      }
      await deps.openNewTableEditor();
      return;
    }
    if (action === 'createView') {
      await deps.openNewObjectDefinitionEditor(id, resolvedDatabaseName, 'views');
      return;
    }
    if (action === 'createFunction') {
      await deps.openNewObjectDefinitionEditor(id, resolvedDatabaseName, 'functions');
      return;
    }
    if (action === 'createQuery') {
      openQueryCreator(id, resolvedDatabaseName);
      return;
    }
    if (action === 'querySql') {
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
      }, false);
      return;
    }
    if (action === 'browseData') {
      if (targetType !== 'object' || !objectName || (objectType !== 'tables' && objectType !== 'views')) {
        return;
      }
      const rowVectorizeRecord = runtime.getDatabaseVectorizeStatusRecord(id, databaseName || '');
      await deps.openTableDataTabByObject({
        objectName,
        objectType,
        rowEstimate: 0,
        tableSize: '-',
        description: '',
        vectorizeStatus: rowVectorizeRecord?.status || 'NOT_VECTORIZED',
        vectorizeMessage: rowVectorizeRecord?.message,
        vectorizeUpdatedAt: rowVectorizeRecord?.updatedAt,
      }, {
        connectionId: id,
        databaseName: databaseName || runtime.getActiveDatabaseName(id),
      });
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
    if (action === 'editDefinition') {
      if (targetType !== 'object' || !objectName || !databaseName) {
        return;
      }
      if (objectType !== 'views' && objectType !== 'functions') {
        return;
      }
      await deps.openObjectDefinitionEditor(id, databaseName, objectType, objectName);
      return;
    }
    if (action === 'dropObject') {
      if (targetType !== 'object' || !objectName || !databaseName || (objectType !== 'views' && objectType !== 'functions')) {
        return;
      }
      await removeObject(id, databaseName, objectType, objectName);
      return;
    }
    if (action === 'copyTableStructure' || action === 'copyTableStructureAndData') {
      if (targetType !== 'object' || !objectName || objectType !== 'tables' || !databaseName) {
        return;
      }
      await deps.copyTableWithinCurrentDatabase(
        action === 'copyTableStructure' ? 'STRUCTURE_ONLY' : 'STRUCTURE_AND_DATA',
        {
          connectionId: id,
          databaseName,
          tableName: objectName,
          dbType: connection?.dbType || '',
        },
      );
      return;
    }
    if (action === 'renameTable') {
      if (targetType !== 'object' || !objectName || objectType !== 'tables' || !databaseName) {
        return;
      }
      deps.openRenameTableModal({
        connectionId: id,
        databaseName,
        tableName: objectName,
      });
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
    if (action === 'copyKeyName') {
      if (targetType !== 'object' || !objectName || objectType !== 'tables' || !databaseName) {
        return;
      }
      void navigator.clipboard.writeText(objectName);
      return;
    }
    if (action === 'createKey') {
      runtime.openCreateRedisKeyModal();
      return;
    }
    if (action === 'editKey') {
      runtime.openEditRedisKeyModal();
      return;
    }
    if (action === 'deleteKey') {
      if (targetType !== 'object' || !objectName || objectType !== 'tables' || !databaseName) {
        return;
      }
      await runtime.deleteRedisKey(objectName);
      return;
    }
    if (action === 'editSavedQuery') {
      if (targetType !== 'object' || objectType !== 'queries' || !objectName) {
        return;
      }
      await runtime.openSavedQueryTabByTitle(id, resolvedDatabaseName, objectName);
      return;
    }
    if (action === 'deleteSavedQuery') {
      if (targetType !== 'object' || objectType !== 'queries' || !objectName || !savedQuery) {
        return;
      }
      await removeSavedQuery(id, savedQuery.id, objectName, resolvedDatabaseName);
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
      return;
    }
    if (category === 'queries') {
      openQueryCreator(id, resolvedDatabaseName);
    }
  }

  async function handleTreeNodeDblclick(node: TreeNodeData) {
    if (node.nodeType === 'connection' && node.connectionId) {
      runtime.workflow.connectionId = node.connectionId;
      const connectionKey = `conn-${node.connectionId}`;
      if (runtime.expandedTreeKeys.value.includes(connectionKey)) {
        runtime.collapseConnectionNode(node.connectionId);
      } else {
        await runtime.ensureConnectionTreeExpanded(node.connectionId);
      }
      return;
    }
    if (!node.connectionId || !node.databaseName) {
      return;
    }
    if (node.nodeType === 'queries' && node.objectName) {
      await runtime.openSavedQueryTabByTitle(node.connectionId, node.databaseName, node.objectName);
      return;
    }
    if (!node.objectName) {
      return;
    }
    if (node.nodeType === 'tables' || node.nodeType === 'views') {
      if (runtime.isKvConnectionId(node.connectionId)) {
        return;
      }
      const rowVectorizeRecord = runtime.getDatabaseVectorizeStatusRecord(node.connectionId, node.databaseName);
      await deps.openTableDataTabByObject({
        objectName: node.objectName,
        objectType: node.nodeType,
        rowEstimate: 0,
        tableSize: '-',
        description: '',
        vectorizeStatus: rowVectorizeRecord?.status || 'NOT_VECTORIZED',
        vectorizeMessage: rowVectorizeRecord?.message,
        vectorizeUpdatedAt: rowVectorizeRecord?.updatedAt,
      }, {
        connectionId: node.connectionId,
        databaseName: node.databaseName,
      });
      return;
    }
    if (node.nodeType === 'functions') {
      await deps.openObjectDefinitionEditor(node.connectionId, node.databaseName, 'functions', node.objectName);
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
        if (record.objectType === 'tables' || record.objectType === 'views') {
          if (runtime.isKvConnectionId(runtime.workflow.connectionId)) {
            return;
          }
          void deps.openTableDataTabByObject(record, {
            connectionId: runtime.workflow.connectionId,
            databaseName: runtime.getActiveDatabaseName(runtime.workflow.connectionId),
          });
          return;
        }
        if (record.objectType === 'functions') {
          void deps.openObjectDefinitionEditor(
            runtime.workflow.connectionId,
            runtime.getActiveDatabaseName(runtime.workflow.connectionId),
            'functions',
            record.objectName,
          );
          return;
        }
        if (record.objectType === 'queries') {
          void runtime.openSavedQueryTabByTitle(
            runtime.workflow.connectionId,
            runtime.getActiveDatabaseName(runtime.workflow.connectionId),
            record.objectName,
          );
          return;
        }
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
        runtime.contextMenu.category = '';
        runtime.contextMenu.objectType = record.objectType;
        runtime.contextMenu.objectName = record.objectName;
      },
    };
  }

  return {
    activateBrowserTab,
    toggleBrowserDetailCollapsed,
    openCreateModal,
    openEditModal,
    openNamespaceCreateModal,
    closeNamespaceModal,
    confirmNamespaceModal,
    closeContextMenu,
    triggerContextAction,
    contextMenuActions,
    handleTreeNodeDblclick,
    onObjectRow,
  };
}
