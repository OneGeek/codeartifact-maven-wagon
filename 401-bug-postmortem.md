# CodeArtifact Maven Wagon: Bug Investigation & Resolution

## Problem Statement

A custom Maven Wagon (`CodeArtifactWagon`) was built to allow Maven builds to resolve artifacts from AWS CodeArtifact using only a `codeartifact:domain/owner/repo` URL in `pom.xml`, with credentials derived automatically from the developer's `~/.aws/credentials`. The wagon needed to:

1. Parse the custom `codeartifact:` scheme URL
2. Resolve the real HTTPS endpoint via the CodeArtifact API
3. Obtain an authorization token via the CodeArtifact API
4. Rewrite the repository URL and inject credentials so the standard HTTP wagon machinery could make authenticated requests

**Symptom:** Progressive 401 failures. Each build run resolved one more dependency than the last (due to local caching), then failed with unauthenticated requests. AWS CloudTrail confirmed the failing requests had no `Authorization` header at all.

## Architecture Constraints

### Maven Wagon Lifecycle

The wagon lifecycle is: `connect()` → `openConnectionInternal()` → `execute()` (per request) → `closeConnection()`.

`AbstractHttpClientWagon.openConnectionInternal()` does two critical things in a fixed order:
1. Rewrites the repository URL: `repository.setUrl(getURL(repository))`
2. Reads `authenticationInfo` to populate a `credentialsProvider` and `authCache`

The `execute()` method then attaches `credentialsProvider` and `authCache` to a per-request `HttpClientContext`.

### WagonTransporter Pooling

Maven's `WagonTransporter` (in `org.eclipse.aether.transport.wagon`) manages wagon instances in a `ConcurrentLinkedQueue` pool. Key behaviors:

- A single `wagonRepo` (`Repository`) instance is shared across all wagon instances for a given remote repository.
- `pollWagon()` creates new wagon instances via `lookupWagon()` when the pool is empty, then calls `connectWagon()` which calls `wagon.connect(wagonRepo, wagonAuth, wagonProxy)`.
- Recycled wagons are returned to the pool **without disconnecting**. On reuse, `connectWagon()` is skipped if `wagon.getRepository() != null`.
- New wagon instances created after the first may receive a `wagonRepo` whose URL has already been mutated from `codeartifact:` to `https://`.

### Repository URL Parsing

`Repository.setUrl()` eagerly parses the URL into `protocol`, `host`, `port`, and `basedir` via `PathUtils`. This means mutating the URL has side effects on all subsequent field reads.

## Investigation Path

### Attempt 1: Override `openConnectionInternal()`, Rewrite URL and Set Auth

**Approach:** Check for `codeartifact:` scheme, resolve endpoint and token, call `repository.setUrl()` with the HTTPS URL, set `authenticationInfo`, then call `super.openConnectionInternal()`.

**Failure mode:** After the first call, `repository.setUrl()` mutated the shared `wagonRepo` to `https://`. On the second call (same or different wagon instance), the scheme check failed, so credentials were never set. The `codeArtifactToken != null` guard partially worked but `authenticationInfo` wasn't always populated on the right instance.

### Attempt 2: Store Parsed Info in a Field (`CodeArtifactRepoInfo`)

**Approach:** Parse the `codeartifact:` URL once into an immutable `CodeArtifactRepoInfo` object (domain, owner, repositoryName, endpoint, token). Use its presence as the durable flag instead of re-checking the URL scheme.

**Failure mode:** Solved the same-instance problem but not the multi-instance problem. New wagon instances created by `WagonTransporter.pollWagon()` had `codeArtifactRepoInfo == null` and received an already-rewritten `https://` URL, so the parsing never triggered.

### Attempt 3: Override `connect()` to Intercept Earlier

**Approach:** Set credentials and rewrite the URL in `connect()` before `super.connect()` calls `openConnectionInternal()`.

**Failure mode:** Recycled wagons in the pool skip `connectWagon()` entirely (and thus `connect()`), so this path never fired for reused instances.

### Attempt 4: Override `execute()` to Re-initialize Per-Request

**Approach:** Check if `credentialsProvider` is null in `execute()` and re-run `openConnectionInternal()`.

**Failure mode:** `credentialsProvider` and `authCache` are private fields on `AbstractHttpClientWagon`, inaccessible from the subclass. The protected getters return null but there are no setters.

### Attempt 5: Bypass Credential Machinery, Set Authorization Header Directly

**Approach:** Override `setHeaders()` (called by `execute()` on every request) to inject the `Authorization: Basic ...` header directly, bypassing `credentialsProvider`/`authCache` entirely.

**Failure mode:** Worked for wagon instances that had `codeArtifactRepoInfo` set, but new instances created after the URL rewrite still had `codeArtifactRepoInfo == null`.

### Attempt 6 (Solution): Static Shared State + Direct Header Injection

**Approach:** Combine `setHeaders()` override with a `static volatile CodeArtifactRepoInfo` field that bridges parsed info from the first wagon instance to all subsequent instances.

**Why it works:**
- The first `CodeArtifactWagon` instance sees the `codeartifact:` URL, parses it, creates `CodeArtifactRepoInfo`, and stores it in both the instance field and the static field.
- Subsequent instances see an `https://` URL but fall through to the `sharedRepoInfo` check and pick up the cached info.
- `setHeaders()` fires on every HTTP request regardless of wagon lifecycle state, ensuring the `Authorization` header is always present.

## Key Lessons

1. **Maven Wagon was not designed for dynamic credential resolution.** The architecture assumes credentials are static and available before the transport layer is invoked. Any solution that tries to resolve credentials inside the wagon fights the lifecycle at every turn.

2. **`WagonTransporter` mutates shared state.** The `wagonRepo` instance is shared across all pooled wagon instances, and `Repository.setUrl()` eagerly parses URL components. Any URL rewrite via `setUrl()` is visible to all wagons and irreversible from the wagon's perspective.

3. **Wagon pooling breaks per-connection initialization.** `WagonTransporter` reuses wagons without re-calling `connect()` or `openConnectionInternal()`, so any state set during connection is not guaranteed to exist on subsequent requests. New wagon instances created after the first may receive an already-rewritten URL, losing the original `codeartifact:` scheme information.

4. **Bypassing the credential machinery was the only reliable path.** The `credentialsProvider`/`authCache`/`AuthScope` system has too many moving parts (host matching, preemptive auth, private fields) to reliably control from a subclass. Setting the `Authorization` header directly in `setHeaders()` is immune to all of these issues and fires on every HTTP request regardless of wagon lifecycle state.

5. **Static state is sometimes the right answer.** When the framework creates multiple instances of your class and mutates shared inputs between them, instance-level state is insufficient. A `ConcurrentHashMap` keyed by repository ID bridges parsed info from the first wagon instance (which sees the `codeartifact:` URL) to subsequent instances (which only see the rewritten `https://` URL).

## Known Limitations

- The token is fetched once at construction time with an 8-hour TTL. Long-running builds or daemon processes could see token expiry, requiring a refresh mechanism on `CodeArtifactRepoInfo`.
- The AWS SDK client is created as a static field on `CodeArtifactRepoInfo` via `AWSCodeArtifactClientBuilder.defaultClient()`. This relies on the default credential chain (`~/.aws/credentials`, environment variables, instance profiles, etc.).