# 阶段三：pi-ai-oauth 学习文档

> 本文档带你系统性地学习 pi-ai-oauth 模块，即 OAuth 2.0 认证系统。这个模块提供了多个 AI 平台的 OAuth 认证实现，支持 Authorization Code + PKCE 流程。建议边读本文档边打开对应源码文件对照学习。

---

## 一、模块概览

**pi-ai-oauth** 依赖 pi-ai-core（使用其工具类），提供统一的 OAuth 2.0 认证抽象。

### 核心功能

- **OAuth SPI**：定义 OAuth 提供者的统一接口和数据结构
- **Provider 注册表**：管理所有 OAuth 提供者的注册和查找
- **多平台实现**：Anthropic、GitHub Copilot、Google Gemini、OpenAI Codex
- **PKCE 工具**：Proof Key for Code Exchange 安全认证流程

### 包结构

```
pi-ai-oauth/
  ├── spi/                    ← SPI 接口定义
  │   ├── OAuthProviderInterface.java
  │   ├── OAuthCredentials.java
  │   └── OAuthLoginCallbacks.java
  ├── registry/               ← 注册表
  │   └── OAuthProviderRegistry.java
  ├── builtin/                ← 内置提供者注册
  │   └── BuiltInOAuthProviders.java
  ├── util/                   ← 工具类
  │   └── PkceUtils.java
  ├── anthropic/              ← Anthropic OAuth
  │   └── AnthropicOAuthProvider.java
  ├── github/                 ← GitHub Copilot OAuth
  │   └── GitHubCopilotOAuthProvider.java
  ├── google/                 ← Google OAuth
  │   ├── AntigravityOAuthProvider.java
  │   └── GeminiCliOAuthProvider.java
  └── openai/                 ← OpenAI Codex OAuth
      └── OpenAICodexOAuthProvider.java
```

---

## 二、SPI 接口层（spi/ 包）— 3 个文件

### 2.1 OAuthProviderInterface.java — 统一接口

**文件：** `spi/OAuthProviderInterface.java`

#### 作用

定义所有 OAuth 提供者必须实现的核心接口。对应 pi-momo 前端的同名接口。

#### 核心方法

| 方法 | 返回类型 | 说明 |
|------|---------|------|
| `provider()` | `String` | 返回提供商标识（如 "anthropic"、"github"） |
| `authorizationUrl(loginCallbacks, pkceVerifier)` | `String` | 生成授权 URL，用户浏览器访问此 URL 进行登录授权 |
| `exchangeCode(code, loginCallbacks, pkceVerifier)` | `CompletableFuture<OAuthCredentials>` | 用授权码换取令牌（access_token + refresh_token） |
| `refreshCredentials(credentials, loginCallbacks)` | `CompletableFuture<OAuthCredentials>` | 用 refresh_token 刷新过期的 access_token |

#### OAuth 2.0 Authorization Code + PKCE 流程

```
1. 用户点击"登录"
2. 应用生成 PKCE code_verifier 和 code_challenge
3. 应用将用户重定向到 authorizationUrl（提供商的授权页面）
4. 用户在提供商页面登录并授权
5. 提供商将用户重定向回回调 URL，附带 authorization code
6. 应用调用 exchangeCode()，用授权码换取 access_token + refresh_token
7. 后续 API 调用使用 access_token 进行认证
8. 当 access_token 过期时，调用 refreshCredentials() 获取新的令牌
```

---

### 2.2 OAuthCredentials.java — 凭证实体

**文件：** `spi/OAuthCredentials.java`

#### 作用

OAuth 认证凭证的数据结构，包含访问令牌、刷新令牌、过期时间等信息。

#### 关键字段

| 字段 | 类型 | 说明 |
|------|------|------|
| `accessToken` | `String` | 访问令牌，用于 API 调用的认证 |
| `refreshToken` | `String` | 刷新令牌，用于获取新的访问令牌 |
| `expiresAt` | `Instant` | 访问令牌的过期时间 |
| `extra` | `Map<String, Object>` | 额外字段（各平台特有的返回数据） |

#### 关键方法

- `isExpired()`：判断令牌是否已过期（考虑 5 分钟缓冲期）
- `isExpired(Duration buffer)`：带缓冲期的过期判断

---

### 2.3 OAuthLoginCallbacks.java — 登录回调接口

**文件：** `spi/OAuthLoginCallbacks.java`

#### 作用

