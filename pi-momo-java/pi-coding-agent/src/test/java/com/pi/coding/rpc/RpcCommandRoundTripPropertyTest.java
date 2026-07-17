package com.pi.coding.rpc;

import com.pi.ai.core.util.PiAiJson;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.StringLength;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Property 14: RPC Command Round-Trip.
 * Verifies all RpcCommand subtypes survive JSON round-trip.
 * Validates: Requirements 20.1, 20.2
 */
class RpcCommandRoundTripPropertyTest {

    @Property(tries = 30)
    void promptCmd(@ForAll @AlphaChars @StringLength(min = 1, max = 10) String id,
                   @ForAll @AlphaChars @StringLength(min = 1, max = 30) String msg) throws Exception {
        assertRoundTrip(new RpcCommand.Prompt(id, msg, null, null));
    }

    @Property(tries = 30)
    void steerCmd(@ForAll @AlphaChars @StringLength(min = 1, max = 10) String id,
                  @ForAll @AlphaChars @StringLength(min = 1, max = 30) String msg) throws Exception {
        assertRoundTrip(new RpcCommand.Steer(id, msg, null));
    }

    @Property(tries = 30)
    void followUpCmd(@ForAll @AlphaChars @StringLength(min = 1, max = 10) String id,
                     @ForAll @AlphaChars @StringLength(min = 1, max = 30) String msg) throws Exception {
        assertRoundTrip(new RpcCommand.FollowUp(id, msg, null));
    }

    @Property(tries = 20)
    void abortCmd(@ForAll @AlphaChars @StringLength(min = 1, max = 10) String id) throws Exception {
        assertRoundTrip(new RpcCommand.Abort(id));
    }

    @Property(tries = 20)
    void newSessionCmd(@ForAll @AlphaChars @StringLength(min = 1, max = 10) String id) throws Exception {
        assertRoundTrip(new RpcCommand.NewSession(id, null));
    }

    @Property(tries = 20)
    void getStateCmd(@ForAll @AlphaChars @StringLength(min = 1, max = 10) String id) throws Exception {
        assertRoundTrip(new RpcCommand.GetState(id));
    }

    @Property(tries = 30)
    void setModelCmd(@ForAll @AlphaChars @StringLength(min = 1, max = 10) String id,
                     @ForAll @AlphaChars @StringLength(min = 1, max = 20) String provider,
                     @ForAll @AlphaChars @StringLength(min = 1, max = 20) String modelId) throws Exception {
        assertRoundTrip(new RpcCommand.SetModel(id, provider, modelId));
    }

    @Property(tries = 20)
    void cycleModelCmd(@ForAll @AlphaChars @StringLength(min = 1, max = 10) String id) throws Exception {
        assertRoundTrip(new RpcCommand.CycleModel(id));
    }

    @Property(tries = 30)
    void setThinkingLevelCmd(@ForAll @AlphaChars @StringLength(min = 1, max = 10) String id,
                             @ForAll("thinkingLevels") String level) throws Exception {
        assertRoundTrip(new RpcCommand.SetThinkingLevel(id, level));
    }

    @Property(tries = 20)
    void cycleThinkingLevelCmd(@ForAll @AlphaChars @StringLength(min = 1, max = 10) String id) throws Exception {
        assertRoundTrip(new RpcCommand.CycleThinkingLevel(id));
    }

    @Property(tries = 20)
    void setSteeringModeCmd(@ForAll @AlphaChars @StringLength(min = 1, max = 10) String id,
                            @ForAll("modes") String mode) throws Exception {
        assertRoundTrip(new RpcCommand.SetSteeringMode(id, mode));
    }

    @Property(tries = 20)
    void setFollowUpModeCmd(@ForAll @AlphaChars @StringLength(min = 1, max = 10) String id,
                            @ForAll("modes") String mode) throws Exception {
        assertRoundTrip(new RpcCommand.SetFollowUpMode(id, mode));
    }

