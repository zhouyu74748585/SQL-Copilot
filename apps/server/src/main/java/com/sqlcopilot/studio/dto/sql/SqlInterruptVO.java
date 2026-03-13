package com.sqlcopilot.studio.dto.sql;

import lombok.Data;

/** SQL 中断响应对象。 */
@Data
public class SqlInterruptVO {

    /** 是否命中并发送了中断信号。 */
    private Boolean interrupted;

    /** 对本次中断处理结果的说明。 */
    private String message;
}
