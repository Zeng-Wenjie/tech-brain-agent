package com.agent.selfdev.security;

import com.agent.config.SelfDevProperties;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * Local persistent store for the first self-dev OWNER.
 */
@Slf4j
@Component
public class SelfDevOwnerStore {

    private static final String KEY_USER_ID = "owner.userId";
    private static final String KEY_USERNAME = "owner.username";

    private final SelfDevProperties properties;

    public SelfDevOwnerStore(SelfDevProperties properties) {
        this.properties = properties;
    }

    public synchronized boolean hasOwner() {
        Properties store = load();
        return !isBlank(store.getProperty(KEY_USER_ID)) || !isBlank(store.getProperty(KEY_USERNAME));
    }

    public synchronized boolean isOwner(Long userId, String username) {
        Properties store = load();
        String storedUserId = store.getProperty(KEY_USER_ID);
        String storedUsername = store.getProperty(KEY_USERNAME);
        boolean idMatches = userId != null && !isBlank(storedUserId) && String.valueOf(userId).equals(storedUserId.trim());
        boolean usernameMatches = !isBlank(username)
                && !isBlank(storedUsername)
                && username.trim().equalsIgnoreCase(storedUsername.trim());
        return idMatches || usernameMatches;
    }

    public synchronized boolean bootstrapOwner(Long userId, String username) {
        if (userId == null || isBlank(username) || hasOwner()) {
            return false;
        }
        Properties store = new Properties();
        store.setProperty(KEY_USER_ID, String.valueOf(userId));
        store.setProperty(KEY_USERNAME, username.trim());
        save(store);
        return true;
    }

    private Properties load() {
        Properties store = new Properties();
        Path file = resolveStoreFile();
        if (!Files.isRegularFile(file)) {
            return store;
        }
        try (InputStream inputStream = Files.newInputStream(file)) {
            store.load(inputStream);
        } catch (Exception e) {
            log.warn("[SelfDevOwner] failed to load owner store: {}, error: {}", file, e.getMessage());
        }
        return store;
    }

    private void save(Properties store) {
        Path file = resolveStoreFile();
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (OutputStream outputStream = Files.newOutputStream(file)) {
                store.store(outputStream, "Tech-Brain self-dev OWNER store");
            }
        } catch (IOException e) {
            throw new IllegalStateException("保存 OWNER 本地配置失败：" + e.getMessage(), e);
        }
    }

    private Path resolveStoreFile() {
        String configured = properties.getOwnerStoreFile();
        String path = isBlank(configured) ? "./data/self-dev-owner.properties" : configured.trim();
        return Paths.get(path).toAbsolutePath().normalize();
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }
}
