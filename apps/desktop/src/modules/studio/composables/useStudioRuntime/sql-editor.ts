import {message} from 'ant-design-vue';
import type * as MonacoApi from 'monaco-editor';
import type {IDisposable} from 'monaco-editor';
import {format as sqlFormat, type SqlLanguage} from 'sql-formatter';
import type {ComputedRef, Ref} from 'vue';
import type {TableDetailVO} from '../../../../types';
import type {
  ObjectDefinitionEditorTab,
  QueryWorkspaceTab,
  SqlEditorContext,
  SqlEditorMountOptions,
  SqlQualifiedColumnContext,
  SqlTableReference,
} from './types';

const sqlIdentifierSegmentPattern = '(?:`[^`]+`|"[^"]+"|\\[[^\\]]+\\]|[A-Za-z_][A-Za-z0-9_$]*)';
const sqlPartialIdentifierPattern = '(?:`[^`]*`|"[^"]*"|\\[[^\\]]*\\]|[A-Za-z_][A-Za-z0-9_$]*)?';
const sqlQualifiedColumnContextPattern = new RegExp(
  `(${sqlIdentifierSegmentPattern}(?:\\s*\\.\\s*${sqlIdentifierSegmentPattern})*)\\s*\\.\\s*(${sqlPartialIdentifierPattern})$`,
);
const sqlTableReferencePattern = new RegExp(
  `\\b(?:from|join|update|into|delete\\s+from)\\s+(${sqlIdentifierSegmentPattern}(?:\\s*\\.\\s*${sqlIdentifierSegmentPattern}){0,2})(?:\\s+(?:as\\s+)?(${sqlIdentifierSegmentPattern}))?`,
  'gi',
);
const sqlAliasStopWords = new Set([
  'where', 'join', 'left', 'right', 'inner', 'outer', 'full', 'cross', 'on',
  'group', 'order', 'having', 'limit', 'offset', 'union', 'set', 'values',
  'returning', 'using',
]);

export const sqlKeywords = [
  'SELECT', 'FROM', 'WHERE', 'GROUP BY', 'ORDER BY', 'HAVING', 'LIMIT', 'OFFSET',
  'DISTINCT', 'AS', 'AND', 'OR', 'NOT', 'IN', 'EXISTS', 'BETWEEN', 'LIKE', 'IS NULL', 'IS NOT NULL',
  'INSERT INTO', 'VALUES', 'UPDATE', 'SET', 'DELETE', 'TRUNCATE', 'MERGE',
  'CREATE TABLE', 'ALTER TABLE', 'DROP TABLE', 'CREATE INDEX', 'DROP INDEX', 'CREATE VIEW', 'DROP VIEW',
  'JOIN', 'INNER JOIN', 'LEFT JOIN', 'RIGHT JOIN', 'FULL JOIN', 'ON', 'UNION', 'UNION ALL',
  'CASE', 'WHEN', 'THEN', 'ELSE', 'END',
  'COUNT', 'SUM', 'AVG', 'MIN', 'MAX',
  'WITH', 'CTE', 'DESC', 'ASC', 'TOP',
];

export function normalizeSqlIdentifier(value: string) {
  const text = String(value || '').trim();
  if (!text) {
    return '';
  }
  if ((text.startsWith('`') && text.endsWith('`'))
    || (text.startsWith('"') && text.endsWith('"'))
    || (text.startsWith('[') && text.endsWith(']'))) {
    return text.slice(1, -1).trim();
  }
  return text;
}

export function splitSqlIdentifierChain(value: string) {
  const matched = value.match(new RegExp(sqlIdentifierSegmentPattern, 'g')) ?? [];
  return matched.map((item) => normalizeSqlIdentifier(item)).filter((item) => !!item);
}

export function normalizeAliasCandidate(value: string) {
  const normalized = normalizeSqlIdentifier(value).trim();
  if (!normalized) {
    return '';
  }
  if (sqlAliasStopWords.has(normalized.toLowerCase())) {
    return '';
  }
  return normalized;
}

export function parseQualifiedColumnContext(linePrefix: string): SqlQualifiedColumnContext | null {
  const matched = linePrefix.match(sqlQualifiedColumnContextPattern);
  if (!matched) {
    return null;
  }
  const qualifierParts = splitSqlIdentifierChain(matched[1] || '');
  if (!qualifierParts.length) {
    return null;
  }
  const suffix = matched[2] || '';
  return {
    qualifierParts,
    prefix: normalizeSqlIdentifier(suffix),
    replaceStartColumn: linePrefix.length - suffix.length + 1,
  };
}

