/**
 * Message conversion package.
 *
 * <p>Contains custom AgentMessage types for the coding agent:
 * <ul>
 *   <li>{@link com.pi.coding.message.BashExecutionMessage} - bash command executions</li>
 *   <li>{@link com.pi.coding.message.CustomMessage} - extension-injected messages</li>
 *   <li>{@link com.pi.coding.message.BranchSummaryMessage} - branch summaries</li>
 *   <li>{@link com.pi.coding.message.CompactionSummaryMessage} - compaction summaries</li>
 * </ul>
 *
 * <p>And {@link com.pi.coding.message.MessageConverter} for converting
 * AgentMessages to LLM-compatible Messages.
 */
package com.pi.coding.message;
