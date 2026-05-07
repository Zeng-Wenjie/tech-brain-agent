package com.agent.aopanno;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 操作日志自定义注解
 * Custom annotation for operation logging.
 */
@Target(ElementType.METHOD)// 指定注解作用目标作用在方法上 / Apply this annotation to methods.
@Retention(RetentionPolicy.RUNTIME)//指定注解作用目标作用在运行上 / Retain this annotation at runtime.
public @interface Log {
}
