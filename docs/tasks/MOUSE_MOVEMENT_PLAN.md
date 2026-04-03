# Mouse Movement Framework — Implementation Plan

## Overview

This document defines every component of the human-like mouse movement framework for NoDriver4j. Each component lists its purpose, the behavioral signals it addresses, reference implementations to study, datasets for calibration, and key parameters. The framework replaces the current Bézier-only approach in `HumanBehavior.java` and `InputController.java`.

## Architecture

The new system is built on a **per-profile seeded behavior model**. Each browser profile gets a deterministic seed that derives a `MovementPersona` — a set of baseline behavioral parameters (preferred speed range, tremor amplitude, curvature tendency, pause patterns, click offset bias). This ensures sequential movements within a session are internally consistent while varying naturally across profiles.

### Delivery Mechanism

Mouse paths are computed in Java as arrays of `{x, y, deltaMs}` tuples, then sent to Chromium via a new CDP command (`Input.dispatchMousePath`) that walks the array internally with frame-aligned timing. This eliminates per-event WebSocket jitter. See Component 12 for details.

> **TEMPORARY:** The custom `Input.dispatchMousePath` CDP command requires a Chromium rebuild and is not available yet. For now, all components should be implemented using the existing `Input.dispatchMouseEvent` CDP calls — dispatching each point in the path individually from Java with `Thread.sleep()` for timing. This lets us develop, test, and validate the entire movement framework (path shape, velocity profile, tremor, sub-movements, etc.) without the Chromium patch. The Java-side output format (`{x, y, deltaMs}` tuples) stays the same either way, so swapping to the new CDP command later is just a transport change in `InputController.moveAlongPath()` — no algorithm code needs to change. Accept that inter-event timing jitter will be slightly worse until the Chromium patch lands.

### Global Speed Multiplier

A universal `speedMultiplier` (default: 1.0) in `InteractionOptions` scales **all** timing across the entire framework. At 2.0, everything runs at double speed — mouse movement durations, inter-action pauses, keystroke delays, scroll delays, click hold durations, idle drift intervals, hesitation pauses, and SessionContext-injected delays are all halved. At 0.5, everything runs at half speed.

This is applied as a final divisor on every computed delay: `actualDelay = computedDelay / speedMultiplier`. It does NOT affect path shape, tremor frequencies, or spatial parameters — only timing. This means movements still look human in shape, just faster or slower overall.

The multiplier is useful for development/testing (crank it up to iterate quickly) and for scripts where stealth is less critical and throughput matters more. The existing `type()` method already accepts a `speedMultiplier` parameter — this global setting provides a unified equivalent across all interaction types.

---

## Component 1: Movement Persona (Per-Profile Seed)

**Purpose:** Ensure behavioral consistency within a session and across sessions for the same profile. Real humans have individual motor signatures — some move fast with little curvature, others move slowly with wide arcs. A bot that randomly varies these characteristics movement-to-movement is more detectable than one that maintains a consistent personality.

