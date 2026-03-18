package com.sqlcopilot.studio.dto.connection;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 新建连接分组请求对象。 */
@Data
public class ConnectionGroupCreateReq {

    /** 分组名称。 */
    @NotBlank
    private String name;
}
