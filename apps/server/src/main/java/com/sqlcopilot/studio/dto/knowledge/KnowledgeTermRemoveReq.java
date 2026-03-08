package com.sqlcopilot.studio.dto.knowledge;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 删除术语请求对象。 */
@Data
public class KnowledgeTermRemoveReq {

    /** 术语主键 ID。 */
    @NotNull
    private Long id;
}
