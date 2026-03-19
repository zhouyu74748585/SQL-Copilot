package com.sqlcopilot.studio.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.sqlcopilot.studio.dto.kv.KvObjectDetailVO;
import com.sqlcopilot.studio.dto.kv.KvObjectSummaryVO;
import com.sqlcopilot.studio.dto.kv.KvOverviewVO;
import com.sqlcopilot.studio.dto.kv.KvQueryExecuteReq;
import com.sqlcopilot.studio.dto.kv.KvRedisBrowserNodeVO;
import com.sqlcopilot.studio.dto.kv.KvRedisBrowserPageVO;
import com.sqlcopilot.studio.dto.kv.KvRedisKeyDeleteReq;
import com.sqlcopilot.studio.dto.kv.KvRedisKeyDeleteVO;
import com.sqlcopilot.studio.dto.kv.KvRedisKeyEntryVO;
import com.sqlcopilot.studio.dto.kv.KvRedisKeySaveReq;
import com.sqlcopilot.studio.dto.kv.KvRedisKeySaveVO;
import com.sqlcopilot.studio.dto.schema.SchemaDatabaseVO;
import com.sqlcopilot.studio.dto.sql.ColumnMetaVO;
import com.sqlcopilot.studio.dto.sql.QueryCellVO;
import com.sqlcopilot.studio.dto.sql.QueryRowVO;
import com.sqlcopilot.studio.dto.sql.SqlExecuteVO;
import com.sqlcopilot.studio.entity.ConnectionEntity;
import com.sqlcopilot.studio.service.ConnectionService;
import com.sqlcopilot.studio.service.KvService;
import com.sqlcopilot.studio.service.kv.KvRuntimeClientFactory;
import com.sqlcopilot.studio.util.BusinessException;
import io.lettuce.core.KeyScanCursor;
import io.lettuce.core.RedisFuture;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.ScoredValue;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.api.StatefulRedisConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.bson.Document;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** KV/文档存储浏览与查询服务。 */
@Service
public class KvServiceImpl implements KvService {

    private static final Logger log = LoggerFactory.getLogger(KvServiceImpl.class);

    private static final String DB_TYPE_MONGODB = "MONGODB";
    private static final String DB_TYPE_REDIS = "REDIS";
    private static final String REDIS_DELETE_TARGET_KEY = "KEY";
    private static final String REDIS_DELETE_TARGET_PATH = "PATH";
    private static final String REDIS_BROWSER_NODE_PATH = "PATH";
    private static final String REDIS_BROWSER_NODE_KEY = "KEY";
    private static final int DEFAULT_MAX_ROWS = 100;
    private static final int DEFAULT_REDIS_BROWSER_PAGE_SIZE = 100;
    private static final int MAX_REDIS_BROWSER_PAGE_SIZE = 200;
    private static final int REDIS_BROWSER_SCAN_BATCH = 500;
    private static final int REDIS_BROWSER_MAX_SCAN_ROUNDS = 12;
    private static final int REDIS_DELETE_SCAN_BATCH = 5000;
    private static final Duration REDIS_BROWSER_METADATA_TIMEOUT = Duration.ofSeconds(5);
    private static final int MAX_BROWSER_OBJECTS = 200;
    private static final Pattern TOKEN_PATTERN = Pattern.compile("\"([^\"]*)\"|'([^']*)'|(\\S+)");

    private final ConnectionService connectionService;
    private final KvRuntimeClientFactory kvRuntimeClientFactory;
    private final ObjectMapper objectMapper;

