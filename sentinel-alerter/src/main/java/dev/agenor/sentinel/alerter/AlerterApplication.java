package dev.agenor.sentinel.alerter;

import dev.agenor.runtime.AgenorRuntime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

import java.net.URI;

@SpringBootApplication
public class AlerterApplication {

    public static void main(String[] args) {
        SpringApplication.run(AlerterApplication.class, args);
    }

    /** Built as a bean, like the coordinator, because it needs the application's database. */
    @Bean
    AlerterAgent alerterAgent(AgenorRuntime runtime, AlertStore store, Webhook webhook) {
        var agent = new AlerterAgent(store, webhook);
        runtime.registerAgent(agent);
        return agent;
    }

    @Bean
    AlertStore alertStore(JdbcClient jdbc, TransactionTemplate tx) {
        return new AlertStore(jdbc, tx);
    }

    @Bean
    Webhook webhook(@Value("${sentinel.webhook-url}") URI url) {
        return new Webhook(url);
    }
}