定义 OAuth 登录流程中需要的回调方法，用于获取授权码和配置信息。对应 pi-momo 前端的同名接口。

#### 回调方法

| 方法 | 说明 |
|------|------|
| `getAuthCode()` | 获取授权码（通常是用户授权后回调到应用的 code） |
| `getRedirectUri()` | 获取重定向 URI（OAuth 应用中注册的回调地址） |
| `getClientId()` | 获取客户端 ID |
| `getScopes()` | 获取请求的权限范围 |

---

## 三、注册表（registry/ 包）— 1 个文件

### 3.1 OAuthProviderRegistry.java

**文件：** `registry/OAuthProviderRegistry.java`

#### 作用

OAuth 提供者的注册表，管理所有 OAuth 提供者的注册和查找。对应 pi-momo 前端的 `oauthProviderRegistry`。

#### 核心方法

| 方法 | 说明 |
|------|------|
| `register(OAuthProviderInterface)` | 注册 OAuth 提供者 |
| `get(String provider)` | 根据提供商名称查找提供者 |
| `getAll()` | 获取所有已注册的提供者 |

---

## 四、内置提供者注册（builtin/ 包）— 1 个文件

### 4.1 BuiltInOAuthProviders.java

**文件：** `builtin/BuiltInOAuthProviders.java`

#### 作用

集中注册所有内置的 OAuth 提供者，在应用启动时调用。

---

## 五、PKCE 工具（util/ 包）— 1 个文件

### 5.1 PkceUtils.java

**文件：** `util/PkceUtils.java`

#### 作用

实现 PKCE（Proof Key for Code Exchange）安全认证流程的工具类。对应 pi-momo 前端的 `pkce.ts`。

#### 核心方法

| 方法 | 说明 |
|------|------|
| `generateCodeVerifier()` | 生成随机的 code_verifier（43-128 个字符的随机字符串） |
| `generateCodeChallenge(String verifier)` | 对 code_verifier 进行 SHA-256 哈希后 Base64URL 编码，生成 code_challenge |

#### PKCE 流程

```
1. 客户端生成 code_verifier（随机字符串）
2. 客户端计算 code_challenge = Base64URL(SHA256(code_verifier))
3. 客户端将 code_challenge 作为授权请求的参数
4. 服务器记录 code_challenge
5. 客户端用 code_verifier 换取令牌
6. 服务器验证 code_verifier 与 code_challenge 匹配
```

---

## 六、平台实现

### 6.1 AnthropicOAuthProvider.java

**文件：** `anthropic/AnthropicOAuthProvider.java`

Anthropic Claude API 的 OAuth 认证实现。支持 Anthropic 的 OAuth 2.0 流程，用于获取 Anthropic API 的访问令牌。

### 6.2 GitHubCopilotOAuthProvider.java

**文件：** `github/GitHubCopilotOAuthProvider.java`

GitHub Copilot 的 OAuth 认证实现。支持 GitHub 的 OAuth Device Flow，用于获取 GitHub Copilot 服务的访问令牌。

### 6.3 AntigravityOAuthProvider.java

**文件：** `google/AntigravityOAuthProvider.java`

Antigravity（Google 内部项目）的 OAuth 认证实现。

### 6.4 GeminiCliOAuthProvider.java

**文件：** `google/GeminiCliOAuthProvider.java`

Google Gemini CLI 的 OAuth 认证实现。支持 Google 的 OAuth 2.0 流程，用于获取 Gemini API 的访问令牌。

### 6.5 OpenAICodexOAuthProvider.java

**文件：** `openai/OpenAICodexOAuthProvider.java`

OpenAI Codex 的 OAuth 认证实现。支持 OpenAI 的 OAuth 2.0 流程，用于获取 OpenAI Codex API 的访问令牌。

---

## 七、学习检查清单

1. [ ] OAuth 2.0 Authorization Code + PKCE 的完整流程是什么？
2. [ ] `OAuthProviderInterface` 定义了哪四个核心方法？
3. [ ] `OAuthCredentials` 如何判断令牌是否过期？为什么需要缓冲期？
4. [ ] PKCE 的 `code_verifier` 和 `code_challenge` 是什么关系？
5. [ ] `OAuthProviderRegistry` 的作用是什么？
6. [ ] 各平台 OAuth 实现分别对应什么 AI 服务？
7. [ ] access_token 过期后，OAuth 流程如何自动刷新？

---

## 八、下一步

完成阶段三后，进入阶段四（pi-ai-providers）学习 AI 模型提供商的具体实现。