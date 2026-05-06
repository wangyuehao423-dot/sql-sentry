package com.yuehao.sqlsentry.rewrite;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.yuehao.sqlsentry.config.SqlSentryProperties;
import com.yuehao.sqlsentry.model.SqlRewriteRule;

import java.util.concurrent.TimeUnit;

public class SqlRewriteLocalCache {

    private final Cache<String, SqlRewriteRule> ruleCache;

    public SqlRewriteLocalCache(SqlSentryProperties properties) {
        this.ruleCache = Caffeine.newBuilder()
                .maximumSize(Math.max(128, properties.getMaxLocalMappings()))
                .expireAfterWrite(24L, TimeUnit.HOURS)
                .build();
    }

    public SqlRewriteRule get(String fingerprint) {
        return ruleCache.getIfPresent(fingerprint);
    }

    public void put(SqlRewriteRule rule) {
        if (rule == null || !hasText(rule.getFingerprint())) {
            return;
        }
        if (!hasText(rule.getOptimizedSql()) && !hasText(rule.getSummary()) && !hasText(rule.getAdvice())) {
            return;
        }
        ruleCache.put(rule.getFingerprint(), rule);
    }

    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
