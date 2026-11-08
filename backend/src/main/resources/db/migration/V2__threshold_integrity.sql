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
