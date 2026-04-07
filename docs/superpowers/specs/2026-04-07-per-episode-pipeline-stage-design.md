# Per-Episode Pipeline Stage Design

## Problem

Current Step 4 uses **all-or-nothing tab unlocking**:

```
tab4bUnlocked = ALL episodes panelApproved
tab4cUnlocked = ALL episodes gridApproved
```

When batch-generating 20 episodes, users must review ALL scripts before seeing Tab 4b.
This blocks parallel workflow and makes the process feel sluggish.

## Goal

Each episode progresses through 4a → 4b → 4c **independently**.
Different episodes can be at different stages simultaneously.

## Design Decision

- **Tab interaction**: Keep 4a/4b/4c tabs, each tab filters episodes by their current stage.
- **Stage model**: `pipelineStage` derived from existing `panelApproved` + `gridStatus` fields (frontend-only, no backend change needed).
- **Rejection**: Rejecting in 4b stays in 4b; optionally reject back to 4a via a separate action.

## Stage Derivation Logic

```
pipelineStage:
  panelApproved == false  →  "script"
  panelApproved == true && gridStatus != "approved"  →  "grid"
  panelApproved == true && gridStatus == "approved"  →  "video"
```

No new backend field required.

## Tab Content Changes

- **All 3 tabs always unlocked**, remove lock icons.
- **Tab 4a (script)**:
  - Main area: episodes with `pipelineStage === "script"` (normal generate/approve/reject actions).
  - Collapsed footer: episodes with `pipelineStage === "grid"` or `"video"` (read-only, marked "已通过 → 4b" / "已通过 → 4c").
- **Tab 4b (grid)**:
  - Main area: episodes with `pipelineStage === "grid"` (normal generate/approve/reject actions).
  - Collapsed footer: episodes with `pipelineStage === "video"` (read-only, marked "已通过 → 4c").
- **Tab 4c (video)**:
  - Main area: episodes with `pipelineStage === "video"` (normal video actions).
  - No collapsed footer needed.

## Rejection Flow

| Action | Effect |
|---|---|
| Reject script in 4a | `panelApproved = false`, stays in "script" (existing behavior) |
| Reject grid in 4b | `gridStatus = "rejected"`, stays in "grid" (existing behavior) |
| "退回脚本阶段" button in 4b | NEW: reset `panelApproved = false`, episode moves back to 4a |

## Frontend Changes

### `Step4Production.tsx`

1. **Remove global unlock logic** (`tab4bUnlocked`, `tab4cUnlocked`).
2. **Add `pipelineStage` computed property** to `EpisodeState` or derive inline.
3. **Filter episodes per tab** based on `pipelineStage`.
4. **Render collapsed "已完成" section** at bottom of 4a and 4b tabs.
5. **Remove lock icons** from tab buttons.
6. **Update tab completion indicator**: show checkmark based on per-tab episode counts rather than global unlock.

### `types.ts`

- Add `pipelineStage` field to `EpisodeState` (or compute in component).
- Add `"退回脚本阶段"` action type if needed.

## Backend Changes

### Optional: New endpoint for rejecting back to script stage

```
PUT /api/projects/{projectId}/episodes/{episodeId}/panel/reject-to-script
```

Sets `panelApproved = false`, resets `gridStatus` to `pending`, clears `gridImages`/`splitShots`.

If we don't want a new endpoint, the existing reject script endpoint can be called from 4b tab context (frontend constructs the call).

## Files to Modify

| File | Change |
|---|---|
| `Step4Production.tsx` | Tab filtering, unlock removal, collapsed sections |
| `types.ts` | Add `pipelineStage` type |
| `Step4Production.module.css` | Styles for collapsed footer, completed badges |
| `EpisodeController.java` (optional) | New reject-to-script endpoint |
