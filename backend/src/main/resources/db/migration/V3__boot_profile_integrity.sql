ALTER TABLE device_boot_profiles ADD UNIQUE(device_id,boot_id,profile_id);
ALTER TABLE measurements ADD FOREIGN KEY(device_id,boot_id,profile_id) REFERENCES device_boot_profiles(device_id,boot_id,profile_id);
