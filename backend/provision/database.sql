-- Generated from Flyway migrations V1-V3.
-- Creates the AirSense schema in the current database; simulator rows are in simulator.sql.
SET TIME ZONE 'UTC';

-- Source: V1__storage.sql
CREATE TABLE users (
 id uuid PRIMARY KEY, username varchar(128) NOT NULL UNIQUE,
 password_hash text NOT NULL, role text NOT NULL CHECK(role='ADMIN'), enabled boolean NOT NULL DEFAULT true,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE devices (
 device_id varchar(64) PRIMARY KEY CHECK(device_id ~ '^[A-Z0-9][A-Z0-9_-]{0,63}$'),
 location varchar(128) NOT NULL, firmware_version varchar(64) NOT NULL DEFAULT 'unknown',
 capabilities jsonb NOT NULL DEFAULT '[]', enabled boolean NOT NULL DEFAULT true,
 created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE acquisition_profiles (
 profile_id varchar(64) PRIMARY KEY, device_id varchar(64) NOT NULL REFERENCES devices,
 kind text NOT NULL CHECK(kind IN ('SIMULATOR','HARDWARE')),
 chip_model text NOT NULL CHECK(chip_model='ESP32-S3'), mode text NOT NULL CHECK(mode='ONESHOT'),
 resolution_bits integer NOT NULL CHECK(resolution_bits=12),
 gas_usable_min_mv numeric NOT NULL, gas_usable_max_mv numeric NOT NULL,
 dust_usable_min_mv numeric NOT NULL, dust_usable_max_mv numeric NOT NULL,
 definition jsonb NOT NULL, commissioning_reference text,
 created_at timestamptz NOT NULL DEFAULT now(), created_by uuid REFERENCES users,
 UNIQUE(device_id,profile_id),
 CHECK(gas_usable_min_mv>=0 AND gas_usable_min_mv<gas_usable_max_mv AND gas_usable_max_mv<=3100),
 CHECK(dust_usable_min_mv>=0 AND dust_usable_min_mv<dust_usable_max_mv AND dust_usable_max_mv<=3100),
 CHECK(kind='SIMULATOR' OR length(commissioning_reference)>0)
);
CREATE TABLE device_boot_profiles (
 device_id varchar(64) NOT NULL, boot_id varchar(32) NOT NULL CHECK(boot_id ~ '^[a-f0-9]{8,32}$'),
 profile_id varchar(64) NOT NULL, location_snapshot varchar(128) NOT NULL,
 established_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(device_id,boot_id),
 FOREIGN KEY(device_id,profile_id) REFERENCES acquisition_profiles(device_id,profile_id)
);
CREATE TABLE calibrations (
 calibration_id varchar(64) PRIMARY KEY, device_id varchar(64) NOT NULL, profile_id varchar(64) NOT NULL,
 sensor text NOT NULL CHECK(sensor IN ('mq135','dust')), baseline_adc_mv numeric,
 method text NOT NULL, definition jsonb NOT NULL, evidence_reference text NOT NULL CHECK(length(evidence_reference)>0),
 created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(device_id,profile_id,calibration_id),
 FOREIGN KEY(device_id,profile_id) REFERENCES acquisition_profiles(device_id,profile_id),
 CHECK(sensor<>'mq135' OR (baseline_adc_mv>0 AND baseline_adc_mv<'Infinity'::numeric))
);
CREATE TABLE measurements (
 id uuid PRIMARY KEY, device_id varchar(64) NOT NULL REFERENCES devices, message_id varchar(114) NOT NULL,
 boot_id varchar(32) NOT NULL, sequence bigint NOT NULL CHECK(sequence BETWEEN 0 AND 9007199254740991),
 schema_version integer NOT NULL CHECK(schema_version=1), measured_at timestamptz, received_at timestamptz NOT NULL,
 uptime_ms bigint NOT NULL CHECK(uptime_ms BETWEEN 0 AND 9007199254740991), report_interval_sec integer NOT NULL CHECK(report_interval_sec BETWEEN 1 AND 3600),
 time_quality text NOT NULL CHECK(time_quality IN ('SYNCED','UNSYNCED')),
 temperature_c numeric, humidity_pct numeric, gas_relative numeric, dust_estimate_ug_m3 numeric,
 gas_adc_counts integer CHECK(gas_adc_counts BETWEEN 0 AND 4095), dust_adc_counts integer CHECK(dust_adc_counts BETWEEN 0 AND 4095),
 gas_adc_mv numeric CHECK(gas_adc_mv BETWEEN 0 AND 10000), dust_adc_mv numeric CHECK(dust_adc_mv BETWEEN 0 AND 10000),
 sht30_quality text NOT NULL, mq135_quality text NOT NULL, dust_quality text NOT NULL,
 profile_id varchar(64) NOT NULL, gas_calibration_id varchar(64), dust_calibration_id varchar(64), location_snapshot varchar(128) NOT NULL,
 original_payload bytea NOT NULL CHECK(octet_length(original_payload)<=4096), canonical_payload text NOT NULL, canonical_payload_hash bytea NOT NULL CHECK(octet_length(canonical_payload_hash)=32),
 UNIQUE(device_id,message_id), UNIQUE(device_id,boot_id,sequence), UNIQUE(device_id,id),
 FOREIGN KEY(device_id,boot_id) REFERENCES device_boot_profiles(device_id,boot_id),
 FOREIGN KEY(device_id,profile_id) REFERENCES acquisition_profiles(device_id,profile_id),
 FOREIGN KEY(device_id,profile_id,gas_calibration_id) REFERENCES calibrations(device_id,profile_id,calibration_id),
 FOREIGN KEY(device_id,profile_id,dust_calibration_id) REFERENCES calibrations(device_id,profile_id,calibration_id),
 CHECK((time_quality='SYNCED')=(measured_at IS NOT NULL)),
 CHECK(sht30_quality IN ('VALID','WARMING_UP','UNCALIBRATED','INVALID','UNAVAILABLE')),
 CHECK(mq135_quality IN ('VALID','WARMING_UP','UNCALIBRATED','INVALID','UNAVAILABLE')),
 CHECK(dust_quality IN ('VALID','WARMING_UP','UNCALIBRATED','INVALID','UNAVAILABLE')),
 CHECK((sht30_quality='VALID' AND temperature_c BETWEEN -40 AND 125 AND humidity_pct BETWEEN 0 AND 100 AND temperature_c IS NOT NULL AND humidity_pct IS NOT NULL) OR (sht30_quality<>'VALID' AND temperature_c IS NULL AND humidity_pct IS NULL)),
 CHECK((mq135_quality='VALID' AND gas_relative>=0 AND gas_relative<'Infinity'::numeric AND gas_relative IS NOT NULL AND gas_calibration_id IS NOT NULL AND gas_adc_counts IS NOT NULL AND gas_adc_mv IS NOT NULL) OR (mq135_quality<>'VALID' AND gas_relative IS NULL)),
 CHECK(dust_quality<>'VALID' OR (dust_adc_counts IS NOT NULL AND dust_adc_mv IS NOT NULL)),
 CHECK(dust_estimate_ug_m3 IS NULL OR (dust_quality='VALID' AND dust_calibration_id IS NOT NULL AND dust_estimate_ug_m3>=0 AND dust_estimate_ug_m3<'Infinity'::numeric))
);
CREATE INDEX measurements_history ON measurements(device_id,measured_at,message_id) WHERE measured_at IS NOT NULL;
CREATE TABLE device_state (
 device_id varchar(64) PRIMARY KEY REFERENCES devices, boot_id varchar(32), reported_online boolean,
 state_payload jsonb, state_received_at timestamptz, last_telemetry_received_at timestamptz,
 latest_measurement_id uuid, updated_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY(device_id,latest_measurement_id) REFERENCES measurements(device_id,id)
);
CREATE TABLE device_metadata_history (
 id uuid PRIMARY KEY, device_id varchar(64) NOT NULL REFERENCES devices,
 original_payload bytea NOT NULL CHECK(octet_length(original_payload)<=4096), metadata jsonb NOT NULL, received_at timestamptz NOT NULL
);
CREATE INDEX metadata_reception ON device_metadata_history(device_id,received_at);
CREATE TABLE ingestion_rejections (
 id uuid PRIMARY KEY, device_id varchar(64) REFERENCES devices, topic varchar(256), message_id varchar(114),
 code varchar(64) NOT NULL, detail varchar(512) NOT NULL, payload_sample bytea CHECK(octet_length(payload_sample)<=4096),
 payload_hash bytea NOT NULL CHECK(octet_length(payload_hash)=32), occurrence_count bigint NOT NULL DEFAULT 1 CHECK(occurrence_count>0),
 received_at timestamptz NOT NULL, last_received_at timestamptz NOT NULL, receipt_payload jsonb,
 UNIQUE(topic,payload_hash,code), CHECK(last_received_at>=received_at)
);
CREATE TABLE thresholds (
 id varchar(64) PRIMARY KEY, device_id varchar(64) NOT NULL REFERENCES devices,
 parameter text NOT NULL CHECK(parameter IN ('temperatureC','humidityPct','gasRelative','dustAdcMv','dustEstimateUgM3')),
 unit text NOT NULL, calibration_id varchar(64) REFERENCES calibrations, profile_id varchar(64) REFERENCES acquisition_profiles,
 revision bigint NOT NULL DEFAULT 1 CHECK(revision>0), direction text NOT NULL CHECK(direction IN ('UPPER','LOWER','RANGE')),
 warning_low numeric, warning_high numeric, alert_low numeric, alert_high numeric, recovery_low numeric, recovery_high numeric,
 enabled boolean NOT NULL, updated_by uuid REFERENCES users, updated_at timestamptz NOT NULL DEFAULT now(), UNIQUE(device_id,parameter),
 CHECK((parameter='temperatureC' AND unit='degC') OR (parameter='humidityPct' AND unit='%RH') OR (parameter='gasRelative' AND unit='ratio' AND calibration_id IS NOT NULL) OR (parameter='dustAdcMv' AND unit='mV' AND profile_id IS NOT NULL) OR (parameter='dustEstimateUgM3' AND unit='ug/m3' AND calibration_id IS NOT NULL))
);
CREATE TABLE admin_sessions (
 session_id_hash bytea PRIMARY KEY CHECK(octet_length(session_id_hash)=32), user_id uuid NOT NULL REFERENCES users,
 csrf_token_hash bytea NOT NULL CHECK(octet_length(csrf_token_hash)=32), created_at timestamptz NOT NULL,
 expires_at timestamptz NOT NULL, last_seen_at timestamptz, invalidated_at timestamptz, CHECK(expires_at>created_at)
);
CREATE TABLE history_snapshots (
 id uuid PRIMARY KEY, device_id varchar(64) NOT NULL REFERENCES devices, from_at timestamptz NOT NULL,
 to_at timestamptz NOT NULL, expires_at timestamptz NOT NULL
);
CREATE TABLE history_snapshot_members (
 snapshot_id uuid REFERENCES history_snapshots ON DELETE CASCADE, ordinal bigint NOT NULL,
 measurement_id uuid NOT NULL REFERENCES measurements, PRIMARY KEY(snapshot_id,ordinal)
);
CREATE FUNCTION immutable_evidence() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'Evidence is append-only'; END $$;
CREATE TRIGGER immutable_measurement BEFORE UPDATE OR DELETE ON measurements FOR EACH ROW EXECUTE FUNCTION immutable_evidence();
CREATE TRIGGER immutable_profile BEFORE UPDATE OR DELETE ON acquisition_profiles FOR EACH ROW EXECUTE FUNCTION immutable_evidence();
CREATE TRIGGER immutable_calibration BEFORE UPDATE OR DELETE ON calibrations FOR EACH ROW EXECUTE FUNCTION immutable_evidence();
CREATE TRIGGER immutable_boot BEFORE UPDATE OR DELETE ON device_boot_profiles FOR EACH ROW EXECUTE FUNCTION immutable_evidence();


-- Source: V2__threshold_integrity.sql
ALTER TABLE thresholds ADD CONSTRAINT threshold_boundary_order CHECK (
 (direction='UPPER' AND warning_low IS NULL AND alert_low IS NULL AND recovery_low IS NULL AND warning_high IS NOT NULL AND alert_high IS NOT NULL AND recovery_high IS NOT NULL AND recovery_high<warning_high AND warning_high<alert_high AND alert_high<'Infinity'::numeric AND recovery_high>'-Infinity'::numeric)
 OR (direction='LOWER' AND warning_high IS NULL AND alert_high IS NULL AND recovery_high IS NULL AND warning_low IS NOT NULL AND alert_low IS NOT NULL AND recovery_low IS NOT NULL AND alert_low<warning_low AND warning_low<recovery_low AND recovery_low<'Infinity'::numeric AND alert_low>'-Infinity'::numeric)
 OR (direction='RANGE' AND alert_low IS NOT NULL AND warning_low IS NOT NULL AND recovery_low IS NOT NULL AND recovery_high IS NOT NULL AND warning_high IS NOT NULL AND alert_high IS NOT NULL AND alert_low<warning_low AND warning_low<recovery_low AND recovery_low<recovery_high AND recovery_high<warning_high AND warning_high<alert_high AND alert_high<'Infinity'::numeric AND alert_low>'-Infinity'::numeric)
);
CREATE FUNCTION check_threshold_binding() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.parameter IN ('gasRelative','dustEstimateUgM3') AND NOT EXISTS (
  SELECT 1 FROM calibrations WHERE calibration_id=NEW.calibration_id AND device_id=NEW.device_id
  AND sensor=CASE NEW.parameter WHEN 'gasRelative' THEN 'mq135' ELSE 'dust' END
 ) THEN RAISE EXCEPTION 'Invalid threshold calibration binding'; END IF;
 IF NEW.parameter='dustAdcMv' AND NOT EXISTS (
  SELECT 1 FROM acquisition_profiles WHERE profile_id=NEW.profile_id AND device_id=NEW.device_id
 ) THEN RAISE EXCEPTION 'Invalid threshold profile binding'; END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER threshold_binding BEFORE INSERT OR UPDATE ON thresholds FOR EACH ROW EXECUTE FUNCTION check_threshold_binding();


-- Source: V3__boot_profile_integrity.sql
ALTER TABLE device_boot_profiles ADD UNIQUE(device_id,boot_id,profile_id);
ALTER TABLE measurements ADD FOREIGN KEY(device_id,boot_id,profile_id) REFERENCES device_boot_profiles(device_id,boot_id,profile_id);