    public KvServiceImpl(ConnectionService connectionService,
                         KvRuntimeClientFactory kvRuntimeClientFactory,
                         ObjectMapper objectMapper) {
        this.connectionService = connectionService;
        this.kvRuntimeClientFactory = kvRuntimeClientFactory;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<SchemaDatabaseVO> listDatabases(Long connectionId) {
        ConnectionEntity entity = connectionService.getConnectionEntity(connectionId);
        String dbType = normalizeType(entity.getDbType());
        if (DB_TYPE_MONGODB.equals(dbType)) {
            return kvRuntimeClientFactory.withMongoClient(entity, connectionId, client -> {
                List<SchemaDatabaseVO> result = new ArrayList<>();
                for (String name : client.listDatabaseNames()) {
                    result.add(buildKvDatabaseVo(name));
                }
                return result;
            });
        }
        if (DB_TYPE_REDIS.equals(dbType)) {
            return kvRuntimeClientFactory.listRedisDatabases(entity, connectionId).stream()
                .map(this::buildKvDatabaseVo)
                .toList();
        }
        throw unsupportedDbType(dbType);
    }

    @Override
    public KvOverviewVO getOverview(Long connectionId, String databaseName) {
        ConnectionEntity entity = connectionService.getConnectionEntity(connectionId);
        String dbType = normalizeType(entity.getDbType());
        if (DB_TYPE_MONGODB.equals(dbType)) {
            return kvRuntimeClientFactory.withMongoClient(entity, connectionId, client ->
                buildMongoOverview(connectionId, resolveMongoDatabaseName(entity, databaseName), client));
        }
        if (DB_TYPE_REDIS.equals(dbType)) {
            KvOverviewVO vo = new KvOverviewVO();
            vo.setConnectionId(connectionId);
            vo.setDatabaseName(resolveRedisDatabaseName(databaseName));
            vo.setObjectLabel("键");
            vo.setObjectCount(0);
            vo.setObjects(List.of());
            return vo;
        }
        throw unsupportedDbType(dbType);
    }

    @Override
    public KvRedisBrowserPageVO browseRedis(Long connectionId,
                                            String databaseName,
                                            String parentPath,
                                            String keyword,
                                            String cursor,
                                            Integer pageSize) {
        ConnectionEntity entity = connectionService.getConnectionEntity(connectionId);
        if (!DB_TYPE_REDIS.equals(normalizeType(entity.getDbType()))) {
            throw unsupportedDbType(entity.getDbType());
        }
        String resolvedDatabaseName = resolveRedisDatabaseName(databaseName);
        String normalizedParentPath = normalizeRedisPath(parentPath);
        String normalizedKeyword = safe(keyword);
        String normalizedCursor = normalizeRedisCursor(cursor);
        int normalizedPageSize = normalizeRedisBrowserPageSize(pageSize);
        return kvRuntimeClientFactory.withRedisConnection(entity, connectionId, resolvedDatabaseName, connection ->
            buildRedisBrowserPage(connectionId,
                resolvedDatabaseName,
                normalizedParentPath,
                normalizedKeyword,
                normalizedCursor,
                normalizedPageSize,
                connection));
    }

    @Override
    public KvObjectDetailVO getObjectDetail(Long connectionId, String databaseName, String objectName) {
        ConnectionEntity entity = connectionService.getConnectionEntity(connectionId);
        String dbType = normalizeType(entity.getDbType());
        if (DB_TYPE_MONGODB.equals(dbType)) {
            return kvRuntimeClientFactory.withMongoClient(entity, connectionId, client ->
                buildMongoObjectDetail(connectionId, resolveMongoDatabaseName(entity, databaseName), objectName, client));
        }
        if (DB_TYPE_REDIS.equals(dbType)) {
            String resolvedDatabaseName = resolveRedisDatabaseName(databaseName);
            return kvRuntimeClientFactory.withRedisConnection(entity, connectionId, resolvedDatabaseName, connection ->
                buildRedisObjectDetail(connectionId, resolveRedisDatabaseName(databaseName), objectName, connection.sync()));
        }
        throw unsupportedDbType(dbType);
    }

    @Override
    public SqlExecuteVO executeQuery(KvQueryExecuteReq req) {
        ConnectionEntity entity = connectionService.getConnectionEntity(req.getConnectionId());
        String dbType = normalizeType(entity.getDbType());
        if (DB_TYPE_MONGODB.equals(dbType)) {
            return kvRuntimeClientFactory.withMongoClient(entity, req.getConnectionId(), client ->
                executeMongoQuery(entity, req, client));
        }
        if (DB_TYPE_REDIS.equals(dbType)) {
            String resolvedDatabaseName = resolveRedisDatabaseName(req.getDatabaseName());
            return kvRuntimeClientFactory.withRedisConnection(entity, req.getConnectionId(), resolvedDatabaseName, connection ->
                executeRedisCommand(entity, req, connection.sync()));
        }
        throw unsupportedDbType(dbType);
    }

    @Override
    public KvRedisKeySaveVO createRedisKey(KvRedisKeySaveReq req) {
        return saveRedisKey(req, true);
    }

    @Override
    public KvRedisKeySaveVO updateRedisKey(KvRedisKeySaveReq req) {
        return saveRedisKey(req, false);
    }

    @Override
    public KvRedisKeyDeleteVO deleteRedisKey(KvRedisKeyDeleteReq req) {
        ConnectionEntity entity = connectionService.getConnectionEntity(req.getConnectionId());
        if (!DB_TYPE_REDIS.equals(normalizeType(entity.getDbType()))) {
            throw unsupportedDbType(entity.getDbType());
        }
        String resolvedDatabaseName = resolveRedisDatabaseName(req.getDatabaseName());
        return kvRuntimeClientFactory.withRedisConnection(entity, req.getConnectionId(), resolvedDatabaseName, connection -> {
            RedisCommands<String, String> sync = connection.sync();
            String targetType = normalizeRedisDeleteTargetType(req.getTargetType());
            String targetValue = safe(req.getTargetValue());
            if (targetValue.isBlank()) {
                throw new BusinessException(400, "Redis 删除目标不能为空");
            }
            long deletedCount = REDIS_DELETE_TARGET_PATH.equals(targetType)
                ? deleteRedisPath(sync, targetValue)
                : deleteRedisExactKey(sync, targetValue);
            KvRedisKeyDeleteVO vo = new KvRedisKeyDeleteVO();
            vo.setTargetType(targetType);
            vo.setTargetValue(targetValue);
            vo.setDeletedCount(deletedCount);
            vo.setMessage(REDIS_DELETE_TARGET_PATH.equals(targetType)
                ? String.format("已删除路径前缀 %s 下 %d 个键", targetValue, deletedCount)
                : String.format("已删除键 %s（%d）", targetValue, deletedCount));
            return vo;
        });
    }

    private KvOverviewVO buildMongoOverview(Long connectionId, String databaseName, com.mongodb.client.MongoClient client) {
        MongoDatabase database = client.getDatabase(databaseName);
        List<KvObjectSummaryVO> objects = new ArrayList<>();
        for (String name : database.listCollectionNames()) {
            KvObjectSummaryVO item = new KvObjectSummaryVO();
            item.setObjectName(name);
            item.setValueType("collection");
            item.setItemCount(database.getCollection(name).estimatedDocumentCount());
            item.setDescription("MongoDB 集合");
            objects.add(item);
        }
        KvOverviewVO vo = new KvOverviewVO();
        vo.setConnectionId(connectionId);
        vo.setDatabaseName(databaseName);
        vo.setObjectLabel("集合");
        vo.setObjectCount(objects.size());
        vo.setObjects(objects);
        return vo;
    }

    private KvOverviewVO buildRedisOverview(Long connectionId, String databaseName, RedisCommands<String, String> sync) {
        List<String> keys = scanRedisKeys(sync, MAX_BROWSER_OBJECTS);
        List<KvObjectSummaryVO> objects = new ArrayList<>();
        for (String key : keys) {
            KvObjectSummaryVO item = new KvObjectSummaryVO();
            item.setObjectName(key);
            item.setValueType(safe(sync.type(key)).toLowerCase(Locale.ROOT));
            item.setItemCount(null);
            long ttl = safeLong(sync.ttl(key));
            item.setDescription(ttl >= 0 ? ("TTL " + ttl + "s") : "无过期时间");
            objects.add(item);
        }
        KvOverviewVO vo = new KvOverviewVO();
        vo.setConnectionId(connectionId);
        vo.setDatabaseName(databaseName);
        vo.setObjectLabel("键");
        vo.setObjectCount(objects.size());
        vo.setObjects(objects);
        return vo;
    }

    private KvRedisBrowserPageVO buildRedisBrowserPage(Long connectionId,
                                                       String databaseName,
                                                       String parentPath,
                                                       String keyword,
                                                       String cursor,
                                                       int pageSize,
                                                       StatefulRedisConnection<String, String> connection) {
        RedisCommands<String, String> sync = connection.sync();
        if (!keyword.isBlank()) {
            return buildRedisSearchBrowserPage(connectionId, databaseName, keyword, cursor, pageSize, connection);
        }
        String matchPattern = buildRedisBranchMatchPattern(parentPath);
        LinkedHashMap<String, KvRedisBrowserNodeVO> nodeMap = new LinkedHashMap<>();
        ScanCursor scanCursor = ScanCursor.of(cursor);
        boolean finished = false;
        int scanRounds = 0;
        do {
            scanRounds++;
            ScanArgs args = ScanArgs.Builder.limit(Math.max(pageSize * 4, REDIS_BROWSER_SCAN_BATCH));
            if (!matchPattern.isBlank()) {
                args.match(matchPattern);
            }
            KeyScanCursor<String> result = sync.scan(scanCursor, args);
            for (String key : result.getKeys()) {
                collectRedisBrowserNode(nodeMap, parentPath, key, false, pageSize);
                if (nodeMap.size() >= pageSize) {
                    break;
                }
            }
            scanCursor = ScanCursor.of(result.getCursor());
            finished = scanCursor.isFinished();
        } while (!finished && nodeMap.size() < pageSize && scanRounds < REDIS_BROWSER_MAX_SCAN_ROUNDS);

        List<KvRedisBrowserNodeVO> items = new ArrayList<>(nodeMap.values());
        fillRedisBrowserKeyMetadata(connection, items);
        items.sort(Comparator
            .comparing((KvRedisBrowserNodeVO item) -> !REDIS_BROWSER_NODE_PATH.equals(item.getNodeType()))
            .thenComparing(item -> safe(item.getNodeName()).toLowerCase(Locale.ROOT)));

        KvRedisBrowserPageVO vo = new KvRedisBrowserPageVO();
        vo.setConnectionId(connectionId);
        vo.setDatabaseName(databaseName);
        vo.setParentPath(parentPath);
        vo.setKeyword(keyword);
        vo.setCursor(cursor);
        vo.setNextCursor(finished ? "0" : scanCursor.getCursor());
        vo.setFinished(finished);
        vo.setItems(items);
        log.debug("Redis 树表浏览完成, connectionId={}, databaseName={}, parentPath={}, keyword={}, itemCount={}, nextCursor={}, finished={}",
            connectionId, databaseName, parentPath, keyword, items.size(), vo.getNextCursor(), finished);
        return vo;
    }

    private KvRedisBrowserPageVO buildRedisSearchBrowserPage(Long connectionId,
                                                             String databaseName,
                                                             String keyword,
                                                             String cursor,
                                                             int pageSize,
                                                             StatefulRedisConnection<String, String> connection) {
        RedisCommands<String, String> sync = connection.sync();
        LinkedHashSet<String> matchedKeys = new LinkedHashSet<>();
        ScanCursor scanCursor = ScanCursor.of(cursor);
        boolean finished = false;
        int scanRounds = 0;
        do {
            scanRounds++;
            ScanArgs args = ScanArgs.Builder.limit(Math.max(pageSize * 4, REDIS_BROWSER_SCAN_BATCH)).match(keyword);
            KeyScanCursor<String> result = sync.scan(scanCursor, args);
            matchedKeys.addAll(result.getKeys());
            scanCursor = ScanCursor.of(result.getCursor());
            finished = scanCursor.isFinished();
        } while (!finished && matchedKeys.size() < pageSize && scanRounds < REDIS_BROWSER_MAX_SCAN_ROUNDS);

        List<KvRedisBrowserNodeVO> items = buildRedisSearchTreeItems(matchedKeys.stream().limit(pageSize).toList());
        fillRedisBrowserKeyMetadata(connection, items);
        KvRedisBrowserPageVO vo = new KvRedisBrowserPageVO();
        vo.setConnectionId(connectionId);
        vo.setDatabaseName(databaseName);
        vo.setParentPath("");
        vo.setKeyword(keyword);
        vo.setCursor(cursor);
        vo.setNextCursor(finished ? "0" : scanCursor.getCursor());
        vo.setFinished(finished);
        vo.setItems(items);
        return vo;
    }

    private List<KvRedisBrowserNodeVO> buildRedisSearchTreeItems(List<String> matchedKeys) {
        LinkedHashMap<String, KvRedisBrowserNodeVO> nodeMap = new LinkedHashMap<>();
        for (String key : matchedKeys) {
            String normalizedKey = safe(key);
            if (normalizedKey.isBlank()) {
                continue;
            }
            List<String> segments = Arrays.stream(normalizedKey.split(":"))
                .map(this::safe)
                .filter(item -> !item.isBlank())
                .toList();
            String currentPath = "";
            for (int index = 0; index < segments.size(); index++) {
                String segment = segments.get(index);
                currentPath = currentPath.isBlank() ? segment : currentPath + ":" + segment;
                if (index < segments.size() - 1) {
                    String nodeKey = "path:" + currentPath;
                    String fullPath = currentPath;
                    nodeMap.computeIfAbsent(nodeKey, ignored -> {
                        KvRedisBrowserNodeVO vo = new KvRedisBrowserNodeVO();
                        vo.setNodeKey(nodeKey);
                        vo.setNodeName(segment);
                        vo.setFullPath(fullPath);
                        vo.setNodeType(REDIS_BROWSER_NODE_PATH);
                        vo.setHasChildren(Boolean.TRUE);
                        vo.setDescription("命中搜索结果的路径节点");
                        return vo;
                    });
                    continue;
                }
                String nodeKey = "key:" + normalizedKey;
                String leafName = segment;
                nodeMap.computeIfAbsent(nodeKey, ignored -> {
                    KvRedisBrowserNodeVO vo = new KvRedisBrowserNodeVO();
                    vo.setNodeKey(nodeKey);
                    vo.setNodeName(leafName);
                    vo.setFullPath(normalizedKey);
                    vo.setNodeType(REDIS_BROWSER_NODE_KEY);
                    vo.setHasChildren(Boolean.FALSE);
                    vo.setObjectName(normalizedKey);
                    vo.setDescription("命中搜索结果的键节点");
                    return vo;
                });
            }
        }
        return nodeMap.values().stream()
            .sorted(Comparator
                .comparingInt((KvRedisBrowserNodeVO item) -> redisTreeDepth(item.getFullPath()))
                .thenComparing((KvRedisBrowserNodeVO item) -> !REDIS_BROWSER_NODE_PATH.equals(item.getNodeType()))
                .thenComparing(item -> safe(item.getFullPath()).toLowerCase(Locale.ROOT)))
            .toList();
    }

    private void collectRedisBrowserNode(Map<String, KvRedisBrowserNodeVO> nodeMap,
                                         String parentPath,
                                         String key,
                                         boolean searchMode,
                                         int pageSize) {
        if (nodeMap.size() >= pageSize) {
            return;
        }
        String normalizedKey = safe(key);
        if (normalizedKey.isBlank()) {
            return;
        }
        String directChildName = resolveRedisDirectChildName(parentPath, normalizedKey);
        if (directChildName.isBlank()) {
            return;
        }
        String fullPath = parentPath.isBlank() ? directChildName : parentPath + ":" + directChildName;
        String relative = parentPath.isBlank()
            ? normalizedKey
            : normalizedKey.substring(Math.min(normalizedKey.length(), parentPath.length() + 1));
        boolean pathNode = relative.contains(":");
        if (pathNode) {
            String nodeKey = "path:" + fullPath;
            nodeMap.computeIfAbsent(nodeKey, ignored -> {
                KvRedisBrowserNodeVO vo = new KvRedisBrowserNodeVO();
                vo.setNodeKey(nodeKey);
                vo.setNodeName(directChildName);
                vo.setFullPath(fullPath);
                vo.setNodeType(REDIS_BROWSER_NODE_PATH);
                vo.setHasChildren(Boolean.TRUE);
                vo.setDescription(searchMode ? "命中搜索结果的路径节点" : "路径节点");
                return vo;
            });
            return;
        }
        String nodeKey = "key:" + normalizedKey;
        nodeMap.computeIfAbsent(nodeKey, ignored -> {
            KvRedisBrowserNodeVO vo = new KvRedisBrowserNodeVO();
            vo.setNodeKey(nodeKey);
            vo.setNodeName(directChildName);
            vo.setFullPath(fullPath);
            vo.setNodeType(REDIS_BROWSER_NODE_KEY);
            vo.setHasChildren(Boolean.FALSE);
            vo.setObjectName(normalizedKey);
            vo.setDescription("键节点");
            return vo;
        });
    }

    private void fillRedisBrowserKeyMetadata(StatefulRedisConnection<String, String> connection,
                                             List<KvRedisBrowserNodeVO> items) {
        List<KvRedisBrowserNodeVO> keyNodes = items.stream()
            .filter(item -> REDIS_BROWSER_NODE_KEY.equals(item.getNodeType()) && !safe(item.getObjectName()).isBlank())
            .toList();
        if (keyNodes.isEmpty()) {
            return;
        }
        RedisAsyncCommands<String, String> async = connection.async();
        List<RedisFuture<String>> typeFutures = new ArrayList<>();
        List<RedisFuture<Long>> ttlFutures = new ArrayList<>();
        try {
            connection.setAutoFlushCommands(false);
            for (KvRedisBrowserNodeVO item : keyNodes) {
                typeFutures.add(async.type(item.getObjectName()));
                ttlFutures.add(async.ttl(item.getObjectName()));
            }
            connection.flushCommands();
            for (int i = 0; i < keyNodes.size(); i++) {
                KvRedisBrowserNodeVO item = keyNodes.get(i);
                String valueType = safe(readRedisFuture(typeFutures.get(i)));
                Long ttlSeconds = readRedisFuture(ttlFutures.get(i));
                long ttl = safeLong(ttlSeconds);
                item.setValueType(valueType.toLowerCase(Locale.ROOT));
                item.setTtlSeconds(ttl >= 0 ? ttl : -1L);
                item.setDescription(ttl >= 0 ? ("TTL " + ttl + "s") : "无过期时间");
            }
        } finally {
            connection.setAutoFlushCommands(true);
        }
    }

    private <T> T readRedisFuture(RedisFuture<T> future) {
        try {
            return future.get(REDIS_BROWSER_METADATA_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        } catch (Exception ex) {
            throw new BusinessException(500, "读取 Redis 键元信息失败: " + ex.getMessage());
        }
    }

    private KvObjectDetailVO buildMongoObjectDetail(Long connectionId,
                                                    String databaseName,
                                                    String objectName,
                                                    com.mongodb.client.MongoClient client) {
        MongoDatabase database = client.getDatabase(databaseName);
        MongoCollection<Document> collection = database.getCollection(objectName);
        Document sample = collection.find().limit(1).first();
        List<String> facts = new ArrayList<>();
        facts.add("estimatedDocumentCount = " + collection.estimatedDocumentCount());
        List<String> indexNames = new ArrayList<>();
        for (Document indexDoc : collection.listIndexes()) {
            Object name = indexDoc.get("name");
            if (name != null) {
                indexNames.add(String.valueOf(name));
            }
        }
        if (!indexNames.isEmpty()) {
            facts.add("indexes = " + String.join(", ", indexNames));
        }

        KvObjectDetailVO vo = new KvObjectDetailVO();
        vo.setConnectionId(connectionId);
        vo.setDatabaseName(databaseName);
        vo.setObjectName(objectName);
        vo.setValueType("collection");
        vo.setDescription("MongoDB 集合对象");
        vo.setQueryTemplate(buildMongoQueryTemplate(objectName));
        vo.setSampleJson(toPrettyJson(sample == null ? Map.of("message", "collection is empty") : sample));
        vo.setFacts(facts);
        return vo;
    }

    private KvObjectDetailVO buildRedisObjectDetail(Long connectionId,
                                                    String databaseName,
                                                    String objectName,
                                                    RedisCommands<String, String> sync) {
        String type = safe(sync.type(objectName)).toLowerCase(Locale.ROOT);
        long ttl = safeLong(sync.ttl(objectName));
        List<String> facts = new ArrayList<>();
        facts.add("ttl = " + (ttl >= 0 ? ttl + "s" : "no-expire"));
        facts.add("type = " + (type.isBlank() ? "unknown" : type));

        KvObjectDetailVO vo = new KvObjectDetailVO();
        vo.setConnectionId(connectionId);
        vo.setDatabaseName(databaseName);
        vo.setObjectName(objectName);
        vo.setValueType(type);
        vo.setDescription("Redis 键对象");
        vo.setQueryTemplate(buildRedisQueryTemplate(objectName, type));
        vo.setSampleJson(loadRedisPreview(sync, objectName, type));
        vo.setTtlSeconds(ttl >= 0 ? ttl : -1L);
        vo.setEditorMode("string".equals(type) ? "text" : "json");
        vo.setEditorPayload(loadRedisEditorPayload(sync, objectName, type));
        vo.setFacts(facts);
        return vo;
    }

    private SqlExecuteVO executeMongoQuery(ConnectionEntity entity,
                                           KvQueryExecuteReq req,
                                           com.mongodb.client.MongoClient client) {
        try {
            JsonNode root = objectMapper.readTree(req.getQueryText());
            String collectionName = safe(root.path("collection").asText());
            if (collectionName.isBlank()) {
                throw new BusinessException(400, "Mongo 查询必须包含 collection 字段");
            }
            String operation = safe(root.path("operation").asText()).toLowerCase(Locale.ROOT);
            if (operation.isBlank()) {
                operation = "find";
            }
            MongoDatabase database = client.getDatabase(resolveMongoDatabaseName(entity, req.getDatabaseName()));
            MongoCollection<Document> collection = database.getCollection(collectionName);
            int maxRows = normalizeMaxRows(req.getMaxRows());

            long start = System.currentTimeMillis();
            List<Map<String, Object>> records;
            if ("find".equals(operation) || "findone".equals(operation)) {
                Document filter = parseDocumentNode(root.get("filter"));
                Document projection = parseDocumentNode(root.get("projection"));
                Document sort = parseDocumentNode(root.get("sort"));
                int limit = root.path("limit").isInt() ? root.path("limit").asInt() : maxRows;
                var iterable = collection.find(filter);
                if (!projection.isEmpty()) {
                    iterable = iterable.projection(projection);
                }
                if (!sort.isEmpty()) {
                    iterable = iterable.sort(sort);
                }
                if ("findone".equals(operation)) {
                    Document first = iterable.first();
                    records = first == null ? List.of() : List.of(documentToMap(first));
                } else {
                    records = new ArrayList<>();
                    for (Document doc : iterable.limit(Math.min(limit, maxRows))) {
                        records.add(documentToMap(doc));
                    }
                }
            } else if ("aggregate".equals(operation)) {
                List<Document> pipeline = parseDocumentListNode(root.get("pipeline"));
                records = new ArrayList<>();
                for (Document doc : collection.aggregate(pipeline).allowDiskUse(false)) {
                    records.add(documentToMap(doc));
                    if (records.size() >= maxRows) {
                        break;
                    }
                }
            } else if ("count".equals(operation) || "countdocuments".equals(operation)) {
                long count = collection.countDocuments(parseDocumentNode(root.get("filter")));
                records = List.of(new LinkedHashMap<>(Map.of("count", count)));
            } else if ("distinct".equals(operation)) {
                String field = safe(root.path("field").asText());
                if (field.isBlank()) {
                    throw new BusinessException(400, "Mongo distinct 查询必须包含 field 字段");
                }
                records = new ArrayList<>();
                for (Object value : collection.distinct(field, parseDocumentNode(root.get("filter")), Object.class)) {
                    records.add(new LinkedHashMap<>(Map.of("value", value)));
                    if (records.size() >= maxRows) {
                        break;
                    }
                }
            } else {
                throw new BusinessException(400, "暂不支持的 Mongo 操作: " + operation);
            }
            return buildExecuteResult(records, System.currentTimeMillis() - start, "执行成功");
        } catch (BusinessException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new BusinessException(500, "Mongo 查询执行失败: " + ex.getMessage());
        }
    }

    private SqlExecuteVO executeRedisCommand(ConnectionEntity entity,
                                             KvQueryExecuteReq req,
                                             RedisCommands<String, String> sync) {
        String queryText = safe(req.getQueryText());
        if (queryText.isBlank()) {
            throw new BusinessException(400, "Redis 命令不能为空");
        }
        List<String> tokens = tokenize(queryText);
        if (tokens.isEmpty()) {
            throw new BusinessException(400, "Redis 命令不能为空");
        }
        String command = tokens.get(0).toUpperCase(Locale.ROOT);
        int maxRows = normalizeMaxRows(req.getMaxRows());
        long start = System.currentTimeMillis();
        List<Map<String, Object>> rows = switch (command) {
            case "GET" -> executeRedisGet(sync, tokens);
            case "TYPE" -> executeRedisType(sync, tokens);
            case "TTL" -> executeRedisTtl(sync, tokens);
            case "EXISTS" -> executeRedisExists(sync, tokens);
            case "HGETALL" -> executeRedisHgetall(sync, tokens, maxRows);
            case "LRANGE" -> executeRedisLrange(sync, tokens, maxRows);
            case "SMEMBERS" -> executeRedisSmembers(sync, tokens, maxRows);
            case "ZRANGE" -> executeRedisZrange(sync, tokens, maxRows);
            case "KEYS" -> executeRedisKeys(sync, tokens, maxRows);
            case "SCAN" -> executeRedisScan(sync, tokens, maxRows);
            default -> throw new BusinessException(400, "当前仅支持 GET/TYPE/TTL/EXISTS/HGETALL/LRANGE/SMEMBERS/ZRANGE/KEYS/SCAN");
        };
        return buildExecuteResult(rows, System.currentTimeMillis() - start, "执行成功");
    }

    private List<Map<String, Object>> executeRedisGet(RedisCommands<String, String> sync, List<String> tokens) {
        requireTokenCount(tokens, 2, "GET key");
        return List.of(new LinkedHashMap<>(Map.of(
            "key", tokens.get(1),
            "value", sync.get(tokens.get(1))
        )));
    }

    private List<Map<String, Object>> executeRedisType(RedisCommands<String, String> sync, List<String> tokens) {
        requireTokenCount(tokens, 2, "TYPE key");
        return List.of(new LinkedHashMap<>(Map.of(
            "key", tokens.get(1),
            "type", sync.type(tokens.get(1))
        )));
    }

    private List<Map<String, Object>> executeRedisTtl(RedisCommands<String, String> sync, List<String> tokens) {
        requireTokenCount(tokens, 2, "TTL key");
        return List.of(new LinkedHashMap<>(Map.of(
            "key", tokens.get(1),
            "ttl", sync.ttl(tokens.get(1))
        )));
    }

    private List<Map<String, Object>> executeRedisExists(RedisCommands<String, String> sync, List<String> tokens) {
        requireTokenCount(tokens, 2, "EXISTS key");
        return List.of(new LinkedHashMap<>(Map.of(
            "key", tokens.get(1),
            "exists", sync.exists(tokens.get(1))
        )));
    }

    private List<Map<String, Object>> executeRedisHgetall(RedisCommands<String, String> sync, List<String> tokens, int maxRows) {
        requireTokenCount(tokens, 2, "HGETALL key");
        Map<String, String> values = sync.hgetall(tokens.get(1));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<String, String> entry : values.entrySet()) {
            rows.add(new LinkedHashMap<>(Map.of("field", entry.getKey(), "value", entry.getValue())));
            if (rows.size() >= maxRows) {
                break;
            }
        }
        return rows;
    }

    private List<Map<String, Object>> executeRedisLrange(RedisCommands<String, String> sync, List<String> tokens, int maxRows) {
        requireTokenCount(tokens, 4, "LRANGE key start stop");
        List<String> values = sync.lrange(tokens.get(1), Long.parseLong(tokens.get(2)), Long.parseLong(tokens.get(3)));
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int i = 0; i < values.size() && i < maxRows; i++) {
            rows.add(new LinkedHashMap<>(Map.of("index", i, "value", values.get(i))));
        }
        return rows;
    }

