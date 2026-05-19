import {
  AppstoreOutlined,
  ClockCircleOutlined,
  CodeOutlined,
  DatabaseOutlined,
  ExperimentOutlined,
  EyeOutlined,
  FolderOpenOutlined,
  HddOutlined,
  MinusCircleOutlined,
  SafetyCertificateOutlined,
  SearchOutlined,
  ToolOutlined,
} from '@ant-design/icons-vue';
import {message} from 'ant-design-vue';
import type {ConnectionVO} from '@sqlcopilot/shared-contracts';
import mysqlIcon from '../../../../assets/icons/mysql.png';
import oracleIcon from '../../../../assets/icons/oracle.png';
import postgresqlIcon from '../../../../assets/icons/postgresql.svg';
import redisIcon from '../../../../assets/icons/redis.png';
import sqliteIcon from '../../../../assets/icons/sqlite.png';
import sqlserverIcon from '../../../../assets/icons/sqlserver.svg';
import treeDatabaseIcon from '../../../../assets/icons/tree-database.png';
import treeTablesGroupIcon from '../../../../assets/icons/tree-tables-group.png';
import treeViewsGroupIcon from '../../../../assets/icons/tree-views-group.png';
import treeTableIcon from '../../../../assets/icons/tree-table.png';
import treeViewIcon from '../../../../assets/icons/tree-view.png';
import treeFunctionIcon from '../../../../assets/icons/tree-function.png';
import treeQueryIcon from '../../../../assets/icons/tree-query.png';
import treeSchemaIcon from '../../../../assets/icons/tree-schema.png';
import treeConnectedIcon from '../../../../assets/icons/tree-connected.png';
import treeOpenedFolderIcon from '../../../../assets/icons/tree-opened-folder.svg';
import folderClosedIcon from '../../../../assets/icons/folde.png';
import keyIcon from '../../../../assets/icons/key.svg';
import type {TableDetailVO} from '../../../../types';
import type {ObjectRow} from './types';

interface ObjectPresentationHelperContext {
  getPrimaryObjectLabel: () => string;
}

export function stripInvisibleChars(value: string) {
  if (!value) {
    return value;
  }
  // 移除零宽字符、不可见标记等隐藏 Unicode 字符，避免复制粘贴引入不可见字符导致 API 调用失败
  return value.replace(/[\u200B\u200C\u200D\u200E\u200F\u202A\u202B\u202C\u202D\u202E\u2060\u2061\u2062\u2063\u2064\u2065\u2066\u2067\u2068\u2069\u206A\u206B\u206C\u206D\u206E\u206F\uFEFF]/g, '');
}

export function containsDatabaseInHostInput(rawHost?: string): boolean {
  const host = (rawHost || '').trim();
  if (!host) {
    return false;
  }
  const queryIndex = host.indexOf('?');
  const normalized = queryIndex >= 0 ? host.slice(0, queryIndex) : host;
  const slashIndex = normalized.indexOf('/');
  return slashIndex >= 0 && slashIndex < normalized.length - 1;
}

export function getDatabaseNamePlaceholder(dbType: string) {
  if (dbType === 'SQLITE') {
    return 'SQLite 文件路径';
  }
  if (dbType === 'REDIS') {
    return '逻辑库（0-15），默认 0';
  }
  if (dbType === 'MONGODB') {
    return '数据库名/默认库';
  }
  return '数据库名/服务名';
}

export function normalizeSelectedDatabases(values: string[] | undefined) {
  if (!values?.length) {
    return [];
  }
  const set = new Set<string>();
  values.forEach((item) => {
    const value = (item || '').trim();
    if (value) {
      set.add(value);
    }
  });
  return Array.from(set);
}

export function toObjectType(value: string): 'tables' | 'views' | 'functions' | 'events' | 'queries' | 'backups' {
  const normalized = value.toLowerCase();
  if (normalized === 'tables' || normalized === 'views' || normalized === 'functions'
    || normalized === 'events' || normalized === 'queries' || normalized === 'backups') {
    return normalized;
  }
  return 'tables';
}

