package com.sqlcopilot.studio.dto.memory;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 保存长期记忆请求对象。 */
@Data
public class MemoryEntrySaveReq {

    /** 长期记忆主键 ID，更新时必填。 */
    private Long id;

    /** 记忆作用域，仅支持 CONNECTION / DATABASE。 */
    @NotBlank
    private String scope;

    /** 连接主键 ID。 */
    private Long connectionId;

    /** 数据库名称。 */
    private String databaseName;

    /** 记忆标题。 */
    @NotBlank
    private String title;

    /** 记忆摘要正文。 */
    @NotBlank
    private String summary;
}