export function findMatchingName(names: string[], target: string) {
  const keyword = String(target || '').trim().toLowerCase();
  if (!keyword) {
    return '';
  }
  return names.find((item) => item.toLowerCase() === keyword) ?? '';
}

export function extractSqlTableReferences(sqlText: string, defaultDatabaseName: string) {
  const references: SqlTableReference[] = [];
  const seen = new Set<string>();
  Array.from(sqlText.matchAll(sqlTableReferencePattern)).forEach((matched) => {
    const objectParts = splitSqlIdentifierChain(matched[1] || '');
    if (!objectParts.length) {
      return;
    }
    const tableName = objectParts[objectParts.length - 1];
    const databaseName = objectParts.length >= 2 ? objectParts[objectParts.length - 2] : defaultDatabaseName;
    if (!tableName || !databaseName) {
      return;
    }
    const alias = normalizeAliasCandidate(matched[2] || '') || tableName;
    const cacheKey = `${databaseName.toLowerCase()}|${tableName.toLowerCase()}|${alias.toLowerCase()}`;
    if (seen.has(cacheKey)) {
      return;
    }
    seen.add(cacheKey);
    references.push({tableName, databaseName, alias});
  });
  return references;
}

export function resolveQualifiedTableReference(
  qualifierParts: string[],
  references: SqlTableReference[],
  availableTableNames: string[],
  defaultDatabaseName: string,
) {
  if (!qualifierParts.length) {
    return null;
  }
  if (qualifierParts.length >= 2) {
    const tableNameCandidate = qualifierParts[qualifierParts.length - 1];
    const databaseNameCandidate = qualifierParts[qualifierParts.length - 2] || defaultDatabaseName;
    const tableName = findMatchingName(availableTableNames, tableNameCandidate) || tableNameCandidate;
    if (!tableName || !databaseNameCandidate) {
      return null;
    }
    return {
      tableName,
      databaseName: databaseNameCandidate,
      sourceLabel: `${databaseNameCandidate}.${tableName}`,
    };
  }

  const qualifier = qualifierParts[0];
  const qualifierLower = qualifier.toLowerCase();
  const aliasMatched = references.find((item) => item.alias.toLowerCase() === qualifierLower);
  if (aliasMatched) {
    return {
      tableName: aliasMatched.tableName,
      databaseName: aliasMatched.databaseName,
      sourceLabel: aliasMatched.alias === aliasMatched.tableName ? aliasMatched.tableName : `${aliasMatched.tableName} AS ${aliasMatched.alias}`,
    };
  }
  const tableMatched = references.find((item) => item.tableName.toLowerCase() === qualifierLower);
  if (tableMatched) {
    return {
      tableName: tableMatched.tableName,
      databaseName: tableMatched.databaseName,
      sourceLabel: tableMatched.tableName,
    };
  }
  const tableName = findMatchingName(availableTableNames, qualifier);
  if (!tableName || !defaultDatabaseName) {
    return null;
  }
  return {
    tableName,
    databaseName: defaultDatabaseName,
    sourceLabel: tableName,
  };
}

export function columnSuggestions(
  monaco: typeof MonacoApi,
  columns: TableDetailVO['columns'],
  range: MonacoApi.IRange,
  prefix: string,
  sourceLabel = '',
) {
  const normalizedPrefix = prefix.trim().toLowerCase();
  return columns
    .filter((column) => {
      const name = String(column.columnName || '').toLowerCase();
      return !normalizedPrefix || name.startsWith(normalizedPrefix);
    })
    .slice(0, 80)
    .map((column) => ({
      label: column.columnName,
      kind: monaco.languages.CompletionItemKind.Field,
      insertText: column.columnName,
      detail: sourceLabel ? `${sourceLabel} · ${column.dataType || 'column'}` : (column.dataType || 'column'),
      documentation: column.columnComment || '',
      range,
      sortText: `0_${column.columnName}`,
    }));
}

export function resolveSelectedSqlSnippet(tab: QueryWorkspaceTab, sqlOverride?: string) {
  const override = (sqlOverride ?? '').trim();
  if (override) {
    return override;
  }
  return tab.selectedSqlText.trim();
}

interface SqlEditorRuntimeHelperContext {
  activeObjectDefinitionEditorTab: ComputedRef<ObjectDefinitionEditorTab | null>;
  activeQueryTab: ComputedRef<QueryWorkspaceTab | null>;
  getActiveSqlEditor: () => MonacoApi.editor.IStandaloneCodeEditor | null;
  getErrorMessage: (error: unknown) => string;
  hideSqlSelectionPopover: () => void;
  isKvDbType: (dbType: string) => boolean;
  queryEditorModeByDbType: (dbType: string) => string;
  queryTabDbType: (tab: QueryWorkspaceTab) => string;
  supportsGenerateChartByDbType: (dbType: string) => boolean;
  touchQueryTab: (tab: QueryWorkspaceTab) => void;
}

