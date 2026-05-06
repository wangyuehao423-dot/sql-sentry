package Service;

import Service.concurrent.DiagnosticExecutorManager;
import Service.metrics.DiagnosticsMetricsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 对外暴露重写平台的运行时诊断信息。
 */
@RestController
public class DiagnosticsMetricsController {

    // 汇总缓存、AI、限流和拒绝等指标。
    private final DiagnosticsMetricsService metricsService;
    // 提供规则执行和 LLM 执行的线程池快照。
    private final DiagnosticExecutorManager executorManager;
    // 负责创建响应所需的 JSON 载荷。
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 使用所需服务创建控制器。
     *
     * @param metricsService 业务指标服务
     * @param executorManager 线程池快照提供方
     */
    public DiagnosticsMetricsController(
            DiagnosticsMetricsService metricsService,
            DiagnosticExecutorManager executorManager) {
        this.metricsService = metricsService;
        this.executorManager = executorManager;
    }

    /**
     * 返回当前诊断快照。
     *
     * @return 诊断信息对应的 JSON 载荷
     */
    @GetMapping("/api/metrics/diagnostics")
    public ObjectNode diagnosticsMetrics() {
        ObjectNode payload = metricsService.snapshot(objectMapper);
        // 附加执行器状态，便于同时查看业务指标和容量数据。
        payload.set("poolSnapshot", executorManager.snapshot(objectMapper));
        return payload;
    }
}
