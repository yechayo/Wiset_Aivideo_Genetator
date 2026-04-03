# Wuyinkeji Provider Integration Design

## Overview

Integrate Nanobanana2 (image generation) and Sora2 (video generation) APIs from the wuyinkeji.com platform into the existing comic production backend. Each project selects its AI providers at creation time, and all generation tasks within that project use the selected providers.

## Provider Selection Strategy

- **Project-level**: A project selects one image provider and one video provider at creation time
- **Stored in `projectInfo` JSON field**: `image_provider` and `video_provider` keys
- **No DB schema change required**
- **Backend dispatch**: `AiServiceConfiguration` maintains provider Maps and dispatches by name

## API Reference

### Wuyinkeji Platform (shared)

- Submit auth: `Authorization` header with API key
- Both APIs are async: submit returns task id, poll for result

### Nanobanana2 (Image Generation)

- **Submit**: `POST https://api.wuyinkeji.com/api/async/image_nanoBanana2`
- **Query**: `GET https://api.wuyinkeji.com/api/async/detail?id={taskId}`
- **Params**: `prompt` (required), `size` (1K/2K/4K), `aspectRatio` (auto/1:1/16:9/9:16/etc), `urls` (reference images array)
- **Submit response**: `{ code: 200, data: { id: "image_xxx", count: 10 } }`
- **Query response**: `{ code: 200, data: { status: 0|1|2|3, remote_url: "https://..." } }`
  - status: 0=queuing, 1=success, 2=failed, 3=generating

### Sora2 (Video Generation)

- **Submit**: `POST https://api.wuyinkeji.com/api/async/video_sora2`
- **Query**: `GET https://csapi.wuyinkeji.com/api/sora2/detail?id={taskId}`
- **Params**: `prompt` (required), `aspectRatio` (9:16/16:9), `url` (reference image), `duration` (10/15), `size` (small/large)
- **Submit response**: `{ code: 200, data: { id: "video_xxx", count: 10 } }`
- **Query response**: `{ code: 200, data: { status: 0|1|2|3, remote_url: "https://...mp4" } }`

## New Files

### 1. `WuyinkejiProperties.java` (`com.comic.config`)

Config properties class for the wuyinkeji platform.

```java
@Data
@Component
@ConfigurationProperties(prefix = "comic.wuyinkeji")
public class WuyinkejiProperties {
    private String apiKey;
    private String baseUrl = "https://api.wuyinkeji.com";
    private String soraQueryBaseUrl = "https://csapi.wuyinkeji.com";
}
```

### 2. `NanobananaImageService.java` (`com.comic.ai.image`)

Implements `ImageGenerationService`. Internally wraps async API as synchronous:

1. Submit task via POST to `/api/async/image_nanoBanana2`
2. Poll `GET /api/async/detail?id={taskId}` every 3 seconds
3. On success (status=1): extract `remote_url`, upload to OSS, return permanent URL
4. On failure (status=2): throw RuntimeException
5. Timeout after 3 minutes: throw RuntimeException
6. Uses Semaphore(2) for concurrency control (same as Seedream)

### 3. `SoraVideoService.java` (`com.comic.ai.video`)

Implements `VideoGenerationService`:

1. Submit task via POST to `/api/async/video_sora2`
2. `generateAsync()` returns task id
3. `getTaskStatus()` queries `GET /api/sora2/detail?id={taskId}`, maps status codes:
   - 0 (queuing) → pending
   - 3 (generating) → processing
   - 1 (success) → completed, with videoUrl from `remote_url`
   - 2 (failed) → failed, with `fail_reason` as errorMessage
4. Uses Semaphore(1) for concurrency control (same as Vidu)

## Modified Files

### 4. `application.yml`

Add wuyinkeji config section:

```yaml
comic:
  wuyinkeji:
    api-key: ${WUYINKEJI_API_KEY:}
    base-url: https://api.wuyinkeji.com
    sora-query-base-url: https://csapi.wuyinkeji.com
```

### 5. `ProjectCreateRequest.java`

Add fields:

```java
private String imageProvider;   // "seedream" | "nanobanana"
private String videoProvider;   // "vidu" | "sora"
```

### 6. `ProjectService.java`

In `createProject()`, store provider in projectInfo:

```java
projectInfo.put(ProjectInfoKeys.IMAGE_PROVIDER, imageProvider);
projectInfo.put(ProjectInfoKeys.VIDEO_PROVIDER, videoProvider);
```

### 7. `AiServiceConfiguration.java`

Replace single `@Primary` bean pattern with provider Maps:

```java
private final Map<String, ImageGenerationService> imageServices;
private final Map<String, VideoGenerationService> videoServices;

public AiServiceConfiguration(
    SeedreamImageService seedreamImageService,
    NanobananaImageService nanobananaImageService,
    ViduVideoService viduVideoService,
    SoraVideoService soraVideoService
) {
    imageServices = Map.of(
        "seedream", seedreamImageService,
        "nanobanana", nanobananaImageService
    );
    videoServices = Map.of(
        "vidu", viduVideoService,
        "sora", soraVideoService
    );
}

public ImageGenerationService getImageService(String provider) {
    return imageServices.getOrDefault(provider, imageServices.get("seedream"));
}

public VideoGenerationService getVideoService(String provider) {
    return videoServices.getOrDefault(provider, videoServices.get("vidu"));
}
```

### 8. `GridImageService.java`

Change from `SeedreamImageService` concrete injection to `AiServiceConfiguration` dispatch:

- Replace `@Resource SeedreamImageService seedreamImageService` with `AiServiceConfiguration aiConfig`
- Add `projectId` parameter to generation methods
- Read `image_provider` from project's `projectInfo`
- Use `aiConfig.getImageService(provider)` to get the correct implementation

### 9. `PanelProductionService.java`

Same pattern as GridImageService:

- Add `AiServiceConfiguration aiConfig` dependency
- Read provider from project's `projectInfo`
- Use `aiConfig.getImageService(provider)` and `aiConfig.getVideoService(provider)` for dispatch

## Files NOT Changed

- `ImageGenerationService.java` — interface unchanged
- `VideoGenerationService.java` — interface unchanged
- `SeedreamImageService.java` — preserved as-is
- `ViduVideoService.java` — preserved as-is
- DB schema — no migration needed

## Nanobanana Async-to-Sync Wrapper

The `ImageGenerationService.generate()` interface is synchronous, but Nanobanana is async. The wrapper pattern:

```
generate(prompt, width, height, style)
  → POST submit (get taskId)
  → loop every 3s: GET detail?id={taskId}
    → status 1: return remote_url (after OSS upload)
    → status 2: throw "generation failed"
    → status 0/3: continue polling
    → timeout 3min: throw "timeout"
```

This makes Nanobanana behave identically to Seedream from the caller's perspective.

## Error Handling

- Unknown provider: fall back to default (seedream/vidu)
- API auth failure: log error, throw RuntimeException
- Poll timeout: log warning, throw RuntimeException
- Network errors: retry once, then throw