export function objectTypeLabelForValue(value: string, primaryObjectLabel = '表') {
  if (value === 'tables') {
    return primaryObjectLabel;
  }
  if (value === 'views') {
    return '视图';
  }
  if (value === 'functions') {
    return '函数';
  }
  if (value === 'queries') {
    return '查询';
  }
  return value;
}

export function createObjectPresentationHelpers(ctx: ObjectPresentationHelperContext) {
  function objectTypeLabel(value: string) {
    return objectTypeLabelForValue(value, ctx.getPrimaryObjectLabel());
  }

  return {
    toObjectType,
    objectTypeLabel,
  };
}

export function supportsSchemaLayer(dbType: string) {
  return dbType === 'POSTGRESQL' || dbType === 'SQLSERVER' || dbType === 'ORACLE';
}

export function buildSchemaContext(databaseName: string, namespaceName: string) {
  const normalizedDatabaseName = (databaseName || '').trim();
  const normalizedNamespaceName = (namespaceName || '').trim();
  if (!normalizedDatabaseName || !normalizedNamespaceName) {
    return normalizedDatabaseName;
  }
  return `${normalizedDatabaseName}::${normalizedNamespaceName}`;
}

export function parseSchemaContext(dbType: string, rawContext: string) {
  const normalizedContext = (rawContext || '').trim();
  if (!supportsSchemaLayer(dbType)) {
    return {
      rawContext: normalizedContext,
      databaseName: normalizedContext,
      namespaceName: '',
    };
  }
  const separatorIndex = normalizedContext.indexOf('::');
  if (separatorIndex <= 0 || separatorIndex >= normalizedContext.length - 2) {
    return {
      rawContext: normalizedContext,
      databaseName: normalizedContext,
      namespaceName: '',
    };
  }
  return {
    rawContext: normalizedContext,
    databaseName: normalizedContext.slice(0, separatorIndex).trim(),
    namespaceName: normalizedContext.slice(separatorIndex + 2).trim(),
  };
}

export function rootDatabaseNameForContext(dbType: string, rawContext: string) {
  return parseSchemaContext(dbType, rawContext).databaseName;
}

export function sanitizeDatabaseName(raw: string) {
  const queryIndex = raw.indexOf('?');
  const semicolonIndex = raw.indexOf(';');
  let value = raw;
  if (queryIndex >= 0) {
    value = value.substring(0, queryIndex);
  }
  if (semicolonIndex >= 0) {
    value = value.substring(0, semicolonIndex);
  }
  while (value.startsWith('/')) {
    value = value.substring(1);
  }
  return value.trim();
}

