package com.agent.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 项目代码工作区安全配置类。
 *
 * <p>适用场景：为后续项目代码读取能力提供统一安全边界，包括工作区根目录、可读取扩展名白名单、
 * 敏感文件名黑名单、敏感扩展名黑名单、单文件大小限制和最大读取字符数限制。</p>
 *
 * <p>调用链：Spring Boot 启动时绑定 application.yml 中的 techbrain.project 配置
 * -> ProjectWorkspaceProperties 注册为 Spring Bean
 * -> ProjectPathGuard 注入本配置并执行路径穿越、敏感文件、扩展名和文件大小校验
 * -> 后续 ListProjectTreeTool / SearchCodeTool / 项目文件读取能力复用这些安全规则。</p>
 *
 * <p>边界说明：本类只保存配置，不读取项目代码，不创建 Tool，不访问数据库，不接入 RAG / Milvus / 向量化，
 * 不影响用户文件库 FileUploadProperties 和 readFileTool。</p>
 */
@Data // 使用 Lombok 生成 getter/setter，保持与现有配置类风格一致。
@Component // 注册为 Spring Bean，供 ProjectPathGuard 注入使用。
@ConfigurationProperties(prefix = "techbrain.project") // 绑定 application.yml 中 techbrain.project.* 配置项。
public class ProjectWorkspaceProperties { // 项目代码工作区安全配置对象。
    private String workspaceDir = "./workspace/projects"; // 项目代码工作区根目录，外部路径必须限制在该目录下。
    private Integer maxFileSizeKb = 512; // 单个项目文件最大允许读取大小，单位 KB。
    private Integer maxReadChars = 50000; // 后续读取项目代码时的默认最大字符数上限。
    private List<String> allowedExtensions = new ArrayList<>(List.of( // 允许作为项目代码读取的多语言文本扩展名白名单。
            "java", "kt", "kts", "groovy", "scala",
            "py", "ipynb",
            "js", "jsx", "ts", "tsx", "vue", "svelte",
            "go", "rs",
            "c", "h", "cpp", "cc", "cxx", "hpp", "hxx",
            "cs", "csproj", "sln",
            "php", "rb",
            "swift", "m", "mm",
            "sql", "sh", "bash", "zsh", "ps1", "bat", "cmd",
            "html", "css", "scss", "less", "xml",
            "json", "yaml", "yml", "properties", "toml", "ini", "env.example",
            "md", "txt"
    ));
    private List<String> blockedFilenames = new ArrayList<>(List.of( // 禁止读取的敏感文件名黑名单，按完整文件名大小写不敏感匹配。
            ".env", ".env.local", ".env.production",
            "application-prod.yml", "application-prod.yaml",
            "application-secret.yml", "application-secret.yaml",
            "bootstrap-prod.yml", "bootstrap-prod.yaml",
            "id_rsa", "id_rsa.pub", "known_hosts", "authorized_keys",
            "config.php", "settings.py"
    ));
    private List<String> blockedExtensions = new ArrayList<>(List.of( // 禁止读取的敏感或二进制扩展名黑名单。
            "pem", "key", "p12", "pfx", "jks", "keystore",
            "crt", "cer", "der",
            "class", "jar", "war", "ear",
            "exe", "dll", "so", "dylib",
            "zip", "rar", "7z", "tar", "gz"
    ));

    public List<String> getAllowedExtensions() { // 返回允许扩展名列表，避免配置缺失时出现 NPE。
        return allowedExtensions == null ? Collections.emptyList() : allowedExtensions; // 配置为空时返回空列表。
    }

    public List<String> getBlockedFilenames() { // 返回敏感文件名列表，避免配置缺失时出现 NPE。
        return blockedFilenames == null ? Collections.emptyList() : blockedFilenames; // 配置为空时返回空列表。
    }

    public List<String> getBlockedExtensions() { // 返回敏感扩展名列表，避免配置缺失时出现 NPE。
        return blockedExtensions == null ? Collections.emptyList() : blockedExtensions; // 配置为空时返回空列表。
    }
}
