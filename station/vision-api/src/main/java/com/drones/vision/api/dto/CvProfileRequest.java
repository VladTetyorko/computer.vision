package com.drones.vision.api.dto;

import com.drones.vision.perception.application.profile.CvProfileSpec;
import com.drones.vision.perception.application.profile.IntentPolicy;
import com.drones.vision.perception.application.profile.IntentPolicyResolver;
import com.drones.vision.perception.domain.model.EventRuleConfig;
import com.drones.vision.perception.domain.model.Intent;
import com.drones.vision.perception.domain.model.ModelRef;

import java.util.List;

/**
 * Body for {@code POST /api/cv/profiles} (create) and {@code PUT /api/cv/profiles/{id}} (update) —
 * one shared request shape for both verbs (docs/plans/active/CV-SETTINGS-PLAN.md &sect;5.1/&sect;5.2's
 * frozen wire contract), this codebase's own precedent ({@code GeofenceZoneRequest}). Deliberately
 * excludes {@code id}/{@code builtIn}/{@code createdAt}/{@code updatedAt} (server-assigned),
 * {@code groupId} (never sent — the owning group comes from the caller's own scope, see {@code
 * CvProfileController}), and {@code eventRule} ({@link CvProfileEventRuleResponse}'s own javadoc:
 * start-time only, never accepted on an update).
 *
 * <p>{@code intent} (docs/plans/active/CV-ORCHESTRATION-PLAN.md &sect;4.7, wave W2.6, Java only —
 * no web surface consumes this field yet) is optional and additive: {@code null} leaves every
 * field below exactly as sent, byte-identical to before this wave. When non-{@code null}, {@link
 * #toSpec()} asks {@link IntentPolicyResolver} for the resolved {@link IntentPolicy} and uses it to
 * seed only the two fields that have an unambiguous "caller left this to the platform" sentinel
 * under this frozen wire shape — a blank {@code model} and an empty {@code labelFilter} — plus the
 * server-synthesized {@code eventRule}'s confidence, which this wire shape never exposes to a
 * caller at all. An explicit, non-blank {@code model} or non-empty {@code labelFilter} always wins
 * over the intent's own choice ("expert tier can still pin a model," &sect;4.7), and {@code
 * Intent#CUSTOM} means exactly that in practice: a caller who sends {@code CUSTOM} together with
 * its own {@code labelFilter} gets that list back unchanged, since a non-empty list already beats
 * the intent's seed. {@code confidenceThreshold}/{@code inferenceFps} are deliberately left alone
 * by this fold — see {@link IntentPolicy}'s own javadoc for why those two fields have no such
 * sentinel under this contract, and are therefore a disclosed, out-of-scope gap for this wave.
 *
 * @param name                human-readable name; must not be blank
 * @param description         human-readable description; must not be {@code null}, may be blank
 * @param model               the CV model id this profile should run — plain string, not a
 *                            versioned {@link ModelRef}; see {@link #toSpec()} for how a version is
 *                            synthesized; blank defers to {@code intent}'s own model when {@code
 *                            intent} is non-{@code null}
 * @param confidenceThreshold minimum confidence to keep a detection, [0,1]
 * @param inferenceFps        target inference sample rate; must be positive
 * @param labelFilter         labels to keep; empty means all labels; empty defers to {@code
 *                            intent}'s own class set when {@code intent} is non-{@code null}
 * @param labelDenyFilter     labels to drop even when {@code labelFilter} would keep them
 * @param detectionEnabled    whether a stream started from this profile runs detection at all
 * @param tracking            tracking configuration a stream started from this profile uses
 * @param intent              the operator's "what am I looking for" pick, or {@code null} to skip
 *                            intent resolution entirely and use the fields above exactly as sent
 */
public record CvProfileRequest(String name, String description, String model, double confidenceThreshold,
                                int inferenceFps, List<String> labelFilter, List<String> labelDenyFilter,
                                boolean detectionEnabled, CvProfileTrackingResponse tracking, Intent intent) {

    /**
     * Sentinel {@link ModelRef#version()} used when a request only ever names a bare model id (no
     * version travels over this wire, matching {@code StartStreamRequest}/{@code
     * PromoteModelRequest}'s own plain-string model fields) — the same {@code "latest"} sentinel
     * {@code CvWiring#configModelCatalog} already synthesizes for a config-catalog-derived {@link
     * com.drones.vision.learning.domain.model.CvModelRecord#version()}, reused here rather than
     * invented a second time.
     */
    private static final String DEFAULT_MODEL_VERSION = "latest";

    /**
     * Maps this request to an application-layer {@link CvProfileSpec}, resolving {@link #intent}
     * (when present) to seed a blank {@code model}/empty {@code labelFilter} and the synthesized
     * {@code eventRule}'s confidence threshold — see this record's own javadoc for exactly which
     * fields intent can and cannot reach under today's wire shape.
     *
     * @return the spec {@link com.drones.vision.perception.application.profile.CvProfileService#create}/
     *         {@link com.drones.vision.perception.application.profile.CvProfileService#update} accept
     * @throws IllegalArgumentException if {@code model} is blank and {@code intent} is {@code
     *                                  null}, if {@code tracking.mode} is unknown, if {@code intent}
     *                                  is {@code Intent#CUSTOM} and {@code labelFilter} is empty, or
     *                                  if any field fails {@link CvProfileSpec}'s own validation
     *                                  (&rarr; 400)
     */
    public CvProfileSpec toSpec() {
        IntentPolicy resolved = intent == null ? null : IntentPolicyResolver.resolve(intent, labelFilter);
        String resolvedModel = model != null && !model.isBlank() ? model
                : resolved != null ? resolved.model().id() : model;
        List<String> resolvedLabelFilter = labelFilter != null && !labelFilter.isEmpty() ? labelFilter
                : resolved != null ? List.copyOf(resolved.classSet()) : labelFilter;
        EventRuleConfig eventRule = resolved == null ? EventRuleConfig.defaults()
                : new EventRuleConfig(EventRuleConfig.defaults().labels(), resolved.reportThreshold(),
                        EventRuleConfig.defaults().consecutiveToOpen(), EventRuleConfig.defaults().absenceToClose());
        return new CvProfileSpec(name, description, new ModelRef(resolvedModel, DEFAULT_MODEL_VERSION),
                confidenceThreshold, inferenceFps, resolvedLabelFilter, labelDenyFilter, detectionEnabled,
                tracking.toTrackingConfig(), eventRule);
    }
}