    private List<Map<String, Object>> executeRedisSmembers(RedisCommands<String, String> sync, List<String> tokens, int maxRows) {
        requireTokenCount(tokens, 2, "SMEMBERS key");
        List<Map<String, Object>> rows = new ArrayList<>();
        int index = 0;
        for (String value : sync.smembers(tokens.get(1))) {
            rows.add(new LinkedHashMap<>(Map.of("index", index++, "value", value)));
            if (rows.size() >= maxRows) {
                break;
            }
        }
        return rows;
    }

    private List<Map<String, Object>> executeRedisZrange(RedisCommands<String, String> sync, List<String> tokens, int maxRows) {
        requireTokenCount(tokens, 4, "ZRANGE key start stop [WITHSCORES]");
        boolean withScores = tokens.size() > 4 && "WITHSCORES".equalsIgnoreCase(tokens.get(4));
        List<Map<String, Object>> rows = new ArrayList<>();
        if (withScores) {
            int index = 0;
            for (ScoredValue<String> value : sync.zrangeWithScores(
                tokens.get(1),
                Long.parseLong(tokens.get(2)),
                Long.parseLong(tokens.get(3))
            )) {
                rows.add(new LinkedHashMap<>(Map.of(
                    "index", index++,
                    "value", value.getValue(),
                    "score", value.getScore()
                )));
                if (rows.size() >= maxRows) {
                    break;
                }
            }
            return rows;
        }
        int index = 0;
        for (String value : sync.zrange(tokens.get(1), Long.parseLong(tokens.get(2)), Long.parseLong(tokens.get(3)))) {
            rows.add(new LinkedHashMap<>(Map.of("index", index++, "value", value)));
            if (rows.size() >= maxRows) {
                break;
            }
        }
        return rows;
    }

