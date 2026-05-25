package com.agent.toolcalling.context; // Tool Calling通用上下文包。

/**
 * Tool Calling工具执行上下文Holder。
 *
 * <p>适用场景：ToolCallingChatServiceImpl在执行具体AiTool前，把当前请求上下文放入ThreadLocal；工具执行结束后立即清理。</p>
 * <p>调用链为：ToolCallingChatServiceImpl.executeToolForStream/streamWithForcedRagSearch -> set(context) -> AiTool.execute(arguments)
 * -> 业务工具通过get()读取userId/conversationId -> finally clear()。</p>
 * <p>本类只保存当前线程内的短生命周期上下文，不能用于跨请求缓存；必须在finally中clear，避免线程池复用导致上下文污染。</p>
 */
public final class ToolCallingContextHolder { // Tool Calling工具执行期间的ThreadLocal上下文容器。

    private static final ThreadLocal<ToolCallingRequestContext> CONTEXT_HOLDER = new ThreadLocal<>(); // 当前线程的Tool Calling请求上下文。

    private ToolCallingContextHolder() { // 工具类禁止实例化。
    }

    public static void set(ToolCallingRequestContext context) { // 设置当前线程上下文。
        if (context == null) { // 旧调用方没有传上下文时清理ThreadLocal。
            clear(); // 避免残留旧上下文。
            return; // 不再写入null。
        }
        CONTEXT_HOLDER.set(context); // 保存当前请求上下文。
    }

    public static ToolCallingRequestContext get() { // 获取当前线程上下文。
        return CONTEXT_HOLDER.get(); // 返回当前Tool Calling请求上下文，可能为null。
    }

    public static void clear() { // 清理当前线程上下文。
        CONTEXT_HOLDER.remove(); // 必须remove，避免线程池复用污染下一次请求。
    }
}
