package com.pi.coding.resource;

import net.jqwik.api.*;
import net.jqwik.api.constraints.*;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property test: Prompt Template Argument Expansion
 * 
 * Validates: Requirement 18.5, 18.6, 18.7
 * Verifies argument expansion correctly handles various syntax.
 */
class PromptTemplateArgumentExpansionPropertyTest {

    @Property(tries = 100)
    void positionalArgumentsAreSubstituted(
        @ForAll @Size(min = 1, max = 10) List<@AlphaChars @StringLength(min = 1, max = 20) String> args
    ) {
        // Create template with positional arguments
        StringBuilder template = new StringBuilder();
        for (int i = 1; i <= args.size(); i++) {
            template.append("$").append(i).append(" ");
        }
        
        String result = PromptTemplates.substituteArgs(template.toString(), args);
        
        // Should contain all arguments
        for (String arg : args) {
            assertThat(result).contains(arg);
        }
    }

    @Property(tries = 100)
    void allArgumentsPlaceholderIsSubstituted(
        @ForAll @Size(min = 1, max = 10) List<@AlphaChars @StringLength(min = 1, max = 20) String> args
    ) {
        String template = "All args: $@";
        
        String result = PromptTemplates.substituteArgs(template, args);
        
        String expected = "All args: " + String.join(" ", args);
        assertThat(result).isEqualTo(expected);
    }

    @Property(tries = 100)
    void argumentsKeywordIsSubstituted(
        @ForAll @Size(min = 1, max = 10) List<@AlphaChars @StringLength(min = 1, max = 20) String> args
    ) {
        String template = "All args: $ARGUMENTS";
        
        String result = PromptTemplates.substituteArgs(template, args);
        
        String expected = "All args: " + String.join(" ", args);
        assertThat(result).isEqualTo(expected);
    }

    @Property(tries = 100)
    void sliceFromNthArgumentWorks(
        @ForAll @Size(min = 3, max = 10) List<@AlphaChars @StringLength(min = 1, max = 20) String> args,
        @ForAll @IntRange(min = 1, max = 3) int start
    ) {
        String template = "Slice: ${@:" + start + "}";
        
        String result = PromptTemplates.substituteArgs(template, args);
        
        // Convert to 0-indexed
        int startIndex = start - 1;
        if (startIndex < args.size()) {
            List<String> sliced = args.subList(startIndex, args.size());
            String expected = "Slice: " + String.join(" ", sliced);
            assertThat(result).isEqualTo(expected);
        }
    }

    @Property(tries = 100)
    void sliceWithLengthWorks(
        @ForAll @Size(min = 5, max = 10) List<@AlphaChars @StringLength(min = 1, max = 20) String> args,
        @ForAll @IntRange(min = 1, max = 3) int start,
        @ForAll @IntRange(min = 1, max = 3) int length
    ) {
        String template = "Slice: ${@:" + start + ":" + length + "}";
        
        String result = PromptTemplates.substituteArgs(template, args);
        
        // Convert to 0-indexed
        int startIndex = start - 1;
        if (startIndex < args.size()) {
            int endIndex = Math.min(startIndex + length, args.size());
            List<String> sliced = args.subList(startIndex, endIndex);
            String expected = "Slice: " + String.join(" ", sliced);
            assertThat(result).isEqualTo(expected);
        }
    }

    @Property(tries = 50)
    void parseCommandArgsRespectsQuotes() {
        String argsString = "arg1 \"arg with spaces\" 'another arg' arg4";
        
        List<String> args = PromptTemplates.parseCommandArgs(argsString);
        
        assertThat(args).hasSize(4);
        assertThat(args.get(0)).isEqualTo("arg1");
        assertThat(args.get(1)).isEqualTo("arg with spaces");
        assertThat(args.get(2)).isEqualTo("another arg");
        assertThat(args.get(3)).isEqualTo("arg4");
    }

    @Property(tries = 50)
    void parseCommandArgsHandlesEmptyString() {
        List<String> args = PromptTemplates.parseCommandArgs("");
        
        assertThat(args).isEmpty();
    }

    @Property(tries = 50)
    void parseCommandArgsHandlesMultipleSpaces(
        @ForAll @Size(min = 1, max = 5) List<@AlphaChars @StringLength(min = 1, max = 10) String> words
    ) {
        String argsString = String.join("   ", words); // Multiple spaces
        
        List<String> args = PromptTemplates.parseCommandArgs(argsString);
        
        assertThat(args).isEqualTo(words);
    }

