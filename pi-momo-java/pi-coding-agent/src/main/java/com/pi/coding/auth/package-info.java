/**
 * Authentication storage and credential management for pi-coding-agent.
 * 
 * <p>This package provides:
 * <ul>
 *   <li>{@link AuthCredential} - Sealed interface for credential types (API key, OAuth)</li>
 *   <li>{@link AuthStorage} - Credential storage with file-based and in-memory backends</li>
 *   <li>{@link AuthStorageBackend} - Backend interface for credential persistence</li>
 * </ul>
 */
package com.pi.coding.auth;
