package com.sqlcopilot.studio.service.stream;

import com.sqlcopilot.studio.dto.ai.AiStreamFinalVO;
import com.sqlcopilot.studio.dto.ai.AiTraceStageVO;
import com.sqlcopilot.studio.dto.ai.AiTraceVO;

/** AI 会话流式观察者。 */
public interface AiStreamObserver {

    /** 会话开始事件。 */
    void onSessionStarted(String sessionId, String actionType);

    /** Auto 意图识别事件。 */
    void onIntentResolved(String sessionId,
                          String actionType,
                          String intentType,
                          String intentLabel,
                          Double intentConfidence,
                          String reasoning);

    /** 阶段更新事件。 */
    void onStageUpdated(String sessionId, String actionType, AiTraceStageVO stage);

    /** 模型思考增量事件。 */
    void onThinkingDelta(String sessionId, String actionType, String deltaText, String accumulatedText);

    /** 模型输出增量事件。 */
    void onOutputDelta(String sessionId, String actionType, String deltaText, String accumulatedText);

    /** Trace 快照事件。 */
    void onTraceSnapshot(String sessionId, String actionType, AiTraceVO trace);

    /** 最终结果事件。 */
    void onResultFinal(String sessionId, String actionType, AiStreamFinalVO finalResult);

    /** 错误事件。 */
    void onError(String sessionId, String actionType, Integer code, String message);

    /** 完成事件。 */
    void onDone(String sessionId, String actionType);
}
