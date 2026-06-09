package ru.cbr.bugbusters.gitwebhookhandler.persistence.config;

import io.swagger.v3.oas.annotations.Hidden;
import org.h2.tools.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.sql.SQLException;

/**
 * Запускает H2 TCP Server на фиксированном порту, чтобы внешние клиенты
 * (DBeaver, IntelliJ Database, любой JDBC-клиент) могли подключиться
 * к файловой БД пока приложение работает.
 *
 * <p>Сервер НЕ запускается при тестах (профиль {@code test}).
 *
 * <h3>Подключение из DBeaver:</h3>
 * <pre>
 *   Тип:      H2 Server
 *   JDBC URL: jdbc:h2:tcp://localhost:9092/~/.mr-checker/db/mr-checker
 *   User:     sa
 *   Password: (пусто)
 * </pre>
 */
@Hidden
@Configuration
@Profile("!test")
public class H2TcpServerConfig {

    private static final Logger log = LoggerFactory.getLogger(H2TcpServerConfig.class);

    /** Порт TCP-сервера H2. Задаётся через {@code h2.tcp-server.port}, по умолчанию 9092. */
    @Value("${h2.tcp-server.port:9092}")
    private String tcpPort;

    /** Разрешённые хосты для подключения. По умолчанию только localhost. */
    @Value("${h2.tcp-server.allowed-hosts:localhost,127.0.0.1}")
    private String allowedHosts;

    /**
     * Создаёт и запускает H2 TCP Server.
     * Spring автоматически вызывает {@code start()} при старте контекста
     * и {@code stop()} при завершении.
     *
     * @return запущенный экземпляр {@link Server}
     * @throws SQLException если не удалось запустить сервер
     */
    @Bean(initMethod = "start", destroyMethod = "stop")
    public Server h2TcpServer() throws SQLException {
        log.info("Запуск H2 TCP Server на порту {} (разрешённые хосты: {})", tcpPort, allowedHosts);
        Server server = Server.createTcpServer(
                "-tcp",
                "-tcpPort", tcpPort,
                "-tcpAllowOthers",
                "-ifNotExists"
        );
        log.info("H2 TCP Server запущен: {}", server.getURL());
        return server;
    }
}
