package com.agent.toolcalling.context; // Tool Calling会话焦点服务包。

import com.fasterxml.jackson.databind.ObjectMapper; // 构造Redis中保存的JSON字符串。
import com.fasterxml.jackson.databind.node.ObjectNode; // 按固定字段生成会话焦点JSON。
import lombok.extern.slf4j.Slf4j; // 输出[ConversationFocus]日志。
import org.springframework.data.redis.core.StringRedisTemplate; // 使用字符串Redis模板保存原始JSON字符串。
import org.springframework.stereotype.Service; // 注册为Spring Bean，供业务Tool注入。

import java.time.LocalDateTime; // 记录焦点更新时间。
import java.util.concurrent.TimeUnit; // 设置Redis TTL单位。

/**
 * 会话最近命中文档焦点服务。
 *
 * <p>适用场景：RAG类工具命中文档后，保存当前userId + conversationId下的最近命中文档上下文，后续工具可读取并解析“这篇笔记”等指代。</p>
 * <p>当前调用链为：RagSearchTool -> ConversationFocusService.saveLastHitArticle -> Redis key
 * {@code techbrain:conversation:focus:{userId}:{conversationId}}。</p>
 * <p>本服务位于Tech-Brain-Tool公共模块，职责是保存通用会话焦点，不依赖具体业务Tool、不读取数据库、不修改聊天消息或长期记忆。</p>
 */
@Slf4j // 生成日志对象。
@Service // 注册通用会话焦点服务。
public class ConversationFocusService { // 保存会话最近命中文档焦点的通用服务。

    private static final String FOCUS_KEY_PREFIX = "techbrain:conversation:focus:"; // Redis key前缀。

    private static final long FOCUS_TTL_HOURS = 6; // 会话焦点过期时间，避免长期残留。

    private static final int TITLE_PREVIEW_MAX_LENGTH = 80; // title日志最多打印80字。

    private static final String SOURCE_TYPE_ARTICLE = "ARTICLE"; // 当前RAG命中文档来源固定为ARTICLE。

    private final StringRedisTemplate stringRedisTemplate; // 保存JSON字符串到Redis。

    private final ObjectMapper objectMapper = new ObjectMapper(); // 仅用于构造ObjectNode，不序列化LocalDateTime对象。

    public ConversationFocusService(StringRedisTemplate stringRedisTemplate) { // 构造器注入Redis模板。
        this.stringRedisTemplate = stringRedisTemplate; // 保存Redis访问入口。
    }

    public void saveLastHitArticle(Long userId,
                                   Long conversationId,
                                   Long articleId,
                                   String title,
                                   String sourceTool,
                                   String query) { // 保存当前会话最近一次RAG命中文章焦点。
        if (userId == null || conversationId == null || articleId == null) { // 缺少关键归属参数时不能保存。
            log.warn("[ConversationFocus] skip save because required id is null, userId: {}, conversationId: {}, articleId: {}",
                    userId, conversationId, articleId); // 只打印ID，不影响RAG主流程。
            return; // 直接跳过保存。
        }

        ConversationFocusContext context = buildArticleFocusContext(articleId, title, sourceTool, query); // 构造标准焦点DTO。
        String redisKey = buildFocusKey(userId, conversationId); // 按userId + conversationId构造Redis key。
        String payloadJson = buildPayloadJson(context); // 构造固定字段JSON字符串。

        log.info("[ConversationFocus] save last hit article, userId: {}, conversationId: {}, articleId: {}, title: {}",
                userId, conversationId, articleId, previewTitle(title)); // 打印保存入口日志，不打印正文。
        log.info("[ConversationFocus] redis key: {}", redisKey); // 打印Redis key，便于验收。
        stringRedisTemplate.opsForValue().set(redisKey, payloadJson, FOCUS_TTL_HOURS, TimeUnit.HOURS); // 写入Redis并设置6小时TTL。
        log.info("[ConversationFocus] save success"); // 标记保存成功。
    }

    private ConversationFocusContext buildArticleFocusContext(Long articleId,
                                                              String title,
                                                              String sourceTool,
                                                              String query) { // 构造文章焦点上下文。
        ConversationFocusContext context = new ConversationFocusContext(); // 新建DTO。
        context.setSourceType(SOURCE_TYPE_ARTICLE); // 当前来源类型固定ARTICLE。
        context.setSourceId(articleId); // 保存articleId。
        context.setTitle(title == null ? "" : title); // 标题为空时保存空字符串，保持JSON字段稳定。
        context.setSourceTool(sourceTool == null ? "" : sourceTool); // 保存产生焦点的工具名。
        context.setQuery(query == null ? "" : query); // 保存触发检索的query。
        context.setUpdateTime(LocalDateTime.now()); // 使用当前时间作为更新时间。
        return context; // 返回标准焦点DTO。
    }

    private String buildPayloadJson(ConversationFocusContext context) { // 将DTO转换为固定JSON结构。
        ObjectNode payload = objectMapper.createObjectNode(); // 创建JSON节点。
        payload.put("sourceType", context.getSourceType()); // 写入来源类型。
        payload.put("sourceId", context.getSourceId()); // 写入来源ID。
        payload.put("title", context.getTitle()); // 写入标题。
        payload.put("sourceTool", context.getSourceTool()); // 写入来源工具。
        payload.put("query", context.getQuery()); // 写入查询语句。
        payload.put("updateTime", context.getUpdateTime().toString()); // 写入ISO-8601格式更新时间。
        return payload.toString(); // 返回JSON字符串。
    }

    private String buildFocusKey(Long userId, Long conversationId) { // 构造Redis key。
        return FOCUS_KEY_PREFIX + userId + ":" + conversationId; // key格式：techbrain:conversation:focus:{userId}:{conversationId}。
    }

    private String previewTitle(String title) { // 生成title日志预览。
        if (title == null) { // title为空时返回空字符串。
            return ""; // 避免日志打印null。
        }
        String normalizedTitle = title.replace('\n', ' ').replace('\r', ' '); // 去掉换行，保持单行日志。
        return normalizedTitle.length() <= TITLE_PREVIEW_MAX_LENGTH
                ? normalizedTitle
                : normalizedTitle.substring(0, TITLE_PREVIEW_MAX_LENGTH) + "..."; // 超长标题截断。
    }
}
