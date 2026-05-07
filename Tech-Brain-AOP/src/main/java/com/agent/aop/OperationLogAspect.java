package com.agent.aop;

import com.agent.entity.OperationLog;
import com.agent.mapper.OperationLohMapper;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Arrays;

@Slf4j
@Aspect
@Component
public class OperationLogAspect {
    @Autowired
    private OperationLohMapper operationLohMapper;
    @Around("@annotation(com.agent.aopanno.Log)")// 指定切点 / Define the pointcut.
    public Object logOperation(ProceedingJoinPoint joinPoint) throws Throwable {
        long startTime = System.currentTimeMillis();
        //获取方法名
        // Get the method name.
        String methodName = joinPoint.getSignature().getName();

        //执行原目标方法
        // Execute the original target method.
        Object result = joinPoint.proceed();

        //计算耗时
        // Calculate the elapsed time.
        long endTime = System.currentTimeMillis();
        long costTime = endTime - startTime;
        log.info("方法执行耗时：{}ms", costTime);

        //构建日志实体
        // Build the operation log entity.
        OperationLog operationLog = new OperationLog();
        operationLog.setOperateEmpId(1);
        operationLog.setOperateTime(LocalDateTime.now());
        operationLog.setClassName(joinPoint.getTarget().getClass().getName());
        operationLog.setMethodName(methodName);
        // 1. 处理请求参数：转为字符串并截断超长部分
        // 1. Convert request parameters to a string and truncate oversized content.
        String params = Arrays.toString(joinPoint.getArgs());
        if (params != null && params.length() > 200) {
            // 如果你的数据库字段只有 255，请把这里的 2000 改成 200
            // If the database column length is 255, keep this limit at 200.
            params = params.substring(0, 200) + "...(数据过长已截断)";
        }
        operationLog.setMethodParams(params);

        // 2. 处理返回值：转为字符串并截断超长部分
        // 2. Convert the return value to a string and truncate oversized content.
        String retValue = result != null ? result.toString() : "void";
        if (retValue != null && retValue.length() > 200) {
            // 如果你的数据库字段只有 255，请把这里的 2000 改成 200
            // If the database column length is 255, keep this limit at 200.
            retValue = retValue.substring(0, 200) + "...(数据过长已截断)";
        }
        operationLog.setReturnValue(retValue);
        operationLog.setCostTime(costTime);
        operationLohMapper.insert(operationLog);
        return result;

    }

}