interface SqlEditorInteractionHelperContext {
  activeObjectDefinitionEditorTab: ComputedRef<ObjectDefinitionEditorTab | null>;
  activeQueryTab: ComputedRef<QueryWorkspaceTab | null>;
  activeWorkbenchTab: Ref<string>;
  ensureTableNamesLoaded: (connectionId: number, databaseName: string) => Promise<string[]>;
  ensureQueryTableDetailLoaded: (
    connectionId: number,
    databaseName: string,
    tableName: string,
  ) => Promise<TableDetailVO | null>;
  formatObjectDefinitionSql: (tab: ObjectDefinitionEditorTab) => void;
  formatSqlForTab: (tab: QueryWorkspaceTab) => void;
  getActiveSqlEditor: () => MonacoApi.editor.IStandaloneCodeEditor | null;
  getSqlAutoSuggestTimer: () => number | null;
  getSqlCompletionProviderDisposable: () => IDisposable | null;
  getSqlEditorMouseDownDisposable: () => IDisposable | null;
  getSqlEditorMouseUpDisposable: () => IDisposable | null;
  getSqlEditorScrollDisposable: () => IDisposable | null;
  getSqlEditorSelectionDisposable: () => IDisposable | null;
  getSqlEditorTypeDisposable: () => IDisposable | null;
  queryEditorContextResolverMap: Map<string, () => SqlEditorContext | null>;
  queryEditorModeByDbType: (dbTypeRaw: string) => string;
  queryTabDbType: (tab: QueryWorkspaceTab) => string;
  queryTabRefByContext: (context: SqlEditorContext | null) => QueryWorkspaceTab | null;
  resolveQueryDatabaseName: (tab: QueryWorkspaceTab | null) => string;
  setActiveSqlEditor: (editor: MonacoApi.editor.IStandaloneCodeEditor | null) => void;
  setSqlAutoSuggestTimer: (timer: number | null) => void;
  setSqlCompletionProviderDisposable: (disposable: IDisposable | null) => void;
  setSqlEditorMouseDownDisposable: (disposable: IDisposable | null) => void;
  setSqlEditorMouseUpDisposable: (disposable: IDisposable | null) => void;
  setSqlEditorScrollDisposable: (disposable: IDisposable | null) => void;
  setSqlEditorSelectionDisposable: (disposable: IDisposable | null) => void;
  setSqlEditorTypeDisposable: (disposable: IDisposable | null) => void;
  sqlEditorContainerRef: Ref<HTMLElement | null>;
  sqlSelectionPopover: {
    visible: boolean;
    left: number;
    top: number;
  };
  syncSelectedSqlForActiveTabFallback?: () => void;
  tableCacheKey: (connectionId: number, databaseName: string) => string;
  tableNameCache: Ref<Record<string, string[]>>;
  tableNameLoadedCache: Ref<Record<string, boolean>>;
}

