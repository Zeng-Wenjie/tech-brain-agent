package com.agent.toolcalling.project.language;

import java.util.regex.Pattern;

/**
 * 项目代码工具使用的轻量级搜索规则。
 *
 * <p>适用场景：本值对象用于描述某一种语言下的类、函数、方法或 import 行级匹配规则。
 * 它由 CodeLanguage 持有，并通过 CodeLanguageRegistry 复用给 SearchCodeTool。
 * 本规则只做单行正则匹配，不构建 AST，不分析依赖，也不总结代码职责。</p>
 *
 * <p>调用链：SearchCodeTool -> CodeLanguageRegistry.resolveByFileName
 * -> CodeLanguage.matchesClassDeclaration 或 matchesFunctionDeclaration
 * -> CodeSearchPattern.matches。</p>
 */
public final class CodeSearchPattern { // 单条轻量级代码搜索规则。
    private final String regexTemplate; // 包含一个 %s 占位符，运行时替换为已转义的查询词。

    private CodeSearchPattern(String regexTemplate) { // 私有构造器确保统一入口校验。
        this.regexTemplate = regexTemplate == null ? "" : regexTemplate; // 空规则兜底为空字符串。
    }

    public static CodeSearchPattern of(String regexTemplate) { // 创建查询词占位匹配规则。
        return new CodeSearchPattern(regexTemplate); // 返回不可变规则对象。
    }

    public boolean matches(String line, String query, boolean caseSensitive) { // 判断源码行是否命中当前规则。
        if (line == null || query == null || query.isBlank() || regexTemplate.isBlank()) { // 空输入直接不命中。
            return false; // 返回未命中。
        }
        try {
            String regex = regexTemplate.replace("%s", Pattern.quote(query.trim())); // 查询词按字面量转义并替换所有占位符。
            int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE; // 默认不区分大小写。
            return Pattern.compile(regex, flags).matcher(line).find(); // 使用 find 支持行内局部声明匹配。
        } catch (IllegalArgumentException e) { // 配置错误不能影响工具主流程。
            return false; // 异常规则按未命中处理。
        }
    }
}
