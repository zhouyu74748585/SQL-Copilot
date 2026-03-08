package com.sqlcopilot.studio.dto.knowledge;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 保存术语请求对象。 */
@Data
public class KnowledgeTermSaveReq {

    /** 术语主键 ID，编辑时传入。 */
    private Long id;

    /** 作用域：GLOBAL/CONNECTION/DATABASE。 */
    @NotBlank
    private String scope;

    /** 连接主键 ID。 */
    private Long connectionId;

    /** 数据库名称。 */
    private String databaseName;

    /** 术语名称。 */
    @NotBlank
    private String term;

    /** 术语说明。 */
    private String description;
}
