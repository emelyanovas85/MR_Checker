package ru.cbr.bugbusters.gitwebhookhandler.review.domain;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

/**
 * Одна группа рефакторинга из JSON-ответа LLM на первом этапе (группировка).
 *
 * <p>Соответствует полю {@code refactoring_groups[*]} из промпта группировки.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record RefactoringGroup(

        @JsonAlias({"group_name", "groupName"})
        String groupName,

        @JsonAlias({"reason"})
        String reason,

        @JsonAlias({"files"})
        List<GroupFile> files,

        @JsonAlias({"refactoring_goal", "refactoringGoal"})
        String refactoringGoal,

        @JsonAlias({"priority"})
        String priority
) {

    /**
     * Краткое описание файла внутри группы.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record GroupFile(

            @JsonAlias({"path"})
            String path,

            @JsonAlias({"status"})
            String status,

            @JsonAlias({"file_type", "fileType"})
            String fileType,

            @JsonAlias({"responsibility"})
            String responsibility
    ) {}

    /**
     * Возвращает список путей файлов группы (для использования в промпте ревью).
     */
    public List<String> filePaths() {
        if (files == null) return List.of();
        return files.stream()
                .filter(f -> f.path() != null)
                .map(GroupFile::path)
                .toList();
    }
}
