package com.pi.coding.settings;

import net.jqwik.api.*;
import net.jqwik.api.constraints.*;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property test: Settings Deep Merge
 * 
 * Validates: Requirement 16.2
 * Project settings take precedence over global settings when there is a conflict.
 */
class SettingsDeepMergePropertyTest {

    @Property(tries = 100)
    void projectSettingsTakePrecedenceOverGlobal(
        @ForAll @StringLength(min = 1, max = 30) String globalProvider,
        @ForAll @StringLength(min = 1, max = 30) String projectProvider
    ) {
        SettingsData global = new SettingsData(
            globalProvider, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null,
            null, null, null, null
        );
        SettingsData project = new SettingsData(
            projectProvider, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null,
            null, null, null, null
        );

        SettingsData merged = SettingsManager.deepMerge(global, project);

        assertThat(merged.defaultProvider()).isEqualTo(projectProvider);
    }

    @Property(tries = 100)
    void globalSettingsUsedWhenProjectFieldIsNull(
        @ForAll @StringLength(min = 1, max = 30) String globalModel,
        @ForAll @StringLength(min = 1, max = 30) String projectProvider
    ) {
        SettingsData global = new SettingsData(
            null, globalModel, null, null, null, null, null,
            null, null, null, null, null, null, null, null,
            null, null, null, null
        );
        SettingsData project = new SettingsData(
            projectProvider, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null,
            null, null, null, null
        );

        SettingsData merged = SettingsManager.deepMerge(global, project);

        // Global model should be preserved since project doesn't override it
        assertThat(merged.defaultModel()).isEqualTo(globalModel);
        // Project provider should be used
        assertThat(merged.defaultProvider()).isEqualTo(projectProvider);
    }

    @Property(tries = 50)
    void nestedCompactionSettingsMergedCorrectly(
        @ForAll @IntRange(min = 1000, max = 100000) int globalReserve,
        @ForAll @IntRange(min = 1000, max = 100000) int projectReserve,
        @ForAll boolean globalEnabled
    ) {
        CompactionSettings globalCompaction = new CompactionSettings(globalEnabled, globalReserve, 16000);
        CompactionSettings projectCompaction = new CompactionSettings(null, projectReserve, null);

        SettingsData global = new SettingsData(
            null, null, null, null, null, null, null,
            null, null, null, null, globalCompaction, null, null, null,
            null, null, null, null
        );
        SettingsData project = new SettingsData(
            null, null, null, null, null, null, null,
            null, null, null, null, projectCompaction, null, null, null,
            null, null, null, null
        );

        SettingsData merged = SettingsManager.deepMerge(global, project);

        assertThat(merged.compaction()).isNotNull();
        // Project reserve takes precedence
        assertThat(merged.compaction().reserveTokens()).isEqualTo(projectReserve);
        // Global enabled is preserved (project has null)
        assertThat(merged.compaction().enabled()).isEqualTo(globalEnabled);
        // Global keepRecentTokens is preserved (project has null)
        assertThat(merged.compaction().keepRecentTokens()).isEqualTo(16000);
    }

    @Property(tries = 50)
    void applyUpdateOnlyChangesNonNullFields(
        @ForAll @StringLength(min = 1, max = 30) String originalProvider,
        @ForAll @StringLength(min = 1, max = 30) String originalModel,
        @ForAll @StringLength(min = 1, max = 30) String newProvider
    ) {
        SettingsData existing = new SettingsData(
            originalProvider, originalModel, null, null, null, null, null,
            null, null, null, null, null, null, null, null,
            null, null, null, null
        );

        SettingsUpdate update = SettingsUpdate.builder()
            .defaultProvider(newProvider)
            .build();

        SettingsData updated = SettingsManager.applyUpdate(existing, update);

        // Provider should be updated
        assertThat(updated.defaultProvider()).isEqualTo(newProvider);
        // Model should be unchanged
        assertThat(updated.defaultModel()).isEqualTo(originalModel);
    }

    @Property(tries = 50)
    void mergeWithEmptyProjectPreservesGlobal(
        @ForAll @StringLength(min = 1, max = 30) String globalProvider,
        @ForAll @StringLength(min = 1, max = 30) String globalModel,
        @ForAll @StringLength(min = 1, max = 30) String globalTheme
    ) {
        SettingsData global = new SettingsData(
            globalProvider, globalModel, null, null, null, null, globalTheme,
            null, null, null, null, null, null, null, null,
            null, null, null, null
        );

        SettingsData merged = SettingsManager.deepMerge(global, SettingsData.EMPTY);

        assertThat(merged.defaultProvider()).isEqualTo(globalProvider);
        assertThat(merged.defaultModel()).isEqualTo(globalModel);
        assertThat(merged.theme()).isEqualTo(globalTheme);
    }

    @Property(tries = 50)
    void mergeWithEmptyGlobalUsesProject(
        @ForAll @StringLength(min = 1, max = 30) String projectProvider,
        @ForAll List<@StringLength(min = 1, max = 20) String> skillPaths
    ) {
        SettingsData project = new SettingsData(
            projectProvider, null, null, null, null, null, null,
            null, null, null, null, null, null, null, null,
            null, skillPaths, null, null
        );

        SettingsData merged = SettingsManager.deepMerge(SettingsData.EMPTY, project);

        assertThat(merged.defaultProvider()).isEqualTo(projectProvider);
        assertThat(merged.skillPaths()).isEqualTo(skillPaths);
    }

    @Property(tries = 30)
    void inMemorySettingsManagerUsesDefaults() {
        SettingsManager manager = SettingsManager.inMemory();

        assertThat(manager.getDefaultProvider()).isNotNull();
        assertThat(manager.getDefaultModel()).isNotNull();
        assertThat(manager.getDefaultThinkingLevel()).isNotNull();
        assertThat(manager.getCompactionSettings()).isNotNull();
        assertThat(manager.getRetrySettings()).isNotNull();
    }
}
