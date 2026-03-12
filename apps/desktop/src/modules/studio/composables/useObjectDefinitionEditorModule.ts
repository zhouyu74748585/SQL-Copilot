import {message} from 'ant-design-vue';
import {getApi, postApi} from '../../../api/client';
import type {
  SchemaObjectDefinitionSaveReq,
  SchemaObjectDefinitionSaveVO,
  SchemaObjectDefinitionVO,
  SchemaObjectType,
} from '../../../types';
import type {StudioRuntime} from './useStudioRuntime';

type ObjectDefinitionEditorTab = StudioRuntime['objectDefinitionEditorTabs']['value'][number];

export interface ObjectDefinitionEditorModule {
  closeObjectDefinitionEditorTab: (tabKey: string) => void;
  openObjectDefinitionEditor: (
    connectionId: number,
    databaseName: string,
    objectType: SchemaObjectType,
    objectName: string,
  ) => Promise<void>;
  openNewObjectDefinitionEditor: (
    connectionId: number,
    databaseName: string,
    objectType: SchemaObjectType,
  ) => Promise<void>;
  handleObjectDefinitionSqlChange: (tab: ObjectDefinitionEditorTab, sqlText: string) => void;
  saveObjectDefinition: (tab: ObjectDefinitionEditorTab) => Promise<void>;
  reloadObjectDefinition: (tab: ObjectDefinitionEditorTab) => Promise<void>;
  copyObjectDefinitionSql: (tab: ObjectDefinitionEditorTab | null) => Promise<void>;
}

