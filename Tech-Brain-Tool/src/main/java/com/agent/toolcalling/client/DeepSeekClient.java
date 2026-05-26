package com.agent.toolcalling.client; // Tool Calling公共客户端包。

import com.agent.toolcalling.config.DeepSeekProperties; // 读取deepseek.*配置。
import com.fasterxml.jackson.databind.JsonNode; // DeepSeek响应统一解析为JsonNode。
import com.fasterxml.jackson.databind.ObjectMapper; // 序列化请求体并解析响应体。
import com.fasterxml.jackson.databind.node.ObjectNode; // chat/completions请求体类型。
import lombok.extern.slf4j.Slf4j; // 打印DeepSeekClient专用日志。
import org.springframework.stereotype.Component; // 注册为Spring Bean，供Agent模块注入。

import javax.net.ssl.SSLHandshakeException; // 识别SSL握手失败并打印明确排查提示。
import java.io.BufferedReader; // 按行读取DeepSeek SSE流。
import java.io.IOException; // 捕获HTTP IO异常和JSON解析异常。
import java.io.InputStream; // 读取流式响应体和错误响应体。
import java.io.InputStreamReader; // 将响应字节流按UTF-8转换为文本行。
import java.net.URI; // 构造DeepSeek请求URI。
import java.net.http.HttpClient; // JDK HTTP客户端。
import java.net.http.HttpRequest; // JDK HTTP请求对象。
import java.net.http.HttpResponse; // JDK HTTP响应对象。
import java.nio.charset.StandardCharsets; // DeepSeek SSE响应按UTF-8解码。
import java.time.Duration; // 设置连接超时和请求超时。

/**
 * DeepSeek HTTP客户端。
 *
 * <p>该类位于Tech-Brain-Tool公共模块，专门封装DeepSeek OpenAI-compatible chat/completions HTTP调用。</p>
 * <p>调用链为：业务Service组装ObjectNode请求体 -> 调用chatCompletions -> 本类负责序列化、拼接URL、设置请求头、发送HTTP请求、解析响应。</p>
 * <p>本类不理解具体Tool Calling业务，不构造messages/tools，不接触Milvus/RAG，只负责稳定发送DeepSeek请求并输出可排查日志。</p>
 */
@Slf4j // 生成日志对象，输出[DeepSeekClient]前缀日志。
@Component // 让Agent模块可以通过Spring注入DeepSeekClient。
public class DeepSeekClient { // DeepSeek公共HTTP调用客户端。

    private static final int CONNECT_TIMEOUT_SECONDS = 30; // 连接超时固定为30秒，避免网络不通时长时间阻塞。

    private static final int DEFAULT_REQUEST_TIMEOUT_SECONDS = 120; // 配置缺失时使用120秒请求超时兜底。

    private final DeepSeekProperties properties; // DeepSeek API Key、baseUrl、modelName、timeout等配置来源。

    private final ObjectMapper objectMapper = new ObjectMapper(); // 本客户端内部独立JSON解析器，避免依赖业务模块ObjectMapper。

    private final HttpClient httpClient = HttpClient.newBuilder() // 创建JDK HttpClient。
            .connectTimeout(Duration.ofSeconds(CONNECT_TIMEOUT_SECONDS)) // 设置连接超时。
            .version(HttpClient.Version.HTTP_1_1) // 强制HTTP/1.1，规避部分代理/VPN对HTTP/2握手兼容问题。
            .build(); // 构建不可变HttpClient实例。

    public DeepSeekClient(DeepSeekProperties properties) { // 构造器注入DeepSeek配置。
        this.properties = properties; // 保存配置引用，后续每次请求读取最新绑定值。
    }