**What it controls:**
- Base movement speed range (maps to Fitts's Law `a` and `b` coefficients)
- Preferred curvature tendency (how much paths arc away from straight lines)
- Tremor amplitude and frequency center (within the 8–12 Hz physiological range)
- Click offset bias (slight consistent bias toward one side of targets, like a right-handed user)
- Pause duration distribution parameters (mean, variance of inter-action pauses)
- Overshoot probability and magnitude scaling
- Idle drift amplitude and frequency

**How it works:**
- Profile entity stores a `long movementSeed`
- On session start, create `MovementPersona` from seed using a seeded `Random`
- All downstream components sample their parameters from the persona rather than global constants
- Persona parameters should fall within the natural human range but stay fixed for that profile

**References:**
- SapiMouse dataset (120 users, each with distinct motor signatures) — extract per-user parameter distributions to define realistic persona ranges
- Boğaziçi dataset (24 users, 2,550 hours) — long-session consistency analysis

**Key parameters to derive from seed:**
- `speedFactor`: 0.7–1.3 multiplier on Fitts's Law MT
- `curvatureBias`: -1.0 to 1.0 (negative = tends left, positive = tends right)
- `tremorAmplitude`: 0.3–1.5 pixels
- `tremorFrequencyCenter`: 8.0–12.0 Hz
- `clickOffsetBiasX`: -2.0 to 2.0 pixels from computed target
- `clickOffsetBiasY`: -2.0 to 2.0 pixels from computed target
- `pauseMeanMs`: 200–600 ms
- `overshootProbability`: 0.1–0.4 for movements over threshold distance
- `idleDriftAmplitude`: 0.5–3.0 pixels

---

## Component 2: Trajectory Generation (Path Shape)

**Purpose:** Generate the spatial path (x, y coordinates) from start to end point. This replaces the current single cubic Bézier approach.

**What detectors check:**
- Straightness index (path length / Euclidean distance) — humans cluster at 1.1–1.4 for aimed movements
- Direction angle distribution — must not cluster at cardinal directions
- Path curvature continuity — no sharp angle changes mid-path

**Approach:** Use the current Bézier curve as the gross path shape but enhance it with:
1. Asymmetric control point placement (human paths curve more in the first half)
2. Micro-perturbations along the path (not just endpoint noise)
3. Direction-dependent curvature (see Component 8)

**References:**
- `ghost-cursor` (github.com/Xetera/ghost-cursor) — current basis, cubic Bézier with randomized control points, Fitts's Law step count
- `NaturalMouseMotion` (github.com/JoonasVali/NaturalMouseMotion) — Java library with pluggable `DeviationProvider` for arc trajectories and `NoiseProvider` for micro-perturbations. MIT license, Maven artifact available. Study its `DefaultDeviationProvider` and `DefaultNoiseProvider`
- `WindMouse` (ben.land/post/2021/04/25/windmouse-human-mouse-movement/) — physics-based approach with gravity (G₀=9) and wind (W₀=3) forces. Produces organic wandering far from target and controlled approach near target. Study for near-target behavior specifically
- `HumanCursor` (github.com/riflosnake/HumanCursor) — Python, MIT, implements variable speed and curvature
- `bezmouse` (github.com/vincentbavitz/bezmouse) — chains Bézier curves with variable momentum

**Key implementation notes:**
- Keep the Bézier curve for gross shape — it's well-understood and produces smooth paths
- Add Perlin noise or similar along the perpendicular axis for micro-irregularities
- The persona's `curvatureBias` should influence control point placement

---

## Component 3: Velocity Profile (Acceleration/Deceleration)

**Purpose:** Control the timing of movement along the path. This is the **single most important anti-detection feature** — constant velocity is the #1 detection signal.

**What detectors check:**
- Velocity profile shape must be bell-shaped (not constant, not stepwise)
- Peak velocity should occur at 35–45% of movement duration (asymmetric, not centered)
- Acceleration must be continuously varying and almost never exactly zero
- Peak velocity ≈ 1.8× average velocity

**Approach:** Apply a minimum-jerk velocity profile with lognormal asymmetry:

```
Base (minimum jerk): v(τ) = 30τ² - 60τ³ + 30τ⁴  (τ = t/T, normalized time)
Asymmetric shift: skew the profile so peak is at τ ≈ 0.38–0.42 instead of 0.5
```

The velocity profile determines the time delta between consecutive path points. Points are spaced evenly along the Bézier parameter, but the time between dispatching them varies according to the velocity curve — fast in the middle, slow at start and end.

**References:**
- Flash & Hogan (1985) "The coordination of arm movements" — the foundational minimum jerk model, produces 5th-order polynomial: `position(τ) = start + (end - start) · [10τ³ - 15τ⁴ + 6τ⁵]`
- Plamondon (1995) sigma-lognormal model — produces the asymmetric velocity profile naturally: `Λ(t; t₀, μ, σ) = [1 / (σ√(2π)(t-t₀))] · exp(-(ln(t-t₀) - μ)² / (2σ²))` with σ ≈ 0.3–0.4
- `NaturalMouseMotion` `SpeedManager` interface — implements acceleration phases, study `DefaultSpeedManager` for a Java reference of non-uniform timing
- Chao Shen dataset (200K+ trajectories, figshare, CC BY 4.0) — extract empirical velocity profiles to validate implementation

**Key parameters:**
- Movement duration from Fitts's Law: `MT = a + b · log₂(D/W + 1)` with a ≈ 200ms, b ≈ 150ms, throughput ~4.0 bits/s
- Add Gaussian noise to MT: SD ≈ 10–15% of calculated MT
- Peak velocity position: sample from Normal(0.40, 0.03) per persona

---

## Component 4: Sub-Movement Decomposition

**Purpose:** Model the ballistic-then-corrective structure of aimed movements. Real mouse pointing consists of a fast primary submovement covering ~75-85% of distance, followed by 0-2 slower corrective submovements.

**What detectors check:**
- Velocity zero-crossings or near-zero points during deceleration phase indicate corrections
- Movements that always land perfectly on the first attempt are suspicious
- The relationship between task difficulty and correction probability

**Approach:**
1. Calculate Index of Difficulty: `ID = log₂(D/W + 1)`
2. If ID > 4 bits, add corrective submovement with probability from persona (0.2–0.5)
3. Primary submovement: covers 75–85% of distance with its own velocity profile
4. Brief pause at transition point (20–50ms of very slow movement, not a full stop)
5. Corrective submovement: covers remaining distance with lower peak velocity and its own bell-shaped profile

**References:**
- Meyer et al. (1988) stochastic optimized submovement model — primary + corrective submovements, endpoint noise SD proportional to average velocity
- Dounskaia et al. (2005) — found submovements in 41% of pointing movements; categorized as "gross" (velocity zero-crossings) and "fine" (small perturbations at low speed)
- BeCAPTCHA-Mouse paper (arxiv.org/pdf/2005.00890) — extracts 37 neuromotor features including sub-movement counts and parameters
- Chao Shen dataset — decompose real trajectories to extract correction probability vs. ID relationship

**Key parameters:**
- Correction probability: `P(correction) = sigmoid(k · (ID - threshold))` where threshold ≈ 3.5 bits
- Primary submovement endpoint: Normal(0.80 · distance, 0.05 · distance) along path direction
- Corrective submovement duration: ~30-40% of primary duration
- Transition pause: 20–50ms

---

## Component 5: Physiological Tremor

**Purpose:** Add the involuntary micro-oscillation present in all human hand movements. Tremor is a known detection signal — its absence flags synthetic input.

**What detectors check:**
- Frequency content in the 8–12 Hz band
- Tremor is present during movement AND during pauses/idle
- Amplitude is small (sub-pixel to ~2 pixels) and varies with fatigue/tension

**Approach:** Overlay a tremor signal on every cursor position:
1. Base oscillation: sum of 2–3 sine waves at frequencies 8–12 Hz with random phases
2. Amplitude modulation: tremor is larger during slow/stopped movement, smaller during fast movement
3. Add broadband noise component (Gaussian, very small amplitude)

```
tremor_x(t) = Σᵢ Aᵢ · sin(2π · fᵢ · t + φᵢ) · amplitude_scale(velocity)
tremor_y(t) = Σᵢ Bᵢ · sin(2π · gᵢ · t + ψᵢ) · amplitude_scale(velocity)
```

Where amplitude_scale decreases as velocity increases (tremor is masked by fast movement).

**References:**
- Nature Digital Medicine (2019) "Population-scale hand tremor analysis via anonymized mouse cursor signals" — confirmed 8–12 Hz physiological tremor detectable in mouse cursor data at population scale, using Fourier analysis on cursor position signals
- Medical literature — normal physiological tremor: 8–12 Hz, amplitude varies by individual
- Boğaziçi dataset (2,550 hours) — analyze frequency content of idle/slow-moving cursor segments

**Key parameters (from persona seed):**
- `tremorFrequencies`: 2–3 frequencies sampled from Uniform(8.0, 12.0) Hz
- `tremorAmplitudes`: per-frequency, sampled from Uniform(0.3, 1.5) pixels
- `tremorPhases`: random per-frequency, fixed for session
- `amplitudeScaleFunction`: linear decay from 1.0 at 0 px/s to 0.1 at >500 px/s

---

## Component 6: Idle Cursor Drift

**Purpose:** Eliminate the "dead cursor" problem — the complete absence of mouse events between intentional actions. Real users' cursors drift slightly even when they're reading, thinking, or waiting.

**What detectors check:**
- Long gaps (>2 seconds) with zero mousemove events are highly suspicious
- Drift should be slow, semi-random, and small in amplitude
- Drift pattern differs from intentional movement (no bell-shaped velocity profile)

**Approach:** Background thread or scheduled task that dispatches small cursor movements during idle periods:
1. After each intentional movement completes, start an idle drift timer
2. Every 50–200ms (variable), dispatch a small movement (0.5–3 pixels)
3. Movement direction follows a **Brownian motion** model — each step's direction is correlated with the previous step
4. Drift stays within a small radius (~20–50 pixels) of the rest position
5. Occasionally (every 5–15 seconds), make a slightly larger "shift" (5–15 pixels) as if adjusting grip
6. Stop drift when the next intentional movement begins

**References:**
- `ghost-cursor` `toggleRandomMove()` — basic implementation of background fidgeting, study for event dispatch pattern
- Boğaziçi dataset — analyze cursor behavior during reading/idle periods to extract drift characteristics
- `NaturalMouseMotion` — no idle drift implementation, but its architecture supports adding one

**Key parameters (from persona seed):**
- `driftIntervalMs`: mean interval between drift events, 80–150ms
- `driftStepSize`: mean step size, 0.5–2.0 pixels
- `driftRadius`: maximum wandering radius from rest position, 20–50 pixels
- `driftCorrelation`: how much each step's direction correlates with previous (0.6–0.9)
- `gripShiftInterval`: seconds between larger adjustments, 5–15s
- `gripShiftSize`: pixels, 5–15

**Implementation note:** The idle drift runs on a separate thread within `InputController` or a dedicated `IdleBehaviorController`. It must coordinate with intentional movements — when `moveMouseTo()` is called, idle drift stops immediately and the intentional movement takes over from the current drifted position.

---

## Component 7: Scroll Behavior

**Purpose:** Make scrolling appear natural with cursor micro-movement during scroll events.

**What detectors check:**
- Cursor should shift slightly during scrolling (real users' hands move on the mouse/trackpad)
- Scroll velocity should vary — humans scroll in bursts with momentum, not at constant speed
- Scroll should decelerate (momentum scrolling)

**Approach:**
1. During each scroll tick, add lateral cursor drift of 1–5 pixels
2. Scroll velocity follows a burst pattern: fast initial ticks, decelerating toward the end
3. Add occasional micro-pauses between scroll bursts (100–300ms)
4. Scroll tick amounts should have realistic variance

**References:**
- Current `InputController.scrollBy()` — already has tick-based scrolling with variance, needs cursor movement added
- `emunium` — implements smooth scrolling with deceleration
- Boğaziçi dataset — analyze scroll events with concurrent cursor position to extract co-movement patterns

**Key changes to existing code:**
- In `InputController.scrollBy()`, add small `dispatchMouseMove()` calls with lateral offset during each tick
- Add momentum model: each successive tick is slightly smaller than the previous
- Vary inter-tick delays to simulate burst-pause-burst pattern

---

## Component 8: Direction-Dependent Dynamics

**Purpose:** Account for the biomechanical fact that mouse movements in different directions have different characteristics. Leftward movements are faster with fewer corrections than rightward; vertical movements differ from horizontal.

**What detectors check:**
- Movement duration should vary by direction for same distance
- Curvature patterns differ by direction
- Sub-movement probability differs by direction

**Approach:** Apply direction-dependent multipliers to timing and curvature parameters:
- Calculate movement angle θ from start to end
- Look up direction multipliers from a table derived from empirical data
- Apply multipliers to: movement duration, curvature magnitude, correction probability

**References:**
- PMC article "The Effect of Direction on Cursor Moving Kinematics" (PMC3304147) — documented that leftward movements had higher peak velocity and fewer submovements than other directions, consistent with elbow-dominant single-joint coordination
- Persona seed should influence which directions feel "natural" (handedness simulation)

**Key parameters:**
- Direction multiplier table (8 compass directions):
  - Right (0°): duration × 1.0, curvature × 1.0
  - Down-right (45°): duration × 1.05, curvature × 0.9
  - Down (90°): duration × 1.1, curvature × 1.1
  - Down-left (135°): duration × 1.0, curvature × 1.0
  - Left (180°): duration × 0.9, curvature × 0.85
  - Up-left (225°): duration × 1.05, curvature × 1.0
  - Up (270°): duration × 1.15, curvature × 1.2
  - Up-right (315°): duration × 1.1, curvature × 1.05
- These are starting estimates — calibrate from the Chao Shen dataset

---

## Component 9: Approach Hesitation

**Purpose:** Model the slowdown and micro-adjustments that occur in the final ~10-20% of a movement as the cursor approaches the target.

**What detectors check:**
- Velocity should decrease smoothly near the target (the deceleration phase of the velocity profile)
- Small direction changes in the last few pixels (visual guidance adjustments)
- Brief hesitation (10-30ms of near-zero velocity) just before the click point

**Approach:**
1. The velocity profile (Component 3) already handles gross deceleration
2. Add 2-4 micro-adjustments in the last 15% of path: small (1-3 pixel) direction changes
3. Add a brief near-stop (5-15ms) in the last 5-10 pixels before final position
4. Final approach velocity should be <50 px/s

**References:**
- WindMouse near-target regime — when distance < D₀ (12px), wind force decays and velocity clips to `sqrt(3) * distance`, producing controlled approach. Port this regime specifically
- Intermittent Control model (Gasser et al., ACM TOCHI 2021) — models visual feedback-driven corrections
- Chao Shen dataset — analyze the last 50ms of point-to-point movements for approach characteristics

**Key parameters:**
- Approach zone: last 15% of path length or last 30 pixels, whichever is larger
- Micro-adjustments: 2–4 small direction changes of 1–3 pixels perpendicular to path
- Pre-click near-stop duration: 5–15ms at velocity < 20 px/s
- These values should have variance from the persona

---

## Component 10: Click Behavior

**Purpose:** Make the click event itself realistic — position distribution, hold duration, and relationship to the approach trajectory.

**What detectors check:**
- Click position should follow Gaussian distribution around target center, not uniform
- Small drift between last mousemove and mousedown (hand settles)
- Click hold duration (mousedown to mouseup) should follow realistic distribution
- Double-click timing when applicable

**Approach:**
1. Switch from `getRandomPoint(paddingPercentage)` to `getRandomPointNearCenter()` with Gaussian distribution
2. Add persona-derived bias to click position (consistent slight offset per profile)
3. Add 0.5–2 pixel drift between final mousemove and mousedown positions
4. Click hold duration: Gaussian centered at 80–120ms, never exactly the same

**References:**
- `BoundingBox.getRandomPointNearCenter()` — already exists in your codebase, uses Gaussian distribution. Switch to using this
- Fitts's Law effective width: `We = 4.133 × SD_x` — endpoint scatter SD should be proportional to movement speed
- `NaturalMouseMotion` — no click modeling, gap to fill

**Key parameters (from persona):**
- `clickOffsetBiasX/Y`: consistent per-profile bias, -2 to +2 pixels
- `clickDriftPixels`: 0.5–2.0 pixels between last move and mousedown
- `clickHoldMeanMs`: 80–120ms (Gaussian)
- `clickHoldSDMs`: 15–30ms

---

## Component 11: Event Completeness

**Purpose:** Ensure the full cascade of browser events fires in the correct order, matching what real user input produces.

**What detectors check:**
- Real clicks fire: `mouseover → mouseenter → mousemove(s) → mousedown → mouseup → click`
- Missing `mouseover` or `mouseenter` events reveal synthetic input
- `pointerover`, `pointerenter`, `pointerdown`, `pointerup` should also fire
- `movementX` and `movementY` properties should be non-zero on mousemove events

**Approach:**
1. When the cursor first enters a new element's bounds, dispatch `mouseover` and `mouseenter` events
2. When leaving an element, dispatch `mouseout` and `mouseleave`
3. Ensure `focus` and `blur` fire on interactive elements appropriately
4. The Chromium-side `Input.dispatchMousePath` command should handle this automatically since it goes through the real input pipeline

**References:**
- MDN Web Docs — DOM event ordering specification
- Pydoll behavioral fingerprinting docs (pydoll.tech) — describes expected event cascades
- Transmit Security blog "Bot Detection Based on Input Method Analysis" — details how missing events are detected

**Implementation note:** If using `Input.dispatchMousePath` at the Chromium level (Component 12), the browser's own event dispatch should generate the full cascade naturally. Verify this with testing rather than manually dispatching each event type.

**Verification item:** Confirm that `mousemove` events dispatched via `Input.dispatchMouseEvent` populate `movementX` and `movementY` properties correctly (delta from previous position). Headless browsers that always report these as zero are trivially detectable. Test this early — if CDP doesn't set them automatically, we may need to address it in the Chromium patch.

---

## Component 12: CDP Transport (Chromium Patch)

> **DEFERRED:** This component requires a Chromium rebuild. Implement all other components first using the existing `Input.dispatchMouseEvent` calls (one per path point, with `Thread.sleep(deltaMs)` between them). The `{x, y, deltaMs}` tuple format is designed so that swapping from per-event dispatch to the batch `Input.dispatchMousePath` command is a single method change in `InputController`. Build and validate everything else first — the timing jitter from WebSocket delivery is a secondary concern compared to getting the movement kinematics right.

**Purpose:** Deliver mouse events with timing fidelity that matches real hardware input, eliminating WebSocket jitter as a detection signal.

**What detectors check:**
- Inter-event timing should match a consistent polling rate (125Hz = 8ms, 500Hz = 2ms, 1000Hz = 1ms)
- Low jitter in inter-event intervals (SD < 1ms for real hardware)
- Events should be aligned with compositor frame schedule

**Approach — New CDP command `Input.dispatchMousePath`:**
1. Java computes the full path as an array of `{x: double, y: double, deltaMs: int}` tuples
2. Single CDP call sends the entire array to Chromium
3. Chromium C++ code walks the array, dispatching each event through the real input pipeline
4. Timing between events uses high-resolution timer, optionally aligned to a simulated polling rate
5. Returns completion acknowledgment when the full path has been dispatched

**Protocol definition:**
```
Input.dispatchMousePath:
  parameters:
    - path: array of {x: number, y: number, deltaMs: number}
    - pollingRateHz: number (optional, default 125)
    - button: string (optional, for drag operations)
  returns:
    - finalX: number
    - finalY: number
```

**References:**
- Chromium source `content/browser/renderer_host/input/` — input event routing
- Chromium `Input.dispatchMouseEvent` implementation — starting point for the new command
- CDP protocol definition files — for adding the new command

**Key considerations:**
- The simulated polling rate should match common mouse hardware (125Hz, 500Hz, 1000Hz)
- Each profile's persona could include a `pollingRate` parameter
- Events should go through the same codepath as real OS input for full event cascade generation

---

## Component 13: Fitts's Law Timing

**Purpose:** Ensure total movement duration is realistic for the given distance and target size.

**Current state:** Already partially implemented in `HumanBehavior.fitts()` but used only for step count, not for actual timing.

**What needs to change:**
1. Calculate total movement time from Fitts's Law: `MT = a + b · log₂(D/W + 1)`
2. Add noise to MT (Gaussian, SD = 10-15% of MT)
3. Use MT as the total duration across which the velocity profile is applied
4. The velocity profile (Component 3) distributes the path points across this duration

**References:**
- MacKenzie (1992) — Shannon formulation, typical mouse parameters: a ≈ 200ms, b ≈ 150ms
- Current `HumanBehavior.fitts()` and `generatePath()` — already compute index of difficulty
- Chao Shen dataset — fit Fitts's Law parameters specifically for mouse pointing

**Key parameters (from persona):**
- `fittsA`: 150–250ms (intercept, sampled from persona)
- `fittsB`: 120–180ms/bit (slope, sampled from persona)
- `fittsNoise`: SD as fraction of MT, 0.08–0.15

---

## Component 14: Inter-Action Timing

**Purpose:** Control the pauses between discrete actions (between clicks, between typing and clicking, between scrolling and clicking).

**What detectors check:**
- Pause duration distribution should be long-tailed (log-normal), not uniform or Gaussian
- Pauses should reflect cognitive load (complex forms → longer pauses)
- Typing-to-mouse transition should include a natural delay

**Approach:**
- Sample inter-action pauses from log-normal distribution
- Persona defines baseline pause parameters
- Actual delay injection is handled transparently by `SessionContext` (Component 15) — when any action method is called, the controller checks what the last action was and when it happened, then injects an appropriate delay before executing. Script authors write `page.click(A); page.click(B);` and the framework handles realistic pacing automatically
- Script authors can influence timing via `ActionIntent` (Component 16) for context-appropriate hesitation

**References:**
- Boğaziçi dataset — extract inter-action pause distributions from natural browsing
- Current `InteractionOptions` — already has delay ranges, but they're uniform distributions. Switch to log-normal

**Key parameters (from persona):**
- `pauseLogMean`: log-space mean, persona-dependent
- `pauseLogSD`: log-space SD, 0.3–0.6
- `typingToMouseDelay`: additional 100–400ms when switching from keyboard to mouse

---

## Component 15: Session Context

**Purpose:** Track cumulative session behavior and use it to dynamically adjust timing and movement characteristics. Toggleable via `InteractionOptions`. Addresses session-level detection signals (click density, movement-to-idle ratio, action pacing) and keyboard-mouse coordination.

**What detectors check:**
- Total mouse distance traveled per session, ratio of clicks to movements, movement-to-idle ratio
- Bursts of rapid clicks followed by no activity — unnatural pacing
- Keyboard and mouse being used simultaneously (real users don't do this)
- Time-on-page before first interaction (bots click immediately)
- Navigation flow timing (visiting checkout in 0.5s without browsing products)

**How it works:**

The `SessionContext` is a stateful object owned by `InputController` that records every action as it happens. It does NOT pre-plan the session — it adjusts the *next* action's behavior based on what has already occurred.

**Data tracked:**
- `lastActionType`: enum — CLICK, TYPE, SCROLL, HOVER, IDLE
- `lastActionTimestamp`: when the last action completed
- `recentActionTimestamps`: rolling window (last 30 seconds) of all action timestamps
- `totalDistanceMoved`: cumulative cursor distance in pixels this session
- `totalClicks`: cumulative click count this session
- `sessionStartTime`: when the session began
- `isTyping`: whether keyboard input is currently active

**Automatic adjustments:**
1. **Activity density throttling:** If `recentActionTimestamps` shows >10 actions in the last 10 seconds, automatically inject an additional pause (500–2000ms) before the next action, scaling with density
2. **Input mode transitions:** When switching from typing to mouse (`lastActionType == TYPE` and new action is mouse-based), inject keyboard-to-mouse transition delay (150–400ms from persona). When switching from mouse to typing, inject mouse-to-keyboard delay (100–300ms)
3. **Idle drift coordination:** When `isTyping == true`, idle cursor drift stops (hand is on keyboard). Drift resumes after typing ends + transition delay
4. **Post-idle re-engagement:** If cursor has been idle for >3 seconds, the next movement starts with slightly lower initial acceleration (hand re-engaging mouse)
5. **First interaction delay:** After page navigation, enforce a minimum delay before the first action (500–2000ms) to simulate page scanning/reading

**Integration point:** Every public action method in `InputController` (`click`, `type`, `scrollBy`, `hover`) checks `SessionContext` at entry and automatically applies appropriate delays. Script authors don't need to think about pacing — it's transparent.

**Toggleable:** Controlled via `InteractionOptions.sessionContextEnabled` (default: true). When disabled, no automatic pacing adjustments occur and scripts run at whatever speed they're written.

**References:**
- Boğaziçi dataset — analyze session-level activity density distributions and input mode transitions
- reCAPTCHA v3 — known to analyze "entire engagement" across multiple pages over time
- ACM paper "Detection of Advanced Web Bots by Combining Web Logs with Mouse Behavioural Biometrics" — combines server-side request patterns with client-side mouse behavior

**Key parameters (from persona):**
- `activityDensityThreshold`: actions per 10-second window before throttling kicks in, 8–15
- `keyboardToMouseDelayMs`: 150–400ms
- `mouseToKeyboardDelayMs`: 100–300ms
- `postIdleSlowdownThreshold`: seconds of idle before re-engagement slowdown applies, 2–5s
- `firstInteractionDelayMs`: 500–2000ms after page load

---

## Component 16: Action Intent

**Purpose:** Allow script authors to signal the semantic intent of an action so the framework can apply context-appropriate hesitation and timing. The framework does NOT attempt to infer intent from selectors — that would be fragile and inaccurate.

**API:**

```java
public enum ActionIntent {
    /** Default. Standard timing, no special behavior. */
    DEFAULT,

    /** Confirming a significant action (form submit, purchase, delete).
     *  Adds pre-action hesitation (200-800ms) simulating review/confirmation. */
    CONFIRM,

    /** Navigating between pages or sections.
     *  Minimal hesitation, confident movement. */
    NAVIGATE,

    /** Casual interaction (opening menus, toggling options).
     *  Standard timing with natural variance. */
    CASUAL,

    /** Precise interaction (small targets, sliders, date pickers).
     *  Slower approach, higher correction probability, tighter click positioning. */
    PRECISE
}
```

**Usage in scripts:**
```java
page.click("#add-to-cart", ActionIntent.CONFIRM);
page.click("#next-page", ActionIntent.NAVIGATE);
page.click(".date-picker-day", ActionIntent.PRECISE);
page.click("#hamburger-menu", ActionIntent.CASUAL);
page.click("#submit-order", ActionIntent.CONFIRM);

// Default when omitted — backward compatible
page.click("#some-button");
```

**How intent modifies behavior:**
- `CONFIRM`: Adds 200–800ms pre-click hesitation (persona-scaled). Simulates a human reviewing inputs or pausing before committing. Movement speed is slightly slower in the final approach.
- `NAVIGATE`: Minimal hesitation (0–50ms). Confident, faster movement. Lower correction probability.
- `CASUAL`: Standard persona timing. No special modifications.
- `PRECISE`: Reduces movement speed by 20–30%. Increases sub-movement correction probability by 1.5×. Uses tighter Gaussian click positioning (smaller SD). Adds 1–2 extra micro-adjustments in approach phase.
- `DEFAULT`: Equivalent to `CASUAL`.

**Implementation:** `ActionIntent` is passed through `InputController.click()` down to `MousePathBuilder` and `performClick()`, where it applies multipliers to the relevant parameters from the persona.

---

## Component 17: Realistic Typing Mistakes

**Purpose:** Simulate natural human typing errors — real users don't type perfectly. The current implementation produces flawless keystroke sequences every time, which is a detectable signal for typing-focused bot detection.

**Toggleable:** Controlled via `InteractionOptions.realisticTyposEnabled` (default: false). When enabled, the typing system occasionally introduces mistakes and self-corrections.

**How it works:**
1. Before dispatching each character, check if a typo should occur (probability from persona, e.g. 1–4% per character)
2. If a typo triggers, substitute the intended character with a realistic wrong key based on physical keyboard adjacency
3. Continue typing 1–4 more characters before "noticing" the mistake (or notice at end of current word, whichever comes first)
4. Backspace to the error position with rapid keystrokes (faster than normal typing, ~30-60ms per backspace)
5. Re-type the correct characters from the error position onward
6. Brief hesitation after correction (50–150ms) before resuming normal speed

**Common error patterns to model (requires additional research):**
- **Adjacent key substitutions:** physical keyboard neighbors (e.g., `a→s`, `s→d`, `k→l`, `;→'`, `i→o`)
- **Transpositions:** swapping two adjacent characters (e.g., "teh" for "the")
- **Double-tap errors:** repeating a character (e.g., "thee" for "the")
- **Shift timing errors:** missing or adding shift on boundary characters (e.g., `A` at start of word when not intended, or lowercase after period)
- **Skip errors:** omitting a character entirely

**Error probability modifiers:**
- Higher error rate for uncommon letter sequences
- Lower error rate for very common words (muscle memory)
- Higher error rate at higher typing speeds
- Error rate influenced by persona (some "typists" are more accurate than others)

> **LOWEST PRIORITY.** This component requires dedicated research into real typing error distributions, keyboard adjacency mapping, and error detection/correction patterns. Implement after all mouse movement components are complete and validated. Research should cover publicly available typing datasets with error data to calibrate error rates and patterns.

**References:**
- Additional research needed — look for typing error datasets, keyboard adjacency studies, and HCI papers on typing error patterns and correction behavior

**Key parameters (from persona):**
- `typoBaseProbability`: 0.01–0.04 per character
- `charsBeforeNoticing`: 1–4 characters after the error
- `backspaceDelayMs`: 30–60ms per backspace (faster than normal typing)
- `postCorrectionPauseMs`: 50–150ms

---

## Component 18: CDP Batch Typing (Chromium Patch)

**Purpose:** Eliminate the CDP call frequency bottleneck for typing. The current implementation dispatches individual `Input.dispatchKeyEvent` calls for each keydown/keyup pair, which hits rate limits at high typing speeds and introduces latency.

> **DEFERRED:** Requires a Chromium rebuild, same as Component 12. Use the existing per-character `Input.dispatchKeyEvent` dispatch for now. The realistic typing logic (delays, context-aware timing, typo simulation) should all be implemented in Java first using the current CDP calls. When the Chromium patch is available, swap the transport layer.

**Approach — New CDP command `Input.dispatchKeySequence`:**
1. Java computes the full keystroke sequence as an array of `{key: string, deltaMs: int, modifiers: int}` tuples
2. Single CDP call sends the entire array to Chromium
3. Chromium C++ code walks the array, dispatching each keydown/keyup pair through the real input pipeline with correct timing
4. Handles special keys: Backspace, Enter, Shift (hold/release across multiple characters), arrow keys, modifier combinations
5. Returns completion acknowledgment when the full sequence has been dispatched

**Protocol definition:**
```
Input.dispatchKeySequence:
  parameters:
    - keystrokes: array of {key: string, deltaMs: number, type: "press"|"down"|"up", modifiers: number}
  returns:
    - completed: boolean
    - keystrokesDispatched: number
```

**Key considerations:**
- The `type: "press"` shorthand dispatches both keydown and keyup with a brief hold (50-100ms)
- `type: "down"` and `type: "up"` allow explicit shift/ctrl hold across multiple characters
- Typo simulation (Component 17) generates the full corrected sequence including backspaces in Java, then sends the complete sequence to Chromium — the C++ side doesn't need to know about typo logic
- All timing values are pre-computed in Java and subject to the global speed multiplier

**References:**
- Chromium source `Input.dispatchKeyEvent` implementation — starting point
- Current `InputController.pressKey()` and `type()` — understand existing keycode mapping and modifier handling that must be ported to C++

---

## Datasets for Calibration

| Dataset | URL | Primary Use |
|---------|-----|-------------|
| Chao Shen (200K trajectories) | figshare.com/articles/dataset/5619313 | Velocity profiles, sub-movement decomposition, Fitts's Law parameters, direction effects |
| Boğaziçi (2,550 hours) | data.mendeley.com/datasets/w6cxr8yc7p/2 | Idle drift, pause distributions, session-level patterns, scroll behavior |
| BeCAPTCHA-Mouse | github.com/BiDAlab/BeCAPTCHA-Mouse | Human vs. bot comparison, sigma-lognormal features, validation |
| SapiMouse (120 users) | ms.sapientia.ro/~manyi/sapimouse | Per-user motor signatures, persona parameter ranges |

---

## Implementation Order

The components should be implemented in this order, based on detection impact and dependency chain:

1. **Component 1: Movement Persona** — foundation for all other components
2. **Component 3: Velocity Profile** — highest detection impact, no dependencies beyond persona
3. **Component 13: Fitts's Law Timing** — provides duration for velocity profile
4. **Component 2: Trajectory Generation** — enhance existing Bézier with noise and asymmetry
5. **Component 5: Physiological Tremor** — overlay on all cursor positions
6. **Component 4: Sub-Movement Decomposition** — adds corrective movements for difficult targets
7. **Component 9: Approach Hesitation** — refines near-target behavior
8. **Component 10: Click Behavior** — Gaussian positioning, drift, hold duration
9. **Component 16: Action Intent** — enum and parameter multipliers, needed before SessionContext
10. **Component 15: Session Context** — transparent inter-action pacing, keyboard-mouse coordination
11. **Component 6: Idle Cursor Drift** — eliminates dead cursor between actions (coordinates with SessionContext)
12. **Component 7: Scroll Behavior** — adds cursor movement during scrolling
13. **Component 8: Direction-Dependent Dynamics** — fine-tuning, lower priority
14. **Component 14: Inter-Action Timing** — log-normal pause distributions (driven by SessionContext)
15. **Component 11: Event Completeness** — verify full event cascade, movementX/movementY check
16. **Component 12: CDP Transport (Mouse)** — Chromium patch, **deferred until Chromium rebuild is possible**
17. **Component 17: Realistic Typing Mistakes** — **lowest priority**, requires additional research into error distributions
18. **Component 18: CDP Batch Typing** — Chromium patch, **deferred until Chromium rebuild is possible**

---

## Files to Add
- `MovementPersona.java` — per-profile behavioral parameter set
- `VelocityProfile.java` — minimum jerk + lognormal asymmetry velocity computation
- `TremorGenerator.java` — physiological tremor signal synthesis
- `IdleDriftController.java` — background cursor drift during idle periods
- `SubMovementPlanner.java` — ballistic + corrective movement decomposition
- `MousePathBuilder.java` — orchestrates all components into final `{x, y, deltaMs}` array
- `SessionContext.java` — tracks session history, applies transparent inter-action pacing and input mode transitions
- `ActionIntent.java` — enum for script authors to signal action semantics (CONFIRM, NAVIGATE, CASUAL, PRECISE, DEFAULT)
- `TypoSimulator.java` — generates realistic typing errors and correction sequences (lowest priority, pending research)

## Files to Update
- `HumanBehavior.java` — integrate new velocity profile, deprecate uniform timing
- `InputController.java` — use new path builder, add idle drift coordination, add scroll cursor movement, integrate SessionContext checks at entry of every action method, accept ActionIntent parameter on click methods, apply global speed multiplier to all computed delays
- `InteractionOptions.java` — add persona-related options, log-normal pause parameters, `sessionContextEnabled` toggle, `speedMultiplier` field, `realisticTyposEnabled` toggle
- `BoundingBox.java` — switch click point generation to Gaussian (already has `getRandomPointNearCenter`)
- `Page.java` — expose `click(selector, ActionIntent)` overloads in public API

## Files to Remove
- None — existing classes are refactored, not removed
