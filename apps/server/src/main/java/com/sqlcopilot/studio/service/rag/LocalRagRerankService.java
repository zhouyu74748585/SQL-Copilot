package com.sqlcopilot.studio.service.rag;

/**
 * 本地 ONNX Rerank 服务标记接口。
 * <p>
 * 用于在 Router 层按能力可选注入，避免在最小化包中硬依赖 ONNX 实现类。
 */
public interface LocalRagRerankService extends RagRerankService {
}
