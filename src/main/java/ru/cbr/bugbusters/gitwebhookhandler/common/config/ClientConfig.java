package ru.cbr.bugbusters.gitwebhookhandler.common.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.gitlab4j.api.GitLabApi;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.http.okhttp.OpenAiHttpClientBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.web.client.RestClient;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.security.KeyStore;
import java.util.Arrays;

/**
 * Конфигурация HTTP-клиентов.
 *
 * ПРОБЛЕМА: Kaspersky TLS inspection proxy шлёт "fatal alert: handshake_failure".
 *
 * ДИАГНОСТИКА: это не проблема доверия сертификату (PKIX path building failed было бы
 * другой ошибкой). Это несовместимость cipher suites:
 *   - Java 21 по умолчанию отключает старые RSA key exchange суиты
 *     (TLS_RSA_WITH_AES_256_GCM_SHA384 и др.) в файле java.security
 *   - OkHttp 4.12 MODERN_TLS также использует только ECDHE суиты
 *   - Kaspersky proxy требует TLS_RSA_* для MITM-инспекции
 *
 * РЕШЕНИЕ:
 * 1. OpenAiHttpClientBuilderCustomizer (официальный Spring AI 2.0 API) —
 *    подменяет SSLSocketFactory внутри SpringAiOpenAiHttpClient.Builder
 * 2. CorporateSslSocketFactory — обёртка вокруг стандартного SSLSocketFactory,
 *    которая на каждом создании сокета добавляет RSA key exchange cipher suites
 *    через SSLParameters (механизм без замены ConnectionSpec).
 *
 * ПРЕДВАРИТЕЛьНОЕ УСЛОВИЕ: Kaspersky root CA должен быть в JVM cacerts:
 *   keytool -importcert -cacerts -alias kaspersky-root -file kaspersky.crt
 */
@Configuration
public class ClientConfig {

    // -------------------------------------------------------------------------
    // SSL infrastructure
    // -------------------------------------------------------------------------

    @Bean
    public X509TrustManager corporateTrustManager() throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm());
        tmf.init((KeyStore) null);
        return Arrays.stream(tmf.getTrustManagers())
                .filter(tm -> tm instanceof X509TrustManager)
                .map(tm -> (X509TrustManager) tm)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No X509TrustManager in cacerts"));
    }

    @Bean
    public SSLContext corporateSslContext(X509TrustManager trustManager) throws Exception {
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new javax.net.ssl.TrustManager[]{trustManager}, null);
        return ctx;
    }

    /**
     * SSLSocketFactory-обёртка, которая добавляет RSA key exchange cipher suites
     * на каждый сокет. Java 21 имеет эти суиты в jdk.tls.client.cipherSuites,
     * но они выключены через файл java.security. Через SSLParameters
     * мы явно возвращаем их в переговор без изменения java.security.
     */
    static final class CorporateSslSocketFactory extends SSLSocketFactory {

        private static final String[] CORPORATE_CIPHERS = {
                // ECDHE (OkHttp MODERN_TLS) — оставляем
                "TLS_ECDHE_RSA_WITH_AES_256_GCM_SHA384",
                "TLS_ECDHE_RSA_WITH_AES_128_GCM_SHA256",
                "TLS_ECDHE_ECDSA_WITH_AES_256_GCM_SHA384",
                "TLS_ECDHE_ECDSA_WITH_AES_128_GCM_SHA256",
                // RSA key exchange (Kaspersky proxy требует именно эти)
                "TLS_RSA_WITH_AES_256_GCM_SHA384",
                "TLS_RSA_WITH_AES_128_GCM_SHA256",
                "TLS_RSA_WITH_AES_256_CBC_SHA256",
                "TLS_RSA_WITH_AES_128_CBC_SHA256",
                "TLS_RSA_WITH_AES_256_CBC_SHA",
                "TLS_RSA_WITH_AES_128_CBC_SHA"
        };

        private final SSLSocketFactory delegate;

        CorporateSslSocketFactory(SSLSocketFactory delegate) {
            this.delegate = delegate;
        }

        @Override
        public String[] getDefaultCipherSuites() {
            return CORPORATE_CIPHERS;
        }

        @Override
        public String[] getSupportedCipherSuites() {
            return CORPORATE_CIPHERS;
        }

        @Override
        public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
            return configure((SSLSocket) delegate.createSocket(s, host, port, autoClose));
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            return configure((SSLSocket) delegate.createSocket(host, port));
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
            return configure((SSLSocket) delegate.createSocket(host, port, localHost, localPort));
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            return configure((SSLSocket) delegate.createSocket(host, port));
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
                throws IOException {
            return configure((SSLSocket) delegate.createSocket(address, port, localAddress, localPort));
        }

        /**
         * Устанавливает на сокет SSLParameters с полным списком cipher suites,
         * переопределяя запреты java.security для этого соединения.
         */
        private SSLSocket configure(SSLSocket socket) {
            SSLParameters params = socket.getSSLParameters();
            // Фильтруем: оставляем только те, которые реально поддерживает JVM
            String[] supported = socket.getSupportedCipherSuites();
            String[] enabled = Arrays.stream(CORPORATE_CIPHERS)
                    .filter(c -> Arrays.asList(supported).contains(c))
                    .toArray(String[]::new);
            params.setCipherSuites(enabled);
            socket.setSSLParameters(params);
            return socket;
        }
    }

    // -------------------------------------------------------------------------
    // Spring AI 2.0 официальный hook для настройки OkHttp внутри Spring AI
    // -------------------------------------------------------------------------

    /**
     * OpenAiHttpClientBuilderCustomizer — единственный правильный способ
     * подменить OkHttp внутри SpringAiOpenAiHttpClient (Spring AI 2.0 API).
     *
     * Вызывается до сборки OkHttpClient для всех OpenAI *Model-бинов
     * (chat, embedding, image, audio, moderation).
     */
    @Bean
    public OpenAiHttpClientBuilderCustomizer kasperkyTlsCustomizer(
            SSLContext sslContext, X509TrustManager trustManager) {
        return builder -> builder
                .sslSocketFactory(new CorporateSslSocketFactory(sslContext.getSocketFactory()))
                .trustManager(trustManager);
    }

    // -------------------------------------------------------------------------
    // Остальные бины
    // -------------------------------------------------------------------------

    @Bean
    public GitLabApi gitLabApi(AppProperties properties) {
        GitLabApi api = new GitLabApi(properties.gitlab().url(), properties.gitlab().token());
        api.setRequestTimeout(5000, 30000);
        return api;
    }

    @Bean
    public RestClient restClient(AppProperties properties) {
        return RestClient.builder()
                .baseUrl(properties.classContext().url())
                .build();
    }

    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        return new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    }

    @Bean
    @Lazy
    public ChatClient.Builder chatClientBuilder(OpenAiChatModel chatModel) {
        return ChatClient.builder(chatModel);
    }
}
