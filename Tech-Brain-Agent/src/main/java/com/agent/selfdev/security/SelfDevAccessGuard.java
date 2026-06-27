package com.agent.selfdev.security;

import com.agent.config.SelfDevProperties;
import org.springframework.stereotype.Component;

/**
 * OWNER 权限守卫。
 *
 * <p>当前用户模型没有 role 字段，因此 OWNER 通过 techbrain.self-dev.owner-user-ids 或 owner-usernames 显式配置。</p>
 */
@Component
public class SelfDevAccessGuard {

    private final SelfDevProperties properties;
    private final SelfDevOwnerStore ownerStore;

    public SelfDevAccessGuard(SelfDevProperties properties, SelfDevOwnerStore ownerStore) {
        this.properties = properties;
        this.ownerStore = ownerStore;
    }

    public boolean isOwner(Long userId) {
        return isOwner(userId, null);
    }

    public boolean isOwner(Long userId, String username) {
        String normalizedUsername = username == null ? null : username.trim();
        boolean ownerByUserId = userId != null
                && properties.getOwnerUserIds() != null
                && properties.getOwnerUserIds().contains(userId);
        boolean ownerByUsername = normalizedUsername != null
                && !normalizedUsername.isEmpty()
                && properties.getOwnerUsernames() != null
                && properties.getOwnerUsernames().stream()
                .filter(value -> value != null && !value.trim().isEmpty())
                .anyMatch(value -> value.trim().equalsIgnoreCase(normalizedUsername));
        return ownerByUserId || ownerByUsername || ownerStore.isOwner(userId, username);
    }

    public boolean hasAnyOwner() {
        boolean hasConfiguredUserId = properties.getOwnerUserIds() != null && !properties.getOwnerUserIds().isEmpty();
        boolean hasConfiguredUsername = properties.getOwnerUsernames() != null
                && properties.getOwnerUsernames().stream().anyMatch(value -> value != null && !value.trim().isEmpty());
        return hasConfiguredUserId || hasConfiguredUsername || ownerStore.hasOwner();
    }

    public boolean bootstrapOwner(Long userId, String username) {
        if (hasAnyOwner()) {
            return false;
        }
        return ownerStore.bootstrapOwner(userId, username);
    }

    public void assertOwner(Long userId) {
        assertOwner(userId, null);
    }

    public void assertOwner(Long userId, String username) {
        if (!isOwner(userId, username)) {
            throw new SecurityException("Claude Code 开发能力仅允许 OWNER 触发。");
        }
    }
}
