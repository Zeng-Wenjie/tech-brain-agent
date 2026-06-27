package com.agent.toolcalling.context;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 会话焦点服务。
 *
 * <p>只保存 RAG 命中文章/笔记焦点和 readFile 成功读取的上传文件焦点，用于“这篇笔记”“这个文件”等指代解析。</p>
 */
@Slf4j
@Service
public class ConversationFocusService {

    private static final String FOCUS_KEY_PREFIX = "techbrain:conversation:focus:";
    private static final String FOCUS_HISTORY_KEY_PREFIX = "techbrain:conversation:focus:history:";
    private static final String FILE_FOCUS_KEY_PREFIX = "techbrain:conversation:file_focus:";
    private static final long FOCUS_TTL_HOURS = 6;
    private static final int FOCUS_HISTORY_LIMIT = 5;
    private static final int TITLE_PREVIEW_MAX_LENGTH = 80;
    private static final String SOURCE_TYPE_ARTICLE = "ARTICLE";
    public static final String SOURCE_TYPE_FILE = "FILE";
    private static final Pattern TOKEN_PATTERN = Pattern.compile("[\\p{IsHan}A-Za-z0-9]{2,}");

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ConversationFocusService(StringRedisTemplate stringRedisTemplate) {
        this.stringRedisTemplate = stringRedisTemplate;
    }

    public void saveLastHitArticle(Long userId,
                                   Long conversationId,
                                   Long articleId,
                                   String title,
                                   String sourceTool,
                                   String query) {
        if (userId == null || conversationId == null || articleId == null) {
            log.warn("[ConversationFocus] skip save article focus, userId: {}, conversationId: {}, articleId: {}",
                    userId, conversationId, articleId);
            return;
        }
        ConversationFocusContext context = new ConversationFocusContext();
        context.setSourceType(SOURCE_TYPE_ARTICLE);
        context.setSourceId(articleId);
        context.setTitle(title == null ? "" : title);
        context.setSourceTool(sourceTool == null ? "" : sourceTool);
        context.setQuery(query == null ? "" : query);
        context.setUpdateTime(LocalDateTime.now());

        String redisKey = buildFocusKey(userId, conversationId);
        String payloadJson = buildPayloadJson(context);
        stringRedisTemplate.opsForValue().set(redisKey, payloadJson, FOCUS_TTL_HOURS, TimeUnit.HOURS);
        saveFocusHistory(userId, conversationId, context);
        log.info("[ConversationFocus] save article focus, userId: {}, conversationId: {}, articleId: {}",
                userId, conversationId, articleId);
    }

    public void saveActiveFileFocus(Long userId,
                                    Long conversationId,
                                    Long fileId,
                                    String originalName,
                                    String fileExt,
                                    String fileType,
                                    String mimeType,
                                    Long fileSize,
                                    String sourceTool) {
        if (userId == null || conversationId == null || fileId == null) {
            log.warn("[ConversationFocus] skip save file focus, userId: {}, conversationId: {}, fileId: {}",
                    userId, conversationId, fileId);
            return;
        }
        ConversationFocusContext context = new ConversationFocusContext();
        context.setSourceType(SOURCE_TYPE_FILE);
        context.setSourceId(fileId);
        context.setTitle(originalName == null ? "" : originalName);
        context.setFileExt(fileExt == null ? "" : fileExt);
        context.setFileType(fileType == null ? "" : fileType);
        context.setMimeType(mimeType == null ? "" : mimeType);
        context.setFileSize(fileSize);
        context.setSourceTool(sourceTool == null ? "" : sourceTool);
        context.setUpdateTime(LocalDateTime.now());

        String redisKey = buildFileFocusKey(userId, conversationId);
        stringRedisTemplate.opsForValue().set(redisKey, buildPayloadJson(context), FOCUS_TTL_HOURS, TimeUnit.HOURS);
        log.info("[ConversationFocus] save file focus, userId: {}, conversationId: {}, fileId: {}",
                userId, conversationId, fileId);
    }

