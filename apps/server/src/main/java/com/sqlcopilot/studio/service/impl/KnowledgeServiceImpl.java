package com.sqlcopilot.studio.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sqlcopilot.studio.dto.knowledge.*;
import com.sqlcopilot.studio.entity.KnowledgeExampleSqlEntity;
import com.sqlcopilot.studio.entity.KnowledgeTermEntity;
import com.sqlcopilot.studio.mapper.KnowledgeExampleSqlMapper;
import com.sqlcopilot.studio.mapper.KnowledgeTermMapper;
import com.sqlcopilot.studio.service.KnowledgeService;
import com.sqlcopilot.studio.service.rag.RagIngestionService;
import com.sqlcopilot.studio.util.BusinessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

@Service
public class KnowledgeServiceImpl implements KnowledgeService {

    private static final TypeReference<List<Long>> LONG_LIST_TYPE = new TypeReference<>() {
    };

    private final KnowledgeTermMapper knowledgeTermMapper;
    private final KnowledgeExampleSqlMapper knowledgeExampleSqlMapper;
    private final RagIngestionService ragIngestionService;
    private final ObjectMapper objectMapper;

    public KnowledgeServiceImpl(KnowledgeTermMapper knowledgeTermMapper,
                                KnowledgeExampleSqlMapper knowledgeExampleSqlMapper,
                                RagIngestionService ragIngestionService,
                                ObjectMapper objectMapper) {
        this.knowledgeTermMapper = knowledgeTermMapper;
        this.knowledgeExampleSqlMapper = knowledgeExampleSqlMapper;
        this.ragIngestionService = ragIngestionService;
        this.objectMapper = objectMapper;
    }

