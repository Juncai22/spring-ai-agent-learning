package com.pi.coding.tool;

import net.jqwik.api.*;
import net.jqwik.api.constraints.*;
import org.assertj.core.api.Assertions;

import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property-based tests for Truncation utility.
 * <p>
 * Property 7: Tool Truncation Bounds
 * Validates: Requirement 25.1, 25.2, 25.4
 */
class TruncationBoundsPropertyTest {

    @Property
    @Label("truncateHead output never exceeds maxLines")
    void truncateHeadNeverExceedsMaxLines(
            @ForAll @StringLength(min = 0, max = 10000) String content,
            @ForAll @IntRange(min = 1, max = 100) int maxLines) {
        
        TruncationOptions options = new TruncationOptions(maxLines, TruncationOptions.DEFAULT_MAX_BYTES);
        TruncationResult result = Truncation.truncateHead(content, options);
        
        assertThat(result.outputLines()).isLessThanOrEqualTo(maxLines);
    }

    @Property
    @Label("truncateHead output never exceeds maxBytes")
    void truncateHeadNeverExceedsMaxBytes(
            @ForAll @StringLength(min = 0, max = 10000) String content,
            @ForAll @IntRange(min = 1, max = 5000) int maxBytes) {
        
        TruncationOptions options = new TruncationOptions(TruncationOptions.DEFAULT_MAX_LINES, maxBytes);
        TruncationResult result = Truncation.truncateHead(content, options);
        
        assertThat(result.outputBytes()).isLessThanOrEqualTo(maxBytes);
    }

    @Property
    @Label("truncateTail output never exceeds maxLines")
    void truncateTailNeverExceedsMaxLines(
            @ForAll @StringLength(min = 0, max = 10000) String content,
            @ForAll @IntRange(min = 1, max = 100) int maxLines) {
        
        TruncationOptions options = new TruncationOptions(maxLines, TruncationOptions.DEFAULT_MAX_BYTES);
        TruncationResult result = Truncation.truncateTail(content, options);
        
        assertThat(result.outputLines()).isLessThanOrEqualTo(maxLines);
    }

    @Property
    @Label("truncateTail output never exceeds maxBytes")
    void truncateTailNeverExceedsMaxBytes(
            @ForAll @StringLength(min = 0, max = 10000) String content,
            @ForAll @IntRange(min = 1, max = 5000) int maxBytes) {
        
        TruncationOptions options = new TruncationOptions(TruncationOptions.DEFAULT_MAX_LINES, maxBytes);
        TruncationResult result = Truncation.truncateTail(content, options);
        
        assertThat(result.outputBytes()).isLessThanOrEqualTo(maxBytes);
    }

    @Property
    @Label("truncateHead preserves content when within limits")
    void truncateHeadPreservesContentWithinLimits(
            @ForAll("smallContent") String content) {
        
        TruncationOptions options = new TruncationOptions(1000, 100000);
        TruncationResult result = Truncation.truncateHead(content, options);
        
        if (!result.truncated()) {
            assertThat(result.content()).isEqualTo(content);
        }
    }

    @Property
    @Label("truncateTail preserves content when within limits")
    void truncateTailPreservesContentWithinLimits(
            @ForAll("smallContent") String content) {
        
        TruncationOptions options = new TruncationOptions(1000, 100000);
        TruncationResult result = Truncation.truncateTail(content, options);
        
        if (!result.truncated()) {
            assertThat(result.content()).isEqualTo(content);
        }
    }

    @Property
    @Label("truncateHead output is prefix of original when truncated by lines")
    void truncateHeadOutputIsPrefixWhenTruncatedByLines(
            @ForAll("multiLineContent") String content,
            @ForAll @IntRange(min = 1, max = 10) int maxLines) {
        
        TruncationOptions options = new TruncationOptions(maxLines, Integer.MAX_VALUE);
        TruncationResult result = Truncation.truncateHead(content, options);
        
        if (result.truncated() && "lines".equals(result.truncatedBy())) {
            assertThat(content).startsWith(result.content());
        }
    }

    @Property
    @Label("truncateTail output is suffix of original when truncated by lines")
    void truncateTailOutputIsSuffixWhenTruncatedByLines(
            @ForAll("multiLineContent") String content,
            @ForAll @IntRange(min = 1, max = 10) int maxLines) {
        
        TruncationOptions options = new TruncationOptions(maxLines, Integer.MAX_VALUE);
        TruncationResult result = Truncation.truncateTail(content, options);
        
        if (result.truncated() && "lines".equals(result.truncatedBy()) && !result.lastLinePartial()) {
            assertThat(content).endsWith(result.content());
        }
    }