export function createSqlEditorRuntimeHelpers(ctx: SqlEditorRuntimeHelperContext) {
  function queryEditorLanguageByDbType(dbTypeRaw: string) {
    const mode = ctx.queryEditorModeByDbType(dbTypeRaw);
    if (mode === 'json') {
      return 'json';
    }
    if (mode === 'redis') {
      return 'plaintext';
    }
    return 'sql';
  }

  function queryUnitLabelByDbType(dbTypeRaw: string) {
    return ctx.isKvDbType(dbTypeRaw) ? '查询' : 'SQL';
  }

  function generateActionLabelByDbType(dbTypeRaw: string) {
    return ctx.isKvDbType(dbTypeRaw) ? '生成查询' : '生成 SQL';
  }

  function explainActionLabelByDbType(dbTypeRaw: string) {
    return ctx.isKvDbType(dbTypeRaw) ? '解释查询' : '解释 SQL';
  }

  function analyzeActionLabelByDbType(dbTypeRaw: string) {
    return ctx.isKvDbType(dbTypeRaw) ? '分析查询' : '分析 SQL';
  }

  function canGenerateChartForTab(tab: QueryWorkspaceTab | null | undefined) {
    if (!tab) {
      return false;
    }
    return ctx.supportsGenerateChartByDbType(ctx.queryTabDbType(tab));
  }

  function sqlFormatterLanguage(dbTypeRaw: string): SqlLanguage {
    const dbType = (dbTypeRaw || 'MYSQL').toUpperCase();
    if (dbType === 'POSTGRESQL') {
      return 'postgresql';
    }
    if (dbType === 'SQLITE') {
      return 'sqlite';
    }
    if (dbType === 'SQLSERVER') {
      return 'transactsql';
    }
    if (dbType === 'ORACLE') {
      return 'plsql';
    }
    return 'mysql';
  }

  function formatSqlText(sourceSql: string, dbTypeRaw: string) {
    const mode = ctx.queryEditorModeByDbType(dbTypeRaw);
    if (mode === 'json') {
      try {
        return JSON.stringify(JSON.parse(sourceSql), null, 2);
      } catch {
        return sourceSql;
      }
    }
    if (mode === 'redis') {
      return sourceSql.trim();
    }
    return sqlFormat(sourceSql, {
      language: sqlFormatterLanguage(dbTypeRaw),
      keywordCase: 'upper',
      linesBetweenQueries: 1,
      tabWidth: 2,
    });
  }

  function formatSqlForTab(tab: QueryWorkspaceTab) {
    const isActiveTab = ctx.activeQueryTab.value?.key === tab.key;
    const editor = isActiveTab ? ctx.getActiveSqlEditor() : null;
    const model = editor?.getModel() ?? null;
    const selection = editor?.getSelection() ?? null;
    const hasSelection = !!model && !!selection && !selection.isEmpty();
    const sourceSql = hasSelection ? model.getValueInRange(selection).trim() : tab.sqlText.trim();
    if (!sourceSql) {
      message.info(ctx.isKvDbType(ctx.queryTabDbType(tab)) ? '请先输入查询文本' : '请先输入 SQL');
      return;
    }

    try {
      const formattedSql = formatSqlText(sourceSql, ctx.queryTabDbType(tab));
      if (editor && model) {
        const targetRange = hasSelection ? selection : model.getFullModelRange();
        editor.pushUndoStop();
        editor.executeEdits('sql-copilot.format-sql', [
          {
            range: targetRange,
            text: formattedSql,
            forceMoveMarkers: true,
          },
        ]);
        editor.pushUndoStop();
        tab.sqlText = model.getValue();
        editor.focus();
      } else {
        tab.sqlText = formattedSql;
      }
      tab.selectedSqlText = '';
      ctx.hideSqlSelectionPopover();
      ctx.touchQueryTab(tab);
      message.success(hasSelection ? '所选内容已格式化' : '查询文本已格式化');
    } catch (error) {
      message.error(`格式化失败：${ctx.getErrorMessage(error)}`);
    }
  }

  function formatObjectDefinitionSql(tab: ObjectDefinitionEditorTab) {
    const isActiveTab = ctx.activeObjectDefinitionEditorTab.value?.key === tab.key;
    const editor = isActiveTab ? ctx.getActiveSqlEditor() : null;
    const model = editor?.getModel() ?? null;
    const selection = editor?.getSelection() ?? null;
    const hasSelection = !!model && !!selection && !selection.isEmpty();
    const sourceSql = hasSelection ? model.getValueInRange(selection).trim() : tab.sqlText.trim();
    if (!sourceSql) {
      message.info('请先输入 SQL');
      return;
    }

    try {
      const formattedSql = formatSqlText(sourceSql, tab.dbType);
      if (editor && model) {
        const targetRange = hasSelection ? selection : model.getFullModelRange();
        editor.pushUndoStop();
        editor.executeEdits('sql-copilot.format-object-definition', [
          {
            range: targetRange,
            text: formattedSql,
            forceMoveMarkers: true,
          },
        ]);
        editor.pushUndoStop();
        tab.sqlText = model.getValue();
        editor.focus();
      } else {
        tab.sqlText = formattedSql;
      }
      tab.dirty = tab.sqlText.trim() !== tab.baselineSql.trim();
      tab.updatedAt = Date.now();
      message.success(hasSelection ? '所选 SQL 已美化' : 'SQL 已美化');
    } catch (error) {
      message.error(`SQL 美化失败：${ctx.getErrorMessage(error)}`);
    }
  }

  return {
    analyzeActionLabelByDbType,
    canGenerateChartForTab,
    explainActionLabelByDbType,
    formatObjectDefinitionSql,
    formatSqlForTab,
    formatSqlText,
    generateActionLabelByDbType,
    queryEditorLanguageByDbType,
    queryUnitLabelByDbType,
    sqlFormatterLanguage,
  };
}