    private List<Map<String, Object>> executeRedisKeys(RedisCommands<String, String> sync, List<String> tokens, int maxRows) {
        requireTokenCount(tokens, 2, "KEYS pattern");
        List<Map<String, Object>> rows = new ArrayList<>();
        int index = 0;
        for (String key : sync.keys(tokens.get(1))) {
            rows.add(new LinkedHashMap<>(Map.of("index", index++, "key", key)));
            if (rows.size() >= maxRows) {
                break;
            }
        }
        return rows;
    }

    private List<Map<String, Object>> executeRedisScan(RedisCommands<String, String> sync, List<String> tokens, int maxRows) {
        String cursor = tokens.size() > 1 ? tokens.get(1) : "0";
        ScanArgs scanArgs = buildRedisScanArgs(tokens);
        KeyScanCursor<String> result = sync.scan(ScanCursor.of(cursor), scanArgs);
        List<Map<String, Object>> rows = new ArrayList<>();
        int index = 0;
        for (String key : result.getKeys()) {
            rows.add(new LinkedHashMap<>(Map.of(
                "index", index++,
                "nextCursor", result.getCursor(),
                "key", key
            )));
            if (rows.size() >= maxRows) {
                break;
            }
        }
        return rows;
    }

    private ScanArgs buildRedisScanArgs(List<String> tokens) {
        ScanArgs args = ScanArgs.Builder.limit(MAX_BROWSER_OBJECTS);
        for (int i = 2; i < tokens.size(); i += 2) {
            String option = tokens.get(i).toUpperCase(Locale.ROOT);
            if ("MATCH".equals(option) && i + 1 < tokens.size()) {
                args.match(tokens.get(i + 1));
            }
            if ("COUNT".equals(option) && i + 1 < tokens.size()) {
                args.limit(Integer.parseInt(tokens.get(i + 1)));
            }
        }
        return args;
    }