    @Property(tries = 20)
    void compactCmd(@ForAll @AlphaChars @StringLength(min = 1, max = 10) String id) throws Exception {
        assertRoundTrip(new RpcCommand.Compact(id, null));
    }

    @Property(tries = 20)
    void setAutoCompactionCmd(@ForAll @AlphaChars @StringLength(min = 1, max = 10) String id,
                              @ForAll boolean enabled) throws Exception {
        assertRoundTrip(new RpcCommand.SetAutoCompaction(id, enabled));
    }

    @Property(tries = 20)
    void setAutoRetryCmd(@ForAll @AlphaChars @StringLength(min = 1, max = 10) String id,
                         @ForAll boolean enabled) throws Exception {
        assertRoundTrip(new RpcCommand.SetAutoRetry(id, enabled));
    }

    @Property(tries = 20)
    void abortRetryCmd(@ForAll @AlphaChars @StringLength(min = 1, max = 10) String id) throws Exception {
        assertRoundTrip(new RpcCommand.AbortRetry(id));
    }

    @Property(tries = 30)
    void bashCmd(@ForAll @AlphaChars @StringLength(min = 1, max = 10) String id,
                 @ForAll @AlphaChars @StringLength(min = 1, max = 30) String command) throws Exception {
        assertRoundTrip(new RpcCommand.Bash(id, command));
    }

    @Property(tries = 20)
    void abortBashCmd(@ForAll @AlphaChars @StringLength(min = 1, max = 10) String id) throws Exception {
        assertRoundTrip(new RpcCommand.AbortBash(id));
    }

    @Property(tries = 20)
    void getSessionStatsCmd(@ForAll @AlphaChars @StringLength(min = 1, max = 10) String id) throws Exception {
        assertRoundTrip(new RpcCommand.GetSessionStats(id));
    }

    @Property(tries = 20)
    void exportHtmlCmd(@ForAll @AlphaChars @StringLength(min = 1, max = 10) String id) throws Exception {
        assertRoundTrip(new RpcCommand.ExportHtml(id, null));
    }

    @Property(tries = 30)
    void switchSessionCmd(@ForAll @AlphaChars @StringLength(min = 1, max = 10) String id,
                          @ForAll @AlphaChars @StringLength(min = 1, max = 30) String path) throws Exception {
        assertRoundTrip(new RpcCommand.SwitchSession(id, path));
    }

    @Property(tries = 30)
    void forkCmd(@ForAll @AlphaChars @StringLength(min = 1, max = 10) String id,
                 @ForAll @AlphaChars @StringLength(min = 1, max = 20) String entryId) throws Exception {
        assertRoundTrip(new RpcCommand.Fork(id, entryId));
    }

    @Property(tries = 20)
    void getMessagesCmd(@ForAll @AlphaChars @StringLength(min = 1, max = 10) String id) throws Exception {
        assertRoundTrip(new RpcCommand.GetMessages(id));
    }

    @Property(tries = 20)
    void getCommandsCmd(@ForAll @AlphaChars @StringLength(min = 1, max = 10) String id) throws Exception {
        assertRoundTrip(new RpcCommand.GetCommands(id));
    }

    @Provide
    Arbitrary<String> thinkingLevels() {
        return Arbitraries.of("off", "low", "medium", "high");
    }

    @Provide
    Arbitrary<String> modes() {
        return Arbitraries.of("all", "one-at-a-time");
    }

    private void assertRoundTrip(RpcCommand original) throws Exception {
        String json = PiAiJson.MAPPER.writeValueAsString(original);
        assertThat(json).isNotEmpty();
        RpcCommand deserialized = PiAiJson.MAPPER.readValue(json, RpcCommand.class);
        assertThat(deserialized).isNotNull();
        assertThat(deserialized.type()).isEqualTo(original.type());
        assertThat(deserialized.id()).isEqualTo(original.id());
        String json2 = PiAiJson.MAPPER.writeValueAsString(deserialized);
        assertThat(json2).isEqualTo(json);
    }
}
