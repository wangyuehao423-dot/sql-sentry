package simulator;

import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DemoOrderService {

    private final DemoOrderMapper demoOrderMapper;

    public DemoOrderService(DemoOrderMapper demoOrderMapper) {
        this.demoOrderMapper = demoOrderMapper;
    }

    public List<DemoOrder> queryByStatus(String status) {
        return demoOrderMapper.selectByStatus(status);
    }

    public int updateStatus(Long id, String status) {
        return demoOrderMapper.updateStatus(id, status);
    }
}