    @Property
    @Label("totalLines and totalBytes are accurate")
    void totalLinesAndBytesAreAccurate(
            @ForAll @StringLength(min = 0, max = 5000) String content) {
        
        TruncationResult headResult = Truncation.truncateHead(content);
        TruncationResult tailResult = Truncation.truncateTail(content);
        
        int expectedLines = content.split("\n", -1).length;
        long expectedBytes = content.getBytes(StandardCharsets.UTF_8).length;
        
        assertThat(headResult.totalLines()).isEqualTo(expectedLines);
        assertThat(headResult.totalBytes()).isEqualTo(expectedBytes);
        assertThat(tailResult.totalLines()).isEqualTo(expectedLines);
        assertThat(tailResult.totalBytes()).isEqualTo(expectedBytes);
    }

    @Property
    @Label("truncateLine respects maxLength")
    void truncateLineRespectsMaxLength(
            @ForAll @StringLength(min = 0, max = 1000) String line,
            @ForAll @IntRange(min = 1, max = 100) int maxLength) {
        
        String result = Truncation.truncateLine(line, maxLength);
        
        assertThat(result.length()).isLessThanOrEqualTo(maxLength);
    }

    @Property
    @Label("truncateLine preserves short lines")
    void truncateLinePreservesShortLines(
            @ForAll @StringLength(min = 0, max = 50) String line) {
        
        String result = Truncation.truncateLine(line, 100);
        
        assertThat(result).isEqualTo(line);
    }

    @Property
    @Label("formatSize returns non-empty string for any bytes")
    void formatSizeReturnsNonEmptyString(
            @ForAll @LongRange(min = 0, max = Long.MAX_VALUE / 2) long bytes) {
        
        String result = Truncation.formatSize(bytes);
        
        assertThat(result).isNotEmpty();
        assertThat(result).matches("\\d+(\\.\\d)?[BKM]B?");
    }

    @Property
    @Label("truncateHead handles UTF-8 multi-byte characters correctly")
    void truncateHeadHandlesUtf8Correctly(
            @ForAll("utf8Content") String content,
            @ForAll @IntRange(min = 10, max = 500) int maxBytes) {
        
        TruncationOptions options = new TruncationOptions(TruncationOptions.DEFAULT_MAX_LINES, maxBytes);
        TruncationResult result = Truncation.truncateHead(content, options);
        
        // Output should be valid UTF-8 (no exception when getting bytes)
        byte[] outputBytes = result.content().getBytes(StandardCharsets.UTF_8);
        assertThat(outputBytes.length).isLessThanOrEqualTo(maxBytes);
        
        // Should be able to decode back to string
        String decoded = new String(outputBytes, StandardCharsets.UTF_8);
        assertThat(decoded).isEqualTo(result.content());
    }

    @Property
    @Label("truncateTail handles UTF-8 multi-byte characters correctly")
    void truncateTailHandlesUtf8Correctly(
            @ForAll("utf8Content") String content,
            @ForAll @IntRange(min = 10, max = 500) int maxBytes) {
        
        TruncationOptions options = new TruncationOptions(TruncationOptions.DEFAULT_MAX_LINES, maxBytes);
        TruncationResult result = Truncation.truncateTail(content, options);
        
        // Output should be valid UTF-8 (no exception when getting bytes)
        byte[] outputBytes = result.content().getBytes(StandardCharsets.UTF_8);
        assertThat(outputBytes.length).isLessThanOrEqualTo(maxBytes);
        
        // Should be able to decode back to string
        String decoded = new String(outputBytes, StandardCharsets.UTF_8);
        assertThat(decoded).isEqualTo(result.content());
    }

    @Provide
    Arbitrary<String> smallContent() {
        return Arbitraries.strings()
            .withCharRange('a', 'z')
            .ofMinLength(0)
            .ofMaxLength(100)
            .map(s -> s.replace("", "\n").substring(1)); // Add some newlines
    }

    @Provide
    Arbitrary<String> multiLineContent() {
        return Arbitraries.integers().between(5, 50)
            .flatMap(lineCount -> 
                Arbitraries.strings()
                    .withCharRange('a', 'z')
                    .ofMinLength(1)
                    .ofMaxLength(50)
                    .list()
                    .ofSize(lineCount)
                    .map(lines -> String.join("\n", lines))
            );
    }

    @Provide
    Arbitrary<String> utf8Content() {
        // Generate content with various UTF-8 characters
        return Arbitraries.of(
            "Hello 世界",
            "日本語テスト\n改行あり",
            "Emoji: 🎉🚀💻\nMore text",
            "Mixed: abc日本語def\n中文测试",
            "Cyrillic: Привет мир\nGreek: Γειά σου",
            "Arabic: مرحبا\nHebrew: שלום",
            "Simple ASCII\nNo special chars",
            "Line1\nLine2\nLine3\nLine4\nLine5"
        );
    }
}
