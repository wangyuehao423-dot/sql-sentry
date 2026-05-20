package simulator;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class DemoStartupRunner implements ApplicationRunner {

    private final DemoOrderService demoOrderService;

    public DemoStartupRunner(DemoOrderService demoOrderService) {
        this.demoOrderService = demoOrderService;
    }

    @Override
    public void run(ApplicationArguments args) {
        demoOrderService.updateStatus(1001L, "PAID");
    }
}