    @Override
    public List<KnowledgeTermVO> listTerms(KnowledgeListQueryReq req) {
        return knowledgeTermMapper.listApplicable(req.getConnectionId(), normalizeDatabaseName(req.getDatabaseName())).stream()
            .map(this::toTermVO)
            .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeTermVO saveTerm(KnowledgeTermSaveReq req) {
        ScopeContext scopeContext = normalizeScope(req.getScope(), req.getConnectionId(), req.getDatabaseName());
        String term = normalizeOneLine(req.getTerm(), 120);
        if (term.isBlank()) {
            throw new BusinessException(400, "术语不能为空");
        }

        long now = System.currentTimeMillis();
        KnowledgeTermEntity entity;
        if (req.getId() != null) {
            entity = requireTerm(req.getId());
            entity.setUpdatedAt(now);
        } else {
            entity = new KnowledgeTermEntity();
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
        }
        entity.setScope(scopeContext.scope());
        entity.setConnectionId(scopeContext.connectionId());
        entity.setDatabaseName(scopeContext.databaseName());
        entity.setTerm(term);
        entity.setDescription(normalizeText(req.getDescription()));

        if (req.getId() != null) {
            knowledgeTermMapper.update(entity);
        } else {
            knowledgeTermMapper.insert(entity);
        }
        ragIngestionService.ingestKnowledgeTerm(entity);
        return toTermVO(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeTerm(KnowledgeTermRemoveReq req) {
        KnowledgeTermEntity entity = requireTerm(req.getId());
        if (knowledgeTermMapper.deleteById(req.getId()) <= 0) {
            throw new BusinessException(404, "术语不存在");
        }
        ragIngestionService.removeKnowledgeTerm(entity);

        List<KnowledgeExampleSqlEntity> examples = knowledgeExampleSqlMapper.listAll();
        long now = System.currentTimeMillis();
        for (KnowledgeExampleSqlEntity example : examples) {
            List<Long> currentIds = parseTermIds(example.getTermIdsJson());
            if (!currentIds.remove(req.getId())) {
                continue;
            }
            example.setTermIdsJson(writeTermIds(currentIds));
            example.setUpdatedAt(now);
            knowledgeExampleSqlMapper.update(example);
            ragIngestionService.ingestKnowledgeExample(example);
        }
    }

    @Override
    public List<KnowledgeExampleSqlVO> listExamples(KnowledgeListQueryReq req) {
        return knowledgeExampleSqlMapper.listApplicable(req.getConnectionId(), normalizeDatabaseName(req.getDatabaseName())).stream()
            .map(this::toExampleVO)
            .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeExampleSqlVO saveExample(KnowledgeExampleSqlSaveReq req) {
        ScopeContext scopeContext = normalizeScope(req.getScope(), req.getConnectionId(), req.getDatabaseName());
        String sqlText = normalizeText(req.getSqlText());
        if (sqlText.isBlank()) {
            throw new BusinessException(400, "样例 SQL 不能为空");
        }
        List<Long> termIds = normalizeTermIds(req.getTermIds());
        long now = System.currentTimeMillis();
        KnowledgeExampleSqlEntity entity;
        if (req.getId() != null) {
            entity = requireExample(req.getId());
            entity.setUpdatedAt(now);
        } else {
            entity = new KnowledgeExampleSqlEntity();
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
        }
        entity.setScope(scopeContext.scope());
        entity.setConnectionId(scopeContext.connectionId());
        entity.setDatabaseName(scopeContext.databaseName());
        entity.setSqlText(sqlText);
        entity.setDescription(normalizeText(req.getDescription()));
        entity.setTermIdsJson(writeTermIds(termIds));

        if (req.getId() != null) {
            knowledgeExampleSqlMapper.update(entity);
        } else {
            knowledgeExampleSqlMapper.insert(entity);
        }
        ragIngestionService.ingestKnowledgeExample(entity);
        return toExampleVO(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void removeExample(KnowledgeExampleSqlRemoveReq req) {
        KnowledgeExampleSqlEntity entity = requireExample(req.getId());
        if (knowledgeExampleSqlMapper.deleteById(req.getId()) <= 0) {
            throw new BusinessException(404, "样例 SQL 不存在");
        }
        ragIngestionService.removeKnowledgeExample(entity);
    }

    @Override
    public KnowledgeVectorRebuildVO rebuildVectors(KnowledgeVectorRebuildReq req) {
        List<KnowledgeTermEntity> terms = knowledgeTermMapper.listAll();
        List<KnowledgeExampleSqlEntity> examples = knowledgeExampleSqlMapper.listAll();
        ragIngestionService.rebuildKnowledgeVectors(terms, examples);
        KnowledgeVectorRebuildVO vo = new KnowledgeVectorRebuildVO();
        vo.setTermCount(terms.size());
        vo.setExampleCount(examples.size());
        vo.setRebuiltAt(System.currentTimeMillis());
        vo.setMessage("知识中心向量已重建完成");
        return vo;
    }

    private KnowledgeTermEntity requireTerm(Long id) {
        if (id == null) {
            throw new BusinessException(400, "术语 ID 不能为空");
        }
        KnowledgeTermEntity entity = knowledgeTermMapper.findById(id);
        if (entity == null) {
            throw new BusinessException(404, "术语不存在");
        }
        return entity;
    }

    private KnowledgeExampleSqlEntity requireExample(Long id) {
        if (id == null) {
            throw new BusinessException(400, "样例 SQL ID 不能为空");
        }
        KnowledgeExampleSqlEntity entity = knowledgeExampleSqlMapper.findById(id);
        if (entity == null) {
            throw new BusinessException(404, "样例 SQL 不存在");
        }
        return entity;
    }

    private KnowledgeTermVO toTermVO(KnowledgeTermEntity entity) {
        KnowledgeTermVO vo = new KnowledgeTermVO();
        vo.setId(entity.getId());
        vo.setScope(entity.getScope());
        vo.setConnectionId(entity.getConnectionId());
        vo.setDatabaseName(normalizeDatabaseName(entity.getDatabaseName()));
        vo.setTerm(normalizeText(entity.getTerm()));
        vo.setDescription(normalizeText(entity.getDescription()));
        vo.setCreatedAt(entity.getCreatedAt());
        vo.setUpdatedAt(entity.getUpdatedAt());
        return vo;
    }

    private KnowledgeExampleSqlVO toExampleVO(KnowledgeExampleSqlEntity entity) {
        KnowledgeExampleSqlVO vo = new KnowledgeExampleSqlVO();
        vo.setId(entity.getId());
        vo.setScope(entity.getScope());
        vo.setConnectionId(entity.getConnectionId());
        vo.setDatabaseName(normalizeDatabaseName(entity.getDatabaseName()));
        vo.setSqlText(normalizeText(entity.getSqlText()));
        vo.setDescription(normalizeText(entity.getDescription()));
        vo.setTermIds(parseTermIds(entity.getTermIdsJson()));
        vo.setCreatedAt(entity.getCreatedAt());
        vo.setUpdatedAt(entity.getUpdatedAt());
        return vo;
    }

    private ScopeContext normalizeScope(String rawScope, Long connectionId, String databaseName) {
        String scope = normalizeText(rawScope).toUpperCase(Locale.ROOT);
        return switch (scope) {
            case "GLOBAL" -> new ScopeContext("GLOBAL", 0L, "");
            case "CONNECTION" -> {
                if (connectionId == null || connectionId <= 0) {
                    throw new BusinessException(400, "连接级知识必须指定 connectionId");
                }
                yield new ScopeContext("CONNECTION", connectionId, "");
            }
            case "DATABASE" -> {
                String normalizedDatabaseName = normalizeDatabaseName(databaseName);
                if (connectionId == null || connectionId <= 0 || normalizedDatabaseName.isBlank()) {
                    throw new BusinessException(400, "数据库级知识必须指定 connectionId 和 databaseName");
                }
                yield new ScopeContext("DATABASE", connectionId, normalizedDatabaseName);
            }
            default -> throw new BusinessException(400, "作用域仅支持 GLOBAL / CONNECTION / DATABASE");
        };
    }

    private List<Long> normalizeTermIds(List<Long> values) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<Long> dedup = new LinkedHashSet<>();
        for (Long value : values) {
            if (value != null && value > 0) {
                dedup.add(value);
            }
        }
        return new ArrayList<>(dedup);
    }

    private List<Long> parseTermIds(String json) {
        String normalized = normalizeText(json);
        if (normalized.isBlank()) {
            return new ArrayList<>();
        }
        try {
            List<Long> values = objectMapper.readValue(normalized, LONG_LIST_TYPE);
            return normalizeTermIds(values);
        } catch (Exception ex) {
            return new ArrayList<>();
        }
    }

    private String writeTermIds(List<Long> termIds) {
        try {
            return objectMapper.writeValueAsString(normalizeTermIds(termIds));
        } catch (Exception ex) {
            throw new BusinessException(500, "序列化关联术语失败");
        }
    }

    private String normalizeDatabaseName(String value) {
        String normalized = normalizeText(value);
        if ("__default__".equals(normalized)) {
            return "";
        }
        return normalized;
    }

    private String normalizeText(String value) {
        return Objects.toString(value, "").trim();
    }

    private String normalizeOneLine(String value, int maxLength) {
        String normalized = normalizeText(value).replaceAll("\\s+", " ");
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength);
    }

    private record ScopeContext(String scope, Long connectionId, String databaseName) {
    }
}