    private SqlExecuteVO buildExecuteResult(List<Map<String, Object>> records, long executionMs, String message) {
        LinkedHashSet<String> columnSet = new LinkedHashSet<>();
        for (Map<String, Object> row : records) {
            columnSet.addAll(row.keySet());
        }
        List<String> columns = new ArrayList<>(columnSet);
        List<ColumnMetaVO> columnMetas = new ArrayList<>();
        for (String column : columns) {
            ColumnMetaVO meta = new ColumnMetaVO();
            meta.setColumnName(column);
            meta.setColumnType("STRING");
            columnMetas.add(meta);
        }
        List<QueryRowVO> rows = new ArrayList<>();
        for (Map<String, Object> record : records) {
            QueryRowVO row = new QueryRowVO();
            List<QueryCellVO> cells = new ArrayList<>();
            for (String column : columns) {
                QueryCellVO cell = new QueryCellVO();
                cell.setColumnName(column);
                cell.setCellValue(stringifyValue(record.get(column)));
                cells.add(cell);
            }
            row.setCells(cells);
            rows.add(row);
        }

        SqlExecuteVO vo = new SqlExecuteVO();
        vo.setSuccess(Boolean.TRUE);
        vo.setAffectedRows(rows.size());
        vo.setExecutionMs(executionMs);
        vo.setColumns(columnMetas);
        vo.setRows(rows);
        vo.setTruncated(Boolean.FALSE);
        vo.setMessage(message);
        return vo;
    }

