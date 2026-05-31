package com.incusense.repository;

import com.incusense.model.Alert;
import com.incusense.model.AppUser;
import com.incusense.model.DriftReferenceCurve;
import com.incusense.model.Lab;
import com.incusense.model.Measurement;
import com.incusense.model.MeasurementId;
import com.incusense.model.NotificationContact;
import com.incusense.model.SensingHub;
import com.incusense.model.SensorHealth;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

public final class Repositories {

    private Repositories() {
    }

    @Repository
    public interface LabRepository extends JpaRepository<Lab, Long> {
        Optional<Lab> findByLabId(String labId);
    }

    @Repository
    public interface AppUserRepository extends JpaRepository<AppUser, Long> {
        Optional<AppUser> findByUsername(String username);

        boolean existsByUsername(String username);
    }

    @Repository
    public interface SensingHubRepository extends JpaRepository<SensingHub, Long> {
        Optional<SensingHub> findByHubKey(String hubKey);

        List<SensingHub> findByLab_LabId(String labId);

        Optional<SensingHub> findByHubKeyAndLab_LabId(String hubKey, String labId);
    }

    @Repository
    public interface MeasurementRepository extends JpaRepository<Measurement, MeasurementId> {
        @Query("select m from Measurement m join fetch m.hub h join fetch h.lab where h.lab.labId = :labId order by m.id.recordedAt desc")
        List<Measurement> findByLabOrderByRecordedAtDesc(@Param("labId") String labId, Pageable pageable);

        @Query("select m from Measurement m join fetch m.hub h join fetch h.lab where h.hubKey = :hubKey and h.lab.labId = :labId order by m.id.recordedAt desc")
        List<Measurement> findByHubAndLabOrderByRecordedAtDesc(@Param("hubKey") String hubKey, @Param("labId") String labId, Pageable pageable);

        @Query(value = "SELECT time_bucket(INTERVAL '1 day', m.recorded_at) AS recordedAt, " +
                "AVG(m.co2_ppm) AS co2Ppm, " +
                "AVG(m.heater_temp) AS heaterTemp, " +
                "AVG(m.env_temp) AS envTemp, " +
                "AVG(m.env_hum) AS envHum, " +
                "AVG(m.rail_12v) AS rail12v " +
                "FROM measurements m " +
                "JOIN sensing_hubs h ON h.id = m.hub_id " +
                "JOIN labs l ON l.id = h.lab_id " +
                "WHERE l.lab_id = :labId AND m.recorded_at >= NOW() - INTERVAL '6 months' " +
                "GROUP BY recordedAt " +
                "ORDER BY recordedAt ASC", nativeQuery = true)
        List<HistoryProjection> find6MonthHistory(@Param("labId") String labId);
    }

    @Repository
    public interface NotificationContactRepository extends JpaRepository<NotificationContact, Long> {
        List<NotificationContact> findByLab_LabId(String labId);

        List<NotificationContact> findByLab_LabIdAndEnabledTrue(String labId);
    }

    @Repository
    public interface AlertRepository extends JpaRepository<Alert, Long> {
        List<Alert> findByHub_Lab_LabIdOrderByCreatedAtDesc(String labId, Pageable pageable);
    }

    @Repository
    public interface DriftReferenceCurveRepository extends JpaRepository<DriftReferenceCurve, Long> {
        List<DriftReferenceCurve> findBySensorType(String sensorType);
    }

    @Repository
    public interface SensorHealthRepository extends JpaRepository<SensorHealth, Long> {
    }

    public interface HistoryProjection {
        java.time.Instant getRecordedAt();

        Double getCo2Ppm();

        Double getHeaterTemp();

        Double getEnvTemp();

        Double getEnvHum();

        Double getRail12v();
    }
}