    @Property(tries = 50)
    void expandPromptTemplateWithNoArgsWorks() {
        PromptTemplate template = new PromptTemplate(
            "test",
            "Test template",
            "Hello, world!",
            "test",
            "/test/path.md"
        );
        
        String result = PromptTemplates.expandPromptTemplate(
            "/test",
            List.of(template)
        );
        
        assertThat(result).isEqualTo("Hello, world!");
    }

    @Property(tries = 50)
    void expandPromptTemplateWithArgsWorks() {
        PromptTemplate template = new PromptTemplate(
            "greet",
            "Greeting template",
            "Hello, $1! You are $2.",
            "test",
            "/test/path.md"
        );
        
        String result = PromptTemplates.expandPromptTemplate(
            "/greet Alice awesome",
            List.of(template)
        );
        
        assertThat(result).isEqualTo("Hello, Alice! You are awesome.");
    }

    @Property(tries = 50)
    void expandPromptTemplateWithQuotedArgsWorks() {
        PromptTemplate template = new PromptTemplate(
            "say",
            "Say template",
            "Message: $1",
            "test",
            "/test/path.md"
        );
        
        String result = PromptTemplates.expandPromptTemplate(
            "/say \"Hello, world!\"",
            List.of(template)
        );
        
        assertThat(result).isEqualTo("Message: Hello, world!");
    }

    @Property(tries = 50)
    void expandPromptTemplateReturnsOriginalIfNotTemplate() {
        String text = "This is not a template";
        
        String result = PromptTemplates.expandPromptTemplate(text, List.of());
        
        assertThat(result).isEqualTo(text);
    }

    @Property(tries = 50)
    void expandPromptTemplateReturnsOriginalIfTemplateNotFound() {
        String text = "/nonexistent arg1 arg2";
        
        String result = PromptTemplates.expandPromptTemplate(text, List.of());
        
        assertThat(result).isEqualTo(text);
    }

    @Property(tries = 50)
    void substituteArgsHandlesEmptyArgs() {
        String template = "No args: $1 $2 $@";
        
        String result = PromptTemplates.substituteArgs(template, List.of());
        
        assertThat(result).isEqualTo("No args:   ");
    }

    @Property(tries = 50)
    void substituteArgsHandlesOutOfBoundsPositional() {
        String template = "Args: $1 $2 $3 $4 $5";
        List<String> args = List.of("a", "b");
        
        String result = PromptTemplates.substituteArgs(template, args);
        
        assertThat(result).isEqualTo("Args: a b   ");
    }

    @Property(tries = 50)
    void substituteArgsDoesNotRecursivelySubstitute() {
        // Argument values containing patterns should NOT be recursively substituted
        String template = "Value: $1";
        List<String> args = List.of("$2");
        
        String result = PromptTemplates.substituteArgs(template, args);
        
        assertThat(result).isEqualTo("Value: $2");
    }

    @Property(tries = 50)
    void substituteArgsHandlesSpecialCharacters(
        @ForAll @StringLength(min = 1, max = 20) String arg
    ) {
        String template = "Value: $1";
        List<String> args = List.of(arg);
        
        String result = PromptTemplates.substituteArgs(template, args);
        
        assertThat(result).isEqualTo("Value: " + arg);
    }

    @Property(tries = 30)
    void sliceWithZeroStartTreatedAsOne() {
        String template = "${@:0}";
        List<String> args = List.of("a", "b", "c");
        
        String result = PromptTemplates.substituteArgs(template, args);
        
        // 0 should be treated as 1 (bash convention)
        assertThat(result).isEqualTo("a b c");
    }

    @Property(tries = 30)
    void multipleSubstitutionsInSameTemplate(
        @ForAll @Size(min = 3, max = 5) List<@AlphaChars @StringLength(min = 1, max = 10) String> args
    ) {
        String template = "First: $1, Second: $2, All: $@, From 2nd: ${@:2}";
        
        String result = PromptTemplates.substituteArgs(template, args);
        
        assertThat(result).contains(args.get(0));
        assertThat(result).contains(args.get(1));
        assertThat(result).contains(String.join(" ", args));
    }
}
