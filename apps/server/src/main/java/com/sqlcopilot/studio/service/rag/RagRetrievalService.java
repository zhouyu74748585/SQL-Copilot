package com.sqlcopilot.studio.service.rag;

import com.sqlcopilot.studio.service.rag.model.RagPromptContext;

public interface RagRetrievalService {

    /**
     * 基于用户输入向量在 Qdrant 分层集合中检索，并构建可直接拼接到 Prompt 的上下文。
     */
    RagPromptContext retrievePromptContext(Long connectionId, String databaseName, String userInput);
}
