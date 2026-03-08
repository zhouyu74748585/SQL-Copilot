package com.sqlcopilot.studio.service;

import com.sqlcopilot.studio.dto.knowledge.*;

import java.util.List;

public interface KnowledgeService {

    List<KnowledgeTermVO> listTerms(KnowledgeListQueryReq req);

    KnowledgeTermVO saveTerm(KnowledgeTermSaveReq req);

    void removeTerm(KnowledgeTermRemoveReq req);

    List<KnowledgeExampleSqlVO> listExamples(KnowledgeListQueryReq req);

    KnowledgeExampleSqlVO saveExample(KnowledgeExampleSqlSaveReq req);

    void removeExample(KnowledgeExampleSqlRemoveReq req);

    KnowledgeVectorRebuildVO rebuildVectors(KnowledgeVectorRebuildReq req);
}