    public ConversationFocusContext getActiveFileFocus(Long userId, Long conversationId) {
        if (userId == null || conversationId == null) {
            return null;
        }
        try {
            String payloadJson = stringRedisTemplate.opsForValue().get(buildFileFocusKey(userId, conversationId));
            ConversationFocusContext focus = parseFocusContext(payloadJson);
            if (focus == null || !SOURCE_TYPE_FILE.equalsIgnoreCase(focus.getSourceType())) {
                return null;
            }
            return focus;
        } catch (Exception e) {
            log.warn("[ConversationFocus] get file focus failed, userId: {}, conversationId: {}",
                    userId, conversationId, e);
            return null;
        }
    }

    public ConversationFocusContext matchFocus(Long userId, Long conversationId, String currentMessage) {
        List<ConversationFocusContext> history = loadFocusHistory(userId, conversationId);
        if (history.isEmpty()) {
            return getLatestArticleFocus(userId, conversationId);
        }
        if (history.size() == 1 || isLatestReference(currentMessage)) {
            return history.get(0);
        }

        String normalizedMessage = normalize(currentMessage);
        ConversationFocusContext best = null;
        int bestScore = 0;
        for (ConversationFocusContext focus : history) {
            int score = matchScore(normalizedMessage, focus);
            if (score > bestScore) {
                best = focus;
                bestScore = score;
            }
        }
        return bestScore > 0 ? best : null;
    }

    private ConversationFocusContext getLatestArticleFocus(Long userId, Long conversationId) {
        if (userId == null || conversationId == null) {
            return null;
        }
        try {
            ConversationFocusContext focus = parseFocusContext(stringRedisTemplate.opsForValue().get(buildFocusKey(userId, conversationId)));
            return focus == null || !SOURCE_TYPE_ARTICLE.equalsIgnoreCase(focus.getSourceType()) ? null : focus;
        } catch (Exception e) {
            log.warn("[ConversationFocus] get article focus failed, userId: {}, conversationId: {}",
                    userId, conversationId, e);
            return null;
        }
    }

    private void saveFocusHistory(Long userId, Long conversationId, ConversationFocusContext context) {
        try {
            List<ConversationFocusContext> history = loadFocusHistory(userId, conversationId);
            List<ConversationFocusContext> updated = new ArrayList<>();
            updated.add(context);
            for (ConversationFocusContext item : history) {
                if (item == null || item.getSourceId() == null || item.getSourceId().equals(context.getSourceId())) {
                    continue;
                }
                updated.add(item);
                if (updated.size() >= FOCUS_HISTORY_LIMIT) {
                    break;
                }
            }
            ArrayNode array = objectMapper.createArrayNode();
            for (ConversationFocusContext item : updated) {
                array.add(toJson(item));
            }
            stringRedisTemplate.opsForValue().set(buildFocusHistoryKey(userId, conversationId), array.toString(),
                    FOCUS_TTL_HOURS, TimeUnit.HOURS);
        } catch (Exception e) {
            log.warn("[ConversationFocus] save focus history failed, userId: {}, conversationId: {}",
                    userId, conversationId, e);
        }
    }

    private List<ConversationFocusContext> loadFocusHistory(Long userId, Long conversationId) {
        if (userId == null || conversationId == null) {
            return Collections.emptyList();
        }
        try {
            String payloadJson = stringRedisTemplate.opsForValue().get(buildFocusHistoryKey(userId, conversationId));
            if (payloadJson == null || payloadJson.isBlank()) {
                return Collections.emptyList();
            }
            JsonNode node = objectMapper.readTree(payloadJson);
            if (!node.isArray()) {
                return Collections.emptyList();
            }
            List<ConversationFocusContext> history = new ArrayList<>();
            for (JsonNode item : node) {
                ConversationFocusContext focus = fromJson(item);
                if (focus != null && SOURCE_TYPE_ARTICLE.equalsIgnoreCase(focus.getSourceType())) {
                    history.add(focus);
                }
            }
            return history;
        } catch (Exception e) {
            log.warn("[ConversationFocus] load focus history failed, userId: {}, conversationId: {}",
                    userId, conversationId, e);
            return Collections.emptyList();
        }
    }

