package com.agent.toolcalling.project;

/**
 * 项目代码目标解析结果。
 *
 * <p>适用场景：项目代码类工具（listProjectTree / searchCode / readProjectFile / analyzeCode /
 * analyzeCallChain / analyzeControllerServiceChain）在路由阶段需要统一判断“用户这一轮到底指向什么目标”，
 * 本对象就是 {@link ProjectCodeTargetResolver} 的解析产物，描述目标类型、提取出的接口路径/类名/方法名，
 * 以及是否允许回退使用 projectFileFocus。</p>
 *
 * <p>调用链：ToolCallingChatServiceImpl 路由阶段
 * -> ProjectCodeTargetResolver.resolve(userMessage, explicitProjectPath, hasProjectFocus, controllerFocus)
 * -> 返回本对象
 * -> 路由根据 targetType / useFocus 决定调用哪个工具、用 endpoint 还是 path、是否允许使用 focus。</p>
 *
 * <p>边界说明：本对象只承载“文本级目标解析”结论，不做磁盘扫描、不读取文件、不接入 RAG/数据库；
 * 真正的全项目扫描由各工具在 Tech-Brain-Agent 内基于 ProjectPathGuard 完成。</p>
 */
public final class ProjectCodeTargetResolution { // 项目代码目标解析结果（不可变）。

    /**
     * 目标类型：用户这一轮指向的项目代码目标种类。
     */
    public enum TargetType { // 目标类型枚举。
        ENDPOINT, // 明确接口路径，例如 /batch、/chat/message。
        FILE,     // 明确 workspace 相对文件路径或文件名。
        CLASS,    // 明确类名（含 Service/Mapper/Tool/Controller/Impl）。
        TOOL,     // 明确 AI Tool 目标，例如 SearchCodeTool 或 searchCode。
        METHOD,   // 明确方法名（无类名时的独立方法目标）。
        DEPENDENCY, // 明确依赖/Service/Mapper/Repository 类型名。
        FOCUS,    // 无明确目标，可使用 projectFileFocus 的指代追问。
        UNKNOWN   // 无法解析到任何项目代码目标。
    }

    /**
     * 解析置信度。
     */
    public enum Confidence { // 置信度枚举。
        EXACT,    // 精确目标（明确路径/接口/类名/方法名）。
        UNIQUE,   // 唯一可用目标（如唯一 focus）。
        MULTIPLE, // 多个候选，需要用户选择。
        NONE      // 没有可用目标。
    }

    private final boolean success; // 是否解析出可用目标。
    private final TargetType targetType; // 目标类型。
    private final Confidence confidence; // 置信度。
    private final String query; // 用户提取出的目标原文。
    private final String path; // 明确 workspace 相对路径，可空。
    private final String endpoint; // 接口路径，可空。
    private final String methodName; // 方法名，可空。
    private final String className; // 类名，可空。
    private final boolean useFocus; // 是否允许使用 projectFileFocus。
    private final String message; // 说明文案。

    private ProjectCodeTargetResolution(Builder builder) { // 仅允许通过 Builder 构造，保证不可变。
        this.success = builder.success; // 保存是否成功。
        this.targetType = builder.targetType == null ? TargetType.UNKNOWN : builder.targetType; // 默认 UNKNOWN。
        this.confidence = builder.confidence == null ? Confidence.NONE : builder.confidence; // 默认 NONE。
        this.query = builder.query; // 保存目标原文。
        this.path = builder.path; // 保存路径。
        this.endpoint = builder.endpoint; // 保存接口路径。
        this.methodName = builder.methodName; // 保存方法名。
        this.className = builder.className; // 保存类名。
        this.useFocus = builder.useFocus; // 保存是否允许 focus。
        this.message = builder.message; // 保存说明。
    }

    public boolean isSuccess() { // 是否解析出可用目标。
        return success;
    }

    public TargetType getTargetType() { // 目标类型。
        return targetType;
    }

    public Confidence getConfidence() { // 置信度。
        return confidence;
    }

    public String getQuery() { // 目标原文。
        return query;
    }

    public String getPath() { // 明确路径。
        return path;
    }

    public String getEndpoint() { // 接口路径。
        return endpoint;
    }

    public String getMethodName() { // 方法名。
        return methodName;
    }

    public String getClassName() { // 类名。
        return className;
    }

    public boolean isUseFocus() { // 是否允许使用 projectFileFocus。
        return useFocus;
    }

    public String getMessage() { // 说明文案。
        return message;
    }

    public static Builder builder() { // 创建 Builder。
        return new Builder();
    }

    @Override // 便于日志打印（不含文件内容、不含绝对路径）。
    public String toString() {
        return "ProjectCodeTargetResolution{type=" + targetType
                + ", confidence=" + confidence
                + ", endpoint=" + endpoint
                + ", className=" + className
                + ", methodName=" + methodName
                + ", path=" + path
                + ", useFocus=" + useFocus + "}"; // 仅打印路由判断关键字段。
    }

    /**
     * ProjectCodeTargetResolution 构造器。
     */
    public static final class Builder { // 链式构造器。
        private boolean success; // 是否成功。
        private TargetType targetType; // 目标类型。
        private Confidence confidence; // 置信度。
        private String query; // 目标原文。
        private String path; // 路径。
        private String endpoint; // 接口路径。
        private String methodName; // 方法名。
        private String className; // 类名。
        private boolean useFocus; // 是否允许 focus。
        private String message; // 说明。

        public Builder success(boolean success) { // 设置成功标记。
            this.success = success;
            return this;
        }

        public Builder targetType(TargetType targetType) { // 设置目标类型。
            this.targetType = targetType;
            return this;
        }

        public Builder confidence(Confidence confidence) { // 设置置信度。
            this.confidence = confidence;
            return this;
        }

        public Builder query(String query) { // 设置目标原文。
            this.query = query;
            return this;
        }

        public Builder path(String path) { // 设置路径。
            this.path = path;
            return this;
        }

        public Builder endpoint(String endpoint) { // 设置接口路径。
            this.endpoint = endpoint;
            return this;
        }

        public Builder methodName(String methodName) { // 设置方法名。
            this.methodName = methodName;
            return this;
        }

        public Builder className(String className) { // 设置类名。
            this.className = className;
            return this;
        }

        public Builder useFocus(boolean useFocus) { // 设置是否允许 focus。
            this.useFocus = useFocus;
            return this;
        }

        public Builder message(String message) { // 设置说明。
            this.message = message;
            return this;
        }

        public ProjectCodeTargetResolution build() { // 构造不可变结果。
            return new ProjectCodeTargetResolution(this);
        }
    }
}
