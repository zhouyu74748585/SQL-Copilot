package com.sqlcopilot.studio.dto.common;

import lombok.Data;

/**
 * 统一接口响应对象。
 *
 * @param <T> 业务数据类型
 */
@Data
public class ApiResponse<T> {

    /** 响应码，0 表示成功。 */
    private Integer code;

    /** 响应消息。 */
    private String message;

    /** 响应数据。 */
    private T data;

    public static <T> ApiResponse<T> success(T data) {
        ApiResponse<T> response = new ApiResponse<>();
        response.setCode(0);
        response.setMessage("success");
        response.setData(data);
        return response;
    }

    public static <T> ApiResponse<T> fail(Integer code, String message) {
        ApiResponse<T> response = new ApiResponse<>();
        response.setCode(code);
        response.setMessage(message);
        return response;
    }
}
