package com.agent.toolcalling.context; // Tool Calling会话焦点服务包。

import com.fasterxml.jackson.databind.ObjectMapper; // 构造Redis中保存的JSON字符串。
import com.fasterxml.jackson.databind.JsonNode; // 解析Redis中读取出的focus JSON。
import com.fasterxml.jackson.databind.node.ArrayNode; // 构造和解析focus history JSON数组。
import com.fasterxml.jackson.databind.node.ObjectNode; // 按固定字段生成会话焦点JSON。
import lombok.extern.slf4j.Slf4j; // 输出[ConversationFocus]日志。
import org.springframework.data.redis.core.StringRedisTemplate; // 使用字符串Redis模板保存原始JSON字符串。
import org.springframework.stereotype.Service; // 注册为Spring Bean，供业务Tool注入。

import java.time.LocalDateTime; // 记录焦点更新时间。
import java.util.ArrayList; // 维护最近命中文档history列表。
import java.util.Collections; // Redis无history或解析失败时返回空列表。
import java.util.HashSet; // 动态英文/数字token匹配时去重。
import java.util.List; // focus history 对外返回列表。
import java.util.Locale; // 文本归一化时统一大小写。
import java.util.Set; // 动态token集合。
import java.util.concurrent.TimeUnit; // 设置Redis TTL单位。
import java.util.regex.Matcher; // 提取英文/数字token。
import java.util.regex.Pattern; // 编译英文/数字token正则。

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

    private static final String FOCUS_HISTORY_KEY_PREFIX = "techbrain:conversation:focus:history:"; // Redis history key前缀。

    private static final long FOCUS_TTL_HOURS = 6; // 会话焦点过期时间，避免长期残留。

    private static final int FOCUS_HISTORY_LIMIT = 5; // 每个会话最多保留最近5条命中文档。

    private static final int TITLE_PREVIEW_MAX_LENGTH = 80; // title日志最多打印80字。

    private static final String SOURCE_TYPE_ARTICLE = "ARTICLE"; // 当前RAG命中文档来源固定为ARTICLE。

    private static final String[] MATCH_STOP_WORDS = { // 泛化停用词和意图词，不包含任何具体业务关键词。
            "整理成要点",
            "核心要点",
            "面试话术",
            "面试表达",
            "ai总结",
            "一下子",
            "能不能",
            "帮我",
            "请",
            "一下",
            "总结",
            "摘要",
            "概括",
            "提炼",
            "整理成",
            "整理",
            "要点",
            "重点",
            "面试",
            "笔记",
            "文章",
            "这篇",
            "那篇",
            "当前",
            "刚才",
            "刚刚",
            "上面",
            "之前",
            "关于",
            "可以",
            "的",
            "把",
            "成"
    };

    private static final Pattern PUNCTUATION_PATTERN = Pattern.compile("[\\s，。！？、：；,.!?;:()（）【】\\[\\]《》\"'“”]"); // 泛化标点和空白清理规则。

    private static final Pattern ALPHA_NUMERIC_TOKEN_PATTERN = Pattern.compile("[A-Za-z0-9]+"); // 动态提取英文/数字token，不写死业务词。

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
        saveFocusHistory(userId, conversationId, context); // 同步维护最近命中文档history，失败不影响latest focus。
    }

    public ConversationFocusContext getLastFocus(Long userId, Long conversationId) { // 读取当前会话最近命中文档焦点。
        log.info("[ConversationFocus] get last focus, userId: {}, conversationId: {}", userId, conversationId); // 打印读取入口日志。
        if (userId == null || conversationId == null) { // 缺少关键归属参数时不能读取。
            log.info("[ConversationFocus] focus found: false"); // 明确记录未找到。
            return null; // 返回null，避免影响工具主流程。
        }

        String redisKey = buildFocusKey(userId, conversationId); // 按userId + conversationId构造Redis key。
        try {
            String payloadJson = stringRedisTemplate.opsForValue().get(redisKey); // 从Redis读取focus JSON字符串。
            boolean found = payloadJson != null && !payloadJson.isBlank(); // 判断是否存在有效值。
            log.info("[ConversationFocus] focus found: {}", found); // 打印是否找到，不打印完整JSON。
            if (!found) { // key不存在或值为空。
                return null; // 返回null表示没有最近焦点。
            }

            ConversationFocusContext focus = parseFocusContext(payloadJson); // 解析Redis JSON为DTO。
            if (focus == null) { // JSON为空节点时视为没有focus。
                log.info("[ConversationFocus] focus found: false"); // 明确记录未找到。
                return null; // 返回null。
            }
            log.info("[ConversationFocus] focus sourceId: {}, title: {}",
                    focus.getSourceId(), previewTitle(focus.getTitle())); // 只打印sourceId和标题预览。
            return focus; // 返回最近命中文档上下文。
        } catch (Exception e) {
            log.warn("[ConversationFocus] get last focus failed, redisKey: {}, error: {}",
                    redisKey, e.getMessage(), e); // 解析或读取失败只记录warn。
            return null; // 读取失败不能影响工具主流程。
        }
    }

    public List<ConversationFocusContext> getFocusHistory(Long userId, Long conversationId) { // 读取当前会话最近命中文档history。
        log.info("[ConversationFocus] get focus history, userId: {}, conversationId: {}", userId, conversationId); // 打印history读取入口。
        if (userId == null || conversationId == null) { // 缺少关键归属参数时不能读取history。
            log.info("[ConversationFocus] focus history found: false"); // 明确记录未找到。
            log.info("[ConversationFocus] focus history size: 0"); // 空history大小固定为0。
            return Collections.emptyList(); // 返回空列表，避免影响主流程。
        }
        String redisKey = buildFocusHistoryKey(userId, conversationId); // 构造history Redis key。
        try {
            String historyJson = stringRedisTemplate.opsForValue().get(redisKey); // 从Redis读取history JSON数组。
            boolean found = historyJson != null && !historyJson.isBlank(); // 判断是否存在有效history。
            log.info("[ConversationFocus] focus history found: {}", found); // 打印是否找到history。
            if (!found) { // key不存在或值为空。
                log.info("[ConversationFocus] focus history size: 0"); // 空history大小固定为0。
                return Collections.emptyList(); // 返回空列表。
            }
            List<ConversationFocusContext> history = parseFocusHistory(historyJson); // 解析history JSON数组。
            log.info("[ConversationFocus] focus history size: {}", history.size()); // 打印history条数。
            return history; // 返回最新在前的history列表。
        } catch (Exception e) {
            log.warn("[ConversationFocus] get focus history failed, redisKey: {}, error: {}",
                    redisKey, e.getMessage(), e); // 解析失败只打印warn。
            return Collections.emptyList(); // 失败不影响主流程。
        }
    }

    public ConversationFocusContext matchFocus(Long userId,
                                               Long conversationId,
                                               String currentMessage) { // 基于当前用户输入和focus history动态匹配要总结的文档。
        log.info("[ConversationFocus] match focus by message: {}", previewTitle(currentMessage)); // 只打印当前消息短preview。
        if (userId == null || conversationId == null) { // 缺少归属参数时无法读取Redis。
            log.info("[ConversationFocus] no matched focus, fallback latest"); // 记录兜底路径。
            return null; // 无法构造latest key，返回null。
        }
        List<ConversationFocusContext> history = getFocusHistory(userId, conversationId); // 读取最近命中history，最新在前。
        if (history.isEmpty()) { // 没有history时兼容旧latest能力。
            log.info("[ConversationFocus] no focus history, fallback latest"); // 记录history缺失兜底。
            return getLastFocus(userId, conversationId); // 回退latest focus。
        }

        String matchText = normalizeForMatch(currentMessage); // 清理泛化意图词，保留动态业务词。
        log.info("[ConversationFocus] normalized match text: {}", matchText); // 打印归一化后的匹配文本。
        if (matchText.isBlank()) { // 纯“这篇笔记”等指代没有明确目标词。
            log.info("[ConversationFocus] no matched focus, fallback latest"); // 记录纯指代兜底。
            return getLastFocus(userId, conversationId); // 纯指代使用latest focus。
        }

        ConversationFocusContext bestFocus = null; // 当前最高分focus。
        int bestScore = 0; // 最高分，必须大于0才认为命中。
        for (int i = 0; i < history.size(); i++) { // 遍历history候选，最新在前。
            ConversationFocusContext focus = history.get(i); // 当前候选focus。
            int score = calculateFocusScore(matchText, currentMessage, focus, i); // 根据title/query动态计算匹配分。
            log.info("[ConversationFocus] focus candidate sourceId: {}, title: {}, score: {}",
                    focus == null ? null : focus.getSourceId(),
                    focus == null ? "" : previewTitle(focus.getTitle()),
                    score); // 打印候选分数，不打印正文。
            if (score > bestScore) { // 分数更高时更新最佳候选。
                bestScore = score; // 保存最高分。
                bestFocus = focus; // 保存最佳focus。
            }
        }

        if (bestFocus != null && bestScore > 0) { // 最高分有效时返回动态匹配结果。
            log.info("[ConversationFocus] matched focus sourceId: {}, title: {}, score: {}",
                    bestFocus.getSourceId(), previewTitle(bestFocus.getTitle()), bestScore); // 打印匹配结果。
            return bestFocus; // 返回匹配到的focus。
        }
        log.info("[ConversationFocus] no matched focus, fallback latest"); // 没有任何动态匹配时兜底latest。
        return getLastFocus(userId, conversationId); // 回退latest focus。
    }

    private void saveFocusHistory(Long userId,
                                  Long conversationId,
                                  ConversationFocusContext currentFocus) { // 保存最近命中文档history，最新在前。
        try {
            log.info("[ConversationFocus] save focus history, userId: {}, conversationId: {}, sourceId: {}",
                    userId, conversationId, currentFocus.getSourceId()); // 打印history保存入口。
            List<ConversationFocusContext> history = new ArrayList<>(getFocusHistory(userId, conversationId)); // 读取旧history，失败时返回空列表。
            history.removeIf(focus -> focus == null
                    || focus.getSourceId() == null
                    || focus.getSourceId().equals(currentFocus.getSourceId())); // 同一sourceId先移除旧项，避免重复。
            history.add(0, currentFocus); // 本次命中放到最前面。
            while (history.size() > FOCUS_HISTORY_LIMIT) { // 超过上限时删除最旧项。
                history.remove(history.size() - 1); // 删除列表尾部旧focus。
            }
            String historyKey = buildFocusHistoryKey(userId, conversationId); // 构造history Redis key。
            String historyJson = buildHistoryPayloadJson(history); // 构造JSON数组字符串。
            stringRedisTemplate.opsForValue().set(historyKey, historyJson, FOCUS_TTL_HOURS, TimeUnit.HOURS); // 写入history并设置TTL。
            log.info("[ConversationFocus] focus history size: {}", history.size()); // 打印保存后的history大小。
        } catch (Exception e) {
            log.warn("[ConversationFocus] save focus history failed, userId: {}, conversationId: {}, sourceId: {}, error: {}",
                    userId, conversationId, currentFocus == null ? null : currentFocus.getSourceId(), e.getMessage(), e); // history失败不影响latest。
        }
    }

    private List<ConversationFocusContext> parseFocusHistory(String historyJson) throws Exception { // 解析Redis中保存的focus history JSON数组。
        JsonNode root = objectMapper.readTree(historyJson); // 读取JSON根节点。
        if (root == null || !root.isArray()) { // 非数组视为无有效history。
            return Collections.emptyList(); // 返回空列表。
        }
        List<ConversationFocusContext> history = new ArrayList<>(); // 保存解析后的history。
        for (JsonNode node : root) { // 遍历数组元素。
            ConversationFocusContext focus = parseFocusContext(node); // 解析单条focus。
            if (focus != null && focus.getSourceId() != null) { // 只保留有sourceId的有效项。
                history.add(focus); // 加入history列表。
            }
        }
        return history; // 返回最新在前的列表。
    }

    private ConversationFocusContext parseFocusContext(String payloadJson) throws Exception { // 解析Redis中保存的focus JSON。
        JsonNode node = objectMapper.readTree(payloadJson); // 读取JSON节点。
        return parseFocusContext(node); // 复用JsonNode解析逻辑。
    }

    private ConversationFocusContext parseFocusContext(JsonNode node) { // 解析单条focus JSON节点。
        if (node == null || node.isNull() || node.isMissingNode()) { // 空节点没有有效focus。
            return null; // 返回null。
        }
        ConversationFocusContext context = new ConversationFocusContext(); // 新建DTO。
        context.setSourceType(node.path("sourceType").asText("")); // 读取来源类型。
        context.setSourceId(readLong(node.path("sourceId"))); // 读取来源ID。
        context.setTitle(node.path("title").asText("")); // 读取标题。
        context.setSourceTool(node.path("sourceTool").asText("")); // 读取来源工具。
        context.setQuery(node.path("query").asText("")); // 读取触发查询。
        String updateTime = node.path("updateTime").asText(""); // 读取更新时间字符串。
        if (!updateTime.isBlank()) { // 有更新时间时尝试解析。
            try {
                context.setUpdateTime(LocalDateTime.parse(updateTime)); // Redis保存时使用LocalDateTime.toString，按ISO格式解析。
            } catch (Exception e) {
                context.setUpdateTime(null); // 时间解析失败不影响focus主体字段。
            }
        }
        return context; // 返回解析后的DTO。
    }

    private Long readLong(JsonNode node) { // 兼容读取数字或字符串形式的Long。
        if (node == null || node.isMissingNode() || node.isNull()) { // 字段缺失。
            return null; // 返回null。
        }
        if (node.isNumber()) { // 数字节点。
            return node.asLong(); // 直接返回long。
        }
        String text = node.asText(""); // 字符串节点。
        if (text == null || text.trim().isEmpty()) { // 空字符串。
            return null; // 返回null。
        }
        try {
            return Long.parseLong(text.trim()); // 解析字符串数字。
        } catch (NumberFormatException e) {
            return null; // 非数字返回null。
        }
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

    private String normalizeForMatch(String text) { // 清理泛化停用词，保留title/query中的动态业务词用于匹配。
        if (text == null) { // null 文本没有匹配信息。
            return ""; // 返回空字符串。
        }
        String normalized = text.toLowerCase(Locale.ROOT); // 统一小写，兼容英文技术词动态匹配。
        normalized = PUNCTUATION_PATTERN.matcher(normalized).replaceAll(""); // 去掉空白和常见标点。
        for (String stopWord : MATCH_STOP_WORDS) { // 只移除泛化意图词和指代词。
            if (stopWord != null && !stopWord.isBlank()) { // 跳过空停用词。
                normalized = normalized.replace(stopWord.toLowerCase(Locale.ROOT), ""); // 不移除任何具体业务词。
            }
        }
        return normalized.trim(); // 返回适合title/query匹配的文本。
    }

    private int calculateFocusScore(String matchText,
                                    String currentMessage,
                                    ConversationFocusContext focus,
                                    int index) { // 根据当前输入和单条focus的title/query动态计算匹配分。
        if (matchText == null || matchText.isBlank() || focus == null) { // 无明确目标词或focus为空时不匹配。
            return 0; // 返回0分。
        }
        String titleText = normalizeForMatch(focus.getTitle()); // 归一化标题，保留业务词。
        String queryText = normalizeForMatch(focus.getQuery()); // 归一化当时RAG查询，保留业务词。
        int score = 0; // 初始化匹配分。

        if (!titleText.isBlank() && titleText.contains(matchText)) { // 标题完整包含当前目标词。
            score += 50; // 标题完整包含权重最高。
        }
        if (!titleText.isBlank() && matchText.contains(titleText)) { // 当前目标词完整包含标题。
            score += 50; // 双向完整包含同等加权。
        }
        if (!queryText.isBlank() && queryText.contains(matchText)) { // 历史query完整包含当前目标词。
            score += 30; // query完整包含次高权重。
        }
        if (!queryText.isBlank() && matchText.contains(queryText)) { // 当前目标词完整包含历史query。
            score += 30; // 双向query完整包含同等加权。
        }

        int lcsTitle = longestCommonSubstringLength(matchText, titleText); // 计算与标题的最长公共连续子串。
        int lcsQuery = longestCommonSubstringLength(matchText, queryText); // 计算与历史query的最长公共连续子串。
        if (lcsTitle >= 2) { // 至少两个连续字符才认为有意义。
            score += lcsTitle * 10; // 标题连续命中权重大。
        }
        if (lcsQuery >= 2) { // 至少两个连续字符才认为有意义。
            score += lcsQuery * 6; // query连续命中权重略低。
        }

        for (String bigram : buildBigrams(matchText)) { // 基于当前目标词生成中文/文本bigram。
            if (titleText.contains(bigram)) { // 标题包含该bigram。
                score += 5; // 标题bigram加分。
            }
            if (queryText.contains(bigram)) { // query包含该bigram。
                score += 3; // query bigram加分。
            }
        }

        Set<String> messageTokens = extractAlphaNumericTokens(currentMessage); // 从当前输入动态提取英文/数字token。
        Set<String> titleTokens = extractAlphaNumericTokens(focus.getTitle()); // 从标题动态提取英文/数字token。
        Set<String> queryTokens = extractAlphaNumericTokens(focus.getQuery()); // 从query动态提取英文/数字token。
        for (String token : messageTokens) { // 遍历当前输入token。
            if (titleTokens.contains(token)) { // 标题有相同token。
                score += 8; // 英文/数字token匹配加分。
            }
            if (queryTokens.contains(token)) { // query有相同token。
                score += 8; // 英文/数字token匹配加分。
            }
        }

        if (score > 0 && index == 0) { // 已有动态文本命中且是最新focus。
            score += 3; // 最近性小幅加权，不能单独制造命中。
        } else if (score > 0 && index == 1) { // 已有动态文本命中且是次新focus。
            score += 2; // 次新小幅加权。
        } else if (score > 0 && index == 2) { // 已有动态文本命中且是第三新focus。
            score += 1; // 第三新小幅加权。
        }
        return score; // 返回最终分数。
    }

    private int longestCommonSubstringLength(String a, String b) { // 计算两个字符串最长公共连续子串长度。
        if (a == null || b == null || a.isBlank() || b.isBlank()) { // 任一为空时没有公共子串。
            return 0; // 返回0。
        }
        int[][] dp = new int[a.length() + 1][b.length() + 1]; // 动态规划表，支持中文字符按char匹配。
        int max = 0; // 当前最长长度。
        for (int i = 1; i <= a.length(); i++) { // 遍历字符串a。
            for (int j = 1; j <= b.length(); j++) { // 遍历字符串b。
                if (a.charAt(i - 1) == b.charAt(j - 1)) { // 当前字符相同。
                    dp[i][j] = dp[i - 1][j - 1] + 1; // 延长连续公共子串。
                    max = Math.max(max, dp[i][j]); // 更新最长长度。
                }
            }
        }
        return max; // 返回最长公共连续子串长度。
    }

    private List<String> buildBigrams(String text) { // 生成长度为2的连续片段。
        if (text == null || text.length() < 2) { // 长度不足2没有bigram。
            return Collections.emptyList(); // 返回空列表。
        }
        List<String> bigrams = new ArrayList<>(); // 保存bigram列表。
        for (int i = 0; i < text.length() - 1; i++) { // 滑动窗口生成bigram。
            bigrams.add(text.substring(i, i + 2)); // 加入连续两个字符片段。
        }
        return bigrams; // 返回所有bigram。
    }

    private Set<String> extractAlphaNumericTokens(String text) { // 从文本中动态提取英文/数字token。
        if (text == null || text.isBlank()) { // 空文本没有token。
            return Collections.emptySet(); // 返回空集合。
        }
        Set<String> tokens = new HashSet<>(); // 去重保存token。
        Matcher matcher = ALPHA_NUMERIC_TOKEN_PATTERN.matcher(text); // 匹配英文、数字、英文数字混合片段。
        while (matcher.find()) { // 遍历所有token。
            String token = matcher.group().toLowerCase(Locale.ROOT); // token统一小写。
            if (!token.isBlank()) { // 跳过空token。
                tokens.add(token); // 加入集合。
            }
        }
        return tokens; // 返回动态token集合。
    }

    private String buildPayloadJson(ConversationFocusContext context) { // 将DTO转换为固定JSON结构。
        return buildPayloadNode(context).toString(); // 返回单条focus JSON字符串。
    }

    private String buildHistoryPayloadJson(List<ConversationFocusContext> history) { // 将history列表转换为JSON数组字符串。
        ArrayNode arrayNode = objectMapper.createArrayNode(); // 创建JSON数组节点。
        if (history != null) { // history非空时逐条写入。
            for (ConversationFocusContext focus : history) { // 遍历history。
                if (focus != null && focus.getSourceId() != null) { // 只写入有效focus。
                    arrayNode.add(buildPayloadNode(focus)); // 加入单条focus JSON节点。
                }
            }
        }
        return arrayNode.toString(); // 返回JSON数组字符串。
    }

    private ObjectNode buildPayloadNode(ConversationFocusContext context) { // 将DTO转换为固定JSON节点。
        ObjectNode payload = objectMapper.createObjectNode(); // 创建JSON节点。
        payload.put("sourceType", context.getSourceType()); // 写入来源类型。
        payload.put("sourceId", context.getSourceId()); // 写入来源ID。
        payload.put("title", context.getTitle()); // 写入标题。
        payload.put("sourceTool", context.getSourceTool()); // 写入来源工具。
        payload.put("query", context.getQuery()); // 写入查询语句。
        payload.put("updateTime", context.getUpdateTime() == null ? "" : context.getUpdateTime().toString()); // 写入ISO-8601格式更新时间。
        return payload; // 返回JSON节点。
    }

    private String buildFocusKey(Long userId, Long conversationId) { // 构造Redis key。
        return FOCUS_KEY_PREFIX + userId + ":" + conversationId; // key格式：techbrain:conversation:focus:{userId}:{conversationId}。
    }

    private String buildFocusHistoryKey(Long userId, Long conversationId) { // 构造Redis history key。
        return FOCUS_HISTORY_KEY_PREFIX + userId + ":" + conversationId; // key格式：techbrain:conversation:focus:history:{userId}:{conversationId}。
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
