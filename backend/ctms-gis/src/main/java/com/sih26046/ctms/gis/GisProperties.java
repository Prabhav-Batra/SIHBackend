package com.sih26046.ctms.gis;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * §11.4: k = 5 is configurable without a code change, in case a deployment's mix of trial
 * sizes needs a different threshold.
 */
@ConfigurationProperties(prefix = "ctms.gis")
public record GisProperties(@DefaultValue("5") int kAnonymityThreshold) {}
