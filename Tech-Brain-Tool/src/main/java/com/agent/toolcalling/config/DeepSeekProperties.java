package com.agent.toolcalling.config; // Tool Calling公共配置包。

import lombok.Data; // 使用Lombok生成getter/setter，便于Spring绑定配置字段。
import org.springframework.boot.context.properties.ConfigurationProperties; // 绑定application.yml中的deepseek配置。
import org.springframework.stereotype.Component; // 注册为Spring Bean，供DeepSeekClient和业务模块注入。

/**
 * DeepSeek配置属性类。
 *
 * <p>该类属于Tech-Brain-Tool公共模块，用于统一承接业务模块application.yml中的deepseek配置。</p>
 * <p>调用链为：Tech-Brain-Agent启动后扫描com.agent包，Spring绑定deepseek.*配置到本类，DeepSeekClient再读取这些配置发起HTTP调用。</p>
 * <p>本类只保存模型调用配置，不包含Tool Calling业务流程，也不依赖Agent、Milvus或具体RAG Service。</p>
 */
@Data // 自动生成DeepSeekClient需要读取的getter/setter。
@Component // 让Spring容器扫描并管理该配置对象。
@ConfigurationProperties(prefix = "deepseek") // 将deepseek.api-key/base-url/model-name等配置绑定到字段。
public class DeepSeekProperties { // DeepSeek客户端公共配置对象。

    private String apiKey; // DeepSeek API Key，来自DEEPSEEK_API_KEY环境变量，不允许打印到日志。

    private String baseUrl; // DeepSeek OpenAI-compatible基础地址，例如https://api.deepseek.com/v1。

    private String modelName; // DeepSeek模型名称，当前项目统一使用deepseek-v4-pro。

    private Double temperature; // 模型温度配置，当前ToolChat请求体暂不在客户端层自动写入。

    private Integer timeoutSeconds; // 单次DeepSeek请求超时时间，单位秒。
}