export function createSqlEditorInteractionHelpers(ctx: SqlEditorInteractionHelperContext) {
  async function resolveSqlColumnSuggestions(
    monaco: typeof MonacoApi,
    model: MonacoApi.editor.ITextModel,
    position: MonacoApi.Position,
    context: SqlEditorContext,
    defaultRange: MonacoApi.IRange,
    wordPrefix: string,
  ) {
    const databaseName = String(context.databaseName || '').trim();
    if (!context.connectionId || !databaseName || databaseName === '未发现数据库') {
      return {
        qualified: false,
        suggestions: [] as ReturnType<typeof columnSuggestions>,
      };
    }

    const linePrefix = model.getLineContent(position.lineNumber).slice(0, position.column - 1);
    const sqlBeforeCursor = model.getValueInRange({
      startLineNumber: 1,
      startColumn: 1,
      endLineNumber: position.lineNumber,
      endColumn: position.column,
    });
    const tableReferences = extractSqlTableReferences(sqlBeforeCursor, databaseName);
    const qualifiedContext = parseQualifiedColumnContext(linePrefix);

    if (qualifiedContext) {
      const availableTableNames = await ctx.ensureTableNamesLoaded(context.connectionId, databaseName);
      const target = resolveQualifiedTableReference(
        qualifiedContext.qualifierParts,
        tableReferences,
        availableTableNames,
        databaseName,
      );
      if (!target) {
        return {
          qualified: true,
          suggestions: [] as ReturnType<typeof columnSuggestions>,
        };
      }
      const detail = await ctx.ensureQueryTableDetailLoaded(context.connectionId, target.databaseName, target.tableName);
      if (!detail?.columns?.length) {
        return {
          qualified: true,
          suggestions: [] as ReturnType<typeof columnSuggestions>,
        };
      }
      const range = {
        startLineNumber: position.lineNumber,
        endLineNumber: position.lineNumber,
        startColumn: qualifiedContext.replaceStartColumn,
        endColumn: position.column,
      };
      return {
        qualified: true,
        suggestions: columnSuggestions(monaco, detail.columns, range, qualifiedContext.prefix, target.sourceLabel),
      };
    }

    if (!wordPrefix || !tableReferences.length) {
      return {
        qualified: false,
        suggestions: [] as ReturnType<typeof columnSuggestions>,
      };
    }

    const uniqueTargets = Array.from(
      new Map(
        tableReferences.map((item) => [`${item.databaseName.toLowerCase()}|${item.tableName.toLowerCase()}`, item]),
      ).values(),
    ).slice(0, 8);

    const details = await Promise.all(
      uniqueTargets.map((item) => ctx.ensureQueryTableDetailLoaded(context.connectionId, item.databaseName, item.tableName)),
    );

    const suggestionMap = new Map<string, ReturnType<typeof columnSuggestions>[number]>();
    details.forEach((detail, index) => {
      if (!detail?.columns?.length) {
        return;
      }
      const target = uniqueTargets[index];
      columnSuggestions(monaco, detail.columns, defaultRange, wordPrefix, target.tableName).forEach((item) => {
        const key = String(item.label || '').toLowerCase();
        if (!key || suggestionMap.has(key)) {
          return;
        }
        suggestionMap.set(key, item);
      });
    });

    return {
      qualified: false,
      suggestions: Array.from(suggestionMap.values()),
    };
  }

  function tableNameSuggestions(
    monaco: typeof MonacoApi,
    names: string[],
    range: MonacoApi.IRange,
    prefix: string,
    databaseName: string,
  ) {
    const keyword = prefix.trim().toLowerCase();
    if (!keyword) {
      return [];
    }
    const uniqueNames = Array.from(new Set(names.filter((item) => !!item)));
    const matched = keyword
      ? uniqueNames.filter((name) => name.toLowerCase().includes(keyword))
      : uniqueNames;
    return matched.slice(0, 300).map((name) => {
      const startsWithPrefix = keyword && name.toLowerCase().startsWith(keyword);
      return {
        label: name,
        kind: monaco.languages.CompletionItemKind.Struct,
        insertText: name,
        range,
        detail: `表 · ${databaseName}`,
        sortText: `${startsWithPrefix ? '0' : '1'}_${name}`,
      };
    });
  }

  function sqlKeywordSuggestions(
    monaco: typeof MonacoApi,
    range: MonacoApi.IRange,
    prefix: string,
  ) {
    const keyword = prefix.trim().toUpperCase();
    if (!keyword) {
      return [];
    }
    const matched = keyword
      ? sqlKeywords.filter((item) => item.includes(keyword))
      : sqlKeywords;
    return matched.map((item) => {
      const startsWithPrefix = keyword && item.startsWith(keyword);
      return {
        label: item,
        kind: monaco.languages.CompletionItemKind.Keyword,
        insertText: item,
        range,
        detail: 'SQL keyword',
        sortText: `${startsWithPrefix ? '0' : '1'}_keyword_${item}`,
      };
    });
  }

  function hasKeywordSuggestion(prefix: string) {
    const keyword = prefix.trim().toUpperCase();
    if (!keyword) {
      return false;
    }
    return sqlKeywords.some((item) => item.includes(keyword));
  }

  function hasTableSuggestion(names: string[], prefix: string) {
    const keyword = prefix.trim().toLowerCase();
    if (!keyword) {
      return false;
    }
    return names.some((name) => name.toLowerCase().includes(keyword));
  }

  function resolveSqlEditorContextForModel(model: MonacoApi.editor.ITextModel | null) {
    if (!model) {
      return null;
    }
    return ctx.queryEditorContextResolverMap.get(model.uri.toString())?.() ?? null;
  }

  function registerSqlEditorContext(
    editor: MonacoApi.editor.IStandaloneCodeEditor,
    getContext: () => SqlEditorContext | null,
  ) {
    let currentModel: MonacoApi.editor.ITextModel | null = null;

    const syncModelContext = () => {
      if (currentModel) {
        ctx.queryEditorContextResolverMap.delete(currentModel.uri.toString());
      }
      currentModel = editor.getModel();
      if (currentModel) {
        ctx.queryEditorContextResolverMap.set(currentModel.uri.toString(), getContext);
      }
    };

    syncModelContext();
    const changeDisposable = editor.onDidChangeModel(() => {
      syncModelContext();
    });
    editor.onDidDispose(() => {
      changeDisposable.dispose();
      if (currentModel) {
        ctx.queryEditorContextResolverMap.delete(currentModel.uri.toString());
        currentModel = null;
      }
    });
  }

  function shouldAutoTriggerSuggestByContext(context: SqlEditorContext | null, prefix: string) {
    if (hasKeywordSuggestion(prefix)) {
      return true;
    }
    if (!context?.connectionId) {
      return false;
    }
    const tab = ctx.queryTabRefByContext(context);
    const databaseName = tab ? ctx.resolveQueryDatabaseName(tab) : String(context.databaseName || '').trim();
    if (!databaseName || databaseName === '未发现数据库') {
      return false;
    }
    const cacheKey = ctx.tableCacheKey(context.connectionId, databaseName);
    const loaded = !!ctx.tableNameLoadedCache.value[cacheKey];
    if (!loaded) {
      void ctx.ensureTableNamesLoaded(context.connectionId, databaseName);
      return false;
    }
    const tableNames = ctx.tableNameCache.value[cacheKey] ?? [];
    return hasTableSuggestion(tableNames, prefix);
  }

  function shouldAutoTriggerSuggest(tab: QueryWorkspaceTab, prefix: string) {
    return shouldAutoTriggerSuggestByContext({
      connectionId: tab.connectionId,
      databaseName: ctx.resolveQueryDatabaseName(tab),
    }, prefix);
  }

  function registerSqlCompletionProvider(monaco: typeof MonacoApi) {
    if (ctx.getSqlCompletionProviderDisposable()) {
      return;
    }
    ctx.setSqlCompletionProviderDisposable(monaco.languages.registerCompletionItemProvider('sql', {
      triggerCharacters: ['.', '`'],
      provideCompletionItems: async (model, position) => {
        const word = model.getWordUntilPosition(position);
        const wordPrefix = (word.word || '').trim();
        const context = resolveSqlEditorContextForModel(model)
          ?? (() => {
            const tab = ctx.activeQueryTab.value;
            if (!tab) {
              return null;
            }
            return {
              connectionId: tab.connectionId,
              databaseName: ctx.resolveQueryDatabaseName(tab),
            };
          })();
        const range = {
          startLineNumber: position.lineNumber,
          endLineNumber: position.lineNumber,
          startColumn: word.startColumn,
          endColumn: word.endColumn,
        };
        const keywordSuggestions = wordPrefix ? sqlKeywordSuggestions(monaco, range, wordPrefix) : [];
        if (!context?.connectionId) {
          return keywordSuggestions.length ? {suggestions: keywordSuggestions} : undefined;
        }
        const databaseName = String(context.databaseName || '').trim();
        if (!databaseName || databaseName === '未发现数据库') {
          return keywordSuggestions.length ? {suggestions: keywordSuggestions} : undefined;
        }
        const columnCompletion = await resolveSqlColumnSuggestions(monaco, model, position, context, range, wordPrefix);
        if (columnCompletion.qualified) {
          return columnCompletion.suggestions.length ? {suggestions: columnCompletion.suggestions} : undefined;
        }
        const tableNames = await ctx.ensureTableNamesLoaded(context.connectionId, databaseName);
        const tableSuggestions = wordPrefix
          ? tableNameSuggestions(monaco, tableNames, range, wordPrefix, databaseName)
          : [];
        const suggestions = [...columnCompletion.suggestions, ...tableSuggestions, ...keywordSuggestions];
        if (!suggestions.length) {
          return undefined;
        }
        return {suggestions};
      },
    }));
  }

  function registerSqlAutoSuggest(
    editor: MonacoApi.editor.IStandaloneCodeEditor,
    getContext: () => SqlEditorContext | null,
  ) {
    ctx.getSqlEditorTypeDisposable()?.dispose();
    ctx.setSqlEditorTypeDisposable(editor.onDidChangeModelContent((event) => {
      if (event.isFlush || !event.changes.length) {
        return;
      }
      const latestChange = event.changes[event.changes.length - 1];
      const typedText = latestChange.text ?? '';
      if (!typedText || typedText.length > 2 || /\s/.test(typedText)) {
        return;
      }
      if (!/[\w.`]/.test(typedText)) {
        return;
      }
      const model = editor.getModel();
      const position = editor.getPosition();
      if (!model || !position) {
        return;
      }
      const currentWord = model.getWordUntilPosition(position).word.trim();
      if (!currentWord) {
        return;
      }
      if (!shouldAutoTriggerSuggestByContext(getContext(), currentWord)) {
        return;
      }
      const currentTimer = ctx.getSqlAutoSuggestTimer();
      if (currentTimer !== null) {
        window.clearTimeout(currentTimer);
      }
      ctx.setSqlAutoSuggestTimer(window.setTimeout(() => {
        editor.trigger('sql-auto-suggest', 'editor.action.triggerSuggest', {});
      }, 60));
      syncSelectedSqlForActiveTab(false);
    }));
  }

  function readSelectedSql(editor: MonacoApi.editor.IStandaloneCodeEditor) {
    const model = editor.getModel();
    const selection = editor.getSelection();
    if (!model || !selection || selection.isEmpty()) {
      return '';
    }
    return model.getValueInRange(selection).trim();
  }

  function hideSqlSelectionPopover() {
    ctx.sqlSelectionPopover.visible = false;
  }

  function updateSqlSelectionPopoverPosition(editor: MonacoApi.editor.IStandaloneCodeEditor) {
    const selection = editor.getSelection();
    const container = ctx.sqlEditorContainerRef.value;
    const editorNode = editor.getDomNode();
    if (!selection || selection.isEmpty() || !container || !editorNode) {
      hideSqlSelectionPopover();
      return;
    }
    const visiblePosition = editor.getScrolledVisiblePosition(selection.getEndPosition());
    if (!visiblePosition) {
      hideSqlSelectionPopover();
      return;
    }
    const containerRect = container.getBoundingClientRect();
    const editorRect = editorNode.getBoundingClientRect();
    const popoverWidth = 340;
    const estimatedHeight = 36;
    const baseLeft = editorRect.left - containerRect.left + visiblePosition.left;
    const baseTop = editorRect.top - containerRect.top + visiblePosition.top;
    const maxLeft = Math.max(8, container.clientWidth - popoverWidth - 8);
    const left = Math.min(Math.max(8, baseLeft), maxLeft);
    const top = Math.max(8, baseTop - estimatedHeight - 6);
    ctx.sqlSelectionPopover.left = left;
    ctx.sqlSelectionPopover.top = top;
    ctx.sqlSelectionPopover.visible = true;
  }

  function syncSelectedSqlForActiveTab(showPopover = true) {
    const activeEditor = ctx.getActiveSqlEditor();
    const activeTab = ctx.activeQueryTab.value;
    if (!activeEditor || !activeTab) {
      hideSqlSelectionPopover();
      return;
    }
    activeTab.selectedSqlText = readSelectedSql(activeEditor);
    if (!activeTab.selectedSqlText) {
      hideSqlSelectionPopover();
      return;
    }
    if (!showPopover) {
      hideSqlSelectionPopover();
      return;
    }
    updateSqlSelectionPopoverPosition(activeEditor);
  }

  function registerSqlSelectionTracker(editor: MonacoApi.editor.IStandaloneCodeEditor) {
    ctx.getSqlEditorSelectionDisposable()?.dispose();
    ctx.setSqlEditorSelectionDisposable(editor.onDidChangeCursorSelection(() => {
      syncSelectedSqlForActiveTab(false);
    }));
  }

  function registerSqlSelectionPopoverTrigger(editor: MonacoApi.editor.IStandaloneCodeEditor) {
    ctx.getSqlEditorMouseDownDisposable()?.dispose();
    ctx.setSqlEditorMouseDownDisposable(editor.onMouseDown(() => {
      hideSqlSelectionPopover();
    }));
    ctx.getSqlEditorMouseUpDisposable()?.dispose();
    ctx.setSqlEditorMouseUpDisposable(editor.onMouseUp(() => {
      syncSelectedSqlForActiveTab(true);
    }));
  }

  function registerSqlScrollTracker(editor: MonacoApi.editor.IStandaloneCodeEditor) {
    ctx.getSqlEditorScrollDisposable()?.dispose();
    ctx.setSqlEditorScrollDisposable(editor.onDidScrollChange(() => {
      if (!ctx.activeQueryTab.value?.selectedSqlText) {
        hideSqlSelectionPopover();
        return;
      }
      updateSqlSelectionPopoverPosition(editor);
    }));
  }

  async function warmupTableSuggestions(tab: QueryWorkspaceTab | null) {
    if (!tab) {
      return;
    }
    const databaseName = ctx.resolveQueryDatabaseName(tab);
    if (!databaseName || databaseName === '未发现数据库') {
      return;
    }
    await ctx.ensureTableNamesLoaded(tab.connectionId, databaseName);
  }

  async function warmupTableSuggestionsForContext(context: SqlEditorContext | null) {
    if (!context?.connectionId) {
      return;
    }
    const databaseName = (context.databaseName || '').trim();
    if (!databaseName || databaseName === '未发现数据库') {
      return;
    }
    await ctx.ensureTableNamesLoaded(context.connectionId, databaseName);
  }

  function handleSqlEditorMount(
    editor: MonacoApi.editor.IStandaloneCodeEditor,
    monaco: typeof MonacoApi,
    options?: SqlEditorMountOptions,
  ) {
    const getContext = options?.getContext ?? (() => {
      const tab = ctx.activeQueryTab.value;
      if (!tab) {
        return null;
      }
      return {
        connectionId: tab.connectionId,
        databaseName: ctx.resolveQueryDatabaseName(tab),
      };
    });
    const enableSelectionActions = options?.enableSelectionActions !== false;

    ctx.setActiveSqlEditor(editor);
    monaco.editor.remeasureFonts();
    editor.layout();
    window.requestAnimationFrame(() => {
      monaco.editor.remeasureFonts();
      editor.layout();
    });
    registerSqlCompletionProvider(monaco);
    registerSqlEditorContext(editor, getContext);
    registerSqlAutoSuggest(editor, getContext);
    editor.addAction({
      id: 'sql-copilot.format-sql',
      label: '美化 SQL',
      contextMenuGroupId: '1_modification',
      keybindings: [monaco.KeyMod.Alt | monaco.KeyMod.Shift | monaco.KeyCode.KeyF],
      run: () => {
        const queryTab = ctx.activeQueryTab.value;
        if (queryTab && ctx.activeWorkbenchTab.value === queryTab.key) {
          ctx.formatSqlForTab(queryTab);
          return;
        }
        const objectDefinitionTab = ctx.activeObjectDefinitionEditorTab.value;
        if (objectDefinitionTab && ctx.activeWorkbenchTab.value === objectDefinitionTab.key) {
          ctx.formatObjectDefinitionSql(objectDefinitionTab);
        }
      },
    });
    if (enableSelectionActions) {
      registerSqlSelectionTracker(editor);
      registerSqlSelectionPopoverTrigger(editor);
      registerSqlScrollTracker(editor);
      syncSelectedSqlForActiveTab(false);
    } else {
      ctx.getSqlEditorSelectionDisposable()?.dispose();
      ctx.setSqlEditorSelectionDisposable(null);
      ctx.getSqlEditorScrollDisposable()?.dispose();
      ctx.setSqlEditorScrollDisposable(null);
      ctx.getSqlEditorMouseDownDisposable()?.dispose();
      ctx.setSqlEditorMouseDownDisposable(null);
      ctx.getSqlEditorMouseUpDisposable()?.dispose();
      ctx.setSqlEditorMouseUpDisposable(null);
      hideSqlSelectionPopover();
    }
    void warmupTableSuggestionsForContext(getContext());
  }

  return {
    tableNameSuggestions,
    sqlKeywordSuggestions,
    hasKeywordSuggestion,
    hasTableSuggestion,
    shouldAutoTriggerSuggest,
    registerSqlCompletionProvider,
    registerSqlAutoSuggest,
    readSelectedSql,
    hideSqlSelectionPopover,
    updateSqlSelectionPopoverPosition,
    syncSelectedSqlForActiveTab,
    registerSqlSelectionTracker,
    registerSqlSelectionPopoverTrigger,
    registerSqlScrollTracker,
    warmupTableSuggestions,
    handleSqlEditorMount,
  };
}
