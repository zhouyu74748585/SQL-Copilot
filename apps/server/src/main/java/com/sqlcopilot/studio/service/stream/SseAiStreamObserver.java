package com.sqlcopilot.studio.service.stream;

import com.sqlcopilot.studio.dto.ai.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicLong;

/** 基于 SSE 的 AI 流式观察者。 */
public class SseAiStreamObserver implements AiStreamObserver {

    private final SseEmitter emitter;
    private final AtomicLong sequence = new AtomicLong(0L);

    public SseAiStreamObserver(SseEmitter emitter) {
        this.emitter = emitter;
    }

    @Override
    public void onSessionStarted(String sessionId, String actionType) {
        send(buildBaseEvent("session.started", sessionId, actionType));
    }

    @Override
    public void onIntentResolved(String sessionId,
                                 String actionType,
                                 String intentType,
                                 String intentLabel,
                                 Double intentConfidence,
                                 String reasoning) {
        AiStreamIntentVO intent = new AiStreamIntentVO();
        intent.setIntentType(intentType);
        intent.setIntentLabel(intentLabel);
        intent.setIntentConfidence(intentConfidence);
        intent.setReasoning(reasoning);
        AiStreamEventVO event = buildBaseEvent("intent.resolved", sessionId, actionType);
        event.setIntent(intent);
        send(event);
    }

    @Override
    public void onStageUpdated(String sessionId, String actionType, AiTraceStageVO stage) {
        AiStreamEventVO event = buildBaseEvent("stage.updated", sessionId, actionType);
        event.setStage(stage);
        send(event);
    }

    @Override
    public void onThinkingDelta(String sessionId, String actionType, String deltaText, String accumulatedText) {
        AiStreamEventVO event = buildBaseEvent("llm.thinking.delta", sessionId, actionType);
        event.setDelta(buildDelta("thinking", deltaText, accumulatedText));
        send(event);
    }

    @Override
    public void onOutputDelta(String sessionId, String actionType, String deltaText, String accumulatedText) {
        AiStreamEventVO event = buildBaseEvent("llm.output.delta", sessionId, actionType);
        event.setDelta(buildDelta("output", deltaText, accumulatedText));
        send(event);
    }

    @Override
    public void onTraceSnapshot(String sessionId, String actionType, AiTraceVO trace) {
        AiStreamEventVO event = buildBaseEvent("trace.snapshot", sessionId, actionType);
        event.setTrace(trace);
        send(event);
    }

    @Override
    public void onResultFinal(String sessionId, String actionType, AiStreamFinalVO finalResult) {
        AiStreamEventVO event = buildBaseEvent("result.final", sessionId, actionType);
        event.setFinalResult(finalResult);
        send(event);
    }

    @Override
    public void onError(String sessionId, String actionType, Integer code, String message) {
        AiStreamErrorVO error = new AiStreamErrorVO();
        error.setCode(code);
        error.setMessage(message);
        AiStreamEventVO event = buildBaseEvent("error", sessionId, actionType);
        event.setError(error);
        send(event);
    }

    @Override
    public void onDone(String sessionId, String actionType) {
        send(buildBaseEvent("done", sessionId, actionType));
    }

    private AiStreamDeltaVO buildDelta(String channel, String deltaText, String accumulatedText) {
        AiStreamDeltaVO delta = new AiStreamDeltaVO();
        delta.setChannel(channel);
        delta.setDeltaText(deltaText);
        delta.setAccumulatedText(accumulatedText);
        return delta;
    }

    private AiStreamEventVO buildBaseEvent(String eventType, String sessionId, String actionType) {
        AiStreamEventVO event = new AiStreamEventVO();
        event.setEventType(eventType);
        event.setSessionId(sessionId);
        event.setActionType(actionType);
        event.setSequence(sequence.incrementAndGet());
        event.setTimestamp(System.currentTimeMillis());
        return event;
    }

    private void send(AiStreamEventVO event) {
        try {
            emitter.send(SseEmitter.event().name(event.getEventType()).data(event));
        } catch (IOException ex) {
            throw new IllegalStateException("SSE 推送失败: " + ex.getMessage(), ex);
        }
    }
}
