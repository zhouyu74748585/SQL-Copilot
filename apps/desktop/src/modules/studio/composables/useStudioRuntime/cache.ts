export function buildRedisBrowserCacheKey(
  connectionId: number,
  databaseName: string,
  parentPath: string,
  keyword: string,
  cursor = '0',
) {
  return `${connectionId}|${databaseName || '__default__'}|${parentPath || '__root__'}|${keyword || '__all__'}|${cursor || '0'}`;
}

export function namespaceCacheKey(connectionId: number, databaseName: string) {
  return `${connectionId}|${databaseName || '__default__'}`;
}

export function tableCacheKey(connectionId: number, databaseName: string) {
  return `${connectionId}|${databaseName || '__default__'}`;
}

export function objectCacheKey(connectionId: number, databaseName: string, objectType: string) {
  return `${connectionId}|${databaseName || '__default__'}|${objectType}`;
}

export function vectorizeStatusCacheKey(connectionId: number, databaseName: string) {
  return `${connectionId}|${databaseName.trim().toLowerCase()}`;
}

export function queryTableDetailCacheKey(connectionId: number, databaseName: string, tableName: string) {
  return `${connectionId}|${databaseName || ''}|${tableName || ''}`;
}