export function useObjectDefinitionEditorModule(runtime: StudioRuntime): ObjectDefinitionEditorModule {
  function closeObjectDefinitionEditorTab(tabKey: string) {
    const index = runtime.objectDefinitionEditorTabs.value.findIndex((item) => item.key === tabKey);
    if (index < 0) {
      return;
    }
    const tabs = [...runtime.objectDefinitionEditorTabs.value];
    tabs.splice(index, 1);
    runtime.objectDefinitionEditorTabs.value = tabs;
    if (runtime.activeWorkbenchTab.value === tabKey) {
      runtime.activeWorkbenchTab.value = tabs[index]?.key || tabs[index - 1]?.key || runtime.browserTabKey;
      runtime.ensureActiveWorkbenchTab();
    }
  }

  function objectTypeLabel(objectType: SchemaObjectType) {
    return objectType === 'views' ? '视图' : '函数';
  }

  function buildDefaultObjectName(objectType: SchemaObjectType) {
    const suffix = new Date().toISOString().replace(/[-:TZ.]/g, '').slice(0, 14);
    return `${objectType === 'views' ? 'new_view' : 'new_function'}_${suffix}`;
  }

  function buildTemplateSql(dbType: string, objectType: SchemaObjectType, objectName: string) {
    const normalizedDbType = (dbType || 'MYSQL').toUpperCase();
    if (objectType === 'views') {
      if (normalizedDbType === 'ORACLE') {
        return `create or replace view ${objectName} as\nselect *\nfrom your_table;`;
      }
      if (normalizedDbType === 'SQLSERVER') {
        return `create view ${objectName} as\nselect top 100 *\nfrom your_table;`;
      }
      return `create or replace view ${objectName} as\nselect *\nfrom your_table\nlimit 100;`;
    }
    if (normalizedDbType === 'ORACLE') {
      return `create or replace function ${objectName}\nreturn number\nis\nbegin\n  return 1;\nend;`;
    }
    if (normalizedDbType === 'MYSQL') {
      return `create function ${objectName}()\nreturns int\nbegin\n  return 1;\nend;`;
    }
    if (normalizedDbType === 'SQLSERVER') {
      return `create function ${objectName}()\nreturns int\nas\nbegin\n  return 1;\nend;`;
    }
    return `create or replace function ${objectName}()\nreturns integer\nlanguage plpgsql\nas $$\nbegin\n  return 1;\nend;\n$$;`;
  }

  function parseObjectNameFromSql(objectType: SchemaObjectType, sqlText: string) {
    const keyword = objectType === 'views' ? 'view' : 'function';
    const matcher = String(sqlText || '').trim().match(
      new RegExp(
        `^create\\s+(?:or\\s+replace\\s+|or\\s+alter\\s+)?(?:algorithm\\s*=\\s*\\w+\\s+)?(?:definer\\s*=\\s*[^\\s]+\\s+)?(?:sql\\s+security\\s+\\w+\\s+)?(?:\\w+\\s+)*?${keyword}\\s+([^\\s(]+)`,
        'i',
      ),
    );
    if (!matcher?.[1]) {
      return '';
    }
    const raw = matcher[1].split('.').pop() || '';
    return raw.replace(/^[`"'[]|[`"'\]]$/g, '').trim();
  }

  async function fetchObjectDefinition(
    connectionId: number,
    databaseName: string,
    objectType: SchemaObjectType,
    objectName: string,
  ) {
    return await getApi<SchemaObjectDefinitionVO>(
      `/api/schema/object/definition?connectionId=${connectionId}&databaseName=${encodeURIComponent(databaseName)}&objectType=${encodeURIComponent(objectType)}&objectName=${encodeURIComponent(objectName)}`,
    );
  }

  async function openObjectDefinitionEditor(
    connectionId: number,
    databaseName: string,
    objectType: SchemaObjectType,
    objectName: string,
  ) {
    const existingTab = runtime.objectDefinitionEditorTabs.value.find(
      (item) => item.connectionId === connectionId
        && item.databaseName === databaseName
        && item.objectType === objectType
        && item.objectName === objectName
        && item.mode === 'edit',
    );
    if (existingTab) {
      runtime.activeWorkbenchTab.value = existingTab.key;
      return;
    }
    const now = Date.now();
    const key = `object-definition-${now}-${Math.round(Math.random() * 1000)}`;
    const dbType = runtime.connections.value.find((item) => item.id === connectionId)?.dbType ?? 'MYSQL';
    const tab: ObjectDefinitionEditorTab = {
      key,
      title: `${objectTypeLabel(objectType)} · ${objectName}`,
      connectionId,
      databaseName,
      objectType,
      objectName,
      dbType,
      mode: 'edit',
      isNewObject: false,
      sqlText: '',
      baselineSql: '',
      loading: true,
      saving: false,
      dirty: false,
      errorMessage: '',
      createdAt: now,
      updatedAt: now,
    };
    runtime.objectDefinitionEditorTabs.value = [...runtime.objectDefinitionEditorTabs.value, tab];
    runtime.activeWorkbenchTab.value = key;
    try {
      const definition = await fetchObjectDefinition(connectionId, databaseName, objectType, objectName);
      const targetTab = runtime.objectDefinitionEditorTabs.value.find((item) => item.key === key);
      if (!targetTab) {
        return;
      }
      targetTab.sqlText = definition.definitionSql || '';
      targetTab.baselineSql = definition.definitionSql || '';
      targetTab.loading = false;
      targetTab.errorMessage = '';
      targetTab.updatedAt = Date.now();
    } catch (error) {
      const msg = error instanceof Error ? error.message : String(error);
      message.error(`加载对象定义失败: ${msg}`);
      closeObjectDefinitionEditorTab(key);
    }
  }

  async function openNewObjectDefinitionEditor(
    connectionId: number,
    databaseName: string,
    objectType: SchemaObjectType,
  ) {
    const now = Date.now();
    const dbType = runtime.connections.value.find((item) => item.id === connectionId)?.dbType ?? 'MYSQL';
    const objectName = buildDefaultObjectName(objectType);
    const sqlText = buildTemplateSql(dbType, objectType, objectName);
    const tab: ObjectDefinitionEditorTab = {
      key: `object-definition-new-${now}-${Math.round(Math.random() * 1000)}`,
      title: `新建${objectTypeLabel(objectType)}`,
      connectionId,
      databaseName,
      objectType,
      objectName,
      dbType,
      mode: 'create',
      isNewObject: true,
      sqlText,
      baselineSql: '',
      loading: false,
      saving: false,
      dirty: true,
      errorMessage: '',
      createdAt: now,
      updatedAt: now,
    };
    runtime.objectDefinitionEditorTabs.value = [...runtime.objectDefinitionEditorTabs.value, tab];
    runtime.activeWorkbenchTab.value = tab.key;
  }

  function handleObjectDefinitionSqlChange(tab: ObjectDefinitionEditorTab, sqlText: string) {
    tab.sqlText = sqlText;
    if (tab.mode === 'create') {
      const parsedObjectName = parseObjectNameFromSql(tab.objectType, sqlText);
      if (parsedObjectName) {
        tab.objectName = parsedObjectName;
      }
    }
    tab.dirty = sqlText.trim() !== tab.baselineSql.trim();
    tab.updatedAt = Date.now();
  }

  async function saveObjectDefinition(tab: ObjectDefinitionEditorTab) {
    const objectName = tab.mode === 'create'
      ? parseObjectNameFromSql(tab.objectType, tab.sqlText)
      : tab.objectName;
    if (!objectName) {
      message.warning('未识别到对象名称，请检查 CREATE 语句头部');
      return;
    }
    const payload: SchemaObjectDefinitionSaveReq = {
      connectionId: tab.connectionId,
      databaseName: tab.databaseName,
      objectType: tab.objectType,
      objectName,
      definitionSql: tab.sqlText,
    };
    if (!payload.definitionSql.trim()) {
      message.warning('定义 SQL 不能为空');
      return;
    }
    tab.saving = true;
    tab.updatedAt = Date.now();
    try {
      const result = await postApi<SchemaObjectDefinitionSaveVO>('/api/schema/object/definition/save', payload);
      tab.sqlText = result.definitionSql || payload.definitionSql;
      tab.baselineSql = result.definitionSql || payload.definitionSql;
      tab.objectName = result.objectName;
      tab.title = `${objectTypeLabel(tab.objectType)} · ${result.objectName}`;
      tab.mode = 'edit';
      tab.isNewObject = false;
      tab.dirty = false;
      tab.errorMessage = '';
      tab.updatedAt = Date.now();
      await runtime.prepareConnectionTreeData(tab.connectionId);
      if (runtime.workflow.connectionId === tab.connectionId
        && runtime.getActiveDatabaseName(tab.connectionId) === tab.databaseName
        && runtime.currentObjectType.value === tab.objectType) {
        await runtime.refreshCurrentObjects();
      }
      if (runtime.selectedObjectName.value === tab.objectName
        && runtime.workflow.connectionId === tab.connectionId
        && runtime.getActiveDatabaseName(tab.connectionId) === tab.databaseName
        && tab.objectType === 'views') {
        await runtime.loadObjectDetail(tab.connectionId, tab.databaseName, 'views', tab.objectName);
      }
      message.success(result.message || '对象定义保存成功');
    } catch (error) {
      const msg = error instanceof Error ? error.message : String(error);
      message.error(`保存对象定义失败: ${msg}`);
    } finally {
      tab.saving = false;
      tab.updatedAt = Date.now();
    }
  }

  async function reloadObjectDefinition(tab: ObjectDefinitionEditorTab) {
    if (tab.mode === 'create') {
      message.info('新建对象尚未保存，暂不支持刷新');
      return;
    }
    if (tab.dirty) {
      message.warning('存在未保存改动，请先保存');
      return;
    }
    tab.loading = true;
    tab.errorMessage = '';
    tab.updatedAt = Date.now();
    try {
      const definition = await fetchObjectDefinition(tab.connectionId, tab.databaseName, tab.objectType, tab.objectName);
      tab.sqlText = definition.definitionSql || '';
      tab.baselineSql = definition.definitionSql || '';
      tab.dirty = false;
      tab.updatedAt = Date.now();
    } catch (error) {
      const msg = error instanceof Error ? error.message : String(error);
      tab.errorMessage = msg;
      message.error(`刷新对象定义失败: ${msg}`);
    } finally {
      tab.loading = false;
      tab.updatedAt = Date.now();
    }
  }

  async function copyObjectDefinitionSql(tab: ObjectDefinitionEditorTab | null) {
    const sqlText = tab?.sqlText?.trim() || '';
    if (!sqlText) {
      return;
    }
    try {
      await navigator.clipboard.writeText(sqlText);
      message.success('SQL 已复制');
    } catch {
      const input = document.createElement('textarea');
      input.value = sqlText;
      input.setAttribute('readonly', 'true');
      input.style.position = 'fixed';
      input.style.opacity = '0';
      document.body.appendChild(input);
      input.select();
      document.execCommand('copy');
      document.body.removeChild(input);
      message.success('SQL 已复制');
    }
  }

  return {
    closeObjectDefinitionEditorTab,
    openObjectDefinitionEditor,
    openNewObjectDefinitionEditor,
    handleObjectDefinitionSqlChange,
    saveObjectDefinition,
    reloadObjectDefinition,
    copyObjectDefinitionSql,
  };
}
