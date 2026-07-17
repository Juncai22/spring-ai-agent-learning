package com.pi.coding.util;

import net.jqwik.api.*;
import net.jqwik.api.constraints.*;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property test: Frontmatter Parsing Consistency
 * 
 * Validates: Requirement 17.2
 * parseFrontmatter followed by stripFrontmatter returns the correct content.
 */
class FrontmatterParsingConsistencyPropertyTest {

    @Property(tries = 100)
    void parseFrontmatterThenStripReturnsOriginalBody(
        @ForAll @StringLength(min = 0, max = 500) String body
    ) {
        String content = "---\nkey: value\n---\n" + body;
        
        FrontmatterResult parsed = Frontmatter.parseFrontmatter(content);
        String stripped = Frontmatter.stripFrontmatter(content);
        
        assertThat(parsed.content()).isEqualTo(body);
        assertThat(stripped).isEqualTo(body);
    }

    @Property(tries = 100)
    void stripFrontmatterWithoutFrontmatterReturnsOriginal(
        @ForAll @StringLength(min = 0, max = 500) String content
    ) {
        Assume.that(!content.startsWith("---"));
        
        String stripped = Frontmatter.stripFrontmatter(content);
        
        assertThat(stripped).isEqualTo(content);
    }

    @Property(tries = 100)
    void parseFrontmatterWithoutFrontmatterReturnsEmptyData(
        @ForAll @StringLength(min = 0, max = 500) String content
    ) {
        Assume.that(!content.startsWith("---"));
        
        FrontmatterResult result = Frontmatter.parseFrontmatter(content);
        
        assertThat(result.data()).isEmpty();
        assertThat(result.content()).isEqualTo(content);
    }

    @Property(tries = 50)
    void parseFrontmatterExtractsYamlData() {
        String content = "---\nname: test-skill\ndescription: A test skill\nenabled: true\n---\nBody content here";
        
        FrontmatterResult result = Frontmatter.parseFrontmatter(content);
        
        assertThat(result.data()).containsKey("name");
        assertThat(result.data()).containsKey("description");
        assertThat(result.data()).containsKey("enabled");
        assertThat(result.getString("name")).isEqualTo("test-skill");
        assertThat(result.getString("description")).isEqualTo("A test skill");
        assertThat(result.getBoolean("enabled")).isTrue();
        assertThat(result.content()).isEqualTo("Body content here");
    }

    @Property(tries = 50)
    void parseFrontmatterHandlesIncompleteFrontmatter(
        @ForAll @StringLength(min = 0, max = 500) String content
    ) {
        // Frontmatter without closing delimiter
        String incomplete = "---\nkey: value\n" + content;
        
        FrontmatterResult result = Frontmatter.parseFrontmatter(incomplete);
        
        // Should return empty data and original content
        assertThat(result.data()).isEmpty();
        assertThat(result.content()).isEqualTo(incomplete);
    }

    @Property(tries = 50)
    void parseFrontmatterHandlesEmptyFrontmatter() {
        String content = "---\n---\nBody content";
        
        FrontmatterResult result = Frontmatter.parseFrontmatter(content);
        
        assertThat(result.data()).isEmpty();
        assertThat(result.content()).isEqualTo("Body content");
    }

    @Property(tries = 50)
    void parseFrontmatterNormalizesLineEndings(
        @ForAll @StringLength(min = 1, max = 100) String body
    ) {
        // Test with different line endings
        String contentCRLF = "---\r\nkey: value\r\n---\r\n" + body;
        String contentLF = "---\nkey: value\n---\n" + body;
        String contentCR = "---\rkey: value\r---\r" + body;
        
        FrontmatterResult resultCRLF = Frontmatter.parseFrontmatter(contentCRLF);
        FrontmatterResult resultLF = Frontmatter.parseFrontmatter(contentLF);
        FrontmatterResult resultCR = Frontmatter.parseFrontmatter(contentCR);
        
        // All should extract the same data
        assertThat(resultCRLF.data()).containsKey("key");
        assertThat(resultLF.data()).containsKey("key");
        assertThat(resultCR.data()).containsKey("key");
    }

    @Property(tries = 30)
    void parseFrontmatterHandlesNullAndEmpty() {
        FrontmatterResult nullResult = Frontmatter.parseFrontmatter(null);
        FrontmatterResult emptyResult = Frontmatter.parseFrontmatter("");
        
        assertThat(nullResult.data()).isEmpty();
        assertThat(nullResult.content()).isEmpty();
        assertThat(emptyResult.data()).isEmpty();
        assertThat(emptyResult.content()).isEmpty();
    }

    @Property(tries = 50)
    void stripFrontmatterIsIdempotent(
        @ForAll @StringLength(min = 0, max = 500) String body
    ) {
        String content = "---\nkey: value\n---\n" + body;
        
        String stripped1 = Frontmatter.stripFrontmatter(content);
        String stripped2 = Frontmatter.stripFrontmatter(stripped1);
        
        // Stripping twice should give the same result
        assertThat(stripped1).isEqualTo(stripped2);
    }

    @Property(tries = 50)
    void parseFrontmatterHandlesInvalidYaml(
        @ForAll @StringLength(min = 1, max = 100) String invalidYaml
    ) {
        Assume.that(!invalidYaml.contains("\n---"));
        
        String content = "---\n" + invalidYaml + "\n---\nBody";
        
        FrontmatterResult result = Frontmatter.parseFrontmatter(content);
        
        // Should handle gracefully - either parse what it can or return empty
        assertThat(result).isNotNull();
        assertThat(result.content()).isNotNull();
    }
}
