package com.sqlcopilot.studio.service.impl;

import com.sqlcopilot.studio.service.llm.LlmStreamListener;
import com.sqlcopilot.studio.service.stream.AiStreamObserver;

/**
 * 禁用思考模式的 LLM 流式监听器包装类。
 * 当 thinkingEnabled=false 时，跳过所有 thinking delta 事件，只转发 output delta 事件。
 */
public class DisabledThinkingLlmStreamListener implements LlmStreamListener {

    private final AiStreamObserver observer;
    private final String sessionId;
    private final String actionType;
    private volatile boolean cancelled = false;

    public DisabledThinkingLlmStreamListener(AiStreamObserver observer, String sessionId, String actionType) {
        this.observer = observer;
        this.sessionId = sessionId;
        this.actionType = actionType;
    }

    @Override
    public void onThinkingDelta(String deltaText, String accumulatedText) {
        // 禁用思考模式，静默忽略所有 thinking delta
    }

    @Override
    public void onOutputDelta(String deltaText, String accumulatedText) {
        if (cancelled) {
            return;
        }
        try {
            observer.onOutputDelta(sessionId, actionType, deltaText, accumulatedText);
        } catch (IllegalStateException ex) {
            // SSE 发送失败（如客户端断开连接 "Broken pipe"），静默停止发送后续事件
            cancelled = true;
        }
    }
}
