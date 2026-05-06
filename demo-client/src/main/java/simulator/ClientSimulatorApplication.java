package simulator;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("simulator")
public class ClientSimulatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(ClientSimulatorApplication.class, args);
    }
}
