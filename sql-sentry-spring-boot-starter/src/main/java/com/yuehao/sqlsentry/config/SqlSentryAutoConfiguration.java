package com.yuehao.sqlsentry.config;

import com.yuehao.sqlsentry.client.SqlCaptureReporter;
import com.yuehao.sqlsentry.client.SqlSentryPullClient;
import com.yuehao.sqlsentry.mybatis.SqlSentryMyBatisInterceptor;
import com.yuehao.sqlsentry.rewrite.SqlRewriteLocalCache;
import okhttp3.OkHttpClient;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.mybatis.spring.boot.autoconfigure.ConfigurationCustomizer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.concurrent.TimeUnit;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(SqlSentryProperties.class)
@ConditionalOnProperty(prefix = "sql.sentry", name = "enabled", havingValue = "true", matchIfMissing = false)
public class SqlSentryAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public OkHttpClient sqlSentryOkHttpClient(SqlSentryProperties properties) {
        return new OkHttpClient.Builder()
                .connectTimeout(properties.getConnectTimeoutMs(), TimeUnit.MILLISECONDS)
                .readTimeout(properties.getReadTimeoutMs(), TimeUnit.MILLISECONDS)
                .writeTimeout(properties.getWriteTimeoutMs(), TimeUnit.MILLISECONDS)
                .callTimeout(properties.getCallTimeoutMs(), TimeUnit.MILLISECONDS)
                .build();
    }

    @Bean
    @ConditionalOnMissingBean
    public SqlRewriteLocalCache sqlRewriteLocalCache(SqlSentryProperties properties) {
        return new SqlRewriteLocalCache(properties);
    }

    @Bean
    @ConditionalOnMissingBean
    public SqlCaptureReporter sqlCaptureReporter(SqlSentryProperties properties, OkHttpClient sqlSentryOkHttpClient) {
        return new SqlCaptureReporter(properties, sqlSentryOkHttpClient);
    }

    @Bean
    @ConditionalOnMissingBean
    public SqlSentryPullClient sqlSentryPullClient(
            SqlSentryProperties properties,
            SqlRewriteLocalCache sqlRewriteLocalCache,
            OkHttpClient sqlSentryOkHttpClient) {
        return new SqlSentryPullClient(properties, sqlRewriteLocalCache, sqlSentryOkHttpClient);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(StatementHandler.class)
    public SqlSentryMyBatisInterceptor sqlSentryMyBatisInterceptor(
            SqlSentryProperties properties,
            SqlRewriteLocalCache sqlRewriteLocalCache,
            SqlCaptureReporter sqlCaptureReporter) {
        return new SqlSentryMyBatisInterceptor(properties, sqlRewriteLocalCache, sqlCaptureReporter);
    }

    @Bean(name = "sqlSentryConfigurationCustomizer")
    @ConditionalOnMissingBean(name = "sqlSentryConfigurationCustomizer")
    @ConditionalOnClass({StatementHandler.class, ConfigurationCustomizer.class})
    public ConfigurationCustomizer sqlSentryConfigurationCustomizer(SqlSentryMyBatisInterceptor interceptor) {
        return configuration -> configuration.addInterceptor(interceptor);
    }
}
