# Project Controller Redesign

## Background

Current `ProjectController` mixes project CRUD, script management, and pipeline orchestration in a single controller with 12+ endpoints. This redesign:

1. Separates concerns via REST sub-resource paths (ProjectController + ScriptController)
2. Migrates all three entities (Project, Episode, Character) to Map-based storage, rewriting services accordingly
3. Restructures script outline as AI-generated structured JSON
4. Removes user-facing chapter concept — auto-batches episode generation on confirm
5. Adds status rollback with precise data cleanup

## Design Decisions

1. **Map-based entities** — Project, Episode, Character all use `Map<String, Object>` for business data (projectInfo, episodeInfo, characterInfo). All dependent services rewritten to use Map access instead of direct field getters.
2. **Script outline as structured JSON** — AI outputs flat JSON with `characters`, `items`, `episodes` arrays, stored in `projectInfo["script"]`. No more markdown-only outline.
3. **No user-facing chapters** — Outline confirmation triggers automatic batch episode generation (2-4 episodes per batch). Chapters are an internal implementation detail.
4. **Logical delete** — Project entity adds `deleted` field (Boolean, default false). All ProjectRepository queries auto-filter `deleted = false`.
5. **Status rollback** — `advance` endpoint supports `direction: "backward"`. Precise cleanup per stage boundary.
6. **Controller split** — ProjectController handles CRUD + status/pipeline. ScriptController handles all script operations.

## Data Model

### Project Entity

Independent fields: `id`, `projectId`, `userId`, `status`, `deleted` (new), `createdAt`, `updatedAt`

`projectInfo` Map key structure:
```json
{
  "storyPrompt": "string",
  "genre": "string",
  "targetAudience": "string",
  "totalEpisodes": 10,
  "episodeDuration": 60,
  "visualStyle": "ANIME",
  "script": {
    "outline": "Markdown full text (for display)",
    "characters": [
      { "name": "string", "role": "string", "personality": "string", "appearance": "string", "background": "string" }
    ],
    "items": [
      { "name": "string", "description": "string" }
    ],
    "episodes": [
      { "ep": 1, "title": "string", "synopsis": "string", "characters": ["string"], "keyItems": ["string"] }
    ]
  }
}
```

### Episode Entity

Independent fields: `id`, `projectId`, `status`, `createdAt`, `updatedAt`

`episodeInfo` Map key structure:
```json
{
  "episodeNum": 1,
  "title": "string",
  "content": "full script content",
  "characters": "string",
  "keyItems": "string",
  "continuityNote": "string",
  "visualStyleNote": "string",
  "synopsis": "synopsis from outline",
  "chapterTitle": "string (internal batch label)",
  "retryCount": 0,
  "storyboardJson": "string (filled by StoryboardService)",
  "errorMsg": "string or null",
  "productionStatus": "NOT_STARTED (filled by EpisodeProductionService)"
}
```

### Character Entity

Independent fields: `id`, `projectId`, `status`, `createdAt`, `updatedAt`

`characterInfo` Map key structure:
```json
{
  "charId": "CHAR-xxx",
  "name": "string",
  "role": "string",
  "personality": "string",
  "appearance": "string",
  "background": "string",
  "images": { "front": "url", "side": "url", "back": "url" },
  "expressionImages": { "happy": "url", "angry": "url" }
}
```

## API Design

### ProjectController — `/api/projects`

| Method | Path | Description |
|--------|------|-------------|
| POST | `/api/projects` | Create project |
| GET | `/api/projects?page&size&status&sortBy&sortOrder` | List projects (paginated) |
| GET | `/api/projects/{id}` | Project detail |
| PUT | `/api/projects/{id}` | Full update |
| PATCH | `/api/projects/{id}` | Partial update |
| DELETE | `/api/projects/{id}` | Logical delete (sets deleted=true) |
| GET | `/api/projects/{id}/status` | Status detail with navigation |
| POST | `/api/projects/{id}/status/advance` | Advance or rollback pipeline |

#### POST `/api/projects` — Create Project

**Request:** `ProjectCreateRequest` (unchanged)
```json
{ "storyPrompt": "string", "genre": "string", "targetAudience": "string", "totalEpisodes": 10, "episodeDuration": 60, "visualStyle": "ANIME" }
```

**Response:** `{ "data": { "projectId": "PROJ-xxxx" } }`

#### GET `/api/projects` — List Projects

**Query params:** `page` (default 1), `size` (default 20), `status` (optional filter), `sortBy` (createdAt|updatedAt), `sortOrder` (asc|desc)

