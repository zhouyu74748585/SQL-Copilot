package com.sqlcopilot.studio.service.rag;

import com.sqlcopilot.studio.service.rag.model.QdrantScoredPoint;

import java.util.List;

/** ONNX 本地重排服务（可降级）。 */
public interface RagRerankService {

    /**
     * 对同一桶命中结果进行重排评分。
     *
     * @return 返回与 hits 等长的评分；若不可用则返回空列表（调用方走降级）。
     */
    List<Double> score(String query, String bucket, List<QdrantScoredPoint> hits);

    /** 当前运行时 provider，便于日志排障。 */
    String getRuntimeProvider();
}
