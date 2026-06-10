package ru.cbr.bugbusters.gitwebhookhandler.webhook.domain;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Атрибуты MR из object_attributes в Merge Request Hook.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record MergeRequestAttributes(
        Long id,
        Long iid,
        String title,
        String state,
        String action,
        @JsonProperty("source_branch") String sourceBranch,
        @JsonProperty("target_branch") String targetBranch,
        @JsonProperty("last_commit") LastCommitInfo lastCommit,
        String url
) {
}
