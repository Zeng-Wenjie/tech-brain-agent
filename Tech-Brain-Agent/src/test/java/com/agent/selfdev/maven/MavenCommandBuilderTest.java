package com.agent.selfdev.maven;

import com.agent.config.MavenCompileProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P11 MavenCommandBuilder 单元测试。
 *
 * <p>适用场景：验证 Maven compile 命令只能由白名单参数构造，不能混入 install/deploy/test/package
 * 或 shell 拼接片段。测试只检查参数数组，不执行任何 Maven 命令。</p>
 */
class MavenCommandBuilderTest {

    @Test
    void buildModuleCompileCommandWithSafeOptions() {
        MavenCommandBuilder builder = new MavenCommandBuilder(properties()); // 创建命令构造器。
        MavenCompileRequest request = new MavenCompileRequest(); // 创建编译请求。
        request.setModule("Tech-Brain-Agent"); // 指定安全模块。
        request.setProfiles(List.of("dev", "local")); // 指定安全 profile。
        request.setExtraArgs(List.of("-q", "-U")); // 指定白名单额外参数。
        request.setSkipTests(true); // 跳过测试。

        MavenCommand command = builder.build(request); // 构造命令。

        assertEquals(List.of("mvn", "-q", "-U", "-Pdev,local", "-pl", "Tech-Brain-Agent", "-am", "-DskipTests", "compile"),
                command.command()); // 只允许固定 compile 命令形态。
        assertEquals("mvn -q -U -Pdev,local -pl Tech-Brain-Agent -am -DskipTests compile",
                command.commandPreview()); // 命令预览由安全参数组成。
    }

    @Test
    void rejectDangerousModuleAndExtraArgs() {
        MavenCommandBuilder builder = new MavenCommandBuilder(properties()); // 创建命令构造器。
        MavenCompileRequest badModule = new MavenCompileRequest(); // 非法模块请求。
        badModule.setModule("../Tech-Brain-Agent"); // 路径穿越模块名。
        assertThrows(IllegalArgumentException.class, () -> builder.build(badModule)); // 路径穿越必须拒绝。

        MavenCompileRequest shellArg = new MavenCompileRequest(); // shell 注入请求。
        shellArg.setExtraArgs(List.of("-q", ";")); // shell 片段。
        assertThrows(IllegalArgumentException.class, () -> builder.build(shellArg)); // shell 片段必须拒绝。

        MavenCompileRequest installGoal = new MavenCompileRequest(); // 非 compile goal 请求。
        installGoal.setExtraArgs(List.of("install")); // 非白名单且危险 goal。
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> builder.build(installGoal)); // install 必须拒绝。
        assertTrue(exception.getMessage().contains("白名单") || exception.getMessage().contains("compile")); // 错误信息应指向限制。
    }

    private MavenCompileProperties properties() {
        MavenCompileProperties properties = new MavenCompileProperties(); // 创建测试配置。
        properties.setExecutable("mvn"); // 使用 PATH 中的 mvn 名称。
        properties.setAllowedExtraArgs(List.of("-q", "-U", "-e", "-X")); // 白名单参数。
        return properties; // 返回配置。
    }
}
