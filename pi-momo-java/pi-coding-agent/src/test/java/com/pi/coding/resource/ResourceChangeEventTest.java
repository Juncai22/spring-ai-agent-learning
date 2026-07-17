package com.pi.coding.resource;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for {@link ResourceChangeEvent}.
 */
class ResourceChangeEventTest {

    @Test
    void shouldCreateEventWithAllFields() {
        // Given
        LoadSkillsResult skillsResult = new LoadSkillsResult(
            List.of(new Skill("test-skill", "Test description", "/path/to/SKILL.md", "/path/to", "user", false)),
            List.of()
        );
        LoadPromptsResult promptsResult = new LoadPromptsResult(List.of(), List.of());
        List<ResourceDiagnostic> diagnostics = List.of(
            new ResourceDiagnostic("warning", "test warning", "/path/to/file")
        );
        long timestamp = System.currentTimeMillis();

        // When
        ResourceChangeEvent event = new ResourceChangeEvent(skillsResult, promptsResult, diagnostics, timestamp);

        // Then
        assertThat(event.skillsResult()).isEqualTo(skillsResult);
        assertThat(event.promptsResult()).isEqualTo(promptsResult);
        assertThat(event.diagnostics()).isEqualTo(diagnostics);
        assertThat(event.timestamp()).isEqualTo(timestamp);
    }

    @Test
    void shouldCreateEventWithNullFields() {
        // When
        ResourceChangeEvent event = new ResourceChangeEvent(null, null, null, 0L);

        // Then
        assertThat(event.skillsResult()).isNull();
        assertThat(event.promptsResult()).isNull();
        assertThat(event.diagnostics()).isNull();
        assertThat(event.timestamp()).isEqualTo(0L);
    }

    @Test
    void ofSkillsShouldCreateEventWithSkillsOnly() {
        // Given
        LoadSkillsResult skillsResult = new LoadSkillsResult(
            List.of(new Skill("my-skill", "My skill description", "/path/SKILL.md", "/path", "project", false)),
            List.of(new ResourceDiagnostic("info", "loaded skill", "/path/SKILL.md"))
        );

        // When
        ResourceChangeEvent event = ResourceChangeEvent.ofSkills(skillsResult);

        // Then
        assertThat(event.skillsResult()).isEqualTo(skillsResult);
        assertThat(event.promptsResult()).isNull();
        assertThat(event.diagnostics()).isEqualTo(skillsResult.diagnostics());
        assertThat(event.timestamp()).isGreaterThan(0L);
    }

    @Test
    void ofSkillsShouldHandleNullSkillsResult() {
        // When
        ResourceChangeEvent event = ResourceChangeEvent.ofSkills(null);

        // Then
        assertThat(event.skillsResult()).isNull();
        assertThat(event.promptsResult()).isNull();
        assertThat(event.diagnostics()).isEmpty();
        assertThat(event.timestamp()).isGreaterThan(0L);
    }

    @Test
    void ofShouldCreateEventWithAllResults() {
        // Given
        LoadSkillsResult skillsResult = new LoadSkillsResult(List.of(), List.of());
        LoadPromptsResult promptsResult = new LoadPromptsResult(List.of(), List.of());
        List<ResourceDiagnostic> diagnostics = List.of(
            new ResourceDiagnostic("error", "test error", "/path")
        );

        // When
        ResourceChangeEvent event = ResourceChangeEvent.of(skillsResult, promptsResult, diagnostics);

        // Then
        assertThat(event.skillsResult()).isEqualTo(skillsResult);
        assertThat(event.promptsResult()).isEqualTo(promptsResult);
        assertThat(event.diagnostics()).isEqualTo(diagnostics);
        assertThat(event.timestamp()).isGreaterThan(0L);
    }

    @Test
    void ofShouldHandleNullDiagnostics() {
        // Given
        LoadSkillsResult skillsResult = new LoadSkillsResult(List.of(), List.of());
        LoadPromptsResult promptsResult = new LoadPromptsResult(List.of(), List.of());

        // When
        ResourceChangeEvent event = ResourceChangeEvent.of(skillsResult, promptsResult, null);

        // Then
        assertThat(event.diagnostics()).isEmpty();
    }

    @Test
    void recordEqualityShouldWork() {
        // Given
        long timestamp = 1234567890L;
        LoadSkillsResult skillsResult = new LoadSkillsResult(List.of(), List.of());
        LoadPromptsResult promptsResult = new LoadPromptsResult(List.of(), List.of());
        List<ResourceDiagnostic> diagnostics = List.of();

        // When
        ResourceChangeEvent event1 = new ResourceChangeEvent(skillsResult, promptsResult, diagnostics, timestamp);
        ResourceChangeEvent event2 = new ResourceChangeEvent(skillsResult, promptsResult, diagnostics, timestamp);

        // Then
        assertThat(event1).isEqualTo(event2);
        assertThat(event1.hashCode()).isEqualTo(event2.hashCode());
    }
}
