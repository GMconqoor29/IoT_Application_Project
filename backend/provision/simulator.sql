-- EXPLICIT TEST DATA ONLY. Never install as a production migration.
INSERT INTO devices(device_id,location,firmware_version) VALUES('AIRSENSE-001','SIMULATOR','simulator-1.0.0');
INSERT INTO acquisition_profiles(profile_id,device_id,kind,chip_model,mode,resolution_bits,gas_usable_min_mv,gas_usable_max_mv,dust_usable_min_mv,dust_usable_max_mv,definition)
VALUES('simulator-s3-001','AIRSENSE-001','SIMULATOR','ESP32-S3','ONESHOT',12,150,3000,150,3000,
'{"profileId":"simulator-s3-001","kind":"SIMULATOR","chipModel":"ESP32-S3","mode":"ONESHOT","resolutionBits":12,"gasUsableMinMv":150,"gasUsableMaxMv":3000,"dustUsableMinMv":150,"dustUsableMaxMv":3000}');
INSERT INTO device_boot_profiles(device_id,boot_id,profile_id,location_snapshot) VALUES('AIRSENSE-001','b7f3a901','simulator-s3-001','SIMULATOR');
INSERT INTO calibrations(calibration_id,device_id,profile_id,sensor,baseline_adc_mv,method,definition,evidence_reference)
VALUES('gas-baseline-001','AIRSENSE-001','simulator-s3-001','mq135',1000,'synthetic-voltage-ratio','{}','Synthetic software fixture; not hardware calibration');
INSERT INTO calibrations(calibration_id,device_id,profile_id,sensor,method,definition,evidence_reference)
VALUES('dust-fit-001','AIRSENSE-001','simulator-s3-001','dust','synthetic-linear',
'{"equation":"linear-adc-mv","slope":0.1,"intercept":-63.5,"minMv":635,"maxMv":3000,"maxUgM3":236.5}',
'Synthetic software fixture; not a measured conversion');
INSERT INTO thresholds(id,device_id,parameter,unit,calibration_id,profile_id,direction,warning_high,alert_high,recovery_high,enabled) VALUES
('gas-rule-001','AIRSENSE-001','gasRelative','ratio','gas-baseline-001',NULL,'UPPER',1.2,1.3,1.15,true),
('temperature-rule-001','AIRSENSE-001','temperatureC','degC',NULL,NULL,'UPPER',30,35,29,true),
('humidity-rule-001','AIRSENSE-001','humidityPct','%RH',NULL,NULL,'UPPER',70,80,65,true),
('dust-rule-001','AIRSENSE-001','dustAdcMv','mV',NULL,'simulator-s3-001','UPPER',1500,2000,1400,true);
