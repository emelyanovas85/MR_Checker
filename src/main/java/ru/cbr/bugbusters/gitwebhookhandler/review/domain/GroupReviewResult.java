package ru.cbr.bugbusters.gitwebhookhandler.review.domain;

/**
 * Результат ревью одной группы рефакторинга.
 */
public record GroupReviewResult(
        int index,
        String groupName,
        boolean success,
        String reviewText
) {
    public static GroupReviewResult success(int index, String groupName, String reviewText) {
        return new GroupReviewResult(index, groupName, true, reviewText);
    }

    public static GroupReviewResult failure(int index, String groupName, String errorMessage) {
        return new GroupReviewResult(index, groupName, false, "Error: " + errorMessage);
    }
}
