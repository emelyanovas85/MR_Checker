package ru.cbr.bugbusters.gitwebhookhandler.review;

import org.junit.jupiter.api.Test;
import ru.cbr.bugbusters.gitwebhookhandler.review.api.ReviewTriggerCommand;
import ru.cbr.bugbusters.gitwebhookhandler.review.domain.GroupReviewResult;
import ru.cbr.bugbusters.gitwebhookhandler.review.service.MarkdownCommentFormatter;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MarkdownCommentFormatterTest {

    private final MarkdownCommentFormatter formatter = new MarkdownCommentFormatter();

    // ReviewTriggerCommand: Long projectId, Long mrIid, String sourceBranch, String targetBranch,
    //                       String lastCommit, String mrTitle, String mrUrl, String triggeredBy
    private ReviewTriggerCommand buildCommand(String src, String tgt, String commit, String title) {
        return new ReviewTriggerCommand(42L, 7L, src, tgt, commit, title, "http://gitlab/mr/7", "test-user");
    }

    @Test
    void format_containsHeader() {
        var cmd = buildCommand("feature/foo", "main", "abc123", "My MR");
        var result = formatter.format(cmd, List.of());
        assertThat(result).contains("## AI Code Review");
    }

    @Test
    void format_containsBranchInfo() {
        var cmd = buildCommand("feature/foo", "main", "abc123", "My MR");
        var result = formatter.format(cmd, List.of());
        assertThat(result).contains("feature/foo").contains("main");
    }

    @Test
    void format_containsCommitAndTitle() {
        var cmd = buildCommand("feat", "main", "deadbeef", "Fix bug");
        var result = formatter.format(cmd, List.of());
        assertThat(result).contains("deadbeef").contains("Fix bug");
    }

    @Test
    void format_successSection_containsGroupName() {
        var cmd = buildCommand("feat", "main", null, null);
        // GroupReviewResult: int index, String groupName, boolean success, String reviewText
        var groupResult = new GroupReviewResult(0, "Security Group", true, "All looks good.");
        var result = formatter.format(cmd, List.of(groupResult));
        assertThat(result).contains("Security Group");
        assertThat(result).contains("✅");
        assertThat(result).contains("<details>");
    }

    @Test
    void format_failedSection_showsErrorIcon() {
        var cmd = buildCommand("feat", "main", null, null);
        var groupResult = new GroupReviewResult(0, "Auth Module", false, "Error occurred.");
        var result = formatter.format(cmd, List.of(groupResult));
        assertThat(result).contains("❌");
        assertThat(result).contains("Auth Module");
    }

    @Test
    void format_escapesMaliciousTitle() {
        var cmd = buildCommand("feat", "main", null, "<script>alert('xss')</script>");
        var result = formatter.format(cmd, List.of());
        assertThat(result).doesNotContain("<script>");
        assertThat(result).contains("&lt;script&gt;");
    }

    @Test
    void format_noGroupName_fallsBackToIndex() {
        var cmd = buildCommand("feat", "main", null, null);
        var groupResult = new GroupReviewResult(2, "", true, "Review text.");
        var result = formatter.format(cmd, List.of(groupResult));
        assertThat(result).contains("Group #3");
    }

    @Test
    void format_multipleGroups_allRendered() {
        var cmd = buildCommand("feat", "main", null, null);
        var g1 = new GroupReviewResult(0, "Group A", true, "OK");
        var g2 = new GroupReviewResult(1, "Group B", false, "Fail");
        var result = formatter.format(cmd, List.of(g1, g2));
        assertThat(result).contains("Group A").contains("Group B");
        assertThat(result).contains("2 group(s)");
        assertThat(result).contains("1 error(s)");
    }
}
