package com.sqlcopilot.studio.support;

import com.sqlcopilot.studio.service.MemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class MemoryVectorMaintenanceRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(MemoryVectorMaintenanceRunner.class);

    private final MemoryService memoryService;

    public MemoryVectorMaintenanceRunner(MemoryService memoryService) {
        this.memoryService = memoryService;
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            memoryService.cleanupLegacyVectors();
        } catch (Exception ex) {
            log.warn("记忆向量遗留数据清理失败, reason={}", ex.getMessage());
        }
    }
}
