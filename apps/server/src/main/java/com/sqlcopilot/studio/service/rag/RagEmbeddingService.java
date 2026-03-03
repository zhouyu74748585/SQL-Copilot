package com.sqlcopilot.studio.service.rag;

import java.util.List;

public interface RagEmbeddingService {

    List<Float> embedText(String text);

    default List<List<Float>> embedTexts(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        List<List<Float>> vectors = new java.util.ArrayList<>(texts.size());
        for (String text : texts) {
            vectors.add(embedText(text));
        }
        return vectors;
    }
}