    private List<String> scanRedisKeys(RedisCommands<String, String> sync, int maxKeys) {
        List<String> keys = new ArrayList<>();
        ScanCursor cursor = ScanCursor.INITIAL;
        ScanArgs args = ScanArgs.Builder.limit(Math.min(maxKeys, 200));
        do {
            KeyScanCursor<String> result = sync.scan(cursor, args);
            keys.addAll(result.getKeys());
            cursor = ScanCursor.of(result.getCursor());
            if (keys.size() >= maxKeys) {
                break;
            }
        } while (!cursor.isFinished());
        if (keys.size() > maxKeys) {
            return keys.subList(0, maxKeys);
        }
        return keys;
    }

    private String buildRedisBranchMatchPattern(String parentPath) {
        String prefix = escapeRedisGlob(parentPath) + ":";
        return parentPath.isBlank() ? "" : prefix + "*";
    }

    private String resolveRedisDirectChildName(String parentPath, String key) {
        if (parentPath.isBlank()) {
            String[] segments = key.split(":", 2);
            return safe(segments[0]);
        }
        String prefix = parentPath + ":";
        if (!key.startsWith(prefix) || key.length() <= prefix.length()) {
            return "";
        }
        String relative = key.substring(prefix.length());
        String[] segments = relative.split(":", 2);
        return safe(segments[0]);
    }

