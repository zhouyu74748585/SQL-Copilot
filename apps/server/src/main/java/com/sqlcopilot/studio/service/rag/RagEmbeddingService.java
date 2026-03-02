package com.sqlcopilot.studio.service.rag;

import java.util.List;

public interface RagEmbeddingService {

    List<Float> embedText(String text);
}
