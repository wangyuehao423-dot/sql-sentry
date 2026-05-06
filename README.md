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

业务项目需要先引入 GitHub Packages 仓库：

```xml
<repositories>
    <repository>
        <id>github</id>
        <url>https://maven.pkg.github.com/wangyuehao423-dot/sql-sentry</url>
    </repository>
</repositories>
```

然后引入 starter：

```xml
<dependency>
    <groupId>com.yuehao</groupId>
    <artifactId>sql-sentry-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

如果从 GitHub Packages 拉取依赖时出现 `401 Unauthorized`，需要在本机 Maven 的 `settings.xml` 中配置 GitHub 用户名和 Personal Access Token。

文件路径：

```text
C:\Users\你的用户名\.m2\settings.xml
```

示例：

```xml
<settings>
    <servers>
        <server>
            <id>github</id>
            <username>wangyuehao423-dot</username>
            <password>你的 GitHub Personal Access Token</password>
        </server>
    </servers>
</settings>
```

注意：`server` 里的 `<id>github</id>` 必须和 `pom.xml` 仓库配置里的 `<id>github</id>` 保持一致。

## Configuration

业务项目在 `application.properties` 中添加：

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

## Mapper Usage

在需要治理的 Mapper 类或方法上添加 `@SqlSentry` 即可启用治理：

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

当前支持对以下 SQL 做慢 SQL 诊断建议：

- `SELECT`
- `WITH`
- `UPDATE`
- `DELETE`
- `INSERT`

但自动改写规则只对 `SELECT / WITH` 生效。

### SELECT / WITH

查询语句可以进入完整自动改写流程：

```text
业务 SQL 执行
↓
starter 拦截 MyBatis SQL
↓
慢 SQL 上报服务端
↓
服务端 AI 诊断
↓
安全校验
↓
保存改写规则
↓
客户端定时拉取规则
↓
下次执行同类 SQL 时自动改写
```

### UPDATE / DELETE / INSERT

非查询语句只生成优化建议，不会自动改写：

```text
UPDATE / DELETE / INSERT 慢 SQL
↓
服务端分析并输出优化建议
↓
不保存为自动改写规则
↓
客户端不会自动替换这类 SQL
```

这样做是为了避免自动改写修改类 SQL 导致数据风险。

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
2. `sql-sentry-server` 可以正常启动。
3. `demo-client` 只通过 Maven dependency 引入 starter。
4. 启动后可以正常触发 Mapper 拦截、慢 SQL 上报和规则拉取。

## Run Server

启动服务端：

```bash
mvn -pl sql-sentry-server spring-boot:run
```

服务端默认端口：

```text
18080
```

服务端主要接口：

```text
POST /api/sql/captures
GET  /api/sql/captures/recent
GET  /api/sql/rewrite-mappings
GET  /api/metrics/diagnostics
```

查看最近慢 SQL：

```bash
curl "http://127.0.0.1:18080/api/sql/captures/recent?limit=10"
```

查看已生成的改写规则：

```bash
curl "http://127.0.0.1:18080/api/sql/rewrite-mappings?limit=10"
```

## Run Demo Client

启动示例业务项目：

```bash
mvn -pl demo-client spring-boot:run
```

demo-client 默认端口：

```text
8080
```

触发 SELECT SQL：

```bash
curl "http://127.0.0.1:8080/orders?status=PAID"
```

触发 UPDATE SQL：

```bash
curl -X PUT "http://127.0.0.1:8080/orders/1/status?status=CANCELLED"
```

## Publish

根工程已配置 GitHub Packages：

```xml
<distributionManagement>
    <repository>
        <id>github</id>
        <url>https://maven.pkg.github.com/wangyuehao423-dot/sql-sentry</url>
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

发布成功后，GitHub Packages 中会出现：

```text
com.yuehao:sql-sentry-spring-boot-starter:1.0.0
```

## Module Responsibilities

| 模块 | 作用 |
|---|---|
| `sql-sentry-spring-boot-starter` | 客户端中间件，负责 SQL 拦截、慢 SQL 上报、规则拉取、本地缓存、SQL 改写 |
| `sql-sentry-server` | 服务端，负责慢 SQL 接收、AI 诊断、安全校验、规则保存 |
| `demo-client` | 示例业务项目，演示如何通过 dependency 接入 starter |