    private String buildPayloadJson(ConversationFocusContext context) {
        return toJson(context).toString();
    }

    private ObjectNode toJson(ConversationFocusContext context) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("sourceType", context.getSourceType());
        if (context.getSourceId() != null) {
            node.put("sourceId", context.getSourceId());
        }
        node.put("title", safe(context.getTitle()));
        node.put("sourceTool", safe(context.getSourceTool()));
        node.put("query", safe(context.getQuery()));
        node.put("fileExt", safe(context.getFileExt()));
        node.put("fileType", safe(context.getFileType()));
        node.put("mimeType", safe(context.getMimeType()));
        if (context.getFileSize() != null) {
            node.put("fileSize", context.getFileSize());
        }
        node.put("updateTime", context.getUpdateTime() == null ? LocalDateTime.now().toString() : context.getUpdateTime().toString());
        return node;
    }

    private ConversationFocusContext parseFocusContext(String payloadJson) {
        if (payloadJson == null || payloadJson.isBlank()) {
            return null;
        }
        try {
            return fromJson(objectMapper.readTree(payloadJson));
        } catch (Exception e) {
            return null;
        }
    }

    private ConversationFocusContext fromJson(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        ConversationFocusContext context = new ConversationFocusContext();
        context.setSourceType(text(node, "sourceType"));
        context.setSourceId(node.path("sourceId").isNumber() ? node.path("sourceId").asLong() : null);
        context.setTitle(text(node, "title"));
        context.setSourceTool(text(node, "sourceTool"));
        context.setQuery(text(node, "query"));
        context.setFileExt(text(node, "fileExt"));
        context.setFileType(text(node, "fileType"));
        context.setMimeType(text(node, "mimeType"));
        context.setFileSize(node.path("fileSize").isNumber() ? node.path("fileSize").asLong() : null);
        String updateTime = text(node, "updateTime");
        if (updateTime != null && !updateTime.isBlank()) {
            try {
                context.setUpdateTime(LocalDateTime.parse(updateTime));
            } catch (Exception ignored) {
                context.setUpdateTime(null);
            }
        }
        return context;
    }

    private int matchScore(String normalizedMessage, ConversationFocusContext focus) {
        if (normalizedMessage == null || focus == null) {
            return 0;
        }
        int score = 0;
        score += tokenScore(normalizedMessage, focus.getTitle(), 8);
        score += tokenScore(normalizedMessage, focus.getQuery(), 4);
        return score;
    }

    private int tokenScore(String normalizedMessage, String source, int weight) {
        if (source == null || source.isBlank()) {
            return 0;
        }
        int score = 0;
        Matcher matcher = TOKEN_PATTERN.matcher(normalize(source));
        while (matcher.find()) {
            String token = matcher.group();
            if (token.length() >= 2 && normalizedMessage.contains(token)) {
                score += weight;
            }
        }
        return score;
    }

    private boolean isLatestReference(String currentMessage) {
        String text = currentMessage == null ? "" : currentMessage;
        return text.contains("这篇") || text.contains("刚才") || text.contains("刚刚")
                || text.contains("上面") || text.contains("上一条") || text.contains("这条");
    }

    private String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? "" : value.asText("");
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String buildFocusKey(Long userId, Long conversationId) {
        return FOCUS_KEY_PREFIX + userId + ":" + conversationId;
    }

    private String buildFocusHistoryKey(Long userId, Long conversationId) {
        return FOCUS_HISTORY_KEY_PREFIX + userId + ":" + conversationId;
    }

    private String buildFileFocusKey(Long userId, Long conversationId) {
        return FILE_FOCUS_KEY_PREFIX + userId + ":" + conversationId;
    }

    private String previewTitle(String title) {
        if (title == null) {
            return "";
        }
        return title.length() <= TITLE_PREVIEW_MAX_LENGTH ? title : title.substring(0, TITLE_PREVIEW_MAX_LENGTH) + "...";
    }
}
