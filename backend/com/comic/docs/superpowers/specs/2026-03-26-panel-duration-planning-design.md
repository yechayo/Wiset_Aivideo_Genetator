# Panel Duration Planning Design

## Background

Panel is the smallest video unit in the system, generating 1-16 seconds of video each. Currently, all panel videos are fixed at 5 seconds, and the user-specified episode duration (`EPISODE_DURATION`) is not used to plan panel count or duration. This design adds duration awareness to the panel generation pipeline so that total video duration approximately matches the user's target.

## Requirements

- Panel stage handles duration allocation (both planning and detailing steps)
- Each panel duration: integer, 1-16 seconds, no scene-type distinction
- Total duration tolerance: target ±5%
- Hybrid approach: AI decides durations + code calibration as fallback

## Approach: Budget-Based Duration Planning

AI receives a total duration budget and allocates time across panels. Code calibrates if AI output doesn't meet the ±5% constraint.

### Data Flow

```
User sets episodeDuration (seconds, stored in Project)
        |
PanelGenerationService.generatePanels()
        | reads episodeDuration from project
        |
Step1: Planning -> prompt informs total N seconds, AI outputs duration per panel
        |
Step2: Detailing -> prompt passes planned duration + remaining budget, AI may adjust ±2s
        |
Code calibration -> proportional scaling if total outside ±5%, clamp to [1, 16]
```

## Step1: Planning Stage Prompt Changes

### Panel output format adds `duration` field

```json
{
  "panel_id": "p1",
  "scene_summary": "...",
  "characters": [...],
  "mood": "...",
  "time_of_day": "...",
  "duration": 5
}
```

### System prompt additions (appended to requirements section)

```
## Duration Planning
Target total duration: {N} seconds (acceptable range: {N_min}~{N_max} seconds)
- Each panel must include a duration field (integer, seconds, range 1~16)
- Sum of all panel durations must be between {N_min}~{N_max} seconds
- Allocate duration thoughtfully based on content importance
- After planning, verify the sum yourself before outputting
```

Where `N_min = round(N * 0.95)`, `N_max = round(N * 1.05)`.

### User prompt additions (before episode content)

```
## Duration Constraints
- Target total duration: {N} seconds
- Per-panel duration range: 1~16 seconds
- Sum of all panel durations must be between {N_min}~{N_max} seconds
```

### Key design decisions

- Duration constraint appears in both system and user prompt for higher AI compliance
- "Verify the sum yourself" is a prompt engineering technique that improves accuracy
- Panel count range (currently 6-15) is removed; AI decides based on duration and content

## Step2: Detailing Stage Prompt Changes

### User prompt additions (after panel plan)

```
## Duration Info
- Planned duration from planning stage: {plannedDuration} seconds
- Allowed adjustment range: {plannedDuration - 2}~{plannedDuration + 2} seconds (must stay in 1~16)
- Remaining budget for this episode: {remainingBudget} seconds ({remainingPanels} panels remaining)
- Include a duration field in your output with the adjusted actual duration
```

Where:
- `plannedDuration` = duration from Step1 for this panel
- `remainingBudget` = target total duration - sum of all confirmed panel durations so far
- `remainingPanels` = total panel count - currently detailed panel count

### Why pass remaining budget

If AI consistently takes the upper bound (+2) for early panels, later panels get squeezed out. Remaining budget gives AI global awareness to self-regulate.

## Code Calibration Logic

After Step2 completes, calibrate all panel durations as a safety net.

### Algorithm

```
Input: targetDuration (int), panelDurations[] (each 1~16)
Output: calibratedDurations[] (int[])

1. totalActual = sum(panelDurations)
2. If totalActual in [targetDuration * 0.95, targetDuration * 1.05] -> no adjustment
3. Otherwise:
   a. ratio = targetDuration / totalActual
   b. For each duration: calibrated = round(duration * ratio)
   c. Clamp to [1, 16]
4. Recalculate sum; if still out of range, adjust the largest-deviation panels by ±1 until within range
```

### Implementation

- New private method: `calibrateDurations(int targetDuration, ArrayNode detailedPanels)`
- Location: `PanelGenerationService.generatePanels()`, after Step2 loop, before building final result
- Uses proportional scaling (not equal distribution) to preserve AI's narrative rhythm

### Missing duration fallback

If AI output lacks `duration` field, assign default: `clamp(round(targetDuration / panelCount), 1, 16)` before calibration.

## Files to Modify

| File | Changes |
|------|---------|
| `PanelPromptBuilder.java` | Add `episodeDuration` param to `buildPlanSystemPrompt` and `buildPlanUserPrompt`; add `plannedDuration`, `remainingBudget`, `remainingPanels` params to `buildPanelDetailUserPrompt` |
| `PanelGenerationService.java` | Read `episodeDuration` from project, pass to prompt builder; pass duration info in Step2; add `calibrateDurations()` method; validate duration field existence after Step1 |

### Not modified

- Entities (Episode, Panel) - duration lives in panelInfo JSON, no schema change needed
- Frontend / DTOs - only AI prompt and backend calibration logic change
- Video generation service - will consume duration field later, not in scope