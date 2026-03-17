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
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.ScoredValue;
import io.lettuce.core.api.sync.RedisCommands;
import org.bson.Document;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** KV/文档存储浏览与查询服务。 */
@Service
public class KvServiceImpl implements KvService {

    private static final String DB_TYPE_MONGODB = "MONGODB";
    private static final String DB_TYPE_REDIS = "REDIS";
    private static final int DEFAULT_MAX_ROWS = 100;
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
                    SchemaDatabaseVO vo = new SchemaDatabaseVO();
                    vo.setDatabaseName(name);
                    vo.setVectorizeStatus("NOT_VECTORIZED");
                    vo.setVectorizeMessage("KV 类型不进行元数据向量化");
                    result.add(vo);
                }
                return result;
            });
        }
        if (DB_TYPE_REDIS.equals(dbType)) {
            SchemaDatabaseVO vo = new SchemaDatabaseVO();
            vo.setDatabaseName(resolveRedisDatabaseName(entity.getDatabaseName()));
            vo.setVectorizeStatus("NOT_VECTORIZED");
            vo.setVectorizeMessage("KV 类型不进行元数据向量化");
            return List.of(vo);
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
            return kvRuntimeClientFactory.withRedisConnection(entity, connectionId, connection ->
                buildRedisOverview(connectionId, resolveRedisDatabaseName(databaseName), connection.sync()));
        }
        throw unsupportedDbType(dbType);
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
            return kvRuntimeClientFactory.withRedisConnection(entity, connectionId, connection ->
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
            return kvRuntimeClientFactory.withRedisConnection(entity, req.getConnectionId(), connection ->
                executeRedisCommand(entity, req, connection.sync()));
        }
        throw unsupportedDbType(dbType);
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
