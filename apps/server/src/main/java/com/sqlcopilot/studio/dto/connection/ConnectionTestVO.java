package com.sqlcopilot.studio.dto.connection;

import lombok.Data;

/** 测试数据库连接响应对象。 */
@Data
public class ConnectionTestVO {

    /** 是否测试成功。 */
    private Boolean success;

    /** 测试结果消息。 */
    private String message;
}