**Response:** paginated `ProjectListItemResponse`

#### PUT/PATCH `/api/projects/{id}` — Update Project

PUT: full update of basic info fields. PATCH: partial update (only provided fields).

#### GET `/api/projects/{id}/status` — Project Status

**Response:**
```json
{
  "projectId": "PROJ-xxx",
  "currentStep": 4,
  "currentStatus": "CHARACTER_REVIEW",
  "previousStatus": "SCRIPT_CONFIRMED",
  "nextStatus": "CHARACTER_CONFIRMED",
  "canGoBack": true,
  "canAdvance": true,
  "isGenerating": false,
  "isFailed": false,
  "isReview": true,
  "completedSteps": [1, 2, 3],
  "stepHistory": [
    { "step": 1, "status": "OUTLINE_GENERATED", "label": "剧本大纲", "confirmed": true },
    { "step": 2, "status": "SCRIPT_CONFIRMED", "label": "剧本确认", "confirmed": true },
    { "step": 3, "status": "CHARACTER_EXTRACTED", "label": "角色提取", "confirmed": true },
    { "step": 4, "status": "CHARACTER_REVIEW", "label": "角色审核", "confirmed": false }
  ],
  "productionProgress": null,
  "storyboardProgress": null
}
```

Enriched data for producing/storyboard phases preserved from current `ProjectStatusResponse`.

#### POST `/api/projects/{id}/status/advance` — Advance/Rollback

**Request (forward):** `{ "direction": "forward", "event": "characters_confirmed" }`
**Request (backward):** `{ "direction": "backward" }`

### ScriptController — `/api/projects/{projectId}/script`

| Method | Path | Description |
|--------|------|-------------|
| GET | `/script` | Get script content (outline + episodes) |
| POST | `/script/generate` | Generate outline (AI outputs structured JSON) |
| POST | `/script/confirm` | Confirm script (auto-batch generate all episodes) |
| POST | `/script/revise` | Revise script |
| PATCH | `/script/outline` | Save outline manually (clears episodes) |

**Removed endpoints:** `generate-episodes`, `generate-all-episodes` (replaced by auto-batch on confirm).

### Unchanged Controllers

EpisodeController, StoryController, CharacterController, FileController, AuthController, ConfigController, JobController, TaskController.

## Status Rollback

### Rollback Cleanup Table

| Rollback Target | Data Cleared |
|----------------|-------------|
| OUTLINE_REVIEW | All Episodes deleted |
| SCRIPT_CONFIRMED | All Characters deleted |
| CHARACTER_REVIEW | All CharacterImages deleted |
| IMAGE_REVIEW | All Storyboards deleted |
| ASSET_LOCKED | All Productions deleted |
| STORYBOARD_REVIEW | All Productions deleted |

### Auto-Batch Generation

On script confirm, episodes are generated in batches:

| Total Episodes | Batch Size |
|---------------|-----------|
| ≤ 3 | All at once |
| 4-6 | 2 per batch |
| 7-12 | 3 per batch |
| > 12 | 4 per batch |

## Implementation Phases

### Phase 1: Entity + deleted + Repository

- Add `deleted` field to Project entity
- Update ProjectRepository: all queries add `deleted = false`
- Add pagination query method to ProjectRepository
- Define key constants for projectInfo / episodeInfo / characterInfo

### Phase 2: ProjectStatus Enum + DTO Updates

- Add `getPreviousStatus()`, `getNextStatus()`, `canGoBack()`, `canAdvance()`, `getStepHistory()` to ProjectStatus enum
- Update ProjectStatusResponse with new fields: previousStatus, nextStatus, canGoBack, canAdvance, stepHistory
- Create AdvanceRequest DTO: `{ direction, event }`
- Create ProjectUpdateRequest DTO for PUT/PATCH
- Create StepHistoryItem DTO

### Phase 3: Service Layer Rewrite

- Rewrite PipelineService: Map access, updateProject, logical delete, rollback support
- Rewrite ScriptService: Map access, structured JSON outline, auto-batch generation on confirm
- Rewrite CharacterExtractService: Map access
- Rewrite CharacterImageGenerationService: Map access
- Rewrite StoryboardService: Map access
- Rewrite EpisodeProductionService: Map access
- Rewrite EpisodeController: Map access
- Update PromptBuilder: outline generation outputs structured JSON

### Phase 4: Controller Refactoring

- Refactor ProjectController: CRUD + pagination + status + advance (remove script endpoints)
- Create ScriptController: 5 script endpoints
