package ru.cbr.bugbusters.gitwebhookhandler.review.service;

import org.springframework.stereotype.Service;
import ru.cbr.bugbusters.gitwebhookhandler.review.api.ReviewTriggerCommand;
import ru.cbr.bugbusters.gitwebhookhandler.review.domain.GroupReviewResult;

import java.util.List;

@Service
public class MarkdownCommentFormatter {

    public String format(ReviewTriggerCommand command, List<GroupReviewResult> results) {
        long failed = results.stream().filter(r -> !r.success()).count();
        StringBuilder sb = new StringBuilder();
        sb.append("## AI Code Review\n\n");
        sb.append("> Branch: `").append(command.sourceBranch())
                .append("` &rarr; `").append(command.targetBranch()).append("`  \n");
        if (command.lastCommit() != null) {
            sb.append("> Commit: `").append(command.lastCommit()).append("`  \n");
        }
        if (command.mrTitle() != null) {
            sb.append("> MR: ").append(escape(command.mrTitle())).append("  \n");
        }
        sb.append("> Triggered by: `").append(command.triggeredBy()).append("`\n\n");
        sb.append(failed == 0
                ? "Analysis completed successfully"
                : "Analysis completed with " + failed + " error(s)");
        sb.append(" | **").append(results.size()).append(" group(s)**\n\n---\n\n");

        for (GroupReviewResult result : results) {
            sb.append(formatSection(result)).append("\n");
        }
        return sb.toString();
    }

    private String formatSection(GroupReviewResult result) {
        String icon = result.success() ? "✅" : "❌";
        // Используем groupName из результата (содержательное имя группы из LLM)
        String title = result.groupName() != null && !result.groupName().isBlank()
                ? result.groupName()
                : "Group #" + (result.index() + 1);
        return "<details>\n<summary>"
                + icon + " <b>" + escape(title) + "</b></summary>\n\n"
                + result.reviewText()
                + "\n\n</details>\n";
    }

    private String escape(String value) {
        return value == null ? ""
                : value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }
}
