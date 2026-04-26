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
    @Around("@annotation(com.agent.aopanno.Log)")// 指定切点
    public Object logOperation(ProceedingJoinPoint joinPoint) throws Throwable {
        long startTime = System.currentTimeMillis();
        //获取方法名
        String methodName = joinPoint.getSignature().getName();

        //执行原目标方法
        Object result = joinPoint.proceed();

        //计算耗时
        long endTime = System.currentTimeMillis();
        long costTime = endTime - startTime;
        log.info("方法执行耗时：{}ms", costTime);

        //构建日志实体
        OperationLog operationLog = new OperationLog();
        operationLog.setOperateEmpId(1);
        operationLog.setOperateTime(LocalDateTime.now());
        operationLog.setClassName(joinPoint.getTarget().getClass().getName());
        operationLog.setMethodName(methodName);
        operationLog.setMethodParams(Arrays.toString(joinPoint.getArgs()));
        operationLog.setReturnValue(result!=null?result.toString():"void");
        operationLog.setCostTime(costTime);
        operationLohMapper.insert(operationLog);
        return result;

    }

}
