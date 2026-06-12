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
 * <p>适用场景：保存当前 userId + conversationId 下的轻量会话焦点，支持 RAG 文章焦点、用户上传文件 activeFileFocus、
 * 项目源码 projectFileFocus 和最近明确项目目标 recentProjectTarget，后续工具路由可读取这些焦点解析“这篇笔记”“这个文件”“这个类”等指代。</p>
 * <p>当前调用链包括：RagSearchTool -> saveLastHitArticle -> Redis 文章焦点；
 * readFile 成功 -> saveActiveFileFocus -> Redis 上传文件焦点；
 * readProjectFile 成功 -> saveProjectFileFocus -> Redis 项目源码文件焦点；
 * searchCode 唯一定位项目文件 -> saveRecentProjectTarget -> Redis 最近项目目标。</p>
 * <p>本服务位于 Tech-Brain-Tool 公共模块，职责是保存通用会话焦点，不读取数据库、不修改聊天消息或长期记忆，也不保存文件正文。</p>
 */
@Slf4j // 生成日志对象。
@Service // 注册通用会话焦点服务。
public class ConversationFocusService { // 保存会话最近命中文档焦点的通用服务。

    private static final String FOCUS_KEY_PREFIX = "techbrain:conversation:focus:"; // Redis key前缀。

    private static final String FOCUS_HISTORY_KEY_PREFIX = "techbrain:conversation:focus:history:"; // Redis history key前缀。

    private static final String FILE_FOCUS_KEY_PREFIX = "techbrain:conversation:file_focus:"; // 文件 activeFileFocus 独立 Redis key 前缀，避免覆盖文章焦点。

    private static final String PROJECT_FILE_FOCUS_KEY_PREFIX = "techbrain:conversation:project_file_focus:"; // 项目源码 projectFileFocus 独立 Redis key 前缀，避免覆盖文章和上传文件焦点。

    private static final String PROJECT_TARGET_FOCUS_KEY_PREFIX = "techbrain:conversation:recent_project_target:"; // 最近项目目标 recentProjectTarget 独立 Redis key 前缀，避免覆盖已读取文件焦点。

    private static final long FOCUS_TTL_HOURS = 6; // 会话焦点过期时间，避免长期残留。

    private static final int FOCUS_HISTORY_LIMIT = 5; // 每个会话最多保留最近5条命中文档。

    private static final int FOCUS_MATCH_MIN_SCORE = 10; // 明确目标匹配最低分，避免只靠最近性误命中latest。

    private static final int TITLE_PREVIEW_MAX_LENGTH = 80; // title日志最多打印80字。

    private static final String SOURCE_TYPE_ARTICLE = "ARTICLE"; // 当前RAG命中文档来源固定为ARTICLE。

    public static final String SOURCE_TYPE_FILE = "FILE"; // readFile 成功读取后的文件焦点类型。

    public static final String SOURCE_TYPE_PROJECT_FILE = "PROJECT_FILE"; // readProjectFile 成功读取后的项目源码文件焦点类型。

    public static final String SOURCE_TYPE_PROJECT_TARGET = "PROJECT_TARGET"; // searchCode 或 analyzeCode 明确定位后的最近项目目标类型。

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
            "前面",
            "关于",
            "内容",
            "文档",
            "资料",
            "相关",
            "可以",
            "的",
            "把",
            "成",
            "就是这",
            "就是这篇",
            "再次",
            "再次总结"
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

