# SQL Sentry

`SQL Sentry` is a slow-SQL capture, diagnosis, and rewrite solution for MyBatis-based services. The repository contains three modules:

- `sql-sentry-spring-boot-starter`
- `sql-sentry-server`
- `demo-client`

`starter` provides the client-side capability: MyBatis interception, slow-SQL reporting, rewrite rule pulling, local caching, and `BoundSql` rewrite.

`server` provides the server-side capability: receiving slow SQL, AI diagnosis, safety checks, storing rewrite rules, and exposing pull/query APIs.

`demo-client` is a sample business application that integrates the starter only through a Maven dependency.

## Modules

```text
root-parent
|- sql-sentry-spring-boot-starter
|- sql-sentry-server
\- demo-client
```

## Starter Usage

Add the GitHub Packages repository in the business project:

```xml
<repositories>
    <repository>
        <id>github</id>
        <url>https://maven.pkg.github.com/wangyuehao423-dot/sql-sentry</url>
    </repository>
</repositories>
```

Then add the starter dependency:

```xml
<dependency>
    <groupId>com.yuehao</groupId>
    <artifactId>sql-sentry-spring-boot-starter</artifactId>
    <version>1.0.0</version>
</dependency>
```

If GitHub Packages returns `401 Unauthorized`, configure your GitHub username and Personal Access Token in Maven `settings.xml`:

```text
C:\Users\<your-user>\.m2\settings.xml
```

```xml
<settings>
    <servers>
        <server>
            <id>github</id>
            <username>wangyuehao423-dot</username>
            <password><your-github-personal-access-token></password>
        </server>
    </servers>
</settings>
```

The `<id>github</id>` here must match the repository id in `pom.xml`.

## Configuration

The SQL Sentry server address is fixed inside the starter:

```text
http://175.178.42.12/sql-sentry
```

Business projects do not need to configure `sql.sentry.server-base-url`, and external configuration cannot override it. Business projects only need to configure whether SQL Sentry is enabled, capture/rewrite toggles, slow-SQL thresholds, `source`, `database`, and AI parameters.

Add only the business-side options you need in `application.properties`:

```properties
sql.sentry.enabled=true
sql.sentry.capture-enabled=true
sql.sentry.rewrite-enabled=true
sql.sentry.slow-sql-threshold-ms=500
sql.sentry.pull-interval-ms=30000
sql.sentry.source=demo-service
sql.sentry.database=demo_db
sql.sentry.ai.model=
sql.sentry.ai.api-url=
sql.sentry.ai.api-key=
```

Default values:

- `sql.sentry.source=default-service`
- `sql.sentry.database=default`
- `sql.sentry.pull-interval-ms=30000`
- `sql.sentry.slow-sql-threshold-ms=500`

## Mapper Usage

Add `@SqlSentry` on the Mapper class or method that should participate:

```java
import com.yuehao.sqlsentry.annotation.SqlSentry;
import org.apache.ibatis.annotations.Mapper;

@SqlSentry
@Mapper
public interface OrderMapper {
}
```

`@SqlAudit` has been removed. Business methods no longer need an extra annotation.

## Behavior

Slow-SQL diagnosis currently supports:

- `SELECT`
- `WITH`
- `UPDATE`
- `DELETE`
- `INSERT`

Automatic rewrite rules are applied only to `SELECT / WITH`.

### SELECT / WITH

```text
Business SQL executes
-> starter intercepts MyBatis SQL
-> slow SQL is reported to the server
-> server runs AI diagnosis
-> safety validation
-> rewrite rule stored
-> client periodically pulls rules
-> similar SQL is auto-rewritten on later execution
```

### UPDATE / DELETE / INSERT

Non-query SQL only produces optimization suggestions and is not auto-rewritten:

```text
UPDATE / DELETE / INSERT slow SQL
-> server analyzes and returns suggestions
-> no rewrite mapping is stored
-> client will not auto-replace this kind of SQL
```

This avoids the risk of automatically rewriting data-modifying SQL.

The server records:

```text
Non-query SQL only generates suggestions, skip rewrite mapping.
```

## Local Verification

From the repository root:

```bash
mvn clean package -DskipTests
```

Optional full test run:

```bash
mvn test
```

Verify:

1. `sql-sentry-spring-boot-starter` packages successfully.
2. `sql-sentry-server` packages successfully and produces an executable Spring Boot jar.
3. `demo-client` integrates the starter without configuring `sql.sentry.server-base-url`.

## Run Server

Start the server:

```bash
mvn -pl sql-sentry-server spring-boot:run
```

Externally accessible server address:

```text
http://175.178.42.12/sql-sentry
```

Server API endpoints remain:

```text
POST /api/sql/captures
GET  /api/sql/captures/recent
GET  /api/sql/rewrite-mappings
GET  /api/metrics/diagnostics
```

Examples:

```bash
curl "http://175.178.42.12/sql-sentry/api/sql/captures/recent?limit=10"
curl "http://175.178.42.12/sql-sentry/api/sql/rewrite-mappings?limit=10"
```

## Run Demo Client

Start the demo client:

```bash
mvn -pl demo-client spring-boot:run
```

Default demo port:

```text
8080
```

Trigger SELECT SQL:

```bash
curl "http://127.0.0.1:8080/orders?status=PAID"
```

Trigger UPDATE SQL:

```bash
curl -X PUT "http://127.0.0.1:8080/orders/1/status?status=CANCELLED"
```

## Publish

The root project is configured for GitHub Packages:

```xml
<distributionManagement>
    <repository>
        <id>github</id>
        <url>https://maven.pkg.github.com/wangyuehao423-dot/sql-sentry</url>
    </repository>
</distributionManagement>
```

Publish the starter locally:

```bash
mvn -B -pl sql-sentry-spring-boot-starter -am deploy
```

GitHub Actions workflow:

```text
.github/workflows/publish.yml
```

After publishing:

```text
com.yuehao:sql-sentry-spring-boot-starter:1.0.0
```
