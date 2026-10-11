package com.bit.portal.lab.store;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("portal-dev")
@MapperScan("com.bit.portal.lab.store")
public class LabRecordingConfiguration { }