    private String normalizeRedisPath(String path) {
        String normalized = safe(path);
        while (normalized.startsWith(":")) {
            normalized = normalized.substring(1);
        }
        while (normalized.endsWith(":")) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String normalizeRedisCursor(String cursor) {
        String normalized = safe(cursor);
        return normalized.isBlank() ? "0" : normalized;
    }

    private int normalizeRedisBrowserPageSize(Integer pageSize) {
        int normalized = pageSize == null || pageSize <= 0 ? DEFAULT_REDIS_BROWSER_PAGE_SIZE : pageSize;
        return Math.min(Math.max(normalized, 20), MAX_REDIS_BROWSER_PAGE_SIZE);
    }

    private String normalizeRedisDeleteTargetType(String targetType) {
        String normalized = safe(targetType).toUpperCase(Locale.ROOT);
        if (REDIS_DELETE_TARGET_KEY.equals(normalized) || REDIS_DELETE_TARGET_PATH.equals(normalized)) {
            return normalized;
        }
        throw new BusinessException(400, "不支持的 Redis 删除目标类型: " + targetType);
    }

    private long deleteRedisExactKey(RedisCommands<String, String> sync, String keyName) {
        return safeLong(sync.del(keyName));
    }

    private long deleteRedisPath(RedisCommands<String, String> sync, String pathPrefix) {
        String normalizedPrefix = normalizeRedisPath(pathPrefix);
        if (normalizedPrefix.isBlank()) {
            throw new BusinessException(400, "Redis 路径前缀不能为空");
        }
        long deletedCount = 0L;
        ScanCursor cursor = ScanCursor.INITIAL;
        ScanArgs args = ScanArgs.Builder.limit(REDIS_DELETE_SCAN_BATCH).match(normalizedPrefix + ":*");
        do {
            KeyScanCursor<String> result = sync.scan(cursor, args);
            List<String> keys = result.getKeys();
            if (!keys.isEmpty()) {
                deletedCount += safeLong(sync.del(keys.toArray(String[]::new)));
            }
            cursor = ScanCursor.of(result.getCursor());
        } while (!cursor.isFinished());
        return deletedCount;
    }

    private int redisTreeDepth(String fullPath) {
        String normalized = safe(fullPath);
        if (normalized.isBlank()) {
            return 0;
        }
        return (int) normalized.chars().filter(ch -> ch == ':').count();
    }

    private String escapeRedisGlob(String text) {
        String normalized = safe(text);
        if (normalized.isBlank()) {
            return "";
        }
        StringBuilder builder = new StringBuilder(normalized.length());
        for (char ch : normalized.toCharArray()) {
            if (ch == '*' || ch == '?' || ch == '[' || ch == ']' || ch == '\\') {
                builder.append('\\');
            }
            builder.append(ch);
        }
        return builder.toString();
    }

    private Document parseDocumentNode(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return new Document();
        }
        try {
            return Document.parse(objectMapper.writeValueAsString(node));
        } catch (Exception ex) {
            throw new BusinessException(400, "Mongo 查询 JSON 结构非法: " + ex.getMessage());
        }
    }