export function parseConfiguredDatabaseName(connection: ConnectionVO) {
  const direct = (connection.databaseName ?? '').trim();
  if (direct) {
    return sanitizeDatabaseName(direct);
  }
  const host = (connection.host ?? '').trim();
  if (!host) {
    return '';
  }
  const stripped = host.replace(/^jdbc:[^:]+:\/\//i, '').replace(/^[a-z]+:\/\//i, '');
  const atIndex = stripped.lastIndexOf('@');
  const hostPart = atIndex >= 0 ? stripped.substring(atIndex + 1) : stripped;
  const slashIndex = hostPart.indexOf('/');
  if (slashIndex >= 0 && slashIndex < hostPart.length - 1) {
    return sanitizeDatabaseName(hostPart.substring(slashIndex + 1));
  }
  return '';
}

export function escapeHtml(raw: string) {
  return raw
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

export function highlightSqlForDisplay(sqlText: string) {
  const escaped = escapeHtml(sqlText || '');
  const keywords = [
    'CREATE', 'ALTER', 'TABLE', 'PRIMARY', 'KEY', 'NOT', 'NULL', 'DEFAULT',
    'AUTO_INCREMENT', 'COMMENT', 'GENERATED', 'BY', 'AS', 'IDENTITY', 'ADD',
    'DROP', 'COLUMN', 'INDEX', 'UNIQUE', 'MODIFY', 'CURRENT_TIMESTAMP', 'ON', 'UPDATE',
  ];
  const dataTypes = [
    'BIGINT', 'INT', 'INTEGER', 'SMALLINT', 'TINYINT', 'MEDIUMINT', 'SERIAL', 'BIGSERIAL',
    'DECIMAL', 'NUMERIC', 'FLOAT', 'DOUBLE', 'REAL', 'BOOLEAN', 'BIT', 'CHAR', 'NCHAR',
    'VARCHAR', 'NVARCHAR', 'TEXT', 'MEDIUMTEXT', 'LONGTEXT', 'DATE', 'TIME', 'DATETIME',
    'TIMESTAMP', 'YEAR', 'BLOB', 'LONGBLOB', 'JSON', 'UUID',
  ];
  const keywordSet = new Set(keywords.map((item) => item.toUpperCase()));
  const typeSet = new Set(dataTypes.map((item) => item.toUpperCase()));
  const tokens = Array.from(new Set([...keywords, ...dataTypes])).sort((a, b) => b.length - a.length);
  const tokenPattern = new RegExp(`\\b(${tokens.join('|')})\\b`, 'gi');
  return escaped.replace(tokenPattern, (matched) => {
    const upper = matched.toUpperCase();
    if (keywordSet.has(upper)) {
      return `<span class="sql-keyword">${matched}</span>`;
    }
    if (typeSet.has(upper)) {
      return `<span class="sql-datatype">${matched}</span>`;
    }
    return matched;
  });
}

export function formatSize(sizeBytes: number) {
  if (!sizeBytes || sizeBytes <= 0) {
    return '0 B';
  }
  const units = ['B', 'KB', 'MB', 'GB', 'TB'];
  let value = sizeBytes;
  let unitIndex = 0;
  while (value >= 1024 && unitIndex < units.length - 1) {
    value /= 1024;
    unitIndex += 1;
  }
  return `${value >= 100 || unitIndex === 0 ? value.toFixed(0) : value.toFixed(1)} ${units[unitIndex]}`;
}

export function formatCompactCount(count?: number) {
  const value = Number(count || 0);
  if (!Number.isFinite(value) || value <= 0) {
    return '0';
  }
  if (value >= 100000000) {
    return `${(value / 100000000).toFixed(value >= 1000000000 ? 0 : 1)}亿`;
  }
  if (value >= 10000) {
    return `${(value / 10000).toFixed(value >= 100000 ? 0 : 1)}万`;
  }
  return `${Math.round(value)}`;
}

export function formatTime(ts?: number) {
  if (!ts || ts <= 0) {
    return '-';
  }
  const date = new Date(ts);
  const yyyy = date.getFullYear();
  const mm = `${date.getMonth() + 1}`.padStart(2, '0');
  const dd = `${date.getDate()}`.padStart(2, '0');
  const hh = `${date.getHours()}`.padStart(2, '0');
  const min = `${date.getMinutes()}`.padStart(2, '0');
  const sec = `${date.getSeconds()}`.padStart(2, '0');
  return `${yyyy}-${mm}-${dd} ${hh}:${min}:${sec}`;
}

export function formatDurationMs(durationMs?: number) {
  const value = Number(durationMs || 0);
  if (!Number.isFinite(value) || value < 0) {
    return '-';
  }
  if (value < 1000) {
    return `${Math.round(value)} ms`;
  }
  if (value < 60000) {
    return `${(value / 1000).toFixed(value >= 10000 ? 0 : 1)} s`;
  }
  const minutes = Math.floor(value / 60000);
  const seconds = Math.round((value % 60000) / 1000);
  return `${minutes} min ${seconds} s`;
}

export function formatVectorizeProvider(provider?: string) {
  const normalized = (provider || '').trim().toUpperCase();
  if (!normalized) {
    return '-';
  }
  if (normalized === 'CORE_ML') {
    return 'Core ML';
  }
  if (normalized === 'DIRECT_ML') {
    return 'DirectML';
  }
  if (normalized === 'HASH_FALLBACK') {
    return '哈希降级';
  }
  return normalized;
}

export function normalizeEnv(value?: string) {
  const env = (value || '').trim().toUpperCase();
  if (env === 'PROD' || env === 'TEST' || env === 'DEV') {
    return env;
  }
  return 'DEV';
}

export function envTagText(value?: string) {
  const env = normalizeEnv(value);
  if (env === 'PROD') {
    return '生产';
  }
  if (env === 'TEST') {
    return '测试';
  }
  return 'Dev';
}

export function envTagClass(value?: string) {
  const env = normalizeEnv(value);
  if (env === 'PROD') {
    return 'is-prod';
  }
  if (env === 'TEST') {
    return 'is-test';
  }
  return 'is-dev';
}

export function envTagIcon(value?: string) {
  const env = normalizeEnv(value);
  if (env === 'PROD') {
    return SafetyCertificateOutlined;
  }
  if (env === 'TEST') {
    return ExperimentOutlined;
  }
  return ToolOutlined;
}

export function nodeIconComponent(dataRef: { nodeType?: string }) {
  if (dataRef.nodeType === 'group') {
    return FolderOpenOutlined;
  }
  if (dataRef.nodeType === 'group-empty') {
    return MinusCircleOutlined;
  }
  if (dataRef.nodeType === 'database') {
    return DatabaseOutlined;
  }
  if (dataRef.nodeType === 'tables') {
    return FolderOpenOutlined;
  }
  if (dataRef.nodeType === 'views') {
    return EyeOutlined;
  }
  if (dataRef.nodeType === 'functions') {
    return CodeOutlined;
  }
  if (dataRef.nodeType === 'events') {
    return ClockCircleOutlined;
  }
  if (dataRef.nodeType === 'queries') {
    return SearchOutlined;
  }
  if (dataRef.nodeType === 'backups') {
    return HddOutlined;
  }
  return AppstoreOutlined;
}

export function dbIconUrl(dbType: string) {
  if (dbType === 'MYSQL') {
    return mysqlIcon;
  }
  if (dbType === 'POSTGRESQL') {
    return postgresqlIcon;
  }
  if (dbType === 'REDIS') {
    return redisIcon;
  }
  if (dbType === 'SQLITE') {
    return sqliteIcon;
  }
  if (dbType === 'SQLSERVER') {
    return sqlserverIcon;
  }
  if (dbType === 'ORACLE') {
    return oracleIcon;
  }
  return '';
}

export function treeNodeIconUrl(
  dataRef: { nodeType?: string; objectName?: string; namespaceName?: string },
  expanded = false,
) {
  const nodeType = dataRef.nodeType;
  if (!nodeType || nodeType === 'connection') {
    return '';
  }
  if (nodeType === 'group' || nodeType === 'group-empty') {
    return expanded ? treeOpenedFolderIcon : folderClosedIcon;
  }
  if (nodeType === 'database' || nodeType === 'database-root') {
    if (dataRef.namespaceName) {
      return treeSchemaIcon;
    }
    return treeDatabaseIcon;
  }
  if (nodeType === 'tables') {
    return dataRef.objectName ? treeTableIcon : treeTablesGroupIcon;
  }
  if (nodeType === 'views') {
    return dataRef.objectName ? treeViewIcon : treeViewsGroupIcon;
  }
  if (nodeType === 'functions') {
    return treeFunctionIcon;
  }
  if (nodeType === 'queries') {
    return treeQueryIcon;
  }
  return '';
}

export function treeTitleIconSrc(
  dataRef: { nodeType?: string; dbType?: string; objectName?: string; namespaceName?: string },
  expanded = false,
) {
  if (dataRef.nodeType === 'connection') {
    return dbIconUrl(String(dataRef.dbType || ''));
  }
  return treeNodeIconUrl(dataRef, expanded);
}

export function browserObjectIconSrc(record: ObjectRow, options?: { expanded?: boolean }) {
  if (record.redisNodeType === 'PATH') {
    return options?.expanded ? treeOpenedFolderIcon : folderClosedIcon;
  }
  if (record.redisNodeType === 'KEY') {
    return keyIcon;
  }
  if (record.redisNodeType === 'LOAD_MORE') {
    return treeConnectedIcon;
  }
  if (record.objectType === 'tables') {
    return treeTableIcon;
  }
  if (record.objectType === 'views') {
    return treeViewIcon;
  }
  if (record.objectType === 'functions') {
    return treeFunctionIcon;
  }
  if (record.objectType === 'queries') {
    return treeQueryIcon;
  }
  return treeDatabaseIcon;
}

export function quoteSqlIdentifier(identifier: string, dbType: string) {
  const text = String(identifier || '').trim();
  if (!text) {
    return '';
  }
  if (dbType === 'SQLSERVER') {
    return `[${text}]`;
  }
  if (dbType === 'POSTGRESQL' || dbType === 'ORACLE') {
    return `"${text}"`;
  }
  return `\`${text}\``;
}

export function quoteSqlObjectName(objectName: string, dbType: string) {
  const normalized = String(objectName || '').trim();
  if (!normalized) {
    return '';
  }
  return normalized
    .split('.')
    .map((segment) => {
      const text = String(segment || '').trim();
      return quoteSqlIdentifier(text, dbType) || text;
    })
    .join('.');
}

export function buildObjectQuerySql(objectName: string, dbType: string) {
  const normalizedDbType = String(dbType || '').trim().toUpperCase();
  const objectRef = quoteSqlObjectName(objectName, normalizedDbType) || objectName;
  if (normalizedDbType === 'SQLSERVER') {
    return `SELECT TOP 100 * FROM ${objectRef}`;
  }
  if (normalizedDbType === 'ORACLE') {
    return `SELECT * FROM ${objectRef} FETCH FIRST 100 ROWS ONLY`;
  }
  return `SELECT * FROM ${objectRef} LIMIT 100`;
}

export function buildColumnSqlDefinition(
  column: TableDetailVO['columns'][number],
  dbType: string,
) {
  const columnName = quoteSqlIdentifier(column.columnName, dbType) || column.columnName;
  const baseType = String(column.dataType || 'TEXT').trim().toUpperCase();
  let typeSql = baseType;
  if (!/\(/.test(baseType) && column.columnSize && column.columnSize > 0) {
    if (column.decimalDigits && column.decimalDigits > 0) {
      typeSql = `${baseType}(${column.columnSize},${column.decimalDigits})`;
    } else if (/char|binary|var|text|int|number|decimal|numeric/i.test(baseType)) {
      typeSql = `${baseType}(${column.columnSize})`;
    }
  }
  const fragments = [`${columnName} ${typeSql}`];
  if (column.nullable === false) {
    fragments.push('NOT NULL');
  }
  if (column.defaultValue != null && String(column.defaultValue).trim() !== '') {
    fragments.push(`DEFAULT ${String(column.defaultValue).trim()}`);
  }
  if (column.autoIncrement) {
    if (dbType === 'SQLSERVER') {
      fragments.push('IDENTITY(1,1)');
    } else if (dbType === 'POSTGRESQL') {
      fragments.push('GENERATED BY DEFAULT AS IDENTITY');
    } else {
      fragments.push('AUTO_INCREMENT');
    }
  }
  if (column.columnComment && dbType === 'MYSQL') {
    fragments.push(`COMMENT '${column.columnComment.replace(/'/g, "''")}'`);
  }
  return fragments.join(' ');
}

export function buildCreateTableSql(tableName: string, columns: TableDetailVO['columns'], dbTypeRaw: string) {
  const dbType = (dbTypeRaw || 'MYSQL').toUpperCase();
  const lines = columns.map((column) => `  ${buildColumnSqlDefinition(column, dbType)}`);
  const primaryKeys = columns
    .filter((column) => column.primaryKey)
    .map((column) => quoteSqlIdentifier(column.columnName, dbType) || column.columnName);
  if (primaryKeys.length) {
    lines.push(`  PRIMARY KEY (${primaryKeys.join(', ')})`);
  }
  const tableQuoted = quoteSqlIdentifier(tableName, dbType) || tableName;
  return `CREATE TABLE ${tableQuoted} (\n${lines.join(',\n')}\n);`;
}

export async function copyTextContent(text: string, successText: string) {
  if (!text.trim()) {
    return;
  }
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text);
      message.success(successText);
      return;
    }
    throw new Error('clipboard unavailable');
  } catch {
    const input = document.createElement('textarea');
    input.value = text;
    input.setAttribute('readonly', 'true');
    input.style.position = 'fixed';
    input.style.opacity = '0';
    document.body.appendChild(input);
    input.select();
    document.execCommand('copy');
    document.body.removeChild(input);
    message.success(successText);
  }
}
