package simulator;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class DemoOrderController {

    private final DemoOrderService demoOrderService;

    public DemoOrderController(DemoOrderService demoOrderService) {
        this.demoOrderService = demoOrderService;
    }

    @GetMapping("/orders")
    public List<DemoOrder> listByStatus(@RequestParam(defaultValue = "PAID") String status) {
        return demoOrderService.queryByStatus(status);
    }

    @PutMapping("/orders/{id}/status")
    public int updateStatus(@PathVariable Long id, @RequestParam String status) {
        return demoOrderService.updateStatus(id, status);
    }
}