    public JsonNode chatCompletions(ObjectNode requestBody) { // 发送DeepSeek chat/completions请求并返回JSON响应。
        try { // 捕获序列化、网络和响应解析异常。
            String requestUrl = buildChatCompletionsUrl(); // 拼接最终请求URL。
            String requestJson = objectMapper.writeValueAsString(requestBody); // 将业务层组装好的请求体序列化为JSON。
            log.debug("[DeepSeekClient] request url: {}", requestUrl); // 请求地址每次都会出现，降级为DEBUG。
            log.debug("[DeepSeekClient] model: {}", requestBody.path("model").asText()); // 模型名降级为DEBUG。
            log.debug("[DeepSeekClient] thinking: {}", requestBody.path("thinking").path("type").asText()); // thinking状态降级为DEBUG。

            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(requestUrl)) // 创建DeepSeek POST请求。
                    .timeout(Duration.ofSeconds(resolveTimeoutSeconds())) // 设置单次请求超时。
                    .header("Authorization", "Bearer " + properties.getApiKey()) // 设置鉴权头，但不打印API Key。
                    .header("Content-Type", "application/json") // DeepSeek接口使用JSON请求体。
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson)) // 写入序列化后的请求体。
                    .build(); // 构建HttpRequest。

            HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString()); // 同步发送请求。
            if (response.statusCode() < 200 || response.statusCode() >= 300) { // 非2xx表示DeepSeek返回错误。
                log.error("[DeepSeekClient] error status: {}", response.statusCode()); // 打印HTTP状态码。
                log.error("[DeepSeekClient] error response: {}", response.body()); // 打印DeepSeek完整错误响应，便于排查参数问题。
                throw new RuntimeException("DeepSeek调用失败: HTTP " + response.statusCode()); // 抛出异常交给上层处理。
            }
            return objectMapper.readTree(response.body()); // 成功响应解析为JsonNode返回给业务层。
        } catch (InterruptedException e) { // 当前线程在HTTP调用过程中被中断。
            Thread.currentThread().interrupt(); // 恢复线程中断标记。
            log.error("[DeepSeekClient] request interrupted: {}", e.getMessage()); // 打印中断原因。
            throw new RuntimeException("DeepSeek调用被中断", e); // 包装为运行时异常向上抛出。
        } catch (IOException e) { // 网络IO、SSL或JSON解析异常。
            log.error("[DeepSeekClient] IO error type: {}", e.getClass().getName()); // 打印IO异常类型。
            log.error("[DeepSeekClient] IO error message: {}", e.getMessage()); // 打印IO异常消息。
            if (e instanceof SSLHandshakeException) { // SSL握手失败单独提示网络/代理方向。
                log.error("[DeepSeekClient] SSL handshake failed. Check network/proxy/VPN/JDK HttpClient HTTP version/API endpoint."); // 输出明确排查建议。
            }
            throw new RuntimeException("DeepSeek调用失败", e); // 保留原始异常并向上抛出。
        }
    }

    public void streamChatCompletions(ObjectNode requestBody, DeepSeekStreamCallback callback) { // 发送DeepSeek流式chat/completions请求。
        if (callback == null) { // 流式调用必须有回调承接token和完成事件。
            throw new IllegalArgumentException("DeepSeekStreamCallback不能为空"); // 避免后续空指针导致流处理中断不清晰。
        }
        ObjectNode streamRequestBody = requestBody.deepCopy(); // 复制请求体，避免修改调用方原始ObjectNode。
        streamRequestBody.put("stream", true); // DeepSeek/OpenAI-compatible流式输出开关。
        try { // 捕获序列化、网络、SSE读取和JSON解析异常。
            String requestUrl = buildChatCompletionsUrl(); // 流式调用复用同一个chat/completions地址。
            String requestJson = objectMapper.writeValueAsString(streamRequestBody); // 将带stream=true的请求体序列化为JSON。
            log.debug("[DeepSeekClient] stream request url: {}", requestUrl); // 流式请求地址降级为DEBUG。
            log.debug("[DeepSeekClient] stream model: {}", streamRequestBody.path("model").asText()); // 流式模型名降级为DEBUG。
            log.debug("[DeepSeekClient] stream thinking: {}", streamRequestBody.path("thinking").path("type").asText()); // thinking状态降级为DEBUG。

            HttpRequest httpRequest = HttpRequest.newBuilder(URI.create(requestUrl)) // 创建DeepSeek流式POST请求。
                    .timeout(Duration.ofSeconds(resolveTimeoutSeconds())) // 流式请求也使用deepseek.timeout-seconds配置。
                    .header("Authorization", "Bearer " + properties.getApiKey()) // 设置鉴权头，不打印API Key。
                    .header("Content-Type", "application/json") // 请求体仍然是JSON。
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson)) // 写入序列化后的流式请求体。
                    .build(); // 构建HttpRequest。

            HttpResponse<InputStream> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofInputStream()); // 使用InputStream逐行读取SSE。
            if (response.statusCode() < 200 || response.statusCode() >= 300) { // 非2xx表示DeepSeek流式请求失败。
                String errorBody = readResponseBody(response.body()); // 尽量读取错误响应体，便于定位参数或鉴权问题。
                log.error("[DeepSeekClient] stream error status: {}", response.statusCode()); // 打印HTTP状态码。
                log.error("[DeepSeekClient] stream error response: {}", errorBody); // 打印DeepSeek错误响应，不包含API Key。
                RuntimeException error = new RuntimeException("DeepSeek流式调用失败: HTTP " + response.statusCode()); // 构造明确异常。
                callback.onError(error); // 通知调用方流式请求失败。
                throw error; // 继续向上抛出，避免调用方误判成功。
            }

            log.debug("[DeepSeekClient] stream started"); // 流式开始细节降级为DEBUG。
            boolean completed = readStreamResponse(response.body(), callback); // 逐行解析data事件并分发token。
            if (!completed) { // 如果服务端未显式返回[DONE]但流自然结束，也视为完成。
                callback.onComplete(); // 通知调用方流已结束。
            log.debug("[DeepSeekClient] stream completed"); // 流式完成细节降级为DEBUG。
            }
        } catch (InterruptedException e) { // 当前线程在HTTP调用过程中被中断。
            Thread.currentThread().interrupt(); // 恢复线程中断标记。
            log.error("[DeepSeekClient] stream error type: {}", e.getClass().getName()); // 打印流式异常类型。
            log.error("[DeepSeekClient] stream error message: {}", e.getMessage()); // 打印流式异常消息。
            callback.onError(e); // 通知调用方中断异常。
            throw new RuntimeException("DeepSeek流式调用被中断", e); // 包装后向上抛出。
        } catch (IOException e) { // 网络IO、SSL、响应读取或JSON解析异常。
            log.error("[DeepSeekClient] stream error type: {}", e.getClass().getName()); // 打印流式IO异常类型。
            log.error("[DeepSeekClient] stream error message: {}", e.getMessage()); // 打印流式IO异常消息。
            if (e instanceof SSLHandshakeException) { // SSL握手失败单独提示网络/代理方向。
                log.error("[DeepSeekClient] SSL handshake failed. Check network/proxy/VPN/JDK HttpClient HTTP version/API endpoint."); // 输出明确排查建议。
            }
            callback.onError(e); // 通知调用方流式异常。
            throw new RuntimeException("DeepSeek流式调用失败", e); // 保留原始异常并向上抛出。
        }
    }

    private boolean readStreamResponse(InputStream responseBody, DeepSeekStreamCallback callback) throws IOException { // 读取并解析DeepSeek SSE响应。
        boolean completed = false; // 标记是否已经收到data: [DONE]。
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(responseBody, StandardCharsets.UTF_8))) { // 按UTF-8逐行读取SSE。
            String line; // 当前SSE文本行。
            while ((line = reader.readLine()) != null) { // 持续读取直到服务端关闭流。
                String trimmedLine = line.trim(); // 去掉两端空白，方便判断data前缀。
                if (trimmedLine.isEmpty() || !trimmedLine.startsWith("data:")) { // 忽略空行和非data行。
                    continue; // DeepSeek SSE当前只需要处理data事件。
                }
                String data = trimmedLine.substring("data:".length()).trim(); // 去掉data:前缀得到真实负载。
                if ("[DONE]".equals(data)) { // DeepSeek流式结束标记。
                    callback.onComplete(); // 通知调用方流式响应完成。
        log.debug("[DeepSeekClient] stream completed"); // 流式完成细节降级为DEBUG。
                    completed = true; // 记录已收到显式完成事件。
                    break; // 收到[DONE]后结束读取。
                }
                JsonNode streamNode = objectMapper.readTree(data); // 普通data负载按JSON解析。
                JsonNode contentNode = streamNode.path("choices").path(0).path("delta").path("content"); // 读取choices[0].delta.content。
                if (!contentNode.isMissingNode() && !contentNode.isNull()) { // content可能不存在或为null。
                    String token = contentNode.asText(); // 转成增量文本。
                    if (token != null && !token.isEmpty()) { // 空token跳过，避免前端收到无意义事件。
                        callback.onToken(token); // 将增量文本交给调用方处理。
                    }
                }
            }
        }
        return completed; // 返回是否收到[DONE]。
    }

    private String readResponseBody(InputStream responseBody) throws IOException { // 读取非2xx错误响应体。
        try (InputStream inputStream = responseBody) { // 确保错误响应流被关闭。
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8); // 将错误响应按UTF-8转成字符串。
        }
    }

    private String buildChatCompletionsUrl() { // 拼接DeepSeek chat/completions完整URL。
        String baseUrl = properties.getBaseUrl(); // 读取deepseek.base-url配置。
        String normalizedBaseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl; // 去掉末尾斜杠，避免双斜杠。
        return normalizedBaseUrl + "/chat/completions"; // 返回https://api.deepseek.com/v1/chat/completions。
    }

    private int resolveTimeoutSeconds() { // 解析请求超时配置。
        Integer timeoutSeconds = properties.getTimeoutSeconds(); // 读取deepseek.timeout-seconds配置。
        return timeoutSeconds == null || timeoutSeconds <= 0 ? DEFAULT_REQUEST_TIMEOUT_SECONDS : timeoutSeconds; // 配置无效时使用默认值。
    }
}
