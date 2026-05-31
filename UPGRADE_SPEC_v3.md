# IncuSense v3 — Implementation Specification (Execution Document)

> **Audience:** the executor model (Sonnet) that will apply these changes. This document is **self-contained**: it does not assume any prior conversation. Read §0 and §1 fully before editing anything.
>
> **Repository root:** `D:\USERDATA\Documents\GitHub\inCo2bate`
> Two sub-projects:
> - `Biosentry_Firmware\` — ESP32-C3 firmware (PlatformIO, Arduino framework).
> - `IncuSense\` — platform: Spring Boot backend (`backend\`), static frontend (`frontend\`), MQTT broker (Mosquitto), TimescaleDB, Nginx, orchestrated by `docker-compose.yml`.

---

## 0. How to use this document / hard constraints

1. **Database changes go ONLY through new Flyway migrations** (`V2__`, `V3__`, `V4__` in `IncuSense\backend\src\main\resources\db\migration\`). **Never edit `V1__init_schema.sql`.** The backend runs with `spring.jpa.hibernate.ddl-auto: validate` — if a JPA entity and the DB schema diverge in column name, type, or nullability, **the application will not start**. Every new column must have an exactly matching entity field and vice versa.
2. **MQTT payload/topic compatibility:** the firmware and backend must agree on topic structure and JSON field names. The contract is defined in §3 and §8. Do not rename existing JSON fields.
3. **Tenant isolation is a security requirement, not a feature.** Every data endpoint must scope results to the authenticated user's lab and return `403` for cross-lab access. This is the #1 risk of this upgrade.
4. **Do not introduce prohibited side effects.** No destructive DB operations beyond the migrations specified. The only "delete" allowed is the user-initiated removal of a notification contact (§5.4), which the UI must confirm.
5. **Execution order matters** — follow §9.
6. After each major block, validate against the **acceptance criteria** in §10.

---

## 1. Verified current state (baseline the executor must respect)

**Auth (single user):**
- `IncuSense\backend\src\main\java\com\incusense\service\UserDetailsServiceImpl.java` compares the username against a single admin loaded from `application.yml` (`incusense.security.admin-username` / `admin-password`). There is **no users table**.
- `security\JwtTokenProvider.java`: JWT `subject = username`, no tenant claim. `createToken(UserDetails)`.
- `security\JwtAuthenticationFilter.java`: standard bearer-token filter, loads `UserDetails`, sets `SecurityContext`.
- `config\SecurityConfig.java`: `BCryptPasswordEncoder`; `permitAll` on `/actuator/health`, `/api/auth/**`, `/ws/**`; everything else `authenticated`. CORS `allowedOriginPatterns("*")`.

**MQTT (flat topic):**
- Firmware publishes to `incusense/hubs/{hubKey}/telemetry|status`, subscribes `.../commands`, publishes `.../ack`. `hubKey` = `lab_alpha_inc_01_node_a` (compile-time `MQTT_HUB_ID` in `Biosentry_Firmware\src\main.cpp`).
- Backend subscribes `incusense/hubs/+/telemetry` (`config\MqttConfig.java` + `application.yml` `incusense.mqtt.topic`).
- `service\MqttIngestionService.java`: regex `incusense/hubs/([^/]+)/telemetry`; **auto-creates** `new SensingHub(hubKey, hubKey)` on first message with **no lab association**.

**Data model:**
- `model\SensingHub.java` → table `sensing_hubs` (`id`, `hub_key` unique, `display_name`, `created_at`, `updated_at`).
- `model\Measurement.java` + `MeasurementId.java` → table `measurements`, composite PK `(hub_id, recorded_at)`, **Timescale hypertable**, 5 NOT NULL doubles: `co2_ppm, heater_temp, env_temp, env_hum, rail_12v`. **`raw_adc` and `sensor_response` are NOT persisted and NOT published** (the firmware computes them in `Task_Sensing` but `publishTelemetry()` serializes only the 6 base fields).
- `dto\Dtos.java`: `MeasurementPayload` has 6 fields with `@JsonProperty` snake_case mapping.
- `repository\Repositories.java`: `SensingHubRepository`, `MeasurementRepository` (with `find6MonthHistory()` native Timescale query, **no lab filter**), `HistoryProjection`.

**Alerts (ephemeral):**
- `service\AlertService.java`: evaluates thresholds, keeps an in-memory `ArrayDeque` (max 100). **No persistence, no notification, no cooldown.** Lost on restart.
- Thresholds: CO2 `<30000 || >70000` → WARN; envTemp `<35.5 || >38.5` → WARN; envHum `<80 || >99.5` → INFO; rail12v `<11.5 || >12.6` → CRITICAL.

**Calibration:** `service\CalibrationService.java` keeps offsets in-memory; **does not publish MQTT commands** (the `commands` topic is subscribed by firmware but nothing publishes to it — pre-existing gap, out of scope here).

**Realtime:** `config\WebSocketConfig.java` exposes STOMP `/ws`, simple broker `/topic`, prefix `/app`. `MqttIngestionService` broadcasts to `/topic/measurements` and `/topic/alerts`. **The frontend does not use WebSocket** — `frontend\js\dashboard.js` polls REST every 5 s.

**Frontend:** static files served by Nginx. `index.html` (login, prefilled `admin`/`admin123`), `dashboard.html`, `js/api.js` (JWT in `localStorage` key `incusense.jwt`), `js/dashboard.js`, `css/style.css` (dark neon theme).

**Build:** `backend\pom.xml` — Spring Boot 3.2.5, Java 21, Hibernate 6, JJWT 0.12.5, spring-integration-mqtt, **no mail starter**, Lombok present.

---

## 2. Finalized decisions

| ID | Decision | Rationale |
|----|----------|-----------|
| **D1** | Alert delivery = **Email only (SMTP)** in v1. Webhook deferred to v2. | Confirmed by product owner. Single `AlertNotifier` implementation. |
| **D2** | Firmware lab provisioning = **build flag `MQTT_LAB_ID`** (compile-time). Runtime NVS "claim" deferred to v2. | Lowest complexity/risk; matches existing compile-time `lab/inc/node` pattern. Re-flash to reassign a device. |
| **D3** | Drift model = **physics-grounded, self-referenced baseline** (no external curve required yet). Power-law calibration + degradation indicators. A real experimental drift dataset will be supplied later and slotted into `drift_reference_curves`. | The available papers define the response formula, the power-law calibration, degradation markers and the ~7-week stability scale, but contain **no digitizable time-vs-drift curve**. |
| **D4** | T/H cross-sensitivity correction = **structural no-op hook** (coefficients = 0) in v1. | The Na:In₂O₃ sensor is humidity-insensitive above 20 RH%; incubator T/RH are tightly controlled. Wire the hook, don't tune it yet. |
| **D5** | Frontend = **Option A: incremental restyle** of the existing static pages (reuse `api.js`, add pages, enhance CSS, add STOMP live updates). No SPA framework, no build pipeline. | Keeps the REST/STOMP contract frozen → backend impact ≈ 0; low debug surface. |

---

## 3. Target architecture

### 3.1 MQTT topic redesign (tenant-scoped)

**From** (flat): `incusense/hubs/{hubKey}/telemetry`
**To** (lab-scoped):
```
incusense/labs/{labId}/hubs/{hubKey}/telemetry
incusense/labs/{labId}/hubs/{hubKey}/status
incusense/labs/{labId}/hubs/{hubKey}/commands
incusense/labs/{labId}/hubs/{hubKey}/ack
```
- `labId`: immutable slug generated at lab registration. **Format:** `lab_` + 8 lowercase hex chars (e.g. `lab_3f9a1c2e`). The default/migrated lab keeps slug `lab_alpha` so the existing firmware/simulation keeps working.
- Backend subscribes: `incusense/labs/+/hubs/+/telemetry` **and** `incusense/labs/+/hubs/+/status`.
- New ingestion regex: `^incusense/labs/([^/]+)/hubs/([^/]+)/telemetry$` → group(1)=`labId`, group(2)=`hubKey`.
- **Security rule:** ingestion resolves the hub only if `labId` exists in `labs`. Telemetry for an unknown `labId` is dropped and logged at WARN. This replaces the unconditional auto-create.

### 3.2 Entity-relationship (additions)

```
labs (1) ──< (N) users
labs (1) ──< (N) sensing_hubs ──< (N) measurements   [measurements gains raw_adc, sensor_response]
labs (1) ──< (N) notification_contacts
sensing_hubs (1) ──< (N) alerts
sensing_hubs (1) ── (1) sensor_health ──> (0..1) drift_reference_curves
```

---

## 4. Flyway migrations (full SQL)

Create three new files under `IncuSense\backend\src\main\resources\db\migration\`.

### 4.1 `V2__multitenant.sql`
```sql
CREATE TABLE IF NOT EXISTS labs (
    id            BIGSERIAL PRIMARY KEY,
    lab_id        VARCHAR(64)  NOT NULL UNIQUE,
    display_name  VARCHAR(160) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS users (
    id            BIGSERIAL PRIMARY KEY,
    username      VARCHAR(128) NOT NULL UNIQUE,
    email         VARCHAR(254) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(32)  NOT NULL DEFAULT 'LAB_USER',
    lab_id        BIGINT       NOT NULL REFERENCES labs(id) ON DELETE CASCADE,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_users_lab ON users(lab_id);

ALTER TABLE sensing_hubs ADD COLUMN IF NOT EXISTS lab_id BIGINT REFERENCES labs(id) ON DELETE CASCADE;
CREATE INDEX IF NOT EXISTS idx_hubs_lab ON sensing_hubs(lab_id);

-- Default lab for backward compatibility with already-ingested data and current firmware (slug 'lab_alpha')
INSERT INTO labs (lab_id, display_name) VALUES ('lab_alpha', 'Default Lab (migrated)')
    ON CONFLICT (lab_id) DO NOTHING;
UPDATE sensing_hubs SET lab_id = (SELECT id FROM labs WHERE lab_id='lab_alpha') WHERE lab_id IS NULL;
```

### 4.2 `V3__alerting.sql`
```sql
CREATE TABLE IF NOT EXISTS notification_contacts (
    id          BIGSERIAL PRIMARY KEY,
    lab_id      BIGINT       NOT NULL REFERENCES labs(id) ON DELETE CASCADE,
    label       VARCHAR(120) NOT NULL,
    channel     VARCHAR(16)  NOT NULL DEFAULT 'EMAIL',   -- v1: EMAIL only
    target      VARCHAR(512) NOT NULL,                   -- email address
    min_level   VARCHAR(16)  NOT NULL DEFAULT 'WARN',    -- INFO|WARN|CRITICAL
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_contacts_lab ON notification_contacts(lab_id);

CREATE TABLE IF NOT EXISTS alerts (
    id          BIGSERIAL PRIMARY KEY,
    hub_id      BIGINT       NOT NULL REFERENCES sensing_hubs(id) ON DELETE CASCADE,
    level       VARCHAR(16)  NOT NULL,
    rule_key    VARCHAR(64)  NOT NULL,                   -- CO2_RANGE|ENV_TEMP|ENV_HUM|RAIL_12V|SENSOR_DRIFT
    message     VARCHAR(512) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_alerts_hub_time ON alerts(hub_id, created_at DESC);
```

### 4.3 `V4__drift.sql`
```sql
-- Raw telemetry needed for drift monitoring (nullable: backward compatible with old firmware)
ALTER TABLE measurements ADD COLUMN IF NOT EXISTS raw_adc INTEGER;
ALTER TABLE measurements ADD COLUMN IF NOT EXISTS sensor_response DOUBLE PRECISION;

CREATE TABLE IF NOT EXISTS drift_reference_curves (
    id             BIGSERIAL PRIMARY KEY,
    sensor_type    VARCHAR(64)  NOT NULL,
    ref_voltage_v  DOUBLE PRECISION,
    ref_temp_c     DOUBLE PRECISION,
    description    VARCHAR(512),
    points_json    JSONB        NOT NULL,   -- see §8.3 for schema
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS sensor_health (
    hub_id              BIGINT PRIMARY KEY REFERENCES sensing_hubs(id) ON DELETE CASCADE,
    curve_id            BIGINT REFERENCES drift_reference_curves(id),
    install_response    DOUBLE PRECISION,
    operating_hours     DOUBLE PRECISION NOT NULL DEFAULT 0,
    last_response       DOUBLE PRECISION,
    observed_drift_pct  DOUBLE PRECISION,
    projected_eol_at    TIMESTAMPTZ,
    health_status       VARCHAR(16) NOT NULL DEFAULT 'OK',  -- OK|DEGRADING|CRITICAL
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- Seed reference curve for the Na:In2O3 CO2 sensor (power-law calibration; coefficients TBD from dataset).
-- 'a' and 'b' are placeholders to be refined when the experimental dataset is available.
INSERT INTO drift_reference_curves (sensor_type, ref_voltage_v, ref_temp_c, description, points_json)
VALUES ('NaIn2O3_CO2', NULL, 250,
        'Na:In2O3 chemoresistive CO2 sensor @250C. Power-law calibration response=a*ppm^b. Coefficients to be fitted from experimental dataset.',
        '{"model":"power_law","a":null,"b":null,"ref_temp_c":250,"valid_ppm":[250,5000],"stability_weeks":7}'::jsonb);
```

> **JSONB mapping note:** map `points_json` in JPA as `String` with `@JdbcTypeCode(org.hibernate.type.SqlTypes.JSON)` (Hibernate 6 / Spring Boot 3.2 supports this natively against PostgreSQL `jsonb`). Do not use a custom Hibernate type dependency.

---

## 5. Backend changes (file-by-file)

Package root: `IncuSense\backend\src\main\java\com\incusense\`.

### 5.1 New entities (`model\`)
- **`Lab.java`** → table `labs`. Fields: `Long id` (`@GeneratedValue(IDENTITY)`), `String labId` (`@Column(name="lab_id", unique=true, length=64)`), `String displayName` (`length=160`), `Instant createdAt`. `@PrePersist` sets `createdAt`.
- **`AppUser.java`** → table `users`. Fields: `Long id`, `String username` (unique, 128), `String email` (254), `String passwordHash` (`@Column(name="password_hash", length=100)`), `String role` (32, default `"LAB_USER"`), `@ManyToOne(optional=false) @JoinColumn(name="lab_id") Lab lab`, `Instant createdAt`. (Name `AppUser` to avoid clash with Spring Security `User`.)
- **`NotificationContact.java`** → table `notification_contacts`. Fields per §4.2 (`label`, `channel`, `target`, `minLevel` `@Column(name="min_level")`, `enabled`, `@ManyToOne Lab lab`, `Instant createdAt`).
- **`Alert.java`** → table `alerts`. Fields: `Long id`, `@ManyToOne(optional=false) @JoinColumn(name="hub_id") SensingHub hub`, `String level`, `String ruleKey` (`@Column(name="rule_key")`), `String message`, `Instant createdAt`.
- **`DriftReferenceCurve.java`** → table `drift_reference_curves`. Fields: `Long id`, `String sensorType`, `Double refVoltageV`, `Double refTempC`, `String description`, `@JdbcTypeCode(SqlTypes.JSON) String pointsJson`, `Instant createdAt`.
- **`SensorHealth.java`** → table `sensor_health`. PK = `hub_id` (use `@Id Long hubId` + `@OneToOne @MapsId @JoinColumn(name="hub_id") SensingHub hub`, OR a plain `@Id` with `@OneToOne`). Fields per §4.3.

### 5.2 Modify existing entities
- **`SensingHub.java`**: add `@ManyToOne @JoinColumn(name="lab_id") Lab lab` + getter/setter. Add constructor `SensingHub(String hubKey, String displayName, Lab lab)`. Keep existing constructor or update all call sites (the only caller is `MqttIngestionService`, which §5.6 rewrites).
- **`Measurement.java`**: add nullable fields `@Column(name="raw_adc") Integer rawAdc` and `@Column(name="sensor_response") Double sensorResponse` + getters. Add a new constructor including them; keep the existing 7-arg constructor delegating with `null, null`.

### 5.3 Repositories (`repository\Repositories.java`)
Add:
- `LabRepository extends JpaRepository<Lab,Long>` → `Optional<Lab> findByLabId(String labId)`.
- `AppUserRepository extends JpaRepository<AppUser,Long>` → `Optional<AppUser> findByUsername(String username)`, `boolean existsByUsername(String username)`.
- `NotificationContactRepository extends JpaRepository<NotificationContact,Long>` → `List<NotificationContact> findByLab_Id(Long labPk)`, `List<NotificationContact> findByLab_LabIdAndEnabledTrue(String labId)`.
- `AlertRepository extends JpaRepository<Alert,Long>` → `List<Alert> findByHub_Lab_LabIdOrderByCreatedAtDesc(String labId, Pageable p)`.
- `DriftReferenceCurveRepository extends JpaRepository<DriftReferenceCurve,Long>` → `List<DriftReferenceCurve> findBySensorType(String t)`.
- `SensorHealthRepository extends JpaRepository<SensorHealth,Long>`.
- `SensingHubRepository`: add `List<SensingHub> findByLab_LabId(String labId)`, `Optional<SensingHub> findByHubKeyAndLab_LabId(String hubKey, String labId)`.
- `MeasurementRepository`: add lab-scoped variants:
  - `findByHub_Lab_LabIdOrderByIdRecordedAtDesc(String labId, Pageable)`
  - `findByHub_HubKeyAndHub_Lab_LabIdOrderByIdRecordedAtDesc(String hubKey, String labId, Pageable)`
  - modify `find6MonthHistory()` → `find6MonthHistory(@Param("labId") String labId)` adding to the native query: `JOIN sensing_hubs h ON h.id=m.hub_id JOIN labs l ON l.id=h.lab_id WHERE l.lab_id=:labId AND m.recorded_at >= NOW() - INTERVAL '6 months'`.

### 5.4 Security (multi-user + tenant claim)
- **`UserDetailsServiceImpl.java`**: rewrite. Inject `AppUserRepository`. `loadUserByUsername` loads `AppUser`, builds `org.springframework.security.core.userdetails.User` with `passwordHash`, role authority `ROLE_<role>`. Throw `UsernameNotFoundException` if absent. Remove the admin-from-yaml logic.
- **`JwtTokenProvider.java`**: add `createToken(String username, String labId, String role)` that adds `.claim("labId", labId).claim("role", role)`. Add `String getLabId(String token)`. Keep signing as-is.
- **`JwtAuthenticationFilter.java`**: after validation, build the authentication with a custom principal `AuthenticatedLabUser(username, labId, role)` (new small class in `security\`) so controllers can read `labId` via `@AuthenticationPrincipal`. Authorities from role claim.
- **`SecurityConfig.java`**: no change to matchers needed — `/api/auth/**` already covers `register`. Verify `/ws/**` stays permitted (SockJS handshake).
- **Bootstrap admin (`config\BootstrapConfig.java`, new):** an `ApplicationRunner` that, if `users` is empty AND env `INCUSENSE_ADMIN_USERNAME` is set, creates a `Lab` (slug `lab_system`) + admin `AppUser` (BCrypt of `INCUSENSE_ADMIN_PASSWORD`). Prevents first-boot lockout. Idempotent.

### 5.5 DTOs (`dto\Dtos.java`)
Add records:
- `RegisterRequest(@NotBlank String username, @NotBlank String email, @NotBlank String password, String labDisplayName, String existingLabId)` — exactly one of `labDisplayName` / `existingLabId` provided.
- `AuthResponse` → add `String labId` field (update the login/register constructors and `Controllers.login`).
- `LabResponse(String labId, String displayName)`.
- `ContactRequest(@NotBlank String label, String channel, @NotBlank String target, String minLevel, boolean enabled)` and `ContactResponse(Long id, String label, String channel, String target, String minLevel, boolean enabled)`.
- `AlertResponse`: already exists — ensure it is built from the persisted `Alert` (add `ruleKey` field; update `MqttIngestionService`/`AlertService` accordingly).
- `MeasurementPayload`: add `@JsonProperty("raw_adc") Integer rawAdc` and `@JsonProperty("sensor_response") Double sensorResponse` (nullable; keep order after `rail12v`).
- `MeasurementResponse`: optionally surface `rawAdc`/`sensorResponse` (nullable) for the health panel.
- `HealthResponse(String hubKey, String status, Double observedDriftPct, Double operatingHours, Instant projectedEolAt, Long curveId)` and `DriftCurveRequest/Response`.

### 5.6 Services
- **`MqttIngestionService.java`**: 
  - Change regex to `^incusense/labs/([^/]+)/hubs/([^/]+)/telemetry$`; extract `labId` + `hubKey`.
  - Resolve `Lab` via `LabRepository.findByLabId(labId)`; **if absent, drop + log WARN and return** (no auto-create of labs).
  - Resolve/create hub via `SensingHubRepository.findByHubKeyAndLab_LabId(hubKey, labId)` else `save(new SensingHub(hubKey, hubKey, lab))`.
  - Persist `raw_adc`/`sensor_response` from the payload (nullable).
  - Broadcast lab-scoped: `/topic/measurements/{labId}`, alerts to `/topic/alerts/{labId}`, health to `/topic/health/{labId}`.
  - After save, call `DriftMonitoringService.update(hub, measurement)` (§8).
- **`AlertService.java`** (refactor):
  - `evaluate(Measurement)` keeps the same 4 thresholds (§1) but assigns a stable `ruleKey` per rule (`CO2_RANGE`, `ENV_TEMP`, `ENV_HUM`, `RAIL_12V`).
  - **Cooldown:** in-memory `Map<String,Instant>` keyed `hubId+":"+ruleKey`; only persist+notify if `now - last >= incusense.alerting.cooldown-seconds`. Update timestamp on fire.
  - **Persist** each fired alert to `alerts` table (via `AlertRepository`).
  - **Dispatch:** load `notification_contacts` for the hub's lab with `enabled=true` and `min_level <= alert.level` (severity order INFO<WARN<CRITICAL); call `AlertNotifier.notify(contact, alert)` **asynchronously**.
  - `recentAlerts(labId)` → reads from DB (lab-scoped), replaces the in-memory deque.
- **`AlertNotifier` (interface) + `EmailNotifier` (impl):** `EmailNotifier` uses `JavaMailSender` (Spring), sends a plain-text email (subject `[IncuSense][<level>] <hubKey>`, body = message + timestamp + lab). **No-op + log** if `spring.mail.host` is blank. Annotate dispatch path with `@Async`; add `@EnableAsync` (e.g., on a new `AsyncConfig` or the main application class).
- **`DriftMonitoringService` (new):** see §8.
- **`CalibrationService.java`**: make `getCalibration`/`updateCalibration` lab-aware only insofar as the controller authorizes the hub belongs to the caller's lab (keys are already per-hubKey). No structural change required beyond authorization in the controller.

### 5.7 Controllers (`controller\Controllers.java`, or split into `AuthController` + `Controllers`)
- `POST /api/auth/register` → validates uniqueness (`409` if `existsByUsername`), creates/joins `Lab` (generate slug `lab_`+8 hex if new), BCrypt-hashes password, saves `AppUser`, returns `AuthResponse` (token issued with `labId` claim) **including `labId`**.
- `POST /api/auth/login` → unchanged behavior, but build token with `labId`/`role` from the loaded `AppUser`; include `labId` in `AuthResponse`.
- `GET /api/lab` → returns `LabResponse` for the caller's lab (so the UI can show the slug to flash into firmware).
- All existing data endpoints become **lab-scoped** using `@AuthenticationPrincipal AuthenticatedLabUser`:
  - `GET /api/hubs` → `findByLab_LabId`.
  - `GET /api/measurements/latest`, `GET /api/hubs/{hubKey}/measurements` → lab-scoped repos; **403** if `hubKey` not in caller's lab.
  - `GET /api/measurements/history` → pass `labId`.
  - `GET /api/alerts` → `recentAlerts(labId)`.
  - `GET/POST /api/calibration/{hubKey}` → authorize hub∈lab, else 403.
- **Notification contacts CRUD** (all lab-scoped, ownership-checked → 403 on mismatch):
  - `GET /api/notification-contacts`
  - `POST /api/notification-contacts` (validate `channel='EMAIL'`, `target` is a valid email, `minLevel ∈ {INFO,WARN,CRITICAL}`)
  - `PUT /api/notification-contacts/{id}`
  - `DELETE /api/notification-contacts/{id}`
- **Sensor health / drift:**
  - `GET /api/hubs/{hubKey}/health` → `HealthResponse` + series for the chart (observed vs expected).
  - `GET /api/drift-curves`, `POST /api/drift-curves`.
  - `POST /api/hubs/{hubKey}/health/assign-curve` (body: `curveId`, optional `installResponse`).

### 5.8 Config / build
- **`pom.xml`**: add
  ```xml
  <dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-mail</artifactId>
  </dependency>
  ```
- **`application.yml`**: change `incusense.mqtt.topic` default to `incusense/labs/+/hubs/+/telemetry`; add a second subscription for status (see MqttConfig note). Add:
  ```yaml
  spring:
    mail:
      host: ${SMTP_HOST:}
      port: ${SMTP_PORT:587}
      username: ${SMTP_USERNAME:}
      password: ${SMTP_PASSWORD:}
      properties.mail.smtp.starttls.enable: true
  incusense:
    alerting:
      from-address: ${ALERT_FROM:incusense@localhost}
      cooldown-seconds: ${ALERT_COOLDOWN:300}
    drift:
      degrading-eol-days: ${DRIFT_EOL_DAYS:7}
  ```
  Keep `incusense.security.admin-*` for the bootstrap runner.
- **`config\MqttConfig.java`**: the inbound adapter currently takes a single topic. Change to subscribe to **both** `incusense/labs/+/hubs/+/telemetry` and `.../status` (the `MqttPahoMessageDrivenChannelAdapter` constructor accepts varargs topics). Keep QoS 1. The `IntegrationFlow` already forwards by `RECEIVED_TOPIC`; in `MqttIngestionService` branch on telemetry vs status (status updates a hub-online flag — optional, can be logged in v1).
- **`docker-compose.yml`**: add SMTP env vars to the `backend` service (`SMTP_HOST`, `SMTP_PORT`, `SMTP_USERNAME`, `SMTP_PASSWORD`, `ALERT_FROM`) — leave blank by default (email disabled). Optionally add a dev SMTP container (e.g. MailHog) — **optional**, document it but do not require it.

---

## 6. Firmware changes (`Biosentry_Firmware\`)

### 6.1 `src\main.cpp` — lab-scoped topics
In the "Identità modulo / contratto MQTT" section, add the lab id macro and rebuild the topic strings:
```cpp
#ifndef MQTT_LAB_ID
#define MQTT_LAB_ID "lab_alpha"   // [v3] override via build flag: -D MQTT_LAB_ID=\"lab_xxxxxxxx\"
#endif

#define TOPIC_BASE         "incusense/labs/" MQTT_LAB_ID "/hubs/" MQTT_HUB_ID
#define TOPIC_MEASUREMENTS TOPIC_BASE "/telemetry"
#define TOPIC_STATUS       TOPIC_BASE "/status"
#define TOPIC_COMMANDS     TOPIC_BASE "/commands"
#define TOPIC_ACK          TOPIC_BASE "/ack"
```
Leave `MQTT_HUB_ID` (derived from `MQTT_LAB_ID`/`MQTT_INC_ID`/`MQTT_NODE_ID`) and all network logic unchanged.

### 6.2 `src\main.cpp` — publish drift fields
In `publishTelemetry()`, after the existing 6 fields, add:
```cpp
doc["raw_adc"]         = payload.raw_adc;
doc["sensor_response"] = serialized(String(payload.sensor_response, 4));
```
Apply the **same two fields** to `appendBacklog()`'s serialized line so the offline backlog stays consistent with live telemetry. `MQTT_BUFFER_SIZE` (512) is already sufficient. `Payload` already contains `raw_adc` and `sensor_response`.

### 6.3 `platformio.ini`
In the simulation env(s), `MQTT_LAB_ID` defaults to `lab_alpha` (matches the migrated default lab). For a real device, set `-D MQTT_LAB_ID=\"<slug from /api/lab>\"`. No new library needed.

> **Provisioning workflow (D2):** user registers a lab in the app → app shows the `labId` slug (via `GET /api/lab`) → device is compiled/flashed with `-D MQTT_LAB_ID="<slug>"`. Document this in the firmware README.

---

## 7. Frontend changes — Option A (incremental restyle, `IncuSense\frontend\`)

**Contract frozen:** all pages consume the existing REST endpoints + STOMP. No build pipeline.

### 7.1 New / modified pages
- **`register.html` (new):** form (username, email, password; radio "create new lab" → `labDisplayName` OR "join existing lab" → `existingLabId`). On success, POST `/api/auth/register`, store token, then **prominently display the lab slug** (`labId`) with copy-to-clipboard and the hint: *"Use this in the firmware build flag: `-D MQTT_LAB_ID=\"<slug>\"`"*. Then redirect to dashboard.
- **`index.html`:** remove prefilled `admin`/`admin123` values; add a link to `register.html`.
- **`dashboard.html`:** header shows lab display name + slug; add a **hub selector** (scoped to lab) and a new **Sensor Health** panel (status badge OK/DEGRADING/CRITICAL, projected EOL date, a Chart.js chart of observed vs expected response). Keep existing metric cards / history / alerts panels.
- **`contacts.html` (new):** CRUD UI for notification contacts (label, email target, min level, enabled). **Delete must show a confirm dialog.**

### 7.2 `js/api.js`
Add methods (same `request()` wrapper, JWT auto-attached): `register(payload)`, `lab()`, `hubs()`, `measurementsForHub(hubKey,limit)`, `notificationContacts()` + `createContact/updateContact/deleteContact`, `hubHealth(hubKey)`, `driftCurves()`/`assignCurve(...)`. Keep `TOKEN_KEY='incusense.jwt'`.

### 7.3 `js/realtime.js` (new) — live updates
Add CDN scripts (consistent with the existing Chart.js CDN usage) for SockJS + STOMP:
```html
<script src="https://cdn.jsdelivr.net/npm/sockjs-client/dist/sockjs.min.js"></script>
<script src="https://cdn.jsdelivr.net/npm/@stomp/stompjs/bundles/stomp.umd.min.js"></script>
```
Connect to `/ws`, subscribe to `/topic/measurements/{labId}`, `/topic/alerts/{labId}`, `/topic/health/{labId}` (read `labId` from `GET /api/lab` or persist it at login). Update the DOM on push. **Keep the 5 s REST poll as a fallback** if the socket is disconnected.

### 7.4 `css/style.css`
Extend the existing dark-neon theme with: design tokens (CSS custom properties for color/spacing/radius), components for the new pages (forms, contact table, health badge, toast for new alerts), and small transitions on metric-card updates. No framework.

---

## 8. Sensor drift model specification (D3, physics-grounded)

The model is grounded in the response definition and calibration law of the project's sensor; it does **not** require an external time-vs-drift curve to function. A real dataset will refine the coefficients later.

### 8.1 Definitions (from the sensor's own physics)
- **Sensor response** (already computed by firmware, now published): `sensor_response = |I_air − I| / I_air` (current at fixed potential).
- **Calibration law:** `response = a · (ppm)^b` (power-law / allometric). `a, b` live in the reference curve `points_json` (seeded null in `V4`, to be fitted from the dataset).
- **Operating point:** 250 °C, fixed voltage, humidity influence negligible above 20 RH% (so the T/H correction §8.4 is a no-op hook in v1).

### 8.2 `DriftMonitoringService.update(hub, measurement)` pipeline
1. **Warm-up guard:** ignore samples within the first ~2 h after boot/temperature change (short-term stabilization drift). Track per-hub via the first-seen timestamp; simplest v1: skip drift update until `operating_hours > 0.1`.
2. **T/H correction hook (§8.4):** `resp_corr = sensor_response · (1 + kT·(ref_temp − env_temp) + kH·(ref_hum − env_hum))` with `kT = kH = 0` in v1 (no-op).
3. **Operating hours:** accumulate the time delta between consecutive measurements for the hub (cap deltas > e.g. 1 h to avoid counting downtime).
4. **Install baseline:** on first stable clean-air reading (or when a ZERO_CAL is applied), set `install_response = resp_corr` if null.
5. **Observed drift:** `observed_drift_pct = (resp_corr − install_response) / install_response · 100`.
6. **EOL projection:** maintain a slope of `observed_drift_pct` vs `operating_hours` (e.g. exponential moving regression). Project the operating-hours at which `|observed_drift_pct|` crosses a threshold (default 20 %), convert to a wall-clock `projected_eol_at` using the recent average sampling cadence. Bound the horizon by the `stability_weeks` scale (~7 weeks) from the curve.
7. **Status + alert:**
   - `OK` if projected EOL is far;
   - `DEGRADING` if `projected_eol_at` within `incusense.drift.degrading-eol-days` → emit alert `ruleKey='SENSOR_DRIFT'`, level `WARN`, **long cooldown (≥ 24 h)** through `AlertService`;
   - `CRITICAL` if the threshold is already exceeded → level `CRITICAL`.
8. Persist `sensor_health`; broadcast `/topic/health/{labId}`.

### 8.3 `points_json` schema
v1 (power-law calibration, the seeded shape):
```json
{ "model": "power_law", "a": <number|null>, "b": <number|null>,
  "ref_temp_c": 250, "valid_ppm": [250, 5000], "stability_weeks": 7 }
```
Future (when the experimental dataset arrives) it may instead carry digitized aging points:
```json
{ "model": "aging_points", "points": [ {"h": 0, "resp": 1.00}, {"h": 168, "resp": 0.97}, ... ] }
```
`DriftMonitoringService` must handle `model ∈ {power_law, aging_points}` (interpolate the latter).

### 8.4 Degradation indicators (beyond baseline drift)
- **Deviation from the power law:** if periodic calibration points no longer fit `a·ppm^b` (a/b shift) → material aging signal.
- **(v2, requires firmware to publish I–V) I–V asymmetry / α drift:** loss of linearity in `I=k·V^α` is a documented damage marker. Out of scope v1.

### 8.5 v2 hook
When sufficient history exists, the drift slope/projection can be replaced by **Gaussian Process Regression** (probabilistic EOL with confidence interval). Keep the `DriftModel` interface open. Out of scope v1.

---

## 9. Execution order (mandatory)

1. **Foundation:** `V2__multitenant.sql` → new/modified entities (`Lab`, `AppUser`, `SensingHub.lab`) → repositories → security rewrite (`UserDetailsServiceImpl`, `JwtTokenProvider`, `JwtAuthenticationFilter`, `AuthenticatedLabUser`, bootstrap) → `register`/`login`/`/api/lab` endpoints → make all data endpoints lab-scoped.
2. **MQTT + firmware:** firmware lab-scoped topics (§6.1) + publish drift fields (§6.2); backend `MqttConfig` + `MqttIngestionService` new regex/subscriptions + lab-resolved ingest.
3. **Alerting:** `V3__alerting.sql` → `Alert`/`NotificationContact` entities + repos → `AlertService` refactor (cooldown, persist, dispatch) → `AlertNotifier`/`EmailNotifier` + `@EnableAsync` → contacts CRUD endpoints → `pom.xml` mail + `application.yml` SMTP.
4. **Drift:** `V4__drift.sql` → `Measurement` new columns + payload/DTO fields → `DriftReferenceCurve`/`SensorHealth` entities + repos → `DriftMonitoringService` → health/drift endpoints.
5. **Frontend (Option A):** `api.js` methods → `register.html`/`index.html`/`contacts.html` → dashboard hub selector + health panel → `realtime.js` STOMP → `style.css`.

Build/validate the backend after each of steps 1, 3, 4 (entity/schema validation will fail fast if mappings are off).

---

## 10. Acceptance criteria (per feature)

**Multi-tenant:**
- A new user can register → receives a token and a `labId` slug.
- Two users in different labs cannot see each other's hubs/measurements/alerts (cross-lab request → 403).
- Backend starts cleanly (`ddl-auto: validate` passes) with `V2` applied.
- Firmware built with `-D MQTT_LAB_ID="<slug>"` publishes to `incusense/labs/<slug>/hubs/.../telemetry`; backend ingests it under the right lab; telemetry for an unknown slug is dropped + logged.

**Alerting (email):**
- Crossing a threshold persists one `alerts` row and (if SMTP configured + a matching enabled contact) sends exactly one email; repeated violations within the cooldown do **not** spam.
- With SMTP unconfigured, the system logs the would-be notification and does not error.
- Contacts CRUD is lab-scoped; delete asks for confirmation in the UI.

**Drift:**
- Firmware telemetry now includes `raw_adc` + `sensor_response`; backend persists them (nullable for old payloads).
- `GET /api/hubs/{hubKey}/health` returns a status and (once baseline set) an `observed_drift_pct`; a synthetic rising drift produces `DEGRADING` then `CRITICAL` and a `SENSOR_DRIFT` alert.

**Frontend:**
- Login/register/contacts/dashboard render; health panel shows status + chart; live updates arrive over STOMP with REST fallback; no change required to backend endpoints.

---

## 11. Risks & guardrails
- **Flyway/validate:** entity↔column mismatch breaks startup. Double-check names/types/nullability against §4.
- **Tenant isolation:** every data query must filter by `labId`; never trust a `hubKey` path param without verifying lab ownership.
- **Alert storms:** the firmware emits at 1 Hz — the `(hubId, ruleKey)` cooldown is mandatory before persist/notify.
- **Async email:** dispatch must not block the MQTT ingest thread; failures are logged, not propagated.
- **Timescale hypertable:** only `ADD COLUMN` (nullable) on `measurements`; never alter its PK.
- **Backward compatibility:** old firmware (flat topic, no drift fields) — after this upgrade it will publish to the old topic and the backend (now subscribing the lab-scoped topic) will not ingest it. This is expected; the firmware must be re-flashed with the new topics. Document clearly.

---

## 12. Appendix

### Deferred to v2
- Webhook/Push/SMS alert channels.
- Runtime NVS "claim" provisioning for firmware.
- T/H regression coefficient tuning (hook already present, coefficients = 0).
- I–V asymmetry degradation indicator (requires firmware to publish I–V sweep).
- GPR-based probabilistic drift prediction.
- Backend → firmware calibration command publisher (pre-existing gap on the `commands` topic).

### Scientific grounding (sensor physics behind §8)
- Rossi et al., *A New Frontier in CO₂ Sensing: Thermal and Light Activation in Na:In₂O₃* (IEEE Sensors 2024) — Na:In₂O₃ @250 °C, response (G_gas−G_air)/G_air, power-law calibration over 250–5000 ppm, humidity-insensitive >20 RH%, ~7-week stability.
- Della Ciana et al., *Dedicated instrumentation for electrical/thermal characterization of chemiresistive gas sensors* (Rev. Sci. Instrum. 92, 074702, 2021) — response `|(I_air−I)/I_air|` at fixed potential, I–V power law `I=k·V^α`, Arrhenius I–T, PWM supply/measure heater driver (the firmware's thermal model), I–V asymmetry as a long-term damage marker.
- Radogna et al., *ML-Enhanced System … CNT Textile Sensors for Ammonia Detection* (IEEE) — regression-model calibration; Gaussian Process Regression best among LR/SVR/DTR/RFR/GPR (methodological basis for the v2 GPR drift hook).
