package com.sqlcopilot.studio.dto.rag;

import lombok.Data;

/** 保存 RAG 向量配置请求对象。 */
@Data
public class RagConfigSaveReq {

    /** 向量化提供方类型：LOCAL_ONNX/ONLINE_OPENAI_COMPAT。 */
    private String ragEmbeddingProviderType;

    /** RAG 向量模型目录路径（可直接配置 clone 后仓库目录）。 */
    private String ragEmbeddingModelDir;

    /** 在线向量化 Base URL。 */
    private String ragEmbeddingOnlineBaseUrl;

    /** 在线向量化 API Key。 */
    private String ragEmbeddingOnlineApiKey;

    /** 在线向量化模型名称。 */
    private String ragEmbeddingOnlineModel;

    /** 是否启用本地 Rerank。 */
    private Boolean ragRerankEnabled;

    /** Rerank 提供方类型：LOCAL_ONNX/ONLINE_OPENAI_COMPAT。 */
    private String ragRerankProviderType;

    /** 本地 Rerank 模型目录。 */
    private String ragRerankModelDir;

    /** 在线 Rerank Base URL。 */
    private String ragRerankOnlineBaseUrl;

    /** 在线 Rerank API Key。 */
    private String ragRerankOnlineApiKey;

    /** 在线 Rerank 模型名称。 */
    private String ragRerankOnlineModel;
}
