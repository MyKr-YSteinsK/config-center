package com.example.configcenter;

import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

@ActiveProfiles("mysql")
@DirtiesContext
class MysqlNameIdentityIT extends WriteNameIdentityScenarios {}
