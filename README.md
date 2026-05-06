# SQL Sentry

`SQL Sentry` 是一个面向 MyBatis 业务系统的慢 SQL 捕获与智能改写方案，项目拆成三个模块：

- `sql-sentry-spring-boot-starter`
- `sql-sentry-server`
- `demo-client`

`starter` 负责客户端能力：MyBatis SQL 拦截、慢 SQL 上报、规则拉取、本地缓存、`BoundSql` 反射改写。  
`server` 负责服务端能力：接收慢 SQL、AI 诊断、安全校验、保存改写规则、提供规则拉取接口。  
`demo-client` 是业务演示工程，只通过 Maven dependency 引入 starter，不再复制源码。

## Modules

```text
root-parent
├─ sql-sentry-spring-boot-starter
├─ sql-sentry-server
└─ demo-client
```

## Starter Usage

业务项目引入 GitHub Packages 仓库：

```xml
<repositories>
    <repository>
        <id>github</id>
        <url>https://maven.pkg.github.com/wangyuehao423/sql-sentry</url>
    </repository>
</repositories>
```

引入 starter：

```xml
<dependency>
    <groupId>com.yuehao</groupId>
    <artifactId>sql-sentry-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

如需从 GitHub Packages 拉取私有包，Maven `settings.xml` 需要配置 GitHub 用户名和 Personal Access Token：

```xml
<servers>
    <server>
        <id>github</id>
        <username>${env.GITHUB_ACTOR}</username>
        <password>${env.GITHUB_TOKEN}</password>
    </server>
</servers>
```

## Configuration

```properties
sql.sentry.enabled=true
sql.sentry.capture-enabled=true
sql.sentry.rewrite-enabled=true
sql.sentry.server-base-url=http://127.0.0.1:18080
sql.sentry.slow-sql-threshold-ms=500
sql.sentry.pull-interval-ms=30000
sql.sentry.source=demo-service
sql.sentry.database=demo_db
```

默认值：

- `sql.sentry.source=default-service`
- `sql.sentry.database=default`

在 Mapper 上标注 `@SqlSentry` 即可启用治理：

```java
import com.yuehao.sqlsentry.annotation.SqlSentry;
import org.apache.ibatis.annotations.Mapper;

@SqlSentry
@Mapper
public interface OrderMapper {
}
```

`@SqlAudit` 已移除，业务方法不需要额外标注。

## Behavior

当前支持 `SELECT`、`WITH`、`UPDATE`、`DELETE`、`INSERT` 等慢 SQL 诊断建议。  
自动改写规则只对 `SELECT / WITH` 生效。

- `SELECT / WITH`：允许生成候选 `optimizedSql`，通过安全校验后下发到客户端自动执行。
- `UPDATE / DELETE / INSERT`：允许分析并输出建议，但不会生成客户端可拉取的自动改写规则。

服务端会明确记录：

```text
Non-query SQL only generates suggestions, skip rewrite mapping.
```

## Local Verification

在根目录执行：

```bash
mvn clean install
```

验证点：

1. `sql-sentry-spring-boot-starter` 可以单独打成 jar。
2. `sql-sentry-server` 可以正常启动并暴露 `/api/sql/captures`、`/api/sql/rewrite-mappings`。
3. `demo-client` 只通过 Maven dependency 引入 starter，启动后可正常触发 Mapper 拦截、慢 SQL 上报和规则拉取。

## Publish

根工程已配置 GitHub Packages：

```xml
<distributionManagement>
    <repository>
        <id>github</id>
        <url>https://maven.pkg.github.com/wangyuehao423/sql-sentry</url>
    </repository>
</distributionManagement>
```

本地发布 starter：

```bash
mvn -B -pl sql-sentry-spring-boot-starter -am deploy
```

GitHub Actions 手动触发工作流：

```text
.github/workflows/publish.yml
```

工作流使用：

- `actions/setup-java@v4`
- `JDK 17`
- `GITHUB_TOKEN`
- `workflow_dispatch`

只发布 `sql-sentry-spring-boot-starter`，不会发布 `demo-client`。
