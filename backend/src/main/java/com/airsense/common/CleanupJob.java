package com.airsense.common;

import com.airsense.auth.AuthRepository;
import com.airsense.monitoring.MonitoringRepository;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class CleanupJob {
  private final AuthRepository auth;
  private final MonitoringRepository monitoring;

  public CleanupJob(AuthRepository auth, MonitoringRepository monitoring) {
    this.auth = auth;
    this.monitoring = monitoring;
  }

  @Scheduled(fixedDelay = 60000)
  public void cleanup() {
    monitoring.cleanup();
    auth.cleanup();
  }
}
