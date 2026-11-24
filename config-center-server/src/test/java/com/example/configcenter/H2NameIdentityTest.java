package com.example.configcenter;

import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("test")
@DirtiesContext
class H2NameIdentityTest extends WriteNameIdentityScenarios {}
