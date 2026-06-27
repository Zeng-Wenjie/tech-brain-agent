package com.agent.selfdev.security;

import com.agent.config.SelfDevProperties;
import org.springframework.stereotype.Component;

/**
 * OWNER 权限守卫。
 *
 * <p>当前用户模型没有 role 字段，因此 OWNER 通过 techbrain.self-dev.owner-user-ids 显式配置。</p>
 */
@Component
public class SelfDevAccessGuard {

    private final SelfDevProperties properties;

    public SelfDevAccessGuard(SelfDevProperties properties) {
        this.properties = properties;
    }

    public boolean isOwner(Long userId) {
        return userId != null
                && properties.getOwnerUserIds() != null
                && properties.getOwnerUserIds().contains(userId);
    }

    public void assertOwner(Long userId) {
        if (!isOwner(userId)) {
            throw new SecurityException("Claude Code 开发能力仅允许 OWNER 触发。");
        }
    }
}
