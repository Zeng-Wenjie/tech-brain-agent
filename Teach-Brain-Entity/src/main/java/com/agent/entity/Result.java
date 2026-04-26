package com.agent.entity;

import lombok.Data;

@Data
public class Result<T> {
    private Integer code;//状态码
    private String message;//提示信息
    private T data;// 数据

    private Result(){}

    //成功
    public static <T> Result<T> success(T data){
        Result<T> result = new Result<>();
        result.setCode(200);
        result.setMessage("操作成功");
        result.setData(data);
        return result;
    }

    //失败
    public static <T> Result<T> error(Integer code,String message){
        Result<T> result = new Result<>();
        result.setCode(code);
        result.setMessage(message);
        return result;
    }
}
