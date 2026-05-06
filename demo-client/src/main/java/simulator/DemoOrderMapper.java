package simulator;

import com.yuehao.sqlsentry.annotation.SqlSentry;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@SqlSentry
@Mapper
public interface DemoOrderMapper {

    @Select("SELECT id, status FROM orders WHERE status = #{status} ORDER BY id")
    List<DemoOrder> selectByStatus(@Param("status") String status);

    @Update("UPDATE orders SET status = #{status} WHERE id = #{id}")
    int updateStatus(@Param("id") Long id, @Param("status") String status);
}
