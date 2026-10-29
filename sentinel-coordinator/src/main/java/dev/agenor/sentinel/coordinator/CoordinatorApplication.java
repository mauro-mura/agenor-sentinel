package dev.agenor.sentinel.coordinator;

import dev.agenor.runtime.AgenorRuntime;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;

@SpringBootApplication
public class CoordinatorApplication {

    public static void main(String[] args) {
        SpringApplication.run(CoordinatorApplication.class, args);
    }

    /**
     * The coordinator needs the application's database, and an agent discovered by package scanning
     * receives only Agenor's services. spring-boot-starter.md: build such an agent as a bean and
     * register it; it starts with the others.
     */
    @Bean
    CoordinatorAgent coordinatorAgent(AgenorRuntime runtime, Ledger ledger, JdbcClient jdbc) {
        var agent = new CoordinatorAgent(ledger, new Targets(jdbc));
        runtime.registerAgent(agent);
        return agent;
    }

    @Bean
    Ledger ledger(JdbcClient jdbc) {
        return new Ledger(jdbc);
    }
}
