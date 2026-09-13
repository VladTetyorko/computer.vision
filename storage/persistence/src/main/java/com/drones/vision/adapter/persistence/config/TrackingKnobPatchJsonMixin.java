package com.drones.vision.adapter.persistence.config;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Jackson mix-in (registered against {@link com.drones.vision.perception.domain.model.TrackingKnobPatch}
 * in {@link PersistenceUnit#start(javax.sql.DataSource, boolean)}) making the {@code tracking} jsonb
 * column's read side tolerant of every pre-{@code V36__cv_profile_patch.sql} row.
 *
 * <p><b>Why this exists — a corrected assumption.</b> Wave W7.2 (docs/plans/active/CV-ORCHESTRATION-PLAN.md
 * &sect;4.7, decision E22) narrowed {@code CvProfileEntity#tracking} from the 10-field {@code
 * TrackingConfig} to the 5-field {@link com.drones.vision.perception.domain.model.TrackingKnobPatch},
 * without reshaping any stored row — the plan going in was that this needed no data migration at all,
 * because this codebase configures {@code FAIL_ON_UNKNOWN_PROPERTIES} nowhere, so Jackson would simply
 * ignore the five extra keys ({@code redetectIouPercent}, {@code maxAgeFrames}, {@code minHits}, {@code
 * reupdateMaxGapMillis}, {@code lock}) a pre-existing row's JSON still carries. That reasoning was wrong:
 * an unconfigured Jackson {@code ObjectMapper}/{@code JsonMapper} FAILS on an unrecognized property by
 * default — "nowhere configured to fail" does not mean "configured to tolerate," it means "runs
 * Jackson's own strict default" — confirmed the hard way when {@code
 * PostgresDockerIntegrationTest.CvProfileRepositoryTests#findAllReturnsEverySavedProfile} and {@code
 * v29MigrationSeedsFourBuiltInCvProfilesWithZeroBindings} both threw {@code
 * UnrecognizedPropertyException: Unrecognized property "lock"} against the real V29 built-in rows on
 * the first scoped build of this wave.
 *
 * <p>This mix-in is the actual fix, and it is deliberately scoped to exactly the one type whose shape
 * changed — not a blanket {@code FAIL_ON_UNKNOWN_PROPERTIES=false} across every jsonb column in this
 * module (that would silently hide a genuine typo/shape bug in any *other* entity's stored JSON too).
 * It is registered on the adapter side ({@link PersistenceUnit}, which already depends on {@code
 * tools.jackson.core:jackson-databind} directly — see that module's {@code pom.xml} comment) rather
 * than as an annotation directly on {@code TrackingKnobPatch} itself, because that type lives in
 * {@code contexts/vision-perception}'s {@code domain.model} package, which the java-clean-code skill
 * (&sect;6) and {@code ArchitectureTest} keep framework-free — no Jackson (or any other framework)
 * annotation belongs on a domain record. A private, empty interface here carries the annotation
 * instead; Jackson attaches it to {@code TrackingKnobPatch} purely by mix-in registration, so the
 * domain type itself never imports Jackson at all.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
interface TrackingKnobPatchJsonMixin {
}
