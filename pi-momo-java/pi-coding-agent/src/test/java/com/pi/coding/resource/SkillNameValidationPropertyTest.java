package com.pi.coding.resource;

import net.jqwik.api.*;
import net.jqwik.api.constraints.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property test: Skill Name Validation
 * 
 * Validates: Requirement 17.3
 * Skills system only accepts skill names that conform to naming rules.
 */
class SkillNameValidationPropertyTest {

    @Property(tries = 100)
    void validSkillNamesAreAccepted(
        @ForAll("validSkillNames") String skillName
    ) throws IOException {
        // Create a temporary skill directory
        Path tempDir = Files.createTempDirectory("skill-test-");
        Path skillDir = tempDir.resolve(skillName);
        Files.createDirectories(skillDir);
        
        Path skillFile = skillDir.resolve("SKILL.md");
        String content = """
            ---
            name: %s
            description: A test skill for validation
            ---
            
            Test content
            """.formatted(skillName);
        Files.writeString(skillFile, content);
        
        try {
            LoadSkillsResult result = Skills.loadSkillsFromDir(
                new LoadSkillsFromDirOptions(tempDir.toString(), "test")
            );
            
            // Should load successfully with no name validation errors
            assertThat(result.skills()).hasSize(1);
            assertThat(result.skills().get(0).name()).isEqualTo(skillName);
            
            // Check that there are no name-related validation errors
            List<ResourceDiagnostic> nameErrors = result.diagnostics().stream()
                .filter(d -> d.message().contains("name") && !d.message().contains("description"))
                .toList();
            assertThat(nameErrors).isEmpty();
            
        } finally {
            // Cleanup
            deleteRecursively(tempDir);
        }
    }

    @Property(tries = 100)
    void invalidSkillNamesAreRejected(
        @ForAll("invalidSkillNames") String skillName
    ) throws IOException {
        Assume.that(skillName != null && !skillName.isEmpty());
        
        // Create a temporary skill directory
        Path tempDir = Files.createTempDirectory("skill-test-");
        Path skillDir = tempDir.resolve("valid-dir-name");
        Files.createDirectories(skillDir);
        
        Path skillFile = skillDir.resolve("SKILL.md");
        String content = """
            ---
            name: %s
            description: A test skill for validation
            ---
            
            Test content
            """.formatted(skillName);
        Files.writeString(skillFile, content);
        
        try {
            LoadSkillsResult result = Skills.loadSkillsFromDir(
                new LoadSkillsFromDirOptions(tempDir.toString(), "test")
            );
            
            // Should have validation warnings about the name
            List<ResourceDiagnostic> nameErrors = result.diagnostics().stream()
                .filter(d -> d.message().contains("name") || d.message().contains("invalid characters"))
                .toList();
            assertThat(nameErrors).isNotEmpty();
            
        } finally {
            // Cleanup
            deleteRecursively(tempDir);
        }
    }

    @Property(tries = 50)
    void skillNameMustMatchParentDirectory() throws IOException {
        String dirName = "my-skill";
        String skillName = "different-name";
        
        Path tempDir = Files.createTempDirectory("skill-test-");
        Path skillDir = tempDir.resolve(dirName);
        Files.createDirectories(skillDir);
        
        Path skillFile = skillDir.resolve("SKILL.md");
        String content = """
            ---
            name: %s
            description: A test skill
            ---
            
            Test content
            """.formatted(skillName);
        Files.writeString(skillFile, content);
        
        try {
            LoadSkillsResult result = Skills.loadSkillsFromDir(
                new LoadSkillsFromDirOptions(tempDir.toString(), "test")
            );
            
            // Should have a warning about name not matching directory
            List<ResourceDiagnostic> mismatchErrors = result.diagnostics().stream()
                .filter(d -> d.message().contains("does not match parent directory"))
                .toList();
            assertThat(mismatchErrors).isNotEmpty();
            
        } finally {
            deleteRecursively(tempDir);
        }
    }

    @Property(tries = 50)
    void skillNameCannotStartOrEndWithHyphen(
        @ForAll @AlphaChars @StringLength(min = 1, max = 10) String middle
    ) throws IOException {
        String skillName = "-" + middle.toLowerCase() + "-";
        
        Path tempDir = Files.createTempDirectory("skill-test-");
        Path skillDir = tempDir.resolve(skillName);
        Files.createDirectories(skillDir);
        
        Path skillFile = skillDir.resolve("SKILL.md");
        String content = """
            ---
            name: %s
            description: A test skill
            ---
            
            Test content
            """.formatted(skillName);
        Files.writeString(skillFile, content);
        
        try {
            LoadSkillsResult result = Skills.loadSkillsFromDir(
                new LoadSkillsFromDirOptions(tempDir.toString(), "test")
            );
            
            // Should have a warning about hyphen at start/end
            List<ResourceDiagnostic> hyphenErrors = result.diagnostics().stream()
                .filter(d -> d.message().contains("must not start or end with a hyphen"))
                .toList();
            assertThat(hyphenErrors).isNotEmpty();
            
        } finally {
            deleteRecursively(tempDir);
        }
    }

