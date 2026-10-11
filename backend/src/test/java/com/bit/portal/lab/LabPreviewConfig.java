package com.bit.portal.lab;

import org.springframework.context.annotation.*;

@Configuration
@Profile("preview")
public class LabPreviewConfig {
    @Bean(destroyMethod="close")
    MockBackgroundApi mockBackgroundApi() throws Exception { return new MockBackgroundApi(18081); }
}
