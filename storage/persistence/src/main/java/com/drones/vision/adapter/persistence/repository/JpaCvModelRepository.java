package com.drones.vision.adapter.persistence.repository;

import com.drones.vision.adapter.persistence.config.JpaOperations;
import com.drones.vision.adapter.persistence.entity.CvModelEntity;
import com.drones.vision.adapter.persistence.entity.CvModelId;
import com.drones.vision.adapter.persistence.mapper.CvModelMapper;
import com.drones.vision.learning.domain.model.CvModelRecord;
import com.drones.vision.learning.domain.model.ModelStatus;
import com.drones.vision.learning.domain.port.CvModelRepositoryPort;

import jakarta.persistence.EntityManagerFactory;

import java.util.List;
import java.util.Optional;

/**
 * {@link CvModelRepositoryPort} backed by Postgres via plain JPA (see {@link JpaOperations}) —
 * docs/plans/active/CV-SETTINGS-PLAN.md §5.3, CV-SETTINGS wave W3.
 *
 * <p>{@link #save} is a {@code merge} on the composite {@code (model_id, version)} key — a pair
 * seen before is replaced in place, a new pair is added, matching the port's own contract.
 * {@link #findLive} does not itself enforce "exactly one LIVE row" — that invariant is {@code
 * ModelRegistryService}'s job (W4-app), same division of responsibility the port's own javadoc
 * spells out.
 */
public final class JpaCvModelRepository implements CvModelRepositoryPort {

    private final JpaOperations jpa;

    public JpaCvModelRepository(EntityManagerFactory entityManagerFactory) {
        this.jpa = new JpaOperations(entityManagerFactory);
    }

    @Override
    public Optional<CvModelRecord> findByIdAndVersion(String modelId, String version) {
        return jpa.read(em -> Optional.ofNullable(em.find(CvModelEntity.class, new CvModelId(modelId, version))))
                .map(CvModelMapper::toDomain);
    }

    @Override
    public List<CvModelRecord> findAll() {
        return jpa.read(em -> em.createQuery("select m from CvModelEntity m", CvModelEntity.class)
                        .getResultList())
                .stream()
                .map(CvModelMapper::toDomain)
                .toList();
    }

    @Override
    public Optional<CvModelRecord> findLive() {
        return jpa.read(em -> em.createQuery(
                        "select m from CvModelEntity m where m.status = :status", CvModelEntity.class)
                        .setParameter("status", ModelStatus.LIVE)
                        .setMaxResults(1)
                        .getResultList())
                .stream()
                .findFirst()
                .map(CvModelMapper::toDomain);
    }

    @Override
    public CvModelRecord save(CvModelRecord model) {
        CvModelEntity saved = jpa.write(em -> em.merge(CvModelMapper.toEntity(model)));
        return CvModelMapper.toDomain(saved);
    }
}