    private List<Document> parseDocumentListNode(JsonNode node) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            return List.of();
        }
        try {
            List<Map<String, Object>> rows = objectMapper.readValue(
                objectMapper.writeValueAsString(node),
                new TypeReference<List<Map<String, Object>>>() {
                }
            );
            List<Document> documents = new ArrayList<>();
            for (Map<String, Object> row : rows) {
                documents.add(new Document(row));
            }
            return documents;
        } catch (Exception ex) {
            throw new BusinessException(400, "Mongo pipeline 必须是 JSON 数组");
        }
    }

    private Map<String, Object> documentToMap(Document document) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : document.entrySet()) {
            row.put(entry.getKey(), entry.getValue());
        }
        return row;
    }

    private String buildMongoQueryTemplate(String collectionName) {
        return """
            {
              "collection": "%s",
              "operation": "find",
              "filter": {},
              "projection": {},
              "sort": {},
              "limit": 100
            }
            """.formatted(collectionName);
    }

    private String buildRedisQueryTemplate(String objectName, String type) {
        String normalizedType = safe(type).toLowerCase(Locale.ROOT);
        return switch (normalizedType) {
            case "hash" -> "HGETALL " + objectName;
            case "list" -> "LRANGE " + objectName + " 0 99";
            case "set" -> "SMEMBERS " + objectName;
            case "zset" -> "ZRANGE " + objectName + " 0 99 WITHSCORES";
            default -> "GET " + objectName;
        };
    }

    private String loadRedisPreview(RedisCommands<String, String> sync, String key, String type) {
        String normalizedType = safe(type).toLowerCase(Locale.ROOT);
        Object preview = switch (normalizedType) {
            case "hash" -> sync.hgetall(key);
            case "list" -> sync.lrange(key, 0, 19);
            case "set" -> sync.smembers(key);
            case "zset" -> sync.zrangeWithScores(key, 0, 19);
            default -> sync.get(key);
        };
        return toPrettyJson(preview);
    }

    private String loadRedisEditorPayload(RedisCommands<String, String> sync, String key, String type) {
        String normalizedType = safe(type).toLowerCase(Locale.ROOT);
        if ("string".equals(normalizedType)) {
            return Objects.toString(sync.get(key), "");
        }
        Object payload = switch (normalizedType) {
            case "hash" -> sync.hgetall(key);
            case "list" -> sync.lrange(key, 0, -1);
            case "set" -> {
                List<String> values = new ArrayList<>(sync.smembers(key));
                values.sort(String::compareToIgnoreCase);
                yield values;
            }
            case "zset" -> {
                List<Map<String, Object>> values = new ArrayList<>();
                for (ScoredValue<String> item : sync.zrangeWithScores(key, 0, -1)) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("member", item.getValue());
                    row.put("score", item.getScore());
                    values.add(row);
                }
                yield values;
            }
            default -> sync.get(key);
        };
        return toPrettyJson(payload);
    }

    private KvRedisKeySaveVO saveRedisKey(KvRedisKeySaveReq req, boolean createMode) {
        ConnectionEntity entity = connectionService.getConnectionEntity(req.getConnectionId());
        if (!DB_TYPE_REDIS.equals(normalizeType(entity.getDbType()))) {
            throw unsupportedDbType(entity.getDbType());
        }
        String resolvedDatabaseName = resolveRedisDatabaseName(req.getDatabaseName());
        return kvRuntimeClientFactory.withRedisConnection(entity, req.getConnectionId(), resolvedDatabaseName, connection -> {
            RedisCommands<String, String> sync = connection.sync();
            String keyName = safe(req.getKeyName());
            String valueType = safe(req.getValueType()).toLowerCase(Locale.ROOT);
            if (keyName.isBlank()) {
                throw new BusinessException(400, "Redis 键名不能为空");
            }
            boolean exists = sync.exists(keyName) > 0;
            if (createMode && exists) {
                throw new BusinessException(400, "Redis 键已存在: " + keyName);
            }
            if (!createMode && !exists) {
                throw new BusinessException(404, "Redis 键不存在: " + keyName);
            }
            if (!createMode && exists) {
                sync.del(keyName);
            }
            writeRedisValue(sync, keyName, valueType, req);
            applyRedisTtl(sync, keyName, req.getTtlSeconds());
            KvRedisKeySaveVO vo = new KvRedisKeySaveVO();
            vo.setSuccess(Boolean.TRUE);
            vo.setMessage(createMode ? "Redis 键创建成功" : "Redis 键更新成功");
            vo.setKeyName(keyName);
            vo.setValueType(valueType);
            return vo;
        });
    }

    private void writeRedisValue(RedisCommands<String, String> sync,
                                 String keyName,
                                 String valueType,
                                 KvRedisKeySaveReq req) {
        switch (valueType) {
            case "string" -> sync.set(keyName, Objects.toString(req.getStringValue(), ""));
            case "hash" -> writeRedisHash(sync, keyName, req.getEntries());
            case "list" -> writeRedisList(sync, keyName, req.getEntries());
            case "set" -> writeRedisSet(sync, keyName, req.getEntries());
            case "zset" -> writeRedisZset(sync, keyName, req.getEntries());
            default -> throw new BusinessException(400, "暂不支持的 Redis 键类型: " + valueType);
        }
    }

    private void writeRedisHash(RedisCommands<String, String> sync, String keyName, List<KvRedisKeyEntryVO> entries) {
        Map<String, String> values = new LinkedHashMap<>();
        for (KvRedisKeyEntryVO item : safeEntries(entries)) {
            String field = safe(item.getKey());
            if (!field.isBlank()) {
                values.put(field, Objects.toString(item.getValue(), ""));
            }
        }
        if (values.isEmpty()) {
            throw new BusinessException(400, "Hash 类型至少需要一个字段");
        }
        sync.hset(keyName, values);
    }

    private void writeRedisList(RedisCommands<String, String> sync, String keyName, List<KvRedisKeyEntryVO> entries) {
        List<String> values = new ArrayList<>();
        for (KvRedisKeyEntryVO item : safeEntries(entries)) {
            values.add(Objects.toString(item.getValue(), ""));
        }
        if (values.isEmpty()) {
            throw new BusinessException(400, "List 类型至少需要一个元素");
        }
        sync.rpush(keyName, values.toArray(new String[0]));
    }

    private void writeRedisSet(RedisCommands<String, String> sync, String keyName, List<KvRedisKeyEntryVO> entries) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (KvRedisKeyEntryVO item : safeEntries(entries)) {
            values.add(Objects.toString(item.getValue(), ""));
        }
        if (values.isEmpty()) {
            throw new BusinessException(400, "Set 类型至少需要一个成员");
        }
        sync.sadd(keyName, values.toArray(new String[0]));
    }

    private void writeRedisZset(RedisCommands<String, String> sync, String keyName, List<KvRedisKeyEntryVO> entries) {
        List<KvRedisKeyEntryVO> safeEntries = safeEntries(entries);
        if (safeEntries.isEmpty()) {
            throw new BusinessException(400, "ZSet 类型至少需要一个成员");
        }
        int written = 0;
        for (KvRedisKeyEntryVO item : safeEntries) {
            String member = safe(item.getKey());
            if (member.isBlank()) {
                member = safe(item.getValue());
            }
            if (member.isBlank()) {
                continue;
            }
            double score = item.getScore() == null ? 0D : item.getScore();
            sync.zadd(keyName, score, member);
            written++;
        }
        if (written == 0) {
            throw new BusinessException(400, "ZSet 类型至少需要一个有效成员");
        }
    }

    private void applyRedisTtl(RedisCommands<String, String> sync, String keyName, Long ttlSeconds) {
        long ttl = ttlSeconds == null ? -1L : ttlSeconds;
        if (ttl > 0) {
            sync.expire(keyName, ttl);
            return;
        }
        sync.persist(keyName);
    }

    private List<KvRedisKeyEntryVO> safeEntries(List<KvRedisKeyEntryVO> entries) {
        return entries == null ? List.of() : entries;
    }

    private List<String> tokenize(String text) {
        List<String> tokens = new ArrayList<>();
        Matcher matcher = TOKEN_PATTERN.matcher(text);
        while (matcher.find()) {
            String value = matcher.group(1);
            if (value == null) {
                value = matcher.group(2);
            }
            if (value == null) {
                value = matcher.group(3);
            }
            if (value != null && !value.isBlank()) {
                tokens.add(value);
            }
        }
        return tokens;
    }

    private int normalizeMaxRows(Integer value) {
        int maxRows = value == null || value <= 0 ? DEFAULT_MAX_ROWS : value;
        return Math.min(Math.max(maxRows, 1), 500);
    }

    private void requireTokenCount(List<String> tokens, int expected, String example) {
        if (tokens.size() < expected) {
            throw new BusinessException(400, "命令参数不足，示例: " + example);
        }
    }

    private String resolveMongoDatabaseName(ConnectionEntity entity, String databaseName) {
        String resolved = safe(databaseName);
        if (!resolved.isBlank()) {
            return resolved;
        }
        resolved = safe(entity.getDatabaseName());
        if (!resolved.isBlank()) {
            return resolved;
        }
        throw new BusinessException(400, "MongoDB 必须选择数据库");
    }

    private String resolveRedisDatabaseName(String databaseName) {
        String resolved = safe(databaseName);
        return resolved.isBlank() ? "0" : resolved;
    }

    private SchemaDatabaseVO buildKvDatabaseVo(String databaseName) {
        SchemaDatabaseVO vo = new SchemaDatabaseVO();
        vo.setDatabaseName(databaseName);
        vo.setVectorizeStatus("NOT_VECTORIZED");
        vo.setVectorizeMessage("KV 类型不进行元数据向量化");
        return vo;
    }

    private long safeLong(Long value) {
        return value == null ? -1L : value;
    }

    private String stringifyValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof String str) {
            return str;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return String.valueOf(value);
        }
    }

    private String toPrettyJson(Object value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception ex) {
            return String.valueOf(value);
        }
    }

    private String safe(String value) {
        return Objects.toString(value, "").trim();
    }

    private String normalizeType(String value) {
        return safe(value).toUpperCase(Locale.ROOT);
    }

    private BusinessException unsupportedDbType(String dbType) {
        return new BusinessException(400, "当前连接不是 KV/文档类型: " + dbType);
    }
}
