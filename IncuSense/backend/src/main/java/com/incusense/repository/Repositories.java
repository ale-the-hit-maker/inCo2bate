package com.incusense.repository;

import com.incusense.model.Measurement;
import com.incusense.model.MeasurementId;
import com.incusense.model.SensingHub;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

public final class Repositories {

    private Repositories() {
    }

    @Repository
    public interface SensingHubRepository extends JpaRepository<SensingHub, Long> {
        Optional<SensingHub> findByHubKey(String hubKey);
    }

    @Repository
    public interface MeasurementRepository extends JpaRepository<Measurement, MeasurementId> {
        @Query("select m from Measurement m join fetch m.hub order by m.id.recordedAt desc")
        List<Measurement> findAllByOrderByIdRecordedAtDesc(Pageable pageable);

        @Query("select m from Measurement m join fetch m.hub where m.hub.hubKey = :hubKey order by m.id.recordedAt desc")
        List<Measurement> findByHubHubKeyOrderByIdRecordedAtDesc(String hubKey, Pageable pageable);
    }
}
