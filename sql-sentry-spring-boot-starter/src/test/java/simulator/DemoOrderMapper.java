package simulator;

import com.yuehao.sqlsentry.annotation.SqlSentry;

@SqlSentry
public interface DemoOrderMapper {

    void selectByStatus(Integer status);

    void updateStatus(String status, Long id);
}
