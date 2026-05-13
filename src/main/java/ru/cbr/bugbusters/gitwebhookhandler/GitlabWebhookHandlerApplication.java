package ru.cbr.bugbusters.gitwebhookhandler;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class GitlabWebhookHandlerApplication {

    public static void main(String[] args) {
        SpringApplication.run(GitlabWebhookHandlerApplication.class, args);
    }
}
