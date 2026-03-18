package com.sqlcopilot.studio.dto.connection;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 重命名连接分组请求对象。 */
@Data
public class ConnectionGroupRenameReq {

    /** 分组 ID。 */
    @NotNull
    private Long groupId;

    /** 分组名称。 */
    @NotBlank
    private String name;
}