        log.info("[ConversationFocus] save last hit article, userId: {}, conversationId: {}, articleId: {}",
                userId, conversationId, articleId); // INFO只保留关键ID，不打印标题。
        log.debug("[ConversationFocus] redis key: {}", redisKey); // Redis key降级为DEBUG。
        stringRedisTemplate.opsForValue().set(redisKey, payloadJson, FOCUS_TTL_HOURS, TimeUnit.HOURS); // 写入Redis并设置6小时TTL。
        log.debug("[ConversationFocus] save success"); // 保存成功细节降级为DEBUG。
        saveFocusHistory(userId, conversationId, context); // 同步维护最近命中文档history，失败不影响latest focus。
    }

    public void saveActiveFileFocus(Long userId,
                                    Long conversationId,
                                    Long fileId,
                                    String originalName,
                                    String fileExt,
                                    String fileType,
                                    String mimeType,
                                    Long fileSize,
                                    String sourceTool) { // 保存当前会话最近成功读取的文件焦点。
        if (userId == null || conversationId == null || fileId == null) { // 缺少归属参数时不能保存文件焦点。
            log.warn("[ConversationFocus] skip save active file focus, userId: {}, conversationId: {}, fileId: {}",
                    userId, conversationId, fileId); // 只打印ID，不影响readFile主流程。
            return; // 直接跳过保存。
        }
        ConversationFocusContext context = buildFileFocusContext(fileId, originalName, fileExt, fileType, mimeType, fileSize, sourceTool); // 构造文件焦点DTO。
        String redisKey = buildFileFocusKey(userId, conversationId); // 文件焦点使用独立key，不覆盖RAG文章焦点。
        String payloadJson = buildPayloadJson(context); // 构造固定字段JSON。
        log.info("[ConversationFocus] save active file focus, userId: {}, conversationId: {}, fileId: {}",
                userId, conversationId, fileId); // 不打印storagePath或文件内容。
        log.debug("[ConversationFocus] file focus redis key: {}", redisKey); // Redis key降级为DEBUG。
        stringRedisTemplate.opsForValue().set(redisKey, payloadJson, FOCUS_TTL_HOURS, TimeUnit.HOURS); // 写入Redis并沿用会话焦点TTL。
    }

    public ConversationFocusContext getActiveFileFocus(Long userId, Long conversationId) { // 读取当前会话最近成功读取的文件焦点。
        log.debug("[ConversationFocus] get active file focus, userId: {}, conversationId: {}", userId, conversationId); // 读取入口降级为DEBUG。
        if (userId == null || conversationId == null) { // 缺少归属参数时不能读取。
            return null; // 返回null，避免影响聊天主流程。
        }
        String redisKey = buildFileFocusKey(userId, conversationId); // 构造文件焦点Redis key。
        try {
            String payloadJson = stringRedisTemplate.opsForValue().get(redisKey); // 从Redis读取文件焦点JSON。
            if (payloadJson == null || payloadJson.isBlank()) { // 没有保存过文件焦点。
                return null; // 返回空焦点。
            }
            ConversationFocusContext focus = parseFocusContext(payloadJson); // 复用通用焦点解析。
            if (focus == null || !SOURCE_TYPE_FILE.equalsIgnoreCase(focus.getSourceType())) { // 只接受FILE类型焦点。
                return null; // 非FILE数据不参与文件指代。
            }
            log.debug("[ConversationFocus] active file focus sourceId: {}, title: {}",
                    focus.getSourceId(), previewTitle(focus.getTitle())); // 只打印ID和标题preview。
            return focus; // 返回文件焦点元信息。
        } catch (Exception e) {
            log.warn("[ConversationFocus] get active file focus failed, redisKey: {}, error: {}",
                    redisKey, e.getMessage(), e); // 读取失败不影响聊天主流程。
            return null; // 兜底返回null。
        }
    }

    public void saveProjectFileFocus(Long userId,
                                     Long conversationId,
                                     String path,
                                     String fileName,
                                     String extension,
                                     String language,
                                     String markdownName,
                                     Long fileSize,
                                     Boolean truncated,
                                     String readMode,
                                     String sourceTool) { // 保存当前会话最近成功读取的项目源码文件焦点。
        if (userId == null || conversationId == null || path == null || path.isBlank()) { // 缺少归属或相对路径时不能保存。
            log.warn("[ProjectFileFocus] skip save, userId: {}, conversationId: {}, path: {}",
                    userId, conversationId, path); // 只打印相对路径候选，不打印绝对路径。
            return; // 不影响 readProjectFile 主流程。
        }
        ConversationFocusContext context = buildProjectFileFocusContext(path, fileName, extension, language, markdownName,
                fileSize, truncated, readMode, sourceTool); // 构造项目源码文件焦点DTO。
        String redisKey = buildProjectFileFocusKey(userId, conversationId); // 项目文件焦点使用独立key，不覆盖文章或上传文件焦点。
        String payloadJson = buildPayloadJson(context); // 构造固定字段JSON。
        log.info("[ProjectFileFocus] save, userId: {}, conversationId: {}, path: {}",
                userId, conversationId, path); // 只打印workspace相对路径，不打印服务器绝对路径或文件内容。
        log.debug("[ProjectFileFocus] redis key: {}", redisKey); // Redis key 降级为 DEBUG。
        stringRedisTemplate.opsForValue().set(redisKey, payloadJson, FOCUS_TTL_HOURS, TimeUnit.HOURS); // 写入Redis并沿用会话焦点TTL。
        saveRecentProjectTarget(userId, conversationId, path, fileName, deriveClassNameFromPath(path, fileName),
                "CLASS_OR_FILE", sourceTool, "UNIQUE"); // 读取成功的项目文件也同步成为最近明确项目目标。
    }

    public ConversationFocusContext getProjectFileFocus(Long userId, Long conversationId) { // 读取当前会话最近成功读取的项目源码文件焦点。
        if (userId == null || conversationId == null) { // 缺少归属参数时不能读取。
            log.info("[ProjectFileFocus] get, userId: {}, conversationId: {}, found: {}", userId, conversationId, false); // 保留验收日志。
            return null; // 返回空焦点。
        }
        String redisKey = buildProjectFileFocusKey(userId, conversationId); // 构造项目文件焦点Redis key。
        try {
            String payloadJson = stringRedisTemplate.opsForValue().get(redisKey); // 从Redis读取项目文件焦点JSON。
            boolean found = payloadJson != null && !payloadJson.isBlank(); // 判断是否存在有效值。
            log.info("[ProjectFileFocus] get, userId: {}, conversationId: {}, found: {}",
                    userId, conversationId, found); // 只打印是否命中。
            if (!found) { // 没有保存过项目文件焦点。
                return null; // 返回空焦点。
            }
            ConversationFocusContext focus = parseFocusContext(payloadJson); // 复用通用焦点解析。
            if (focus == null
                    || !SOURCE_TYPE_PROJECT_FILE.equalsIgnoreCase(focus.getSourceType())
                    || focus.getPath() == null
                    || focus.getPath().isBlank()) { // 只接受 PROJECT_FILE 且 path 有效的焦点。
                return null; // 非项目文件焦点不参与项目源码指代。
            }
            log.debug("[ProjectFileFocus] path: {}, title: {}", focus.getPath(), previewTitle(focus.getTitle())); // 只打印相对路径和标题preview。
            return focus; // 返回项目源码焦点元信息。
        } catch (Exception e) {
            log.warn("[ProjectFileFocus] get failed, redisKey: {}, error: {}",
                    redisKey, e.getMessage(), e); // 读取失败不影响聊天主流程。
            return null; // 兜底返回null。
        }
    }

    public void saveRecentProjectTarget(Long userId,
                                        Long conversationId,
                                        String path,
                                        String fileName,
                                        String className,
                                        String targetType,
                                        String sourceTool,
                                        String confidence) { // 保存最近一次明确定位到的项目代码目标。
        if (userId == null || conversationId == null || path == null || path.isBlank()) { // 缺少归属或相对路径时不能保存。
            log.warn("[RecentProjectTarget] skip save, userId: {}, conversationId: {}, path: {}",
                    userId, conversationId, path); // 只打印相对路径候选，不打印绝对路径。
            return; // 不影响 searchCode/readProjectFile/analyzeCode 主流程。
        }
        ConversationFocusContext context = buildRecentProjectTargetContext(path, fileName, className,
                targetType, sourceTool, confidence); // 构造最近项目目标DTO。
        String redisKey = buildProjectTargetFocusKey(userId, conversationId); // 最近项目目标使用独立key。
        String payloadJson = buildPayloadJson(context); // 构造固定字段JSON。
        log.info("[RecentProjectTarget] save, userId: {}, conversationId: {}, path: {}",
                userId, conversationId, path); // 只打印 workspace 相对路径，不打印源码内容。
        log.debug("[RecentProjectTarget] redis key: {}", redisKey); // Redis key 降级为 DEBUG。
        stringRedisTemplate.opsForValue().set(redisKey, payloadJson, FOCUS_TTL_HOURS, TimeUnit.HOURS); // 写入Redis并沿用会话焦点TTL。
    }

    public ConversationFocusContext getRecentProjectTarget(Long userId, Long conversationId) { // 读取当前会话最近明确定位到的项目代码目标。
        if (userId == null || conversationId == null) { // 缺少归属参数时不能读取。
            log.info("[RecentProjectTarget] get, userId: {}, conversationId: {}, found: {}", userId, conversationId, false); // 保留验收日志。
            return null; // 返回空目标。
        }
        String redisKey = buildProjectTargetFocusKey(userId, conversationId); // 构造最近项目目标 Redis key。
        try {
            String payloadJson = stringRedisTemplate.opsForValue().get(redisKey); // 从Redis读取最近项目目标JSON。
            boolean found = payloadJson != null && !payloadJson.isBlank(); // 判断是否存在有效值。
            log.info("[RecentProjectTarget] get, userId: {}, conversationId: {}, found: {}",
                    userId, conversationId, found); // 只打印是否命中。
            if (!found) { // 没有保存过最近项目目标。
                return null; // 返回空目标。
            }
            ConversationFocusContext focus = parseFocusContext(payloadJson); // 复用通用焦点解析。
            if (focus == null
                    || !SOURCE_TYPE_PROJECT_TARGET.equalsIgnoreCase(focus.getSourceType())
                    || focus.getPath() == null
                    || focus.getPath().isBlank()) { // 只接受 PROJECT_TARGET 且 path 有效的焦点。
                return null; // 非最近项目目标不参与“它/这个类”解析。
            }
            log.debug("[RecentProjectTarget] path: {}, title: {}", focus.getPath(), previewTitle(focus.getTitle())); // 只打印相对路径和标题preview。
            return focus; // 返回最近项目目标元信息。
        } catch (Exception e) {
            log.warn("[RecentProjectTarget] get failed, redisKey: {}, error: {}",
                    redisKey, e.getMessage(), e); // 读取失败不影响聊天主流程。
            return null; // 兜底返回null。
        }
    }

    public ConversationFocusContext getLastFocus(Long userId, Long conversationId) { // 读取当前会话最近命中文档焦点。
        log.debug("[ConversationFocus] get last focus, userId: {}, conversationId: {}", userId, conversationId); // 读取入口降级为DEBUG。
        if (userId == null || conversationId == null) { // 缺少关键归属参数时不能读取。
            log.debug("[ConversationFocus] focus found: false"); // 未找到细节降级为DEBUG。
            return null; // 返回null，避免影响工具主流程。
        }

        String redisKey = buildFocusKey(userId, conversationId); // 按userId + conversationId构造Redis key。
        try {
            String payloadJson = stringRedisTemplate.opsForValue().get(redisKey); // 从Redis读取focus JSON字符串。
            boolean found = payloadJson != null && !payloadJson.isBlank(); // 判断是否存在有效值。
            log.debug("[ConversationFocus] focus found: {}", found); // 是否找到降级为DEBUG。
            if (!found) { // key不存在或值为空。
                return null; // 返回null表示没有最近焦点。
            }

            ConversationFocusContext focus = parseFocusContext(payloadJson); // 解析Redis JSON为DTO。
            if (focus == null) { // JSON为空节点时视为没有focus。
                log.debug("[ConversationFocus] focus found: false"); // 未找到细节降级为DEBUG。
                return null; // 返回null。
            }
            log.debug("[ConversationFocus] focus sourceId: {}, title: {}",
                    focus.getSourceId(), previewTitle(focus.getTitle())); // 只打印sourceId和标题预览。
            return focus; // 返回最近命中文档上下文。
        } catch (Exception e) {
            log.warn("[ConversationFocus] get last focus failed, redisKey: {}, error: {}",
                    redisKey, e.getMessage(), e); // 解析或读取失败只记录warn。
            return null; // 读取失败不能影响工具主流程。
        }
    }

    public List<ConversationFocusContext> getFocusHistory(Long userId, Long conversationId) { // 读取当前会话最近命中文档history。
        log.debug("[ConversationFocus] get focus history, userId: {}, conversationId: {}", userId, conversationId); // history读取入口降级为DEBUG。
        if (userId == null || conversationId == null) { // 缺少关键归属参数时不能读取history。
            log.debug("[ConversationFocus] focus history found: false"); // 未找到细节降级为DEBUG。
            log.debug("[ConversationFocus] focus history size: 0"); // 空history大小降级为DEBUG。
            return Collections.emptyList(); // 返回空列表，避免影响主流程。
        }
        String redisKey = buildFocusHistoryKey(userId, conversationId); // 构造history Redis key。
        try {
            String historyJson = stringRedisTemplate.opsForValue().get(redisKey); // 从Redis读取history JSON数组。
            boolean found = historyJson != null && !historyJson.isBlank(); // 判断是否存在有效history。
            log.debug("[ConversationFocus] focus history found: {}", found); // 是否找到history降级为DEBUG。
            if (!found) { // key不存在或值为空。
                log.debug("[ConversationFocus] focus history size: 0"); // 空history大小降级为DEBUG。
                return Collections.emptyList(); // 返回空列表。
            }
            List<ConversationFocusContext> history = parseFocusHistory(historyJson); // 解析history JSON数组。
            log.debug("[ConversationFocus] focus history size: {}", history.size()); // history条数降级为DEBUG。
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
        log.debug("[ConversationFocus] match focus by message: {}", previewTitle(currentMessage)); // 当前消息preview降级为DEBUG。
        if (userId == null || conversationId == null) { // 缺少归属参数时无法读取Redis。
            log.debug("[ConversationFocus] explicit target not matched, return null"); // 无法构造Redis key时直接返回null。
            return null; // 无法构造latest key，返回null。
        }

        String matchText = normalizeForMatch(currentMessage); // 清理泛化意图词，保留动态业务词。
        boolean pureReference = isPureReference(currentMessage, matchText); // 判断当前输入是否只是“这篇/刚才那篇”等纯指代。
        log.info("[ConversationFocus] normalized match text: {}", matchText); // 保留归一化文本，验证明确目标不会误回退latest。
        log.info("[ConversationFocus] pure reference: {}", pureReference); // 保留纯指代判断结果，便于验证兜底策略。

        List<ConversationFocusContext> history = getFocusHistory(userId, conversationId); // 读取最近命中history，最新在前。
        if (pureReference) { // 纯“这篇笔记/刚才那篇”等指代没有明确目标词。
            log.debug("[ConversationFocus] pure reference fallback latest"); // 纯指代允许使用latest focus。
            return getLastFocus(userId, conversationId); // 纯指代使用latest focus。
        }
        if (history.isEmpty()) { // 明确目标但没有history时不能回退latest，避免总结无关文档。
            log.info("[ConversationFocus] explicit target not matched, return null"); // 保留关键兜底日志。
            return null; // 返回null，让工具给出友好提示。
        }

        ConversationFocusContext bestFocus = null; // 当前最高分focus。
        int bestScore = 0; // 最高分，必须大于0才认为命中。
        for (int i = 0; i < history.size(); i++) { // 遍历history候选，最新在前。
            ConversationFocusContext focus = history.get(i); // 当前候选focus。
            int score = calculateFocusScore(matchText, currentMessage, focus, i); // 根据title/query动态计算匹配分。
            log.debug("[ConversationFocus] focus candidate sourceId: {}, title: {}, score: {}",
                    focus == null ? null : focus.getSourceId(),
                    focus == null ? "" : previewTitle(focus.getTitle()),
                    score); // 打印候选分数，不打印正文。
            if (score > bestScore) { // 分数更高时更新最佳候选。
                bestScore = score; // 保存最高分。
                bestFocus = focus; // 保存最佳focus。
            }
        }

        if (bestFocus != null && bestScore >= FOCUS_MATCH_MIN_SCORE) { // 最高分达到阈值时返回动态匹配结果。
            log.info("[ConversationFocus] matched focus sourceId: {}, score: {}",
                    bestFocus.getSourceId(), bestScore); // INFO只保留最终命中ID和分数。
            return bestFocus; // 返回匹配到的focus。
        }
        log.info("[ConversationFocus] explicit target not matched, return null"); // 明确目标未达到阈值时禁止回退latest。
        return null; // 返回null，避免误总结最近无关文档。
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
            log.debug("[ConversationFocus] focus history size: {}", history.size()); // 保存后的history大小降级为DEBUG。
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
        context.setFileExt(node.path("fileExt").asText("")); // 读取文件扩展名，ARTICLE焦点为空。
        context.setFileType(node.path("fileType").asText("")); // 读取文件业务类型，ARTICLE焦点为空。
        context.setMimeType(node.path("mimeType").asText("")); // 读取文件MIME类型，ARTICLE焦点为空。
        context.setFileSize(readLong(node.path("fileSize"))); // 读取文件大小，ARTICLE焦点为空。
        context.setPath(node.path("path").asText("")); // 读取项目文件相对路径，非 PROJECT_FILE 为空。
        context.setLanguage(node.path("language").asText("")); // 读取项目文件语言展示名，非 PROJECT_FILE 为空。
        context.setMarkdownName(node.path("markdownName").asText("")); // 读取项目文件 Markdown 语言名，旧数据为空。
        context.setTruncated(readBoolean(node.path("truncated"))); // 读取项目文件最近读取是否截断。
        context.setReadMode(node.path("readMode").asText("")); // 读取项目文件最近读取模式。
        context.setClassName(node.path("className").asText("")); // 读取最近项目目标类名。
        context.setTargetType(node.path("targetType").asText("")); // 读取最近项目目标类型。
        context.setConfidence(node.path("confidence").asText("")); // 读取最近项目目标置信度。
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

    private Boolean readBoolean(JsonNode node) { // 兼容读取布尔或字符串形式的Boolean。
        if (node == null || node.isMissingNode() || node.isNull()) { // 字段缺失。
            return null; // 返回null。
        }
        if (node.isBoolean()) { // 布尔节点。
            return node.asBoolean(); // 直接返回boolean。
        }
        String text = node.asText(""); // 字符串节点。
        if (text == null || text.trim().isEmpty()) { // 空字符串。
            return null; // 返回null。
        }
        return Boolean.parseBoolean(text.trim()); // 解析字符串布尔值。
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

    private ConversationFocusContext buildFileFocusContext(Long fileId,
                                                           String originalName,
                                                           String fileExt,
                                                           String fileType,
                                                           String mimeType,
                                                           Long fileSize,
                                                           String sourceTool) { // 构造文件 activeFileFocus 上下文。
        ConversationFocusContext context = new ConversationFocusContext(); // 新建DTO。
        context.setSourceType(SOURCE_TYPE_FILE); // 文件焦点来源类型固定为FILE。
        context.setSourceId(fileId); // 保存user_file.id。
        context.setTitle(originalName == null ? "" : originalName); // title用于保存原始文件名。
        context.setSourceTool(sourceTool == null ? "" : sourceTool); // 保存产生焦点的工具名，通常为readFile。
        context.setQuery(""); // 文件焦点不保存用户问题，避免污染文章焦点匹配。
        context.setFileExt(fileExt == null ? "" : fileExt); // 保存扩展名。
        context.setFileType(fileType == null ? "" : fileType); // 保存业务文件类型。
        context.setMimeType(mimeType == null ? "" : mimeType); // 保存MIME类型。
        context.setFileSize(fileSize); // 保存文件大小。
        context.setUpdateTime(LocalDateTime.now()); // 使用当前时间作为更新时间。
        return context; // 返回文件焦点DTO。
    }

    private ConversationFocusContext buildProjectFileFocusContext(String path,
                                                                  String fileName,
                                                                  String extension,
                                                                  String language,
                                                                  String markdownName,
                                                                  Long fileSize,
                                                                  Boolean truncated,
                                                                  String readMode,
                                                                  String sourceTool) { // 构造项目源码 projectFileFocus 上下文。
        ConversationFocusContext context = new ConversationFocusContext(); // 新建DTO。
        context.setSourceType(SOURCE_TYPE_PROJECT_FILE); // 项目源码焦点来源类型固定为 PROJECT_FILE。
        context.setSourceId(null); // 项目源码文件没有数据库sourceId，使用相对 path 定位。
        context.setTitle(fileName == null || fileName.isBlank() ? path : fileName); // title 保存文件名，缺失时用相对路径兜底。
        context.setSourceTool(sourceTool == null ? "" : sourceTool); // 保存产生焦点的工具名，通常为 readProjectFile。
        context.setQuery(""); // 项目文件焦点不保存用户问题，避免污染文章焦点匹配。
        context.setPath(path == null ? "" : path); // 保存 workspace 相对路径，不保存绝对路径。
        context.setFileExt(extension == null ? "" : extension); // 保存扩展名。
        context.setLanguage(language == null ? "" : language); // 保存语言展示名。
        context.setMarkdownName(markdownName == null ? "" : markdownName); // 保存 Markdown code fence 语言名。
        context.setFileSize(fileSize); // 保存文件大小元信息。
        context.setTruncated(truncated); // 保存最近读取是否截断。
        context.setReadMode(readMode == null ? "" : readMode); // 保存最近读取模式。
        context.setUpdateTime(LocalDateTime.now()); // 使用当前时间作为更新时间。
        return context; // 返回项目源码焦点DTO。
    }

    private ConversationFocusContext buildRecentProjectTargetContext(String path,
                                                                     String fileName,
                                                                     String className,
                                                                     String targetType,
                                                                     String sourceTool,
                                                                     String confidence) { // 构造最近项目目标 recentProjectTarget 上下文。
        String safePath = path == null ? "" : path; // 兜底相对路径。
        String safeFileName = fileName == null || fileName.isBlank() ? fileNameFromPath(safePath) : fileName; // 优先使用工具结果文件名。
        ConversationFocusContext context = new ConversationFocusContext(); // 新建DTO。
        context.setSourceType(SOURCE_TYPE_PROJECT_TARGET); // 最近项目目标来源类型固定为 PROJECT_TARGET。
        context.setSourceId(null); // 项目目标没有数据库sourceId，使用相对 path 定位。
        context.setTitle(safeFileName == null || safeFileName.isBlank() ? safePath : safeFileName); // title 保存文件名，缺失时用相对路径兜底。
        context.setSourceTool(sourceTool == null ? "" : sourceTool); // 保存产生目标的工具名，例如 searchCode/readProjectFile/analyzeCode。
        context.setQuery(""); // 最近项目目标不保存用户问题，避免污染 RAG 文章焦点匹配。
        context.setPath(safePath); // 保存 workspace 相对路径，不保存服务器绝对路径。
        context.setFileExt(extensionFromFileName(safeFileName)); // 保存扩展名，方便后续展示和语言判断。
        context.setClassName(className == null || className.isBlank() ? deriveClassNameFromPath(safePath, safeFileName) : className); // 保存 Java 类名候选。
        context.setTargetType(targetType == null || targetType.isBlank() ? "CLASS_OR_FILE" : targetType); // 保存目标类型。
        context.setConfidence(confidence == null || confidence.isBlank() ? "UNIQUE" : confidence); // 保存唯一命中置信度。
        context.setUpdateTime(LocalDateTime.now()); // 使用当前时间作为更新时间。
        return context; // 返回最近项目目标DTO。
    }

    private String fileNameFromPath(String path) { // 从 workspace 相对路径中提取文件名。
        if (path == null || path.isBlank()) { // 空路径没有文件名。
            return ""; // 返回空字符串。
        }
        String normalizedPath = path.replace('\\', '/'); // 统一路径分隔符。
        int slashIndex = normalizedPath.lastIndexOf('/'); // 查找最后一个路径分隔符。
        return slashIndex >= 0 ? normalizedPath.substring(slashIndex + 1) : normalizedPath; // 返回末段文件名。
    }

    private String extensionFromFileName(String fileName) { // 从文件名中提取扩展名。
        if (fileName == null || fileName.isBlank()) { // 空文件名没有扩展名。
            return ""; // 返回空字符串。
        }
        int dotIndex = fileName.lastIndexOf('.'); // 查找最后一个点。
        if (dotIndex < 0 || dotIndex == fileName.length() - 1) { // 没有点或点在末尾。
            return ""; // 返回空字符串。
        }
        return fileName.substring(dotIndex + 1); // 返回不带点的扩展名。
    }

    private String deriveClassNameFromPath(String path, String fileName) { // 从 Java 文件名推导类名。
        String name = fileName == null || fileName.isBlank() ? fileNameFromPath(path) : fileName; // 优先使用文件名。
        if (name == null || name.isBlank()) { // 没有文件名。
            return ""; // 返回空字符串。
        }
        return name.toLowerCase(Locale.ROOT).endsWith(".java")
                ? name.substring(0, name.length() - ".java".length())
                : ""; // 仅 Java 文件推导类名。
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

    private boolean isPureReference(String currentMessage, String normalizedMatchText) { // 判断当前输入是否只是对latest focus的纯指代。
        if (normalizedMatchText == null || normalizedMatchText.isBlank()) { // 去掉泛化词后没有剩余目标词。
            return true; // 纯指代允许回退latest focus。
        }
        return false; // 仍有剩余词时视为明确目标，必须匹配history。
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
        payload.put("fileExt", context.getFileExt() == null ? "" : context.getFileExt()); // 写入文件扩展名，ARTICLE焦点为空。
        payload.put("fileType", context.getFileType() == null ? "" : context.getFileType()); // 写入文件业务类型，ARTICLE焦点为空。
        payload.put("mimeType", context.getMimeType() == null ? "" : context.getMimeType()); // 写入文件MIME类型，ARTICLE焦点为空。
        if (context.getFileSize() == null) { // 文件大小缺失时写null。
            payload.putNull("fileSize"); // 保持字段稳定。
        } else {
            payload.put("fileSize", context.getFileSize()); // 写入文件大小。
        }
        payload.put("path", context.getPath() == null ? "" : context.getPath()); // 写入项目文件相对路径，非 PROJECT_FILE 为空。
        payload.put("language", context.getLanguage() == null ? "" : context.getLanguage()); // 写入项目文件语言展示名。
        payload.put("markdownName", context.getMarkdownName() == null ? "" : context.getMarkdownName()); // 写入项目文件 Markdown 语言名。
        if (context.getTruncated() == null) { // 截断状态缺失时写null。
            payload.putNull("truncated"); // 保持字段稳定。
        } else {
            payload.put("truncated", context.getTruncated()); // 写入项目文件截断状态。
        }
        payload.put("readMode", context.getReadMode() == null ? "" : context.getReadMode()); // 写入项目文件读取模式。
        payload.put("className", context.getClassName() == null ? "" : context.getClassName()); // 写入最近项目目标类名。
        payload.put("targetType", context.getTargetType() == null ? "" : context.getTargetType()); // 写入最近项目目标类型。
        payload.put("confidence", context.getConfidence() == null ? "" : context.getConfidence()); // 写入最近项目目标置信度。
        payload.put("updateTime", context.getUpdateTime() == null ? "" : context.getUpdateTime().toString()); // 写入ISO-8601格式更新时间。
        return payload; // 返回JSON节点。
    }

    private String buildFocusKey(Long userId, Long conversationId) { // 构造Redis key。
        return FOCUS_KEY_PREFIX + userId + ":" + conversationId; // key格式：techbrain:conversation:focus:{userId}:{conversationId}。
    }

    private String buildFocusHistoryKey(Long userId, Long conversationId) { // 构造Redis history key。
        return FOCUS_HISTORY_KEY_PREFIX + userId + ":" + conversationId; // key格式：techbrain:conversation:focus:history:{userId}:{conversationId}。
    }

    private String buildFileFocusKey(Long userId, Long conversationId) { // 构造文件 activeFileFocus Redis key。
        return FILE_FOCUS_KEY_PREFIX + userId + ":" + conversationId; // key格式：techbrain:conversation:file_focus:{userId}:{conversationId}。
    }

    private String buildProjectFileFocusKey(Long userId, Long conversationId) { // 构造项目源码 projectFileFocus Redis key。
        return PROJECT_FILE_FOCUS_KEY_PREFIX + userId + ":" + conversationId; // key格式：techbrain:conversation:project_file_focus:{userId}:{conversationId}。
    }

    private String buildProjectTargetFocusKey(Long userId, Long conversationId) { // 构造最近项目目标 recentProjectTarget Redis key。
        return PROJECT_TARGET_FOCUS_KEY_PREFIX + userId + ":" + conversationId; // key格式：techbrain:conversation:recent_project_target:{userId}:{conversationId}。
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