    @Property(tries = 50)
    void skillNameCannotContainConsecutiveHyphens(
        @ForAll @AlphaChars @StringLength(min = 1, max = 10) String part1,
        @ForAll @AlphaChars @StringLength(min = 1, max = 10) String part2
    ) throws IOException {
        String skillName = part1.toLowerCase() + "--" + part2.toLowerCase();
        
        Path tempDir = Files.createTempDirectory("skill-test-");
        Path skillDir = tempDir.resolve(skillName);
        Files.createDirectories(skillDir);
        
        Path skillFile = skillDir.resolve("SKILL.md");
        String content = """
            ---
            name: %s
            description: A test skill
            ---
            
            Test content
            """.formatted(skillName);
        Files.writeString(skillFile, content);
        
        try {
            LoadSkillsResult result = Skills.loadSkillsFromDir(
                new LoadSkillsFromDirOptions(tempDir.toString(), "test")
            );
            
            // Should have a warning about consecutive hyphens
            List<ResourceDiagnostic> consecutiveErrors = result.diagnostics().stream()
                .filter(d -> d.message().contains("must not contain consecutive hyphens"))
                .toList();
            assertThat(consecutiveErrors).isNotEmpty();
            
        } finally {
            deleteRecursively(tempDir);
        }
    }

    @Property(tries = 30)
    void skillNameMustBeLowercaseAlphanumericAndHyphens() throws IOException {
        String skillName = "MySkill_123";
        
        Path tempDir = Files.createTempDirectory("skill-test-");
        Path skillDir = tempDir.resolve(skillName);
        Files.createDirectories(skillDir);
        
        Path skillFile = skillDir.resolve("SKILL.md");
        String content = """
            ---
            name: %s
            description: A test skill
            ---
            
            Test content
            """.formatted(skillName);
        Files.writeString(skillFile, content);
        
        try {
            LoadSkillsResult result = Skills.loadSkillsFromDir(
                new LoadSkillsFromDirOptions(tempDir.toString(), "test")
            );
            
            // Should have a warning about invalid characters
            List<ResourceDiagnostic> charErrors = result.diagnostics().stream()
                .filter(d -> d.message().contains("invalid characters"))
                .toList();
            assertThat(charErrors).isNotEmpty();
            
        } finally {
            deleteRecursively(tempDir);
        }
    }

    @Property(tries = 30)
    void skillNameCannotExceedMaxLength() throws IOException {
        // Create a name longer than MAX_NAME_LENGTH (64)
        String skillName = "a".repeat(65);
        
        Path tempDir = Files.createTempDirectory("skill-test-");
        Path skillDir = tempDir.resolve(skillName);
        Files.createDirectories(skillDir);
        
        Path skillFile = skillDir.resolve("SKILL.md");
        String content = """
            ---
            name: %s
            description: A test skill
            ---
            
            Test content
            """.formatted(skillName);
        Files.writeString(skillFile, content);
        
        try {
            LoadSkillsResult result = Skills.loadSkillsFromDir(
                new LoadSkillsFromDirOptions(tempDir.toString(), "test")
            );
            
            // Should have a warning about exceeding max length
            List<ResourceDiagnostic> lengthErrors = result.diagnostics().stream()
                .filter(d -> d.message().contains("exceeds") && d.message().contains("characters"))
                .toList();
            assertThat(lengthErrors).isNotEmpty();
            
        } finally {
            deleteRecursively(tempDir);
        }
    }

    // Arbitraries

    @Provide
    Arbitrary<String> validSkillNames() {
        return Arbitraries.strings()
            .withCharRange('a', 'z')
            .ofMinLength(1)
            .ofMaxLength(20)
            .map(s -> s.isEmpty() ? "a" : s)
            .filter(s -> !s.startsWith("-") && !s.endsWith("-"))
            .filter(s -> !s.contains("--"));
    }

    @Provide
    Arbitrary<String> invalidSkillNames() {
        return Arbitraries.oneOf(
            // Contains uppercase
            Arbitraries.strings().withCharRange('A', 'Z').ofMinLength(1).ofMaxLength(10),
            // Contains underscore
            Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(10).map(s -> s + "_test"),
            // Contains space
            Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(10).map(s -> s + " test"),
            // Starts with hyphen
            Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(10).map(s -> "-" + s),
            // Ends with hyphen
            Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(10).map(s -> s + "-"),
            // Contains consecutive hyphens
            Arbitraries.strings().alpha().ofMinLength(1).ofMaxLength(10).map(s -> s + "--test")
        );
    }

    // Helper methods

    private void deleteRecursively(Path path) throws IOException {
        if (Files.isDirectory(path)) {
            try (var stream = Files.list(path)) {
                stream.forEach(child -> {
                    try {
                        deleteRecursively(child);
                    } catch (IOException e) {
                        // Ignore
                    }
                });
            }
        }
        Files.deleteIfExists(path);
    }
}
