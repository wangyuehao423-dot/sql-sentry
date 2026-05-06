package Service;

import Service.rewrite.SqlRewriteMappingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 慢 SQL 捕获与改写映射导出的 REST 入口。
 */
@RestController
public class SqlCaptureController {

    // 处理来自客户端应用的慢 SQL 捕获请求。
    private final SqlCaptureService sqlCaptureService;
    // 导出已审批的改写映射，供 starter 侧轮询使用。
    private final SqlRewriteMappingService sqlRewriteMappingService;
    // 为控制器方法构造 JSON 响应载荷。
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 使用所需依赖创建控制器。
     *
     * @param sqlCaptureService 用于记录捕获 SQL 的服务
     * @param sqlRewriteMappingService 用于导出已审批映射的服务
     */
    public SqlCaptureController(
            SqlCaptureService sqlCaptureService,
            SqlRewriteMappingService sqlRewriteMappingService) {
        this.sqlCaptureService = sqlCaptureService;
        this.sqlRewriteMappingService = sqlRewriteMappingService;
    }

    /**
     * 接收应用上报的慢 SQL。
     *
     * @param payload 包含 SQL 文本、来源元数据、耗时和可选 AI 配置的 HTTP 请求体
     * @return 载荷被接受处理后返回的 202 响应
     */
    @PostMapping("/api/sql/captures")
    public ResponseEntity<ObjectNode> captureSql(@RequestBody(required = false) CapturePayload payload) {
        // 没有 SQL 文本时，捕获流程无法继续。
        if (payload == null || !hasText(payload.getSql())) {
            return errorResponse(HttpStatus.BAD_REQUEST, "sql 字段不能为空");
        }

        // 将 Web 层载荷转换为服务层请求对象。
        ObjectNode result = sqlCaptureService.recordCapture(new SqlCaptureService.CaptureRequest(
                payload.getSql(),
                payload.getExplainPlan(),
                payload.getSource(),
                payload.getDatabase(),
                payload.getTraceId(),
                payload.getElapsedMs(),
                payload.getModel(),
                payload.getApiUrl(),
                payload.getApiKey()
        ));
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(result);
    }

    /**
     * 返回最近的慢 SQL 捕获记录。
     *
     * @param limit 最多返回的记录数
     * @return 最近捕获记录列表
     */
    @GetMapping("/api/sql/captures/recent")
    public JsonNode recentCaptures(@RequestParam(name = "limit", defaultValue = "8") int limit) {
        return sqlCaptureService.listRecentCaptures(limit);
    }

    /**
     * 导出最近审批通过的改写映射。
     *
     * @param limit 最多返回的映射数量
     * @return 包含导出映射的 JSON 文档
     */
    @GetMapping("/api/sql/rewrite-mappings")
    public ObjectNode rewriteMappings(@RequestParam(name = "limit", defaultValue = "200") int limit) {
        return sqlRewriteMappingService.exportMappings(limit);
    }

    /**
     * 构造标准的 JSON 错误响应。
     *
     * @param status 要返回的 HTTP 状态码
     * @param message 供人阅读的错误信息
     * @return 包装在 ResponseEntity 中的 JSON 错误载荷
     */
    private ResponseEntity<ObjectNode> errorResponse(HttpStatus status, String message) {
        ObjectNode error = objectMapper.createObjectNode();
        error.put("message", message);
        return ResponseEntity.status(status).body(error);
    }

    /**
     * 判断字符串是否包含非空白字符。
     *
     * @param value 输入字符串
     * @return 当值非 null 且去空白后不为空时返回 true
     */
    private boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }

    /**
     * 仅用于 HTTP JSON 绑定的控制器层请求体。
     */
    public static final class CapturePayload {
        // 调用方上报的原始 SQL 文本。
        private String sql;
        // 调用方可选上传的 EXPLAIN 输出。
        private String explainPlan;
        // 逻辑应用名或模块名。
        private String source;
        // 数据库或数据源名称。
        private String database;
        // 请求链路追踪标识。
        private String traceId;
        // SQL 执行耗时，单位毫秒。
        private Long elapsedMs;
        private String model;
        private String apiUrl;
        private String apiKey;

        public String getSql() {
            return sql;
        }

        public void setSql(String sql) {
            this.sql = sql;
        }

        public String getExplainPlan() {
            return explainPlan;
        }

        public void setExplainPlan(String explainPlan) {
            this.explainPlan = explainPlan;
        }

        public String getSource() {
            return source;
        }

        public void setSource(String source) {
            this.source = source;
        }

        public String getDatabase() {
            return database;
        }

        public void setDatabase(String database) {
            this.database = database;
        }

        public String getTraceId() {
            return traceId;
        }

        public void setTraceId(String traceId) {
            this.traceId = traceId;
        }

        public Long getElapsedMs() {
            return elapsedMs;
        }

        public void setElapsedMs(Long elapsedMs) {
            this.elapsedMs = elapsedMs;
        }

        public String getModel() {
            return model;
        }

        public void setModel(String model) {
            this.model = model;
        }

        public String getApiUrl() {
            return apiUrl;
        }

        public void setApiUrl(String apiUrl) {
            this.apiUrl = apiUrl;
        }

        public String getApiKey() {
            return apiKey;
        }

        public void setApiKey(String apiKey) {
            this.apiKey = apiKey;
        }
    }
}
